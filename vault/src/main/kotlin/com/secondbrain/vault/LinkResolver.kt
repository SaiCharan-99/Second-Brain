package com.secondbrain.vault

import com.secondbrain.model.DanglingLink
import com.secondbrain.model.LinkRef
import com.secondbrain.model.VaultConfig
import kotlin.math.max

/**
 * Finds `[[wikilinks]]` and resolves them against the vault.
 *
 * EC-N7: a link that does not resolve is recorded as dangling and **the link text
 * is never rewritten**. EC-N8: fuzzy matching only above `link_fuzzy_threshold`
 * (0.85), because a wrong link is worse than no link.
 *
 * Three things here are not in the artifacts:
 *
 *  - **Alias and heading syntax** (F14). D-004 says files are Obsidian-shaped,
 *    and Obsidian has `[[target|alias]]`, `[[target#heading]]` and
 *    `[[target^block]]`. Parsing naively makes the target `Note|display text`,
 *    which can never resolve. Resolution uses the base target; `rawTarget` keeps
 *    the full original text so nothing is lost.
 *  - **Code spans are skipped** (F15). A note about this system will contain
 *    `` `[[wikilinks]]` `` in a code span, and treating that as a link produces a
 *    dangling entry to "wikilinks" on the first capture that mentions it.
 *  - **Ambiguity** (D-030). Two notes titled "Notes" in different folders means
 *    `[[Notes]]` has no single answer. It stays dangling with both candidates
 *    recorded, rather than silently pointing at one.
 */
