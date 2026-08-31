package com.secondbrain.model

/**
 * The Step 1 latency contract.
 *
 * ARCHITECTURE.md asks for "per-stage latency logged" without saying what a
 * stage is, and §9 sets a ~4 s budget for the round trip without saying which
 * two points it spans. This type pins both down so the number in DECISIONS.md
 * means something six weeks from now.
 *
 * All values are epoch millis. `null` means the stage was not reached.
 *
 *   captureStartedAt ──▶ captureStoppedAt      how long the user spoke
 *   captureStoppedAt ──▶ sttRequestSentAt      our own overhead: WAV framing, disk, encode
 *   sttRequestSentAt ──▶ sttResponseAt         Gemini
 *   sttResponseAt    ──▶ ttsRequestSentAt      normalisation
 *   ttsRequestSentAt ──▶ firstAudioOutAt       Kokoro cold/warm start  (EC-T3)
 *   captureStoppedAt ──▶ firstAudioOutAt       THE number. Budget ~4 s.
 */
data class StageTimings(
    val utteranceId: String,
    val captureStartedAt: Long,
    val captureStoppedAt: Long? = null,
    val sttRequestSentAt: Long? = null,
    val sttResponseAt: Long? = null,
    val ttsRequestSentAt: Long? = null,
    /** First byte actually written to the playback line, not first byte received. */
    val firstAudioOutAt: Long? = null,
    val playbackDoneAt: Long? = null,
    val wavBytes: Long? = null,
    val sttAttempts: Int? = null,
) {
    val spokenMs: Long? get() = delta(captureStartedAt, captureStoppedAt)
    val localOverheadMs: Long? get() = delta(captureStoppedAt, sttRequestSentAt)
    val sttMs: Long? get() = delta(sttRequestSentAt, sttResponseAt)
    val normalizeMs: Long? get() = delta(sttResponseAt, ttsRequestSentAt)
    val ttsFirstByteMs: Long? get() = delta(ttsRequestSentAt, firstAudioOutAt)

    /** Capture-stop to first audible byte. The one the 4 s budget applies to. */
    val roundTripMs: Long? get() = delta(captureStoppedAt, firstAudioOutAt)

    private fun delta(from: Long?, to: Long?): Long? =
        if (from == null || to == null) null else to - from

    /** One line, grep-able, stable column order. */
    fun toLogLine(): String = buildString {
        append("LATENCY utterance=").append(utteranceId)
        append(" spoken=").append(fmt(spokenMs))
        append(" local=").append(fmt(localOverheadMs))
        append(" stt=").append(fmt(sttMs))
        append(" norm=").append(fmt(normalizeMs))
        append(" tts_first=").append(fmt(ttsFirstByteMs))
        append(" ROUNDTRIP=").append(fmt(roundTripMs))
        append(" wav_bytes=").append(wavBytes ?: "-")
        append(" stt_attempts=").append(sttAttempts ?: "-")
    }

    private fun fmt(v: Long?): String = v?.let { "${it}ms" } ?: "-"
}
