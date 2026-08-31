package com.secondbrain.vault

import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * The only thing standing between a model-supplied string and the filesystem.
 *
 * R4: "Every path-bearing argument from a model, on every tool, without
 * exception. This is not defence in depth, it is the only defence." EC-A2 is the
 * `../` case; everything else here came out of probing what this specific
 * platform actually does.
 *
 * Rejections are exceptions rather than return values on purpose. A path that
 * escapes the vault is not a condition the model gets to negotiate with.
 */
object PathSafety {

    class UnsafePathException(val supplied: String, val reason: String) :
        Exception("unsafe path '" + supplied + "': " + reason)

    /**
     * Characters that cannot appear in a path segment.
     *
     * `:` is here for two reasons: it is illegal in a Windows filename, and
     * `note.md:hidden` opens an NTFS alternate data stream, which writes bytes
     * nobody can see in a directory listing. Java rejects it with
     * InvalidPathException, but R4 says this class is the defence, so it does not
     * delegate.
     */
    private val illegalChars = charArrayOf(':', '*', '?', '"', '<', '>', '|', '\u0000')

    /**
     * Windows device names. Verified NOT to be a problem on the target machine —
     * `con.md`, `nul.md`, `aux.md` and `com1.md` all round-tripped cleanly — so
     * these are rejected for portability, not because of an observed failure.
     */
    private val reservedNames = setOf(
        "con", "prn", "aux", "nul",
        "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
        "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9",
    )

    /**
     * Resolves a vault-relative path against [root] and verifies it cannot escape.
     *
     * The check is done twice on purpose: lexically on the supplied string, then
     * again on the canonicalised real path. The first catches `../`; the second
     * catches a symlink or NTFS junction whose target sits outside the vault,
     * which no amount of string inspection can see.
     *
     * @param mustExist when true, a missing path is an error rather than a
     *        location we are about to create.
     */
    fun resolve(root: Path, supplied: String, mustExist: Boolean = false): Path {
        if (supplied.isBlank()) throw UnsafePathException(supplied, "empty")

        // Deliberately NOT trimmed. Trimming the whole string strips a trailing
        // space off the final segment, which Windows then rejects with
        // InvalidPathException - an exception type no caller expects - instead of
        // the clean UnsafePathException that validateSegment produces.
        val normalisedInput = supplied.replace('\\', '/')

        if (normalisedInput.startsWith("/") || normalisedInput.startsWith("//")) {
            throw UnsafePathException(supplied, "absolute paths are not allowed")
        }
        // "C:/x" or "C:\x"
        if (normalisedInput.length >= 2 && normalisedInput[1] == ':') {
            throw UnsafePathException(supplied, "drive-qualified paths are not allowed")
        }

        val segments = normalisedInput.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) throw UnsafePathException(supplied, "no path segments")

        segments.forEach { segment -> validateSegment(supplied, segment) }

        if (segments.any { it == ".." }) {
            throw UnsafePathException(supplied, "'..' is not allowed")
        }

        val canonicalRoot = root.toAbsolutePath().normalize()
        val candidate = canonicalRoot.resolve(segments.joinToString("/")).normalize()

        // Lexical check. Catches ".." that survived, and any normalisation surprise.
        if (!candidate.startsWith(canonicalRoot)) {
            throw UnsafePathException(supplied, "resolves outside the vault root")
        }
        if (candidate == canonicalRoot) {
            throw UnsafePathException(supplied, "refers to the vault root itself")
        }

        if (mustExist && Files.notExists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw UnsafePathException(supplied, "does not exist")
        }

        verifyNoSymlinkEscape(supplied, canonicalRoot, candidate)
        return candidate
    }

    /**
     * Walks from the candidate up to the root, resolving each existing ancestor to
     * its real path. A link anywhere along the chain — not just at the leaf — can
     * redirect outside the vault, so checking only the final component is not
     * enough.
     *
     * This compares `toRealPath()` rather than testing `Files.isSymbolicLink()`,
     * and that distinction is load-bearing on Windows. Measured: a **directory
     * junction** created by an unprivileged user reads through to a directory
     * outside the vault, sits lexically inside the root, and
     * `Files.isSymbolicLink()` returns **false** for it. An implementation built
     * on `isSymbolicLink` would miss the escape completely. `toRealPath()`
     * resolves junctions, so it does not. Do not "simplify" this (D-040).
     */
    private fun verifyNoSymlinkEscape(supplied: String, canonicalRoot: Path, candidate: Path) {
        val realRoot = try {
            canonicalRoot.toRealPath()
        } catch (_: Exception) {
            // The root itself does not exist yet; nothing to escape from.
            return
        }

        var cursor: Path? = candidate
        while (cursor != null && cursor.startsWith(canonicalRoot)) {
            if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
                val real = try {
                    cursor.toRealPath()
                } catch (_: Exception) {
                    cursor
                }
                if (!real.startsWith(realRoot)) {
                    throw UnsafePathException(
                        supplied,
                        "escapes the vault via a link at '" + canonicalRoot.relativize(cursor) + "'",
                    )
                }
            }
            if (cursor == canonicalRoot) break
            cursor = cursor.parent
        }
    }

    private fun validateSegment(supplied: String, segment: String) {
        if (segment == ".") throw UnsafePathException(supplied, "'.' segment")

        illegalChars.forEach { c ->
            if (segment.indexOf(c) >= 0) {
                val shown = if (c == '\u0000') "NUL" else "'" + c + "'"
                throw UnsafePathException(supplied, "illegal character " + shown + " in '" + segment + "'")
            }
        }

        segment.forEach { c ->
            if (c.code < 0x20) {
                throw UnsafePathException(supplied, "control character in '" + segment + "'")
            }
        }

        // Verified on this machine: writing "dot.md." produces a file named
        // "dot.md" on disk. The index would then hold a path no directory scan can
        // reproduce, so the two drift permanently. Same for a trailing space.
        if (segment.endsWith(".")) {
            throw UnsafePathException(
                supplied,
                "'" + segment + "' ends with a dot; Windows silently strips it and the index would drift",
            )
        }
        if (segment != segment.trim()) {
            throw UnsafePathException(supplied, "'" + segment + "' has leading or trailing whitespace")
        }

        val stem = segment.substringBefore('.').lowercase()
        if (stem in reservedNames) {
            throw UnsafePathException(supplied, "'" + stem + "' is a reserved device name on Windows")
        }
    }

    /**
     * Vault-relative path with forward slashes, which is the form the index
     * stores. `notes.path` and `folders.path` are always in this shape regardless
     * of platform separator.
     */
    fun relativise(root: Path, absolute: Path): String =
        root.toAbsolutePath().normalize()
            .relativize(absolute.toAbsolutePath().normalize())
            .toString()
            .replace('\\', '/')

    /**
     * True when [supplied] is safe. For places that want a boolean rather than an
     * exception, such as filtering a directory scan.
     */
    fun isSafe(root: Path, supplied: String): Boolean =
        try {
            resolve(root, supplied)
            true
        } catch (_: UnsafePathException) {
            false
        } catch (_: InvalidPathException) {
            false
        }
}
