package com.unciv.logic.map.tile

import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.stats.Stat
import com.unciv.models.stats.Stats
import kotlin.random.Random

/**
 * Pillage loot mechanics, extracted logic-pure from
 * `UnitActionsPillage.pillageLooting` (same formula, same notifications;
 * the UI keeps a delegating wrapper for its ConfirmPopup flow).
 * Coordinator-ordered pillaging calls this directly so game outcomes never
 * depend on desktop dialogs.
 */
object TilePillage {
    fun pillageLooting(tile: Tile, unit: MapUnit) {
        val closestCity = unit.civ.cities.minByOrNull { it.getCenterTile().aerialDistanceTo(tile) }
        val improvement = tile.getImprovementToPillage()!!

        var pillageYield = Stats()
        val stateForConditionals = unit.cache.state
        val random = Random(unit.civ.gameInfo.turns * unit.getTile().position.hashCode().toLong())
        for (unique in improvement.getMatchingUniques(UniqueType.PillageYieldRandom, stateForConditionals)) {
            for ((stat, value) in unique.stats) {
                var yieldsToAdd = Stats()
                yieldsToAdd.add(stat, (random.nextInt((value + 1).toInt()) + random.nextInt((value + 1).toInt()).toFloat()))
                if (unique.isModifiedByGameSpeed())
                    yieldsToAdd *= unit.civ.gameInfo.speed.modifier
                if (unique.isModifiedByGameProgress())
                    yieldsToAdd *= unique.getGameProgressModifier(unit.civ)
                pillageYield.add(yieldsToAdd)
            }
        }
        for (unique in improvement.getMatchingUniques(UniqueType.PillageYieldFixed, stateForConditionals)) {
            var yieldsToAdd = unique.stats
            if (unique.isModifiedByGameSpeed())
                yieldsToAdd *= unit.civ.gameInfo.speed.modifier
            if (unique.isModifiedByGameProgress())
                yieldsToAdd *= unique.getGameProgressModifier(unit.civ)
            pillageYield.add(yieldsToAdd)
        }

        for (unique in unit.getMatchingUniques(UniqueType.PercentYieldFromPillaging, checkCivInfoUniques = true)) {
            pillageYield *= 1 + unique.params[0].toFloat() / 100f
        }

        if (pillageYield.isEmpty()) return

        val globalPillageYield = Stats()
        val toCityPillageYield = Stats()
        for ((stat, value) in pillageYield) {
            if (stat in Stat.statsWithCivWideField) {
                unit.civ.addStat(stat, value.toInt())
                globalPillageYield[stat] += value
            } else if (closestCity != null) {
                closestCity.addStat(stat, value.toInt())
                toCityPillageYield[stat] += value
            }
        }

        fun Stats.notify(suffix: String) {
            if (isEmpty()) return
            val text = "We have looted [${toStringWithoutIcons()}] from a [${improvement.name}]" + suffix
            unit.civ.addNotification(text, tile.position, NotificationCategory.War, "ImprovementIcons/${improvement.name}", NotificationIcon.War)
        }
        toCityPillageYield.notify(" which has been sent to [${closestCity?.name}]")
        globalPillageYield.notify("")
    }
}
