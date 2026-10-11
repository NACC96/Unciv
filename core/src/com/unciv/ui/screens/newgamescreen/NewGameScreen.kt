package com.unciv.ui.screens.newgamescreen

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.ui.TextField
import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.GameStarter
import com.unciv.logic.IdChecker
import com.unciv.logic.UncivShowableException
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.files.MapSaver
import com.unciv.logic.map.MapGeneratedMainType
import com.unciv.logic.multiplayer.Multiplayer
import com.unciv.logic.multiplayer.storage.FileStorageRateLimitReached
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.metadata.GameSetupInfo
import com.unciv.models.metadata.Player
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.addSeparator
import com.unciv.ui.components.extensions.addSeparatorVertical
import com.unciv.ui.components.extensions.pad
import com.unciv.ui.components.extensions.setFontColor
import com.unciv.ui.components.extensions.setFontSize
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.GameUiStyle
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.ExpanderTab
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.ConfirmPopup
import com.unciv.ui.popups.Popup
import com.unciv.ui.popups.ToastPopup
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.basescreen.RecreateOnResize
import com.unciv.ui.screens.pickerscreens.PickerScreen
import com.unciv.ui.screens.pickerscreens.PickerPane
import com.unciv.utils.Concurrency
import com.unciv.utils.Log
import com.unciv.utils.isUUID
import com.unciv.utils.launchOnGLThread
import kotlinx.coroutines.coroutineScope
import java.net.URI
import kotlin.math.floor
import com.badlogic.gdx.utils.Align
import com.unciv.ui.components.widgets.AutoScrollPane as ScrollPane

