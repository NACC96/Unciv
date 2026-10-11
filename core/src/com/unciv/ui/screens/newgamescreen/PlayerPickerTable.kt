package com.unciv.ui.screens.newgamescreen

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.logic.IdChecker
import com.unciv.logic.agents.AgentProfile
import com.unciv.logic.agents.AgentSlotLogic
import com.unciv.logic.agents.SlotControl
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.civilization.PlayerType.AI
import com.unciv.logic.civilization.PlayerType.Human
import com.unciv.logic.multiplayer.FriendList
import com.unciv.models.metadata.GameParameters
import com.unciv.models.metadata.GameSetupInfo
import com.unciv.models.metadata.Player
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.nation.Nation
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.translations.tr
import com.unciv.ui.components.GameUiStyle
import com.unciv.ui.components.extensions.setFontColor
import com.unciv.ui.components.extensions.surroundWithCircle
import com.unciv.ui.components.extensions.toCheckBox
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.UncivTextField
import com.unciv.ui.components.widgets.WrappableLabel
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.Popup
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.multiplayerscreens.FriendPickerList
import com.unciv.ui.screens.pickerscreens.PickerPane
import com.unciv.ui.screens.pickerscreens.PickerScreen
import com.unciv.utils.isUUID
import com.unciv.utils.removeRange
import com.unciv.ui.components.widgets.AutoScrollPane as ScrollPane

/**
 * This [Table] is used to pick or edit players information for new game creation.
 * Could be inserted to [NewGameScreen], or any other [BaseScreen]
 * which provides [GameSetupInfo] and [Ruleset].
 * Upon player changes updates property [gameParameters]. Also updates available nations when mod changes.
 * @param previousScreen A [Screen][BaseScreen] where the player table is inserted, should provide [GameSetupInfo] as property, updated when a player is added/deleted/changed
 * @param gameParameters contains info about number of players.
 * @param blockWidth sets a width for the Civ "blocks". If too small a third of the stage is used.
 */
