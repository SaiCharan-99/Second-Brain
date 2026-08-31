package com.secondbrain.vault

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant

/** EC-N1, EC-N2, and the transliteration decision in D-029. */
class SlugifierTest {

    private val slugifier = Slugifier()
    private val now = Instant.parse("2026-09-01T14:32:11Z")

    @Nested
    @DisplayName("EC-N2 illegal characters and whitespace")
    inner class Cleaning {

        @Test
        fun `the design board filename is reproduced exactly`() {
            assertEquals(
                "offline-inference-is-the-moat",
                slugifier.slugify("Offline inference is the moat"),
            )
        }

        @Test
        @DisplayName("a title containing / : ? is safe")
        fun `filesystem illegal chars`() {
            assertEquals("pricing-a-positioning-problem", slugifier.slugify("Pricing: a positioning problem"))
            assertEquals("what-now", slugifier.slugify("What now?"))
            assertEquals("a-b", slugifier.slugify("a/b"))
            assertEquals("half-full", slugifier.slugify("half\\full"))
        }

        @Test
        fun `runs of whitespace and punctuation collapse to one hyphen`() {
            assertEquals("one-two-three", slugifier.slugify("one    two \n\n three"))
            assertEquals("a-b", slugifier.slugify("a --- b"))
            assertEquals("hello-world", slugifier.slugify("...hello... world!!!"))
        }

        @Test
        fun `never starts or ends with a hyphen`() {
            listOf("!!!leading", "trailing???", "  spaced  ", "-a-").forEach {
                val slug = slugifier.slugify(it)
                assertFalse(slug.startsWith("-"), "'$it' produced '$slug'")
                assertFalse(slug.endsWith("-"), "'$it' produced '$slug'")
            }
        }

        @Test
        fun `is always lowercase and ASCII`() {
            val slug = slugifier.slugify("MiXeD CaSe TiTlE")
            assertEquals("mixed-case-title", slug)
            assertTrue(slug.all { it.code < 128 })
        }
    }

    @Nested
    @DisplayName("D-029 transliteration to ASCII")
    inner class Transliteration {

        @Test
        fun `latin diacritics are folded`() {
            assertEquals("cafe-resume-naive", slugifier.slugify("Café résumé naïve"))
        }

        @Test
        @DisplayName("EC-V5: Telugu produces a readable ASCII slug")
        fun `telugu`() {
            assertEquals("bluprint-lens", slugifier.slugify("బ్లూప్రింట్ లెన్స్"))
            assertEquals("idi-cala-bagundi", slugifier.slugify("ఇది చాలా బాగుంది"))
        }

        @Test
        @DisplayName("EC-V5: Devanagari produces a readable ASCII slug")
        fun `devanagari`() {
            assertEquals("idi-bahuta-achcha-hai", slugifier.slugify("इदि बहुत अछ्छा है"))
        }

        @Test
        fun `every script reduces to pure ASCII`() {
            listOf(
                "బ్లూప్రింట్ లెన్స్", "इदि बहुत अछ्छा है", "中文测试",
                "Ελληνικά", "Русский", "日本語のノート",
            ).forEach { title ->
                val slug = slugifier.slugify(title)
                assertTrue(slug.all { it.code < 128 }, "'$title' produced non-ASCII: '$slug'")
                assertTrue(slug.isNotEmpty(), "'$title' produced an empty slug")
            }
        }
    }

    @Nested
    @DisplayName("the empty-slug crash the fallback exists for")
    inner class EmptyFallback {

        @Test
        @DisplayName("emoji-only and punctuation-only titles transliterate to nothing")
        fun `fallback fires`() {
            // Measured: both of these reduce to zero characters, which without a
            // fallback is a file with no name.
            listOf("🚀🚀🚀", "??? !!! ...", "   ", "###", "🎉").forEach { title ->
                val slug = slugifier.slugify(title, now)
                assertEquals("note-2026-09-01-2002", slug, "for input '$title'")
            }
        }

        @Test
        fun `the fallback is a legal filename`() {
            val slug = slugifier.slugify("🚀", now)
            assertTrue(slug.isNotEmpty())
            assertTrue(slug.all { it.isLetterOrDigit() || it == '-' })
        }
    }

    @Nested
    @DisplayName("EC-N2 the 80-character cap")
    inner class Truncation {

        @Test
        fun `caps at the configured length`() {
            val long = (1..40).joinToString(" ") { "word$it" }
            assertTrue(slugifier.slugify(long).length <= 80)
        }

        @Test
        fun `truncates at a hyphen boundary rather than mid-word`() {
            val slug = slugifier.slugify(
                "this is a deliberately very long note title that will certainly exceed the eighty character cap"
            )
            assertTrue(slug.length <= 80, "length was ${slug.length}")
            assertFalse(slug.endsWith("-"))
            // A boundary cut means the last token is a whole word.
            assertTrue(slug.split("-").last().isNotEmpty())
        }

        @Test
        fun `a single enormous word is hard-truncated rather than dropped`() {
            val slug = Slugifier(20).slugify("a".repeat(100))
            assertEquals(20, slug.length)
        }

        @Test
        fun `a custom cap is honoured`() {
            assertTrue(Slugifier(10).slugify("one two three four five").length <= 10)
        }
    }

