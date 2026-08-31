package com.secondbrain.ports

import com.secondbrain.model.Backlink
import com.secondbrain.model.FolderVerdict
import com.secondbrain.model.Note
import com.secondbrain.model.NoteDraft
import com.secondbrain.model.SearchHit
import com.secondbrain.model.TreeNode

/**
 * Everything `:agent` is allowed to do to the vault.
 *
 * Deliberately scoped to the autonomous tools in ARCHITECTURE.md section 4 and
 * nothing more. Dashboard-only queries (note lists, the Folder Guard audit panel)
 * live on the concrete `:vault` type, which `:app` may depend on directly — a
 * port that also carries UI queries has stopped meaning anything.
 *
 * There is no `deleteNote`. No tool exposes deletion, so the port does not
 * express it. External deletions are reconciled by the FileWatcher, not by a
 * caller (D-032).
 */
interface VaultStore {

    /**
     * Folder and file structure, so the model can choose placement.
     *
     * EC-A5: depth-limited, and folders past `tree_folder_listing_cap` report a
     * count rather than a listing, so a 2000-note vault cannot blow the context.
     */
    suspend fun tree(depth: Int? = null): TreeNode

    /** Reads one note. Path goes through PathSafety like every other path (R4). */
    suspend fun read(path: String): Note?

    /** FTS5 search over title, summary and body. */
    suspend fun search(query: String, limit: Int = 10): List<SearchHit>

    /**
     * Creates a folder, subject to the Folder Guard.
     *
     * EC-N6: this is the call that gets intercepted. A rejection is a normal
     * return value carrying a reason and a suggested alternative, not an
     * exception — the model has to be able to act on it.
     */
    suspend fun createFolder(path: String): FolderVerdict

    /**
     * Renders and writes a new note.
     *
     * EC-N9 / D-053: the write is refused when the note looks like a duplicate of
     * one already in the vault, and the rejection names the match so the model can
     * append to it instead. Set [confirmNew] to write anyway — two genuinely
     * distinct thoughts about one subject must stay writable, so the guard is a
     * question rather than a wall.
     */
    suspend fun writeNote(draft: NoteDraft, confirmNew: Boolean = false): WriteResult

    /**
     * Appends to an existing note under a heading.
     *
     * The heading is created at the end of the body when absent (D-033).
     * `created` is preserved; only `updated` moves.
     */
    suspend fun appendNote(path: String, heading: String, markdown: String): WriteResult

    /**
     * Moves a note to a different folder, correcting a bad placement.
     *
     * EC-N5: records `moved_from` in frontmatter so the history stays visible.
     */
    suspend fun moveNote(path: String, toFolder: String): WriteResult

    /** Inbound links to a note, with the surrounding context for display. */
    suspend fun backlinks(path: String): List<Backlink>
}

/** Outcome of a vault mutation. */
sealed interface WriteResult {

    data class Written(
        val path: String,
        /** Links that resolved to an existing note. */
        val resolvedLinks: List<String> = emptyList(),
        /** Links left dangling. The text is never rewritten (EC-N7). */
        val danglingLinks: List<String> = emptyList(),
        /** Set when a slug collision forced a suffix (EC-N1). */
        val slugSuffixed: Boolean = false,
    ) : WriteResult

    /**
     * The write did not happen. [reason] is structured so it can go back to the
     * model as a `tool_result` it can act on.
     */
    data class Rejected(
        val reason: String,
        val detail: String,
        /** Set on a `duplicate` rejection: the note this one looked like. */
        val existingPath: String? = null,
        val score: Double? = null,
    ) : WriteResult
}
