package com.unciv.ui.components

import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.scenes.scene2d.Group
import com.unciv.testing.GdxTestRunner
import com.unciv.ui.components.widgets.ZoomableScrollPane
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises real Scene2D layout without a native window or provider run. */
@RunWith(GdxTestRunner::class)
class ZoomableScrollPaneSnapshotTest {
    private fun content(width: Float = 1200f, height: Float = 900f) = Group().apply {
        setSize(width, height)
    }

    private fun pane() = ZoomableScrollPane().apply {
        actor = content()
        setSize(800f, 600f)
        layout()
        scrollTo(650f, 350f, immediately = true)
    }

    @Test
    fun `replacing map content keeps a landmark at the same screen position`() {
        val pane = pane()
        pane.zoom(1.6f)
        pane.layout()
        val old = pane.actor!!
        val oldX = old.x
        val oldY = old.y
        val oldMaxX = pane.maxX
        val oldMaxY = pane.maxY

        pane.actor = content()
        pane.validate()

        Assert.assertEquals("Replacement must retain map padding at the current zoom", oldX, pane.actor!!.x, 0.01f)
        Assert.assertEquals("Replacement must retain vertical map padding", oldY, pane.actor!!.y, 0.01f)
        Assert.assertEquals(oldMaxX, pane.maxX, 0.01f)
        Assert.assertEquals(oldMaxY, pane.maxY, 0.01f)
        Assert.assertEquals(650f, pane.scrollX, 0.01f)
        Assert.assertEquals(350f, pane.scrollY, 0.01f)
    }

    @Test
    fun `replacement updates the scroll range when map dimensions change`() {
        val pane = pane()
        val oldMaxX = pane.maxX
        val oldMaxY = pane.maxY

        pane.actor = content(1500f, 1100f)
        pane.validate()

        Assert.assertEquals("New tiles must be reachable horizontally", oldMaxX + 300f, pane.maxX, 0.01f)
        Assert.assertEquals("New tiles must be reachable vertically", oldMaxY + 200f, pane.maxY, 0.01f)
    }

    @Test
    fun `replacement culling covers the current panned viewport`() {
        val pane = pane()
        val expected = Rectangle().also { pane.getViewport(it) }
        val replacement = content()
        pane.actor = replacement
        pane.validate()

        Assert.assertEquals("Replacement must receive viewport culling before its first draw", expected, replacement.cullingArea)
    }
}
