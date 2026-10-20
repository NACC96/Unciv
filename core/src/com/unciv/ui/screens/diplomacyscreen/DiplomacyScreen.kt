package com.unciv.ui.screens.diplomacyscreen

import com.unciv.logic.agents.DeclareWarCommand

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.SplitPane
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.utils.Align
import com.unciv.ui.components.AtlasWidgets
import com.unciv.ui.components.GameUiStyle
import com.unciv.Constants
import com.unciv.GUI
import com.unciv.ui.screens.worldscreen.AgentSnapshotScreen
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.ui.screens.worldscreen.refreshAgentSnapshot
import com.unciv.view.GameView
import com.unciv.UncivGame
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.civilization.diplomacy.DiplomacyManager
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.civilization.diplomacy.RelationshipLevel
import com.unciv.logic.trade.Trade
import com.unciv.models.translations.tr
import com.unciv.ui.audio.MusicMood
import com.unciv.ui.audio.MusicTrackChooserFlags
import com.unciv.ui.components.extensions.addSeparator
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.extensions.getCloseButton
import com.unciv.ui.components.extensions.surroundWithCircle
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.components.input.KeyboardBinding
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.tilegroups.citybutton.InfluenceTable
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.ConfirmPopup
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.basescreen.RecreateOnResize
import com.unciv.view.CivView
import com.unciv.view.ForeignCivView
import kotlin.math.floor
import com.unciv.ui.components.widgets.AutoScrollPane as ScrollPane

/**
 * Creates the diplomacy screen for [viewingCivView].
 *
 * When [selectCivView] is given and [selectTrade] is not, that Civilization is selected as if clicked on the left side.
 * When [selectCivView] is given and [selectTrade] is not but [showTrade] is set, the [TradeTable] for that Civilization is shown.
 * When [selectCivView] and [selectTrade] are supplied, that Trade for that Civilization is selected, used for the counter-offer option from `TradePopup`.
 * Note calling this with [selectCivView] a City State and [selectTrade] supplied is **not allowed**.
 */
