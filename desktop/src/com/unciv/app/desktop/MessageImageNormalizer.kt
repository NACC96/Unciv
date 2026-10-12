package com.unciv.app.desktop

import com.unciv.logic.agents.MessageImageDraft
import java.awt.Image
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.roundToInt

/** In-memory clipboard normalization. Invoke off the rendering thread. */
object MessageImageNormalizer {
    private const val MAX_SOURCE_PIXELS = 32_000_000L
    private const val MAX_EDGE = 2048
    private const val MAX_BYTES = 4 * 1024 * 1024

    fun normalize(source: Image): MessageImageDraft {
        val sourceWidth = source.getWidth(null)
        val sourceHeight = source.getHeight(null)
        require(sourceWidth > 0 && sourceHeight > 0) { "Clipboard image dimensions are unavailable." }
        require(sourceWidth.toLong() * sourceHeight <= MAX_SOURCE_PIXELS) { "Clipboard image exceeds 32 megapixels." }
        val scale = minOf(1.0, MAX_EDGE.toDouble() / maxOf(sourceWidth, sourceHeight))
        val width = (sourceWidth * scale).roundToInt().coerceAtLeast(1)
        val height = (sourceHeight * scale).roundToInt().coerceAtLeast(1)
        val normalized = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = normalized.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            require(graphics.drawImage(source, 0, 0, width, height, null)) { "Clipboard image could not be read completely." }
        } finally { graphics.dispose() }
        // Explicit memory stream prevents ImageIO's default disk cache from touching any volume.
        val output = object : ByteArrayOutputStream() {
            override fun write(value: Int) {
                require(count < MAX_BYTES) { "Normalized image exceeds the 4 MiB PNG limit." }
                super.write(value)
            }
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                require(count.toLong() + length <= MAX_BYTES) { "Normalized image exceeds the 4 MiB PNG limit." }
                super.write(bytes, offset, length)
            }
        }
        try {
            javax.imageio.stream.MemoryCacheImageOutputStream(output).use { stream ->
                require(ImageIO.write(normalized, "png", stream)) { "PNG image encoding is unavailable." }
            }
            val bytes = output.toByteArray()
            require(bytes.size <= MAX_BYTES) { "Normalized image exceeds the 4 MiB PNG limit." }
            return MessageImageDraft(bytes, "image/png", width, height)
        } catch (ex: javax.imageio.IIOException) {
            throw IllegalArgumentException("Clipboard image could not be encoded within the 4 MiB PNG limit.", ex)
        } finally { normalized.flush() }
    }
}
