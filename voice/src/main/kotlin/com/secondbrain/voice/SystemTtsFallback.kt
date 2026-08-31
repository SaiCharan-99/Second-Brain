package com.secondbrain.voice

import com.secondbrain.model.AudioChunk
import com.secondbrain.model.SpeechRequest
import com.secondbrain.ports.TtsPort
import com.secondbrain.ports.TtsUnavailableException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * EC-T4: when Kokoro is unreachable, speak anyway rather than failing silently.
 *
 * ARCHITECTURE.md suggests "a local JVM TTS (FreeTTS/system)". FreeTTS is
 * abandoned (last release 2009), ships an awkward JSAPI licensing story, and
 * sounds worse than what Windows already has built in. Rejected.
 *
 * What this does instead: drives the SAPI voice already present on every Windows
 * install via PowerShell's System.Speech.Synthesis.SpeechSynthesizer, rendering
 * to a temporary WAV that then plays through the same [JvmAudioPlayback] path as
 * Kokoro. Zero dependencies, no new licence, and the audio route is identical so
 * barge-in keeps working in the degraded state.
 *
 * On a non-Windows host this reports unavailable, and the caller degrades to
 * on-screen text -- which EC-T4 permits, as long as it is never silent.
 *
 * See DECISIONS.md D-016.
 */
class SystemTtsFallback : TtsPort {

    private val log = LoggerFactory.getLogger(SystemTtsFallback::class.java)

    override val modelId: String get() = "windows-sapi"
    override val isFallback: Boolean get() = true

    private val isWindows: Boolean =
        System.getProperty("os.name").orEmpty().lowercase().contains("windows")

    val isAvailable: Boolean get() = isWindows

    override fun synthesize(request: SpeechRequest): Flow<AudioChunk> = flow {
        if (!isWindows) {
            throw TtsUnavailableException(
                "No local TTS on this platform (${System.getProperty("os.name")}). " +
                    "Falling back to on-screen text (EC-T4)."
            )
        }

        val tmp = Files.createTempFile("secondbrain-tts-", ".wav")
        try {
            // SAPI rate is -10..10 and roughly logarithmic; 0 is normal pace.
            val rate = ((request.speed - 1.0) * 5).toInt().coerceIn(-10, 10)
            val script = buildString {
                append("Add-Type -AssemblyName System.Speech; ")
                append("\$s = New-Object System.Speech.Synthesis.SpeechSynthesizer; ")
                append("\$s.Rate = ").append(rate).append("; ")
                append("\$s.SetOutputToWaveFile('").append(tmp.toString().replace("'", "''")).append("'); ")
                append("\$s.Speak([Console]::In.ReadToEnd()); ")
                append("\$s.Dispose()")
            }

            val process = ProcessBuilder(
                "powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script,
            ).redirectErrorStream(true).start()

            // Text goes over stdin, not as an argument: a note title containing a
            // quote would otherwise break the command, and worse, be interpreted.
            process.outputStream.bufferedWriter().use { it.write(request.text) }

            val finished = process.waitFor(30, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                throw TtsUnavailableException("Local SAPI synthesis timed out after 30s.")
            }
            if (process.exitValue() != 0) {
                val err = process.inputStream.readBytes().decodeToString().take(300)
                throw TtsUnavailableException("Local SAPI synthesis failed (exit ${process.exitValue()}): $err")
            }

            val bytes = Files.readAllBytes(tmp)
            if (bytes.isEmpty()) throw TtsUnavailableException("Local SAPI produced no audio.")

            val parsed = WavCodec.parse(bytes)
            log.info(
                "Spoke via local Windows SAPI fallback ({} Hz, {} bytes). Kokoro is unavailable (EC-T4).",
                parsed.format.sampleRateHz, parsed.pcm.size,
            )
            emit(AudioChunk(parsed.pcm, parsed.format, sentenceIndex = 0, isLastOfSentence = true))
        } finally {
            runCatching { Files.deleteIfExists(tmp) }
        }
    }.flowOn(Dispatchers.IO)
}
