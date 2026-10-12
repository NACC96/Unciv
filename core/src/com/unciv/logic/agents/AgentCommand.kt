package com.unciv.logic.agents

import com.unciv.logic.map.HexCoord
import com.unciv.logic.trade.TradeOfferType
import com.unciv.logic.civilization.AlertType
import com.unciv.models.stats.Stat

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
 * mandatory decisions — those reject with DECISIONS_PENDING. External agents
 * must explicitly acknowledge delivered incoming mail (MESSAGES_UNACKNOWLEDGED).
 * On success the
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

/** One-way diplomatic mail; the recipient sees it when its next turn begins. */
class SendDiplomaticMessageCommand(
    override val actorCivId: String,
    val actionId: String,
    val expectedRevision: Int,
    val targetCivId: String,
    val text: String
) : AgentCommand

class DiplomaticMessageSentResult(
    override val actorCivId: String,
    val receipt: ActionReceipt,
    val message: DiplomaticMessage
) : CommandResult

/** Explicit acknowledgement of delivered incoming mail (game.act diplomacy.acknowledgeMessage). */
class AcknowledgeDiplomaticMessageCommand(
    override val actorCivId: String,
    val actionId: String,
    val expectedRevision: Int,
    val messageId: String,
    val text: String
) : AgentCommand

class DiplomaticMessageAcknowledgedResult(
    override val actorCivId: String,
    val receipt: ActionReceipt,
    val message: DiplomaticMessage
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

enum class ProductionOp { ADD_TOP, ADD_BOTTOM, REMOVE, RAISE, LOWER, MOVE_TOP, MOVE_END, REMOVE_ALL, PURCHASE, SELL }

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
    val tilePos: HexCoord? = null,
    /** Currency captured with the displayed purchase intent; legacy callers buy with gold. */
    val purchaseStat: Stat = Stat.Gold
) : AgentCommand

class CityProductionResult(
    override val actorCivId: String,
    val receipt: ActionReceipt,
    val queue: List<String>
) : CommandResult

/** Human construction-menu intent. Null [cityId] includes the civ's persistent new-city setting. */
class CityConstructionSettingsCommand(
    override val actorCivId: String,
    val actionId: String,
    val expectedRevision: Int,
    val constructionName: String,
    val disabled: Boolean,
    val cityId: String? = null
) : AgentCommand

class CityConstructionSettingsResult(
    override val actorCivId: String,
    val receipt: ActionReceipt
) : CommandResult

/** Human checkbox for an existing saved unit-promotion preference in an owned city. */
class SetCityUnitSavedPromotionCommand(
    override val actorCivId: String,
    val actionId: String,
    val expectedRevision: Int,
    val cityId: String,
    val baseUnit: String,
    val enabled: Boolean
) : AgentCommand

/** Save defaults from the authoritative unit, after any requested promotion path has committed. */
class SaveUnitPromotionDefaultsCommand(
    override val actorCivId: String,
    val actionId: String,
    val expectedRevision: Int,
    val unitId: Int
) : AgentCommand

