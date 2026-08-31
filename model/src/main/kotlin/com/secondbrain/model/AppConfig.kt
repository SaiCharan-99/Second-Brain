package com.secondbrain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Every cap, threshold and endpoint in the system.
 *
 * R7: "Caps and thresholds live in config, never in prompts." If you are about
 * to write a number into a prompt string, it belongs here instead.
 *
 * Loaded from ~/.secondbrain/config.toml by ConfigLoader, overridable per-key by
 * environment variable. Nothing in this tree is ever logged verbatim — see
 * [redacted].
 */
@Serializable
data class AppConfig(
    val paths: PathsConfig = PathsConfig(),
    val audio: AudioConfig = AudioConfig(),
    val gate: VoiceGateConfig = VoiceGateConfig(),
    val stt: SttConfig,
    val tts: TtsConfig,
    val sessions: SessionsConfig = SessionsConfig(),
    val speech: SpeechConfig = SpeechConfig(),
    val vault: VaultConfig = VaultConfig(),
) {
    /** Safe to log. Secrets replaced, everything else intact. */
    fun redacted(): AppConfig = copy(
        stt = stt.copy(apiKey = MASK),
        tts = tts.copy(apiKey = tts.apiKey?.let { MASK }),
    )

    companion object {
        const val MASK: String = "***REDACTED***"
    }
}

@Serializable
data class PathsConfig(
    /**
     * Root of all app state. `~` is expanded by ConfigLoader against
     * user.home — Windows has no `~`, and the runtime target is a Windows
     * laptop, so this cannot be left to the shell.
     */
    val root: String = "~/.secondbrain",
)

@Serializable
data class AudioConfig(
    @SerialName("sample_rate_hz") val sampleRateHz: Int = 16_000,
    /**
     * Substring matched against javax.sound mixer names, case-insensitive.
     * Null or no match falls back to the platform default line (EC-V9).
     */
    @SerialName("preferred_capture_device") val preferredCaptureDevice: String? = null,
    @SerialName("preferred_playback_device") val preferredPlaybackDevice: String? = null,
    /** Capture buffer per read. Smaller = lower latency, more syscalls. */
    @SerialName("capture_chunk_ms") val captureChunkMs: Int = 50,
    /**
     * Playback must be cuttable within this budget for barge-in (EC-V3). The
     * playback line writes in chunks no larger than this so stop() never has to
     * wait on a long blocking write.
     */
    @SerialName("playback_chunk_ms") val playbackChunkMs: Int = 40,
)

@Serializable
data class VoiceGateConfig(
    /** PUSH_TO_TALK is the default and the only validated mode (E13, EC-V2). */
    val mode: GateMode = GateMode.PUSH_TO_TALK,

    // ── EC-V1: discard silence and accidental triggers with zero API cost ──
    @SerialName("min_utterance_ms") val minUtteranceMs: Long = 400,
    /**
     * Hard ceiling on one utterance (EC-V6). On hitting it, capture stops and
     * what we have is transcribed — losing a thought is worse than truncating
     * one (E6). The real limit is pinned by spike S1.1.
     */
    @SerialName("max_utterance_ms") val maxUtteranceMs: Long = 60_000,

    // ── EC-V1: the "calibrated floor" ──
    /**
     * Utterance peak RMS must exceed (measured noise floor + this margin), in dB.
     * The floor itself is measured at startup and cached in calibration.json —
     * it is never written back into config.toml (E4).
     */
    @SerialName("energy_margin_db") val energyMarginDb: Double = 12.0,
    @SerialName("calibration_ms") val calibrationMs: Long = 500,

    // ── EnergyVad mode only. Built, unvalidated (E13). ──
    @SerialName("vad_silence_timeout_ms") val vadSilenceTimeoutMs: Long = 1_200,

    // ── EC-V3 barge-in ──
    /**
     * PUSH_TO_TALK: a keypress cuts playback. Deterministic, and immune to the
     * mic hearing our own TTS, which is unavoidable on a single headset with no
     * acoustic echo cancellation anywhere in the JVM (E3).
     */
    @SerialName("barge_in") val bargeIn: BargeInMode = BargeInMode.KEYPRESS,
    /** Energy barge-in ignores the first N ms after playback starts. */
    @SerialName("barge_in_grace_ms") val bargeInGraceMs: Long = 300,
    /**
     * Energy barge-in additionally requires capture and playback to be
     * physically different devices, unless this is set true. Do not set it true
     * without reading E3.
     */
    @SerialName("allow_energy_barge_in_same_device")
    val allowEnergyBargeInSameDevice: Boolean = false,
)

enum class GateMode { PUSH_TO_TALK, ENERGY_VAD }

enum class BargeInMode {
    /** Space cuts playback. Default. Zero false positives. */
    KEYPRESS,

    /** Detected speech energy cuts playback. Requires the guards above. */
    ENERGY,

    /** Playback always runs to completion. */
    DISABLED,
}

@Serializable
data class SttConfig(
    /** EC-G3: model IDs live in config. A 404 must name this key. */
    val model: String,
    @SerialName("api_key") val apiKey: String,
    @SerialName("base_url") val baseUrl: String = "https://generativelanguage.googleapis.com",
    /**
     * Above this, switch from inline base64 to the Files API. The real cutover
     * is pinned by spike S1.1 (EC-V6).
     */
    @SerialName("inline_limit_bytes") val inlineLimitBytes: Long = 18_000_000,
    /**
     * STT is read-only and idempotent, so retrying is correct and is NOT an R5
     * violation — R5 governs irreversible actions (E8).
     */
    @SerialName("max_attempts") val maxAttempts: Int = 3,
    @SerialName("initial_backoff_ms") val initialBackoffMs: Long = 500,
    @SerialName("request_timeout_ms") val requestTimeoutMs: Long = 60_000,
)

@Serializable
data class TtsConfig(
    val model: String,
    val voice: String,
    @SerialName("base_url") val baseUrl: String,
    @SerialName("api_key") val apiKey: String? = null,
    /** Container Kokoro should return. Pinned by spike S1.2. */
    @SerialName("response_format") val responseFormat: String = "wav",
    val speed: Double = 1.0,
    @SerialName("max_attempts") val maxAttempts: Int = 3,
    @SerialName("initial_backoff_ms") val initialBackoffMs: Long = 500,
    @SerialName("request_timeout_ms") val requestTimeoutMs: Long = 60_000,
    /** EC-T3: fill dead air with an audible cue after this long. */
    @SerialName("thinking_cue_after_ms") val thinkingCueAfterMs: Long = 1_500,
    /** EC-T4: fall back to platform TTS rather than failing silently. */
    @SerialName("fallback_enabled") val fallbackEnabled: Boolean = true,
)

@Serializable
data class SessionsConfig(
    /**
     * E1. ARCHITECTURE.md §2 says sessions rotate at 30 days; Step 1's build
     * list reads as "delete the WAV once the transcript commits". Those are two
     * different policies, so both are expressible and the default keeps the
     * audio — it is the only dataset for measuring STT accuracy on real speech.
     */
    @SerialName("retention_days") val retentionDays: Int = 30,
    /**
     * When true, the WAV is deleted the moment its transcript commits. R10
     * forbids deleting it any earlier under any setting.
     */
    @SerialName("delete_wav_on_commit") val deleteWavOnCommit: Boolean = false,
)

@Serializable
data class SpeechConfig(
    /** EC-T2: hard cap on spoken output. Truncate at a sentence boundary. */
    @SerialName("max_speech_seconds") val maxSpeechSeconds: Int = 60,
)
