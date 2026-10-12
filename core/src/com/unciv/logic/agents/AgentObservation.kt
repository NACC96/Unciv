package com.unciv.logic.agents

import com.unciv.logic.GameInfo
import com.unciv.logic.battle.TargetHelper
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.city.City
import com.unciv.models.ruleset.unique.UniqueTarget
import com.unciv.models.ruleset.unique.UniqueType

/**
 * Visibility-filtered observation DTOs (AI_AGENT_PLAN.md §5.1 game.observe /
 * game.inspect, §5.2 observation policy). Built ONLY from committed state,
 * never from candidates. Field-by-field policy:
 *
 * - Own empire: everything a human controller sees (cities + queues,
 *   specialists omitted v1, units + movement, research, policies, gold,
 *   diplomacy known to the civ, notifications as recent events).
 * - Visible world: foreign units passing [MapUnit.isVisibleTo] (tile in
 *   viewableTiles + invisibility rules); foreign cities on viewable tiles
 *   expose name/owner/health/population only — never queues or stats.
 * - Explored-but-unseen and unexplored: omitted entirely in v1 (no remembered
 *   foreign state; TileHistory integration is a follow-up). Unexplored geometry
 *   is not enumerated, so exploration orders stay closes over unknowns.
 * - Movement targets list ADJACENT enterable tiles only: anything beyond is
 *   discovered by moving, never by a global destinations oracle. Attack
 *   targets reuse the engine's own enumeration (the same data behind the UI
 *   attack arrows), which is visibility-safe by construction.
 */
class ObservedUnit(
    var unitId: Int = 0,
    var name: String = "",
    var ownerCivId: String = "",
    var x: Int = 0,
    var y: Int = 0,
    var health: Int = 0,
    var movementLeft: Float = 0f,
    var action: String? = null,
    /** Own-unit inspection only; foreign units never include tile detail. */
    var tile: ObservedTile? = null
) {
    @Suppress("unused")
    constructor() : this(0, "", "", 0, 0, 0, 0f, null)
}

class ObservedCity(
    var cityId: String = "",
    var name: String = "",
    var ownerCivId: String = "",
    var x: Int = 0,
    var y: Int = 0,
    var health: Int = 0,
    var population: Int = 0,
    var isPuppet: Boolean = false,
    var isBeingRazed: Boolean = false,
    /** Own cities only; always empty for foreign cities. */
    var productionQueue: ArrayList<String> = ArrayList()
) {
    @Suppress("unused")
    constructor() : this("", "", "", 0, 0, 0, 0, false, false, ArrayList())
}

class EmpireOverview(
    var gold: Int = 0,
    var currentResearch: String? = null,
    var researchQueue: ArrayList<String> = ArrayList(),
    var freeTechs: Int = 0,
    var storedCulture: Int = 0,
    var cultureNeeded: Int = 0,
    var freePolicies: Int = 0,
    var adoptedPolicies: ArrayList<String> = ArrayList(),
    var cityCount: Int = 0,
    var unitCount: Int = 0,
    /** One-time aircraft/missile grants waiting for a city with air capacity; not deployed units. */
    var pendingStartingUnits: LinkedHashMap<String, Int> = LinkedHashMap(),
    /** Enough culture or a free policy to adopt one now. */
    var canAdoptPolicy: Boolean = false,
    /** Policies and branches civics.adopt accepts now; empty unless [canAdoptPolicy]. */
    var adoptablePolicies: ArrayList<String> = ArrayList()
) {
    @Suppress("unused")
    constructor() : this(0, null, ArrayList(), 0, 0, 0, 0, ArrayList(), 0, 0, LinkedHashMap(), false, ArrayList())
}

