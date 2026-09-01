package com.secondbrain.voice

import com.secondbrain.model.TtsConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

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
}
