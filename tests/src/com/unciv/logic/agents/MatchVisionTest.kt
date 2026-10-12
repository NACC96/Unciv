package com.unciv.logic.agents

import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The match's map vision widens what agents observe; game rules keep each civilization's real sight. */
@RunWith(BaseTestRunner::class)
class MatchVisionTest {

    private class Fixture(vision: MatchVision, metEachOther: Boolean = false) {
        val testGame = TestGame(baseRuleset = BaseRuleset.Civ_V_Vanilla)
        val coordinator: MatchCoordinator
        val rome = run {
            testGame.gameInfo.gameParameters.baseRuleset = BaseRuleset.Civ_V_Vanilla.fullName
            testGame.makeHexagonalMap(5)
            testGame.addCiv(testGame.ruleset.nations["Rome"]!!, isPlayer = true)
        }
        val greece = testGame.addCiv(testGame.ruleset.nations["Greece"]!!, isPlayer = true)
        val scoutId = testGame.addUnit("Scout", rome, testGame.getTile(0, 1)).id
        // Out of Rome's sight, with a city further out still.
        val farGreekId = testGame.addUnit("Warrior", greece, testGame.getTile(4, 0)).id
        val greekCityId = testGame.addCity(greece, testGame.getTile(4, 1)).id
        val settlerId = testGame.addUnit("Settler", rome, testGame.getTile(-3, 2)).id
        val unseenTile = HexCoord(0, -5)

        init {
            testGame.addCity(rome, testGame.getTile(0, 0), initialPopulation = 2)
            if (metEachOther) rome.diplomacyFunctions.makeCivilizationsMeet(greece)
            val match = AgentMatchState(matchId = "m1", timelineId = "t1", turnPhase = TurnPhase.AWAITING_DECISIONS, vision = vision)
            for (civ in listOf(rome, greece)) match.controllers[civ.civID] = CivilizationController(
                civ.civID, ControllerKind.EXTERNAL_AGENT, AgentProfile("p-${civ.civID}", civ.civID, "codex", "m", null, "", 1))
            coordinator = MatchCoordinator(testGame.gameInfo, match, InMemoryCheckpointWriter())
        }
    }

    @Test
    fun `fog keeps today's observation and new matches start with everything visible`() {
        val f = Fixture(MatchVision.FOG)
        assertFalse("This tile must start outside Rome's sight and exploration", f.testGame.getTile(0, -5).isExplored(f.rome))
        val obs = f.coordinator.observe(f.rome.civID)
        assertEquals(MatchVision.FOG, obs.mapSummary.vision)
        assertFalse(obs.visibleForeignUnits.any { it.unitId == f.farGreekId })
        assertTrue(obs.visibleForeignCities.isEmpty())
        assertNull(f.coordinator.inspectTile(f.rome.civID, f.unseenTile))
        assertNull(f.coordinator.inspectUnit(f.rome.civID, f.farGreekId))
        assertEquals("Saves from before the setting keep fog", MatchVision.FOG, AgentMatchState().vision)

        val setup = AgentMatchSetup()
        assertEquals(MatchVision.FULL, setup.buildMatchState(f.testGame.gameInfo).vision)
    }

    @Test
    fun `map vision reveals every tile and city but only units within real sight`() {
        val f = Fixture(MatchVision.MAP)
        val obs = f.coordinator.observe(f.rome.civID)
        assertEquals(MatchVision.MAP, obs.mapSummary.vision)
        assertEquals(f.testGame.gameInfo.tileMap.values.size, obs.mapSummary.knownTileCount)
        assertTrue(obs.mapSummary.visibleTileCount < obs.mapSummary.knownTileCount)
        assertTrue(obs.visibleForeignCities.any { it.cityId == f.greekCityId })
        assertFalse("Units outside real sight stay hidden", obs.visibleForeignUnits.any { it.unitId == f.farGreekId })
        assertNull(f.coordinator.inspectUnit(f.rome.civID, f.farGreekId))

        val revealed = f.coordinator.inspectTile(f.rome.civID, f.unseenTile)!!
        assertEquals(TileKnowledgeVisibility.REVEALED, revealed.visibility)
        assertTrue(revealed.detailsKnown)
        assertNotNull(revealed.terrain)
        val visibleQuery = f.coordinator.map(f.rome.civID, MapQuery(MapQueryMode.VISIBLE, limit = 128))
        assertTrue("VISIBLE stays real sight", visibleQuery.tiles.none { it.x == 0 && it.y == -5 })
    }

