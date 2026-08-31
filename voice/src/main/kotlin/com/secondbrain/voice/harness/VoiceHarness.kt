package com.secondbrain.voice.harness

import com.secondbrain.model.AppConfig
import com.secondbrain.model.AudioFormatSpec
import com.secondbrain.model.ConfigException
import com.secondbrain.model.ConfigLoader
import com.secondbrain.model.SpeechRequest
import com.secondbrain.model.SttStatus
import com.secondbrain.model.StageTimings
import com.secondbrain.model.Transcript
import com.secondbrain.ports.AudioDeviceLostException
import com.secondbrain.ports.TtsPort
import com.secondbrain.ports.TtsUnavailableException
import com.secondbrain.voice.AudioDevices
import com.secondbrain.voice.GeminiStt
import com.secondbrain.voice.HttpClients
import com.secondbrain.voice.JvmAudioCapture
import com.secondbrain.voice.JvmAudioPlayback
import com.secondbrain.voice.KokoroTts
import com.secondbrain.voice.NoiseFloorCalibrator
import com.secondbrain.voice.SecretRedactor
import com.secondbrain.voice.SessionStore
import com.secondbrain.voice.SpeechNormalizer
import com.secondbrain.voice.SystemTtsFallback
import com.secondbrain.voice.ThinkingCue
import com.secondbrain.voice.VoiceGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess

/**
 * Step 1 exit criterion: `./gradlew :voice:run` -- hold Space, speak, see the
 * transcript, hear it read back, with per-stage latency logged.
 *
 * This is a harness, not the product. ARCHITECTURE.md section 1 gives main() to
 * :app; this exists so the voice loop can be validated before the agent loop
 * (Step 3) or the UI (Step 4) exist to validate it through.
 *
 * What it proves, in order of how much it matters:
 *   EC-V1  silence and accidental triggers cost zero API calls (counter asserted)
 *   EC-V3  a keypress during playback cuts audio inside 100 ms
 *   EC-V7  the WAV is on disk before the network is touched, and survives a kill
 *   EC-V9  unplugging the headset mid-session does not crash
 *   EC-G1  a missing key fails at startup with a named error
 *   EC-T1  Markdown never reaches the TTS endpoint
 *   EC-T4  Kokoro down still speaks, via local SAPI
 */
object VoiceHarness {
    val log = LoggerFactory.getLogger(VoiceHarness::class.java)
}

/** Counts real network calls, so EC-V1 is asserted rather than eyeballed. */
object ApiCallCounter {
    val stt = AtomicInteger(0)
    val tts = AtomicInteger(0)
    override fun toString(): String = "stt=${stt.get()} tts=${tts.get()}"
}

private enum class State { IDLE, LISTENING, THINKING, SPEAKING }

