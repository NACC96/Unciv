package com.unciv.logic.agents

import com.unciv.json.json
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import com.unciv.utils.DebugUtils
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class AgentMapKnowledgeTest {
    private class Fixture {
        val f = TestGame(baseRuleset = BaseRuleset.Civ_V_Vanilla).apply {
            makeHexagonalMap(5)
            gameInfo.agentNormalVisibility = true
        }
        val civ = f.addCiv(f.ruleset.nations["Rome"]!!, isPlayer = true)
        val unit = f.addUnit("Scout", civ, f.getTile(0, 0))
        val state = AgentMatchState(matchId = "m", timelineId = "t").apply {
            controllers[civ.civID] = CivilizationController(civ.civID, ControllerKind.EXTERNAL_AGENT)
        }
        fun capture(revision: Int) {
            civ.cache.updateViewableTiles()
            AgentMapSightings().apply { captureAll(f.gameInfo, state); flushInto(state, revision) }
            state.stateRevision = revision
        }
        fun hide() {
            unit.destroy()
            civ.cache.updateViewableTiles()
        }
    }

    @Test fun visible_then_remembered_keeps_unseen_changes_stale() {
        val x = Fixture(); x.capture(3)
        val tile = x.f.getTile(0, 0)
        val before = buildPermittedTile(x.f.gameInfo, x.state, x.civ.civID, tile.position)!!
        x.hide()
        tile.setTerrainFeatures(listOf("Forest"))
        tile.improvement = "Farm"
        val foreign = x.f.addCiv(x.f.ruleset.nations["Greece"]!!)
        x.f.addCity(foreign, tile)
        val remembered = buildPermittedTile(x.f.gameInfo, x.state, x.civ.civID, tile.position)!!
        assertEquals(TileKnowledgeVisibility.REMEMBERED, remembered.visibility)
        assertEquals(before.features, remembered.features)
        assertEquals(before.ownerCivId, remembered.ownerCivId)
        assertEquals(before.improvement, remembered.improvement)
        assertEquals(3, remembered.lastSeenRevision)
    }

    @Test fun map_memory_deep_copy_and_reload_isolate_nested_values() {
        val x = Fixture(); x.capture(2)
        val position = HexCoord()
        val original = x.state.mapKnowledge.get(x.civ.civID, position)!!
        original.resource = ObservedResource("Iron", 4, 0, 2)
        original.city = ObservedPublicCity("id", "City", x.civ.civID, 200, 1)
        original.ownWork = ObservedOwnWork("Farm", 2)
        val copy = x.state.deepCopy().mapKnowledge.get(x.civ.civID, position)!!
        copy.features!!.add("Forest")
        copy.yields!!.values["Food"] = 999f
        copy.resource!!.name = "Coal"
        copy.city!!.name = "Changed"
        copy.ownWork!!.turnsRemaining = 99
        assertFalse(original.features!!.contains("Forest"))
        assertNotEquals(999f, original.yields!!.values["Food"])
        assertEquals("Iron", original.resource!!.name)
        assertEquals("City", original.city!!.name)
        assertEquals(2, original.ownWork!!.turnsRemaining)
        val serializer = json().apply { setUsePrototypes(false) }
        val saved = serializer.toJson(x.state)
        val reloaded = serializer.fromJson(AgentMatchState::class.java, saved)
        assertEquals(AgentJson.stringify(original.toWire()),
            AgentJson.stringify(reloaded.mapKnowledge.get(x.civ.civID, position)!!.toWire()))
    }

    @Test fun map_discovery_does_not_claim_complete_sighting() {
        val x = Fixture()
        val far = x.f.getTile(5, 0)
        far.setExplored(x.civ, true)
        x.capture(1)
        val result = buildPermittedTile(x.f.gameInfo, x.state, x.civ.civID, far.position)!!
        assertFalse(result.detailsKnown)
        assertNull(result.lastSeenTurn)
        assertNull(result.terrain)
        assertNull(result.features)
        assertNull(result.yields)
    }

    @Test fun resource_reveal_preserves_unseen_old_border_and_sighting_timestamp() {
        val x = Fixture(); val tile = x.f.getTile(0, 0)
        tile.setTileResource("Iron"); tile.resourceAmount = 6
        x.capture(4); x.hide()
        val before = x.state.mapKnowledge.get(x.civ.civID, tile.position)!!.copy()
        tile.improvement = "Mine"
        val foreign = x.f.addCiv(x.f.ruleset.nations["Greece"]!!)
        x.f.addCity(foreign, tile)
        x.civ.tech.addTechnology(x.f.ruleset.tileResources["Iron"]!!.revealedBy!!)
        x.capture(5)
        val result = buildPermittedTile(x.f.gameInfo, x.state, x.civ.civID, tile.position)!!
        assertEquals(before.ownerCivId, result.ownerCivId)
        assertEquals(before.improvement, result.improvement)
        assertEquals(before.lastSeenTurn, result.lastSeenTurn)
        assertEquals(4, result.lastSeenRevision)
        assertEquals("Iron", result.resource!!.name)
        assertEquals(6, result.resource!!.amount)
        assertEquals(ResourceKnowledgeSource.TECHNOLOGY, result.resource!!.source)
        assertEquals(5, result.resource!!.updatedRevision)
        val serialized = json().apply { setUsePrototypes(false) }.toJson(x.state)
        val reloaded = json().fromJson(AgentMatchState::class.java, serialized)
            .mapKnowledge.get(x.civ.civID, tile.position)!!.resource!!
        assertEquals(ResourceKnowledgeSource.TECHNOLOGY, reloaded.source)
        assertEquals(5, reloaded.updatedRevision)
    }

    @Test fun intermediate_sightings_survive_final_visibility_loss_and_failed_candidate_isolation() {
        val x = Fixture()
        val candidate = x.state.deepCopy()
        val collector = AgentMapSightings()
        collector.recordVisible(x.civ)
        x.hide()
        collector.captureAll(x.f.gameInfo, candidate)
        collector.flushInto(candidate, 2)
        assertTrue(candidate.mapKnowledge.get(x.civ.civID, HexCoord())!!.detailsKnown)
        assertTrue(x.state.mapKnowledge.tilesByCiv.isEmpty())
    }

    @Test fun read_paths_and_publication_do_not_write_memory() {
        val x = Fixture(); x.capture(2)
        val serializer = json().apply { setUsePrototypes(false) }
        val before = serializer.toJson(x.state)
        repeat(3) {
            buildPermittedTile(x.f.gameInfo, x.state, x.civ.civID, HexCoord())!!.features!!.add("Changed")
            buildMapPage(x.f.gameInfo, x.state, x.civ.civID, MapQuery())
            buildMapSummary(x.f.gameInfo, x.state, x.civ.civID)
        }
        assertEquals(before, serializer.toJson(x.state))
        assertNull(x.f.gameInfo.clone().agentMapSightings)
    }

    @Test fun debug_reveal_during_restoration_does_not_mark_agent_world_explored() {
        val x = Fixture()
        val old = DebugUtils.VISIBLE_MAP
        try {
            DebugUtils.VISIBLE_MAP = true
            x.f.gameInfo.suppressAutomationDuringTransients = true
            x.f.gameInfo.setTransients()
            assertFalse(x.f.getTile(5, 0).isExploredNormally(x.civ))
            assertNull(buildPermittedTile(x.f.gameInfo, x.state, x.civ.civID, HexCoord(5, 0)))
        } finally { DebugUtils.VISIBLE_MAP = old }
    }

    @Test fun genuine_v1_without_version_fields_migrates_and_preserves_identity() {
        val oldMatch = """{"matchId":"m","timelineId":"t","stateRevision":7,"privateNotes":{"Rome":"notes"}}"""
        val oldBundle = AgentJson.stringify(mapOf("gameJson" to "{}", "matchJson" to oldMatch))
        val upgraded = migrateAgentBundleJson(oldBundle)
        assertEquals(2, upgraded.bundleVersion)
        assertEquals("{}", upgraded.gameJson)
        assertTrue(upgraded.matchJson.contains("\"formatVersion\":2"))
        val state = json().fromJson(AgentMatchState::class.java, upgraded.matchJson)
        assertEquals("m", state.matchId); assertEquals("t", state.timelineId)
        assertEquals(7, state.stateRevision); assertEquals("notes", state.privateNotes["Rome"])
        assertTrue(state.mapKnowledge.tilesByCiv.isEmpty())
        val explicitV2 = json().apply { setUsePrototypes(false) }.toJson(upgraded)
        assertTrue(explicitV2.contains("\"bundleVersion\":2"))
        assertEquals(2, migrateAgentBundleJson(explicitV2).bundleVersion)
    }

    @Test fun mismatched_and_future_versions_reject() {
        for ((bundleVersion, stateVersion) in listOf(1 to 2, 2 to 1, 3 to 3)) {
            val bundle = AgentBundle("{}", "{\"formatVersion\":$stateVersion}", bundleVersion)
            try { migrateAgentBundle(bundle); fail("Expected rejection") }
            catch (expected: IllegalArgumentException) { assertTrue(expected.message!!.isNotBlank()) }
        }
        try { AgentMatchState(formatVersion = 1).validateFormat(); fail("Strict validation must reject v1") }
        catch (expected: IllegalArgumentException) { assertTrue(expected.message!!.contains("1")) }
    }
}
