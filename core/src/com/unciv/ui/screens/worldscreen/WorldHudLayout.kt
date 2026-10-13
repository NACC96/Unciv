package com.unciv.ui.screens.worldscreen

import com.badlogic.gdx.math.Rectangle

/** Screen-space allocations shared by the HUD and floating detail panels. No actor is scaled to fit. */
data class HudRegions(
    val header: Rectangle, val tracker: Rectangle, val activity: Rectangle,
    val unitDock: Rectangle, val minimap: Rectangle, val turnAction: Rectangle,
    val occupied: List<Rectangle>, val unobstructedMapFraction: Float,
    val compact: Boolean
)

object WorldHudLayout {
    fun calculate(width: Float, height: Float, fontScale: Float, activityOpen: Boolean): HudRegions {
        require(width > 0 && height > 0 && fontScale > 0)
        val compact = width < 960f
        val gap = 8f
        val headerHeight = minOf((if (compact) 166f else 132f) * fontScale, height * .5f)
        val header = Rectangle(0f, height - headerHeight, width, headerHeight)
        val turnHeight = minOf(64f * fontScale, height * .2f)
        val rightWidth = minOf(280f, width * .27f)
        val leftWidth = minOf(300f, width * .26f)
        val turnAction: Rectangle
        val tracker: Rectangle
        val activity: Rectangle
        val dock: Rectangle
        val minimap: Rectangle
        if (compact) {
            turnAction = Rectangle(gap, gap, width - gap * 2, turnHeight)
            // Optional panels occupy the same explicit tab viewport, one at a time.
            val optional = Rectangle(gap, turnAction.y + turnHeight + gap, width - gap * 2,
                (height - 44f * fontScale - turnHeight - gap * 3).coerceAtLeast(1f))
            tracker = Rectangle(optional); activity = Rectangle(optional)
            dock = Rectangle(optional); minimap = Rectangle(optional)
        } else {
            turnAction = Rectangle(width - rightWidth - gap, gap, rightWidth, turnHeight)
            val trackerHeight = minOf(106f * fontScale, (header.y - gap * 3) * .3f)
            tracker = Rectangle(gap, header.y - gap - trackerHeight, leftWidth, trackerHeight)
            activity = Rectangle(gap, gap, leftWidth, (tracker.y - gap * 2).coerceAtLeast(1f))
            val dockHeight = minOf(158f, header.y * .3f)
            dock = Rectangle(leftWidth + gap * 2, gap, width - leftWidth - rightWidth - gap * 4, dockHeight)
            minimap = Rectangle(turnAction.x, turnAction.y + turnAction.height + gap, rightWidth,
                minOf(180f, header.y * .3f))
        }
        val occupied = listOf(header, tracker, dock, minimap, turnAction) + if (activityOpen) listOf(activity) else emptyList()
        return HudRegions(header, tracker, activity, dock, minimap, turnAction, occupied,
            (1f - unionArea(occupied) / (width * height)).coerceIn(0f, 1f), compact)
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
