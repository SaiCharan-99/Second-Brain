package com.secondbrain.app.voice

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.secondbrain.agent.ConfirmationGate
import com.secondbrain.app.AppColors
import com.secondbrain.app.MonoTextStyle
import com.secondbrain.model.CalendarProposal
import com.secondbrain.model.CartLine
import com.secondbrain.model.EmailProposal
import com.secondbrain.model.FieldKind
import com.secondbrain.model.LedgerKind
import com.secondbrain.model.OrderProposal
import com.secondbrain.model.ProposalField
import com.secondbrain.ports.CalendarPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * WF-2/WF-3: "same gate machinery, different payload." One composable, two
 * field layouts. Overlays whichever screen is showing (see `App.kt`) because a
 * proposal can be open regardless of which one the user is looking at.
 *
 * R9 (decision 5 of the Step 5/6 plan): every button here is a click. Nothing
 * in this file accepts a spoken "yes" — the confirmation-click exception is
 * exactly this UI.
 */
@Composable
fun ProposalWindow(
    gate: ConfirmationGate,
    /** Optional: enables live conflict recompute on a calendar time edit (EC-C4). Null is fine — just skips the recompute. */
    calendarPort: CalendarPort? = null,
) {
    val pending by gate.state.collectAsState()
    val current = pending ?: return
    val scope = rememberCoroutineScope()

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 520.dp).padding(24.dp),
            color = AppColors.Surface,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, AppColors.BorderStrong),
        ) {
            Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState())) {
                Header(current)
                Spacer(Modifier.height(16.dp))

                when (val proposal = current.proposal) {
                    is EmailProposal -> EmailBody(current, gate)
                    is CalendarProposal -> CalendarBody(current, proposal, gate, calendarPort, scope)
                    is OrderProposal -> OrderBody(proposal)
                }

                current.conflictWarning?.let {
                    Spacer(Modifier.height(10.dp))
                    Banner(it, AppColors.Amber)
                }
                // EC-E3. Amber, not red, and it does not disable Confirm — the
                // draft is intact and pressing Confirm again after signing in
                // is exactly what the user is being asked to do.
                current.reauthNotice?.let {
                    Spacer(Modifier.height(10.dp))
                    Banner(it, AppColors.Amber)
                }
                current.validationError?.let {
                    Spacer(Modifier.height(10.dp))
                    Banner(it, AppColors.Dangling)
                }

                Spacer(Modifier.height(18.dp))
                HorizontalDivider(color = AppColors.Border)
                Spacer(Modifier.height(14.dp))
                Actions(current, gate, scope)
            }
        }
    }
}

@Composable
private fun Header(state: ConfirmationGate.UiState) {
    val label = when (state.proposal.kind) {
        LedgerKind.EMAIL_SEND -> "EMAIL — pending your approval"
        LedgerKind.CALENDAR_CREATE -> "CALENDAR EVENT — pending your approval"
        LedgerKind.ORDER_PLACE -> "ORDER — pending your approval"
    }
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge, color = AppColors.Blue, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(state.proposal.speechSummary, style = MaterialTheme.typography.bodyMedium, color = AppColors.Ink)
    }
}

@Composable
private fun Banner(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.10f), shape = RoundedCornerShape(8.dp)) {
        Text(text, modifier = Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall, color = color)
    }
}

// ── Email ────────────────────────────────────────────────────────────────

@Composable
private fun EmailBody(state: ConfirmationGate.UiState, gate: ConfirmationGate) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        field(state, "to") { VerbatimAwareField(it, gate, state.proposalId, state.stage) }
        state.fields.firstOrNull { it.id == "cc" }?.let { VerbatimAwareField(it, gate, state.proposalId, state.stage) }
        field(state, "subject") { EditableRow(it, gate, state.proposalId) }
        field(state, "body") { EditableRow(it, gate, state.proposalId, singleLine = false) }
    }
}

// ── Calendar ─────────────────────────────────────────────────────────────

