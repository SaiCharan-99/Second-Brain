package com.secondbrain.app.voice

import com.secondbrain.ports.LlmBlock
import java.awt.Color
import java.awt.FileDialog
import java.awt.Frame
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.Base64
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * Step 8 / WF-6: gets a photo from disk onto the wire as an [LlmBlock.Image].
 *
 * Two halves, deliberately separated. [pickFile] opens a native OS dialog and
 * is therefore untestable and un-unit-testable by construction — CLAUDE.md's
 * `:app` bar ("Manual. Compose UI tests are not worth the time") applies to
 * this half. [encodeForVision] and the two functions under it are pure
 * `BufferedImage -> BufferedImage -> ByteArray`, which is exactly the shape
 * [TreeFlatten]/[NoteMarkdown] already carve out of `:app` for a real unit
 * test — [ImageIntakeTest] exercises them against an in-memory image, no file
 * or dialog involved.
 *
 * ### Why resize at all
 *
 * A phone photo of a grocery list easily runs 4000×3000px at several
 * megabytes. Anthropic's vision guidance is that resolution above roughly
 * 1568px on the long edge buys no reading accuracy and only costs more
 * tokens and more upload time — Claude downsamples internally past that
 * point regardless. So this resizes to that ceiling itself: same accuracy,
 * a fraction of the bytes, and a base64 payload nowhere near the request
 * size limit that never needs its own guard as a result.
 *
 * ### Why JPEG, always
 *
 * The four inputs this accepts (jpg/png/gif/bmp/webp, via [pickFile]'s
 * filter) collapse to one output format. A photo has no need for PNG's
 * lossless guarantee or an alpha channel Claude would never use, and one
 * output format is one code path through [ClaudeClient.sdkMediaType] to get
 * right rather than five.
 */
object ImageIntake {

    private const val MAX_DIMENSION = 1568
    private const val JPEG_QUALITY = 0.85f
    private val EXTENSIONS = listOf(".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp")

    /**
     * Opens a native "open file" dialog restricted to image files. Blocks the
     * calling thread until the user picks a file or cancels; call this off
     * the Compose/UI dispatcher (`Dispatchers.IO`), same discipline as any
     * other blocking call in this codebase.
     *
     * @return null on cancel — indistinguishable from "no file exists", which
     *   is the correct behaviour for a cancelled dialog.
     */
    fun pickFile(parent: Frame? = null): Path? {
        val dialog = FileDialog(parent, "Attach a photo", FileDialog.LOAD)
        dialog.setFilenameFilter { _, name -> EXTENSIONS.any { name.lowercase().endsWith(it) } }
        dialog.isVisible = true // blocks here until the dialog closes
        val dir = dialog.directory ?: return null
        val file = dialog.file ?: return null
        return Path.of(dir, file)
    }

    /**
     * Reads, downscales and JPEG-encodes [path] for a Claude vision request.
     *
     * @throws IllegalArgumentException if [path] is not a format `ImageIO`
     *   can decode. Caller's responsibility to catch and speak this — see
     *   [VoiceController.attachImage].
     */
    fun encodeForVision(path: Path): LlmBlock.Image {
        val original = ImageIO.read(path.toFile())
            ?: throw IllegalArgumentException("Not a readable image: ${path.fileName}")
        val resized = downscale(original, MAX_DIMENSION)
        val bytes = toJpegBytes(resized, JPEG_QUALITY)
        return LlmBlock.Image(Base64.getEncoder().encodeToString(bytes), "image/jpeg")
    }

    /** Scales so the longer edge is at most [maxDimension]. A no-op if already smaller — never upscales. */
    internal fun downscale(src: BufferedImage, maxDimension: Int): BufferedImage {
        val longEdge = maxOf(src.width, src.height)
        if (longEdge <= maxDimension) return src

        val scale = maxDimension.toDouble() / longEdge
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)

        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        // White background: TYPE_INT_RGB has no alpha, so a transparent PNG's
        // cutout would otherwise render as whatever garbage was in the buffer.
        g.color = Color.WHITE
        g.fillRect(0, 0, w, h)
        g.drawImage(src, 0, 0, w, h, null)
        g.dispose()
        return out
    }

    internal fun toJpegBytes(image: BufferedImage, quality: Float): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpg").next()
        val params = writer.defaultWriteParam.apply {
            compressionMode = ImageWriteParam.MODE_EXPLICIT
            compressionQuality = quality
        }
        val out = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(out).use { ios ->
            writer.output = ios
            writer.write(null, IIOImage(image, null, null), params)
        }
        writer.dispose()
        return out.toByteArray()
    }
}
