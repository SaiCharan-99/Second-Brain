package com.secondbrain.app.vault

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.secondbrain.app.AppColors
import com.secondbrain.app.MonoTextStyle
import com.secondbrain.model.Backlink
import com.secondbrain.model.FolderDecision
import com.secondbrain.vault.VaultIndex

/**
 * WF-5, all three panes plus the two panels the design board calls out
 * separately: backlinks under the reader, and a collapsible Folder Guard audit
 * view ("You will need this to tune the thresholds" — ARCHITECTURE.md §7
 * Step 4).
 */
@Composable
fun VaultScreen(controller: VaultBrowserController, notePathToOpen: String?) {
    val state by controller.state.collectAsState()

    // A chip clicked on the Voice screen names a note to jump to. This is a
    // side effect (it mutates controller state and launches a coroutine), so
    // it belongs in LaunchedEffect, not the composable body directly —
    // composition itself must stay side-effect-free, since Compose is free to
    // re-run or discard a composition pass. Keyed on notePathToOpen so it
    // fires once per distinct navigation request, not every recomposition.
    LaunchedEffect(notePathToOpen) {
        if (notePathToOpen != null && controller.state.value.selectedNotePath != notePathToOpen) {
            controller.selectNote(notePathToOpen)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.weight(1f).fillMaxWidth()) {
            TreePane(state, controller, modifier = Modifier.widthIn(min = 200.dp, max = 260.dp).fillMaxHeight())
            VerticalDivider(color = AppColors.Border)
            ListPane(state, controller, modifier = Modifier.widthIn(min = 240.dp, max = 320.dp).fillMaxHeight())
            VerticalDivider(color = AppColors.Border)
            ReaderPane(state, controller, modifier = Modifier.weight(1f).fillMaxHeight())
        }
        HorizontalDivider(color = AppColors.Border)
        FolderGuardPanel(state.folderDecisions)
    }
}

// ── Pane 1: tree ─────────────────────────────────────────────────────────

@Composable
private fun TreePane(state: VaultBrowserController.UiState, controller: VaultBrowserController, modifier: Modifier) {
    val rows = remember(state.tree, state.expanded) { TreeFlatten.flatten(state.tree, state.expanded) }

    LazyColumn(modifier.background(AppColors.Canvas), contentPadding = PaddingValues(vertical = 8.dp)) {
        item {
            TreeRowContent(
                label = "All notes",
                depth = 0,
                count = state.tree.rollupNoteCount,
                dangling = state.tree.danglingCount,
                chevron = null,
                selected = state.selectedFolder == null,
                onClick = { controller.selectFolder(null) },
            )
        }
        items(rows, key = { it.node.path }) { row ->
            TreeRowContent(
                label = row.node.name,
                depth = row.depth + 1,
                count = row.node.rollupNoteCount,
                dangling = row.node.danglingCount,
                chevron = if (row.hasChildren) row.isExpanded else null,
                selected = state.selectedFolder == row.node.path,
                onClick = {
                    controller.selectFolder(row.node.path)
                    if (row.hasChildren) controller.toggleFolder(row.node.path)
                },
            )
        }
    }
}

/** @param chevron null = no children (a leaf folder); otherwise expanded/collapsed. */
@Composable
private fun TreeRowContent(
    label: String,
    depth: Int,
    count: Int,
    dangling: Int,
    chevron: Boolean?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) AppColors.Blue.copy(alpha = 0.10f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(start = 12.dp + (depth * 14).dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            when (chevron) { true -> "▾"; false -> "▸"; null -> " " },
            modifier = Modifier.width(14.dp),
            color = AppColors.Muted,
            fontSize = 11.sp,
        )
        Text(
            label,
            modifier = Modifier.weight(1f),
            color = if (selected) AppColors.Blue else AppColors.Ink,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
        )
        Text(count.toString(), style = MonoTextStyle, color = AppColors.Muted)
        if (dangling > 0) {
            Spacer(Modifier.width(4.dp))
            DanglingBadge(dangling)
        }
    }
}

