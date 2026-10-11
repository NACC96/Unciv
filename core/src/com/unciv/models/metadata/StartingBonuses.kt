package com.unciv.models.metadata

import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.tile.ResourceType
import yairm210.purity.annotations.Readonly
import yairm210.purity.annotations.LocalState

/** Extra setup grants, applied once during game creation. Deposits count tiles, not stockpiles. */
class StartingBonuses {
    var gold = 0
    var culture = 0
    var units = LinkedHashMap<String, Int>()
    var deposits = LinkedHashMap<String, Int>()

    @Readonly fun copy(): StartingBonuses {
        @LocalState val result = StartingBonuses()
        result.gold = gold; result.culture = culture
        result.units = LinkedHashMap(units); result.deposits = LinkedHashMap(deposits)
        return result
    }

    fun validate(ruleset: Ruleset, nation: String): List<String> = buildList {
        if (gold < 0 || culture < 0) add("Starting gold and culture must be nonnegative.")
        for ((name, count) in units) {
            val unit = ruleset.units[name]
            if (count < 0 || count > 50) add("Extra unit counts must be between 0 and 50.")
            if (unit == null || (unit.uniqueTo != null && unit.uniqueTo != nation)) add("$name is not an available starting unit for $nation.")
        }
        for ((name, count) in deposits) {
            if (count < 0 || count > 10) add("Extra deposit counts must be between 0 and 10.")
            if (ruleset.tileResources[name]?.resourceType != ResourceType.Strategic) add("$name is not a strategic resource in this ruleset.")
        }
    }

    fun summary(): String = buildList {
        if (gold > 0) add("+$gold gold")
        if (culture > 0) add("+$culture culture")
        units.filterValues { it > 0 }.forEach { (name, count) -> add("+$count $name") }
        deposits.filterValues { it > 0 }.forEach { (name, count) -> add("+$count $name deposits") }
    }.joinToString(" · ")
}
