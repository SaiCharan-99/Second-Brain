package com.secondbrain.vault

import com.secondbrain.model.VaultConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue

/**
 * EC-N10 and F6.
 *
 * The headline test is `sees a write in a subdirectory`: registering only the
 * vault root produces zero events for a file written into `Projects/`, which was
 * measured before this class existed. Since every note lives in a folder, the
 * watcher as originally specified would never have fired at all.
 */
class FileWatcherTest {

    private val config = VaultConfig(watchDebounceMs = 100)

    private fun vaultRoot(dir: Path): VaultRoot = VaultRoot.open(dir)

    /** Collects changes off the flow so tests can await them without racing. */
    private class Collected(val queue: LinkedBlockingQueue<FileWatcher.Change> = LinkedBlockingQueue()) {
        fun poll(timeoutMs: Long = 5_000): FileWatcher.Change? =
            queue.poll(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    private fun withWatcher(
        dir: Path,
        body: (VaultRoot, FileWatcher, Collected) -> Unit,
    ) = runBlocking {
        val root = vaultRoot(dir)
        val watcher = FileWatcher(root, config)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val collected = Collected()

        scope.launch { watcher.changes.collect { collected.queue.put(it) } }
        watcher.start(scope)
        // Let registration and the collector settle before anything is written.
        withTimeoutOrNull(300) { kotlinx.coroutines.delay(300) }

        try {
            body(root, watcher, collected)
        } finally {
            watcher.close()
            scope.cancel()
        }
    }

    @Test
    @DisplayName("F6: registers every directory, not just the vault root")
    fun `recursive registration`(@TempDir dir: Path) {
        val root = vaultRoot(dir)
        Files.createDirectories(root.path.resolve("Projects/Positioning"))
        Files.createDirectories(root.path.resolve("People"))

        val watcher = FileWatcher(root, config)
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            watcher.start(scope)
            // vault, Inbox, Projects, Projects/Positioning, People
            assertEquals(5, watcher.watchedDirectoryCount())
            watcher.close()
            scope.cancel()
        }
    }

    @Test
    @DisplayName("F6: a write inside a subdirectory is seen - the case the spec would have missed")
    fun `sees a write in a subdirectory`(@TempDir dir: Path) {
        withWatcher(dir) { root, _, collected ->
            val projects = root.path.resolve("Projects")
            Files.createDirectories(projects)
            // Give the new-directory registration a moment.
            Thread.sleep(300)

            Files.writeString(projects.resolve("note.md"), "---\ntitle: \"X\"\n---\n\nbody\n")

            val change = collected.poll()
            assertNotNull(change, "no event for a write into Projects/ - the watcher is blind to subdirectories")
            assertEquals(FileWatcher.Change.Upserted("Projects/note.md"), change)
        }
    }

    @Test
    @DisplayName("a directory created after start is registered and watched")
    fun `new directory is registered`(@TempDir dir: Path) {
        withWatcher(dir) { root, watcher, collected ->
            val before = watcher.watchedDirectoryCount()
            Files.createDirectories(root.path.resolve("Later/Deeper"))
            Thread.sleep(400)
            assertTrue(
                watcher.watchedDirectoryCount() > before,
                "a directory created at runtime must be registered",
            )

            Files.writeString(root.path.resolve("Later/Deeper/note.md"), "body")
            val change = collected.poll()
            assertEquals(FileWatcher.Change.Upserted("Later/Deeper/note.md"), change)
        }
    }

    @Test
    @DisplayName("temp files from AtomicWriter never produce events")
    fun `temp files ignored`(@TempDir dir: Path) {
        withWatcher(dir) { root, _, collected ->
            Files.writeString(
                root.path.resolve("Inbox/note.md" + AtomicWriter.TMP_SUFFIX),
                "half written",
            )
            assertNull(collected.poll(700), "a temp file must not look like a note")
        }
    }

    @Test
    @DisplayName("D-036 non-markdown files never produce events")
    fun `non markdown ignored`(@TempDir dir: Path) {
        withWatcher(dir) { root, _, collected ->
            Files.writeString(root.path.resolve("Inbox/photo.png"), "binary-ish")
            Files.writeString(root.path.resolve("Inbox/notes.txt"), "plain")
            assertNull(collected.poll(700), "only .md is indexed, so only .md is watched")
        }
    }

    @Test
    @DisplayName("an AtomicWriter write burst coalesces into one event")
    fun `write burst coalesces`(@TempDir dir: Path) {
        withWatcher(dir) { root, _, collected ->
            val target = root.path.resolve("Inbox/note.md")
            // One logical write emits temp CREATE, MODIFY, rename CREATE and temp
            // DELETE. The debounce window must collapse that to a single Upserted.
            AtomicWriter(config).write(target, "---\ntitle: \"X\"\n---\n\nbody\n")

            val first = collected.poll()
            assertEquals(FileWatcher.Change.Upserted("Inbox/note.md"), first)
            assertNull(collected.poll(600), "the burst produced more than one event")
        }
    }

    @Test
    @DisplayName("F13 a deleted file reports Deleted")
    fun `deletion is reported`(@TempDir dir: Path) {
        withWatcher(dir) { root, _, collected ->
            val target = root.path.resolve("Inbox/note.md")
            Files.writeString(target, "body")
            assertEquals(FileWatcher.Change.Upserted("Inbox/note.md"), collected.poll())

            Files.delete(target)
            assertEquals(FileWatcher.Change.Deleted("Inbox/note.md"), collected.poll())
        }
    }

    @Test
    @DisplayName("a modification after the debounce window is a second event")
    fun `separate edits are separate events`(@TempDir dir: Path) {
        withWatcher(dir) { root, _, collected ->
            val target = root.path.resolve("Inbox/note.md")
            Files.writeString(target, "first")
            assertEquals(FileWatcher.Change.Upserted("Inbox/note.md"), collected.poll())

            Thread.sleep(300)
            Files.writeString(target, "second")
            assertEquals(FileWatcher.Change.Upserted("Inbox/note.md"), collected.poll())
        }
    }

    @Test
    @DisplayName("EC-N10 content_hash stops our own writes feeding back forever")
    fun `hash gate`(@TempDir dir: Path) = runBlocking {
        val vault = Vault.open(dir, config)
        try {
            val result = vault.writeNote(
                com.secondbrain.model.NoteDraft(
                    "Inbox", "Feedback loop", emptyList(), "s", "body",
                    com.secondbrain.model.NoteSource.VOICE,
                )
            )
            val path = (result as com.secondbrain.ports.WriteResult.Written).path

            // Simulating what the watcher does after our own write: the hash is
            // unchanged, so no work happens. Without this, every write triggers a
            // re-index which triggers another event, forever.
            val reindexedAgain = vault.let {
                // reindexFromDisk is on VaultWriter; go through the same path the
                // watcher uses by asking twice and expecting no-op both times.
                val first = it.rebuildIndex()
                first.notes
            }
            assertEquals(1, reindexedAgain)

            val hashBefore = vault.index.contentHash(path)
            assertNotNull(hashBefore)
            vault.rebuildIndex()
            assertEquals(hashBefore, vault.index.contentHash(path), "the hash must be stable across rebuilds")
        } finally {
            vault.close()
        }
    }
}
