package com.secondbrain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Conversation phase. R8: transitions are hard context resets.
 *
 * Only [CAPTURE] and [QUERY] are reachable in Step 3 — no email, calendar or
 * commerce tool exists yet. The machinery is built with the gated hook so Step 5
 * is plumbing, but nothing pretends the other three are exercised (D-050).
 */
enum class Phase { CAPTURE, EMAIL, CALENDAR, COMMERCE, QUERY }

/**
 * R2: every tool is one of exactly two classes, declared at registration.
 *
 * "There is no third class and no runtime promotion." R3: anything unclear
 * defaults to [GATED].
 */
enum class ToolClass { AUTONOMOUS, GATED }

/**
 * Why an agent turn stopped.
 *
 * Section 4's flowchart branches on `end_turn` and `tool_use` only. The API has
 * five outcomes, and two of the missing three are silent failures: `refusal`
 * returns HTTP 200 with possibly-empty content, and `max_tokens` can truncate a
 * tool call mid-JSON (H12 / D-049).
 */
enum class TurnEnd {
    /** Model produced a final text reply. */
    END_TURN,

    /** Response was cut at max_tokens. Content may be truncated mid-block. */
    MAX_TOKENS,

    /** EC-A1: the iteration cap forced a text reply. */
    ITERATION_CAP,

    /** Safety classifier declined. `stopDetails` carries the category. */
    REFUSAL,

    /** All retries exhausted (EC-A7). The transcript is preserved regardless. */
    API_FAILED,

    /** The user spoke during THINKING, so this turn was abandoned (D-048). */
    CANCELLED,
}

/**
 * One tool execution, as it happened.
 *
 * Exists because the design board's assistant turn carries "Open note" and
 * "Move" chips, which need the note *path* — structured data, not the spoken
 * sentence. Returning only a string would force Step 4's UI to regex the reply
 * (H27 / D-051).
 */
data class ToolEvent(
    val name: String,
    val toolClass: ToolClass,
    /** Verbatim JSON the model supplied. Parsed, never string-matched. */
    val inputJson: String,
    val resultJson: String,
    val isError: Boolean,
    val durationMs: Long,
    /** Vault-relative path when this tool touched a note. Feeds the UI chips. */
    val notePath: String? = null,
)

/** What one API round-trip cost. Four token classes, four different prices. */
data class TurnUsage(
    /**
     * Uncached input tokens only. Total prompt is
     * `inputTokens + cacheWriteTokens + cacheReadTokens` — computing spend from
     * this field alone under-reports badly once caching is on (H1 / D-046).
     */
    val inputTokens: Long,
    val outputTokens: Long,
    /** Written to cache this request. Billed at 1.25x (5m TTL) or 2x (1h). */
    val cacheWriteTokens: Long = 0,
    /** Served from cache this request. Billed at 0.1x. */
    val cacheReadTokens: Long = 0,
) {
    val totalPromptTokens: Long get() = inputTokens + cacheWriteTokens + cacheReadTokens

    operator fun plus(other: TurnUsage) = TurnUsage(
        inputTokens = inputTokens + other.inputTokens,
        outputTokens = outputTokens + other.outputTokens,
        cacheWriteTokens = cacheWriteTokens + other.cacheWriteTokens,
        cacheReadTokens = cacheReadTokens + other.cacheReadTokens,
    )

    companion object {
        val ZERO = TurnUsage(0, 0, 0, 0)
    }
}

/**
 * Per-million-token prices.
 *
 * In config, not in code. EC-G3 already puts model IDs in `config.toml`; prices
 * belong beside them so switching models forces you to look at the price, and a
 * stale price is visible rather than buried in a constant. The Step 3 exit
 * criterion records a per-capture USD figure that sets the budget for every
 * later step, so a wrong number here corrupts Steps 4 through 7 (H2 / D-046).
 *
 * Defaults are Claude Opus 5 as of 2026-09-01: $5.00 in / $25.00 out.
 */
@Serializable
data class ModelPricing(
    @SerialName("input_usd_per_mtok") val inputUsdPerMTok: Double = 5.00,
    @SerialName("output_usd_per_mtok") val outputUsdPerMTok: Double = 25.00,
    /** Cache writes are 1.25x base input at the 5-minute TTL. */
    @SerialName("cache_write_multiplier") val cacheWriteMultiplier: Double = 1.25,
    /** Cache reads are ~0.1x base input. */
    @SerialName("cache_read_multiplier") val cacheReadMultiplier: Double = 0.1,
) {
    fun usdFor(usage: TurnUsage): Double {
        val perToken = inputUsdPerMTok / 1_000_000.0
        return usage.inputTokens * perToken +
            usage.cacheWriteTokens * perToken * cacheWriteMultiplier +
            usage.cacheReadTokens * perToken * cacheReadMultiplier +
            usage.outputTokens * (outputUsdPerMTok / 1_000_000.0)
    }
}

/**
 * The outcome of one user utterance and everything the assistant did about it.
 *
 * H18: a "turn" is one utterance plus the whole response, not one message.
 * Eight turns of twelve iterations each would otherwise be ~200 content blocks.
 */
data class AgentTurnResult(
    val conversationId: String,
    val turnIndex: Int,
    val phase: Phase,
    /** Already normalised and capped for speech (EC-T1, EC-T2). */
    val spokenText: String,
    val end: TurnEnd,
    val toolEvents: List<ToolEvent>,
    val usage: TurnUsage,
    val usd: Double,
    val iterations: Int,
    val latencyMs: Long,
    /** Populated only on [TurnEnd.REFUSAL]; null for every other outcome. */
    val refusalCategory: String? = null,
    val error: String? = null,
) {
    /** Paths of notes this turn wrote or moved. What the UI chips act on. */
    val touchedNotes: List<String>
        get() = toolEvents.mapNotNull { it.notePath }.distinct()
}