class PlayerPickerTable(
    val previousScreen: IPreviousScreen,
    var gameParameters: GameParameters,
    blockWidth: Float = 0f
): Table() {
    val playerListTable = Table()
    val civBlocksWidth = if (blockWidth > 10f) blockWidth else
        if (NewGameScreen.usesStackedLayout(previousScreen.stage.width, previousScreen.stage.height))
            previousScreen.stage.width - 20f else previousScreen.stage.width / 3 - 12f
    private var randomNumberLabel: WrappableLabel? = null

    /** Locks player table for editing, currently unused, was previously used for scenarios and could be useful in the future. */
    var locked = false

    /**
     * External-agent assignments by slot. Presence in this map is the agent
     * distinction: agent slots stay [Human]-typed for rule parity, and
     * [AgentSlotLogic] keeps the two in sync. Profiles are edited through
     * [AgentProfilePopup] and snapshot-copied at match creation.
     */
    val agentProfiles = HashMap<Player, AgentProfile>()
    val profileCache = HashMap<Player, AgentProfile>()

    /** No random civilization is available, potentially used in the future during map editing. */
    var noRandom = false

    private val friendList = FriendList()

    init {
        for (player in gameParameters.players)
            player.playerId = "" // This is to stop people from getting other users' IDs and cheating with them in multiplayer games

        top()
        val watchAgentsBtn = GameUiStyle.button("Watch agents play (Spectator)").apply {
            label.wrap = true
            label.setAlignment(Align.center)
        }
        watchAgentsBtn.onClick { setupSpectatorAgentMatch() }
        add(watchAgentsBtn).width(civBlocksWidth - 16f).padTop(8f).padBottom(8f).row()

        val shuffle = "Shuffle Civ Order at Start".toCheckBox(gameParameters.shufflePlayerOrder) { gameParameters.shufflePlayerOrder = it }
        shuffle.polishSetupWidgets()
        shuffle.label.wrap = true
        add(shuffle).width(civBlocksWidth - 16f).padTop(2f).padBottom(8f).row()
        add(ScrollPane(playerListTable).apply { setOverscroll(false, false) }).width(civBlocksWidth)
        update()
        background = GameUiStyle.background("NewGameScreen/PlayerPickerTable")
    }

    /**
     * Updates view of main player table. Used when mod picked or player changed.
     * Also sets desired civilization, that is preferable for human players.
     * @param desiredCiv desired civilization name
     */
    fun update(desiredCiv: String = "") {
        playerListTable.clear()
        val ruleset = previousScreen.ruleset // the mod picking changes this ruleset

        reassignRemovedModReferences()

        fun isChoosable(nation: Nation) = (nation.isMajorCiv || nation.isSpectator) && !nation.hasUnique(UniqueType.WillNotBeChosenForNewGames)
        val newRulesetPlayableCivs = ruleset.nations.values.count(::isChoosable)

        if (gameParameters.players.size > newRulesetPlayableCivs)
            gameParameters.players.removeRange(newRulesetPlayableCivs, gameParameters.players.size)
        // Drop agent profiles of trimmed slots.
        agentProfiles.keys.retainAll(gameParameters.players.toSet())

        if (desiredCiv.isNotEmpty()) assignDesiredCiv(desiredCiv)

        for (player in gameParameters.players) {
            playerListTable.add(getPlayerTable(player)).width(civBlocksWidth).padBottom(10f).row()
        }

        val isRandomNumberOfPlayers = gameParameters.randomNumberOfPlayers
        if (isRandomNumberOfPlayers) {
            randomNumberLabel = WrappableLabel("", civBlocksWidth - 20f, Color.GOLD)
            playerListTable.add(randomNumberLabel).fillX().pad(0f, 10f, 20f, 10f).row()
            updateRandomNumberLabel()
        }

        if (!locked && gameParameters.players.size < newRulesetPlayableCivs) {
            val addPlayerButton = "+".toLabel(ImageGetter.CHARCOAL, 30)
                .apply { this.setAlignment(Align.center) }
                .surroundWithCircle(50f)
                .onClick {
                    // no random mode - add first not spectator civ if still available
                    val player = if (noRandom || isRandomNumberOfPlayers) {
                        val availableCiv = getAvailablePlayerCivs().firstOrNull()
                        if (availableCiv != null) Player(availableCiv)
                        // Spectators can only be Humans
                        else Player(Constants.spectator, PlayerType.Human).apply { setNationTransient(ruleset) }
                    } else Player()  // normal: add random AI
                    gameParameters.players.add(player)
                    update()
                }
            playerListTable.add(addPlayerButton).pad(10f)
        }

        // enable start game when at least one human player and they're not alone
        val humanPlayerCount = gameParameters.players.count { it.playerType == PlayerType.Human }
        val isValid = humanPlayerCount >= 1 && (isRandomNumberOfPlayers || gameParameters.players.size >= 2)
        if (previousScreen is NewGameScreen) previousScreen.setStartEnabled(isValid)
        else (previousScreen as? PickerScreen)?.setRightSideButtonEnabled(isValid)
    }

    /**
     * Agent-match readiness errors for [NewGameScreen.getErrorMessage]: slot
     * structure plus profile/ruleset validation. Empty when no agent slots
     * exist (ordinary games are unaffected).
     */
    fun collectAgentErrors(): List<String> {
        if (agentProfiles.isEmpty()) return emptyList()
        val errors = ArrayList(AgentSlotLogic.collectSlotErrors(gameParameters.players, agentProfiles))
        try {
            val setup = AgentSlotLogic.buildSetup(gameParameters.players, agentProfiles)
            errors.addAll(setup.validate(gameParameters))
        } catch (ex: IllegalArgumentException) {
            errors.add(ex.message ?: "Invalid agent configuration")
        }
        return errors
    }

    fun setupSpectatorAgentMatch() {
        gameParameters.players.clear()
        agentProfiles.clear()
        profileCache.clear()

        val spectator = Player(Constants.spectator, PlayerType.Human).apply {
            setNationTransient(previousScreen.ruleset)
        }
        gameParameters.players.add(spectator)

        val nations = previousScreen.ruleset.nations.values
            .filter { !it.isCityState && !it.isSpectator && it.name != Constants.random }
            .shuffled()
            .take(2)

        for (nation in nations) {
            val agentPlayer = Player(nation.name, PlayerType.Human).apply {
                setNationTransient(previousScreen.ruleset)
            }
            gameParameters.players.add(agentPlayer)
            val profile = AgentProfile.defaultFor(nation.name, "codex")
            agentProfiles[agentPlayer] = profile
            profileCache[agentPlayer] = profile
        }
        update()
    }

    fun onCivChanged(player: Player, oldCiv: String, newCiv: String) {
        val existing = agentProfiles[player] ?: profileCache[player]
        if (existing != null) {
            AgentSlotLogic.onCivChanged(player, oldCiv, newCiv, agentProfiles, profileCache)
        }
    }

    /** Snapshot of the current slot assignment for match creation. */
    fun buildAgentMatchSetup() = AgentSlotLogic.buildSetup(gameParameters.players, agentProfiles)

    fun snapshotAgentSlots(): List<com.unciv.logic.files.PresetAgentSlot> = gameParameters.players.mapIndexedNotNull { index, player ->
        val profile = agentProfiles[player] ?: profileCache[player] ?: return@mapIndexedNotNull null
        com.unciv.logic.files.PresetAgentSlot().also {
            it.index = index; it.active = player in agentProfiles; it.profile = profile.copy()
        }
    }

    fun restoreAgentSlots(slots: List<com.unciv.logic.files.PresetAgentSlot>) {
        for (slot in slots) {
            val player = gameParameters.players.getOrNull(slot.index) ?: continue
            val profile = slot.profile.copy()
            profileCache[player] = profile
            if (slot.active && player.chosenCiv != Constants.spectator) {
                player.playerType = PlayerType.Human
                agentProfiles[player] = profile
            }
        }
        if (slots.isNotEmpty()) update()
    }

    fun updateRandomNumberLabel() {        randomNumberLabel?.run {
            val playerRange = if (gameParameters.minNumberOfPlayers == gameParameters.maxNumberOfPlayers) {
                gameParameters.minNumberOfPlayers.tr()
            } else {
                "${gameParameters.minNumberOfPlayers} - ${gameParameters.maxNumberOfPlayers}"
            }
            val numberOfExplicitPlayersText = if (gameParameters.players.size == 1) {
                "The number of players will be adjusted"
            } else {
                "These [${gameParameters.players.size}] players will be adjusted"
            }
            val text = "[$numberOfExplicitPlayersText] to [$playerRange] actual players by adding random AI's or by randomly omitting AI's."
            wrap = false
            align(Align.center)
            setText(text.tr())
            wrap = true
        }
    }

    /**
     * Reassigns removed mod references to random civilization
     */
    private fun reassignRemovedModReferences() {
        for (player in gameParameters.players) {
            if (!previousScreen.ruleset.nations.containsKey(player.chosenCiv)
                || previousScreen.ruleset.nations[player.chosenCiv]!!.isCityState
                || previousScreen.ruleset.nations[player.chosenCiv]!!.hasUnique(UniqueType.WillNotBeChosenForNewGames))
                player.chosenCiv = Constants.random
        }
    }

    /**
     * Assigns desired civilization for human players with 'random' choice
     * @param desiredCiv string containing desired civilization name
     */
    private fun assignDesiredCiv(desiredCiv: String) {
        // No auto-select if desiredCiv already used
        if (gameParameters.players.any { it.chosenCiv == desiredCiv }) return
        // Do auto-select, silently no-op if no suitable slot (human with 'random' choice)
        val player = gameParameters.players.firstOrNull { it.chosenCiv == Constants.random && it.playerType == Human }
        player?.chosenCiv = desiredCiv
        player?.setNationTransient(previousScreen.ruleset)
    }

    /**
     * Creates [Table] for single player containing clickable
     * player type button ("AI" or "Human"), nation [Table]
     * and "-" remove player button.*
     * @param player for which [Table] is generated
     * @return [Table] containing the all the elements
     */
    private fun getPlayerTable(player: Player): Table {
        val playerTable = Table()
        playerTable.name = "SetupNation-${player.chosenCiv}"
        playerTable.pad(12f)
        playerTable.background = GameUiStyle.background("NewGameScreen/PlayerPickerTable/PlayerTable", GameUiStyle.raised)
        val contentWidth = (civBlocksWidth - 24f).coerceAtLeast(100f)

        val nationTable = getNationTable(player)
        playerTable.add(nationTable).width(contentWidth).left().row()
        val actions = Table().apply { name = "SetupNationActions" }
        val actionCount = if (player.chosenCiv == Constants.spectator) 1 else 2
        val actionWidth = (contentWidth - actionCount * 6f - if (locked) 0f else 36f) / actionCount

        val control = AgentSlotLogic.controlOf(player, agentProfiles)
        val playerTypeTextButton = GameUiStyle.button(player.playerType.name).apply {
            name = "SetupPlayerControl"
            label.wrap = true
        }
        // Agent slots stay Human-typed (rule parity); the label carries the distinction.
        if (control == SlotControl.AGENT)
            playerTypeTextButton.setText("Agent")
        actions.add(playerTypeTextButton).width(actionWidth).padRight(6f)

        if (player.chosenCiv != Constants.spectator) {
            val editAgentButton = GameUiStyle.button("Edit")
            editAgentButton.onClick {
                val profile = agentProfiles[player]
                if (profile != null) {
                    AgentProfilePopup(this@PlayerPickerTable, player, profile, onSave = { update() })
                } else NationSettingsPopup(this@PlayerPickerTable, player)
            }
            actions.add(editAgentButton).width(actionWidth).padRight(6f)
        }
        fun updatePlayerTypeButtonEnabled() {
            // This could be written much shorter with logical operators - I think this is readable
            val enabled = when {
                // Can always change AI to Human
                player.playerType == PlayerType.AI -> true
                // we cannot change Spectator player to AI type, robots not allowed to spectate :(
                player.chosenCiv == Constants.spectator -> false
                // In randomNumberOfPlayers mode, don't let the user choose random AI's
                gameParameters.randomNumberOfPlayers && player.chosenCiv == Constants.random -> false
                else -> true
            } && AgentSlotLogic.isSlotEditable(player, gameParameters.randomNumberOfPlayers)
            GameUiStyle.setEnabled(playerTypeTextButton, enabled)
        }
        updatePlayerTypeButtonEnabled()

        nationTable.onClick {
            if (locked) return@onClick
            val noRandom = noRandom ||
                    gameParameters.randomNumberOfPlayers && player.playerType == PlayerType.AI
            popupNationPicker(player, noRandom)
            updatePlayerTypeButtonEnabled()
        }
        playerTypeTextButton.onClick {
            val next = AgentSlotLogic.nextControl(AgentSlotLogic.controlOf(player, agentProfiles))
            AgentSlotLogic.applyControl(player, next, agentProfiles, profileCache)
            if (next == SlotControl.AGENT)
                AgentProfilePopup(this@PlayerPickerTable, player,
                    agentProfiles[player]!!,
                    onSave = { update() })
            update()
        }

        if (!locked) {
            actions.add(GameUiStyle.button("-")
                .onClick {
                    gameParameters.players.remove(player)
                    AgentSlotLogic.forgetPlayer(player, agentProfiles)
                    profileCache.remove(player)
                    update()
                }
            ).width(36f)
        }
        playerTable.add(actions).width(contentWidth).left().padTop(8f).padBottom(6f).row()

        if (control == SlotControl.AGENT) {
            val profile = agentProfiles[player]
            if (profile != null) {
                val thinking = profile.reasoning?.let { "Reasoning: $it" } ?: "Reasoning: default"
                val summaryText = "${profile.displayName} · ${profile.modelId} · $thinking"
                val summaryLabel = summaryText.toLabel(GameUiStyle.muted, 12).apply {
                    wrap = true
                    name = "SetupAgentSummary"
                }
                playerTable.add(summaryLabel).width(contentWidth).left().padBottom(6f).row()
            }
        }

        val bonuses = player.startingBonuses.summary()
        if (bonuses.isNotEmpty()) {
            playerTable.add(bonuses.toLabel(GameUiStyle.warning, 12).apply {
                wrap = true
                name = "SetupBonusSummary"
            }).width(contentWidth).left().padBottom(4f).row()
        }

        if (gameParameters.isOnlineMultiplayer && player.playerType == PlayerType.Human) {
            val multiplayer = Table()
            multiplayer.addPlayerTableMultiplayerControls(player)
            playerTable.add(multiplayer).width(contentWidth).fillX().row()
        }

        return playerTable
    }

    private fun Table.addPlayerTableMultiplayerControls(player: Player) {
        row()

        val playerIdTextField = UncivTextField("Please input Player ID!", player.playerId)
        add(playerIdTextField).colspan(2).fillX().pad(5f)
        val errorLabel = "✘".toLabel(Color.RED)
        add(errorLabel).pad(5f).row()

        fun onPlayerIdTextUpdated() {
            val friend = IdChecker.checkAndReturnPlayerUuid(playerIdTextField.text)
            if (friend?.playerID?.isUUID() ?: false) {
                player.playerId = playerIdTextField.text.trim()
                errorLabel.apply {
                    setText("✔") // U+2714 heavy checkmark
                    setFontColor(Color.GREEN)
                }
            } else {
                errorLabel.apply {
                    setText("✘") // U+2718 heavy ballot x
                    setFontColor(Color.RED)
                }
            }
        }
        onPlayerIdTextUpdated()
        playerIdTextField.addListener { onPlayerIdTextUpdated(); true }

        val currentUserId = UncivGame.Current.settings.multiplayer.getUserId()
        val setCurrentUserButton = "Set current user".toTextButton()
        setCurrentUserButton.onClick {
            playerIdTextField.text = currentUserId
            onPlayerIdTextUpdated()
        }
        add(setCurrentUserButton).colspan(3).fillX().pad(5f).row()

        val copyFromClipboardButton = "Player ID from clipboard".toTextButton()
        copyFromClipboardButton.onClick {
            playerIdTextField.text = Gdx.app.clipboard.contents
            onPlayerIdTextUpdated()
        }
        add(copyFromClipboardButton).right().colspan(3).fillX().pad(5f).row()

        //check if friends list is empty before adding the select friend button
        if (friendList.listOfFriends.isNotEmpty()) {
            val selectPlayerFromFriendsList = "Player ID from friends list".toTextButton()
            selectPlayerFromFriendsList.onClick {
                popupFriendPicker(player)
            }
            add(selectPlayerFromFriendsList).left().colspan(3).fillX().pad(5f)
        }
    }

    /**
     * Creates clickable icon and nation name for some [Player].
     * @param player [Player] for which generated
     * @return [Table] containing nation icon and name
     */
    private fun getNationTable(player: Player): Table {
        val nationTable = Table()
        val nationImageName = previousScreen.ruleset.nations[player.chosenCiv]
        val nationImage =
            if (nationImageName == null)
                ImageGetter.getRandomNationPortrait(40f)
            else ImageGetter.getNationPortrait(nationImageName, 40f)
        nationTable.add(nationImage).pad(5f)
        nationTable.add(player.chosenCiv.toLabel(GameUiStyle.ink, hideIcons = true).apply {
            wrap = true
            name = "SetupNationName"
        }).width((civBlocksWidth - 84f).coerceAtLeast(40f)).left().pad(5f)
        nationTable.touchable = Touchable.enabled
        return nationTable
    }

    /**
     * Opens Friend picking popup with all friends,
     * currently available for [player] to choose, depending on current
     * friends list and if another friend is selected.
     * @param player current player
     */
    private fun popupFriendPicker(player: Player) {
        FriendSelectionPopup(this, player, previousScreen as BaseScreen).open()
        update()
    }

    /**
     * Opens Nation picking popup with all nations,
     * currently available for [player] to choose, depending on current
     * ruleset and other players nation choice.
     * @param player current player
     */
    private fun popupNationPicker(player: Player, noRandom: Boolean) {
        NationPickerPopup(this, player, noRandom).open()
        update()
    }

    /**
     * Returns a list of available civilization for all players, according
     * to current ruleset, with exception of city states nations, spectator and barbarians.
     *
     * Skips nations already chosen by a player, unless parameter [dontSkipNation] says to keep a
     * specific one. That is used so the picker can be used to inspect and confirm the current selection.
     *
     * @return [Sequence] of available [Nation]s
     */
    internal fun getAvailablePlayerCivs(dontSkipNation: String? = null) =
        previousScreen.ruleset.nations.values.asSequence()
            .filter { it.isMajorCiv }
            .filterNot { it.hasUnique(UniqueType.WillNotBeChosenForNewGames) }
            .filter { it.name == dontSkipNation || gameParameters.players.none { player -> player.chosenCiv == it.name } }

    /**
     * Returns a list of available friends.
     * Skips friends already chosen.
     *
     * @return [Sequence] of available [FriendList.Friend]s
     */
    internal fun getAvailableFriends(): Sequence<FriendList.Friend> {
        val friendListWithRemovedFriends = friendList.listOfFriends.toMutableList()
        for (index in gameParameters.players.indices) {
            val currentFriendId = previousScreen.gameSetupInfo.gameParameters.players[index].playerId
            friendListWithRemovedFriends.remove(friendList.getFriendById(currentFriendId))
        }
        return friendListWithRemovedFriends.asSequence()
    }
}