class ObservationDto(
    var protocolVersion: Int = 1,
    var revision: Int = 0,
    var matchId: String = "",
    var timelineId: String = "",
    var turnSerial: Int = 0,
    var phase: TurnPhase = TurnPhase.READY_TO_START,
    var activeCivId: String = "",
    var isMyTurn: Boolean = false,
    /** Completed end-turn commits (monotonic; invocation completion signal). */
    var endTurnsCommitted: Int = 0,
    var requiredDecisions: ArrayList<PendingDecision> = ArrayList(),
    var recentEvents: ArrayList<String> = ArrayList(),
    var empire: EmpireOverview = EmpireOverview(),
    var ownUnits: ArrayList<ObservedUnit> = ArrayList(),
    var visibleForeignUnits: ArrayList<ObservedUnit> = ArrayList(),
    var ownCities: ArrayList<ObservedCity> = ArrayList(),
    var visibleForeignCities: ArrayList<ObservedCity> = ArrayList(),
    /** Current-turn delivered mail and older unread mail; repeated reads/retries do not drain it. */
    var inbox: ArrayList<DiplomaticMessage> = ArrayList(),
    var messageContacts: ArrayList<MessageContact> = ArrayList(),
    var observationVersion: Int = 2,
    var mapSummary: MapSummary = MapSummary(),
    /** At most twelve recent private records, including replies to the actor's sent mail. */
    var diplomaticHistory: ArrayList<DiplomaticMessage> = ArrayList()
) {
    @Suppress("unused")
    constructor() : this(1, 0, "", "", 0, TurnPhase.READY_TO_START, "", false, 0,
        ArrayList(), ArrayList(), EmpireOverview(), ArrayList(), ArrayList(), ArrayList(), ArrayList(),
        ArrayList(), ArrayList())
}

class AttackOption(
    var targetX: Int = 0,
    var targetY: Int = 0,
    var targetKind: String = ""
) {
    @Suppress("unused")
    constructor() : this(0, 0, "")
}

/** Gated action families for one entity (game.legal_actions). */
class LegalActionsDto(
    var moveTargets: ArrayList<HexCoord> = ArrayList(),
    var attackTargets: ArrayList<AttackOption> = ArrayList(),
    var orders: ArrayList<UnitOrder> = ArrayList(),
    /** unit.found would be accepted on the unit's current tile right now. */
    var canFound: Boolean = false,
    /** Why a founding unit cannot found here now, e.g. "Too close to another city"; null otherwise. */
    var foundBlockedReason: String? = null,
    /** Unit responses only: movement points left this turn. */
    var movementLeft: Float = 0f,
    var constructions: ArrayList<String> = ArrayList(),
    var researchable: ArrayList<String> = ArrayList(),
    var freeTechs: Int = 0,
    var freePolicies: Int = 0,
    var promotions: ArrayList<String> = ArrayList(),
    var canUpgrade: Boolean = false,
    var upgradeTo: String? = null,
    var greatActions: ArrayList<GreatPersonAction> = ArrayList(),
    var revision: Int = 0,
    var improvements: ArrayList<WorkerImprovementOption> = ArrayList(),
    var workInProgress: WorkInProgress? = null,
    var repair: RepairOption? = null,
    var canCancelWork: Boolean = false
) {
    @Suppress("unused")
    constructor() : this(ArrayList(), ArrayList(), ArrayList(), false, null, 0f, ArrayList(),
        ArrayList(), 0, 0, ArrayList(), false, null, ArrayList())
}

/** Static rules summary (game.rules): pinned identity + discoverable catalog. */
class RulesSummaryDto(
    var rulesetName: String = "",
    var baseRuleset: String = "",
    var gameVersion: String = "",
    var note: String = "Static rules may be cached; actions validated live against this ruleset.",
    /** Catalog sections game.rules accepts. */
    var sections: ArrayList<String> = ArrayList(RULES_SECTIONS)
) {
    @Suppress("unused")
    constructor() : this("", "", "", "", ArrayList(RULES_SECTIONS))
}

private fun MapUnit.toObserved() = ObservedUnit(
    id, name, owner,
    getTile().position.x, getTile().position.y,
    health, currentMovement, action
)

/**
 * Single-entity inspection (game.inspect): own units in full; foreign units
 * only when visible (identity + position; movement/orders zeroed); hidden
 * and nonexistent IDs share the null response (no oracle).
 */
