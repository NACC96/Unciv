package com.unciv.view

import com.badlogic.gdx.graphics.Color
import com.unciv.logic.GameInfo
import com.unciv.logic.battle.CityCombatant
import com.unciv.logic.battle.ICombatant
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.city.City
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.logic.presentation.*
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.ruleset.unique.GameContext

/** Event-time projection only. No engine object or mutable GameView survives a call. */
object VisualEventProjector : VisualProjection {
    private fun gameView(game: GameInfo, view: VisualViewKey): GameView? {
        val viewer = game.civilizations.firstOrNull { it.civID == view.viewerCivId } ?: return null
        // A real spectator is unrestricted; spectatorMode alone never grants extra map knowledge.
        if (viewer.isSpectator() && !view.spectatorMode) return null
        return GameView(game, viewer, view.spectatorMode, view.revealMap, view.revealUnits)
    }

    private fun visible(view: GameView, tile: Tile): Boolean {
        val tileView = view.getTile(tile)
        return tileView.isForceVisible() || view.civView.isSpectator() || view.civView.canSeeTile(tileView)
    }

    private fun Tile.positionValue() = VisualPosition(position.x, position.y)
    private fun colors(civ: ForeignCivView) = VisualOwnerColors(
        Color.rgba8888(civ.getInnerColor()), Color.rgba8888(civ.getOuterColor()))

    override fun position(game: GameInfo, view: VisualViewKey, tile: Tile): VisualPosition? {
        val current = gameView(game, view) ?: return null
        return tile.positionValue().takeIf { visible(current, tile) }
    }

    override fun unit(game: GameInfo, view: VisualViewKey, unit: MapUnit, atTile: Tile): VisualUnitSample? {
        val current = gameView(game, view) ?: return null
        val viewer = current.civView.getCiv()
        if (!visible(current, atTile)) return null
        if (unit.civ !== viewer && !viewer.isSpectator() && !current.revealUnits) {
            if (atTile === unit.currentTile) {
                if (!unit.isVisibleTo(viewer)) return null
            } else {
                // Transit hooks can precede currentTile assignment. Check detection at the actual node,
                // never reuse visibility at the unit's final position across an unseen route gap.
                val transitState = GameContext(civInfo = unit.civ, unit = unit, tile = atTile)
                val invisible = unit.hasUnique(UniqueType.Invisible, transitState) ||
                    unit.hasUnique(UniqueType.InvisibleToNonAdjacent, transitState) && atTile.getTilesInDistance(1).none {
                        it.getUnits().any { other -> other.civ === viewer }
                    }
                if (invisible && viewer.viewableInvisibleUnitsTiles[atTile]?.any { unit.matchesFilter(it) } != true)
                    return null
            }
        }
        val permitted = current.getForeignMapUnitView(unit)
        val owner = permitted.civ()
        return VisualUnitSample(VisualUnitDescriptor(permitted.id, permitted.name, owner.civID,
            colors(owner), permitted.unitHealth), atTile.positionValue(), unit.isDestroyed)
    }

    override fun combatant(game: GameInfo, view: VisualViewKey, combatant: ICombatant): VisualCombatantSample? =
        when (combatant) {
            is MapUnitCombatant -> unit(game, view, combatant.unit, combatant.unit.currentTile)?.let {
                VisualCombatantSample(it.unit, it.position, it.removed)
            }
            is CityCombatant -> city(game, view, combatant.city)?.let {
                VisualCombatantSample(it.city, it.position, it.removed)
            }
            else -> null
        }

    override fun city(game: GameInfo, view: VisualViewKey, city: City): VisualCitySample? {
        val current = gameView(game, view) ?: return null
        val tile = city.getCenterTile()
        // Exploration memories do not authorize witnessing a capture, founding or destruction now.
        if (!visible(current, tile)) return null
        val permitted = current.getForeignCityView(city)
        val owner = permitted.owningCiv()
        return VisualCitySample(VisualCityDescriptor(city.id, "City", owner.civID, colors(owner),
            permitted.getHealth(), permitted.name), tile.positionValue(), city.civ.cities.none { it.id == city.id })
    }

    override fun improvement(game: GameInfo, view: VisualViewKey, tile: Tile): VisualImprovementSample? {
        val current = gameView(game, view) ?: return null
        if (!visible(current, tile)) return null
        val permitted = current.getTile(tile)
        return VisualImprovementSample(tile.positionValue(), permitted.getShownImprovement(),
            permitted.roadStatus.name, permitted.improvementIsPillaged, permitted.roadIsPillaged)
    }
}
