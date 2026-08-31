package com.secondbrain.vault

import com.ibm.icu.text.Transliterator
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Turns a spoken title into a filename.
 *
 * EC-N2 (illegal chars, whitespace, 80-char cap) and EC-N1 (collision suffixing).
 *
 * Titles are transliterated to ASCII rather than keeping their original script.
 * That is a deliberate choice by Udit (D-029) and it needs ICU: `java.text.Normalizer`
 * decomposes Latin diacritics only and produces an **empty string** for Telugu,
 * Devanagari and every other non-Latin script. Measured, before the dependency
 * was accepted:
 *
 *   బ్లూప్రింట్ లెన్స్   ->  bluprint-lens          (Normalizer alone: empty)
 *   ఇది చాలా బాగుంది    ->  idi-cala-bagundi       (Normalizer alone: empty)
 *   इदि बहुत अछ्छा है    ->  idi-bahuta-achcha-hai  (Normalizer alone: empty)
 *   Café résumé naïve  ->  cafe-resume-naive
 *
 * EC-V5 still holds: only the *filename* is transliterated. The frontmatter
 * `title` keeps the original script, untouched.
 */
class Slugifier(
    private val maxLength: Int = 80,
) {

    private val toAscii: Transliterator = Transliterator.getInstance("Any-Latn; Latin-ASCII")

    private val fallbackStamp = DateTimeFormatter
        .ofPattern("yyyy-MM-dd-HHmm")
        .withZone(ZoneId.systemDefault())

    /**
     * Slug for [title], without the `.md` extension.
     *
     * Always non-empty: a title of only emoji or punctuation transliterates to
     * nothing, which would otherwise produce an unnamed file. Verified — "🚀🚀🚀"
     * and "??? !!! ..." both reduce to zero characters. The timestamp fallback is
     * a safety net for exactly that case, not a second slug policy.
     */
    fun slugify(title: String, now: Instant = Instant.now()): String {
        val ascii = toAscii.transliterate(title)

        val slug = ascii
            .lowercase()
            // Anything that is not an unaccented letter or digit becomes a break.
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .let { truncateAtBoundary(it, maxLength) }

        return slug.ifEmpty { "note-" + fallbackStamp.format(now) }
    }

    /**
     * Truncates to [limit] characters, preferring a hyphen boundary so the slug
     * does not end mid-word. Never returns a trailing hyphen.
     */
    private fun truncateAtBoundary(slug: String, limit: Int): String {
        if (slug.length <= limit) return slug
        val cut = slug.take(limit)
        val lastBreak = cut.lastIndexOf('-')
        // Only honour the boundary if it keeps a useful amount of the title.
        val kept = if (lastBreak >= limit / 2) cut.take(lastBreak) else cut
        return kept.trim('-')
    }

    /**
     * First free slug in [folder], suffixing `-2`, `-3`, ... on collision.
     *
     * EC-N1 says append " 2" with a space; the filename in the design board is
     * `offline-inference-is-the-moat.md`, fully hyphenated, so a space would be
     * the only whitespace in any filename we produce. Hyphen instead (D-029).
     *
     * The suffix is appended to an *already shortened* base, so a title at the
     * 80-character cap cannot push the result over it. Getting that order wrong is
     * the easy bug here.
     *
     * [taken] is consulted case-insensitively: on this filesystem, writing
     * `TheMoat.md` and then `themoat.md` leaves one file and silently destroys the
     * first note's content. Verified. Our own slugs are always lowercase so we
     * cannot self-collide, but an externally created file can (F7 / D-035).
     */
    fun uniqueSlug(
        title: String,
        taken: Set<String>,
        now: Instant = Instant.now(),
    ): SlugResult {
        val takenLower = taken.map { it.lowercase().removeSuffix(".md") }.toSet()
        val base = slugify(title, now)

        if (base.lowercase() !in takenLower) return SlugResult(base, suffixed = false)

        var n = 2
        while (true) {
            val suffix = "-" + n
            val room = (maxLength - suffix.length).coerceAtLeast(1)
            val candidate = truncateAtBoundary(base, room) + suffix
            if (candidate.lowercase() !in takenLower) {
                return SlugResult(candidate, suffixed = true)
            }
            n++
            if (n > 9_999) {
                // Beyond any plausible collision count. Fall back to something
                // guaranteed unique rather than looping.
                return SlugResult(base.take(40).trim('-') + "-" + now.toEpochMilli(), suffixed = true)
            }
        }
    }

    data class SlugResult(val slug: String, val suffixed: Boolean)

    /**
     * Slug for a folder name.
     *
     * Folders keep their human casing on disk — the design board shows
     * `Projects/Positioning`, not `projects/positioning` — so this only strips
     * what is unsafe and collapses whitespace. It does not lowercase.
     */
    fun folderName(proposed: String): String {
        val ascii = toAscii.transliterate(proposed)
        return ascii
            .replace(Regex("[^\\p{Alnum} _-]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('.', '-', '_')
            .take(60)
            .trim()
    }
}
