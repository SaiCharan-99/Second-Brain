package com.secondbrain.vault

import com.secondbrain.model.Note
import com.secondbrain.model.NoteDraft
import com.secondbrain.model.NoteSource
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The only thing in this codebase that produces `.md` bytes (R1).
 *
 * Pure. Same inputs, same output, byte for byte — which is what makes the
 * index rebuild testable and the round-trip property meaningful.
 *
 * ### Frontmatter is quoted, unlike the example in section 3
 *
 * ARCHITECTURE.md section 3 shows unquoted YAML scalars:
 *
 * ```
 * title: Offline inference is the moat
 * ```
 *
 * That format breaks on titles the user will actually dictate. *"Pricing: a
 * positioning problem"* — one of the note titles in the design board itself —
 * emits `title: Pricing: a positioning problem`, which is not valid YAML. A title
 * starting with `-`, `#`, `[`, `&`, `*` or `?`, or containing a newline, breaks or
 * silently changes meaning in the same way. `summary` has identical exposure, and
 * `tags: [a, b]` breaks on any tag containing a comma or bracket.
 *
 * So every string scalar is emitted double-quoted and escaped. This deviates from
 * the documented example and is logged as D-028.
 */
object NoteRenderer {

    /**
     * Section 3 shows an offset (`+05:30`), not a zone ID. Kept as-is: for notes an
     * offset is unambiguous and human-readable. EC-C3's zone-ID rule is about
     * calendar events, where a future local time has to survive a DST change.
     */
    private val timestamp: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    const val FRONTMATTER_DELIMITER: String = "---"

    /**
     * Renders a new note.
     *
     * @param now used for both `created` and `updated`.
     */
    fun render(draft: NoteDraft, now: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
        renderInternal(
            title = draft.title,
            created = now,
            updated = now,
            tags = draft.tags,
            source = draft.source,
            summary = draft.summary,
            body = draft.bodyMarkdown,
            movedFrom = emptyList(),
            zone = zone,
        )

    /**
     * Re-renders an existing note.
     *
     * Used by append and move. [Note.created] is carried through untouched —
     * resetting it is the silent data loss that made a parser necessary in the
     * first place (F3).
     */
    fun rerender(note: Note, updated: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
        renderInternal(
            title = note.title,
            created = note.created,
            updated = updated,
            tags = note.tags,
            source = note.source,
            summary = note.summary,
            body = note.bodyMarkdown,
            movedFrom = note.movedFrom,
            zone = zone,
        )

    private fun renderInternal(
        title: String,
        created: Instant,
        updated: Instant,
        tags: List<String>,
        source: NoteSource,
        summary: String,
        body: String,
        movedFrom: List<String>,
        zone: ZoneId,
    ): String = buildString {
        appendLine(FRONTMATTER_DELIMITER)
        appendLine("title: " + yamlString(title))
        appendLine("created: " + isoOffset(created, zone))
        appendLine("updated: " + isoOffset(updated, zone))
        appendLine("tags: " + yamlFlowList(tags.map(::sanitiseTag)))
        appendLine("source: " + yamlString(source.name.lowercase()))
        appendLine("summary: " + yamlString(summary))
        if (movedFrom.isNotEmpty()) {
            appendLine("moved_from: " + yamlFlowList(movedFrom))
        }
        appendLine(FRONTMATTER_DELIMITER)
        appendLine()
        append(normaliseBody(body))
    }

    private fun isoOffset(instant: Instant, zone: ZoneId): String =
        timestamp.format(OffsetDateTime.ofInstant(instant, zone))

    /**
     * A double-quoted YAML scalar.
     *
     * Only `\` and `"` need escaping inside a double-quoted YAML scalar; the
     * remaining control characters are removed rather than escaped, because a
     * literal newline in a title is a transcription artefact, not intent.
     */
    internal fun yamlString(raw: String): String {
        val flattened = raw
            .replace("\r\n", " ")
            .replace('\r', ' ')
            .replace('\n', ' ')
            .replace('\t', ' ')
            .filter { it.code >= 0x20 || it.code == 0x20 }
            .replace(Regex(" {2,}"), " ")
            .trim()

        val escaped = flattened
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")

        return "\"" + escaped + "\""
    }

    internal fun yamlFlowList(items: List<String>): String =
        if (items.isEmpty()) "[]"
        else items.joinToString(prefix = "[", postfix = "]") { yamlString(it) }

    /**
     * Tags become lowercase hyphenated tokens.
     *
     * They come from the model, so a tag containing a comma or bracket is
     * plausible, and R3's fail-closed instinct applies: sanitise rather than
     * trust. Quoting alone would keep `tags: ["a, b"]` valid YAML but wrong — one
     * tag where two were meant.
     */
    internal fun sanitiseTag(raw: String): String =
        raw.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), "-")
            .trim('-')

    /**
     * Body normalisation: CRLF to LF, exactly one trailing newline.
     *
     * Byte-stability matters more than it looks — `content_hash` compares whole
     * files, so an inconsistent trailing newline would make every rebuild look
     * like an external edit.
     */
    internal fun normaliseBody(body: String): String {
        val lf = body.replace("\r\n", "\n").replace('\r', '\n').trimEnd()
        return if (lf.isEmpty()) "" else lf + "\n"
    }
}
