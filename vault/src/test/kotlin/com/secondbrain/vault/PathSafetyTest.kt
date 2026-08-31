package com.secondbrain.vault

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * R4 / EC-A2. The four traversal cases named in the Step 2 exit criteria are
 * marked; the rest came out of probing what this platform actually does.
 */
class PathSafetyTest {

    private fun vault(dir: Path): Path = dir.resolve("vault").also { Files.createDirectories(it) }

    private fun assertRejected(root: Path, supplied: String) {
        val e = assertThrows(PathSafety.UnsafePathException::class.java, {
            PathSafety.resolve(root, supplied)
        }, "expected '$supplied' to be rejected")
        assertTrue(e.message!!.contains(supplied) || e.message!!.isNotEmpty())
    }

    @Nested
    @DisplayName("Step 2 exit criteria: path traversal")
    inner class Traversal {

        @Test
        @DisplayName("../../etc/passwd")
        fun `relative escape`(@TempDir dir: Path) = assertRejected(vault(dir), "../../etc/passwd")

        @Test
        @DisplayName("/etc/passwd")
        fun `absolute unix`(@TempDir dir: Path) = assertRejected(vault(dir), "/etc/passwd")

        @Test
        @DisplayName("Projects/../../..")
        fun `mid-path escape`(@TempDir dir: Path) = assertRejected(vault(dir), "Projects/../../..")

        @Test
        @DisplayName("symlink escape")
        fun `symlink escape`(@TempDir dir: Path) {
            val root = vault(dir)
            val outside = dir.resolve("outside").also { Files.createDirectories(it) }
            Files.writeString(outside.resolve("secret.md"), "not yours")

            val link = root.resolve("Escape")
            val created = runCatching { Files.createSymbolicLink(link, outside) }.isSuccess
            // Windows refuses symlink creation without elevation or developer mode.
            // The junction test below covers the same escape and DOES run here, so
            // skipping this one loses no coverage.
            assumeTrue(created, "this OS/user cannot create symlinks; the junction test covers it")

            assertRejected(root, "Escape/secret.md")
            assertRejected(root, "Escape")
        }

        @Test
        @DisplayName("directory junction escape - the Windows case that actually matters")
        fun `junction escape`(@TempDir dir: Path) {
            assumeTrue(
                System.getProperty("os.name").lowercase().contains("windows"),
                "junctions are a Windows concept",
            )

            val root = vault(dir)
            val outside = dir.resolve("outside").also { Files.createDirectories(it) }
            Files.writeString(outside.resolve("secret.md"), "not yours")
            val link = root.resolve("Escape")

            // Unlike a symlink, a junction needs no elevation, which makes it the
            // escape a real unprivileged process would actually use.
            val process = ProcessBuilder(
                "cmd", "/c", "mklink", "/J",
                link.toAbsolutePath().toString(),
                outside.toAbsolutePath().toString(),
            ).redirectErrorStream(true).start()
            process.waitFor()
            assumeTrue(Files.exists(link), "could not create a junction on this machine")

            // Establish that the escape is real before asserting we block it.
            assertEquals("not yours", Files.readString(link.resolve("secret.md")))
            assertTrue(link.startsWith(root), "the junction is lexically inside the vault")
            assertFalse(
                Files.isSymbolicLink(link),
                "junctions are NOT symlinks to Java - an isSymbolicLink() check would miss this",
            )
            assertFalse(
                link.toRealPath().startsWith(root.toRealPath()),
                "the junction really does resolve outside the vault",
            )

            // And now the actual assertion.
            assertRejected(root, "Escape/secret.md")
            assertRejected(root, "Escape")
        }
    }

    @Nested
    @DisplayName("more escapes")
    inner class MoreEscapes {

        @Test
        fun `drive-qualified windows paths`(@TempDir dir: Path) {
            val root = vault(dir)
            assertRejected(root, "C:/Windows/System32/config")
            assertRejected(root, "C:\\Windows\\System32")
        }

        @Test
        fun `backslash separators are normalised then checked`(@TempDir dir: Path) {
            val root = vault(dir)
            assertRejected(root, "..\\..\\etc\\passwd")
            assertRejected(root, "Projects\\..\\..\\..")
        }

        @Test
        fun `UNC paths`(@TempDir dir: Path) = assertRejected(vault(dir), "//server/share/file.md")

        @Test
        fun `the vault root itself is not a valid target`(@TempDir dir: Path) {
            val root = vault(dir)
            assertRejected(root, ".")
            assertRejected(root, "./")
        }

        @Test
        fun `empty and whitespace`(@TempDir dir: Path) {
            val root = vault(dir)
            assertRejected(root, "")
            assertRejected(root, "   ")
            assertRejected(root, "///")
        }
    }

