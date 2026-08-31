package com.secondbrain.ports

import com.secondbrain.model.AudioChunk
import com.secondbrain.model.SpeechRequest
import kotlinx.coroutines.flow.Flow

/** Raised when every TTS attempt failed and no fallback is available (EC-T4). */
class TtsUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Text to speech.
 *
 * Returns a [Flow] rather than a byte array because EC-T3 wants the first
 * sentence on the wire and audible before the rest of the text has even been
 * synthesised. Sentence chunking is the implementation's business; the caller
 * just collects and plays.
 *
 * [SpeechRequest.text] has already been through SpeechNormalizer, so no
 * implementation ever sees raw Markdown (EC-T1).
 */
interface TtsPort {
    val modelId: String

    /** True when this is the degraded local path, so the caller can say so (EC-T4). */
    val isFallback: Boolean get() = false

    fun synthesize(request: SpeechRequest): Flow<AudioChunk>
}
