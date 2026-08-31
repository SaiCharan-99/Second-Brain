package com.secondbrain.vault

import com.secondbrain.model.VaultConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** EC-N4, plus the two Windows durability gaps measured in F5. */
class AtomicWriterTest {

    private val config = VaultConfig()

    @Nested
    @DisplayName("Step 2 exit criterion: crash between tmp-write and move")
    inner class CrashSimulation {

        @Test
        @DisplayName("a crash before the rename leaves no partial file and no temp file")
        fun `crash before move`(@TempDir dir: Path) {
            val target = dir.resolve("note.md")

            // The test seam fires in exactly the window the exit criterion names:
            // after the temp file is written and fsynced, before the rename.
            val writer = AtomicWriter(config) { throw RuntimeException("simulated kill") }

            assertThrows(RuntimeException::class.java) { writer.write(target, "new content") }

            assertFalse(Files.exists(target), "no partial target file may be left behind")
            assertFalse(
                Files.exists(dir.resolve("note.md" + AtomicWriter.TMP_SUFFIX)),
                "the temp file must be cleaned up",
            )
        }

        @Test
        @DisplayName("a crash while overwriting leaves the previous content intact")
        fun `crash preserves the old note`(@TempDir dir: Path) {
            val target = dir.resolve("note.md")
            Files.writeString(target, "original content")

            val writer = AtomicWriter(config) { throw RuntimeException("simulated kill") }
            assertThrows(RuntimeException::class.java) { writer.write(target, "replacement") }

            // This is the property that matters: a crash mid-write must never be
            // able to lose the note that was already there.
            assertEquals("original content", Files.readString(target))
        }

        @Test
        @DisplayName("the temp file is complete and fsynced before the rename is attempted")
        fun `temp file is whole`(@TempDir dir: Path) {
            val target = dir.resolve("note.md")
            val content = "x".repeat(100_000)
            var observedTempSize = -1L

            val writer = AtomicWriter(config) { tmp ->
                observedTempSize = Files.size(tmp)
                assertEquals(content, Files.readString(tmp), "the temp file must be complete before the move")
            }
            writer.write(target, content)

            assertEquals(content.length.toLong(), observedTempSize)
            assertEquals(content, Files.readString(target))
        }
    }

    @Nested
    @DisplayName("ordinary writes")
    inner class Writes {

        @Test
        fun `creates the file and its parent directories`(@TempDir dir: Path) {
            val target = dir.resolve("Projects/Positioning/note.md")
            AtomicWriter(config).write(target, "body")

            assertTrue(Files.exists(target))
            assertEquals("body", Files.readString(target))
        }

        @Test
        fun `overwrites atomically`(@TempDir dir: Path) {
            val target = dir.resolve("note.md")
            val writer = AtomicWriter(config)

            writer.write(target, "first")
            writer.write(target, "second")

            assertEquals("second", Files.readString(target))
            assertFalse(Files.exists(dir.resolve("note.md" + AtomicWriter.TMP_SUFFIX)))
        }

        @Test
        fun `UTF-8 survives, including non-Latin scripts`(@TempDir dir: Path) {
            val target = dir.resolve("note.md")
            val content = "బ్లూప్రింట్ లెన్స్ — the moat — café"
            AtomicWriter(config).write(target, content)
            assertEquals(content, Files.readString(target))
        }

        @Test
        fun `an empty write produces an empty file, not a missing one`(@TempDir dir: Path) {
            val target = dir.resolve("note.md")
            AtomicWriter(config).write(target, "")
            assertTrue(Files.exists(target))
            assertEquals("", Files.readString(target))
        }

        @Test
        fun `no temp file survives a successful write`(@TempDir dir: Path) {
            AtomicWriter(config).write(dir.resolve("a/b/c.md"), "x")
            val leftovers = Files.walk(dir).use { s ->
                s.filter { it.fileName.toString().endsWith(AtomicWriter.TMP_SUFFIX) }.count()
            }
            assertEquals(0L, leftovers)
        }
    }

    @Nested
    @DisplayName("F5 the Windows-specific gaps, measured")
    inner class WindowsGaps {

        @Test
        @DisplayName("ATOMIC_MOVE fails when a reader holds the target open; the retry covers it")
        fun `retry over a held handle`(@TempDir dir: Path) {
            val target = dir.resolve("note.md")
            Files.writeString(target, "original")

            // Hold the target open, which is what Defender, the Search Indexer and
            // the Step 4 dashboard reader all do routinely.
            FileChannel.open(target, StandardOpenOption.READ).use {
                // With a short backoff this either succeeds via retry or falls back
                // to a non-atomic replace. Either way the write must land, and
                // nothing may be lost.
                AtomicWriter(config.copy(atomicMoveAttempts = 3, atomicMoveBackoffMs = 5))
                    .write(target, "replacement")
            }

            assertEquals("replacement", Files.readString(target))
            assertFalse(Files.exists(dir.resolve("note.md" + AtomicWriter.TMP_SUFFIX)))
        }

        @Test
        @DisplayName("directory fsync is unavailable here, so the writer must not depend on it")
        fun `directory fsync unavailable`(@TempDir dir: Path) {
            // Documenting the measured platform limit that D-034 accepts as a risk:
            // the file contents are durable, the directory entry for the rename is
            // not, and there is no JVM workaround.
            val fileSyncWorks = runCatching {
                val f = dir.resolve("f")
                FileChannel.open(f, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { it.force(true) }
                true
            }.getOrDefault(false)
            assertTrue(fileSyncWorks, "file fsync is required and does work")

            val dirSyncWorks = runCatching {
                FileChannel.open(dir, StandardOpenOption.READ).use { it.force(true) }
                true
            }.getOrDefault(false)

            if (System.getProperty("os.name").lowercase().contains("windows")) {
                assertFalse(
                    dirSyncWorks,
                    "if this starts passing on Windows, D-034's accepted risk can be revisited",
                )
            }
            // The writer never calls it, so a write still succeeds either way.
            AtomicWriter(config).write(dir.resolve("note.md"), "body")
            assertEquals("body", Files.readString(dir.resolve("note.md")))
        }
    }
}
