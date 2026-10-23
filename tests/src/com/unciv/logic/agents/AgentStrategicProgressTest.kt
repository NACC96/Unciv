package com.unciv.logic.agents

import com.unciv.json.json
import com.unciv.logic.UncivShowableException
import com.unciv.logic.civilization.diplomacy.DeclareWarReason
import com.unciv.logic.civilization.diplomacy.WarType
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.file.Files

/** Factual progress is part of the actual observation/checkpoint boundary, not inferred from prose. */
@RunWith(BaseTestRunner::class)
class AgentStrategicProgressTest {
    private class Fixture(
        val writer: CheckpointWriter = InMemoryCheckpointWriter(),
        unitReadyAtNextStart: Boolean = false
    ) {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_Vanilla).apply {
            gameInfo.gameParameters.baseRuleset = BaseRuleset.Civ_V_Vanilla.fullName
            gameInfo.gameParameters.victoryTypes = arrayListOf("Domination")
            makeHexagonalMap(6)
        }
        val rome = game.addCiv(game.ruleset.nations["Rome"]!!, isPlayer = true)
        val greece = game.addCiv(game.ruleset.nations["Greece"]!!, isPlayer = true)
        val city = game.addCity(rome, game.getTile(-3, 0))
        val greekCity = game.addCity(greece, game.getTile(3, 0))
        val warrior = game.addUnit("Warrior", rome, game.getTile(-3, 1))
        val coordinator: MatchCoordinator

