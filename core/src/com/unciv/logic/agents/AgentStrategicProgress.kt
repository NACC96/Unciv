package com.unciv.logic.agents

import com.unciv.logic.GameInfo
import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.INonPerpetualConstruction

private const val STRATEGIC_HISTORY_LIMIT = 3
private const val STRATEGIC_EVENT_LIMIT = 12
private const val STRATEGIC_PRODUCTION_LIMIT = 8

/** Stable own-entity facts only. These complete baselines are saved, never sent as raw observations. */
data class StrategicUnitSnapshot(
    var unitId: Int = 0,
    var name: String = "",
    var x: Int = 0,
    var y: Int = 0,
    var health: Int = 0
) : IsPartOfGameInfoSerialization

data class StrategicCitySnapshot(
    var cityId: String = "",
    var name: String = "",
    /** The major civilization's original capital, matching the Domination objective. */
    var isOriginalCapital: Boolean = false,
    var foundingCivId: String? = null,
    var population: Int = 0,
    var construction: String = "",
    var production: HashMap<String, Int> = HashMap(),
    var buildings: ArrayList<String> = ArrayList()
) : IsPartOfGameInfoSerialization {
    fun detachedCopy() = copy(production = HashMap(production), buildings = ArrayList(buildings))
}

data class StrategicSnapshot(
    var gameTurn: Int = 0,
    var turnSerial: Int = 0,
    var research: String? = null,
    var science: HashMap<String, Int> = HashMap(),
    var technologies: ArrayList<String> = ArrayList(),
    var repeatedTechnologies: Int = 0,
    var policies: ArrayList<String> = ArrayList(),
    var storedCulture: Int = 0,
    var gold: Int = 0,
    var cities: ArrayList<StrategicCitySnapshot> = ArrayList(),
    var units: ArrayList<StrategicUnitSnapshot> = ArrayList()
) : IsPartOfGameInfoSerialization {
    fun detachedCopy() = copy(science = HashMap(science), technologies = ArrayList(technologies),
        policies = ArrayList(policies), cities = cities.mapTo(ArrayList()) { it.detachedCopy() },
        units = units.mapTo(ArrayList()) { it.copy() })
}

/**
 * An observed state difference, not an invented explanation of its cause. A unit gained may have
 * been built, bought, gifted or captured; a unit lost may have died, been gifted or consumed.
 * Research/production selection changes never stand in for completion.
 */
data class StrategicProgressEvent(
    var kind: String = "",
    var unitId: Int? = null,
    var cityId: String? = null,
    var name: String? = null,
    var previousName: String? = null,
    var beforeNumber: Int? = null,
    var afterNumber: Int? = null,
    var fromX: Int? = null,
    var fromY: Int? = null,
    var toX: Int? = null,
    var toY: Int? = null,
    var isOriginalCapital: Boolean? = null,
    var foundingCivId: String? = null
) : IsPartOfGameInfoSerialization

data class StrategicProgressDelta(
    /** False means an earlier boundary is missing; it never means there were zero earlier changes. */
    var baselineAvailable: Boolean = false,
    var fromGameTurn: Int? = null,
    var toGameTurn: Int = 0,
    /** Counts cover every difference, including details omitted by [truncated]. */
    var eventCounts: LinkedHashMap<String, Int> = LinkedHashMap(),
    var events: ArrayList<StrategicProgressEvent> = ArrayList(),
    var truncated: Boolean = false
) : IsPartOfGameInfoSerialization {
    fun detachedCopy() = copy(eventCounts = LinkedHashMap(eventCounts), events = events.mapTo(ArrayList()) { it.copy() })
}

data class CompletedStrategicTurn(
    /** Tracking-local ordinal, not an estimate of how many turns this civilization has ever had. */
    var ownTurnOrdinal: Int = 0,
    var gameTurn: Int = 0,
    var turnSerial: Int = 0,
    var historyComplete: Boolean = false,
    var changes: StrategicProgressDelta = StrategicProgressDelta(),
    /** Diagnostic only. Sending mail or repeatedly issuing orders is not objective progress. */
    var appliedCommandCounts: LinkedHashMap<String, Int> = LinkedHashMap()
) : IsPartOfGameInfoSerialization {
    fun detachedCopy() = copy(changes = changes.detachedCopy(), appliedCommandCounts = LinkedHashMap(appliedCommandCounts))
}