/**
 * Everything the agent loop is allowed to be tuned by.
 *
 * R7: "Caps and thresholds live in config, never in prompts. A prompt asking a
 * model to keep it short is not a cap."
 */
@Serializable
data class AgentConfig(
    /** EC-G3: never hardcoded. A 404 must name this key. */
    val model: String = "claude-opus-5",

    /**
     * Model used for rolling summaries and phase carry-over. Same model by
     * default so there is one cache namespace, though a summary call is a
     * one-shot with its own prefix and will not see cache hits anyway (D-047).
     */
    @SerialName("summary_model") val summaryModel: String = "claude-opus-5",

    @SerialName("api_key") val apiKey: String = "",

    /**
     * Thinking is ON by default on Opus 5, and thinking tokens bill as output.
     * `budget_tokens` returns a 400 on this model — depth is controlled by
     * [effort], not by a token budget (H3 / D-049).
     */
    @SerialName("thinking_enabled") val thinkingEnabled: Boolean = true,

    /** low | medium | high | xhigh | max. Defaults to the API default. */
    val effort: String = "high",

    @SerialName("max_tokens") val maxTokens: Long = 8_192,

    // ── EC-A1 ────────────────────────────────────────────────────────────────

    /**
     * Maximum API round-trips per turn. Section 4 says "12 tool calls", which is
     * ambiguous once a single response can carry several `tool_use` blocks —
     * so round-trips and executions are capped separately (H10 / D-049).
     */
    @SerialName("max_iterations") val maxIterations: Int = 12,

    /** Total tool executions per turn, across all iterations. */
    @SerialName("max_tool_executions") val maxToolExecutions: Int = 24,

    /** EC-A3: attempts allowed to correct an unknown tool or bad schema. */
    @SerialName("max_self_corrections") val maxSelfCorrections: Int = 2,

    // ── EC-A7 ────────────────────────────────────────────────────────────────

    @SerialName("max_attempts") val maxAttempts: Int = 3,
    @SerialName("initial_backoff_ms") val initialBackoffMs: Long = 1_000,
    @SerialName("request_timeout_ms") val requestTimeoutMs: Long = 120_000,

    // ── EC-A6 / R8 ───────────────────────────────────────────────────────────

    /** Turns kept verbatim before older ones collapse into a rolling summary. */
    @SerialName("context_window_turns") val contextWindowTurns: Int = 8,

    // ── Prompt caching (H4-H8) ───────────────────────────────────────────────

    /**
     * The system prompt plus seven tool schemas is a large stable prefix resent
     * on every iteration, and cache reads are 0.1x. Not caching means paying
     * roughly ten times over on the prefix, up to twelve times per capture.
     */
    @SerialName("cache_enabled") val cacheEnabled: Boolean = true,

    /**
     * A breakpoint walks back at most 20 content blocks. Twelve tool calls is 24
     * blocks in one turn, which pushes the previous entry out of the lookback and
     * silently rewrites the whole conversation. Intermediate breakpoints keep it
     * inside the window (H5 / D-046).
     */
    @SerialName("cache_block_interval") val cacheBlockInterval: Int = 15,

    /**
     * Voice meets every criterion for pre-warming: user-visible first-request
     * latency, a large shared prefix, and a quiet moment at startup. Costs one
     * cache write, removes the cold-write penalty from the first capture.
     */
    @SerialName("prewarm_cache") val prewarmCache: Boolean = true,

    // ── EC-G2 ────────────────────────────────────────────────────────────────

    /**
     * Session spend ceiling. Checked before a new utterance starts, never
     * mid-turn — tripping at iteration 7 of a capture would abandon a thought,
     * which is what EC-V7 exists to prevent (H23 / D-047).
     */
    @SerialName("session_usd_ceiling") val sessionUsdCeiling: Double = 2.00,

    /** Spoken warning once spend passes this fraction of the ceiling. */
    @SerialName("session_usd_warn_at") val sessionUsdWarnAt: Double = 0.75,

    // ── Pricing ──────────────────────────────────────────────────────────────
    // Flat rather than a nested `pricing` object on purpose: config.toml uses
    // single-level sections, so a nested field would be unreachable from the file
    // and `ignoreUnknownKeys` would silently fall back to these defaults - wrong
    // prices, no error, and a corrupted budget for every later step.
    @SerialName("input_usd_per_mtok") val inputUsdPerMTok: Double = 5.00,
    @SerialName("output_usd_per_mtok") val outputUsdPerMTok: Double = 25.00,
    @SerialName("cache_write_multiplier") val cacheWriteMultiplier: Double = 1.25,
    @SerialName("cache_read_multiplier") val cacheReadMultiplier: Double = 0.1,
) {
    /** The four prices as the calculator wants them. */
    val pricing: ModelPricing
        get() = ModelPricing(inputUsdPerMTok, outputUsdPerMTok, cacheWriteMultiplier, cacheReadMultiplier)

    /**
     * Safe to log.
     *
     * The mask is a literal rather than `AppConfig.MASK`: referencing a const in
     * the other data class while `AppConfig` holds an `AgentConfig` crashed the
     * Kotlin expression checker outright.
     */
    fun redacted(): AgentConfig = copy(apiKey = "***REDACTED***")
}
