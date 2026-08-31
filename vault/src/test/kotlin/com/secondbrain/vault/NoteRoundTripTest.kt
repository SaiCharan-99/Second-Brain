package com.secondbrain.vault

import com.secondbrain.model.NoteDraft
import com.secondbrain.model.NoteSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId

/**
 * R1 and F3/F4.
 *
 * [NoteRenderer] is the only producer of `.md` bytes; [NoteParser] is its inverse.
 * The round-trip property is what makes append and move safe — without it,
 * `created` silently resets on the first append.
 */
class NoteRoundTripTest {

    private val now = Instant.parse("2026-09-01T09:02:11Z")
    private val zone = ZoneId.of("Asia/Kolkata")

    private fun draft(
        title: String = "Offline inference is the moat",
        tags: List<String> = listOf("architecture", "positioning"),
        summary: String = "Competitors need network round-trips; we don't. That's the pitch.",
        body: String = "Competitors all need a network round-trip for inference. We don't.\n\n" +
            "Relates to [[BluePrint Lens]] and the [[Competition Demo Plan]].",
    ) = NoteDraft("Projects/Positioning", title, tags, summary, body, NoteSource.VOICE)

    @Nested
    @DisplayName("section 3 note format")
    inner class Format {

        @Test
        fun `renders the documented shape`() {
            val md = NoteRenderer.render(draft(), now, zone)

            assertTrue(md.startsWith("---\n"), md.take(60))
            assertTrue(md.contains("\ntitle: "), md)
            assertTrue(md.contains("\ncreated: 2026-09-01T14:32:11+05:30\n"), md)
            assertTrue(md.contains("\nupdated: 2026-09-01T14:32:11+05:30\n"), md)
            assertTrue(md.contains("\ntags: [\"architecture\", \"positioning\"]\n"), md)
            assertTrue(md.contains("\nsource: \"voice\"\n"), md)
            assertTrue(md.contains("\nsummary: "), md)
            assertTrue(md.contains("Relates to [[BluePrint Lens]]"), md)
        }

        @Test
        fun `is pure - identical inputs give identical bytes`() {
            assertEquals(
                NoteRenderer.render(draft(), now, zone),
                NoteRenderer.render(draft(), now, zone),
            )
        }

        @Test
        fun `body ends with exactly one newline`() {
            val md = NoteRenderer.render(draft(body = "one line\n\n\n\n"), now, zone)
            assertTrue(md.endsWith("one line\n"), "ends with: '${md.takeLast(20)}'")
            assertFalse(md.endsWith("\n\n"))
        }

        @Test
        fun `wikilinks are never rewritten`() {
            // EC-N7: the link text stays exactly as dictated, resolved or not.
            val md = NoteRenderer.render(draft(body = "See [[Nothing That Exists]]."), now, zone)
            assertTrue(md.contains("[[Nothing That Exists]]"), md)
        }
    }