fun buildUnitInspection(game: GameInfo, actorCivId: String, unitId: Int): ObservedUnit? {
    val civ = game.getCivilizationOrNull(actorCivId) ?: return null
    val own = civ.units.getCivUnits().firstOrNull { it.id == unitId }
    if (own != null && !own.isDestroyed) return own.toObserved()
    for (other in game.civilizations) {
        if (other.civID == actorCivId || other.isSpectator()) continue
        val foreign = other.units.getCivUnits().firstOrNull { it.id == unitId }
        if (foreign != null && !foreign.isDestroyed && foreign.isVisibleTo(civ)) {
            val observed = foreign.toObserved()
            observed.movementLeft = 0f
            observed.action = null
            return observed
        }
    }
    return null
}

private fun City.toObservedOwn(): ObservedCity {
    val pos = getCenterTile().position
    return ObservedCity(
        id, name, civ.civID,
        pos.x, pos.y, health, population.population,
        isPuppet, isBeingRazed,
        ArrayList(cityConstructions.constructionQueue)
    )
}

private fun City.toObservedForeign(): ObservedCity {
    val pos = getCenterTile().position
    return ObservedCity(
        id, name, civ.civID,
        pos.x, pos.y, health, population.population,
        isPuppet, isBeingRazed, ArrayList()
    )
}

/**
 * Builds the permitted overview for [actorCivId] from committed game + match
 * state. Pure read path: no mutation, no candidate, no global side effects.
 */
fun buildObservation(
    game: GameInfo,
    match: AgentMatchState,
    actorCivId: String
): ObservationDto {
    val civ = game.getCivilizationOrNull(actorCivId)
        ?: throw AgentCommandException(AgentCommandError.INVALID_ARGUMENT, "Unknown civilization $actorCivId")
    val dto = ObservationDto()
    dto.revision = match.stateRevision
    dto.mapSummary = buildMapSummary(game, match, actorCivId)
    dto.matchId = match.matchId
    dto.timelineId = match.timelineId
    dto.turnSerial = match.turnSerial
    dto.phase = match.turnPhase
    dto.activeCivId = game.currentPlayer
    dto.isMyTurn = match.turnPhase == TurnPhase.AWAITING_DECISIONS && game.currentPlayer == actorCivId
    dto.endTurnsCommitted = match.endTurnsCommitted
    if (dto.isMyTurn) dto.inbox = ArrayList(match.diplomaticMessages.filter {
        it.isDeliveredTo(actorCivId, match.turnSerial) &&
            (it.deliveredTurnSerial == match.turnSerial || it.acknowledgedTurnSerial == null)
    }.map { it.copy() })
    dto.diplomaticHistory = ArrayList(match.diplomaticMessages.withIndex().filter {
        it.value.senderCivId == actorCivId || it.value.isDeliveredTo(actorCivId, match.turnSerial)
    }.sortedWith(compareByDescending<IndexedValue<DiplomaticMessage>> {
        maxOf(it.value.sentTurnSerial, it.value.deliveredTurnSerial ?: -1,
            it.value.acknowledgedTurnSerial ?: -1)
    }.thenByDescending { it.value.acknowledgedRevision ?: -1 }.thenByDescending { it.index })
        .take(12).map { it.value.copy() })
    dto.messageContacts = buildMessageContacts(game, match, actorCivId)
    dto.requiredDecisions = ArrayList(match.pendingDecisions
        .filter { it.owningCivId == actorCivId }.map { it.copy() })
    dto.recentEvents = ArrayList(civ.notifications.takeLast(20).map { it.text })

    dto.empire = EmpireOverview(
        civ.gold,
        civ.tech.currentTechnologyName(),
        ArrayList(civ.tech.techsToResearch),
        civ.tech.freeTechs,
        civ.policies.storedCulture,
        try { civ.policies.getCultureNeededForNextPolicy() } catch (ex: Exception) { 0 },
        civ.policies.freePolicies,
        ArrayList(civ.policies.getAdoptedPolicies().toList()),
        civ.cities.size,
        civ.units.getCivUnitsSize(),
        LinkedHashMap(civ.pendingStartingUnits)
    )
    dto.empire.canAdoptPolicy = civ.policies.canAdoptPolicy()
    if (dto.empire.canAdoptPolicy) dto.empire.adoptablePolicies = ArrayList(
        game.ruleset.policies.values.filter { civ.policies.isAdoptable(it) }.map { it.name })

    for (unit in civ.units.getCivUnits()) {
        if (!unit.isDestroyed) dto.ownUnits.add(unit.toObserved())
    }
    for (city in civ.cities) dto.ownCities.add(city.toObservedOwn())

    for (other in game.civilizations) {
        if (other.civID == actorCivId || other.isSpectator()) continue
        for (unit in other.units.getCivUnits()) {
            if (unit.isDestroyed || !unit.isVisibleTo(civ)) continue
            // Foreign units expose identity and position only: movement and
            // orders are not observable (and must not leak readiness).
            val observed = unit.toObserved()
            observed.movementLeft = 0f
            observed.action = null
            dto.visibleForeignUnits.add(observed)
        }
        for (city in other.cities) {
            if (city.getCenterTile() in civ.viewableTiles) dto.visibleForeignCities.add(city.toObservedForeign())
        }
    }
    return dto
}