    @Test
    fun `full vision shows every unit and tile live but attacks still need real sight`() {
        val f = Fixture(MatchVision.FULL)
        val obs = f.coordinator.observe(f.rome.civID)
        assertTrue(obs.visibleForeignUnits.any { it.unitId == f.farGreekId })
        assertTrue(obs.visibleForeignCities.any { it.cityId == f.greekCityId })
        assertEquals(obs.mapSummary.knownTileCount, obs.mapSummary.visibleTileCount)
        assertEquals(TileKnowledgeVisibility.VISIBLE, f.coordinator.inspectTile(f.rome.civID, f.unseenTile)!!.visibility)
        assertEquals(f.greece.civID, f.coordinator.inspectUnit(f.rome.civID, f.farGreekId)!!.ownerCivId)

        val legal = f.coordinator.legalActions(f.rome.civID, unitId = f.scoutId)
        assertTrue(legal.attackTargets.none { it.targetX == 4 && it.targetY == 0 })
        assertFalse("Revealing for observation never explores for the game", f.testGame.getTile(0, -5).isExplored(f.rome))
    }

    @Test
    fun `changing vision is a metadata commit that survives reload and pauses`() {
        val f = Fixture(MatchVision.FOG)
        val revision = f.coordinator.getStateRevision()
        f.coordinator.setVision(MatchVision.FULL)
        assertEquals(MatchVision.FULL, f.coordinator.getVision())
        assertEquals("No gameplay revision", revision, f.coordinator.getStateRevision())
        assertTrue(f.coordinator.observe(f.rome.civID).visibleForeignUnits.any { it.unitId == f.farGreekId })

        val reloaded = migrateAgentMatchStateJson(f.coordinator.serializeCommittedMatchForPublication())
        assertEquals(MatchVision.FULL, reloaded.vision)
        assertEquals(MatchVision.FULL, reloaded.deepCopy().vision)
    }

    @Test
    fun `rivals list met civilizations under fog and everyone under revealed vision`() {
        val unmet = Fixture(MatchVision.FOG).let { it.coordinator.observe(it.rome.civID) }
        assertTrue(unmet.rivals.isEmpty())

        val met = Fixture(MatchVision.FOG, metEachOther = true)
        val obs = met.coordinator.observe(met.rome.civID)
        val greece = obs.rivals.single { it.civId == met.greece.civID }
        assertTrue(greece.met)
        assertEquals("Peace", greece.status)
        assertNotNull(greece.attitudeTowardYou)
        assertEquals(1, greece.cities)
        assertTrue(greece.militaryStrength > 0)
        val stronger = if (greece.militaryStrength > obs.empire.militaryStrength) 1 else 0
        assertEquals(1 + stronger, obs.empire.militaryRank)

        val revealed = Fixture(MatchVision.MAP).let { it.coordinator.observe(it.rome.civID) }
        val unmetGreece = revealed.rivals.single()
        assertFalse(unmetGreece.met)
        assertNull(unmetGreece.status)
    }

    @Test
    fun `explore acts now and settlers get ranked sites with automation that founds`() {
        val f = Fixture(MatchVision.FOG)
        val scoutBefore = f.coordinator.getUnitPosition(f.rome.civID, f.scoutId)
        val explored = f.coordinator.execute(UnitOrderCommand(f.rome.civID, "explore", f.coordinator.getStateRevision(),
            f.scoutId, UnitOrder.EXPLORE)) as UnitOrderResult
        assertEquals("applied", explored.receipt.outcome)
        assertTrue("Explore moves with the scout's remaining movement now",
            f.coordinator.getUnitPosition(f.rome.civID, f.scoutId) != scoutBefore)

        val legal = f.coordinator.legalActions(f.rome.civID, unitId = f.settlerId)
        assertTrue(legal.orders.contains(UnitOrder.AUTOMATE))
        assertTrue(legal.orders.contains(UnitOrder.EXPLORE))
        assertTrue("A settler on open explored land has ranked sites", legal.citySites.isNotEmpty())
        assertEquals(legal.citySites.sortedByDescending { it.score }, legal.citySites)

        val cities = f.rome.cities.size
        var result: UnitOrderResult? = null
        for (attempt in 0 until 4) {
            result = f.coordinator.execute(UnitOrderCommand(f.rome.civID, "settle-$attempt", f.coordinator.getStateRevision(),
                f.settlerId, UnitOrder.AUTOMATE)) as UnitOrderResult
            if (result.foundedCityId != null) break
            f.coordinator.execute(EndTurnCommand(f.rome.civID, "end-r-$attempt", f.coordinator.getStateRevision()))
            f.coordinator.execute(EndTurnCommand(f.greece.civID, "end-g-$attempt", f.coordinator.getStateRevision()))
        }
        assertNotNull("Automation founds within a few turns", result!!.foundedCityId)
        assertTrue(result.disbanded)
        assertEquals(cities + 1, f.coordinator.observe(f.rome.civID).empire.cityCount)
        assertFalse("Only founding units advertise automation",
            f.coordinator.legalActions(f.rome.civID, unitId = f.scoutId).orders.contains(UnitOrder.AUTOMATE))
    }
}
