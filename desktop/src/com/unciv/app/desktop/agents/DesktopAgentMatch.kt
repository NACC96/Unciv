package com.unciv.app.desktop.agents

import com.badlogic.gdx.Gdx

import com.unciv.logic.GameInfo
import com.unciv.logic.agents.AgentCommand
import com.unciv.logic.agents.AgentControl
import com.unciv.logic.agents.AgentEngineBridge
import com.unciv.logic.agents.AgentJson
import com.unciv.logic.agents.AgentMatchSession
import com.unciv.logic.agents.AgentMatchState
import com.unciv.logic.agents.AgentMatchSetup
import com.unciv.logic.agents.AgentOrdinarySave
import com.unciv.logic.agents.AgentProfile
import com.unciv.logic.agents.AgentRunControls
import com.unciv.logic.agents.AgentRunState
import com.unciv.logic.agents.AgentSnapshotPublication
import com.unciv.logic.agents.CivilizationController
import com.unciv.logic.agents.ControllerKind
import com.unciv.logic.agents.DiplomaticMessage
import com.unciv.logic.agents.MessageContact
import com.unciv.logic.agents.EndTurnCommand
import com.unciv.logic.agents.EngineCallException
import com.unciv.logic.agents.FileCheckpointStore
import com.unciv.logic.agents.HumanCommandExecutor
import com.unciv.logic.agents.MatchCoordinator
import com.unciv.logic.agents.SetControllerCommand
import com.unciv.logic.agents.TurnPhase
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.utils.Concurrency
import com.unciv.utils.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.PrintWriter
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Desktop-owned agent match host (AI_AGENT_PLAN.md §3.1, §9, §11).
 * Implements core [AgentMatchSession] for WorldScreen display and input
 * routing. Owns exactly one thing per layer boundary:
 *
 * - engine: [MatchCoordinator] + [FileCheckpointStore] under the
 *   project-owned data directory (never the ordinary Unciv profile);
 * - run state: [AgentRunState] transitions only (pause never kills);
 * - supervisor: one `node dist/daemon.js` child speaking the versioned
 *   stdio protocol; engine RPCs answered from the coordinator under a
 *   single host lock; provider streams never touch protocol stdout.
 *
 * Committed actions publish isolated render snapshots to the attached
 * world screen, rebinding cities/units/selections by stable IDs.
 * Human secondary controls (city screens, diplomacy popups, shortcuts)
 * are enumerated in docs/agent-mutation-audit.md — most still route
 * legacy; the end-turn button and [canChangeState] gate route here.
 */
