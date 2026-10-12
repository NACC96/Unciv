package com.unciv.ui.screens.worldscreen.status

import com.badlogic.gdx.math.Rectangle

/** Local panel regions only. Input actors and session authority remain with their owners. */
data class PanelRegions(val content: Rectangle, val composer: Rectangle, val footer: Rectangle)

object AgentPanelLayout {
    fun calculate(width: Float, height: Float, fontScale: Float): PanelRegions {
        require(width.isFinite() && height.isFinite() && fontScale.isFinite())
        require(width > 0f && height > 0f && fontScale > 0f)
        val footerHeight = minOf(84f * fontScale, height * .28f)
        val composerHeight = minOf(192f * fontScale, height * .4f)
        return PanelRegions(
            Rectangle(0f, footerHeight + composerHeight, width, height - footerHeight - composerHeight),
            Rectangle(0f, footerHeight, width, composerHeight),
            Rectangle(0f, 0f, width, footerHeight))
    }
}
