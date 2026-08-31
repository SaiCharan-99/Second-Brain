package com.secondbrain.model

/**
 * One request to synthesise speech.
 *
 * [text] has already been through SpeechNormalizer — the TTS port never sees
 * raw Markdown (EC-T1). Splitting into sentences is the TTS implementation's
 * job, because EC-T3 wants the first sentence on the wire before the rest of
 * the text is even known.
 */
data class SpeechRequest(
    val text: String,
    val voice: String,
    val speed: Double = 1.0,
    /**
     * Hard ceiling on synthesised speech, EC-T2. Enforced by truncating at a
     * sentence boundary and offering the rest on screen. Lives in config, never
     * in a prompt (R7).
     */
    val maxSpeechSeconds: Int,
)

/**
 * A chunk of decoded PCM ready for the playback line, plus the format it is in.
 *
 * Kokoro's output container and sample rate are unknown until spike S1.2, so
 * the format travels with the chunk rather than being assumed.
 */
data class AudioChunk(
    val pcm: ByteArray,
    val format: AudioFormatSpec,
    /** Zero-based index of the sentence this chunk belongs to. */
    val sentenceIndex: Int,
    val isLastOfSentence: Boolean,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioChunk) return false
        return sentenceIndex == other.sentenceIndex &&
            isLastOfSentence == other.isLastOfSentence &&
            format == other.format &&
            pcm.contentEquals(other.pcm)
    }

    override fun hashCode(): Int {
        var result = pcm.contentHashCode()
        result = 31 * result + format.hashCode()
        result = 31 * result + sentenceIndex
        result = 31 * result + isLastOfSentence.hashCode()
        return result
    }
}
