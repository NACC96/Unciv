package com.unciv.logic.presentation

import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.battle.Nuke
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import com.unciv.view.VisualEventProjector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** A nuclear strike reaches the screen as one event holding everything it caused, under each viewer's fog. */
@RunWith(BaseTestRunner::class)
class NuclearStrikeCaptureTest {
    private class Fixture {
        val game = TestGame().apply { makeHexagonalMap(7) }
        val rome = game.addCiv(game.ruleset.nations.getValue("Rome"), isPlayer = true)
        val greece = game.addCiv(game.ruleset.nations.getValue("Greece"))
        val launchTile = game.getTile(-5, 0)
        val groundZero = game.getTile(3, 0)
        val greekCity = game.addCity(greece, game.getTile(5, 0))
        val bomb = run {
            game.addCity(rome, launchTile)
            game.addUnit("Atomic Bomb", rome, launchTile)
        }
        /** Weak enough to die even from the half-strength blast of a bomb launched without Uranium. */
        val victim = game.addUnit("Warrior", greece, groundZero).apply { health = 30 }
        val romanScout = game.addUnit("Warrior", rome, game.getTile(-4, 0))
        val journal = VisualJournal(game.gameInfo.gameId)

        init { for (civ in listOf(rome, greece)) civ.cache.updateSightAndResources() }

        /** Everything, as under FULL vision. */
        val everything = VisualViewKey(rome.civID, false, 1L, revealMap = true, revealUnits = true)

        fun watch(view: VisualViewKey) {
            journal.reset(view)
            VisualCapture.install(game.gameInfo, VisualCapture.Session(view, VisualEventProjector, journal))
        }

        fun nuke() = Nuke.NUKE(MapUnitCombatant(bomb), groundZero)

        fun strike(view: VisualViewKey): VisualEvent.NuclearStrike =
            journal.readAfter(0L, view).events.single() as VisualEvent.NuclearStrike

        fun position(x: Int, y: Int) = VisualPosition(x, y)
    }

    @Test
    fun `a detonation is one journal event holding its hits and removals`() {
        val f = Fixture()
        f.watch(f.everything)
        f.nuke()

        val batch = f.journal.readAfter(0L, f.everything)
        val strike = batch.events.single() as VisualEvent.NuclearStrike
        assertEquals(f.position(-5, 0), strike.source)
        assertEquals(f.position(3, 0), strike.target)
        assertEquals(f.bomb.id, (strike.weapon as VisualUnitDescriptor).unitId)
        assertEquals(2, strike.blastRadius)
        assertFalse(strike.intercepted)

        val hit = strike.parts.filterIsInstance<VisualEvent.Attack>()
        assertEquals(setOf("unit:${f.victim.id}", "city:${f.greekCity.id}"), hit.map { it.defender!!.stableId }.toSet())
        assertTrue(hit.all { it.kind == VisualAttackKind.NUCLEAR })
        val removed = strike.parts.filterIsInstance<VisualEvent.RemoveUnit>().map { it.unit.unitId }
        assertEquals("The victim dies, and the weapon is used up last", listOf(f.victim.id, f.bomb.id), removed)

        // Parts never take journal places, so the journal sees no gap and the screen keeps playing.
        assertEquals(1L, strike.id.sequence)
        assertEquals(0L, batch.droppedThroughSequence)
        f.romanScout.movement.moveToTile(f.game.getTile(-3, 0))
        val next = f.journal.readAfter(0L, f.everything)
        assertEquals(listOf(1L, 2L), next.events.map { it.id.sequence })
        assertEquals(0L, next.droppedThroughSequence)
    }

    @Test
    fun `the target's owner sees the blast but not where it came from`() {
        val f = Fixture()
        val greekView = VisualViewKey(f.greece.civID, false, 1L)
        f.watch(greekView)
        f.nuke()

        val strike = f.strike(greekView)
        assertNull(strike.source)
        assertNull(strike.weapon)
        assertEquals(f.position(3, 0), strike.target)
        assertFalse(strike.parts.any { it is VisualEvent.RemoveUnit && it.unit.unitId == f.bomb.id })
        assertTrue(strike.parts.any { it is VisualEvent.RemoveUnit && it.unit.unitId == f.victim.id })
    }

    @Test
    fun `the launcher sees its weapon leave but not a ground zero out of sight`() {
        val f = Fixture()
        val romanView = VisualViewKey(f.rome.civID, false, 1L)
        f.watch(romanView)
        f.nuke()

        val strike = f.strike(romanView)
        assertEquals(f.position(-5, 0), strike.source)
        assertEquals(f.bomb.id, (strike.weapon as VisualUnitDescriptor).unitId)
        assertNull(strike.target)
        assertEquals(listOf(f.bomb.id), strike.parts.map { (it as VisualEvent.RemoveUnit).unit.unitId })
    }

    @Test
    fun `an intercepted weapon never shows a detonation, and only to those who saw it`() {
        for (viewerSawWeapon in listOf(true, false)) {
            val f = Fixture()
            val view = if (viewerSawWeapon) f.everything else VisualViewKey(f.greece.civID, false, 1L)
            f.watch(view)
            val scope = VisualCapture.begin(f.game.gameInfo, view)
            try {
                val before = scope.beginNuclearStrike(MapUnitCombatant(f.bomb), f.groundZero, 2)
                f.bomb.destroy()
                scope.endNuclearStrike(before, MapUnitCombatant(f.bomb), f.groundZero, intercepted = true)
                scope.publishCommitted()
            } finally {
                scope.close()
            }

            val events = f.journal.readAfter(0L, view).events
            if (!viewerSawWeapon) {
                assertTrue("Nothing happened that this viewer could see", events.isEmpty())
                continue
            }
            val strike = events.single() as VisualEvent.NuclearStrike
            assertTrue(strike.intercepted)
            assertNull("No detonation at ground zero", strike.target)
            assertEquals(f.position(-5, 0), strike.source)
            assertEquals(listOf(f.bomb.id), strike.parts.map { (it as VisualEvent.RemoveUnit).unit.unitId })
        }
    }
}
