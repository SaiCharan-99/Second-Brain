package com.secondbrain.agent

import com.secondbrain.model.AgentConfig
import com.secondbrain.model.Phase
import com.secondbrain.model.TurnUsage
import com.secondbrain.ports.LlmBlock
import com.secondbrain.ports.LlmMessage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID

/**
 * Conversation history, phases, and the context window (R8, EC-A6).
 *
 * ### What a "turn" is
 *
 * One user utterance and everything the assistant did about it — not one message.
 * Section 4 says "keep a rolling window of the last 8 turns"; with a
 * twelve-iteration cap, eight turns of messages would be ~200 content blocks
 * while eight turns of *utterances* is what a person means by eight turns (H18).
 * The design board's "TURN 3 / 8" chip counts these.
 *
 * ### Phase transitions are hard resets
 *
 * R8: a new phase starts from the system prompt plus a one-paragraph carry-over,
 * not the full history. "Soft windowing leaks stale intent into new decisions,
 * and stale intent on a gated action is how you send the wrong email."
 *
 * In Step 3 only [Phase.CAPTURE] and [Phase.QUERY] are reachable — no email,
 * calendar or commerce tool exists — so the machinery is built and the other
 * three are honestly untested (D-050).
 */
class ConversationStore(
    private val db: AgentDb,
    private val config: AgentConfig,
) {

    private val log = LoggerFactory.getLogger(ConversationStore::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Live state for the current conversation. */
    data class State(
        val conversationId: String,
        val phase: Phase,
        /** Turns in the current phase. Resets on a phase change. */
        val turnsInPhase: Int,
        /** Verbatim messages inside the window, ready to send. */
        val windowed: List<LlmMessage>,
        /** Rolling summary of turns that fell out of the window, or null. */
        val rollingSummary: String?,
        /** One-paragraph carry-over from the previous phase, or null. */
        val carryOver: String?,
    ) {
        /** For the design board's "TURN n / 8" chip. */
        fun turnLabel(windowSize: Int): String = "${turnsInPhase.coerceAtMost(windowSize)} / $windowSize"
    }

    // ── serialisation of message blocks ─────────────────────────────────────

    @Serializable
    private data class StoredBlock(
        val kind: String,
        val text: String? = null,
        val signature: String? = null,
        val id: String? = null,
        val name: String? = null,
        val inputJson: String? = null,
        val toolUseId: String? = null,
        val content: String? = null,
        val isError: Boolean = false,
        /** Step 8/WF-6. Persisted verbatim so a reloaded conversation replays identically — see [encode]'s doc note on why this can't be dropped. */
        val base64: String? = null,
        val mediaType: String? = null,
    )

    @Serializable
    private data class StoredMessage(val role: String, val blocks: List<StoredBlock>)

    private fun encode(message: LlmMessage): String = json.encodeToString(
        StoredMessage.serializer(),
        StoredMessage(
            role = message.role.name,
            blocks = message.blocks.map { block ->
                when (block) {
                    is LlmBlock.Text -> StoredBlock("text", text = block.text)
                    // Thinking blocks are persisted with their signature because
                    // replaying them unchanged is what keeps a resend valid. On
                    // Opus 5 the text is usually empty (display defaults to
                    // omitted) and the block still has to survive the round trip.
                    is LlmBlock.Thinking -> StoredBlock("thinking", text = block.thinking, signature = block.signature)
                    is LlmBlock.ToolUse -> StoredBlock("tool_use", id = block.id, name = block.name, inputJson = block.inputJson)
                    is LlmBlock.ToolResult -> StoredBlock("tool_result", toolUseId = block.toolUseId, content = block.content, isError = block.isError)
                    // Persisted whole, base64 included, not dropped or
                    // referenced-by-path: a reload mid-window with a later
                    // tool_result talking about "the attached photo" needs
                    // that photo still present to replay coherently. app.db
                    // is precious and unbounded by design (R10) - this just
                    // means a photo-heavy session grows it faster than a
                    // text-only one, which D-084 records as accepted.
                    is LlmBlock.Image -> StoredBlock("image", base64 = block.base64, mediaType = block.mediaType)
                }
            },
        ),
    )

    private fun decode(stored: String): LlmMessage {
        val message = json.decodeFromString(StoredMessage.serializer(), stored)
        return LlmMessage(
            role = LlmMessage.Role.valueOf(message.role),
            blocks = message.blocks.mapNotNull { block ->
                when (block.kind) {
                    "text" -> LlmBlock.Text(block.text.orEmpty())
                    "thinking" -> LlmBlock.Thinking(block.text.orEmpty(), block.signature)
                    "tool_use" -> LlmBlock.ToolUse(block.id.orEmpty(), block.name.orEmpty(), block.inputJson ?: "{}")
                    "tool_result" -> LlmBlock.ToolResult(block.toolUseId.orEmpty(), block.content.orEmpty(), block.isError)
                    "image" -> LlmBlock.Image(block.base64.orEmpty(), block.mediaType ?: "image/jpeg")
                    else -> null
                }
            },
        )
    }

    // ── lifecycle ───────────────────────────────────────────────────────────

    fun startConversation(phase: Phase = Phase.CAPTURE, now: Instant = Instant.now()): State {
        val id = UUID.randomUUID().toString()
        db.connection.prepareStatement(
            "INSERT INTO conversations(id, started_at, ended_at, phase) VALUES (?,?,NULL,?)"
        ).use { ps ->
            ps.setString(1, id)
            ps.setString(2, now.toString())
            ps.setString(3, phase.name)
            ps.executeUpdate()
        }
        log.info("Started conversation {} in phase {}", id, phase)
        return State(id, phase, 0, emptyList(), null, null)
    }

    fun endConversation(conversationId: String, now: Instant = Instant.now()) {
        db.connection.prepareStatement("UPDATE conversations SET ended_at = ? WHERE id = ?").use { ps ->
            ps.setString(1, now.toString())
            ps.setString(2, conversationId)
            ps.executeUpdate()
        }
    }

    /** Persists every message of a completed turn, verbatim. */
    fun recordTurn(
        state: State,
        turnIndex: Int,
        messages: List<LlmMessage>,
        usage: TurnUsage,
        now: Instant = Instant.now(),
    ) {
        // Only the messages this turn added; earlier ones are already stored.
        val existing = countMessages(state.conversationId)
        val fresh = messages.drop(existing)

        db.transaction {
            fresh.forEachIndexed { offset, message ->
                db.connection.prepareStatement(
                    """
                    INSERT INTO messages(conv_id, turn_index, phase, role, content,
                                         tokens_in, tokens_out, cache_write_tokens, cache_read_tokens, at)
                    VALUES (?,?,?,?,?,?,?,?,?,?)
                    """.trimIndent()
                ).use { ps ->
                    ps.setString(1, state.conversationId)
                    ps.setInt(2, turnIndex)
                    ps.setString(3, state.phase.name)
                    ps.setString(4, message.role.name)
                    ps.setString(5, encode(message))
                    // Usage is per-turn, not per-message, so it lands on the last
                    // message of the turn rather than being divided arbitrarily.
                    val isLast = offset == fresh.lastIndex
                    if (isLast) {
                        ps.setLong(6, usage.inputTokens)
                        ps.setLong(7, usage.outputTokens)
                        ps.setLong(8, usage.cacheWriteTokens)
                        ps.setLong(9, usage.cacheReadTokens)
                    } else {
                        ps.setNull(6, java.sql.Types.INTEGER)
                        ps.setNull(7, java.sql.Types.INTEGER)
                        ps.setNull(8, java.sql.Types.INTEGER)
                        ps.setNull(9, java.sql.Types.INTEGER)
                    }
                    ps.setString(10, now.toString())
                    ps.executeUpdate()
                }
            }
        }
    }

    private fun countMessages(conversationId: String): Int =
        db.connection.prepareStatement("SELECT COUNT(*) FROM messages WHERE conv_id = ?").use { ps ->
            ps.setString(1, conversationId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }

    /**
     * Advances the window after a turn.
     *
     * When the number of turns in the phase exceeds `context_window_turns`, the
     * oldest turns leave the verbatim window. [summariser] produces the rolling
     * summary of what left; it is a suspend function because that is a Claude call
     * (D-047), and it is only invoked when something actually falls out.
     */
    suspend fun advance(
        state: State,
        turnIndex: Int,
        messagesAfterTurn: List<LlmMessage>,
        summariser: suspend (dropped: List<LlmMessage>, previous: String?) -> String,
    ): State {
        val turns = state.turnsInPhase + 1
        if (turns <= config.contextWindowTurns) {
            return state.copy(turnsInPhase = turns, windowed = messagesAfterTurn)
        }

        val turnsToDrop = turns - config.contextWindowTurns
        val boundary = messageIndexAfterTurns(state.conversationId, turnsToDrop, messagesAfterTurn)
        val dropped = messagesAfterTurn.take(boundary)
        val kept = messagesAfterTurn.drop(boundary)

        if (dropped.isEmpty()) {
            return state.copy(turnsInPhase = turns, windowed = messagesAfterTurn)
        }

        val summary = summariser(dropped, state.rollingSummary)
        log.debug("Window advanced: {} message(s) summarised out of the verbatim window", dropped.size)

        return state.copy(
            turnsInPhase = turns,
            windowed = kept,
            rollingSummary = summary,
        )
    }

    /**
     * How many messages belong to the oldest [turnsToDrop] turns.
     *
     * Reads from the store rather than guessing from the in-memory list, because
     * a turn's message count varies with how many tools it used.
     */
    private fun messageIndexAfterTurns(
        conversationId: String,
        turnsToDrop: Int,
        fallback: List<LlmMessage>,
    ): Int {
        val turnIndices = mutableListOf<Int>()
        db.connection.prepareStatement(
            "SELECT DISTINCT turn_index FROM messages WHERE conv_id = ? ORDER BY turn_index ASC"
        ).use { ps ->
            ps.setString(1, conversationId)
            ps.executeQuery().use { rs -> while (rs.next()) turnIndices += rs.getInt(1) }
        }
        if (turnIndices.isEmpty()) return 0

        val cutoff = turnIndices.take(turnsToDrop).lastOrNull() ?: return 0
        var count = 0
        db.connection.prepareStatement(
            "SELECT COUNT(*) FROM messages WHERE conv_id = ? AND turn_index <= ?"
        ).use { ps ->
            ps.setString(1, conversationId)
            ps.setInt(2, cutoff)
            ps.executeQuery().use { rs -> if (rs.next()) count = rs.getInt(1) }
        }
        return count.coerceAtMost(fallback.size)
    }

    /**
     * Hard reset across a phase boundary (R8).
     *
     * The new phase starts from the system prompt plus one paragraph. Nothing
     * verbatim crosses the boundary — that is the whole point.
     */
    suspend fun transitionPhase(
        state: State,
        to: Phase,
        carryOverWriter: suspend (from: Phase, to: Phase, history: List<LlmMessage>) -> String,
        now: Instant = Instant.now(),
    ): State {
        if (to == state.phase) return state

        val carryOver = carryOverWriter(state.phase, to, state.windowed)

        db.connection.prepareStatement("UPDATE conversations SET phase = ? WHERE id = ?").use { ps ->
            ps.setString(1, to.name)
            ps.setString(2, state.conversationId)
            ps.executeUpdate()
        }

        log.info("Phase {} -> {}: hard context reset (R8)", state.phase, to)

        return State(
            conversationId = state.conversationId,
            phase = to,
            turnsInPhase = 0,
            windowed = emptyList(),
            rollingSummary = null,
            carryOver = carryOver,
        )
    }

    /**
     * Builds the history to send: carry-over and rolling summary as a leading
     * user message, then the verbatim window.
     *
     * Both go in `messages` rather than the system prompt, which stays frozen for
     * the cache (H6).
     */
    fun historyFor(state: State): List<LlmMessage> {
        val preamble = buildString {
            state.carryOver?.let {
                append("Carried over from the ").append(state.phase.name.lowercase())
                append(" conversation so far: ").append(it).append("\n\n")
            }
            state.rollingSummary?.let {
                append("Earlier in this conversation: ").append(it)
            }
        }.trim()

        return if (preamble.isEmpty()) state.windowed
        else listOf(LlmMessage(LlmMessage.Role.USER, listOf(LlmBlock.Text(preamble)))) + state.windowed
    }

    /** Every message of a conversation, for replay or inspection. */
    fun replay(conversationId: String): List<LlmMessage> {
        val out = mutableListOf<LlmMessage>()
        db.connection.prepareStatement(
            "SELECT content FROM messages WHERE conv_id = ? ORDER BY id ASC"
        ).use { ps ->
            ps.setString(1, conversationId)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    runCatching { out += decode(rs.getString(1)) }
                        .onFailure { log.warn("Could not decode a stored message: {}", it.message) }
                }
            }
        }
        return out
    }
}
