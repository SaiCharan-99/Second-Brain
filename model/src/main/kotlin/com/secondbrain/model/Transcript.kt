package com.secondbrain.model

/**
 * Outcome of speech-to-text for one [Utterance].
 *
 * [status] exists because of E2: a failed or empty transcript never "commits",
 * and R10 forbids deleting a WAV before its transcript commits. Without a
 * terminal status, every STT failure would strand its audio forever and the
 * failure itself would be uncountable. Writing a status IS the commit.
 */
data class Transcript(
    val utteranceId: String,
    /**
     * Verbatim text. EC-V5: mixed-script Telugu / Hindi / English is passed
     * through untouched. Never "cleaned", never translated.
     */
    val text: String,
    val status: SttStatus,
    val model: String,
    val latencyMs: Long,
    /** Attempts consumed, including the successful one. STT retry is safe (E8). */
    val attempts: Int = 1,
    val error: String? = null,
) {
    val isUsable: Boolean get() = status == SttStatus.OK && text.isNotBlank()

    companion object {
        fun empty(utteranceId: String, model: String, latencyMs: Long, attempts: Int) =
            Transcript(utteranceId, "", SttStatus.EMPTY, model, latencyMs, attempts)

        fun failed(utteranceId: String, model: String, latencyMs: Long, attempts: Int, error: String) =
            Transcript(utteranceId, "", SttStatus.FAILED, model, latencyMs, attempts, error)
    }
}

enum class SttStatus {
    /** Transcript returned with content. */
    OK,

    /** API succeeded but returned nothing intelligible. Speak "I didn't catch that". */
    EMPTY,

    /** All retries exhausted. The WAV is retained regardless (EC-V7). */
    FAILED,
}
