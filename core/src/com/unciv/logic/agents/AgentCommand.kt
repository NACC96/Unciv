package com.unciv.logic.agents

import com.unciv.logic.map.HexCoord
import com.unciv.logic.trade.TradeOfferType

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
 * ownership and that the destination is a real tile; the engine resolves the
 * actual path like a human map click (full or partial move) and the receipt
 * reports the final position. Already-there, spent, and progress-less moves
 * are illegal rather than silent no-ops.
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

/**
 * Research selection (game.act type `research.select`). Covers normal queue
 * edits and free-technology picks; the two are distinct explicit choices
 * (mirroring TechPickerScreen's modes), selected via [useFreeTech].
 */
class SelectResearchCommand(
    override val actorCivId: String,
    val actionId: String,
    val expectedRevision: Int,
    val techName: String,
    /** True appends the tech path to the queue; false replaces the queue. */
    val queue: Boolean,
    val useFreeTech: Boolean
) : AgentCommand

class ResearchSelectedResult(
    override val actorCivId: String,
    val receipt: ActionReceipt,
    /** Current technology after the command, null when the queue is empty. */
    val currentTech: String?
) : CommandResult

enum class ProductionOp { ADD_TOP, ADD_BOTTOM, REMOVE, RAISE, LOWER, PURCHASE }

/**
 * City production-queue edits (game.act type `city.produce`). [name] is the
 * construction name for ADD_TOP/ADD_BOTTOM/PURCHASE; [index] the queue position for
 * REMOVE/RAISE/LOWER (and the purchase queue slot, -1 = not from queue).
 * [tilePos] serves CreatesOneImprovement buildings.
 */
class CityProductionCommand(
    override val actorCivId: String,
    val actionId: String,
    val expectedRevision: Int,
    val cityId: String,
    val op: ProductionOp,
    val name: String = "",
    val index: Int = -1,
    val tilePos: HexCoord? = null
) : AgentCommand

class CityProductionResult(
    override val actorCivId: String,
    val receipt: ActionReceipt,
    val queue: List<String>
) : CommandResult

/** Settler founding (game.act type `unit.found`). Puppet founding included where the unit carries it. */
class FoundCityCommand(
    override val actorCivId: String,
    val actionId: String,
    val expectedRevision: Int,
    val unitId: Int
) : AgentCommand

class CityFoundedResult(
    override val actorCivId: String,
    val receipt: ActionReceipt,
    val cityId: String
) : CommandResult

enum class UnitOrder { FORTIFY, FORTIFY_UNTIL_HEALED, SLEEP, SLEEP_UNTIL_HEALED, WAKE, DISBAND }

/** Explicit persistent unit orders (game.act type `unit.order`). Gates mirror the UI action predicates. */
class UnitOrderCommand(
    override val actorCivId: String,
    val actionId: String,
    val expectedRevision: Int,
    val unitId: Int,
    val order: UnitOrder
) : AgentCommand

class UnitOrderResult(
    override val actorCivId: String,
    val receipt: ActionReceipt,
    /** Unit action after the command, null when woken or disbanded. */
    val action: String?,
    val disbanded: Boolean
) : CommandResult

/**
 * Unit or city attack (game.act type `unit.attack`). Exactly one of
 * [attackerUnitId]/[attackerCityId] is set. Captures raise a MANDATORY
 * `conquest-disposition` decision; nothing is auto-selected.
 */
class AttackCommand(
    override val actorCivId: String,
    val actionId: String,
    val expectedRevision: Int,
    val attackerUnitId: Int? = null,
    val attackerCityId: String? = null,
    val target: HexCoord = HexCoord(0, 0)
) : AgentCommand

class AttackResult(
    override val actorCivId: String,
    val receipt: ActionReceipt,
    val attackerAlive: Boolean,
    val defenderAlive: Boolean,
    val attackerDamageDealt: Int,
    val defenderDamageDealt: Int
) : CommandResult

enum class ConquestDisposition { ANNEX, PUPPET, RAZE, LIBERATE }

/** Resolves a `conquest-disposition` decision (game.act type `conquest.resolve`). */
class ResolveConquestCommand(
    override val actorCivId: String,
    val actionId: String,
    val expectedRevision: Int,
    val decisionId: String,
    val disposition: ConquestDisposition
) : AgentCommand

