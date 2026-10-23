package com.unciv.logic.agents

import com.unciv.json.json
import com.unciv.logic.civilization.diplomacy.DeclareWarReason
import com.unciv.logic.civilization.diplomacy.WarType
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class AgentVictoryProgressTest {
    private class Fixture(captureReady: Boolean = false,
        victoryTypes: List<String> = listOf("Domination", "Scientific"), timeLimit: Int = 500) {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_Vanilla).apply {
            gameInfo.gameParameters.baseRuleset = BaseRuleset.Civ_V_Vanilla.fullName
            gameInfo.gameParameters.victoryTypes = ArrayList(victoryTypes)
            gameInfo.gameParameters.maxTurns = timeLimit
            makeHexagonalMap(6)
        }
        val rome = game.addCiv(game.ruleset.nations.getValue("Rome"), isPlayer = true)
        val greece = game.addCiv(game.ruleset.nations.getValue("Greece"), isPlayer = true)
        val capital = game.addCity(rome, game.getTile(-3, 0))
        val greekCapital = game.addCity(greece, game.getTile(3, 0))
        val attackerId: Int
        val writer = InMemoryCheckpointWriter()
        val coordinator: MatchCoordinator
        init {
            for (civ in listOf(rome, greece)) {
                civ.tech.addTechnology("Agriculture")
                civ.tech.techsToResearch.add("Pottery")
            }
            if (captureReady) {
                game.addCity(rome, game.getTile(-5, 0))
                rome.diplomacyFunctions.makeCivilizationsMeet(greece)
                rome.getDiplomacyManager(greece)!!.declareWar(DeclareWarReason(WarType.DirectWar))
                capital.health = 1
            }
            attackerId = game.addUnit("Modern Armor", greece,
                game.getTile(if (captureReady) -2 else 3, if (captureReady) 0 else 1)).id
            game.gameInfo.turns = 139
            game.gameInfo.currentPlayer = rome.civID
            game.gameInfo.currentPlayerCiv = rome
            val match = AgentMatchState(matchId = "victory-progress", timelineId = "timeline",
                turnSerial = 418, turnPhase = TurnPhase.AWAITING_DECISIONS)
            for (civ in listOf(rome, greece)) match.controllers[civ.civID] = CivilizationController(
                civ.civID, ControllerKind.EXTERNAL_AGENT,
                AgentProfile("p-${civ.civID}", civ.civName, "codex", "test", null, "", 1))
            coordinator = MatchCoordinator(game.gameInfo, match, writer)
        }
    }

    private fun Map<*, *>.objectAt(key: String): Map<*, *> {
        assertTrue("Expected object '$key' in $this", this[key] is Map<*, *>)
        return this[key] as Map<*, *>
    }
    private fun progress(c: MatchCoordinator, civ: String, route: String = "Domination"): Map<*, *> {
        val victory = (AgentEngineBridge(c).observe(civ) as Map<*, *>).objectAt("victory")
        val available = (victory["available"] as List<*>).map { it as Map<*, *> }
        return available.single { it["name"] == route }.objectAt("progress")
    }
    private fun end(c: MatchCoordinator, civ: String, id: String) =
        c.execute(EndTurnCommand(civ, id, c.getStateRevision()))

    @Test fun `historical saves start an honest interval and reads notes and preparation cannot reset it`() {
        val f = Fixture()
        val first = progress(f.coordinator, f.rome.civID)
        assertEquals("ORIGINAL_CAPITALS", first["metric"])
        assertEquals(1, first["current"])
        assertEquals(2, first["target"])
        assertEquals(139, first["trackedSinceGameTurn"])
        assertEquals(139, first["unchangedSinceGameTurn"])
        assertNull(first["lastChangedGameTurn"])
        val saved = f.coordinator.saveToJson()
        repeat(3) { assertEquals(first, progress(f.coordinator, f.rome.civID)) }
        assertEquals(saved, f.coordinator.saveToJson())
        assertEquals(0, f.writer.writeCount)
        repeat(3) { round ->
            f.coordinator.execute(UpdateNotesCommand(f.rome.civID, "New deadline ${200 + round}", round))
            end(f.coordinator, f.rome.civID, "rome-$round")
            end(f.coordinator, f.greece.civID, "greece-$round")
        }
        assertEquals(142, f.coordinator.getGameTurns())
        assertEquals(first, progress(f.coordinator, f.rome.civID))
        val restored = MatchCoordinator.loadFromJson(f.coordinator.saveToJson())
        assertEquals(first, progress(restored, f.rome.civID))
        val scientific = progress(restored, f.rome.civID, "Scientific")
        assertEquals("COMPLETED_MILESTONES", scientific["metric"])
        assertEquals(0, scientific["current"])
        assertTrue((scientific["target"] as Int) > 0)
    }

    @Test fun `capital gain and loss update both owners only when the real capture commits`() {
        val f = Fixture(captureReady = true)
        end(f.coordinator, f.rome.civID, "before-capture")
        val beforeRome = progress(f.coordinator, f.rome.civID)
        val beforeGreece = progress(f.coordinator, f.greece.civID)
        val attack = AttackCommand(f.greece.civID, "capture", f.coordinator.getStateRevision(),
            f.attackerId, target = HexCoord(-3, 0))
        f.coordinator.execute(attack)
        val pending = f.coordinator.getPendingDecisions(f.greece.civID).single { it.decisionType == "conquest-disposition" }
        assertEquals(beforeRome, progress(f.coordinator, f.rome.civID))
        val keep = ResolveConquestCommand(f.greece.civID, "keep-capital", f.coordinator.getStateRevision(),
            pending.decisionId, ConquestDisposition.PUPPET)
        f.writer.failNextWrite = true
        try {
            f.coordinator.execute(keep)
            fail("Injected publication failure must reject capture")
        } catch (ex: AgentCommandException) {
            assertEquals(AgentCommandError.INTERNAL_ERROR, ex.code)
        }
        assertEquals(beforeRome, progress(f.coordinator, f.rome.civID))
        assertEquals(beforeGreece, progress(f.coordinator, f.greece.civID))
        f.coordinator.retryPausedMatch()
        f.coordinator.execute(keep)
        val lost = progress(f.coordinator, f.rome.civID)
        val gained = progress(f.coordinator, f.greece.civID)
        assertEquals(0, lost["current"])
        assertEquals(2, gained["current"])
        for (changed in listOf(lost, gained)) {
            assertEquals(139, changed["trackedSinceGameTurn"])
            assertEquals(f.coordinator.getGameTurns(), changed["lastChangedGameTurn"])
            assertEquals(f.coordinator.getGameTurns(), changed["unchangedSinceGameTurn"])
        }
        f.coordinator.execute(keep)
        assertEquals(gained, progress(f.coordinator, f.greece.civID))
        assertEquals(gained, progress(MatchCoordinator.loadFromJson(f.coordinator.saveToJson()), f.greece.civID))
    }

    @Test fun `a save without victory history keeps notes and initializes only its observed round`() {
        val f = Fixture()
        f.coordinator.execute(UpdateNotesCommand(f.rome.civID, "Historical campaign notes", 0))
        end(f.coordinator, f.rome.civID, "old-rome")
        end(f.coordinator, f.greece.civID, "old-greece")
        val bundle = json().fromJson(AgentBundle::class.java, f.coordinator.saveToJson())
        @Suppress("UNCHECKED_CAST")
        val match = AgentJson.parse(bundle.matchJson) as MutableMap<String, Any?>
        match.remove("victoryProgress")
        bundle.matchJson = AgentJson.stringify(match)
        val restored = MatchCoordinator.loadFromJson(json().apply { setUsePrototypes(false) }.toJson(bundle))
        assertEquals("Historical campaign notes", restored.getNotes(f.rome.civID).first)
        val progress = progress(restored, f.rome.civID)
        assertEquals(140, progress["trackedSinceGameTurn"])
        assertEquals(140, progress["unchangedSinceGameTurn"])
        assertNull(progress["lastChangedGameTurn"])
    }

    @Test fun `a committed peaceful milestone changes its canonical counter and retains its clocks on reload`() {
        val f = Fixture(victoryTypes = listOf("Time"), timeLimit = 140)
        for (civ in listOf(f.rome, f.greece)) assertEquals(0, progress(f.coordinator, civ.civID, "Time")["current"])
        end(f.coordinator, f.rome.civID, "time-rome")
        end(f.coordinator, f.greece.civID, "time-greece")
        assertEquals(140, f.coordinator.getGameTurns())
        val game = f.coordinator.deserializeForPublication(f.coordinator.serializeCommittedGameForPublication())
        val winner = game.civilizations.maxByOrNull { it.calculateTotalScore() }!!
        assertEquals("Time", winner.victoryManager.getVictoryTypeAchieved())
        val changed = progress(f.coordinator, winner.civID, "Time")
        assertEquals("COMPLETED_MILESTONES", changed["metric"])
        assertEquals(1, changed["current"])
        assertEquals(1, changed["target"])
        assertEquals(139, changed["trackedSinceGameTurn"])
        assertEquals(140, changed["unchangedSinceGameTurn"])
        assertEquals(140, changed["lastChangedGameTurn"])
        val saved = f.coordinator.saveToJson()
        repeat(3) { assertEquals(changed, progress(f.coordinator, winner.civID, "Time")) }
        assertEquals(saved, f.coordinator.saveToJson())
        assertEquals(changed, progress(MatchCoordinator.loadFromJson(saved), winner.civID, "Time"))
    }
}
