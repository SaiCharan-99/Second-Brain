package com.secondbrain.model

import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId

/** Where a captured thought came from. Mirrors NoteDraft.source (§3). */
enum class NoteSource { VOICE, TEXT, IMAGE }

/**
 * One recording. Created the instant capture starts, before a single byte of
 * audio exists, because [startedAt] is load-bearing.
 *
 * EC-C2: "tomorrow" spoken at 23:58 must resolve against the timestamp of the
 * *utterance*, not of the API call that eventually processes it. The calendar
 * workflow is Step 6, but the field is free today and a painful retrofit later,
 * so it is captured from Step 1.
 */
data class Utterance(
    val id: String,
    /** Wall-clock instant at which capture began. Never the API-call time. */
    val startedAt: Instant,
    /** Zone the utterance was spoken in. Stored as an ID, never a fixed offset (EC-C3). */
    val zoneId: ZoneId,
    val durationMs: Long,
    /** Path to the persisted WAV. Written before any network call (EC-V7). */
    val wavPath: Path,
    /** Peak RMS over the recording, dBFS. Feeds the EC-V1 pre-flight check. */
    val peakRmsDbfs: Double,
    val source: NoteSource = NoteSource.VOICE,
    /** True when capture was cut short by the hard duration cap (EC-V6 / E6). */
    val truncatedByCap: Boolean = false,
)
