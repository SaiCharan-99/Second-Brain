package com.secondbrain.app.voice

import com.secondbrain.ports.LlmBlock
import com.secondbrain.ports.LlmMessage

/**
 * Flattens a run of [LlmMessage]s into plain text for the rolling-summary call
 * (D-047: "a real summariser is a Claude call").
 *
 * Replaying [dropped][render] verbatim into a fresh one-shot request is not
 * safe: a `tool_result` without the `tool_use` it answers, in the SAME
 * request, is a 400 from the API — [com.secondbrain.agent.AgentLoop]'s own
 * H9 finding — and the turns falling out of the context window are exactly
 * the kind of run that has no reason to land on a clean user/assistant pair.
 * So this renders text and tool names only; it never re-sends a raw block.
 */
object ConversationDigest {

    fun render(messages: List<LlmMessage>): String = buildString {
        messages.forEach { message ->
            message.blocks.forEach { block ->
                when (block) {
                    is LlmBlock.Text -> if (block.text.isNotBlank()) {
                        append(roleLabel(message.role)).append(": ").append(block.text.trim()).append('\n')
                    }
                    is LlmBlock.ToolUse -> append("[used tool: ").append(block.name).append("]\n")
                    // Thinking and tool_result add mechanics, not meaning, to a
                    // digest meant for placement/linking context — see
                    // SystemPrompt.summarizeInstruction.
                    is LlmBlock.Thinking, is LlmBlock.ToolResult -> Unit
                }
            }
        }
    }.trim()

    /**
     * A `USER`-role message is as often a batch of `tool_result`s as it is the
     * user's own words — both share the role because that is what the API
     * requires (§4). Since [render] already drops `ToolResult` blocks, a
     * pure-tool-result message simply contributes nothing under this label,
     * which is correct rather than misleading.
     */
    private fun roleLabel(role: LlmMessage.Role): String = when (role) {
        LlmMessage.Role.USER -> "User"
        LlmMessage.Role.ASSISTANT -> "Assistant"
        LlmMessage.Role.SYSTEM -> "Note"
    }
}
