package com.unciv.ui.screens.worldscreen

import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.math.Vector2

/** Screen-space allocations shared by the HUD and floating detail panels. No actor is scaled to fit.
 *  [minimap] is the minimap's allocation; [minimapCard] is the part its card actually covers, and
 *  [tileInfo] (empty when hidden) stacks above that card. Empty rectangles mean "hidden". */
data class HudRegions(
    val header: Rectangle, val tracker: Rectangle, val activity: Rectangle,
    val unitDock: Rectangle, val minimap: Rectangle, val turnAction: Rectangle,
    val occupied: List<Rectangle>, val unobstructedMapFraction: Float,
    val compact: Boolean,
    val minimapCard: Rectangle = minimap,
    val tileInfo: Rectangle = Rectangle(),
) {
    /** Top of the right-hand card stack (tile info, else the minimap card). */
    val rightStackTop get() = if (tileInfo.height > 0f) tileInfo.y + tileInfo.height else minimapCard.y + minimapCard.height
}

/** Measured content for [WorldHudLayout.calculate]. A null or zero-area size means the panel is hidden. */
data class HudContent(
    val tracker: Vector2? = null,
    val selection: Vector2? = null,
    val activityOpen: Boolean = false,
    val turnAction: Vector2? = null,
    val minimap: Vector2? = null,
    val tileInfo: Vector2? = null,
)

object WorldHudLayout {
    const val gap = 8f
    /** Single floating cluster row: 8 margin + 40 cluster + 8 margin. */
    const val headerRowHeight = 56f
    const val compactTabsHeight = 44f
    const val compactBreakpoint = 960f

    fun headerHeight(width: Float, height: Float, fontScale: Float) =
        minOf((if (width < compactBreakpoint) headerRowHeight + compactTabsHeight else headerRowHeight) * fontScale, height * .5f)

    private fun rightWidth(width: Float) = minOf(280f, width * .27f)
    private fun leftWidth(width: Float) = minOf(300f, width * .26f)

    /** Conservative default: a full tracker and a full-width selection dock are always reserved. */
    fun calculate(width: Float, height: Float, fontScale: Float, activityOpen: Boolean): HudRegions {
        require(width > 0 && height > 0 && fontScale > 0)
        val header = headerHeight(width, height, fontScale)
        val headerY = height - header
        val tracker = Vector2(leftWidth(width), minOf(106f * fontScale, (headerY - gap * 3) * .3f))
        val dock = Vector2(width - leftWidth(width) - rightWidth(width) - gap * 4, minOf(158f, headerY * .3f))
        return calculate(width, height, fontScale, HudContent(tracker, dock, activityOpen))
    }

