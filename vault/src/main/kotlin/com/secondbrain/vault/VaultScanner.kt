package com.secondbrain.vault

import com.secondbrain.model.VaultConfig
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.time.Instant

/**
 * Rebuilds `index.db` by walking the vault.
 *
 * This is the component the Step 2 exit criterion actually exercises: "`index.db`
 * can be deleted and fully rebuilt from `vault/` with identical contents." R10
 * depends on it being true — the whole justification for calling `index.db`
 * disposable is that this class can reconstruct it.
 *
 * "Identical" is only achievable because no row carries a wall-clock value
 * captured at index time. `folders.created_at` derives from the earliest note in
 * the folder, `dangling_links.seen_at` from the referencing note's `updated`, and
 * `folder_decisions` is projected from `app.db` (D-027, D-026).
 */
class VaultScanner(
    private val root: VaultRoot,
    private val index: VaultIndex,
    private val appDb: AppDb,
    private val config: VaultConfig = VaultConfig(),
    private val linkResolver: LinkResolver = LinkResolver(config),
) {

    private val log = LoggerFactory.getLogger(VaultScanner::class.java)

    data class ScanReport(
        val folders: Int,
        val notes: Int,
        val resolvedLinks: Int,
        val danglingLinks: Int,
        val skipped: List<String>,
        val durationMs: Long,
    )

    /**
     * Full rebuild. Drops the schema and re-derives every row.
     *
     * Two passes over the notes, because link resolution needs to know about every
     * note before it can resolve anything — a single pass would leave every
     * forward reference dangling purely because of walk order.
     */
    fun rebuild(): ScanReport {
        val started = System.currentTimeMillis()
        index.recreate()

        val skipped = mutableListOf<String>()

        // ── folders ──────────────────────────────────────────────────────────
        val folders = root.folders().sorted()

        // ── pass 1: notes ────────────────────────────────────────────────────
        data class Loaded(val note: com.secondbrain.model.Note, val raw: String)
        val loaded = mutableListOf<Loaded>()

        root.notes().forEach { relative ->
            val absolute = runCatching { root.resolve(relative, mustExist = true) }.getOrNull()
            if (absolute == null) {
                skipped += relative + " (rejected by PathSafety)"
                return@forEach
            }
            val raw = runCatching { Files.readString(absolute) }.getOrNull()
            if (raw == null) {
                skipped += relative + " (unreadable)"
                return@forEach
            }
            // Notes at the vault root are not a thing (D-032): every note lives in
            // a folder, so the folders() foreign key is always satisfiable.
            if (!relative.contains('/')) {
                skipped += relative + " (at the vault root; every note must live in a folder)"
                return@forEach
            }
            val mtime = runCatching { Files.getLastModifiedTime(absolute).toInstant() }
                .getOrDefault(Instant.EPOCH)
            loaded += Loaded(NoteParser.parse(relative, raw, mtime), raw)
        }

        var resolvedCount = 0
        var danglingCount = 0

        index.transaction {
            // Folders first, so the notes.folder foreign key holds.
            folders.forEach { folder ->
                val earliest = loaded
                    .filter { it.note.folder == folder }
                    .minOfOrNull { it.note.created }
                val createdAt = earliest ?: runCatching {
                    Files.getLastModifiedTime(root.resolve(folder)).toInstant()
                }.getOrDefault(Instant.EPOCH)
                index.upsertFolder(folder, createdAt)
            }

            loaded.forEach { index.upsertNote(it.note, it.note.bodyMarkdown) }

            // ── pass 2: links, now that every note is known ──────────────────
            val candidates = loaded.map {
                LinkResolver.Candidate(it.note.path, it.note.title, it.note.slug)
            }
            loaded.forEach { entry ->
                val resolution = linkResolver.resolve(entry.note.path, entry.note.bodyMarkdown, candidates)
                index.replaceLinks(
                    entry.note.path,
                    resolution.resolved,
                    resolution.dangling,
                    entry.note.updated.toString(),
                )
                resolvedCount += resolution.resolved.size
                danglingCount += resolution.dangling.size
            }

            index.recomputeFolderCounts()

            // Projection of the precious database, not an independent copy.
            index.syncFolderDecisions(appDb.allFolderDecisions())
        }

        val report = ScanReport(
            folders = folders.size,
            notes = loaded.size,
            resolvedLinks = resolvedCount,
            danglingLinks = danglingCount,
            skipped = skipped,
            durationMs = System.currentTimeMillis() - started,
        )

        log.info(
            "Rebuilt index.db from the vault: {} folders, {} notes, {} links, {} dangling in {}ms",
            report.folders, report.notes, report.resolvedLinks, report.danglingLinks, report.durationMs,
        )
        skipped.forEach { log.warn("Skipped during scan: {}", it) }

        return report
    }

    /**
     * Rebuilds only if the schema version is wrong or the database is missing.
     *
     * EC-N11: a schema mismatch rebuilds rather than migrating, which is only safe
     * because of everything above.
     */
    fun rebuildIfNeeded(): ScanReport? {
        val needed = index.openOrRecreate()
        return if (needed) rebuild() else null
    }
}
