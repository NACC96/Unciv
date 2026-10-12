package com.unciv.ui.screens.cityscreen

import com.badlogic.gdx.math.Rectangle

/** Display-only geometry; all coordinates are stage coordinates with a bottom-left origin. */
data class CityRegions(
    val header: Rectangle,
    val map: Rectangle,
    val queue: Rectangle,
    val catalog: Rectangle,
    val details: Rectangle,
    val footer: Rectangle,
    val tabs: Rectangle,
    val narrow: Boolean
)

object CityScreenLayout {
    fun calculate(width: Float, height: Float, fontScale: Float): CityRegions {
        val w = width.coerceAtLeast(1f)
        val h = height.coerceAtLeast(1f)
        val scale = fontScale.coerceIn(1f, 2f)
        val gap = minOf(8f, w / 30f, h / 40f)
        val contentWidth = (w - gap * 2).coerceAtLeast(0f)
        val headerHeight = minOf(94f * scale, h * .22f)
        val footerHeight = minOf(52f * scale, h * .15f)
        val footer = Rectangle(gap, gap, contentWidth, footerHeight)
        val header = Rectangle(gap, h - gap - headerHeight, contentWidth, headerHeight)
        val bodyY = footer.y + footer.height + gap
        val bodyHeight = (header.y - gap - bodyY).coerceAtLeast(0f)
        val narrow = w < 1200f * scale
        if (narrow) {
            val queueHeight = minOf(124f * scale, bodyHeight * .36f)
            val tabHeight = minOf(40f * scale, bodyHeight * .16f)
            val queue = Rectangle(gap, header.y - gap - queueHeight, contentWidth, queueHeight)
            val tabs = Rectangle(gap, queue.y - gap - tabHeight, contentWidth, tabHeight)
            val body = Rectangle(gap, bodyY, contentWidth, (tabs.y - gap - bodyY).coerceAtLeast(0f))
            return CityRegions(header, Rectangle(body), queue, Rectangle(body), Rectangle(body), footer, tabs, true)
        }
        val left = minOf(330f * scale, contentWidth * .27f)
        val right = minOf(360f * scale, contentWidth * .29f)
        val queueHeight = minOf(220f * scale, bodyHeight * .36f)
        val queue = Rectangle(gap, header.y - gap - queueHeight, left, queueHeight)
        val catalog = Rectangle(gap, bodyY, left, (queue.y - gap - bodyY).coerceAtLeast(0f))
        val map = Rectangle(gap + left + gap, bodyY, (contentWidth - left - right - gap * 2).coerceAtLeast(0f), bodyHeight)
        val details = Rectangle(w - gap - right, bodyY, right, bodyHeight)
        return CityRegions(header, map, queue, catalog, details, footer, Rectangle(), false)
    }
}

/** Draft display state belongs to the screen, never to a rebuilt actor tree or the save file. */
data class CityScreenState(
    var search: String = "",
    var category: Int = 0,
    var availableOnly: Boolean = false,
    var page: Int = 0,
    var showSelection: Boolean = false,
    var queueScroll: Float = 0f,
    var catalogScroll: Float = 0f,
    var cityScroll: Float = 0f,
    var detailScroll: Float = 0f,
    var queueExpanded: Boolean = true
)
