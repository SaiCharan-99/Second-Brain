package com.secondbrain.agent

import com.secondbrain.model.AgentConfig
import com.secondbrain.model.AgentTurnResult
import com.secondbrain.model.Phase
import com.secondbrain.model.ToolEvent
import com.secondbrain.model.TurnEnd
import com.secondbrain.model.TurnUsage
import com.secondbrain.ports.LlmBlock
import com.secondbrain.ports.LlmMessage
import com.secondbrain.ports.LlmPort
import com.secondbrain.ports.LlmRequest
import com.secondbrain.ports.LlmStop
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The orchestrator: ARCHITECTURE.md section 4, hand-written (D-005).
 *
 * The flow in section 4 is a good map with three gaps this fills, all of which
 * would be silent failures:
 *
 * **Parallel tool calls.** The flowchart is strictly sequential — one `tool_use`,
 * one `tool_result`, loop. Parallel tool use is on by default, so one assistant
 * message can carry several `tool_use` blocks, and **all** their results must go
 * back in a single user message. Splitting them across messages trains the model
 * out of parallel calls, and omitting any one of them is a 400 on the next
 * request rather than a soft failure (H9).
 *
 * **Five stop reasons, not two.** `end_turn` and `tool_use` are the happy paths.
 * `refusal` arrives as HTTP 200 with possibly-empty content, so an unguarded loop
 * speaks silence; `max_tokens` can truncate a tool call mid-JSON (H12).
 *
 * **What "12 tool calls" means.** Section 4's cap is ambiguous once a response
 * can carry several calls, so round-trips and executions are capped separately —
 * twelve round-trips of N parallel calls each is otherwise unbounded (H10).
 */
