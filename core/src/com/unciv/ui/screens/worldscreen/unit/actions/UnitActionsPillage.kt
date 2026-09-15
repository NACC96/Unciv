package com.unciv.ui.screens.worldscreen.unit.actions

import com.unciv.logic.agents.PillageCommand

import com.unciv.GUI
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.logic.map.tile.TilePillage
import com.unciv.models.UnitAction
import com.unciv.models.UnitActionType
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.stats.Stat
import com.unciv.models.stats.Stats
import com.unciv.ui.components.extensions.toPercent
import com.unciv.ui.popups.ConfirmPopup
import yairm210.purity.annotations.Readonly
import kotlin.random.Random

object UnitActionsPillage {

    internal fun getPillageActions(unit: MapUnit, tile: Tile): Sequence<UnitAction> {
        val pillageAction = getPillageAction(unit, tile)
            ?: return emptySequence()
        if (pillageAction.action == null || unit.civ.isAIOrAutoPlaying())
            return sequenceOf(pillageAction)
        else return sequenceOf(UnitAction(UnitActionType.Pillage, 65f, pillageAction.title) {
            val pillageText = "Are you sure you want to pillage this [${tile.getImprovementToPillageName()!!}]?"
            ConfirmPopup(
                GUI.getWorldScreen(),
                pillageText,
                "Pillage",
                true
            ) {
                // Agent matches route pillage through the coordinator (§3.2).
                val worldScreen = GUI.getWorldScreen()
                if (worldScreen.routeThroughAgentSession {
                        listOf({ r: Int ->
                            PillageCommand(unit.civ.civID, "ui-${System.nanoTime()}", r, unit.id)
                        })
                    }) return@ConfirmPopup
                (pillageAction.action)()
                GUI.setUpdateWorldOnNextRender()
            }.open()
        })
    }

    internal fun getPillageAction(unit: MapUnit, tile: Tile): UnitAction? {
        val improvementName = unit.currentTile.getImprovementToPillageName()
        if (unit.isCivilian() || improvementName == null || tile.getOwner() == unit.civ) return null
        return UnitAction(
            UnitActionType.Pillage, 65f,
            title = "${UnitActionType.Pillage} [$improvementName]",
            action = {
                val pillagedImprovement = unit.currentTile.getImprovementToPillageName()!!  // can this differ from improvementName due to later execution???
                val pillagingImprovement = unit.currentTile.canPillageTileImprovement()
                val pillageText = "An enemy [${unit.baseUnit.name}] has pillaged our [$pillagedImprovement]"
                val icon = "ImprovementIcons/$pillagedImprovement"
                tile.getOwner()?.addNotification(
                    pillageText,
                    tile.position,
                    NotificationCategory.War,
                    icon,
                    NotificationIcon.War,
                    unit.baseUnit.name
                )

                pillageLooting(tile, unit)
                tile.setPillaged()  // Also triggers reassignPopulation
                if (tile.resource != null) tile.getOwner()?.cache?.updateCivResources()    // this might take away a resource

                val freePillage = unit.hasUnique(UniqueType.NoMovementToPillage, checkCivInfoUniques = true)
                if (!freePillage) unit.useMovementPoints(1f)

                if (pillagingImprovement) { // only Improvements heal HP
                    var healAmount = 25f
                    for (unique in unit.getMatchingUniques(UniqueType.PercentHealthFromPillaging, checkCivInfoUniques = true)) {
                        healAmount *= unique.params[0].toPercent()
                    }
                    unit.healBy(healAmount.toInt())
                }
                
                if (tile.getImprovementToPillage()?.hasUnique(UniqueType.DestroyedWhenPillaged) == true) {
                    tile.removeImprovement()
                }
                    
            }.takeIf { unit.hasMovement() && canPillage(unit, tile) }
        )
    }

    private fun pillageLooting(tile: Tile, unit: MapUnit) =
        TilePillage.pillageLooting(tile, unit)

    // Public - used in UnitAutomation
    @Readonly
    fun canPillage(unit: MapUnit, tile: Tile): Boolean {
        if (unit.isTransported) return false
        if (!tile.canPillageTile()) return false
        if (unit.hasUnique(UniqueType.CannotPillage, checkCivInfoUniques = true)) return false
        val tileOwner = tile.getOwner()
        // Can't pillage friendly tiles, just like you can't attack them - it's an 'act of war' thing
        return tileOwner == null || unit.civ.isAtWarWith(tileOwner)
    }
}
