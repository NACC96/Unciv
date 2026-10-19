package com.unciv.logic.agents

import com.unciv.Constants
import com.unciv.logic.GameInfo
import com.unciv.logic.MultiFilter
import com.unciv.logic.civilization.managers.ImprovementFunctions
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.ImprovementBuildingProblem
import com.unciv.logic.map.tile.RoadStatus
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.tile.TileImprovement
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType

class WorkerImprovementOption(
    var name: String = "",
    var kind: String = "IMPROVEMENT",
    var turnsToBuild: Int = 0,
    var replacesImprovement: String? = null,
    var connectsResource: Boolean = false,
    var yieldDelta: WorkerYieldDelta? = null,
    /** Built at once and uses up the unit (Work Boats on a water resource). */
    var consumesUnit: Boolean = false
)

/** Null when no private, sanitized actor/own-city yield context is available. */
class WorkerYieldDelta(
    var context: String = "actor-own-city-tile",
    var yields: Map<String, Float> = emptyMap()
)

class WorkInProgress(var name: String = "", var turnsRemaining: Int = 0)
class RepairOption(var turnsToRepair: Int = 0)
class WorkerOptions(
    var improvements: ArrayList<WorkerImprovementOption> = ArrayList(),
    var workInProgress: WorkInProgress? = null,
    var repair: RepairOption? = null,
    var canCancelWork: Boolean = false
)

/** [tile] may be a disposable projection; all conditional checks bind to that tile. */
data class WorkerBuildContext(
    val game: GameInfo,
    val actorCivId: String,
    val unit: MapUnit,
    val tile: Tile = unit.currentTile
) {
    val state get() = GameContext(civInfo = unit.civ, unit = unit, tile = tile, gameInfo = game)
}

private fun illegalWorker(message: String): Nothing =
    throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, message)

private fun requireWorker(context: WorkerBuildContext, requireMovement: Boolean = true) {
    val unit = context.unit
    if (unit.civ.civID != context.actorCivId || unit.civ.gameInfo !== context.game)
        illegalWorker("Unit is not an actor worker")
    val blocker = when {
        unit.civ.isBarbarian -> "barbarian units do not build"
        !unit.getMatchingUniques(UniqueType.BuildImprovements, context.state).any() ->
            if (createsWaterImprovements(context))
                "${unit.name} only creates improvements on water resources, using itself up: " +
                    "on the resource tile, unit.build the improvement legal_actions lists"
            else "${unit.name} has no improvement-building ability here"
        unit.isEmbarked() -> "${unit.name} is embarked; it must stand on land"
        requireMovement && !unit.hasMovement() -> "${unit.name} has no movement left this turn"
        else -> null
    }
    if (blocker != null) illegalWorker("Unit cannot work on improvements now: $blocker")
    if (context.tile.isCityCenter() || context.tile.isMarkedForCreatesOneImprovement())
        illegalWorker("Worker cannot modify this tile")
}

/** Mirrors the engine builder filter with the supplied tile, avoiding cache.state on projections.
 * MapUnit.canBuildImprovement has an in-progress-repair shortcut; it must not authorize arbitrary builds.
 */
private fun builderMatches(context: WorkerBuildContext, improvement: TileImprovement): Boolean =
    context.unit.getMatchingUniques(UniqueType.BuildImprovements, context.state).any { unique ->
        MultiFilter.multiFilter(unique.params[0], {
            improvement.matchesFilter(it, context.state) || context.tile.matchesTerrainFilter(it, context.unit.civ)
        })
    }

private fun createsWaterImprovements(context: WorkerBuildContext): Boolean =
    context.unit.getMatchingUniques(UniqueType.CreateWaterImprovements, context.state).any()

/** unit.build of [improvement] is the consuming water creation: the unit cannot build it as a worker. */
fun isWaterImprovementCreation(context: WorkerBuildContext, improvement: TileImprovement): Boolean =
    createsWaterImprovements(context) && !builderMatches(context, improvement)

/**
 * The in-game "Create [improvement]" action of Work Boats (UnitActionsFromUniques.getWaterImprovementAction):
 * on a water tile, the first improvement of its resource that can be built now, built at once by using up the unit.
 */
