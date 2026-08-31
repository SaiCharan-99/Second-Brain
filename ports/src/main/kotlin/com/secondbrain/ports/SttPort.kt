package com.secondbrain.ports

import com.secondbrain.model.AudioFormatSpec
import com.secondbrain.model.Transcript
import java.nio.file.Path

/**
 * Speech to text.
 *
 * Contract:
 *  - The WAV at [wavPath] is already persisted. The implementation must not
 *    delete it under any circumstance (EC-V7, R10).
 *  - Never throws for a transport failure. Retries internally per config and
 *    returns [Transcript] with status FAILED once attempts are exhausted, so
 *    the caller always has something to commit (E2).
 *  - Never "cleans" a transcript. Mixed-script Telugu / Hindi / English is
 *    passed through exactly as returned (EC-V5).
 */
interface SttPort {
    val modelId: String

    suspend fun transcribe(
        utteranceId: String,
        wavPath: Path,
        format: AudioFormatSpec,
    ): Transcript
}
