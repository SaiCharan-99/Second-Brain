package com.secondbrain.voice

import com.secondbrain.model.AudioFormatSpec
import com.secondbrain.model.SessionsConfig
import com.secondbrain.model.SttStatus
import com.secondbrain.model.Transcript
import com.secondbrain.model.Utterance
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Persists every utterance to ~/.secondbrain/sessions/<ts>/.
 *
 *   audio.wav        written BEFORE any network call        (EC-V7)
 *   transcript.txt   the text, once STT returns
 *   meta.json        utterance + STT outcome + timings      (E2)
 *
 * R10: "audio is never deleted before its transcript commits."
 *
 * Two things the design documents did not settle, decided here:
 *
 * E1 -- Section 2 says sessions rotate at 30 days; Step 1's build list reads as
 * "delete the WAV once the transcript commits". Both are now expressible via
 * [SessionsConfig], and the default keeps the audio for 30 days. That audio is
 * the only dataset for measuring Gemini's accuracy on real Indian-English and
 * code-switched speech, which is the entire point of Step 1's spike; deleting it
 * immediately would throw away the measurement.
 *
 * E2 -- a failed or empty transcript never "commits", so under a literal reading
 * of R10 its WAV could never be cleaned up and the failure would be invisible.
 * Writing meta.json IS the commit, whatever the status. FAILED and EMPTY audio
 * is always retained regardless of delete_wav_on_commit, because that is exactly
 * the audio worth listening to again.
 */