class NewGameScreen(
    defaultGameSetupInfo: GameSetupInfo? = null,
    private val savedAgentSlots: List<com.unciv.logic.files.PresetAgentSlot> = emptyList()
): IPreviousScreen, PickerScreen(), RecreateOnResize {

    override val gameSetupInfo = defaultGameSetupInfo ?: GameSetupInfo.fromSettings()
    override val ruleset = Ruleset()  // updateRuleset will clear and add
    private val newGameOptionsTable: GameOptionsTable
    internal val playerPickerTable: PlayerPickerTable
    private val mapOptionsTable: MapOptionsTable
    private var mapOptionsTableInitialized = false
    private var startBlockedReason: String? = null

    init {
        val isPortrait = usesStackedLayout(stage.width, stage.height)

        // The mods loaded here may come from the last-started game (see GameSetupInfo.fromSettings) -
        // if that combination is now broken (e.g. a mod was updated/removed), silently fall back to
        // defaults instead of opening straight into an unusable, error-flagged mod selection.
        if (defaultGameSetupInfo == null) resetIfInitialModsAreBroken()

        tryUpdateRuleset(updateUI = false)  // must come before playerPickerTable so mod nations from fromSettings

        // remove the victory types which are not in the rule set (e.g. were in the recently disabled mod)
        gameSetupInfo.gameParameters.victoryTypes.removeAll { it !in ruleset.victories.keys }

        if (gameSetupInfo.gameParameters.victoryTypes.isEmpty())
            gameSetupInfo.gameParameters.victoryTypes.addAll(ruleset.selectableVictories().map { it.name })

        setStartEnabled(true)  // PlayerPickerTable init may disable this same visible action
        playerPickerTable = PlayerPickerTable(
            this, gameSetupInfo.gameParameters,
            if (isPortrait) stackedContentWidth(stage.width) else 0f
        )
        playerPickerTable.restoreAgentSlots(savedAgentSlots)
        newGameOptionsTable = GameOptionsTable(
            this, isPortrait,
            updatePlayerPickerTable = { desiredCiv -> playerPickerTable.update(desiredCiv) },
            updatePlayerPickerRandomLabel = { playerPickerTable.updateRandomNumberLabel() }
        )
        mapOptionsTable = MapOptionsTable(this)
        mapOptionsTableInitialized = true
        closeButton.onActivation {
            mapOptionsTable.cancelBackgroundJobs()
            game.popScreen()
        }
        closeButton.keyShortcuts.add(KeyCharAndCode.BACK)

        if (isPortrait) initPortrait()
        else initLandscape()
        topTable.polishSetupWidgets()
        topTable.background = GameUiStyle.background("NewGameScreen/TopTable")

        val resetAction: (() -> Unit)? = if (UncivGame.Current.settings.lastGameSetup != null) {
            {
                ConfirmPopup(
                    this,
                    "Are you sure you want to reset all game options to defaults?",
                    "Reset to defaults",
                ) {
                    val gameSetupInfo = GameSetupInfo().apply {
                        gameParameters.espionageEnabled = true
                    }
                    game.replaceCurrentScreen{ NewGameScreen(gameSetupInfo) }
                }.open(true)
            }
        } else null

        showStartReadiness(startBlockedReason)
        pickerPane.configureSetupFooter(stage.width, stage.height, { SetupPresetsPopup(this) }, resetAction)
        showStartReadiness(startBlockedReason)
        // No Enter shortcut: a key pressed in a seed or size field must never start a paid agent match.
        rightSideButton.onActivation(this::startGameAvoidANRs)
    }

    private fun startGameAvoidANRs(){
        // Don't allow players to click the game while we're checking if it's ok
        Gdx.input.inputProcessor = null
        mapOptionsTable.cancelBackgroundJobs()
        Concurrency.run {  // even just *checking* can take time
            val errorMessage = getErrorMessage()
            if (errorMessage != null){
                Concurrency.runOnGLThread {
                    val errorPopup = Popup(this@NewGameScreen)
                    errorPopup.addGoodSizedLabel(errorMessage).row()
                    errorPopup.addCloseButton()
                    errorPopup.open()
                    Gdx.input.inputProcessor = stage
                }
                return@run
            }

            // Requires a custom popup so can't be folded into getErrorMessage
            val modCheckResult = newGameOptionsTable.modCheckboxes.savedModcheckResult
            newGameOptionsTable.modCheckboxes.savedModcheckResult = null
            if (modCheckResult != null) {
                Concurrency.runOnGLThread {
                    AcceptModErrorsPopup(
                        this@NewGameScreen, modCheckResult,
                        action = {
                            gameSetupInfo.gameParameters.acceptedModCheckErrors = modCheckResult
                            startGameAvoidANRs()
                        }
                    )
                    Gdx.input.inputProcessor = stage
                }
                return@run
            }
            startGame()
        }
    }
    
    // Should be run NOT on main thread because it contacts MP server and loads maps etc
    fun getErrorMessage(): String? {
        if (gameSetupInfo.gameParameters.isOnlineMultiplayer) {
            if (!checkConnectionToMultiplayerServer())
                return if (Multiplayer.usesCustomServer()) "Couldn't connect to Multiplayer Server!"
                    else "Couldn't connect to Dropbox!"

            for (player in gameSetupInfo.gameParameters.players.filter { it.playerType == PlayerType.Human }) {
                if (!(IdChecker.checkAndReturnPlayerUuid(player.playerId)?.playerID?.isUUID() ?: false)) {
                    return "Invalid player ID!"
                }
            }

            if (!gameSetupInfo.gameParameters.anyoneCanSpectate) {
                if (gameSetupInfo.gameParameters.players.none { it.playerId == UncivGame.Current.settings.multiplayer.getUserId() })
                    return "You are not allowed to spectate!"
            }
        }

        if (gameSetupInfo.gameParameters.players.none {
                it.playerType == PlayerType.Human &&
                        // do not allow multiplayer with only spectator(s) and AI(s) - non-MP that works
                        !(it.chosenCiv == Constants.spectator && gameSetupInfo.gameParameters.isOnlineMultiplayer)
            }) return "No human players selected!"

        if (gameSetupInfo.gameParameters.victoryTypes.isEmpty()) return "No victory conditions were selected!"

        // Agent slots stay Human-typed (rule parity), so the human-count gate
        // above already admits all-agent matches. Their own contract —
        // Vanilla, no espionage/mods/random counts, configured providers —
        // is enforced here before creation.
        val agentErrors = playerPickerTable.collectAgentErrors()
        if (agentErrors.isNotEmpty()) return agentErrors.joinToString("\n")
        val bonusErrors = gameSetupInfo.gameParameters.players.flatMap { player ->
            player.startingBonuses.validate(ruleset, player.chosenCiv).map { "${player.chosenCiv}: $it" }
        }
        if (bonusErrors.isNotEmpty()) return bonusErrors.joinToString("\n")
        
        if (mapOptionsTable.mapTypeSelectBox.selected.value == MapGeneratedMainType.custom) {
            val map = try {
                MapSaver.loadMap(gameSetupInfo.mapFile!!)
            } catch (ex: Throwable) {
                return "Could not load map"
            }

            val rulesetIncompatibilities = map.getRulesetIncompatibility(ruleset)
            if (rulesetIncompatibilities.isNotEmpty())
                return "Map is incompatible with the chosen ruleset!".tr() + "\n" + rulesetIncompatibilities.joinToString("\n"){it.tr()}
        } else {
            // Generated map - check for sensible dimensions and if exceeded correct them and notify user
            val mapSize = gameSetupInfo.mapParameters.mapSize
            val message = mapSize.fixUndesiredSizes(gameSetupInfo.mapParameters.worldWrap)
            if (message != null) {
                with (mapOptionsTable.generatedMapOptionsTable) {
                    customMapSizeRadius.intValue = mapSize.radius
                    customMapWidth.intValue = mapSize.width
                    customMapHeight.intValue = mapSize.height
                }
                return message
            }
        }
        return null
    }
    
    private fun startGame() {

        Concurrency.runOnGLThread {
            setStartEnabled(false)
            rightSideButton.setText(Constants.working.tr())
            setSkin()
            
            // Creating a new game can take a while and we don't want ANRs
            Concurrency.runOnNonDaemonThreadPool("NewGame") {
                startNewGame()
            }
        }
    }

    /** Subtables may need an upper limit to their width - they can ask this function. */
    internal fun getColumnWidth() = floor(
        if (usesStackedLayout(stage.width, stage.height)) minOf(stage.width, stackedContentWidth(stage.width) + 20f)
        else stage.width / 3)

    internal fun setStartEnabled(enabled: Boolean) {
        GameUiStyle.apply(rightSideButton, primary = true)
        GameUiStyle.setEnabled(rightSideButton, enabled)
    }

    /** Explains a disabled Start in the footer; `null` restores the agent-start notice. */
    internal fun showStartReadiness(blockedReason: String?) {
        startBlockedReason = blockedReason
        val description = pickerPane.descriptionLabel
        description.setFontColor(if (blockedReason == null) GameUiStyle.muted else GameUiStyle.warning)
        description.setText((blockedReason ?: AGENT_START_NOTICE).tr())
    }

    companion object {
        private const val AGENT_START_NOTICE =
            "Starting an agent game begins automated play immediately using the selected provider account."
        /** Stacked setup keeps a readable measure instead of stretching cards across wide windows. */
        private const val MAX_STACKED_WIDTH = 720f

        /** Three option columns need readable controls even in a short landscape window. */
        internal fun usesStackedLayout(width: Float, height: Float) = width < 1080f || width < height * 4f / 3f

        internal fun stackedContentWidth(stageWidth: Float) = minOf(stageWidth - 20f, MAX_STACKED_WIDTH)
    }

    internal fun refreshExampleMap() {
        if (mapOptionsTableInitialized)
            mapOptionsTable.refreshExampleMap()
    }

    private fun initLandscape() {
        scrollPane.setScrollingDisabled(true,true)

        topTable.add("Game Options".toLabel(fontSize = Constants.headingFontSize)).pad(20f, 0f)
        topTable.addSeparatorVertical(ImageGetter.CHARCOAL, 1f)
        topTable.add("Map Options".toLabel(fontSize = Constants.headingFontSize)).pad(20f,0f)
        topTable.addSeparatorVertical(ImageGetter.CHARCOAL, 1f)
        topTable.add("Civilizations".toLabel(fontSize = Constants.headingFontSize)).pad(20f,0f)
        topTable.addSeparator(Color.CLEAR, height = 1f)

        topTable.add(ScrollPane(newGameOptionsTable)
                .apply { setOverscroll(false, false) })
                .width(stage.width / 3).top()
        topTable.addSeparatorVertical(Color.CLEAR, 1f)
        topTable.add(ScrollPane(mapOptionsTable)
                .apply { setOverscroll(false, false) })
                .width(stage.width / 3).top()
        topTable.addSeparatorVertical(Color.CLEAR, 1f)
        topTable.add(playerPickerTable)  // No ScrollPane, PlayerPickerTable has its own
                .width(stage.width / 3).top()
    }

    private fun initPortrait() {
        scrollPane.setScrollingDisabled(false,false)
        topTable.configureStackedSetup(newGameOptionsTable, mapOptionsTable, playerPickerTable)
    }

    private fun checkConnectionToMultiplayerServer(): Boolean {
        return try {
            val multiplayerServer = UncivGame.Current.settings.multiplayer.getServer()
            val u = URI(if (Multiplayer.usesDropbox()) "https://content.dropboxapi.com" else multiplayerServer).toURL()
            val con = u.openConnection()
            con.connectTimeout = 3000
            con.connect()

            true
        } catch(_: Throwable) {
            false
        }
    }

    private suspend fun startNewGame() = coroutineScope {
        val popup = Popup(this@NewGameScreen)
        launchOnGLThread {
            popup.addGoodSizedLabel(Constants.working).row()
            popup.open()
            ImageGetter.setNewRuleset(ruleset) // To build the temp atlases
        }

        val newGame:GameInfo
        try {
            val selectedScenario = mapOptionsTable.getSelectedScenario()
            newGame = if (selectedScenario == null)
                GameStarter.startNewGame(gameSetupInfo)
            else {
                val gameInfo = game.files.loadGameFromFile(selectedScenario.file)
                // Remove the Spectator - it was recommended by the wiki as Scenario builder
                gameInfo.civilizations.removeIf { it.civID == Constants.spectator }
                for (civ in gameInfo.civilizations) {
                    civ.playerType = PlayerType.AI
                    civ.diplomacy.remove(Constants.spectator)
                    civ.popupAlerts.removeIf { it.type == AlertType.FirstContact && it.value == Constants.spectator }
                }
                // Ergo the Spectator can't be chosen from NewGameScreen - make sure
                gameSetupInfo.gameParameters.players.removeIf { it.chosenCiv == Constants.spectator }
                // Now assign player types to explicit player Nation choices that exist in the game,
                // remembering which are already "used".
                // (at the moment NewGameScreen forbids such choices for scenarios, but let's support it here in case someone goes and does) 
                val randomPool = gameInfo.civilizations.filter { it.isMajorCiv() }.map { it.civID }.toMutableSet()
                fun Civilization.assign(playerInfo: Player) {
                    playerType = playerInfo.playerType
                    randomPool.remove(civID)
                }
                for (playerInfo in gameSetupInfo.gameParameters.players) {
                    if (playerInfo.chosenCiv == Constants.random) continue
                    gameInfo.getCivilizationOrNull(playerInfo.chosenCiv)?.assign(playerInfo)
                }
                // Now assign player types for "Random" entries
                for (playerInfo in gameSetupInfo.gameParameters.players) {
                    if (playerInfo.chosenCiv != Constants.random) continue
                    val civID = randomPool.randomOrNull() ?: continue
                    gameInfo.getCivilizationOrNull(civID)?.assign(playerInfo)
                }
                // If the Spectator was active when saved, skip it
                if (gameInfo.currentPlayer == Constants.spectator) {
                    gameInfo.currentPlayer = ""
                    gameInfo.nextTurn() // TODO Risky - triggers?
                    gameInfo.turns--
                }
                gameInfo
            }
        } catch (exception: Exception) {
            exception.printStackTrace()
            launchOnGLThread {
                popup.apply {
                    reuseWith("It looks like we can't make a map with the parameters you requested!")
                    row()
                    addGoodSizedLabel(if (exception is UncivShowableException) exception.localizedMessage
                        else "Maybe you put too many players into too small a map?").row()
                    addButton("Copy to clipboard"){
                        Gdx.app.clipboard.contents = exception.stackTraceToString()
                    }
                    addCloseButton()
                }
                Gdx.input.inputProcessor = stage
                setStartEnabled(true)
                rightSideButton.setText("Start game!".tr())
            }
            return@coroutineScope
        }

        if (gameSetupInfo.gameParameters.isOnlineMultiplayer) {
            newGame.isUpToDate = true // So we don't try to download it from dropbox the second after we upload it - the file is not yet ready for loading!
            try {
                game.onlineMultiplayer.createGame(newGame)
                game.files.autosaves.requestAutoSave(newGame)
            } catch (ex: FileStorageRateLimitReached) {
                launchOnGLThread {
                    popup.reuseWith("Server limit reached! Please wait for [${ex.limitRemainingSeconds}] seconds", true)
                    setStartEnabled(true)
                    rightSideButton.setText("Start game!".tr())
                }
                Gdx.input.inputProcessor = stage
                return@coroutineScope
            } catch (ex: Exception) {
                Log.error("Error while creating game", ex)
                launchOnGLThread {
                    popup.reuseWith("Could not upload game!", true)
                    setStartEnabled(true)
                    rightSideButton.setText("Start game!".tr())
                }
                Gdx.input.inputProcessor = stage
                return@coroutineScope
            }
        }

        val agentSetup = if (playerPickerTable.collectAgentErrors().isEmpty() && playerPickerTable.agentProfiles.isNotEmpty()) {
            playerPickerTable.buildAgentMatchSetup()
        } else null

        val worldScreen = game.loadGame(newGame, agentSetup = agentSetup)

        if (agentSetup != null) {
            worldScreen.agentSession?.startAutomaticPlay()
        }

        worldScreen.autoSave()

        if (newGame.gameParameters.isOnlineMultiplayer) {
            launchOnGLThread {
                    // Save gameId to clipboard because you have to do it anyway.
                    Gdx.app.clipboard.contents = newGame.gameId
                    // Popup to notify the User that the gameID got copied to the clipboard
                    ToastPopup("Game ID copied to clipboard!".tr(), worldScreen, 2500)
            }
        }
    }

    /** If the mod/baseRuleset combination inherited from [gameSetupInfo] is broken (Error severity),
     *  reset it to the default base ruleset with no mods, so we never build the UI around an
     *  unusable selection. */
    private fun resetIfInitialModsAreBroken() {
        val gameParameters = gameSetupInfo.gameParameters
        if (gameParameters.mods.isEmpty()) return
        val (_, errors) = RulesetCache.checkCombinedModLinks(gameParameters.mods, gameParameters.baseRuleset)
        if (!errors.isError()) return
        gameParameters.mods.clear()
        gameParameters.baseRuleset = BaseRuleset.Civ_V_GnK.fullName
    }

    /** Updates our local [ruleset] from [gameSetupInfo], guarding against exceptions.
     *
     *  Note: The options reset on failure is not propagated automatically to the Widgets -
     *  the caller must ensure that.
     *
     *  @return Success - failure means gameSetupInfo was reset to defaults and the Ruleset was reverted to G&K
     */
    fun tryUpdateRuleset(updateUI: Boolean): Boolean {
        var success = true
        fun handleFailure(message: String): Ruleset {
            success = false
            ToastPopup(message, this, 5000)
            gameSetupInfo.gameParameters.mods.clear()
            gameSetupInfo.gameParameters.baseRuleset = BaseRuleset.Civ_V_GnK.fullName
            return RulesetCache[BaseRuleset.Civ_V_GnK.fullName]!!
        }

        val newRuleset = try {
            // this can throw with non-default gameSetupInfo, e.g. when Mods change or we change the impact of Mod errors
            RulesetCache.getComplexRuleset(gameSetupInfo.gameParameters)
        } catch (ex: UncivShowableException) {
            handleFailure("«YELLOW»{Your previous options needed to be reset to defaults.}«»\n\n${ex.localizedMessage}")
        } catch (ex: Throwable) {
            Log.debug("updateRuleset failed", ex)
            handleFailure("«RED»{Your previous options needed to be reset to defaults.}«»")
        }

        ruleset.clear()
        ruleset.add(newRuleset)
        // Activate restored mod translations before constructing or updating the options tables.
        game.translations.translationActiveMods = gameSetupInfo.gameParameters.getModsAndBaseRuleset()
        ImageGetter.setNewRuleset(ruleset)
        game.musicController.setModList(gameSetupInfo.gameParameters.getModsAndBaseRuleset())

        if (updateUI) {
            newGameOptionsTable.updateRuleset(ruleset)
            topTable.polishSetupWidgets()
        }
        return success
    }

    fun lockTables() {
        playerPickerTable.locked = true
        newGameOptionsTable.locked = true
    }

    fun unlockTables() {
        playerPickerTable.locked = false
        newGameOptionsTable.locked = false
    }

    fun updateTables() {
        playerPickerTable.gameParameters = gameSetupInfo.gameParameters
        playerPickerTable.update()
        newGameOptionsTable.changeGameParameters(gameSetupInfo.gameParameters)
        newGameOptionsTable.update()
        topTable.polishSetupWidgets()
    }

    internal fun loadPreset(preset: com.unciv.logic.files.SetupPreset) {
        require(RulesetCache.containsKey(preset.setup.gameParameters.baseRuleset)) { "The preset's base ruleset is not installed." }
        require(preset.setup.gameParameters.mods.all { RulesetCache.containsKey(it) }) { "One or more of the preset's mods are not installed." }
        RulesetCache.getComplexRuleset(preset.setup.gameParameters)
        mapOptionsTable.cancelBackgroundJobs()
        game.replaceCurrentScreen { NewGameScreen(preset.setup, preset.agents) }
    }

    override fun recreate(): BaseScreen = NewGameScreen(gameSetupInfo, playerPickerTable.snapshotAgentSlots())
}

