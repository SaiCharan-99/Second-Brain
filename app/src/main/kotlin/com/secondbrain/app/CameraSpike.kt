package com.secondbrain.app

import com.github.sarxos.webcam.Webcam
import javax.imageio.ImageIO
import java.io.File

/**
 * D-096's camera-backend compatibility spike — "run it on the target laptop
 * before building UI on it," per the plan's own Stage 2 instruction and this
 * project's own standing rule against designing ahead of what is validated.
 *
 * Not part of the app. Enumerates every camera Sarxos can see, opens the
 * default one, grabs one still frame, writes it next to the working directory
 * so the result can be looked at, and closes the device. Prints exactly what
 * happened either way — a laptop with no camera is a valid, useful spike
 * result, not a failure to hide.
 *
 * `./gradlew.bat :app:cameraSpike`
 */
fun main() {
    println("Enumerating cameras (Sarxos Webcam Capture)...")

    val webcams = try {
        Webcam.getWebcams()
    } catch (e: Exception) {
        println("SPIKE RESULT: enumeration itself threw - ${e::class.simpleName}: ${e.message}")
        return
    }

    if (webcams.isEmpty()) {
        println("SPIKE RESULT: zero cameras found. Either this machine has none, or the driver could not see one that exists.")
        return
    }

    println("Found ${webcams.size} camera(s):")
    webcams.forEach { println("  - ${it.name}") }

    val webcam = Webcam.getDefault()
    if (webcam == null) {
        println("SPIKE RESULT: Webcam.getWebcams() found devices but getDefault() returned null.")
        return
    }

    println("Opening '${webcam.name}'...")
    val opened = try {
        webcam.open()
        true
    } catch (e: Exception) {
        println("SPIKE RESULT: open() threw - ${e::class.simpleName}: ${e.message}")
        false
    }
    if (!opened || !webcam.isOpen) {
        println("SPIKE RESULT: device did not open.")
        return
    }

    try {
        println("Grabbing one frame...")
        val frame = webcam.image
        if (frame == null) {
            println("SPIKE RESULT: opened successfully but getImage() returned null.")
            return
        }
        val out = File("camera-spike-frame.jpg")
        ImageIO.write(frame, "jpg", out)
        println("SPIKE RESULT: SUCCESS. ${frame.width}x${frame.height} frame captured and saved to ${out.absolutePath}")
    } finally {
        webcam.close()
        println("Device closed.")
    }
}
