package com.unciv.logic.agents

import com.unciv.json.json
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.file.Files

/** Coordinator transactions and opponent observations through public APIs, never the exporter. */
@RunWith(BaseTestRunner::class)
class AgentMapTransactionsTest {
    private class Fixture {
        val f = TestGame(baseRuleset = BaseRuleset.Civ_V_Vanilla).apply {
            makeHexagonalMap(7)
            gameInfo.gameParameters.baseRuleset = BaseRuleset.Civ_V_Vanilla.fullName
        }
        val actor = f.addCiv(f.ruleset.nations["China"]!!, isPlayer = true)
        val observer = f.addCiv(f.ruleset.nations["Greece"]!!, isPlayer = true)
        val actorCity = f.addCity(actor, f.getTile(0, 0))
        val observerCity = f.addCity(observer, f.getTile(6, 0))
        val scout = f.addUnit("Scout", actor, f.getTile(-4, 0))
        val observerScout = f.addUnit("Scout", observer, f.getTile(3, 0))
        val writer = InMemoryCheckpointWriter()
        val profile = AgentProfile("p", "Test", "codex", "gpt-6-luna", "xhigh", "", 1)
        val coordinator: MatchCoordinator
        init {
            actor.addGold(1000)
            actor.diplomacyFunctions.makeCivilizationsMeet(observer)
            f.gameInfo.currentPlayer = actor.civID
            val match = AgentMatchState(matchId = "map-transactions", timelineId = "t",
                turnPhase = TurnPhase.AWAITING_DECISIONS)
            for (civ in listOf(actor, observer)) match.controllers[civ.civID] = CivilizationController(
                civ.civID, ControllerKind.EXTERNAL_AGENT, profile.copy())
            coordinator = MatchCoordinator(f.gameInfo, match, writer)
        }
    }

    private fun savedState(coordinator: MatchCoordinator): AgentMatchState {
        val serializer = json()
        val bundle = serializer.fromJson(AgentBundle::class.java, coordinator.saveToJson())
        return serializer.fromJson(AgentMatchState::class.java, bundle.matchJson)
    }

    private fun memoryJson(coordinator: MatchCoordinator): String =
        json().apply { setUsePrototypes(false) }.toJson(savedState(coordinator).mapKnowledge)

    @Test fun multi_step_move_retains_intermediate_sightings() {
        val f = Fixture(); val position = HexCoord(-3, 0)
        val before = f.coordinator.inspectTile(f.actor.civID, position)!!
        assertEquals(TileKnowledgeVisibility.VISIBLE, before.visibility)
        assertEquals(0, before.lastSeenRevision)
        f.coordinator.execute(MoveUnitCommand(f.actor.civID, "scout-west", 0, f.scout.id, HexCoord(-6, 0)))
        assertEquals(HexCoord(-6, 0), f.coordinator.getUnitPosition(f.actor.civID, f.scout.id))
        val remembered = f.coordinator.inspectTile(f.actor.civID, position)!!
        assertEquals(TileKnowledgeVisibility.REMEMBERED, remembered.visibility)
        // At -5 the scout still sees -3; at final -6 it does not. The revision
        // therefore proves an intermediate action sighting, not initialization or final sweep.
        assertEquals(f.coordinator.getStateRevision(), remembered.lastSeenRevision)
        assertEquals(before.terrain, remembered.terrain)
        assertEquals(remembered.toWire(), MatchCoordinator.loadFromJson(f.coordinator.saveToJson())
            .inspectTile(f.actor.civID, position)!!.toWire())
    }

