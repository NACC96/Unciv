package com.unciv.app.desktop.agents

import com.unciv.logic.GameInfo
import com.unciv.logic.agents.AgentControl
import com.unciv.logic.agents.AgentEngineBridge
import com.unciv.logic.agents.AgentGuidanceQueue
import com.unciv.logic.agents.AgentJson
import com.unciv.logic.agents.AgentMatchSession
import com.unciv.logic.agents.AgentMatchState
import com.unciv.logic.agents.AgentMatchSetup
import com.unciv.logic.agents.AgentProfile
import com.unciv.logic.agents.AgentRunControls
import com.unciv.logic.agents.AgentRunState
import com.unciv.logic.agents.ControllerKind
import com.unciv.logic.agents.EndTurnCommand
import com.unciv.logic.agents.EngineCallException
import com.unciv.logic.agents.FileCheckpointStore
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
 * Snapshot publication to the live map (rebinding cities/units/selections
 * by stable IDs without rebuilding the UI) is the documented next step;
 * until then the map refreshes on explicit reopen (see the status text).
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
    private val supervisorDist: File? = null
) : AgentMatchSession {

    override val matchId: String = match.matchId

    private val lock = Any()
    private val coordinator = MatchCoordinator(game, match, fileStore(dataDir, match.matchId))
    private val bridge = AgentEngineBridge(coordinator)
    private val guidance = AgentGuidanceQueue()
    private var runState: AgentRunState = AgentRunState.PAUSED
    private var authorityEpoch = 1
    private val sessions = HashMap<String, String>() // civId -> provider session ID
    private var supervisor: SupervisorProcess? = null
    private var failureReason: String? = null

    companion object {
        fun checkpointDir(dataDir: File, matchId: String): File =
            File(dataDir, ".agent-data/checkpoints/$matchId")

        private fun fileStore(dataDir: File, matchId: String) =
            FileCheckpointStore(checkpointDir(dataDir, matchId), matchId)

        /** Node discovery: explicit path, then PATH. Null when unavailable. */
        fun findNode(explicit: File? = null): File? {
            if (explicit != null && explicit.canExecute()) return explicit
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
        val current: String?
        val rev: Int
        synchronized(lock) {
            state = runState
            current = if (coordinator.getTurnPhase() != TurnPhase.AWAITING_DECISIONS) null
            else coordinator.getCurrentCivId()
            rev = coordinator.getStateRevision()
        }
        val who = current?.let { civ ->
            val controller = synchronized(lock) { coordinator.getController(civ) }
            val detail = controller?.agentProfile?.let { " (${it.provider}/${it.modelId})" } ?: ""
            "$civ$detail"
        }
        return when (state) {
            AgentRunState.PAUSED -> "Paused · ${who ?: "—"} to move · rev $rev"
            AgentRunState.STARTING -> "Starting provider · ${who ?: "—"} · rev $rev"
            AgentRunState.AGENT_DECIDING -> "Agent deciding · ${who ?: "—"} · rev $rev"
            AgentRunState.APPLYING -> "Applying action · rev $rev"
            AgentRunState.WAITING_HUMAN -> "Waiting human · ${who ?: "—"} · rev $rev"
            AgentRunState.PAUSING -> "Pausing… · rev $rev"
            AgentRunState.NEEDS_ATTENTION -> "Needs attention: ${failureReason ?: "unknown"} · rev $rev"
            AgentRunState.FINISHED -> "Finished · rev $rev"
        }
    }

    override fun isHumanInputAllowed(): Boolean {
        synchronized(lock) {
            if (runState != AgentRunState.PAUSED && runState != AgentRunState.WAITING_HUMAN) return false
            if (coordinator.getTurnPhase() != TurnPhase.AWAITING_DECISIONS) return false
            return coordinator.getController(coordinator.getCurrentCivId())?.kind == ControllerKind.HUMAN
        }
    }

    override fun onNextTurnPressed(): Boolean {
        val civId = synchronized(lock) {
            if (coordinator.getTurnPhase() != TurnPhase.AWAITING_DECISIONS) return false
            val current = coordinator.getCurrentCivId()
            if (coordinator.getController(current)?.kind != ControllerKind.HUMAN) return false
            current
        }
        Concurrency.runOnNonDaemonThreadPool("AgentMatch") {
            try {
                synchronized(lock) {
                    coordinator.execute(EndTurnCommand(civId, "ui-end-${UUID.randomUUID()}",
                        coordinator.getStateRevision()))
                }
                refreshUi()
            } catch (ex: Exception) {
                fail(ex.message ?: "end-turn failed")
            }
        }
        return true
    }

    override fun close() {
        synchronized(lock) { runState = AgentRunState.PAUSED }
        try {
            supervisor?.shutdown()
        } catch (ex: Exception) {
            Log.error("Agent supervisor shutdown failed", ex)
        }
        supervisor = null
        worldScreen?.agentSession = null
    }

    //endregion
    //region Run controls

    /** UI Run: continuous agent play, pausing at human turns and failures. */
    fun run() {
        if (!transition(AgentControl.RUN)) return
        Concurrency.runOnNonDaemonThreadPool("AgentMatch") { runLoop() }
    }

    fun pause() {
        transition(AgentControl.PAUSE)
        refreshUi()
    }

    fun stop() {
        transition(AgentControl.STOP)
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
        transition(AgentControl.TAKEOVER)
        reassign(civId, ControllerKind.HUMAN, null)
    }

    fun returnToAgent(civId: String, profile: AgentProfile) {
        transition(AgentControl.RETURN_TO_AGENT)
        reassign(civId, ControllerKind.EXTERNAL_AGENT, profile)
    }

    fun queueGuidance(civId: String, text: String) {
        synchronized(lock) { guidance.queue(civId, text) }
    }

    private fun transition(control: AgentControl): Boolean {
        synchronized(lock) {
            val next = AgentRunControls.transition(runState, control) ?: return false
            runState = next.to
            return true
        }
    }

    private fun fail(reason: String) {
        synchronized(lock) {
            failureReason = reason
            runState = AgentRunState.NEEDS_ATTENTION
        }
        refreshUi()
    }

    //endregion
    //region Match loop

    private fun runLoop() {
        while (true) {
            val state = synchronized(lock) { runState }
            if (state != AgentRunState.STARTING && state != AgentRunState.AGENT_DECIDING
                && state != AgentRunState.APPLYING
            ) return
            val civId = synchronized(lock) { currentCommandable() }
            if (civId == null) {
                fail("No eligible civilization to continue the match")
                return
            }
            when (synchronized(lock) { coordinator.getController(civId)?.kind }) {
                ControllerKind.HUMAN -> {
                    synchronized(lock) { runState = AgentRunState.WAITING_HUMAN }
                    refreshUi()
                    return
                }
                else -> runAgentTurn(civId)
            }
            if (synchronized(lock) { coordinator.getTurnPhase() } == TurnPhase.COMPLETED) {
                synchronized(lock) { runState = AgentRunState.FINISHED }
                refreshUi()
                return
            }
        }
    }

    private fun currentCommandable(): String? {
        if (coordinator.getTurnPhase() == TurnPhase.READY_TO_START) return null
        if (coordinator.getTurnPhase() != TurnPhase.AWAITING_DECISIONS) return null
        return coordinator.getCurrentCivId()
    }

    private fun stepSingleTurn() {
        // Scripted single step: end the current turn through the coordinator.
        // Provider-driven steps arrive via runAgentTurn instead.
        synchronized(lock) {
            if (coordinator.getTurnPhase() == TurnPhase.READY_TO_START)
                coordinator.beginAwaitingTurn()
            if (coordinator.getTurnPhase() != TurnPhase.AWAITING_DECISIONS) return
            val civId = coordinator.getCurrentCivId()
            coordinator.execute(EndTurnCommand(civId, "step-${UUID.randomUUID()}",
                coordinator.getStateRevision()))
        }
        refreshUi()
    }

    //endregion
    //region Supervisor process

    private fun runAgentTurn(civId: String) {
        synchronized(lock) { runState = AgentRunState.AGENT_DECIDING }
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
        val control: Map<String, Any?>
        synchronized(lock) {
            control = mapOf(
                "protocol" to 1,
                "kind" to "control",
                "op" to "run_turn",
                "runId" to runId,
                "provider" to profile.provider,
                "providerConfig" to mapOf(
                    "model" to profile.modelId,
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
                "guidance" to guidance.takeForDelivery(civId))
        }
        val outcome = proc.runTurn(control)
        synchronized(lock) {
            val session = outcome.sessionId
            if (session != null) sessions[civId] = session
            if (outcome.status == "completed_turn") {
                runState = AgentRunState.APPLYING
            } else {
                fail(outcome.detail)
                return
            }
        }
        refreshUi()
        synchronized(lock) {
            if (runState == AgentRunState.APPLYING) runState = AgentRunState.AGENT_DECIDING
        }
    }

    private fun reassign(civId: String, kind: ControllerKind, profile: AgentProfile?) {
        // Host-side revocation (process/session teardown, epoch bump) lands
        // here with the real supervisor; the coordinator records the rest.
        synchronized(lock) {
            authorityEpoch = AgentRunControls.nextEpoch(authorityEpoch)
            sessions.remove(civId)
            coordinator.execute(SetControllerCommand(
                civId, "reassign-${UUID.randomUUID()}",
                coordinator.getStateRevision(), civId, kind, profile))
            runState = AgentRunState.PAUSED
        }
        refreshUi()
    }

    private fun ensureSupervisor(): SupervisorProcess {
        supervisor?.let { if (it.isAlive()) return it }
        val node = findNode(nodeExecutable)
            ?: throw IllegalStateException("Node.js runtime not found (set -Dunciv.node.path)")
        val dist = findSupervisorDist(supervisorDist)
            ?: throw IllegalStateException("Supervisor bundle not found (set -Dunciv.supervisor.dist)")
        val proc = SupervisorProcess(node, File(dist, "daemon.js"),
            answerEngineRequest = this::answerEngineRequest)
        supervisor = proc
        return proc
    }

    private fun refreshUi() {
        val screen = worldScreen ?: return
        Concurrency.runOnGLThread {
            screen.shouldUpdate = true
        }
    }

    //endregion

    /** Engine-RPC dispatch for supervisor frames (called on the RPC thread). */
    fun answerEngineRequest(method: String, params: Map<String, Any?>): Any? {
        synchronized(lock) {
            return when (method) {
                "observe" -> bridge.observe(params["civId"] as String)
                "inspect" -> bridge.inspect(params["civId"] as String,
                    (params["unitId"] as Number).toInt())
                "rules" -> bridge.rules()
                "legal_actions" -> bridge.legalActions(
                    params["civId"] as String,
                    (params["unitId"] as? Number)?.toInt(),
                    params["cityId"] as? String)
                "act" -> bridge.act(params)
                "action_result" -> bridge.actionResult(params["actionId"] as String)
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