class LinkResolver(
    private val config: VaultConfig,
) {

    /** A `[[...]]` occurrence found in a body. */
    data class Occurrence(
        /** Everything between the brackets, verbatim. Stored as `raw_target`. */
        val rawTarget: String,
        /** Target with `|alias`, `#heading` and `^block` removed. */
        val baseTarget: String,
        /** Character offset of the `[[` in the scanned body. Used for context extraction. */
        val start: Int,
        val end: Int,
    )

    /** A note the resolver can match against. */
    data class Candidate(val path: String, val title: String, val slug: String)

    data class Resolution(
        val resolved: List<LinkRef>,
        val dangling: List<DanglingLink>,
    )

    private val wikilink = Regex("""\[\[([^\[\]\n]+)]]""")

    /**
     * Extracts every wikilink occurrence, skipping fenced blocks and inline code.
     */
    fun findOccurrences(body: String): List<Occurrence> {
        val masked = maskCode(body)
        return wikilink.findAll(masked)
            .mapNotNull { match ->
                val raw = match.groupValues[1].trim()
                if (raw.isEmpty()) return@mapNotNull null
                Occurrence(
                    rawTarget = raw,
                    baseTarget = baseTarget(raw),
                    start = match.range.first,
                    end = match.range.last + 1,
                )
            }
            .filter { it.baseTarget.isNotEmpty() }
            .toList()
    }

    /**
     * Replaces code spans and fenced blocks with spaces of equal length.
     *
     * Blanking rather than deleting keeps every offset identical to the original
     * body, so context extraction still lines up.
     */
    internal fun maskCode(body: String): String {
        val out = StringBuilder(body)

        fun blank(range: IntRange) {
            for (i in range) if (out[i] != '\n') out[i] = ' '
        }

        // Fenced blocks first: a ``` block can contain backticks.
        Regex("""(?m)^[ \t]*(`{3,}|~{3,})[\s\S]*?^[ \t]*\1[ \t]*$""").findAll(body)
            .forEach { blank(it.range) }
        // An unterminated fence runs to the end of the note.
        Regex("""(?m)^[ \t]*(`{3,}|~{3,})[\s\S]*$""").find(out.toString())
            ?.let { blank(it.range) }
        // Inline code, longest runs first so ``a `b` c`` is handled.
        Regex("""(`{1,3})(?:(?!\1).)*?\1""").findAll(out.toString())
            .forEach { blank(it.range) }

        return out.toString()
    }

    /** Strips `|alias`, `#heading` and `^block`. */
    internal fun baseTarget(raw: String): String =
        raw.substringBefore('|')
            .substringBefore('#')
            .substringBefore('^')
            .trim()

    /**
     * Resolves every occurrence in [body] for the note at [fromPath].
     *
     * @param candidates every other note in the vault.
     */
    fun resolve(
        fromPath: String,
        body: String,
        candidates: Collection<Candidate>,
    ): Resolution {
        val resolved = LinkedHashMap<String, LinkRef>()
        val dangling = LinkedHashMap<String, DanglingLink>()

        findOccurrences(body).forEach { occurrence ->
            // Never link a note to itself.
            val pool = candidates.filter { it.path != fromPath }
            val match = bestMatch(occurrence.baseTarget, pool)

            when (match) {
                is Match.Unique ->
                    resolved[occurrence.rawTarget] = LinkRef(
                        fromPath = fromPath,
                        toPath = match.candidate.path,
                        rawTarget = occurrence.rawTarget,
                        score = match.score,
                    )

                is Match.Ambiguous ->
                    // D-030: a tie stays dangling. EC-N8's own reasoning - a wrong
                    // link is worse than no link - applies to ties, not just to
                    // weak matches.
                    dangling[occurrence.rawTarget] = DanglingLink(
                        fromPath = fromPath,
                        rawTarget = occurrence.rawTarget,
                        ambiguousCandidates = match.candidates.map { it.path }.sorted(),
                    )

                Match.None ->
                    dangling[occurrence.rawTarget] = DanglingLink(
                        fromPath = fromPath,
                        rawTarget = occurrence.rawTarget,
                    )
            }
        }

        return Resolution(resolved.values.toList(), dangling.values.toList())
    }

    private sealed interface Match {
        data class Unique(val candidate: Candidate, val score: Double) : Match
        data class Ambiguous(val candidates: List<Candidate>, val score: Double) : Match
        data object None : Match
    }

    private fun bestMatch(target: String, candidates: Collection<Candidate>): Match {
        if (candidates.isEmpty()) return Match.None

        // Exact match on title or slug, case-insensitive, wins outright.
        val exact = candidates.filter {
            it.title.equals(target, ignoreCase = true) || it.slug.equals(target, ignoreCase = true)
        }
        if (exact.size == 1) return Match.Unique(exact.single(), 1.0)
        if (exact.size > 1) return Match.Ambiguous(exact, 1.0)

        // A vault-relative path, with or without the extension.
        val byPath = candidates.filter {
            it.path.equals(target, ignoreCase = true) ||
                it.path.equals("$target.md", ignoreCase = true) ||
                it.path.removeSuffix(".md").equals(target, ignoreCase = true)
        }
        if (byPath.size == 1) return Match.Unique(byPath.single(), 1.0)
        if (byPath.size > 1) return Match.Ambiguous(byPath, 1.0)

        // Fuzzy, EC-N8: only at or above the threshold.
        val scored = candidates
            .map { it to similarity(target, it) }
            .filter { it.second >= config.linkFuzzyThreshold }
        if (scored.isEmpty()) return Match.None

        val topScore = scored.maxOf { it.second }
        val leaders = scored.filter { it.second >= topScore - 1e-9 }

        return if (leaders.size == 1) {
            Match.Unique(leaders.single().first, topScore)
        } else {
            Match.Ambiguous(leaders.map { it.first }, topScore)
        }
    }

    /** Best of title similarity and slug similarity. */
    internal fun similarity(target: String, candidate: Candidate): Double =
        maxOf(
            stringSimilarity(normalise(target), normalise(candidate.title)),
            stringSimilarity(normalise(target), normalise(candidate.slug)),
        )

    private fun normalise(s: String): String =
        s.lowercase().replace(Regex("[^a-z0-9]+"), "")

    /** 1 - normalised Levenshtein. Same measure the Folder Guard uses. */
    internal fun stringSimilarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return 1.0 - levenshtein(a, b).toDouble() / max(a.length, b.length).toDouble()
    }

    private fun levenshtein(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                current[j] = minOf(
                    previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1,
                    current[j - 1] + 1,
                    previous[j] + 1,
                )
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }

    /**
     * Text around a link occurrence, for the backlinks panel.
     *
     * The design board shows "…open on the moat, then the demo…" under each
     * backlink. There is no snippet column in the section 2 schema, so this is
     * extracted on read: always fresh, never stale, no schema change (F11).
     */
    fun contextAround(body: String, occurrence: Occurrence, radius: Int = 60): String {
        val from = (occurrence.start - radius).coerceAtLeast(0)
        val to = (occurrence.end + radius).coerceAtMost(body.length)

        val slice = body.substring(from, to)
            .replace(Regex("\\s+"), " ")
            .trim()

        val prefix = if (from > 0) "…" else ""
        val suffix = if (to < body.length) "…" else ""
        return prefix + slice + suffix
    }

    /**
     * Occurrences in [body] whose base target points at [targetTitle] or
     * [targetSlug]. Used to build backlink context, and to re-resolve dangling
     * links when a new note appears (F12).
     */
    fun occurrencesTargeting(body: String, targetTitle: String, targetSlug: String): List<Occurrence> =
        findOccurrences(body).filter {
            it.baseTarget.equals(targetTitle, ignoreCase = true) ||
                it.baseTarget.equals(targetSlug, ignoreCase = true) ||
                stringSimilarity(normalise(it.baseTarget), normalise(targetTitle)) >= config.linkFuzzyThreshold
        }
}