@Composable
private fun CalendarBody(
    state: ConfirmationGate.UiState,
    proposal: CalendarProposal,
    gate: ConfirmationGate,
    calendarPort: CalendarPort?,
    scope: CoroutineScope,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (proposal.allDay) {
            Banner("All day — no specific time was given. Say so now if that's wrong.", AppColors.Muted)
        }
        field(state, "title") { EditableRow(it, gate, state.proposalId) }
        field(state, "start") {
            VerbatimAwareField(it, gate, state.proposalId, state.stage, onChanged = { recomputeConflicts(gate, state.proposalId, calendarPort, scope) })
        }
        field(state, "end") {
            VerbatimAwareField(it, gate, state.proposalId, state.stage, onChanged = { recomputeConflicts(gate, state.proposalId, calendarPort, scope) })
        }
        state.fields.firstOrNull { it.id == "location" }?.let { EditableRow(it, gate, state.proposalId) }
        state.fields.firstOrNull { it.id == "description" }?.let { EditableRow(it, gate, state.proposalId, singleLine = false) }
        state.fields.firstOrNull { it.id == "attendees" }?.let { VerbatimAwareField(it, gate, state.proposalId, state.stage) }
    }
}

// ── Order ────────────────────────────────────────────────────────────────

/**
 * WF-4's order window: *"full itemised cart, pre-existing items flagged, failed
 * items shown **above** the total."*
 *
 * Read-only, deliberately, and this is the one proposal type where that is a
 * design decision rather than a shortcut (EC-Z15). The other two edit a local
 * payload; a cart line lives on the server, so changing one is a network call
 * that can be refused. Editing it here would mean a text field that sometimes
 * silently fails. Instead the user says what they want changed — the mic stays
 * live while this is open — and the model does it with the cart tools and
 * proposes again. See [ConfirmationGate.GateOutcome.RevisionRequested].
 */
@Composable
private fun OrderBody(proposal: OrderProposal) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {

        // Step 7's exit criterion: "a demo toggle... visibly labelled in the UI
        // when the fake is active. Never demo a fake without saying so."
        if (proposal.isFake) {
            Banner("DEMO CATALOGUE — this is not a real order and nothing will be delivered.", AppColors.Amber)
        }

        // EC-Z10: above the total, always. A failure the user only notices
        // after they have read the price is a failure they did not notice.
        if (proposal.failedItems.isNotEmpty()) {
            Surface(
                color = AppColors.Dangling.copy(alpha = 0.10f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(10.dp)) {
                    Text(
                        "NOT IN THIS ORDER",
                        style = MaterialTheme.typography.labelSmall,
                        color = AppColors.Dangling,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    proposal.failedItems.forEach {
                        Text(
                            "${it.requested} — ${it.reason}",
                            style = MaterialTheme.typography.bodySmall,
                            color = AppColors.Ink,
                        )
                    }
                }
            }
        }

        proposal.cart.lines.forEach { line -> CartLineRow(line) }

        HorizontalDivider(color = AppColors.Border)

        TotalRow("Subtotal", proposal.cart.subtotal.format())
        if (proposal.cart.deliveryFee.paise > 0) {
            TotalRow("Delivery", proposal.cart.deliveryFee.format())
        }
        TotalRow("Total", proposal.cart.total.format(), emphasise = true)

        Text(
            proposal.paymentMethod,
            style = MaterialTheme.typography.bodySmall,
            color = AppColors.Muted,
        )

        // EC-Z17. Not a block — a large order can be legitimate — but it must
        // not be possible to approve one without having been told.
        if (proposal.overCeiling) {
            Banner(
                "This is over your usual limit of ${proposal.ceiling?.format() ?: "—"}. " +
                    "Check the quantities before you place it.",
                AppColors.Red,
            )
        }
    }
}

@Composable
private fun CartLineRow(line: CartLine) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            Text(line.name, style = MaterialTheme.typography.bodyMedium, color = AppColors.Ink)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    buildString {
                        line.size?.let { append(it).append("  ·  ") }
                        append("×${line.quantity}")
                        append("  ·  ").append(line.unitPrice.format()).append(" each")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.Muted,
                )
            }
            // EC-Z7: a line that was in the cart before this session started
            // must not read as something we just added on their behalf.
            if (!line.addedThisSession) {
                Text(
                    "was already in your cart",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppColors.Amber,
                )
            }
        }
        Text(line.lineTotal.format(), style = MonoTextStyle, color = AppColors.Ink)
    }
}

