package com.unciv.ui.components.atlas

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.ui.components.GameUiStyle
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.onActivation
import com.unciv.models.translations.tr

/** State supplied by the rules/view. Highlighting a draft does not change its eligibility. */
enum class AtlasChoiceState(val marker: String, val caption: String) {
    LOCKED("×", "Unavailable"),
    AVAILABLE("○", "Available"),
    CURRENT("→", "Current"),
    COMPLETED("✓", "Completed")
}

/** A selectable description, never an adopt/research/build command. Locked choices can show details. */
class AtlasChoiceCard(
    title: String,
    detail: String,
    state: AtlasChoiceState,
    onSelect: () -> Unit
) : Table() {
    override fun getPrefWidth() = super.getPrefWidth().coerceAtLeast(300f)
    override fun getMinWidth() = 0f
    private val heading = title.toLabel(GameUiStyle.ink, GameUiStyle.Type.label).apply { wrap = true }
    private val status = "".toLabel(GameUiStyle.muted, GameUiStyle.Type.small).apply { wrap = true }
    private val description = detail.toLabel(GameUiStyle.muted, GameUiStyle.Type.body).apply { wrap = true }
    private val reason = "".toLabel(GameUiStyle.warning, GameUiStyle.Type.body).apply { wrap = true }
    private val text = Table()
    var choiceState: AtlasChoiceState = state
        private set
    var isSelected = false
        private set

    init {
        name = "AtlasChoiceCard:$title"
        touchable = Touchable.enabled
        pad(GameUiStyle.Space.l)
        text.add(heading).growX().minWidth(0f).left().row()
        text.add(status).growX().minWidth(0f).left().padTop(GameUiStyle.Space.s).row()
        if (detail.isNotEmpty())
            text.add(description).growX().minWidth(0f).left().padTop(GameUiStyle.Space.m).row()
        add(text).growX().minWidth(0f)
        onActivation { onSelect() }
        refreshFrame()
    }

    fun withIcon(icon: Actor, size: Float = 36f): AtlasChoiceCard {
        clearChildren()
        add(icon).size(size).top().padRight(GameUiStyle.Space.l)
        add(text).growX().minWidth(0f)
        return this
    }

    fun setSelected(selected: Boolean) {
        if (isSelected == selected) return
        isSelected = selected
        refreshFrame()
    }

    fun setState(state: AtlasChoiceState) {
        choiceState = state
        refreshFrame()
    }

    fun setDetail(detail: String) {
        description.setText(detail)
        if (description.parent == null && detail.isNotEmpty())
            text.add(description).growX().minWidth(0f).left().padTop(GameUiStyle.Space.m).row()
    }

    fun setReason(text: String) {
        reason.setText(text.tr())
        if (reason.parent == null && text.isNotEmpty())
            this.text.add(reason).growX().minWidth(0f).left().padTop(GameUiStyle.Space.m).row()
    }

    private fun refreshFrame() {
        val rim = when (choiceState) {
            AtlasChoiceState.LOCKED -> GameUiStyle.muted
            AtlasChoiceState.AVAILABLE -> GameUiStyle.info
            AtlasChoiceState.CURRENT -> GameUiStyle.accent
            AtlasChoiceState.COMPLETED -> GameUiStyle.positive
        }
        status.setText("${choiceState.marker} ${choiceState.caption.tr()}")
        status.style = com.badlogic.gdx.scenes.scene2d.ui.Label.LabelStyle(status.style).apply { fontColor = rim }
        background = GameUiStyle.outlinedBackground("Atlas/Choice/${choiceState.name}",
            if (isSelected) GameUiStyle.pressed else GameUiStyle.inset,
            if (isSelected) GameUiStyle.selection else rim,
            if (isSelected || choiceState == AtlasChoiceState.CURRENT) 2.5f else 1.5f)
    }
}
