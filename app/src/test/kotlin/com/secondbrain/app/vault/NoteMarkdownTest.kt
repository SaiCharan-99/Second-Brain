package com.secondbrain.app.vault

import com.secondbrain.app.AppColors
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Click *wiring* (does the embedded [androidx.compose.ui.text.LinkAnnotation]
 * actually fire on a real pointer event) is exercised manually — CLAUDE.md's
 * testing table marks `:app` "Manual. Compose UI tests are not worth the time
 * on this timeline." What is worth a plain JVM test, with no Compose runtime
 * needed to run it: the text this hand-rolled renderer produces, and which
 * spans it colours which way.
 */
class NoteMarkdownTest {

    private fun click(@Suppress("UNUSED_PARAMETER") target: String) = Unit

    @Nested
    @DisplayName("plain prose")
    inner class Prose {
        @Test
        fun `text with no markdown passes through unchanged`() {
            val out = NoteMarkdown.render("Just a thought, nothing fancy.", emptySet(), ::click)
            assertEquals("Just a thought, nothing fancy.", out.text)
        }

        @Test
        fun `multiple lines are preserved`() {
            val out = NoteMarkdown.render("First line.\nSecond line.", emptySet(), ::click)
            assertEquals("First line.\nSecond line.", out.text)
        }

        @Test
        fun `bold and italic markers are stripped from the visible text`() {
            val out = NoteMarkdown.render("This is **bold** and this is *italic*.", emptySet(), ::click)
            assertEquals("This is bold and this is italic.", out.text)
        }

        @Test
        fun `inline code keeps its content, loses its backticks`() {
            val out = NoteMarkdown.render("See `Projects/X/y.md` for the note.", emptySet(), ::click)
            assertEquals("See Projects/X/y.md for the note.", out.text)
        }

        @Test
        fun `a heading loses its hashes`() {
            val out = NoteMarkdown.render("## Section title", emptySet(), ::click)
            assertEquals("Section title", out.text)
        }

        @Test
        fun `a bullet becomes a dot prefix`() {
            val out = NoteMarkdown.render("- first item", emptySet(), ::click)
            assertEquals("•  first item", out.text)
        }
    }

    @Nested
    @DisplayName("[[wikilinks]]")
    inner class Wikilinks {
        @Test
        fun `a simple wikilink shows its target text without brackets`() {
            val out = NoteMarkdown.render("Relates to [[BluePrint Lens]].", emptySet(), ::click)
            assertEquals("Relates to BluePrint Lens.", out.text)
        }

        @Test
        fun `F14 alias syntax displays the alias, not the target`() {
            val out = NoteMarkdown.render("See [[BluePrint Lens|the other project]].", emptySet(), ::click)
            assertEquals("See the other project.", out.text)
        }

        @Test
        fun `F14 heading syntax is dropped from the display text`() {
            val out = NoteMarkdown.render("See [[BluePrint Lens#Pricing]].", emptySet(), ::click)
            assertEquals("See BluePrint Lens.", out.text)
        }

        @Test
        fun `a resolved link renders in the link colour, not the dangling colour`() {
            val out = NoteMarkdown.render("[[Existing Note]]", emptySet(), ::click)
            assertTrue(out.spanStyles.any { it.item.color == AppColors.Blue })
            assertFalse(out.spanStyles.any { it.item.color == AppColors.Dangling })
        }

        @Test
        fun `EC-N7-EC-N8 a dangling link renders visually distinct`() {
            val out = NoteMarkdown.render("[[No Such Note]]", setOf("No Such Note"), ::click)
            assertTrue(out.spanStyles.any { it.item.color == AppColors.Dangling })
            assertFalse(out.spanStyles.any { it.item.color == AppColors.Blue })
        }

        @Test
        fun `two links in one line both render`() {
            val out = NoteMarkdown.render("[[A]] and [[B]]", setOf("B"), ::click)
            assertEquals("A and B", out.text)
            assertEquals(1, out.spanStyles.count { it.item.color == AppColors.Blue })
            assertEquals(1, out.spanStyles.count { it.item.color == AppColors.Dangling })
        }
    }

    @Nested
    @DisplayName("displayTarget")
    inner class DisplayTargetTest {
        @Test
        fun `plain target is returned as-is`() {
            assertEquals("BluePrint Lens", NoteMarkdown.displayTarget("BluePrint Lens"))
        }

        @Test
        fun `alias wins over the raw target`() {
            assertEquals("the other project", NoteMarkdown.displayTarget("BluePrint Lens|the other project"))
        }

        @Test
        fun `heading suffix is dropped`() {
            assertEquals("BluePrint Lens", NoteMarkdown.displayTarget("BluePrint Lens#Pricing"))
        }

        @Test
        fun `an empty alias falls back to the raw target rather than showing nothing`() {
            assertEquals("BluePrint Lens|", NoteMarkdown.displayTarget("BluePrint Lens|"))
        }
    }
}
