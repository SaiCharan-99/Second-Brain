package com.secondbrain.voice

import com.secondbrain.model.AudioFormatSpec
import com.secondbrain.model.VoiceGateConfig

/**
 * The EC-V1 pre-flight decision: is this recording worth an API call?
 *
 * Deterministic and offline. Two independent conditions, both required:
 *   duration >= min_utterance_ms   AND   peak RMS > (noise floor + margin)
 *
 * Getting this wrong in the permissive direction costs money on every
 * accidental keypress. Getting it wrong in the strict direction loses thoughts,
 * which is worse -- hence duration and energy are ANDed rather than scored
 * together, and the reason for rejection is always reported so it shows up in
 * the log instead of looking like the app ignored you.
 */
object VoiceGate {

    sealed interface Verdict {
        /** Send it. */
        data object Accept : Verdict

        /** Discard with zero API cost. [reason] is spoken/logged, never silent. */
        data class Discard(val reason: DiscardReason, val detail: String) : Verdict
    }

    enum class DiscardReason {
        /** Shorter than min_utterance_ms. Almost always an accidental keypress. */
        TOO_SHORT,

        /** Long enough, but no louder than the room. EC-V1. */
        TOO_QUIET,

        /** No audio at all. Device problem, not a user problem. */
        NO_AUDIO,
    }

    /**
     * @param thresholdDbfs floor + margin, from [NoiseFloorCalibrator].
     */
    fun evaluate(
        pcmByteCount: Long,
        peakRmsDbfs: Double,
        format: AudioFormatSpec,
        config: VoiceGateConfig,
        thresholdDbfs: Double,
    ): Verdict {
        if (pcmByteCount <= 0) {
            return Verdict.Discard(DiscardReason.NO_AUDIO, "no PCM captured")
        }

        val durationMs = format.durationMsForBytes(pcmByteCount)
        if (durationMs < config.minUtteranceMs) {
            return Verdict.Discard(
                DiscardReason.TOO_SHORT,
                "${durationMs}ms < ${config.minUtteranceMs}ms minimum",
            )
        }

        if (peakRmsDbfs <= thresholdDbfs) {
            return Verdict.Discard(
                DiscardReason.TOO_QUIET,
                "peak %.1f dBFS <= threshold %.1f dBFS".format(peakRmsDbfs, thresholdDbfs),
            )
        }

        return Verdict.Accept
    }

    /** True once capture has hit the hard ceiling and must be cut (EC-V6 / E6). */
    fun hasHitDurationCap(
        pcmByteCount: Long,
        format: AudioFormatSpec,
        config: VoiceGateConfig,
    ): Boolean = format.durationMsForBytes(pcmByteCount) >= config.maxUtteranceMs

    /**
     * Trailing-silence detector for ENERGY_VAD mode.
     *
     * Built because Step 1's spec asks for both gate modes, but PUSH_TO_TALK is
     * the default and the only validated one -- EC-V2 exists precisely because
     * energy endpointing cuts people off mid-sentence, and the 1200 ms timeout
     * is a guess until measured against real speech (E13).
     */
    fun isTrailingSilence(
        recentChunkLevels: List<Double>,
        format: AudioFormatSpec,
        chunkMs: Long,
        config: VoiceGateConfig,
        thresholdDbfs: Double,
    ): Boolean {
        if (chunkMs <= 0) return false
        val chunksForTimeout = (config.vadSilenceTimeoutMs / chunkMs).toInt()
        if (chunksForTimeout <= 0 || recentChunkLevels.size < chunksForTimeout) return false
        return recentChunkLevels.takeLast(chunksForTimeout).all { it <= thresholdDbfs }
    }
}
