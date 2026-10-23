package com.unciv.logic.agents

import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.models.ruleset.Milestone
import com.unciv.models.ruleset.MilestoneType
import com.unciv.models.ruleset.Victory

/** Bounds apply to detail, never to the saved enabled set or canonical completion checks. */
private const val VICTORY_DETAIL_LIMIT = 32

class ObservedVictoryMilestone(
    var description: String = "",
    var type: String? = null,
    /** Null only for a milestone this engine adapter cannot interpret. */
    var completed: Boolean? = null,
    var current: Int? = null,
    var target: Int? = null,
    /** Capability of the external command interface, independent of enablement/completion. */
    var status: String = "supported",
    var unsupportedReason: String? = null,
    var remainingSpaceshipParts: LinkedHashMap<String, Int>? = null,
    var partsTruncated: Boolean = false
)

class ObservedVictoryRoute(
    var name: String = "",
    var completed: Boolean? = null,
    var completedMilestones: Int = 0,
    /** Zero-based index into the canonical ordered milestone definition; null when complete. */
    var nextMilestone: Int? = null,
    var milestones: ArrayList<ObservedVictoryMilestone> = ArrayList(),
    var milestonesTruncated: Boolean = false,
    var progress: ObservedVictoryProgress? = null
)

class ObservedOriginalCapital(
    var foundingCivId: String = "",
    var cityId: String? = null,
    var name: String? = null,
    var x: Int? = null,
    var y: Int? = null,
    var ownerCivId: String? = null,
    /** CURRENT is real sight/FULL; REVEALED is MAP; REMEMBERED never refreshes ownership. */
    var knowledge: String = "UNKNOWN",
    var lastSeenGameTurn: Int? = null,
    var lastSeenRevision: Int? = null
)

class ObservedDomination(
    var ownedOriginalCapitals: Int = 0,
    /** Null when the actor is not allowed to know the civilization count. */
    var requiredOriginalCapitals: Int? = null,
    var capitals: ArrayList<ObservedOriginalCapital> = ArrayList(),
    var capitalsTruncated: Boolean = false
)

class ObservedVictoryContext(
    var enabledVictoryTypes: ArrayList<String> = ArrayList(),
    /** Canonical availability also applies civilization-specific rules. */
    var available: ArrayList<ObservedVictoryRoute> = ArrayList(),
    /** Canonical satisfied objective; awarding the game still belongs to the engine's turn path. */
    var achieved: String? = null,
    var domination: ObservedDomination? = null,
    var availableTruncated: Boolean = false
)

/** Read-only adapter. Do not advance a turn, award a victory, or refresh saved sightings here. */
fun buildVictoryObservation(game: GameInfo, match: AgentMatchState, civ: Civilization): ObservedVictoryContext {
    val available = civ.victoryManager.getAvailableVictories()
    val hideCount = match.vision == MatchVision.FOG && civ.shouldHideCivCount()
    val context = ObservedVictoryContext(enabledVictoryTypes = ArrayList(game.gameParameters.victoryTypes))
    val dominationMilestone = available.asSequence().flatMap { it.milestoneObjects.asSequence() }
        .firstOrNull { it.type == MilestoneType.CaptureAllCapitals }
    if (dominationMilestone != null) context.domination = buildDomination(game, match, civ, dominationMilestone, hideCount)
    // Unknown mod syntax must remain unknown, not crash the ordinary observation or count as a win.
    val allKnown = available.all { victory -> victory.milestoneObjects.all { it.type != null } }
    if (allKnown) context.achieved = civ.victoryManager.getVictoryTypeAchieved()
    else context.achieved = available.firstOrNull { victory ->
        victory.milestoneObjects.all { it.type != null && it.hasBeenCompletedBy(civ) }
    }?.name
    for (victory in available.take(VICTORY_DETAIL_LIMIT)) {
        val milestones = victory.milestoneObjects
        val states = milestones.map { if (it.type == null) null else it.hasBeenCompletedBy(civ) }
        val next = states.indexOfFirst { it != true }.takeIf { it >= 0 }
        context.available.add(ObservedVictoryRoute(
            name = victory.name,
            completed = if (states.any { it == false }) false else if (states.any { it == null }) null else true,
            completedMilestones = states.takeWhile { it == true }.size,
            nextMilestone = next,
            milestones = milestones.take(VICTORY_DETAIL_LIMIT).mapIndexedTo(ArrayList()) { index, milestone ->
                observeMilestone(civ, victory, milestone, states[index], hideCount)
            },
            milestonesTruncated = milestones.size > VICTORY_DETAIL_LIMIT,
            progress = match.victoryProgress[civ.civID]?.routes?.get(victory.name)?.let { record ->
                val capital = milestones.firstOrNull { it.type == MilestoneType.CaptureAllCapitals }
                val target = if (capital == null) milestones.size
                    else if (hideCount) null else capital.civsWithPotentialCapitalsToOwn(game).size
                ObservedVictoryProgress(record.metric, record.current, target,
                    record.trackedSinceGameTurn, record.unchangedSinceGameTurn, record.lastChangedGameTurn)
            }
        ))
    }
    context.availableTruncated = available.size > VICTORY_DETAIL_LIMIT
    return context
}

