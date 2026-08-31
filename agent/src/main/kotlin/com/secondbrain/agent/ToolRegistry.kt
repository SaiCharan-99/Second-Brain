package com.secondbrain.agent

import com.secondbrain.model.ToolClass
import com.secondbrain.ports.LlmToolSpec
import org.slf4j.LoggerFactory

/**
 * One registered tool.
 *
 * R2: the class is declared here, at construction, and there is no setter.
 * "There is no third class and no runtime promotion."
 */
class ToolSpec(
    val name: String,
    val description: String,
    /**
     * JSON Schema as text.
     *
     * Text rather than a builder so the bytes are fixed at registration. Schemas
     * render before system and messages in the cache key, so a schema assembled
     * per-request — from a map with non-deterministic iteration order, say —
     * would invalidate the cache on every call while looking identical (H6).
     */
    val inputSchemaJson: String,
    val toolClass: ToolClass,
    /**
     * Executes the tool. [inputJson] is the model's arguments, verbatim.
     *
     * Returns the `tool_result` content. Throwing is allowed and handled — the
     * dispatcher converts it into `is_error: true` rather than letting it kill
     * the loop (H13).
     */
    val handler: suspend (inputJson: String) -> ToolOutcome,
) {
    init {
        require(name.isNotBlank()) { "tool name must not be blank" }
        require(name.matches(Regex("^[a-zA-Z0-9_-]{1,64}$"))) {
            "tool name '$name' must match ^[a-zA-Z0-9_-]{1,64}$"
        }
        require(description.isNotBlank()) { "tool '$name' needs a description; the model reads it" }
    }

    fun toLlmToolSpec(): LlmToolSpec =
        LlmToolSpec(name, description, inputSchemaJson, toolClass)
}

/**
 * What a tool handler returns.
 *
 * [notePath] exists so the loop can surface a structured result to the UI: the
 * design board's assistant turn offers "Open note" and "Move" chips, which need
 * the path rather than the spoken sentence (H27).
 */
data class ToolOutcome(
    val resultJson: String,
    val isError: Boolean = false,
    val notePath: String? = null,
)

/**
 * Every tool the model can call, and which class each one is.
 *
 * The list is ordered and frozen after [build]. Order is part of the prompt cache
 * key, so a registry that iterated a `HashMap` would produce a different byte
 * sequence per JVM run and silently lose every cache hit.
 */
class ToolRegistry private constructor(
    private val tools: List<ToolSpec>,
) {

    private val byName: Map<String, ToolSpec> = tools.associateBy { it.name }

    val names: List<String> get() = tools.map { it.name }

    fun get(name: String): ToolSpec? = byName[name]

    /** Ordered, for the request. Never sorted or filtered per-request. */
    fun specs(): List<LlmToolSpec> = tools.map { it.toLlmToolSpec() }

    fun gatedNames(): List<String> = tools.filter { it.toolClass == ToolClass.GATED }.map { it.name }

    class Builder {
        private val log = LoggerFactory.getLogger(ToolRegistry::class.java)
        private val tools = mutableListOf<ToolSpec>()

        fun register(spec: ToolSpec): Builder {
            require(tools.none { it.name == spec.name }) { "tool '${spec.name}' is already registered" }
            tools += spec
            return this
        }

        fun autonomous(
            name: String,
            description: String,
            inputSchemaJson: String,
            handler: suspend (String) -> ToolOutcome,
        ): Builder = register(ToolSpec(name, description, inputSchemaJson, ToolClass.AUTONOMOUS, handler))

        fun gated(
            name: String,
            description: String,
            inputSchemaJson: String,
            handler: suspend (String) -> ToolOutcome,
        ): Builder = register(ToolSpec(name, description, inputSchemaJson, ToolClass.GATED, handler))

        fun build(): ToolRegistry {
            val registry = ToolRegistry(tools.toList())

            // R2's prohibitions are checkable, so they are checked. A tool named
            // like a direct-execution verb for an irreversible action must not
            // exist at all, "even behind a debug flag".
            val forbidden = listOf("email_send", "calendar_create", "place_order", "send_email", "order_place")
            registry.names.forEach { name ->
                require(name !in forbidden) {
                    "R2: '$name' is a direct-execution tool for a gated action. Sending is not " +
                        "something the model can express. Register a GATED proposal tool instead."
                }
            }

            // The classification table, logged at startup. Section 5 WF-4 asks for
            // this for bridged MCP tools; there is no reason our own tools should
            // be less visible.
            log.info("Tool registry ({} tools):", registry.names.size)
            tools.forEach { log.info("  {}  {}", it.toolClass.name.padEnd(10), it.name) }

            return registry
        }
    }

    companion object {
        fun builder(): Builder = Builder()
    }
}
