package com.secondbrain.agent

import com.secondbrain.model.EmailProposal
import com.secondbrain.ports.LlmBlock
import com.secondbrain.ports.MailPort
import com.secondbrain.ports.SendOutcome
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * `email_draft`/`request_typed_input` end to end through the real
 * [ToolDispatcher] + [ToolRegistry] + [ConfirmationGate] — not just the
 * handler in isolation, since EC-A4's schema validation happens one layer up.
 */
class EmailToolsTest {

    private val databases = TestDatabases()

    @org.junit.jupiter.api.AfterEach
    fun closeTrackedDatabases() = databases.closeAll()


    private class FakeMail(private val outcome: SendOutcome) : MailPort {
        var sent: EmailProposal? = null
        var calls = 0
        override suspend fun send(proposal: EmailProposal, idempotencyKey: String): SendOutcome {
            calls++
            sent = proposal
            return outcome
        }
    }

    private fun gate(dir: Path) = ConfirmationGate(ActionLedger(databases.open(dir)))

    private fun dispatcherFor(mail: MailPort, gate: ConfirmationGate): ToolDispatcher {
        val tools = EmailTools(mail, gate) { _, _ -> VaultTools.AskResult.NoAnswer("not exercised here") }
        val registry = tools.register(ToolRegistry.builder()).build()
        return ToolDispatcher(registry)
    }

    @Test
    @DisplayName("EC-A4: missing required fields are rejected before the handler ever runs")
    fun `schema validation catches missing fields`(@TempDir dir: Path) = runTest {
        val g = gate(dir)
        val dispatcher = dispatcherFor(FakeMail(SendOutcome.Sent("x")), g)
        val result = dispatcher.dispatch(LlmBlock.ToolUse("tu_1", "email_draft", """{"to":"a@b.com"}"""))
        assertTrue(result.needsSelfCorrection)
        assertTrue(result.result.isError)
    }

    @Test
    @DisplayName("EC-E1: an invalid recipient is rejected before any gate opens")
    fun `invalid recipient never opens a gate`(@TempDir dir: Path) = runTest {
        val g = gate(dir)
        val dispatcher = dispatcherFor(FakeMail(SendOutcome.Sent("x")), g)
        val result = dispatcher.dispatch(
            LlmBlock.ToolUse(
                "tu_1", "email_draft",
                """{"to":"not-an-address","subject":"s","body":"b","speech_summary":"sum"}""",
            )
        )
        assertTrue(result.result.isError)
        assertTrue(result.result.content.contains("invalid_address"))
        assertNull(g.state.value)
    }

    @Test
    @DisplayName("EC-E1/E5: invalid cc addresses are also rejected up front")
    fun `invalid cc never opens a gate`(@TempDir dir: Path) = runTest {
        val g = gate(dir)
        val dispatcher = dispatcherFor(FakeMail(SendOutcome.Sent("x")), g)
        val result = dispatcher.dispatch(
            LlmBlock.ToolUse(
                "tu_1", "email_draft",
                """{"to":"a@b.com","cc":["not-an-address"],"subject":"s","body":"b","speech_summary":"sum"}""",
            )
        )
        assertTrue(result.result.isError)
        assertNull(g.state.value)
    }

    @Test
    fun `happy path- gate opens, resolves, and the fake adapter sees the call exactly once`(@TempDir dir: Path) = runTest {
        val g = gate(dir)
        val mail = FakeMail(SendOutcome.Sent("msg-1"))
        val dispatcher = dispatcherFor(mail, g)

        val dispatched = async(start = CoroutineStart.UNDISPATCHED) {
            dispatcher.dispatch(
                LlmBlock.ToolUse(
                    "tu_1", "email_draft",
                    """{"to":"a@b.com","subject":"s","body":"b","speech_summary":"sum"}""",
                )
            )
        }

        val proposalId = g.state.value!!.proposalId
        g.confirmContent(proposalId)
        g.confirmVerbatim(proposalId, "to")
        g.confirmExecute(proposalId)

        val result = dispatched.await()
        assertTrue(result.result.content.contains("\"sent\":true"))
        assertEquals(1, mail.calls)
        assertEquals("a@b.com", mail.sent?.to)
        assertNull(g.state.value)
    }

    @Test
    @DisplayName("request_typed_input validates email shape before returning a value")
    fun `typed input validates email shape`(@TempDir dir: Path) = runTest {
        val g = gate(dir)
        val tools = EmailTools(FakeMail(SendOutcome.Sent("x")), g) { _, _ -> VaultTools.AskResult.Answered("not-an-address") }
        val registry = tools.register(ToolRegistry.builder()).build()
        val dispatcher = ToolDispatcher(registry)

        val result = dispatcher.dispatch(
            LlmBlock.ToolUse("tu_1", "request_typed_input", """{"prompt":"what's the address?","kind":"email"}""")
        )
        assertTrue(result.result.isError)
        assertTrue(result.result.content.contains("invalid_address"))
    }

    @Test
    fun `request_typed_input returns the typed value for a non-email kind unvalidated`(@TempDir dir: Path) = runTest {
        val g = gate(dir)
        val tools = EmailTools(FakeMail(SendOutcome.Sent("x")), g) { _, _ -> VaultTools.AskResult.Answered("2") }
        val registry = tools.register(ToolRegistry.builder()).build()
        val dispatcher = ToolDispatcher(registry)

        val result = dispatcher.dispatch(
            LlmBlock.ToolUse("tu_1", "request_typed_input", """{"prompt":"how many?"}""")
        )
        assertTrue(result.result.content.contains("\"value\":\"2\""))
    }
}
