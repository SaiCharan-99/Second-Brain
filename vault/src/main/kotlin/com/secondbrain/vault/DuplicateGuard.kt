package com.secondbrain.vault

import com.secondbrain.model.VaultConfig
import kotlin.math.max

/**
 * EC-N9, built the same way the Folder Guard is built.
 *
 * The architecture specifies this as a prompt instruction: "before
 * `vault_write_note`, search for near-duplicates and offer append". That relies
 * on the model choosing to search first — which is exactly the mechanism D-007
 * argues at length does not survive contact: *"a prompt saying 'reuse existing
 * folders where possible' does not survive two hundred captures."* If that
 * reasoning holds for folders it holds for duplicates, so Udit chose the
 * deterministic gate (D-053).
 *
 * The other half of EC-N9 is that its stated measure does not exist. It says
 * "cosine/FTS similarity is very high" — there is no cosine anywhere in this
 * system, and FTS5 `rank` is an unbounded negative bm25 score that is not
 * comparable across queries, so "very high" is not expressible with it. This
 * compares summaries with the same normalised-Levenshtein and Jaccard machinery
 * the Folder Guard already uses: bounded 0..1, deterministic, and testable
 * offline (D-053).
 *
 * A rejection is never a wall. The model can write the note anyway by setting
 * `confirm_new`, because two genuinely distinct thoughts about one subject must
 * remain writable.
 */
class DuplicateGuard(
    private val config: VaultConfig,
) {

    sealed interface Verdict {
        data object Distinct : Verdict

        data class Duplicate(
            val existingPath: String,
            val existingTitle: String,
            val score: Double,
            /** Which field drove the match, for the message the model reads. */
            val matchedOn: String,
        ) : Verdict
    }

    /** A note to compare against. */
    data class Candidate(val path: String, val title: String, val summary: String)

    /**
     * Compares a proposed note against existing ones.
     *
     * Title and summary are **combined**, weighted toward the title, rather than
     * taking whichever scores higher. Taking the max was the first design and it
     * was wrong: summaries are model-generated and often generically phrased, so
     * one shared summary made two entirely unrelated notes look identical. With a
     * weighting, summary agreement alone contributes at most `1 - TITLE_WEIGHT`,
     * which is below any sane threshold - a duplicate has to actually be about
     * the same thing.
     *
     * When either summary is blank (a stub, or a note written without one) the
     * title carries the whole score, since there is nothing else to go on.
     */
    fun evaluate(
        title: String,
        summary: String,
        candidates: Collection<Candidate>,
    ): Verdict {
        if (candidates.isEmpty()) return Verdict.Distinct

        var best: Verdict.Duplicate? = null

        candidates.forEach { candidate ->
            val titleScore = similarity(title, candidate.title)
            val hasSummaries = summary.isNotBlank() && candidate.summary.isNotBlank()
            val summaryScore = if (hasSummaries) similarity(summary, candidate.summary) else 0.0

            val score = if (hasSummaries) {
                TITLE_WEIGHT * titleScore + (1.0 - TITLE_WEIGHT) * summaryScore
            } else {
                titleScore
            }
            val matchedOn = when {
                !hasSummaries -> "title"
                titleScore >= summaryScore -> "title and summary"
                else -> "summary"
            }

            val current = best
            if (score >= config.duplicateSimilarityThreshold && (current == null || score > current.score)) {
                best = Verdict.Duplicate(candidate.path, candidate.title, score, matchedOn)
            }
        }

        return best ?: Verdict.Distinct
    }

    /**
     * 0..1 similarity of two pieces of prose.
     *
     * Same shape as [FolderGuard.score] — weighted token Jaccard plus normalised
     * edit distance — because the two guards should agree about what "similar"
     * means, and because that machinery is already tested.
     *
     * Token overlap carries most of the weight here. Spoken restatements of one
     * thought reuse the words and reorder them, which Jaccard catches and edit
     * distance does not.
     */
    internal fun similarity(a: String, b: String): Double {
        val tokensA = tokenise(a)
        val tokensB = tokenise(b)
        if (tokensA.isEmpty() || tokensB.isEmpty()) return 0.0

        val jaccard = tokensA.intersect(tokensB).size.toDouble() / tokensA.union(tokensB).size.toDouble()

        val normalisedA = a.lowercase().replace(Regex("[^a-z0-9]"), "")
        val normalisedB = b.lowercase().replace(Regex("[^a-z0-9]"), "")
        val editSimilarity = if (normalisedA.isEmpty() || normalisedB.isEmpty()) 0.0
        else 1.0 - levenshtein(normalisedA, normalisedB).toDouble() / max(normalisedA.length, normalisedB.length)

        val jw = config.duplicateJaccardWeight
        return jw * jaccard + (1.0 - jw) * editSimilarity
    }

    /**
     * Lowercase, strip punctuation, drop stopwords.
     *
     * Stopwords are dropped here but not in the Folder Guard, and the difference
     * is deliberate: folder names are one or two content words where "the" never
     * appears, while a spoken summary is a sentence where shared function words
     * would inflate every pair's overlap toward a false match.
     */
    internal fun tokenise(text: String): Set<String> =
        text.lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .split(' ')
            .filter { it.isNotBlank() && it.length > 2 && it !in STOPWORDS }
            .toSet()

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

    private companion object {
        /**
         * How much of the combined score the title carries.
         *
         * High enough that a shared summary alone tops out at 0.35, comfortably
         * under the 0.78 default threshold. Two notes are duplicates when they are
         * about the same thing, and the title is what says what a note is about.
         */
        const val TITLE_WEIGHT = 0.65

        val STOPWORDS = setOf(
            "the", "and", "for", "that", "this", "with", "not", "but", "you", "are",
            "was", "were", "have", "has", "had", "our", "his", "her", "its", "they",
            "them", "then", "than", "from", "into", "about", "would", "could", "should",
            "there", "their", "what", "when", "which", "who", "will", "just", "how",
        )
    }
}
