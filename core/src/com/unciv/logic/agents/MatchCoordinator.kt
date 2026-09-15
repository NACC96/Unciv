package com.unciv.logic.agents

import com.unciv.json.json
import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.managers.TurnManager
import com.unciv.logic.map.HexCoord

/**
 * Single-writer owner of one agent-enabled match (AI_AGENT_PLAN.md §3.2).
 *
 * All mutations — human commands, agent commands, turn transitions,
 * controller changes — enter through [execute], which is `@Synchronized`:
 * one logical mutation at a time, no interleaving. (The desktop will call
 * this from a dedicated serial executor; the synchronization is the
 * contract, the thread is a deployment detail.)
 *
 * Per operation:
 * 1. Validate authority, phase, pause state, and expected revision.
 * 2. Build an unpublished candidate via serialization roundtrip, with the
 *    [AgentExecutionPolicy] installed before transient restoration. The
 *    candidate never touches `UncivGame.Current` or any visible screen.
 * 3. Execute and validate entirely on the candidate.
 * 4. Serialize a new generation and hand it to [CheckpointWriter]. Only
 *    after durable publication, replace the committed state and acknowledge.
 * 5. On any exception or persistence failure: discard the candidate,
 *    preserve the prior committed state, pause with a reason, and throw
 *    [AgentCommandException]. No success receipt or gameplay effect escapes.
 *
 * Idempotency: `actionId -> (request fingerprint, receipt)` is rehydrated
 * from the active turn's receipts, so recovery from a checkpoint keeps the
 * same deduplication scope. Same ID + same request replays the original
 * receipt (including its recorded result); same ID + different request is
 * [AgentCommandError.ACTION_ID_CONFLICT].
 */
