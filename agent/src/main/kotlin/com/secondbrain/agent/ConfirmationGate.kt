package com.secondbrain.agent

import com.secondbrain.model.CalendarProposal
import com.secondbrain.model.EmailAddressValidator
import com.secondbrain.model.EmailProposal
import com.secondbrain.model.LedgerKind
import com.secondbrain.model.LedgerState
import com.secondbrain.model.OrderProposal
import com.secondbrain.model.Proposal
import com.secondbrain.model.ProposalField
import com.secondbrain.model.FieldKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicReference

/**
 * ARCHITECTURE §7 Step 5: "the confirmation gate... suspends the loop, emits
 * the proposal, awaits resolution." The safety machinery R2/R5/R6/R9 describe,
 * built once and reused unchanged for both email (Step 5) and calendar
 * (Step 6) — WF-3's own framing: "same gate machinery, different payload."
 *
 * ### How R2 stays true without dispatcher interception
 *
 * A `GATED` tool's handler (`EmailTools.draftEmail`, `CalendarTools.proposeEvent`)
 * runs exactly like an autonomous one now — [ToolDispatcher] no longer
 * special-cases the class (see its own doc for why). What still makes "gated
 * tools never execute from a model call" true is structural, not a dispatcher
 * check: the handler's *only* path to a real side effect is [submit], and
 * [submit] does not call [executor] until a human has clicked through every
 * stage below. There is no code path from a `tool_use` block to `gmail.send`
 * that does not pass through a resolved [Stage.READY] gate.
 *
 * ### EC-A8, honestly
 *
 * [pendingRef]'s compare-and-set gives "one gate at a time" as a real,
 * testable property: a second concurrent [submit] gets [GateOutcome.Busy]
 * immediately, no second ledger row, no suspend. In today's actual call paths
 * this is a defensive backstop rather than something a user can trigger: tool
 * calls within one turn dispatch sequentially (`AgentLoop`'s existing `for`
 * loop), so a second `tool_use` in the same batch cannot even begin dispatching
 * until the first's `submit` returns; and `VoiceController.turnMutex` prevents
 * a *second turn* from starting while the first's gate is still open (decision
 * 6 of the Step 5/6 plan — talking during an open gate queues behind it rather
 * than racing it). "One gate at a time" is therefore already guaranteed by
 * those two properties; this class enforces it again anyway, because a future
 * caller (a test, a second entry point) should not be able to violate it by
 * construction, and because R3's fail-closed instinct applies here too.
 *
 * ### R6 / EC-E2, mechanically
 *
 * There is exactly one mutable copy of the proposal per open gate — the one
 * inside [_state]. [editField] and [retypeVerbatim] both replace it in place.
 * [confirmExecute] always reads *that* copy and hands it to [executor]. There
 * is no code path that could execute the original draft after an edit.
 */