    /** Content-aware allocation: every panel hugs its measured size and hidden panels take no room. */
    fun calculate(width: Float, height: Float, fontScale: Float, content: HudContent): HudRegions {
        require(width > 0 && height > 0 && fontScale > 0)
        val compact = width < compactBreakpoint
        val headerHeight = headerHeight(width, height, fontScale)
        val header = Rectangle(0f, height - headerHeight, width, headerHeight)
        val defaultTurnHeight = minOf(64f * fontScale, height * .2f)
        val rightWidth = rightWidth(width)
        val leftWidth = leftWidth(width)
        fun Vector2?.shown() = this != null && x > 0f && y > 0f
        val turnHeight = content.turnAction.takeIf { it.shown() }
            ?.let { maxOf(it.y, 44f * fontScale).coerceAtMost(maxOf(44f * fontScale, height * .45f)) } ?: defaultTurnHeight
        val turnAction: Rectangle
        val tracker: Rectangle
        val activity: Rectangle
        val dock: Rectangle
        val minimap: Rectangle
        val minimapCard: Rectangle
        var tileInfo = Rectangle()
        if (compact) {
            turnAction = Rectangle(gap, gap, width - gap * 2, turnHeight)
            // Optional panels occupy the same explicit tab viewport, one at a time.
            val optional = Rectangle(gap, turnAction.y + turnHeight + gap, width - gap * 2,
                (height - compactTabsHeight * fontScale - turnHeight - gap * 3).coerceAtLeast(1f))
            tracker = if (content.tracker.shown()) Rectangle(optional) else Rectangle()
            activity = Rectangle(optional)
            dock = Rectangle(optional); minimap = Rectangle(optional); minimapCard = Rectangle(optional)
        } else {
            val turnWidth = content.turnAction.takeIf { it.shown() }
                ?.let { it.x.coerceIn(rightWidth, maxOf(rightWidth, minOf(340f, width * .3f))) } ?: rightWidth
            turnAction = Rectangle(width - turnWidth - gap, gap, turnWidth, turnHeight)
            tracker = content.tracker.takeIf { it.shown() }?.let {
                val h = minOf(it.y, (header.y - gap * 3) * .45f)
                Rectangle(gap, header.y - gap - h, minOf(it.x, width * .4f), h)
            } ?: Rectangle()
            val leftTop = if (tracker.height > 0f) tracker.y else header.y
            activity = Rectangle(gap, gap, leftWidth, (leftTop - gap * 2).coerceAtLeast(1f))
            val minimapY = turnAction.y + turnAction.height + gap
            minimap = Rectangle(width - rightWidth - gap, minimapY, rightWidth,
                minOf(180f, header.y * .3f).coerceAtMost((header.y - gap - minimapY).coerceAtLeast(1f)))
            minimapCard = content.minimap.takeIf { it.shown() }?.let {
                val w = minOf(it.x, minimap.width); val h = minOf(it.y, minimap.height)
                Rectangle(minimap.x + minimap.width - w, minimap.y, w, h)
            } ?: Rectangle(minimap)
            content.tileInfo.takeIf { it.shown() }?.let {
                val y = minimapCard.y + minimapCard.height + gap
                val h = minOf(it.y, header.y - gap - y)
                val w = minOf(it.x, maxOf(rightWidth, turnWidth))
                if (h > 0f) tileInfo = Rectangle(width - gap - w, y, w, h)
            }
            dock = content.selection.takeIf { it.shown() }?.let {
                val bandLeft = if (content.activityOpen) activity.x + activity.width + gap else gap
                val bandRight = minOf(turnAction.x, minimap.x) - gap
                val bandWidth = (bandRight - bandLeft).coerceAtLeast(1f)
                val w = minOf(it.x, bandWidth)
                val h = minOf(it.y, (header.y - gap * 2) * .45f)
                Rectangle((bandLeft + (bandWidth - w) / 2f).coerceAtLeast(0f), gap, w, h)
            } ?: Rectangle()
        }
        val occupied = listOf(header, tracker, dock, minimapCard, turnAction, tileInfo)
            .filter { it.width > 0f && it.height > 0f } + if (content.activityOpen) listOf(activity) else emptyList()
        return HudRegions(header, tracker, activity, dock, minimap, turnAction, occupied,
            (1f - unionArea(occupied) / (width * height)).coerceIn(0f, 1f), compact, minimapCard, tileInfo)
    }

    /** Exact rectangle union using vertical slabs. Overlays never double-count covered map pixels. */
    fun unionArea(rectangles: List<Rectangle>): Float {
        val rects = rectangles.filter { it.width > 0 && it.height > 0 }
        val edges = rects.flatMap { listOf(it.x, it.x + it.width) }.distinct().sorted()
        return edges.zipWithNext().sumOf { (left, right) ->
            val intervals = rects.filter { it.x < right && it.x + it.width > left }
                .map { it.y to it.y + it.height }.sortedBy { it.first }
            var covered = 0f
            var end = Float.NEGATIVE_INFINITY
            for ((bottom, top) in intervals) {
                covered += (top - maxOf(bottom, end)).coerceAtLeast(0f)
                end = maxOf(end, top)
            }
            ((right - left) * covered).toDouble()
        }.toFloat()
    }
}
