package com.secondbrain.voice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * EC-T1. CLAUDE.md's testing table names SpeechNormalizer as the one part of
 * :voice that gets real unit tests, because it is the only part that is pure.
 */
class SpeechNormalizerTest {

    @Nested
    @DisplayName("EC-T1 emphasis and markup never reach the TTS endpoint")
    inner class Emphasis {

        @Test
        fun `strips bold, italic and strikethrough`() {
            assertEquals(
                "This is important and this is subtle and this is gone.",
                SpeechNormalizer.normalize(
                    "This is **important** and this is *subtle* and this is ~~gone~~."
                ),
            )
        }

        @Test
        fun `strips underscore emphasis without eating snake_case`() {
            assertEquals(
                "The vault_write_note tool is autonomous.",
                SpeechNormalizer.normalize("The vault_write_note tool is _autonomous_."),
            )
        }

        @Test
        fun `strips triple emphasis`() {
            assertEquals("Never.", SpeechNormalizer.normalize("***Never***."))
        }

        @Test
        fun `does not leave stray asterisks anywhere`() {
            val out = SpeechNormalizer.normalize("**a** *b* ***c*** ~~d~~ __e__ _f_")
            assertFalse(out.contains('*'), "found an asterisk in: $out")
            assertFalse(out.contains('~'), "found a tilde in: $out")
        }
    }

    @Nested
    @DisplayName("EC-T1 headings, quotes, rules and tables")
    inner class Structure {

        @Test
        fun `strips heading markers but keeps the text`() {
            assertEquals("Placement rules", SpeechNormalizer.normalize("## Placement rules"))
        }

        @Test
        fun `strips blockquote markers`() {
            assertEquals("He said no.", SpeechNormalizer.normalize("> He said no."))
        }

        @Test
        fun `drops horizontal rules`() {
            assertEquals("Before After", SpeechNormalizer.normalize("Before\n\n---\n\nAfter"))
        }

        @Test
        fun `announces a table instead of reading pipes`() {
            val out = SpeechNormalizer.normalize(
                """
                Here are the tools:

                | Tool | Class |
                |---|---|
                | vault_read | autonomous |
                | email_draft | gated |
                """.trimIndent()
            )
            assertTrue(out.contains("table on screen"), out)
            assertFalse(out.contains('|'), "read a pipe aloud: $out")
        }
    }

    @Nested
    @DisplayName("EC-T1 code and links")
    inner class CodeAndLinks {

        @Test
        fun `keeps inline code content because note paths must be audible`() {
            assertEquals(
                "Saved to Projects/Positioning as your note.",
                SpeechNormalizer.normalize("Saved to `Projects/Positioning` as your note."),
            )
        }

        @Test
        fun `replaces a fenced block with a mention`() {
            val out = SpeechNormalizer.normalize("Try this:\n```kotlin\nval x = 1\n```\nDone.")
            assertTrue(out.contains("a code block"), out)
            assertFalse(out.contains("val x"), out)
        }

        @Test
        fun `keeps link text and drops the URL`() {
            assertEquals(
                "See the design board for details.",
                SpeechNormalizer.normalize("See [the design board](https://example.com/x?y=1) for details."),
            )
        }

        @Test
        fun `elides a bare URL to a link`() {
            val out = SpeechNormalizer.normalize("It is at https://example.com/very/long/path now.")
            assertEquals("It is at a link now.", out)
        }

        @Test
        fun `describes an image by its alt text`() {
            assertEquals(
                "an image of a grocery list",
                SpeechNormalizer.normalize("![a grocery list](file.png)"),
            )
        }
    }

    @Nested
    @DisplayName("EC-T1 bullets become ordinals")
    inner class Bullets {

        @Test
        fun `dash bullets become first second third`() {
            val out = SpeechNormalizer.normalize("- capture\n- email\n- calendar")
            assertEquals("First, capture. Second, email. Third, calendar.", out)
        }

        @Test
        fun `numbered lists become ordinals too`() {
            val out = SpeechNormalizer.normalize("1. one\n2. two")
            assertEquals("First, one. Second, two.", out)
        }

        @Test
        fun `keeps existing terminal punctuation`() {
            assertEquals("First, done. Second, also done.", SpeechNormalizer.normalize("- done.\n- also done."))
        }

        @Test
        fun `past ten items it stops numbering rather than saying fourteenth`() {
            val out = SpeechNormalizer.normalize((1..12).joinToString("\n") { "- item $it" })
            assertTrue(out.startsWith("First, item 1."), out)
            assertTrue(out.contains("Tenth, item 10."), out)
            assertFalse(out.contains("Eleventh"), out)
            assertTrue(out.contains("item 11."), out)
        }

        @Test
        fun `numbering restarts after a paragraph break`() {
            val out = SpeechNormalizer.normalize("- a\n- b\n\nProse.\n\n- c")
            assertTrue(out.contains("First, a."), out)
            assertTrue(out.contains("Second, b."), out)
            assertTrue(out.contains("First, c."), out)
        }
    }