class AgentLoop(
    private val llm: LlmPort,
    private val registry: ToolRegistry,
    private val dispatcher: ToolDispatcher,
    private val prompts: SystemPrompt,
    private val config: AgentConfig,
    /**
     * Step 5/6: lets `CalendarTools` see "when this utterance was spoken"
     * without changing every tool handler's signature. See [TurnClock]'s own
     * doc. Defaulted so every existing call site (tests, `CaptureHarness`,
     * pre-Step-5 code) keeps compiling unchanged with a private clock nobody
     * else can observe.
     */
    private val turnClock: TurnClock = TurnClock(),
) {

    private val log = LoggerFactory.getLogger(AgentLoop::class.java)

    /**
     * Cooperative cancellation.
     *
     * D-048: speaking during THINKING cancels the in-flight turn. Checked between
     * iterations and after each tool rather than relying on coroutine
     * cancellation, because the same lesson from Step 1's playback applies —
     * cancellation only lands where something checks for it, and a tool handler
     * doing blocking file I/O is not a suspension point.
     *
     * Safe in Step 3 specifically: every vault write is atomic and serialised, so
     * a cancel between tools leaves no partial state, and no irreversible action
     * exists yet.
     *
     * **Re-examined at Step 5, as flagged above.** A cancel firing while a call
     * is suspended inside `ConfirmationGate.submit` (an open proposal window) is
     * safe for the same structural reason ask_user's indefinite wait already
     * was: this flag is checked *between* calls in the `for (call in calls)`
     * loop above, never during one, so a cancel does not touch a gate that is
     * already open — the gate is a UI-driven state machine with its own
     * lifecycle, not something this loop can reach into. If the *next* call in
     * the same batch hasn't dispatched yet, it gets the standard "cancelled"
     * tool_result and never opens; the gate already open keeps running until a
     * human resolves it (or, if the app dies first, EC-A9's startup
     * reconciliation resolves the orphaned ledger row instead). Nothing here
     * needed to change for that to be true — it was already the right answer,
     * just unverified until this comment.
     */
    class Cancellation {
        private val flag = AtomicBoolean(false)
        fun cancel() = flag.set(true)
        fun isCancelled(): Boolean = flag.get()
        fun reset() = flag.set(false)
    }

    /**
     * Runs one turn: the user said something, and this is everything the
     * assistant does about it.
     *
     * @param history verbatim prior messages for this phase, already windowed.
     * @param utteranceAt EC-C2: the recording's *start* instant (see
     *   [com.secondbrain.model.Utterance.startedAt]'s own doc), not whenever
     *   this function happens to be called. Defaulted to call-time for source
     *   compatibility with existing tests and `CaptureHarness`, where there is
     *   no real recording to time-stamp.
     * @param zone EC-C3: the zone the utterance was spoken in, stored as an
     *   ID and never a fixed offset.
     * @param images Step 8/WF-6: photos attached to this turn — a grocery
     *   list, handwritten notes, a product's packaging. Placed *before* the
     *   text block in the user message, per Anthropic's own guidance that an
     *   image grounds better when it precedes the prose describing it. Empty
     *   for every pre-Step-8 call site; nothing about a text-only turn changes.
     */
    suspend fun run(
        utterance: String,
        phase: Phase,
        history: List<LlmMessage>,
        conversationId: String,
        turnIndex: Int,
        cancellation: Cancellation = Cancellation(),
        utteranceAt: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
        images: List<LlmBlock.Image> = emptyList(),
    ): TurnOutput {
        val started = System.currentTimeMillis()
        turnClock.set(utteranceAt, zone, hasImage = images.isNotEmpty())

        val messages = history.toMutableList()
        val userBlocks: List<LlmBlock> = images + LlmBlock.Text(prompts.userTurn(utterance, now = utteranceAt, zone = zone))
        messages += LlmMessage(LlmMessage.Role.USER, userBlocks)

        val toolEvents = mutableListOf<ToolEvent>()
        var usage = TurnUsage.ZERO
        var iterations = 0
        var executions = 0
        var selfCorrections = 0
        var end: TurnEnd = TurnEnd.END_TURN
        var spokenText = ""
        var refusalCategory: String? = null
        var error: String? = null

        loop@ while (true) {
            if (cancellation.isCancelled()) {
                end = TurnEnd.CANCELLED
                break@loop
            }

            // ── EC-A1: the cap ───────────────────────────────────────────────
            if (iterations >= config.maxIterations || executions >= config.maxToolExecutions) {
                log.warn(
                    "Iteration cap reached ({} round-trips, {} executions). Forcing a text reply.",
                    iterations, executions,
                )
                // The notice goes in as a mid-conversation system message, not by
                // editing the system prompt. Editing the prompt mid-conversation
                // would invalidate the cached prefix for the rest of the turn,
                // which is a real cost for a message that exists to save cost (H7).
                messages += LlmMessage(
                    LlmMessage.Role.SYSTEM,
                    listOf(LlmBlock.Text(prompts.iterationCapNotice(iterations))),
                )

                val forced = llm.send(request(messages, toolsEnabled = false))
                usage += forced.usage
                iterations++
                spokenText = forced.text
                end = TurnEnd.ITERATION_CAP
                break@loop
            }

            val response = llm.send(request(messages))
            usage += response.usage
            iterations++

            when (response.stop) {
                LlmStop.TOOL_USE -> {
                    // Keep the assistant turn verbatim, thinking blocks included.
                    // Dropping them changes the request the model sees on resend.
                    messages += LlmMessage(LlmMessage.Role.ASSISTANT, response.blocks)

                    val calls = response.toolUses
                    if (calls.isEmpty()) {
                        // stop_reason said tool_use but no block arrived. Treat as
                        // a text turn rather than looping forever on nothing.
                        spokenText = response.text
                        end = TurnEnd.END_TURN
                        break@loop
                    }

                    val results = mutableListOf<LlmBlock>()
                    var correctionThisIteration = false

                    for (call in calls) {
                        // Every call gets a result, even after a cancel: the
                        // conversation we persist has to stay valid for replay.
                        if (cancellation.isCancelled()) {
                            results += LlmBlock.ToolResult(
                                call.id,
                                """{"error":"cancelled","message":"the user started a new utterance"}""",
                                isError = true,
                            )
                            continue
                        }

                        val dispatched = dispatcher.dispatch(call)
                        results += dispatched.result
                        toolEvents += dispatched.event
                        executions++

                        if (dispatched.needsSelfCorrection) correctionThisIteration = true

                        if (executions >= config.maxToolExecutions) {
                            log.warn("Tool execution cap ({}) hit mid-iteration.", config.maxToolExecutions)
                        }
                    }

                    // All results in ONE user message. Splitting them is what
                    // trains the model out of parallel calls (H9).
                    messages += LlmMessage(LlmMessage.Role.USER, results)

                    if (correctionThisIteration) {
                        selfCorrections++
                        // EC-A3 caps self-correction at 2. The artifacts do not say
                        // what attempt 3 does; it takes the same path as the
                        // iteration cap — notice, forced text reply (H14).
                        if (selfCorrections > config.maxSelfCorrections) {
                            log.warn("Self-correction cap ({}) exceeded.", config.maxSelfCorrections)
                            messages += LlmMessage(
                                LlmMessage.Role.SYSTEM,
                                listOf(LlmBlock.Text(prompts.selfCorrectionCapNotice())),
                            )
                            val forced = llm.send(request(messages, toolsEnabled = false))
                            usage += forced.usage
                            iterations++
                            spokenText = forced.text
                            end = TurnEnd.ITERATION_CAP
                            break@loop
                        }
                    }

                    // Since Step 5: no separate branch needed here for a gated
                    // call. dispatcher.dispatch(call) above already ran the
                    // gated handler to completion — including, for
                    // email_draft/calendar_propose_event, suspending inside
                    // ConfirmationGate.submit() until a human resolved it. The
                    // tool_result already reflects the outcome (sent, failed,
                    // unknown, cancelled_by_user, or gate_busy). See
                    // ConfirmationGate's doc for why this loop needs no
                    // special-casing at all to make R2 hold.
                }

                LlmStop.END_TURN -> {
                    messages += LlmMessage(LlmMessage.Role.ASSISTANT, response.blocks)
                    spokenText = response.text
                    end = TurnEnd.END_TURN
                    break@loop
                }

                LlmStop.MAX_TOKENS -> {
                    // The reply was cut. If it was cut inside a tool_use block the
                    // input JSON is incomplete, so the call is not trustworthy and
                    // is not executed — we take whatever text arrived and stop.
                    log.warn("Response hit max_tokens ({}). Content may be truncated.", config.maxTokens)
                    messages += LlmMessage(LlmMessage.Role.ASSISTANT, response.blocks)
                    spokenText = response.text.ifBlank { prompts.truncatedFallback() }
                    end = TurnEnd.MAX_TOKENS
                    break@loop
                }

                LlmStop.REFUSAL -> {
                    // HTTP 200 with possibly-empty content. Without this branch the
                    // machine would say nothing at all and look broken.
                    refusalCategory = response.refusalCategory
                    log.warn("Model declined the request (category={}).", refusalCategory)
                    spokenText = prompts.refusalFallback(refusalCategory)
                    end = TurnEnd.REFUSAL
                    break@loop
                }

                LlmStop.API_FAILED -> {
                    // EC-A7: speak the error, preserve the transcript. The utterance
                    // is already on disk from Step 1, so nothing is lost.
                    error = response.error
                    log.error("Claude unreachable: {}", error)
                    spokenText = prompts.apiFailureFallback()
                    end = TurnEnd.API_FAILED
                    break@loop
                }
            }
        }

        if (end == TurnEnd.CANCELLED) {
            log.info("Turn cancelled after {} iteration(s); {} already spent.", iterations, "%.5f".format(config.pricing.usdFor(usage)))
            spokenText = ""
        }

        val result = AgentTurnResult(
            conversationId = conversationId,
            turnIndex = turnIndex,
            phase = phase,
            spokenText = spokenText,
            end = end,
            toolEvents = toolEvents,
            usage = usage,
            // Tokens already spent are logged even on a cancelled turn. We paid.
            usd = config.pricing.usdFor(usage),
            iterations = iterations,
            latencyMs = System.currentTimeMillis() - started,
            refusalCategory = refusalCategory,
            error = error,
        )

        return TurnOutput(result, messages)
    }

    /** The turn's result plus the message list to persist. */
    data class TurnOutput(
        val result: AgentTurnResult,
        /** Full verbatim conversation after this turn, for `messages` in app.db. */
        val messages: List<LlmMessage>,
    )

    private fun request(messages: List<LlmMessage>, toolsEnabled: Boolean = true) = LlmRequest(
        model = config.model,
        systemPrompt = prompts.system(),
        messages = messages,
        tools = if (toolsEnabled) registry.specs() else emptyList(),
        maxTokens = config.maxTokens,
        thinkingEnabled = config.thinkingEnabled,
        // D-066: effort is Opus/Sonnet/Fable-tier only and a 400 on Haiku.
        // Blank means "omit the field", never "send an empty effort string".
        effort = config.effort.takeIf { it.isNotBlank() },
        cacheSystemPrefix = config.cacheEnabled,
        cacheMessageBreakpoints = if (config.cacheEnabled) breakpoints(messages) else emptySet(),
    )

    /**
     * Message indices to mark with a cache breakpoint.
     *
     * A breakpoint walks back at most **20 content blocks** to find a prior entry.
     * A twelve-call turn is 24 blocks — 12 `tool_use` plus 12 `tool_result` — so
     * the next request's breakpoint cannot see the previous one and silently
     * rewrites the whole conversation at the 1.25x write price, on every request,
     * with byte-identical payloads. Nothing announces it; the bill is just higher.
     *
     * Marking every ~15 blocks keeps each breakpoint inside the window (H5).
     */
    internal fun breakpoints(messages: List<LlmMessage>): Set<Int> {
        if (messages.isEmpty()) return emptySet()

        val marks = mutableSetOf<Int>()
        var blocksSinceMark = 0

        messages.forEachIndexed { index, message ->
            blocksSinceMark += message.blocks.size
            if (blocksSinceMark >= config.cacheBlockInterval) {
                marks += index
                blocksSinceMark = 0
            }
        }

        // Always mark the last message: it is the read point for the next request.
        marks += messages.lastIndex

        // The API allows 4 breakpoints per request and one is spent on the system
        // prefix, so keep the 3 most recent - the older ones are already covered
        // by whatever entry they wrote previously.
        return marks.sortedDescending().take(3).toSet()
    }
}