    @Nested
    @DisplayName("F4 frontmatter that the documented unquoted format would break on")
    inner class Quoting {

        @Test
        @DisplayName("a colon in the title - the design board's own note title")
        fun `colon in title`() {
            val md = NoteRenderer.render(draft(title = "Pricing: a positioning problem"), now, zone)
            assertTrue(md.contains("""title: "Pricing: a positioning problem""""), md)

            // And it survives the round trip, which unquoted YAML would not.
            val parsed = NoteParser.parse("Projects/Positioning/x.md", md, now)
            assertEquals("Pricing: a positioning problem", parsed.title)
        }

        @Test
        fun `quotes and backslashes are escaped`() {
            val title = """He said "no" \ then left"""
            val md = NoteRenderer.render(draft(title = title), now, zone)
            val parsed = NoteParser.parse("a/b.md", md, now)
            assertEquals(title, parsed.title)
        }

        @Test
        fun `leading YAML-significant characters are safe`() {
            listOf(
                "- a bullet title", "# a hash title", "[bracketed]", "& anchor", "* star",
                "? question", "| pipe", ">-folded", "@reserved", "%directive",
            ).forEach { title ->
                val md = NoteRenderer.render(draft(title = title), now, zone)
                val parsed = NoteParser.parse("a/b.md", md, now)
                assertEquals(title, parsed.title, "failed for '$title'")
            }
        }

        @Test
        fun `newlines in a transcribed title are flattened, not emitted raw`() {
            val md = NoteRenderer.render(draft(title = "line one\nline two"), now, zone)
            val frontmatter = md.substringAfter("---\n").substringBefore("\n---")
            assertEquals(
                frontmatter.lines().count { it.startsWith("title:") }, 1,
                "the title must occupy exactly one line",
            )
            assertEquals("line one line two", NoteParser.parse("a/b.md", md, now).title)
        }

        @Test
        fun `a summary containing a colon round-trips`() {
            val summary = "The pitch: nobody else can do this offline."
            val md = NoteRenderer.render(draft(summary = summary), now, zone)
            assertEquals(summary, NoteParser.parse("a/b.md", md, now).summary)
        }

        @Test
        @DisplayName("tags are sanitised, so a comma cannot split one tag into two")
        fun `tag sanitising`() {
            val md = NoteRenderer.render(draft(tags = listOf("A, B", "with space", "UPPER", "wéird!")), now, zone)
            val parsed = NoteParser.parse("a/b.md", md, now)
            assertEquals(listOf("a-b", "with-space", "upper", "wéird"), parsed.tags)
            assertEquals(4, parsed.tags.size, "a comma must not create a fifth tag")
        }

        @Test
        fun `empty tags render as an empty list`() {
            val md = NoteRenderer.render(draft(tags = emptyList()), now, zone)
            assertTrue(md.contains("\ntags: []\n"), md)
            assertEquals(emptyList<String>(), NoteParser.parse("a/b.md", md, now).tags)
        }
    }

    @Nested
    @DisplayName("F3 the round-trip property")
    inner class RoundTrip {

        @Test
        fun `parse of render preserves every field`() {
            val d = draft()
            val md = NoteRenderer.render(d, now, zone)
            val parsed = NoteParser.parse("Projects/Positioning/offline-inference-is-the-moat.md", md, Instant.EPOCH)

            assertEquals(d.title, parsed.title)
            assertEquals(d.summary, parsed.summary)
            assertEquals(d.tags, parsed.tags)
            assertEquals(d.source, parsed.source)
            assertEquals("Projects/Positioning", parsed.folder)
            assertEquals("offline-inference-is-the-moat", parsed.slug)
            assertEquals(now, parsed.created)
            assertEquals(now, parsed.updated)
            assertTrue(parsed.bodyMarkdown.contains("Relates to [[BluePrint Lens]]"))
        }

        @Test
        @DisplayName("re-render is byte-stable, so content_hash does not churn")
        fun `rerender stability`() {
            val md = NoteRenderer.render(draft(), now, zone)
            val parsed = NoteParser.parse("a/b.md", md, now)
            val again = NoteRenderer.rerender(parsed, now, zone)
            assertEquals(md, again, "render -> parse -> rerender must be a fixed point")
        }

        @Test
        @DisplayName("created is preserved on re-render - the silent data loss F3 exists for")
        fun `created survives`() {
            val created = Instant.parse("2026-01-15T04:00:00Z")
            val md = NoteRenderer.render(draft(), created, zone)
            val parsed = NoteParser.parse("a/b.md", md, Instant.EPOCH)

            val later = Instant.parse("2026-09-01T09:02:11Z")
            val appended = NoteRenderer.rerender(parsed.copy(bodyMarkdown = parsed.bodyMarkdown + "\n\nmore"), later, zone)
            val reparsed = NoteParser.parse("a/b.md", appended, Instant.EPOCH)

            assertEquals(created, reparsed.created, "created must never move")
            assertEquals(later, reparsed.updated, "updated must move")
        }

        @Test
        @DisplayName("EC-N5 moved_from survives and accumulates")
        fun `moved from`() {
            val md = NoteRenderer.render(draft(), now, zone)
            val parsed = NoteParser.parse("Inbox/x.md", md, now)

            val moved = parsed.copy(movedFrom = listOf("Inbox/x.md"))
            val rendered = NoteRenderer.rerender(moved, now, zone)
            assertTrue(rendered.contains("moved_from: [\"Inbox/x.md\"]"), rendered)

            val again = NoteParser.parse("Projects/x.md", rendered, now)
            assertEquals(listOf("Inbox/x.md"), again.movedFrom)

            val twice = NoteRenderer.rerender(again.copy(movedFrom = again.movedFrom + "Projects/x.md"), now, zone)
            assertEquals(
                listOf("Inbox/x.md", "Projects/x.md"),
                NoteParser.parse("People/x.md", twice, now).movedFrom,
            )
        }

        @Test
        fun `moved_from is absent when the note has never moved`() {
            val md = NoteRenderer.render(draft(), now, zone)
            assertFalse(md.contains("moved_from"), md)
        }

        @Test
        @DisplayName("EC-V5 mixed-script titles survive untouched in frontmatter")
        fun `code switching`() {
            val title = "బ్లూప్రింట్ లెన్స్ and the moat"
            val md = NoteRenderer.render(draft(title = title), now, zone)
            assertEquals(title, NoteParser.parse("a/b.md", md, now).title)
            // The slug is transliterated (D-029), the title is not.
            assertTrue(md.contains(title), "the original script must be in the frontmatter")
        }
    }

    @Nested
    @DisplayName("EC-N10 the parser tolerates hand-edited files")
    inner class Tolerance {

        @Test
        fun `a file with no frontmatter is all body`() {
            val parsed = NoteParser.parse("Inbox/hand-written.md", "Just some text I typed.", now)
            assertEquals("Just some text I typed.", parsed.bodyMarkdown)
            assertEquals("Hand written", parsed.title, "title falls back to the de-slugged filename")
            assertEquals(now, parsed.created)
        }

        @Test
        fun `unquoted frontmatter written by a human is understood`() {
            val raw = """
                ---
                title: A plain title
                tags: [one, two]
                summary: no quotes here
                ---

                Body text.
            """.trimIndent()
            val parsed = NoteParser.parse("Inbox/a.md", raw, now)
            assertEquals("A plain title", parsed.title)
            assertEquals(listOf("one", "two"), parsed.tags)
            assertEquals("no quotes here", parsed.summary)
            assertEquals("Body text.", parsed.bodyMarkdown)
        }

        @Test
        fun `single-quoted scalars are understood`() {
            val raw = "---\ntitle: 'Single quoted'\n---\n\nBody."
            assertEquals("Single quoted", NoteParser.parse("a/b.md", raw, now).title)
        }

        @Test
        fun `an unterminated frontmatter block does not swallow the note`() {
            val raw = "---\ntitle: Broken\n\nThis is actually body text."
            val parsed = NoteParser.parse("Inbox/a.md", raw, now)
            assertTrue(parsed.bodyMarkdown.contains("This is actually body text."), parsed.bodyMarkdown)
        }

        @Test
        fun `CRLF input is normalised`() {
            val raw = "---\r\ntitle: \"Windows\"\r\n---\r\n\r\nBody line.\r\n"
            val parsed = NoteParser.parse("a/b.md", raw, now)
            assertEquals("Windows", parsed.title)
            assertFalse(parsed.bodyMarkdown.contains('\r'))
        }

        @Test
        fun `an unparseable timestamp falls back to the file time`() {
            val raw = "---\ntitle: \"X\"\ncreated: not-a-date\n---\n\nBody."
            assertEquals(now, NoteParser.parse("a/b.md", raw, now).created)
        }

        @Test
        fun `content hash changes with content and is stable otherwise`() {
            val a = NoteRenderer.render(draft(), now, zone)
            val b = NoteRenderer.render(draft(body = "different"), now, zone)
            assertEquals(NoteParser.sha256(a), NoteParser.sha256(a))
            assertTrue(NoteParser.sha256(a) != NoteParser.sha256(b))
            assertEquals(64, NoteParser.sha256(a).length, "SHA-256 hex is 64 chars")
        }
    }
}