class ConquestResolvedResult(
    override val actorCivId: String,
    val receipt: ActionReceipt,
    val cityId: String,
    val disposition: ConquestDisposition
) : CommandResult

/** War declaration (game.act type `diplomacy.declareWar`). Direct wars only in v1. */
class DeclareWarCommand(
    override val actorCivId: String,
    val actionId: String,
    val expectedRevision: Int,
    val targetCivId: String
) : AgentCommand

/** Peace (game.act type `diplomacy.makePeace`). No treaty cooldown in v1; treaties ride trade. */
class MakePeaceCommand(
    override val actorCivId: String,
    val actionId: String,
    val expectedRevision: Int,
    val targetCivId: String
) : AgentCommand

class DiplomacyResult(
    override val actorCivId: String,
    val receipt: ActionReceipt,
    /** Diplomatic status after the command (e.g. War, Peace). */
    val status: String
) : CommandResult

/** One side of a trade proposal: ruleset offer name, typed kind, amount. */
data class TradeOfferDto(val name: String, val type: TradeOfferType, val amount: Int)

/**
 * Bilateral proposal (game.act type `diplomacy.proposeTrade`). Validated and
 * queued as a TradeRequest on the target; acceptance happens on the
 * responder's turn (async, like human play) or immediately by coordinator
 * command. Counteroffers compose from a decline plus a reversed proposal.
 */
class ProposeTradeCommand(
    override val actorCivId: String,
    val actionId: String,
    val expectedRevision: Int,
    val targetCivId: String,
    val ourOffers: List<TradeOfferDto>,
    val theirOffers: List<TradeOfferDto>
) : AgentCommand

class TradeProposedResult(
    override val actorCivId: String,
    val receipt: ActionReceipt,
    val pendingRequestsWithTarget: Int
) : CommandResult

/** Accept/decline a queued proposal (game.act type `diplomacy.respondTrade`). */
class RespondTradeCommand(
    override val actorCivId: String,
    val actionId: String,
    val expectedRevision: Int,
    val requestingCivId: String,
    val accept: Boolean
) : AgentCommand

class TradeRespondedResult(
    override val actorCivId: String,
    val receipt: ActionReceipt,
    val accepted: Boolean
) : CommandResult

/**
 * Policy adoption (game.act type `civics.adopt`). Branch openers and members
 * share the call (branches subclass policies); free picks spend freePolicies
 * automatically, exactly like PolicyPickerScreen.
 */
class AdoptPolicyCommand(
    override val actorCivId: String,
    val actionId: String,
    val expectedRevision: Int,
    val policyName: String
) : AgentCommand

class PolicyAdoptedResult(
    override val actorCivId: String,
    val receipt: ActionReceipt,
    /** Names adopted by this command (member + auto-completed finisher). */
    val adopted: List<String>
) : CommandResult

enum class CitizenOp {
    WORK_TILE, STOP_TILE, LOCK_TILE, UNLOCK_TILE,
    ASSIGN_SPECIALIST, UNASSIGN_SPECIALIST,
    SET_FOCUS, SET_MANUAL_SPECIALISTS, SET_AVOID_GROWTH, REASSIGN
}

/**
 * Citizen management (game.act type `city.citizens`). [tilePos] selects the
 * tile for tile ops; [specialist] names the specialist type; [focus] names a
 * CityFocus; [flag] drives the boolean ops. Gates mirror the city-screen
 * predicates; engine `require` violations map to ILLEGAL_ACTION.
 */
class CityCitizensCommand(
    override val actorCivId: String,
    val actionId: String,
    val expectedRevision: Int,
    val cityId: String,
    val op: CitizenOp,
    val tilePos: HexCoord? = null,
    val specialist: String = "",
    val focus: String = "",
    val flag: Boolean = false
) : AgentCommand

class CityCitizensResult(
    override val actorCivId: String,
    val receipt: ActionReceipt,
    val freePopulation: Int
) : CommandResult

/** City tile purchase with gold (game.act type `city.buyTile`). */
class BuyTileCommand(
    override val actorCivId: String,
    val actionId: String,
    val expectedRevision: Int,
    val cityId: String,
    val tilePos: HexCoord
) : AgentCommand