    @Nested
    @DisplayName("EC-T1 abbreviation expansion")
    inner class Abbreviations {

        @Test
        fun `expands latin abbreviations`() {
            assertEquals(
                "for example this, that is that, and so on.",
                SpeechNormalizer.normalize("e.g. this, i.e. that, etc."),
            )
        }

        @Test
        fun `expands versus and initialisms`() {
            assertEquals("Compose versus JavaFX, as soon as possible.", SpeechNormalizer.normalize("Compose vs JavaFX, ASAP."))
        }
    }

    @Nested
    @DisplayName("EC-T2 spoken-length cap")
    inner class Cap {

        private val long = (1..40).joinToString(" ") { "Sentence number $it is here." }

        @Test
        fun `truncates at a sentence boundary and reports it`() {
            val capped = SpeechNormalizer.capForSpeech(SpeechNormalizer.normalize(long), maxSeconds = 10)
            assertTrue(capped.truncated, "expected truncation")
            assertTrue(capped.text.endsWith("."), "did not end at a sentence boundary: ${capped.text}")
            assertTrue(capped.estimatedSeconds <= 12.0, "estimate ${capped.estimatedSeconds}s exceeded the cap")
        }

        @Test
        fun `short text is not truncated`() {
            val capped = SpeechNormalizer.capForSpeech("Saved to Inbox.", maxSeconds = 60)
            assertFalse(capped.truncated)
            assertEquals("Saved to Inbox.", capped.text)
        }

        @Test
        fun `a single over-long sentence is still spoken rather than dropped`() {
            val one = "word ".repeat(500).trim() + "."
            val capped = SpeechNormalizer.capForSpeech(one, maxSeconds = 5)
            assertFalse(capped.text.isBlank(), "an over-long single sentence must not vanish")
        }

        @Test
        fun `empty input is safe`() {
            val capped = SpeechNormalizer.capForSpeech("", maxSeconds = 60)
            assertEquals("", capped.text)
            assertFalse(capped.truncated)
        }
    }

    @Nested
    @DisplayName("EC-T3 sentence splitting for streaming")
    inner class Sentences {

        @Test
        fun `splits on terminal punctuation`() {
            assertEquals(
                listOf("Saved to Projects.", "One wikilink resolved.", "Anything else?"),
                SpeechNormalizer.sentences("Saved to Projects. One wikilink resolved. Anything else?"),
            )
        }

        @Test
        fun `does not split mid-abbreviation after normalisation`() {
            val normalized = SpeechNormalizer.normalize("Use it for example here. Then stop.")
            assertEquals(2, SpeechNormalizer.sentences(normalized).size)
        }
    }

    @Nested
    @DisplayName("EC-V5 code-switched text is not damaged")
    inner class CodeSwitching {

        @Test
        fun `mixed script passes through untouched`() {
            val input = "Idi oka manchi idea, but I need to check the numbers first."
            assertEquals(input, SpeechNormalizer.normalize(input))
        }

        @Test
        fun `devanagari and telugu characters survive`() {
            val input = "यह अच्छा है and ఇది బాగుంది too."
            assertEquals(input, SpeechNormalizer.normalize(input))
        }
    }

    @Test
    fun `collapses newlines and runs of whitespace into speakable prose`() {
        assertEquals(
            "One two three.",
            SpeechNormalizer.normalize("One    two\n\n\nthree."),
        )
    }

    @Test
    fun `a realistic assistant reply is fully clean`() {
        val reply = """
            ## Saved

            I filed it under `Projects/Positioning` as **"Offline inference is the moat"**.

            - One wikilink resolved to [[BluePrint Lens]]
            - One is dangling: [[Competition Demo Plan]]

            See [the note](https://example.com/n/1) — e.g. for the backlinks panel.
        """.trimIndent()

        val out = SpeechNormalizer.normalize(reply)

        assertFalse(out.contains('*'), out)
        assertFalse(out.contains('`'), out)
        assertFalse(out.contains('#'), out)
        assertFalse(out.contains("https"), out)
        assertTrue(out.contains("Projects/Positioning"), out)
        assertTrue(out.contains("First, One wikilink resolved"), out)
        assertTrue(out.contains("for example"), out)
        // Wikilinks are intentionally left intact: EC-N7 says link text is never
        // silently rewritten, and the brackets are inaudible anyway.
        assertTrue(out.contains("BluePrint Lens"), out)
    }
}
