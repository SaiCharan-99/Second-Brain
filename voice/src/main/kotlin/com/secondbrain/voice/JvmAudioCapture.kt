package com.secondbrain.voice

import com.secondbrain.model.AudioConfig
import com.secondbrain.model.AudioFormatSpec
import com.secondbrain.ports.AudioCapturePort
import com.secondbrain.ports.AudioDevice
import com.secondbrain.ports.AudioDeviceLostException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.slf4j.LoggerFactory
import javax.sound.sampled.LineUnavailableException
import javax.sound.sampled.TargetDataLine

/**
 * Microphone capture over javax.sound.sampled.TargetDataLine.
 *
 * 16 kHz mono PCM16 signed little-endian, confirmed supported on the target
 * laptop by spike S1.3.
 *
 * The line is opened per capture and closed on flow completion. Holding a line
 * open across utterances would be marginally faster and is exactly how EC-V9
 * turns into a crash: a headset unplugged between two utterances leaves a stale
 * line whose next read throws.
 */
class JvmAudioCapture(
    private val config: AudioConfig,
    override val format: AudioFormatSpec = AudioFormatSpec.CAPTURE,
) : AudioCapturePort {

    private val log = LoggerFactory.getLogger(JvmAudioCapture::class.java)

    /** Name of the device the last capture actually used. Feeds the E3 same-device check. */
    @Volatile
    var lastDeviceName: String = "unknown"
        private set

    override fun devices(): List<AudioDevice> = AudioDevices.captureDevices(format)

    override fun capture(deviceId: String?): Flow<ByteArray> = flow {
        val chunkBytes = format.bytesForDurationMs(config.captureChunkMs.toLong()).toInt()
            .coerceAtLeast(format.bytesPerFrame)
        // Four chunks of headroom: enough that a GC pause does not drop audio,
        // small enough that we are never far behind real time.
        val bufferBytes = chunkBytes * 4

        val (line, deviceName) = try {
            AudioDevices.openCapture(format, deviceId ?: config.preferredCaptureDevice, bufferBytes)
        } catch (e: LineUnavailableException) {
            throw AudioDeviceLostException(
                "Could not open a microphone. Is another application using it, or was a headset just unplugged?",
                e,
            )
        }
        lastDeviceName = deviceName
        log.debug("capture open: device='{}' chunk={}B buffer={}B", deviceName, chunkBytes, bufferBytes)

        val buf = ByteArray(chunkBytes)
        try {
            line.start()
            while (true) {
                val read = try {
                    line.read(buf, 0, buf.size)
                } catch (e: Exception) {
                    // A device yanked mid-read. Surface it; do not crash (EC-V9).
                    throw AudioDeviceLostException("Microphone disappeared mid-recording ($deviceName).", e)
                }
                if (read <= 0) {
                    throw AudioDeviceLostException("Microphone returned no data ($deviceName). Device likely removed.")
                }
                emit(if (read == buf.size) buf.copyOf() else buf.copyOf(read))
            }
        } finally {
            closeQuietly(line)
        }
    }.flowOn(Dispatchers.IO)

    private fun closeQuietly(line: TargetDataLine) {
        runCatching { line.stop() }
        runCatching { line.flush() }
        runCatching { line.close() }
    }
}
