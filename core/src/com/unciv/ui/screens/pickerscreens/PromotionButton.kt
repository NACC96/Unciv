package com.unciv.ui.screens.pickerscreens

import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.utils.Align
import com.unciv.models.ruleset.unit.Promotion
import com.unciv.ui.components.widgets.BorderedTable
import com.unciv.ui.components.GameUiStyle
import com.unciv.ui.components.atlas.AtlasChoiceCard
import com.unciv.ui.components.atlas.AtlasChoiceState
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen

internal class PromotionButton(
    val node: PromotionTree.PromotionNode,
    val isPickable: Boolean,
    private val adoptedLabelStyle: Label.LabelStyle,
    maxWidth: Float
) : BorderedTable(
    path="PromotionScreen/PromotionButton",
    defaultBgShape = BaseScreen.skinStrings.roundedEdgeRectangleMidShape,
    defaultBgBorder = BaseScreen.skinStrings.roundedEdgeRectangleMidBorderShape
) {
    private val label = node.promotion.name.toLabel(hideIcons = true)
    private val defaultLabelStyle = label.style
    private val colors = BaseScreen.skin[PromotionScreenColors::class.java]
    private val atlasCard = if (GameUiStyle.isAtlas) AtlasChoiceCard(node.promotion.name,
        node.promotion.prerequisites.joinToString("\n") { "Requires [$it]" }, when {
            node.isAdopted -> AtlasChoiceState.COMPLETED
            isPickable -> AtlasChoiceState.AVAILABLE
            else -> AtlasChoiceState.LOCKED
        }) {}.withIcon(ImageGetter.getPromotionPortrait(node.promotion.name)) else null

    init {

        touchable = Touchable.enabled
        borderSize = if (GameUiStyle.isAtlas) 0f else 5f

        pad(5f)
        align(Align.left)
        if (atlasCard != null) {
            pad(0f)
            add(atlasCard).width(maxWidth + 60f).growX().minWidth(0f)
        } else {
            add(ImageGetter.getPromotionPortrait(node.promotion.name)).padRight(10f)
            label.setEllipsis(true)
            add(label).left().maxWidth(maxWidth)
        }

        updateColor(false, emptySet(), emptySet())
    }

    fun updateColor(isSelected: Boolean, pathToSelection: Set<Promotion>, prerequisites: Set<PromotionTree.PromotionNode>) {
        if (atlasCard != null) {
            atlasCard.setSelected(isSelected)
            bgColor = GameUiStyle.inset
            bgBorderColor = when {
                isSelected -> GameUiStyle.selection
                node.promotion in pathToSelection -> GameUiStyle.accent
                node in prerequisites -> GameUiStyle.info
                else -> GameUiStyle.muted
            }
            return
        }
        bgColor = when {
            isSelected -> colors.selected
            node.isAdopted -> colors.promoted
            node.promotion in pathToSelection -> colors.pathToSelection
            node in prerequisites -> colors.prerequisite
            isPickable -> colors.pickable
            else -> colors.default
        }

        label.style = if (!isSelected && node.isAdopted) adoptedLabelStyle
            else defaultLabelStyle
    }

}
