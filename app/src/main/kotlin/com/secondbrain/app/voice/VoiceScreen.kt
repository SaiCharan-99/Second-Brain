package com.secondbrain.app.voice

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.secondbrain.app.AppColors
import com.secondbrain.app.MonoTextStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Voice screen: WF-1 end to end, seen. Large hold-to-talk control, a state
 * indicator prominent enough that "the user must never wonder whether it heard
 * them" (ARCHITECTURE.md §7 Step 4), a scrolling transcript, and the session
 * cost readout in the status bar rather than the main canvas — the design
 * board's own call, "the session cost meter sits in the status bar rather than
 * the main canvas."
 *
 * All state and every side effect live in [VoiceController]; this function
 * only renders [VoiceController.state] and forwards gestures to it.
 */
@Composable
fun VoiceScreen(controller: VoiceController, onOpenNote: (String) -> Unit) {
    val state by controller.state.collectAsState()
    val focusRequester = remember { FocusRequester() }
    var spaceDown by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusTarget()
            .onPreviewKeyEvent { event ->
                if (event.key != Key.Spacebar) return@onPreviewKeyEvent false
                when (event.type) {
                    KeyEventType.KeyDown -> {
                        // EC-V3: any keypress during SPEAKING cuts audio first.
                        if (state.micState == VoiceController.MicState.SPEAKING) controller.onBargeIn()
                        // Key repeat fires KeyDown ~30x/sec while held (D-019's
                        // same finding, now in Compose Desktop's AWT-backed
                        // event source) — this flag is what makes hold-to-talk
                        // actually mean "once", not "restart repeatedly".
                        if (!spaceDown) { spaceDown = true; controller.onTalkDown() }
                        true
                    }
                    KeyEventType.KeyUp -> {
                        if (spaceDown) { spaceDown = false; controller.onTalkUp() }
                        true
                    }
                    else -> false
                }
            },
    ) {
        StatusBar(state, controller)
        HorizontalDivider(color = AppColors.Border)

        TranscriptLog(
            lines = state.lines,
            onOpenNote = onOpenNote,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )

        HorizontalDivider(color = AppColors.Border)
        MicPanel(state = state, controller = controller)
    }
}

@Composable
private fun StatusBar(state: VoiceController.UiState, controller: VoiceController) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PhaseChip(state.phase.name)
            Spacer(Modifier.width(8.dp))
            Text(
                "TURN " + state.turnLabel,
                style = MonoTextStyle,
                color = AppColors.Muted,
            )
        }
        Text(
            state.statusLine.ifBlank { state.micDeviceLabel },
            style = MaterialTheme.typography.bodySmall,
            color = AppColors.Muted,
            modifier = Modifier.weight(1f),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // Step 8 / D-082 gap 1: only rendered when commerce is live
            // against the real Zepto MCP (commerceLive) — FakeCommerceAdapter
            // and commerce.enabled=false both mean this chip never appears.
            if (state.commerceLive && !state.commerceSignedIn) {
                CommerceSignInChip(controller)
            }
            Text(state.sessionCostLabel, style = MonoTextStyle, color = AppColors.Muted)
        }
    }
}

@Composable
private fun CommerceSignInChip(controller: VoiceController) {
    AssistChip(
        onClick = { controller.signInToCommerce() },
        label = { Text("Sign in to Zepto") },
        colors = AssistChipDefaults.assistChipColors(labelColor = AppColors.Amber),
        border = BorderStroke(1.dp, AppColors.Amber.copy(alpha = 0.4f)),
    )
}

/**
 * "Reads as a small chip next to the state because a hard context reset is
 * invisible otherwise" — the design board's own reasoning for surfacing R8's
 * phase boundary at all, even though only CAPTURE is reachable before Step 5.
 */
@Composable
private fun PhaseChip(label: String) {
    Surface(
        color = AppColors.Ink.copy(alpha = 0.06f),
        shape = RoundedCornerShape(4.dp),
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MonoTextStyle,
            color = AppColors.Ink,
        )
    }
}

@Composable
private fun TranscriptLog(
    lines: List<VoiceController.TranscriptLine>,
    onOpenNote: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
    }

    if (lines.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                "Hold the mic or press and hold Space, then speak.",
                color = AppColors.Muted,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(vertical = 12.dp, horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(lines, key = { it.id }) { line -> TranscriptRow(line, onOpenNote) }
    }
}

