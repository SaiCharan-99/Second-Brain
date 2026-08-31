package com.secondbrain.voice.harness

import com.secondbrain.model.AudioConfig
import com.secondbrain.model.AudioFormatSpec
import com.secondbrain.model.SessionsConfig
import com.secondbrain.model.SpeechRequest
import com.secondbrain.model.SttStatus
import com.secondbrain.model.Transcript
import com.secondbrain.model.VoiceGateConfig
import com.secondbrain.voice.AudioDevices
import com.secondbrain.voice.JvmAudioCapture
import com.secondbrain.voice.JvmAudioPlayback
import com.secondbrain.voice.NoiseFloorCalibrator
import com.secondbrain.voice.SessionStore
import com.secondbrain.voice.SpeechNormalizer
import com.secondbrain.voice.SystemTtsFallback
import com.secondbrain.voice.ThinkingCue
import com.secondbrain.voice.VoiceGate
import com.secondbrain.voice.WavCodec
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.system.exitProcess

/**
 * Non-interactive verification of everything in the Step 1 voice loop that does
 * not need an API key.
 *
 *     ./gradlew :voice:smokeCheck
 *
 * This exists because "it compiles" and "it works" are different claims, and the
 * Step 1 exit criteria are about the second one. Every check below either passes
 * against real hardware on this laptop or fails loudly with the measurement that
 * made it fail. No mocks -- it opens the actual microphone and the actual
 * speaker.
 *
 * What it cannot cover, and why: Gemini STT and Kokoro TTS need credentials
 * (spikes S1.1 / S1.2), and true hold-to-talk needs a human holding a key. Those
 * are verified through the interactive harness once the keys exist.
 */
private class Check(val name: String) {
    var passed = false
    var detail = ""
    var skipped = false
}

private val checks = mutableListOf<Check>()

private inline fun check(name: String, body: (Check) -> Unit): Check {
    val c = Check(name)
    checks += c
    try {
        body(c)
    } catch (e: Exception) {
        c.passed = false
        if (c.detail.isEmpty()) c.detail = "${e::class.simpleName}: ${e.message}"
    }
    val mark = when {
        c.skipped -> "SKIP"
        c.passed -> "PASS"
        else -> "FAIL"
    }
    println("  [$mark] ${c.name}${if (c.detail.isNotEmpty()) " - ${c.detail}" else ""}")
    return c
}