internal fun Table.configureStackedSetup(options: GameOptionsTable, map: MapOptionsTable, players: PlayerPickerTable) {
    // The nation cards already carry the capped stacked measure; every section shares it, centred.
    val contentWidth = players.civBlocksWidth + 20f
    add(ExpanderTab("Game Options") { it.add(options).row() }).width(contentWidth).row()
    addSeparator(GameUiStyle.hover, height = 1f).width(contentWidth)
    add(options.modCheckboxes).width(contentWidth).row()
    addSeparator(GameUiStyle.hover, height = 1f).width(contentWidth)
    add(ExpanderTab("Map Options") { it.add(map).row() }).width(contentWidth).row()
    addSeparator(GameUiStyle.hover, height = 1f).width(contentWidth)
    (players.playerListTable.parent as ScrollPane).setScrollingDisabled(true, true)
    add(ExpanderTab("Civilizations") { it.add(players).row() }).width(contentWidth).row()
}

/** Presentation-only traversal keeps option-table listeners and mod-specific contents intact. */
internal fun Actor.polishSetupWidgets() {
    if (this is PlayerPickerTable) return  // Nation cards already own their hierarchy and semantic colors.
    when (this) {
        is CheckBox -> {
            style = CheckBox.CheckBoxStyle(style).apply {
                fontColor = GameUiStyle.ink
                disabledFontColor = GameUiStyle.muted
            }
            label.setFontSize(14)
        }
        is TextButton -> {
            GameUiStyle.apply(this)
            GameUiStyle.setEnabled(this, !isDisabled)
        }
        is TextField -> polishSetupField()
        is SelectBox<*> -> polishSetupSelect()
        is Label -> if (style.fontColor == Color.WHITE) setFontColor(GameUiStyle.ink)
        is Table -> if (background != null)
            background = GameUiStyle.background("NewGameScreen/Options/${javaClass.simpleName}")
    }
    if (this is Group) children.forEach { it.polishSetupWidgets() }
}

