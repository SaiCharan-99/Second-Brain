package com.secondbrain.app.voice

import com.secondbrain.agent.AgentLoop
import com.secondbrain.agent.ConfirmationGate
import com.secondbrain.agent.ConversationStore
import com.secondbrain.agent.CostMeter
import com.secondbrain.agent.SystemPrompt
import com.secondbrain.agent.VaultTools
import com.secondbrain.model.AppConfig
import com.secondbrain.model.AudioFormatSpec
import com.secondbrain.model.LedgerKind
import com.secondbrain.model.Phase
import com.secondbrain.model.SpeechRequest
import com.secondbrain.model.SttStatus
import com.secondbrain.model.Transcript
import com.secondbrain.model.TurnEnd
import com.secondbrain.model.Utterance
import com.secondbrain.ports.AudioCapturePort
import com.secondbrain.ports.AudioDeviceLostException
import com.secondbrain.ports.AudioPlaybackPort
import com.secondbrain.ports.LlmBlock
import com.secondbrain.ports.LlmMessage
import com.secondbrain.ports.LlmPort
import com.secondbrain.ports.LlmRequest
import com.secondbrain.ports.SttPort
import com.secondbrain.ports.TtsPort
import com.secondbrain.ports.TtsUnavailableException
import com.secondbrain.voice.SessionStore
import com.secondbrain.voice.SpeechNormalizer
import com.secondbrain.voice.SystemTtsFallback
import com.secondbrain.voice.ThinkingCue
import com.secondbrain.voice.VoiceGate
import com.secondbrain.voice.WavCodec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicLong

/**
 * The Voice screen's brain. Everything [com.secondbrain.voice.harness.VoiceHarness]
 * proved in Step 1 — the gate, barge-in, EC-V7's write-before-network-call order
 * — reused verbatim; what's new here is real orchestration: the agent loop
 * instead of an echo, `ask_user` that actually speaks and listens, a rolling
 * summariser that is a real Claude call (D-047), and the EC-G2 spoken
 * confirmation to keep spending past the session ceiling.
 *
 * Not a `@Composable`. This is a plain coroutine-driven state machine exposing
 * [state] as a [StateFlow]; [com.secondbrain.app.voice.VoiceScreen] only reads
 * it. Keeping the two separate is what makes [handleAskUser] possible to write
 * at all — it needs to suspend for an arbitrarily long, real-world amount of
 * time (a person deciding what to say), which a composable's lifecycle is the
 * wrong shape for.
 */