private fun observeMilestone(
    civ: Civilization, victory: Victory, milestone: Milestone, completed: Boolean?, hideCount: Boolean
): ObservedVictoryMilestone {
    val dto = ObservedVictoryMilestone(milestone.uniqueDescription, milestone.type?.name, completed)
    when (milestone.type) {
        null -> {
            dto.status = "unsupported"
            dto.unsupportedReason = "Unrecognized milestone definition; completion and requirements are unknown."
        }
        MilestoneType.CaptureAllCapitals -> {
            dto.current = milestone.originalMajorCapitalsOwned(civ)
            if (!hideCount) dto.target = milestone.civsWithPotentialCapitalsToOwn(civ.gameInfo).size
        }
        MilestoneType.AddedSSPartsInCapital -> {
            val remaining = victory.requiredSpaceshipPartsAsCounter.clone()
            remaining.remove(civ.victoryManager.currentsSpaceshipParts)
            dto.target = victory.requiredSpaceshipPartsAsCounter.sumValues()
            dto.current = dto.target!! - remaining.sumValues()
            dto.remainingSpaceshipParts = LinkedHashMap(remaining.entries.sortedBy { it.key }
                .take(VICTORY_DETAIL_LIMIT).associate { it.key to it.value })
            dto.partsTruncated = remaining.size > VICTORY_DETAIL_LIMIT
            dto.status = "unsupported"
            dto.unsupportedReason = "External agents can research and build parts, but no command assembles spaceship parts in the capital."
        }
        MilestoneType.CompletePolicyBranches -> {
            dto.current = civ.policies.completedBranches.size
            dto.target = milestone.params[0].toInt()
        }
        MilestoneType.HaveCountable -> {
            dto.current = milestone.getCountableDone(civ)
            dto.target = milestone.getCountableToDo()
        }
        MilestoneType.ScoreAfterTimeOut -> {
            dto.current = civ.gameInfo.turns
            dto.target = civ.gameInfo.gameParameters.maxTurns
        }
        // Boolean canonical predicates supply useful progress without private world details.
        else -> {
            dto.current = if (completed == true) 1 else 0
            dto.target = 1
        }
    }
    return dto
}

private fun buildDomination(
    game: GameInfo, match: AgentMatchState, civ: Civilization, milestone: Milestone, hideCount: Boolean
): ObservedDomination {
    val visible = observedSight(game, match, civ)
    val records = LinkedHashMap<String, ObservedOriginalCapital>()
    fun add(tile: ObservedTile, knowledge: String) {
        val city = tile.city ?: return
        if (city.isOriginalCapital != true) return
        val founder = city.foundingCivId ?: return
        // Legacy/malformed metadata cannot invent an actor or turn a city-state into a capital target.
        if (game.getCivilizationOrNull(founder)?.isMajorCiv() != true) return
        if (game.getCivilizationOrNull(city.ownerCivId) == null) return
        records[founder] = ObservedOriginalCapital(founder, city.cityId, city.name, tile.x, tile.y,
            city.ownerCivId, knowledge, tile.lastSeenTurn, tile.lastSeenRevision)
    }
    if (match.vision == MatchVision.FOG) {
        for (tile in match.mapKnowledge.tilesByCiv[civ.civID]?.values.orEmpty()) {
            val liveTile = game.tileMap.getIfTileExistsOrNull(tile.x, tile.y) ?: continue
            if (liveTile !in visible) add(tile, "REMEMBERED")
        }
    }
    for (city in game.getCities()) {
        val tile = city.getCenterTile()
        if (tile !in visible && match.vision != MatchVision.MAP) continue
        if (!city.isOriginalCapital) continue
        val observed = buildPermittedTile(game, match, civ.civID, HexCoord(tile.position.x, tile.position.y), visible) ?: continue
        add(observed, if (tile in visible) "CURRENT" else "REVEALED")
    }
    val potentialFounders = milestone.civsWithPotentialCapitalsToOwn(game)
    val knownFounders = game.civilizations.filter { it.isMajorCiv() && (it in potentialFounders || it.civID in records) &&
        (it == civ || civ.knows(it) || it.civID in records || match.vision == MatchVision.FULL) }
    val capitals = knownFounders.map { records[it.civID] ?: ObservedOriginalCapital(it.civID) }
    return ObservedDomination(
        ownedOriginalCapitals = milestone.originalMajorCapitalsOwned(civ),
        requiredOriginalCapitals = if (hideCount) null else milestone.civsWithPotentialCapitalsToOwn(game).size,
        capitals = ArrayList(capitals.take(VICTORY_DETAIL_LIMIT)),
        capitalsTruncated = capitals.size > VICTORY_DETAIL_LIMIT
    )
}
