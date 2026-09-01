package com.secondbrain.agent

import com.secondbrain.model.LedgerKind
import com.secondbrain.model.LedgerRow
import com.secondbrain.model.LedgerState
import org.slf4j.LoggerFactory
import java.sql.ResultSet
import java.sql.Types
import java.time.Instant
import java.util.UUID

/**
 * R5: "the ledger is the source of truth for irreversible actions, and it
 * never auto-retries." `proposal_id` is the idempotency key — written before
 * [ConfirmationGate] ever calls an adapter, and nothing anywhere in this
 * codebase re-executes a row once it leaves PROPOSED/APPROVED on its own.
 *
 * `app.db`'s `agent`-owned tables, migration 2 on [AgentDb] (D-045's two-owner
 * split). Table shape matches ARCHITECTURE §2's `action_ledger` exactly.
 */
class ActionLedger(private val db: AgentDb) {

    private val log = LoggerFactory.getLogger(ActionLedger::class.java)

    fun create(kind: LedgerKind, payloadJson: String, now: Instant = Instant.now()): String {
        val id = UUID.randomUUID().toString()
        db.connection.prepareStatement(
            """
            INSERT INTO action_ledger(proposal_id, kind, payload, state, external_id, error, created_at, updated_at)
            VALUES (?,?,?,?,NULL,NULL,?,?)
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, id)
            ps.setString(2, kind.name)
            ps.setString(3, payloadJson)
            ps.setString(4, LedgerState.PROPOSED.name)
            ps.setString(5, now.toString())
            ps.setString(6, now.toString())
            ps.executeUpdate()
        }
        log.info("Ledger {} created: {} PROPOSED", id, kind)
        return id
    }

    /** EC-E2: the snapshot the user actually saw, every time it changes. */
    fun updatePayload(proposalId: String, payloadJson: String, now: Instant = Instant.now()) {
        db.connection.prepareStatement(
            "UPDATE action_ledger SET payload = ?, updated_at = ? WHERE proposal_id = ?"
        ).use { ps ->
            ps.setString(1, payloadJson)
            ps.setString(2, now.toString())
            ps.setString(3, proposalId)
            ps.executeUpdate()
        }
    }

    fun transition(
        proposalId: String,
        state: LedgerState,
        externalId: String? = null,
        error: String? = null,
        now: Instant = Instant.now(),
    ) {
        db.connection.prepareStatement(
            "UPDATE action_ledger SET state = ?, external_id = COALESCE(?, external_id), error = ?, updated_at = ? WHERE proposal_id = ?"
        ).use { ps ->
            ps.setString(1, state.name)
            if (externalId == null) ps.setNull(2, Types.VARCHAR) else ps.setString(2, externalId)
            if (error == null) ps.setNull(3, Types.VARCHAR) else ps.setString(3, error)
            ps.setString(4, now.toString())
            ps.setString(5, proposalId)
            ps.executeUpdate()
        }
        log.info("Ledger {} -> {}", proposalId, state)
    }

    fun get(proposalId: String): LedgerRow? =
        db.connection.prepareStatement(
            "SELECT proposal_id, kind, payload, state, external_id, error, created_at, updated_at FROM action_ledger WHERE proposal_id = ?"
        ).use { ps ->
            ps.setString(1, proposalId)
            ps.executeQuery().use { rs -> if (rs.next()) rowOf(rs) else null }
        }

    fun all(): List<LedgerRow> {
        val out = mutableListOf<LedgerRow>()
        db.connection.createStatement().use { s ->
            s.executeQuery(
                "SELECT proposal_id, kind, payload, state, external_id, error, created_at, updated_at " +
                    "FROM action_ledger ORDER BY created_at DESC"
            ).use { rs -> while (rs.next()) out += rowOf(rs) }
        }
        return out
    }

    private fun rowOf(rs: ResultSet) = LedgerRow(
        proposalId = rs.getString(1),
        kind = LedgerKind.valueOf(rs.getString(2)),
        payloadJson = rs.getString(3),
        state = LedgerState.valueOf(rs.getString(4)),
        externalId = rs.getString(5),
        error = rs.getString(6),
        createdAt = rs.getString(7),
        updatedAt = rs.getString(8),
    )

    /**
     * EC-A9. Run once at startup, before anything else touches the ledger.
     *
     * PROPOSED/APPROVED rows are UI state from a process that no longer exists —
     * no window will ever resolve them. EXECUTING rows are genuinely unknown:
     * the process died between "about to call the adapter" and "recorded the
     * result," and R5 forbids ever re-trying them automatically, on restart or
     * otherwise.
     *
     * Verified safe against `ConversationStore`: a turn's messages are only
     * persisted once `AgentLoop.run` returns, so a crash mid-gate leaves no
     * orphaned `tool_use`/`tool_result` pairing in the conversation history to
     * repair — only this ledger row, which this method handles.
     */
    fun reconcileOnStartup(now: Instant = Instant.now()): ReconciliationReport {
        var cancelled = 0
        var unknown = 0
        db.transaction {
            db.connection.prepareStatement(
                "UPDATE action_ledger SET state = ?, updated_at = ? WHERE state IN (?, ?)"
            ).use { ps ->
                ps.setString(1, LedgerState.CANCELLED.name)
                ps.setString(2, now.toString())
                ps.setString(3, LedgerState.PROPOSED.name)
                ps.setString(4, LedgerState.APPROVED.name)
                cancelled = ps.executeUpdate()
            }
            db.connection.prepareStatement(
                "UPDATE action_ledger SET state = ?, updated_at = ? WHERE state = ?"
            ).use { ps ->
                ps.setString(1, LedgerState.UNKNOWN.name)
                ps.setString(2, now.toString())
                ps.setString(3, LedgerState.EXECUTING.name)
                unknown = ps.executeUpdate()
            }
        }
        if (cancelled > 0 || unknown > 0) {
            log.warn(
                "Startup reconciliation: {} PROPOSED/APPROVED -> CANCELLED, {} EXECUTING -> UNKNOWN",
                cancelled, unknown,
            )
        }
        return ReconciliationReport(cancelled, unknown)
    }

    data class ReconciliationReport(val cancelledCount: Int, val unknownCount: Int)
}
