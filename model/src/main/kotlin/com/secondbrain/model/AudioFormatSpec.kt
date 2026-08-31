package com.secondbrain.model

/**
 * The wire format for captured audio. 16 kHz mono PCM16 signed little-endian.
 *
 * Verified available as a [javax.sound.sampled.TargetDataLine] format on the
 * target laptop during spike S1.3 — see DECISIONS.md D-013.
 */
data class AudioFormatSpec(
    val sampleRateHz: Int = 16_000,
    val bitsPerSample: Int = 16,
    val channels: Int = 1,
    val signed: Boolean = true,
    val bigEndian: Boolean = false,
) {
    val bytesPerFrame: Int get() = channels * (bitsPerSample / 8)
    val bytesPerSecond: Int get() = sampleRateHz * bytesPerFrame

    fun durationMsForBytes(byteCount: Long): Long =
        (byteCount * 1000L) / bytesPerSecond

    fun bytesForDurationMs(ms: Long): Long =
        (ms * bytesPerSecond) / 1000L

    companion object {
        /** Capture format. Fixed: Gemini and every downstream stage assume it. */
        val CAPTURE = AudioFormatSpec()
    }
}