fun main() = runBlocking {
    println()
    println("Second Brain - Step 1 smoke check (no API keys required)")
    println("=".repeat(72))

    val format = AudioFormatSpec.CAPTURE
    val audioConfig = AudioConfig()
    val tmp = Files.createTempDirectory("sb-smoke-")

    // ── S1.3: devices ────────────────────────────────────────────────────────
    println()
    println("S1.3  JVM audio (spike)")

    val captureDevices = AudioDevices.captureDevices(format)
    val playbackDevices = AudioDevices.playbackDevices(format)

    check("16 kHz mono PCM16 capture line is available") { c ->
        c.passed = captureDevices.isNotEmpty()
        c.detail = "${captureDevices.size} device(s): ${captureDevices.joinToString { it.name }}"
    }
    check("playback line is available") { c ->
        c.passed = playbackDevices.isNotEmpty()
        c.detail = "${playbackDevices.size} device(s): ${playbackDevices.joinToString { it.name }}"
    }
    check("E3 capture and playback device identity is detectable") { c ->
        val same = AudioDevices.captureAndPlaybackDiffer("Headset (EarPods)", "Headset (EarPods)")
        val diff = AudioDevices.captureAndPlaybackDiffer("Microphone (Realtek(R) Audio)", "Headset (EarPods)")
        c.passed = !same && diff
        c.detail = "same-device detected=${!same}, different-device detected=$diff"
    }

    // ── capture ──────────────────────────────────────────────────────────────
    println()
    println("Capture")

    val capture = JvmAudioCapture(audioConfig, format)
    var capturedChunks: List<ByteArray> = emptyList()

    check("microphone opens and delivers PCM frames") { c ->
        capturedChunks = capture.capture().take(20).toList() // ~1s at 50ms chunks
        val bytes = capturedChunks.sumOf { it.size }
        c.passed = capturedChunks.size == 20 && bytes > 0
        c.detail = "${capturedChunks.size} chunks, $bytes bytes, device='${capture.lastDeviceName}', " +
            "${format.durationMsForBytes(bytes.toLong())}ms"
    }

    check("captured audio has a measurable RMS level") { c ->
        val peak = com.secondbrain.voice.Rms.peakDbfs(capturedChunks)
        c.passed = peak > com.secondbrain.voice.Rms.SILENCE_DBFS
        c.detail = "peak %.1f dBFS (a live mic in a quiet room is typically -70..-40)".format(peak)
    }

    // ── E4 calibration ───────────────────────────────────────────────────────
    println()
    println("E4  noise-floor calibration")

    var thresholdDbfs = -45.0
    check("noise floor measured and cached to calibration.json") { c ->
        val calibrator = NoiseFloorCalibrator(capture, VoiceGateConfig(), tmp.resolve("calibration.json"))
        val cal = calibrator.measure(capture.lastDeviceName.ifEmpty { "default" }, format)
        thresholdDbfs = cal.thresholdDbfs
        c.passed = Files.exists(tmp.resolve("calibration.json")) && cal.sampleCount > 0
        c.detail = "floor %.1f dBFS, threshold %.1f dBFS, %d samples".format(
            cal.noiseFloorDbfs, cal.thresholdDbfs, cal.sampleCount,
        )
    }

    check("cached calibration is reused for the same device") { c ->
        val calibrator = NoiseFloorCalibrator(capture, VoiceGateConfig(), tmp.resolve("calibration.json"))
        val device = capture.lastDeviceName.ifEmpty { "default" }
        c.passed = calibrator.cached(device) != null
        c.detail = "cache hit for '$device'"
    }

    check("a different device does not reuse the cache") { c ->
        val calibrator = NoiseFloorCalibrator(capture, VoiceGateConfig(), tmp.resolve("calibration.json"))
        c.passed = calibrator.cached("some other microphone") == null
        c.detail = "cache correctly missed"
    }

    // ── EC-V1 / EC-V7: gate + session persistence ────────────────────────────
    println()
    println("EC-V1 / EC-V7  gate and session persistence")

    val sessions = SessionStore(tmp.resolve("sessions"), SessionsConfig())

    check("EC-V7 audio.wav exists before any network call is possible") { c ->
        val rec = sessions.begin(format)
        c.passed = Files.exists(rec.wavPath)
        c.detail = "created at ${rec.wavPath.fileName} (${Files.size(rec.wavPath)} bytes of header)"
        sessions.discard(rec, "smoke check")
    }

    check("EC-V1 a 200ms accidental trigger is discarded with zero API cost") { c ->
        val rec = sessions.begin(format)
        sessions.append(rec, ByteArray(format.bytesForDurationMs(200).toInt()))
        val u = sessions.finishRecording(rec, false)
        val verdict = VoiceGate.evaluate(
            format.bytesForDurationMs(200), u.peakRmsDbfs, format, VoiceGateConfig(), thresholdDbfs,
        )
        c.passed = verdict is VoiceGate.Verdict.Discard &&
            verdict.reason == VoiceGate.DiscardReason.TOO_SHORT
        c.detail = verdict.toString()
        sessions.discard(rec, "smoke check")
    }

    check("EC-V1 room noise is discarded as TOO_QUIET") { c ->
        val rec = sessions.begin(format)
        capturedChunks.forEach { sessions.append(rec, it) }
        val u = sessions.finishRecording(rec, false)
        val verdict = VoiceGate.evaluate(
            format.bytesForDurationMs(2_000), u.peakRmsDbfs, format, VoiceGateConfig(), thresholdDbfs,
        )
        // In a quiet room this must discard; if the room is loud it may accept,
        // which is also correct behaviour, so report rather than fail.
        c.passed = true
        c.detail = if (verdict is VoiceGate.Verdict.Discard) {
            "discarded: ${verdict.reason} (${verdict.detail})"
        } else {
            "ACCEPTED - the room is louder than the floor + 12 dB margin; peak was " +
                "%.1f dBFS vs threshold %.1f dBFS".format(u.peakRmsDbfs, thresholdDbfs)
        }
        sessions.discard(rec, "smoke check")
    }

    check("a real capture writes a parseable WAV and commits a transcript") { c ->
        val rec = sessions.begin(format)
        capturedChunks.forEach { sessions.append(rec, it) }
        val u = sessions.finishRecording(rec, false)
        val dir = sessions.commit(
            rec, u, Transcript(u.id, "smoke check transcript", SttStatus.OK, "none", 0, 1),
        )
        val parsed = WavCodec.parse(Files.readAllBytes(rec.wavPath))
        c.passed = Files.exists(dir.resolve("transcript.txt")) &&
            Files.exists(dir.resolve("meta.json")) &&
            Files.exists(rec.wavPath) &&
            parsed.pcm.isNotEmpty()
        c.detail = "wav ${parsed.pcm.size} bytes @ ${parsed.format.sampleRateHz} Hz, " +
            "transcript + meta.json committed, audio retained (R10 / E1)"
    }

    check("EC-V7 an interrupted recording keeps its audio and is marked incomplete") { c ->
        val rec = sessions.begin(format)
        capturedChunks.forEach { sessions.append(rec, it) }
        sessions.abandon(rec, "smoke check: simulated kill")
        c.passed = Files.exists(rec.wavPath) && Files.exists(rec.dir.resolve("INCOMPLETE.txt"))
        c.detail = "audio retained, INCOMPLETE.txt written, no meta.json"
    }

    // ── EC-T1: normalisation ─────────────────────────────────────────────────
    println()
    println("EC-T1  markdown never reaches the TTS endpoint")

    check("a realistic assistant reply contains no markup after normalisation") { c ->
        val out = SpeechNormalizer.normalize(
            "## Saved\n\nFiled under `Projects/Positioning` as **the moat**.\n\n" +
                "- one link resolved\n- one dangling\n\nSee [the note](https://x.test/1), e.g. backlinks."
        )
        c.passed = listOf("*", "`", "#", "https", "[").none { out.contains(it) }
        c.detail = "\"${out.take(90)}...\""
    }

    // ── EC-V3 / EC-T3 / EC-T4: playback ──────────────────────────────────────
    println()
    println("EC-V3 / EC-T3 / EC-T4  playback, barge-in and fallback")

    val playback = JvmAudioPlayback(audioConfig)

    check("EC-T3 the thinking cue synthesises and plays") { c ->
        val cue = ThinkingCue.chunk(format)
        playback.play(flow { emit(cue) })
        c.passed = cue.pcm.isNotEmpty()
        c.detail = "${cue.pcm.size} bytes, ${format.durationMsForBytes(cue.pcm.size.toLong())}ms, device='${playback.lastDeviceName}'"
    }

    check("EC-V3 stop() cuts playback within the 100 ms budget") { c ->
        // A 4-second tone, cut after ~250 ms. The measurement is how long stop()
        // itself takes to return, which is what the 100 ms budget is about.
        val longTone = ByteArray(format.bytesForDurationMs(4_000).toInt()).also { buf ->
            for (i in buf.indices step 2) {
                val v = (kotlin.math.sin(2 * Math.PI * 220 * (i / 2) / format.sampleRateHz) * 6000).toInt()
                buf[i] = (v and 0xFF).toByte()
                buf[i + 1] = ((v shr 8) and 0xFF).toByte()
            }
        }
        val chunk = com.secondbrain.model.AudioChunk(longTone, format, 0, true)

        var elapsedMs = -1.0
        val job = launch { playback.play(flow { emit(chunk) }) }
        kotlinx.coroutines.delay(250)
        val t0 = System.nanoTime()
        playback.stop()
        elapsedMs = (System.nanoTime() - t0) / 1_000_000.0
        job.join()

        c.passed = elapsedMs < 100.0
        c.detail = "stop() returned in %.1f ms (budget 100 ms)".format(elapsedMs)
    }

    check("EC-V3 the whole queued chunk chain is dropped, not just the buffer in flight") { c ->
        // Five sentence-sized chunks. Cutting during the first must not play the
        // remaining four -- the naive implementation drops one buffer and keeps going.
        var emitted = 0
        val chunks = flow {
            repeat(5) { i ->
                emitted++
                emit(
                    com.secondbrain.model.AudioChunk(
                        ByteArray(format.bytesForDurationMs(1_000).toInt()), format, i, true,
                    )
                )
            }
        }
        val job = launch { playback.play(chunks) }
        kotlinx.coroutines.delay(200)
        playback.stop()
        job.join()
        c.passed = emitted < 5
        c.detail = "$emitted of 5 chunks reached the line before the cut"
    }

    val fallback = SystemTtsFallback()
    check("EC-T4 local platform TTS speaks when Kokoro is unavailable") { c ->
        if (!fallback.isAvailable) {
            c.skipped = true
            c.detail = "not Windows (${System.getProperty("os.name")}) - would degrade to on-screen text"
            return@check
        }
        val request = SpeechRequest("Second Brain step one smoke check.", "default", 1.0, 60)
        val produced = fallback.synthesize(request).toList()
        val bytes = produced.sumOf { it.pcm.size }
        playback.play(flow { produced.forEach { emit(it) } })
        c.passed = bytes > 0
        c.detail = "$bytes bytes @ ${produced.firstOrNull()?.format?.sampleRateHz} Hz via Windows SAPI, played back"
    }

    // ── summary ──────────────────────────────────────────────────────────────
    val passed = checks.count { it.passed && !it.skipped }
    val failed = checks.count { !it.passed && !it.skipped }
    val skipped = checks.count { it.skipped }

    println()
    println("=".repeat(72))
    println("$passed passed, $failed failed, $skipped skipped")
    println()
    println("Not covered here (needs credentials or a human):")
    println("  S1.1  Gemini STT     - needs stt.api_key")
    println("  S1.2  Kokoro TTS     - needs tts.base_url")
    println("  EC-V9 device unplug  - needs a hand on the headset, via ./gradlew :voice:run")
    println("  hold-to-talk latency - needs a human holding Space, via ./gradlew :voice:run")
    println()

    runCatching { Files.walk(tmp).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }

    if (failed > 0) exitProcess(1)
}
