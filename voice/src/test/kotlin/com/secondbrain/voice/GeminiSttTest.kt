package com.secondbrain.voice

import com.secondbrain.model.AudioFormatSpec
import com.secondbrain.model.SttConfig
import com.secondbrain.model.SttStatus
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * D-086: the fallback-key mechanism, against a scripted [MockEngine] rather
 * than the live Gemini endpoint — no request here should ever leave the
 * process. This is the file that would have caught the `AgentLoopTest`-style
 * "looks right, isn't" bug this session already hit twice (D-078's
 * `JsonBridge`, the mutable-list-aliasing test bug in `AgentLoopTest`): the
 * fallback logic reads as obviously correct and was worth verifying rather
 * than trusting on sight.
 */
class GeminiSttTest {

    private fun wavFile(): Path = Files.createTempFile("gemini-stt-test", ".wav").also {
        Files.write(it, ByteArray(64))
    }

    private fun okBody(text: String) =
        """{"candidates":[{"content":{"parts":[{"audioTranscription":{"text":"$text"}}]}}]}"""

    private val quotaBody = """{"error":{"code":429,"message":"Quota exceeded"}}"""

    private fun jsonHeaders() = headersOf(HttpHeaders.ContentType, "application/json")

    private fun config(fallback: String? = "fallback-key", maxAttempts: Int = 2) = SttConfig(
        model = "gemini-3.5-transcribe",
        apiKey = "primary-key",
        fallbackApiKey = fallback,
        maxAttempts = maxAttempts,
        initialBackoffMs = 1, // keep the test fast; backoff itself isn't what's under test
    )

    @Test
    @DisplayName("primary key succeeding never touches the fallback")
    fun `primary success skips fallback entirely`() = runTest {
        var calls = 0
        val engine = MockEngine { request ->
            calls++
            assertEquals("primary-key", request.headers["x-goog-api-key"])
            respond(okBody("hello there"), HttpStatusCode.OK, jsonHeaders())
        }
        val stt = GeminiStt(HttpClient(engine), config())

        val result = stt.transcribe("u1", wavFile(), AudioFormatSpec.CAPTURE)

        assertEquals(SttStatus.OK, result.status)
        assertEquals("hello there", result.text)
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
            else respond(okBody("fallback worked"), HttpStatusCode.OK, jsonHeaders())
        }
        val stt = GeminiStt(HttpClient(engine), config(maxAttempts = 2))

        val result = stt.transcribe("u1", wavFile(), AudioFormatSpec.CAPTURE)

        assertEquals(SttStatus.OK, result.status)
        assertEquals("fallback worked", result.text)
        // Every configured attempt on the primary key is spent before the
        // fallback is ever reached - this is the "exhaust first" contract,
        // not an early bail on the first 429.
        assertEquals(2, keysUsed.count { it == "primary-key" })
        assertEquals(1, keysUsed.count { it == "fallback-key" })
    }

    @Test
    @DisplayName("a bad primary credential (401), not just a quota 429, still falls back")
    fun `non-retryable primary failure also falls back`() = runTest {
        val engine = MockEngine { request ->
            when (request.headers["x-goog-api-key"]) {
                "primary-key" -> respond("""{"error":{"code":401,"message":"bad key"}}""", HttpStatusCode.Unauthorized, jsonHeaders())
                else -> respond(okBody("fallback worked"), HttpStatusCode.OK, jsonHeaders())
            }
        }
        val stt = GeminiStt(HttpClient(engine), config())

        val result = stt.transcribe("u1", wavFile(), AudioFormatSpec.CAPTURE)

        // 401 is non-retryable within a single key's own attempt loop (fails
        // fast rather than burning all maxAttempts on dead credentials), but
        // still triggers the fallback - a revoked primary key is exactly as
        // recoverable this way as an exhausted quota is.
        assertEquals(SttStatus.OK, result.status)
        assertEquals("fallback worked", result.text)
    }

    @Test
    @DisplayName("no fallback configured behaves exactly as before D-086")
    fun `unconfigured fallback fails the same way it always did`() = runTest {
        var calls = 0
        val engine = MockEngine { calls++; respond(quotaBody, HttpStatusCode.TooManyRequests, jsonHeaders()) }
        val stt = GeminiStt(HttpClient(engine), config(fallback = null, maxAttempts = 2))

        val result = stt.transcribe("u1", wavFile(), AudioFormatSpec.CAPTURE)

        assertEquals(SttStatus.FAILED, result.status)
        assertEquals(2, calls, "only the primary key's own maxAttempts, nothing more")
    }

    @Test
    @DisplayName("both keys failing reports the fallback's own error, and stops")
    fun `both keys failing reports cleanly`() = runTest {
        var calls = 0
        val engine = MockEngine { calls++; respond(quotaBody, HttpStatusCode.TooManyRequests, jsonHeaders()) }
        val stt = GeminiStt(HttpClient(engine), config(maxAttempts = 1))

        val result = stt.transcribe("u1", wavFile(), AudioFormatSpec.CAPTURE)

        assertEquals(SttStatus.FAILED, result.status)
        assertEquals(2, calls, "one attempt on each of the two keys")
        assertTrue(result.error != null)
    }

    @Test
    @DisplayName("a blank fallback key is treated as unconfigured, not as a real key to try")
    fun `blank fallback key is not attempted`() = runTest {
        var calls = 0
        val engine = MockEngine { calls++; respond(quotaBody, HttpStatusCode.TooManyRequests, jsonHeaders()) }
        val stt = GeminiStt(HttpClient(engine), config(fallback = "  ", maxAttempts = 1))

        stt.transcribe("u1", wavFile(), AudioFormatSpec.CAPTURE)

        assertEquals(1, calls, "a blank string is not a key")
    }
}