/**
 * Gated per-entity actions for [unitId] or [cityId] (exactly one set).
 * Movement targets are adjacent enterable tiles only (no destination oracle);
 * attacks reuse the engine enumeration behind the UI arrows.
 */
fun buildLegalActions(
    game: GameInfo,
    actorCivId: String,
    unitId: Int? = null,
    cityId: String? = null
): LegalActionsDto {
    if ((unitId == null) == (cityId == null))
        throw AgentCommandException(AgentCommandError.INVALID_ARGUMENT, "Exactly one of unitId/cityId is required")
    val civ = game.getCivilizationOrNull(actorCivId)
        ?: throw AgentCommandException(AgentCommandError.INVALID_ARGUMENT, "Unknown civilization $actorCivId")
    val dto = LegalActionsDto()
    dto.researchable = ArrayList(
        game.ruleset.technologies.values.filter { civ.tech.canBeResearched(it.name) }.map { it.name })
    dto.freeTechs = civ.tech.freeTechs
    dto.freePolicies = civ.policies.freePolicies

    if (unitId != null) {
        val unit = civ.units.getCivUnits().firstOrNull { it.id == unitId }
            ?: throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "No such visible unit")
        val workerOptions = buildWorkerOptions(game, actorCivId, unit)
        dto.improvements = workerOptions.improvements
        dto.workInProgress = workerOptions.workInProgress
        dto.repair = workerOptions.repair
        dto.canCancelWork = workerOptions.canCancelWork
        if (unit.owner != actorCivId || unit.isDestroyed)
            throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "No such visible unit")
        if (unit.hasMovement()) {
            for (neighbor in unit.getTile().neighbors) {
                if (unit.movement.canMoveTo(neighbor)) dto.moveTargets.add(neighbor.position)
            }
            if (unit.canAttack()) {
                for (option in TargetHelper.getAttackableEnemies(unit, unit.movement.getDistanceToTiles()))
                    dto.attackTargets.add(AttackOption(
                        option.tileToAttack.position.x, option.tileToAttack.position.y,
                        if (option.tileToAttack.isCityCenter()) "city" else "unit"))
            }
        }
        if (unit.canFortify() && unit.hasMovement()) dto.orders.add(UnitOrder.FORTIFY)
        if (!unit.isFortifyingUntilHealed() && unit.canHealInCurrentTile()) dto.orders.add(UnitOrder.FORTIFY_UNTIL_HEALED)
        if (!unit.isFortified() && !unit.canFortify() && !unit.isGuarding() && unit.hasMovement())
            dto.orders.add(UnitOrder.SLEEP)
        if (!unit.isSleepingUntilHealed() && unit.canHealInCurrentTile()) dto.orders.add(UnitOrder.SLEEP_UNTIL_HEALED)
        if (unit.action != null) dto.orders.add(UnitOrder.WAKE)
        dto.orders.add(UnitOrder.DISBAND)
        dto.movementLeft = unit.currentMovement
        if (unit.getMatchingUniques(UniqueType.FoundCity).any() || unit.getMatchingUniques(UniqueType.FoundPuppetCity).any()) {
            dto.foundBlockedReason = foundCityBlocker(unit)
            dto.canFound = dto.foundBlockedReason == null
        }
        if (unit.promotions.canBePromoted())
            dto.promotions = ArrayList(unit.promotions.getAvailablePromotions().map { it.name }.toList())
        val upgradesTo = unit.baseUnit.upgradesTo
        if (!upgradesTo.isNullOrEmpty()) {
            val target = civ.getEquivalentUnit(upgradesTo)
            dto.canUpgrade = unit.upgrade.canUpgrade(target)
            dto.upgradeTo = target.name
        }
        for (action in GreatPersonAction.entries) {
            val unique = when (action) {
                GreatPersonAction.HURRY_RESEARCH ->
                    UniqueType.CanHurryResearch
                GreatPersonAction.HURRY_POLICY ->
                    UniqueType.CanHurryPolicy
                GreatPersonAction.HURRY_WONDER ->
                    UniqueType.CanSpeedupWonderConstruction
                GreatPersonAction.HURRY_BUILDING ->
                    UniqueType.CanSpeedupConstruction
                GreatPersonAction.TRADE_MISSION ->
                    UniqueType.CanTradeWithCityStateForGoldAndInfluence
                GreatPersonAction.GOLDEN_AGE ->
                    UniqueType.OneTimeEnterGoldenAgeTurns
            }
            if (unit.getMatchingUniques(unique).any()
                || (action == GreatPersonAction.GOLDEN_AGE
                    && unit.getMatchingUniques(UniqueType.OneTimeEnterGoldenAge).any())
            ) dto.greatActions.add(action)
        }
        return dto
    }

    val city = game.getCities().firstOrNull { it.id == cityId }
    if (city == null || city.civ.civID != actorCivId)
        throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "No such city under your control")
    for (name in game.ruleset.buildings.keys + game.ruleset.units.keys) {
        try {
            val construction = city.cityConstructions.getConstruction(name)
            if (city.cityConstructions.canAddToQueue(construction)) dto.constructions.add(name)
        } catch (ex: Exception) {
            // Unknown/unbuildable names are skipped, not errors.
        }
    }
    return dto
}