/** One actor's transaction-owned coverage; private strategy remains in the existing notes string. */
data class AgentStrategicHistory(
    var trackedSinceGameTurn: Int = 0,
    var trackedSinceTurnSerial: Int = 0,
    var ownTurnOrdinal: Int = 0,
    var admission: StrategicSnapshot? = null,
    var previousEnd: StrategicSnapshot? = null,
    var incoming: StrategicProgressDelta = StrategicProgressDelta(),
    var completedTurns: ArrayList<CompletedStrategicTurn> = ArrayList()
) : IsPartOfGameInfoSerialization {
    fun detachedCopy() = copy(admission = admission?.detachedCopy(), previousEnd = previousEnd?.detachedCopy(),
        incoming = incoming.detachedCopy(), completedTurns = completedTurns.mapTo(ArrayList()) { it.detachedCopy() })
}

class ObservedStrategicResearch(
    var name: String = "",
    var storedScience: Int = 0,
    var cost: Int = 0,
    var remainingScience: Int = 0
)

class ObservedStrategicProduction(
    var cityId: String = "",
    var name: String = "",
    var construction: String = "",
    var storedProduction: Int = 0,
    /** Null for perpetual work such as Idle or converting production to another yield. */
    var cost: Int? = null
)

class ObservedCurrentStrategicWork(
    var research: ObservedStrategicResearch? = null,
    var production: ArrayList<ObservedStrategicProduction> = ArrayList(),
    var productionCount: Int = 0,
    var productionTruncated: Boolean = false
)

class ObservedStrategicProgress(
    /** True only after all three retained own turns have complete incoming and action coverage. */
    var historyComplete: Boolean = false,
    var trackedSinceGameTurn: Int? = null,
    var trackedSinceTurnSerial: Int? = null,
    /** Counts admissions since tracking began; zero means this actor has not been admitted yet. */
    var ownTurnOrdinal: Int = 0,
    var current: ObservedCurrentStrategicWork = ObservedCurrentStrategicWork(),
    var incoming: StrategicProgressDelta = StrategicProgressDelta(),
    var completedTurns: ArrayList<CompletedStrategicTurn> = ArrayList()
)

/** Pure actor-scoped read. All returned history is detached from the committed match. */
fun buildStrategicProgress(game: GameInfo, match: AgentMatchState, actorCivId: String): ObservedStrategicProgress {
    val civ = game.getCivilization(actorCivId)
    val history = match.strategicProgress[actorCivId]
    val completed = history?.completedTurns.orEmpty().takeLast(STRATEGIC_HISTORY_LIMIT)
    val current = ObservedCurrentStrategicWork(productionCount = civ.cities.size,
        productionTruncated = civ.cities.size > STRATEGIC_PRODUCTION_LIMIT)
    civ.tech.currentTechnologyName()?.let { name ->
        current.research = ObservedStrategicResearch(name, civ.tech.researchOfTech(name),
            civ.tech.costOfTech(name), civ.tech.remainingScienceToTech(name))
    }
    for (city in civ.cities.sortedBy { it.id }.take(STRATEGIC_PRODUCTION_LIMIT)) {
        val construction = city.cityConstructions.currentConstructionName()
        val definition = city.cityConstructions.getCurrentConstruction()
        current.production.add(ObservedStrategicProduction(city.id, city.name, construction,
            city.cityConstructions.getWorkDone(construction),
            (definition as? INonPerpetualConstruction)?.getProductionCost(civ, city)))
    }
    return ObservedStrategicProgress(
        historyComplete = completed.size == STRATEGIC_HISTORY_LIMIT && completed.all { it.historyComplete },
        trackedSinceGameTurn = history?.trackedSinceGameTurn,
        trackedSinceTurnSerial = history?.trackedSinceTurnSerial,
        ownTurnOrdinal = history?.ownTurnOrdinal ?: 0,
        current = current,
        incoming = history?.incoming?.detachedCopy() ?: StrategicProgressDelta(toGameTurn = game.turns),
        completedTurns = completed.mapTo(ArrayList()) { it.detachedCopy() }
    )
}

/** Called once on an unpublished admission candidate, AFTER start-turn effects and serial advance. */
internal fun admitStrategicTurn(game: GameInfo, match: AgentMatchState, actorCivId: String) {
    val existing = match.strategicProgress[actorCivId]
    if (existing?.admission?.turnSerial == match.turnSerial) return
    val snapshot = captureStrategicSnapshot(game, match, game.getCivilization(actorCivId))
    val history = existing ?: AgentStrategicHistory(game.turns, match.turnSerial).also {
        match.strategicProgress[actorCivId] = it
    }
    history.ownTurnOrdinal++
    history.incoming = compareStrategicSnapshots(history.previousEnd, snapshot)
    history.admission = snapshot
}

/**
 * Called on the same end-turn candidate AFTER end effects and the end receipt, BEFORE rotation
 * clears receipts or start-turn effects change the world. A failed checkpoint discards all of it.
 */
