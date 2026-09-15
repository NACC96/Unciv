package com.unciv.logic.agents

import com.unciv.logic.map.HexCoord

/**
 * Typed state-changing commands routed through [MatchCoordinator]
 * (AI_AGENT_PLAN.md §3.2, §5.1). One command = one logical mutation; no
 * batching in v1. Identity uses existing engine IDs: civ IDs (String),
 * unit integer IDs, engine hex coordinates — never display names.
 */
sealed interface AgentCommand {
    /** Acting civilization ID. */
    val actorCivId: String
}

/**
 * Private strategy-notes write (game.notes). Bumps only the notes revision,
 * never the gameplay state revision; still commits through the checkpoint
 * so notes survive reload paired with their game state.
 */
class UpdateNotesCommand(
    override val actorCivId: String,
    val notes: String,
    val expectedNotesRevision: Int
) : AgentCommand

/**
 * Single-unit movement (game.act type `unit.move`). The coordinator checks
 * ownership and destination enterability; the engine resolves the actual
 * path (full or partial move) and the receipt reports the final position.
 */
class MoveUnitCommand(
    override val actorCivId: String,
    /** Idempotency key: same ID + same request replays the receipt; same ID + different request is a conflict. */
    val actionId: String,
    val expectedRevision: Int,
    val unitId: Int,
    val destination: HexCoord
) : AgentCommand

/**
 * End the acting civ's turn (game.end_turn). Never auto-resolves missing
 * mandatory decisions — those reject with DECISIONS_PENDING. On success the
 * command atomically applies end-turn effects, rotates through built-in
 * automation inline, applies the next commandable civ's start-turn effects,
 * and lands in AWAITING_DECISIONS (or COMPLETED). Crash-safe by construction:
 * either the whole transition commits or nothing does, and the revision
 * gate makes retries safe.
 */
class EndTurnCommand(
    override val actorCivId: String,
    val actionId: String,
    val expectedRevision: Int
) : AgentCommand

/** Terminal outcome of [MatchCoordinator.execute]. */
sealed interface CommandResult {
    val actorCivId: String
}

class NotesUpdatedResult(
    override val actorCivId: String,
    val notesRevision: Int
) : CommandResult

class UnitMovedResult(
    override val actorCivId: String,
    val receipt: ActionReceipt,
    val unitId: Int,
    val finalPosition: HexCoord
) : CommandResult

class TurnEndedResult(
    override val actorCivId: String,
    val receipt: ActionReceipt,
    /** Civ now awaiting decisions, or null when the match COMPLETED. */
    val nextCivId: String?,
    val wrappedToNewRound: Boolean,
    val gameTurns: Int
) : CommandResult
