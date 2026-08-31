package com.secondbrain.voice

import com.secondbrain.model.AudioChunk
import com.secondbrain.model.AudioFormatSpec
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * EC-T3: fill dead air with an audible cue once the wait passes
 * `thinking_cue_after_ms`, so a Kokoro cold start does not read as "it didn't
 * hear me".
 *
 * Synthesised rather than shipped as an asset: no file to bundle, no licence to
 * check, no resource-loading path to get wrong, and the tone is tunable in one
 * line. Two soft sine pips with an exponential decay -- deliberately quiet and
 * unvoiced so it cannot be mistaken for the assistant starting to speak.
 */
object ThinkingCue {

    private const val PIP_HZ = 660.0
    private const val PIP_MS = 90
    private const val GAP_MS = 110
    private const val AMPLITUDE = 0.14 // ~-17 dBFS. Present, not startling.

    /** A ready-to-play chunk at the capture sample rate. */
    fun chunk(format: AudioFormatSpec = AudioFormatSpec.CAPTURE): AudioChunk =
        AudioChunk(
            pcm = render(format),
            format = format,
            sentenceIndex = -1, // not part of any sentence
            isLastOfSentence = true,
        )

    internal fun render(format: AudioFormatSpec): ByteArray {
        val pipSamples = (format.sampleRateHz * PIP_MS) / 1000
        val gapSamples = (format.sampleRateHz * GAP_MS) / 1000
        val total = pipSamples * 2 + gapSamples
        val out = ByteArray(total * 2)

        var index = 0
        repeat(2) { pip ->
            val offset = pip * (pipSamples + gapSamples)
            for (i in 0 until pipSamples) {
                // Exponential decay envelope: no click at onset, no click at end.
                val envelope = exp(-4.0 * i / pipSamples)
                val value = sin(2.0 * PI * PIP_HZ * i / format.sampleRateHz) * envelope * AMPLITUDE
                writeSample(out, (offset + i) * 2, value)
            }
            index = offset + pipSamples
        }
        // Gap between the pips is already zero-filled.
        check(index <= total)
        return out
    }

    private fun writeSample(dest: ByteArray, byteOffset: Int, value: Double) {
        if (byteOffset + 1 >= dest.size) return
        val sample = (value * Short.MAX_VALUE).toInt().coerceIn(-32768, 32767)
        dest[byteOffset] = (sample and 0xFF).toByte()
        dest[byteOffset + 1] = ((sample shr 8) and 0xFF).toByte()
    }
}
