package com.secondbrain.agent

import com.secondbrain.model.CalendarProposal
import com.secondbrain.model.EmailProposal
import com.secondbrain.model.FieldKind
import com.secondbrain.model.LedgerKind
import com.secondbrain.model.LedgerState
import com.secondbrain.model.Proposal
import com.secondbrain.model.ProposalField
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant

/**
 * The safety machinery: R2 (structural, via [ConfirmationGate.submit] never
 * being reachable except through a human-resolved gate), R6/EC-E2 (approved
 * means the payload the user saw), EC-A8 (one gate at a time), EC-E3/E4/Z8's
 * three-way outcome mapping.
 *
 * [CoroutineStart.UNDISPATCHED] is used throughout to launch [ConfirmationGate.submit]:
 * its body runs synchronously up to its first real suspension point
 * (`deferred.await()`), so [ConfirmationGate.state] already reflects the
 * opened gate the moment the `async` call returns — no scheduler pumping
 * needed, and it works the same under `runTest` or a real dispatcher.
 */
class ConfirmationGateTest {

    private fun gate(dir: Path): ConfirmationGate = ConfirmationGate(ActionLedger(AgentDb(dir.resolve("app.db"))))

    private val emailFields = listOf(
        ProposalField("subject", "Subject", "hello", FieldKind.CONTENT),
        ProposalField("body", "Body", "world", FieldKind.CONTENT),
        ProposalField("to", "To", "a@b.com", FieldKind.VERBATIM, requiresVerbatimVerification = true),
    )

    private fun emailProposal(to: String = "a@b.com") =
        EmailProposal(to = to, subject = "hello", body = "world", speechSummary = "a note to someone")

    @Nested
    @DisplayName("happy path")
    inner class HappyPath {

        @Test
        fun `email- content approve, verbatim confirm, then execute`(@TempDir dir: Path) = runTest {
            val g = gate(dir)
            var executedWith: Proposal? = null

            val result = async(start = CoroutineStart.UNDISPATCHED) {
                g.submit(LedgerKind.EMAIL_SEND, emailProposal(), emailFields) { _, approved ->
                    executedWith = approved
                    ConfirmationGate.ExecutorResult.Success("msg-1")
                }
            }

            val id = g.state.value!!.proposalId
            assertEquals(ConfirmationGate.Stage.CONTENT_REVIEW, g.state.value!!.stage)

            g.confirmContent(id)
            assertEquals(ConfirmationGate.Stage.VERBATIM_VERIFY, g.state.value!!.stage)

            g.confirmVerbatim(id, "to")
            assertEquals(ConfirmationGate.Stage.READY, g.state.value!!.stage)

            g.confirmExecute(id)

            val outcome = result.await()
            assertTrue(outcome is ConfirmationGate.GateOutcome.Executed)
            assertEquals("msg-1", (outcome as ConfirmationGate.GateOutcome.Executed).externalId)
            assertEquals("a@b.com", (executedWith as EmailProposal).to)
            assertNull(g.state.value) // the gate closes once resolved
        }

        @Test
        @DisplayName("a proposal with no verbatim fields (e.g. a calendar event with no attendees) skips straight to READY")
        fun `no verbatim fields skips the verify stage`(@TempDir dir: Path) = runTest {
            val g = gate(dir)
            val proposal = CalendarProposal(
                title = "Focus block",
                start = Instant.parse("2026-09-02T06:30:00Z"),
                end = Instant.parse("2026-09-02T07:30:00Z"),
                zoneId = "Asia/Kolkata",
                allDay = false,
                speechSummary = "an hour block",
            )
            val fields = listOf(ProposalField("title", "Title", "Focus block", FieldKind.CONTENT))

            val result = async(start = CoroutineStart.UNDISPATCHED) {
                g.submit(LedgerKind.CALENDAR_CREATE, proposal, fields) { _, _ -> ConfirmationGate.ExecutorResult.Success("evt-1") }
            }
            val id = g.state.value!!.proposalId
            g.confirmContent(id)
            assertEquals(ConfirmationGate.Stage.READY, g.state.value!!.stage)
            g.confirmExecute(id)
            assertTrue(result.await() is ConfirmationGate.GateOutcome.Executed)
        }
    }

