package com.secondbrain.voice

import com.secondbrain.model.AudioChunk
import com.secondbrain.model.AudioFormatSpec
import com.secondbrain.model.SpeechRequest
import com.secondbrain.model.TtsConfig
import com.secondbrain.ports.TtsPort
import com.secondbrain.ports.TtsUnavailableException
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import java.util.Base64
import kotlin.random.Random

/**
 * Text to speech via Gemini's native audio generation (`gemini-3.1-flash-tts-preview`
 * and siblings), superseding the Kokoro assumption in ARCHITECTURE.md §7 Step 1.
 *
 * D-065: everything below is measured against the live API, not assumed —
 * request shape, response shape and the audio format were each verified with
 * a real key before this file was written, the same discipline
 * [KokoroTts]'s own doc comment describes spike S1.2 as owing it.
 *
 *  - the endpoint is `generateContent`, the same family [GeminiStt] already calls
 *  - `generationConfig.responseModalities = ["AUDIO"]` switches the model into
 *    speech mode; `speechConfig.voiceConfig.prebuiltVoiceConfig.voiceName`
 *    picks the voice
 *  - the response carries raw, headerless PCM in
 *    `candidates[0].content.parts[0].inlineData.data`, base64-encoded. The
 *    sample rate and channel count are never assumed to be 24 kHz mono — both
 *    are parsed out of `inlineData.mimeType` (observed as
 *    `"audio/l16; rate=24000; channels=1"`) on every response, because a
 *    future model or voice is free to differ and a silently wrong rate is an
 *    audible bug, not a crash.
 *
 * EC-T3: sentences are synthesised and emitted one at a time, same as
 * [KokoroTts], so the first sentence is audible before the rest exist.
 */
