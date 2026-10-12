package com.unciv.ui.screens.worldscreen.status

import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox
import com.unciv.ui.screens.basescreen.BaseScreen

/** Keep long agent identities available without letting their dropdown cover the viewport edges. */
internal class AgentNationSelectBox<T> : SelectBox<T>(BaseScreen.skin) {
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