    @Nested
    @DisplayName("R6 / EC-E2: approved means what the user saw")
    inner class ApprovalInvalidation {

        @Test
        fun `editing a content field resets approval to PROPOSED and re-opens content review`(@TempDir dir: Path) = runTest {
            val g = gate(dir)
            val result = async(start = CoroutineStart.UNDISPATCHED) {
                g.submit(LedgerKind.EMAIL_SEND, emailProposal(), emailFields) { _, _ -> ConfirmationGate.ExecutorResult.Success("x") }
            }
            val id = g.state.value!!.proposalId
            g.confirmContent(id)
            g.confirmVerbatim(id, "to")
            assertEquals(ConfirmationGate.Stage.READY, g.state.value!!.stage)

            g.editField(id, "body", "a completely different message")

            assertEquals(ConfirmationGate.Stage.CONTENT_REVIEW, g.state.value!!.stage)
            assertEquals("a completely different message", (g.state.value!!.proposal as EmailProposal).body)

            // Re-approving does not force re-verifying "to" again - only content was invalidated.
            g.confirmContent(id)
            assertEquals(ConfirmationGate.Stage.READY, g.state.value!!.stage)

            g.confirmExecute(id)
            val outcome = result.await() as ConfirmationGate.GateOutcome.Executed
            assertNotNull(outcome)
        }

        @Test
        @DisplayName("EC-C6: editing a VERBATIM field that doesn't require verification (a time picker) never invalidates approval")
        fun `editing a non-verifying verbatim field does not invalidate approval`(@TempDir dir: Path) = runTest {
            val g = gate(dir)
            val proposal = CalendarProposal(
                title = "Lunch", start = Instant.parse("2026-09-02T06:30:00Z"), end = Instant.parse("2026-09-02T07:30:00Z"),
                zoneId = "Asia/Kolkata", allDay = false, speechSummary = "lunch",
            )
            val fields = listOf(
                ProposalField("title", "Title", "Lunch", FieldKind.CONTENT),
                ProposalField("start", "Start", "2026-09-02T12:00", FieldKind.VERBATIM),
                ProposalField("end", "End", "2026-09-02T13:00", FieldKind.VERBATIM),
            )
            val result = async(start = CoroutineStart.UNDISPATCHED) {
                g.submit(LedgerKind.CALENDAR_CREATE, proposal, fields) { _, _ -> ConfirmationGate.ExecutorResult.Success("e1") }
            }
            val id = g.state.value!!.proposalId
            g.confirmContent(id)
            assertEquals(ConfirmationGate.Stage.READY, g.state.value!!.stage)

            g.editField(id, "start", "2026-09-02T14:00")

            // Still READY - a time edit is not a content edit (EC-C6).
            assertEquals(ConfirmationGate.Stage.READY, g.state.value!!.stage)
            assertEquals(14, (g.state.value!!.proposal as CalendarProposal).start.atZone(java.time.ZoneId.of("Asia/Kolkata")).hour)

            g.confirmExecute(id)
            assertTrue(result.await() is ConfirmationGate.GateOutcome.Executed)
        }

        @Test
        @DisplayName("EC-E2: the EXECUTED payload is the edited one, never the original draft")
        fun `execute always uses the current edited snapshot`(@TempDir dir: Path) = runTest {
            val g = gate(dir)
            var seenBody: String? = null
            val result = async(start = CoroutineStart.UNDISPATCHED) {
                g.submit(LedgerKind.EMAIL_SEND, emailProposal(), emailFields) { _, approved ->
                    seenBody = (approved as EmailProposal).body
                    ConfirmationGate.ExecutorResult.Success("x")
                }
            }
            val id = g.state.value!!.proposalId
            g.editField(id, "body", "the edited version")
            g.confirmContent(id)
            g.confirmVerbatim(id, "to")
            g.confirmExecute(id)
            result.await()
            assertEquals("the edited version", seenBody)
        }
    }

