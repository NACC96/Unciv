package com.unciv.ui.screens.worldscreen.status

import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.screens.basescreen.BaseScreen

/** Keep long agent identities available without letting their dropdown cover the viewport edges. */
internal class AgentNationSelectBox<T> : SelectBox<T>(BaseScreen.skin) {
    /** Draws the selected item at this type size (e.g. a compact chip); null keeps the skin font size.
     * The open list keeps the skin size so long identities stay readable. */
    var fontSize: Int? = null

    override fun drawItem(batch: Batch, font: BitmapFont, item: T, x: Float, y: Float, width: Float): GlyphLayout {
        val size = fontSize ?: return super.drawItem(batch, font, item, x, y, width)
        val data = font.data
        val scaleX = data.scaleX
        val scaleY = data.scaleY
        val capHeight = data.capHeight
        data.setScale(size / Fonts.ORIGINAL_FONT_SIZE)
        // SelectBox centres the cap height of the unscaled font; re-centre the smaller glyphs.
        val centred = y - (capHeight - data.capHeight) / 2f
        try {
            return super.drawItem(batch, font, item, x, centred, width)
        } finally {
            data.setScale(scaleX, scaleY)
        }
    }

    override fun newScrollPane(): SelectBoxScrollPane<T> = object : SelectBoxScrollPane<T>(this) {
        override fun show(stage: Stage) {
            super.show(stage)
            val margin = minOf(8f, stage.width / 2f)
            width = minOf(width, (stage.width - margin * 2f).coerceAtLeast(1f))
            x = x.coerceIn(margin, maxOf(margin, stage.width - width - margin))
            val verticalMargin = minOf(8f, stage.height / 2f)
            height = minOf(height, (stage.height - verticalMargin * 2f).coerceAtLeast(1f))
            y = y.coerceIn(verticalMargin, maxOf(verticalMargin, stage.height - height - verticalMargin))
            // With horizontal scrolling disabled, List draws long names with an ellipsis.
            validate()
        }
    }
}
