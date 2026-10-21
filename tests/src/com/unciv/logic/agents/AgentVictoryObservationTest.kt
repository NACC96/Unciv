package com.unciv.logic.agents

import com.unciv.json.json
import com.unciv.logic.civilization.Civilization
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.Victory
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Assert the model's actual JSON boundary, including explicit false/zero/unknown values. */
@RunWith(BaseTestRunner::class)
class AgentVictoryObservationTest {
    private class Fixture {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_Vanilla).apply {
            gameInfo.gameParameters.baseRuleset = BaseRuleset.Civ_V_Vanilla.fullName
            gameInfo.gameParameters.victoryTypes = arrayListOf("Domination")
            makeHexagonalMap(8)
            gameInfo.agentNormalVisibility = true
        }
        val rome = game.addCiv(game.ruleset.nations["Rome"]!!, isPlayer = true)
        val greece = game.addCiv(game.ruleset.nations["Greece"]!!, isPlayer = true)
        val egypt = game.addCiv(game.ruleset.nations["Egypt"]!!)
        val romeCapital = game.addCity(rome, game.getTile(-5, 0))
        val greekCapital = game.addCity(greece, game.getTile(4, 0))
        val egyptCapital = game.addCity(egypt, game.getTile(0, 5))
        val match = AgentMatchState(matchId = "victory-test", timelineId = "timeline", turnSerial = 524,
            stateRevision = 19, turnPhase = TurnPhase.AWAITING_DECISIONS).apply {
            controllers[rome.civID] = CivilizationController(rome.civID, ControllerKind.EXTERNAL_AGENT,
                AgentProfile("p", "Rome", "codex", "test", null, "", 1))
            controllers[greece.civID] = CivilizationController(greece.civID, ControllerKind.HUMAN)
            controllers[egypt.civID] = CivilizationController(egypt.civID, ControllerKind.BUILTIN_AI)
        }

        init { game.gameInfo.turns = 104 }

