package com.unciv.view

import com.unciv.logic.presentation.VisualViewKey
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class GameViewRevealTest {
    private class Fixture {
        val game = TestGame().apply { makeHexagonalMap(3) }
        val player = game.addCiv(game.ruleset.nations.getValue("Rome"), isPlayer = true)
        val enemy = game.addCiv(game.ruleset.nations.getValue("Greece"))
        val hiddenTile = game.getTile(2, 0)
        val neighbor = game.getTile(2, 1)
        val hiddenUnit = game.addUnit("Warrior", enemy, hiddenTile)

        init {
            game.addCity(player, game.getTile(-2, 0))
            game.addUnit("Warrior", player, game.getTile(-2, 0))
            hiddenTile.improvement = "Farm"
            player.setLastSeenImprovement(hiddenTile.position, "Mine")
            player.viewableTiles = emptySet()
            for (tile in game.tileMap.values) tile.setExplored(player, false)
        }

        fun view(revealMap: Boolean = false, revealUnits: Boolean = false) =
            GameView(game.gameInfo, player, revealMap = revealMap, revealUnits = revealUnits)
    }

    @Test fun `map reveal presents unknown terrain and current improvements without changing real knowledge`() {
        val f = Fixture()
        val fog = f.view()
        val map = f.view(revealMap = true)
        val revealedTile = map.getTile(f.hiddenTile)

        assertFalse(fog.getTile(f.hiddenTile).isExplored())
        assertEquals("Mine", fog.getTile(f.hiddenTile).getShownImprovement())
        assertTrue(revealedTile.isExplored())
        assertTrue(map.civView.hasExplored(revealedTile))
        assertTrue(revealedTile.getVisibleNeighbors().any { it.position() == f.neighbor.position })
        assertEquals("Farm", revealedTile.getShownImprovement())
        assertFalse("MAP keeps fog shading and real unit sight", revealedTile.isForceVisible())
        assertFalse(map.civView.canSeeTile(revealedTile))
        assertNull(revealedTile.militaryUnit)
        assertTrue(revealedTile.getVisibleUnits().isEmpty())

        assertFalse(f.player.hasExplored(f.hiddenTile))
        assertEquals("Mine", f.player.getLastSeenImprovement(f.hiddenTile.position))
        assertTrue(f.player.viewableTiles.isEmpty())
    }

    @Test fun `only full event projection reveals foreign units outside sight`() {
        val f = Fixture()
        val fog = VisualViewKey(f.player.civID, false, 1L)
        val map = fog.copy(revealMap = true)
        val full = map.copy(revealUnits = true)

        for (restricted in listOf(fog, map)) {
            assertNull(VisualEventProjector.position(f.game.gameInfo, restricted, f.hiddenTile))
            assertNull(VisualEventProjector.unit(f.game.gameInfo, restricted, f.hiddenUnit, f.hiddenTile))
        }
        assertNotNull(VisualEventProjector.position(f.game.gameInfo, full, f.hiddenTile))
        assertEquals(f.hiddenUnit.id,
            VisualEventProjector.unit(f.game.gameInfo, full, f.hiddenUnit, f.hiddenTile)?.unit?.unitId)
        assertNotEquals("Visibility is part of animation authority", full, map)
        assertNull(VisualEventProjector.unit(f.game.gameInfo, map, f.hiddenUnit, f.hiddenTile))
        assertFalse(f.player.hasExplored(f.hiddenTile))
    }

    @Test fun `map reveal respects invisible-unit detection while full presentation may reveal them`() {
        val f = Fixture()
        val invisibleTile = f.game.getTile(1, 1)
        val invisible = f.game.addDefaultMeleeUnitWithUniques(f.enemy, invisibleTile, UniqueType.Invisible.text)
        f.player.viewableTiles = setOf(invisibleTile)
        val map = VisualViewKey(f.player.civID, false, 1L, revealMap = true)

        assertNull(f.view(revealMap = true).getTile(invisibleTile).militaryUnit)
        assertNull(VisualEventProjector.unit(f.game.gameInfo, map, invisible, invisibleTile))
        assertNotNull(f.view(revealMap = true, revealUnits = true).getTile(invisibleTile).militaryUnit)
        assertNotNull(VisualEventProjector.unit(f.game.gameInfo, map.copy(revealUnits = true), invisible, invisibleTile))
    }
}
