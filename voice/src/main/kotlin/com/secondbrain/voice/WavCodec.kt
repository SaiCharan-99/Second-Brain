package com.secondbrain.voice

import com.secondbrain.model.AudioFormatSpec
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal RIFF/WAVE reader and writer for PCM16.
 *
 * Hand-rolled rather than javax.sound.sampled.AudioSystem.write because we need
 * to stream a header out before the payload length is known (capture is still
 * running) and to parse whatever container Kokoro returns without going through
 * a file. Pure functions, unit tested.
 */
object WavCodec {

    private const val RIFF = 0x46464952 // "RIFF" little-endian
    private const val WAVE = 0x45564157 // "WAVE"
    const val HEADER_BYTES: Int = 44

    class WavParseException(message: String) : Exception(message)

    data class ParsedWav(val format: AudioFormatSpec, val pcm: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ParsedWav) return false
            return format == other.format && pcm.contentEquals(other.pcm)
        }

        override fun hashCode(): Int = 31 * format.hashCode() + pcm.contentHashCode()
    }

    /**
     * A 44-byte canonical PCM header. [pcmByteCount] may be a placeholder when
     * streaming; call [patchLengths] once the true size is known.
     */
    fun header(format: AudioFormatSpec, pcmByteCount: Int): ByteArray {
        val buf = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        val byteRate = format.sampleRateHz * format.bytesPerFrame

        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt(36 + pcmByteCount)
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))

        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16)                                  // subchunk size for PCM
        buf.putShort(1)                                 // audio format: 1 = PCM
        buf.putShort(format.channels.toShort())
        buf.putInt(format.sampleRateHz)
        buf.putInt(byteRate)
        buf.putShort(format.bytesPerFrame.toShort())    // block align
        buf.putShort(format.bitsPerSample.toShort())

        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(pcmByteCount)

        return buf.array()
    }

    /** Rewrites the two length fields of an already-written 44-byte header. */
    fun patchLengths(header: ByteArray, pcmByteCount: Int) {
        require(header.size >= HEADER_BYTES) { "header too short: ${header.size}" }
        ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(4, 36 + pcmByteCount)
            putInt(40, pcmByteCount)
        }
    }

    fun wrap(format: AudioFormatSpec, pcm: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(HEADER_BYTES + pcm.size)
        out.write(header(format, pcm.size))
        out.write(pcm)
        return out.toByteArray()
    }

    /**
     * Parses a PCM WAVE file. Walks the chunk list rather than assuming a
     * 44-byte header, because plenty of encoders insert LIST/fact chunks and a
     * fixed offset would silently read metadata as audio.
     */
    fun parse(bytes: ByteArray): ParsedWav {
        if (bytes.size < 12) throw WavParseException("not a WAV: only ${bytes.size} bytes")
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        if (buf.getInt(0) != RIFF) throw WavParseException("missing RIFF magic")
        if (buf.getInt(8) != WAVE) throw WavParseException("missing WAVE magic")

        var pos = 12
        var sampleRate = -1
        var channels = -1
        var bits = -1
        var audioFormat = -1
        var pcm: ByteArray? = null

        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = buf.getInt(pos + 4)
            val body = pos + 8
            if (size < 0 || body + size > bytes.size) {
                // Truncated final chunk: take what is actually there rather than
                // throwing. A cut-short recording is still worth transcribing.
                if (id == "data") {
                    pcm = bytes.copyOfRange(body, bytes.size)
                }
                break
            }
            when (id) {
                "fmt " -> {
                    if (size < 16) throw WavParseException("fmt chunk too small: $size")
                    audioFormat = buf.getShort(body).toInt()
                    channels = buf.getShort(body + 2).toInt()
                    sampleRate = buf.getInt(body + 4)
                    bits = buf.getShort(body + 14).toInt()
                }
                "data" -> pcm = bytes.copyOfRange(body, body + size)
            }
            pos = body + size + (size % 2) // chunks are word-aligned
        }

        if (audioFormat != 1) {
            throw WavParseException(
                "unsupported WAV encoding $audioFormat (only PCM=1 is supported). " +
                    "If this came from the TTS endpoint, set tts.response_format to a PCM WAV."
            )
        }
        if (sampleRate <= 0 || channels <= 0 || bits <= 0) throw WavParseException("missing fmt chunk")
        val data = pcm ?: throw WavParseException("missing data chunk")

        return ParsedWav(
            AudioFormatSpec(
                sampleRateHz = sampleRate,
                bitsPerSample = bits,
                channels = channels,
                signed = true,
                bigEndian = false,
            ),
            data,
        )
    }

    /** True if the payload starts with RIFF/WAVE. Used to sniff TTS responses. */
    fun looksLikeWav(bytes: ByteArray): Boolean =
        bytes.size >= 12 &&
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).let {
                it.getInt(0) == RIFF && it.getInt(8) == WAVE
            }
}