class VoiceController(
    private val scope: CoroutineScope,
    private val appConfig: AppConfig,
    private val capture: AudioCapturePort,
    private val playback: AudioPlaybackPort,
    private val sessions: SessionStore,
    private val stt: SttPort,
    private val primaryTts: TtsPort,
    private val fallbackTts: SystemTtsFallback,
    private val thresholdDbfs: Double,
    micDeviceLabel: String,
    private val llm: LlmPort,
    private val agentLoop: AgentLoop,
    private val store: ConversationStore,
    private val costMeter: CostMeter,
    private val prompts: SystemPrompt,
    /**
     * Since Step 5. Always constructed (it's cheap — see `ActionLedger`), even
     * when Google isn't configured and no gated tool that could ever call
     * [ConfirmationGate.submit] is registered — [observeConfirmationGate] then
     * simply never sees a non-null state.
     */
    private val confirmationGate: ConfirmationGate,
    /**
     * Step 8: non-null only when commerce is live against the real Zepto MCP
     * (`McpOAuth` exists — see `Main.kt`). Null when commerce is off or using
     * `FakeCommerceAdapter`, which needs no sign-in at all. A lambda rather
     * than the `McpOAuth` type itself so this file stays decoupled from
     * `:integrations` the same way every other constructor parameter here is
     * a `:ports`/`:agent` type, never a concrete adapter.
     */
    private val commerceSignIn: (suspend () -> Result<Unit>)? = null,
    private val isCommerceSignedIn: () -> Boolean = { false },
) {
    private val log = LoggerFactory.getLogger(VoiceController::class.java)

    enum class MicState {
        IDLE, LISTENING, THINKING, SPEAKING,

        /**
         * Since Step 5: a `ProposalWindow` is open, waiting on a click. Talking
         * down during this state records normally and queues behind the
         * in-flight turn on [turnMutex] rather than cancelling the open
         * proposal — cancelling an irreversible action's proposal is
         * click-only (R9), never a side effect of starting a new recording.
         * See the Step 5/6 plan's decision 6.
         */
        AWAITING_CONFIRMATION,
    }
    enum class Speaker { USER, ASSISTANT, SYSTEM }

    /** Backs `request_typed_input` — the general-purpose typing escape hatch (D-008/D-054). */
    data class TypedInputRequest(val prompt: String, val kind: String)

    data class TranscriptLine(
        val id: Long,
        val speaker: Speaker,
        val text: String,
        /** Vault-relative paths this reply touched. Feeds the "Open note" chips. */
        val notePaths: List<String> = emptyList(),
        val isError: Boolean = false,
    )

    data class UiState(
        val micState: MicState = MicState.IDLE,
        val lines: List<TranscriptLine> = emptyList(),
        val sessionCostLabel: String = "session $0.0000",
        val turnLabel: String = "0 / 8",
        val phase: Phase = Phase.CAPTURE,
        val statusLine: String = "",
        /** True only while a Claude `ask_user` question is waiting on the mic. */
        val awaitingAnswer: Boolean = false,
        val micDeviceLabel: String = "",
        /** Non-null while `request_typed_input` is waiting on a typed value. */
        val pendingTypedInput: TypedInputRequest? = null,
        /** Step 8: true iff commerce is live against the real Zepto MCP — see [commerceSignIn]'s own doc. */
        val commerceLive: Boolean = false,
        val commerceSignedIn: Boolean = false,
        /** Step 8/WF-6: filename of a photo attached and waiting to go out with the next turn. */
        val pendingImageLabel: String? = null,
    )

    private val _state = MutableStateFlow(
        UiState(
            micDeviceLabel = micDeviceLabel,
            commerceLive = commerceSignIn != null,
            commerceSignedIn = isCommerceSignedIn(),
        )
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Step 8/WF-6: a photo picked via [attachImage], waiting for the next turn to carry it. */
    private data class PendingImage(val label: String, val block: LlmBlock.Image)

    @Volatile
    private var pendingImage: PendingImage? = null

    private val lineId = AtomicLong(0)

    /**
     * Serialises everything from "a WAV finished recording" through the agent
     * loop and its bookkeeping — one turn's post-processing at a time.
     *
     * D-048 makes this necessary in a way the CaptureHarness never had to
     * face: talking again while THINKING cancels the in-flight turn AND
     * immediately starts a new recording, so the cancelled turn's tail
     * ([ConversationStore.recordTurn], [ConversationStore.advance],
     * [CostMeter.record]) can genuinely still be running when the new
     * utterance finishes. Without this lock both could mutate
     * [conversationState] and [turnIndex] at once. [handleAskUser]'s
     * answer-delivery path deliberately never takes this lock — see its doc.
     */
    private val turnMutex = Mutex()

    private var conversationState: ConversationStore.State = store.startConversation(Phase.CAPTURE)
    private var turnIndex = 0

    // Written from onTalkDown/onTalkUp (UI-associated dispatcher) and, on the
    // AudioDeviceLostException path, from the capture coroutine running on
    // scope's own dispatcher - a different thread. @Volatile for the same
    // reason JvmAudioCapture/JvmAudioPlayback mark their cross-thread fields
    // this way, not because a race here has been observed.
    @Volatile
    private var recording: SessionStore.Recording? = null

    @Volatile
    private var captureJob: Job? = null

    private val cancellation = AgentLoop.Cancellation()

    /** Non-null exactly while a `ask_user` tool call is waiting on an answer. */
    @Volatile
    private var pendingAskAnswer: CompletableDeferred<VaultTools.AskResult>? = null

    /** Set when [CostMeter.check] returns `Blocked`; the next utterance is read as yes/no. */
    @Volatile
    private var awaitingCostConfirmation = false

    /** Non-null exactly while a `request_typed_input` tool call is waiting on a typed value. */
    @Volatile
    private var pendingTypedAnswer: CompletableDeferred<VaultTools.AskResult>? = null

    /**
     * The proposal most recently announced by TTS. Guards against re-speaking
     * the summary on every unrelated `ConfirmationGate.state` emission (an
     * edit, a verbatim confirm) — only a genuinely new proposal id triggers it.
     */
    @Volatile
    private var lastAnnouncedProposalId: String? = null

    init {
        observeConfirmationGate()
    }

    /**
     * Since Step 5: two jobs, both driven by [ConfirmationGate.state].
     *
     * **Speaks the summary once, the moment a gate opens.** WF-2/WF-3: "TTS
     * speaks a SUMMARY of the body" / "TTS: tomorrow, Tuesday the 2nd, noon to
     * 1 PM" — this has to happen here, not as part of the model's own reply,
     * because the model's turn is itself suspended inside
     * `ConfirmationGate.submit` for as long as the window is open; it gets no
     * chance to say anything until a human resolves it. [pending]'s
     * `proposal.speechSummary` is exactly the line `EmailTools`/`CalendarTools`
     * built for this.
     *
     * **Mirrors [ConfirmationGate.state] into [MicState.AWAITING_CONFIRMATION]
     * and back**, set only *after* the summary finishes speaking (so the
     * mic-state label reads "Speaking" while the summary plays, then "Waiting
     * for you"). Only reacts to the gate's own open/close transitions — a
     * [MicState] change from anything else (a queued recording starting, the
     * turn that opened the gate moving on to speak its final result) is
     * untouched, because this collector only re-fires when
     * [ConfirmationGate.state] itself changes, not on every [_state] update.
     */
    private fun observeConfirmationGate() {
        scope.launch {
            confirmationGate.state.collect { pending ->
                if (pending != null && pending.proposalId != lastAnnouncedProposalId) {
                    lastAnnouncedProposalId = pending.proposalId
                    appendLine(Speaker.ASSISTANT, pending.proposal.speechSummary)
                    speak(pending.proposal.speechSummary)
                }
                _state.update { current ->
                    when {
                        pending != null && current.micState != MicState.AWAITING_CONFIRMATION ->
                            current.copy(micState = MicState.AWAITING_CONFIRMATION)
                        pending == null && current.micState == MicState.AWAITING_CONFIRMATION ->
                            current.copy(micState = MicState.IDLE)
                        else -> current
                    }
                }
            }
        }
    }

    // ── UI-facing controls ──────────────────────────────────────────────────

    /**
     * Talk pressed. Behaviour depends on what was already happening — mirrors
     * [com.secondbrain.voice.harness.PttWindow]'s `onTalkStart`, plus D-048's
     * new THINKING branch.
     */
    fun onTalkDown() {
        when (_state.value.micState) {
            MicState.LISTENING -> return // already down; the UI's own key-repeat guard should prevent this too
            MicState.SPEAKING -> { playback.stop(); beginRecording() } // EC-V3 barge-in
            MicState.THINKING -> { cancellation.cancel(); beginRecording() } // D-048
            MicState.IDLE -> beginRecording()
            // Decision 6 of the Step 5/6 plan: deliberately NOT cancellation.cancel()
            // here. The open proposal is untouched; this recording's turn queues
            // on turnMutex behind the one that opened the gate. Cancelling here
            // would risk discarding a gate outcome that resolves moments later -
            // including a real "sent"/"created" - the instant the top-of-loop
            // cancellation check in AgentLoop.run next runs (see that class's
            // updated doc for the full argument).
            MicState.AWAITING_CONFIRMATION -> beginRecording()
        }
    }

    fun onTalkUp() {
        val rec = recording ?: return
        recording = null
        scope.launch {
            captureJob?.cancelAndJoin()
            captureJob = null
            runCatching { processUtterance(rec) }.onFailure { e ->
                log.error("Utterance processing failed", e)
                appendLine(Speaker.SYSTEM, "Something went wrong: ${e.message}", isError = true)
                sessions.abandon(rec, "processing failed: ${e::class.simpleName}")
                setMicState(MicState.IDLE)
            }
        }
    }

    /** Any key/click during SPEAKING. EC-V3: must cut audio within 100 ms. */
    fun onBargeIn() {
        if (_state.value.micState != MicState.SPEAKING) return
        playback.stop()
    }

    // ── Step 8: photo capture (WF-6) ────────────────────────────────────────

    /**
     * A photo has been picked (via [ImageIntake.pickFile], called by
     * [com.secondbrain.app.voice.VoiceScreen] off the UI thread). Resizes and
     * encodes it, then holds it until the next turn — either a spoken caption
     * (normal hold-to-talk; see [processUtterance]'s pending-image check) or
     * [sendPendingImage] with none.
     *
     * A second call before the first is sent replaces the pending photo
     * rather than queuing two — there is one turn to attach to, so there is
     * one slot.
     */
    fun attachImage(path: java.nio.file.Path) {
        val block = try {
            ImageIntake.encodeForVision(path)
        } catch (e: Exception) {
            log.warn("Could not read image {}: {}", path, e.message)
            _state.update { it.copy(statusLine = "Couldn't read that image: ${e.message}") }
            return
        }
        pendingImage = PendingImage(path.fileName.toString(), block)
        _state.update { it.copy(pendingImageLabel = path.fileName.toString(), statusLine = "") }
    }

    fun clearPendingImage() {
        pendingImage = null
        _state.update { it.copy(pendingImageLabel = null) }
    }

    /**
     * Sends the pending photo with no spoken caption. [DEFAULT_IMAGE_CAPTION]
     * stands in for one — the system prompt's Photos section tells the model
     * to look at what it sees and decide, same as it would from a caption.
     *
     * Goes through [turnMutex] like any other turn, but not through
     * [processUtterance] — there is no recording, so nothing to run STT on.
     */
    fun sendPendingImage() {
        val pending = pendingImage ?: return
        pendingImage = null
        _state.update { it.copy(pendingImageLabel = null) }
        appendLine(Speaker.USER, "[photo: ${pending.label}]")
        scope.launch {
            turnMutex.withLock { runTurnCore(DEFAULT_IMAGE_CAPTION, listOf(pending.block)) }
        }
    }

    // ── Step 8: Zepto sign-in (D-082 gap 1) ─────────────────────────────────

    /** No-op if commerce is not live (see [commerceSignIn]'s doc) or a sign-in is already in flight. */
    fun signInToCommerce() {
        val signIn = commerceSignIn ?: return
        scope.launch {
            _state.update { it.copy(statusLine = "Opening your browser to sign in to Zepto…") }
            signIn().fold(
                onSuccess = {
                    _state.update { it.copy(statusLine = "Signed in to Zepto.", commerceSignedIn = true) }
                },
                onFailure = { e ->
                    log.warn("Zepto sign-in failed: {}", e.message)
                    _state.update { it.copy(statusLine = "Zepto sign-in didn't complete: ${e.message}") }
                },
            )
        }
    }

    fun shutdown() {
        runCatching { playback.stop() }
        captureJob?.cancel()
        runCatching { store.endConversation(conversationState.conversationId) }
    }

    // ── recording ────────────────────────────────────────────────────────────

    private fun beginRecording() {
        playback.stop() // belt and braces, same as the Step 1 harness
        val rec = sessions.begin(AudioFormatSpec.CAPTURE)
        recording = rec
        setMicState(MicState.LISTENING)
        captureJob = scope.launch {
            try {
                capture.capture().collect { chunk ->
                    sessions.append(rec, chunk)
                    val pcmBytes = (Files.size(rec.wavPath) - WavCodec.HEADER_BYTES).coerceAtLeast(0L)
                    if (VoiceGate.hasHitDurationCap(pcmBytes, AudioFormatSpec.CAPTURE, appConfig.gate)) {
                        throw CaptureCapReached()
                    }
                }
            } catch (_: CaptureCapReached) {
                // EC-V6 / E6: hard stop at the cap. onTalkUp() processes what we have.
            } catch (e: AudioDeviceLostException) {
                log.error("Audio device lost: {}", e.message)
                recording = null
                setMicState(MicState.IDLE)
                appendLine(Speaker.SYSTEM, e.message ?: "Lost the microphone.", isError = true)
                scope.launch { speak("I lost the microphone. Plug it back in and try again.") }
            }
        }
    }

    private class CaptureCapReached : RuntimeException("utterance duration cap reached")

    // ── the turn ────────────────────────────────────────────────────────────

    private suspend fun processUtterance(rec: SessionStore.Recording) {
        val utterance = sessions.finishRecording(rec, truncatedByCap = false)

        // ── EC-V1 pre-flight: zero API cost below threshold. Applies uniformly,
        // including to an ask_user answer — a fumbled press should just be
        // retried, not silently counted as "no answer" (see the file's
        // top-level design note on this call site).
        val pcmBytes = (Files.size(utterance.wavPath) - WavCodec.HEADER_BYTES).coerceAtLeast(0L)
        val verdict = VoiceGate.evaluate(pcmBytes, utterance.peakRmsDbfs, AudioFormatSpec.CAPTURE, appConfig.gate, thresholdDbfs)
        if (verdict is VoiceGate.Verdict.Discard) {
            sessions.discard(rec, "${verdict.reason}: ${verdict.detail}")
            _state.update { it.copy(statusLine = "Too quiet or too short — not sent.") }
            setMicState(MicState.IDLE)
            return
        }

        // ── answering a pending ask_user question? Bypasses turnMutex on
        // purpose: the turn holding that lock is the one waiting for THIS. ──
        val pending = pendingAskAnswer
        if (pending != null) {
            pendingAskAnswer = null
            setMicState(MicState.THINKING)
            val transcript = stt.transcribe(utterance.id, utterance.wavPath, AudioFormatSpec.CAPTURE)
            sessions.commit(rec, utterance, transcript)
            if (transcript.isUsable) {
                appendLine(Speaker.USER, transcript.text)
                pending.complete(VaultTools.AskResult.Answered(transcript.text))
            } else {
                appendLine(Speaker.SYSTEM, sttOutcomeMessage(transcript))
                pending.complete(VaultTools.AskResult.NoAnswer(transcript.status.name.lowercase()))
            }
            return
        }

        // ── EC-Z14: speaking while an ORDER window is open revises the cart. ──
        //
        // Bypasses turnMutex for the same reason the ask_user path above does:
        // the turn holding that lock is the one suspended inside
        // ConfirmationGate.submit, and it is exactly the turn we are about to
        // release. Waiting for the lock here would deadlock against the thing
        // we are trying to unblock.
        //
        // Only ORDER_PLACE. An email or calendar proposal has editable fields
        // in the window itself, so speech has nothing to add there and would
        // just be an ambiguous second way to do the same thing; a cart's
        // contents live on the server and cannot be edited in the window at
        // all, which is what makes this the only route (see OrderProposal).
        val openGate = confirmationGate.state.value
        if (openGate != null && openGate.proposal.kind == LedgerKind.ORDER_PLACE) {
            setMicState(MicState.THINKING)
            val transcript = stt.transcribe(utterance.id, utterance.wavPath, AudioFormatSpec.CAPTURE)
            sessions.commit(rec, utterance, transcript)
            if (transcript.isUsable) {
                appendLine(Speaker.USER, transcript.text)
                // The words go through verbatim. "Make it two, not four" only
                // survives if the numbers do, so nothing paraphrases here.
                confirmationGate.requestRevision(openGate.proposalId, transcript.text)
            } else {
                appendLine(Speaker.SYSTEM, sttOutcomeMessage(transcript))
                setMicState(MicState.AWAITING_CONFIRMATION)
            }
            return
        }

        turnMutex.withLock { runTurn(rec, utterance) }
    }

    private suspend fun runTurn(rec: SessionStore.Recording, utterance: Utterance) {
        setMicState(MicState.THINKING)
        val transcript = stt.transcribe(utterance.id, utterance.wavPath, AudioFormatSpec.CAPTURE)
        sessions.commit(rec, utterance, transcript)

        if (!transcript.isUsable) {
            appendLine(Speaker.SYSTEM, sttOutcomeMessage(transcript))
            speak(prompts.emptyTranscriptFallback())
            // Step 8: a pending photo is NOT dropped on a failed transcript -
            // the user can hold to talk again, or press "send without
            // caption". It only leaves via a turn that actually consumes it,
            // or an explicit clearPendingImage().
            return
        }

        // ── resolving a previous EC-G2 pause? ──
        if (awaitingCostConfirmation) {
            awaitingCostConfirmation = false
            appendLine(Speaker.USER, transcript.text)
            when (CostConfirmation.parse(transcript.text)) {
                CostConfirmation.Verdict.Yes -> {
                    costMeter.raiseCeiling(appConfig.agent.sessionUsdCeiling)
                    appendLine(
                        Speaker.SYSTEM,
                        "Budget extended by $%.2f. The paused request was not kept — say it again.".format(appConfig.agent.sessionUsdCeiling),
                    )
                    speak("Okay. Go ahead and say that again.")
                }
                else -> {
                    appendLine(Speaker.SYSTEM, "Paused. Hold to talk when you'd like to continue.")
                    speak("Okay, I've stopped.")
                }
            }
            return
        }

        appendLine(Speaker.USER, transcript.text)

        // Step 8: a photo attached earlier rides along with the next real
        // utterance, whatever it turns out to say. Consumed here - a caption
        // that arrives, successfully, always claims the pending photo.
        val image = pendingImage
        if (image != null) {
            pendingImage = null
            _state.update { it.copy(pendingImageLabel = null) }
        }

        runTurnCore(
            utteranceText = transcript.text,
            images = image?.let { listOf(it.block) } ?: emptyList(),
            // EC-C2/EC-C3: the recording's own start instant and zone, not
            // call-time Instant.now() - this is what actually wires up
            // Utterance.startedAt's own doc comment end to end (Step 5/6).
            utteranceAt = utterance.startedAt,
            zone = utterance.zoneId,
        )
    }

    /**
     * The shared tail of a turn: cost gate, [AgentLoop.run], bookkeeping,
     * speak the reply. Factored out of [runTurn] so [sendPendingImage] — which
     * has no recording, and therefore nothing to run STT on — can reach the
     * same path without a fake [Utterance].
     */
    private suspend fun runTurnCore(
        utteranceText: String,
        images: List<LlmBlock.Image> = emptyList(),
        utteranceAt: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ) {
        // ── EC-G2: checked before a new utterance starts, never mid-turn ──
        when (val v = costMeter.check()) {
            is CostMeter.Verdict.Blocked -> {
                awaitingCostConfirmation = true
                val msg = "This session has spent $%.2f of its $%.2f budget. Say 'go ahead' to keep going, or 'stop' to leave it there."
                    .format(v.spentUsd, v.ceilingUsd)
                appendLine(Speaker.SYSTEM, msg)
                speak(msg)
                return
            }
            is CostMeter.Verdict.Warn -> _state.update {
                it.copy(statusLine = "Session spend $%.4f of $%.2f.".format(v.spentUsd, v.ceilingUsd))
            }
            CostMeter.Verdict.Proceed -> {}
        }

        cancellation.reset()
        val output = agentLoop.run(
            utterance = utteranceText,
            phase = conversationState.phase,
            history = store.historyFor(conversationState),
            conversationId = conversationState.conversationId,
            turnIndex = turnIndex,
            cancellation = cancellation,
            utteranceAt = utteranceAt,
            zone = zone,
            images = images,
        )
        val result = output.result

        store.recordTurn(conversationState, turnIndex, output.messages, result.usage)
        costMeter.record(CostMeter.Service.CLAUDE, result.usage, conversationState.conversationId, turnIndex, appConfig.agent.model)
        conversationState = store.advance(conversationState, turnIndex, output.messages, ::summarise)
        turnIndex++
        publishCostAndTurn()

        if (result.end == TurnEnd.CANCELLED) {
            // D-048: the user is already talking again; onTalkDown() has moved
            // the mic state on. Touching it here would stomp that.
            return
        }

        appendLine(
            Speaker.ASSISTANT,
            result.spokenText.ifBlank { "(no reply)" },
            notePaths = result.touchedNotes,
            isError = result.end == TurnEnd.API_FAILED || result.end == TurnEnd.REFUSAL,
        )

        if (result.spokenText.isNotBlank()) speak(result.spokenText) else setMicState(MicState.IDLE)
    }

    private fun sttOutcomeMessage(transcript: Transcript): String = when (transcript.status) {
        SttStatus.EMPTY -> "(no speech detected)"
        SttStatus.FAILED -> "Transcription failed after ${transcript.attempts} attempt(s): ${transcript.error}. Your recording is safe."
        SttStatus.OK -> "(nothing intelligible)"
    }

    // ── ask_user: the VaultTools.AskResult supplier ────────────────────────

    /**
     * Speaks [question], then suspends — genuinely, for as long as it takes a
     * person to answer — until the user's next talk-press-and-release resolves
     * it. D-055 leaves the timeout to the caller because "what the right wait
     * is has not been measured"; this implements the honest version of that:
     * no timeout at all, since inventing a number here would be exactly the
     * kind of guess CLAUDE.md's working style says not to make silently.
     *
     * Deliberately outside [turnMutex]. This IS the [pendingAskAnswer] the
     * mutex-holding turn in [runTurn] is waiting on; taking the same lock here
     * would deadlock against itself.
     */
    suspend fun handleAskUser(question: String): VaultTools.AskResult {
        appendLine(Speaker.ASSISTANT, question)
        speak(question) // ends back at IDLE
        _state.update { it.copy(awaitingAnswer = true) }
        val deferred = CompletableDeferred<VaultTools.AskResult>()
        pendingAskAnswer = deferred
        try {
            return deferred.await()
        } finally {
            _state.update { it.copy(awaitingAnswer = false) }
        }
    }

    // ── request_typed_input: D-008's sanctioned typing escape hatch ────────

    /**
     * Backs `request_typed_input`. Speaks [prompt] (it is still information
     * worth hearing, even though the answer is typed), then suspends until the
     * UI's typed-input overlay calls [submitTypedInput] or [cancelTypedInput].
     *
     * Unlike [handleAskUser], nothing here touches the microphone or
     * [turnMutex] at all — this is a keyboard interaction end to end, so there
     * is no recording to race against and no reason to bypass the mutex the
     * way [handleAskUser]'s answer-delivery path has to.
     */
    suspend fun handleTypedInput(prompt: String, kind: String): VaultTools.AskResult {
        appendLine(Speaker.ASSISTANT, prompt)
        speak(prompt)
        _state.update { it.copy(pendingTypedInput = TypedInputRequest(prompt, kind)) }
        val deferred = CompletableDeferred<VaultTools.AskResult>()
        pendingTypedAnswer = deferred
        try {
            return deferred.await()
        } finally {
            _state.update { it.copy(pendingTypedInput = null) }
        }
    }

    /** Called from the typed-input overlay when the user submits a value. */
    fun submitTypedInput(value: String) {
        val deferred = pendingTypedAnswer ?: return
        pendingTypedAnswer = null
        appendLine(Speaker.USER, value)
        deferred.complete(VaultTools.AskResult.Answered(value))
    }

    /** Called from the typed-input overlay when the user dismisses it without a value. */
    fun cancelTypedInput() {
        val deferred = pendingTypedAnswer ?: return
        pendingTypedAnswer = null
        deferred.complete(VaultTools.AskResult.NoAnswer("user_cancelled"))
    }

    // ── the rolling summariser (D-047) ─────────────────────────────────────

    /**
     * The `summariser` [ConversationStore.advance] asks for. A one-shot Claude
     * call over a text digest of the turns falling out of the window — never a
     * replay of their raw blocks; see [ConversationDigest]'s doc for why that
     * would be unsafe.
     */
    private suspend fun summarise(dropped: List<LlmMessage>, previous: String?): String {
        val digest = ConversationDigest.render(dropped)
        if (digest.isBlank()) return previous.orEmpty()

        val request = LlmRequest(
            model = appConfig.agent.summaryModel,
            systemPrompt = "You summarise conversation excerpts in one short paragraph for later reuse.",
            messages = listOf(LlmMessage(LlmMessage.Role.USER, listOf(LlmBlock.Text(prompts.summarizeInstruction(digest, previous))))),
            tools = emptyList(),
            maxTokens = 300,
            thinkingEnabled = false,
            effort = null,
            // A one-shot request has its own prefix and will not see a cache
            // hit regardless of this flag (D-047) - explicit false so nobody
            // reads a cost-meter anomaly here as a caching bug later.
            cacheSystemPrefix = false,
        )
        val response = llm.send(request)
        costMeter.record(
            CostMeter.Service.CLAUDE_SUMMARY, response.usage,
            conversationState.conversationId, turnIndex, appConfig.agent.summaryModel,
        )
        return response.text.trim().ifBlank { previous.orEmpty() }
    }

    // ── speech out ──────────────────────────────────────────────────────────

    private suspend fun speak(rawText: String) {
        val normalized = SpeechNormalizer.normalize(rawText)
        val capped = SpeechNormalizer.capForSpeech(normalized, appConfig.speech.maxSpeechSeconds)
        if (capped.text.isBlank()) {
            setMicState(MicState.IDLE)
            return
        }

        val request = SpeechRequest(
            text = capped.text,
            voice = appConfig.tts.voice,
            speed = appConfig.tts.speed,
            maxSpeechSeconds = appConfig.speech.maxSpeechSeconds,
        )
        setMicState(MicState.SPEAKING)

        // EC-T3: fill dead air with a soft cue if nothing is audible yet.
        val cueJob = scope.launch {
            delay(appConfig.tts.thinkingCueAfterMs)
            if (_state.value.micState == MicState.SPEAKING) {
                runCatching { playback.play(flow { emit(ThinkingCue.chunk(AudioFormatSpec.CAPTURE)) }) }
            }
        }

        try {
            val marked = flow {
                var first = true
                primaryTts.synthesize(request).collect { chunk ->
                    if (first) { first = false; cueJob.cancel() }
                    emit(chunk)
                }
            }
            playback.play(marked)
        } catch (e: TtsUnavailableException) {
            cueJob.cancel()
            log.warn("Primary TTS unavailable: {}", e.message)
            if (appConfig.tts.fallbackEnabled && fallbackTts.isAvailable) {
                appendLine(Speaker.SYSTEM, "Voice service unavailable — using the local voice.", isError = true)
                runCatching { playback.play(fallbackTts.synthesize(request)) }
                    .onFailure { appendLine(Speaker.SYSTEM, "Local voice failed too. Text only: ${capped.text}", isError = true) }
            } else {
                appendLine(Speaker.SYSTEM, "No voice available. Text only: ${capped.text}", isError = true)
            }
        } finally {
            cueJob.cancel()
            if (_state.value.micState == MicState.SPEAKING) setMicState(MicState.IDLE)
        }

        if (capped.truncated) {
            appendLine(Speaker.SYSTEM, "(truncated for speech — the full reply is above)")
        }
    }

    // ── small helpers ───────────────────────────────────────────────────────

    private fun appendLine(speaker: Speaker, text: String, notePaths: List<String> = emptyList(), isError: Boolean = false) {
        val line = TranscriptLine(lineId.getAndIncrement(), speaker, text, notePaths, isError)
        _state.update { it.copy(lines = it.lines + line, statusLine = "") }
    }

    private fun setMicState(next: MicState) {
        _state.update { it.copy(micState = next) }
    }

    private fun publishCostAndTurn() {
        _state.update {
            it.copy(
                sessionCostLabel = costMeter.sessionLabel(),
                turnLabel = conversationState.turnLabel(appConfig.agent.contextWindowTurns),
                phase = conversationState.phase,
            )
        }
    }

    private companion object {
        /**
         * Stands in for a caption on [sendPendingImage]'s no-speech path. Not
         * a prompt asking the model to guess — the Photos section of
         * [SystemPrompt.system] is what actually tells it what to do with an
         * attached image; this is just the text `AgentLoop.run` needs
         * something in the `utterance` slot to log and persist.
         */
        const val DEFAULT_IMAGE_CAPTION = "(no caption — see the attached photo)"
    }
}
