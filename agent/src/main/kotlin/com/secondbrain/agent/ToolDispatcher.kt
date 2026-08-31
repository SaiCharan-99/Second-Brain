package com.secondbrain.agent

import com.secondbrain.model.ToolClass
import com.secondbrain.model.ToolEvent
import com.secondbrain.ports.LlmBlock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/**
 * Validates and executes one tool call.
 *
 * EC-A3 (unknown tool), EC-A4 (malformed input), and the case the artifacts do
 * not cover: a handler that throws (H13).
 *
 * Every path returns a `tool_result`. That is not politeness — a `tool_use`
 * block without a matching `tool_result` in the next request is a 400 from the
 * API, so dropping a result on the floor turns a recoverable tool error into a
 * dead conversation.
 */
class ToolDispatcher(
    private val registry: ToolRegistry,
) {

    private val log = LoggerFactory.getLogger(ToolDispatcher::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    /** Outcome of dispatching one `tool_use` block. */
    data class Dispatched(
        val result: LlmBlock.ToolResult,
        val event: ToolEvent,
        /** True when the model needs to correct itself: unknown tool or bad input. */
        val needsSelfCorrection: Boolean,
        /** Set when a GATED tool was requested. Step 5 suspends on this. */
        val gatedToolName: String? = null,
    )

    suspend fun dispatch(call: LlmBlock.ToolUse): Dispatched {
        val started = System.currentTimeMillis()
        val spec = registry.get(call.name)

        // ── EC-A3: unknown tool ──────────────────────────────────────────────
        if (spec == null) {
            log.warn("Model called unknown tool '{}'", call.name)
            return error(
                call, started,
                code = "unknown_tool",
                message = "There is no tool named '${call.name}'.",
                extra = buildJsonObject {
                    put("available_tools", JsonArray(registry.names.map { JsonPrimitive(it) }))
                },
                needsSelfCorrection = true,
            )
        }

        // ── EC-A4: validate against the schema before dispatch ───────────────
        val validation = validate(call.inputJson, spec.inputSchemaJson)
        if (validation.isNotEmpty()) {
            log.warn("Model called '{}' with invalid input: {}", call.name, validation)
            return error(
                call, started,
                code = "invalid_input",
                message = "Input does not match the schema for '${call.name}'.",
                extra = buildJsonObject {
                    put("field_errors", JsonArray(validation.map { JsonPrimitive(it) }))
                },
                needsSelfCorrection = true,
            )
        }

        // ── R2: a gated tool never executes from a model call ────────────────
        if (spec.toolClass == ToolClass.GATED) {
            // Step 3 registers no gated tools, so this is unreachable today. It is
            // here rather than at Step 5 because the invariant belongs with the
            // dispatcher: there must be no code path where a GATED handler runs
            // because the model asked. ConfirmationGate resolves these in Step 5.
            log.info("Gated tool '{}' requested; suspending rather than executing (R2).", call.name)
            return Dispatched(
                result = LlmBlock.ToolResult(
                    toolUseId = call.id,
                    content = buildJsonObject {
                        put("status", "awaiting_user_confirmation")
                        put("tool", call.name)
                    }.toString(),
                    isError = false,
                ),
                event = ToolEvent(
                    name = call.name,
                    toolClass = ToolClass.GATED,
                    inputJson = call.inputJson,
                    resultJson = "{\"status\":\"awaiting_user_confirmation\"}",
                    isError = false,
                    durationMs = System.currentTimeMillis() - started,
                ),
                needsSelfCorrection = false,
                gatedToolName = call.name,
            )
        }

        // ── execute ──────────────────────────────────────────────────────────
        return try {
            val outcome = spec.handler(call.inputJson)
            Dispatched(
                result = LlmBlock.ToolResult(call.id, outcome.resultJson, outcome.isError),
                event = ToolEvent(
                    name = call.name,
                    toolClass = spec.toolClass,
                    inputJson = call.inputJson,
                    resultJson = outcome.resultJson,
                    isError = outcome.isError,
                    durationMs = System.currentTimeMillis() - started,
                    notePath = outcome.notePath,
                ),
                needsSelfCorrection = false,
            )
        } catch (e: Exception) {
            // H13: a throwing handler must not kill the loop, and its result must
            // still come back or the next request is malformed.
            log.error("Tool '{}' threw", call.name, e)
            error(
                call, started,
                code = "tool_failed",
                message = "${e::class.simpleName}: ${e.message}",
                extra = JsonObject(emptyMap()),
                needsSelfCorrection = false,
            )
        }
    }

    private fun error(
        call: LlmBlock.ToolUse,
        started: Long,
        code: String,
        message: String,
        extra: JsonObject,
        needsSelfCorrection: Boolean,
    ): Dispatched {
        val body = buildJsonObject {
            put("error", code)
            put("message", message)
            extra.forEach { (k, v) -> put(k, v) }
        }.toString()

        return Dispatched(
            result = LlmBlock.ToolResult(call.id, body, isError = true),
            event = ToolEvent(
                name = call.name,
                toolClass = registry.get(call.name)?.toolClass ?: ToolClass.AUTONOMOUS,
                inputJson = call.inputJson,
                resultJson = body,
                isError = true,
                durationMs = System.currentTimeMillis() - started,
            ),
            needsSelfCorrection = needsSelfCorrection,
        )
    }

    /**
     * Validates model input against the tool's JSON Schema.
     *
     * Deliberately a small validator rather than a full JSON Schema engine: these
     * schemas are flat objects of strings, integers, booleans and string arrays.
     * EC-A4 asks for "field-level errors as `tool_result`", and a real engine's
     * error vocabulary is worse for that than these messages — the model has to
     * act on what comes back.
     *
     * Returns one message per problem; empty means valid.
     */
    internal fun validate(inputJson: String, schemaJson: String): List<String> {
        val errors = mutableListOf<String>()

        val input = try {
            json.parseToJsonElement(inputJson.ifBlank { "{}" })
        } catch (e: Exception) {
            return listOf("input is not valid JSON: ${e.message}")
        }
        if (input !is JsonObject) return listOf("input must be a JSON object, got ${input::class.simpleName}")

        val schema = try {
            json.parseToJsonElement(schemaJson).jsonObject
        } catch (e: Exception) {
            return listOf("tool schema is not valid JSON: ${e.message}")
        }

        val properties = schema["properties"]?.jsonObject ?: JsonObject(emptyMap())
        val required = schema["required"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull() } ?: emptyList()

        required.forEach { field ->
            val value = input[field]
            if (value == null) {
                errors += "missing required field '$field'"
            } else if (value is JsonPrimitive && !value.isString && value.content == "null") {
                errors += "required field '$field' is null"
            }
        }

        input.forEach { (field, value) ->
            val propSchema = properties[field]?.jsonObject
            if (propSchema == null) {
                // Unknown fields are reported rather than ignored: a model that
                // invents a field usually misread the schema, and silently
                // dropping it produces a note missing whatever it meant to set.
                errors += "unknown field '$field' (expected one of: ${properties.keys.joinToString(", ")})"
                return@forEach
            }
            val expected = propSchema["type"]?.jsonPrimitive?.contentOrNull() ?: return@forEach
            typeError(field, expected, value)?.let { errors += it }
        }

        return errors
    }

    private fun typeError(field: String, expected: String, value: JsonElement): String? = when (expected) {
        "string" -> if (value is JsonPrimitive && value.isString) null
        else "field '$field' must be a string"

        "integer" -> if (value is JsonPrimitive && !value.isString && value.content.toLongOrNull() != null) null
        else "field '$field' must be an integer"

        "number" -> if (value is JsonPrimitive && !value.isString && value.content.toDoubleOrNull() != null) null
        else "field '$field' must be a number"

        "boolean" -> if (value is JsonPrimitive && !value.isString && value.content in setOf("true", "false")) null
        else "field '$field' must be a boolean"

        "array" -> if (value is JsonArray) null else "field '$field' must be an array"

        "object" -> if (value is JsonObject) null else "field '$field' must be an object"

        else -> null
    }

    private fun JsonPrimitive.contentOrNull(): String? = if (isString || content != "null") content else null
}
