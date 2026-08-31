package com.secondbrain.voice

import com.secondbrain.model.AudioFormatSpec
import com.secondbrain.model.SessionsConfig
import com.secondbrain.model.SttStatus
import com.secondbrain.model.Transcript
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/**
 * R10 ("audio is never deleted before its transcript commits"), EC-V7, and the
 * two policy gaps resolved during planning: E1 (retention) and E2 (a failed
 * transcript never commits).
 */
class SessionStoreTest {

    private val format = AudioFormatSpec.CAPTURE

    private fun store(dir: Path, config: SessionsConfig = SessionsConfig()) =
        SessionStore(dir, config)

    private fun speech(bytes: Int = 32_000) = ByteArray(bytes) { ((it * 37) % 200 - 100).toByte() }

    @Test
    @DisplayName("EC-V7 the WAV exists on disk before any network call could happen")
    fun `wav exists at begin`(@TempDir dir: Path) {
        val s = store(dir)
        val rec = s.begin(format)
        assertTrue(Files.exists(rec.wavPath), "audio.wav must exist the moment capture starts")
        assertEquals(WavCodec.HEADER_BYTES.toLong(), Files.size(rec.wavPath))
    }

    @Test
    fun `appended audio produces a parseable WAV with correct lengths`(@TempDir dir: Path) {
        val s = store(dir)
        val rec = s.begin(format)
        val payload = speech(6_400)
        s.append(rec, payload)
        val utterance = s.finishRecording(rec, truncatedByCap = false)

        val parsed = WavCodec.parse(Files.readAllBytes(rec.wavPath))
        assertEquals(payload.size, parsed.pcm.size)
        assertEquals(200L, utterance.durationMs)
        assertTrue(utterance.peakRmsDbfs > Rms.SILENCE_DBFS)
    }

    @Test
    @DisplayName("R10 by default the WAV survives commit, for 30 days (E1)")
    fun `wav retained by default`(@TempDir dir: Path) {
        val s = store(dir)
        val rec = s.begin(format)
        s.append(rec, speech())
        val utterance = s.finishRecording(rec, false)

        s.commit(rec, utterance, ok(utterance.id))

        assertTrue(Files.exists(rec.wavPath), "default policy keeps the audio (E1)")
        assertTrue(Files.exists(rec.dir.resolve("transcript.txt")))
        assertTrue(Files.exists(rec.dir.resolve("meta.json")))
    }

    @Test
    @DisplayName("E1 delete_wav_on_commit=true deletes only AFTER the transcript lands")
    fun `wav deleted when configured`(@TempDir dir: Path) {
        val s = store(dir, SessionsConfig(deleteWavOnCommit = true))
        val rec = s.begin(format)
        s.append(rec, speech())
        val utterance = s.finishRecording(rec, false)

        s.commit(rec, utterance, ok(utterance.id))

        assertFalse(Files.exists(rec.wavPath))
        assertTrue(Files.exists(rec.dir.resolve("transcript.txt")), "the transcript must survive")
        assertTrue(Files.exists(rec.dir.resolve("meta.json")))
    }

    @Test
    @DisplayName("E2 a FAILED transcript still commits, and its audio is always kept")
    fun `failed transcript retains audio even when deletion is configured`(@TempDir dir: Path) {
        val s = store(dir, SessionsConfig(deleteWavOnCommit = true))
        val rec = s.begin(format)
        s.append(rec, speech())
        val utterance = s.finishRecording(rec, false)

        s.commit(rec, utterance, Transcript.failed(utterance.id, "gemini", 900, 3, "connection reset"))

        assertTrue(Files.exists(rec.wavPath), "FAILED audio is the only material for diagnosing STT")
        val meta = Files.readString(rec.dir.resolve("meta.json"))
        assertTrue(meta.contains("\"sttStatus\": \"FAILED\""), meta)
        assertTrue(meta.contains("connection reset"), meta)
        assertTrue(meta.contains("\"wavRetained\": true"), meta)
    }