        fun coordinator() = MatchCoordinator(game.gameInfo, match, InMemoryCheckpointWriter())
        fun observe(civ: Civilization = rome): Map<*, *> = AgentEngineBridge(coordinator()).observe(civ.civID) as Map<*, *>
    }

    private fun Map<*, *>.objectAt(key: String): Map<*, *> {
        assertTrue("Expected object '$key' in $this", this[key] is Map<*, *>)
        return this[key] as Map<*, *>
    }
    private fun Map<*, *>.objectsAt(key: String): List<Map<*, *>> {
        assertTrue("Expected array '$key' in $this", this[key] is List<*>)
        return (this[key] as List<*>).map { it as Map<*, *> }
    }

    @Test fun `Domination-only bridge observation exposes the enabled objective and actual round`() {
        val f = Fixture()
        // A defeated civilization and mixed controllers make serial / roster size an invalid clock.
        f.game.addCiv(f.game.ruleset.nations["Persia"]!!).citiesCreated = 1
        val observation = f.observe()
        assertEquals(104, observation["gameTurn"])
        assertEquals(524, observation["turnSerial"])
        val victory = observation.objectAt("victory")
        assertEquals(listOf("Domination"), victory["enabledVictoryTypes"])
        val route = victory.objectsAt("available").single()
        assertEquals("Domination", route["name"])
        assertEquals(false, route["completed"])
        assertEquals(0, route["completedMilestones"])
        assertEquals(0, route["nextMilestone"])
        assertEquals("CaptureAllCapitals", route.objectsAt("milestones").single()["type"])
        assertEquals(false, route.objectsAt("milestones").single()["completed"])
        assertNull(victory["achieved"])
        assertEquals(listOf("Domination"), f.game.gameInfo.gameParameters.victoryTypes)
    }

    @Test fun `hidden Time remains available and scientific assembly remains enabled but unsupported`() {
        val f = Fixture()
        f.game.gameInfo.gameParameters.victoryTypes = arrayListOf("Time", "Scientific")
        assertTrue(f.game.ruleset.victories.getValue("Time").hiddenInVictoryScreen)
        val victory = f.observe().objectAt("victory")
        assertEquals(setOf("Time", "Scientific"), (victory["enabledVictoryTypes"] as List<*>).toSet())
        val routes = victory.objectsAt("available").associateBy { it["name"] }
        assertEquals(setOf("Time", "Scientific"), routes.keys)
        val assembly = routes.getValue("Scientific").objectsAt("milestones")
            .single { it["type"] == "AddedSSPartsInCapital" }
        assertEquals("unsupported", assembly["status"])
        assertEquals(false, assembly["completed"])
        assertEquals(0, assembly["current"])
        assertEquals(3, assembly.objectAt("remainingSpaceshipParts")["SS Booster"])
        assertTrue((assembly["unsupportedReason"] as String).isNotBlank())
    }

    @Test fun `availability uses civilization rules and unknown mod milestones stay explicitly unknown`() {
        val f = Fixture()
        val serializer = AgentEngineBridge(f.coordinator())
        val humanOnly = json().fromJson(Victory::class.java,
            """{"name":"Human goal","uniques":["Only available <for [Human player] Civilizations>"],"milestones":["Build [Monument]"]}""")
        val unknown = json().fromJson(Victory::class.java,
            """{"name":"Mod goal","milestones":["Unsupported mod requirement"]}""")
        f.game.ruleset.victories[humanOnly.name] = humanOnly
        f.game.ruleset.victories[unknown.name] = unknown
        f.game.gameInfo.gameParameters.victoryTypes = arrayListOf(humanOnly.name, unknown.name)
        // The ordinary bridge serializer also covers in-memory mod fixtures; the coordinator's
        // save loader deliberately reloads installed rules instead of this test's mutated clone.
        fun observe(civ: Civilization) = (serializer.toJsonMap(buildObservation(f.game.gameInfo, f.match, civ.civID))
            as Map<*, *>).objectAt("victory")
        val human = observe(f.rome)
        assertEquals(setOf(humanOnly.name, unknown.name), human.objectsAt("available").map { it["name"] }.toSet())
        val ai = observe(f.egypt)
        assertEquals(listOf(unknown.name), ai.objectsAt("available").map { it["name"] })
        val route = ai.objectsAt("available").single()
        assertNull(route["completed"])
        assertNull(route.objectsAt("milestones").single()["completed"])
        assertEquals("unsupported", route.objectsAt("milestones").single()["status"])
        assertNull(ai["achieved"])
    }

    @Test fun `hidden civilization count stays unknown without leaking an unmet founder identity`() {
        val f = Fixture()
        f.game.gameInfo.gameParameters.randomNumberOfPlayers = true
        f.game.gameInfo.gameParameters.maxNumberOfPlayers = 8
        f.match.vision = MatchVision.FOG
        val victory = f.observe().objectAt("victory")
        val domination = victory.objectAt("domination")
        assertNull(domination["requiredOriginalCapitals"])
        assertEquals(listOf(f.rome.civID), domination.objectsAt("capitals").map { it["foundingCivId"] })
        assertNull(victory.objectsAt("available").single().objectsAt("milestones").single()["target"])
    }

    @Test fun `capital ownership agrees with canonical victory through replacement defeat liberation and recapture`() {
        val f = Fixture()
        f.match.vision = MatchVision.FULL
        val replacement = f.game.addCity(f.greece, f.game.getTile(7, 0))
        fun check(owned: Int) {
            val victory = f.observe().objectAt("victory")
            val domination = victory.objectAt("domination")
            assertEquals(owned, domination["ownedOriginalCapitals"])
            assertEquals(3, domination["requiredOriginalCapitals"])
            val capitals = domination.objectsAt("capitals")
            assertFalse(capitals.any { it["cityId"] == replacement.id })
            val greek = capitals.single { it["foundingCivId"] == f.greece.civID }
            assertEquals(f.greekCapital.id, greek["cityId"])
            assertEquals(f.greekCapital.civ.civID, greek["ownerCivId"])
            assertEquals("CURRENT", greek["knowledge"])
            assertEquals(f.rome.victoryManager.getVictoryTypeAchieved(), victory["achieved"])
            assertEquals(f.game.ruleset.victories.getValue("Domination").milestoneObjects.single()
                .hasBeenCompletedBy(f.rome), victory.objectsAt("available").single()["completed"])
        }
        check(1)
        f.greekCapital.moveToCiv(f.egypt)
        assertTrue(replacement.isCapital())
        check(1)
        replacement.moveToCiv(f.rome)
        assertTrue(f.greece.isDefeated())
        check(1)
        f.greekCapital.moveToCiv(f.rome)
        check(2)
        f.greekCapital.moveToCiv(f.greece)
        check(1)
        f.greekCapital.moveToCiv(f.rome)
        f.egyptCapital.moveToCiv(f.rome)
        check(3)
    }

    @Test fun `FOG retains unknown and stale capitals while MAP identifies its revealed cities`() {
        val f = Fixture()
        f.rome.diplomacyFunctions.makeCivilizationsMeet(f.greece)
        f.match.vision = MatchVision.FOG
        val unknown = f.observe().objectAt("victory").objectAt("domination").objectsAt("capitals")
            .single { it["foundingCivId"] == f.greece.civID }
        assertEquals("UNKNOWN", unknown["knowledge"])
        for (field in listOf("cityId", "name", "x", "y", "ownerCivId")) assertNull(unknown[field])
        // Persist a real historical sighting, then transfer its city outside Rome's sight.
        // Two tiles away is outside Greece's initial border and within the Scout's sight.
        val scout = f.game.addUnit("Scout", f.rome, f.game.getTile(2, 0))
        f.rome.cache.updateViewableTiles()
        val oldSighting = buildPermittedTile(f.game.gameInfo, f.match, f.rome.civID, f.greekCapital.location)!!
        f.match.mapKnowledge.put(f.rome.civID, oldSighting)
        scout.destroy()
        f.rome.cache.updateViewableTiles()
        f.greekCapital.moveToCiv(f.egypt)
        f.game.gameInfo.turns = 110
        val known = f.observe().objectAt("victory").objectAt("domination").objectsAt("capitals")
            .single { it["foundingCivId"] == f.greece.civID }
        assertEquals("REMEMBERED", known["knowledge"])
        assertEquals(f.greece.civID, known["ownerCivId"])
        assertEquals(104, known["lastSeenGameTurn"])
        assertEquals(19, known["lastSeenRevision"])
        f.match.vision = MatchVision.MAP
        val revealed = f.observe().objectAt("victory").objectAt("domination").objectsAt("capitals")
            .single { it["foundingCivId"] == f.greece.civID }
        assertEquals("REVEALED", revealed["knowledge"])
        assertEquals(f.egypt.civID, revealed["ownerCivId"])
        f.match.vision = MatchVision.FULL
        val current = f.observe().objectAt("victory").objectAt("domination").objectsAt("capitals")
            .single { it["foundingCivId"] == f.greece.civID }
        assertEquals("CURRENT", current["knowledge"])
        assertEquals(f.egypt.civID, current["ownerCivId"])
        assertEquals(110, current["lastSeenGameTurn"])
    }

    @Test fun `repeated serialized observations and rules reads leave committed game and match unchanged`() {
        val f = Fixture()
        val coordinator = f.coordinator()
        val bridge = AgentEngineBridge(coordinator)
        val gameBefore = coordinator.serializeCommittedGameForPublication()
        val matchBefore = coordinator.serializeCommittedMatchForPublication()
        val first = AgentJson.stringify(bridge.observe(f.rome.civID))
        repeat(3) {
            assertEquals(first, AgentJson.stringify(bridge.observe(f.rome.civID)))
            bridge.rules()
        }
        assertEquals(gameBefore, coordinator.serializeCommittedGameForPublication())
        assertEquals(matchBefore, coordinator.serializeCommittedMatchForPublication())
        assertEquals(19, coordinator.getStateRevision())
        assertTrue((AgentJson.parse(first) as Map<*, *>).containsKey("victory"))
        // Older city sightings have unknown capital identity, never false or fabricated identity.
        val legacy = json().fromJson(ObservedPublicCity::class.java,
            """{"cityId":"old","name":"Old city","ownerCivId":"Greece","health":100,"population":2}""")
        val copied = legacy.copy().toWire()
        assertTrue(copied.containsKey("isOriginalCapital"))
        assertNull(copied["isOriginalCapital"])
        assertNull(copied["foundingCivId"])
    }

    @Test fun `capital metadata persists across match round trip and deep copies without inventing old identity`() {
        val f = Fixture()
        val sighting = buildPermittedTile(f.game.gameInfo, f.match, f.rome.civID, f.romeCapital.location)!!
        f.match.mapKnowledge.put(f.rome.civID, sighting)
        val restored = migrateAgentMatchStateJson(json().apply { setUsePrototypes(false) }.toJson(f.match))
        val restoredCity = restored.mapKnowledge.get(f.rome.civID, f.romeCapital.location)!!.city!!
        assertEquals(true, restoredCity.toWire()["isOriginalCapital"])
        assertEquals(f.rome.civID, restoredCity.toWire()["foundingCivId"])
        val copy = restored.deepCopy()
        copy.mapKnowledge.get(f.rome.civID, f.romeCapital.location)!!.city!!.name = "changed copy"
        assertEquals(f.romeCapital.name, restoredCity.name)
    }
}
