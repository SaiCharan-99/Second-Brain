package com.secondbrain.voice

import com.secondbrain.model.AudioFormatSpec
import com.secondbrain.model.VoiceGateConfig
import com.secondbrain.ports.AudioCapturePort
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * Measures the room noise floor so EC-V1 has a number to compare against.
 *
 * EC-V1 says an utterance must have "RMS energy above a calibrated floor" but
 * nothing in the design says how that floor is derived, when, or where it lives
 * (E4). The answers here:
 *
 *  - Derived by sampling `calibration_ms` of room audio at startup and taking
 *    the median chunk RMS. Median, not mean, so one cough during calibration
 *    does not deafen the gate for the whole session.
 *  - The gate threshold is floor + `energy_margin_db`.
 *  - Cached in ~/.secondbrain/calibration.json, NOT written back into
 *    config.toml. The app must never rewrite a file the user hand-edits and
 *    which holds their API keys.
 *  - Re-measured when the capture device changes, because a laptop mic and a
 *    headset mic have completely different floors.
 */
class NoiseFloorCalibrator(
    private val capture: AudioCapturePort,
    private val gateConfig: VoiceGateConfig,
    private val calibrationFile: Path,
) {

    private val log = LoggerFactory.getLogger(NoiseFloorCalibrator::class.java)
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    @Serializable
    data class Calibration(
        val deviceName: String,
        val noiseFloorDbfs: Double,
        val thresholdDbfs: Double,
        val measuredAt: String,
        val sampleCount: Int,
    )

    /** Loads a cached calibration for [deviceName], or null if absent or stale. */
    fun cached(deviceName: String): Calibration? {
        if (Files.notExists(calibrationFile)) return null
        return runCatching {
            json.decodeFromString(Calibration.serializer(), Files.readString(calibrationFile))
        }.getOrElse {
            log.warn("calibration.json unreadable ({}); re-measuring.", it.message)
            null
        }?.takeIf { it.deviceName == deviceName }
    }

    /**
     * Measures the floor, writes the cache and returns it.
     *
     * Costs `calibration_ms` at startup (500 ms by default) and zero API calls.
     */
    suspend fun measure(deviceName: String, format: AudioFormatSpec): Calibration {
        val chunkMs = 50L
        val chunksNeeded = (gateConfig.calibrationMs / chunkMs).toInt().coerceAtLeast(3)

        val chunks = capture.capture().take(chunksNeeded).toList()
        val levels = chunks.map { Rms.dbfs(it) }.sorted()
        val floor = if (levels.isEmpty()) Rms.SILENCE_DBFS else levels[levels.size / 2]

        val calibration = Calibration(
            deviceName = deviceName,
            noiseFloorDbfs = floor,
            thresholdDbfs = floor + gateConfig.energyMarginDb,
            measuredAt = Instant.now().toString(),
            sampleCount = levels.size,
        )

        runCatching {
            Files.createDirectories(calibrationFile.parent)
            Files.writeString(calibrationFile, json.encodeToString(Calibration.serializer(), calibration))
        }.onFailure { log.warn("Could not persist calibration to {}: {}", calibrationFile, it.message) }

        log.info(
            "Noise floor on '{}': {} dBFS over {} samples. Gate threshold {} dBFS (margin {} dB).",
            deviceName,
            "%.1f".format(floor),
            levels.size,
            "%.1f".format(calibration.thresholdDbfs),
            gateConfig.energyMarginDb,
        )
        return calibration
    }

    /** Cached value if it matches the device, otherwise a fresh measurement. */
    suspend fun calibrate(deviceName: String, format: AudioFormatSpec): Calibration =
        cached(deviceName)?.also {
            log.info(
                "Reusing cached noise floor for '{}': {} dBFS, threshold {} dBFS (measured {}).",
                it.deviceName, "%.1f".format(it.noiseFloorDbfs), "%.1f".format(it.thresholdDbfs), it.measuredAt,
            )
        } ?: measure(deviceName, format)
}
