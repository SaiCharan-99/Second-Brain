package com.secondbrain.vault

import com.secondbrain.model.FolderVerdict
import com.secondbrain.model.VaultConfig
import kotlin.math.max

/**
 * Stops the vault turning into ninety top-level folders inside a week.
 *
 * Section 5 WF-1 calls this "the single most important quality component"; D-007
 * is explicit that a prompt saying "reuse existing folders where possible" does
 * not survive two hundred captures. So it is deterministic Kotlin that intercepts
 * `vault_create_folder`, and the model has to respond to a structured rejection.
 *
 * Scoring, straight from section 5:
 *
 *   0.6 * jaccard(tokens) + 0.4 * (1 - normalisedLevenshtein(slug))
 *
 * Compared against **every** existing folder, not just siblings (D-030). One
 * folder per concept anywhere in the vault; `Projects/Reading` gets redirected to
 * a top-level `Reading` if that exists.
 *
 * Thresholds live in [VaultConfig] (R7) so Step 3 can tune them against 20 real
 * captures. Worth knowing before you try: because Jaccard over single-token names
 * is binary, single-word comparisons land at ~0.95 or below ~0.40 and the 0.72
 * threshold cannot move them. It only bites on multi-word names.
 */
class FolderGuard(
    private val config: VaultConfig,
    private val slugifier: Slugifier = Slugifier(),
) {

    /**
     * @param proposed vault-relative folder path, e.g. `Projects/Positioning`.
     * @param existingFolders every folder currently in the vault, vault-relative.
     */
    fun evaluate(proposed: String, existingFolders: Set<String>): FolderVerdict {
        val cleanedSegments = proposed
            .replace('\\', '/')
            .split('/')
            .map { slugifier.folderName(it) }
            .filter { it.isNotEmpty() }

        if (cleanedSegments.isEmpty()) {
            return FolderVerdict.Rejected(
                proposed = proposed,
                reason = FolderVerdict.RejectReason.INVALID,
                detail = "'" + proposed + "' contains no usable folder name",
            )
        }

        val path = cleanedSegments.joinToString("/")
        val depth = cleanedSegments.size

        // Already there. Not a rejection - the model asked for something that exists.
        existingFolders.firstOrNull { it.equalsIgnoreCase(path) }?.let {
            return FolderVerdict.Accepted(it)
        }

        // ── rule 4: depth ────────────────────────────────────────────────────
        if (depth > config.maxFolderDepth) {
            return FolderVerdict.Rejected(
                proposed = path,
                reason = FolderVerdict.RejectReason.DEPTH,
                detail = "depth " + depth + " exceeds the maximum of " + config.maxFolderDepth +
                    ". Place it no deeper than " + config.maxFolderDepth + " levels.",
            )
        }

        // ── rule 3: similarity, against every existing folder ────────────────
        val best = existingFolders
            .map { it to score(path, it) }
            .maxByOrNull { it.second }

        if (best != null && best.second >= config.folderSimilarityThreshold) {
            return FolderVerdict.Rejected(
                proposed = path,
                reason = FolderVerdict.RejectReason.SIMILAR,
                useInstead = best.first,
                score = best.second,
                detail = "'" + path + "' is too similar to the existing '" + best.first +
                    "' (score " + "%.2f".format(best.second) + "). Use that instead.",
            )
        }

        // ── rule 5: top-level cap ────────────────────────────────────────────
        if (depth == 1) {
            val topLevelCount = existingFolders.count { !it.contains('/') }
            if (topLevelCount >= config.maxTopLevelFolders) {
                return FolderVerdict.Rejected(
                    proposed = path,
                    reason = FolderVerdict.RejectReason.CAP,
                    score = best?.second,
                    detail = "the vault already has " + topLevelCount + " top-level folders " +
                        "(cap " + config.maxTopLevelFolders + "). Nest '" + path +
                        "' inside an existing top-level folder instead.",
                )
            }
        }

        // ── rule 4 again: the parent must exist ──────────────────────────────
        if (depth > 1) {
            val parent = cleanedSegments.dropLast(1).joinToString("/")
            if (existingFolders.none { it.equalsIgnoreCase(parent) }) {
                return FolderVerdict.Rejected(
                    proposed = path,
                    reason = FolderVerdict.RejectReason.INVALID,
                    detail = "parent folder '" + parent + "' does not exist. Create it first, " +
                        "or choose an existing folder.",
                )
            }
        }

        return FolderVerdict.Accepted(path)
    }

    /**
     * Similarity of two folder paths in the range 0..1.
     *
     * Both the token set and the edit distance are computed on the **last
     * segment**, because that is the concept being named. `Projects/Reading` vs
     * `Reading` should score as "Reading vs Reading", not be diluted by the
     * differing prefix — that dilution is exactly how sprawl gets through.
     */
    internal fun score(a: String, b: String): Double {
        val leafA = a.substringAfterLast('/')
        val leafB = b.substringAfterLast('/')

        val tokensA = tokenise(leafA)
        val tokensB = tokenise(leafB)

        val jaccard = jaccard(tokensA, tokensB)
        val editSimilarity = 1.0 - normalisedLevenshtein(
            leafA.lowercase().replace(Regex("[^a-z0-9]"), ""),
            leafB.lowercase().replace(Regex("[^a-z0-9]"), ""),
        )

        val jw = config.folderJaccardWeight
        return jw * jaccard + (1.0 - jw) * editSimilarity
    }

    /** Lowercase, strip punctuation, singularise, tokenise. Section 5 step 1. */
    internal fun tokenise(name: String): Set<String> =
        name.lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .split(' ')
            .filter { it.isNotBlank() }
            .map(::singularise)
            .toSet()

    /**
     * Conservative singulariser.
     *
     * Section 5 says "singularise" without saying how, and the obvious answer is
     * wrong: a Porter stemmer turns *People* into *Peopl* and *Analysis* into
     * *Analysi*, so `People` would stop matching itself in a way nobody would ever
     * debug. This strips a trailing `s` only where that is safe, plus a short
     * irregular map. It affects scoring only — the stored folder name is never
     * touched (D-031).
     */
    internal fun singularise(word: String): String {
        irregulars[word]?.let { return it }
        if (word.length <= 3) return word
        if (word.endsWith("ss") || word.endsWith("us") || word.endsWith("is")) return word
        if (word.endsWith("ies") && word.length > 4) return word.dropLast(3) + "y"
        if (word.endsWith("ches") || word.endsWith("shes") || word.endsWith("xes")) return word.dropLast(2)
        if (word.endsWith("s")) return word.dropLast(1)
        return word
    }

    private val irregulars = mapOf(
        "people" to "person",
        "children" to "child",
        "men" to "man",
        "women" to "woman",
        "teeth" to "tooth",
        "feet" to "foot",
        "data" to "datum",
        "media" to "medium",
        "criteria" to "criterion",
        "analyses" to "analysis",
        "theses" to "thesis",
    )

    internal fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val intersection = a.intersect(b).size.toDouble()
        val union = a.union(b).size.toDouble()
        return intersection / union
    }

    /** Levenshtein distance divided by the longer length. 0 = identical. */
    internal fun normalisedLevenshtein(a: String, b: String): Double {
        if (a == b) return 0.0
        if (a.isEmpty() || b.isEmpty()) return 1.0
        val distance = levenshtein(a, b)
        return distance.toDouble() / max(a.length, b.length).toDouble()
    }

    private fun levenshtein(a: String, b: String): Int {
        // Two rows rather than a full matrix: folder names are short, but there is
        // no reason to allocate n*m.
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)

        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                val insertion = current[j - 1] + 1
                val deletion = previous[j] + 1
                current[j] = minOf(substitution, insertion, deletion)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }

    private fun String.equalsIgnoreCase(other: String) = this.equals(other, ignoreCase = true)
}
