package com.secondbrain.model

import java.time.Instant

/**
 * What the model fills in. Exactly the shape in ARCHITECTURE.md section 3.
 *
 * R1: the LLM produces this; a deterministic Kotlin function produces the bytes.
 * If you are about to ask a model for raw `.md` content, stop.
 */
data class NoteDraft(
    /** Vault-relative folder. Validated by PathSafety, then FolderGuard. */
    val folder: String,
    /** Human title. May contain spaces, punctuation and any script (EC-V5). */
    val title: String,
    val tags: List<String>,
    /** One sentence. Used by the dashboard list and by search. */
    val summary: String,
    /** May contain `[[wikilinks]]`. */
    val bodyMarkdown: String,
    val source: NoteSource,
)

/**
 * A note that already exists on disk, parsed back into structure.
 *
 * The inverse of [NoteDraft], and the reason `NoteParser` exists at all: moving a
 * note has to add `moved_from` to its frontmatter, appending has to bump
 * `updated`, and re-indexing an externally edited file has to read its title and
 * tags back. All three need to read a rendered note into structure first.
 *
 * Without this, `created` is silently reset to now on the first append. See F3 /
 * D-028.
 */
data class Note(
    /** Vault-relative path including the `.md` extension. */
    val path: String,
    val folder: String,
    val title: String,
    val slug: String,
    val tags: List<String>,
    val summary: String,
    val bodyMarkdown: String,
    /** Preserved across every re-render. Never reset. */
    val created: Instant,
    val updated: Instant,
    val source: NoteSource,
    /** EC-N5: set by `vault_move_note`, oldest first. Visible history. */
    val movedFrom: List<String> = emptyList(),
    /** SHA-256 of the whole file, hex. Guards redundant re-index (EC-N10). */
    val contentHash: String = "",
)
