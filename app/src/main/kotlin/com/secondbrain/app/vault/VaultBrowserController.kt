package com.secondbrain.app.vault

import com.secondbrain.model.Backlink
import com.secondbrain.model.FolderDecision
import com.secondbrain.model.Note
import com.secondbrain.model.TreeNode
import com.secondbrain.ports.WriteResult
import com.secondbrain.vault.Vault
import com.secondbrain.vault.VaultIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * State and queries behind WF-5's three panes plus the Folder Guard audit
 * panel. `:app` is the one module allowed to see the concrete [Vault] type
 * rather than the [com.secondbrain.ports.VaultStore] port — this is exactly
 * the "dashboard-only queries" surface that type comment describes.
 *
 * Every query here rides the same SQLite connections `:vault`'s own
 * `FileWatcher` reindex path already shares with the app's writes — D-038's
 * WAL-plus-`busy_timeout` mitigation is what makes that safe "without any
 * application-level coordination" at this scale, and a third concurrent
 * reader (this controller) is the same kind of access, not a new kind.
 */
class VaultBrowserController(
    private val scope: CoroutineScope,
    private val vault: Vault,
) {
    private val log = LoggerFactory.getLogger(VaultBrowserController::class.java)

    /** Offered inline when the reader shows a `[[dangling link]]` (WF-5). */
    data class PendingStub(val fromPath: String, val rawTarget: String, val folder: String)

    data class UiState(
        val tree: TreeNode = TreeNode("", "vault", 0, 0, 0, 0),
        val expanded: Set<String> = emptySet(),
        /** null selects the pinned "All notes" pseudo-folder, not "nothing". */
        val selectedFolder: String? = null,
        val notes: List<VaultIndex.IndexedNote> = emptyList(),
        val selectedNotePath: String? = null,
        val note: Note? = null,
        /** This note's outbound [[wikilinks]] that resolved: rawTarget -> path. */
        val noteResolvedLinks: Map<String, String> = emptyMap(),
        /** This note's outbound [[wikilinks]] that did not. */
        val noteDanglingLinks: Set<String> = emptySet(),
        val backlinks: List<Backlink> = emptyList(),
        val folderDecisions: List<FolderDecision> = emptyList(),
        val pendingStub: PendingStub? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refresh()
        // ARCHITECTURE.md §7 Step 4: "FileWatcher → UI state flow, so a capture
        // on screen 1 appears on screen 2 with no restart." vault.changes is
        // that flow (Vault.kt's own doc explains why it carries no payload).
        scope.launch { vault.changes.collect { refresh() } }
    }

    // ── tree ────────────────────────────────────────────────────────────────

    fun toggleFolder(path: String) {
        _state.update { s -> s.copy(expanded = if (path in s.expanded) s.expanded - path else s.expanded + path) }
    }

    fun selectFolder(path: String?) {
        _state.update { it.copy(selectedFolder = path) }
        scope.launch(Dispatchers.IO) { loadNotes() }
    }

    // ── list / reader ───────────────────────────────────────────────────────

    fun selectNote(path: String) {
        _state.update { it.copy(selectedNotePath = path, pendingStub = null) }
        scope.launch(Dispatchers.IO) { loadNote(path) }
    }

    /**
     * A `[[wikilink]]` was clicked in the reader. Resolved -> navigate there
     * and sync the tree/list selection (WF-5's "automatically redirect").
     * Dangling -> offer the inline "Create stub" affordance instead of doing
     * nothing, per WF-5's own dangling-link branch.
     */
    fun navigateWikilink(rawTarget: String) {
        val current = _state.value
        val resolvedPath = current.noteResolvedLinks[rawTarget]
        if (resolvedPath != null) {
            selectNote(resolvedPath)
            return
        }
        val fromPath = current.selectedNotePath ?: return
        val folder = fromPath.substringBeforeLast('/', missingDelimiterValue = "Inbox")
        _state.update { it.copy(pendingStub = PendingStub(fromPath, rawTarget, folder)) }
    }

    fun dismissPendingStub() {
        _state.update { it.copy(pendingStub = null) }
    }

    fun confirmCreateStub() {
        val pending = _state.value.pendingStub ?: return
        _state.update { it.copy(pendingStub = null) }
        scope.launch(Dispatchers.IO) {
            when (val result = vault.createStub(pending.fromPath, pending.rawTarget)) {
                is WriteResult.Written -> selectNote(result.path) // vault.changes drives the tree/list refresh
                is WriteResult.Rejected -> log.warn("Stub creation for '{}' rejected: {}", pending.rawTarget, result.detail)
            }
        }
    }

    // ── refresh ─────────────────────────────────────────────────────────────

    /** Re-queries everything the current selection needs. Cheap at this scale (D-042). */
    fun refresh() {
        scope.launch(Dispatchers.IO) {
            val tree = vault.index.tree(maxDepth = Int.MAX_VALUE) // the dashboard wants the whole tree, not EC-A5's LLM-context cap
            val decisions = vault.folderDecisions(30)
            _state.update { it.copy(tree = tree, folderDecisions = decisions) }
            loadNotes()
            _state.value.selectedNotePath?.let { loadNote(it) }
        }
    }

    private fun loadNotes() {
        val folder = _state.value.selectedFolder
        val notes = if (folder == null) {
            // D-035: paths compare case-insensitively but sort here is by time.
            // Instant.parse, not a raw string compare — Instant.toString() omits
            // trailing zero fractional digits, so two timestamps in the same
            // second can compare out of order as plain strings.
            vault.index.allNotes().sortedByDescending { row ->
                runCatching { Instant.parse(row.updatedAt) }.getOrDefault(Instant.EPOCH)
            }
        } else {
            vault.notesInFolder(folder)
        }
        _state.update { it.copy(notes = notes) }
    }

    private suspend fun loadNote(path: String) {
        val note = vault.read(path)
        if (note == null) {
            _state.update {
                it.copy(note = null, noteResolvedLinks = emptyMap(), noteDanglingLinks = emptySet(), backlinks = emptyList())
            }
            return
        }
        val resolved = vault.index.allLinks().filter { it.fromPath == path }.associate { it.rawTarget to it.toPath }
        val dangling = vault.index.allDangling().filter { it.fromPath == path }.map { it.rawTarget }.toSet()
        val backlinks = vault.backlinks(path)
        _state.update {
            it.copy(note = note, noteResolvedLinks = resolved, noteDanglingLinks = dangling, backlinks = backlinks)
        }
    }
}
