package com.unciv.logic

import com.unciv.Constants
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.tile.Tile
import com.unciv.models.metadata.StartingBonuses
import com.unciv.models.ruleset.tile.TileResource
import com.unciv.models.ruleset.unique.GameContext

/** New-game grants, plus delivery of saved aircraft grants once a city has room. */
object StartingBonusApplicator {
    fun apply(civ: Civilization, start: Tile, bonuses: StartingBonuses, starts: Collection<Tile>) {
        val errors = bonuses.validate(civ.gameInfo.ruleset, civ.civID)
        if (errors.isNotEmpty()) throw UncivShowableException(errors.joinToString("\n"))
        val reserved = HashSet<Tile>()
        val placements = ArrayList<Pair<Tile, Tile>>()
        for ((name, count) in bonuses.deposits) {
            val resource = civ.gameInfo.ruleset.tileResources[name]!!
            val candidates = start.getTilesInDistance(4).filter { tile ->
                tile !in starts && tile !in reserved && tile.resource == null && !tile.isCityCenter() &&
                    tile.improvement == null && !tile.isNaturalWonder() && !tile.isImpassible() &&
                    (tile.getOwner() == null || tile.getOwner() == civ) &&
                    starts.all { other -> other == start || tile.aerialDistanceTo(start) < tile.aerialDistanceTo(other) }
            }.sortedWith(compareBy<Tile> { !resource.generatesNaturallyOn(it) }
                .thenBy { it.aerialDistanceTo(start) }.thenBy { it.position.x }.thenBy { it.position.y })
                .mapNotNull { tile -> prepareDeposit(civ, tile, resource)?.let { tile to it } }
                .take(count).toList()
            if (candidates.size != count) throw UncivShowableException("Cannot place $count extra $name deposits near ${civ.civID}'s start. Choose fewer deposits or a different map.")
            candidates.forEach { reserved.add(it.first); placements.add(it) }
        }
        // Plan on clones first, so an impossible request never leaves a partly modified map.
        placements.forEach { (tile, prepared) ->
            tile.setTerrainFeatures(prepared.terrainFeatures)
            tile.setTileResource(prepared.tileResource!!, majorDeposit = true)
        }
        for ((name, count) in bonuses.units) {
            if (civ.gameInfo.ruleset.units[name]!!.isAirUnit()) {
                if (count > 0) civ.pendingStartingUnits[name] = (civ.pendingStartingUnits[name] ?: 0) + count
                continue
            }
            repeat(count) {
                if (civ.units.placeUnitNearTile(start.position, name) == null)
                    throw UncivShowableException("There is no room for all extra $name units near ${civ.civID}'s start. Choose fewer units or a different map.")
            }
        }
        civ.addGold(bonuses.gold)
        civ.policies.addCulture(bonuses.culture)
        deliverPendingUnits(civ)
    }

    /** Prefer existing terrain, and clear removable features only if extraction needs it. */
    private fun prepareDeposit(civ: Civilization, tile: Tile, resource: TileResource): Tile? {
        val ruleset = civ.gameInfo.ruleset
        val domain = tile.getBaseTerrain().type
        if (resource.terrainsCanBeFoundOn.none { ruleset.terrains[it]?.displayAs(domain, ruleset) == true }) return null
        val prepared = tile.clone(addUnits = false).apply {
            setTerrainFeatures(terrainFeatures)
            setTileResource(resource, majorDeposit = true)
        }
        val improvements = resource.getImprovements().mapNotNull { ruleset.tileImprovements[it] }
            .filter { it.uniqueTo == null || it.uniqueTo == civ.civID }
        fun canExtract(): Boolean = improvements.any {
            prepared.improvementFunctions.canImprovementBeBuiltHere(it, resourceIsVisible = true,
                gameContext = GameContext(civInfo = civ, tile = prepared))
        }
        if (canExtract()) return prepared
        val removableFeatures = ruleset.tileRemovals.map { it.name.removePrefix(Constants.remove) }.toSet()
        prepared.setTerrainFeatures(tile.terrainFeatures.filterNot { it in removableFeatures })
        return prepared.takeIf { canExtract() }
    }

    /** Respect normal air-base capacity; only successful deliveries consume the saved grant. */
    fun deliverPendingUnits(civ: Civilization) {
        if (civ.pendingStartingUnits.isEmpty() || civ.cities.isEmpty()) return
        for ((name, count) in civ.pendingStartingUnits.toMap()) {
            val baseUnit = civ.gameInfo.ruleset.units[name] ?: continue
            var remaining = count
            for (city in civ.cities) {
                val tile = city.getCenterTile()
                while (remaining > 0 && tile.airUnits.count { !it.isTransported } < city.getMaxAirUnits()) {
                    if (civ.units.placeUnitNearTile(tile.position, baseUnit) == null) break
                    remaining--
                    if (remaining == 0) civ.pendingStartingUnits.remove(name)
                    else civ.pendingStartingUnits[name] = remaining
                }
                if (remaining == 0) break
            }
        }
    }
}