class UnitPromotionDefaultsSavedResult(
    override val actorCivId: String,
    val receipt: ActionReceipt,
    val cityId: String
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

enum class UnitOrder {
    FORTIFY, FORTIFY_UNTIL_HEALED, SLEEP, SLEEP_UNTIL_HEALED, WAKE, DISBAND, PARADROP,
    EXPLORE, STOP_EXPLORATION, AUTOMATE, STOP_AUTOMATION, GUARD, SET_UP, AIR_SWEEP,
    ESCORT_FORMATION, STOP_ESCORT_FORMATION
}

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

/** Native human city-state peace confirmation creates the normal timed bilateral treaty. */
class NegotiateCityStatePeaceCommand(
    override val actorCivId: String,
    val actionId: String,
    val expectedRevision: Int,
    val cityStateId: String
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
    SET_FOCUS, SET_MANUAL_SPECIALISTS, SET_AVOID_GROWTH, REASSIGN, TOGGLE_AVOID_GROWTH
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
    val flag: Boolean = false,
    /** Reset Citizens explicitly unlocks all tiles before reassignment. */
    val resetLocked: Boolean = false
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

/** Internal human-routed intent: validate all entries before starting any work. */
class QueueWorkerImprovementsCommand(
    override val actorCivId: String,
    val actionId: String,
    val expectedRevision: Int,
    val unitId: Int,
    val improvementNames: List<String>
) : AgentCommand

class WorkerImprovementsQueuedResult(
    override val actorCivId: String,
    val receipt: ActionReceipt,
    val turnsToBuild: Int
) : CommandResult

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

enum class GreatPersonAction { HURRY_RESEARCH, HURRY_POLICY, HURRY_WONDER, HURRY_BUILDING, TRADE_MISSION, GOLDEN_AGE }

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

/** Human free-person picker choice, distinct from an existing unit's special action. */
class ChooseFreeGreatPersonCommand(
    override val actorCivId: String,
    val actionId: String,
    val expectedRevision: Int,
    val unitName: String
) : AgentCommand

class FreeGreatPersonChosenResult(
    override val actorCivId: String,
    val receipt: ActionReceipt,
    val unitId: Int
) : CommandResult

/** Only informational alerts can be dismissed without answering a gameplay decision. */
class DismissAlertCommand(
    override val actorCivId: String,
    val actionId: String,
    val expectedRevision: Int,
    val alertType: AlertType,
    val alertValue: String
) : AgentCommand

class DismissDiplomaticVoteResultsCommand(
    override val actorCivId: String,
    val actionId: String,
    val expectedRevision: Int
) : AgentCommand

/** Consume the optional policy prompt; this never spends culture or free policies. */
class MarkPolicyPickerOpenedCommand(
    override val actorCivId: String,
    val actionId: String,
    val expectedRevision: Int
) : AgentCommand

class HumanPresentationResult(
    override val actorCivId: String,
    val receipt: ActionReceipt
) : CommandResult

/** These alerts have a close-only acknowledgement; decision-bearing alerts keep their typed routes. */
fun isDismissibleAgentAlert(type: AlertType): Boolean = type in setOf(
    AlertType.Defeated, AlertType.WonderBuilt, AlertType.TechResearched, AlertType.WarDeclaration,
    AlertType.FirstContact, AlertType.BorderConflict, AlertType.TilesStolen, AlertType.Denounced,
    AlertType.CitySettledNearOtherCivDespiteOurPromise, AlertType.ReligionSpreadDespiteOurPromise,
    AlertType.SpyingOnUsDespiteOurPromise, AlertType.AttackedUsDespitePromise,
    AlertType.AcceptingDemand, AlertType.RejectingDemand, AlertType.GoldenAge,
    AlertType.StartIntro, AlertType.GameHasBeenWon
)

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

/** Human city-state resource-improvement gift, including its fixed 200-gold cost. */
class GiftImprovementToCityStateCommand(
    override val actorCivId: String,
    val actionId: String,
    val expectedRevision: Int,
    val cityStateId: String,
    val tilePos: HexCoord,
    val improvementName: String
) : AgentCommand

class ImprovementGiftedResult(
    override val actorCivId: String,
    val receipt: ActionReceipt
) : CommandResult

/** Denounce a known civilization (game.act type `diplomacy.denounce`). */
class DenounceCommand(
    override val actorCivId: String,
    val actionId: String,
    val expectedRevision: Int,
    val targetCivId: String
) : AgentCommand

class DenouncedResult(
    override val actorCivId: String,
    val receipt: ActionReceipt
) : CommandResult

/** Rename an own city (game.act type `city.rename`). */
class RenameCityCommand(
    override val actorCivId: String,
    val actionId: String,
    val expectedRevision: Int,
    val cityId: String,
    val newName: String
) : AgentCommand

class CityRenamedResult(
    override val actorCivId: String,
    val receipt: ActionReceipt,
    val name: String
) : CommandResult

/** Cast a diplomatic-victory vote (game.act type `diplomacy.castVote`).
 *  A null/blank [targetCivId] is an abstention. Resolves the MANDATORY
 *  `diplomatic-vote` decision raised at turn start. */
class CastVoteCommand(
    override val actorCivId: String,
    val actionId: String,
    val expectedRevision: Int,
    val targetCivId: String?
) : AgentCommand

class VoteCastResult(
    override val actorCivId: String,
    val receipt: ActionReceipt,
    val targetCivId: String?
) : CommandResult

/** Annex an own puppet city (game.act type `city.annex`). */
class AnnexCityCommand(
    override val actorCivId: String,
    val actionId: String,
    val expectedRevision: Int,
    val cityId: String
) : AgentCommand

class CityAnnexedResult(
    override val actorCivId: String,
    val receipt: ActionReceipt
) : CommandResult

/** Start or stop razing an own city (game.act type `city.raze`). */
class SetRazingCommand(
    override val actorCivId: String,
    val actionId: String,
    val expectedRevision: Int,
    val cityId: String,
    val raze: Boolean
) : AgentCommand

class RazingSetResult(
    override val actorCivId: String,
    val receipt: ActionReceipt,
    val razing: Boolean
) : CommandResult

/** Cancel a worker's improvement-in-progress (game.act type `unit.stopImprovement`). */
class StopImprovementCommand(
    override val actorCivId: String,
    val actionId: String,
    val expectedRevision: Int,
    val unitId: Int
) : AgentCommand

class ImprovementStoppedResult(
    override val actorCivId: String,
    val receipt: ActionReceipt
) : CommandResult

/** Unit swap with an eligible unit on [destination] (game.act type `unit.swap`). */
class SwapUnitsCommand(
    override val actorCivId: String,
    val actionId: String,
    val expectedRevision: Int,
    val unitId: Int,
    val destination: HexCoord
) : AgentCommand

class UnitsSwappedResult(
    override val actorCivId: String,
    val receipt: ActionReceipt,
    val finalPosition: HexCoord
) : CommandResult

/** Rename an own unit (game.act type `unit.rename`). A blank [newName] clears. */
class RenameUnitCommand(
    override val actorCivId: String,
    val actionId: String,
    val expectedRevision: Int,
    val unitId: Int,
    val newName: String
) : AgentCommand

class UnitRenamedResult(
    override val actorCivId: String,
    val receipt: ActionReceipt,
    val name: String
) : CommandResult

/** Persistent road connection to [destination] (game.act type `unit.connectRoad`). */
class ConnectRoadCommand(
    override val actorCivId: String,
    val actionId: String,
    val expectedRevision: Int,
    val unitId: Int,
    val destination: HexCoord
) : AgentCommand

class RoadConnectionStartedResult(
    override val actorCivId: String,
    val receipt: ActionReceipt
) : CommandResult

/** Demand tribute from a city-state: gold or a worker (game.act `citystate.tribute`). */
class TributeCommand(
    override val actorCivId: String,
    val actionId: String,
    val expectedRevision: Int,
    val cityStateId: String,
    val demandWorker: Boolean
) : AgentCommand

class TributeTakenResult(
    override val actorCivId: String,
    val receipt: ActionReceipt,
    val worker: Boolean
) : CommandResult

/** Pledge or revoke protection of a city-state (game.act `citystate.protection`). */
class ProtectionCommand(
    override val actorCivId: String,
    val actionId: String,
    val expectedRevision: Int,
    val cityStateId: String,
    val pledge: Boolean
) : AgentCommand

class ProtectionSetResult(
    override val actorCivId: String,
    val receipt: ActionReceipt,
    val pledged: Boolean
) : CommandResult

/** Diplomatic marriage of a city-state (game.act `citystate.marry`).
 *  G&K-unique gated; Vanilla never exposes the button. */
class DiplomaticMarriageCommand(
    override val actorCivId: String,
    val actionId: String,
    val expectedRevision: Int,
    val cityStateId: String
) : AgentCommand

class CityStateMarriedResult(
    override val actorCivId: String,
    val receipt: ActionReceipt
) : CommandResult

/** Retract our pending trade offer (game.act type `diplomacy.retractTrade`). */
class RetractTradeCommand(
    override val actorCivId: String,
    val actionId: String,
    val expectedRevision: Int,
    val targetCivId: String
) : AgentCommand

class TradeRetractedResult(
    override val actorCivId: String,
    val receipt: ActionReceipt
) : CommandResult

enum class ProtectorAlertChoice { DECLARE_WAR, SIDE_WITH, BREAK_PLEDGE }

/** Answer a bullied/attacked-protected-minor alert
 *  (game.act type `diplomacy.respondProtectorAlert`). */
class RespondProtectorAlertCommand(
    override val actorCivId: String,
    val actionId: String,
    val expectedRevision: Int,
    val cityStateId: String,
    val attackerCivId: String,
    val choice: ProtectorAlertChoice,
    /** True when the war is for an allied (not merely protected) city-state. */
    val alliedCityState: Boolean = false
) : AgentCommand

class ProtectorAlertAnsweredResult(
    override val actorCivId: String,
    val receipt: ActionReceipt,
    val choice: ProtectorAlertChoice
) : CommandResult

/** Answer an incoming declaration-of-friendship offer
 *  (game.act type `diplomacy.respondFriendshipOffer`). */
class RespondFriendshipOfferCommand(
    override val actorCivId: String,
    val actionId: String,
    val expectedRevision: Int,
    val requesterCivId: String,
    val accept: Boolean
) : AgentCommand

class FriendshipOfferAnsweredResult(
    override val actorCivId: String,
    val receipt: ActionReceipt,
    val accepted: Boolean
) : CommandResult

/** Gift an own unit to the territory owner (city-state or friendly major civ) (game.act type `unit.gift`). */
class GiftUnitCommand(
    override val actorCivId: String,
    val actionId: String,
    val expectedRevision: Int,
    val unitId: Int
) : AgentCommand

class UnitGiftedResult(
    override val actorCivId: String,
    val receipt: ActionReceipt,
    val recipientCivId: String
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

/** Issue a diplomatic demand (game.act type `diplomacy.demand`).
 *  Mirrors the MajorCivDiplomacyTable demands button: places the demand
 *  alert on the target civ, which must answer on its own turn. */
class DemandCommand(
    override val actorCivId: String,
    val actionId: String,
    val expectedRevision: Int,
    val targetCivId: String,
    val demandName: String
) : AgentCommand

class DemandIssuedResult(
    override val actorCivId: String,
    val receipt: ActionReceipt,
    val issued: Boolean
) : CommandResult

/** Answer a received demand (game.act type `diplomacy.respondDemand`).
 *  The matching pending decision is resolved by accepting or refusing,
 *  exactly as the AlertPopup accept/refuse buttons do for humans. */
class RespondDemandCommand(
    override val actorCivId: String,
    val actionId: String,
    val expectedRevision: Int,
    val requesterCivId: String,
    val demandName: String,
    val accept: Boolean
) : AgentCommand

class DemandRespondedResult(
    override val actorCivId: String,
    val receipt: ActionReceipt,
    val accepted: Boolean
) : CommandResult

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
