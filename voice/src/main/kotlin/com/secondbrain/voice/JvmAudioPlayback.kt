package com.secondbrain.voice

import com.secondbrain.model.AudioChunk
import com.secondbrain.model.AudioConfig
import com.secondbrain.model.AudioFormatSpec
import com.secondbrain.ports.AudioDevice
import com.secondbrain.ports.AudioPlaybackPort
import com.secondbrain.ports.PlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.sound.sampled.SourceDataLine
import kotlin.coroutines.cancellation.CancellationException

/**
 * Speaker playback that can be cut mid-sentence.
 *
 * EC-V3 requires stopping within 100 ms of a barge-in. Four things make that
 * true, and all four are load-bearing:
 *
 *  1. Audio is written in `playback_chunk_ms` slices (40 ms default), so the
 *     longest blocking write we can be stuck inside is ~40 ms.
 *  2. [stop] calls SourceDataLine.flush(), which discards the hardware buffer
 *     instead of letting queued audio drain. Without flush, stop() returns
 *     promptly and you still hear another half-second of speech.
 *  3. The collect lambda THROWS to unwind, which terminates collection and with
 *     it the upstream flow. This is the one two earlier versions of this file
 *     got wrong, and the smoke check caught both:
 *       - `return@collect` on a flag only skips the current chunk. The audio
 *         goes silent but the flow runs to completion, and KokoroTts issues one
 *         HTTP request PER SENTENCE -- barge-in on a six-sentence reply kept
 *         paying for five sentences nobody hears.
 *       - `job.cancel()` alone does nothing either, because cancellation is
 *         cooperative and this lambda is pure blocking I/O with no suspension
 *         point for the cancel to land on.
 *  4. The flag is checked inside the write loop too, so a cut lands mid-chunk
 *     rather than at the next chunk boundary.
 *
 * Cancelling via an independent [Job] rather than the caller's own job means a
 * barge-in is not an error: [play] returns normally, and only a genuine failure
 * propagates.
 */
class JvmAudioPlayback(
    private val config: AudioConfig,
) : AudioPlaybackPort {

    private val log = LoggerFactory.getLogger(JvmAudioPlayback::class.java)

    @Volatile
    override var state: PlaybackState = PlaybackState.IDLE
        private set

    /** Name of the device the last playback used. Feeds the E3 same-device check. */
    @Volatile
    var lastDeviceName: String = "unknown"
        private set

    /** Millis at which the current playback began. Used for the barge-in grace window. */
    @Volatile
    var playbackStartedAtMs: Long = 0L
        private set

    private val cancelled = AtomicBoolean(false)
    private val activeLine = AtomicReference<SourceDataLine?>(null)
    private val collectJob = AtomicReference<Job?>(null)

    override fun devices(): List<AudioDevice> = AudioDevices.playbackDevices()

    override suspend fun play(chunks: Flow<AudioChunk>, deviceId: String?) {
        cancelled.set(false)
        state = PlaybackState.PLAYING
        playbackStartedAtMs = System.currentTimeMillis()

        val lineRef = AtomicReference<SourceDataLine?>(null)
        val formatRef = AtomicReference<AudioFormatSpec?>(null)
        val failure = AtomicReference<Throwable?>(null)

        // An independent Job: cancelling it must not cancel the caller, because a
        // barge-in is a normal outcome rather than a failure.
        val scope = CoroutineScope(currentCoroutineContext() + Job() + Dispatchers.IO)
        val job = scope.launch {
            try {
                chunks.collect { chunk ->
                    // Cancellation in Kotlin is cooperative and this lambda is
                    // pure blocking I/O with no suspension point, so a cancel()
                    // from stop() is never observed here on its own. Throwing is
                    // what actually terminates the collection and, with it, the
                    // upstream TTS flow.
                    currentCoroutineContext().ensureActive()
                    if (cancelled.get()) throw AbortPlayback()

                    val sliceBytes = chunk.format
                        .bytesForDurationMs(config.playbackChunkMs.toLong()).toInt()
                        .let { it - (it % chunk.format.bytesPerFrame) }
                        .coerceAtLeast(chunk.format.bytesPerFrame)

                    // The TTS format is not known until the first chunk arrives --
                    // Kokoro's output sample rate is a spike S1.2 answer, not an
                    // assumption -- so the line is opened lazily from the chunk.
                    val current = lineRef.get()
                    val active: SourceDataLine = if (current == null || formatRef.get() != chunk.format) {
                        current?.let { closeQuietly(it) }
                        val (opened, name) = AudioDevices.openPlayback(
                            chunk.format,
                            config.preferredPlaybackDevice,
                            sliceBytes * 4,
                        )
                        lineRef.set(opened)
                        formatRef.set(chunk.format)
                        lastDeviceName = name
                        activeLine.set(opened)
                        opened.start()
                        log.debug(
                            "playback open: device='{}' {}Hz {}ch slice={}B",
                            name, chunk.format.sampleRateHz, chunk.format.channels, sliceBytes,
                        )
                        opened
                    } else {
                        current
                    }

                    var offset = 0
                    while (offset < chunk.pcm.size) {
                        if (cancelled.get()) throw AbortPlayback()
                        val len = minOf(sliceBytes, chunk.pcm.size - offset)
                        active.write(chunk.pcm, offset, len)
                        offset += len
                    }
                }

                // Only drain if we were not cut. Draining a flushed line is a
                // no-op, but draining a live one is what makes the last word audible.
                if (!cancelled.get()) lineRef.get()?.drain()
            } catch (_: AbortPlayback) {
                // Barge-in. Not an error.
            } catch (_: CancellationException) {
                // External cancellation. Also not an error for the caller.
            } catch (t: Throwable) {
                failure.set(t)
            }
        }
        collectJob.set(job)

        try {
            job.join()
        } finally {
            collectJob.set(null)
            state = PlaybackState.IDLE
            activeLine.set(null)
            lineRef.get()?.let { closeQuietly(it) }
        }

        failure.get()?.let { throw it }
    }

    /**
     * Cuts playback. Must return within 100 ms (EC-V3).
     *
     * Order matters: raise the flag first so any in-flight write loop bails,
     * flush the hardware buffer so nothing already queued is heard, then cancel
     * the collector so the upstream TTS flow stops producing.
     */
    override fun stop() {
        if (state != PlaybackState.PLAYING) return
        state = PlaybackState.STOPPING
        cancelled.set(true)

        activeLine.get()?.let { line ->
            runCatching { line.stop() }
            runCatching { line.flush() }
        }

        // Non-blocking: cancel() returns immediately, so the 100 ms budget holds
        // even if the upstream flow is mid-HTTP-request.
        collectJob.get()?.cancel()
        log.debug("playback stop() issued")
    }

    /** True when [stop] may fire, i.e. past the grace window (E3). */
    fun pastBargeInGrace(graceMs: Long): Boolean =
        state == PlaybackState.PLAYING &&
            System.currentTimeMillis() - playbackStartedAtMs >= graceMs

    /**
     * Thrown to unwind out of [Flow.collect] on barge-in.
     *
     * Not a CancellationException subclass on purpose: this is a deliberate,
     * expected control-flow exit that must never be mistaken for the caller's
     * coroutine being cancelled.
     */
    private class AbortPlayback : RuntimeException(null, null, false, false)

    private fun closeQuietly(line: SourceDataLine) {
        runCatching { line.stop() }
        runCatching { line.flush() }
        runCatching { line.close() }
    }
}
