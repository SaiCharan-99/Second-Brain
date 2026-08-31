package com.secondbrain.voice

import com.secondbrain.model.AudioFormatSpec
import com.secondbrain.model.VoiceGateConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * EC-V1 is the only edge case in the whole system whose failure mode is "spends
 * money on nothing", so it gets asserted rather than eyeballed.
 */
class VoiceGateTest {

    private val format = AudioFormatSpec.CAPTURE
    private val config = VoiceGateConfig()
    private val threshold = -45.0

    private fun bytesFor(ms: Long) = format.bytesForDurationMs(ms)

    @Test
    @DisplayName("EC-V1 a 200ms accidental keypress is discarded as TOO_SHORT")
    fun `too short is discarded`() {
        val verdict = VoiceGate.evaluate(bytesFor(200), peakRmsDbfs = -10.0, format, config, threshold)
        val discard = assertInstanceOf(VoiceGate.Verdict.Discard::class.java, verdict)
        assertEquals(VoiceGate.DiscardReason.TOO_SHORT, discard.reason)
        assertTrue(discard.detail.contains("200ms"), discard.detail)
    }

    @Test
    @DisplayName("EC-V1 room noise for two seconds is discarded as TOO_QUIET")
    fun `too quiet is discarded`() {
        val verdict = VoiceGate.evaluate(bytesFor(2_000), peakRmsDbfs = -52.0, format, config, threshold)
        val discard = assertInstanceOf(VoiceGate.Verdict.Discard::class.java, verdict)
        assertEquals(VoiceGate.DiscardReason.TOO_QUIET, discard.reason)
    }

    @Test
    fun `zero bytes is NO_AUDIO, which is a device problem not a user problem`() {
        val verdict = VoiceGate.evaluate(0, peakRmsDbfs = -10.0, format, config, threshold)
        val discard = assertInstanceOf(VoiceGate.Verdict.Discard::class.java, verdict)
        assertEquals(VoiceGate.DiscardReason.NO_AUDIO, discard.reason)
    }

    @Test
    fun `real speech is accepted`() {
        val verdict = VoiceGate.evaluate(bytesFor(2_500), peakRmsDbfs = -18.0, format, config, threshold)
        assertInstanceOf(VoiceGate.Verdict.Accept::class.java, verdict)
    }

    @Test
    @DisplayName("both conditions are required, not scored together")
    fun `loud but short is still rejected`() {
        // A loud 100 ms click must not be accepted just because it is loud. The
        // conditions are ANDed on purpose.
        val verdict = VoiceGate.evaluate(bytesFor(100), peakRmsDbfs = -3.0, format, config, threshold)
        assertInstanceOf(VoiceGate.Verdict.Discard::class.java, verdict)
    }

    @Test
    fun `exactly at the minimum duration is accepted`() {
        val verdict = VoiceGate.evaluate(
            bytesFor(config.minUtteranceMs), peakRmsDbfs = -20.0, format, config, threshold,
        )
        assertInstanceOf(VoiceGate.Verdict.Accept::class.java, verdict)
    }

    @Test
    fun `exactly at the energy threshold is rejected, not accepted`() {
        // Boundary chosen deliberately: at the floor, it is the room, not a voice.
        val verdict = VoiceGate.evaluate(bytesFor(2_000), peakRmsDbfs = threshold, format, config, threshold)
        assertInstanceOf(VoiceGate.Verdict.Discard::class.java, verdict)
    }

    @Test
    @DisplayName("EC-V6 / E6 the duration cap is detected so capture can be cut")
    fun `duration cap`() {
        assertFalse(VoiceGate.hasHitDurationCap(bytesFor(59_000), format, config))
        assertTrue(VoiceGate.hasHitDurationCap(bytesFor(60_000), format, config))
        assertTrue(VoiceGate.hasHitDurationCap(bytesFor(61_000), format, config))
    }

    @Test
    @DisplayName("E13 VAD trailing-silence detection, built but unvalidated")
    fun `trailing silence`() {
        val chunkMs = 50L
        val chunksForTimeout = (config.vadSilenceTimeoutMs / chunkMs).toInt() // 24

        val allQuiet = List(chunksForTimeout) { -60.0 }
        assertTrue(VoiceGate.isTrailingSilence(allQuiet, format, chunkMs, config, threshold))

        val oneLoudAtEnd = List(chunksForTimeout - 1) { -60.0 } + listOf(-20.0)
        assertFalse(VoiceGate.isTrailingSilence(oneLoudAtEnd, format, chunkMs, config, threshold))

        // EC-V2: a mid-sentence pause shorter than the timeout must NOT endpoint.
        val shortPause = List(chunksForTimeout - 5) { -60.0 }
        assertFalse(VoiceGate.isTrailingSilence(shortPause, format, chunkMs, config, threshold))
    }

    @Test
    fun `RMS measures a sine wave at roughly the expected level`() {
        // A full-scale sine has an RMS of 1/sqrt(2), i.e. about -3 dBFS.
        val samples = 16_000
        val pcm = ByteArray(samples * 2)
        for (i in 0 until samples) {
            val v = (sin(2 * PI * 440 * i / 16_000.0) * Short.MAX_VALUE).toInt()
            pcm[i * 2] = (v and 0xFF).toByte()
            pcm[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        val level = Rms.dbfs(pcm)
        assertTrue(level in -4.0..-2.0, "expected about -3 dBFS, got $level")
    }

    @Test
    fun `RMS of digital silence is the silence floor`() {
        assertEquals(Rms.SILENCE_DBFS, Rms.dbfs(ByteArray(3_200)))
    }

    @Test
    fun `RMS handles an odd trailing byte without crashing`() {
        assertEquals(Rms.SILENCE_DBFS, Rms.dbfs(ByteArray(1)))
        assertEquals(Rms.SILENCE_DBFS, Rms.dbfs(ByteArray(0)))
    }
}