internal fun finishStrategicTurn(game: GameInfo, match: AgentMatchState, actorCivId: String) {
    val history = match.strategicProgress[actorCivId] ?: return
    val admission = history.admission ?: return
    // The coordinator's idempotency handles retries; this also keeps a restored same boundary inert.
    if (history.completedTurns.lastOrNull()?.turnSerial == admission.turnSerial) return
    val end = captureStrategicSnapshot(game, match, game.getCivilization(actorCivId))
    val ownChanges = compareStrategicSnapshots(admission, end)
    val applied = match.currentTurnReceipts.filter { it.actorCivId == actorCivId && it.outcome == "applied" }
        .groupingBy { it.requestFingerprint.substringBefore('|') }.eachCount()
        .filterKeys { !it.startsWith("internal:") && it != "end-turn" }
    history.completedTurns.add(CompletedStrategicTurn(history.ownTurnOrdinal, game.turns, match.turnSerial,
        history.incoming.baselineAvailable, mergeStrategicChanges(history.incoming, ownChanges),
        LinkedHashMap(applied.toSortedMap())))
    while (history.completedTurns.size > STRATEGIC_HISTORY_LIMIT) history.completedTurns.removeAt(0)
    history.previousEnd = end
}

private fun captureStrategicSnapshot(game: GameInfo, match: AgentMatchState, civ: Civilization): StrategicSnapshot =
    StrategicSnapshot(game.turns, match.turnSerial, civ.tech.currentTechnologyName(), HashMap(civ.tech.techsInProgress),
        ArrayList(civ.tech.techsResearched.sorted()), civ.tech.repeatingTechsResearched,
        ArrayList(civ.policies.getAdoptedPolicies().sorted()), civ.policies.storedCulture, civ.gold,
        civ.cities.sortedBy { it.id }.mapTo(ArrayList()) { city ->
            StrategicCitySnapshot(city.id, city.name,
                city.isOriginalCapital && city.foundingCivObject?.isMajorCiv() == true,
                city.foundingCivObject?.civID, city.population.population,
                city.cityConstructions.currentConstructionName(), HashMap(city.cityConstructions.inProgressConstructions),
                city.cityConstructions.getBuiltBuildings().map { it.name }.sorted().toCollection(ArrayList()))
        },
        civ.units.getCivUnits().sortedBy { it.id }.mapTo(ArrayList()) { unit ->
            StrategicUnitSnapshot(unit.id, unit.name, unit.getTile().position.x, unit.getTile().position.y, unit.health)
        })

