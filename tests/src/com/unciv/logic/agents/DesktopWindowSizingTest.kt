package com.unciv.logic.agents

import com.unciv.app.desktop.DesktopScreenMode
import com.unciv.models.metadata.GameSettings.WindowState
import org.junit.Assert.assertEquals
import org.junit.Test
import java.awt.Insets
import java.awt.Rectangle

class DesktopWindowSizingTest {
    @Test
    fun `Retina work area stays in logical points and constrains oversized saved windows`() {
        val logical = DesktopScreenMode.logicalWorkArea(Rectangle(0, 0, 1720, 1080), Insets(24, 0, 80, 0))
        assertEquals(Rectangle(0, 24, 1720, 976), logical)
        assertEquals(WindowState(1720, 976), WindowState(3440, 2160).coerceIn(logical))
        assertEquals(WindowState(900, 600), WindowState(900, 600).coerceIn(logical))
    }
}
