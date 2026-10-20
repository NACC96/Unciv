package com.unciv.ui.screens.pickerscreens

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.unciv.logic.civilization.managers.TechManager
import com.unciv.models.translations.tr
import com.unciv.ui.objectdescriptions.TechnologyDescriptions
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.components.extensions.addBorder
import com.unciv.ui.components.extensions.brighten
import com.unciv.ui.components.extensions.center
import com.unciv.ui.components.extensions.centerY
import com.unciv.ui.components.extensions.darken
import com.unciv.ui.components.extensions.setFontSize
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.GameUiStyle
import com.unciv.ui.components.atlas.AtlasChoiceState

class TechButton(
    techName: String,
    private val techManager: TechManager,
    isWorldScreen: Boolean = true
) : Table(BaseScreen.skin) {

    internal val text = "".toLabel().apply {
        wrap = false
        setFontSize(14)
        setAlignment(Align.left)
        setEllipsis(true)
    }

    internal val turns = "".toLabel().apply {
        setFontSize(14)
        setAlignment(Align.right)
    }

    private val backgroundImage: Image  // Table.background is the border
    private val choiceStatus = "".toLabel(GameUiStyle.muted, GameUiStyle.Type.small)
    private val atlasNode = GameUiStyle.isAtlas && !isWorldScreen
    /** The world screen's research card: a frosted card with a slim brass progress rail. */
    private val atlasTracker = GameUiStyle.isAtlas && isWorldScreen

    init {
        touchable = Touchable.enabled

        val path = "TechPickerScreen/TechButton"
        val default = BaseScreen.skinStrings.roundedEdgeRectangleMidShape
        backgroundImage = object : Image(BaseScreen.skinStrings.getUiBackground(path, default)){
            override fun draw(batch: Batch?, parentAlpha: Float) = super.draw(batch, parentAlpha)
        }
        background = BaseScreen.skinStrings.getUiBackground(path, default, Color.WHITE.darken(0.3f))

        addActor(backgroundImage)

        pad(5f, 5f, 5f, 0f)

        add(ImageGetter.getTechIconPortrait(techName, 46f))
            .padRight(5f).padLeft(2f).left()

        if (isWorldScreen) {
            val techCost = techManager.costOfTech(techName)
            val remainingTech = techManager.remainingScienceToTech(techName)
            val techThisTurn = techManager.civInfo.stats.statsForNextTurn.science

            val percentComplete = (techCost - remainingTech) / techCost.toFloat()
            val percentWillBeComplete = (techCost - (remainingTech-techThisTurn)) / techCost.toFloat()
            if (atlasTracker) {
                val progressBar = ImageGetter.ProgressBar(3f, 44f, true)
                    .setBackground(GameUiStyle.raised)
                    .setSemiProgress(GameUiStyle.accent.apply { a = 0.45f }, percentWillBeComplete)
                    .setProgress(GameUiStyle.accent, percentComplete)
                add(progressBar).padLeft(2f).padRight(GameUiStyle.Space.m)
            } else {
                val progressBar = ImageGetter.ProgressBar(2f, 48f, true)
                    .setBackground(Color.WHITE)
                    .setSemiProgress(Color.BLUE.cpy().brighten(0.3f), percentWillBeComplete)
                    .setProgress(Color.BLUE.cpy().darken(0.5f), percentComplete)
                add(progressBar.addBorder(1f, Color.GRAY.cpy())).padLeft(0f).padRight(5f)
            }
        }

        val rightSide = object : Table() {
            override fun draw(batch: Batch?, parentAlpha: Float) = super.draw(batch, parentAlpha)
        }

        rightSide.add(text).width(140f).top().left().padRight(15f)
        rightSide.add(turns).width(40f).top().left().padRight(10f).row()

        addTechEnabledIcons(techName, rightSide)
        if (atlasNode) {
            text.wrap = true
            text.setEllipsis(false)
            text.color = GameUiStyle.ink
            turns.color = GameUiStyle.muted
            rightSide.add(choiceStatus).colspan(2).growX().left().padTop(GameUiStyle.Space.s).row()
            rightSide.add("${techManager.researchOfTech(techName)}/${techManager.costOfTech(techName)}".toLabel(
                GameUiStyle.muted, GameUiStyle.Type.small)).colspan(2).left().row()
            backgroundImage.isVisible = false
        }
        if (atlasTracker) {
            background = GameUiStyle.surface("Atlas/World/TechTracker")
            backgroundImage.isVisible = false
            pad(GameUiStyle.Space.m, GameUiStyle.Space.m, GameUiStyle.Space.m, GameUiStyle.Space.xs)
            text.color = GameUiStyle.ink
            turns.color = GameUiStyle.muted
        }

        rightSide.centerY(this)
        add(rightSide).expandX().left()
        
        // Render both Skin images adjacently to reduce a texture swap between them
        rightSide.toBack()
        backgroundImage.toBack()
        pack()

        backgroundImage.setSize(width - 3f, height - 3f)
        backgroundImage.align = Align.center
        backgroundImage.center(this)

        pack()
    }

    fun setButtonColor(color: Color) {
        if (atlasNode || atlasTracker) return
        backgroundImage.color = color
        pack()
    }

    internal fun setChoiceState(state: AtlasChoiceState, selected: Boolean, queued: Boolean) {
        if (!atlasNode) return
        val rim = when (state) {
            AtlasChoiceState.LOCKED -> GameUiStyle.muted
            AtlasChoiceState.AVAILABLE -> GameUiStyle.info
            AtlasChoiceState.CURRENT -> GameUiStyle.accent
            AtlasChoiceState.COMPLETED -> GameUiStyle.positive
        }
        choiceStatus.setText("${state.marker} ${state.caption.tr()}" + if (queued) " · ${"Queued".tr()}" else "")
        choiceStatus.color = rim
        text.color = GameUiStyle.ink
        background = GameUiStyle.outlinedBackground("Atlas/Technology/${state.name}",
            if (selected) GameUiStyle.pressed else GameUiStyle.inset,
            if (selected) GameUiStyle.selection else rim, if (selected) 2.5f else 1.5f)
    }

    private fun addTechEnabledIcons(techName: String, rightSide: Table) {
        val techEnabledIcons = object : Table(){
            init { align(Align.left) }
            override fun draw(batch: Batch?, parentAlpha: Float) = super.draw(batch, parentAlpha)
        }
        techEnabledIcons.background = BaseScreen.skinStrings.getUiBackground(
            "TechPickerScreen/TechButtonIconsOutline",
            BaseScreen.skinStrings.roundedEdgeRectangleSmallShape,
            tintColor = ImageGetter.CHARCOAL.cpy().apply { a = 0.7f }
        )
        techEnabledIcons.pad(2f, 10f, 2f, 0f)
        techEnabledIcons.defaults().padRight(5f)

        val civ = techManager.civInfo
        val tech = civ.gameInfo.ruleset.technologies[techName]!!

        TechnologyDescriptions.getTechEnabledIcons(tech, civ, techIconSize = 30f)
            .take(5)
            .forEach { techEnabledIcons.add(it) }

        rightSide.add(techEnabledIcons)
            .colspan(2)
            .minWidth(195f)
            .prefWidth(195f)
            .maxWidth(195f)
            .expandX().left().row()
        
        techEnabledIcons.toBack() // First thing in the table to render so the 2 skin textures are rendered back to back
    }

    override fun draw(batch: Batch?, parentAlpha: Float) = super.draw(batch, parentAlpha)

}