fun requireWaterImprovementAllowed(context: WorkerBuildContext, improvement: TileImprovement) {
    val unit = context.unit
    val tile = context.tile
    if (unit.civ.civID != context.actorCivId || unit.civ.gameInfo !== context.game)
        illegalWorker("Unit is not an actor worker")
    if (!unit.hasMovement()) illegalWorker("Unit cannot create improvements now: ${unit.name} has no movement left this turn")
    val resource = tile.tileResource?.takeIf { tile.isWater && unit.civ.canSeeResource(it) }
        ?: illegalWorker("Unit cannot create improvements here: ${unit.name} must stand on a water tile with a resource")
    if (!resource.isImprovedBy(improvement.name)) illegalWorker("${improvement.name} does not improve ${resource.name}; " +
        "it is improved by ${resource.getImprovements().joinToString(" or ")}")
    if (tile.improvement == improvement.name && !tile.improvementIsPillaged)
        illegalWorker("This tile already has ${improvement.name}")
    val creatable = resource.getImprovingImprovement(tile, context.state)
    if (creatable != null && creatable.name != improvement.name)
        illegalWorker("${unit.name} can create only ${creatable.name} on this ${resource.name}")
    val problems = tile.improvementFunctions.getImprovementBuildingProblems(improvement, context.state)
    if (problems.any()) illegalWorker("Improvement cannot be built on this tile now: ${improvement.name} " +
        problems.distinct().joinToString("; ") { describeBuildingProblem(it, improvement, context) })
}

/** The water creation unit.build would perform here, for legal_actions. */
private fun waterImprovementOption(context: WorkerBuildContext): WorkerImprovementOption? {
    if (!createsWaterImprovements(context)) return null
    val tile = context.tile
    val resource = tile.tileResource?.takeIf { tile.isWater && context.unit.civ.canSeeResource(it) } ?: return null
    val improvement = resource.getImprovingImprovement(tile, context.state)
        ?.takeIf { isWaterImprovementCreation(context, it) } ?: return null
    try { requireWaterImprovementAllowed(context, improvement) } catch (_: AgentCommandException) { return null }
    return WorkerImprovementOption(improvement.name, turnsToBuild = 0, replacesImprovement = tile.improvement,
        connectsResource = true, consumesUnit = true)
}

/** @param explain spell out why the build is refused; option discovery skips that work. */
fun requireWorkerBuildAllowed(context: WorkerBuildContext, improvement: TileImprovement, explain: Boolean = true) {
    requireWorker(context)
    val tile = context.tile
    if (tile.improvementInProgress == improvement.name)
        throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "ALREADY_IN_PROGRESS: This improvement is already in progress")
    if (tile.hasImprovementInProgress()) illegalWorker("Stop existing work explicitly before switching improvements")
    if (improvement.turnsToBuild < 0 || improvement.name == Constants.cancelImprovementOrder
        || improvement.name == Constants.repair || improvement.isGreatImprovement())
        illegalWorker("This is not a worker build; use its explicit action")
    if (!builderMatches(context, improvement)) {
        if (!explain) illegalWorker("Unit cannot build this improvement")
        val filters = context.unit.getMatchingUniques(UniqueType.BuildImprovements, context.state)
            .joinToString(" or ") { "[${it.params[0]}]" }
        illegalWorker("Unit cannot build this improvement: ${context.unit.name} builds only $filters improvements")
    }
    val problems = tile.improvementFunctions.getImprovementBuildingProblems(improvement, context.state)
    if (problems.none()) return
    if (!explain) illegalWorker("Improvement cannot be built on this tile now")
    illegalWorker("Improvement cannot be built on this tile now: ${improvement.name} " +
        problems.distinct().joinToString("; ") { describeBuildingProblem(it, improvement, context) })
}

/** Plain-language [ImprovementBuildingProblem]s, as checked by the engine for this civilization and tile. */
private fun describeBuildingProblem(problem: ImprovementBuildingProblem, improvement: TileImprovement, context: WorkerBuildContext) =
    when (problem) {
        ImprovementBuildingProblem.Replaced -> "is replaced by your civilization's unique improvement"
        ImprovementBuildingProblem.WrongCiv -> "is unique to another civilization"
        ImprovementBuildingProblem.MissingTech -> "requires ${improvement.techRequired}"
        ImprovementBuildingProblem.Unbuildable -> "cannot be built by workers"
        ImprovementBuildingProblem.ConditionallyUnbuildable -> "is unavailable under current conditions"
        ImprovementBuildingProblem.UnmetConditional -> "has unmet conditions"
        ImprovementBuildingProblem.NotJustOutsideBorders -> "must be just outside your borders"
        ImprovementBuildingProblem.OutsideBorders ->
            if (improvement.hasUnique(UniqueType.CanBuildJustOutsideBorders, context.state))
                "needs a tile inside or adjacent to your territory"
            else "needs a tile inside your territory (this tile " +
                (context.tile.getOwner()?.let { "belongs to ${it.civID}" } ?: "is unowned") + ")"
        ImprovementBuildingProblem.Obsolete -> "is obsolete"
        ImprovementBuildingProblem.MissingResources -> "needs resources you lack"
        ImprovementBuildingProblem.Other -> "is not allowed on this tile's terrain, feature or resource"
    }

