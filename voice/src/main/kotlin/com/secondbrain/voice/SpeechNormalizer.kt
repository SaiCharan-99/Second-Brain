package com.secondbrain.voice

/**
 * Turns Markdown into something worth listening to (EC-T1).
 *
 * Claude replies in Markdown. Handed straight to TTS, that becomes "asterisk
 * asterisk important asterisk asterisk" and a read-aloud URL. This is the layer
 * that stops it, and it runs before every single synthesis call.
 *
 * Deliberately NOT a Markdown parser. A parser would be correct and slow to
 * write; a sequence of targeted rewrites over one paragraph of assistant prose
 * is what the job actually needs, and every rule below has a test.
 *
 * EC-V5 note: this normalises the ASSISTANT's output. Transcripts of the user's
 * speech are never touched -- mixed-script Telugu / Hindi / English is passed
 * through verbatim.
 */
object SpeechNormalizer {

    /**
     * Expanded because TTS reads them as letters or gets the stress wrong.
     *
     * Order matters. An abbreviation whose trailing dot may ALSO be the sentence
     * terminator gets a sentence-final rule first, otherwise expanding "etc." to
     * "and so on" eats the full stop and the sentence splitter that EC-T3 relies
     * on silently merges two sentences into one.
     */
    private val abbreviations: List<Pair<Regex, String>> = listOf(
        // Sentence-final forms: keep the terminator.
        Regex("""\betc\.(?=\s*$|\s+["'(\[\p{Lu}])""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
            to "and so on.",
        Regex("""\bapprox\.(?=\s*$|\s+["'(\[\p{Lu}])""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
            to "approximately.",
        // Mid-sentence forms.
        Regex("""\be\.g\.""", RegexOption.IGNORE_CASE) to "for example",
        Regex("""\bi\.e\.""", RegexOption.IGNORE_CASE) to "that is",
        Regex("""\betc\.""", RegexOption.IGNORE_CASE) to "and so on",
        Regex("""\bvs\.?\b""", RegexOption.IGNORE_CASE) to "versus",
        Regex("""\bapprox\.""", RegexOption.IGNORE_CASE) to "approximately",
        Regex("""\bw/o\b""", RegexOption.IGNORE_CASE) to "without",
        Regex("""\bw/\b""", RegexOption.IGNORE_CASE) to "with",
        Regex("""\bASAP\b""") to "as soon as possible",
        Regex("""\bFYI\b""") to "for your information",
        Regex("""\bTBD\b""") to "to be decided",
        Regex("""\bAKA\b""", RegexOption.IGNORE_CASE) to "also known as",
    )

    private val ordinals = listOf(
        "First", "Second", "Third", "Fourth", "Fifth", "Sixth",
        "Seventh", "Eighth", "Ninth", "Tenth",
    )

    private val sentenceEnd = Regex("""(?<=[.!?])\s+(?=[A-Z\p{Lu}"'(\[])""")

    fun normalize(markdown: String): String {
        var s = markdown

        s = stripCodeFences(s)
        s = stripImages(s)
        s = elideLinks(s)
        s = stripHeadings(s)
        s = bulletsToOrdinals(s)
        s = stripEmphasis(s)
        s = stripInlineCode(s)
        s = stripBlockquotes(s)
        s = stripTables(s)
        s = stripRules(s)
        s = expandAbbreviations(s)
        s = collapseWhitespace(s)

        return s.trim()
    }

    // ── individual rules ────────────────────────────────────────────────────

    /** A fenced block is never speech. Say that it exists and move on. */
    internal fun stripCodeFences(s: String): String =
        s.replace(Regex("""```[\s\S]*?```"""), " a code block ")
            .replace(Regex("""```[\s\S]*$"""), " a code block ")

    internal fun stripImages(s: String): String =
        s.replace(Regex("""!\[([^\]]*)]\([^)]*\)""")) { m ->
            val alt = m.groupValues[1].trim()
            if (alt.isEmpty()) " an image " else " an image of $alt "
        }

    /** "[the docs](https://…)" becomes "the docs"; a bare URL becomes "a link". */
    internal fun elideLinks(s: String): String =
        s.replace(Regex("""\[([^\]]+)]\([^)]*\)""")) { it.groupValues[1] }
            .replace(Regex("""<(https?://[^>]+)>"""), " a link ")
            .replace(Regex("""\bhttps?://\S+"""), " a link ")
            .replace(Regex("""\bwww\.\S+"""), " a link ")

    internal fun stripHeadings(s: String): String =
        s.lineSequence()
            .map { line -> line.replace(Regex("""^\s{0,3}#{1,6}\s+"""), "") }
            .joinToString("\n")

    /**
     * Bullets and numbered items become "First, ... Second, ..." so a list is
     * audible as a list. Past ten items it falls back to a plain pause, because
     * "Fourteenth" in the middle of a sentence is worse than nothing.
     */
    internal fun bulletsToOrdinals(s: String): String {
        val out = StringBuilder()
        var index = 0
        var inList = false

        s.lineSequence().forEach { rawLine ->
            val bullet = Regex("""^\s*(?:[-*+]|\d{1,2}[.)])\s+(.*)$""").find(rawLine)
            if (bullet != null) {
                val body = bullet.groupValues[1].trim()
                if (body.isEmpty()) return@forEach
                val prefix = if (index < ordinals.size) "${ordinals[index]}, " else ""
                out.append(prefix).append(body)
                if (!body.endsWith(".") && !body.endsWith("?") && !body.endsWith("!")) out.append('.')
                out.append(' ')
                index++
                inList = true
            } else {
                if (inList) { index = 0; inList = false }
                out.append(rawLine).append('\n')
            }
        }
        return out.toString()
    }

    internal fun stripEmphasis(s: String): String =
        s.replace(Regex("""\*\*\*(.+?)\*\*\*"""), "$1")
            .replace(Regex("""\*\*(.+?)\*\*"""), "$1")
            .replace(Regex("""(?<![\w*])\*(?!\s)(.+?)(?<!\s)\*(?![\w*])"""), "$1")
            .replace(Regex("""___(.+?)___"""), "$1")
            .replace(Regex("""__(.+?)__"""), "$1")
            .replace(Regex("""(?<![\w_])_(?!\s)(.+?)(?<!\s)_(?![\w_])"""), "$1")
            .replace(Regex("""~~(.+?)~~"""), "$1")

    /**
     * Backticks are dropped but the content is kept: a note path like
     * `Projects/Positioning` is exactly what the user needs to hear.
     */
    internal fun stripInlineCode(s: String): String =
        s.replace(Regex("""`([^`]+)`"""), "$1")

    internal fun stripBlockquotes(s: String): String =
        s.lineSequence().map { it.replace(Regex("""^\s{0,3}>\s?"""), "") }.joinToString("\n")

    /** Tables do not survive speech. Say so rather than reading pipes. */
    internal fun stripTables(s: String): String {
        val lines = s.lines()
        val isDivider = { l: String -> Regex("""^\s*\|?[\s:|-]{4,}\|?\s*$""").matches(l) && l.contains('-') }
        if (lines.none { isDivider(it) && it.contains('|') }) return s

        val out = mutableListOf<String>()
        var announced = false
        lines.forEach { line ->
            val looksTabular = line.count { it == '|' } >= 2
            if (looksTabular || (isDivider(line) && line.contains('|'))) {
                if (!announced) { out += "There's a table on screen."; announced = true }
            } else {
                if (line.isNotBlank()) announced = false
                out += line
            }
        }
        return out.joinToString("\n")
    }

    internal fun stripRules(s: String): String =
        s.lineSequence()
            .filterNot { Regex("""^\s*([-*_])(\s*\1){2,}\s*$""").matches(it) }
            .joinToString("\n")

    internal fun expandAbbreviations(s: String): String =
        abbreviations.fold(s) { acc, (re, replacement) -> acc.replace(re, replacement) }

    internal fun collapseWhitespace(s: String): String =
        s.replace(Regex("""[ \t]+"""), " ")
            .replace(Regex("""\s*\n\s*\n\s*"""), " ")
            .replace(Regex("""\s*\n\s*"""), " ")
            .replace(Regex(""" {2,}"""), " ")
            .replace(Regex("""\s+([.,;:!?])"""), "$1")

    // ── sentence splitting, for EC-T2 and EC-T3 ─────────────────────────────

    /**
     * Splits normalised text into sentences.
     *
     * EC-T3 sends the first sentence to TTS immediately rather than waiting for
     * the whole reply, which is the main lever on perceived latency.
     */
    fun sentences(normalized: String): List<String> {
        if (normalized.isBlank()) return emptyList()
        return normalized.split(sentenceEnd).map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * EC-T2: hard cap on spoken output, truncated at a sentence boundary.
     *
     * Returns the speakable part plus whether anything was dropped, so the caller
     * can offer "there's more -- want the rest, or is it on screen?" rather than
     * just stopping.
     */
    data class Capped(val text: String, val truncated: Boolean, val estimatedSeconds: Double)

    /** ~2.6 words/second is a natural TTS pace. Good enough to cap on. */
    private const val WORDS_PER_SECOND = 2.6

    fun capForSpeech(normalized: String, maxSeconds: Int): Capped {
        val all = sentences(normalized)
        if (all.isEmpty()) return Capped("", false, 0.0)

        val kept = mutableListOf<String>()
        var words = 0
        val budgetWords = (maxSeconds * WORDS_PER_SECOND).toInt()

        for (sentence in all) {
            val w = sentence.split(Regex("""\s+""")).count { it.isNotBlank() }
            if (kept.isNotEmpty() && words + w > budgetWords) break
            kept += sentence
            words += w
        }

        return Capped(
            text = kept.joinToString(" "),
            truncated = kept.size < all.size,
            estimatedSeconds = words / WORDS_PER_SECOND,
        )
    }
}