class FriendSelectionPopup(
    private val playerPicker: PlayerPickerTable,
    player: Player,
    screen: BaseScreen,
) : Popup(screen) {

    private val pickerPane = PickerPane()
    private var selectedFriendId: String? = null

    init {
        val pickerCell = add()
            .width(700f).fillX().expandX()
            .minHeight(screen.stage.height * 0.5f)
            .maxHeight(screen.stage.height * 0.8f)

        val friendList = FriendPickerList(playerPicker, ::friendSelected)
        pickerPane.topTable.add(friendList)
        pickerPane.rightSideButton.setText("Select friend".tr())
        pickerPane.closeButton.onActivation(::close)
        pickerPane.closeButton.keyShortcuts.add(KeyCharAndCode.BACK)
        pickerCell.setActor(pickerPane)
        pickerPane.rightSideButton.onClick {
            close()
            val friendId = selectedFriendId
            if (friendId != null) {
                player.playerId = selectedFriendId.toString()
                close()
                playerPicker.update()
            }
        }

        clickBehindToClose = true
    }

    private fun friendSelected(friendName: String) {
        val friendsList = FriendList()
        val friend = friendsList.getFriendByName(friendName)
        if (friend != null) {
            selectedFriendId = friend.playerID
        }
        pickerPane.setRightSideButtonEnabled(true)
        pickerPane.rightSideButton.setText("Select [$friendName]".tr())
    }

}
