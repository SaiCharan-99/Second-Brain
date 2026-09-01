package com.secondbrain.ports

import com.secondbrain.model.EmailProposal

/**
 * Sending, as `:agent` sees it. CLAUDE.md's module map names this;
 * `:integrations`' `GmailAdapter` is the only implementation.
 *
 * There is deliberately no `read`/`search` here — R2/D-006's whole point is
 * that sending is not something a model call can trigger directly, and this
 * port is scoped to exactly what `ConfirmationGate`'s executor needs after a
 * human approves. The Gmail scope requested is `gmail.send` only.
 */
interface MailPort {
    /**
     * [idempotencyKey] is the ledger's `proposal_id`. Gmail has no server-side
     * dedup token for `send` — the real guarantee against a double send is the
     * caller never invoking this twice for one proposal (R5), not anything this
     * method does with the key itself. It is threaded through anyway so an
     * implementation that *can* dedupe (a different provider, a test double)
     * has somewhere to put it.
     */
    suspend fun send(proposal: EmailProposal, idempotencyKey: String): SendOutcome
}

/** Three-way outcome shape shared with [CalendarPort] — see EC-E3/EC-E4. */
sealed interface SendOutcome {
    data class Sent(val messageId: String) : SendOutcome

    /** A definite rejection (invalid recipient, quota, etc). Ledger -> FAILED. */
    data class Failed(val reason: String) : SendOutcome

    /**
     * EC-E4: the network dropped mid-call, or the response never arrived. We
     * genuinely do not know whether the email sent. Ledger -> UNKNOWN, and R5
     * forbids ever auto-retrying this.
     */
    data class Unknown(val reason: String) : SendOutcome

    /**
     * EC-E3: token refresh failed. Ledger stays APPROVED — not FAILED — so the
     * draft is not lost; the user re-authenticates and the same proposal can be
     * confirmed again.
     */
    data class NeedsReauth(val reason: String) : SendOutcome
}
