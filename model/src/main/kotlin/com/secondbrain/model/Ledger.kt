package com.secondbrain.model

/** ARCHITECTURE §2's `action_ledger.kind`. */
enum class LedgerKind { EMAIL_SEND, CALENDAR_CREATE, ORDER_PLACE }

/**
 * ARCHITECTURE §2's `action_ledger.state`, verbatim. R5: "`proposal_id` is the
 * idempotency key... a lost response becomes `UNKNOWN` and is surfaced to the
 * user... nothing re-executes on restart."
 */
enum class LedgerState { PROPOSED, APPROVED, EXECUTING, DONE, FAILED, CANCELLED, UNKNOWN }

/** One row of `action_ledger`. */
data class LedgerRow(
    val proposalId: String,
    val kind: LedgerKind,
    val payloadJson: String,
    val state: LedgerState,
    val externalId: String? = null,
    val error: String? = null,
    val createdAt: String,
    val updatedAt: String,
)
