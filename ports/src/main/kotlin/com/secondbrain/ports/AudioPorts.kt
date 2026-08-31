package com.secondbrain.ports

import com.secondbrain.model.AudioChunk
import com.secondbrain.model.AudioFormatSpec
import kotlinx.coroutines.flow.Flow

/** A capture or playback device as the platform reports it. */
data class AudioDevice(
    val id: String,
    val name: String,
    val isDefault: Boolean,
)

/** Raised when a line vanishes mid-session, e.g. an unplugged headset (EC-V9). */
class AudioDeviceLostException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Microphone capture.
 *
 * Implementations must not throw on device loss; they surface it through the
 * returned flow so the caller can re-enumerate and speak an error rather than
 * crash (EC-V9).
 */
interface AudioCapturePort {
    val format: AudioFormatSpec

    fun devices(): List<AudioDevice>

    /**
     * Opens the line and emits raw PCM frames until the flow is cancelled.
     * Each emission is at most `captureChunkMs` of audio.
     */
    fun capture(deviceId: String? = null): Flow<ByteArray>
}

/** What the playback line is doing. Barge-in needs this observable (EC-V3). */
enum class PlaybackState { IDLE, PLAYING, STOPPING }

/**
 * Speaker playback.
 *
 * [stop] is the whole point of this port: EC-V3 requires cutting audio within
 * 100 ms of detected barge-in. Implementations write in small chunks so a
 * blocking write never outlives that budget, and drop the ENTIRE queued chunk
 * chain rather than just the buffer in flight.
 */
interface AudioPlaybackPort {
    val state: PlaybackState

    fun devices(): List<AudioDevice>

    /** Suspends until the stream is exhausted or [stop] is called. */
    suspend fun play(chunks: Flow<AudioChunk>, deviceId: String? = null)

    /** Cuts playback and discards everything queued. Must return within 100 ms. */
    fun stop()
}