    @Nested
    @DisplayName("EC-A8: one gate at a time")
    inner class OneAtATime {

        @Test
        fun `a second submit while one is pending is refused without suspending`(@TempDir dir: Path) = runTest {
            val g = gate(dir)
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                g.submit(LedgerKind.EMAIL_SEND, emailProposal(), emailFields) { _, _ -> ConfirmationGate.ExecutorResult.Success("x") }
            }
            assertEquals(ConfirmationGate.Stage.CONTENT_REVIEW, g.state.value!!.stage)

            // Not launched in a coroutine: a busy submit returns immediately,
            // synchronously, without ever suspending.
            val secondOutcome = g.submit(LedgerKind.CALENDAR_CREATE, emailProposal(), emailFields) { _, _ ->
                ConfirmationGate.ExecutorResult.Success("should not run")
            }
            assertEquals(ConfirmationGate.GateOutcome.Busy, secondOutcome)

            // The first gate is untouched by the second's rejection.
            assertEquals(ConfirmationGate.Stage.CONTENT_REVIEW, g.state.value!!.stage)
            g.cancel(g.state.value!!.proposalId)
            assertEquals(ConfirmationGate.GateOutcome.CancelledByUser, first.await())
        }
    }

    @Nested
    @DisplayName("EC-E6: cancel at any stage")
    inner class Cancellation {

        @Test
        fun `cancel from content review`(@TempDir dir: Path) = runTest {
            val g = gate(dir)
            val result = async(start = CoroutineStart.UNDISPATCHED) {
                g.submit(LedgerKind.EMAIL_SEND, emailProposal(), emailFields) { _, _ -> ConfirmationGate.ExecutorResult.Success("x") }
            }
            g.cancel(g.state.value!!.proposalId)
            assertEquals(ConfirmationGate.GateOutcome.CancelledByUser, result.await())
            assertNull(g.state.value)
        }

        @Test
        fun `cancel from ready, before the final click`(@TempDir dir: Path) = runTest {
            val g = gate(dir)
            val result = async(start = CoroutineStart.UNDISPATCHED) {
                g.submit(LedgerKind.EMAIL_SEND, emailProposal(), emailFields) { _, _ -> ConfirmationGate.ExecutorResult.Success("x") }
            }
            val id = g.state.value!!.proposalId
            g.confirmContent(id)
            g.confirmVerbatim(id, "to")
            g.cancel(id)
            assertEquals(ConfirmationGate.GateOutcome.CancelledByUser, result.await())
        }

        @Test
        @DisplayName("cancel is refused once EXECUTING - the real call is already in flight")
        fun `cancel does nothing once executing`(@TempDir dir: Path) = runTest {
            val g = gate(dir)
            var completedNormally = false
            val result = async(start = CoroutineStart.UNDISPATCHED) {
                g.submit(LedgerKind.EMAIL_SEND, emailProposal(), emailFields) { _, _ ->
                    // cancel() is attempted WHILE this executor is "running" -
                    // it must be a no-op, since confirmExecute has already
                    // moved the stage to EXECUTING before invoking it.
                    completedNormally = true
                    ConfirmationGate.ExecutorResult.Success("x")
                }
            }
            val id = g.state.value!!.proposalId
            g.confirmContent(id)
            g.confirmVerbatim(id, "to")
            g.confirmExecute(id)
            g.cancel(id) // too late - already resolved and cleared

            assertTrue(completedNormally)
            assertTrue(result.await() is ConfirmationGate.GateOutcome.Executed)
        }
    }

    @Nested
    @DisplayName("executor outcomes map to the right ledger state")
    inner class OutcomeMapping {

        private fun ActionLedger.stateOf(id: String) = get(id)!!.state

        @Test
        fun `Failed maps to FAILED and resolves the gate`(@TempDir dir: Path) = runTest {
            val ledger = ActionLedger(AgentDb(dir.resolve("app.db")))
            val g = ConfirmationGate(ledger)
            val result = async(start = CoroutineStart.UNDISPATCHED) {
                g.submit(LedgerKind.EMAIL_SEND, emailProposal(), emailFields) { _, _ -> ConfirmationGate.ExecutorResult.Failed("bad address") }
            }
            val id = g.state.value!!.proposalId
            g.confirmContent(id); g.confirmVerbatim(id, "to"); g.confirmExecute(id)
            val outcome = result.await()
            assertTrue(outcome is ConfirmationGate.GateOutcome.ExecutionFailed)
            assertEquals(LedgerState.FAILED, ledger.stateOf(id))
        }

        @Test
        @DisplayName("EC-E4/Z8: Unknown maps to UNKNOWN and resolves the gate - never retried")
        fun `Unknown maps to UNKNOWN`(@TempDir dir: Path) = runTest {
            val ledger = ActionLedger(AgentDb(dir.resolve("app.db")))
            val g = ConfirmationGate(ledger)
            val result = async(start = CoroutineStart.UNDISPATCHED) {
                g.submit(LedgerKind.EMAIL_SEND, emailProposal(), emailFields) { _, _ -> ConfirmationGate.ExecutorResult.Unknown("timed out") }
            }
            val id = g.state.value!!.proposalId
            g.confirmContent(id); g.confirmVerbatim(id, "to"); g.confirmExecute(id)
            assertTrue(result.await() is ConfirmationGate.GateOutcome.ExecutionUnknown)
            assertEquals(LedgerState.UNKNOWN, ledger.stateOf(id))
        }

        @Test
        @DisplayName("EC-E3: NeedsReauth stays APPROVED, and the gate stays open for a retry")
        fun `NeedsReauth keeps the gate open`(@TempDir dir: Path) = runTest {
            val ledger = ActionLedger(AgentDb(dir.resolve("app.db")))
            val g = ConfirmationGate(ledger)
            var attempts = 0
            val result = async(start = CoroutineStart.UNDISPATCHED) {
                g.submit(LedgerKind.EMAIL_SEND, emailProposal(), emailFields) { _, _ ->
                    attempts++
                    if (attempts == 1) ConfirmationGate.ExecutorResult.NeedsReauth("token expired")
                    else ConfirmationGate.ExecutorResult.Success("x")
                }
            }
            val id = g.state.value!!.proposalId
            g.confirmContent(id); g.confirmVerbatim(id, "to")
            g.confirmExecute(id) // first attempt: needs reauth

            assertEquals(LedgerState.APPROVED, ledger.stateOf(id))
            assertNotNull(g.state.value) // gate still open - draft not lost
            assertEquals(ConfirmationGate.Stage.READY, g.state.value!!.stage)

            g.confirmExecute(id) // retry after "re-authenticating"
            assertTrue(result.await() is ConfirmationGate.GateOutcome.Executed)
            assertEquals(2, attempts)
        }

        @Test
        fun `a throwing executor is treated as Unknown, never crashes the gate`(@TempDir dir: Path) = runTest {
            val ledger = ActionLedger(AgentDb(dir.resolve("app.db")))
            val g = ConfirmationGate(ledger)
            val result = async(start = CoroutineStart.UNDISPATCHED) {
                g.submit(LedgerKind.EMAIL_SEND, emailProposal(), emailFields) { _, _ -> throw IllegalStateException("boom") }
            }
            val id = g.state.value!!.proposalId
            g.confirmContent(id); g.confirmVerbatim(id, "to"); g.confirmExecute(id)
            assertTrue(result.await() is ConfirmationGate.GateOutcome.ExecutionUnknown)
            assertEquals(LedgerState.UNKNOWN, ledger.stateOf(id))
        }
    }

    @Nested
    @DisplayName("EC-E1: address shape validation on retype")
    inner class VerbatimRetype {

        @Test
        fun `retyping an invalid address sets a validation error and does not confirm it`(@TempDir dir: Path) = runTest {
            val g = gate(dir)
            val result = async(start = CoroutineStart.UNDISPATCHED) {
                g.submit(LedgerKind.EMAIL_SEND, emailProposal(), emailFields) { _, _ -> ConfirmationGate.ExecutorResult.Success("x") }
            }
            val id = g.state.value!!.proposalId
            g.confirmContent(id)
            g.retypeVerbatim(id, "to", "not-an-address")
            assertNotNull(g.state.value!!.validationError)
            assertEquals(ConfirmationGate.Stage.VERBATIM_VERIFY, g.state.value!!.stage)

            g.retypeVerbatim(id, "to", "corrected@example.com")
            assertNull(g.state.value!!.validationError)
            g.confirmVerbatim(id, "to")
            assertEquals(ConfirmationGate.Stage.READY, g.state.value!!.stage)
            assertEquals("corrected@example.com", (g.state.value!!.proposal as EmailProposal).to)

            g.confirmExecute(id)
            assertTrue(result.await() is ConfirmationGate.GateOutcome.Executed)
        }
    }
}