    @Test fun checkpoint_failure_discards_sightings() {
        val f = Fixture()
        val memoryBefore = memoryJson(f.coordinator)
        val tileBefore = f.coordinator.inspectTile(f.actor.civID, HexCoord(-3, 0))!!.toWire()
        f.writer.failNextWrite = true
        try {
            f.coordinator.execute(MoveUnitCommand(f.actor.civID, "failed-move", 0, f.scout.id, HexCoord(-6, 0)))
            fail("Expected checkpoint failure")
        } catch (ex: AgentCommandException) { assertEquals(AgentCommandError.INTERNAL_ERROR, ex.code) }
        assertEquals(memoryBefore, memoryJson(f.coordinator))
        assertEquals(tileBefore, f.coordinator.inspectTile(f.actor.civID, HexCoord(-3, 0))!!.toWire())
        assertEquals(HexCoord(-4, 0), f.coordinator.getUnitPosition(f.actor.civID, f.scout.id))
        assertEquals(0, f.coordinator.getStateRevision())
        assertEquals(0, f.writer.writeCount)
        assertNull(f.coordinator.getReceipt("failed-move"))
        assertTrue(f.coordinator.isPaused())
    }

    @Test fun rejected_action_discards_sightings() {
        val f = Fixture(); val before = f.coordinator.saveToJson()
        val memoryBefore = memoryJson(f.coordinator)
        try {
            f.coordinator.execute(MoveUnitCommand(f.actor.civID, "invalid-move", 0, f.scout.id, HexCoord(999, 999)))
            fail("Expected action rejection")
        } catch (ex: AgentCommandException) { assertEquals(AgentCommandError.ILLEGAL_ACTION, ex.code) }
        assertEquals(before, f.coordinator.saveToJson())
        assertEquals(memoryBefore, memoryJson(f.coordinator))
        assertEquals(0, f.writer.writeCount)
        assertNull(f.coordinator.getReceipt("invalid-move"))
    }

    @Test fun stationary_inactive_observer_records_visible_tile_change() {
        val f = Fixture(); val position = HexCoord(2, 0)
        val actorBefore = f.coordinator.getCurrentCivId()
        val scoutBefore = f.coordinator.getUnitPosition(f.observer.civID, f.observerScout.id)
        assertNull(f.coordinator.inspectTile(f.observer.civID, position)!!.ownerCivId)
        f.coordinator.execute(BuyTileCommand(f.actor.civID, "buy-border", 0, f.actorCity.id, position))
        val stored = savedState(f.coordinator).mapKnowledge.get(f.observer.civID, position)!!
        assertEquals(f.actor.civID, stored.ownerCivId)
        assertEquals(f.coordinator.getStateRevision(), stored.lastSeenRevision)
        assertEquals(actorBefore, f.coordinator.getCurrentCivId())
        assertEquals(scoutBefore, f.coordinator.getUnitPosition(f.observer.civID, f.observerScout.id))
        assertTrue(savedState(f.coordinator).currentTurnReceipts.all { it.actorCivId == f.actor.civID })
    }

    @Test fun inactive_civ_remembers_permitted_opponent_turn_sighting() {
        val f = TestGame(baseRuleset = BaseRuleset.Civ_V_Vanilla).apply {
            makeHexagonalMap(7)
            gameInfo.gameParameters.baseRuleset = BaseRuleset.Civ_V_Vanilla.fullName
        }
        val ally = f.addCiv(f.ruleset.nations["Geneva"]!!, isPlayer = true)
        val observer = f.addCiv(f.ruleset.nations["Greece"]!!, isPlayer = true)
        val city = f.addCity(ally, f.getTile(0, 0))
        f.addCity(observer, f.getTile(6, 0))
        ally.addGold(1000)
        ally.diplomacyFunctions.makeCivilizationsMeet(observer)
        ally.allyCiv = observer
        f.gameInfo.currentPlayer = ally.civID
        val match = AgentMatchState(matchId = "ally-sighting", timelineId = "t",
            turnPhase = TurnPhase.AWAITING_DECISIONS)
        for (civ in listOf(ally, observer)) match.controllers[civ.civID] =
            CivilizationController(civ.civID, ControllerKind.HUMAN)
        val coordinator = MatchCoordinator(f.gameInfo, match)
        val position = HexCoord(2, 0)
        assertNull(coordinator.inspectTile(observer.civID, position))
        coordinator.execute(BuyTileCommand(ally.civID, "ally-border", 0, city.id, position))
        // Shared ally vision gained during the other civilization's turn must
        // be durable already, before the observer receives any invocation.
        val stored = savedState(coordinator).mapKnowledge.get(observer.civID, position)!!
        assertTrue(stored.detailsKnown)
        assertEquals(ally.civID, stored.ownerCivId)
        assertEquals(coordinator.getStateRevision(), stored.lastSeenRevision)
        assertEquals(ally.civID, coordinator.getCurrentCivId())
        assertEquals(stored.toWire(), savedState(MatchCoordinator.loadFromJson(coordinator.saveToJson()))
            .mapKnowledge.get(observer.civID, position)!!.toWire())
    }

