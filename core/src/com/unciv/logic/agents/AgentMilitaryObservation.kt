package com.unciv.logic.agents

import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType

/** Capability counts overlap (for example, siege is also ranged). They are not attack predictions. */
class ObservedMilitaryForces(
    /** VISIBLE_ONLY counts sightings, never the enemy's whole army. */
    var coverage: String = "VISIBLE_ONLY",
    var knownMilitaryUnits: Int = 0,
    var totalMilitaryUnits: Int? = null,
    var conventionalUnits: Int = 0,
    var nuclearWeapons: Int = 0,
    var captureCapableUnits: Int = 0,
    var rangedUnits: Int = 0,
    var siegeUnits: Int = 0,
    var aircraft: Int = 0,
    var interceptors: Int = 0,
    var damagedUnits: Int = 0,
    /** Own units only: sum of non-nuclear unit force, without treasury or naval ranking multipliers. */
    var conventionalUnitForce: Int? = null
)

class ObservedRivalForces(var civId: String = "", var forces: ObservedMilitaryForces = ObservedMilitaryForces())

class ObservedMilitaryContext(
    var own: ObservedMilitaryForces = ObservedMilitaryForces(),
    var rivals: ArrayList<ObservedRivalForces> = ArrayList()
)

fun buildMilitaryObservation(
    game: GameInfo, match: AgentMatchState, civ: Civilization, rivals: List<ObservedRival>
): ObservedMilitaryContext {
    val own = describeForces(civ.units.getCivUnits().filterNot { it.isDestroyed }.toList(), complete = true, own = true)
    val others = rivals.mapNotNullTo(ArrayList()) { rival ->
        val other = game.getCivilizationOrNull(rival.civId) ?: return@mapNotNullTo null
        val units = other.units.getCivUnits().filter {
            !it.isDestroyed && (match.vision == MatchVision.FULL || it.isVisibleTo(civ))
        }.toList()
        ObservedRivalForces(other.civID, describeForces(units, complete = match.vision == MatchVision.FULL, own = false))
    }
    return ObservedMilitaryContext(own, others)
}

private fun describeForces(units: List<MapUnit>, complete: Boolean, own: Boolean): ObservedMilitaryForces {
    val military = units.filter { it.isMilitary() }
    // Foreign composition uses only public unit definitions and observed health. Promotions,
    // current movement/orders and private civilization modifiers do not cross this boundary.
    fun has(unit: MapUnit, unique: UniqueType): Boolean = if (own) unit.hasUnique(unique)
        else unit.baseUnit.hasUnique(unique, GameContext.IgnoreConditionals)
    val conventional = military.filterNot { has(it, UniqueType.NuclearWeapon) }
    return ObservedMilitaryForces(
        coverage = if (complete) "COMPLETE" else "VISIBLE_ONLY",
        knownMilitaryUnits = military.size,
        totalMilitaryUnits = military.size.takeIf { complete },
        conventionalUnits = conventional.size,
        nuclearWeapons = military.size - conventional.size,
        captureCapableUnits = conventional.count {
            it.baseUnit.isMelee() && !has(it, UniqueType.CannotCaptureCities) && !has(it, UniqueType.CanDestroyCities)
        },
        rangedUnits = conventional.count { it.baseUnit.isRanged() },
        siegeUnits = conventional.count { it.baseUnit.unitType == "Siege" },
        aircraft = conventional.count { it.baseUnit.isAirUnit() },
        interceptors = conventional.count { has(it, UniqueType.ChanceInterceptAirAttacks) },
        damagedUnits = military.count { it.health < 100 },
        conventionalUnitForce = if (own) conventional.sumOf { it.getForceEvaluation() } else null
    )
}
