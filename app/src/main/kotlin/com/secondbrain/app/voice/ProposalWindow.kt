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
import com.secondbrain.model.EmailProposal
import com.secondbrain.model.FieldKind
import com.secondbrain.model.LedgerKind
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
                }

                current.conflictWarning?.let {
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
    when (state.stage) {
        ConfirmationGate.Stage.CONTENT_REVIEW -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { gate.cancel(state.proposalId) }) { Text("Cancel") }
            Button(
                onClick = { gate.confirmContent(state.proposalId) },
                enabled = state.validationError == null,
            ) { Text("Approve") }
        }

        ConfirmationGate.Stage.VERBATIM_VERIFY -> VerbatimStage(state, gate)

        ConfirmationGate.Stage.READY -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
            if (state.proposal.kind == LedgerKind.EMAIL_SEND) "Sending…" else "Creating…",
            style = MaterialTheme.typography.bodyMedium,
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
