package com.secondbrain.voice

import com.secondbrain.model.SpeechRequest
import com.secondbrain.model.TtsConfig
import com.secondbrain.ports.TtsUnavailableException
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.Base64

/**
 * D-065: the one piece of GeminiTts worth a plain unit test — the rest is
 * exercised the way GeminiStt/KokoroTts always have been, "manual + latency
 * measurement" per CLAUDE.md's testing table, now backed by the live-API
 * verification recorded in DECISIONS.md rather than a guess.
 */
class GeminiTtsTest {

    // Never used for a real request — just satisfies the constructor.
    private val tts = GeminiTts(HttpClient(CIO), TtsConfig())

    @Test
    @DisplayName("the exact shape measured live: audio/l16; rate=24000; channels=1")
    fun `parses the observed mimeType`() {
        val format = tts.formatFromMimeType("audio/l16; rate=24000; channels=1")
        assertEquals(24_000, format.sampleRateHz)
        assertEquals(1, format.channels)
    }

    @Test
    fun `a different rate or channel count is read, never assumed`() {
        val format = tts.formatFromMimeType("audio/l16; rate=48000; channels=2")
        assertEquals(48_000, format.sampleRateHz)
        assertEquals(2, format.channels)
    }

    @Test
    fun `field order in the mimeType does not matter`() {
        val format = tts.formatFromMimeType("audio/l16; channels=2; rate=16000")
        assertEquals(16_000, format.sampleRateHz)
        assertEquals(2, format.channels)
    }

    @Test
    @DisplayName("a mimeType with neither field falls back to what was actually measured, not a guess")
    fun `missing fields fall back to the observed default`() {
        val format = tts.formatFromMimeType("audio/l16")
        assertEquals(24_000, format.sampleRateHz)
        assertEquals(1, format.channels)
    }

    // ── D-086: the fallback-key mechanism, against a scripted MockEngine ────

    private fun okBody(pcmBytes: ByteArray = byteArrayOf(1, 2, 3, 4)) = """
        {"candidates":[{"content":{"parts":[{"inlineData":{
          "mimeType":"audio/l16; rate=24000; channels=1",
          "data":"${Base64.getEncoder().encodeToString(pcmBytes)}"
        }}]}}]}
    """.trimIndent()

    private val quotaBody = """{"error":{"code":429,"message":"Quota exceeded"}}"""
    private fun jsonHeaders() = headersOf(HttpHeaders.ContentType, "application/json")

    private fun config(fallback: String? = "fallback-key", maxAttempts: Int = 2) = TtsConfig(
        apiKey = "primary-key",
        fallbackApiKey = fallback,
        maxAttempts = maxAttempts,
        initialBackoffMs = 1,
    )

    private suspend fun GeminiTts.speak(text: String = "hello") =
        synthesize(SpeechRequest(text, voice = "Kore", maxSpeechSeconds = 60)).toList()

    @Test
    @DisplayName("primary key succeeding never touches the fallback")
    fun `primary success skips fallback entirely`() = runTest {
        var calls = 0
        val engine = MockEngine { request ->
            calls++
            assertEquals("primary-key", request.headers["x-goog-api-key"])
            respond(okBody(), HttpStatusCode.OK, jsonHeaders())
        }
        val chunks = GeminiTts(HttpClient(engine), config()).speak()

        assertEquals(1, chunks.size)
        assertEquals(1, calls, "the fallback key must never be tried when the primary succeeds")
    }

    @Test
    @DisplayName("primary exhausting every retry falls back, and the fallback's success is what's returned")
    fun `primary exhausted falls back and succeeds`() = runTest {
        val keysUsed = mutableListOf<String?>()
        val engine = MockEngine { request ->
            val key = request.headers["x-goog-api-key"]
            keysUsed += key
            if (key == "primary-key") respond(quotaBody, HttpStatusCode.TooManyRequests, jsonHeaders())
            else respond(okBody(byteArrayOf(9, 9, 9)), HttpStatusCode.OK, jsonHeaders())
        }
        val chunks = GeminiTts(HttpClient(engine), config(maxAttempts = 2)).speak()

        assertEquals(1, chunks.size)
        assertTrue(chunks.single().pcm.contentEquals(byteArrayOf(9, 9, 9)))
        assertEquals(2, keysUsed.count { it == "primary-key" })
        assertEquals(1, keysUsed.count { it == "fallback-key" })
    }

    @Test
    @DisplayName("no fallback configured behaves exactly as before D-086")
    fun `unconfigured fallback fails the same way it always did`() = runTest {
        var calls = 0
        val engine = MockEngine { calls++; respond(quotaBody, HttpStatusCode.TooManyRequests, jsonHeaders()) }
        val gemini = GeminiTts(HttpClient(engine), config(fallback = null, maxAttempts = 2))

        assertThrowsUnavailable { gemini.speak() }
        assertEquals(2, calls, "only the primary key's own maxAttempts, nothing more")
    }

    @Test
    @DisplayName("both keys failing throws, reporting the fallback's own error")
    fun `both keys failing reports cleanly`() = runTest {
        var calls = 0
        val engine = MockEngine { calls++; respond(quotaBody, HttpStatusCode.TooManyRequests, jsonHeaders()) }
        val gemini = GeminiTts(HttpClient(engine), config(maxAttempts = 1))

        assertThrowsUnavailable { gemini.speak() }
        assertEquals(2, calls, "one attempt on each of the two keys")
    }

    /** [assertThrows] plus a suspend body, without nesting a second `runTest` inside the one already running. */
    private suspend fun assertThrowsUnavailable(block: suspend () -> Unit) {
        val thrown = try {
            block()
            null
        } catch (e: TtsUnavailableException) {
            e
        }
        assertTrue(thrown != null, "expected TtsUnavailableException")
    }
}
