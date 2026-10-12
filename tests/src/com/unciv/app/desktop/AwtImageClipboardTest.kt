package com.unciv.app.desktop

import org.junit.Assert.*
import org.junit.Test
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage

class AwtImageClipboardTest {
    @Test fun `image normalization runs on worker and completion posts to rendering thread`() {
        val system = Clipboard("test-only")
        val image = BufferedImage(4, 2, BufferedImage.TYPE_INT_ARGB)
        system.setContents(object : Transferable {
            override fun getTransferDataFlavors() = arrayOf(DataFlavor.imageFlavor)
            override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.imageFlavor
            override fun getTransferData(flavor: DataFlavor): Any = image
        }, null)
        var worker: (() -> Unit)? = null
        var render: (() -> Unit)? = null
        val clipboard = AwtClipboard({ system }, { worker = it }, { render = it })
        assertTrue(clipboard.hasImageContents())
        var completed = false
        clipboard.readImage { draft, error ->
            assertNull(error); assertEquals(4, draft!!.width); completed = true
        }
        assertFalse(completed)
        worker!!()
        assertFalse(completed)
        render!!()
        assertTrue(completed)
    }
    @Test fun `text clipboard remains available and image paste fails without a draft`() {
        val system = Clipboard("test-only")
        system.setContents(StringSelection("Peace"), null)
        val clipboard = AwtClipboard({ system }, { it() }, { it() })
        assertFalse(clipboard.hasImageContents())
        assertTrue(clipboard.hasContents())
        assertEquals("Peace", clipboard.contents)
        clipboard.readImage { draft, error -> assertNull(draft); assertNotNull(error) }
    }
}