/** Return the actual engine repair time; guards are shared by discovery and mutation. */
fun requireWorkerRepairAllowed(context: WorkerBuildContext): Int {
    requireWorker(context)
    val tile = context.tile
    if (tile.improvementInProgress == Constants.repair)
        throw AgentCommandException(AgentCommandError.ILLEGAL_ACTION, "ALREADY_IN_PROGRESS: Repair is already in progress")
    if (tile.hasImprovementInProgress()) illegalWorker("Stop existing work explicitly before repairing")
    val repair = context.game.ruleset.tileImprovements[Constants.repair]
        ?: illegalWorker("Repair is unavailable in this ruleset")
    val improvement = tile.getImprovementToRepair() ?: illegalWorker("Nothing to repair here")
    if (!builderMatches(context, improvement) || tile.isEnemyTerritory(context.unit.civ))
        illegalWorker("Unit cannot repair this improvement")
    if (ImprovementFunctions.getImprovementBuildingProblems(improvement, context.state, tile)
            .any { it == ImprovementBuildingProblem.OutsideBorders })
        illegalWorker("Cannot repair outside authorized borders")
    return minOf(
        repair.getTurnsToBuild(context.unit.civ, context.unit),
        improvement.getTurnsToBuild(context.unit.civ, context.unit))
}

fun requireWorkerCancelAllowed(context: WorkerBuildContext) {
    requireWorker(context)
    val tile = context.tile
    val active = tile.getTileImprovementInProgress() ?: illegalWorker("No cancellable work here")
    if (tile.turnsToImprovement <= 0 || tile.improvementQueue.any { it.turnsToImprovement < 0 })
        illegalWorker("Work is a one-time construction marker")
    if (tile.getOwner()?.let { it != context.unit.civ } == true || tile.isEnemyTerritory(context.unit.civ))
        illegalWorker("Cannot cancel foreign work")
    val authorization = if (active.name == Constants.repair) tile.getImprovementToRepair() else active
    if (authorization == null || !builderMatches(context, authorization))
        illegalWorker("Unit cannot cancel this work")
    if (ImprovementFunctions.getImprovementBuildingProblems(authorization, context.state, tile)
            .any { it == ImprovementBuildingProblem.OutsideBorders })
        illegalWorker("Cannot cancel work outside authorized borders")
}

fun buildWorkerOptions(game: GameInfo, actorCivId: String, unit: MapUnit): WorkerOptions {
    val context = WorkerBuildContext(game, actorCivId, unit)
    val result = WorkerOptions()
    waterImprovementOption(context)?.let { result.improvements.add(it) }
    try { requireWorker(context, requireMovement = false) } catch (_: AgentCommandException) { return result }
    val tile = context.tile
    result.workInProgress = tile.improvementInProgress?.let { WorkInProgress(it, tile.turnsToImprovement) }
    result.canCancelWork = try { requireWorkerCancelAllowed(context); true } catch (_: AgentCommandException) { false }
    result.repair = try { RepairOption(requireWorkerRepairAllowed(context)) } catch (_: AgentCommandException) { null }
    for (improvement in game.ruleset.tileImprovements.values.sortedBy { it.name }) {
        try { requireWorkerBuildAllowed(context, improvement, explain = false) } catch (_: AgentCommandException) { continue }
        result.improvements.add(WorkerImprovementOption(
            name = improvement.name,
            kind = when {
                improvement.isRoad() -> "ROAD"
                improvement.name.startsWith(Constants.remove) ->
                    if (improvement.name.removePrefix(Constants.remove) in tile.terrainFeatures) "REMOVE_FEATURE" else "REMOVE_IMPROVEMENT"
                else -> "IMPROVEMENT"
            },
            turnsToBuild = if (game.gameParameters.godMode) 1 else improvement.getTurnsToBuild(unit.civ, unit),
            replacesImprovement = tile.improvement?.takeIf {
                !improvement.isRoad() && !improvement.name.startsWith(Constants.remove)
            },
            connectsResource = tile.tileResource?.let {
                unit.civ.canSeeResource(it) && it.isImprovedBy(improvement.name)
            } == true
        ))
    }
    return result
}

/** Atomic human queue validation. This does not advertise later entries as legal-now builds.
 * Costs are checked cumulatively without spending any resources. No engine completion methods run.
 */
