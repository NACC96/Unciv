package com.unciv.app.desktop

import org.junit.Assert.*
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

class MessageImageNormalizerTest {
    @Test fun `normalization preserves aspect ratio and encodes bounded PNG in memory`() {
        val draft = MessageImageNormalizer.normalize(BufferedImage(3000, 1500, BufferedImage.TYPE_INT_ARGB))
        assertEquals("image/png", draft.mimeType)
        assertEquals(2000, draft.width)
        assertEquals(1000, draft.height)
        assertTrue(draft.bytes.size <= 4 * 1024 * 1024)
        val decoded = ImageIO.read(ByteArrayInputStream(draft.bytes))
        assertEquals(2000, decoded.width)
        assertEquals(1000, decoded.height)
    }
    @Test fun `source image over 32 megapixels rejected before drawing`() {
        val image = object : java.awt.Image() {
            override fun getWidth(observer: java.awt.image.ImageObserver?) = 8000
            override fun getHeight(observer: java.awt.image.ImageObserver?) = 5000
            override fun getSource(): java.awt.image.ImageProducer = error("Must reject dimensions first")
            override fun getGraphics(): java.awt.Graphics = error("Must reject dimensions first")
            override fun getProperty(name: String?, observer: java.awt.image.ImageObserver?): Any = error("Not needed")
        }
        assertThrows(IllegalArgumentException::class.java) { MessageImageNormalizer.normalize(image) }
    }
    @Test fun `high entropy PNG shrinks until it fits the provider envelope`() {
        val image = BufferedImage(2048, 2048, BufferedImage.TYPE_INT_ARGB)
        val random = java.util.Random(41)
        for (y in 0 until image.height) for (x in 0 until image.width) image.setRGB(x, y, random.nextInt())
        val draft = MessageImageNormalizer.normalize(image)
        assertTrue(draft.width < 2000)
        assertEquals(draft.width, draft.height)
        assertTrue(draft.bytes.size <= 3_500_000)
        assertTrue(java.util.Base64.getEncoder().encodeToString(draft.bytes).length < 5_000_000)
        val decoded = ImageIO.read(ByteArrayInputStream(draft.bytes))
        assertEquals(draft.width, decoded.width)
        assertEquals(draft.height, decoded.height)
    }
}
