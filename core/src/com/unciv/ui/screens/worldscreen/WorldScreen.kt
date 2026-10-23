package com.unciv.ui.screens.worldscreen

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.utils.JsonReader
import com.unciv.logic.agents.AgentCommandException
import com.unciv.logic.agents.AgentCommandError
import com.unciv.logic.agents.ControllerKind
import com.unciv.logic.agents.migrateAgentMatchStateJson
import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.agents.AgentMatchSession
import com.unciv.logic.agents.AgentCommand
import com.unciv.logic.agents.AgentSnapshotPublication
import com.unciv.logic.agents.MatchVision
import com.unciv.logic.agents.ResolvedSelection
import com.unciv.view.GameView
import com.unciv.view.VisualEventProjector
import com.unciv.ui.screens.worldscreen.presentation.WorldVisualTimeline
import com.unciv.logic.presentation.*
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import com.unciv.logic.UncivShowableException
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.event.EventBus
import com.unciv.logic.map.HexCoord
import com.unciv.logic.multiplayer.MultiplayerGameUpdated
import com.unciv.logic.multiplayer.storage.FileStorageRateLimitReached
import com.unciv.logic.multiplayer.storage.MultiplayerAuthException
import com.unciv.logic.trade.TradeEvaluation
import com.unciv.models.TutorialTrigger
import com.unciv.models.metadata.GameSetupInfo
import com.unciv.models.ruleset.Event
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.ui.components.extensions.centerX
import com.unciv.ui.components.extensions.darken
import com.unciv.ui.components.input.KeyShortcutDispatcherVeto
import com.unciv.ui.components.input.KeyboardBinding
import com.unciv.ui.components.input.KeyboardPanningListener
import com.unciv.ui.components.input.onClick
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.AuthPopup
import com.unciv.ui.popups.Popup
import com.unciv.ui.popups.ToastPopup
import com.unciv.ui.popups.hasOpenPopups
import com.unciv.ui.popups.options.OptionsPopupPages
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.cityscreen.CityScreen
import com.unciv.ui.screens.devconsole.DevConsolePopup
import com.unciv.ui.screens.mainmenuscreen.MainMenuScreen
import com.unciv.ui.screens.newgamescreen.NewGameScreen
import com.unciv.ui.screens.overviewscreen.EmpireOverviewCategories
import com.unciv.ui.screens.overviewscreen.EmpireOverviewScreen
import com.unciv.ui.screens.pickerscreens.DiplomaticVoteResultScreen
import com.unciv.ui.screens.pickerscreens.GreatPersonPickerScreen
import com.unciv.ui.screens.savescreens.LoadGameScreen
import com.unciv.ui.screens.savescreens.QuickSave
import com.unciv.ui.screens.savescreens.SaveGameScreen
import com.unciv.ui.screens.victoryscreen.VictoryScreen
import com.unciv.ui.screens.worldscreen.bottombar.BattleTable
import com.unciv.ui.screens.worldscreen.bottombar.TileInfoTable
import com.unciv.ui.screens.worldscreen.chat.ChatButton
import com.unciv.ui.screens.worldscreen.mainmenu.WorldScreenMusicPopup
import com.unciv.ui.screens.worldscreen.minimap.MinimapHolder
import com.unciv.ui.screens.worldscreen.status.AutoPlayStatusButton
import com.unciv.ui.screens.worldscreen.status.AgentActivityFeed
import com.unciv.ui.screens.worldscreen.status.AgentNationSettingsPanel
import com.unciv.ui.screens.worldscreen.status.AgentMessagesPanel
import com.unciv.ui.screens.worldscreen.status.MultiplayerStatusButton
import com.unciv.ui.screens.worldscreen.status.NextTurnButton
import com.unciv.ui.screens.worldscreen.status.NextTurnProgress
import com.unciv.ui.screens.worldscreen.status.SmallUnitButton
import com.unciv.ui.screens.worldscreen.status.StatusButtons
import com.unciv.ui.screens.worldscreen.topbar.WorldScreenTopBar
import com.unciv.ui.screens.worldscreen.unit.AutoPlay
import com.unciv.ui.screens.worldscreen.unit.UnitTable
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActionsTable
import com.unciv.ui.screens.worldscreen.worldmap.WorldMapHolder
import com.unciv.ui.screens.worldscreen.worldmap.WorldMapTileUpdater.updateTiles
import com.unciv.utils.Concurrency
import com.unciv.utils.debug
import com.unciv.utils.launchOnGLThread
import com.unciv.utils.launchOnThreadPool
import com.unciv.utils.withGLContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import yairm210.purity.annotations.Readonly
import java.util.Timer
import kotlin.concurrent.timer

/**
 * Do not create this screen without seriously thinking about the implications: this is the single most memory-intensive class in the application.
 * There really should ever be only one in memory at the same time, likely managed by [UncivGame].
 *
 * @param gameInfo The game state the screen should represent
 * @param viewingCiv The currently active [civilization][Civilization]
 * @param restoreState
 */
