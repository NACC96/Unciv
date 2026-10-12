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
        assertEquals(2048, draft.width)
        assertEquals(1024, draft.height)
        assertTrue(draft.bytes.size <= 4 * 1024 * 1024)
        val decoded = ImageIO.read(ByteArrayInputStream(draft.bytes))
        assertEquals(2048, decoded.width)
        assertEquals(1024, decoded.height)
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
    @Test fun `encoded PNG above byte limit is rejected`() {
        val image = BufferedImage(2048, 2048, BufferedImage.TYPE_INT_ARGB)
        val random = java.util.Random(41)
        for (y in 0 until image.height) for (x in 0 until image.width) image.setRGB(x, y, random.nextInt())
        assertThrows(IllegalArgumentException::class.java) { MessageImageNormalizer.normalize(image) }
    }
}
