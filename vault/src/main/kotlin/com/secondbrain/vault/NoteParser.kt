package com.secondbrain.vault

import com.secondbrain.model.Note
import com.secondbrain.model.NoteSource
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * Reads a rendered note back into structure. The inverse of [NoteRenderer].
 *
 * Not in the artifacts, and four specified features need it (F3 / D-028):
 *
 *  - `vault_move_note` must add `moved_from` to frontmatter (EC-N5)
 *  - `vault_append_note` must bump `updated` and preserve `created`
 *  - re-indexing an externally edited note must read title, tags and summary
 *    back off disk (EC-N10)
 *  - rebuilding `index.db` must read every note in the vault (EC-N11)
 *
 * Without it, `created` is reset to now on the first append. Nothing looks broken;
 * the note just quietly forgets when it was written.
 *
 * This does not violate R1. The parser reads; [NoteRenderer] is still the only
 * thing that writes `.md` bytes.
 *
 * Deliberately tolerant. A note the user hand-edited in Notepad will not match
 * our exact output, and refusing to index it would be worse than guessing well:
 * unparseable frontmatter falls back to defaults derived from the file itself
 * rather than throwing.
 */
object NoteParser {

    /**
     * @param path vault-relative, forward slashes, including `.md`.
     * @param raw whole file contents.
     * @param fileFallbackTime used for `created`/`updated` when frontmatter has
     *        none — normally the filesystem mtime, so an externally created note
     *        gets a sensible timestamp instead of "now".
     */
    fun parse(path: String, raw: String, fileFallbackTime: Instant): Note {
        val text = raw.replace("\r\n", "\n").replace('\r', '\n')
        val (frontmatter, body) = split(text)

        val title = frontmatter["title"]?.let(::unquote)?.takeIf { it.isNotBlank() }
            ?: titleFromPath(path)

        return Note(
            path = path,
            folder = path.substringBeforeLast('/', ""),
            title = title,
            slug = path.substringAfterLast('/').removeSuffix(".md"),
            tags = frontmatter["tags"]?.let(::parseFlowList) ?: emptyList(),
            summary = frontmatter["summary"]?.let(::unquote) ?: "",
            bodyMarkdown = body,
            created = frontmatter["created"]?.let { parseInstant(it) } ?: fileFallbackTime,
            updated = frontmatter["updated"]?.let { parseInstant(it) } ?: fileFallbackTime,
            source = frontmatter["source"]?.let(::unquote)?.let(::parseSource) ?: NoteSource.TEXT,
            movedFrom = frontmatter["moved_from"]?.let(::parseFlowList) ?: emptyList(),
            contentHash = sha256(raw),
        )
    }

    /** SHA-256 of the whole file, hex. Cheap "has this changed" for EC-N10. */
    fun sha256(raw: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Splits leading frontmatter from the body.
     *
     * A file with no frontmatter is all body — a hand-created note is still a note.
     */
    internal fun split(text: String): Pair<Map<String, String>, String> {
        val lines = text.lines()
        if (lines.firstOrNull()?.trim() != NoteRenderer.FRONTMATTER_DELIMITER) {
            return emptyMap<String, String>() to text.trim()
        }

        val closing = lines.drop(1).indexOfFirst { it.trim() == NoteRenderer.FRONTMATTER_DELIMITER }
        if (closing < 0) {
            // Opening delimiter with no close. Treat the whole thing as body rather
            // than swallowing the note's content into a broken header.
            return emptyMap<String, String>() to text.trim()
        }

        val headerLines = lines.subList(1, closing + 1)
        val bodyLines = lines.drop(closing + 2)

        val frontmatter = LinkedHashMap<String, String>()
        headerLines.forEach { line ->
            val colon = line.indexOf(':')
            if (colon <= 0) return@forEach
            val key = line.take(colon).trim()
            // Only the FIRST colon splits, so "title: Pricing: a problem" keeps its
            // second colon in the value. This is the case that made quoting
            // necessary on the write side.
            val value = line.substring(colon + 1).trim()
            if (key.isNotEmpty()) frontmatter[key] = value
        }

        return frontmatter to bodyLines.joinToString("\n").trim()
    }

    /**
     * Unwraps a YAML scalar.
     *
     * Handles our own double-quoted output and the bare scalars a human editor
     * would leave behind.
     */
    internal fun unquote(raw: String): String {
        val v = raw.trim()
        return when {
            v.length >= 2 && v.startsWith('"') && v.endsWith('"') ->
                v.substring(1, v.length - 1)
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
            v.length >= 2 && v.startsWith('\'') && v.endsWith('\'') ->
                v.substring(1, v.length - 1).replace("''", "'")
            else -> v
        }
    }

    /** Parses `[a, b]` or `["a", "b"]`, and a bare comma list a human might type. */
    internal fun parseFlowList(raw: String): List<String> {
        val v = raw.trim()
        val inner = if (v.startsWith("[") && v.endsWith("]")) v.substring(1, v.length - 1) else v
        if (inner.isBlank()) return emptyList()

        val items = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var escaped = false

        inner.forEach { c ->
            when {
                escaped -> { current.append(c); escaped = false }
                c == '\\' && inQuotes -> escaped = true
                c == '"' -> { inQuotes = !inQuotes; current.append(c) }
                c == ',' && !inQuotes -> { items += current.toString(); current.clear() }
                else -> current.append(c)
            }
        }
        items += current.toString()

        return items.map { unquote(it) }.filter { it.isNotBlank() }
    }

    private fun parseInstant(raw: String): Instant? {
        val v = unquote(raw)
        if (v.isBlank()) return null
        return try {
            OffsetDateTime.parse(v).toInstant()
        } catch (_: DateTimeParseException) {
            try {
                Instant.parse(v)
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }

    private fun parseSource(raw: String): NoteSource =
        when (raw.trim().lowercase()) {
            "voice" -> NoteSource.VOICE
            "image" -> NoteSource.IMAGE
            else -> NoteSource.TEXT
        }

    /** Last resort title: de-slug the filename. */
    private fun titleFromPath(path: String): String =
        path.substringAfterLast('/')
            .removeSuffix(".md")
            .replace('-', ' ')
            .replace('_', ' ')
            .trim()
            .replaceFirstChar { it.uppercase() }
}
