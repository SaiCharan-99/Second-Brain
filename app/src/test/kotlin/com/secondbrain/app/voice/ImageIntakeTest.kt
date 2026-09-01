package com.secondbrain.app.voice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.util.Base64
import javax.imageio.ImageIO

/**
 * The pure half of [ImageIntake] — [ImageIntake.downscale] and
 * [ImageIntake.toJpegBytes] — against in-memory images. [ImageIntake.pickFile]
 * opens a native OS dialog and is exercised manually, per CLAUDE.md's `:app`
 * testing bar.
 */
class ImageIntakeTest {

    private fun solidImage(width: Int, height: Int, color: Color = Color.RED): BufferedImage {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = color
        g.fillRect(0, 0, width, height)
        g.dispose()
        return img
    }

    // ── downscale ────────────────────────────────────────────────────────────

    @Test
    fun `an image already under the cap is returned unchanged, never upscaled`() {
        val small = solidImage(400, 300)
        val result = ImageIntake.downscale(small, 1568)
        assertEquals(400, result.width)
        assertEquals(300, result.height)
    }

    @Test
    fun `a landscape image over the cap is scaled so the long edge hits it exactly`() {
        val large = solidImage(4000, 3000) // 4:3
        val result = ImageIntake.downscale(large, 1568)
        assertEquals(1568, result.width)
        assertEquals(1176, result.height) // 3000 * (1568/4000), rounded down
    }

    @Test
    fun `a portrait image scales on height, the actual long edge`() {
        val tall = solidImage(1200, 5000)
        val result = ImageIntake.downscale(tall, 1568)
        assertEquals(1568, result.height)
        assertTrue(result.width < 1200, "width should have scaled down with height")
    }

    @Test
    fun `a square image right at the cap is unchanged`() {
        val exact = solidImage(1568, 1568)
        val result = ImageIntake.downscale(exact, 1568)
        assertEquals(1568, result.width)
        assertEquals(1568, result.height)
    }

    // ── toJpegBytes ──────────────────────────────────────────────────────────

    @Test
    fun `encoded bytes decode back to a JPEG of the same dimensions`() {
        val img = solidImage(200, 150, Color.BLUE)
        val bytes = ImageIntake.toJpegBytes(img, 0.85f)

        assertTrue(bytes.isNotEmpty())
        // JPEG magic bytes (SOI marker), so this fails loudly if the format
        // ever silently changes rather than producing an unreadable string.
        assertEquals(0xFF.toByte(), bytes[0])
        assertEquals(0xD8.toByte(), bytes[1])

        val decoded = ImageIO.read(bytes.inputStream())
        assertEquals(200, decoded.width)
        assertEquals(150, decoded.height)
    }

    @Test
    fun `a lower quality produces fewer bytes for the same image`() {
        val img = solidImage(600, 600, Color.GREEN)
        val high = ImageIntake.toJpegBytes(img, 0.95f)
        val low = ImageIntake.toJpegBytes(img, 0.10f)
        assertTrue(low.size < high.size, "lower JPEG quality should compress smaller")
    }

    // ── end to end (minus the file-picker dialog) ───────────────────────────

    @Test
    fun `encoding a large image produces a valid, appropriately capped base64 block`() {
        // Simulates a phone photo: bigger than the 1568px ceiling on both axes.
        val photo = solidImage(3024, 4032)
        val bytes = ImageIntake.toJpegBytes(ImageIntake.downscale(photo, 1568), 0.85f)
        val base64 = Base64.getEncoder().encodeToString(bytes)

        assertTrue(Base64.getDecoder().decode(base64).contentEquals(bytes), "must round-trip through base64")
        // Comfortably under any request-size concern - the whole point of
        // downscaling before this ever reaches ClaudeClient.
        assertTrue(bytes.size < 2_000_000, "a downscaled solid-color JPEG should be well under 2MB, was ${bytes.size}")

        val decoded = ImageIO.read(bytes.inputStream())
        assertTrue(maxOf(decoded.width, decoded.height) <= 1568)
    }
}