fun validateWorkerImprovementQueue(context: WorkerBuildContext, names: List<String>): List<TileImprovement> {
    if (names.size != 2 || !names[0].startsWith(Constants.remove)
        || names[0].removePrefix(Constants.remove) !in context.tile.terrainFeatures
        || names[1].startsWith(Constants.remove))
        throw AgentCommandException(AgentCommandError.INVALID_ARGUMENT, "Worker queue requires feature removal followed by a build")
    val projection = context.tile.clone(addUnits = false)
    projection.stateThisTile = context.copy(tile = projection).state
    projection.setTerrainTransients()
    val projectedContext = context.copy(tile = projection)
    val costs = HashMap<String, Int>()
    val improvements = ArrayList<TileImprovement>()
    for (name in names) {
        val improvement = context.game.ruleset.tileImprovements[name]
            ?: throw AgentCommandException(AgentCommandError.INVALID_ARGUMENT, "Unknown improvement: $name")
        requireWorkerBuildAllowed(projectedContext, improvement)
        for ((resource, amount) in improvement.getStockpiledResourceRequirements(projectedContext.state)) {
            val total = (costs[resource] ?: 0) + amount
            if (total > context.unit.civ.getResourceAmount(resource)) illegalWorker("Queued work requires unavailable resources")
            costs[resource] = total
        }
        improvements.add(improvement)
        projectWorkerImprovement(projection, improvement)
    }
    return improvements
}

/** Field-only completion on a private tile. In particular, no sightings, chop yields, road ownership,
 * notifications, stockpile writes, marker removal, or triggered uniques can reach live state.
 */
private fun projectWorkerImprovement(tile: Tile, improvement: TileImprovement) {
    when {
        RoadStatus.entries.any { it.removeAction == improvement.name } -> {
            tile.roadStatus = RoadStatus.None
            tile.roadIsPillaged = false
        }
        improvement.isRoad() -> {
            tile.roadStatus = RoadStatus.valueOf(improvement.name)
            tile.roadIsPillaged = false
        }
        improvement.name.startsWith(Constants.remove) -> {
            val removed = improvement.name.removePrefix(Constants.remove)
            val previous = tile.tileImprovement
            if (tile.improvement == removed || previous != null
                && removed in previous.terrainsCanBeBuiltOn
                && tile.baseTerrain !in previous.terrainsCanBeBuiltOn) {
                tile.setImprovementBasic(null)
                tile.improvementIsPillaged = false
            }
            tile.setTerrainFeatures(tile.terrainFeatures.filterNot { it == removed })
        }
        else -> {
            tile.setImprovementBasic(improvement)
            tile.improvementIsPillaged = false
            if (improvement.hasUnique(UniqueType.RemovesFeaturesIfBuilt))
                tile.setTerrainFeatures(tile.terrainFeatures.filterNot { feature ->
                    Constants.remove + feature in tile.ruleset.tileImprovements
                        && !improvement.isAllowedOnFeature(tile.ruleset.terrains[feature]!!)
                })
        }
    }
}

/** Called only on the coordinator's disposable candidate after revision/ownership checks.
 * Validation and price calculation finish before the first engine assignment, so bad later work
 * cannot partially clear or charge the candidate. The engine charges the first entry; later entries
 * need explicit charges because Tile.queueImprovement only computes their build time.
 */
fun startValidatedWorkerQueue(context: WorkerBuildContext, names: List<String>): Int {
    val improvements = validateWorkerImprovementQueue(context, names)
    val projection = context.tile.clone(addUnits = false)
    projection.stateThisTile = context.copy(tile = projection).state
    projection.setTerrainTransients()
    val laterCosts = ArrayList<Map<String, Int>>()
    for (improvement in improvements) {
        val costs = LinkedHashMap<String, Int>()
        for ((resource, amount) in improvement.getStockpiledResourceRequirements(context.copy(tile = projection).state))
            costs[resource] = amount
        laterCosts.add(costs)
        projectWorkerImprovement(projection, improvement)
    }
    context.tile.startWorkingOnImprovement(improvements.first(), context.unit.civ, context.unit)
    for (index in 1 until improvements.size) {
        for ((resourceName, amount) in laterCosts[index]) {
            val resource = context.game.ruleset.tileResources[resourceName] ?: continue
            context.unit.civ.gainStockpiledResource(resource, -amount)
        }
        context.tile.queueImprovement(improvements[index], context.unit.civ, context.unit)
    }
    context.unit.action = null
    return context.tile.turnsToImprovement
}