class WorldScreen(
    gameInfo: GameInfo,
    val autoPlay: AutoPlay,
    viewingCiv: Civilization,
    restoreState: RestoreState? = null
) : BaseScreen() {
    /** When set, causes the screen to update in the next [render][render] event */
    var shouldUpdate = false

    /**
     * The game state the screen represents. Agent-match snapshot publication
     * swaps this to the coordinator's committed snapshot (see
     * [installAgentSnapshot]); ordinary games never reassign it.
     */
    var gameInfo: GameInfo = gameInfo
        internal set

    /** Active civilization identity — re-pointed into each published snapshot. */
    var viewingCiv: Civilization = viewingCiv
        internal set

    /** Indicates it's the player's ([viewingCiv]) turn */
    var isPlayersTurn = viewingCiv.isCurrentPlayer()
        internal set     // only this class is allowed to make changes
    
    /** Indicates that a game failed to upload, and needs to be uploaded */
    var failedUpload = false
        private set

    /** Defers reopening an unfulfilled free-Great-Person choice until the player requests it. */
    internal var deferFreeGreatPersonPicker = false

    /** Selected civilization, used in spectator and replay mode, equals viewingCiv in ordinary games */
    var selectedCiv = viewingCiv
        internal set
    /** The [selectedCiv]'s perspective. For non-spectators this this equals the viewingGameView.
     *  Only ever differs from [unfoggedGameView] for viewers with [hasSpectatorVision]. */
    var selectedGameView = perspectiveOf(gameInfo, selectedCiv)
        internal set

    /** [civ]'s own perspective, widened by the agent match's map vision. Spectators always see everything unfogged. */
    private fun perspectiveOf(game: GameInfo, civ: Civilization,
                              spectatorMode: Boolean = viewingCiv.isSpectator(),
                              vision: MatchVision = agentSession?.getVision() ?: MatchVision.FOG): GameView {
        val revealed = !civ.isSpectator()
        return GameView(game, civ, spectatorMode,
            revealMap = revealed && vision != MatchVision.FOG, revealUnits = revealed && vision == MatchVision.FULL)
    }

    /** The unrestricted perspective behind the fog toggle, for specific UI elements only: a spectator's own
     *  view, or the played nation's own view with the map revealed. Null without [hasSpectatorVision]. */
    private var cachedUnfoggedGameView: GameView? = null
    private val unfoggedGameView get(): GameView? {
        if (!hasSpectatorVision()) return null
        cachedUnfoggedGameView?.let { return it }
        val view = when {
            viewingCiv.isSpectator() -> GameView(gameInfo, viewingCiv, spectatorMode = true)
            hasSpectatorVision() -> GameView(gameInfo, viewingCiv, revealMap = true)
            else -> return null
        }
        return view.also { cachedUnfoggedGameView = it }
    }
    fun getGameViewConsideringForOfWar() = if (fogOfWar) selectedGameView else unfoggedGameView ?: selectedGameView

    /** Spectators, including the local spectator while playing a nation they took over, may lift fog of war. */
    fun hasSpectatorVision(): Boolean = viewingCiv.isSpectator() || agentSession?.canSpectate() == true

    /** UI toggle only, kept across takeovers: whether the map/tile panel should show [selectedGameView]'s
     *  (fogged) vision or [unfoggedGameView]'s. Without spectator vision it has no effect. */
    var fogOfWar = true
        set(value) {
            if (field == value) return
            field = value
            refreshVisualView(force = true)
        }

    /**
     * Active agent match session, if this screen shows an agent-enabled
     * match. Routes human gameplay through committed coordinator actions and
     * gates input by the active controller. The bounded Vanilla route audit and
     * remaining unsupported paths are documented in docs/agent-mutation-audit.md.
     */
    var agentSession: AgentMatchSession? = null
        set(value) {
            if (field === value) return
            field = value
            selectedGameView = perspectiveOf(gameInfo, selectedCiv)
            cachedUnfoggedGameView = null
            refreshVisualView(force = true)
        }

    companion object { private val visualEpochs = AtomicLong() }
    private val visualLock = Any()
    private val visualAuthoritySerial = AtomicLong()
    private val visualViewState = AtomicReference(VisualViewKey(viewingCiv.civID, viewingCiv.isSpectator(), visualEpochs.incrementAndGet()))
    private var visualView: VisualViewKey
        get() = visualViewState.get()
        set(value) { visualViewState.set(value) }
    @Volatile private var visualDisposed = false
    @Volatile private var visualSuspended = false
    @Volatile private var pendingVisualBatch: VisualBatch? = null
    private var ordinaryVisualSession: VisualCapture.Session? = null
    private var ordinaryVisualCursor = 0L
    private var installedVisualSequence = 0L
    private var installedVisualTimeline: String? = null
    /** V3 must compare this before rendering effects and reconcile whenever it changes. */
    @Volatile var visualResetGeneration = 0L
        private set
    /** Desktop installs one asynchronous callback; never perform host work inline on the GL thread. */
    @Volatile var visualViewChangedListener: (() -> Unit)? = null

    private val visualTimelineDelegate = lazy { WorldVisualTimeline(this) }
    val visualTimeline: WorldVisualTimeline get() = visualTimelineDelegate.value

    fun usesSharedVisualPlayback(): Boolean = agentSession != null || ordinaryVisualSession != null

    /** GL-only cleanup, without forcing a cache or timeline to initialize on an unused/hidden screen. */
    fun reconcileVisualActors() {
        if (visualTimelineDelegate.isInitialized()) visualTimeline.reconcile()
    }

    fun currentVisualViewKey(): VisualViewKey = visualView
    fun isVisualTransportAvailable(): Boolean = !visualDisposed && !visualSuspended

    /** May be called under the host lock. Does not touch actors or perform GL work. */
    fun invalidateVisualAuthority(): VisualViewKey = synchronized(visualLock) {
        ordinaryVisualSession?.invalidate()
        ordinaryVisualSession = null
        VisualCapture.install(gameInfo, null)
        pendingVisualBatch = null
        ordinaryVisualCursor = 0L
        installedVisualSequence = 0L
        installedVisualTimeline = null
        visualResetGeneration++
        visualAuthoritySerial.incrementAndGet()
        visualView.copy(epoch = visualEpochs.incrementAndGet()).also { visualView = it }
    }

    /** Skip/resize clear queued work and start capture from the current committed state. GL thread only. */
    fun resetVisualPlayback() {
        reconcileVisualActors()
        invalidateVisualAuthority()
        if (agentSession == null) installOrdinaryCapture()
        visualViewChangedListener?.invoke()
    }

    private fun refreshVisualView(force: Boolean = false) {
        val effective = getGameViewConsideringForOfWar()
        val previous = currentVisualViewKey()
        if (!force && previous == visualKeyFor(effective, previous.epoch)) return
        synchronized(visualLock) {
            invalidateVisualAuthority()
            visualView = visualKeyFor(effective, visualView.epoch)
        }
        if (agentSession == null) installOrdinaryCapture()
        visualViewChangedListener?.invoke()
    }

    private fun visualKeyFor(view: GameView, epoch: Long) = VisualViewKey(
        view.civView.civID, view.spectatorMode, epoch, view.revealMap, view.revealUnits)

    private fun installOrdinaryCapture() = synchronized(visualLock) {
        if (visualDisposed || visualSuspended || agentSession != null || gameInfo.agentMatchStateJson != null) return@synchronized
        if (ordinaryVisualSession != null) return@synchronized
        val journal = VisualJournal(gameInfo.gameId)
        journal.reset(visualView)
        val authority = visualViewState
        val view = visualView
        ordinaryVisualSession = VisualCapture.Session(view, VisualEventProjector, journal) { authority.get() == view }.also {
            VisualCapture.install(gameInfo, it)
        }
        ordinaryVisualCursor = 0L
    }

    /** Bounded handoff to playback. Hidden/skipped screens and obsolete view epochs return nothing. */
    fun takeVisualBatch(): VisualBatch? = synchronized(visualLock) {
        val batch = pendingVisualBatch
        pendingVisualBatch = null
        batch?.takeIf { isVisualTransportAvailable() && it.view == visualView }
    }

    private fun installVisualBatch(batch: VisualBatch) = synchronized(visualLock) {
        if (!isVisualTransportAvailable() || batch.view != visualView) return@synchronized
        if (batch.events.any { it.view != visualView || it.id.gameId != gameInfo.gameId }) return@synchronized
        val timeline = batch.events.firstOrNull()?.id?.timelineId ?: installedVisualTimeline
        if (batch.events.any { it.id.timelineId != timeline }) return@synchronized
        if (installedVisualTimeline != null && installedVisualTimeline != timeline) {
            pendingVisualBatch = null
            installedVisualSequence = 0L
            visualResetGeneration++
        }
        installedVisualTimeline = timeline
        // Multiple snapshots may have been built before any GL acknowledgment. Do not replay their overlap,
        // including events the playback consumer already drained from the pending batch.
        val fresh = batch.events.filter { it.id.sequence > installedVisualSequence }
        val previous = pendingVisualBatch?.takeIf { it.view == batch.view }
        val all = previous?.events.orEmpty() + fresh
        val dropped = maxOf(previous?.droppedThroughSequence ?: 0L, batch.droppedThroughSequence,
            all.dropLast(VisualJournal.MAX_EVENTS).lastOrNull()?.id?.sequence ?: 0L)
        installedVisualSequence = maxOf(installedVisualSequence, batch.throughSequence)
        pendingVisualBatch = VisualBatch(batch.view, all.takeLast(VisualJournal.MAX_EVENTS), installedVisualSequence, dropped)
    }

    internal fun installOrdinaryTurnVisuals(batch: VisualBatch) = synchronized(visualLock) {
        val current = visualView
        if (agentSession != null || !isVisualTransportAvailable() || current.viewerCivId != batch.view.viewerCivId ||
            current.spectatorMode != batch.view.spectatorMode || current.revealMap != batch.view.revealMap ||
            current.revealUnits != batch.view.revealUnits || batch.events.isEmpty()) return@synchronized
        // Never replace actions already committed on the new screen with the preceding turn's handoff.
        if (ordinaryVisualSession?.journal?.readAfter(0L, current)?.throughSequence != 0L) return@synchronized
        val timeline = batch.events.first().id.timelineId
        if (batch.events.any { it.id.gameId != gameInfo.gameId || it.id.timelineId != timeline }) return@synchronized
        val rebound = batch.events.map { it.withView(current) }
        ordinaryVisualSession?.invalidate()
        val journal = VisualJournal(gameInfo.gameId, timeline)
        journal.reset(current)
        journal.appendCommitted(rebound)
        val authority = visualViewState
        ordinaryVisualSession = VisualCapture.Session(current, VisualEventProjector, journal) { authority.get() == current }.also {
            VisualCapture.install(gameInfo, it)
        }
        installVisualBatch(VisualBatch(current, rebound, batch.throughSequence, batch.droppedThroughSequence))
        ordinaryVisualCursor = batch.throughSequence
    }

    private fun suspendVisuals() = synchronized(visualLock) {
        visualSuspended = true
        ordinaryVisualSession?.invalidate()
        ordinaryVisualSession = null
        VisualCapture.install(gameInfo, null)
        pendingVisualBatch = null
        installedVisualSequence = 0L
        installedVisualTimeline = null
        visualView = visualView.copy(epoch = visualEpochs.incrementAndGet())
        visualResetGeneration++
    }

    /** The session owns asynchronous work, so detach it before releasing this screen's resources. */
    fun closeAgentSession() {
        val session = agentSession ?: return
        agentSession = null
        session.close()
    }

    private var civilizationOverview: com.unciv.ui.screens.worldscreen.overview.CivilizationOverviewPanel? = null

    fun toggleCivilizationOverview() {
        val panel = civilizationOverview
        if (panel == null || !panel.isVisible) {
            // These floating detail panels share the right side of the map; Activity remains independent.
            stage.root.children.filterIsInstance<AgentMessagesPanel>().forEach { it.hide() }
            stage.root.children.filterIsInstance<AgentNationSettingsPanel>().forEach { it.hide() }
        }
        if (panel == null) civilizationOverview = com.unciv.ui.screens.worldscreen.overview.CivilizationOverviewPanel(this)
        else panel.toggle()
        shouldUpdate = true
    }

    fun hideCivilizationOverview() {
        civilizationOverview?.hide()
    }

    /** `true` when it's the player's turn unless he is a spectator.
     *  Agent matches route through the session: only its human turns allow input. */
    val canChangeState
        get() = if (agentSession != null) agentSession!!.isHumanInputAllowed()
            else isPlayersTurn && !viewingCiv.isSpectator()

    /**
     * Attempts to route one human gameplay mutation through the agent-match
     * coordinator (AI_AGENT_PLAN.md §3.2). Returns false when no session is
     * attached — the caller then proceeds with the LEGACY engine path, so
     * ordinary games are untouched. When a session exists the action is
     * consumed either way: success publishes a snapshot, failure surfaces
     * the coordinator's rejection.
     */
    fun routeThroughAgentSession(
        buildCommands: (expectedRevision: Int) -> List<(Int) -> AgentCommand>
    ): Boolean = routeThroughAgentSession({}, buildCommands)

    fun routeThroughAgentSession(
        onComplete: (String?) -> Unit,
        buildCommands: (expectedRevision: Int) -> List<(Int) -> AgentCommand>
    ): Boolean {
        val session = agentSession ?: return false
        val clickedViewerId = currentViewerCivId()
        // Bind UI indices and choices to the frame on which the user clicked.
        val displayedRevision = gameInfo.agentMatchStateJson?.let { JsonReader().parse(it).getInt("stateRevision", 0) }
        val commands = try { buildCommands(displayedRevision ?: 0) } catch (ex: Exception) {
            val error = ex.message ?: "This action could not be prepared."
            ToastPopup(error, this).open(force = true)
            onComplete(error)
            return true
        }
        // Never run coordinator mutations on the GL thread (serialization +
        // checkpoint I/O): dispatch to a worker, report back on the GL thread.
        Concurrency.runOnNonDaemonThreadPool("HumanAction") {
            val error = session.executeHumanAction { revision ->
                if (displayedRevision != null && displayedRevision != revision)
                    listOf({ _: Int -> throw AgentCommandException(AgentCommandError.STALE_STATE,
                        "The displayed game has changed. Please try this action again.", revision) })
                else commands
            }
            Concurrency.runOnGLThread {
                if (game.worldScreen !== this@WorldScreen || agentSession !== session || currentViewerCivId() != clickedViewerId) return@runOnGLThread
                if (error != null) ToastPopup(error, this@WorldScreen).open(force = true)
                shouldUpdate = true
                onComplete(error)
            }
        }
        return true
    }

    //region Agent snapshot publication (plan §9) — install runs on the GL thread

    /** Selection handles the host captures before serialization. */
    fun currentViewerCivId(): String = viewingCiv.civID
    fun currentSelectedUnitId(): Int? = bottomUnitTable.selectedUnit?.id
    fun currentSelectedCityId(): String? = bottomUnitTable.selectedCity?.getCity()?.id
    fun currentSelectedTilePos(): HexCoord? = mapHolder.selectedTile?.position()

    /**
     * Installs a committed coordinator snapshot into this screen. The swap
     * and map rebinding MUST run on the GL thread (the host schedules it);
     * serialization/deserialization already happened on the host's worker
     * thread. Selections re-resolve by stable ID — entities that no longer
     * exist clear their selection instead of holding orphans.
     */
    private val agentPublicationFence = com.unciv.logic.agents.AgentPublicationFence()

    fun installAgentSnapshot(snapshot: GameInfo, selection: ResolvedSelection, publicationGeneration: Long = 0, viewerCivId: String? = null, visualBatch: VisualBatch? = null): Boolean {
        // A queued frame from an old host must not replace the currently opened game or its autosave.
        if (game.worldScreen !== this || game.gameInfo !== gameInfo || agentSession == null || gameInfo.gameId != snapshot.gameId) return false
        if (gameInfo === snapshot) return false
        if (visualDisposed || visualBatch != null && visualBatch.view.epoch != visualView.epoch) return false
        val oldViewerId = viewingCiv.civID
        val newViewer = snapshot.civilizations.firstOrNull { it.civID == (viewerCivId ?: oldViewerId) }
            ?: return false // snapshot without the viewer is a host bug; keep current render
        val viewerChanged = newViewer.civID != oldViewerId
        val newSelected = if (viewerChanged || !newViewer.isSpectator()) newViewer else
            snapshot.civilizations.firstOrNull { it.civID == selectedCiv.civID } ?: newViewer
        val vision = snapshot.agentMatchStateJson?.let { migrateAgentMatchStateJson(it).vision }
            ?: agentSession?.getVision() ?: MatchVision.FOG
        val newSelectedView = perspectiveOf(snapshot, newSelected, newViewer.isSpectator(), vision)
        val newEffectiveView = when {
            fogOfWar -> newSelectedView
            newViewer.isSpectator() -> GameView(snapshot, newViewer, spectatorMode = true)
            agentSession?.canSpectate() == true -> GameView(snapshot, newViewer, revealMap = true)
            else -> newSelectedView
        }
        val expectedVisualView = visualKeyFor(newEffectiveView, visualView.epoch)
        if (visualBatch != null && (visualBatch.view != expectedVisualView ||
                visualBatch.events.any { it.view != expectedVisualView || it.id.gameId != snapshot.gameId })) return false
        if (!agentPublicationFence.accept(publicationGeneration)) return false
        gameInfo = snapshot
        game.gameInfo = snapshot
        // Revoke pending effects before rebinding anything that could render a new authority.
        if (visualView != expectedVisualView) synchronized(visualLock) {
            pendingVisualBatch = null
            installedVisualSequence = 0L
            installedVisualTimeline = null
            visualResetGeneration++
        }
        viewingCiv = newViewer
        selectedCiv = newSelected
        selectedGameView = newSelectedView
        synchronized(visualLock) {
            visualView = expectedVisualView
        }
        // Unrestricted vision is re-resolved for the new viewer, so it never outlives the local spectator.
        cachedUnfoggedGameView = null
        isPlayersTurn = viewingCiv.isCurrentPlayer()

        if (viewerChanged) {
            hideCivilizationOverview()
            stage.root.children.filterIsInstance<AgentMessagesPanel>().forEach { it.hide(); it.refresh() }
            stage.root.children.filterIsInstance<AgentActivityFeed>().forEach { it.hide() }
            stage.root.children.filterIsInstance<AgentNationSettingsPanel>().forEach { it.hide() }
            stage.root.children.filterIsInstance<Popup>().toList().forEach { it.close() }
            stage.keyboardFocus = null
            stage.scrollFocus = null
            bottomUnitTable.selectSpy(null)
            bottomUnitTable.selectUnit()
            undoHandler.clearCheckpoints()
            deferFreeGreatPersonPicker = false
        }

        // Rebind the map layer to the new object graph.
        mapHolder.rebindToSnapshot(snapshot.tileMap)

        // Re-resolve selections by stable ID; drop removed entities.
        val unit = selection.unitId?.takeUnless { viewerChanged }?.let { id ->
            snapshot.civilizations.asSequence().flatMap { it.units.getCivUnits() }
                .firstOrNull { it.id == id }
        }
        val unitView = unit?.let { selectedGameView.getMapUnitView(it) }
        val cityView = selection.cityId?.takeUnless { viewerChanged }?.let { id ->
            snapshot.getCities().firstOrNull { it.id == id }?.let { selectedGameView.getForeignCityView(it) }
        }
        bottomUnitTable.reselectAfterSnapshot(unitView, cityView)
        mapHolder.selectedTile = selection.tile?.takeUnless { viewerChanged }?.let { pos ->
            val tile = snapshot.tileMap.values.firstOrNull { it.position == pos }
            tile?.let { selectedGameView.getTile(it) }
        }
        val nativeHumanHandoff = if (viewerChanged && !newViewer.isSpectator()) snapshot.agentMatchStateJson?.let { encoded ->
            val state = migrateAgentMatchStateJson(encoded)
            val principal = snapshot.civilizations.firstOrNull { it.civID == state.localControlCivId }
            principal?.isSpectator() == false && state.controllers[newViewer.civID]?.kind == ControllerKind.HUMAN &&
                snapshot.civilizations.count { !it.isSpectator() && state.controllers[it.civID]?.kind == ControllerKind.HUMAN } > 1
        } == true else false
        if (nativeHumanHandoff) {
            // Retain the world in the stack; the privacy cover must also replace any old private screen.
            val readyScreen = game.screen as? PlayerReadyScreen
            if (readyScreen != null && !readyScreen.retainedWorldInStack)
                game.replaceCurrentScreen { PlayerReadyScreen(this) }
            else {
                game.resetToWorldScreen()
                game.pushScreen { PlayerReadyScreen(this, retainedWorldInStack = true) }
            }
        } else game.screen?.refreshAgentSnapshot()
        if (visualBatch != null) installVisualBatch(visualBatch)
        shouldUpdate = true
        Gdx.graphics.requestRendering()
        return true
    }

    //endregion

    val mapHolder = WorldMapHolder(this, gameInfo.tileMap)

    internal var waitingForAutosave = false

    // Floating Widgets going counter-clockwise
    internal val topBar = WorldScreenTopBar(this)
    internal val techPolicyAndDiplomacy = TechPolicyDiplomacyButtons(this)
    internal val chatButton = ChatButton(this)
    private val unitActionsTable = UnitActionsTable(this)
    /** Bottom left widget holding information about a selected unit or city */
    internal val bottomUnitTable = UnitTable(this)
    private val battleTable = BattleTable(this)
    private val zoomController = ZoomButtonPair(mapHolder)
    internal val minimapWrapper = MinimapHolder(mapHolder)
    private val bottomTileInfoTable = TileInfoTable(this)
    internal val notificationsScroll = NotificationsScroll(this)
    internal val nextTurnButton = NextTurnButton(this)
    private val statusButtons = StatusButtons(nextTurnButton, this)
    internal val smallUnitButton = SmallUnitButton(this, statusButtons)
    private val tutorialTaskTable = Table().apply {
        background = skinStrings.getUiBackground("WorldScreen/TutorialTaskTable", tintColor = skinStrings.skinConfig.baseColor.darken(0.5f))
    }
    private var tutorialTaskTableHash = 0

    private var atlasHud: AtlasWorldHud? = null

    private var nextTurnUpdateJob: Job? = null

    private val events = EventBus.EventReceiver()

    private var uiEnabled = true

    internal val undoHandler = UndoHandler(this)


    init {
        // notifications are right-aligned, they take up only as much space as necessary.
        notificationsScroll.width = stage.width / 2

        minimapWrapper.x = stage.width - minimapWrapper.width

        // This is the most memory-intensive operation we have currently, most OutOfMemory errors will occur here
        mapHolder.addTiles()
        mapHolder.reloadMaxZoom()

        // resume music (in case choices from the menu lead to instantiation of a new WorldScreen)
        UncivGame.Current.musicController.resume()

        stage.addActor(mapHolder)
        stage.scrollFocus = mapHolder
        stage.addActor(notificationsScroll)  // very low in z-order, so we're free to let it extend _below_ tile info and minimap if we want
        stage.addActor(tutorialTaskTable)    // behind topBar!
        stage.addActor(topBar)
        stage.addActor(statusButtons)
        stage.addActor(techPolicyAndDiplomacy)
        stage.addActor(chatButton)

        stage.addActor(zoomController)
        zoomController.isVisible = UncivGame.Current.settings.showZoomButtons

        stage.addActor(bottomUnitTable)
        stage.addActor(unitActionsTable)
        stage.addActor(bottomTileInfoTable)
        stage.addActor(minimapWrapper)
        if (com.unciv.ui.components.GameUiStyle.isAtlas)
            atlasHud = AtlasWorldHud(this, techPolicyAndDiplomacy, bottomUnitTable, unitActionsTable, minimapWrapper, statusButtons, bottomTileInfoTable)

        battleTable.width = stage.width / 3
        battleTable.x = stage.width / 3
        stage.addActor(battleTable)

        val tileToCenterOn: HexCoord =
                when {
                    viewingCiv.getCapital() != null -> viewingCiv.getCapital()!!.location.toHexCoord()
                    viewingCiv.units.getCivUnits().any() -> viewingCiv.units.getCivUnits().first().getTile().position
                    else -> HexCoord.Zero
                }

        mapHolder.isAutoScrollEnabled = Gdx.app.type == Application.ApplicationType.Desktop && game.settings.mapAutoScroll
        mapHolder.mapPanningSpeed = game.settings.mapPanningSpeed

        // Don't select unit and change selectedCiv when centering as spectator
        mapHolder.setCenterPosition(tileToCenterOn, immediately = true, selectUnit = !viewingCiv.isSpectator())

        tutorialController.allTutorialsShowedCallback = { shouldUpdate = true }

        addKeyboardListener() // for map panning by W,S,A,D
        addKeyboardPresses()  // shortcut keys like F1


        if (gameInfo.gameParameters.isOnlineMultiplayer && !gameInfo.isUpToDate)
            isPlayersTurn = false // until we're up to date, don't let the player do anything

        if (gameInfo.gameParameters.isOnlineMultiplayer) {
            val gameId = gameInfo.gameId
            events.receive(MultiplayerGameUpdated::class, { it.preview.gameId == gameId }) {
                if (isNextTurnUpdateRunning() || game.onlineMultiplayer.hasLatestGameState(gameInfo, it.preview)) {
                    return@receive
                }
                Concurrency.run("Load latest multiplayer state") {
                    loadLatestMultiplayerState()
                }
            }
        }

        if (restoreState != null) restore(restoreState)
        refreshVisualView()
        installOrdinaryCapture()

        // don't run update() directly, because the UncivGame.worldScreen should be set so that the city buttons and tile groups
        //  know what the viewing civ is.
        shouldUpdate = true
    }

    override fun show() {
        super.show()
        visualSuspended = false
        installOrdinaryCapture()
        visualViewChangedListener?.invoke()
    }

    override fun hide() {
        reconcileVisualActors()
        suspendVisuals()
        visualViewChangedListener?.invoke()
        super.hide()
    }

    override fun dispose() {
        if (visualTimelineDelegate.isInitialized()) visualTimeline.dispose()
        visualDisposed = true
        suspendVisuals()
        visualViewChangedListener = null
        closeAgentSession()
        resizeDeferTimer?.cancel()
        events.stopReceiving()
        statusButtons.dispose()
        civilizationOverview?.dispose()
        mapHolder.glassBackdrop.dispose()
        super.dispose()
    }

    override fun getCivilopediaRuleset() = gameInfo.ruleset

    // Handle disabling and re-enabling WASD listener while Options are open
    override fun openOptionsPopup(startingPage: OptionsPopupPages, withDebug: Boolean, onClose: () -> Unit) {
        val oldListener = stage.root.listeners.filterIsInstance<KeyboardPanningListener>().firstOrNull()
        if (oldListener != null) {
            stage.removeListener(oldListener)
            oldListener.dispose()
        }
        super.openOptionsPopup(startingPage, withDebug) {
            addKeyboardListener()
            onClose()
        }
    }

    fun openEmpireOverview(category: EmpireOverviewCategories? = null, selection: String = "") {
        game.pushScreen{ EmpireOverviewScreen(selectedGameView.civView, category, selection) }
    }

    fun openNewGameScreen() {
        val newGameSetupInfo = GameSetupInfo(gameInfo)
        newGameSetupInfo.mapParameters.reseed()
        val newGameScreen = NewGameScreen(newGameSetupInfo)
        game.pushScreen{ newGameScreen }
    }

    fun openSaveGameScreen() {
        // See #10353 - we don't support locally saving an online multiplayer game
        if (gameInfo.gameParameters.isOnlineMultiplayer) return
        game.pushScreen{ SaveGameScreen(gameInfo) }
    }

    private fun addKeyboardPresses() {
        globalShortcuts.add(KeyboardBinding.DeselectOrQuit) { backButtonAndESCHandler() }

        // Space and N are assigned in NextTurnButton constructor
        // Functions that have a big button are assigned there (WorldScreenTopBar, TechPolicyDiplomacyButtons..)
        globalShortcuts.add(KeyboardBinding.Civilopedia) { openCivilopedia() }
        globalShortcuts.add(KeyboardBinding.EmpireOverviewTrades) { openEmpireOverview(EmpireOverviewCategories.Trades) }
        globalShortcuts.add(KeyboardBinding.EmpireOverviewUnits) { openEmpireOverview(EmpireOverviewCategories.Units) }
        globalShortcuts.add(KeyboardBinding.EmpireOverviewPolitics) { openEmpireOverview(EmpireOverviewCategories.Politics) }
        globalShortcuts.add(KeyboardBinding.EmpireOverviewNotifications) { openEmpireOverview(EmpireOverviewCategories.Notifications) }
        globalShortcuts.add(KeyboardBinding.VictoryScreen) { game.pushScreen{ VictoryScreen(this) } }
        globalShortcuts.add(KeyboardBinding.EmpireOverviewStats) { openEmpireOverview(EmpireOverviewCategories.Stats) }
        globalShortcuts.add(KeyboardBinding.EmpireOverviewResources) { openEmpireOverview(EmpireOverviewCategories.Resources) }
        globalShortcuts.add(KeyboardBinding.QuickSave) { QuickSave.save(gameInfo, this) }
        globalShortcuts.add(KeyboardBinding.QuickLoad) { QuickSave.load(this) }
        globalShortcuts.add(KeyboardBinding.ViewCapitalCity) {
            // A private inactive viewer must never open the active foreign nation's full CityView.
            val capital = (if (viewingCiv.isSpectator()) gameInfo.getCurrentPlayerCivilization() else viewingCiv).getCapital()
            if (capital != null && !mapHolder.setCenterPosition(capital.location.toHexCoord()))
                game.pushScreen{ CityScreen(selectedGameView.getCityView(capital)) }
        }
        globalShortcuts.add(KeyboardBinding.Options) { // Game Options
            openOptionsPopup { nextTurnButton.update() }
        }
        globalShortcuts.add(KeyboardBinding.SaveGame) { openSaveGameScreen() }    //   Save
        globalShortcuts.add(KeyboardBinding.LoadGame) { game.pushScreen{ LoadGameScreen() } }    //   Load
        globalShortcuts.add(KeyboardBinding.QuitGame) { game.popScreen() }    //   WorldScreen is the last screen, so this quits
        globalShortcuts.add(KeyboardBinding.NewGame) { openNewGameScreen() }
        globalShortcuts.add(KeyboardBinding.MusicPlayer) {
            WorldScreenMusicPopup(this).open(force = true)
        }
        globalShortcuts.add(Input.Keys.NUMPAD_ADD) { this.mapHolder.zoomIn() }    //   '+' Zoom
        globalShortcuts.add(Input.Keys.NUMPAD_SUBTRACT) { this.mapHolder.zoomOut() }    //   '-' Zoom
        globalShortcuts.add(KeyboardBinding.ToggleUI) { toggleUI() }
        globalShortcuts.add(KeyboardBinding.ToggleYieldDisplay) { minimapWrapper.yieldImageButton.toggle() }
        globalShortcuts.add(KeyboardBinding.ToggleWorkedTilesDisplay) { minimapWrapper.populationImageButton.toggle() }
        globalShortcuts.add(KeyboardBinding.ToggleMovementDisplay) { minimapWrapper.movementsImageButton.toggle() }
        globalShortcuts.add(KeyboardBinding.ToggleResourceDisplay) { minimapWrapper.resourceImageButton.toggle() }
        globalShortcuts.add(KeyboardBinding.ToggleImprovementDisplay) { minimapWrapper.improvementsImageButton.toggle() }

        globalShortcuts.add(KeyboardBinding.DeveloperConsole, action = ::openDeveloperConsole)
    }

    @Readonly
    fun openDeveloperConsole() {
        // No cheating unless you're by yourself, ignoring a possible spectator.
        // Agent matches forbid the console outright: it mutates through the
        // engine, bypassing the coordinator.
        if (agentSession != null) return
        if (gameInfo.civilizations.count { it.isHuman() && !it.isSpectator() } > 1) return
        DevConsolePopup(this)
    }

    private fun toggleUI() {
        uiEnabled = !uiEnabled
        topBar.isVisible = uiEnabled
        statusButtons.isVisible = uiEnabled
        techPolicyAndDiplomacy.isVisible = uiEnabled
        tutorialTaskTable.isVisible = uiEnabled
        bottomTileInfoTable.isVisible = uiEnabled
        unitActionsTable.isVisible = uiEnabled
        notificationsScroll.isVisible = uiEnabled
        minimapWrapper.isVisible = uiEnabled
        bottomUnitTable.isVisible = uiEnabled
        // The battle preview updates first, so the HUD layout can place it above the selection card.
        if (uiEnabled) battleTable.update() else battleTable.isVisible = false
        atlasHud?.setVisible(uiEnabled)
    }

    private fun addKeyboardListener() {
        stage.addListener(KeyboardPanningListener(mapHolder, allowWASD = true))
    }

    // We contain a map...
    override fun getShortcutDispatcherVetoer() = KeyShortcutDispatcherVeto.createTileGroupMapDispatcherVetoer()

    private suspend fun loadLatestMultiplayerState(): Unit = coroutineScope {
        if (game.screen != this@WorldScreen) return@coroutineScope // User already went somewhere else

        val loadingGamePopup = Popup(this@WorldScreen)
        launchOnGLThread {
            loadingGamePopup.addGoodSizedLabel("Loading latest game state...")
            loadingGamePopup.open()
        }

        try {
            debug("loadLatestMultiplayerState current game: gameId: %s, turn: %s, curCiv: %s",
                gameInfo.gameId, gameInfo.turns, gameInfo.currentPlayer)
            val latestGame = game.onlineMultiplayer.multiplayerServer.downloadGame(gameInfo.gameId)
            debug("loadLatestMultiplayerState downloaded game: gameId: %s, turn: %s, curCiv: %s",
                latestGame.gameId, latestGame.turns, latestGame.currentPlayer)
            if (viewingCiv.civID == latestGame.currentPlayer || viewingCiv.civID == Constants.spectator) {
                game.notifyTurnStarted()
            }
            launchOnGLThread {
                loadingGamePopup.close()
            }
            startNewScreenJob(latestGame, autoPlay)
        } catch (ex: Throwable) {
            launchOnGLThread {
                val (message) = LoadGameScreen.getLoadExceptionMessage(ex, "Couldn't download the latest game state!")
                loadingGamePopup.clear()
                loadingGamePopup.addGoodSizedLabel(message).colspan(2).row()
                loadingGamePopup.addButton("Retry") {
                    launchOnThreadPool("Load latest multiplayer state after error") {
                        loadLatestMultiplayerState()
                    }
                }.right()
                loadingGamePopup.addButton("Main menu") {
                    game.pushScreen{ MainMenuScreen() }
                }.left()
            }
        }
    }

    // This is private so that we will set the shouldUpdate to true instead.
    // That way, not only do we save a lot of unnecessary updates, we also ensure that all updates are called from the main GL thread
    // and we don't get any silly concurrency problems!
    private fun update() {

        if (uiEnabled) {
            displayTutorialsOnUpdate()

            bottomUnitTable.update()

            updateSelectedCiv()

            
            val minimapView = getGameViewConsideringForOfWar()
            minimapWrapper.update(minimapView.civView.getCiv(), minimapView.revealMap)
            bottomTileInfoTable.civView = getGameViewConsideringForOfWar().civView
            bottomTileInfoTable.updateTileTable(mapHolder.selectedTile)
            // Atlas hosts these actors in HUD panes; absolute positions would fight their tables.
            if (atlasHud == null) {
                bottomTileInfoTable.x = stage.width - bottomTileInfoTable.width
                bottomTileInfoTable.y = if (game.settings.showMinimap) minimapWrapper.height + 5f else 0f
            }

            battleTable.update()

            displayTutorialTaskOnUpdate()
        }

        mapHolder.resetArrows()
        if (UncivGame.Current.settings.showUnitMovements) {
            mapHolder.updateMovementOverlay(
                getGameViewConsideringForOfWar(),
                selectedGameView.civView.getUnits().asSequence(),
            )
        }

        zoomController.isVisible = UncivGame.Current.settings.showZoomButtons

        // if we use the clone, then when we update viewable tiles
        // it doesn't update the explored tiles of the civ... need to think about that harder
        // it causes a bug when we move a unit to an unexplored tile (for instance a cavalry unit which can move far)

        mapHolder.updateTiles(getGameViewConsideringForOfWar().civView)
        mapHolder.rebuildVisualUnitIndex()

        topBar.update(selectedCiv)
        civilizationOverview?.takeIf { it.isVisible }?.refresh()
        if (tutorialTaskTable.isVisible)
            tutorialTaskTable.y = topBar.getYForTutorialTask() - tutorialTaskTable.height

        if (techPolicyAndDiplomacy.update())
            displayTutorial(TutorialTrigger.OtherCivEncountered)

        if (uiEnabled) {
            // UnitActionsTable measures geometry (its own y, techPolicyAndDiplomacy and fogOfWarButton), so call update this late
            if (atlasHud == null) unitActionsTable.y = bottomUnitTable.height
            unitActionsTable.update(bottomUnitTable.selectedUnit?.getUnit())
        }

        // If the game has ended, lets stop AutoPlay
        if (autoPlay.isAutoPlaying() && !gameInfo.oneMoreTurnMode && (viewingCiv.isDefeated() || gameInfo.checkForVictory())) {
            autoPlay.stopAutoPlay()
        }

        // canChangeState (not just isPlayersTurn) keeps agent-owned popup
        // decisions (AlertPopup/TradePopup) from being answered by the human
        // viewer while an external agent holds the turn.
        if (!hasOpenPopups() && !autoPlay.isAutoPlaying() && isPlayersTurn && canChangeState) {
            when {
                viewingCiv.shouldShowDiplomaticVotingResults() ->
                    UncivGame.Current.pushScreen{ DiplomaticVoteResultScreen(gameInfo.diplomaticVictoryVotesCast, viewingCiv) }
                !gameInfo.oneMoreTurnMode && (viewingCiv.isDefeated() || gameInfo.checkForVictory()) ->
                    game.pushScreen{ VictoryScreen(this) }
                hasPendingFreeGreatPerson() && !deferFreeGreatPersonPicker ->
                    openGreatPersonPicker()
                viewingCiv.popupAlerts.any() -> AlertPopup(this, viewingCiv.popupAlerts.first())
                viewingCiv.tradeRequests.isNotEmpty() -> {
                    // In the meantime this became invalid, perhaps because we accepted previous trades
                    for (tradeRequest in viewingCiv.tradeRequests.toList())
                        if (!TradeEvaluation().isTradeValid(tradeRequest.trade, viewingCiv,
                                gameInfo.getCivilization(tradeRequest.requestingCiv)))
                            viewingCiv.tradeRequests.remove(tradeRequest)

                    if (viewingCiv.tradeRequests.isNotEmpty()) // if a valid one still exists
                        TradePopup(this).open()
                }
            }
        }

        updateGameplayButtons()

        // Lay the Atlas HUD out first, so notifications clear this update's panels, not the last ones.
        if (uiEnabled) atlasHud?.layout()
        val coveredNotificationsTop = atlasHud?.notificationCover?.y ?: (stage.height - statusButtons.y)
        val coveredNotificationsBottom = atlasHud?.notificationCover?.x ?: (bottomTileInfoTable.height + bottomTileInfoTable.y)
//                (if (game.settings.showMinimap) minimapWrapper.height else 0f)
        notificationsScroll.update(viewingCiv.notifications, coveredNotificationsTop, coveredNotificationsBottom)
        if (uiEnabled) atlasHud?.applyNotificationLayer()

        val posZoomFromRight = if (game.settings.showMinimap) minimapWrapper.width
        else bottomTileInfoTable.width
        zoomController.setPosition(stage.width - posZoomFromRight - 10f, 10f, Align.bottomRight)
    }

    @Readonly
    internal fun hasPendingFreeGreatPerson() = viewingCiv.greatPeople.freeGreatPeople > 0

    internal fun openGreatPersonPicker() {
        deferFreeGreatPersonPicker = false
        game.pushScreen { GreatPersonPickerScreen(this, viewingCiv) }
    }

    private fun getCurrentTutorialTask(): Event? {
        if (!game.settings.tutorialTasksCompleted.contains("Create a trade route")) {
            if (viewingCiv.cache.citiesConnectedToCapitalToMediums.any { it.key.civ == viewingCiv })
                game.settings.addCompletedTutorialTask("Create a trade route")
        }
        val stateForConditionals = viewingCiv.state
        return gameInfo.ruleset.events.values.firstOrNull {
            it.presentation == Event.Presentation.Floating &&
                it.isAvailable(stateForConditionals)
        }
    }

    private fun displayTutorialsOnUpdate() {

        displayTutorial(TutorialTrigger.Introduction)

        displayTutorial(TutorialTrigger.EnemyCityNeedsConqueringWithMeleeUnit) {
            viewingCiv.diplomacy.values.asSequence()
                    .filter { it.diplomaticStatus == DiplomaticStatus.War }
                    .map { it.otherCiv } // we're now lazily enumerating over CivilizationInfo's we're at war with
                    .flatMap { it.cities.asSequence() } // ... all *their* cities
                    .filter { it.health == 1 } // ... those ripe for conquering
                    .flatMap { it.getCenterTile().getTilesInDistance(2) }
                    // ... all tiles around those in range of an average melee unit
                    // -> and now we look for a unit that could do the conquering because it's ours
                    //    no matter whether civilian, air or ranged, tell user he needs melee
                    .any { it.getUnits().any { unit -> unit.civ == viewingCiv } }
        }
        displayTutorial(TutorialTrigger.AfterConquering) { viewingCiv.cities.any { it.hasJustBeenConquered } }

        displayTutorial(TutorialTrigger.InjuredUnits) { gameInfo.getCurrentPlayerCivilization().units.getCivUnits().any { it.health < 100 } }

        displayTutorial(TutorialTrigger.Workers) {
            gameInfo.getCurrentPlayerCivilization().units.getCivUnits().any {
                it.cache.hasUniqueToBuildImprovements && it.isCivilian() && !it.isGreatPerson()
            }
        }
    }

    private fun displayTutorialTaskOnUpdate() {
        fun setInvisible() {
            tutorialTaskTable.isVisible = false
            tutorialTaskTable.clear()
            tutorialTaskTableHash = 0
        }
        if (!game.settings.showTutorials || viewingCiv.isDefeated()) return setInvisible()
        val tutorialTask = getCurrentTutorialTask() ?: return setInvisible()

        if (!UncivGame.Current.isTutorialTaskCollapsed) {
            val hash = tutorialTask.hashCode()  // Default implementation is OK - we see the same instance or not
            if (hash != tutorialTaskTableHash) {
                val renderEvent = RenderEvent(tutorialTask, this) {
                    shouldUpdate = true
                }
                if (!renderEvent.isValid) return setInvisible()
                tutorialTaskTable.clear()
                tutorialTaskTable.add(renderEvent).pad(10f)
                tutorialTaskTableHash = hash
            }
        } else {
            tutorialTaskTable.clear()
            tutorialTaskTable.add(ImageGetter.getImage("OtherIcons/HiddenTutorialTask").apply { setSize(30f,30f) }).pad(5f)
            tutorialTaskTableHash = 0
        }
        tutorialTaskTable.pack()
        tutorialTaskTable.centerX(stage)
        tutorialTaskTable.y = topBar.getYForTutorialTask() - tutorialTaskTable.height
        tutorialTaskTable.onClick {
            UncivGame.Current.isTutorialTaskCollapsed = !UncivGame.Current.isTutorialTaskCollapsed
            displayTutorialTaskOnUpdate()
        }
        tutorialTaskTable.isVisible = true
    }

    /** Re-places the Atlas HUD panes after a panel opens, closes or changes size, without a full screen update. */
    internal fun relayoutHud() {
        if (uiEnabled) atlasHud?.layout()
    }

    private var inspectedNationId: String? = null

    /** Nation inspection is local presentation state, never a viewer/controller handoff. */
    fun inspectNation(civId: String) {
        val permitted = com.unciv.ui.screens.worldscreen.topbar.NationTurnRoster.read(gameInfo, viewingCiv, gameInfo.currentPlayer)
            .firstOrNull { it.civ.civID == civId } ?: return
        inspectedNationId = permitted.civ.civID
        bottomUnitTable.selectUnit()
        setSelectedCiv(permitted.civ)
        shouldUpdate = true
    }

    /** "Follow active nation": drop any inspection or selection, back to the spectator's own view.
     *  Like [inspectNation], this never touches the session, controller or match vision. */
    fun clearInspectedNation() {
        inspectedNationId = null
        bottomUnitTable.selectUnit()
        setSelectedCiv(viewingCiv)
        shouldUpdate = true
    }

    fun setSelectedCiv(civ: Civilization) {
        selectedCiv = if (viewingCiv.isSpectator()) civ else viewingCiv
        selectedGameView = perspectiveOf(gameInfo, selectedCiv)
        refreshVisualView()
    }

    private fun updateSelectedCiv() {
        if (!viewingCiv.isSpectator() || bottomUnitTable.selectedUnit != null || bottomUnitTable.selectedCity != null)
            inspectedNationId = null
        val inspected = inspectedNationId?.let { id -> gameInfo.civilizations.firstOrNull { it.civID == id } }
        setSelectedCiv(when {
            bottomUnitTable.selectedUnit != null -> bottomUnitTable.selectedUnit!!.civ().getCiv()
            bottomUnitTable.selectedCity != null -> bottomUnitTable.selectedCity!!.owningCiv().getCiv()
            inspected != null -> inspected
            else -> viewingCiv
        })
    }

    class RestoreState(
        mapHolder: WorldMapHolder,
        val selectedCivName: String,
        val viewingCivName: String,
        val fogOfWar: Boolean
    ) {
        val zoom = mapHolder.scaleX
        val scrollX = mapHolder.scrollX
        val scrollY = mapHolder.scrollY
    }
    
    @Readonly
    fun getRestoreState(): RestoreState {
        return RestoreState(mapHolder, selectedCiv.civID, viewingCiv.civID, fogOfWar)
    }

    private fun restore(restoreState: RestoreState) {

        // A restored host viewer wins over the previous screen's spectator selection and fog.
        if (viewingCiv.civID != restoreState.viewingCivName) return
        mapHolder.zoom(restoreState.zoom)
        mapHolder.scrollX = restoreState.scrollX
        mapHolder.scrollY = restoreState.scrollY
        mapHolder.updateVisualScroll()

        val selected = gameInfo.civilizations.firstOrNull { it.civID == restoreState.selectedCivName }
        setSelectedCiv(if (viewingCiv.isSpectator()) selected ?: viewingCiv else viewingCiv)
        fogOfWar = restoreState.fogOfWar
    }

    fun nextTurn() {
        // Agent-enabled matches never take the legacy clone/nextTurn path:
        // the session routes the current civ's end-turn through the match
        // coordinator (or consumes the press when nothing may act).
        agentSession?.let { it.onNextTurnPressed(); return }
        isPlayersTurn = false
        shouldUpdate = true
        val progressBar = NextTurnProgress(nextTurnButton)
        progressBar.start(this)

        // on a separate thread so the user can explore their world while we're passing the turn
        nextTurnUpdateJob = Concurrency.runOnNonDaemonThreadPool("NextTurn") {
            debug("Next turn starting")
            val startTime = System.currentTimeMillis()
            val originalGameInfo = gameInfo
            val gameInfoClone = originalGameInfo.clone()
            gameInfoClone.setTransients()  // this can get expensive on large games, not the clone itself

            progressBar.increment()

            val turnView = currentVisualViewKey()
            val turnJournal = VisualJournal(gameInfoClone.gameId)
            turnJournal.reset(turnView)
            val turnAuthority = visualViewState
            val turnSession = VisualCapture.Session(turnView, VisualEventProjector, turnJournal) { turnAuthority.get() == turnView }
            val committedTurnVisuals = VisualCapture.begin(gameInfoClone, turnSession).use { capture ->
                gameInfoClone.nextTurn(progressBar, true)
                capture.finish()
            }

            if (originalGameInfo.gameParameters.isOnlineMultiplayer) {
                // outer try-catch for non-auth exceptions
                try {
                    // keep retrying if upload fails AND reauthentication succeeds
                    var retryUpload: Boolean
                    do {
                        try {
                            game.onlineMultiplayer.updateGame(gameInfoClone)
                            // upload succeeded
                            retryUpload = false
                        } catch (_: MultiplayerAuthException) {
                            // true only if authentication succeeds (the popup permits retries)
                            // false only if user closes the auth popup or the popup init crashes
                            val authResult = CompletableDeferred<Boolean>()
                            launchOnGLThread {
                                try {
                                    AuthPopup(this@WorldScreen, authResult::complete).open(true)
                                } catch (ex: Exception) {
                                    // GL thread crashed during AuthPopup init, let's wrap up
                                    authResult.complete(false)
                                    // ensure exception is passed to crash handler
                                    throw ex
                                }
                            }
                            retryUpload = authResult.await()
                        }
                    } while (retryUpload)
                } catch (ex: Exception) { // non-auth exceptions
                    when (ex) {
                        is FileStorageRateLimitReached -> {
                            val message = "Server limit reached! Please wait for [${ex.limitRemainingSeconds}] seconds"
                            launchOnGLThread {
                                val cantUploadNewGamePopup = Popup(this@WorldScreen)
                                cantUploadNewGamePopup.addGoodSizedLabel(message).row()
                                cantUploadNewGamePopup.addCloseButton()
                                cantUploadNewGamePopup.open()
                            }
                        }
                        else -> {
                            val message = "Could not upload game! Reason: [${ex.message ?: "Unknown"}]"
                            launchOnGLThread {
                                val cantUploadNewGamePopup = Popup(this@WorldScreen)
                                cantUploadNewGamePopup.addGoodSizedLabel(message).row()
                                cantUploadNewGamePopup.addButton("Copy to clipboard") {
                                    Gdx.app.clipboard.contents = ex.stackTraceToString()
                                }
                                cantUploadNewGamePopup.addCloseButton()
                                cantUploadNewGamePopup.open()
                            }
                        }
                    }

                    this@WorldScreen.failedUpload = true // Since we couldn't push the new game clone, then we need to try again
                    this@WorldScreen.shouldUpdate = true
                    return@runOnNonDaemonThreadPool
                }
            }

            if (game.gameInfo != originalGameInfo) // while this was turning we loaded another game
                return@runOnNonDaemonThreadPool

            debug("Next turn took %sms", System.currentTimeMillis() - startTime)

            // Special case: when you are the only alive human player, the game will always be up to date
            if (gameInfo.gameParameters.isOnlineMultiplayer
                    && gameInfoClone.civilizations.count { it.isAlive() && it.playerType == PlayerType.Human } == 1) {
                gameInfoClone.isUpToDate = true
            }

            progressBar.increment()

            val visualHandoff = if (currentVisualViewKey() == turnView) runCatching {
                turnJournal.appendCommitted(committedTurnVisuals)
                OrdinaryVisualHandoff(visualAuthoritySerial, visualAuthoritySerial.get(), turnJournal.readAfter(0L, turnView))
            }.getOrNull() else null
            startNewScreenJob(gameInfoClone, autoPlay, visualHandoff = visualHandoff)
        }
    }

    fun switchToNextUnit(resetDue: Boolean = true) {
        // Try to select something new if we already have the next pending unit selected.
        if (bottomUnitTable.selectedUnit != null && resetDue)
            bottomUnitTable.selectedUnit!!.getUnit().due = false
        val nextDueUnit = viewingCiv.units.cycleThroughDueUnits(bottomUnitTable.selectedUnit?.getUnit())
        if (nextDueUnit != null) {
            mapHolder.setCenterPosition(
                nextDueUnit.currentTile.position,
                immediately = false,
                selectUnit = false
            )
            bottomUnitTable.selectUnit(selectedGameView.getForeignMapUnitView(nextDueUnit).tryGetMapUnitView()!!)
        } else {
            mapHolder.removeAction(mapHolder.blinkAction)
            mapHolder.selectedTile = null
            bottomUnitTable.selectUnit()
        }
        shouldUpdate = true
    }
    
    @Readonly
    internal fun isNextTurnUpdateRunning(): Boolean {
        val job = nextTurnUpdateJob
        return job != null && job.isActive
    }

    private fun updateGameplayButtons() {
        nextTurnButton.update()

        updateAutoPlayStatusButton()
        updateMultiplayerStatusButton()

        statusButtons.update(false)
        // Atlas places the turn action and chat button in HUD panes (AtlasWorldHud).
        if (atlasHud != null) return
        val maxWidth = stage.width - techPolicyAndDiplomacy.width - 25f
        if(statusButtons.width > maxWidth) {
            statusButtons.update(true)
        }
        statusButtons.setPosition(stage.width - statusButtons.width - 10f, topBar.y - statusButtons.height - 10f)

        // Update chat button position to always be below techPolicyAndDiplomacy
        chatButton.updatePosition()
    }

    private fun updateAutoPlayStatusButton() {
        if (statusButtons.autoPlayStatusButton == null) {
            if (game.settings.autoPlay.showAutoPlayButton)
                statusButtons.autoPlayStatusButton = AutoPlayStatusButton(this, nextTurnButton)
        } else {
            if (!game.settings.autoPlay.showAutoPlayButton) {
                statusButtons.autoPlayStatusButton = null
                autoPlay.stopAutoPlay()
            }
        }
    }

    private fun updateMultiplayerStatusButton() {
        if (gameInfo.gameParameters.isOnlineMultiplayer || game.settings.multiplayer.statusButtonInSinglePlayer) {
            if (statusButtons.multiplayerStatusButton != null) return
            statusButtons.multiplayerStatusButton = MultiplayerStatusButton(this,
                game.onlineMultiplayer.multiplayerFiles.getGameByGameId(gameInfo.gameId))
        } else {
            if (statusButtons.multiplayerStatusButton == null) return
            statusButtons.multiplayerStatusButton = null
        }
    }


    private var resizeDeferTimer: Timer? = null

    override fun resize(width: Int, height: Int) {
        resetVisualPlayback()
        if (agentSession != null) {
            // Keep the live host and feed attached; reconstructing this screen
            // discards the agent session. ExtendViewport scales down with the window.
            stage.viewport.update(width, height, true)
            notificationsScroll.width = stage.width / 2
            minimapWrapper.x = stage.width - minimapWrapper.width
            battleTable.width = stage.width / 3
            battleTable.x = stage.width / 3
            mapHolder.setSize(stage.width / mapHolder.scaleX, stage.height / mapHolder.scaleY)
            shouldUpdate = true
            return
        }
        resizeDeferTimer?.cancel()
        if (resizeDeferTimer == null && stage.viewport.screenWidth == width && stage.viewport.screenHeight == height) return
        resizeDeferTimer = timer("Resize", daemon = true, 500L, Long.MAX_VALUE) {
            resizeDeferTimer?.cancel()
            resizeDeferTimer = null
            startNewScreenJob(gameInfo, autoPlay, true) // start over
        }
    }

    override fun render(delta: Float) {
        if (agentSession == null && isVisualTransportAvailable()) {
            ordinaryVisualSession?.let { session ->
                val batch = session.journal.readAfter(ordinaryVisualCursor, currentVisualViewKey())
                if (batch.throughSequence > ordinaryVisualCursor) {
                    installVisualBatch(batch)
                    ordinaryVisualCursor = batch.throughSequence
                }
            }
        }
        //  This is so that updates happen in the MAIN THREAD, where there is a GL Context,
        //    otherwise images will not load properly!
        if (shouldUpdate && resizeDeferTimer == null) {
            shouldUpdate = false

            // Since updating the worldscreen can take a long time, *especially* the first time, we disable input processing to avoid ANRs
            Gdx.input.inputProcessor = null
            update()
            showTutorialsOnNextTurn()
            if (Gdx.input.inputProcessor == null) // Update may have replaced the worldscreen with a GreatPersonPickerScreen etc, so the input would already be set
                Gdx.input.inputProcessor = stage
        }

        visualTimeline.advance(delta)
        // Only surfaces drawn by this screen may sample its backdrop.
        com.unciv.ui.components.glass.GlassBackdrop.active = mapHolder.glassBackdrop
        try { super.render(delta) } finally { com.unciv.ui.components.glass.GlassBackdrop.active = null }
    }


    private fun showTutorialsOnNextTurn() {
        if (!game.settings.showTutorials || autoPlay.isAutoPlaying()) return
        displayTutorial(TutorialTrigger.SlowStart)
        displayTutorial(TutorialTrigger.CityExpansion) { viewingCiv.cities.any { it.expansion.tilesClaimed() > 0 } }
        displayTutorial(TutorialTrigger.BarbarianEncountered) { viewingCiv.viewableTiles.any { it.getUnits().any { unit -> unit.civ.isBarbarian } } }
        displayTutorial(TutorialTrigger.RoadsAndRailroads) { viewingCiv.cities.size > 2 }
        displayTutorial(TutorialTrigger.Happiness) { viewingCiv.getHappiness() < 5 }
        displayTutorial(TutorialTrigger.Unhappiness) { viewingCiv.getHappiness() < 0 }
        displayTutorial(TutorialTrigger.GoldenAge) { viewingCiv.goldenAges.isGoldenAge() }
        displayTutorial(TutorialTrigger.IdleUnits) { gameInfo.turns >= 50 && game.settings.checkForDueUnits }
        displayTutorial(TutorialTrigger.ContactMe) { gameInfo.turns >= 100 }
        val resources = viewingCiv.detailedCivResources.asSequence().filter { it.origin == "All" }  // Avoid full list copy
        displayTutorial(TutorialTrigger.LuxuryResource) { resources.any { it.resource.resourceType == ResourceType.Luxury } }
        displayTutorial(TutorialTrigger.StrategicResource) { resources.any { it.resource.resourceType == ResourceType.Strategic } }
        displayTutorial(TutorialTrigger.EnemyCity) {
            viewingCiv.getKnownCivs().filter { viewingCiv.isAtWarWith(it) }
                    .flatMap { it.cities.asSequence() }.any { viewingCiv.hasExplored(it.getCenterTile()) }
        }
        displayTutorial(TutorialTrigger.Embarking) { viewingCiv.hasUnique(UniqueType.LandUnitEmbarkation) }
        displayTutorial(TutorialTrigger.NaturalWonders) { viewingCiv.naturalWonders.size > 0 }
        displayTutorial(TutorialTrigger.WeLoveTheKingDay) { viewingCiv.cities.any { it.demandedResource != "" } }
    }

    private fun backButtonAndESCHandler() {

        // Deselect Unit
        if (bottomUnitTable.selectedUnit != null) {
            bottomUnitTable.selectUnit()
            shouldUpdate = true
            return
        }

        // Deselect city
        if (bottomUnitTable.selectedCity != null) {
            bottomUnitTable.selectUnit()
            shouldUpdate = true
            return
        }

        if (bottomUnitTable.selectedSpy != null) {
            bottomUnitTable.selectSpy(null)
            shouldUpdate = true
            return
        }

        game.popScreen()
    }

    fun autoSave() {
        waitingForAutosave = true
        shouldUpdate = true
        UncivGame.Current.files.autosaves.requestAutoSave(gameInfo, true).invokeOnCompletion {
            // only enable the user to next turn once we've saved the current one
            waitingForAutosave = false
            shouldUpdate = true
        }
    }
}