class SessionStore(
    private val sessionsRoot: Path,
    private val config: SessionsConfig,
) {

    private val log = LoggerFactory.getLogger(SessionStore::class.java)
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    private val stamp = DateTimeFormatter
        .ofPattern("yyyyMMdd-HHmmss-SSS")
        .withZone(ZoneId.systemDefault())

    @Serializable
    data class SessionMeta(
        val utteranceId: String,
        val startedAt: String,
        val zoneId: String,
        val durationMs: Long,
        val peakRmsDbfs: Double,
        val truncatedByCap: Boolean,
        val wavBytes: Long,
        val sttStatus: String,
        val sttModel: String? = null,
        val sttLatencyMs: Long? = null,
        val sttAttempts: Int? = null,
        val sttError: String? = null,
        val transcriptChars: Int = 0,
        val committedAt: String,
        val wavRetained: Boolean,
    )

    /** A recording in progress. Holds an open handle; always [finish] or [abandon]. */
    class Recording internal constructor(
        val utteranceId: String,
        val startedAt: Instant,
        val dir: Path,
        val wavPath: Path,
        internal val format: AudioFormatSpec,
    ) {
        internal var pcmBytes: Long = 0
        internal var peakDbfs: Double = Rms.SILENCE_DBFS
    }

    /**
     * Opens the session directory and writes a placeholder WAV header.
     *
     * Called at capture start, so the file exists on disk before a single byte of
     * audio is buffered in memory. A crash mid-utterance leaves a valid, if
     * short, WAV rather than nothing.
     */
    fun begin(format: AudioFormatSpec = AudioFormatSpec.CAPTURE, now: Instant = Instant.now()): Recording {
        val dir = sessionsRoot.resolve(stamp.format(now))
        Files.createDirectories(dir)
        val wav = dir.resolve("audio.wav")
        Files.write(wav, WavCodec.header(format, 0), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
        return Recording(UUID.randomUUID().toString(), now, dir, wav, format)
    }

    /** Appends captured PCM. Streamed rather than buffered so a 60 s take is bounded. */
    fun append(recording: Recording, pcm: ByteArray) {
        if (pcm.isEmpty()) return
        Files.write(recording.wavPath, pcm, StandardOpenOption.APPEND)
        recording.pcmBytes += pcm.size
        val level = Rms.dbfs(pcm)
        if (level > recording.peakDbfs) recording.peakDbfs = level
    }

    /** Patches the RIFF lengths and returns the finished [Utterance]. */
    fun finishRecording(recording: Recording, truncatedByCap: Boolean): Utterance {
        patchWavLengths(recording)
        return Utterance(
            id = recording.utteranceId,
            startedAt = recording.startedAt,
            zoneId = ZoneId.systemDefault(),
            durationMs = recording.format.durationMsForBytes(recording.pcmBytes),
            wavPath = recording.wavPath,
            peakRmsDbfs = recording.peakDbfs,
            truncatedByCap = truncatedByCap,
        )
    }

    /**
     * Commits the transcript. THIS is the point after which the WAV may be
     * deleted, and not one instruction earlier (R10).
     *
     * Order is deliberate: transcript.txt, then meta.json, then -- only if
     * configured and only on a usable transcript -- the WAV.
     */
    fun commit(
        recording: Recording,
        utterance: Utterance,
        transcript: Transcript,
    ): Path {
        Files.writeString(recording.dir.resolve("transcript.txt"), transcript.text)

        // FAILED / EMPTY audio is always kept: it is the only material for
        // diagnosing why STT went wrong.
        val retainWav = !config.deleteWavOnCommit || transcript.status != SttStatus.OK

        val meta = SessionMeta(
            utteranceId = utterance.id,
            startedAt = utterance.startedAt.toString(),
            zoneId = utterance.zoneId.id,
            durationMs = utterance.durationMs,
            peakRmsDbfs = utterance.peakRmsDbfs,
            truncatedByCap = utterance.truncatedByCap,
            wavBytes = recording.pcmBytes,
            sttStatus = transcript.status.name,
            sttModel = transcript.model,
            sttLatencyMs = transcript.latencyMs,
            sttAttempts = transcript.attempts,
            sttError = transcript.error,
            transcriptChars = transcript.text.length,
            committedAt = Instant.now().toString(),
            wavRetained = retainWav,
        )
        writeAtomic(recording.dir.resolve("meta.json"), json.encodeToString(SessionMeta.serializer(), meta))

        if (!retainWav) {
            runCatching { Files.deleteIfExists(recording.wavPath) }
                .onFailure { log.warn("Could not delete {} after commit: {}", recording.wavPath, it.message) }
        }
        return recording.dir
    }

    /**
     * Records a gate rejection (EC-V1) without a transcript and without an API
     * call. The directory is removed: a 200 ms accidental keypress is not a
     * thought and keeping it would bury the real recordings.
     */
    fun discard(recording: Recording, reason: String) {
        patchWavLengths(recording)
        log.info("Discarded utterance {} ({}). Zero API calls.", recording.utteranceId, reason)
        runCatching {
            Files.deleteIfExists(recording.wavPath)
            Files.deleteIfExists(recording.dir)
        }.onFailure { log.debug("Could not clean up discarded session dir: {}", it.message) }
    }

    /**
     * EC-V7: called when the process is coming down mid-utterance. Leaves the WAV
     * and a marker on disk. Nothing is deleted -- a dropped connection or a kill
     * must not lose a thought.
     */
    fun abandon(recording: Recording, reason: String) {
        patchWavLengths(recording)
        runCatching {
            Files.writeString(
                recording.dir.resolve("INCOMPLETE.txt"),
                "Recording was interrupted before its transcript committed.\n" +
                    "reason: $reason\n" +
                    "utterance: ${recording.utteranceId}\n" +
                    "at: ${Instant.now()}\n" +
                    "The audio is intact and was NOT deleted (EC-V7, R10).\n",
            )
        }
        log.warn("Abandoned utterance {} ({}). Audio retained at {}", recording.utteranceId, reason, recording.wavPath)
    }

    /**
     * Deletes session directories older than `retention_days`.
     *
     * Run at startup rather than on a timer: this is a desktop app that is not
     * always running, and a background sweeper is a thread to get wrong for no
     * gain. Directories with INCOMPLETE.txt and no meta.json are never swept --
     * an uncommitted recording is exactly what R10 protects.
     */
    fun sweep(now: Instant = Instant.now()): Int {
        if (config.retentionDays <= 0) return 0
        if (Files.notExists(sessionsRoot)) return 0

        val cutoff = now.minus(Duration.ofDays(config.retentionDays.toLong()))
        var removed = 0

        Files.newDirectoryStream(sessionsRoot).use { stream ->
            stream.forEach { dir ->
                if (!Files.isDirectory(dir)) return@forEach
                val committed = Files.exists(dir.resolve("meta.json"))
                val incomplete = Files.exists(dir.resolve("INCOMPLETE.txt"))
                if (!committed && incomplete) {
                    log.info("Keeping uncommitted session {} regardless of age (R10).", dir.fileName)
                    return@forEach
                }
                val modified = runCatching { Files.getLastModifiedTime(dir).toInstant() }.getOrNull() ?: return@forEach
                if (modified.isBefore(cutoff)) {
                    runCatching {
                        Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                        removed++
                    }.onFailure { log.warn("Could not sweep {}: {}", dir, it.message) }
                }
            }
        }
        if (removed > 0) {
            log.info("Swept {} session(s) older than {} days.", removed, config.retentionDays)
        }
        return removed
    }

    private fun patchWavLengths(recording: Recording) {
        runCatching {
            val header = WavCodec.header(recording.format, recording.pcmBytes.toInt())
            java.nio.channels.FileChannel
                .open(recording.wavPath, StandardOpenOption.WRITE)
                .use { ch -> ch.write(java.nio.ByteBuffer.wrap(header), 0) }
        }.onFailure { log.warn("Could not patch WAV header for {}: {}", recording.wavPath, it.message) }
    }

    private fun writeAtomic(target: Path, content: String) {
        val tmp = target.resolveSibling(target.fileName.toString() + ".tmp")
        Files.writeString(tmp, content)
        runCatching {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }.onFailure {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
