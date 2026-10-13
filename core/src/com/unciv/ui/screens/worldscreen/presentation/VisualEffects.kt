package com.unciv.ui.screens.worldscreen.presentation

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.unciv.ui.components.widgets.ShadowedLabel
import com.unciv.ui.images.ImageGetter
import kotlin.math.sin

/** Deliberately abstract outcome feedback, positioned only by authorized event coordinates. */
class VisualEffects(
    private val kind: Kind,
    text: String? = null,
    tint: Color = Color(0xe9cf92ff.toInt()),
    private val reduced: Boolean = false,
    private val projectileStyle: Projectile = Projectile.ARROW
) : Group(), AutoCloseable {
    enum class Kind { MOVE, HIT, DEFEAT, FOUND, WORK, CAPTURE, NUCLEAR, PROJECTILE }
    enum class Projectile { ARROW, SHOT, SHELL, TORPEDO, AIR }
    private val marks = ArrayList<Image>()
    private val label = text?.let { ShadowedLabel(it, 17, Color.WHITE, Color(0x101b2bff)) }
    private val origin = Vector2()
    private val destination = Vector2()
    private var closed = false

    init {
        touchable = Touchable.disabled
        isTransform = false
        setSize(54f, 54f)
        if (kind == Kind.PROJECTILE) {
            val mark = ImageGetter.getDot(tint)
            when (projectileStyle) {
                Projectile.ARROW -> mark.setSize(11f, 2f)
                Projectile.SHOT -> mark.setSize(5f, 2f)
                Projectile.SHELL -> mark.setSize(5f, 5f)
                Projectile.TORPEDO -> mark.setSize(9f, 3f)
                Projectile.AIR -> mark.setSize(8f, 2f)
            }
            marks += mark; addActor(mark)
        } else {
            val count = when (kind) { Kind.HIT, Kind.WORK -> 4; Kind.NUCLEAR -> 8; else -> 6 }
            repeat(count) {
                val mark = ImageGetter.getDot(tint)
                mark.setSize(if (kind == Kind.WORK) 3f else 7f, 2f)
                mark.setOrigin(mark.width / 2, mark.height / 2)
                marks += mark; addActor(mark)
            }
        }
        label?.let { addActor(it) }
        children.forEach { it.touchable = Touchable.disabled }
    }

    override fun hit(x: Float, y: Float, touchable: Boolean): Actor? = null

    /** Coordinates are re-resolved by the timeline every frame, including horizontal world wrapping. */
    fun update(progress: Float, source: Vector2, target: Vector2? = null) {
        if (closed) return
        origin.set(source); destination.set(target ?: source)
        val p = progress.coerceIn(0f, 1f)
        setPosition(source.x, source.y)
        val centerX = 27f
        val centerY = 27f
        color.a = if (reduced) if (p < .85f) 1f else (1f - p) / .15f else 1f - p * p
        if (kind == Kind.PROJECTILE) {
            val mark = marks.first()
            val dx = destination.x - origin.x
            val dy = destination.y - origin.y
            val arc = if (projectileStyle == Projectile.SHELL) sin(p * Math.PI).toFloat() * 18f else 0f
            mark.setPosition(centerX + dx * p, centerY + dy * p + arc)
            mark.rotation = Vector2(dx, dy).angleDeg()
        } else {
            val radius = if (reduced) 20f else when (kind) {
                Kind.HIT -> 7f + p * 15f
                Kind.WORK -> 12f + sin(p * Math.PI * 2).toFloat() * 4f
                Kind.DEFEAT -> 24f * (1f - p)
                Kind.FOUND, Kind.CAPTURE -> 14f + p * 17f
                Kind.NUCLEAR -> 14f + p * 28f
                else -> 20f + p * 6f
            }
            marks.forEachIndexed { index, mark ->
                val angle = index * 360f / marks.size
                val vector = Vector2(radius, 0f).rotateDeg(angle)
                mark.setPosition(centerX + vector.x - mark.width / 2, centerY + vector.y - mark.height / 2)
                mark.rotation = angle + if (kind == Kind.HIT || kind == Kind.WORK) 0f else 90f
            }
        }
        label?.setPosition(centerX - label.width / 2, 47f + if (reduced) 0f else p * 10f)
    }

    override fun close() { if (!closed) { closed = true; remove(); clearChildren() } }
}
