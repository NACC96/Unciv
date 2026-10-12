package com.unciv.ui.components.tilegroups.layers

import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.Touchable
import kotlin.math.max
import kotlin.math.min

/** Container holding all [TileLayer] instances of one subtype across the whole map,
 *  forming a single rendering/interaction layer in [com.unciv.ui.components.tilegroups.TileGroupMap].
 *
 *  Keeping each layer type in its own Group lets [com.unciv.ui.components.tilegroups.TileGroupMap]
 *  short-circuit [hit] and [act] at the layer boundary instead of iterating every tile actor:
 *  non-interactive layers have [touchable] = [Touchable.disabled] so [hit] returns null without
 *  recursing into N children; [act] is suppressed unless [actable] is true. */
open class TileMapLayer<T : TileLayer>(
    initialCapacity: Int,
    private val actable: Boolean = false,
    touchable: Boolean = false,
) : Group() {

    val tileLayers = ArrayList<T>(initialCapacity)

    init {
        isTransform = false
        this.touchable = if (touchable) Touchable.childrenOnly else Touchable.disabled
        children.ensureCapacity(initialCapacity * 2) // rough estimate: ~2 images/tile on average
    }

    /** Register a tile-layer and flush its buffered images into this Group. */
    fun add(layer: T, tileX: Float, tileY: Float) {
        tileLayers.add(layer)
        layer.attachTo(this, tileX, tileY)
    }

    override fun act(delta: Float) {
        if (actable) super.act(delta)
    }
    override fun draw(batch: Batch, parentAlpha: Float) = super.draw(batch, parentAlpha)
    override fun hit(x: Float, y: Float, touchable: Boolean): Actor? = super.hit(x, y, touchable)
}

// individual inheritors for perf data only
class TerrainMapLayer(initialCapacity: Int, actable: Boolean = false, touchable: Boolean = false):
    TileMapLayer<TileLayerTerrain>(initialCapacity, actable, touchable) {
    override fun act(delta: Float) = super.act(delta)
    override fun draw(batch: Batch, parentAlpha: Float) = super.draw(batch, parentAlpha)
}

class FeaturesMapLayer(initialCapacity: Int, actable: Boolean = false, touchable: Boolean = false):
    TileMapLayer<TileLayerFeatures>(initialCapacity, actable, touchable) {
    override fun act(delta: Float) = super.act(delta)
    override fun draw(batch: Batch, parentAlpha: Float) = super.draw(batch, parentAlpha)
}

class BordersMapLayer(initialCapacity: Int, actable: Boolean = false, touchable: Boolean = false):
    TileMapLayer<TileLayerBorders>(initialCapacity, actable, touchable) {
    override fun act(delta: Float) = super.act(delta)
    override fun draw(batch: Batch, parentAlpha: Float) = super.draw(batch, parentAlpha)
}

class ResourceMapLayer(initialCapacity: Int, actable: Boolean = false, touchable: Boolean = false):
    TileMapLayer<TileLayerResource>(initialCapacity, actable, touchable) {
    override fun act(delta: Float) = super.act(delta)
    override fun draw(batch: Batch, parentAlpha: Float) = super.draw(batch, parentAlpha)
}

class ImprovementMapLayer(initialCapacity: Int, actable: Boolean = false, touchable: Boolean = false):
    TileMapLayer<TileLayerImprovement>(initialCapacity, actable, touchable) {
    override fun act(delta: Float) = super.act(delta)
    override fun draw(batch: Batch, parentAlpha: Float) = super.draw(batch, parentAlpha)
}

class MiscMapLayer(initialCapacity: Int, actable: Boolean = false, touchable: Boolean = false):
    TileMapLayer<TileLayerMisc>(initialCapacity, actable, touchable) {
    override fun act(delta: Float) = super.act(delta)
    override fun draw(batch: Batch, parentAlpha: Float) = super.draw(batch, parentAlpha)
}

class YieldMapLayer(initialCapacity: Int, actable: Boolean = false, touchable: Boolean = false):
    TileMapLayer<TileLayerYield>(initialCapacity, actable, touchable) {
    override fun act(delta: Float) = super.act(delta)
    override fun draw(batch: Batch, parentAlpha: Float) = super.draw(batch, parentAlpha)
}

class UnitSpriteMapLayer(initialCapacity: Int, actable: Boolean = false, touchable: Boolean = false):
    TileMapLayer<TileLayerUnitSprite>(initialCapacity, actable, touchable) {
    override fun act(delta: Float) = super.act(delta)
    override fun draw(batch: Batch, parentAlpha: Float) = super.draw(batch, parentAlpha)
}

class OverlayMapLayer(initialCapacity: Int, actable: Boolean = false, touchable: Boolean = false):
    TileMapLayer<TileLayerOverlay>(initialCapacity, actable, touchable) {
    override fun act(delta: Float) = super.act(delta)
    override fun draw(batch: Batch, parentAlpha: Float) = super.draw(batch, parentAlpha)
}

class UnitFlagMapLayer(initialCapacity: Int, actable: Boolean = false, touchable: Boolean = false):
    TileMapLayer<TileLayerUnitFlag>(initialCapacity, actable, touchable) {
    override fun act(delta: Float) = super.act(delta)
    override fun draw(batch: Batch, parentAlpha: Float) = super.draw(batch, parentAlpha)
}

class CityButtonMapLayer(initialCapacity: Int, actable: Boolean = false, touchable: Boolean = false):
    TileMapLayer<TileLayerCityButton>(initialCapacity, actable, touchable) {
    private val cityCullingArea = Rectangle()

    override fun act(delta: Float) = super.act(delta)

    override fun draw(batch: Batch, parentAlpha: Float) {
        val parentArea = cullingArea ?: return super.draw(batch, parentAlpha)
        var expandLeft = 0f
        var expandRight = 0f
        var expandBottom = 0f
        var expandTop = 0f
        // City wrappers deliberately have no size. Group's culling tests their origins,
        // so include the annotation stack's overhang without changing movement or hit bounds.
        for (wrapperIndex in 0 until children.size) {
            val wrapper = children[wrapperIndex] as? Group ?: continue
            if (!wrapper.isVisible) continue
            val scaleX = if (wrapper.isTransform) wrapper.scaleX else 1f
            val scaleY = if (wrapper.isTransform) wrapper.scaleY else 1f
            val originX = wrapper.originX
            val originY = wrapper.originY
            for (childIndex in 0 until wrapper.children.size) {
                val child = wrapper.children[childIndex]
                if (!child.isVisible) continue
                val x1 = originX + (child.x - originX) * scaleX
                val x2 = originX + (child.x + child.width - originX) * scaleX
                val y1 = originY + (child.y - originY) * scaleY
                val y2 = originY + (child.y + child.height - originY) * scaleY
                // Expand the opposite viewport edge: a child reaching right can be
                // visible while its wrapper origin sits to the left of the viewport.
                expandLeft = max(expandLeft, max(x1, x2) - wrapper.width)
                expandRight = max(expandRight, -min(x1, x2))
                expandBottom = max(expandBottom, max(y1, y2) - wrapper.height)
                expandTop = max(expandTop, -min(y1, y2))
            }
        }
        cityCullingArea.set(parentArea.x - expandLeft, parentArea.y - expandBottom,
            parentArea.width + expandLeft + expandRight, parentArea.height + expandBottom + expandTop)
        cullingArea = cityCullingArea
        try {
            super.draw(batch, parentAlpha)
        } finally {
            cullingArea = parentArea
        }
    }
}
