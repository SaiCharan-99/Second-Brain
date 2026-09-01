package com.secondbrain.model

import java.time.Instant

/**
 * The Step 5/6 confirmation-gate model: ARCHITECTURE §7 Step 5's
 * "`Proposal` (sealed: `EmailProposal`, `CalendarProposal`, `OrderProposal`),
 * `Resolution`, `FieldKind`."
 *
 * `OrderProposal` is deliberately not here yet. Zepto's real shape is unknown
 * until Step 7's blocking spikes run (D-009), and CLAUDE.md's working style is
 * explicit: "never design ahead of what is validated." Adding a speculative
 * third variant now is exactly that.
 *
 * [Proposal.kind] reuses [LedgerKind] rather than a second, parallel
 * "ProposalKind" enum with the same two values — a proposal and its ledger row
 * describe the same action, and a second enum would only ever need mapping
 * back and forth to this one.
 */

/**
 * Whether an edit to a field invalidates content approval (R6, WF-2).
 *
 * [VERBATIM]'s load-bearing meaning is "this edit does NOT reset approval to
 * PROPOSED" — not literally "read back character by character." It covers two
 * different things for that one reason: an email/attendee address that genuinely
 * goes through TTS spell-back verification, AND a calendar start/end time edit
 * per EC-C6 ("time edits do not invalidate approval — time is what the window is
 * for"), which is not spoken back at all. Reusing one enum for both avoids
 * inventing a third field kind — and a third R9-adjacent concept — for what is,
 * behaviourally, the same rule. See the Step 5/6 plan for the explicit call-out.
 */
enum class FieldKind {
    /** An edit here resets approval to PROPOSED. */
    CONTENT,

    /** An edit here does not. See the class doc for what this actually covers. */
    VERBATIM,
}

/** One editable field in a `ProposalWindow`. */
data class ProposalField(
    val id: String,
    val label: String,
    val value: String,
    val kind: FieldKind,
    /**
     * True only for fields that go through the spoken spell-and-confirm stage
     * (an email or attendee address). False for a [FieldKind.VERBATIM] field
     * that merely doesn't invalidate approval, like a calendar time picker.
     */
    val requiresVerbatimVerification: Boolean = false,
)

sealed interface Proposal {
    val kind: LedgerKind

    /**
     * One line for TTS. "Three sentences to Udit, asking for the current status
     * of BluePrint Lens." Never the body/description verbatim (EC-T5).
     */
    val speechSummary: String
}

data class EmailProposal(
    val to: String,
    val cc: List<String> = emptyList(),
    val subject: String,
    val body: String,
    override val speechSummary: String,
) : Proposal {
    override val kind: LedgerKind get() = LedgerKind.EMAIL_SEND
}

data class CalendarProposal(
    val title: String,
    val start: Instant,
    val end: Instant,
    val zoneId: String,
    /** EC-C8: stated explicitly in the read-back so the user can correct it. */
    val allDay: Boolean,
    val attendees: List<String> = emptyList(),
    val location: String? = null,
    val description: String? = null,
    /** EC-C4: informational only. A busy slot is never blocked, only warned about. */
    val conflictWarning: String? = null,
    override val speechSummary: String,
) : Proposal {
    override val kind: LedgerKind get() = LedgerKind.CALENDAR_CREATE
}