@Composable
private fun TotalRow(label: String, value: String, emphasise: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (emphasise) AppColors.Ink else AppColors.Muted,
            fontWeight = if (emphasise) FontWeight.SemiBold else FontWeight.Normal,
        )
        Text(
            value,
            style = MonoTextStyle,
            color = AppColors.Ink,
            fontWeight = if (emphasise) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/** EC-C4: recompute after every start/end edit, never blocking the edit itself. */
private fun recomputeConflicts(gate: ConfirmationGate, proposalId: String, calendarPort: CalendarPort?, scope: CoroutineScope) {
    if (calendarPort == null) return
    scope.launch {
        val current = gate.state.value ?: return@launch
        val proposal = current.proposal as? CalendarProposal ?: return@launch
        val busy = calendarPort.findBusy(proposal.start, proposal.end)
        val warning = if (busy.isEmpty()) null else "Overlaps ${busy.size} existing event(s)."
        gate.setConflictWarning(proposalId, warning)
    }
}

@Composable
private fun field(state: ConfirmationGate.UiState, id: String, content: @Composable (ProposalField) -> Unit) {
    state.fields.firstOrNull { it.id == id }?.let { content(it) }
}

/**
 * A field that is freely editable during [ConfirmationGate.Stage.CONTENT_REVIEW]
 * but, once approved, is shown read-only in the main body — its own dedicated
 * confirm/retype card in [VerbatimStage] is the only way to change it from
 * there on. Editing both here and in that card at once would be confusing and
 * would fight over which edit wins.
 */
@Composable
private fun VerbatimAwareField(
    field: ProposalField,
    gate: ConfirmationGate,
    proposalId: String,
    stage: ConfirmationGate.Stage,
    onChanged: () -> Unit = {},
) {
    if (stage == ConfirmationGate.Stage.CONTENT_REVIEW) {
        EditableRow(field, gate, proposalId, onChanged = onChanged)
    } else {
        ReadOnlyRow(field)
    }
}

@Composable
private fun ReadOnlyRow(field: ProposalField) {
    Column {
        Text(field.label, style = MaterialTheme.typography.labelSmall, color = AppColors.Muted)
        Text(field.value.ifBlank { "—" }, style = MonoTextStyle, color = AppColors.Ink)
    }
}

/** A [FieldKind.CONTENT] field: always freely editable, at every stage (an edit resets approval, per R6). */
@Composable
private fun EditableRow(
    field: ProposalField,
    gate: ConfirmationGate,
    proposalId: String,
    singleLine: Boolean = true,
    onChanged: () -> Unit = {},
) {
    OutlinedTextField(
        value = field.value,
        onValueChange = {
            gate.editField(proposalId, field.id, it)
            onChanged()
        },
        label = { Text(field.label) },
        singleLine = singleLine,
        textStyle = if (field.kind == FieldKind.VERBATIM) MonoTextStyle else MaterialTheme.typography.bodyMedium,
        modifier = Modifier.fillMaxWidth(),
    )
}

// ── Stage-specific actions ──────────────────────────────────────────────

@Composable
private fun Actions(state: ConfirmationGate.UiState, gate: ConfirmationGate, scope: CoroutineScope) {
    val isOrder = state.proposal.kind == LedgerKind.ORDER_PLACE

    when (state.stage) {
        // An order has nothing to review in two stages - no verbatim fields, no
        // editable content - so it goes straight to the committing button
        // rather than making the user press Approve and then Place order.
        ConfirmationGate.Stage.CONTENT_REVIEW -> if (isOrder) {
            OrderActions(state, gate, scope)
        } else Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { gate.cancel(state.proposalId) }) { Text("Cancel") }
            Button(
                onClick = { gate.confirmContent(state.proposalId) },
                enabled = state.validationError == null,
            ) { Text("Approve") }
        }

        ConfirmationGate.Stage.VERBATIM_VERIFY -> VerbatimStage(state, gate)

        ConfirmationGate.Stage.READY -> if (isOrder) {
            OrderActions(state, gate, scope)
        } else Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { gate.cancel(state.proposalId) }) { Text("Cancel") }
            Button(
                onClick = { scope.launch { gate.confirmExecute(state.proposalId) } },
                enabled = state.validationError == null,
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Red, contentColor = Color.White),
            ) {
                Text(if (state.proposal.kind == LedgerKind.EMAIL_SEND) "Send" else "Create event")
            }
        }

        ConfirmationGate.Stage.EXECUTING -> Text(
            when (state.proposal.kind) {
                LedgerKind.EMAIL_SEND -> "Sending…"
                LedgerKind.CALENDAR_CREATE -> "Creating…"
                LedgerKind.ORDER_PLACE -> "Placing your order…"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = AppColors.Muted,
        )
    }
}

