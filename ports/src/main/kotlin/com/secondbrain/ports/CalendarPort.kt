package com.secondbrain.ports

import com.secondbrain.model.CalendarProposal
import java.time.Instant

/**
 * Calendar access, as `:agent` sees it. `:integrations`' `CalendarAdapter` is
 * the only implementation. Scope requested is `calendar.events` only.
 */
interface CalendarPort {
    /** EC-C4: read-only. A conflict is surfaced as a warning, never blocks a proposal. */
    suspend fun findBusy(start: Instant, end: Instant): List<BusyBlock>

    /** See [MailPort.send]'s doc on [idempotencyKey] — the same reasoning applies. */
    suspend fun insert(proposal: CalendarProposal, idempotencyKey: String): InsertOutcome
}

data class BusyBlock(val start: Instant, val end: Instant, val title: String? = null)

/** Same three-way (plus reauth) outcome shape as [SendOutcome] — see EC-E3/EC-E4/EC-Z8's pattern. */
sealed interface InsertOutcome {
    data class Created(val eventId: String) : InsertOutcome
    data class Failed(val reason: String) : InsertOutcome
    data class Unknown(val reason: String) : InsertOutcome
    data class NeedsReauth(val reason: String) : InsertOutcome
}
