package com.secondbrain.voice

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * RMS energy over PCM16 little-endian frames, in dBFS.
 *
 * This is the measurement EC-V1's "calibrated floor" is compared against, and
 * the one energy barge-in uses. dBFS rather than a raw amplitude because the
 * useful thresholds are logarithmic: a 12 dB margin over the room means the
 * same thing in a quiet room and a noisy one, whereas a linear threshold does
 * not.
 *
 * Silence is reported as [SILENCE_DBFS] rather than negative infinity so the
 * value stays arithmetic-safe.
 */
object Rms {

    const val SILENCE_DBFS: Double = -120.0
    private const val FULL_SCALE = 32768.0

    fun dbfs(pcm: ByteArray, offset: Int = 0, length: Int = pcm.size - offset): Double {
        if (length < 2) return SILENCE_DBFS
        var sumSquares = 0.0
        var samples = 0
        var i = offset
        val end = offset + (length and 1.inv())
        while (i + 1 < end) {
            // little-endian signed 16-bit
            val sample = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toInt()
            val norm = sample / FULL_SCALE
            sumSquares += norm * norm
            samples++
            i += 2
        }
        if (samples == 0) return SILENCE_DBFS
        val rms = sqrt(sumSquares / samples)
        if (rms <= 0.0) return SILENCE_DBFS
        return (20.0 * log10(rms)).coerceAtLeast(SILENCE_DBFS)
    }

    /** Peak of per-chunk RMS. What [com.secondbrain.model.Utterance.peakRmsDbfs] holds. */
    fun peakDbfs(chunks: List<ByteArray>): Double =
        chunks.maxOfOrNull { dbfs(it) } ?: SILENCE_DBFS
}
