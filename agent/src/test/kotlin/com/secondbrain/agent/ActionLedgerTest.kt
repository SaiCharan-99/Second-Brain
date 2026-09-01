package com.secondbrain.agent

import com.secondbrain.model.LedgerKind
import com.secondbrain.model.LedgerState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * R5's state machine, and EC-A9's startup reconciliation — "on startup, any
 * ledger row in PROPOSED/APPROVED is marked CANCELLED. Any row in EXECUTING is
 * marked UNKNOWN. Never auto-execute or auto-retry on restart."
 */
class ActionLedgerTest {

    private fun ledger(dir: Path): ActionLedger = ActionLedger(AgentDb(dir.resolve("app.db")))

    @Test
    fun `create starts a row at PROPOSED`(@TempDir dir: Path) {
        val l = ledger(dir)
        val id = l.create(LedgerKind.EMAIL_SEND, """{"to":"a@b.com"}""")
        val row = l.get(id)!!
        assertEquals(LedgerState.PROPOSED, row.state)
        assertEquals(LedgerKind.EMAIL_SEND, row.kind)
        assertNull(row.externalId)
    }

    @Test
    fun `transition updates state and preserves the payload`(@TempDir dir: Path) {
        val l = ledger(dir)
        val id = l.create(LedgerKind.CALENDAR_CREATE, """{"title":"lunch"}""")
        l.transition(id, LedgerState.APPROVED)
        assertEquals(LedgerState.APPROVED, l.get(id)!!.state)
        assertEquals("""{"title":"lunch"}""", l.get(id)!!.payloadJson)
    }

    @Test
    @DisplayName("EC-E2: updatePayload overwrites the snapshot the user actually saw")
    fun `updatePayload replaces the payload without touching state`(@TempDir dir: Path) {
        val l = ledger(dir)
        val id = l.create(LedgerKind.EMAIL_SEND, """{"body":"original"}""")
        l.transition(id, LedgerState.APPROVED)
        l.updatePayload(id, """{"body":"edited"}""")
        val row = l.get(id)!!
        assertEquals("""{"body":"edited"}""", row.payloadJson)
        assertEquals(LedgerState.APPROVED, row.state)
    }

    @Test
    fun `transition can record an external id and an error independently`(@TempDir dir: Path) {
        val l = ledger(dir)
        val id = l.create(LedgerKind.EMAIL_SEND, "{}")
        l.transition(id, LedgerState.DONE, externalId = "msg-123")
        assertEquals("msg-123", l.get(id)!!.externalId)

        val id2 = l.create(LedgerKind.EMAIL_SEND, "{}")
        l.transition(id2, LedgerState.FAILED, error = "bad address")
        assertEquals("bad address", l.get(id2)!!.error)
        assertNull(l.get(id2)!!.externalId)
    }

    @Test
    @DisplayName("EC-A9: PROPOSED and APPROVED rows are cancelled on startup")
    fun `reconcileOnStartup cancels stale proposed and approved rows`(@TempDir dir: Path) {
        val l = ledger(dir)
        val proposed = l.create(LedgerKind.EMAIL_SEND, "{}")
        val approved = l.create(LedgerKind.CALENDAR_CREATE, "{}").also { l.transition(it, LedgerState.APPROVED) }

        val report = l.reconcileOnStartup()

        assertEquals(2, report.cancelledCount)
        assertEquals(0, report.unknownCount)
        assertEquals(LedgerState.CANCELLED, l.get(proposed)!!.state)
        assertEquals(LedgerState.CANCELLED, l.get(approved)!!.state)
    }

    @Test
    @DisplayName("EC-A9/R5: EXECUTING rows become UNKNOWN, never DONE, never retried")
    fun `reconcileOnStartup marks executing rows unknown`(@TempDir dir: Path) {
        val l = ledger(dir)
        val id = l.create(LedgerKind.EMAIL_SEND, "{}")
        l.transition(id, LedgerState.EXECUTING)

        val report = l.reconcileOnStartup()

        assertEquals(0, report.cancelledCount)
        assertEquals(1, report.unknownCount)
        assertEquals(LedgerState.UNKNOWN, l.get(id)!!.state)
    }

    @Test
    fun `reconcileOnStartup leaves terminal rows untouched`(@TempDir dir: Path) {
        val l = ledger(dir)
        val done = l.create(LedgerKind.EMAIL_SEND, "{}").also { l.transition(it, LedgerState.DONE, externalId = "m1") }
        val failed = l.create(LedgerKind.EMAIL_SEND, "{}").also { l.transition(it, LedgerState.FAILED, error = "x") }
        val cancelled = l.create(LedgerKind.EMAIL_SEND, "{}").also { l.transition(it, LedgerState.CANCELLED) }
        val unknown = l.create(LedgerKind.EMAIL_SEND, "{}").also { l.transition(it, LedgerState.UNKNOWN) }

        val report = l.reconcileOnStartup()

        assertEquals(0, report.cancelledCount)
        assertEquals(0, report.unknownCount)
        assertEquals(LedgerState.DONE, l.get(done)!!.state)
        assertEquals(LedgerState.FAILED, l.get(failed)!!.state)
        assertEquals(LedgerState.CANCELLED, l.get(cancelled)!!.state)
        assertEquals(LedgerState.UNKNOWN, l.get(unknown)!!.state)
    }

    @Test
    fun `reconciliation survives a restart against the same file`(@TempDir dir: Path) {
        val file = dir.resolve("app.db")
        val id = ActionLedger(AgentDb(file)).create(LedgerKind.EMAIL_SEND, "{}")
        // Simulate the app dying mid-send: a fresh AgentDb/ActionLedger, same file.
        val reopened = ActionLedger(AgentDb(file))
        reopened.transition(id, LedgerState.EXECUTING)
        val restarted = ActionLedger(AgentDb(file))
        val report = restarted.reconcileOnStartup()
        assertEquals(1, report.unknownCount)
        assertEquals(LedgerState.UNKNOWN, restarted.get(id)!!.state)
    }

    @Test
    fun `all lists every row, most recent first`(@TempDir dir: Path) {
        val l = ledger(dir)
        // Explicit, distinct timestamps - wall-clock resolution on some
        // platforms (Windows' ~15ms timer tick) is coarse enough that two
        // real Instant.now() calls milliseconds apart can render identically.
        val first = l.create(LedgerKind.EMAIL_SEND, "{}", now = java.time.Instant.parse("2026-09-01T10:00:00Z"))
        val second = l.create(LedgerKind.CALENDAR_CREATE, "{}", now = java.time.Instant.parse("2026-09-01T10:00:01Z"))
        val rows = l.all()
        assertEquals(2, rows.size)
        assertEquals(second, rows.first().proposalId)
        assertEquals(first, rows.last().proposalId)
    }
}
