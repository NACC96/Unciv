package com.unciv.ui.screens.diplomacyscreen

import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOfferType
import com.unciv.ui.components.AtlasWidgets
import com.unciv.ui.components.GameUiStyle
import com.unciv.ui.components.extensions.toLabel

/** Read-only exact terms. Grouping does not mutate amounts, duration, order in the model or consent. */
object TradeTermsView {
    fun render(title: String, offers: Iterable<TradeOffer>, text: (TradeOffer) -> String = { it.getOfferText() }): Table =
        Table().apply {
            name = "AtlasTradeTerms:$title"
            pad(GameUiStyle.Space.l)
            background = GameUiStyle.panelBackground("Atlas/Trade/ExactTerms", 1f)
            add(AtlasWidgets.header(title)).growX().minWidth(0f).row()
            val terms = offers.toList()
            for (type in TradeOfferType.entries) {
                val group = terms.filter { it.type == type }
                if (group.isEmpty()) continue
                add(type.name.replace('_', ' ').toLabel(GameUiStyle.accent, GameUiStyle.Type.small)
                    .apply { wrap = true }).growX().minWidth(0f).left().padTop(GameUiStyle.Space.m).row()
                for (offer in group)
                    add(text(offer).toLabel(GameUiStyle.ink).apply { wrap = true })
                        .growX().minWidth(0f).left().padTop(GameUiStyle.Space.s).row()
            }
            if (terms.isEmpty()) add("None".toLabel(GameUiStyle.muted)).left()
        }
}
