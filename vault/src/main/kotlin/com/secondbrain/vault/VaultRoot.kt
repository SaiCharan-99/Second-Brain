package com.secondbrain.vault

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors

/**
 * The vault directory itself.
 *
 * Resolves `~/.secondbrain/vault`, creates it on first run, and seeds `Inbox/`.
 *
 * Only `Inbox/` is seeded. Section 2's storage layout also shows `Projects/` and
 * `People/`, but that tree is illustrative and Step 2's build list says "seeds
 * `Inbox/`" — and pre-creating folders the user may never want is precisely the
 * sprawl the Folder Guard exists to prevent. Every other folder gets created
 * because a capture needed it (D-032).
 */
class VaultRoot private constructor(
    val path: Path,
) {

    companion object {
        const val INBOX: String = "Inbox"

        private val log = LoggerFactory.getLogger(VaultRoot::class.java)

        /**
         * @param appRoot the `~/.secondbrain` directory, already expanded.
         */
        fun open(appRoot: Path): VaultRoot {
            val vault = appRoot.resolve("vault").toAbsolutePath().normalize()
            val fresh = Files.notExists(vault)

            Files.createDirectories(vault)
            val inbox = vault.resolve(INBOX)
            if (Files.notExists(inbox)) Files.createDirectories(inbox)

            if (fresh) log.info("Created a new vault at {} with {}/", vault, INBOX)
            else log.debug("Vault at {}", vault)

            return VaultRoot(vault)
        }
    }

    /** Absolute path for a vault-relative path, via [PathSafety] (R4). */
    fun resolve(relative: String, mustExist: Boolean = false): Path =
        PathSafety.resolve(path, relative, mustExist)

    /** Vault-relative, forward slashes. The form the index stores. */
    fun relativise(absolute: Path): String = PathSafety.relativise(path, absolute)

    /**
     * Every folder in the vault, vault-relative, excluding the root itself.
     *
     * Skips anything [PathSafety] would refuse, so a directory created outside the
     * app with an unsafe name is ignored rather than crashing a scan.
     */
    fun folders(): Set<String> =
        Files.walk(path)
            .use { stream ->
                stream
                    .filter { Files.isDirectory(it) && it != path }
                    .map { relativise(it) }
                    .filter { PathSafety.isSafe(path, it) }
                    .collect(Collectors.toSet())
            }

    /** Every `.md` file in the vault, vault-relative. Non-markdown is ignored (D-036). */
    fun notes(): List<String> =
        Files.walk(path)
            .use { stream ->
                stream
                    .filter { Files.isRegularFile(it) }
                    .map { relativise(it) }
                    .filter { it.endsWith(".md") && PathSafety.isSafe(path, it) }
                    .collect(Collectors.toList())
            }
            .sorted()

    /**
     * Deletes orphaned temp files left by a crash between write and rename.
     *
     * Nothing in the artifacts cleans these up, so without a sweep they accumulate
     * forever and every one of them is a note whose write did not complete (F20).
     * Run at startup; returns what was removed so it can be logged rather than
     * silently discarded.
     */
    fun sweepTempFiles(): List<String> {
        if (Files.notExists(path)) return emptyList()

        val orphans = Files.walk(path).use { stream ->
            stream
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(AtomicWriter.TMP_SUFFIX) }
                .map { it }
                .collect(Collectors.toList())
        }

        val removed = mutableListOf<String>()
        orphans.forEach { orphan ->
            val rel = relativise(orphan)
            val size = runCatching { Files.size(orphan) }.getOrDefault(-1L)
            runCatching { Files.delete(orphan) }
                .onSuccess {
                    removed += rel
                    log.warn(
                        "Removed orphaned temp file {} ({} bytes). A write did not complete; " +
                            "the note it belonged to was never committed.",
                        rel, size,
                    )
                }
                .onFailure { log.warn("Could not remove orphaned temp file {}: {}", rel, it.message) }
        }
        return removed
    }
}