/** Differences are deterministic and bounded in detail, while every kind's total survives truncation. */
private fun compareStrategicSnapshots(before: StrategicSnapshot?, after: StrategicSnapshot): StrategicProgressDelta {
    if (before == null) return StrategicProgressDelta(toGameTurn = after.gameTurn)
    val events = ArrayList<StrategicProgressEvent>()
    val oldCities = before.cities.associateBy { it.cityId }
    val newCities = after.cities.associateBy { it.cityId }
    for (city in before.cities.filter { it.cityId !in newCities }) events.add(cityOwnershipEvent(city, gained = false))
    for (city in after.cities.filter { it.cityId !in oldCities }) events.add(cityOwnershipEvent(city, gained = true))
    val oldUnits = before.units.associateBy { it.unitId }
    val newUnits = after.units.associateBy { it.unitId }
    for (unit in before.units.filter { it.unitId !in newUnits }) events.add(StrategicProgressEvent(
        "unitLost", unitId = unit.unitId, name = unit.name, fromX = unit.x, fromY = unit.y, beforeNumber = unit.health))
    for (unit in after.units.filter { it.unitId !in oldUnits }) events.add(StrategicProgressEvent(
        "unitGained", unitId = unit.unitId, name = unit.name, toX = unit.x, toY = unit.y, afterNumber = unit.health))
    for (technology in after.technologies.filter { it !in before.technologies })
        events.add(StrategicProgressEvent("technologyGained", name = technology))
    if (before.repeatedTechnologies != after.repeatedTechnologies) events.add(StrategicProgressEvent("repeatedResearchChanged",
        beforeNumber = before.repeatedTechnologies, afterNumber = after.repeatedTechnologies))
    for (policy in after.policies.filter { it !in before.policies })
        events.add(StrategicProgressEvent("policyAdopted", name = policy))
    for (policy in before.policies.filter { it !in after.policies })
        events.add(StrategicProgressEvent("policyLost", name = policy))
    for (city in after.cities) {
        val previous = oldCities[city.cityId] ?: continue
        for (building in city.buildings.filter { it !in previous.buildings })
            events.add(StrategicProgressEvent("buildingGained", cityId = city.cityId, name = building))
        for (building in previous.buildings.filter { it !in city.buildings })
            events.add(StrategicProgressEvent("buildingLost", cityId = city.cityId, name = building))
        if (previous.population != city.population) events.add(StrategicProgressEvent("populationChanged",
            cityId = city.cityId, name = city.name, beforeNumber = previous.population, afterNumber = city.population))
        if (previous.construction != city.construction) events.add(StrategicProgressEvent("productionSelectionChanged",
            cityId = city.cityId, name = city.construction, previousName = previous.construction))
        for (construction in (previous.production.keys + city.production.keys).sorted()) {
            val work = city.production[construction] ?: 0
            val previousWork = previous.production[construction] ?: 0
            if (work != previousWork) events.add(StrategicProgressEvent("productionWorkChanged",
                cityId = city.cityId, name = construction, beforeNumber = previousWork, afterNumber = work))
        }
    }
    for (unit in after.units) {
        val previous = oldUnits[unit.unitId] ?: continue
        if (previous.name != unit.name) events.add(StrategicProgressEvent("unitTypeChanged",
            unitId = unit.unitId, name = unit.name, previousName = previous.name))
        if (previous.x != unit.x || previous.y != unit.y) events.add(StrategicProgressEvent("unitMoved",
            unitId = unit.unitId, name = unit.name, fromX = previous.x, fromY = previous.y, toX = unit.x, toY = unit.y))
        if (previous.health != unit.health) events.add(StrategicProgressEvent("unitHealthChanged",
            unitId = unit.unitId, name = unit.name, beforeNumber = previous.health, afterNumber = unit.health))
    }
    if (before.research != after.research) events.add(StrategicProgressEvent("researchSelectionChanged",
        name = after.research, previousName = before.research))
    for (technology in (before.science.keys + after.science.keys).sorted()) {
        val science = after.science[technology] ?: 0
        val previousScience = before.science[technology] ?: 0
        if (science != previousScience) events.add(StrategicProgressEvent("researchWorkChanged",
            name = technology, beforeNumber = previousScience, afterNumber = science))
    }
    if (before.storedCulture != after.storedCulture) events.add(StrategicProgressEvent("storedCultureChanged",
        beforeNumber = before.storedCulture, afterNumber = after.storedCulture))
    if (before.gold != after.gold) events.add(StrategicProgressEvent("goldChanged",
        beforeNumber = before.gold, afterNumber = after.gold))
    return boundedStrategicDelta(true, before.gameTurn, after.gameTurn,
        events.groupingBy { it.kind }.eachCount(), events, false)
}

private fun cityOwnershipEvent(city: StrategicCitySnapshot, gained: Boolean): StrategicProgressEvent =
    StrategicProgressEvent(
        (if (city.isOriginalCapital) "originalCapital" else "city") + (if (gained) "Gained" else "Lost"),
        cityId = city.cityId, name = city.name, isOriginalCapital = city.isOriginalCapital,
        foundingCivId = city.foundingCivId)

private fun mergeStrategicChanges(incoming: StrategicProgressDelta, actions: StrategicProgressDelta): StrategicProgressDelta {
    val counts = HashMap(incoming.eventCounts)
    for ((kind, count) in actions.eventCounts) counts[kind] = (counts[kind] ?: 0) + count
    return boundedStrategicDelta(incoming.baselineAvailable, incoming.fromGameTurn ?: actions.fromGameTurn,
        actions.toGameTurn, counts, incoming.events + actions.events, incoming.truncated || actions.truncated)
}

private fun boundedStrategicDelta(
    baselineAvailable: Boolean, fromGameTurn: Int?, toGameTurn: Int,
    counts: Map<String, Int>, events: List<StrategicProgressEvent>, alreadyTruncated: Boolean
): StrategicProgressDelta {
    // Losses and acquired assets cannot be crowded out by a long column of marching/healing units.
    val prioritized = events.sortedBy { when (it.kind) {
        "originalCapitalLost", "originalCapitalGained" -> 0
        "unitLost", "unitGained", "cityLost", "cityGained" -> 1
        "technologyGained", "policyAdopted", "policyLost", "buildingGained", "buildingLost", "repeatedResearchChanged" -> 2
        "researchWorkChanged", "productionWorkChanged", "unitMoved", "unitHealthChanged", "unitTypeChanged" -> 3
        else -> 4
    } }
    return StrategicProgressDelta(baselineAvailable, fromGameTurn, toGameTurn,
        LinkedHashMap(counts.toSortedMap()), prioritized.take(STRATEGIC_EVENT_LIMIT).mapTo(ArrayList()) { it.copy() },
        alreadyTruncated || events.size > STRATEGIC_EVENT_LIMIT)
}
