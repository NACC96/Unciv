package com.unciv.ui.components

import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.actions.Actions
import com.unciv.testing.BaseTestRunner
import com.unciv.ui.components.tilegroups.layers.CityButtonMapLayer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito

@RunWith(BaseTestRunner::class)
class CityButtonMapLayerCullingTest {
    private class Annotation : Actor() {
        var draws = 0
        override fun draw(batch: Batch?, parentAlpha: Float) { draws++ }
    }

    private fun batch() = Mockito.mock(Batch::class.java).also {
        Mockito.`when`(it.transformMatrix).thenReturn(Matrix4())
    }

    private fun layer(area: Rectangle) = CityButtonMapLayer(1, actable = true).apply {
        cullingArea = area
    }

    private fun wrapper(child: Actor, x: Float, y: Float) = Group().apply {
        isTransform = false
        setPosition(x, y)
        addActor(child)
    }

    @Test fun `the full annotation stack can cross the bottom edge without changing the supplied area`() {
        // TileGroupMap already supplies its border allowance. The 104-unit stack must
        // still draw when its point-sized wrapper sits below that allowance.
        val area = Rectangle(-75f, -75f, 650f, 650f)
        val mapLayer = layer(area)
        val annotation = Annotation().apply { setSize(366f, 104f) }
        val group = wrapper(annotation, 200f, -96f)
        mapLayer.addActor(group)

        mapLayer.draw(batch(), 1f)

        assertEquals(1, annotation.draws)
        assertSame(area, mapLayer.cullingArea)
        assertEquals(Rectangle(-75f, -75f, 650f, 650f), area)
        assertEquals(0f, group.width, 0f)
        assertEquals(0f, group.height, 0f)
        assertEquals(-96f, group.y, 0f)
    }

    @Test fun `a stack entirely beyond the supplied area remains culled`() {
        val mapLayer = layer(Rectangle(-75f, -75f, 650f, 650f))
        val annotation = Annotation().apply { setSize(366f, 104f) }
        mapLayer.addActor(wrapper(annotation, 200f, -180f))

        mapLayer.draw(batch(), 1f)

        assertEquals(0, annotation.draws)
    }

    @Test fun `zoom scaling uses the wrapper origin and negative child offsets`() {
        val mapLayer = layer(Rectangle(0f, 0f, 200f, 200f))
        val annotation = Annotation().apply {
            setPosition(-100f, 0f)
            setSize(200f, 100f)
        }
        val group = wrapper(annotation, 225f, -62f).apply {
            isTransform = true
            setOrigin(25f, 25f)
            setScale(0.5f)
        }
        mapLayer.addActor(group)
        val batch = batch()

        // Effective child bounds are x=-37.5..62.5 and y=12.5..62.5.
        mapLayer.draw(batch, 1f)
        assertEquals(1, annotation.draws)
        group.x = 238f // Left edge is now beyond the right viewport edge.
        mapLayer.draw(batch, 1f)
        assertEquals(1, annotation.draws)
        group.setPosition(225f, -63f) // Top edge is now below the viewport.
        mapLayer.draw(batch, 1f)
        assertEquals(1, annotation.draws)
    }

    @Test fun `a child extending right keeps its wrapper visible from the left edge`() {
        val mapLayer = layer(Rectangle(0f, 0f, 200f, 200f))
        val annotation = Annotation().apply {
            setPosition(-60f, 0f)
            setSize(160f, 40f)
        }
        val group = wrapper(annotation, -99f, 50f)
        mapLayer.addActor(group)
        val batch = batch()

        mapLayer.draw(batch, 1f)
        assertEquals(1, annotation.draws)
        group.x = -101f
        mapLayer.draw(batch, 1f)
        assertEquals(1, annotation.draws)
    }

    @Test fun `non-transform wrappers ignore retained zoom scale and origin`() {
        val mapLayer = layer(Rectangle(0f, 0f, 200f, 200f))
        val annotation = Annotation().apply { setSize(100f, 104f) }
        val group = wrapper(annotation, 50f, -103f).apply {
            setScale(0.1f)
            setOrigin(25f, 25f)
        }
        mapLayer.addActor(group)

        mapLayer.draw(batch(), 1f)

        assertEquals(1, annotation.draws)
        assertEquals(0.1f, group.scaleX, 0f)
        assertEquals(25f, group.originX, 0f)
    }

    @Test fun `drawing leaves movement actions and local coordinates intact`() {
        val mapLayer = layer(Rectangle(0f, 0f, 200f, 200f))
        val annotation = Annotation().apply {
            setPosition(-50f, -20f)
            setSize(100f, 100f)
        }
        val group = wrapper(annotation, 100f, 50f).apply {
            isTransform = true
            setOrigin(25f, 25f)
            setScale(0.5f)
            addAction(Actions.moveTo(100f, -104f, 0.4f))
        }
        mapLayer.addActor(group)

        mapLayer.act(0.2f)
        assertEquals(-27f, group.y, 0.001f)
        mapLayer.draw(batch(), 1f)
        assertEquals(-27f, group.y, 0.001f)
        assertEquals(100f, group.x, 0f)
        assertEquals(25f, group.originY, 0f)
        assertEquals(0.5f, group.scaleY, 0f)
        assertEquals(-50f, annotation.x, 0f)
        assertEquals(-20f, annotation.y, 0f)
        mapLayer.act(0.2f)
        assertEquals(-104f, group.y, 0.001f)
        assertEquals(0, group.actions.size)
    }
}
