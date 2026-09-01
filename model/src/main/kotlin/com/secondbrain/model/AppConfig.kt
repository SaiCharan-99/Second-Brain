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
    val agent: AgentConfig = AgentConfig(),
    val google: GoogleConfig = GoogleConfig(),
    val commerce: CommerceConfig = CommerceConfig(),
) {
    /** Safe to log. Secrets replaced, everything else intact. */
    fun redacted(): AppConfig = copy(
        stt = stt.copy(apiKey = MASK, fallbackApiKey = stt.fallbackApiKey?.let { MASK }),
        tts = tts.copy(apiKey = tts.apiKey?.let { MASK }, fallbackApiKey = tts.fallbackApiKey?.let { MASK }),
        agent = agent.redacted(),
        google = google.redacted(),
        commerce = commerce.redacted(),
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
    /**
     * A second Gemini key, tried only once every attempt against [apiKey] has
     * failed (any reason — bad credentials, quota, transport). Exists because
     * a free-tier key's daily request quota is small enough that a normal
     * testing session exhausts it (D-086), and two independent free-tier
     * projects roughly double the effective daily budget for nothing. Null
     * means "no fallback" and the existing single-key behaviour is unchanged.
     */
    @SerialName("fallback_api_key") val fallbackApiKey: String? = null,
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
    /**
     * D-065: Gemini by default, superseding §7 Step 1's Kokoro assumption —
     * measured against the live API before being wired in. `voice`/`base_url`
     * default to Gemini's own values for the same reason `stt.base_url`
     * already does; `KokoroTts` still exists in `:voice` and reads this same
     * config shape if anyone points it at a self-hosted endpoint instead.
     */
    val model: String = "gemini-3.1-flash-tts-preview",
    /** A Gemini prebuilt voice name (e.g. "Kore"), or a Kokoro voice id if using KokoroTts. */
    val voice: String = "Kore",
    @SerialName("base_url") val baseUrl: String = "https://generativelanguage.googleapis.com",
    @SerialName("api_key") val apiKey: String? = null,
    /** Same fallback mechanism as [SttConfig.fallbackApiKey] — see its doc. Ignored by KokoroTts. */
    @SerialName("fallback_api_key") val fallbackApiKey: String? = null,
    /** KokoroTts only: the container it should return. Ignored by GeminiTts, which always gets raw PCM. */
    @SerialName("response_format") val responseFormat: String = "wav",
    /** KokoroTts only: Gemini TTS has no speed parameter, only prompt phrasing. */
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

/**
 * Google OAuth, for `email_draft` and the calendar tools (Steps 5-6).
 *
 * Optional, deliberately unlike `agent.api_key` and `stt`/`tts.api_key`: those
 * are core-path (nothing works without Claude/Gemini), Google is opt-in. Blank
 * [clientId]/[clientSecret] means `Main.kt` logs a warning and simply does not
 * register the email/calendar tools rather than failing at startup — voice
 * capture (Steps 3-4) keeps working with no Google account at all.
 */
@Serializable
data class GoogleConfig(
    @SerialName("client_id") val clientId: String = "",
    @SerialName("client_secret") val clientSecret: String = "",
    /**
     * Loopback OAuth redirect port. 0 lets `LocalServerReceiver` pick a free
     * port itself, which the "loopback IP address" flow Google's own current
     * docs describe supports for a Desktop-app-type client — no fixed
     * registered redirect URI needed. Set a fixed port only if your OAuth
     * client was created before that flow existed and needs one registered.
     */
    @SerialName("redirect_port") val redirectPort: Int = 0,
    /**
     * Where the OAuth token pair lives. Its own tiny SQLite file, not app.db —
     * see the Step 5/6 plan: `:integrations` cannot reach `:agent`'s AgentDb or
     * `:vault`'s AppDb (no such dependency edge in §1), and this is a clean,
     * non-precious sub-domain of its own (losing it just means one more consent
     * screen). Supersedes ARCHITECTURE §2's placement of oauth_tokens in app.db.
     */
    @SerialName("token_store_path") val tokenStorePath: String = "oauth_tokens.db",
) {
    fun redacted(): GoogleConfig = copy(
        clientId = if (clientId.isBlank()) clientId else AppConfig.MASK,
        clientSecret = if (clientSecret.isBlank()) clientSecret else AppConfig.MASK,
    )
}

/**
 * Step 7 — Zepto MCP and the grocery workflow (WF-4).
 *
 * Two defaults here are deliberately the timid ones, and both are R2/R3
 * reasoning rather than caution for its own sake:
 *
 * [enabled] is false. Zepto's own documentation says it plainly — *"any order
 * placed through the Zepto MCP will be processed as a real Zepto order"*. There
 * is no sandbox to develop against, so commerce is off until someone turns it
 * on knowing that.
 *
 * [useFake] is true. Even once enabled, the default adapter is the deterministic
 * fake, so the whole flow is demoable and testable with zero money at risk.
 * §7 Step 7 requires the fake be **visibly labelled in the UI** whenever it is
 * active, which `OrderProposal.isFake` carries and `ProposalWindow` renders.
 */
@Serializable
data class CommerceConfig(
    /** Off until explicitly enabled. Real orders, real money, no sandbox. */
    val enabled: Boolean = false,

    /** True = `FakeCommerceAdapter` (safe, offline, labelled). False = the real Zepto MCP. */
    @SerialName("use_fake") val useFake: Boolean = true,

    @SerialName("mcp_url") val mcpUrl: String = "https://mcp.zepto.co.in/mcp",

    /**
     * Filled in by Dynamic Client Registration on first run and written back
     * here, so the app registers once rather than on every launch. Blank
     * triggers registration.
     */
    @SerialName("oauth_client_id") val oauthClientId: String = "",

    /**
     * Loopback port for the OAuth redirect.
     *
     * Measured during spike S7.1 and worth stating because it is the opposite
     * of what `GoogleConfig` does: the redirect host **must be `localhost`, not
     * `127.0.0.1`**. Zepto's authorization server sits behind an AWS load
     * balancer whose WAF returns a bare HTML 403 for a registration payload
     * containing the literal IP — `http://localhost:8765/callback` registers
     * fine, `http://127.0.0.1:8765/callback` does not (D-079). Fixed rather
     * than 0-and-pick-one because the port is baked into the registered
     * redirect URI.
     */
    @SerialName("redirect_port") val redirectPort: Int = 8765,

    /** Own file, same reasoning as `GoogleConfig.tokenStorePath`. */
    @SerialName("token_store_path") val tokenStorePath: String = "zepto_tokens.db",

    /**
     * EC-Z17, and the single most valuable control in this step. Above this
     * total the order window demands a second, explicit acknowledgement.
     *
     * It is not a hard block: a genuinely large order is legitimate and the
     * user can say so. What it stops is the *silent* large order — "twenty
     * kilos of rice" misheard from "two kilos" builds a perfectly valid cart
     * that nothing else in the pipeline has any reason to question. R7: this
     * lives here and is enforced in code, never asked for in a prompt.
     */
    @SerialName("order_ceiling_inr") val orderCeilingInr: Long = 2_000,

    /** EC-Z3: how many candidates a search returns for the model to rank. */
    @SerialName("max_search_results") val maxSearchResults: Int = 8,

    /** Stage 4 (D-098): how many `commerce_prepare_list` searches run at once, never unbounded. */
    @SerialName("max_comparison_concurrency") val maxComparisonConcurrency: Int = 4,

    /**
     * EC-Z13: an extracted list item at or below this confidence is read back
     * for confirmation before it is ever searched for.
     */
    @SerialName("low_confidence_threshold") val lowConfidenceThreshold: Double = 0.75,

    @SerialName("request_timeout_ms") val requestTimeoutMs: Long = 30_000,
    @SerialName("max_attempts") val maxAttempts: Int = 3,
) {
    fun redacted(): CommerceConfig =
        copy(oauthClientId = if (oauthClientId.isBlank()) oauthClientId else AppConfig.MASK)

    val orderCeiling: Money get() = Money.ofRupees(orderCeilingInr)
}