class DiplomacyScreen(
    internal val viewingCivView: CivView,
    private val selectCivView: ForeignCivView? = null,
    private val selectTrade: Trade? = null,
    private val showTrade: Boolean = selectTrade != null
): BaseScreen(), RecreateOnResize, AgentSnapshotScreen {
    override val snapshotGameInfo = viewingCivView.getCiv().gameInfo
    override val snapshotViewerCivId = game.worldScreen?.takeIf { it.gameInfo.gameId == snapshotGameInfo.gameId }?.currentViewerCivId() ?: viewingCivView.gameView.civView.civID
    private var selectedOtherCivId: String? = selectCivView?.civID
    private var activeTrade: TradeTable? = null

    override fun show() = refreshAgentSnapshot()

    override fun recreateForSnapshot(worldScreen: WorldScreen): BaseScreen? {
        val civ = worldScreen.gameInfo.civilizations.firstOrNull { it.civID == viewingCiv.civID } ?: return null
        if (!worldScreen.viewingCiv.isSpectator() && civ !== worldScreen.viewingCiv) return null
        val view = GameView(worldScreen.gameInfo, civ, worldScreen.viewingCiv.isSpectator())
        val other = worldScreen.gameInfo.civilizations.firstOrNull { it.civID == selectedOtherCivId && civ.knows(it) }
        val draft = activeTrade?.tradeView?.let { trade ->
            Trade().apply { ourOffers.addAll(trade.ourStagedOffers()); theirOffers.addAll(trade.theirStagedOffers()) }
        }
        // A newly committed proposal is loaded by TradeTable itself. Do not overwrite it
        // with the pre-dispatch draft (which may already have been cleared by its caller).
        val hasPendingOffer = other?.tradeRequests?.any { it.requestingCiv == civ.civID } == true
        return DiplomacyScreen(view.civView, other?.let { view.getForeignCivView(it) },
            draft.takeIf { other != null && !hasPendingOffer }, activeTrade != null && other != null).also {
            it.leftSideScroll.scrollY = leftSideScroll.scrollY
            it.leftSideScroll.updateVisualScroll()
        }
    }

    companion object {
        private const val nationIconSize = 100f
        private const val nationIconPad = 10f
        private const val closeButtonSize = 50f
        /** distance of the floating close button from the top and right */
        private const val closeButtonPad = 10f
    }

    internal val viewingCiv: Civilization = viewingCivView.getCiv()
    private val selectCiv: Civilization? = selectCivView?.getCiv()

    private val highlightColor: Color = clearColor.cpy().lerp(skin.getColor("color"), 0.333f)

    private val leftSideTable = Table().apply {
        background = skinStrings.getUiBackground("DiplomacyScreen/LeftSide", tintColor = clearColor)
    }
    private val leftSideScroll = ScrollPaneWithMinSize()

    private var highlightedCivButton: Table? = null
    private val highlightBackground = skinStrings.getUiBackground("DiplomacyScreen/SelectedCiv", tintColor = highlightColor)

    internal val rightSideTable = Table().apply {
        background = skinStrings.getUiBackground("DiplomacyScreen/RightSide", tintColor = highlightColor)
    }

    private val splitPane = SplitPaneCenteringLeftSide()

    private val closeButton = getCloseButton(closeButtonSize) { game.popScreen() }

    internal fun isNotPlayersTurn() = !GUI.isAllowedChangeState()

    init {
        // In cramped conditions, start the left side with enough width for nation icon and padding, but allow it to get squeezed until just the icon fits.
        // (and SplitPane will squeeze even beyond the minWidth our left side supplies - when the right side has a conflicting minWidth, then both get squeezed).
        splitPane.splitAmount = 0.2f.coerceAtLeast(leftSideScroll.prefWidth / stage.width)

        updateLeftSideTable(selectCiv)

        if (GameUiStyle.isAtlas) {
            val layout = Table().apply {
                setFillParent(true)
                background = GameUiStyle.background("Atlas/Diplomacy/Screen", GameUiStyle.surface)
                add(AtlasWidgets.header("Diplomacy", viewingCiv.civName)).growX().minWidth(0f).row()
                add(splitPane).grow().minWidth(0f).minHeight(0f).row()
                add(Table().apply {
                    background = GameUiStyle.panelBackground("Atlas/Diplomacy/Footer", 1f)
                    add(closeButton).size(GameUiStyle.standardControlHeight).pad(GameUiStyle.Space.m).right().expandX()
                }).growX()
            }
            stage.addActor(layout)
            layout.validate()
            splitPane.splitAmount = if (stage.width < 700f) 0.22f else 0.2f
            rightSideTable.add(AtlasWidgets.header("Choose a civilization")).growX().minWidth(0f)
        } else {
            splitPane.setFillParent(true)
            stage.addActor(splitPane)
            positionCloseButton()
            stage.addActor(closeButton)
        } // This must come after the split pane so it will be above, that the button will be clickable

        if (selectCiv != null) {
            if (showTrade) {
                val tradeTable = setTrade(selectCiv)
                if (selectTrade != null)
                    tradeTable.tradeView.setStagedTrade(selectTrade)
                tradeTable.offerColumnsTable.update()
            } else
                updateRightSide(selectCiv)
        }

        globalShortcuts.add(KeyboardBinding.Civilopedia) { openCivilopedia() }
    }

    override fun getCivilopediaRuleset() = viewingCivView.ruleset

    private inner class ScrollPaneWithMinSize : ScrollPane(leftSideTable) {
        // On cramped screens 20% default splitAmount can make the left side smaller than a nation icon.
        // Also, content changes of the right side may claim too much space, pushing the split further to the left.
        // This reduces some ugliness - but remember Portrait lies, its size parameter means the *inner* circle.
        override fun getMinWidth() = if (GameUiStyle.isAtlas) 0f else nationIconSize * 1.1f // See PortraitNation's borderSize = size*0.1f parameter and how it's used
        override fun getPrefWidth() = if (GameUiStyle.isAtlas) 190f else minWidth + 2 * nationIconPad
    }

    private inner class SplitPaneCenteringLeftSide : SplitPane(leftSideScroll, rightSideTable, GameUiStyle.isAtlas && stage.width < 700f, skin) {
        // A lot of effort for little effect, but noticeable on really cramped width.
        // SplitPane supports no events at all, but this centers the nation icons whenever splitAmount changes,
        // whether from touchDragged or clampSplitAmount (yes both actual methods in SplitPane).
        var lastSplitAmount = splitAmount
        override fun validate() {
            if (GameUiStyle.isAtlas && stage.width < 700f && height > 0f)
                splitAmount = (leftSideTable.prefHeight / height).coerceIn(0.16f, 0.4f)
            super.validate()
            if (splitAmount == lastSplitAmount) return
            lastSplitAmount = splitAmount
            leftSideScroll.scrollPercentX = 0.5f
        }
    }

    private fun positionCloseButton() {
        if (GameUiStyle.isAtlas) return
        closeButton.setPosition(stage.width - closeButtonPad, stage.height - closeButtonPad, Align.topRight)
    }

    internal fun updateLeftSideTable(selectCiv: Civilization?) {
        leftSideTable.clear()
        if (GameUiStyle.isAtlas) {
            leftSideTable.background = GameUiStyle.panelBackground("Atlas/Diplomacy/Nations", 1f)
            leftSideScroll.setScrollingDisabled(stage.width >= 700f, stage.width < 700f)
        }
        leftSideTable.add().padBottom(closeButtonPad).row()  // no default pad, and make distance of first civ to top same as for the close button

        var selectCivY = 0f

        for (civ in viewingCiv.diplomacyFunctions.getKnownCivsSorted()) {
            if (civ == selectCiv) {
                selectCivY = leftSideTable.prefHeight
            }

            val civIndicator = ImageGetter.getNationPortrait(civ.nation, if (GameUiStyle.isAtlas) 44f else nationIconSize)

            val diplomacy = civ.getDiplomacyManager(viewingCiv)!!
            val relationLevel = diplomacy.relationshipLevel()
            val relationshipIcon = if (civ.isCityState && relationLevel == RelationshipLevel.Ally)
                ImageGetter.getImage("OtherIcons/Star")
                    .surroundWithCircle(size = 30f, color = relationLevel.color).apply {
                        actor.color = Color.GOLD
                    }
            else
                ImageGetter.getCircle(
                    color = if (civ.isHuman() && viewingCiv.isHuman()) getHumanRelationshipColor(diplomacy)
                    else if (diplomacy.diplomaticStatus == DiplomaticStatus.DefensivePact) Color.PURPLE
                    else if (civ.isAtWarWith(viewingCiv)) Color.RED
                    else relationLevel.color,
                    size = 30f
                )
            civIndicator.addActor(relationshipIcon)

            if (civ.isCityState) {
                val innerColor = civ.gameInfo.ruleset.nations[civ.civName]!!.getInnerColor()
                val typeIcon = ImageGetter.getImage("CityStateIcons/"+civ.cityStateType.name)
                    .surroundWithCircle(size = 35f, color = innerColor).apply {
                        actor.color = ImageGetter.CHARCOAL
                    }
                civIndicator.addActor(typeIcon)
                typeIcon.y = floor(civIndicator.height - typeIcon.height)
                typeIcon.x = floor(civIndicator.width - typeIcon.width)
            }

            if (civ.isCityState && civ.questManager.haveQuestsFor(viewingCiv)) {
                val questIcon = ImageGetter.getImage(NotificationIcon.Quest)
                    .surroundWithCircle(size = 30f, color = Color.GOLDENROD)
                civIndicator.addActor(questIcon)
                questIcon.x = floor(civIndicator.width - questIcon.width)
            }

            val civNameLabel = civ.civName.toLabel(hideIcons = true)
            if (GameUiStyle.isAtlas) { civNameLabel.wrap = true; civNameLabel.color = GameUiStyle.ink }

            // The wrapper serves only to highlight the selected civ better
            val civButton = Table().apply {
                defaults().pad(if (GameUiStyle.isAtlas) GameUiStyle.Space.s else nationIconPad)
                add(civIndicator).row()
                add(civNameLabel).minWidth(0f).growX().row()
                if (GameUiStyle.isAtlas) add((if (civ.isAtWarWith(viewingCiv)) "War" else
                    if (civ.isHuman() && viewingCiv.isHuman()) getHumanRelationshipText(diplomacy)
                    else relationLevel.name).toLabel(GameUiStyle.muted).apply { wrap = true })
                    .growX().minWidth(0f).row()
                onClick {
                    updateRightSide(civ)
                    highlightCiv(this)
                }
                if (civ == selectCiv) highlightCiv(this)
            }

            if (GameUiStyle.isAtlas && stage.width < 700f)
                leftSideTable.add(civButton).width(132f).minWidth(0f).pad(GameUiStyle.Space.s)
            else leftSideTable.add(civButton).padBottom(20f - nationIconPad).growX().row()
        }

        if (selectCivY != 0f) {
            leftSideScroll.layout()
            leftSideScroll.scrollY = selectCivY + (nationIconSize + 2 * nationIconPad - stage.height) / 2
            leftSideScroll.updateVisualScroll()
        }
    }

    private fun highlightCiv(civButton: Table) {
        highlightedCivButton?.background = null
        civButton.background = if (GameUiStyle.isAtlas)
            GameUiStyle.outlinedBackground("Atlas/Diplomacy/SelectedNation", GameUiStyle.pressed, GameUiStyle.accent, 2f)
            else highlightBackground
        highlightedCivButton = civButton
    }

    internal fun updateRightSide(otherCiv: Civilization) {
        selectedOtherCivId = otherCiv.civID
        activeTrade = null
        rightSideTable.clear()
        UncivGame.Current.musicController.chooseTrack(otherCiv.civName,
            MusicMood.peaceOrWar(viewingCiv.isAtWarWith(otherCiv)),MusicTrackChooserFlags.setSelectNation)
        val content = if (otherCiv.isCityState) CityStateDiplomacyTable(this).getCityStateDiplomacyTable(otherCiv)
            else MajorCivDiplomacyTable(this).getMajorCivDiplomacyTable(otherCiv)
        showConversation(content)
    }

    //region Major Civ Diplomacy

    internal fun setTrade(otherCiv: Civilization): TradeTable {
        selectedOtherCivId = otherCiv.civID
        rightSideTable.clear()
        val tradeTable = TradeTable(viewingCivView, viewingCivView.gameView.getForeignCivView(otherCiv), this)
        activeTrade = tradeTable
        rightSideTable.add(tradeTable).grow().minWidth(0f).minHeight(0f)
        return tradeTable
    }

    /**
     * Helper function for updateLeftSideTable() and getHumanRelationshipTable() (human-human relationships only)
     * @param otherCivDiplomacyManager Other human player [DiplomacyManager]
     * @return Relationship color between two human players
     */
    private fun getHumanRelationshipColor(otherCivDiplomacyManager: DiplomacyManager): Color {
        // should ensure colors align with equivalent human-AI relationship colors (RelationshipLevel ?)
        // as of writing, RelationshipLevel colors are not appropriate IMO, so using hardcoded values for now
        return if (otherCivDiplomacyManager.diplomaticStatus == DiplomaticStatus.DefensivePact)
            Color.CYAN
        else if (otherCivDiplomacyManager.hasFlag(DiplomacyFlags.DeclarationOfFriendship))
            Color.GREEN
        else if (otherCivDiplomacyManager.diplomaticStatus == DiplomaticStatus.War)
            Color.RED
        else
            RelationshipLevel.Neutral.color
    }

    /**
     * Human-human relationships only. See also: getHumanRelationshipColor()
     * @param otherCivDiplomacyManager Other human player [DiplomacyManager]
     * @return Relationship text (e.g. "Friend")
     */
    private fun getHumanRelationshipText(otherCivDiplomacyManager: DiplomacyManager): String {
        return if (otherCivDiplomacyManager.diplomaticStatus == DiplomaticStatus.DefensivePact)
            Constants.defensivePact
        else if (otherCivDiplomacyManager.hasFlag(DiplomacyFlags.DeclarationOfFriendship))
            RelationshipLevel.Friend.name
        else if (otherCivDiplomacyManager.diplomaticStatus == DiplomaticStatus.War)
            RelationshipLevel.Enemy.name
        else
            RelationshipLevel.Neutral.name
    }

    /**
     * @param otherCivDiplomacyManager Other human player [DiplomacyManager]
     * @return Relationship [Table] for human vs human player only
     */
    internal fun getHumanRelationshipTable(otherCivDiplomacyManager: DiplomacyManager): Table {
        val relationshipTable = Table()
        val relationshipColor: Color = getHumanRelationshipColor(otherCivDiplomacyManager)
        val relationshipText: String = getHumanRelationshipText(otherCivDiplomacyManager)

        relationshipTable.add("{Our relationship}: ".toLabel())
        relationshipTable.add(relationshipText.toLabel(relationshipColor)).row()
        return relationshipTable
    }

    internal fun getRelationshipTable(otherCivDiplomacyManager: DiplomacyManager): Table {
        val relationshipTable = Table()

        val opinionOfUs =
            if (otherCivDiplomacyManager.civInfo.isCityState) otherCivDiplomacyManager.getInfluence().toInt()
            else otherCivDiplomacyManager.opinionOfOtherCiv().toInt()

        relationshipTable.add("{Our relationship}: ".toLabel())
        val relationshipLevel = otherCivDiplomacyManager.relationshipLevel()
        val relationshipText = relationshipLevel.name.tr() + " ($opinionOfUs)"
        val relationshipColor = when (relationshipLevel) {
            RelationshipLevel.Neutral -> Color.WHITE
            RelationshipLevel.Favorable, RelationshipLevel.Friend,
            RelationshipLevel.Ally -> Color.GREEN
            RelationshipLevel.Afraid -> Color.YELLOW
            else -> Color.RED
        }

        relationshipTable.add(relationshipText.toLabel(relationshipColor)).row()
        if (otherCivDiplomacyManager.civInfo.isCityState)
            relationshipTable.add(
                InfluenceTable(
                    otherCivDiplomacyManager.getInfluence(),
                    relationshipLevel,
                    200f, 10f
                )
            ).colspan(2).pad(5f)
        return relationshipTable
    }

    internal fun getDeclareWarButton(
        diplomacyManager: DiplomacyManager,
        otherCiv: Civilization
    ): TextButton {
        val declareWarButton = "Declare war".toTextButton(skin.get("negative", TextButton.TextButtonStyle::class.java))
        val turnsToPeaceTreaty = diplomacyManager.turnsToPeaceTreaty()
        if (turnsToPeaceTreaty > 0) {
            declareWarButton.disable()
            declareWarButton.setText(declareWarButton.text.toString() + " (${turnsToPeaceTreaty.tr()}${Fonts.turn})")
        }
        declareWarButton.onClick {
            ConfirmPopup(this, getDeclareWarButtonText(otherCiv), "Declare war") {
                // Agent matches route war declaration through the coordinator (§3.2).
                if (UncivGame.Current.worldScreen?.routeThroughAgentSession {
                        listOf({ r: Int ->
                            DeclareWarCommand(viewingCiv.civID, "ui-${System.nanoTime()}", r, otherCiv.civID)
                        })
                    } == true) return@ConfirmPopup

                diplomacyManager.declareWar()
                setRightSideFlavorText(otherCiv, otherCiv.nation.attacked, "Very well.")
                updateLeftSideTable(otherCiv)
                val music = UncivGame.Current.musicController
                music.chooseTrack(otherCiv.civName, MusicMood.War, MusicTrackChooserFlags.setSpecific)
                music.playVoice("${otherCiv.civName}.attacked")
            }.open()
        }
        if (isNotPlayersTurn()) declareWarButton.disable()
        return declareWarButton
    }

    private fun getDeclareWarButtonText(otherCiv: Civilization): String {
        val messageLines = arrayListOf<String>()
        messageLines += "Declare war on [${otherCiv.civName}]?"
        
        if (otherCiv.getDiplomacyManager(viewingCiv)!!.hasFlag(DiplomacyFlags.AgreedToNotAttackUs))
            messageLines += "This will break your promise to not attack them. Other leaders will view this unfavorably."
        
        // Tell the player who all will join the other side from defensive pacts
        val otherCivDefensivePactList = otherCiv.diplomacy.values.filter {
            otherCivDiploManager -> otherCivDiploManager.otherCiv != viewingCiv
            && otherCivDiploManager.diplomaticStatus == DiplomaticStatus.DefensivePact
            && !otherCivDiploManager.otherCiv.isAtWarWith(viewingCiv) }
            .map { it.otherCiv }

        // Defensive pact chains are not allowed now
        for (civ in otherCivDefensivePactList) {
            messageLines += if (viewingCiv.knows(civ)) {
                "[${civ.civName}] will also join them in the war"
            } else {
                "[An unknown civilization] will also join them in the war"
            }
        }

        // Tell the player that their defensive pacts will be canceled.
        for (civDiploManager in viewingCiv.diplomacy.values) {
            if (civDiploManager.otherCiv != otherCiv
                && civDiploManager.diplomaticStatus == DiplomaticStatus.DefensivePact
                && !otherCivDefensivePactList.contains(civDiploManager.otherCiv)) {
                messageLines += "This will cancel your defensive pact with [${civDiploManager.otherCiv.civName}]"
            }
        }
        return messageLines.joinToString("\n") { "{$it}" }
    }

    //endregion

    // response currently always gets "Very Well.", but that may expand in the future.
    @Suppress("SameParameterValue")
    internal fun setRightSideFlavorText(
        otherCiv: Civilization,
        flavorText: String,
        response: String
    ) {
        val diplomacyTable = Table()
        diplomacyTable.defaults().pad(10f)
        diplomacyTable.add(LeaderIntroTable(otherCiv))
        diplomacyTable.addSeparator()
        diplomacyTable.add(flavorText.toLabel()).row()

        val responseButton = response.toTextButton()
        responseButton.onActivation { updateRightSide(otherCiv) }
        responseButton.keyShortcuts.add(KeyCharAndCode.SPACE)
        diplomacyTable.add(responseButton)

        if (GameUiStyle.isAtlas) showConversation(diplomacyTable, responseButton)
        else { rightSideTable.clear(); rightSideTable.add(diplomacyTable) }
    }

    internal fun showConversation(content: Table, primary: TextButton? = null) {
        rightSideTable.clear()
        if (GameUiStyle.isAtlas) {
            val panel = if (content.name == "AtlasDiplomacyPanel") content
                else AtlasDiplomacyLayout.panel(content, primary, rightSideLabelWidth())
            rightSideTable.add(panel).grow().minWidth(0f).minHeight(0f)
        } else rightSideTable.add(ScrollPane(content)).height(stage.height)
    }

    internal fun getGoToOnMapButton(civilization: Civilization): TextButton {
        val goToOnMapButton = "Go to on map".toTextButton()
        goToOnMapButton.onClick {
            val worldScreen = UncivGame.Current.resetToWorldScreen()
            worldScreen.mapHolder.setCenterPosition(civilization.getCapital()!!.location.toHexCoord(), selectUnit = false)
        }
        return goToOnMapButton
    }

    /** Calculate a width for [TradeTable] two-column layout, called from [OfferColumnsTable]
     *
     *  _Caller is responsible to not exceed this **including its own padding**_
     */
    // Note breaking the rule above will squeeze the leftSideScroll to the left - cumulatively.
    internal fun getTradeColumnsWidth() = rightSideWidth() / 2

    /** Recommended Cell width for wrappable Labels spanning the right side, e.g. city-state protectors */
    internal fun rightSideLabelWidth() = rightSideWidth() - 40f

    private fun rightSideWidth(): Float {
        splitPane.validate() // Ensure rightSideTable is sized
        return rightSideTable.width
    }

    override fun resize(width: Int, height: Int) {
        super.resize(width, height)
        positionCloseButton()
    }

    override fun recreate(): BaseScreen {
        val other = viewingCiv.diplomacyFunctions.getKnownCivsSorted().firstOrNull { it.civID == selectedOtherCivId }
        val draft = activeTrade?.tradeView?.let { source -> Trade().apply {
            ourOffers.addAll(source.ourStagedOffers().map { it.copy() })
            theirOffers.addAll(source.theirStagedOffers().map { it.copy() })
        } }
        return DiplomacyScreen(viewingCivView, other?.let { viewingCivView.gameView.getForeignCivView(it) },
            draft, activeTrade != null).also {
            it.leftSideScroll.scrollY = leftSideScroll.scrollY
            it.leftSideScroll.scrollX = leftSideScroll.scrollX
            it.leftSideScroll.updateVisualScroll()
        }
    }
}
