package com.secondbrain.agent

import com.secondbrain.model.AgentConfig
import com.secondbrain.model.TurnUsage
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * EC-G2: every call logged, with a session ceiling.
 *
 * ### The arithmetic the schema in section 2 could not express
 *
 * There are four token classes at four prices, and only one of them is what
 * `usage.input_tokens` reports:
 *
 * | class | price relative to base input |
 * |---|---|
 * | uncached input | 1x |
 * | cache write (5-minute TTL) | 1.25x |
 * | cache read | 0.1x |
 * | output, including thinking | 5x (Opus 5: $25 vs $5) |
 *
 * `input_tokens` is the **uncached remainder only**. Total prompt is
 * `input_tokens + cache_creation + cache_read`. Costing a turn from
 * `input_tokens` alone under-reports by whatever the cache served, which in a
 * healthy loop is most of it — and the Step 3 exit criterion records a
 * per-capture figure that becomes the budget for Steps 4 through 7 (H1 / D-046).
 *
 * ### The ceiling is checked between utterances, never mid-turn
 *
 * Tripping at iteration seven of a capture would abandon a thought halfway
 * through, which is what EC-V7 exists to prevent. A turn already in flight always
 * finishes; the ceiling decides whether the *next* one starts (H23).
 */
class CostMeter(
    private val db: AgentDb,
    private val config: AgentConfig,
) {

    private val log = LoggerFactory.getLogger(CostMeter::class.java)

    /** Session spend in micro-dollars, so accumulation is exact. */
    private val sessionMicroUsd = AtomicLong(0)

    private var warned = false

    /** Services that spend money. Logged separately so each is attributable. */
    enum class Service {
        /** The agent loop itself. */
        CLAUDE,

        /**
         * Rolling summaries and phase carry-over.
         *
         * A separate row rather than folded into CLAUDE, so the per-capture figure
         * can be read without summary overhead confusing it (D-047).
         */
        CLAUDE_SUMMARY,

        GEMINI,
        KOKORO,
    }

    fun record(
        service: Service,
        usage: TurnUsage,
        conversationId: String? = null,
        turnIndex: Int? = null,
        model: String? = null,
        now: Instant = Instant.now(),
    ): Double {
        val usd = config.pricing.usdFor(usage)
        sessionMicroUsd.addAndGet(Math.round(usd * 1_000_000))

        db.connection.prepareStatement(
            """
            INSERT INTO cost_meter(conv_id, turn_index, service, model, tokens_in, tokens_out,
                                   cache_write_tokens, cache_read_tokens, units, usd, at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?)
            """.trimIndent()
        ).use { ps ->
            if (conversationId == null) ps.setNull(1, java.sql.Types.VARCHAR) else ps.setString(1, conversationId)
            if (turnIndex == null) ps.setNull(2, java.sql.Types.INTEGER) else ps.setInt(2, turnIndex)
            ps.setString(3, service.name)
            if (model == null) ps.setNull(4, java.sql.Types.VARCHAR) else ps.setString(4, model)
            ps.setLong(5, usage.inputTokens)
            ps.setLong(6, usage.outputTokens)
            ps.setLong(7, usage.cacheWriteTokens)
            ps.setLong(8, usage.cacheReadTokens)
            // `units` from section 2 is retained as the total token count, so the
            // column keeps a meaning rather than being dead weight.
            ps.setDouble(9, (usage.totalPromptTokens + usage.outputTokens).toDouble())
            ps.setDouble(10, usd)
            ps.setString(11, now.toString())
            ps.executeUpdate()
        }

        log.debug(
            "{} cost USD {} (in={} out={} cache_w={} cache_r={}); session USD {}",
            service, "%.6f".format(usd),
            usage.inputTokens, usage.outputTokens, usage.cacheWriteTokens, usage.cacheReadTokens,
            "%.4f".format(sessionUsd()),
        )
        return usd
    }

    fun sessionUsd(): Double = sessionMicroUsd.get() / 1_000_000.0

    /** What the design board's status bar shows: `session $0.0416`. */
    fun sessionLabel(): String = "session $" + "%.4f".format(sessionUsd())

    /** Spend for one capture, from the ledger. The figure the exit criterion wants. */
    fun usdForTurn(conversationId: String, turnIndex: Int): Double =
        db.connection.prepareStatement(
            "SELECT COALESCE(SUM(usd), 0) FROM cost_meter WHERE conv_id = ? AND turn_index = ?"
        ).use { ps ->
            ps.setString(1, conversationId)
            ps.setInt(2, turnIndex)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getDouble(1) else 0.0 }
        }

    /** Mean spend per turn across a conversation. */
    fun meanUsdPerTurn(conversationId: String): Double =
        db.connection.prepareStatement(
            """
            SELECT COALESCE(AVG(turn_total), 0) FROM (
              SELECT SUM(usd) AS turn_total FROM cost_meter
              WHERE conv_id = ? AND turn_index IS NOT NULL
              GROUP BY turn_index
            )
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, conversationId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getDouble(1) else 0.0 }
        }

    /** Per-service totals, for the DECISIONS entry the exit criterion asks for. */
    fun breakdown(): Map<String, Double> {
        val out = LinkedHashMap<String, Double>()
        db.connection.createStatement().use { s ->
            s.executeQuery("SELECT service, SUM(usd) FROM cost_meter GROUP BY service ORDER BY 2 DESC")
                .use { rs -> while (rs.next()) out[rs.getString(1)] = rs.getDouble(2) }
        }
        return out
    }

    /** Ceiling verdict. Checked before a new utterance starts, never mid-turn. */
    sealed interface Verdict {
        data object Proceed : Verdict

        /** Past the warning fraction. Speak it once, then carry on. */
        data class Warn(val spentUsd: Double, val ceilingUsd: Double) : Verdict

        /**
         * Over the ceiling. EC-G2: "requires confirmation to continue".
         *
         * Confirmation is **spoken**, not a click. R9 permits exactly two typing
         * exceptions — verbatim fields and confirmation clicks for irreversible
         * actions — and continuing to spend money is neither irreversible nor a
         * verbatim field, so a spoken yes keeps us at two exceptions rather than
         * inventing a third (H22 / D-047).
         */
        data class Blocked(val spentUsd: Double, val ceilingUsd: Double) : Verdict
    }

    fun check(): Verdict {
        val spent = sessionUsd()
        val ceiling = config.sessionUsdCeiling

        if (spent >= ceiling) {
            log.warn("Session spend USD {} has reached the USD {} ceiling.", "%.4f".format(spent), "%.2f".format(ceiling))
            return Verdict.Blocked(spent, ceiling)
        }
        if (!warned && spent >= ceiling * config.sessionUsdWarnAt) {
            warned = true
            return Verdict.Warn(spent, ceiling)
        }
        return Verdict.Proceed
    }

    /** After the user says yes to continuing past the ceiling. */
    fun raiseCeiling(byUsd: Double) {
        log.info("User confirmed continuing; ceiling raised by USD {}", "%.2f".format(byUsd))
        sessionMicroUsd.addAndGet(-Math.round(byUsd * 1_000_000))
        warned = false
    }
}
