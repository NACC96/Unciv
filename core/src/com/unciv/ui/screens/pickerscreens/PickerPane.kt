package com.unciv.ui.screens.pickerscreens

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.SplitPane
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.VerticalGroup
import com.unciv.Constants
import com.unciv.GUI
import com.unciv.ui.components.AtlasWidgets
import com.unciv.ui.components.GameUiStyle
import com.unciv.ui.components.atlas.AtlasChoiceCard
import com.unciv.ui.components.atlas.AtlasChoiceState
import com.unciv.ui.images.IconTextButton
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.extensions.enable
import com.unciv.ui.components.extensions.isEnabled
import com.unciv.ui.components.extensions.toTextButton

class PickerPane(
    disableScroll: Boolean = false,
) : Table() {
    /** The close button on the lower left of [bottomTable], see [PickerScreen.setDefaultCloseAction].
     *  Note if you don't use that helper, you'll need to do both click and keyboard support yourself. */
    val closeButton = Constants.close.toTextButton()
    /** A scrollable wrapped Label you can use to show descriptions in the [bottomTable], starts empty */
    val descriptionLabel = DescriptionLabel()
    /** Wraps descriptionLabel - a subclass may replace the content, but beware: zero padding around this */
    internal val descriptionScroll: AutoScrollPane
    /** A wrapper containing [rightSideButton]. You can add buttons, they will be arranged vertically */
    val rightSideGroup = VerticalGroup()
    /** A button on the lower right of [bottomTable] you can use for a "OK"-type action, starts disabled */
    val rightSideButton = "".toTextButton()

    private val screenSplit = 0.85f
    internal var maxBottomTableHeight = 150f   // about 7 lines of normal text; setup can reserve larger captions
    /** Bespoke screen footers retain their own action composition and width constraints. */
    internal var hasCustomFooter = false

    /**
     * The table displaying the choices from which to pick (usually).
     * Also the element which most of the screen real estate is devoted to displaying.
     */
    val topTable = Table()
    /** Holds the [Close button][closeButton], a [description label][descriptionLabel] and an [action button][rightSideButton] */
    val bottomTable = Table()
    /** A ScrollPane scrolling [topTable], disabled by the disableScroll parameter */
    val scrollPane = AutoScrollPane(topTable)
    /** A fixed SplitPane holds [scrollPane] and [bottomTable] */
    val splitPane = SplitPane(scrollPane, bottomTable, true, BaseScreen.skin)
    private val header = Table()

    init {
        descriptionLabel.wrap = true
        val descriptionWithPad = Table()
        descriptionWithPad.add(descriptionLabel).pad(10f).grow()
        descriptionScroll = AutoScrollPane(descriptionWithPad, BaseScreen.skin)
        rightSideButton.disable()
        rightSideGroup.addActor(rightSideButton)

        if (GameUiStyle.isAtlas) {
            name = "AtlasChoicePane"
            background = GameUiStyle.background("Atlas/Choice/Screen", GameUiStyle.surface)
            bottomTable.background = GameUiStyle.panelBackground("Atlas/Choice/Footer", 1f)
            bottomTable.pad(GameUiStyle.Space.m)
            descriptionLabel.color = GameUiStyle.ink
            descriptionScroll.setScrollingDisabled(true, false)
            descriptionScroll.setOverscroll(false, false)
            bottomTable.add(descriptionScroll).colspan(2).grow().minHeight(0f).row()
            GameUiStyle.apply(closeButton)
            GameUiStyle.apply(rightSideButton, primary = true)
            closeButton.label.wrap = true
            rightSideButton.label.wrap = true
            rightSideGroup.fill()
            rightSideGroup.space(GameUiStyle.Space.s)
            bottomTable.add(closeButton).minWidth(0f).minHeight(GameUiStyle.standardControlHeight)
                .fillX().padRight(GameUiStyle.Space.m)
            bottomTable.add(rightSideGroup).minWidth(0f).growX()
                .minHeight(GameUiStyle.standardControlHeight)
            add(header).growX().minWidth(0f).row()
        } else {
            bottomTable.add(closeButton).pad(10f)
            bottomTable.add(descriptionScroll).grow()
            bottomTable.add(rightSideGroup).pad(10f).right()
        }

        scrollPane.setScrollingDisabled(disableScroll, disableScroll)  // lock scrollPane
        if (disableScroll) scrollPane.clearListeners()  // remove focus capture of AutoScrollPane too
        add(splitPane).expand().fill()
    }

    override fun layout() {
        if (GameUiStyle.isAtlas) {
            if (hasCustomFooter) {
                val available = (height - header.prefHeight).coerceAtLeast(1f)
                val footerHeight = bottomTable.prefHeight.coerceAtMost(available * 0.6f)
                splitPane.setMinSplitAmount(0.2f)
                splitPane.setMaxSplitAmount(0.95f)
                splitPane.splitAmount = ((available - footerHeight) / available).coerceIn(0.2f, 0.95f)
                super.layout()
                return
            }
            val actionWidth = (width - 3 * GameUiStyle.Space.m).coerceAtLeast(1f)
            bottomTable.getCell(closeButton).width((actionWidth * 0.28f).coerceAtMost(160f))
            // Wrapping is width-driven; a long confirm label grows the fixed footer instead of
            // forcing the entire tree or belief list wider than the viewport.
            rightSideButton.labelCell.width((actionWidth * 0.72f - 2 * GameUiStyle.Space.l).coerceAtLeast(1f))
            val actionsHeight = maxOf(closeButton.prefHeight, rightSideGroup.prefHeight)
            val available = (height - header.prefHeight).coerceAtLeast(1f)
            val footerHeight = (actionsHeight + 88f + 2 * GameUiStyle.Space.m)
                .coerceAtMost(available * 0.48f).coerceAtLeast(actionsHeight + 2 * GameUiStyle.Space.m)
            splitPane.setMinSplitAmount(0.25f)
            splitPane.setMaxSplitAmount(0.92f)
            splitPane.splitAmount = ((available - footerHeight) / available).coerceIn(0.25f, 0.92f)
            super.layout()
            return
        }
        super.layout()
        bottomTable.height = bottomTable.height.coerceAtMost(maxBottomTableHeight)
        splitPane.splitAmount = (scrollPane.height / (scrollPane.height + bottomTable.height)).coerceAtLeast(screenSplit)
    }

    internal fun setHeader(title: String, subtitle: String? = null) {
        if (!GameUiStyle.isAtlas) return
        header.clearChildren()
        header.add(AtlasWidgets.header(title, subtitle)).growX().minWidth(0f)
        invalidateHierarchy()
    }

    /** Enables the [rightSideButton]. See [pick] for a way to set the text. */
    fun setRightSideButtonEnabled(enabled: Boolean) {
        rightSideButton.isEnabled = enabled
    }

    /** Sets the text of the [rightSideButton] (does not autotranslate) and enables it if it's the player's turn */
    fun pick(rightButtonText: String) {
        if (GUI.isMyTurn()) rightSideButton.enable()
        rightSideButton.setText(rightButtonText)
    }

    /** This Label adjusts the Y scroll of its ascendant ScrollPane when its text is set:
     *  - short texts stay top-aligned with [closeButton]
     *  - taller texts scroll some of the wrapper top padding out
     *  - still taller texts scroll most but not all of the top padding out
     *
     *  This also ensures vertical scroll is reset when changing description
     */
    inner class DescriptionLabel : Label("", BaseScreen.skin) {
        override fun setText(newText: CharSequence?) {
            super.setText(newText)
            descriptionScroll.validate()
            descriptionScroll.scrollY = (prefHeight + 10f - descriptionScroll.height).coerceIn(0f, 8f)
            descriptionScroll.updateVisualScroll()
        }
    }

    companion object {
        /** Icon size used in [getPickerOptionButton]. */
        const val pickerOptionIconSize = 30f
        /** Return a button for picker screens that display a list of big buttons with icons and labels. */
        fun getPickerOptionButton(icon: Actor, label: String, detail: String = "",
                                  state: AtlasChoiceState = AtlasChoiceState.AVAILABLE): Button {
            if (GameUiStyle.isAtlas) return Button(BaseScreen.skin).apply {
                name = "AtlasChoiceButton:$label"
                pad(0f)
                add(AtlasChoiceCard(label, detail, state) {}.withIcon(icon))
                    .growX().minWidth(0f)
            }
            return IconTextButton(label, icon).apply {
                iconCell.size(pickerOptionIconSize).pad(10f)
                labelCell.pad(10f)
            }
        }

        fun setChoiceSelected(button: Button, selected: Boolean) {
            button.children.filterIsInstance<AtlasChoiceCard>().firstOrNull()?.setSelected(selected)
        }
    }
}
