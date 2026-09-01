package com.secondbrain.app.voice

import com.secondbrain.ports.LlmBlock
import com.secondbrain.ports.LlmMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** D-047's real summariser call is built on this digest, never on the raw blocks. */
class ConversationDigestTest {

    private fun user(text: String) = LlmMessage(LlmMessage.Role.USER, listOf(LlmBlock.Text(text)))
    private fun assistant(text: String) = LlmMessage(LlmMessage.Role.ASSISTANT, listOf(LlmBlock.Text(text)))

    @Test
    fun `user and assistant text is labelled and kept`() {
        val digest = ConversationDigest.render(listOf(user("Note this down."), assistant("Saved to Inbox.")))
        assertEquals("User: Note this down.\nAssistant: Saved to Inbox.", digest)
    }

    @Test
    fun `a tool_use block becomes a short bracketed line`() {
        val messages = listOf(
            LlmMessage(
                LlmMessage.Role.ASSISTANT,
                listOf(LlmBlock.ToolUse(id = "t1", name = "vault_write_note", inputJson = "{}")),
            ),
        )
        val digest = ConversationDigest.render(messages)
        assertEquals("[used tool: vault_write_note]", digest)
    }

    @Test
    fun `H9 a pure tool_result message contributes nothing under the User label`() {
        val messages = listOf(
            LlmMessage(LlmMessage.Role.USER, listOf(LlmBlock.ToolResult(toolUseId = "t1", content = "{\"path\":\"Inbox/x.md\"}"))),
        )
        val digest = ConversationDigest.render(messages)
        assertTrue(digest.isBlank())
        assertFalse(digest.contains("path"))
    }

    @Test
    fun `thinking blocks are dropped entirely`() {
        val messages = listOf(
            LlmMessage(LlmMessage.Role.ASSISTANT, listOf(LlmBlock.Thinking(thinking = "considering folders..."))),
        )
        assertTrue(ConversationDigest.render(messages).isBlank())
    }

    @Test
    fun `blank text blocks are skipped, not rendered as an empty labelled line`() {
        val messages = listOf(user("  "), assistant("Real reply."))
        assertEquals("Assistant: Real reply.", ConversationDigest.render(messages))
    }

    @Test
    fun `a mixed turn renders in order`() {
        val messages = listOf(
            user("Email Udit about the demo."),
            LlmMessage(
                LlmMessage.Role.ASSISTANT,
                listOf(
                    LlmBlock.Thinking(thinking = "no email tool yet"),
                    LlmBlock.ToolUse(id = "t1", name = "ask_user", inputJson = "{}"),
                ),
            ),
            LlmMessage(LlmMessage.Role.USER, listOf(LlmBlock.ToolResult(toolUseId = "t1", content = "{}"))),
            assistant("Got it, I'll hold off."),
        )
        assertEquals(
            "User: Email Udit about the demo.\n[used tool: ask_user]\nAssistant: Got it, I'll hold off.",
            ConversationDigest.render(messages),
        )
    }
}
