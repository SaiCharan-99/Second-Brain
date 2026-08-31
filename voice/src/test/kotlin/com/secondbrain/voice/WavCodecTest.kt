package com.secondbrain.voice

import com.secondbrain.model.AudioFormatSpec
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavCodecTest {

    private val format = AudioFormatSpec.CAPTURE

    private fun pcm(bytes: Int) = ByteArray(bytes) { (it % 256 - 128).toByte() }

    @Test
    fun `header is 44 bytes and round-trips through parse`() {
        val payload = pcm(320)
        val wav = WavCodec.wrap(format, payload)

        assertEquals(WavCodec.HEADER_BYTES + payload.size, wav.size)
        val parsed = WavCodec.parse(wav)
        assertEquals(16_000, parsed.format.sampleRateHz)
        assertEquals(1, parsed.format.channels)
        assertEquals(16, parsed.format.bitsPerSample)
        assertArrayEquals(payload, parsed.pcm)
    }

    @Test
    fun `patchLengths rewrites both size fields`() {
        val header = WavCodec.header(format, 0)
        WavCodec.patchLengths(header, 1024)

        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(36 + 1024, buf.getInt(4), "RIFF chunk size")
        assertEquals(1024, buf.getInt(40), "data chunk size")
    }

    @Test
    fun `looksLikeWav sniffs correctly`() {
        assertTrue(WavCodec.looksLikeWav(WavCodec.wrap(format, pcm(64))))
        assertFalse(WavCodec.looksLikeWav(byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x44)))
        assertFalse(WavCodec.looksLikeWav(ByteArray(4)))
    }

    @Test
    fun `parses a file with an extra LIST chunk before data`() {
        // Encoders insert metadata chunks. A fixed 44-byte offset would read the
        // metadata as audio and produce noise, so the parser walks the chunk list.
        val payload = pcm(128)
        val listBody = "INFOISFT".toByteArray(Charsets.US_ASCII) + ByteArray(4)
        val body = ByteBuffer.allocate(8 + 16 + 8 + listBody.size + 8 + payload.size)
            .order(ByteOrder.LITTLE_ENDIAN)
        body.put("fmt ".toByteArray(Charsets.US_ASCII)); body.putInt(16)
        body.putShort(1); body.putShort(1); body.putInt(16_000); body.putInt(32_000)
        body.putShort(2); body.putShort(16)
        body.put("LIST".toByteArray(Charsets.US_ASCII)); body.putInt(listBody.size); body.put(listBody)
        body.put("data".toByteArray(Charsets.US_ASCII)); body.putInt(payload.size); body.put(payload)

        val full = ByteBuffer.allocate(12 + body.capacity()).order(ByteOrder.LITTLE_ENDIAN)
        full.put("RIFF".toByteArray(Charsets.US_ASCII))
        full.putInt(4 + body.capacity())
        full.put("WAVE".toByteArray(Charsets.US_ASCII))
        full.put(body.array())

        val parsed = WavCodec.parse(full.array())
        assertArrayEquals(payload, parsed.pcm)
    }

    @Test
    fun `a truncated data chunk yields the audio that is actually present`() {
        // EC-V7 / crash mid-write: a recording cut short is still worth
        // transcribing, so a short data chunk must not throw.
        val payload = pcm(400)
        val wav = WavCodec.wrap(format, payload)
        val truncated = wav.copyOfRange(0, wav.size - 150)

        val parsed = WavCodec.parse(truncated)
        assertEquals(payload.size - 150, parsed.pcm.size)
    }

    @Test
    fun `rejects a non-PCM encoding by naming the config key`() {
        val wav = WavCodec.wrap(format, pcm(64))
        // flip the audioFormat field from 1 (PCM) to 3 (IEEE float)
        ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN).putShort(20, 3)

        val e = assertThrows(WavCodec.WavParseException::class.java) { WavCodec.parse(wav) }
        assertTrue(e.message!!.contains("PCM"), e.message)
    }

    @Test
    fun `rejects garbage that is not a WAV at all`() {
        assertThrows(WavCodec.WavParseException::class.java) {
            WavCodec.parse("this is an HTML error page".toByteArray())
        }
    }

    @Test
    fun `duration maths matches the format`() {
        // 16 kHz mono PCM16 is 32000 bytes per second.
        assertEquals(32_000, format.bytesPerSecond)
        assertEquals(1_000L, format.durationMsForBytes(32_000))
        assertEquals(6_400L, format.bytesForDurationMs(200))
    }
}