    @Test fun metadata_only_notes_mail_and_controller_changes_preserve_map_memory() {
        val f = Fixture(); val memoryBefore = memoryJson(f.coordinator)
        f.coordinator.execute(UpdateNotesCommand(f.actor.civID, "plan", 0))
        assertEquals(memoryBefore, memoryJson(f.coordinator))
        f.coordinator.execute(SendDiplomaticMessageCommand(f.actor.civID, "mail",
            f.coordinator.getStateRevision(), f.observer.civID, "Hello"))
        assertEquals(memoryBefore, memoryJson(f.coordinator))
        f.coordinator.execute(SetControllerCommand(f.actor.civID, "controller",
            f.coordinator.getStateRevision(), f.observer.civID, ControllerKind.EXTERNAL_AGENT,
            f.profile.copy().apply { profileRevision++ }))
        assertEquals(memoryBefore, memoryJson(f.coordinator))
    }

    @Test fun engine_fixture_v1_copy_migrates_without_inventing_hidden_detail() {
        val f = Fixture()
        f.coordinator.execute(MoveUnitCommand(f.actor.civID, "legacy-scout", 0, f.scout.id, HexCoord(-6, 0)))
        f.coordinator.execute(UpdateNotesCommand(f.actor.civID, "keep these notes", 0))
        val before = savedState(f.coordinator)
        val current = json().fromJson(AgentBundle::class.java, f.coordinator.saveToJson())
        @Suppress("UNCHECKED_CAST")
        val legacyState = AgentJson.parse(current.matchJson) as MutableMap<String, Any?>
        legacyState.remove("formatVersion"); legacyState.remove("mapKnowledge")
        // A copy of a real serialized engine fixture, encoded as the actual
        // versionless Gdx v1 envelope. No live application save is accessed.
        val legacy = AgentJson.stringify(mapOf("gameJson" to current.gameJson,
            "matchJson" to AgentJson.stringify(legacyState)))
        val dir = Files.createTempDirectory("agent-v1-engine-copy-").toFile()
        try {
            val store = FileCheckpointStore(dir, "legacy-copy")
            store.writeBundle(legacy)
            val loaded = MatchCoordinator.loadFromJson(store.recover()!!.bundleJson)
            val after = savedState(loaded)
            assertEquals(before.matchId, after.matchId); assertEquals(before.timelineId, after.timelineId)
            assertEquals(before.stateRevision, after.stateRevision)
            assertEquals(before.privateNotes, after.privateNotes)
            assertEquals(before.controllers.keys, after.controllers.keys)
            assertEquals(before.currentTurnReceipts.map { it.actionId }, after.currentTurnReceipts.map { it.actionId })
            assertEquals(HexCoord(-6, 0), loaded.getUnitPosition(f.actor.civID, f.scout.id))
            val historical = loaded.inspectTile(f.actor.civID, HexCoord(-3, 0))!!
            assertFalse(historical.detailsKnown)
            assertNull(historical.terrain); assertNull(historical.lastSeenRevision)
            assertEquals(3, after.formatVersion)
            assertEquals(legacy, dir.resolve("legacy-copy-gen-000001.unciv-agent").readText())
        } finally { dir.deleteRecursively() }
    }
}
