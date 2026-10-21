package com.unciv.logic.agents

import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class AgentMilitaryObservationTest {
    private class Fixture {
        val test = TestGame(baseRuleset = BaseRuleset.Civ_V_Vanilla).apply {
            gameInfo.gameParameters.baseRuleset = BaseRuleset.Civ_V_Vanilla.fullName
            gameInfo.gameParameters.victoryTypes = arrayListOf("Domination")
            makeHexagonalMap(7, "Grassland")
        }
        val rome = test.addCiv(test.ruleset.nations.getValue("Rome"), isPlayer = true)
        val greece = test.addCiv(test.ruleset.nations.getValue("Greece"))
        val home = test.addCity(rome, test.getTile(0, 0))
        val foreign = test.addCity(greece, test.getTile(5, 0))
        val rifle = test.addUnit("Rifleman", rome, test.getTile(0, 1)).apply { health = 50 }
        val artillery = test.addUnit("Artillery", rome, test.getTile(-1, 0))
        val visibleEnemy = test.addUnit("Warrior", greece, test.getTile(2, 0))
        val hiddenEnemy = test.addUnit("Tank", greece, test.getTile(5, 1))
        val match = AgentMatchState(matchId = "military", timelineId = "test", turnPhase = TurnPhase.AWAITING_DECISIONS).apply {
            controllers[rome.civID] = CivilizationController(rome.civID, ControllerKind.EXTERNAL_AGENT,
                AgentProfile("p", "Rome", "codex", "test", null, "", 1))
            controllers[greece.civID] = CivilizationController(greece.civID)
        }
        init {
            rome.diplomacyFunctions.makeCivilizationsMeet(greece)
            test.addUnit("Worker", rome, test.getTile(0, -1))
            test.addUnit("Nuclear Missile", greece, foreign.getCenterTile())
            rome.cache.updateViewableTiles()
        }
        fun coordinator() = MatchCoordinator(test.gameInfo, match)
        fun observe(): Map<*, *> = AgentEngineBridge(coordinator()).observe(rome.civID) as Map<*, *>
    }

    private fun Map<*, *>.obj(key: String): Map<*, *> {
        assertTrue("Expected $key in the actor's observation", this[key] is Map<*, *>)
        return this[key] as Map<*, *>
    }
    private fun Map<*, *>.rival(): Map<*, *> = ((this["rivals"] as List<*>).single() as Map<*, *>).obj("forces")

    @Test fun `own conventional readiness excludes nuclear weapons and treasury multipliers`() {
        val f = Fixture()
        val before = f.observe()
        val army = before.obj("military").obj("own")
        assertEquals("COMPLETE", army["coverage"])
        assertEquals(2, army["knownMilitaryUnits"])
        assertEquals(2, army["totalMilitaryUnits"])
        assertEquals(2, army["conventionalUnits"])
        assertEquals(0, army["nuclearWeapons"])
        assertEquals(1, army["captureCapableUnits"])
        assertEquals(1, army["rangedUnits"])
        assertEquals(1, army["siegeUnits"])
        assertEquals(1, army["damagedUnits"])
        assertTrue((army["conventionalUnitForce"] as Number).toInt() > 0)

        f.test.addUnit("Nuclear Missile", f.rome, f.home.getCenterTile())
        f.test.addUnit("Atomic Bomb", f.rome, f.home.getCenterTile())
        f.rome.addGold(10000)
        val after = f.observe()
        val armed = after.obj("military").obj("own")
        assertEquals(2, armed["nuclearWeapons"])
        assertEquals(4, armed["knownMilitaryUnits"])
        assertEquals(army["conventionalUnits"], armed["conventionalUnits"])
        assertEquals(army["conventionalUnitForce"], armed["conventionalUnitForce"])
        assertTrue((after.obj("empire")["militaryStrength"] as Number).toInt() >
            (before.obj("empire")["militaryStrength"] as Number).toInt())
    }

    @Test fun `foreign composition respects real unit visibility even with a revealed map`() {
        val f = Fixture()
        for (vision in listOf(MatchVision.FOG, MatchVision.MAP)) {
            f.match.vision = vision
            val army = f.observe().obj("military").rival()
            assertEquals("VISIBLE_ONLY", army["coverage"])
            assertEquals(1, army["knownMilitaryUnits"])
            assertNull(army["totalMilitaryUnits"])
            assertEquals(0, army["nuclearWeapons"])
            // Foreign private promotions and force calculations are not exposed by this summary.
            assertNull(army["conventionalUnitForce"])
        }
        f.match.vision = MatchVision.FULL
        val full = f.observe().obj("military").rival()
        assertEquals("COMPLETE", full["coverage"])
        assertEquals(3, full["totalMilitaryUnits"])
        assertEquals(2, full["conventionalUnits"])
        assertEquals(1, full["nuclearWeapons"])
    }

    @Test fun `no visible rivals does not imply an empty enemy army`() {
        val f = Fixture()
        f.visibleEnemy.destroy()
        val army = f.observe().obj("military").rival()
        assertEquals("VISIBLE_ONLY", army["coverage"])
        assertEquals(0, army["knownMilitaryUnits"])
        assertNull(army["totalMilitaryUnits"])
        assertNull(army["conventionalUnitForce"])
    }

    @Test fun `ranged and prohibited capture units cannot masquerade as a capture force`() {
        val f = Fixture()
        f.rifle.destroy()
        f.test.addUnit("Helicopter Gunship", f.rome, f.test.getTile(0, 1))
        assertEquals(0, f.observe().obj("military").obj("own")["captureCapableUnits"])
        val coordinator = f.coordinator()
        val before = coordinator.saveToJson()
        repeat(3) { AgentEngineBridge(coordinator).observe(f.rome.civID) }
        assertEquals(before, coordinator.saveToJson())
    }
}
