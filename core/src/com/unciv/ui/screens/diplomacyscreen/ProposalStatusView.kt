package com.unciv.ui.screens.diplomacyscreen

import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.models.translations.tr
import com.unciv.ui.components.GameUiStyle
import com.unciv.ui.components.extensions.toLabel

/** Presentation only; callers derive this from existing pending offers/committed outcomes. */
enum class ProposalDisplayState(val marker: String, val caption: String) {
    DRAFT("○", "Draft"), PENDING("→", "Pending"), ACCEPTED("✓", "Accepted"),
    REJECTED("×", "Rejected"), EXPIRED("–", "Expired")
}

object ProposalStatusView {
    fun render(state: ProposalDisplayState, turn: Int?, detail: String): Table = Table().apply {
        name = "ProposalStatus:${state.name}"
        pad(GameUiStyle.Space.m)
        val tint = when (state) {
            ProposalDisplayState.ACCEPTED -> GameUiStyle.positive
            ProposalDisplayState.REJECTED -> GameUiStyle.error
            ProposalDisplayState.PENDING -> GameUiStyle.warning
            else -> GameUiStyle.muted
        }
        background = GameUiStyle.outlinedBackground("Atlas/Proposal/${state.name}", GameUiStyle.inset, tint)
        val text = "${state.marker} ${state.caption.tr()}" +
            if (turn == null) "" else " · ${"Turn".tr()} ${turn.tr()}"
        add(text.toLabel(tint).apply { wrap = true }).growX().minWidth(0f).left().row()
        if (detail.isNotEmpty())
            add(detail.toLabel(GameUiStyle.ink).apply { wrap = true })
                .growX().minWidth(0f).left().padTop(GameUiStyle.Space.s)
    }
}
