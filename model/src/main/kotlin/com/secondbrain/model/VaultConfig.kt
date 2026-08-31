package com.secondbrain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Every threshold the vault uses.
 *
 * R7: "Caps and thresholds live in config, never in prompts." D-007 is explicit
 * that the Folder Guard numbers are guesses to be tuned against real captures in
 * Step 3, which only works if they are config rather than code.
 */
@Serializable
data class VaultConfig(

    // ── Folder Guard (section 5 WF-1, EC-N6, D-007) ──────────────────────────

    /**
     * `0.6 * jaccard(tokens) + 0.4 * (1 - normalisedLevenshtein(slug))` at or
     * above this rejects the proposed folder as SIMILAR.
     *
     * Note for whoever tunes this: because Jaccard over single-token folder
     * names is binary, every single-word comparison lands either at ~0.95 or
     * below ~0.40 — this threshold sits in a wide empty gap and cannot change
     * those outcomes. It only bites on multi-word names ("Second Brain" vs
     * "Second Brain UI"). See F23 / D-031.
     */
    @SerialName("folder_similarity_threshold") val folderSimilarityThreshold: Double = 0.72,

    /** Weight on token-set Jaccard. The remainder goes to slug edit distance. */
    @SerialName("folder_jaccard_weight") val folderJaccardWeight: Double = 0.6,

    /** Deepest folder allowed. Depth 1 is a top-level folder. */
    @SerialName("max_folder_depth") val maxFolderDepth: Int = 3,

    /** A new top-level folder is rejected once this many already exist. */
    @SerialName("max_top_level_folders") val maxTopLevelFolders: Int = 12,

    // ── Links (EC-N7, EC-N8) ─────────────────────────────────────────────────

    /**
     * A `[[wikilink]]` resolves only at or above this similarity. Below it the
     * link stays dangling, because a wrong link is worse than no link.
     */
    @SerialName("link_fuzzy_threshold") val linkFuzzyThreshold: Double = 0.85,

    // ── Slugs (EC-N1, EC-N2) ─────────────────────────────────────────────────

    /** Maximum slug length, characters, before the `.md` extension. */
    @SerialName("max_slug_length") val maxSlugLength: Int = 80,

    // ── vault_tree (EC-A5) ───────────────────────────────────────────────────

    /** Default depth returned by `vault_tree`. */
    @SerialName("tree_default_depth") val treeDefaultDepth: Int = 3,

    /** Past this many notes, a folder reports a count instead of a listing. */
    @SerialName("tree_folder_listing_cap") val treeFolderListingCap: Int = 20,

    // ── Watcher (EC-N10) ─────────────────────────────────────────────────────

    /** Coalescing window for filesystem events, milliseconds. */
    @SerialName("watch_debounce_ms") val watchDebounceMs: Long = 300,

    // ── Writer (EC-N4, F5) ───────────────────────────────────────────────────

    /**
     * ATOMIC_MOVE fails with AccessDeniedException on Windows whenever another
     * handle holds the target open — Defender, Search Indexer and the dashboard
     * reader all do. Verified. Bounded retry, then give up loudly.
     */
    @SerialName("atomic_move_attempts") val atomicMoveAttempts: Int = 5,
    @SerialName("atomic_move_backoff_ms") val atomicMoveBackoffMs: Long = 40,
)

/** Folder Guard verdict. Every one of these is written to `folder_decisions`. */
sealed interface FolderVerdict {

    /** The folder may be created. */
    data class Accepted(val path: String) : FolderVerdict

    /** Rejected. [reason] and [useInstead] go back to the model as structured JSON. */
    data class Rejected(
        val proposed: String,
        val reason: RejectReason,
        /** The existing folder to use instead, when there is one. */
        val useInstead: String? = null,
        val score: Double? = null,
        val detail: String,
    ) : FolderVerdict

    enum class RejectReason { SIMILAR, DEPTH, CAP, INVALID }
}

/** A resolved `[[wikilink]]`. */
data class LinkRef(
    val fromPath: String,
    val toPath: String,
    /** Exactly what was inside the brackets, including any `|alias` or `#heading`. */
    val rawTarget: String,
    val score: Double,
)

/**
 * An unresolved `[[wikilink]]`.
 *
 * [ambiguousCandidates] is non-empty when two or more notes tied for the top
 * score. Per D-030 that stays dangling rather than guessing, and the candidates
 * are recorded so the dashboard can offer a choice.
 */
data class DanglingLink(
    val fromPath: String,
    val rawTarget: String,
    val ambiguousCandidates: List<String> = emptyList(),
)

/** An inbound link, with the surrounding text the dashboard shows. */
data class Backlink(
    val fromPath: String,
    val fromTitle: String,
    val rawTarget: String,
    /** Text around the link occurrence. Extracted on read, never stored (F11). */
    val context: String,
)

/** One FTS5 search result. */
data class SearchHit(
    val path: String,
    val title: String,
    val summary: String,
    /** `snippet()` output with the match delimited. */
    val snippet: String,
    val rank: Double,
)

/**
 * One node of the dashboard tree.
 *
 * Two counts because the index and the UI want different things: the mockup
 * shows `Projects 23` where its three children hold 9 + 7 + 7, so the tree
 * displays a rollup, while only [directNoteCount] can be maintained
 * incrementally. See F10 / D-027.
 */
data class TreeNode(
    val path: String,
    val name: String,
    val depth: Int,
    val directNoteCount: Int,
    val rollupNoteCount: Int,
    /** Dangling links originating from notes in this folder. Badged in the tree. */
    val danglingCount: Int,
    val children: List<TreeNode> = emptyList(),
)

/** A row of the Folder Guard audit panel. */
data class FolderDecision(
    val id: Long,
    val proposed: String,
    val verdict: String,
    val matched: String?,
    val score: Double?,
    val at: String,
)