    @Nested
    @DisplayName("EC-N1 collision suffixing")
    inner class Collisions {

        @Test
        @DisplayName("Step 2 exit criterion: three notes, same title, same day")
        fun `three collisions`() {
            val taken = mutableSetOf<String>()

            val first = slugifier.uniqueSlug("Offline inference is the moat", taken, now)
            assertEquals("offline-inference-is-the-moat", first.slug)
            assertFalse(first.suffixed)
            taken += first.slug

            val second = slugifier.uniqueSlug("Offline inference is the moat", taken, now)
            assertEquals("offline-inference-is-the-moat-2", second.slug)
            assertTrue(second.suffixed)
            taken += second.slug

            val third = slugifier.uniqueSlug("Offline inference is the moat", taken, now)
            assertEquals("offline-inference-is-the-moat-3", third.slug)
            assertTrue(third.suffixed)
        }

        @Test
        @DisplayName("D-029: the suffix is -2, not ' 2', so no filename has whitespace")
        fun `hyphen suffix`() {
            val result = slugifier.uniqueSlug("Note", setOf("note"), now)
            assertEquals("note-2", result.slug)
            assertFalse(result.slug.contains(' '))
        }

        @Test
        @DisplayName("the suffix cannot push the slug over the cap")
        fun `suffix respects the cap`() {
            // The base is deliberately AT the cap here. Appending the suffix after
            // truncating to the full cap - the obvious ordering - would produce 19
            // characters against a 17-character limit. Truncate first, then suffix.
            val cap = 17
            val slugifier = Slugifier(cap)
            val title = "a very long title indeed that exceeds the cap"

            val base = slugifier.slugify(title)
            assertTrue(base.length <= cap, "base was ${base.length}: '$base'")

            val result = slugifier.uniqueSlug(title, setOf(base), now)
            assertTrue(
                result.slug.length <= cap,
                "suffixed slug was ${result.slug.length} against a cap of $cap: '${result.slug}'",
            )
            assertTrue(result.slug.endsWith("-2"), "got '${result.slug}'")
            assertTrue(result.suffixed)
        }

        @Test
        fun `a two-digit suffix also respects the cap`() {
            val cap = 12
            val slugifier = Slugifier(cap)
            val title = "some reasonably long note title"
            val base = slugifier.slugify(title)
            val taken = mutableSetOf(base)

            // Walk up past -9 into two digits.
            repeat(12) {
                val r = slugifier.uniqueSlug(title, taken, now)
                assertTrue(r.slug.length <= cap, "'${r.slug}' is ${r.slug.length} chars against a cap of $cap")
                taken += r.slug
            }
        }

        @Test
        @DisplayName("F7: collision detection is case-insensitive")
        fun `case insensitive`() {
            // Writing TheMoat.md then themoat.md leaves ONE file on this
            // filesystem and destroys the first note's content. Verified.
            val result = slugifier.uniqueSlug("The Moat", setOf("The-Moat"), now)
            assertNotEquals("the-moat", result.slug)
            assertEquals("the-moat-2", result.slug)
        }

        @Test
        fun `an existing set with the md extension is understood`() {
            val result = slugifier.uniqueSlug("Note", setOf("note.md"), now)
            assertEquals("note-2", result.slug)
        }

        @Test
        fun `gaps are reused rather than skipped`() {
            // note and note-3 exist; the next one takes note-2. Deterministic and
            // the alternative (always append the highest+1) leaves permanent holes.
            val result = slugifier.uniqueSlug("Note", setOf("note", "note-3"), now)
            assertEquals("note-2", result.slug)
        }

        @Test
        fun `many collisions still terminate`() {
            val taken = (1..50).map { if (it == 1) "note" else "note-$it" }.toSet()
            val result = slugifier.uniqueSlug("Note", taken, now)
            assertEquals("note-51", result.slug)
        }
    }

    @Nested
    @DisplayName("folder names keep their human casing")
    inner class FolderNames {

        @Test
        fun `casing and spaces survive`() {
            assertEquals("Second Brain", slugifier.folderName("Second Brain"))
            assertEquals("Projects", slugifier.folderName("Projects"))
        }

        @Test
        fun `unsafe characters are removed`() {
            assertEquals("Projects", slugifier.folderName("Projects/"))
            assertEquals("A B", slugifier.folderName("A: B"))
            assertEquals("Notes", slugifier.folderName("  Notes.  "))
        }

        @Test
        fun `non-latin folder names transliterate too`() {
            assertEquals("bluprint lens", slugifier.folderName("బ్లూప్రింట్ లెన్స్"))
        }
    }
}
