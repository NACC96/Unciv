package com.unciv.ui.components.widgets

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.ui.Skin
import com.unciv.UncivGame
import kotlin.math.abs
import kotlin.math.roundToInt

/** An [AutoScrollPane] for reading long text whose wheel and trackpad steps do not depend on the
 *  content length.
 *
 *  Gdx moves `max(0.9 × pane height, 0.1 × scroll range) / 4` per wheel unit, so a long history
 *  jumps whole screens per notch and a trackpad flick (dozens of units) overshoots by thousands
 *  of pixels. Here precise deltas (trackpads, Magic Mouse) follow the finger like native lists,
 *  and whole wheel notches move a couple of lines with the usual smooth animation.
 */
open class ReadingScrollPane(widget: Actor?, style: ScrollPaneStyle = ScrollPaneStyle()) : AutoScrollPane(widget, style) {
    constructor(widget: Actor?, skin: Skin) : this(widget, skin.get(ScrollPaneStyle::class.java))

    /** Called before a wheel or trackpad step moves the content, e.g. to stop following live output. */
    var onUserScroll: (() -> Unit)? = null

    // Called from the ScrollPane constructor: must not rely on this class's initialized fields.
    override fun addScrollListener() {
        addListener(object : InputListener() {
            override fun scrolled(event: InputEvent, x: Float, y: Float, amountX: Float, amountY: Float): Boolean {
                event.cancel()
                setScrollbarsVisible(true)
                val vertical = isScrollY && !isScrollingDisabledY
                val horizontal = isScrollX && !isScrollingDisabledX
                if (!vertical && !horizontal) return false
                onUserScroll?.invoke()
                // A sideways-only gesture still moves a vertical list, and vice versa.
                val dy = if (vertical) (if (amountY == 0f && !horizontal) amountX else amountY) else 0f
                val dx = if (horizontal) (if (amountX == 0f && !vertical) amountY else amountX) else 0f
                val now = System.nanoTime()
                val notch = classify(dy, now) or classify(dx, now)
                val line = lineHeight()
                if (dy != 0f) scrollY = (scrollY + step(dy, line, notch)).coerceIn(0f, maxY)
                if (dx != 0f) scrollX = (scrollX + step(dx, line, notch)).coerceIn(0f, maxX)
                // Notches glide; precise deltas already arrive as a smooth stream.
                if (!notch) updateVisualScroll()
                return true
            }
        })
    }

    private fun lineHeight() = baseLineHeight * (UncivGame.Current.settings.fontSizeMultiplier.takeIf { it > 0f } ?: 1f)

    companion object {
        /** Body text line height at font scale 1. */
        const val baseLineHeight = 20f
        /** GLFW scales macOS precise deltas to a tenth of the points scrolled. */
        const val pointsPerPreciseUnit = 10f
        /** A precise stream keeps whole values (e.g. a 30-point flick reports 3.0) precise this long. */
        private const val preciseStreamNanos = 500_000_000L
        private val mac = System.getProperty("os.name")?.lowercase()?.startsWith("mac") == true
        /** Lines per whole wheel notch. macOS already accelerates its wheel line counts. */
        private val linesPerNotch = if (mac) 2f else 3f
        private var lastFractionNanos = Long.MIN_VALUE / 2

        /** Wheels report whole notches; precise devices mostly report fractions, and the whole
         *  values inside such a stream belong to it. */
        fun classify(amount: Float, now: Long): Boolean {
            if (amount == 0f) return false
            if (abs(amount - amount.roundToInt()) >= 1e-4f) { lastFractionNanos = now; return false }
            return now - lastFractionNanos > preciseStreamNanos
        }

        /** Distance in stage units for one scroll event of [amount] units. Outside macOS a precise
         *  unit equals a notch, so fractions scale the notch distance. */
        fun step(amount: Float, lineHeight: Float, notch: Boolean): Float =
            if (notch || !mac) amount * linesPerNotch * lineHeight
            else amount * pointsPerPreciseUnit
    }
}
