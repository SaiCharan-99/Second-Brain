package com.secondbrain.vault

import com.secondbrain.model.VaultConfig
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Writes a file so a crash cannot leave it truncated (EC-N4).
 *
 * The recipe in the architecture is "write to `.tmp` in the same directory,
 * `fsync`, then atomic `Files.move` with `ATOMIC_MOVE`". Two thirds of that works
 * on this platform. Both gaps were measured, not guessed:
 *
 *  1. **Directory fsync is unavailable.** `FileChannel.open(dir).force(true)`
 *     throws `AccessDeniedException` on Windows. Without it the rename itself
 *     cannot be made durable — the file contents are safe, the directory entry is
 *     at the OS's discretion. There is no workaround from the JVM. Accepted risk,
 *     logged as D-034: the exposure is a rename lost to a power cut in the
 *     milliseconds after it returned, and the vault stays consistent either way
 *     because the tmp file is the only thing that could be left behind.
 *
 *  2. **ATOMIC_MOVE fails when anything holds the target open.** Measured:
 *     `AccessDeniedException` when a second handle has the destination open for
 *     reading. Windows Defender, the Search Indexer and the Step 4 dashboard
 *     reader all do that routinely, so this is a normal condition rather than an
 *     exceptional one. Handled with bounded retry and backoff. Readers elsewhere
 *     in this module open-read-close and never hold a handle across a suspension
 *     point, which is the other half of the fix.
 */
class AtomicWriter(
    private val config: VaultConfig = VaultConfig(),
    /**
     * Test seam. Invoked after the temp file is written and fsynced, immediately
     * before the rename — the exact window the Step 2 exit criteria require
     * simulating a crash inside. Throwing here reproduces a kill at that point
     * without needing to actually kill a process.
     */
    private val beforeMove: (Path) -> Unit = {},
) {

    private val log = LoggerFactory.getLogger(AtomicWriter::class.java)

    companion object {
        const val TMP_SUFFIX: String = ".sbtmp"
    }

    /**
     * Writes [content] to [target] atomically.
     *
     * The temp file is created in the same directory so the rename never crosses
     * a filesystem, which is what makes it atomic in the first place.
     */
    fun write(target: Path, content: String) {
        writeBytes(target, content.toByteArray(Charsets.UTF_8))
    }

    fun writeBytes(target: Path, bytes: ByteArray) {
        val parent = target.parent
            ?: throw IOException("target has no parent directory: $target")
        Files.createDirectories(parent)

        val tmp = parent.resolve(target.fileName.toString() + TMP_SUFFIX)

        try {
            FileChannel.open(
                tmp,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
            ).use { channel ->
                var written = 0
                val buffer = java.nio.ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) written += channel.write(buffer)
                // File contents durable. This part does work on Windows.
                channel.force(true)
            }

            beforeMove(tmp)

            moveWithRetry(tmp, target)
        } catch (e: Throwable) {
            // Never leave a half-written temp file behind for the sweeper to find.
            runCatching { Files.deleteIfExists(tmp) }
            throw e
        }
    }

    /**
     * ATOMIC_MOVE, retried on `AccessDeniedException`.
     *
     * Falls back to a non-atomic REPLACE_EXISTING move only after every atomic
     * attempt has failed, and says so in the log. A non-atomic move has a window
     * where the target is absent, which is worse than atomic but far better than
     * losing the write — and the tmp file still exists throughout, so nothing is
     * unrecoverable.
     */
    private fun moveWithRetry(tmp: Path, target: Path) {
        var lastError: AccessDeniedException? = null

        repeat(config.atomicMoveAttempts) { attempt ->
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE)
                if (attempt > 0) {
                    log.info("ATOMIC_MOVE to {} succeeded on attempt {}", target.fileName, attempt + 1)
                }
                return
            } catch (e: AccessDeniedException) {
                lastError = e
                // Something holds the target open. Almost always Defender, the
                // Search Indexer, or a reader that has not closed yet.
                Thread.sleep(config.atomicMoveBackoffMs * (attempt + 1))
            } catch (e: UnsupportedOperationException) {
                log.warn("ATOMIC_MOVE unsupported on this filesystem; falling back for {}", target)
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
                return
            }
        }

        log.warn(
            "ATOMIC_MOVE to {} failed {} times ({}). Falling back to a non-atomic replace.",
            target.fileName,
            config.atomicMoveAttempts,
            lastError?.message,
        )
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
    }

    /**
     * Reads a file fully and closes the handle immediately.
     *
     * Every read in this module goes through here. Holding a handle open is what
     * makes another process's ATOMIC_MOVE fail, so "read it all, close it now" is
     * a correctness requirement rather than a style preference.
     */
    fun read(path: Path): String = Files.readString(path, Charsets.UTF_8)
}