class MatchCoordinator(
    game: GameInfo,
    match: AgentMatchState,
    val writer: CheckpointWriter = InMemoryCheckpointWriter(),
    val policy: AgentExecutionPolicy = AgentExecutionPolicy()
) {
    private val json = json()
    private var committedGame: GameInfo
    private var committedMatch: AgentMatchState
    private val actionReceipts = HashMap<String, ActionReceipt>()
    private var mutationInFlight: String? = null

    init {
        match.validateFormat()
        // Deep-copy inputs so the caller retains no alias into committed state.
        committedGame = deserializeGame(json.toJson(game))
        committedMatch = match.deepCopy()
        rebuildHotIndex()
    }

    //region Reads (no mutation)

    fun getStateRevision(): Int = committedMatch.stateRevision
    fun getTurnPhase(): TurnPhase = committedMatch.turnPhase
    fun isPaused(): Boolean = committedMatch.matchPaused
    fun getPausedReason(): String? = committedMatch.matchPausedReason

    /** Position of a unit in COMMITTED state, or null if absent/destroyed. */
    fun getUnitPosition(civId: String, unitId: Int): HexCoord? {
        val unit = committedGame.getCivilizationOrNull(civId)
            ?.units?.getCivUnits()?.firstOrNull { it.id == unitId }
            ?: return null
        return if (unit.isDestroyed) null else unit.getTile().position
    }

    /** (automated flag, action) of a unit in COMMITTED state; nulls when absent. */
    fun getUnitOrder(civId: String, unitId: Int): Pair<Boolean, String?> {
        val unit = committedGame.getCivilizationOrNull(civId)
            ?.units?.getCivUnits()?.firstOrNull { it.id == unitId }
            ?: return false to null
        return unit.isAutomated() to unit.action
    }

    fun getNotes(civId: String): Pair<String, Int> {
        requireController(civId)
        return (committedMatch.privateNotes[civId] ?: "") to
            (committedMatch.notesRevisions[civId] ?: 0)
    }

    /** Civ ID whose turn is currently awaiting decisions (empty unless AWAITING). */
    fun getCurrentCivId(): String = committedGame.currentPlayer

    fun getGameTurns(): Int = committedGame.turns
    fun getTurnSerial(): Int = committedMatch.turnSerial

    //endregion
    //region Execution

    @Synchronized
    fun execute(command: AgentCommand): CommandResult {
        if (committedMatch.matchPaused)
            throw AgentCommandException(
                AgentCommandError.MATCH_PAUSED,
                "Match is paused: ${committedMatch.matchPausedReason ?: "no reason recorded"}"
            )
        val controller = requireController(command.actorCivId)
        if (controller.kind != ControllerKind.HUMAN && controller.kind != ControllerKind.EXTERNAL_AGENT)
            throw AgentCommandException(
                AgentCommandError.AUTHORITY_REVOKED,
                "Civ ${command.actorCivId} is ${controller.kind} and takes no coordinator commands"
            )
        if (committedMatch.turnPhase != TurnPhase.AWAITING_DECISIONS)
            throw AgentCommandException(
                AgentCommandError.NOT_YOUR_TURN,
                "Commands require phase AWAITING_DECISIONS, current is ${committedMatch.turnPhase}"
            )
        if (mutationInFlight != null)
            throw AgentCommandException(
                AgentCommandError.MUTATION_IN_PROGRESS,
                "Mutation $mutationInFlight is still in flight"
            )

        return when (command) {
            is UpdateNotesCommand -> executeNotes(command)
            is MoveUnitCommand -> executeMove(command)
            is EndTurnCommand -> executeEndTurn(command)
        }
    }

    private fun executeNotes(command: UpdateNotesCommand): NotesUpdatedResult {
        val controller = requireController(command.actorCivId)
        if (controller.kind != ControllerKind.EXTERNAL_AGENT && controller.kind != ControllerKind.HUMAN)
            throw AgentCommandException(AgentCommandError.AUTHORITY_REVOKED, "No notes for $controller.kind")
        if (command.notes.toByteArray(Charsets.UTF_8).size > 16 * 1024)
            throw AgentCommandException(AgentCommandError.INVALID_ARGUMENT, "Notes exceed 16 KiB UTF-8")

        val candidate = committedMatch.deepCopy()
        val current = candidate.notesRevisions[command.actorCivId] ?: 0
        if (command.expectedNotesRevision != current)
            throw AgentCommandException(
                AgentCommandError.STALE_STATE,
                "Notes changed; re-read (expected $current)"
            )
        candidate.notesRevisions[command.actorCivId] = current + 1
        candidate.privateNotes[command.actorCivId] = command.notes

        // Notes still commit through the checkpoint: game snapshot and notes
        // must always restore as a pair, never mixed across generations.
        commit(candidateGame = null, candidateMatch = candidate)
        return NotesUpdatedResult(command.actorCivId, current + 1)
    }

    private fun executeMove(command: MoveUnitCommand): UnitMovedResult {
        val fingerprint = moveFingerprint(command)
        actionReceipts[command.actionId]?.let { prior ->
            if (prior.requestFingerprint == fingerprint) {
                val (x, y) = prior.resultJson.split(",")
                return UnitMovedResult(
                    command.actorCivId, prior, command.unitId, HexCoord(x.toInt(), y.toInt())
                )
            }
            throw AgentCommandException(
                AgentCommandError.ACTION_ID_CONFLICT,
                "actionId ${command.actionId} reused with a different request"
            )
        }
        if (command.expectedRevision != committedMatch.stateRevision)
            throw AgentCommandException(
                AgentCommandError.STALE_STATE,
                "Expected revision ${command.expectedRevision} but committed is ${committedMatch.stateRevision}",
                currentRevision = committedMatch.stateRevision
            )

        mutationInFlight = command.actionId
        try {
            val candidateGame = deserializeGame(json.toJson(committedGame))
            val candidateMatch = committedMatch.deepCopy()

            val civ = candidateGame.getCivilizationOrNull(command.actorCivId)
                ?: throw AgentCommandException(
                    AgentCommandError.INVALID_ARGUMENT, "Unknown civilization ${command.actorCivId}"
                )
            // Hidden and nonexistent units share one externally safe response.
            val unit = civ.units.getCivUnits().firstOrNull { it.id == command.unitId }
            if (unit == null || unit.owner != command.actorCivId || unit.isDestroyed)
                throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "No such visible unit")
            val tile = try {
                candidateGame.tileMap[command.destination]
            } catch (ex: Exception) {
                throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Destination is not a tile of this map")
            }
            if (!unit.movement.canMoveTo(tile))
                throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "Unit cannot move there")

            @Suppress("UNUSED_VARIABLE") // policy hook: automation flags are read by rule code from here in W4
            val executionPolicy = policy
            unit.movement.moveToTile(tile)
            val finalPosition = unit.getTile().position

            val before = committedMatch.stateRevision
            candidateMatch.stateRevision = before + 1
            val receipt = ActionReceipt(
                actionId = command.actionId,
                actorCivId = command.actorCivId,
                timelineId = committedMatch.timelineId,
                requestFingerprint = fingerprint,
                beforeRevision = before,
                afterRevision = before + 1,
                outcome = "applied",
                committedEventIds = ArrayList(),
                resultJson = "${finalPosition.x},${finalPosition.y}"
            )
            candidateMatch.currentTurnReceipts.add(receipt)

            commit(candidateGame = candidateGame, candidateMatch = candidateMatch)
            actionReceipts[command.actionId] = receipt
            return UnitMovedResult(command.actorCivId, receipt, command.unitId, finalPosition)
        } finally {
            mutationInFlight = null
        }
    }

    //endregion
    //region Turn lifecycle

    /**
     * Opens the match: applies start-turn effects exactly once for the first
     * eligible commandable civ and enters AWAITING_DECISIONS. Requires phase
     * READY_TO_START; already-decided games (victory present) COMPLETE
     * immediately with no new work scheduled.
     */
    @Synchronized
    fun beginAwaitingTurn(): TurnEndedResult {
        if (committedMatch.matchPaused)
            throw AgentCommandException(AgentCommandError.MATCH_PAUSED, "Match is paused")
        if (committedMatch.turnPhase != TurnPhase.READY_TO_START)
            throw AgentCommandException(
                AgentCommandError.ILLEGAL_ACTION,
                "beginAwaitingTurn requires phase READY_TO_START, current is ${committedMatch.turnPhase}"
            )

        val candidateGame = deserializeGame(json.toJson(committedGame))
        val candidateMatch = committedMatch.deepCopy()
        if (candidateGame.victoryData != null) {
            candidateMatch.turnPhase = TurnPhase.COMPLETED
            candidateMatch.stateRevision = committedMatch.stateRevision + 1
            commit(candidateGame = candidateGame, candidateMatch = candidateMatch)
            val receipt = ActionReceipt(
                actionId = "internal:begin-t${candidateMatch.turnSerial}-completed",
                actorCivId = "",
                timelineId = candidateMatch.timelineId,
                requestFingerprint = "internal:begin",
                beforeRevision = committedMatch.stateRevision,
                afterRevision = candidateMatch.stateRevision,
                outcome = "applied",
                committedEventIds = ArrayList(),
                resultJson = ""
            )
            return TurnEndedResult("", receipt, null, false, candidateGame.turns)
        }

        val anchor = candidateGame.civilizations.indexOfFirst { it.civID == candidateGame.currentPlayer }
        val startAfter = if (anchor >= 0) anchor - 1 else -1
        val next = rotateToNextCommandable(candidateGame, candidateMatch, startAfter)
        if (next == null) {
            pauseCommitted("No eligible civilization to open the match")
            throw AgentCommandException(
                AgentCommandError.INTERNAL_ERROR, "No eligible civilization to open the match"
            )
        }
        mutationInFlight = "internal:begin"
        try {
            beginTurnOnCandidate(candidateGame, candidateMatch, next.civID)
            val receipt = candidateMatch.currentTurnReceipts.last()
            commit(candidateGame = candidateGame, candidateMatch = candidateMatch)
            actionReceipts.clear()
            actionReceipts[receipt.actionId] = receipt
            return TurnEndedResult("", receipt, next.civID, next.wrappedAround, candidateGame.turns)
        } finally {
            mutationInFlight = null
        }
    }

    private fun pauseCommitted(reason: String) {
        committedMatch.matchPaused = true
        committedMatch.matchPausedReason = reason
    }

    private fun executeEndTurn(command: EndTurnCommand): TurnEndedResult {
        val fingerprint = "end-turn"
        actionReceipts[command.actionId]?.let { prior ->
            if (prior.requestFingerprint == fingerprint) {
                val next = if (committedMatch.turnPhase == TurnPhase.AWAITING_DECISIONS)
                    committedMatch.currentTurnReceipts.firstOrNull()?.actorCivId else null
                return TurnEndedResult(command.actorCivId, prior, next, false, committedGame.turns)
            }
            throw AgentCommandException(
                AgentCommandError.ACTION_ID_CONFLICT,
                "actionId ${command.actionId} reused with a different request"
            )
        }
        if (command.expectedRevision != committedMatch.stateRevision)
            throw AgentCommandException(
                AgentCommandError.STALE_STATE,
                "Expected revision ${command.expectedRevision} but committed is ${committedMatch.stateRevision}",
                currentRevision = committedMatch.stateRevision
            )

        val blocking = committedMatch.pendingDecisions
            .filter { it.owningCivId == command.actorCivId && it.classification == DecisionClassification.MANDATORY }
        if (blocking.isNotEmpty())
            throw AgentCommandException(
                AgentCommandError.DECISIONS_PENDING,
                "Resolve mandatory decisions first: ${blocking.joinToString(",") { it.decisionId }}. " +
                    "Missing decisions are never chosen automatically."
            )

        mutationInFlight = command.actionId
        try {
            val candidateGame = deserializeGame(json.toJson(committedGame))
            val candidateMatch = committedMatch.deepCopy()
            val civ = candidateGame.getCivilizationOrNull(command.actorCivId)
                ?: throw AgentCommandException(
                    AgentCommandError.INVALID_ARGUMENT, "Unknown civilization ${command.actorCivId}"
                )

            // End-turn effects exactly once for the acting civ. Coordinator-routed
            // civs (human and external) always suppress unsolicited automation.
            TurnManager(civ).endTurn(null, suppressAutomation = true)

            val before = committedMatch.stateRevision
            candidateMatch.stateRevision = before + 1
            val endReceipt = ActionReceipt(
                actionId = command.actionId,
                actorCivId = command.actorCivId,
                timelineId = committedMatch.timelineId,
                requestFingerprint = fingerprint,
                beforeRevision = before,
                afterRevision = before + 1,
                outcome = "applied",
                committedEventIds = ArrayList(),
                resultJson = ""
            )
            candidateMatch.currentTurnReceipts.add(endReceipt)

            if (candidateGame.victoryData != null) {
                // Victory achieved: stop scheduling new work, keep the ending
                // checkpoint inspectable. Existing victory UI reads victoryData.
                candidateMatch.turnPhase = TurnPhase.COMPLETED
                commit(candidateGame = candidateGame, candidateMatch = candidateMatch)
                actionReceipts[command.actionId] = endReceipt
                return TurnEndedResult(command.actorCivId, endReceipt, null, false, candidateGame.turns)
            }

            val fromIndex = candidateGame.civilizations.indexOfFirst { it.civID == command.actorCivId }
            val next = rotateToNextCommandable(candidateGame, candidateMatch, fromIndex)
            if (next == null) {
                candidateMatch.turnPhase = TurnPhase.COMPLETED
                candidateMatch.matchPaused = true
                candidateMatch.matchPausedReason = "No eligible civilization to continue the match"
                commit(candidateGame = candidateGame, candidateMatch = candidateMatch)
                actionReceipts[command.actionId] = endReceipt
                return TurnEndedResult(command.actorCivId, endReceipt, null, false, candidateGame.turns)
            }
            beginTurnOnCandidate(candidateGame, candidateMatch, next.civID)
            commit(candidateGame = candidateGame, candidateMatch = candidateMatch)
            actionReceipts.clear()
            actionReceipts[command.actionId] = endReceipt
            actionReceipts[candidateMatch.currentTurnReceipts.last().actionId] =
                candidateMatch.currentTurnReceipts.last()
            return TurnEndedResult(
                command.actorCivId, endReceipt, next.civID, next.wrappedAround, candidateGame.turns
            )
        } finally {
            mutationInFlight = null
        }
    }

    private class RotationResult(val civID: String, val wrappedAround: Boolean)

    /**
     * Advances [fromIndex] through the civilization list, running built-in
     * automation inline as one indivisible turn per AI civ (defeated civs and
     * spectators skipped), until the next HUMAN/EXTERNAL_AGENT civ.
     * Returns null when no eligible civ exists (caller terminates or pauses
     * with a reason — never spins). Caps steps well above one full round.
     */
    private fun rotateToNextCommandable(
        candidateGame: GameInfo,
        candidateMatch: AgentMatchState,
        fromIndex: Int
    ): RotationResult? {
        val civs = candidateGame.civilizations
        if (civs.isEmpty()) return null
        var index = fromIndex
        var wrapped = false
        val cap = civs.size * 2 + 8
        repeat(cap) {
            index = (index + 1) % civs.size
            if (index == 0) {
                wrapped = true
                // Round rollover, mirroring GameInfo.nextTurn order minus the
                // ranking-history snapshot (deferred to the victory pass).
                candidateGame.turns++
            }
            val civ = civs[index]
            if (civ.isSpectator() || civ.isDefeated()) return@repeat
            val kind = candidateMatch.controllers[civ.civID]?.kind
                ?: throw AgentCommandException(
                    AgentCommandError.INTERNAL_ERROR, "No controller for civilization ${civ.civID}"
                )
            if (kind == ControllerKind.BUILTIN_AI) {
                // One indivisible automated turn (plan §9): existing automation
                // runs internally, never through provider invocations.
                TurnManager(civ).startTurn(null, suppressAutomation = false)
                TurnManager(civ).automateTurn()
                TurnManager(civ).endTurn(null, suppressAutomation = false)
                if (candidateGame.victoryData != null) return null
                return@repeat
            }
            return RotationResult(civ.civID, wrapped)
        }
        return null
    }

    /** Applies start-turn effects for [civId] and opens its decision ledger. */
    private fun beginTurnOnCandidate(
        candidateGame: GameInfo,
        candidateMatch: AgentMatchState,
        civId: String
    ) {
        val civ = candidateGame.getCivilization(civId)
        TurnManager(civ).startTurn(null, suppressAutomation = true)
        candidateGame.currentPlayer = civ.civID
        candidateGame.currentPlayerCiv = civ
        candidateGame.currentTurnStartTime = System.currentTimeMillis()
        candidateMatch.turnPhase = TurnPhase.AWAITING_DECISIONS
        candidateMatch.turnSerial++
        val before = candidateMatch.stateRevision
        candidateMatch.stateRevision = before + 1
        // New turn, new idempotency scope: the hot index is swapped only after
        // a successful commit (see callers), so a failed persist can never
        // strand the committed state without its deduplication scope.
        val beginReceipt = ActionReceipt(
            actionId = "internal:begin-t${candidateMatch.turnSerial}-$civId",
            actorCivId = civId,
            timelineId = candidateMatch.timelineId,
            requestFingerprint = "internal:begin",
            beforeRevision = before,
            afterRevision = before + 1,
            outcome = "applied",
            committedEventIds = ArrayList(),
            resultJson = ""
        )
        candidateMatch.currentTurnReceipts.clear()
        candidateMatch.currentTurnReceipts.add(beginReceipt)
    }

    //endregion
    //region Commit and persistence

    /**
     * Durably publish the candidate, then swap it in. [candidateGame] is null
     * for metadata-only commits (notes), which still rewrite the checkpoint
     * so game and metadata generations never diverge.
     */
    private fun commit(candidateGame: GameInfo?, candidateMatch: AgentMatchState) {
        val gameJson = if (candidateGame != null) json.toJson(candidateGame) else json.toJson(committedGame)
        val matchJson = json.toJson(candidateMatch)
        try {
            writer.write(gameJson, matchJson)
        } catch (ex: Exception) {
            // Discard the candidate, preserve prior committed state, pause
            // with the reason. No success receipt escapes.
            committedMatch.matchPaused = true
            committedMatch.matchPausedReason = "Checkpoint persistence failed: ${ex.message}"
            throw AgentCommandException(AgentCommandError.INTERNAL_ERROR, "Checkpoint persistence failed", null)
        }
        if (candidateGame != null) committedGame = candidateGame
        committedMatch = candidateMatch
    }

    /** Versioned bundle for save/reopen and recovery scans. */
    fun saveToJson(): String {
        val persistedGame = committedGame
        return json.toJson(
            AgentBundle(
                json.toJson(persistedGame),
                json.toJson(committedMatch)
            )
        )
    }

    //endregion
    //region Internals

    private fun requireController(civId: String): CivilizationController =
        committedMatch.controllers[civId]
            ?: throw AgentCommandException(AgentCommandError.INVALID_ARGUMENT, "Unknown civilization $civId")

    private fun moveFingerprint(command: MoveUnitCommand): String =
        "unit.move|${command.unitId}|${command.destination.x},${command.destination.y}"

    private fun deserializeGame(gameJson: String): GameInfo {
        val game = json.fromJson(GameInfo::class.java, gameJson)
        // Controlled transient restoration on the unpublished candidate.
        // Agent policy is installed before restoration (executeMove binds it
        // above); restoration itself must not make unsolicited choices.
        game.setTransients()
        return game
    }

    private fun rebuildHotIndex() {
        actionReceipts.clear()
        for (receipt in committedMatch.currentTurnReceipts)
            actionReceipts[receipt.actionId] = receipt
    }

    companion object {
        /** Reloads a coordinator from [saveToJson]; hot dedup scope is restored with it. */
        fun loadFromJson(
            bundleJson: String,
            writer: CheckpointWriter = InMemoryCheckpointWriter(),
            policy: AgentExecutionPolicy = AgentExecutionPolicy()
        ): MatchCoordinator {
            val json = json()
            val bundle = json.fromJson(AgentBundle::class.java, bundleJson)
            bundle.validateFormat()
            val game = json.fromJson(GameInfo::class.java, bundle.gameJson)
            val match = json.fromJson(AgentMatchState::class.java, bundle.matchJson)
            return MatchCoordinator(game, match, writer, policy)
        }
    }
}

/** Serializable save-bundle envelope (game + match travel as paired JSON). */
class AgentBundle(
    var gameJson: String = "",
    var matchJson: String = "",
    var bundleVersion: Int = AGENT_MATCH_FORMAT_VERSION
) : com.unciv.logic.IsPartOfGameInfoSerialization {
    @Suppress("unused") // used by json serialization
    constructor() : this("", "", AGENT_MATCH_FORMAT_VERSION)

    fun validateFormat() {
        require(bundleVersion == AGENT_MATCH_FORMAT_VERSION) {
            "Unsupported agent bundle version $bundleVersion"
        }
    }
}