/** Reuses the PickerPane action so readiness, working state and keyboard input share one widget. */
internal fun PickerPane.configureSetupFooter(
    width: Float,
    height: Float,
    onPresets: () -> Unit,
    onReset: (() -> Unit)? = null
) {
    GameUiStyle.apply(closeButton)
    GameUiStyle.apply(rightSideButton, primary = true)
    GameUiStyle.setEnabled(rightSideButton, !rightSideButton.isDisabled)
    rightSideButton.setText("Start game!".tr())
    rightSideButton.name = "SetupStart"
    // Back stands apart on the left; Presets separates the destructive Reset from the primary Start.
    val secondary = ArrayList<TextButton>()
    if (onReset != null) secondary.add(GameUiStyle.button("Reset to defaults").apply { onClick(onReset) })
    secondary.add(GameUiStyle.button("Presets").apply { onClick(onPresets) })
    val buttons = listOf(closeButton) + secondary + rightSideButton

    val gap = 8f
    val spacer = 16f
    val available = width - 16f - gap * buttons.size - spacer
    fun preferred(button: TextButton) =
        if (button === rightSideButton) maxOf(button.prefWidth, 150f) else button.prefWidth
    val preferredTotal = buttons.sumOf { preferred(it).toDouble() }.toFloat()
    // Natural widths when they fit; otherwise shrink proportionally so Start stays the widest.
    val scale = (available / preferredTotal).coerceAtMost(1f)

    val actions = Table().apply { name = "SetupFooterActions" }
    for (button in buttons) {
        button.label.wrap = scale < 1f
        button.label.setAlignment(Align.center)
        val cell = actions.add(button).width(preferred(button) * scale).fillY().pad(gap / 2)
        if (button === closeButton) cell.padRight(gap / 2 + spacer).expandX().left()
    }
    bottomTable.clear()
    bottomTable.background = GameUiStyle.background("NewGameScreen/BottomTable")
    bottomTable.pad(8f)
    // Measure the wrapped caption at its real available width. A fixed-height
    // description scroll pane clips its second line in short desktop windows.
    descriptionLabel.name = "SetupFooterDescription"
    descriptionLabel.setFontColor(GameUiStyle.muted)
    descriptionLabel.setAlignment(Align.left)
    descriptionLabel.width = width - 16f
    bottomTable.add(descriptionLabel).width(width - 16f).growX().padBottom(6f).row()
    bottomTable.add(actions).growX().row()
    // PickerPane normally reserves only 15% for the footer. A short desktop window
    // needs the actual fixed action height, with the option area taking the remainder.
    splitPane.maxSplitAmount = (1f - bottomTable.prefHeight / height).coerceIn(0.4f, 0.85f)
    invalidateHierarchy()
}