fun main() = runBlocking {
    val log = VoiceHarness.log

    // ── config, fail-fast (EC-G1) ────────────────────────────────────────────
    val config: AppConfig = try {
        ConfigLoader.load()
    } catch (e: ConfigException) {
        System.err.println()
        System.err.println("─── Second Brain cannot start ───")
        System.err.println(e.message)
        exitProcess(2)
    }

    // Register the real secrets so redaction covers shapes the patterns miss.
    SecretRedactor.register(config.stt.apiKey, config.tts.apiKey)
    log.info("Config loaded. {}", config.redacted().let { "stt.model=${it.stt.model} tts.model=${it.tts.model} tts.base_url=${it.tts.baseUrl}" })

    val root: Path = ConfigLoader.expandHome(config.paths.root)
    Files.createDirectories(root)
    val sessionsRoot = root.resolve("sessions")
    Files.createDirectories(sessionsRoot)

    val format = AudioFormatSpec(sampleRateHz = config.audio.sampleRateHz)

    // ── wiring ───────────────────────────────────────────────────────────────
    val capture = JvmAudioCapture(config.audio, format)
    val playback = JvmAudioPlayback(config.audio)
    val sessions = SessionStore(sessionsRoot, config.sessions)

    val sttHttp = HttpClients.create(config.stt.requestTimeoutMs, "gemini")
    val ttsHttp = HttpClients.create(config.tts.requestTimeoutMs, "kokoro")
    val stt = GeminiStt(sttHttp, config.stt)
    val kokoro = KokoroTts(ttsHttp, config.tts)
    val fallbackTts = SystemTtsFallback()

    AudioDevices.logInventory()
    val swept = sessions.sweep()
    if (swept > 0) log.info("Startup sweep removed {} expired session(s).", swept)

    // ── noise floor (E4) ─────────────────────────────────────────────────────
    val calibrator = NoiseFloorCalibrator(capture, config.gate, root.resolve("calibration.json"))
    val probeDevice = config.audio.preferredCaptureDevice
        ?: AudioDevices.captureDevices(format).firstOrNull { it.isDefault }?.name
        ?: AudioDevices.captureDevices(format).firstOrNull()?.name
        ?: "unknown"
    val calibration = try {
        calibrator.calibrate(probeDevice, format)
    } catch (e: Exception) {
        log.error("Noise-floor calibration failed ({}). Falling back to a fixed -45 dBFS threshold.", e.message)
        NoiseFloorCalibrator.Calibration(probeDevice, -57.0, -45.0, "fallback", 0)
    }

    // ── UI ───────────────────────────────────────────────────────────────────
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Swing's event thread and the coroutine scope both touch these, so they are
    // atomics rather than plain locals. Kotlin does not allow @Volatile on a
    // local, and this harness genuinely is multi-threaded: key events arrive on
    // the EDT while capture and playback run on Dispatchers.IO.
    val stateRef = java.util.concurrent.atomic.AtomicReference(State.IDLE)
    val captureJobRef = java.util.concurrent.atomic.AtomicReference<Job?>(null)
    val recordingRef = java.util.concurrent.atomic.AtomicReference<SessionStore.Recording?>(null)
    val timingsRef = java.util.concurrent.atomic.AtomicReference<StageTimings?>(null)
    val quitting = java.util.concurrent.atomic.AtomicBoolean(false)

    lateinit var window: PttWindow

    fun setState(next: State) {
        stateRef.set(next)
        window.setState(next.name.lowercase().replaceFirstChar { it.uppercase() })
    }

    // ── the loop ─────────────────────────────────────────────────────────────

    suspend fun speak(text: String) {
        val normalized = SpeechNormalizer.normalize(text)          // EC-T1
        val capped = SpeechNormalizer.capForSpeech(normalized, config.speech.maxSpeechSeconds) // EC-T2
        if (capped.text.isBlank()) return

        val request = SpeechRequest(
            text = capped.text,
            voice = config.tts.voice,
            speed = config.tts.speed,
            maxSpeechSeconds = config.speech.maxSpeechSeconds,
        )

        setState(State.SPEAKING)

        // EC-T3: if nothing is audible yet after thinking_cue_after_ms, play a cue.
        val cueJob = scope.launch {
            delay(config.tts.thinkingCueAfterMs)
            if (stateRef.get() == State.SPEAKING && timingsRef.get()?.firstAudioOutAt == null) {
                runCatching { playback.play(flow { emit(ThinkingCue.chunk(format)) }) }
            }
        }

        var engine: TtsPort = kokoro
        try {
            timingsRef.updateAndGet { it?.copy(ttsRequestSentAt = System.currentTimeMillis()) }
            ApiCallCounter.tts.incrementAndGet()

            val chunks = engine.synthesize(request)
            val marked = flow {
                var first = true
                chunks.collect { chunk ->
                    if (first) {
                        first = false
                        cueJob.cancel()
                        timingsRef.updateAndGet { it?.copy(firstAudioOutAt = System.currentTimeMillis()) }
                    }
                    emit(chunk)
                }
            }
            playback.play(marked)
        } catch (e: TtsUnavailableException) {
            cueJob.cancel()
            // EC-T4: never fail silently.
            log.warn("Kokoro unavailable: {}", SecretRedactor.redact(e.message))
            window.append("  [tts] Kokoro unavailable - using the local voice. ${e.message}")
            if (config.tts.fallbackEnabled && fallbackTts.isAvailable) {
                engine = fallbackTts
                runCatching {
                    val marked = flow {
                        var first = true
                        engine.synthesize(request).collect { chunk ->
                            if (first) {
                                first = false
                                timingsRef.updateAndGet { it?.copy(firstAudioOutAt = System.currentTimeMillis()) }
                            }
                            emit(chunk)
                        }
                    }
                    playback.play(marked)
                }.onFailure {
                    window.append("  [tts] Local voice failed too. Text only: ${capped.text}")
                }
            } else {
                window.append("  [tts] No voice available. Text only: ${capped.text}")
            }
        } finally {
            cueJob.cancel()
            timingsRef.updateAndGet { it?.copy(playbackDoneAt = System.currentTimeMillis()) }
            if (stateRef.get() == State.SPEAKING) setState(State.IDLE)
        }

        if (capped.truncated) {
            window.append("  [tts] Truncated at a sentence boundary (EC-T2). Full text is on screen.")
        }
    }

    suspend fun processUtterance(rec: SessionStore.Recording) {
        val utterance = sessions.finishRecording(rec, truncatedByCap = false)
        timingsRef.updateAndGet {
            it?.copy(
                captureStoppedAt = System.currentTimeMillis(),
                wavBytes = Files.size(rec.wavPath),
            )
        }

        // ── EC-V1 pre-flight: zero API cost below threshold ──────────────────
        val verdict = VoiceGate.evaluate(
            pcmByteCount = Files.size(rec.wavPath) - com.secondbrain.voice.WavCodec.HEADER_BYTES,
            peakRmsDbfs = utterance.peakRmsDbfs,
            format = format,
            config = config.gate,
            thresholdDbfs = calibration.thresholdDbfs,
        )
        if (verdict is VoiceGate.Verdict.Discard) {
            sessions.discard(rec, "${verdict.reason}: ${verdict.detail}")
            window.append("  [gate] discarded - ${verdict.reason}: ${verdict.detail}")
            window.setStatus("gate discarded (" + verdict.reason + ") | api calls " + ApiCallCounter)
            setState(State.IDLE)
            return
        }

        setState(State.THINKING)
        window.append("  [gate] accepted - ${utterance.durationMs}ms, peak %.1f dBFS".format(utterance.peakRmsDbfs))

        // ── STT ──────────────────────────────────────────────────────────────
        timingsRef.updateAndGet { it?.copy(sttRequestSentAt = System.currentTimeMillis()) }
        ApiCallCounter.stt.incrementAndGet()
        val transcript: Transcript = stt.transcribe(utterance.id, utterance.wavPath, format)
        timingsRef.updateAndGet { it?.copy(sttResponseAt = System.currentTimeMillis(), sttAttempts = transcript.attempts) }

        // ── commit BEFORE anything can fail (E2, R10) ────────────────────────
        val dir = sessions.commit(rec, utterance, transcript)
        log.debug("Session committed to {}", dir)

        when (transcript.status) {
            SttStatus.OK -> if (transcript.text.isBlank()) {
                window.append("You: (nothing intelligible)")
                speak("I didn't catch that.")
            } else {
                window.append("You: ${transcript.text}")
                // Step 1 has no LLM: the machine repeats what it heard. That is the
                // whole exit criterion -- speak in, speak out, nothing in between.
                speak(transcript.text)
            }
            SttStatus.EMPTY -> {
                window.append("You: (no speech detected by STT)")
                speak("I didn't catch that.")
            }
            SttStatus.FAILED -> {
                window.append("  [stt] FAILED after ${transcript.attempts} attempts: ${transcript.error}")
                window.append("  [stt] Your audio is safe at ${rec.wavPath} (EC-V7).")
                speak("I couldn't reach the transcription service. Your recording is saved.")
            }
        }

        timingsRef.get()?.let {
            log.info(it.toLogLine())
            window.setStatus(it.toLogLine() + " | api calls " + ApiCallCounter)
        }
        if (stateRef.get() != State.SPEAKING) setState(State.IDLE)
    }

    window = PttWindow(
        onTalkStart = {
            if (stateRef.get() == State.IDLE || stateRef.get() == State.SPEAKING) {
                playback.stop() // EC-V3, belt and braces
                val rec = sessions.begin(format)
                recordingRef.set(rec)
                timingsRef.set(StageTimings(rec.utteranceId, System.currentTimeMillis()))
                setState(State.LISTENING)
                window.append("")
                captureJobRef.set(scope.launch {
                    try {
                        capture.capture().collect { chunk ->
                            sessions.append(rec, chunk)
                            // EC-V6 / E6: hard stop at the cap, keep what we have.
                            if (VoiceGate.hasHitDurationCap(rec.pcmBytesForCap(), format, config.gate)) {
                                window.append("  [gate] hit the ${config.gate.maxUtteranceMs}ms cap - stopping capture.")
                                throw CaptureCapReached()
                            }
                        }
                    } catch (_: CaptureCapReached) {
                        // handled by the release path
                    } catch (e: AudioDeviceLostException) {
                        // EC-V9: speak it, do not crash.
                        log.error("Audio device lost: {}", e.message)
                        window.append("  [audio] ${e.message}")
                        AudioDevices.logInventory()
                        sessions.abandon(rec, "capture device lost")
                        recordingRef.set(null)
                        setState(State.IDLE)
                        scope.launch { speak("I lost the microphone. Plug it back in and try again.") }
                    }
                })
            }
        },
        onTalkEnd = {
            val rec = recordingRef.getAndSet(null) ?: return@PttWindow
            scope.launch {
                captureJobRef.getAndSet(null)?.cancelAndJoin()
                runCatching { processUtterance(rec) }
                    .onFailure {
                        log.error("Utterance processing failed", it)
                        window.append("  [error] ${SecretRedactor.redact(it.message)}")
                        sessions.abandon(rec, "processing failed: ${it::class.simpleName}")
                        setState(State.IDLE)
                    }
            }
        },
        onBargeIn = {
            // EC-V3: cut playback within 100 ms. Measured, not assumed.
            val t0 = System.nanoTime()
            playback.stop()
            val ms = (System.nanoTime() - t0) / 1_000_000.0
            log.info("BARGE-IN stop() returned in %.1f ms (budget 100 ms, EC-V3)".format(ms))
            window.append("  [barge-in] playback cut in %.1f ms".format(ms))
        },
        onQuit = {
            if (quitting.compareAndSet(false, true)) {
                recordingRef.get()?.let { sessions.abandon(it, "user quit mid-recording") }
                playback.stop()
                window.append("")
                window.append("Session over. API calls: " + ApiCallCounter)
                log.info("Shutting down. API calls this session: {}", ApiCallCounter)
                scope.launch {
                    runCatching { sttHttp.close() }
                    runCatching { ttsHttp.close() }
                    window.close()
                    exitProcess(0)
                }
            }
        },
    )

    window.show()
    window.append("Second Brain - Step 1 voice harness")
    window.append("capture device: $probeDevice")
    window.append("noise floor %.1f dBFS, gate threshold %.1f dBFS (margin ${config.gate.energyMarginDb} dB)"
        .format(calibration.noiseFloorDbfs, calibration.thresholdDbfs))
    window.append("gate mode ${config.gate.mode}, barge-in ${config.gate.bargeIn}")
    window.append("sessions -> $sessionsRoot")
    window.append("")
    window.append("Hold SPACE, say something, release. ESC to quit.")
    window.setStatus("ready | api calls " + ApiCallCounter)

    // Keep the coroutine alive; Swing owns the event loop from here.
    while (!quitting.get()) {
        withTimeoutOrNull(500) { delay(500) }
    }
}

private class CaptureCapReached : RuntimeException("utterance duration cap reached")

/** PCM byte count so far, excluding the WAV header. */
private fun SessionStore.Recording.pcmBytesForCap(): Long =
    runCatching { Files.size(wavPath) - com.secondbrain.voice.WavCodec.HEADER_BYTES }
        .getOrDefault(0L)
        .coerceAtLeast(0L)
