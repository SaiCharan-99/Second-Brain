package com.secondbrain.voice

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * CLAUDE.md hard prohibition: "Never write an API key into a log, a transcript,
 * a note, a commit, or a chat message."
 *
 * These tests exist because the failure is silent and permanent -- by the time
 * anyone notices, the key is already in a log file that has been read, synced or
 * pasted somewhere.
 */
class SecretRedactorTest {

    @AfterEach
    fun tearDown() = SecretRedactor.clearRegistered()

    @Test
    @DisplayName("a Google key is masked with no surrounding context at all")
    fun `google key shape`() {
        val out = SecretRedactor.redact("using AIzaSyD-1234567890abcdefghijklmnopqrs for STT")
        assertFalse(out.contains("AIzaSy"), out)
        assertTrue(out.contains(SecretRedactor.MASK), out)
    }

    @Test
    @DisplayName("the query-parameter form Gemini uses is masked but stays readable")
    fun `query parameter`() {
        val out = SecretRedactor.redact(
            "GET https://generativelanguage.googleapis.com/v1beta/models/x:generateContent?key=AIzaSyABCDEFGHIJKLMNOPQRSTUVWXYZ012345"
        )
        assertFalse(out.contains("AIzaSy"), out)
        assertTrue(out.contains("?key="), "the parameter name should survive so logs stay diagnosable: $out")
    }

    @Test
    fun `authorization bearer header is masked`() {
        val out = SecretRedactor.redact("Authorization: Bearer sk-ant-api03-verysecretvalue123456")
        assertFalse(out.contains("verysecret"), out)
        assertTrue(out.startsWith("Authorization: Bearer "), out)
    }

    @Test
    fun `x-goog-api-key header is masked`() {
        val out = SecretRedactor.redact("x-goog-api-key: AIzaSyABCDEFGHIJKLMNOPQRSTUVWXYZ012345")
        assertFalse(out.contains("AIzaSy"), out)
    }

    @Test
    fun `json bodies are masked`() {
        val out = SecretRedactor.redact("""{"model":"x","api_key":"hunter2-and-then-some","voice":"af"}""")
        assertFalse(out.contains("hunter2"), out)
        assertTrue(out.contains("\"voice\":\"af\""), "non-secret fields must survive: $out")
    }

    @Test
    @DisplayName("a config.toml line quoted into an error message is masked")
    fun `toml line`() {
        val out = SecretRedactor.redact("stt config:\n  api_key = \"AIzaSyREALKEYHERE1234567890abc\"\n  model = \"gemini\"")
        assertFalse(out.contains("REALKEY"), out)
        assertTrue(out.contains("model = \"gemini\""), out)
    }

    @Test
    @DisplayName("a registered secret is masked even in a shape no pattern predicts")
    fun `registered secret`() {
        SecretRedactor.register("wildly-unusual-token-format-9184")
        val out = SecretRedactor.redact("the endpoint wants wildly-unusual-token-format-9184 in a cookie")
        assertFalse(out.contains("wildly-unusual"), out)
    }

    @Test
    fun `short strings are not registered, so common words are not masked`() {
        SecretRedactor.register("abc")
        assertEquals("abc is fine", SecretRedactor.redact("abc is fine"))
    }

    @Test
    fun `null and empty are safe`() {
        assertEquals("", SecretRedactor.redact(null))
        assertEquals("", SecretRedactor.redact(""))
    }

    @Test
    fun `ordinary log lines are untouched`() {
        val line = "LATENCY utterance=abc spoken=2100ms stt=880ms ROUNDTRIP=1450ms"
        assertEquals(line, SecretRedactor.redact(line))
    }

    @Test
    @DisplayName("the Ktor wrapper redacts before the message reaches any appender")
    fun `ktor wrapper`() {
        val captured = mutableListOf<String>()
        val logger = SecretRedactor.KtorRedactingLogger(
            object : io.ktor.client.plugins.logging.Logger {
                override fun log(message: String) { captured += message }
            }
        )
        logger.log("REQUEST: https://x/y?key=AIzaSyABCDEFGHIJKLMNOPQRSTUVWXYZ012345")
        assertEquals(1, captured.size)
        assertFalse(captured[0].contains("AIzaSy"), captured[0])
    }
}
