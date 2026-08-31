package com.secondbrain.voice

import com.secondbrain.model.AudioFormatSpec
import com.secondbrain.ports.AudioDevice
import org.slf4j.LoggerFactory
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.Mixer
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine

/**
 * Device enumeration and selection.
 *
 * EC-V9: an unplugged headset must not crash the session. The fix is not a
 * try/catch at the call site, it is never caching a Mixer across an utterance --
 * javax.sound's device list is re-read from the OS on every
 * AudioSystem.getMixerInfo() call, so re-enumerating is how you recover.
 *
 * Spike S1.3, run on the target laptop before this file existed, confirmed:
 *   TargetDataLine 16k/16/mono/signed/LE  -> supported
 *   SourceDataLine 16k and 24k mono       -> supported
 *   3 capture devices, 3 playback devices, headset hot-pluggable
 * See DECISIONS.md D-013.
 */
object AudioDevices {

    private val log = LoggerFactory.getLogger(AudioDevices::class.java)

    fun toJvmFormat(spec: AudioFormatSpec): AudioFormat = AudioFormat(
        spec.sampleRateHz.toFloat(),
        spec.bitsPerSample,
        spec.channels,
        spec.signed,
        spec.bigEndian,
    )

    fun captureDevices(spec: AudioFormatSpec = AudioFormatSpec.CAPTURE): List<AudioDevice> =
        enumerate(TargetDataLine::class.java, spec)

    fun playbackDevices(spec: AudioFormatSpec = AudioFormatSpec.CAPTURE): List<AudioDevice> =
        enumerate(SourceDataLine::class.java, spec)

    private fun enumerate(lineClass: Class<*>, spec: AudioFormatSpec): List<AudioDevice> {
        val info = DataLine.Info(lineClass, toJvmFormat(spec))
        val out = mutableListOf<AudioDevice>()
        AudioSystem.getMixerInfo().forEach { mixerInfo ->
            val mixer = runCatching { AudioSystem.getMixer(mixerInfo) }.getOrNull() ?: return@forEach
            if (!runCatching { mixer.isLineSupported(info) }.getOrDefault(false)) return@forEach
            out += AudioDevice(
                id = mixerInfo.name,
                name = mixerInfo.name,
                isDefault = mixerInfo.name.startsWith("Primary Sound"),
            )
        }
        return out
    }

    /**
     * Opens a capture line, preferring a device whose name contains [preferred].
     *
     * Falls back to the platform default line when the preference does not match
     * anything, which is the normal case after a headset is unplugged mid-session.
     */
    fun openCapture(
        spec: AudioFormatSpec,
        preferred: String?,
        bufferBytes: Int,
    ): Pair<TargetDataLine, String> {
        val format = toJvmFormat(spec)
        val info = DataLine.Info(TargetDataLine::class.java, format)

        matchingMixer(info, preferred)?.let { (mixer, name) ->
            runCatching {
                val line = mixer.getLine(info) as TargetDataLine
                line.open(format, bufferBytes)
                return line to name
            }.onFailure { log.warn("capture device '{}' matched but would not open: {}", name, it.message) }
        }

        val line = AudioSystem.getLine(info) as TargetDataLine
        line.open(format, bufferBytes)
        return line to "system default"
    }

    fun openPlayback(
        spec: AudioFormatSpec,
        preferred: String?,
        bufferBytes: Int,
    ): Pair<SourceDataLine, String> {
        val format = toJvmFormat(spec)
        val info = DataLine.Info(SourceDataLine::class.java, format)

        matchingMixer(info, preferred)?.let { (mixer, name) ->
            runCatching {
                val line = mixer.getLine(info) as SourceDataLine
                line.open(format, bufferBytes)
                return line to name
            }.onFailure { log.warn("playback device '{}' matched but would not open: {}", name, it.message) }
        }

        val line = AudioSystem.getLine(info) as SourceDataLine
        line.open(format, bufferBytes)
        return line to "system default"
    }

    private fun matchingMixer(info: DataLine.Info, preferred: String?): Pair<Mixer, String>? {
        if (preferred.isNullOrBlank()) return null
        val needle = preferred.trim().lowercase()
        AudioSystem.getMixerInfo().forEach { mixerInfo ->
            if (!mixerInfo.name.lowercase().contains(needle)) return@forEach
            val mixer = runCatching { AudioSystem.getMixer(mixerInfo) }.getOrNull() ?: return@forEach
            if (runCatching { mixer.isLineSupported(info) }.getOrDefault(false)) {
                return mixer to mixerInfo.name
            }
        }
        log.warn(
            "No audio device matched '{}'. Falling back to the system default. Available: {}",
            preferred,
            AudioSystem.getMixerInfo().joinToString { it.name },
        )
        return null
    }

    /**
     * True when capture and playback resolve to different physical devices.
     *
     * E3: energy-based barge-in on a single headset makes the microphone hear our
     * own TTS, and with no acoustic echo cancellation anywhere in the JVM the
     * assistant interrupts itself in a loop. Energy barge-in is therefore gated
     * on this returning true.
     */
    fun captureAndPlaybackDiffer(captureName: String, playbackName: String): Boolean {
        fun normalize(s: String) = s.lowercase()
            .replace(Regex("""\((r|tm)\)"""), "")
            .replace(Regex("""[^a-z0-9]+"""), " ")
            .trim()
        val a = normalize(captureName)
        val b = normalize(playbackName)
        if (a.isEmpty() || b.isEmpty()) return false
        return !(a == b || a.contains(b) || b.contains(a))
    }

    /** Logged once at startup so a device problem is diagnosable from the log alone. */
    fun logInventory() {
        val cap = captureDevices()
        val play = playbackDevices()
        log.info("Audio capture devices ({}): {}", cap.size, cap.joinToString { it.name })
        log.info("Audio playback devices ({}): {}", play.size, play.joinToString { it.name })
        if (cap.isEmpty()) log.error("No capture device supports 16 kHz mono PCM16. Voice input is unavailable.")
    }
}