class ConfirmationGate(
    private val ledger: ActionLedger,
) {
    private val log = LoggerFactory.getLogger(ConfirmationGate::class.java)

    enum class Stage { CONTENT_REVIEW, VERBATIM_VERIFY, READY, EXECUTING }

    /** What the tool handler gets back once the gate resolves, one way or another. */
    sealed interface GateOutcome {
        data class Executed(val externalId: String) : GateOutcome
        data class ExecutionFailed(val reason: String) : GateOutcome

        /** EC-E4/EC-Z8-style: never retried automatically. */
        data class ExecutionUnknown(val reason: String) : GateOutcome

        /** EC-E3: the draft is not lost. The gate stays open for a retry after re-auth. */
        data class NeedsReauth(val reason: String) : GateOutcome

        /** EC-E6. */
        data object CancelledByUser : GateOutcome

        /** EC-A8. */
        data object Busy : GateOutcome

        /**
         * EC-Z14: the user wants to change the proposal rather than accept or
         * abandon it, and the change is one this window cannot make.
         *
         * ### Why this outcome has to exist
         *
         * [submit] suspends the calling tool handler, and therefore the whole
         * agent loop, for as long as the window is open — and
         * `VoiceController.turnMutex` queues any new utterance behind that. So
         * while a proposal is open the model cannot run a turn, which means
         * *nothing the user says can be acted on*. For email and calendar that
         * is fine: every field is a string the window edits directly.
         *
         * For an order it is not. A cart lives on the far side of a network
         * call — "drop the milk, and make it two breads" is two server
         * mutations that can each be *rejected* (out of stock, quantity cap),
         * and [editField] is synchronous and has nowhere to put that. WF-4's
         * flow has no path for it either: its only loop-back adds more items.
         *
         * So revision is expressed by *ending* the gate. The tool handler gets
         * this outcome, hands [instruction] back to the model as a normal
         * `tool_result`, the model edits the real cart with the autonomous cart
         * tools, re-reads it, and proposes again. Nothing is lost, because the
         * cart was never local in the first place.
         *
         * Note what this deliberately does not do: it never approves anything.
         * Speech opens a revision; only a click commits (R9).
         *
         * @param instruction what the user said, verbatim, if they spoke it.
         *   Null when they simply pressed "Change something", in which case the
         *   model is told to ask.
         */
        data class RevisionRequested(val instruction: String?) : GateOutcome
    }

    /** What an executor (`EmailTools`/`CalendarTools`) reports after the real call. */
    sealed interface ExecutorResult {
        data class Success(val externalId: String) : ExecutorResult
        data class Failed(val reason: String) : ExecutorResult
        data class Unknown(val reason: String) : ExecutorResult
        data class NeedsReauth(val reason: String) : ExecutorResult
    }

    /** What `:app`'s `ProposalWindow` observes and drives. */
    data class UiState(
        val proposalId: String,
        val proposal: Proposal,
        val fields: List<ProposalField>,
        val stage: Stage,
        /** fieldId -> confirmed. Only fields with `requiresVerbatimVerification` appear here. */
        val verbatimConfirmed: Map<String, Boolean> = emptyMap(),
        /** Non-null disables the Confirm/Send button, with the reason shown inline. */
        val validationError: String? = null,
        /** EC-C4: informational only, never blocks. */
        val conflictWarning: String? = null,
        /**
         * EC-E3: shown after a [ExecutorResult.NeedsReauth], and deliberately
         * NOT [validationError].
         *
         * These have opposite meanings for the Confirm button. A validation
         * error means "this cannot be sent as it stands", so Confirm is
         * disabled. A re-auth notice means "this is fine, the credentials
         * weren't" — the whole point of EC-E3 is that the draft survives and
         * the user can retry after re-authenticating. Putting the notice in
         * [validationError] disabled the only button that could perform that
         * retry, which made the gate permanently un-completable: the draft was
         * preserved exactly as EC-E3 asks, and then nothing could ever be done
         * with it except cancel.
         */
        val reauthNotice: String? = null,
    )

    private class Pending(
        val proposalId: String,
        val executor: suspend (proposalId: String, proposal: Proposal) -> ExecutorResult,
        val validator: (Proposal) -> String?,
        val deferred: CompletableDeferred<GateOutcome>,
    )

    private val pendingRef = AtomicReference<Pending?>(null)

    private val _state = MutableStateFlow<UiState?>(null)
    val state: StateFlow<UiState?> = _state.asStateFlow()

    /**
     * Called by a GATED tool handler. Suspends — genuinely, for as long as it
     * takes a person to click through the window — until the gate resolves.
     */
    suspend fun submit(
        kind: LedgerKind,
        proposal: Proposal,
        fields: List<ProposalField>,
        conflictWarning: String? = null,
        validator: (Proposal) -> String? = { null },
        executor: suspend (proposalId: String, proposal: Proposal) -> ExecutorResult,
    ): GateOutcome {
        val proposalId = ledger.create(kind, encode(proposal))
        val deferred = CompletableDeferred<GateOutcome>()
        val pending = Pending(proposalId, executor, validator, deferred)

        if (!pendingRef.compareAndSet(null, pending)) {
            log.info("Gate busy; {} proposal {} not opened.", kind, proposalId)
            // Roll the just-created row back rather than leave it PROPOSED
            // forever with nothing that will ever resolve it.
            ledger.transition(proposalId, LedgerState.CANCELLED, error = "gate_busy")
            return GateOutcome.Busy
        }

        log.info("Gate opened: {} {}", kind, proposalId)
        _state.value = UiState(
            proposalId = proposalId,
            proposal = proposal,
            fields = fields,
            // D-101: a proposal with no editable fields - today only
            // OrderProposal, per EC-Z15's "a cart line is not an editable
            // string" - has nothing for an Approve click to do, so it opens
            // straight at READY. Hardcoding CONTENT_REVIEW here unconditionally
            // was a real bug: ProposalWindow's Actions() already shows the
            // "Place order" button at CONTENT_REVIEW too (its own comment says
            // "an order has nothing to review... goes straight to the
            // committing button"), but confirmExecute() only ever acts from
            // Stage.READY, so the button was clickable and silently did
            // nothing - forever, for every order, since Step 7 shipped.
            stage = if (fields.isEmpty()) Stage.READY else Stage.CONTENT_REVIEW,
            validationError = validator(proposal),
            conflictWarning = conflictWarning,
        )

        return try {
            deferred.await()
        } finally {
            pendingRef.set(null)
            _state.value = null
        }
    }

    // ── UI-driven mutations. Silently no-op if proposalId doesn't match the
    // currently open gate (a stale window after a resolve, a double-click). ──

    /** Edits one field. A [FieldKind.CONTENT] edit resets approval to PROPOSED (R6). */
    fun editField(proposalId: String, fieldId: String, newValue: String) {
        withPending(proposalId) { pending, current ->
            val fieldSpec = current.fields.firstOrNull { it.id == fieldId } ?: return@withPending current
            val updatedProposal = applyField(current.proposal, fieldId, newValue)
            val updatedFields = current.fields.map { if (it.id == fieldId) it.copy(value = newValue) else it }
            ledger.updatePayload(proposalId, encode(updatedProposal))

            val invalidatesApproval = fieldSpec.kind == FieldKind.CONTENT
            if (invalidatesApproval) {
                ledger.transition(proposalId, LedgerState.PROPOSED)
            }

            current.copy(
                proposal = updatedProposal,
                fields = updatedFields,
                stage = if (invalidatesApproval) Stage.CONTENT_REVIEW else current.stage,
                validationError = pending.validator(updatedProposal),
                // A retyped verbatim value needs re-confirming; an edited
                // content field doesn't touch verbatim confirmations at all.
                verbatimConfirmed = if (fieldSpec.requiresVerbatimVerification)
                    current.verbatimConfirmed - fieldId
                else current.verbatimConfirmed,
            )
        }
    }

    /**
     * Same as [editField] but for a field going through verbatim verification,
     * with an address-shape check (EC-E1). Distinguished from [editField]
     * because it always re-enters [Stage.VERBATIM_VERIFY] regardless of the
     * field's [FieldKind] — a calendar time field never needs this path.
     */
    fun retypeVerbatim(proposalId: String, fieldId: String, newValue: String) {
        withPending(proposalId) { pending, current ->
            val field = current.fields.firstOrNull { it.id == fieldId && it.requiresVerbatimVerification }
                ?: return@withPending current
            val shapeError = if (looksLikeAddress(fieldId)) addressShapeError(newValue) else null
            val updatedProposal = applyField(current.proposal, fieldId, newValue)
            ledger.updatePayload(proposalId, encode(updatedProposal))

            current.copy(
                proposal = updatedProposal,
                fields = current.fields.map { if (it.id == fieldId) it.copy(value = newValue) else it },
                verbatimConfirmed = current.verbatimConfirmed - fieldId,
                stage = Stage.VERBATIM_VERIFY,
                validationError = shapeError ?: pending.validator(updatedProposal),
            )
        }
    }

    /** Stage 1 -> stage 2 (or straight to READY if nothing needs verbatim verification). */
    fun confirmContent(proposalId: String) {
        withPending(proposalId) { _, current ->
            if (current.stage != Stage.CONTENT_REVIEW || current.validationError != null) return@withPending current
            ledger.transition(proposalId, LedgerState.APPROVED)
            current.copy(stage = nextStageAfterApproval(current))
        }
    }

    /** One verbatim field confirmed ("that's right"). */
    fun confirmVerbatim(proposalId: String, fieldId: String) {
        withPending(proposalId) { _, current ->
            val updated = current.verbatimConfirmed + (fieldId to true)
            val withConfirmed = current.copy(verbatimConfirmed = updated)
            withConfirmed.copy(stage = nextStageAfterApproval(withConfirmed))
        }
    }

    /** Live conflict recompute after a calendar time edit (EC-C4). Never blocks. */
    fun setConflictWarning(proposalId: String, warning: String?) {
        withPending(proposalId) { _, current -> current.copy(conflictWarning = warning) }
    }

    /**
     * The final, irreversible click (R9). Only acts from [Stage.READY]. Maps
     * [ExecutorResult] to the matching [LedgerState] and, for every outcome
     * except [ExecutorResult.NeedsReauth], resolves the gate.
     */
    suspend fun confirmExecute(proposalId: String) {
        val pending = pendingRef.get()?.takeIf { it.proposalId == proposalId } ?: return
        val current = _state.value?.takeIf { it.proposalId == proposalId } ?: return
        if (current.stage != Stage.READY || current.validationError != null) return

        // Clear any previous re-auth notice: this attempt supersedes it.
        _state.value = current.copy(stage = Stage.EXECUTING, reauthNotice = null)
        ledger.transition(proposalId, LedgerState.EXECUTING)
        log.info("Gate {} -> EXECUTING", proposalId)

        val outcome = try {
            pending.executor(proposalId, current.proposal)
        } catch (e: Exception) {
            log.error("Executor threw for {}", proposalId, e)
            ExecutorResult.Unknown("${e::class.simpleName}: ${e.message}")
        }

        when (outcome) {
            is ExecutorResult.Success -> {
                ledger.transition(proposalId, LedgerState.DONE, externalId = outcome.externalId)
                pending.deferred.complete(GateOutcome.Executed(outcome.externalId))
            }
            is ExecutorResult.Failed -> {
                ledger.transition(proposalId, LedgerState.FAILED, error = outcome.reason)
                pending.deferred.complete(GateOutcome.ExecutionFailed(outcome.reason))
            }
            is ExecutorResult.Unknown -> {
                // R5: never auto-retried, from here or anywhere else.
                ledger.transition(proposalId, LedgerState.UNKNOWN, error = outcome.reason)
                pending.deferred.complete(GateOutcome.ExecutionUnknown(outcome.reason))
            }
            is ExecutorResult.NeedsReauth -> {
                // EC-E3: stays APPROVED. The gate is left open (deferred NOT
                // completed) so the draft survives a re-auth and a retry.
                ledger.transition(proposalId, LedgerState.APPROVED, error = outcome.reason)
                _state.value = current.copy(
                    stage = Stage.READY,
                    // reauthNotice, not validationError - see UiState.reauthNotice.
                    // Confirm has to stay enabled for "press Confirm again" to
                    // be something the user can actually do.
                    reauthNotice = "Needs re-authentication: ${outcome.reason}. Sign in again, then press Confirm.",
                )
            }
        }
    }

    /**
     * EC-Z14: end the gate so the model can act on a change the window cannot
     * make itself. See [GateOutcome.RevisionRequested] for the full reasoning.
     *
     * Ledger-wise this is a cancellation — nothing executed, and the row is
     * closed with a reason that distinguishes it from the user walking away.
     * The replacement proposal gets its own row and its own `proposal_id`,
     * which is what R5 wants: one idempotency key per thing that could
     * actually be executed, never a mutated one.
     */
    fun requestRevision(proposalId: String, instruction: String? = null) {
        val pending = pendingRef.get()?.takeIf { it.proposalId == proposalId } ?: return
        val current = _state.value?.takeIf { it.proposalId == proposalId } ?: return
        // Same guard as cancel: once the real call is in flight, revising is
        // no longer a thing that can happen.
        if (current.stage == Stage.EXECUTING) return
        ledger.transition(proposalId, LedgerState.CANCELLED, error = "revision_requested")
        log.info("Gate {} - revision requested by user", proposalId)
        pending.deferred.complete(GateOutcome.RevisionRequested(instruction?.takeIf { it.isNotBlank() }))
    }

    /** EC-E6: cancel at any stage up to (not including) EXECUTING — once the real call is in flight it is too late to cancel. */
    fun cancel(proposalId: String, reason: String = "user_cancelled") {
        val pending = pendingRef.get()?.takeIf { it.proposalId == proposalId } ?: return
        val current = _state.value?.takeIf { it.proposalId == proposalId } ?: return
        if (current.stage == Stage.EXECUTING) return
        ledger.transition(proposalId, LedgerState.CANCELLED, error = reason)
        log.info("Gate {} cancelled by user ({})", proposalId, reason)
        pending.deferred.complete(GateOutcome.CancelledByUser)
    }

    // ── internals ───────────────────────────────────────────────────────────

    private fun nextStageAfterApproval(state: UiState): Stage {
        val needsVerbatim = state.fields.filter { it.requiresVerbatimVerification }
        val allConfirmed = needsVerbatim.all { state.verbatimConfirmed[it.id] == true }
        return if (needsVerbatim.isEmpty() || allConfirmed) Stage.READY else Stage.VERBATIM_VERIFY
    }

    private fun withPending(proposalId: String, transform: (Pending, UiState) -> UiState) {
        val pending = pendingRef.get() ?: return
        if (pending.proposalId != proposalId) return
        val current = _state.value ?: return
        if (current.proposalId != proposalId) return
        // Once EXECUTING, the real call is in flight - nothing about the
        // proposal is editable or cancellable any more (see cancel()'s own
        // matching guard for why cancel needs this too).
        if (current.stage == Stage.EXECUTING) return
        _state.value = transform(pending, current)
    }

    private fun looksLikeAddress(fieldId: String): Boolean = fieldId in setOf("to", "cc", "attendees")

    private fun addressShapeError(rawValue: String): String? {
        val addresses = splitAddresses(rawValue)
        if (addresses.isEmpty()) return "Enter at least one address."
        val bad = addresses.filterNot { EmailAddressValidator.isValid(it) }
        return if (bad.isEmpty()) null else "Not a valid address: ${bad.joinToString(", ")}"
    }

    private fun splitAddresses(value: String): List<String> =
        value.split(",", ";").map { it.trim() }.filter { it.isNotEmpty() }

    private fun applyField(proposal: Proposal, fieldId: String, value: String): Proposal = when (proposal) {
        is EmailProposal -> when (fieldId) {
            "to" -> proposal.copy(to = value.trim())
            "cc" -> proposal.copy(cc = splitAddresses(value))
            "subject" -> proposal.copy(subject = value)
            "body" -> proposal.copy(body = value)
            else -> proposal
        }
        is CalendarProposal -> when (fieldId) {
            "title" -> proposal.copy(title = value)
            "location" -> proposal.copy(location = value.ifBlank { null })
            "description" -> proposal.copy(description = value.ifBlank { null })
            "attendees" -> proposal.copy(attendees = splitAddresses(value))
            "start" -> parseLocal(value, proposal.zoneId)?.let { proposal.copy(start = it) } ?: proposal
            "end" -> parseLocal(value, proposal.zoneId)?.let { proposal.copy(end = it) } ?: proposal
            else -> proposal
        }
        // EC-Z15: an OrderProposal has no editable fields, by construction —
        // its content is a server-side cart, and changing it is a network
        // mutation this synchronous path cannot perform or report on.
        // `CommerceTools` registers no ProposalField for it, so nothing can
        // reach here; revision goes through requestRevision() instead.
        is OrderProposal -> proposal
    }

    /** `ProposalWindow`'s time fields are edited as local ISO strings (no offset). */
    private fun parseLocal(value: String, zoneId: String) =
        runCatching { LocalDateTime.parse(value).atZone(ZoneId.of(zoneId)).toInstant() }.getOrNull()

    /**
     * The ledger's payload column is audit-only — nothing in this plan ever
     * deserialises it back into a typed [Proposal] (EC-A9's reconciliation only
     * needs the *state*, not the payload). Hand-built JSON, matching
     * `VaultTools`/`ToolDispatcher`'s own style, rather than making [Proposal]
     * `@Serializable` just to route around `Instant` needing a custom
     * serialiser for a value nothing reads back.
     */
    private fun encode(proposal: Proposal): String = when (proposal) {
        is EmailProposal -> buildJsonObject {
            put("to", proposal.to)
            put("cc", JsonArray(proposal.cc.map { JsonPrimitive(it) }))
            put("subject", proposal.subject)
            put("body", proposal.body)
        }.toString()
        is CalendarProposal -> buildJsonObject {
            put("title", proposal.title)
            put("start", proposal.start.toString())
            put("end", proposal.end.toString())
            put("zone", proposal.zoneId)
            put("all_day", proposal.allDay)
            put("attendees", JsonArray(proposal.attendees.map { JsonPrimitive(it) }))
            proposal.location?.let { put("location", it) }
            proposal.description?.let { put("description", it) }
        }.toString()
        // R6/EC-Z6: the snapshot is the *server's* cart as shown to the user,
        // line by line, so the ledger records what they actually approved
        // rather than a total they would have to take on trust.
        is OrderProposal -> buildJsonObject {
            put("payment_method", proposal.paymentMethod)
            put("item_count", proposal.cart.itemCount)
            put("subtotal_paise", proposal.cart.subtotal.paise)
            put("delivery_fee_paise", proposal.cart.deliveryFee.paise)
            put("total_paise", proposal.cart.total.paise)
            put("cod_available", proposal.cart.codAvailable)
            put("over_ceiling", proposal.overCeiling)
            put("is_fake", proposal.isFake)
            put("lines", JsonArray(proposal.cart.lines.map { line ->
                buildJsonObject {
                    put("line_id", line.lineId)
                    put("product_id", line.productId)
                    put("name", line.name)
                    line.size?.let { put("size", it) }
                    put("unit_price_paise", line.unitPrice.paise)
                    put("quantity", line.quantity)
                    put("added_this_session", line.addedThisSession)
                }
            }))
            put("failed_items", JsonArray(proposal.failedItems.map {
                buildJsonObject { put("requested", it.requested); put("reason", it.reason) }
            }))
        }.toString()
    }
}