@Composable
private fun DanglingBadge(count: Int) {
    Surface(color = AppColors.Dangling.copy(alpha = 0.15f), shape = CircleShape) {
        Text(
            count.toString(),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
            color = AppColors.Dangling,
            fontSize = 10.sp,
        )
    }
}

// ── Pane 2: list ─────────────────────────────────────────────────────────

@Composable
private fun ListPane(state: VaultBrowserController.UiState, controller: VaultBrowserController, modifier: Modifier) {
    Column(modifier) {
        Text(
            state.selectedFolder ?: "All notes",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            style = MaterialTheme.typography.labelLarge,
            color = AppColors.Muted,
        )
        HorizontalDivider(color = AppColors.Border)
        if (state.notes.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("No notes here yet.", color = AppColors.Muted, style = MaterialTheme.typography.bodySmall)
            }
            return@Column
        }
        LazyColumn {
            items(state.notes, key = { it.path }) { note ->
                NoteRow(note, selected = note.path == state.selectedNotePath) { controller.selectNote(note.path) }
            }
        }
    }
}

@Composable
private fun NoteRow(note: VaultIndex.IndexedNote, selected: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) AppColors.Blue.copy(alpha = 0.08f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(note.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = AppColors.Ink, maxLines = 1)
        if (note.summary.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(note.summary, style = MaterialTheme.typography.bodySmall, color = AppColors.Muted, maxLines = 1)
        }
        Spacer(Modifier.height(2.dp))
        Text(note.updatedAt.take(16).replace('T', ' '), style = MonoTextStyle.copy(fontSize = 10.sp), color = AppColors.Muted)
    }
}

// ── Pane 3: reader + backlinks ──────────────────────────────────────────

@Composable
private fun ReaderPane(state: VaultBrowserController.UiState, controller: VaultBrowserController, modifier: Modifier) {
    Box(modifier) {
        val note = state.note
        if (note == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Select a note to read it.", color = AppColors.Muted)
            }
            return@Box
        }

        // navigateWikilink reads controller.state itself at click time, so the
        // callback identity does not need to depend on it — only the text
        // actually being rendered should force a re-render.
        val annotatedBody = remember(note.bodyMarkdown, state.noteDanglingLinks) {
            NoteMarkdown.render(note.bodyMarkdown, state.noteDanglingLinks, controller::navigateWikilink)
        }

        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 20.dp, 20.dp, 72.dp)) {
            // One item, but many siblings within it — a LazyColumn item slot
            // does not itself arrange multiple children vertically (that is
            // what Box's default behaviour does with an unconstrained group of
            // measurables), so this needs its own explicit Column exactly as
            // any other multi-child spot would.
            item {
                Column {
                    Text(note.title, style = MaterialTheme.typography.headlineMedium, color = AppColors.Ink)
                    Spacer(Modifier.height(4.dp))
                    Text(note.path, style = MonoTextStyle, color = AppColors.Muted)
                    if (note.movedFrom.isNotEmpty()) {
                        Text(
                            "moved from " + note.movedFrom.joinToString(" → "),
                            style = MonoTextStyle.copy(fontSize = 10.sp),
                            color = AppColors.Muted,
                        )
                    }
                    if (note.tags.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            note.tags.forEach { tag ->
                                Surface(color = AppColors.Ink.copy(alpha = 0.06f), shape = RoundedCornerShape(4.dp)) {
                                    Text(tag, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MonoTextStyle, color = AppColors.Ink)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = annotatedBody,
                        style = MaterialTheme.typography.bodyMedium.copy(color = AppColors.Ink, lineHeight = 22.sp),
                    )
                    Spacer(Modifier.height(28.dp))
                    HorizontalDivider(color = AppColors.Border)
                    Spacer(Modifier.height(12.dp))
                    BacklinksPanel(state.backlinks, controller)
                }
            }
        }

        state.pendingStub?.let { pending -> PendingStubBanner(pending, controller, Modifier.align(Alignment.BottomCenter)) }
    }
}