@Composable
private fun TranscriptRow(line: VoiceController.TranscriptLine, onOpenNote: (String) -> Unit) {
    val (label, labelColor, align) = when (line.speaker) {
        VoiceController.Speaker.USER -> Triple("YOU", AppColors.Blue, Alignment.End)
        VoiceController.Speaker.ASSISTANT -> Triple("SECOND BRAIN", AppColors.Green, Alignment.Start)
        VoiceController.Speaker.SYSTEM -> Triple("", AppColors.Muted, Alignment.Start)
    }

    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = align) {
        if (label.isNotEmpty()) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = labelColor,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Surface(
            color = if (line.speaker == VoiceController.Speaker.SYSTEM) Color.Transparent else AppColors.Surface,
            shape = RoundedCornerShape(10.dp),
            border = if (line.speaker == VoiceController.Speaker.SYSTEM) null
                else BorderStroke(1.dp, AppColors.Border),
        ) {
            Text(
                line.text,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                color = if (line.isError) AppColors.Dangling else if (line.speaker == VoiceController.Speaker.SYSTEM) AppColors.Muted else AppColors.Ink,
                style = if (line.speaker == VoiceController.Speaker.SYSTEM) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
            )
        }
        if (line.notePaths.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                line.notePaths.forEach { path ->
                    AssistChip(
                        onClick = { onOpenNote(path) },
                        label = { Text(noteLabel(path)) },
                        colors = AssistChipDefaults.assistChipColors(labelColor = AppColors.Blue),
                    )
                }
            }
        }
    }
}

private fun noteLabel(path: String): String =
    "Open " + path.substringAfterLast('/').removeSuffix(".md")

@Composable
private fun MicPanel(state: VoiceController.UiState, controller: VoiceController) {
    val scope = rememberCoroutineScope()
    val color = when (state.micState) {
        VoiceController.MicState.IDLE -> AppColors.Ink
        VoiceController.MicState.LISTENING -> AppColors.Blue
        VoiceController.MicState.THINKING -> AppColors.Amber
        VoiceController.MicState.SPEAKING -> AppColors.Green
        VoiceController.MicState.AWAITING_CONFIRMATION -> AppColors.Amber
    }
    val label = when (state.micState) {
        VoiceController.MicState.IDLE -> "Idle"
        VoiceController.MicState.LISTENING -> "Listening"
        VoiceController.MicState.THINKING -> "Thinking"
        VoiceController.MicState.SPEAKING -> "Speaking"
        VoiceController.MicState.AWAITING_CONFIRMATION -> "Waiting for you"
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Step 8/WF-6.
        ImageCaptureRow(state, controller)
        Spacer(Modifier.height(10.dp))

        Text(label, style = MaterialTheme.typography.headlineMedium, color = color)
        Spacer(Modifier.height(4.dp))
        Text(
            when {
                state.pendingImageLabel != null -> "Hold to add a caption, or send without one"
                state.awaitingAnswer -> "Your turn — hold to answer"
                state.micState == VoiceController.MicState.AWAITING_CONFIRMATION ->
                    "Resolve the window above, or hold to talk about something else"
                else -> "Hold to talk — release to send"
            },
            style = MaterialTheme.typography.bodySmall,
            color = AppColors.Muted,
        )
        Spacer(Modifier.height(16.dp))
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(color)
                .border(2.dp, color.copy(alpha = 0.4f), CircleShape)
                .pointerInput(controller) {
                    awaitEachGesture {
                        awaitFirstDown()
                        scope.launch {
                            if (state.micState == VoiceController.MicState.SPEAKING) controller.onBargeIn()
                            controller.onTalkDown()
                        }
                        waitForUpOrCancellation()
                        controller.onTalkUp()
                    }
                },
        )
    }
}

/**
 * Step 8 / WF-6: attach a photo (a grocery list, notes, a product) to the
 * next turn. [ImageIntake.pickFile] opens a native, blocking dialog, so it
 * runs on `Dispatchers.IO` via [rememberCoroutineScope] rather than the
 * Compose/UI dispatcher — the same discipline every other blocking call in
 * this codebase follows.
 *
 * Once a photo is attached, this becomes a small confirmation row instead:
 * the filename, a way to send it with no caption, and a way to drop it.
 */
@Composable
private fun ImageCaptureRow(state: VoiceController.UiState, controller: VoiceController) {
    val scope = rememberCoroutineScope()

    if (state.pendingImageLabel == null) {
        AssistChip(
            onClick = {
                scope.launch {
                    val path = withContext(Dispatchers.IO) { ImageIntake.pickFile() }
                    if (path != null) controller.attachImage(path)
                }
            },
            label = { Text("Attach a photo") },
            colors = AssistChipDefaults.assistChipColors(labelColor = AppColors.Blue),
        )
        return
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(color = AppColors.Ink.copy(alpha = 0.06f), shape = RoundedCornerShape(4.dp)) {
            Text(
                state.pendingImageLabel,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                style = MonoTextStyle,
                color = AppColors.Ink,
            )
        }
        AssistChip(onClick = { controller.sendPendingImage() }, label = { Text("Send") })
        AssistChip(onClick = { controller.clearPendingImage() }, label = { Text("Remove") })
    }
}
