package com.unciv.logic.agents

import com.unciv.json.json
import com.unciv.logic.GameInfo
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

    fun getNotes(civId: String): Pair<String, Int> {
        requireController(civId)
        return (committedMatch.privateNotes[civId] ?: "") to
            (committedMatch.notesRevisions[civId] ?: 0)
    }

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
