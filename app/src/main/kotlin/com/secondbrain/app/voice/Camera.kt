package com.secondbrain.app.voice

import com.github.sarxos.webcam.Webcam
import org.slf4j.LoggerFactory
import java.awt.image.BufferedImage
import java.io.IOException

/**
 * D-096: a thin wrapper over Sarxos Webcam Capture, confirmed against a real
 * device on the target laptop (`:app:cameraSpike`, D-096's own decision entry
 * has the measured result — one camera, `HI556 0`, 640×360, opened and closed
 * cleanly). No `CameraPort` in `:ports`: [ImageIntake] already established
 * that OS/hardware-level image access lives directly in `:app` with no cross-
 * module interface, since nothing outside `:app` ever needs to call it — this
 * follows the same precedent rather than inventing a new one.
 *
 * One instance per open/close cycle, not reused — [CameraWindow] constructs a
 * fresh [Camera] each time it opens and lets it go out of scope on close,
 * which is simpler than a shared singleton and matches how briefly this is
 * actually held open (one capture session).
 */
class Camera : AutoCloseable {

    private val log = LoggerFactory.getLogger(Camera::class.java)

    @Volatile private var webcam: Webcam? = null

    sealed interface OpenResult {
        data class Opened(val name: String) : OpenResult

        /** The spike's own confirmed case: zero devices, or the platform driver saw none. */
        data object NoCamera : OpenResult

        /** Another process (or another window in this app) already has it open. */
        data class Busy(val reason: String) : OpenResult

        data class Failed(val reason: String) : OpenResult
    }

    /** Every camera Sarxos can currently see, for the selector the plan asks for "when multiple cameras exist". */
    fun listCameras(): List<String> = runCatching { Webcam.getWebcams().map { it.name } }.getOrDefault(emptyList())

    /**
     * Opens a camera by name (from [listCameras]), or the first available one
     * if [name] is null. Safe to call from a Compose side-effect — Sarxos's
     * own `open()` is itself blocking, so the caller is expected to run this
     * off the UI thread (see `CameraWindow`'s `LaunchedEffect`).
     */
    fun open(name: String? = null): OpenResult {
        val cameras = runCatching { Webcam.getWebcams() }.getOrElse {
            log.warn("Camera enumeration failed: {}", it.message)
            return OpenResult.Failed(it.message ?: "could not list cameras")
        }
        if (cameras.isEmpty()) return OpenResult.NoCamera

        val target = (if (name != null) cameras.firstOrNull { it.name == name } else cameras.firstOrNull())
            ?: return OpenResult.NoCamera

        return try {
            target.open()
            if (!target.isOpen) return OpenResult.Failed("device did not report open")
            webcam = target
            log.info("Camera opened: {}", target.name)
            OpenResult.Opened(target.name)
        } catch (e: IOException) {
            // Sarxos surfaces "device busy" as an IOException from the native
            // layer with no dedicated exception type - string-matched here
            // because there is nothing more specific to catch.
            val busy = e.message?.contains("busy", ignoreCase = true) == true
            log.warn("Camera open failed ({}): {}", if (busy) "busy" else "error", e.message)
            if (busy) OpenResult.Busy(e.message ?: "camera busy") else OpenResult.Failed(e.message ?: "unknown error")
        } catch (e: Exception) {
            log.warn("Camera open threw unexpectedly: {}", e.message)
            OpenResult.Failed("${e::class.simpleName}: ${e.message}")
        }
    }

    val isOpen: Boolean get() = webcam?.isOpen == true

    /** The underlying `Webcam`, for `WebcamPanel`'s live preview. Null until [open] succeeds. */
    fun handle(): Webcam? = webcam

    /** One still frame. Null if the device stopped responding mid-session (unplugged, driver crash). */
    fun captureFrame(): BufferedImage? {
        val cam = webcam ?: return null
        return try {
            cam.image
        } catch (e: Exception) {
            log.warn("Frame capture failed: {}", e.message)
            null
        }
    }

    override fun close() {
        val cam = webcam ?: return
        webcam = null
        runCatching { cam.close() }.onFailure { log.warn("Camera close threw: {}", it.message) }
    }
}