    @Nested
    @DisplayName("platform filename hazards, all measured on this machine")
    inner class PlatformHazards {

        @Test
        @DisplayName("F8: a trailing dot silently becomes a different filename")
        fun `trailing dot`(@TempDir dir: Path) {
            // Verified: writing "dot.md." produces "dot.md" on disk, so the index
            // would hold a path no directory scan can reproduce.
            val root = vault(dir)
            assertRejected(root, "Projects/note.md.")
            assertRejected(root, "Projects./note.md")
        }

        @Test
        fun `trailing or leading whitespace in a segment`(@TempDir dir: Path) {
            val root = vault(dir)
            assertRejected(root, "Projects/note.md ")
            assertRejected(root, " Projects/note.md")
            assertRejected(root, "Projects /note.md")
        }

        @Test
        @DisplayName("F29: an NTFS alternate data stream is an invisible write")
        fun `alternate data stream`(@TempDir dir: Path) {
            assertRejected(vault(dir), "Projects/note.md:hidden")
        }

        @Test
        fun `other illegal filename characters`(@TempDir dir: Path) {
            val root = vault(dir)
            listOf("a*b", "a?b", "a\"b", "a<b", "a>b", "a|b").forEach {
                assertRejected(root, "Projects/$it.md")
            }
        }

        @Test
        fun `control characters and NUL`(@TempDir dir: Path) {
            val root = vault(dir)
            assertRejected(root, "Projects/no\u0000te.md")
            assertRejected(root, "Projects/no\nte.md")
        }

        @Test
        @DisplayName("reserved device names are refused for portability, not observed failure")
        fun `reserved names`(@TempDir dir: Path) {
            // These round-tripped cleanly in a probe on this machine, so this is a
            // portability guard rather than a fix for a measured bug.
            val root = vault(dir)
            listOf("con.md", "NUL.md", "aux.md", "com1.md", "LPT9.md").forEach {
                assertRejected(root, "Projects/$it")
            }
        }
    }

    @Nested
    @DisplayName("valid paths are allowed through")
    inner class Valid {

        @Test
        fun `ordinary note paths resolve inside the vault`(@TempDir dir: Path) {
            val root = vault(dir)
            val resolved = PathSafety.resolve(root, "Projects/Positioning/offline-inference.md")
            assertTrue(resolved.startsWith(root))
            assertTrue(resolved.toString().endsWith("offline-inference.md"))
        }

        @Test
        fun `folders with spaces are fine`(@TempDir dir: Path) {
            val root = vault(dir)
            val resolved = PathSafety.resolve(root, "Projects/Second Brain/note.md")
            assertTrue(resolved.startsWith(root))
        }

        @Test
        fun `mustExist rejects a missing path but allows an existing one`(@TempDir dir: Path) {
            val root = vault(dir)
            Files.createDirectories(root.resolve("Inbox"))
            Files.writeString(root.resolve("Inbox/a.md"), "x")

            // Existing: fine.
            PathSafety.resolve(root, "Inbox/a.md", mustExist = true)

            // Missing with mustExist: rejected.
            assertThrows(PathSafety.UnsafePathException::class.java) {
                PathSafety.resolve(root, "Inbox/missing.md", mustExist = true)
            }

            // Missing WITHOUT mustExist: allowed - this is a path we are about to create.
            PathSafety.resolve(root, "Inbox/missing.md")
        }

        @Test
        fun `relativise round-trips with forward slashes`(@TempDir dir: Path) {
            val root = vault(dir)
            val absolute = PathSafety.resolve(root, "Projects/Positioning/note.md")
            assertEquals("Projects/Positioning/note.md", PathSafety.relativise(root, absolute))
        }

        @Test
        fun `isSafe mirrors resolve without throwing`(@TempDir dir: Path) {
            val root = vault(dir)
            assertTrue(PathSafety.isSafe(root, "Inbox/a.md"))
            assertFalse(PathSafety.isSafe(root, "../a.md"))
            assertFalse(PathSafety.isSafe(root, "Inbox/a.md:ads"))
        }
    }
}