        init {
            for (civ in listOf(rome, greece)) {
                civ.tech.addTechnology("Agriculture")
                civ.tech.techsToResearch.add("Pottery")
            }
            rome.tech.addScience(7)
            city.cityConstructions.constructionQueue.clear()
            city.cityConstructions.constructionQueue.add(if (unitReadyAtNextStart) "Warrior" else "Monument")
            city.cityConstructions.addProductionPoints(if (unitReadyAtNextStart)
                game.ruleset.units.getValue("Warrior").getProductionCost(rome, city) else 9)
            game.gameInfo.turns = 104
            game.gameInfo.currentPlayer = rome.civID
            game.gameInfo.currentPlayerCiv = rome
            // An old format-3 save already admitted this turn. It has no earlier progress baseline.
            val match = AgentMatchState(matchId = "progress-test", timelineId = "timeline",
                turnSerial = 524, turnPhase = TurnPhase.AWAITING_DECISIONS)
            for (civ in listOf(rome, greece)) match.controllers[civ.civID] = CivilizationController(
                civ.civID, ControllerKind.EXTERNAL_AGENT,
                AgentProfile("p-${civ.civID}", civ.civName, "codex", "test", null, "", 1))
            match.privateNotes[greece.civID] = "SECRET_RIVAL_PLAN"
            coordinator = MatchCoordinator(game.gameInfo, match, writer)
        }
    }

    private fun Map<*, *>.objectAt(key: String): Map<*, *> {
        assertTrue("Expected object '$key' in $this", this[key] is Map<*, *>)
        return this[key] as Map<*, *>
    }

    private fun Map<*, *>.objectsAt(key: String): List<Map<*, *>> {
        assertTrue("Expected array '$key' in $this", this[key] is List<*>)
        return (this[key] as List<*>).map { it as Map<*, *> }
    }

    private fun progress(coordinator: MatchCoordinator, civId: String): Map<*, *> =
        (AgentEngineBridge(coordinator).observe(civId) as Map<*, *>).objectAt("strategicProgress")

    private fun end(coordinator: MatchCoordinator, civId: String, actionId: String): EndTurnCommand =
        EndTurnCommand(civId, actionId, coordinator.getStateRevision()).also { coordinator.execute(it) }

    private fun savedMatch(coordinator: MatchCoordinator): AgentMatchState =
        migrateAgentMatchStateJson(coordinator.serializeCommittedMatchForPublication())

    @Test fun `old admitted saves expose incomplete history and numerical ongoing work without mutating reads`() {
        val f = Fixture()
        val before = f.coordinator.saveToJson()
        val first = progress(f.coordinator, f.rome.civID)
        assertEquals(false, first["historyComplete"])
        assertEquals(104, first["trackedSinceGameTurn"])
        assertEquals(524, first["trackedSinceTurnSerial"])
        assertEquals(1, first["ownTurnOrdinal"])
        assertEquals(false, first.objectAt("incoming")["baselineAvailable"])
        assertTrue(first.objectsAt("completedTurns").isEmpty())
        val current = first.objectAt("current")
        val research = current.objectAt("research")
        assertEquals("Pottery", research["name"])
        assertEquals(7, research["storedScience"])
        assertTrue((research["cost"] as Number).toInt() > 7)
        val production = current.objectsAt("production").single()
        assertEquals(f.city.id, production["cityId"])
        assertEquals("Monument", production["construction"])
        assertEquals(9, production["storedProduction"])
        repeat(3) { assertEquals(first, progress(f.coordinator, f.rome.civID)) }
        assertEquals(before, f.coordinator.saveToJson())
        assertEquals(0, (f.writer as InMemoryCheckpointWriter).writeCount)
        assertFalse(AgentJson.stringify(first).contains("SECRET_RIVAL_PLAN"))
    }

    @Test fun `replayed actions and end turn append one summary while rejected candidates append none`() {
        val f = Fixture()
        val move = MoveUnitCommand(f.rome.civID, "travel", f.coordinator.getStateRevision(),
            f.warrior.id, HexCoord(-2, 1))
        f.coordinator.execute(move)
        f.coordinator.execute(move)
        assertTrue(progress(f.coordinator, f.rome.civID).objectsAt("completedTurns").isEmpty())
        val beforeRejected = f.coordinator.saveToJson()
        try {
            f.coordinator.execute(MoveUnitCommand(f.rome.civID, "impossible", f.coordinator.getStateRevision(),
                f.warrior.id, HexCoord(99, 99)))
            fail("Off-map movement must be rejected")
        } catch (ex: AgentCommandException) {
            assertEquals(AgentCommandError.ILLEGAL_ACTION, ex.code)
        }
        assertEquals(beforeRejected, f.coordinator.saveToJson())
        val ended = end(f.coordinator, f.rome.civID, "end-rome")
        val once = progress(f.coordinator, f.rome.civID)
        f.coordinator.execute(ended)
        assertEquals(once, progress(f.coordinator, f.rome.civID))
        val summary = once.objectsAt("completedTurns").single()
        assertEquals(1, summary["ownTurnOrdinal"])
        assertEquals(104, summary["gameTurn"])
        assertEquals(524, summary["turnSerial"])
        assertEquals(1, summary.objectAt("changes").objectAt("eventCounts")["unitMoved"])
        assertEquals(1, summary.objectAt("appliedCommandCounts")["unit.move"])
    }

    @Test fun `failed end publication rolls back the candidate progress with its game effects`() {
        val f = Fixture()
        val before = progress(f.coordinator, f.rome.civID)
        val gameBefore = f.coordinator.serializeCommittedGameForPublication()
        (f.writer as InMemoryCheckpointWriter).failNextWrite = true
        try {
            end(f.coordinator, f.rome.civID, "failed-end")
            fail("Injected checkpoint failure must reject end turn")
        } catch (ex: AgentCommandException) {
            assertEquals(AgentCommandError.INTERNAL_ERROR, ex.code)
        }
        assertEquals(before, progress(f.coordinator, f.rome.civID))
        assertEquals(gameBefore, f.coordinator.serializeCommittedGameForPublication())
        assertEquals(0, f.writer.writeCount)
    }

    @Test fun `start turn unit gain is visible before planning survives checkpoint recovery and merges once`() {
        val dir = Files.createTempDirectory("strategic-progress-").toFile()
        try {
            val store = FileCheckpointStore(dir, "progress-test")
            val f = Fixture(store, unitReadyAtNextStart = true)
            end(f.coordinator, f.rome.civID, "rome-first")
            val prior = progress(f.coordinator, f.rome.civID).objectsAt("completedTurns").single()
            assertNull(prior.objectAt("changes").objectAt("eventCounts")["unitGained"])
            end(f.coordinator, f.greece.civID, "greece-first")
            assertEquals(f.rome.civID, f.coordinator.getCurrentCivId())
            val opening = progress(f.coordinator, f.rome.civID)
            assertEquals(2, opening["ownTurnOrdinal"])
            assertEquals(1, opening.objectsAt("completedTurns").size)
            val incoming = opening.objectAt("incoming")
            assertEquals(true, incoming["baselineAvailable"])
            assertEquals(1, incoming.objectAt("eventCounts")["unitGained"])
            val gain = incoming.objectsAt("events").single { it["kind"] == "unitGained" }
            assertEquals("Warrior", gain["name"])
            assertNotEquals(f.warrior.id, gain["unitId"])
            assertFalse("A gained unit is factual, its production provenance is not inferred",
                incoming.objectsAt("events").any { it["kind"] == "unitProduced" })
            val restored = MatchCoordinator.loadFromJson(store.recover()!!.bundleJson)
            assertEquals(opening, progress(restored, f.rome.civID))
            val admittedSave = restored.saveToJson()
            repeat(3) { assertEquals(opening, progress(restored, f.rome.civID)) }
            assertEquals(admittedSave, restored.saveToJson())
            end(restored, f.rome.civID, "rome-second")
            val completed = progress(restored, f.rome.civID).objectsAt("completedTurns")
            assertEquals(2, completed.size)
            val second = completed.single { it["ownTurnOrdinal"] == 2 }
            assertEquals(1, second.objectAt("changes").objectAt("eventCounts")["unitGained"])
            end(restored, f.greece.civID, "greece-second")
            assertNull(progress(restored, f.rome.civID).objectAt("incoming").objectAt("eventCounts")["unitGained"])
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun `opponent capture and unit loss appear on next admission without inferring hidden causes`() {
        val f = Fixture()
        // Keep a peaceful route enabled here so losing the original capital does not end this
        // two-player fixture before the losing civilization receives its next observation.
        f.game.gameInfo.gameParameters.victoryTypes = arrayListOf("Time")
        f.game.addCity(f.rome, f.game.getTile(-5, -1))
        f.rome.diplomacyFunctions.makeCivilizationsMeet(f.greece)
        f.rome.getDiplomacyManager(f.greece)!!.declareWar(DeclareWarReason(WarType.DirectWar))
        f.city.health = 1
        f.warrior.health = 1
        val cityAttacker = f.game.addUnit("Modern Armor", f.greece, f.game.getTile(-2, 0))
        val unitAttacker = f.game.addUnit("Modern Armor", f.greece, f.game.getTile(-2, 2))
        val match = savedMatch(f.coordinator).apply { strategicProgress.clear() }
        val coordinator = MatchCoordinator(f.game.gameInfo, match)
        end(coordinator, f.rome.civID, "rome-before-loss")
        coordinator.execute(AttackCommand(f.greece.civID, "kill-assigned-unit", coordinator.getStateRevision(),
            unitAttacker.id, target = HexCoord(-3, 1)))
        coordinator.execute(AttackCommand(f.greece.civID, "take-capital", coordinator.getStateRevision(),
            cityAttacker.id, target = HexCoord(-3, 0)))
        val conquest = coordinator.getPendingDecisions(f.greece.civID).single { it.decisionType == "conquest-disposition" }
        coordinator.execute(ResolveConquestCommand(f.greece.civID, "keep-capital", coordinator.getStateRevision(),
            conquest.decisionId, ConquestDisposition.PUPPET))
        end(coordinator, f.greece.civID, "greece-after-loss")
        val incoming = progress(coordinator, f.rome.civID).objectAt("incoming")
        val losses = incoming.objectsAt("events")
        assertEquals(1, incoming.objectAt("eventCounts")["originalCapitalLost"])
        assertEquals(f.city.id, losses.single { it["kind"] == "originalCapitalLost" }["cityId"])
        assertEquals(f.rome.civID, losses.single { it["kind"] == "originalCapitalLost" }["foundingCivId"])
        assertEquals(f.warrior.id, losses.single { it["kind"] == "unitLost" }["unitId"])
        assertTrue(losses.none { "cause" in it || "attacker" in it || "ownerCivId" in it })
        assertEquals(1, progress(coordinator, f.greece.civID).objectsAt("completedTurns").single()
            .objectAt("changes").objectAt("eventCounts")["originalCapitalGained"])
    }

    @Test fun `queue switches and standing orders are distinct from acquired assets and ongoing numerical work`() {
        val f = Fixture()
        f.coordinator.execute(SelectResearchCommand(f.rome.civID, "change-research", f.coordinator.getStateRevision(),
            "Mining", false, false))
        f.coordinator.execute(CityProductionCommand(f.rome.civID, "change-production", f.coordinator.getStateRevision(),
            f.city.id, ProductionOp.ADD_TOP, "Warrior"))
        f.coordinator.execute(UnitOrderCommand(f.rome.civID, "fortify", f.coordinator.getStateRevision(),
            f.warrior.id, UnitOrder.FORTIFY))
        f.coordinator.execute(UpdateNotesCommand(f.rome.civID, "The army is ready in my prose.", 0))
        end(f.coordinator, f.rome.civID, "end-changed-plan")
        val completed = progress(f.coordinator, f.rome.civID).objectsAt("completedTurns").single()
        val changes = completed.objectAt("changes")
        val counts = changes.objectAt("eventCounts")
        assertEquals(1, counts["researchSelectionChanged"])
        assertEquals(1, counts["productionSelectionChanged"])
        assertNull(counts["technologyGained"])
        assertNull(counts["buildingGained"])
        assertNull(counts["unitGained"])
        assertNull(counts["unitMoved"])
        assertEquals(1, completed.objectAt("appliedCommandCounts")["unit.order"])
        val work = changes.objectsAt("events").single { it["kind"] == "productionWorkChanged" }
        assertEquals("Warrior", work["name"])
        assertEquals(0, work["beforeNumber"])
        assertTrue((work["afterNumber"] as Number).toInt() > 0)
        val research = changes.objectsAt("events").single { it["kind"] == "researchWorkChanged" }
        assertEquals("Mining", research["name"])
        assertEquals(0, research["beforeNumber"])
        assertTrue((research["afterNumber"] as Number).toInt() > 0)
    }

    @Test fun `completed window fills honestly remains bounded and forked checkpoints retain their own history`() {
        val f = Fixture()
        end(f.coordinator, f.rome.civID, "rome-1")
        val forkPoint = f.coordinator.saveToJson()
        for (round in 2..5) {
            end(f.coordinator, f.greece.civID, "greece-$round")
            end(f.coordinator, f.rome.civID, "rome-$round")
        }
        val current = progress(f.coordinator, f.rome.civID)
        assertEquals(true, current["historyComplete"])
        assertEquals(listOf(3, 4, 5), current.objectsAt("completedTurns").map { it["ownTurnOrdinal"] })
        val oldBundle = json().fromJson(AgentBundle::class.java, forkPoint)
        val forkMatch = migrateAgentMatchStateJson(oldBundle.matchJson).apply {
            timelineId = "forked-timeline"
            currentTurnReceipts.forEach { it.timelineId = timelineId }
        }
        oldBundle.matchJson = AgentOrdinarySave.encode(forkMatch)
        val fork = MatchCoordinator.loadFromJson(json().apply { setUsePrototypes(false) }.toJson(oldBundle))
        val historical = progress(fork, f.rome.civID)
        assertEquals(false, historical["historyComplete"])
        assertEquals(listOf(1), historical.objectsAt("completedTurns").map { it["ownTurnOrdinal"] })
        assertEquals(104, fork.getGameTurns())
        end(fork, f.greece.civID, "fork-greece")
        end(fork, f.rome.civID, "fork-rome")
        assertEquals(listOf(1, 2), progress(fork, f.rome.civID).objectsAt("completedTurns").map { it["ownTurnOrdinal"] })
        assertEquals(current, progress(f.coordinator, f.rome.civID))
    }

    @Test fun `truncated details retain exact change counts and do not duplicate full own unit observations`() {
        val f = Fixture()
        val tiles = f.game.tileMap.values.filter { !it.isCityCenter() && it.militaryUnit == null && it.getOwner() != f.greece }.take(20)
        assertEquals(20, tiles.size)
        for (tile in tiles) f.game.addUnit("Warrior", f.rome, tile).health = 50
        f.rome.addGold(10000) // Isolate healing from bankruptcy disbands in this large-army fixture.
        val match = savedMatch(f.coordinator).apply { strategicProgress.clear() }
        val coordinator = MatchCoordinator(f.game.gameInfo, match)
        end(coordinator, f.rome.civID, "heal-many")
        val observed = progress(coordinator, f.rome.civID)
        val changes = observed.objectsAt("completedTurns").single().objectAt("changes")
        assertEquals(true, changes["truncated"])
        assertEquals(12, changes.objectsAt("events").size)
        assertEquals(20, changes.objectAt("eventCounts")["unitHealthChanged"])
        assertFalse(observed.objectAt("current").containsKey("units"))
        assertTrue("Bounded progress stays comfortably within the 96 KiB observation budget",
            AgentJson.stringify(observed).toByteArray(Charsets.UTF_8).size < 24 * 1024)
    }

    @Test fun `progress candidates and returned observation objects share no mutable history`() {
        val f = Fixture()
        end(f.coordinator, f.rome.civID, "first-end")
        end(f.coordinator, f.greece.civID, "other-end")
        val state = savedMatch(f.coordinator)
        val before = AgentOrdinarySave.encode(state)
        val copy = state.deepCopy()
        copy.victoryProgress.getValue(f.rome.civID).routes.getValue("Domination").unchangedSinceGameTurn = 0
        copy.victoryProgress.getValue(f.greece.civID).routes.clear()
        val candidate = copy.strategicProgress.getValue(f.rome.civID)
        candidate.admission!!.units[0].health = -1
        candidate.previousEnd!!.cities[0].production["Monument"] = -1
        candidate.incoming.events.add(StrategicProgressEvent("invented"))
        candidate.completedTurns[0].changes.events[0].kind = "invented"
        candidate.completedTurns[0].appliedCommandCounts["invented"] = 99
        candidate.completedTurns.clear()
        assertEquals(before, AgentOrdinarySave.encode(state))
        assertEquals(AgentOrdinarySave.encode(state).contains("invented"), false)

        val committedBefore = f.coordinator.serializeCommittedMatchForPublication()
        val detached = buildStrategicProgress(
            f.coordinator.deserializeForPublication(f.coordinator.serializeCommittedGameForPublication()), state, f.rome.civID)
        detached.completedTurns[0].changes.events[0].kind = "changed by reader"
        detached.completedTurns[0].appliedCommandCounts["reader"] = 1
        detached.incoming.eventCounts["reader"] = 1
        assertEquals(before, AgentOrdinarySave.encode(state))
        assertEquals(committedBefore, f.coordinator.serializeCommittedMatchForPublication())
    }

    @Test fun `ordinary saves reject unknown progress owners and legacy saves omit history without losing notes`() {
        val f = Fixture()
        val game = f.coordinator.deserializeForPublication(f.coordinator.serializeCommittedGameForPublication())
        val invalid = savedMatch(f.coordinator).apply { strategicProgress["unknown-civilization"] = AgentStrategicHistory() }
        game.agentMatchStateJson = AgentOrdinarySave.encode(invalid)
        try {
            AgentOrdinarySave.read(game)
            fail("Progress must belong to a civilization in this saved world")
        } catch (ex: UncivShowableException) {
            assertTrue(ex.message.orEmpty().contains("Strategic progress"))
        }
        val bundle = json().fromJson(AgentBundle::class.java, f.coordinator.saveToJson())
        @Suppress("UNCHECKED_CAST")
        val legacy = AgentJson.parse(bundle.matchJson) as MutableMap<String, Any?>
        legacy.remove("strategicProgress")
        bundle.matchJson = AgentJson.stringify(legacy)
        val restored = MatchCoordinator.loadFromJson(json().apply { setUsePrototypes(false) }.toJson(bundle))
        assertEquals("SECRET_RIVAL_PLAN", restored.getNotes(f.greece.civID).first)
        val unknownHistory = progress(restored, f.rome.civID)
        assertEquals(false, unknownHistory["historyComplete"])
        assertEquals(false, unknownHistory.objectAt("incoming")["baselineAvailable"])
        assertTrue(unknownHistory.objectsAt("completedTurns").isEmpty())
        assertEquals(AGENT_MATCH_FORMAT_VERSION, savedMatch(restored).formatVersion)
    }
}
