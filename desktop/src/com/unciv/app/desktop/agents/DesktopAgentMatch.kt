package com.unciv.app.desktop.agents

import com.badlogic.gdx.Gdx

import com.unciv.logic.GameInfo
import com.unciv.logic.agents.AgentCommand
import com.unciv.logic.agents.AgentControl
import com.unciv.logic.agents.AgentEngineBridge
import com.unciv.logic.agents.AgentJson
import com.unciv.logic.agents.MessageImageDraft
import com.unciv.logic.agents.AgentMatchSession
import com.unciv.logic.agents.AgentMatchState
import com.unciv.logic.agents.AgentMatchSetup
import com.unciv.logic.agents.AgentOrdinarySave
import com.unciv.logic.agents.AgentProfile
import com.unciv.logic.agents.AgentRunControls
import com.unciv.logic.agents.AgentRunState
import com.unciv.logic.agents.AgentRunTimer
import com.unciv.logic.agents.AgentRunTiming
import com.unciv.logic.agents.AgentSnapshotPublication
import com.unciv.logic.agents.CivilizationController
import com.unciv.logic.agents.ControllerKind
import com.unciv.logic.agents.DiplomaticImageAttachment
import com.unciv.logic.agents.DiplomaticMessage
import com.unciv.logic.agents.MessageContact
import com.unciv.logic.agents.EndTurnCommand
import com.unciv.logic.agents.EngineCallException
import com.unciv.logic.agents.FileCheckpointStore
import com.unciv.logic.agents.HumanCommandExecutor
import com.unciv.logic.agents.MatchCoordinator
import com.unciv.logic.agents.SetControllerCommand
import com.unciv.logic.agents.NationControl
import com.unciv.logic.agents.NationControlChange
import com.unciv.logic.agents.NationSetting
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
 * Human secondary controls enter the validated coordinator routes listed
 * in docs/agent-mutation-audit.md. [canChangeState] gates the actual viewer;
 * ordinary games keep their session-less engine callbacks.
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
    private val recoveryLineage: String? = null,
    /** Transient invocation clock, injected by deterministic tests; never saved with match state. */
    clockNanos: () -> Long = System::nanoTime
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

    override fun sendMessage(targetCivId: String, text: String, onComplete: (String?) -> Unit) =
        sendMessage(targetCivId, text, null, onComplete)

    override fun sendMessage(targetCivId: String, text: String, image: MessageImageDraft?,
                             onComplete: (String?) -> Unit) {
        // Capture the actual viewing identity and own bytes before dispatch.
        val viewer = worldScreen?.currentViewerCivId()
        val draft = image?.copy()
        val messageId = UUID.randomUUID().toString()
        Concurrency.runOnNonDaemonThreadPool("Save diplomatic message") {
            val error = try {
                // Compute the immutable reference and validate cheap rules before any disk I/O.
                val metadata = draft?.let { DiplomaticImageAttachment(
                    java.security.MessageDigest.getInstance("SHA-256").digest(it.bytes)
                        .joinToString("") { byte -> "%02x".format(byte) },
                    it.mimeType, it.width, it.height, it.bytes.size) }
                synchronized(lock) {
                    require(viewer != null) { "Messaging requires an attached player or spectator" }
                    checkMessageViewerLocked(viewer)
                    coordinator.validatePlayerMessage(viewer, messageId, targetCivId, text,
                        listOfNotNull(metadata))
                }
                // Store publication completes before a metadata/checkpoint reference can commit.
                // Slow external-volume writes never hold the host lock used by the HUD.
                val attachments = draft?.let { listOf(imageStore.store(it)) } ?: emptyList()
                synchronized(lock) {
                    checkMessageViewerLocked(viewer!!)
                    publishCommittedChangesLocked {
                        coordinator.queuePlayerMessage(viewer, messageId, targetCivId, text, attachments)
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

    override fun readMessageImage(messageId: String, attachmentId: String,
                                  onComplete: (MessageImageDraft?, String?) -> Unit) {
        val viewer = worldScreen?.currentViewerCivId()
        Concurrency.run("Read message image") {
            var draft: MessageImageDraft? = null
            val error = try {
                val metadata = synchronized(lock) {
                    require(viewer != null) { "Message images require an attached viewer" }
                    checkMessageViewerLocked(viewer)
                    coordinator.getViewerMessageImage(viewer, messageId, attachmentId)
                }
                draft = imageStore.read(metadata)
                null
            } catch (ex: Exception) { ex.message ?: "Could not read the message image" }
            Concurrency.runOnGLThread {
                if (closed.get() || worldScreen?.currentViewerCivId() != viewer)
                    onComplete(null, "The message viewer has changed or closed")
                else onComplete(draft?.copy(), error)
                Gdx.graphics.requestRendering()
            }
        }
    }

    /** Viewer identity is owned by committed host metadata; workers never read GL actor fields. */
    private fun checkMessageViewerLocked(viewer: String) {
        check(!closed.get() && matchOpen) { "This match has closed or was left" }
        check(coordinator.getLocalViewingCivId() == viewer) { "The message viewer has changed" }
    }

    private val lock = Any()
    val recoveryDirectory: File = recoveryLineage?.let { File(checkpointDir(dataDir, match.matchId), "restores/$it") }
        ?: checkpointDir(dataDir, match.matchId)
    val coordinator = MatchCoordinator(game, match.deepCopy().also {
        NationControl.initialize(game, it, if (recoveryLineage == null) worldScreen?.currentViewerCivId() else null)
    }, fileStore(dataDir, recoveryDirectory, match.matchId))
    private val imageStore = FileMessageImageStore(dataDir)
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
    private val runTimer = AgentRunTimer(clockNanos)
    private val sessions = HashMap<String, String>() // civId -> provider session ID
    private var supervisor: SupervisorRunner? = null
    private var failureReason: String? = match.matchPausedReason
    private val isLoopRunning = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    /** Navigation may hide a retained match; reopening grants access, never automatic play. */
    private var matchOpen = true // protected by lock
    private data class PendingNationChange(
        val civId: String?, val change: NationControlChange, val profile: AgentProfile?,
        val origin: String?, val viewer: String?, val onComplete: (String?) -> Unit,
        var committed: Boolean = false
    )
    private var pendingNationChange: PendingNationChange? = null
    private var pendingNationReason: String? = null
    /** A committed assignment can outlive a failed render payload; Retry republishes without dispatch. */
    private var pendingViewRefresh = false
    private var viewInstallInFlight = false
    /** Render attempts advance independently so a failed accepted frame can be retried. */
    private var publicationSequence = 0L

    /** Production requires the actual rendered viewer; explicit injected fixtures can be headless. */
    private fun actualViewerLocked(): String? {
        if (worldScreen != null)
            return if (worldScreen.agentSession === this) worldScreen.currentViewerCivId() else null
        return if (supervisorFactory != null) coordinator.getLocalViewingCivId()
            ?: humanExecutor.currentHumanCivId() else null
    }

    override fun getNationSettings(): List<NationSetting> = synchronized(lock) {
        if (closed.get() || !matchOpen || actualViewerLocked() == null) emptyList()
        else coordinator.getNationSettings()
    }
    override fun canSpectate(): Boolean = synchronized(lock) {
        !closed.get() && matchOpen && actualViewerLocked() != null && coordinator.canSpectate()
    }
    override fun getPendingNationChange(): String? = synchronized(lock) {
        pendingNationChange?.let {
            pendingNationReason ?: "Pending nation change — waiting for the agent to finish. Leaving cancels this change."
        }
    }

    override fun requestNationChange(civId: String?, change: NationControlChange, profile: AgentProfile?,
                                     onComplete: (String?) -> Unit) {
        val copiedProfile = profile?.copy()
        val request: PendingNationChange
        try {
            synchronized(lock) {
                check(!closed.get() && matchOpen) { "This match is not open" }
                check(pendingNationChange == null && !viewInstallInFlight) { "Another nation change or view refresh is pending" }
                val viewer = actualViewerLocked()
                require(viewer != null) { "Nation settings require an attached player or spectator" }
                coordinator.validateNationChange(civId, change, copiedProfile)
                request = PendingNationChange(civId, change, copiedProfile,
                    coordinator.getLocalControlCivId(), viewer, onComplete)
                pendingNationChange = request
                pendingNationReason = null
                runState = AgentRunState.PAUSING
            }
        } catch (ex: Exception) {
            completeNationChange(onComplete, ex.message ?: "Could not change nation settings")
            return
        }
        refreshUi()
        Concurrency.runOnNonDaemonThreadPool("Save nation settings") {
            synchronized(lock) {
                if (pendingNationChange !== request) return@synchronized
                // An admitted or selected loop owns its final bookkeeping and drains in finally.
                if (!isLoopRunning.get() && providerRunCivId == null) applyPendingNationChangeLocked()
            }
        }
    }

    private fun completeNationChange(callback: (String?) -> Unit, error: String?) {
        if (worldScreen == null) callback(error)
        else Concurrency.runOnGLThread {
            val completion = synchronized(lock) {
                error ?: if (closed.get() || !matchOpen) "The match was closed or left" else null
            }
            callback(completion)
            Gdx.graphics.requestRendering()
        }
    }

    private fun cancelPendingNationChangeLocked(reason: String) {
        val pending = pendingNationChange ?: return
        pendingNationChange = null
        pendingNationReason = null
        if (!isLoopRunning.get() && providerRunCivId == null)
            runState = if (pendingViewRefresh || coordinator.isPaused()) AgentRunState.NEEDS_ATTENTION else AgentRunState.PAUSED
        completeNationChange(pending.onComplete, if (pending.committed)
            "The nation change was saved, but the match was closed or left before its view was installed" else reason)
    }

    private fun markViewRefreshFailedLocked(details: String?): String {
        pendingViewRefresh = true
        val saved = if (pendingNationChange?.committed == true) "The nation change" else "The committed state"
        failureReason = "$saved was saved, but the view refresh failed: $details. Retry to refresh the view."
        runState = if (isLoopRunning.get()) AgentRunState.PAUSING else AgentRunState.NEEDS_ATTENTION
        return failureReason!!
    }

    private fun finishPendingNationChangeLocked(pending: PendingNationChange, error: String?) {
        if (pendingNationChange !== pending) return // Close/navigation already completed it.
        val completion = if (error != null && pending.committed) {
            if (error.contains("was saved")) error else markViewRefreshFailedLocked(error)
        } else error
        if (completion == null) {
            pendingViewRefresh = false
            failureReason = coordinator.getPausedReason()
            runState = if (coordinator.isPaused()) AgentRunState.NEEDS_ATTENTION else AgentRunState.PAUSED
        } else if (!pending.committed) {
            failureReason = coordinator.getPausedReason() ?: completion
            runState = if (coordinator.isPaused()) AgentRunState.NEEDS_ATTENTION else AgentRunState.PAUSED
        }
        pendingNationChange = null
        pendingNationReason = null
        refreshUi()
        completeNationChange(pending.onComplete, completion)
    }

    /** Always holds the same lock as Resume, and runs only after loop ownership is released. */
    private fun applyPendingNationChangeLocked() {
        val pending = pendingNationChange ?: return
        if (pending.committed) return // The durable change is waiting for its GL installation.
        check(!isLoopRunning.get() && providerRunCivId == null)
        val beforeGeneration = coordinator.getPublicationGeneration()
        try {
            check(!closed.get() && matchOpen) { "This match is not open" }
            check(coordinator.getLocalControlCivId() == pending.origin) { "The local player has changed" }
            check(actualViewerLocked() == pending.viewer) { "The viewing nation has changed" }
            coordinator.applyNationChange(pending.civId, pending.change, pending.profile)
            pending.committed = true
            if (pending.change != NationControlChange.SPECTATE) {
                authorityEpoch = AgentRunControls.nextEpoch(authorityEpoch)
                pending.civId?.let { sessions.remove(it) }
            }
            pendingViewRefresh = true
            viewInstallInFlight = true
            runState = AgentRunState.PAUSING
            pendingNationReason = "The nation change was saved; updating the view."
            publishSnapshotLocked { error ->
                synchronized(lock) {
                    viewInstallInFlight = false
                    finishPendingNationChangeLocked(pending, error)
                }
            }
        } catch (ex: Exception) {
            viewInstallInFlight = false
            if (!pending.committed && coordinator.getPublicationGeneration() != beforeGeneration) {
                // Preserve publication of checkpoint-failure metadata without misreporting the assignment.
                try { publishSnapshotLocked() } catch (publicationError: Exception) {
                    Log.error("Could not publish checkpoint-failure metadata", publicationError)
                }
            }
            finishPendingNationChangeLocked(pending, ex.message ?: "Could not save nation settings")
        }
    }

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

    private fun humanInputAllowedLocked(): Boolean {
        if (closed.get() || !matchOpen || isLoopRunning.get() || providerRunCivId != null || pendingNationChange != null) return false
        if (runState != AgentRunState.PAUSED && runState != AgentRunState.WAITING_HUMAN) return false
        if (coordinator.isPaused() || coordinator.getTurnPhase() != TurnPhase.AWAITING_DECISIONS) return false
        val viewer = actualViewerLocked() ?: return false
        val savedViewer = coordinator.getLocalViewingCivId()
        if (savedViewer != null && savedViewer != viewer) return false
        return viewer == coordinator.getCurrentCivId() && coordinator.getController(viewer)?.kind == ControllerKind.HUMAN
    }

    override fun isHumanInputAllowed(): Boolean = synchronized(lock) { humanInputAllowedLocked() }

    override fun onNextTurnPressed(): Boolean {
        val civId = synchronized(lock) {
            if (!humanInputAllowedLocked()) return false
            coordinator.getCurrentCivId()
        }
        Concurrency.runOnNonDaemonThreadPool("AgentMatch") {
            try {
                synchronized(lock) {
                    if (!humanInputAllowedLocked() || coordinator.getCurrentCivId() != civId) return@runOnNonDaemonThreadPool
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
        if (!humanInputAllowedLocked()) return@synchronized "Human input requires the active nation's own view"
        val actor = actualViewerLocked()!!
        publishCommittedChangesLocked {
            humanExecutor.executeAll(actor) { revision ->
                buildCommands(revision).map { factory ->
                    { currentRevision: Int ->
                        factory(currentRevision).also { command ->
                            require(command.actorCivId == actor) { "Command belongs to another nation" }
                            require(command !is SetControllerCommand) { "Use Nation settings to change controllers" }
                        }
                    }
                }
            }
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

    private fun publishSnapshotLocked(onInstalled: ((String?) -> Unit)? = null) {
        if (closed.get() || onInstalled != null && !matchOpen) {
            onInstalled?.invoke("The match was closed or left")
            return
        }
        val screen = worldScreen
        if (screen == null) {
            onInstalled?.invoke(null)
            return
        }
        val payload = AgentSnapshotPublication.buildPayload(
            coordinator,
            viewerCivId = coordinator.getLocalViewingCivId() ?: screen.currentViewerCivId(),
            selectedUnitId = screen.currentSelectedUnitId(),
            selectedCityId = screen.currentSelectedCityId(),
            selectedTile = screen.currentSelectedTilePos()
        )
        publicationSequence = maxOf(publicationSequence + 1, payload.generation)
        payload.generation = publicationSequence
        val snapshot = AgentSnapshotPublication.deserializeGame(payload)
        val selection = AgentSnapshotPublication.resolveSelection(snapshot, payload)
        Concurrency.runOnGLThread {
            synchronized(lock) {
                if (closed.get() || onInstalled != null && !matchOpen) {
                    onInstalled?.invoke("The match was closed or left")
                    return@synchronized
                }
                val error = try {
                    screen.installAgentSnapshot(snapshot, selection, payload.generation, payload.viewerCivId)
                    Gdx.graphics.requestRendering()
                    null
                } catch (ex: Exception) {
                    markViewRefreshFailedLocked(ex.message ?: "Could not install the committed view")
                }
                onInstalled?.invoke(error)
            }
        }
    }

    override fun close() {
        val idleSupervisor = synchronized(lock) {
            if (!closed.compareAndSet(false, true)) return
            matchOpen = false
            viewInstallInFlight = false
            cancelPendingNationChangeLocked("The match closed before the nation change was saved")
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
        synchronized(lock) {
            matchOpen = false
            viewInstallInFlight = false
            cancelPendingNationChangeLocked("The match was left before the nation change was saved")
        }
        pause()
    }

    override fun onMatchEntered() {
        synchronized(lock) {
            if (closed.get()) return
            matchOpen = true
            if (isLoopRunning.get()) return // The admitted run still owns its pause/drain transition.
            if (pendingViewRefresh) runState = AgentRunState.NEEDS_ATTENTION
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
        !closed.get() && matchOpen && pendingNationChange == null && runState == AgentRunState.PAUSED && !coordinator.isPaused() && coordinator.getTurnPhase() != TurnPhase.COMPLETED
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
            if (pendingViewRefresh) {
                viewInstallInFlight = true
                runState = AgentRunState.PAUSING
                try {
                    publishSnapshotLocked { error ->
                        synchronized(lock) {
                            viewInstallInFlight = false
                            if (closed.get() || !matchOpen) return@synchronized
                            if (error == null) {
                                pendingViewRefresh = false
                                failureReason = coordinator.getPausedReason()
                                runState = if (coordinator.isPaused() || pendingNationChange != null)
                                    AgentRunState.NEEDS_ATTENTION else AgentRunState.PAUSED
                            }
                            refreshUi()
                        }
                    }
                } catch (ex: Exception) {
                    viewInstallInFlight = false
                    markViewRefreshFailedLocked(ex.message)
                    refreshUi()
                }
                return
            }
            try {
                publishCommittedChangesLocked { coordinator.retryPausedMatch() }
            } catch (ex: Exception) {
                failureReason = coordinator.getPausedReason() ?: ex.message
                refreshUi()
                return
            }
            failureReason = null
            if (pendingNationChange != null) applyPendingNationChangeLocked()
            else runState = AgentRunState.STARTING
        }
        refreshUi()
        triggerRunLoop()
    }

    override fun getRunState(): AgentRunState = synchronized(lock) { runState }

    override fun getActiveRunTiming(): AgentRunTiming? = synchronized(lock) {
        if (closed.get() || !matchOpen) return@synchronized null
        val viewer = actualViewerLocked() ?: return@synchronized null
        runTimer.snapshot()?.takeIf { coordinator.isSpectatorViewer(viewer) || it.civId == viewer }
    }

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
        actualViewerLocked()?.let { coordinator.isSpectatorViewer(it) }
            ?: coordinator.getControllers().values.none { it.kind == ControllerKind.HUMAN }
    }

    override fun startAutomaticPlay() {
        synchronized(lock) {
            if (closed.get() || !matchOpen || pendingNationChange != null || pendingViewRefresh) return
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
            if (closed.get() || !matchOpen || pendingNationChange != null || pendingViewRefresh || runState == AgentRunState.PAUSING) return
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
            if (closed.get() || !matchOpen || pendingNationChange != null || pendingViewRefresh || runState != AgentRunState.STARTING) return
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
                            } else if (pendingViewRefresh && !viewInstallInFlight) {
                                runState = AgentRunState.NEEDS_ATTENTION
                            }
                            if (pendingNationChange != null) {
                                if (coordinator.isPaused() || runState == AgentRunState.NEEDS_ATTENTION) {
                                    pendingNationReason = "Blocked: ${failureReason ?: "the provider failed"}. Retry before applying; leaving cancels this change."
                                    runState = AgentRunState.NEEDS_ATTENTION
                                } else applyPendingNationChangeLocked()
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
        requestNationChange(civId, NationControlChange.PLAY_AS, null) { error ->
            if (error != null) Log.debug("Nation takeover rejected: %s", error)
        }
    }

    fun returnToAgent(civId: String, profile: AgentProfile) {
        requestNationChange(civId, NationControlChange.HAND_TO_AGENT, profile) { error ->
            if (error != null) Log.debug("Agent handback rejected: %s", error)
        }
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
                if (coordinator.isPaused() || pendingViewRefresh) {
                    if (coordinator.isPaused()) failureReason = coordinator.getPausedReason()
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
            if (pendingViewRefresh) {
                runState = AgentRunState.PAUSING
                return
            }
            if (closed.get() || !matchOpen || pendingNationChange != null ||
                runState !in setOf(AgentRunState.STARTING, AgentRunState.AGENT_DECIDING, AgentRunState.APPLYING)) return
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
                "guidance" to turnGuidance).also {
                    // From this reservation onward a later pause must drain, never cancel this run.
                    providerRunCivId = civId
                }
        } } catch (ex: Exception) {
            fail(coordinator.getPausedReason() ?: "Cannot prepare agent turn: ${ex.message}")
            return
        }
        val outcome = try {
            synchronized(lock) { runTimer.start(runId, civId) }
            // One admission update arms the caption task, independent of activity/map polling.
            refreshUi()
            proc.runTurn(control)
        } finally {
            synchronized(lock) { runTimer.clear(); providerRunCivId = null }
        }
        synchronized(lock) {
            val session = outcome.sessionId
            if (session != null) sessions[civId] = session
            if (outcome.status == "completed_turn") {
                publishSnapshotLocked()
                if (!closed.get() && (coordinator.isPaused() || pendingViewRefresh)) {
                    if (coordinator.isPaused()) failureReason = coordinator.getPausedReason()
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

    /** Every RPC is bound to a currently admitted provider, including final reads after end_turn. */
    private fun requireProviderActorLocked(method: String, params: Map<String, Any?>): String {
        val admitted = providerRunCivId
            ?: throw EngineCallException("AUTHORITY_REVOKED", "No provider run is admitted")
        val actorKey = when (method) {
            "act" -> "actorCivId"
            "rules", "action_result" -> null
            else -> "civId"
        }
        if (actorKey != null && params[actorKey] !is String)
            throw EngineCallException("INVALID_ARGUMENT", "Missing string argument $actorKey")
        if (listOf("civId", "actorCivId").any { it in params && params[it] != admitted })
            throw EngineCallException("AUTHORITY_REVOKED", "Actor does not match the admitted civilization")
        return admitted
    }

    /** Engine-RPC dispatch for supervisor frames (called on the RPC thread). */
    fun answerEngineRequest(method: String, params: Map<String, Any?>): Any? {
        fun string(name: String): String = params[name] as? String
            ?: throw EngineCallException("INVALID_ARGUMENT", "Missing string argument $name")
        fun integer(name: String): Int {
            val number = params[name] as? Number
                ?: throw EngineCallException("INVALID_ARGUMENT", "Missing integer argument $name")
            val value = number.toDouble()
            if (!value.isFinite() || value != number.toInt().toDouble())
                throw EngineCallException("INVALID_ARGUMENT", "Invalid integer argument $name")
            return number.toInt()
        }
        if (method == "message_image") {
            val (metadata, actor, epoch) = synchronized(lock) {
                val admitted = requireProviderActorLocked(method, params)
                val metadata = try { coordinator.getDeliveredMessageImage(admitted, string("messageId"), string("attachmentId")) }
                catch (ex: com.unciv.logic.agents.AgentCommandException) {
                    throw EngineCallException(ex.code.name, ex.message ?: "No such accessible message image")
                }
                Triple(metadata, admitted, authorityEpoch)
            }
            val image = try { imageStore.read(metadata) } catch (_: Exception) {
                throw EngineCallException("ILLEGAL_ACTION", "No such accessible message image")
            }
            val data = java.util.Base64.getEncoder().encodeToString(image.bytes)
            synchronized(lock) {
                if (providerRunCivId != actor || authorityEpoch != epoch)
                    throw EngineCallException("AUTHORITY_REVOKED", "The provider run has changed")
            }
            return mapOf("attachmentId" to metadata.attachmentId, "mimeType" to metadata.mimeType,
                "width" to metadata.width, "height" to metadata.height, "data" to data)
        }
        return synchronized(lock) {
            val actor = requireProviderActorLocked(method, params)
            publishCommittedChangesLocked {
                when (method) {
                    "observe" -> bridge.observe(actor)
                    "inspect" -> bridge.inspect(actor, integer("unitId"))
                    "inspect_tile" -> bridge.inspectTile(actor, params)
                    "map" -> bridge.map(actor, params)
                    "rules" -> bridge.rules(params)
                    "legal_actions" -> bridge.legalActions(actor,
                        if (params["unitId"] == null) null else integer("unitId"),
                        if (params["cityId"] == null) null else string("cityId"))
                    "act" -> bridge.act(params)
                    "action_result" -> bridge.actionResult(string("actionId"), actor)
                    "notes_get" -> bridge.readNotes(actor)
                    "notes_put" -> bridge.updateNotes(actor, string("notes"), integer("expectedNotesRevision"))
                    "end_turn" -> bridge.endTurn(actor, string("actionId"), coordinator.getStateRevision())
                    else -> throw EngineCallException("INVALID_ARGUMENT", "Unknown method $method")
                }
            }
        }
    }
}
