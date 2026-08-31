package com.secondbrain.vault

import com.secondbrain.model.VaultConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.StandardWatchEventKinds.OVERFLOW
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Watches the vault for edits made outside the app (EC-N10).
 *
 * Four things here that "WatchService on the vault root, debounced 300ms" does not
 * cover, all of which would break it in practice:
 *
 * **1. `WatchService` is not recursive.** Measured: registering only the vault root
 * produced *zero* events for a file written into `Projects/`. Since every note
 * lives in a folder (D-032), the watcher as specified would never fire at all.
 * Every directory is registered individually, and new directories are registered
 * as they appear.
 *
 * **2. Our own writes trigger it.** Every [VaultWriter] write fires
 * CREATE/MODIFY, so without a guard the watcher re-indexes what we just indexed,
 * forever. EC-N10 names `content_hash` as the answer and that is exactly where it
 * is applied — [VaultWriter.reindexFromDisk] returns early when the hash matches.
 *
 * **3. `AtomicWriter` produces a burst.** One logical write emits a temp-file
 * CREATE, a MODIFY, a rename CREATE and a temp DELETE. Temp files are ignored
 * outright and the debounce window coalesces the rest into one re-index.
 *
 * **4. OVERFLOW is real.** When events arrive faster than they are drained the OS
 * drops them and reports OVERFLOW. Ignoring it means silently missing edits, so it
 * escalates to a full rescan.
 */
class FileWatcher(
    private val root: VaultRoot,
    private val config: VaultConfig = VaultConfig(),
) : AutoCloseable {

    private val log = LoggerFactory.getLogger(FileWatcher::class.java)

    /** What the watcher decided happened, after debouncing. */
    sealed interface Change {
        /** A note was created or modified. Re-index it. */
        data class Upserted(val path: String) : Change

        /** A note's file is gone. Forget it and dangle its inbound links. */
        data class Deleted(val path: String) : Change

        /** Events were dropped by the OS. Full rescan required. */
        data object Overflowed : Change
    }

    private val watchService: WatchService = FileSystems.getDefault().newWatchService()
    private val registered = ConcurrentHashMap<WatchKey, Path>()

    private val _changes = MutableSharedFlow<Change>(extraBufferCapacity = 256)
    val changes: SharedFlow<Change> = _changes

    private var job: Job? = null

    /** Registers every directory under the vault root and starts polling. */
    fun start(scope: CoroutineScope) {
        registerRecursively(root.path)
        log.info("Watching {} directories under {}", registered.size, root.path)

        job = scope.launch(Dispatchers.IO) {
            // path -> last event millis. Coalesces the write burst.
            val pending = HashMap<String, Long>()
            val deleted = HashSet<String>()

            while (isActive) {
                val key = try {
                    watchService.poll(100, TimeUnit.MILLISECONDS)
                } catch (_: ClosedWatchServiceException) {
                    return@launch
                } catch (_: InterruptedException) {
                    return@launch
                }

                if (key != null) {
                    val dir = registered[key]
                    if (dir == null) {
                        key.cancel()
                    } else {
                        key.pollEvents().forEach { event ->
                            if (event.kind() == OVERFLOW) {
                                log.warn("WatchService reported OVERFLOW; events were dropped. Rescanning.")
                                _changes.emit(Change.Overflowed)
                                return@forEach
                            }

                            @Suppress("UNCHECKED_CAST")
                            val relativeEvent = (event as java.nio.file.WatchEvent<Path>).context()
                            val absolute = dir.resolve(relativeEvent)

                            // A new directory has to be registered or everything
                            // inside it is invisible.
                            if (event.kind() == ENTRY_CREATE && Files.isDirectory(absolute)) {
                                registerRecursively(absolute)
                                return@forEach
                            }

                            val name = absolute.fileName?.toString() ?: return@forEach

                            // The temp files our own AtomicWriter creates. Never events.
                            if (name.endsWith(AtomicWriter.TMP_SUFFIX)) return@forEach
                            // D-036: only markdown is indexed, so only markdown is watched.
                            if (!name.endsWith(".md")) return@forEach

                            val relative = runCatching { root.relativise(absolute) }.getOrNull()
                                ?: return@forEach
                            if (!PathSafety.isSafe(root.path, relative)) return@forEach

                            when (event.kind()) {
                                ENTRY_DELETE -> {
                                    pending.remove(relative)
                                    deleted += relative
                                }
                                ENTRY_CREATE, ENTRY_MODIFY -> {
                                    deleted -= relative
                                    pending[relative] = System.currentTimeMillis()
                                }
                            }
                        }
                        if (!key.reset()) registered.remove(key)
                    }
                }

                // Emit anything whose debounce window has elapsed.
                val now = System.currentTimeMillis()
                val ready = pending.filterValues { now - it >= config.watchDebounceMs }.keys.toList()
                ready.forEach { path ->
                    pending.remove(path)
                    // A create followed by a delete inside one window is a no-op.
                    if (Files.exists(root.path.resolve(path))) {
                        _changes.emit(Change.Upserted(path))
                    }
                }

                if (deleted.isNotEmpty()) {
                    val settled = deleted.toList()
                    deleted.clear()
                    settled.forEach { path ->
                        // ATOMIC_MOVE deletes the temp file and can briefly look
                        // like a delete of the target. Only report an actual absence.
                        if (Files.notExists(root.path.resolve(path))) {
                            _changes.emit(Change.Deleted(path))
                        }
                    }
                }

                if (key == null && pending.isEmpty() && deleted.isEmpty()) {
                    delay(50)
                }
            }
        }
    }

    private fun registerRecursively(start: Path) {
        if (Files.notExists(start)) return
        Files.walk(start).use { stream ->
            stream.filter { Files.isDirectory(it) }.forEach { dir ->
                runCatching {
                    val key = dir.register(watchService, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE)
                    registered[key] = dir
                }.onFailure { log.warn("Could not watch {}: {}", dir, it.message) }
            }
        }
    }

    /** Directories currently registered. Exposed so the recursion is testable. */
    fun watchedDirectoryCount(): Int = registered.size

    override fun close() {
        job?.cancel()
        job = null
        runCatching { watchService.close() }
        registered.clear()
    }
}
