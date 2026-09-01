package com.secondbrain.ports

import com.secondbrain.model.ToolClass
import com.secondbrain.model.TurnUsage
import kotlinx.coroutines.flow.Flow

/**
 * The reasoning model, as everything above `:agent` sees it.
 *
 * This port is what keeps the Anthropic SDK's Jackson types, `Optional<T>` and
 * builders inside `ClaudeClient` — and it is what makes CLAUDE.md's "Fake
 * `LlmPort`" testing bar reachable: the loop, the iteration cap, parallel-block
 * handling, every `stop_reason` branch and the cost arithmetic are all testable
 * with zero API calls (D-044).
 *
 * Deliberately not a general chat abstraction. It expresses exactly what the
 * agent loop in ARCHITECTURE.md section 4 needs and nothing more.
 */
interface LlmPort {

    val modelId: String

    /**
     * One round-trip.
     *
     * Never throws for a transport failure. Retries internally per config and
     * returns [LlmResponse] with [LlmStop.API_FAILED] once attempts are
     * exhausted, so the caller always has something to say and the transcript is
     * never lost (EC-A7).
     */
    suspend fun send(request: LlmRequest): LlmResponse

    /**
     * Streaming variant, for first-sentence TTS latency (EC-T3).
     *
     * Text deltas are emitted as they arrive; the final [LlmResponse] arrives as
     * the last element. Note what this can and cannot buy: when the model calls a
     * tool there is no text to speak, so the latency win only lands on the final
     * text iteration of a turn (H26).
     */
    fun stream(request: LlmRequest): Flow<LlmStreamEvent>

    /**
     * Writes the cache for [request]'s prefix without generating anything.
     *
     * A `max_tokens: 0` request: the API runs prefill, writes the breakpoint and
     * returns immediately with empty content. Used at startup so the first real
     * capture does not pay the cold-write latency (H8).
     */
    suspend fun prewarm(request: LlmRequest)
}

/** A message in the conversation, in the shape the API wants. */
data class LlmMessage(
    val role: Role,
    val blocks: List<LlmBlock>,
) {
    enum class Role {
        USER,
        ASSISTANT,

        /**
         * Mid-conversation operator instruction.
         *
         * Supported on Opus 5 and appended to `messages` rather than editing the
         * top-level system prompt, which would invalidate the cached prefix. This
         * is the correct channel for EC-A1's cap notice and EC-G2's cost warning
         * (H7).
         */
        SYSTEM,
    }
}

/** One content block. Mirrors the API's block types, minus what we do not use. */
sealed interface LlmBlock {

    data class Text(val text: String) : LlmBlock

    /**
     * Model reasoning.
     *
     * Round-tripped unchanged when continuing on the same model. On Opus 5 the
     * default display is `omitted`, so [thinking] is usually empty — the block
     * still has to be replayed, because dropping it changes the request the model
     * sees (H21).
     */
    data class Thinking(val thinking: String, val signature: String? = null) : LlmBlock

    data class ToolUse(val id: String, val name: String, val inputJson: String) : LlmBlock

    data class ToolResult(
        val toolUseId: String,
        val content: String,
        val isError: Boolean = false,
    ) : LlmBlock

    /**
     * A photo attached to a user turn — grocery lists, handwritten notes,
     * product packaging (Step 8, WF-6).
     *
     * [mediaType] is a plain wire string (`"image/jpeg"`), not the SDK's own
     * enum, matching this port's independence from Jackson/the Anthropic SDK
     * (D-044): `ClaudeClient` is the one place allowed to know that enum
     * exists. `ImageIntake` (`:app`) always produces `"image/jpeg"`; the field
     * stays a string rather than a closed set so a future intake path (a
     * screenshot as PNG, say) does not need a port change to use one.
     */
    data class Image(val base64: String, val mediaType: String) : LlmBlock
}

/** A tool as the model sees it. [toolClass] is ours; the API never sees it. */
data class LlmToolSpec(
    val name: String,
    val description: String,
    /** JSON Schema. Must serialise byte-identically every request, or the cache misses. */
    val inputSchemaJson: String,
    val toolClass: ToolClass,
)

data class LlmRequest(
    val model: String,
    /**
     * Frozen. Nothing per-request may appear here — a date, a note count or the
     * vault tree in the system prompt invalidates the cache on every single call
     * (H6). Volatile context goes in the user turn instead.
     */
    val systemPrompt: String,
    val messages: List<LlmMessage>,
    /** Ordered. A varying tool order is a silent cache invalidator. */
    val tools: List<LlmToolSpec>,
    val maxTokens: Long,
    val thinkingEnabled: Boolean,
    val effort: String?,
    /** Adds an explicit breakpoint at the end of the system + tools prefix. */
    val cacheSystemPrefix: Boolean,
    /**
     * Message indices to place intermediate breakpoints after, to stay inside
     * the 20-block lookback window during a long tool-calling turn (H5).
     */
    val cacheMessageBreakpoints: Set<Int> = emptySet(),
)

/** Why the model stopped. Five outcomes, not the two in section 4's flowchart. */
enum class LlmStop { END_TURN, TOOL_USE, MAX_TOKENS, REFUSAL, API_FAILED }

data class LlmResponse(
    val stop: LlmStop,
    val blocks: List<LlmBlock>,
    val usage: TurnUsage,
    /** Populated only when [stop] is [LlmStop.REFUSAL]. Guard before reading. */
    val refusalCategory: String? = null,
    val error: String? = null,
    val attempts: Int = 1,
) {
    val text: String get() = blocks.filterIsInstance<LlmBlock.Text>().joinToString("") { it.text }
    val toolUses: List<LlmBlock.ToolUse> get() = blocks.filterIsInstance<LlmBlock.ToolUse>()
}

sealed interface LlmStreamEvent {
    /** A chunk of the final text reply. Feeds sentence-level TTS. */
    data class TextDelta(val text: String) : LlmStreamEvent

    /** The turn is calling tools, so nothing will be spoken this iteration. */
    data object ToolUseStarted : LlmStreamEvent

    data class Completed(val response: LlmResponse) : LlmStreamEvent
}
