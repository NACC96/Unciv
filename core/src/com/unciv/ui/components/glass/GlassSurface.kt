package com.unciv.ui.components.glass

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.math.Vector3

/** Draws the frosted fill of a rounded surface from the active [GlassBackdrop]. */
object GlassSurface {
    private val from = Vector3()
    private val to = Vector3()

    /** Fills ([x], [y], [width], [height]) with the blurred map under it, tinted by [tint] at
     *  [tintAmount] (0 = clear glass, 1 = opaque [tint]), clipped to [radius] corners.
     *  @return false when there is no blur this frame; the caller then draws its plain fill. */
    fun draw(batch: Batch, x: Float, y: Float, width: Float, height: Float,
             radius: Float, tint: Color, tintAmount: Float): Boolean {
        if (width <= 0f || height <= 0f) return true
        val backdrop = GlassBackdrop.active ?: return false
        val texture = backdrop.sample() ?: return false
        val shader = backdrop.glassShader ?: return false
        backdrop.textureCoordinate(batch, x, y, from)
        backdrop.textureCoordinate(batch, x + width, y + height, to)
        val pixelWidth = (to.x - from.x) * texture.width
        // Antialias over about one screen pixel of the ⅛ buffer's parent, in surface units.
        val unitsPerPixel = if (pixelWidth > 0f) width / (pixelWidth * 8f) else 1f
        val prior = batch.shader
        batch.shader = shader
        shader.setUniformf("u_uv", from.x, from.y, to.x, to.y)
        shader.setUniformf("u_size", width, height)
        shader.setUniformf("u_radius", radius.coerceAtMost(minOf(width, height) / 2f))
        shader.setUniformf("u_aa", unitsPerPixel.coerceIn(0.25f, 2f))
        shader.setUniformf("u_tint", tint.r, tint.g, tint.b, tintAmount.coerceIn(0f, 1f))
        batch.draw(texture, x, y, width, height, from.x, from.y, to.x, to.y)
        batch.shader = prior
        return true
    }
}