class TileBoughtResult(
    override val actorCivId: String,
    val receipt: ActionReceipt,
    val tilePos: HexCoord
) : CommandResult

/** Worker improvement construction (game.act type `unit.build`). Multi-turn progress runs through endTurn. */
class BuildImprovementCommand(
    override val actorCivId: String,
    val actionId: String,
    val expectedRevision: Int,
    val unitId: Int,
    val improvementName: String
) : AgentCommand

class ImprovementStartedResult(
    override val actorCivId: String,
    val receipt: ActionReceipt,
    /** Turns the improvement will take. */
    val turnsToBuild: Int
) : CommandResult

/** Promotion adoption for experienced units (game.act type `unit.promote`). */
class PromoteUnitCommand(
    override val actorCivId: String,
    val actionId: String,
    val expectedRevision: Int,
    val unitId: Int,
    val promotionName: String
) : AgentCommand

class UnitPromotedResult(
    override val actorCivId: String,
    val receipt: ActionReceipt,
    val promotionName: String
) : CommandResult

/** Unit upgrade to its equivalent (game.act type `unit.upgrade`). */
class UpgradeUnitCommand(
    override val actorCivId: String,
    val actionId: String,
    val expectedRevision: Int,
    val unitId: Int
) : AgentCommand

class UnitUpgradedResult(
    override val actorCivId: String,
    val receipt: ActionReceipt,
    val newUnitName: String
) : CommandResult

enum class GreatPersonAction { HURRY_RESEARCH, HURRY_POLICY, HURRY_WONDER, HURRY_BUILDING, TRADE_MISSION }

/** Great-person special actions (game.act type `unit.great`). Gates mirror UnitActionsGreatPerson. */
class GreatPersonCommand(
    override val actorCivId: String,
    val actionId: String,
    val expectedRevision: Int,
    val unitId: Int,
    val action: GreatPersonAction
) : AgentCommand

class GreatPersonResult(
    override val actorCivId: String,
    val receipt: ActionReceipt,
    val action: GreatPersonAction
) : CommandResult

/** Gold gift to a city-state (game.act type `citystate.giftGold`). */
class GiftGoldToCityStateCommand(    override val actorCivId: String,
    val actionId: String,
    val expectedRevision: Int,
    val cityStateId: String,
    val amount: Int
) : AgentCommand

class GoldGiftedResult(
    override val actorCivId: String,
    val receipt: ActionReceipt,
    val influenceGained: Int
) : CommandResult

/** Tile pillaging with full loot (game.act type `unit.pillage`). */
class PillageCommand(
    override val actorCivId: String,
    val actionId: String,
    val expectedRevision: Int,
    val unitId: Int
) : AgentCommand

class PillagedResult(
    override val actorCivId: String,
    val receipt: ActionReceipt,
    val pillaged: String
) : CommandResult

/** Pillaged-tile repair queueing (game.act type `unit.repair`). */
class RepairCommand(
    override val actorCivId: String,
    val actionId: String,
    val expectedRevision: Int,
    val unitId: Int
) : AgentCommand

class RepairStartedResult(
    override val actorCivId: String,
    val receipt: ActionReceipt,
    val turnsToRepair: Int
) : CommandResult

/** Declaration of friendship (game.act type `diplomacy.friendship`). */
class FriendshipCommand(
    override val actorCivId: String,
    val actionId: String,
    val expectedRevision: Int,
    val targetCivId: String
) : AgentCommand

/**
 * Controller reassignment (takeover / return-to-agent / provider switch
 * bookkeeping). Not a gameplay mutation: it changes WHO may act, never game
 * state, and does not consume steps. Committed with the same discipline so
 * recovery never splits game and controller views. Authority revocation
 * (process/session teardown, epoch bump) happens in the host BEFORE this
 * commits; the coordinator records the new assignment.
 */
class SetControllerCommand(
    override val actorCivId: String,
    val actionId: String,
    val expectedRevision: Int,
    val targetCivId: String,
    val kind: ControllerKind,
    val profile: AgentProfile? = null
) : AgentCommand

class ControllerSetResult(
    override val actorCivId: String,
    val receipt: ActionReceipt,
    val targetCivId: String,
    val kind: ControllerKind
) : CommandResult