class GeminiTts(
    private val http: HttpClient,
    private val config: TtsConfig,
) : TtsPort {

    private val log = LoggerFactory.getLogger(GeminiTts::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    override val modelId: String get() = config.model

    override fun synthesize(request: SpeechRequest): Flow<AudioChunk> = flow {
        val sentences = SpeechNormalizer.sentences(request.text)
            .ifEmpty { listOf(request.text) }
            .filter { it.isNotBlank() }

        sentences.forEachIndexed { index, sentence ->
            val (pcm, format) = synthesizeOne(sentence)
            emit(AudioChunk(pcm = pcm, format = format, sentenceIndex = index, isLastOfSentence = true))
        }
    }

    private suspend fun synthesizeOne(sentence: String): Pair<ByteArray, AudioFormatSpec> {
        val primaryKey = config.apiKey.orEmpty()
        return try {
            attemptWithKey(primaryKey, sentence)
        } catch (primaryFailure: TtsUnavailableException) {
            // D-086: same fallback mechanism as GeminiStt — see SttConfig's doc.
            // Tried on ANY failure the primary key produced, retryable or not,
            // since a bad/revoked primary key is exactly as recoverable this
            // way as an exhausted quota is.
            val fallbackKey = config.fallbackApiKey
            if (fallbackKey.isNullOrBlank()) throw primaryFailure
            log.warn("Primary Gemini key failed for TTS ({}); retrying with the fallback key.", primaryFailure.message)
            attemptWithKey(fallbackKey, sentence)
        }
    }

    /** One full attempt cycle (up to `config.maxAttempts`) against a single key. */
    private suspend fun attemptWithKey(apiKey: String, sentence: String): Pair<ByteArray, AudioFormatSpec> {
        var lastError: String? = null

        for (attempt in 1..config.maxAttempts) {
            try {
                val response = http.post("${config.baseUrl.trimEnd('/')}/v1beta/models/${config.model}:generateContent") {
                    contentType(ContentType.Application.Json)
                    // Header rather than ?key=, matching GeminiStt — the secret
                    // never enters a URL, which is what ends up in logs.
                    header("x-goog-api-key", apiKey)
                    setBody(requestBody(sentence).toString())
                }

                val text = response.bodyAsText()
                if (!response.status.isSuccess()) {
                    val detail = SecretRedactor.redact(text).take(400)
                    lastError = "HTTP ${response.status.value}: $detail"
                    if (!isRetryable(response.status)) {
                        throw TtsUnavailableException(explain(response.status, detail))
                    }
                    log.warn("Gemini TTS attempt {}/{} failed: {}", attempt, config.maxAttempts, lastError)
                    backoff(attempt)
                    continue
                }

                return decode(text)
            } catch (e: TtsUnavailableException) {
                throw e
            } catch (e: Exception) {
                lastError = SecretRedactor.redact("${e::class.simpleName}: ${e.message}")
                log.warn("Gemini TTS attempt {}/{} threw: {}", attempt, config.maxAttempts, lastError)
                if (attempt < config.maxAttempts) backoff(attempt)
            }
        }

        throw TtsUnavailableException("Gemini TTS unreachable after ${config.maxAttempts} attempts. Last error: $lastError")
    }

    private fun requestBody(text: String) = buildJsonObject {
        put("contents", buildJsonArray {
            add(buildJsonObject {
                put("parts", buildJsonArray { add(buildJsonObject { put("text", text) }) })
            })
        })
        putJsonObject("generationConfig") {
            put("responseModalities", buildJsonArray { add(JsonPrimitive("AUDIO")) })
            putJsonObject("speechConfig") {
                putJsonObject("voiceConfig") {
                    putJsonObject("prebuiltVoiceConfig") { put("voiceName", config.voice) }
                }
            }
        }
    }

    /**
     * Parses `inlineData` directly rather than through [WavCodec] — there is
     * no RIFF header to sniff. Every response measured is bare PCM; assuming
     * a WAV wrapper "just in case" would be guessing at a shape nothing here
     * has ever seen.
     */
    private fun decode(responseBody: String): Pair<ByteArray, AudioFormatSpec> {
        val inlineData = runCatching {
            json.parseToJsonElement(responseBody)
                .jsonObject["candidates"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("content")?.jsonObject?.get("parts")?.jsonArray
                ?.firstOrNull()?.jsonObject?.get("inlineData")?.jsonObject
        }.getOrNull()

        val mimeType = inlineData?.get("mimeType")?.jsonPrimitive?.content
        val data = inlineData?.get("data")?.jsonPrimitive?.content

        if (mimeType == null || data == null) {
            throw TtsUnavailableException(
                "Gemini TTS returned no audio. Check tts.voice in config.toml is a real voice name " +
                    "(EC-G3) — the model may also have declined the prompt. Raw response: " +
                    SecretRedactor.redact(responseBody).take(300),
            )
        }

        return Base64.getDecoder().decode(data) to formatFromMimeType(mimeType)
    }

    /**
     * `inlineData.mimeType` was measured as `"audio/l16; rate=24000; channels=1"`
     * — parsed here rather than hardcoded, because a future model or voice is
     * free to return a different rate or channel count, and a silently wrong
     * one is an audible bug (pitch-shifted, garbled speech), not a crash.
     * Missing fields fall back to what was actually observed, not a guess
     * pulled from documentation.
     */
    internal fun formatFromMimeType(mimeType: String): AudioFormatSpec {
        val rate = mimeRate.find(mimeType)?.groupValues?.get(1)?.toIntOrNull() ?: 24_000
        val channels = mimeChannels.find(mimeType)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        return AudioFormatSpec(sampleRateHz = rate, channels = channels)
    }

    private val mimeRate = Regex("""rate=(\d+)""")
    private val mimeChannels = Regex("""channels=(\d+)""")

    private fun isRetryable(status: HttpStatusCode): Boolean =
        status.value == 408 || status.value == 429 || status.value >= 500

    private fun explain(status: HttpStatusCode, raw: String): String = when (status.value) {
        400 -> "Gemini TTS rejected the request (400). Likely tts.voice in config.toml is not a real voice name. Detail: $raw"
        401, 403 -> "Gemini TTS rejected the credentials (${status.value}). Check tts.api_key in config.toml."
        404 -> "Gemini TTS model '${config.model}' not found (404). Check tts.model in config.toml (EC-G3)."
        else -> "Gemini TTS failed (${status.value}): $raw"
    }

    private suspend fun backoff(attempt: Int) {
        val base = config.initialBackoffMs * (1L shl (attempt - 1))
        delay(base + Random.nextLong(0, (base / 2).coerceAtLeast(1)))
    }

    private fun HttpStatusCode.isSuccess(): Boolean = value in 200..299
}