class DesktopAgentMatch(
    game: GameInfo,
    match: AgentMatchState,
    val setup: AgentMatchSetup,
    dataDir: File,
    private val worldScreen: WorldScreen? = null,
    private val nodeExecutable: File? = null,
    private val supervisorDist: File? = null,
    /** Injected runner (tests, scripted providers); production spawns `node dist/daemon.js`. */
    private val supervisorFactory: (() -> SupervisorRunner)? = null,
    /** Fresh checkpoint namespace when an ordinary save reopens an older branch. */
    private val recoveryLineage: String? = null
) : AgentMatchSession {

    override val matchId: String = match.matchId

    override fun getCommittedMatchStateJson(): String = synchronized(lock) {
        coordinator.serializeCommittedMatchForPublication()
    }

    private val activity = AgentActivityJournal(File(dataDir, ".agent-data/logs/$matchId"))
    private val readingActivity = AtomicBoolean(false)
    override fun getActivity(): List<com.unciv.logic.agents.AgentActivityEntry> {
        if (readingActivity.compareAndSet(false, true)) Concurrency.run("Read agent activity") {
            try { activity.refresh() } finally { readingActivity.set(false) }
        }
        val viewer = worldScreen?.currentViewerCivId() ?: return emptyList()
        return synchronized(lock) {
            if (coordinator.isSpectatorViewer(viewer)) activity.entries()
            else activity.entries().filter { it.civId == viewer }
        }
    }
    override fun getActivityDetails(id: String): String {
        val viewer = worldScreen?.currentViewerCivId() ?: return ""
        val permitted = synchronized(lock) {
            coordinator.isSpectatorViewer(viewer) || activity.entries().any { it.id == id && it.civId == viewer }
        }
        return if (permitted) activity.details(id) else ""
    }
    override fun getActivityDirectory(): String? {
        val viewer = worldScreen?.currentViewerCivId() ?: return null
        return synchronized(lock) {
            if (coordinator.isSpectatorViewer(viewer)) activity.directory.absolutePath else null
        }
    }

    override fun getMessageContacts(): List<MessageContact> {
        val viewer = worldScreen?.currentViewerCivId() ?: return emptyList()
        return synchronized(lock) { coordinator.getMessageContacts(viewer).filter { it.isAgent } }
    }

    override fun getMessages(): List<DiplomaticMessage> {
        val viewer = worldScreen?.currentViewerCivId() ?: return emptyList()
        return synchronized(lock) { coordinator.getMessageHistory(viewer) }
    }

    override fun sendMessage(targetCivId: String, text: String, onComplete: (String?) -> Unit) {
        // Capture the actual viewing identity before dispatch; the composer cannot supply a sender.
        val viewer = worldScreen?.currentViewerCivId()
        val messageId = UUID.randomUUID().toString()
        Concurrency.runOnNonDaemonThreadPool("Save diplomatic message") {
            val error = try {
                synchronized(lock) {
                    check(!closed.get()) { "This match has closed" }
                    require(viewer != null) { "Messaging requires an attached player or spectator" }
                    publishCommittedChangesLocked {
                        coordinator.queuePlayerMessage(viewer, messageId, targetCivId, text)
                    }
                }
                null
            } catch (ex: Exception) { ex.message ?: "Could not save the message" }
            Concurrency.runOnGLThread {
                onComplete(error)
                Gdx.graphics.requestRendering()
            }
        }
    }

    private val lock = Any()
    val recoveryDirectory: File = recoveryLineage?.let { File(checkpointDir(dataDir, match.matchId), "restores/$it") }
        ?: checkpointDir(dataDir, match.matchId)
    val coordinator = MatchCoordinator(game, match, fileStore(dataDir, recoveryDirectory, match.matchId))
    private val bridge = AgentEngineBridge(coordinator)
    private val humanExecutor = HumanCommandExecutor(coordinator)
    private var runState: AgentRunState = when {
        match.turnPhase == TurnPhase.COMPLETED -> AgentRunState.FINISHED
        match.matchPaused -> AgentRunState.NEEDS_ATTENTION
        else -> AgentRunState.PAUSED
    }
    private var authorityEpoch = 1
    /** Host-side identity remains bound to the admitted run, including its final reads. */
    private var providerRunCivId: String? = null
    private val sessions = HashMap<String, String>() // civId -> provider session ID
    private var supervisor: SupervisorRunner? = null
    private var failureReason: String? = match.matchPausedReason
    private val isLoopRunning = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    /** Navigation may hide a retained match; reopening grants access, never automatic play. */
    private var matchOpen = true // protected by lock

    companion object {
        fun checkpointDir(dataDir: File, matchId: String): File =
            File(dataDir, ".agent-data/checkpoints/$matchId")

        private fun fileStore(dataDir: File, directory: File, matchId: String) = object : com.unciv.logic.agents.CheckpointWriter {
            private val store = FileCheckpointStore(directory, matchId, maxRetainedGenerations = 3)
            override fun write(gameJson: String, matchJson: String) {
                com.unciv.app.desktop.DesktopDataDirectory.requireAvailableVolume(dataDir.absoluteFile)
                store.write(gameJson, matchJson)
            }
        }

        fun restore(game: GameInfo, match: AgentMatchState, dataDir: File,
                    worldScreen: WorldScreen? = null,
                    supervisorFactory: (() -> SupervisorRunner)? = null): DesktopAgentMatch =
            DesktopAgentMatch(game, match, AgentOrdinarySave.setupFor(game, match), dataDir,
                worldScreen = worldScreen, supervisorFactory = supervisorFactory,
                recoveryLineage = UUID.randomUUID().toString())

        /**
         * Node discovery (AI_AGENT_PLAN.md §10): explicit path (system
         * property), then a Node bundled NEXT TO the application (packaging
         * layout: `<app dir>/node/bin|node.exe` or `<app dir>/node`), then
         * PATH as a last resort. Null when all fail — callers report the
         * searched locations. No shell is ever involved, so paths with
         * spaces need no quoting.
         */
        fun findNode(explicit: File? = null): File? {
            if (explicit != null && explicit.canExecute()) return explicit
            val appDir = try {
                DesktopAgentMatch::class.java.protectionDomain.codeSource.location.toURI()
            } catch (ex: Exception) {
                null
            }?.let { File(it).parentFile }
            if (appDir != null) {
                for (candidate in listOf(
                    File(appDir, "node/bin/node"), // bundled POSIX layout
                    File(appDir, "node/node.exe"), // bundled Windows layout
                    File(appDir, "node"), // bare binary drop-in
                    File(appDir, "../Resources/node/bin/node"),
                    File(appDir, "../Resources/node"),
                )) {
                    if (candidate.canExecute()) return candidate
                }
            }
            for (dir in (System.getenv("PATH") ?: "").split(File.pathSeparator)) {
                for (name in listOf("node", "node.exe")) {
                    val candidate = File(dir, name)
                    if (candidate.canExecute()) return candidate
                }
            }
            return null
        }

        /** Supervisor bundle: explicit dir, then sibling `agent-runner/dist`, then repo dev path. */
        fun findSupervisorDist(explicit: File? = null): File? {
            if (explicit != null && File(explicit, "daemon.js").isFile) return explicit
            val fromJar = try {
                File(DesktopAgentMatch::class.java.protectionDomain.codeSource.location.toURI()).parentFile
            } catch (ex: Exception) {
                null
            }
            val sibling = fromJar?.let { File(it, "agent-runner/dist") }
            if (sibling != null && File(sibling, "daemon.js").isFile) return sibling
            val dev = File("agent-runner/dist")
            if (File(dev, "daemon.js").isFile) return dev
            return null
        }
    }

    //region AgentMatchSession

    override fun statusText(): String {
        val state: AgentRunState
        val currentCiv: String?
        val rev: Int
        val controller: CivilizationController?
        val failure: String?
        synchronized(lock) {
            state = runState
            currentCiv = if (coordinator.getTurnPhase() == TurnPhase.AWAITING_DECISIONS) coordinator.getCurrentCivId() else null
            rev = coordinator.getStateRevision()
            controller = currentCiv?.let { coordinator.getController(it) }
            failure = failureReason
        }

        val civInfo = if (currentCiv != null) {
            val agent = controller?.agentProfile
            if (agent != null) {
                val thinking = agent.reasoning?.let { " [$it]" } ?: ""
                "${agent.displayName} ($currentCiv · ${agent.provider}/${agent.modelId}$thinking)"
            } else if (controller?.kind == ControllerKind.HUMAN) {
                "$currentCiv (Human)"
            } else {
                "$currentCiv (AI)"
            }
        } else "—"

        return when (state) {
            AgentRunState.PAUSED -> "Paused · $civInfo · rev $rev"
            AgentRunState.STARTING -> "Starting provider · $civInfo · rev $rev"
            AgentRunState.AGENT_DECIDING -> "Agent deciding · $civInfo · rev $rev"
            AgentRunState.APPLYING -> "Applying action · $civInfo · rev $rev"
            AgentRunState.WAITING_HUMAN -> "Waiting for player · $civInfo · rev $rev"
            AgentRunState.PAUSING -> "Pausing… · $civInfo · rev $rev"
            AgentRunState.NEEDS_ATTENTION -> "Needs attention: ${failure ?: "unknown"} · $civInfo · rev $rev"
            AgentRunState.FINISHED -> "Match finished · rev $rev"
        }
    }

    override fun isHumanInputAllowed(): Boolean {
        synchronized(lock) {
            if (closed.get() || !matchOpen) return false
            if (runState != AgentRunState.PAUSED && runState != AgentRunState.WAITING_HUMAN) return false
            if (coordinator.getTurnPhase() != TurnPhase.AWAITING_DECISIONS) return false
            return coordinator.getController(coordinator.getCurrentCivId())?.kind == ControllerKind.HUMAN
        }
    }

    override fun onNextTurnPressed(): Boolean {
        val civId = synchronized(lock) {
            if (closed.get() || !matchOpen) return false
            if (coordinator.getTurnPhase() != TurnPhase.AWAITING_DECISIONS) return false
            val current = coordinator.getCurrentCivId()
            if (coordinator.getController(current)?.kind != ControllerKind.HUMAN) return false
            current
        }
        Concurrency.runOnNonDaemonThreadPool("AgentMatch") {
            try {
                synchronized(lock) {
                    if (closed.get() || !matchOpen) return@runOnNonDaemonThreadPool
                    publishCommittedChangesLocked {
                        coordinator.execute(EndTurnCommand(civId, "ui-end-${UUID.randomUUID()}",
                            coordinator.getStateRevision()))
                    }
                    val nextCiv = if (coordinator.getTurnPhase() == TurnPhase.AWAITING_DECISIONS) coordinator.getCurrentCivId() else null
                    if (nextCiv != null && coordinator.getController(nextCiv)?.kind == ControllerKind.HUMAN) {
                        runState = AgentRunState.WAITING_HUMAN
                    } else if (coordinator.getTurnPhase() == TurnPhase.COMPLETED) {
                        runState = AgentRunState.FINISHED
                    } else {
                        runState = AgentRunState.STARTING
                    }
                }
                refreshUi()
                // If the next turn belongs to an agent, automatically trigger continuous play
                triggerRunLoop()
            } catch (ex: Exception) {
                fail(ex.message ?: "end-turn failed")
            }
        }
        return true
    }

    override fun executeHumanAction(
        buildCommands: (expectedRevision: Int) -> List<(Int) -> AgentCommand>
    ): String? = synchronized(lock) {
        if (closed.get() || !matchOpen) return@synchronized "This match is not open"
        publishCommittedChangesLocked {
            humanExecutor.executeAll(null, buildCommands)
        }
    }.also {
        if (it == null) refreshUi()
    }

    /** All coordinator mutations publish their committed pair, including failure-only metadata. */
    private inline fun <T> publishCommittedChangesLocked(action: () -> T): T {
        val beforeGeneration = coordinator.getPublicationGeneration()
        try {
            return action()
        } finally {
            if (coordinator.getPublicationGeneration() != beforeGeneration) {
                if (coordinator.isPaused() && !closed.get()) {
                    failureReason = coordinator.getPausedReason()
                    runState = AgentRunState.NEEDS_ATTENTION
                }
                publishSnapshotLocked()
            }
        }
    }

    private fun publishSnapshotLocked() {
        if (closed.get()) return
        val screen = worldScreen ?: return
        val payload = AgentSnapshotPublication.buildPayload(
            coordinator,
            viewerCivId = screen.currentViewerCivId(),
            selectedUnitId = screen.currentSelectedUnitId(),
            selectedCityId = screen.currentSelectedCityId(),
            selectedTile = screen.currentSelectedTilePos()
        )
        val snapshot = AgentSnapshotPublication.deserializeGame(payload)
        val selection = AgentSnapshotPublication.resolveSelection(snapshot, payload)
        Concurrency.runOnGLThread {
            if (closed.get()) return@runOnGLThread
            screen.installAgentSnapshot(snapshot, selection, payload.generation)
            Gdx.graphics.requestRendering()
        }
    }

    override fun close() {
        val idleSupervisor = synchronized(lock) {
            if (!closed.compareAndSet(false, true)) return
            matchOpen = false
            // Keep engine RPCs alive for an admitted turn. Its existing worker owns shutdown.
            runState = if (isLoopRunning.get()) AgentRunState.PAUSING else AgentRunState.PAUSED
            if (isLoopRunning.get()) null else detachSupervisorLocked()
        }
        if (worldScreen?.agentSession === this) worldScreen.agentSession = null
        if (idleSupervisor != null) Concurrency.runOnNonDaemonThreadPool("Close agent supervisor") {
            shutdownSupervisor(idleSupervisor)
        }
    }

    override fun onMatchLeft() {
        synchronized(lock) { matchOpen = false }
        pause()
    }

    override fun onMatchEntered() {
        synchronized(lock) {
            if (closed.get()) return
            matchOpen = true
            if (coordinator.isPaused()) {
                failureReason = coordinator.getPausedReason()
                runState = AgentRunState.NEEDS_ATTENTION
            }
        }
    }

    private fun detachSupervisorLocked(): SupervisorRunner? = supervisor.also { supervisor = null }

    private fun shutdownSupervisor(runner: SupervisorRunner) {
        try { runner.shutdown() } catch (ex: Exception) {
            Log.error("Agent supervisor shutdown failed", ex)
        }
    }

    override fun canPause(): Boolean = synchronized(lock) {
        !closed.get() && matchOpen && (runState == AgentRunState.STARTING || runState == AgentRunState.AGENT_DECIDING || runState == AgentRunState.APPLYING)
    }

    override fun canResume(): Boolean = synchronized(lock) {
        !closed.get() && matchOpen && runState == AgentRunState.PAUSED && !coordinator.isPaused() && coordinator.getTurnPhase() != TurnPhase.COMPLETED
    }

    override fun canRetry(): Boolean = synchronized(lock) {
        !closed.get() && matchOpen && !isLoopRunning.get() && runState == AgentRunState.NEEDS_ATTENTION
    }

    override fun pause() {
        synchronized(lock) {
            when (runState) {
                AgentRunState.STARTING, AgentRunState.AGENT_DECIDING, AgentRunState.APPLYING -> {
                    runState = AgentRunState.PAUSING
                }
                AgentRunState.WAITING_HUMAN, AgentRunState.PAUSED -> {
                    runState = AgentRunState.PAUSED
                }
                else -> {}
            }
        }
        refreshUi()
    }

    override fun resume() {
        run()
    }

    override fun retry() {
        synchronized(lock) {
            if (closed.get() || !matchOpen || isLoopRunning.get()) return
            if (runState != AgentRunState.NEEDS_ATTENTION) return
            try {
                publishCommittedChangesLocked { coordinator.retryPausedMatch() }
            } catch (ex: Exception) {
                failureReason = coordinator.getPausedReason() ?: ex.message
                refreshUi()
                return
            }
            failureReason = null
            runState = AgentRunState.STARTING
        }
        refreshUi()
        triggerRunLoop()
    }

    override fun getRunState(): AgentRunState = synchronized(lock) { runState }

    override fun getActiveCivId(): String? = synchronized(lock) {
        if (coordinator.getTurnPhase() == TurnPhase.AWAITING_DECISIONS) coordinator.getCurrentCivId() else null
    }

    override fun getActiveAgentProfile(): AgentProfile? = synchronized(lock) {
        val civId = if (coordinator.getTurnPhase() == TurnPhase.AWAITING_DECISIONS) coordinator.getCurrentCivId() else null
        civId?.let { coordinator.getController(it)?.agentProfile }
    }

    override fun getAgentProfile(civId: String): AgentProfile? = synchronized(lock) {
        coordinator.getController(civId)?.agentProfile?.copy()
    }

    override fun getFailureReason(): String? = synchronized(lock) { failureReason }

    override fun isSpectatorMode(): Boolean = synchronized(lock) {
        coordinator.getControllers().values.none { it.kind == ControllerKind.HUMAN }
    }

    override fun startAutomaticPlay() {
        synchronized(lock) {
            if (closed.get() || !matchOpen) return
            if (coordinator.getTurnPhase() == TurnPhase.READY_TO_START) {
                publishCommittedChangesLocked { coordinator.beginAwaitingTurn() }
            }
            val current = coordinator.getCurrentCivId()
            if (coordinator.getController(current)?.kind == ControllerKind.HUMAN) {
                runState = AgentRunState.WAITING_HUMAN
                refreshUi()
                return
            }
        }
        run()
    }

    //endregion
    //region Run controls

    /** Continuous agent play, advancing across civs and pausing at human turns or failures. */
    fun run() {
        synchronized(lock) {
            if (closed.get() || !matchOpen || runState == AgentRunState.PAUSING) return
            if (coordinator.isPaused()) return
            if (runState == AgentRunState.FINISHED) return
            if (coordinator.getTurnPhase() == TurnPhase.COMPLETED) {
                runState = AgentRunState.FINISHED
                refreshUi()
                return
            }
            if (coordinator.getTurnPhase() == TurnPhase.READY_TO_START) {
                publishCommittedChangesLocked { coordinator.beginAwaitingTurn() }
            }
            val current = coordinator.getCurrentCivId()
            if (coordinator.getController(current)?.kind == ControllerKind.HUMAN) {
                runState = AgentRunState.WAITING_HUMAN
                refreshUi()
                return
            }
            if (runState != AgentRunState.AGENT_DECIDING && runState != AgentRunState.APPLYING) {
                runState = AgentRunState.STARTING
            }
        }
        refreshUi()
        triggerRunLoop()
    }

    private fun triggerRunLoop() {
        synchronized(lock) {
            if (closed.get() || !matchOpen || runState != AgentRunState.STARTING) return
            if (!isLoopRunning.compareAndSet(false, true)) return
            Concurrency.runOnNonDaemonThreadPool("AgentMatch") {
                try {
                    runLoop()
                } catch (ex: Exception) {
                    fail(ex.message ?: "Agent match loop failed")
                } finally {
                    val closingSupervisor = synchronized(lock) {
                        isLoopRunning.set(false)
                        if (closed.get()) {
                            runState = AgentRunState.PAUSED
                            detachSupervisorLocked()
                        } else {
                            if (coordinator.isPaused()) {
                                failureReason = coordinator.getPausedReason()
                                runState = AgentRunState.NEEDS_ATTENTION
                            }
                            // Resume/retry may arrive after the loop publishes its boundary,
                            // but before this worker releases ownership. Hand off that request.
                            if (matchOpen && runState == AgentRunState.STARTING) triggerRunLoop()
                            null
                        }
                    }
                    if (closingSupervisor != null) shutdownSupervisor(closingSupervisor)
                    refreshUi()
                }
            }
        }
    }

    fun stop() {
        if (!transition(AgentControl.STOP)) return
        try {
            supervisor?.kill()
        } catch (ex: Exception) {
            Log.error("Agent supervisor kill failed", ex)
        }
        refreshUi()
    }

    fun stepOnce() {
        if (!transition(AgentControl.STEP_TURN)) return
        Concurrency.runOnNonDaemonThreadPool("AgentMatch") {
            stepSingleTurn()
            synchronized(lock) {
                if (runState != AgentRunState.FINISHED && runState != AgentRunState.NEEDS_ATTENTION)
                    runState = AgentRunState.PAUSED
            }
            refreshUi()
        }
    }

    fun takeOverHuman() {
        val civId = synchronized(lock) { currentCommandable() } ?: return
        if (!transition(AgentControl.TAKEOVER)) return
        reassign(civId, ControllerKind.HUMAN, null)
    }

    fun returnToAgent(civId: String, profile: AgentProfile) {
        if (!transition(AgentControl.RETURN_TO_AGENT)) return
        reassign(civId, ControllerKind.EXTERNAL_AGENT, profile)
    }

    fun queueGuidance(civId: String, text: String) {
        synchronized(lock) {
            publishCommittedChangesLocked { coordinator.queueGuidance(civId, text) }
        }
    }

    private fun transition(control: AgentControl): Boolean {
        synchronized(lock) {
            if (closed.get() || !matchOpen) return false
            if (isLoopRunning.get() && control in listOf(AgentControl.RETRY, AgentControl.SWITCH_PROVIDER,
                    AgentControl.TAKEOVER, AgentControl.RETURN_TO_AGENT, AgentControl.STEP_ACTION, AgentControl.STEP_TURN))
                return false
            val next = AgentRunControls.transition(runState, control) ?: return false
            runState = next.to
            return true
        }
    }

    private fun fail(reason: String) {
        synchronized(lock) {
            failureReason = reason
            runState = if (closed.get()) AgentRunState.PAUSED else AgentRunState.NEEDS_ATTENTION
        }
        refreshUi()
    }

    //endregion
    //region Match loop

    private fun runLoop() {
        while (true) {
            val (state, civId) = synchronized(lock) {
                if (closed.get() || !matchOpen) {
                    runState = if (!closed.get() && coordinator.isPaused()) AgentRunState.NEEDS_ATTENTION
                        else AgentRunState.PAUSED
                    return
                }
                if (coordinator.isPaused()) {
                    failureReason = coordinator.getPausedReason()
                    runState = AgentRunState.NEEDS_ATTENTION
                    refreshUi()
                    return
                }
                if (runState == AgentRunState.PAUSING) {
                    runState = AgentRunState.PAUSED
                    refreshUi()
                    return
                }
                if (runState != AgentRunState.STARTING && runState != AgentRunState.AGENT_DECIDING
                    && runState != AgentRunState.APPLYING
                ) {
                    return
                }
                if (coordinator.getTurnPhase() == TurnPhase.READY_TO_START) {
                    publishCommittedChangesLocked { coordinator.beginAwaitingTurn() }
                }
                if (coordinator.getTurnPhase() == TurnPhase.COMPLETED) {
                    runState = AgentRunState.FINISHED
                    refreshUi()
                    return
                }
                val civ = currentCommandable()
                Pair(runState, civ)
            }

            if (civId == null) {
                fail("No eligible civilization to continue the match")
                return
            }

            val controllerKind = synchronized(lock) { coordinator.getController(civId)?.kind }
            if (controllerKind == ControllerKind.HUMAN) {
                synchronized(lock) {
                    runState = if (closed.get() || !matchOpen || runState == AgentRunState.PAUSING)
                        AgentRunState.PAUSED else AgentRunState.WAITING_HUMAN
                    publishSnapshotLocked()
                }
                refreshUi()
                return
            }

            runAgentTurn(civId)

            synchronized(lock) {
                if (runState == AgentRunState.PAUSING || runState == AgentRunState.PAUSED) {
                    if (runState == AgentRunState.PAUSING) runState = AgentRunState.PAUSED
                    refreshUi()
                    return
                }
                if (coordinator.getTurnPhase() == TurnPhase.COMPLETED) {
                    runState = AgentRunState.FINISHED
                    refreshUi()
                    return
                }
            }
        }
    }

    private fun currentCommandable(): String? {
        if (coordinator.getTurnPhase() == TurnPhase.READY_TO_START) return null
        if (coordinator.getTurnPhase() != TurnPhase.AWAITING_DECISIONS) return null
        return coordinator.getCurrentCivId()
    }

    private fun stepSingleTurn() {
        synchronized(lock) {
            if (closed.get() || !matchOpen) return
            if (coordinator.getTurnPhase() == TurnPhase.READY_TO_START)
                publishCommittedChangesLocked { coordinator.beginAwaitingTurn() }
            if (coordinator.getTurnPhase() != TurnPhase.AWAITING_DECISIONS) return
            val civId = coordinator.getCurrentCivId()
            publishCommittedChangesLocked {
                coordinator.execute(EndTurnCommand(civId, "step-${UUID.randomUUID()}",
                    coordinator.getStateRevision()))
            }
        }
        refreshUi()
    }

    //endregion
    //region Supervisor process

    private fun runAgentTurn(civId: String) {
        synchronized(lock) {
            // Pause can arrive after runLoop selects the next civ but before dispatch claims it.
            if (closed.get() || !matchOpen) return
            if (runState == AgentRunState.PAUSING || runState == AgentRunState.PAUSED) return
            if (runState != AgentRunState.STARTING && runState != AgentRunState.AGENT_DECIDING
                && runState != AgentRunState.APPLYING) return
            runState = AgentRunState.AGENT_DECIDING
        }
        refreshUi()
        val proc = try {
            ensureSupervisor()
        } catch (ex: Exception) {
            fail("Cannot start supervisor: ${ex.message}")
            return
        }
        val profile = synchronized(lock) {
            coordinator.getController(civId)?.agentProfile
        } ?: run {
            fail("No agent profile for $civId")
            return
        }
        val runId = "run-${UUID.randomUUID()}"
        val control: Map<String, Any?> = try { synchronized(lock) {
            val turnGuidance = publishCommittedChangesLocked { coordinator.takeGuidanceForDelivery(civId) }
            mapOf(
                "protocol" to 1,
                "kind" to "control",
                "op" to "run_turn",
                "runId" to runId,
                "provider" to profile.provider,
                "providerConfig" to mapOf(
                    "model" to profile.modelId,
                    "reasoning" to profile.reasoning,
                    "resumeId" to sessions[civId]),
                "binding" to mapOf("matchId" to matchId,
                    "timelineId" to coordinator.getTimelineId(),
                    "civId" to civId, "runId" to runId,
                    "authorityEpoch" to authorityEpoch),
                "profile" to mapOf("displayName" to profile.displayName,
                    "personality" to profile.personality,
                    "modelLabel" to profile.modelId),
                "context" to mapOf("rulesetName" to coordinator.getRulesetName(),
                    "protocolVersion" to 1, "civId" to civId,
                    "turnSerial" to coordinator.getTurnSerial()),
                "guidance" to turnGuidance)
        } } catch (ex: Exception) {
            fail(coordinator.getPausedReason() ?: "Cannot prepare agent turn: ${ex.message}")
            return
        }
        val outcome = try {
            synchronized(lock) { providerRunCivId = civId }
            proc.runTurn(control)
        } finally {
            synchronized(lock) { providerRunCivId = null }
        }
        synchronized(lock) {
            val session = outcome.sessionId
            if (session != null) sessions[civId] = session
            if (outcome.status == "completed_turn") {
                publishSnapshotLocked()
                if (!closed.get() && coordinator.isPaused()) {
                    failureReason = coordinator.getPausedReason()
                    runState = AgentRunState.NEEDS_ATTENTION
                } else if (closed.get() || !matchOpen || runState == AgentRunState.PAUSING || runState == AgentRunState.PAUSED) {
                    runState = AgentRunState.PAUSED
                } else {
                    runState = AgentRunState.STARTING
                }
            } else {
                fail(outcome.detail)
                return
            }
        }
        refreshUi()
    }

    private fun reassign(civId: String, kind: ControllerKind, profile: AgentProfile?) {
        synchronized(lock) {
            if (closed.get() || !matchOpen || isLoopRunning.get()) return
            authorityEpoch = AgentRunControls.nextEpoch(authorityEpoch)
            sessions.remove(civId)
            publishCommittedChangesLocked {
                coordinator.execute(SetControllerCommand(
                    civId, "reassign-${UUID.randomUUID()}",
                    coordinator.getStateRevision(), civId, kind, profile))
            }
            runState = AgentRunState.PAUSED
        }
        refreshUi()
    }

    private fun ensureSupervisor(): SupervisorRunner {
        supervisor?.let { if (it.isAlive()) return it }
        supervisorFactory?.invoke()?.let {
            supervisor = it
            return it
        }
        val node = findNode(nodeExecutable)
            ?: throw IllegalStateException(
                "Node.js runtime not found. Searched: -Dunciv.node.path, " +
                    "a 'node' directory next to the application, and PATH. " +
                    "Install Node 24 or set -Dunciv.node.path=/path/to/node"
            )
        val dist = findSupervisorDist(supervisorDist)
            ?: throw IllegalStateException(
                "Supervisor bundle not found. Searched: -Dunciv.supervisor.dist, " +
                    "'agent-runner/dist' next to the application, and 'agent-runner/dist' " +
                    "in the working directory. Package agent-runner/dist with the app " +
                    "or set -Dunciv.supervisor.dist=/path/to/dist"
            )
        val proc = SupervisorProcess(node, File(dist, "daemon.js"),
            activityLogsDir = activity.directory,
            answerEngineRequest = this::answerEngineRequest)
        supervisor = proc
        return proc
    }

    private fun refreshUi() {
        if (closed.get()) return
        val screen = worldScreen ?: return
        Concurrency.runOnGLThread {
            if (closed.get()) return@runOnGLThread
            screen.shouldUpdate = true
            Gdx.graphics.requestRendering()
        }
    }

    //endregion

    /** Engine-RPC dispatch for supervisor frames (called on the RPC thread). */
    fun answerEngineRequest(method: String, params: Map<String, Any?>): Any? {
        synchronized(lock) {
            return publishCommittedChangesLocked {
                val mapActor = if (method == "map" || method == "inspect_tile") {
                    val admitted = providerRunCivId ?: coordinator.getCurrentCivId()
                    if (params["civId"] != admitted)
                        throw EngineCallException("AUTHORITY_REVOKED", "Map read actor does not match the admitted civilization")
                    admitted
                } else null
                when (method) {
                    "observe" -> bridge.observe(params["civId"] as String)
                    "inspect" -> bridge.inspect(params["civId"] as String,
                        (params["unitId"] as Number).toInt())
                    "inspect_tile" -> bridge.inspectTile(mapActor!!, params)
                    "map" -> bridge.map(mapActor!!, params)
                    "rules" -> bridge.rules(params)
                    "legal_actions" -> bridge.legalActions(
                        params["civId"] as String,
                        (params["unitId"] as? Number)?.toInt(),
                        params["cityId"] as? String)
                    "act" -> bridge.act(params)
                    "action_result" -> bridge.actionResult(params["actionId"] as String,
                        providerRunCivId ?: coordinator.getCurrentCivId())
                    "notes_get" -> bridge.readNotes(params["civId"] as String)
                    "notes_put" -> bridge.updateNotes(
                        params["civId"] as String, params["notes"] as String,
                        (params["expectedNotesRevision"] as Number).toInt())
                    "end_turn" -> bridge.endTurn(
                        params["civId"] as String,
                        params["actionId"] as String,
                        coordinator.getStateRevision())
                    else -> throw EngineCallException("INVALID_ARGUMENT", "Unknown method $method")
                }
            }
        }
    }
}