/**
 * Engine founding checks shared by legal_actions and unit.found, so canFound
 * never promises a city the command then rejects. Null means the unit can
 * found on its current tile now. Spacing ignores visibility, matching the
 * human UI's disabled found-city button.
 */
fun foundCityBlocker(unit: MapUnit): String? {
    val unique = unit.getMatchingUniques(UniqueType.FoundCity).firstOrNull()
        ?: unit.getMatchingUniques(UniqueType.FoundPuppetCity).firstOrNull()
        ?: return "Unit cannot found cities"
    if (unique.modifiers.any {
            it.type?.targetTypes?.contains(UniqueTarget.UnitActionModifier) == true
                && it.type != UniqueType.UnitActionConsumeUnit
        }) return "Complex founding rites are unsupported in v1"
    val tile = unit.getTile()
    val civ = unit.civ
    return when {
        tile.isWater || tile.isImpassible() -> "Cannot found a city here"
        civ.isOneCityChallenger() && civ.hasEverOwnedOriginalCapital -> "Cannot found another city"
        !unit.hasMovement() -> "Unit has no movement left"
        !tile.canBeSettled(civ) -> "Too close to another city"
        else -> null
    }
}

fun buildRulesSummary(game: GameInfo): RulesSummaryDto {
    return RulesSummaryDto(
        rulesetName = game.ruleset.name,
        baseRuleset = game.gameParameters.baseRuleset,
        gameVersion = com.unciv.UncivGame.VERSION.toString()
    )
}
