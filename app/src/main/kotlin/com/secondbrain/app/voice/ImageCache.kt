package com.secondbrain.app.voice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.awt.image.BufferedImage
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

/**
 * Stage 4 (D-098): product thumbnails for [ShoppingComparisonWindow] and the
 * Saved Cart screen. Deliberately the plainest possible loader — no new
 * external image-loading library, since a comparison table needs "small
 * thumbnail, loads without freezing the window, never crashes on a bad URL",
 * not a caching pipeline. In-memory only, process-lifetime — a restart
 * re-fetches from the CDN URL, which is fine since nothing here depends on it
 * staying resident.
 */
object ImageCache {
    private val log = LoggerFactory.getLogger(ImageCache::class.java)
    private val cache = ConcurrentHashMap<String, BufferedImage>()

    /** Off the calling thread always — a `Composable`'s `LaunchedEffect` is the intended caller. Null on any failure, never throws. */
    suspend fun load(url: String): BufferedImage? {
        cache[url]?.let { return it }
        return withContext(Dispatchers.IO) {
            val image = runCatching { ImageIO.read(URI(url).toURL()) }.getOrElse {
                log.debug("Thumbnail load failed for {}: {}", url, it.message)
                null
            }
            if (image != null) cache[url] = image
            image
        }
    }
}