    @Test
    @DisplayName("E2 an EMPTY transcript commits with a status, so it is countable")
    fun `empty transcript commits`(@TempDir dir: Path) {
        val s = store(dir)
        val rec = s.begin(format)
        s.append(rec, speech())
        val utterance = s.finishRecording(rec, false)

        s.commit(rec, utterance, Transcript.empty(utterance.id, "gemini", 700, 1))

        val meta = Files.readString(rec.dir.resolve("meta.json"))
        assertTrue(meta.contains("\"sttStatus\": \"EMPTY\""), meta)
        assertEquals("", Files.readString(rec.dir.resolve("transcript.txt")))
    }

    @Test
    @DisplayName("EC-V1 a gate rejection leaves nothing behind and cost nothing")
    fun `discard removes the session directory`(@TempDir dir: Path) {
        val s = store(dir)
        val rec = s.begin(format)
        s.append(rec, ByteArray(3_200))

        s.discard(rec, "TOO_SHORT: 100ms < 400ms minimum")

        assertFalse(Files.exists(rec.wavPath))
        assertFalse(Files.exists(rec.dir))
    }

    @Test
    @DisplayName("EC-V7 abandon keeps the audio and marks it incomplete")
    fun `abandon retains everything`(@TempDir dir: Path) {
        val s = store(dir)
        val rec = s.begin(format)
        s.append(rec, speech())

        s.abandon(rec, "user quit mid-recording")

        assertTrue(Files.exists(rec.wavPath), "a dropped connection must not lose a thought")
        assertTrue(Files.exists(rec.dir.resolve("INCOMPLETE.txt")))
        assertFalse(Files.exists(rec.dir.resolve("meta.json")), "no meta.json means no commit happened")
    }

    @Test
    @DisplayName("E1 sweep removes committed sessions past the retention window")
    fun `sweep removes old committed sessions`(@TempDir dir: Path) {
        val s = store(dir, SessionsConfig(retentionDays = 30))

        val old = dir.resolve("20200101-120000-000")
        Files.createDirectories(old)
        Files.writeString(old.resolve("meta.json"), "{}")
        Files.writeString(old.resolve("transcript.txt"), "old thought")
        Files.setLastModifiedTime(
            old,
            java.nio.file.attribute.FileTime.from(Instant.now().minus(Duration.ofDays(60))),
        )

        val recent = dir.resolve("20991231-120000-000")
        Files.createDirectories(recent)
        Files.writeString(recent.resolve("meta.json"), "{}")

        assertEquals(1, s.sweep())
        assertFalse(Files.exists(old))
        assertTrue(Files.exists(recent))
    }

    @Test
    @DisplayName("R10 sweep never removes an uncommitted recording, whatever its age")
    fun `sweep spares incomplete sessions`(@TempDir dir: Path) {
        val s = store(dir, SessionsConfig(retentionDays = 1))

        val orphan = dir.resolve("20200101-120000-000")
        Files.createDirectories(orphan)
        Files.writeString(orphan.resolve("INCOMPLETE.txt"), "interrupted")
        Files.write(orphan.resolve("audio.wav"), WavCodec.wrap(format, speech(1_600)))
        Files.setLastModifiedTime(
            orphan,
            java.nio.file.attribute.FileTime.from(Instant.now().minus(Duration.ofDays(400))),
        )

        assertEquals(0, s.sweep())
        assertTrue(Files.exists(orphan.resolve("audio.wav")), "R10: audio outlives retention until it commits")
    }

    @Test
    fun `retention of zero disables sweeping entirely`(@TempDir dir: Path) {
        val s = store(dir, SessionsConfig(retentionDays = 0))
        val old = dir.resolve("20200101-120000-000")
        Files.createDirectories(old)
        Files.writeString(old.resolve("meta.json"), "{}")
        Files.setLastModifiedTime(old, java.nio.file.attribute.FileTime.from(Instant.EPOCH))

        assertEquals(0, s.sweep())
        assertTrue(Files.exists(old))
    }

    @Test
    @DisplayName("EC-C2 the utterance timestamp is capture-start, not processing time")
    fun `utterance carries the recording start instant`(@TempDir dir: Path) {
        val s = store(dir)
        val at = Instant.parse("2026-09-01T23:58:00Z")
        val rec = s.begin(format, now = at)
        s.append(rec, speech())
        val utterance = s.finishRecording(rec, false)

        assertEquals(at, utterance.startedAt)
    }

    private fun ok(id: String) = Transcript(id, "offline inference is the moat", SttStatus.OK, "gemini", 820, 1)
}