@Composable
private fun BacklinksPanel(backlinks: List<Backlink>, controller: VaultBrowserController) {
    // Self-contained Column rather than relying on the caller's layout to
    // stack these siblings — see the comment at this function's only call
    // site for why that matters.
    Column {
        Text("Backlinks", style = MaterialTheme.typography.labelLarge, color = AppColors.Muted)
        Spacer(Modifier.height(6.dp))
        if (backlinks.isEmpty()) {
            Text("Nothing links here yet.", style = MaterialTheme.typography.bodySmall, color = AppColors.Muted)
            return@Column
        }
        backlinks.forEach { link ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { controller.selectNote(link.fromPath) }
                    .padding(vertical = 6.dp),
            ) {
                Text(link.fromTitle, style = MaterialTheme.typography.bodyMedium, color = AppColors.Blue)
                if (link.context.isNotBlank()) {
                    Text("…${link.context}…", style = MaterialTheme.typography.bodySmall, color = AppColors.Muted, maxLines = 2)
                }
            }
        }
    }
}

/** WF-5: "click dangling link → Create stub note in the same folder." */
@Composable
private fun PendingStubBanner(pending: VaultBrowserController.PendingStub, controller: VaultBrowserController, modifier: Modifier) {
    Surface(
        modifier = modifier.padding(16.dp),
        color = AppColors.Ink,
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "'${NoteMarkdown.displayTarget(pending.rawTarget)}' has no note yet.",
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.width(12.dp))
            TextButton(onClick = controller::confirmCreateStub) {
                // A lighter tint than AppColors.Blue: this button sits on the
                // dark Ink surface above, not the light canvas the rest of the
                // palette assumes.
                Text("Create stub in ${pending.folder}", color = Color(0xA8, 0xC4, 0xFF))
            }
            TextButton(onClick = controller::dismissPendingStub) {
                Text("Dismiss", color = Color.White.copy(alpha = 0.7f))
            }
        }
    }
}

// ── Folder Guard audit panel ────────────────────────────────────────────

/** D-007: "You will need this to tune the thresholds." Collapsed by default. */
@Composable
private fun FolderGuardPanel(decisions: List<FolderDecision>) {
    var expanded by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().background(AppColors.Canvas)) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (expanded) "▾" else "▸", color = AppColors.Muted, fontSize = 11.sp, modifier = Modifier.width(16.dp))
            Text(
                "Folder Guard decisions (${decisions.size})",
                style = MaterialTheme.typography.labelMedium,
                color = AppColors.Muted,
            )
        }
        if (expanded) {
            HorizontalDivider(color = AppColors.Border)
            LazyColumn(Modifier.fillMaxWidth().height(160.dp), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 6.dp)) {
                items(decisions, key = { it.id }) { d -> FolderDecisionRow(d) }
            }
        }
    }
}

@Composable
private fun FolderDecisionRow(d: FolderDecision) {
    // d.verdict is the String column AppDb writes (FolderVerdict.RejectReason
    // stringified, or "ACCEPTED") — a plain equality check is all a two-colour
    // audit row needs; see FolderVerdict.RejectReason for the full enum this
    // widens away from.
    val verdictColor = if (d.verdict == "ACCEPTED") AppColors.Green else AppColors.Dangling
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(d.proposed, style = MonoTextStyle, color = AppColors.Ink, modifier = Modifier.weight(1f), maxLines = 1)
        Text(d.verdict, style = MonoTextStyle, color = verdictColor, modifier = Modifier.width(150.dp))
        Text(d.matched.orEmpty(), style = MonoTextStyle, color = AppColors.Muted, modifier = Modifier.weight(1f), maxLines = 1)
        Text(d.score?.let { "%.2f".format(it) }.orEmpty(), style = MonoTextStyle, color = AppColors.Muted, modifier = Modifier.width(50.dp))
    }
}
