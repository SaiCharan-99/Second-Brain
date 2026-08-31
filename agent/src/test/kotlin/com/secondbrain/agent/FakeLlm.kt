package com.secondbrain.agent

import com.secondbrain.model.TurnUsage
import com.secondbrain.ports.LlmBlock
import com.secondbrain.ports.LlmPort
import com.secondbrain.ports.LlmRequest
import com.secondbrain.ports.LlmResponse
import com.secondbrain.ports.LlmStop
import com.secondbrain.ports.LlmStreamEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Scripted [LlmPort]. CLAUDE.md's testing bar for `:agent` is "Fake `LlmPort`",
 * and this is it.
 *
 * Every branch of the loop — the iteration cap, parallel tool blocks, each stop
 * reason, self-correction, cancellation, and the cost arithmetic — is reachable
 * with zero API calls and zero dollars. That is the whole reason [LlmPort] exists
 * as a port rather than the loop talking to the SDK directly.
 *
 * Requests are captured so tests can assert on what was *sent*, which is how the
 * cache-breakpoint and tool-order properties get verified.
 */
class FakeLlm(
    private val script: MutableList<LlmResponse> = mutableListOf(),
) : LlmPort {

    override val modelId: String = "fake-model"

    val requests = mutableListOf<LlmRequest>()
    var prewarmCalls = 0
        private set

    /** Response used once the script runs out. Keeps a runaway loop terminating. */
    var fallback: LlmResponse = text("done")

    override suspend fun send(request: LlmRequest): LlmResponse {
        requests += request
        return if (script.isEmpty()) fallback else script.removeAt(0)
    }

    override fun stream(request: LlmRequest): Flow<LlmStreamEvent> = flow {
        val response = send(request)
        if (response.toolUses.isNotEmpty()) emit(LlmStreamEvent.ToolUseStarted)
        else if (response.text.isNotEmpty()) emit(LlmStreamEvent.TextDelta(response.text))
        emit(LlmStreamEvent.Completed(response))
    }

    override suspend fun prewarm(request: LlmRequest) {
        prewarmCalls++
        requests += request
    }

    fun enqueue(vararg responses: LlmResponse): FakeLlm {
        script += responses
        return this
    }

    /** Repeats [response] [times] over, for cap tests. */
    fun enqueueRepeated(times: Int, response: () -> LlmResponse): FakeLlm {
        repeat(times) { script += response() }
        return this
    }

    companion object {
        private var toolCounter = 0

        val USAGE = TurnUsage(inputTokens = 100, outputTokens = 50, cacheWriteTokens = 0, cacheReadTokens = 900)

        fun text(text: String, usage: TurnUsage = USAGE) = LlmResponse(
            stop = LlmStop.END_TURN,
            blocks = listOf(LlmBlock.Text(text)),
            usage = usage,
        )

        /** One tool call. */
        fun toolCall(name: String, inputJson: String, usage: TurnUsage = USAGE) = LlmResponse(
            stop = LlmStop.TOOL_USE,
            blocks = listOf(LlmBlock.ToolUse("tu_${toolCounter++}", name, inputJson)),
            usage = usage,
        )

        /**
         * Several tool calls in one assistant message.
         *
         * The case section 4's flowchart does not have: parallel tool use is on by
         * default and one response can carry many calls (H9).
         */
        fun parallelToolCalls(vararg calls: Pair<String, String>, usage: TurnUsage = USAGE) = LlmResponse(
            stop = LlmStop.TOOL_USE,
            blocks = calls.map { (name, input) -> LlmBlock.ToolUse("tu_${toolCounter++}", name, input) },
            usage = usage,
        )

        /** A tool call preceded by a thinking block, as Opus 5 actually replies. */
        fun thinkingThenToolCall(name: String, inputJson: String, usage: TurnUsage = USAGE) = LlmResponse(
            stop = LlmStop.TOOL_USE,
            blocks = listOf(
                LlmBlock.Thinking("", signature = "sig_${toolCounter}"),
                LlmBlock.ToolUse("tu_${toolCounter++}", name, inputJson),
            ),
            usage = usage,
        )

        fun refusal(category: String = "cyber", usage: TurnUsage = USAGE) = LlmResponse(
            stop = LlmStop.REFUSAL,
            // HTTP 200 with EMPTY content. Without a branch for this the machine
            // says nothing at all and looks broken.
            blocks = emptyList(),
            usage = usage,
            refusalCategory = category,
        )

        fun maxTokens(partialText: String = "", usage: TurnUsage = USAGE) = LlmResponse(
            stop = LlmStop.MAX_TOKENS,
            blocks = if (partialText.isEmpty()) emptyList() else listOf(LlmBlock.Text(partialText)),
            usage = usage,
        )

        fun apiFailed(error: String = "all 3 attempts failed") = LlmResponse(
            stop = LlmStop.API_FAILED,
            blocks = emptyList(),
            usage = TurnUsage.ZERO,
            error = error,
            attempts = 3,
        )

        /** `stop_reason: tool_use` with no tool block. Should not loop forever. */
        fun toolUseWithNoBlocks(usage: TurnUsage = USAGE) = LlmResponse(
            stop = LlmStop.TOOL_USE,
            blocks = listOf(LlmBlock.Text("I meant to call something")),
            usage = usage,
        )
    }
}