/** A revocable value token, deliberately without a reference to the old screen or its game graph. */
private class OrdinaryVisualHandoff(val authority: AtomicLong, val serial: Long, val batch: VisualBatch)

/** Keep the old screen collectible while [UncivGame.loadGame] constructs the replacement. */
private fun startNewScreenJob(gameInfo: GameInfo, autoPlay: AutoPlay, autosaveDisabled: Boolean = false,
                              visualHandoff: OrdinaryVisualHandoff? = null) {
    Concurrency.run {
        // Only a successful ordinary next-turn publication can transfer values across screen creation.
        val handoff = visualHandoff?.takeIf {
            UncivGame.Current.worldScreen?.currentVisualViewKey() == it.batch.view && it.authority.get() == it.serial
        }
        val newWorldScreen = try {
            UncivGame.Current.loadGame(gameInfo, autoPlay)
        } catch (notAPlayer: UncivShowableException) {
            withGLContext {
                val (message) = LoadGameScreen.getLoadExceptionMessage(notAPlayer)
                val mainMenu = UncivGame.Current.goToMainMenu()
                ToastPopup(message, mainMenu)
            }
            return@run
        } catch (_: OutOfMemoryError) {
            withGLContext {
                val mainMenu = UncivGame.Current.goToMainMenu()
                ToastPopup("Not enough memory on phone to load game!", mainMenu)
            }
            return@run
        }

        if (handoff != null) withGLContext {
            if (UncivGame.Current.worldScreen === newWorldScreen && handoff.authority.get() == handoff.serial)
                newWorldScreen.installOrdinaryTurnVisuals(handoff.batch)
        }

        val shouldAutoSave = !autosaveDisabled
                && gameInfo.turns % UncivGame.Current.settings.turnsBetweenAutosaves == 0
        if (shouldAutoSave) {
            newWorldScreen.autoSave()
        }
    }
}