/** Shared by `EmailTools` and `CalendarTools` so the mapping lives in one place. */
fun ConfirmationGate.GateOutcome.toToolOutcome(): ToolOutcome = when (this) {
    is ConfirmationGate.GateOutcome.Executed -> ToolOutcome(
        buildJsonObject { put("sent", true); put("external_id", externalId) }.toString(),
    )
    is ConfirmationGate.GateOutcome.ExecutionFailed -> ToolOutcome(
        buildJsonObject { put("error", "execution_failed"); put("message", reason) }.toString(),
        isError = true,
    )
    is ConfirmationGate.GateOutcome.ExecutionUnknown -> ToolOutcome(
        buildJsonObject {
            put("status", "unknown")
            put("message", "This may or may not have gone through: $reason. Do not try again - tell the user to check manually.")
        }.toString(),
    )
    is ConfirmationGate.GateOutcome.NeedsReauth -> ToolOutcome(
        buildJsonObject { put("status", "needs_reauth"); put("message", reason) }.toString(),
    )
    ConfirmationGate.GateOutcome.CancelledByUser -> ToolOutcome(
        buildJsonObject { put("cancelled_by_user", true) }.toString(),
    )
    ConfirmationGate.GateOutcome.Busy -> ToolOutcome(
        buildJsonObject {
            put("gate_busy", true)
            put("message", "Another confirmation window is already open. Wait for the user to resolve it, then propose this again.")
        }.toString(),
    )
    // EC-Z14. Explicitly NOT an error and explicitly not `cancelled_by_user`:
    // both of those tell the model to stop, and this means the opposite. The
    // instruction is passed through verbatim rather than paraphrased, because
    // "make it two, not four" only survives if the numbers do.
    is ConfirmationGate.GateOutcome.RevisionRequested -> ToolOutcome(
        buildJsonObject {
            put("revision_requested", true)
            if (instruction != null) {
                put("user_said", instruction)
                put(
                    "next_step",
                    "The user wants changes before ordering. Do exactly what they said using the cart tools, " +
                        "then call commerce_propose_order again. Do not place anything.",
                )
            } else {
                put(
                    "next_step",
                    "The user wants to change something but did not say what. Ask them with ask_user, make the " +
                        "change with the cart tools, then call commerce_propose_order again.",
                )
            }
        }.toString(),
    )
}
