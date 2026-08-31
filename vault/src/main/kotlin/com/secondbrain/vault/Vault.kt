package com.secondbrain.vault

import com.secondbrain.model.Backlink
import com.secondbrain.model.FolderDecision
import com.secondbrain.model.FolderVerdict
import com.secondbrain.model.Note
import com.secondbrain.model.NoteDraft
import com.secondbrain.model.SearchHit
import com.secondbrain.model.TreeNode
import com.secondbrain.model.VaultConfig
import com.secondbrain.ports.VaultStore
import com.secondbrain.ports.WriteResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * The vault, assembled. Implements [VaultStore] for `:agent` and exposes the
 * dashboard queries `:app` needs.
 *
 * `:app` is the only module that gets to see this type; `:agent` sees the port.
 */
class Vault(
    val root: VaultRoot,
    val index: VaultIndex,
    val appDb: AppDb,
    private val config: VaultConfig,
    private val writer: VaultWriter,
    private val scanner: VaultScanner,
    private val linkResolver: LinkResolver,
    private val atomicWriter: AtomicWriter,
) : VaultStore, AutoCloseable {

    private val log = LoggerFactory.getLogger(Vault::class.java)

    companion object {
        /**
         * Opens or creates everything, in the order the dependencies require.
         *
         * `app.db` migrates (it is precious); `index.db` rebuilds on a schema
         * mismatch (it is disposable). That asymmetry is R10 expressed in code.
         */
        fun open(appRoot: Path, config: VaultConfig = VaultConfig()): Vault {
            Files.createDirectories(appRoot)

            val root = VaultRoot.open(appRoot)

            // Orphaned temp files mean a write did not complete. Say so out loud.
            val swept = root.sweepTempFiles()
            if (swept.isNotEmpty()) {
                LoggerFactory.getLogger(Vault::class.java)
                    .warn("Swept {} orphaned temp file(s) from a previous crash: {}", swept.size, swept)
            }

            val appDb = AppDb(appRoot.resolve("app.db"))
            val index = VaultIndex(appRoot.resolve("index.db"), config)

            val slugifier = Slugifier(config.maxSlugLength)
            val linkResolver = LinkResolver(config)
            val atomicWriter = AtomicWriter(config)
            val writer = VaultWriter(
                root = root,
                index = index,
                appDb = appDb,
                config = config,
                writer = atomicWriter,
                slugifier = slugifier,
                folderGuard = FolderGuard(config, slugifier),
                linkResolver = linkResolver,
                duplicateGuard = DuplicateGuard(config),
            )
            val scanner = VaultScanner(root, index, appDb, config, linkResolver)

            scanner.rebuildIfNeeded()

            return Vault(root, index, appDb, config, writer, scanner, linkResolver, atomicWriter)
        }
    }

    // ── VaultStore ──────────────────────────────────────────────────────────

    override suspend fun tree(depth: Int?): TreeNode =
        index.tree(depth ?: config.treeDefaultDepth)

    override suspend fun read(path: String): Note? {
        val absolute = runCatching { root.resolve(path, mustExist = true) }.getOrNull() ?: return null
        val raw = runCatching { atomicWriter.read(absolute) }.getOrNull() ?: return null
        val mtime = runCatching { Files.getLastModifiedTime(absolute).toInstant() }
            .getOrDefault(Instant.EPOCH)
        return NoteParser.parse(path, raw, mtime)
    }

    override suspend fun search(query: String, limit: Int): List<SearchHit> =
        index.search(query, limit)

    override suspend fun createFolder(path: String): FolderVerdict =
        writer.createFolder(path)

    override suspend fun writeNote(draft: NoteDraft, confirmNew: Boolean): WriteResult = runWrite {
        writer.writeNote(draft, confirmNew = confirmNew)
    }

    override suspend fun appendNote(path: String, heading: String, markdown: String): WriteResult = runWrite {
        writer.appendNote(path, heading, markdown)
    }

    override suspend fun moveNote(path: String, toFolder: String): WriteResult = runWrite {
        writer.moveNote(path, toFolder)
    }

    /**
     * Inbound links, with the surrounding text the dashboard shows.
     *
     * The context is extracted from the source note on read rather than stored:
     * there is no snippet column in the section 2 schema, and a stored snippet
     * goes stale the moment the source note is edited (F11 / D-027).
     */
    override suspend fun backlinks(path: String): List<Backlink> {
        val target = read(path) ?: return emptyList()

        return index.backlinks(path).map { backlink ->
            val sourceBody = read(backlink.fromPath)?.bodyMarkdown
            val context = sourceBody
                ?.let { body ->
                    linkResolver
                        .occurrencesTargeting(body, target.title, target.slug)
                        .firstOrNull()
                        ?.let { linkResolver.contextAround(body, it) }
                }
                .orEmpty()
            backlink.copy(context = context)
        }
    }

    /**
     * A `PathSafety` rejection is a rejection, not a crash.
     *
     * R4 makes these exceptions on purpose — an escaping path is not negotiable —
     * but the tool layer needs a structured `tool_result` it can hand back to the
     * model, so the boundary converts here and nowhere else.
     */
    private suspend fun runWrite(body: suspend () -> VaultWriter.Outcome): WriteResult =
        try {
            val outcome = body()
            WriteResult.Written(
                path = outcome.path,
                resolvedLinks = outcome.resolvedLinks,
                danglingLinks = outcome.danglingLinks,
                slugSuffixed = outcome.slugSuffixed,
            )
        } catch (e: PathSafety.UnsafePathException) {
            log.warn("Rejected unsafe path '{}': {}", e.supplied, e.reason)
            WriteResult.Rejected("unsafe_path", e.reason)
        } catch (e: java.nio.file.NoSuchFileException) {
            WriteResult.Rejected("not_found", "no note at '" + e.file + "'")
        } catch (e: VaultWriter.DuplicateNoteException) {
            // A rejection the model is expected to act on, not a failure.
            WriteResult.Rejected(
                reason = "duplicate",
                detail = e.message.orEmpty(),
                existingPath = e.existingPath,
                score = e.score,
            )
        }

    // ── dashboard queries (:app only) ───────────────────────────────────────

    fun notesInFolder(folder: String): List<VaultIndex.IndexedNote> = index.notesInFolder(folder)

    fun folderDecisions(limit: Int = 50): List<FolderDecision> = appDb.folderDecisions(limit)

    fun danglingLinks() = index.allDangling()

    /**
     * Creates a stub for a dangling link, in the same folder as the note that
     * references it.
     *
     * The design board offers "Create stub in Positioning" inline under the
     * dangling-link notice, which fixes both the folder and the title. Writing it
     * re-resolves the dangling link automatically (F12).
     */
    suspend fun createStub(fromPath: String, rawTarget: String): WriteResult {
        val folder = fromPath.substringBeforeLast('/', VaultRoot.INBOX)
        val title = linkResolver.baseTarget(rawTarget)
        if (title.isBlank()) return WriteResult.Rejected("invalid_target", "'" + rawTarget + "' has no target")

        // confirmNew: the user clicked "Create stub" for a specific dangling
        // target, so the duplicate guard has nothing to add. Letting it refuse
        // here would make the button silently do nothing.
        return writeNote(
            NoteDraft(
                folder = folder,
                title = title,
                tags = emptyList(),
                summary = "",
                bodyMarkdown = "",
                source = com.secondbrain.model.NoteSource.TEXT,
            ),
            confirmNew = true,
        )
    }

    // ── watcher ─────────────────────────────────────────────────────────────

    private var watcher: FileWatcher? = null

    /**
     * Starts watching for external edits (EC-N10).
     *
     * OVERFLOW escalates to a full rescan rather than being ignored: the OS has
     * told us it dropped events, so the index is provably behind and there is no
     * way to know by how much.
     */
    fun startWatching(scope: CoroutineScope) {
        val fw = FileWatcher(root, config)
        watcher = fw
        fw.start(scope)

        scope.launch {
            fw.changes.collect { change ->
                when (change) {
                    is FileWatcher.Change.Upserted -> writer.reindexFromDisk(change.path)
                    is FileWatcher.Change.Deleted -> writer.forgetDeleted(change.path)
                    FileWatcher.Change.Overflowed -> {
                        log.warn("Rescanning the whole vault after a watcher overflow.")
                        scanner.rebuild()
                    }
                }
            }
        }
    }

    fun rebuildIndex(): VaultScanner.ScanReport = scanner.rebuild()

    override fun close() {
        watcher?.close()
        index.close()
        appDb.close()
    }
}
