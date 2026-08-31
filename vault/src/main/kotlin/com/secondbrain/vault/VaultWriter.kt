package com.secondbrain.vault

import com.secondbrain.model.FolderVerdict
import com.secondbrain.model.Note
import com.secondbrain.model.NoteDraft
import com.secondbrain.model.VaultConfig
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.time.Instant

/**
 * Every mutation to the vault goes through here (EC-N3).
 *
 * A single [Mutex] serialises all writes. Step 2's build list asks for "a
 * single-writer actor with a per-file mutex", which is two mechanisms for one
 * job — a single serialised writer already gives per-file exclusion, and adding
 * per-path locks on top only buys parallelism across different notes, which this
 * workload does not have. One voice, one note at a time. If a bulk import ever
 * appears, per-path locks can be layered on then (D-039).
 *
 * Serialising also matters for the *index*: the writer and the FileWatcher both
 * write `index.db`, and funnelling our own mutations through one lock means the
 * only concurrency SQLite has to survive is writer-versus-watcher, which WAL and
 * `busy_timeout` handle.
 */
class VaultWriter(
    private val root: VaultRoot,
    private val index: VaultIndex,
    private val appDb: AppDb,
    private val config: VaultConfig = VaultConfig(),
    private val writer: AtomicWriter = AtomicWriter(config),
    private val slugifier: Slugifier = Slugifier(config.maxSlugLength),
    private val folderGuard: FolderGuard = FolderGuard(config, slugifier),
    private val linkResolver: LinkResolver = LinkResolver(config),
) {

    private val log = LoggerFactory.getLogger(VaultWriter::class.java)
    private val lock = Mutex()

    /** What a write did, so the caller can tell the user and the model. */
    data class Outcome(
        val path: String,
        val resolvedLinks: List<String>,
        val danglingLinks: List<String>,
        val slugSuffixed: Boolean,
        /** Notes whose dangling links this write satisfied (F12). */
        val undangled: List<String> = emptyList(),
    )

    // ── folders ─────────────────────────────────────────────────────────────

    /**
     * Creates a folder if the Folder Guard allows it.
     *
     * Section 5 rule 6: **every** verdict is written to the audit trail, accepted
     * or rejected. The rejections are the interesting ones — they are the evidence
     * for tuning the threshold in Step 3.
     */
    suspend fun createFolder(proposed: String): FolderVerdict = lock.withLock {
        val existing = index.folders().ifEmpty { root.folders() }
        val verdict = folderGuard.evaluate(proposed, existing)

        when (verdict) {
            is FolderVerdict.Accepted -> {
                recordVerdict(verdict.path, "ACCEPTED", null, null)
                val absolute = root.resolve(verdict.path)
                if (Files.notExists(absolute)) {
                    Files.createDirectories(absolute)
                    log.info("Created folder {}", verdict.path)
                }
                index.upsertFolder(verdict.path, folderCreationTime(verdict.path))
                index.recomputeFolderCounts()
                syncDecisionProjection()
            }
            is FolderVerdict.Rejected -> {
                recordVerdict(
                    verdict.proposed,
                    "REJECTED_" + verdict.reason.name,
                    verdict.useInstead,
                    verdict.score,
                )
                syncDecisionProjection()
                log.info("Folder Guard rejected '{}': {}", verdict.proposed, verdict.detail)
            }
        }
        verdict
    }

    private fun recordVerdict(proposed: String, verdict: String, matched: String?, score: Double?) {
        appDb.recordFolderDecision(proposed, verdict, matched, score)
    }

    private fun syncDecisionProjection() {
        // One-way: app.db is the source of truth, index.db holds a projection.
        index.syncFolderDecisions(appDb.allFolderDecisions())
    }

    /**
     * Deterministic folder timestamp: the earliest `created` among its notes, or
     * the filesystem time when it has none.
     *
     * Section 2 has `folders.created_at` captured at index time, which would differ
     * on every rebuild and make the "identical contents" exit criterion
     * unachievable. Deriving it makes a rebuild byte-identical (D-027).
     */
    private fun folderCreationTime(path: String): Instant {
        val notes = index.notesInFolder(path)
        if (notes.isNotEmpty()) {
            return notes.minOf { Instant.parse(it.createdAt) }
        }
        return runCatching {
            Files.getLastModifiedTime(root.resolve(path)).toInstant()
        }.getOrElse { Instant.EPOCH }
    }

    // ── notes ───────────────────────────────────────────────────────────────

    /** Renders and writes a new note. R1: [NoteRenderer] produces the bytes. */
    suspend fun writeNote(draft: NoteDraft, now: Instant = Instant.now()): Outcome = lock.withLock {
        val folder = draft.folder.trim().ifEmpty { VaultRoot.INBOX }

        // R4: every path-bearing argument, without exception.
        val folderAbsolute = root.resolve(folder)
        if (Files.notExists(folderAbsolute)) {
            Files.createDirectories(folderAbsolute)
            index.upsertFolder(folder, now)
        }

        val taken = Files.list(folderAbsolute).use { stream ->
            stream.map { it.fileName.toString() }.filter { it.endsWith(".md") }
                .map { it.removeSuffix(".md") }
                .toList().toSet()
        }
        val slugResult = slugifier.uniqueSlug(draft.title, taken, now)
        val relativePath = folder + "/" + slugResult.slug + ".md"
        val absolute = root.resolve(relativePath)

        val content = NoteRenderer.render(draft, now)
        writer.writeBytes(absolute, content.toByteArray(Charsets.UTF_8))

        val note = NoteParser.parse(relativePath, content, now)
        val outcome = indexNote(note, content, slugResult.suffixed)

        log.info(
            "Wrote {} ({} resolved, {} dangling{})",
            relativePath, outcome.resolvedLinks.size, outcome.danglingLinks.size,
            if (slugResult.suffixed) ", slug suffixed" else "",
        )
        outcome
    }

    /**
     * Appends markdown under a heading.
     *
     * The heading is created at the end when absent — the least surprising
     * behaviour, and the artifacts do not say (D-033). `created` is carried
     * through by [NoteRenderer.rerender]; only `updated` moves.
     */
    suspend fun appendNote(
        path: String,
        heading: String,
        markdown: String,
        now: Instant = Instant.now(),
    ): Outcome = lock.withLock {
        val absolute = root.resolve(path, mustExist = true)
        val existing = NoteParser.parse(path, writer.read(absolute), now)

        val body = appendUnderHeading(existing.bodyMarkdown, heading, markdown)
        val updated = existing.copy(bodyMarkdown = body, updated = now)
        val content = NoteRenderer.rerender(updated, now)

        writer.writeBytes(absolute, content.toByteArray(Charsets.UTF_8))
        indexNote(NoteParser.parse(path, content, now), content, slugSuffixed = false)
    }

    /**
     * Inserts [markdown] under [heading], creating the heading if it is absent.
     *
     * Appended text is sanitised so it cannot begin a line with `---`: that would
     * read as a frontmatter delimiter and split the note in two on the next parse.
     * The model supplies this text, so R3's fail-closed instinct applies.
     */
    internal fun appendUnderHeading(body: String, heading: String, markdown: String): String {
        val safe = markdown
            .replace(Regex("(?m)^-{3,}\\s*$"), "***")
            .trimEnd()

        val headingLine = if (heading.trimStart().startsWith("#")) heading.trim() else "## " + heading.trim()
        val lines = body.lines().toMutableList()

        val headingIndex = lines.indexOfFirst {
            it.trim().equals(headingLine, ignoreCase = true) ||
                it.trim().trimStart('#').trim().equals(heading.trim(), ignoreCase = true) &&
                it.trim().startsWith("#")
        }

        if (headingIndex < 0) {
            val out = StringBuilder(body.trimEnd())
            if (out.isNotEmpty()) out.append("\n\n")
            out.append(headingLine).append("\n\n").append(safe).append("\n")
            return out.toString()
        }

        // Insert at the end of that heading's section, i.e. before the next heading
        // at the same or a shallower level.
        val level = lines[headingIndex].takeWhile { it == '#' }.length
        var insertAt = lines.size
        for (i in headingIndex + 1 until lines.size) {
            val line = lines[i]
            if (line.trimStart().startsWith("#")) {
                val thisLevel = line.trimStart().takeWhile { it == '#' }.length
                if (thisLevel in 1..level) { insertAt = i; break }
            }
        }
        while (insertAt > headingIndex + 1 && lines[insertAt - 1].isBlank()) insertAt--

        lines.add(insertAt, "")
        lines.add(insertAt + 1, safe)
        return lines.joinToString("\n").trimEnd() + "\n"
    }

    /**
     * Moves a note to another folder.
     *
     * EC-N5: `moved_from` is appended to frontmatter so the history stays visible.
     * That is why a parser exists — the alternative is re-deriving the note from
     * nothing and losing `created`.
     */
    suspend fun moveNote(path: String, toFolder: String, now: Instant = Instant.now()): Outcome =
        lock.withLock {
            val source = root.resolve(path, mustExist = true)
            val targetFolder = toFolder.trim().ifEmpty { VaultRoot.INBOX }
            val targetFolderAbsolute = root.resolve(targetFolder)

            if (Files.notExists(targetFolderAbsolute)) {
                Files.createDirectories(targetFolderAbsolute)
                index.upsertFolder(targetFolder, now)
            }

            val existing = NoteParser.parse(path, writer.read(source), now)

            val taken = Files.list(targetFolderAbsolute).use { stream ->
                stream.map { it.fileName.toString() }
                    .filter { it.endsWith(".md") }
                    .map { it.removeSuffix(".md") }
                    .toList().toSet()
            } - existing.slug
            val slugResult = slugifier.uniqueSlug(existing.title, taken, now)

            val newPath = targetFolder + "/" + slugResult.slug + ".md"
            if (newPath.equals(path, ignoreCase = true)) {
                log.debug("moveNote: {} is already in {}", path, targetFolder)
                return@withLock Outcome(path, emptyList(), emptyList(), false)
            }

            val moved = existing.copy(
                path = newPath,
                folder = targetFolder,
                slug = slugResult.slug,
                movedFrom = existing.movedFrom + path,
                updated = now,
            )
            val content = NoteRenderer.rerender(moved, now)

            writer.writeBytes(root.resolve(newPath), content.toByteArray(Charsets.UTF_8))
            Files.deleteIfExists(source)

            index.transaction { index.deleteNote(path) }
            val outcome = indexNote(NoteParser.parse(newPath, content, now), content, slugResult.suffixed)

            log.info("Moved {} -> {}", path, newPath)
            outcome.copy(path = newPath)
        }

    // ── indexing ────────────────────────────────────────────────────────────

    /**
     * Indexes a note and resolves its links, then re-resolves any dangling link
     * this note now satisfies.
     *
     * That second half is F12: without it, creating a stub for
     * `[[Competition Demo Plan]]` leaves the dangling badge on screen forever,
     * which looks exactly like a bug.
     */
    private fun indexNote(note: Note, content: String, slugSuffixed: Boolean): Outcome {
        val undangled = mutableListOf<String>()

        index.transaction {
            index.upsertFolder(note.folder, note.created)
            index.upsertNote(note, note.bodyMarkdown)

            val candidates = index.allNotes().map {
                LinkResolver.Candidate(it.path, it.title, it.slug)
            }
            val resolution = linkResolver.resolve(note.path, note.bodyMarkdown, candidates)
            index.replaceLinks(note.path, resolution.resolved, resolution.dangling, note.updated.toString())

            // Anything that was waiting for this note.
            index.danglingTargeting(note.title, note.slug).forEach { dangling ->
                if (dangling.fromPath == note.path) return@forEach
                val sourceAbsolute = runCatching { root.resolve(dangling.fromPath, mustExist = true) }.getOrNull()
                    ?: return@forEach
                val sourceRaw = runCatching { writer.read(sourceAbsolute) }.getOrNull() ?: return@forEach
                val sourceNote = NoteParser.parse(dangling.fromPath, sourceRaw, note.updated)
                val reResolution = linkResolver.resolve(dangling.fromPath, sourceNote.bodyMarkdown, candidates)
                index.replaceLinks(
                    dangling.fromPath,
                    reResolution.resolved,
                    reResolution.dangling,
                    sourceNote.updated.toString(),
                )
                if (reResolution.resolved.any { it.rawTarget == dangling.rawTarget }) {
                    undangled += dangling.fromPath
                }
            }

            index.recomputeFolderCounts()
        }

        val resolution = linkResolver.resolve(
            note.path,
            note.bodyMarkdown,
            index.allNotes().map { LinkResolver.Candidate(it.path, it.title, it.slug) },
        )

        return Outcome(
            path = note.path,
            resolvedLinks = resolution.resolved.map { it.rawTarget },
            danglingLinks = resolution.dangling.map { it.rawTarget },
            slugSuffixed = slugSuffixed,
            undangled = undangled.distinct(),
        )
    }

    /**
     * Re-indexes a note from disk. Used by the FileWatcher for external edits.
     *
     * EC-N10: `content_hash` short-circuits when nothing actually changed, which
     * is what stops our own writes from feeding back through the watcher into an
     * endless re-index loop.
     */
    suspend fun reindexFromDisk(path: String, now: Instant = Instant.now()): Boolean = lock.withLock {
        val absolute = runCatching { root.resolve(path, mustExist = true) }.getOrNull() ?: return@withLock false
        val raw = runCatching { writer.read(absolute) }.getOrNull() ?: return@withLock false

        val hash = NoteParser.sha256(raw)
        if (index.contentHash(path) == hash) return@withLock false

        val mtime = runCatching { Files.getLastModifiedTime(absolute).toInstant() }.getOrDefault(now)
        indexNote(NoteParser.parse(path, raw, mtime), raw, slugSuffixed = false)
        log.debug("Re-indexed {} after an external change", path)
        true
    }

    /** Removes a note from the index because its file is gone (F13). */
    suspend fun forgetDeleted(path: String) = lock.withLock {
        index.transaction {
            index.deleteNote(path)
            index.recomputeFolderCounts()
        }
        log.info("Note {} was deleted outside the app; inbound links are now dangling.", path)
    }
}
