package com.unciv.app.desktop

import com.unciv.logic.agents.MessageImageDraft
import java.awt.Image
import java.awt.RenderingHints
import java.awt.Transparency
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.imageio.ImageIO
import kotlin.math.roundToInt

/** In-memory clipboard normalization. Invoke off the rendering thread. */
object MessageImageNormalizer {
    private const val MAX_SOURCE_PIXELS = 32_000_000L
    // Conservative cross-provider envelope, including base64 overhead on partner endpoints.
    private const val MAX_EDGE = 2000
    private const val TARGET_BYTES = 3_500_000
    private class ImageBudgetExceeded : IOException()

    fun normalize(source: Image): MessageImageDraft {
        val sourceWidth = source.getWidth(null)
        val sourceHeight = source.getHeight(null)
        require(sourceWidth > 0 && sourceHeight > 0) { "Clipboard image dimensions are unavailable." }
        require(sourceWidth.toLong() * sourceHeight <= MAX_SOURCE_PIXELS) { "Clipboard image exceeds 32 megapixels." }
        var scale = minOf(1.0, MAX_EDGE.toDouble() / maxOf(sourceWidth, sourceHeight))
        val pixelType = if (source is BufferedImage && source.transparency == Transparency.OPAQUE)
            BufferedImage.TYPE_INT_RGB else BufferedImage.TYPE_INT_ARGB
        while (true) {
            val width = (sourceWidth * scale).roundToInt().coerceAtLeast(1)
            val height = (sourceHeight * scale).roundToInt().coerceAtLeast(1)
            val normalized = BufferedImage(width, height, pixelType)
            try {
                val graphics = normalized.createGraphics()
                try {
                    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                    require(graphics.drawImage(source, 0, 0, width, height, null)) { "Clipboard image could not be read completely." }
                } finally { graphics.dispose() }
                val bytes = encodeWithinBudget(normalized)
                if (bytes != null) return MessageImageDraft(bytes, "image/png", width, height)
                check(width > 1 || height > 1) { "Clipboard image could not fit within the PNG limit." }
                scale *= 0.8
            } finally { normalized.flush() }
        }
    }

    private fun encodeWithinBudget(image: BufferedImage): ByteArray? {
        val output = object : ByteArrayOutputStream() {
            override fun write(value: Int) {
                if (count >= TARGET_BYTES) throw ImageBudgetExceeded()
                super.write(value)
            }
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                if (count.toLong() + length > TARGET_BYTES) throw ImageBudgetExceeded()
                super.write(bytes, offset, length)
            }
        }
        try {
            // Explicit memory stream prevents ImageIO's default disk cache from touching any volume.
            javax.imageio.stream.MemoryCacheImageOutputStream(output).use { stream ->
                require(ImageIO.write(image, "png", stream)) { "PNG image encoding is unavailable." }
            }
            return output.toByteArray()
        } catch (ex: IOException) {
            if (generateSequence<Throwable>(ex) { it.cause }.any { it is ImageBudgetExceeded }) return null
            throw IllegalArgumentException("Clipboard image could not be encoded.", ex)
        }
    }
}
