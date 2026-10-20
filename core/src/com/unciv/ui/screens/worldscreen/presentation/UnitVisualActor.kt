package com.unciv.ui.screens.worldscreen.presentation

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.unciv.logic.presentation.VisualUnitDescriptor
import com.unciv.ui.images.atlas.*

/** A value-only ghost. Texture lifetime belongs to the cache, and this actor never accepts input. */
class UnitVisualActor(
    val descriptor: VisualUnitDescriptor,
    private val cache: AtlasClipCache,
    private val key: ClipKey,
    private val imageWidth: Float,
    private val imageOffsetX: Float,
    private val imageOffsetY: Float
) : Actor(), AutoCloseable {
    private var handle: ClipHandle? = null
    private var frame: FrameSpec? = null
    private var colorRegion: TextureRegion? = null
    private var shadowRegion: TextureRegion? = null
    private var masks: List<Pair<TextureRegion, Color>> = emptyList()
    private var closed = false
    var opacity = 1f
    var hitAmount = 0f
    val ready: Boolean get() = !closed && colorRegion != null && handle?.let(cache::isCurrent) == true
    val looping: Boolean get() = handle?.clip?.looping == true
    val clipDurationSeconds: Float get() = (handle?.clip?.durationMs ?: 0L) / 1000f

    init { touchable = Touchable.disabled; setSize(imageWidth, imageWidth) }
    override fun hit(x: Float, y: Float, touchable: Boolean): Actor? = null

    /** Requests/pins and frame-region lookups happen in timeline advance, never in draw. */
    fun prepare(elapsedSeconds: Float): Boolean {
        if (closed) return false
        val previous = handle
        if (previous != null && !cache.isCurrent(previous)) {
            previous.close(); handle = null; frame = null; colorRegion = null; shadowRegion = null; masks = emptyList()
        }
        val pinned = handle ?: cache.request(key)?.also { handle = it } ?: return false
        val clip = pinned.clip
        val duration = clip.durationMs.coerceAtLeast(1L)
        val elapsed = (elapsedSeconds.coerceAtLeast(0f) * 1000f).toLong()
        var remaining = if (clip.looping) elapsed % duration else elapsed.coerceAtMost(duration - 1)
        val next = clip.frames.firstOrNull { f ->
            if (remaining < f.durationMs) true else { remaining -= f.durationMs; false }
        } ?: clip.frames.last()
        if (next == frame) return true
        try {
            fun region(spec: FrameRegion): TextureRegion = pinned.region(spec).also {
                if (clip.mirroredFacing != null && !it.isFlipX) it.flip(true, false)
            }
            shadowRegion = next.shadow?.let(::region)
            colorRegion = region(next.color)
            val owner = descriptor.colors
            masks = if (owner == null) emptyList() else next.masks.mapNotNull { mask ->
                val rgba = when (mask.id.lowercase()) {
                    "nation", "inner", "primary" -> owner.innerRgba
                    "outer", "secondary" -> owner.outerRgba
                    else -> return@mapNotNull null
                }
                region(mask.region) to Color(rgba)
            }
            frame = next
        } catch (_: IllegalStateException) {
            close()
            return false
        }
        return true
    }

    override fun draw(batch: Batch, parentAlpha: Float) {
        val pinned = handle ?: return
        val current = frame ?: return
        // Generation checks are allocation-free; even a queued cache reset cannot draw retired textures.
        if (closed || !cache.isCurrent(pinned)) return
        val region = colorRegion ?: return
        val scale = imageWidth / current.originalWidth
        val anchorX = if (pinned.clip.mirroredFacing == null) current.anchorX else current.color.width - current.anchorX
        val groundX = x + imageOffsetX + pinned.clip.anchorX * scale
        val groundY = y + imageOffsetY + (current.originalHeight - pinned.clip.anchorY) * scale
        val left = groundX - anchorX * scale
        val bottom = groundY - (current.color.height - current.anchorY) * scale
        val width = current.color.width * scale
        val height = current.color.height * scale
        val oldColor = batch.packedColor
        val alpha = (parentAlpha * color.a * opacity).coerceIn(0f, 1f)
        batch.setColor(1f, 1f - hitAmount * .55f, 1f - hitAmount * .55f, alpha)
        shadowRegion?.let { batch.draw(it, left, bottom, width, height) }
        batch.draw(region, left, bottom, width, height)
        for ((mask, tint) in masks) {
            batch.setColor(tint.r, tint.g * (1f - hitAmount * .55f), tint.b * (1f - hitAmount * .55f), alpha * tint.a)
            batch.draw(mask, left, bottom, width, height)
        }
        batch.packedColor = oldColor
    }

    override fun close() {
        if (closed) return
        closed = true
        remove()
        masks = emptyList(); frame = null; colorRegion = null; shadowRegion = null
        handle?.close(); handle = null
    }
}