/**
 * The order window's three buttons, and the reason there are three.
 *
 * "Change something" is EC-Z14's click-driven half. The spoken half — saying
 * *"drop the milk"* while this window is open — reaches the same
 * [ConfirmationGate.requestRevision] through `VoiceController`, carrying the
 * words with it. This button is the fallback for when the user would rather
 * click than talk, and it asks the model to ask them what to change.
 *
 * Placing the order stays a click, always. R9's second sanctioned exception is
 * "one button press per irreversible action", and this is that press — speech
 * can open a revision but never commits money.
 */
@Composable
private fun OrderActions(state: ConfirmationGate.UiState, gate: ConfirmationGate, scope: CoroutineScope) {
    val order = state.proposal as? OrderProposal
    var ceilingAcknowledged by remember(state.proposalId) { mutableStateOf(false) }
    val needsAcknowledgement = order?.overCeiling == true && !ceilingAcknowledged

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // EC-Z17: over the ceiling, the commit button is gated behind an
        // explicit "yes, I meant that much" rather than merely a warning above
        // a button that is still one click away.
        if (needsAcknowledgement) {
            OutlinedButton(
                onClick = { ceilingAcknowledged = true },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("I've checked the quantities — this total is right") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { gate.cancel(state.proposalId) }) { Text("Cancel") }
            OutlinedButton(onClick = { gate.requestRevision(state.proposalId) }) { Text("Change something") }
            Button(
                onClick = { scope.launch { gate.confirmExecute(state.proposalId) } },
                enabled = state.validationError == null && !needsAcknowledgement,
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Red, contentColor = Color.White),
            ) { Text("Place order") }
        }

        Text(
            "Or just say what you'd like changed.",
            style = MaterialTheme.typography.labelSmall,
            color = AppColors.Muted,
        )
    }
}

/**
 * EC-E1/E5: each verbatim field (an address, or two) is spelled back and
 * confirmed independently. "Sounds right" confirms; "Let me retype it" opens
 * an inline correction field — D-008's typing-to-correct pattern, applied per
 * field rather than through the general `request_typed_input` tool, since the
 * user is already looking straight at this window.
 */
@Composable
private fun VerbatimStage(state: ConfirmationGate.UiState, gate: ConfirmationGate) {
    val pendingFields = state.fields.filter { it.requiresVerbatimVerification && state.verbatimConfirmed[it.id] != true }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Confirm each address before this goes ahead:",
            style = MaterialTheme.typography.labelMedium,
            color = AppColors.Muted,
        )
        pendingFields.forEach { f -> VerbatimField(f, state, gate) }
    }
}

@Composable
private fun VerbatimField(field: ProposalField, state: ConfirmationGate.UiState, gate: ConfirmationGate) {
    var editing by remember(field.id) { mutableStateOf(false) }
    var draft by remember(field.id) { mutableStateOf(field.value) }

    Column {
        Text(field.label, style = MaterialTheme.typography.labelSmall, color = AppColors.Muted)
        if (editing) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                singleLine = true,
                textStyle = MonoTextStyle,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { editing = false; draft = field.value }) { Text("Cancel") }
                Button(onClick = {
                    gate.retypeVerbatim(state.proposalId, field.id, draft)
                    editing = false
                }) { Text("Save") }
            }
        } else {
            Text(field.value, style = MonoTextStyle, color = AppColors.Ink)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { editing = true; draft = field.value }) { Text("Let me retype it") }
                Button(onClick = { gate.confirmVerbatim(state.proposalId, field.id) }) { Text("Sounds right") }
            }
        }
    }
}
