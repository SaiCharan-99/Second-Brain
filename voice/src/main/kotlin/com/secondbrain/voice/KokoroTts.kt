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
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import kotlin.random.Random

/**
 * Text to speech via a Kokoro endpoint.
 *
 * STATUS: the request/response contract is spike S1.2 and is NOT yet confirmed
 * against a live endpoint. What is implemented here is the OpenAI-compatible
 * `POST /v1/audio/speech` shape that kokoro-fastapi and the common hosted
 * deployments expose, because that is the only contract with more than one
 * implementation behind it. Everything contract-shaped is either configurable
 * or sniffed from the response rather than assumed:
 *
 *  - the path and base URL come from tts.base_url
 *  - the container comes from tts.response_format, and the actual bytes are
 *    sniffed for RIFF/WAVE regardless
 *  - the sample rate is read out of the WAV header, never hardcoded, because the
 *    playback line is opened from whatever format the chunk declares
 *
 * If S1.2 shows a different shape, this file changes and DECISIONS.md records
 * what the endpoint actually does. Nothing above this file assumes anything.
 *
 * EC-T3: sentences are synthesised one at a time and emitted as they arrive, so
 * the first sentence is audible while the rest are still being generated. That
 * is the single biggest lever on perceived latency in the whole loop.
 */
class KokoroTts(
    private val http: HttpClient,
    private val config: TtsConfig,
) : TtsPort {

    private val log = LoggerFactory.getLogger(KokoroTts::class.java)

    override val modelId: String get() = config.model

    override fun synthesize(request: SpeechRequest): Flow<AudioChunk> = flow {
        val sentences = SpeechNormalizer.sentences(request.text)
            .ifEmpty { listOf(request.text) }
            .filter { it.isNotBlank() }

        if (sentences.isEmpty()) return@flow

        sentences.forEachIndexed { index, sentence ->
            val pcm = synthesizeOne(sentence)
            emit(
                AudioChunk(
                    pcm = pcm.pcm,
                    format = pcm.format,
                    sentenceIndex = index,
                    isLastOfSentence = true,
                )
            )
        }
    }

    private suspend fun synthesizeOne(sentence: String): WavCodec.ParsedWav {
        var lastError: String? = null

        for (attempt in 1..config.maxAttempts) {
            try {
                val response = http.post("${config.baseUrl.trimEnd('/')}/v1/audio/speech") {
                    contentType(ContentType.Application.Json)
                    config.apiKey?.takeIf { it.isNotBlank() }?.let {
                        header("Authorization", "Bearer $it")
                    }
                    setBody(
                        buildJsonObject {
                            put("model", config.model)
                            put("voice", config.voice)
                            put("input", sentence)
                            put("response_format", config.responseFormat)
                            put("speed", config.speed)
                        }.toString()
                    )
                }

                if (!response.status.isSuccess()) {
                    val detail = SecretRedactor.redact(response.bodyAsText()).take(400)
                    lastError = "HTTP ${response.status.value}: $detail"
                    if (!isRetryable(response.status)) {
                        throw TtsUnavailableException(explain(response.status, detail))
                    }
                    log.warn("Kokoro attempt {}/{} failed: {}", attempt, config.maxAttempts, lastError)
                    backoff(attempt)
                    continue
                }

                val bytes = response.bodyAsBytes()
                return decode(bytes)
            } catch (e: TtsUnavailableException) {
                throw e
            } catch (e: Exception) {
                lastError = SecretRedactor.redact("${e::class.simpleName}: ${e.message}")
                log.warn("Kokoro attempt {}/{} threw: {}", attempt, config.maxAttempts, lastError)
                if (attempt < config.maxAttempts) backoff(attempt)
            }
        }

        throw TtsUnavailableException(
            "Kokoro unreachable after ${config.maxAttempts} attempts. Last error: $lastError"
        )
    }

    /**
     * Decodes the response body to PCM.
     *
     * The JDK ships no MP3 decoder, so a provider that returns MP3 needs either
     * `response_format = "wav"` or a decoder dependency. Rather than a mystery
     * failure inside the playback line, that shows up here as a message naming
     * the config key. This is the single most likely way S1.2 bites.
     */
    private fun decode(bytes: ByteArray): WavCodec.ParsedWav {
        if (WavCodec.looksLikeWav(bytes)) {
            return WavCodec.parse(bytes)
        }
        if (config.responseFormat.equals("pcm", ignoreCase = true)) {
            // Raw PCM has no header, so the format cannot be sniffed. Kokoro's
            // native rate is 24 kHz; confirm in S1.2 before trusting this branch.
            log.debug("Treating {} bytes as headerless PCM16 at 24 kHz mono.", bytes.size)
            return WavCodec.ParsedWav(AudioFormatSpec(sampleRateHz = 24_000, channels = 1), bytes)
        }
        throw TtsUnavailableException(
            "TTS returned ${bytes.size} bytes that are not RIFF/WAVE and tts.response_format is " +
                "'${config.responseFormat}'. The JVM cannot decode compressed audio without an extra " +
                "dependency -- set tts.response_format to \"wav\" (or \"pcm\") in config.toml."
        )
    }

    private fun isRetryable(status: HttpStatusCode): Boolean =
        status.value == 408 || status.value == 429 || status.value >= 500

    private fun explain(status: HttpStatusCode, raw: String): String = when (status.value) {
        401, 403 -> "TTS endpoint rejected the credentials (${status.value}). Check tts.api_key in config.toml."
        404 -> "TTS endpoint returned 404 for /v1/audio/speech. Either tts.base_url is wrong or this " +
            "provider is not OpenAI-compatible. This is exactly what spike S1.2 exists to settle."
        422, 400 -> "TTS endpoint rejected the request (${status.value}). Likely tts.voice or tts.model. Detail: $raw"
        else -> "TTS endpoint failed (${status.value}): $raw"
    }

    private suspend fun backoff(attempt: Int) {
        val base = config.initialBackoffMs * (1L shl (attempt - 1))
        delay(base + Random.nextLong(0, (base / 2).coerceAtLeast(1)))
    }

    private fun HttpStatusCode.isSuccess(): Boolean = value in 200..299
}
