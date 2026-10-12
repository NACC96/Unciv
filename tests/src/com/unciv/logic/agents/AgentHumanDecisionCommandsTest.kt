package com.unciv.logic.agents

import com.unciv.Constants
import com.unciv.json.json
import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.CivFlags
import com.unciv.logic.civilization.PopupAlert
import com.unciv.logic.civilization.managers.ReligionState
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.stats.Stat
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class AgentHumanDecisionCommandsTest {
    private class Fixture(spectator: Boolean = true, humans: Boolean = false, agentFirst: Boolean = false) {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_Vanilla)
        val writer = InMemoryCheckpointWriter()
        val rome: String
        val greece: String
        val cityId: String
        val firstAgent: String?
        val coordinator: MatchCoordinator
        init {
            game.gameInfo.gameParameters.baseRuleset = BaseRuleset.Civ_V_Vanilla.fullName
            game.makeHexagonalMap(5)
            val r = game.addCiv(game.ruleset.nations["Rome"]!!, isPlayer = true)
            val g = game.addCiv(game.ruleset.nations["Greece"]!!, isPlayer = true)
            rome = r.civID; greece = g.civID
            val city = game.addCity(r, game.getTile(0, 0))
            cityId = city.id
            city.cityConstructions.constructionQueue.clear()
            game.addUnit("Warrior", g, game.getTile(3, 0))
            val agent = if (agentFirst) game.addCiv(game.ruleset.nations["Egypt"]!!, isPlayer = true) else null
            agent?.let { game.addUnit("Warrior", it, game.getTile(-3, 0)) }
            firstAgent = agent?.civID
            r.addGold(10000)
            r.greatPeople.freeGreatPeople = 1
            r.policies.shouldOpenPolicyPicker = true
            r.addFlag(CivFlags.ShowDiplomaticVotingResults.name, 0)
            r.popupAlerts.clear(); g.popupAlerts.clear()
            r.popupAlerts.add(PopupAlert(AlertType.StartIntro, ""))
            r.popupAlerts.add(PopupAlert(AlertType.TechResearched, "Agriculture"))
            g.popupAlerts.add(PopupAlert(AlertType.StartIntro, ""))
            game.gameInfo.currentPlayer = firstAgent ?: rome
            game.gameInfo.currentPlayerCiv = agent ?: r
            val s = if (spectator) game.addCiv(game.ruleset.nations[Constants.spectator]!!, isPlayer = true) else null
            val match = AgentMatchState(matchId = "human-decisions", timelineId = "t",
                turnPhase = TurnPhase.AWAITING_DECISIONS,
                localControlCivId = s?.civID, localViewingCivId = s?.civID)
            match.controllers[rome] = CivilizationController(rome,
                if (humans) ControllerKind.HUMAN else ControllerKind.EXTERNAL_AGENT,
                if (humans) null else AgentProfile.defaultFor("Rome"))
            match.controllers[greece] = CivilizationController(greece,
                if (humans) ControllerKind.HUMAN else ControllerKind.EXTERNAL_AGENT,
                if (humans) null else AgentProfile.defaultFor("Greece"))
            agent?.let { match.controllers[it.civID] = CivilizationController(it.civID,
                ControllerKind.EXTERNAL_AGENT, AgentProfile.defaultFor("Egypt")) }
            coordinator = MatchCoordinator(game.gameInfo, match, writer)
        }
        fun snapshot(): GameInfo = coordinator.deserializeForPublication(coordinator.serializeCommittedGameForPublication())
        fun state(): AgentMatchState = json().fromJson(AgentMatchState::class.java, coordinator.serializeCommittedMatchForPublication())
        fun play() = coordinator.applyNationChange(rome, NationControlChange.PLAY_AS, null)
        fun reject(block: () -> Unit): AgentCommandException {
            try { block(); fail("Expected rejection") } catch (error: AgentCommandException) { return error }
            throw AssertionError("unreachable")
        }
    }

    @Test fun `play as atomically consumes only target intro and survives publications and restore`() {
        val f = Fixture(); f.play()
        val committed = f.snapshot()
        assertFalse(committed.getCivilization(f.rome).popupAlerts.any { it.type == AlertType.StartIntro })
        assertTrue(committed.getCivilization(f.greece).popupAlerts.any { it.type == AlertType.StartIntro })
        assertTrue(committed.getCivilization(f.rome).popupAlerts.any { it.type == AlertType.TechResearched })
        assertTrue(f.game.gameInfo.getCivilization(f.rome).popupAlerts.any { it.type == AlertType.StartIntro })
        f.coordinator.execute(CityCitizensCommand(f.rome, "toggle", f.coordinator.getStateRevision(), f.cityId, CitizenOp.TOGGLE_AVOID_GROWTH))
        val restored = MatchCoordinator.loadFromJson(f.coordinator.saveToJson())
        assertFalse(restored.deserializeForPublication(restored.serializeCommittedGameForPublication())
            .getCivilization(f.rome).popupAlerts.any { it.type == AlertType.StartIntro })
        assertEquals(f.rome, f.state().localViewingCivId)
    }

    @Test fun `failed takeover checkpoint preserves intro controller and viewer`() {
        val f = Fixture(); val before = f.state()
        f.writer.failNextWrite = true
        f.reject { f.play() }
        assertTrue(f.snapshot().getCivilization(f.rome).popupAlerts.any { it.type == AlertType.StartIntro })
        assertEquals(ControllerKind.EXTERNAL_AGENT, f.coordinator.getController(f.rome)!!.kind)
        assertEquals(before.localViewingCivId, f.state().localViewingCivId)
        assertEquals(before.stateRevision, f.coordinator.getStateRevision())
    }

    @Test fun `informational dismissal and flags are durable replayable human commands`() {
        val f = Fixture(); f.play()
        val rev = f.coordinator.getStateRevision()
        val dismiss = DismissAlertCommand(f.rome, "dismiss", rev, AlertType.TechResearched, "Agriculture")
        val receipt = (f.coordinator.execute(dismiss) as HumanPresentationResult).receipt
        assertEquals(receipt.afterRevision, (f.coordinator.execute(dismiss) as HumanPresentationResult).receipt.afterRevision)
        f.coordinator.execute(DismissDiplomaticVoteResultsCommand(f.rome, "vote-results", f.coordinator.getStateRevision()))
        f.coordinator.execute(MarkPolicyPickerOpenedCommand(f.rome, "policy-opened", f.coordinator.getStateRevision()))
        val civ = f.snapshot().getCivilization(f.rome)
        assertFalse(civ.popupAlerts.any { it.type == AlertType.TechResearched })
        assertEquals(-1, civ.flagsCountdown[CivFlags.ShowDiplomaticVotingResults.name])
        assertFalse(civ.policies.shouldOpenPolicyPicker)
        assertTrue(f.game.gameInfo.getCivilization(f.rome).policies.shouldOpenPolicyPicker)
        assertEquals(AgentCommandError.ACTION_ID_CONFLICT, f.reject {
            f.coordinator.execute(DismissAlertCommand(f.rome, "dismiss", rev, AlertType.GoldenAge, ""))
        }.code)
    }

    @Test fun `generic dismissal cannot discard decisions and checkpoint failure preserves flags`() {
        val f = Fixture(); f.play()
        assertEquals(AgentCommandError.ILLEGAL_ACTION, f.reject {
            f.coordinator.execute(DismissAlertCommand(f.rome, "discard-conquest", f.coordinator.getStateRevision(), AlertType.CityConquered, "x"))
        }.code)
        f.writer.failNextWrite = true
        f.reject { f.coordinator.execute(MarkPolicyPickerOpenedCommand(f.rome, "failed-policy", f.coordinator.getStateRevision())) }
        assertTrue(f.snapshot().getCivilization(f.rome).policies.shouldOpenPolicyPicker)
    }

    @Test fun `free great person grant consumes entitlement once and rejects foreign turn`() {
        val f = Fixture(); f.play()
        val civ = f.snapshot().getCivilization(f.rome)
        val name = civ.greatPeople.getFreeGreatPersonOptions().first().name
        val command = ChooseFreeGreatPersonCommand(f.rome, "free-person", f.coordinator.getStateRevision(), name)
        val granted = f.coordinator.execute(command) as FreeGreatPersonChosenResult
        assertEquals(granted.unitId, (f.coordinator.execute(command) as FreeGreatPersonChosenResult).unitId)
        val after = f.snapshot().getCivilization(f.rome)
        assertEquals(0, after.greatPeople.freeGreatPeople)
        assertEquals(1, after.units.getCivUnits().count { it.name == name })
        assertEquals(1, f.game.gameInfo.getCivilization(f.rome).greatPeople.freeGreatPeople)
        assertEquals(AgentCommandError.NOT_YOUR_TURN, f.reject {
            f.coordinator.execute(ChooseFreeGreatPersonCommand(f.greece, "foreign-person", f.coordinator.getStateRevision(), name))
        }.code)
    }

    @Test fun `relative growth toggles cancel and purchase removes only captured matching queue entry`() {
        val f = Fixture(); f.play()
        repeat(2) { f.coordinator.execute(CityCitizensCommand(f.rome, "toggle-$it", f.coordinator.getStateRevision(), f.cityId, CitizenOp.TOGGLE_AVOID_GROWTH)) }
        assertFalse(f.snapshot().getCivilization(f.rome).cities.first().avoidGrowth)
        for (name in listOf("Monument", "Warrior")) f.coordinator.execute(CityProductionCommand(f.rome, "queue-$name", f.coordinator.getStateRevision(), f.cityId, ProductionOp.ADD_BOTTOM, name))
        val queue = f.snapshot().getCivilization(f.rome).cities.first().cityConstructions.constructionQueue
        val index = queue.indexOf("Warrior")
        assertEquals(AgentCommandError.STALE_STATE, f.reject {
            f.coordinator.execute(CityProductionCommand(f.rome, "wrong-slot", f.coordinator.getStateRevision(), f.cityId, ProductionOp.PURCHASE, "Monument", index))
        }.code)
        f.coordinator.execute(CityProductionCommand(f.rome, "buy-warrior", f.coordinator.getStateRevision(), f.cityId, ProductionOp.PURCHASE, "Warrior", index, purchaseStat = Stat.Gold))
        assertFalse(f.snapshot().getCivilization(f.rome).cities.first().cityConstructions.constructionQueue.contains("Warrior"))
        assertTrue(f.snapshot().getCivilization(f.rome).cities.first().cityConstructions.constructionQueue.contains("Monument"))
    }

    @Test fun `native local human handoff updates saved principal and viewer while spectator view stays explicit`() {
        val local = Fixture(spectator = false, humans = true)
        local.coordinator.execute(EndTurnCommand(local.rome, "handoff", local.coordinator.getStateRevision()))
        assertEquals(local.greece, local.coordinator.getCurrentCivId())
        assertEquals(local.greece, local.state().localControlCivId)
        assertEquals(local.greece, local.state().localViewingCivId)
        val spectator = Fixture(humans = true)
        val before = spectator.state()
        spectator.coordinator.execute(EndTurnCommand(spectator.rome, "spectator-handoff", spectator.coordinator.getStateRevision()))
        assertEquals(before.localControlCivId, spectator.state().localControlCivId)
        assertEquals(before.localViewingCivId, spectator.state().localViewingCivId)
    }

    @Test fun `agent first native match chooses human principal and moves viewer at human turn`() {
        val f = Fixture(spectator = false, humans = true, agentFirst = true)
        assertEquals(f.rome, f.state().localControlCivId)
        f.coordinator.execute(EndTurnCommand(f.firstAgent!!, "agent-ended", f.coordinator.getStateRevision()))
        assertEquals(f.rome, f.coordinator.getCurrentCivId())
        assertEquals(f.rome, f.state().localViewingCivId)
    }

    @Test fun `native handoff checkpoint failure retains prior principal and old save viewer is repaired`() {
        val f = Fixture(spectator = false, humans = true)
        val before = f.state(); val serial = f.coordinator.getTurnSerial()
        f.writer.failNextWrite = true
        f.reject { f.coordinator.execute(EndTurnCommand(f.rome, "failed-handoff", f.coordinator.getStateRevision())) }
        assertEquals(f.rome, f.coordinator.getCurrentCivId())
        assertEquals(before.localControlCivId, f.state().localControlCivId)
        assertEquals(before.localViewingCivId, f.state().localViewingCivId)
        assertEquals(serial, f.coordinator.getTurnSerial())
        f.coordinator.retryPausedMatch()
        f.coordinator.execute(EndTurnCommand(f.rome, "handoff", f.coordinator.getStateRevision()))
        val stale = f.state().apply { localControlCivId = f.rome; localViewingCivId = f.rome }
        assertEquals(f.greece, NationControl.savedViewerCivId(f.snapshot(), stale))
        val restored = MatchCoordinator(f.snapshot(), stale)
        assertEquals(f.greece, restored.getLocalControlCivId())
        assertEquals(f.greece, restored.getLocalViewingCivId())
    }

    @Test fun `failed free person placement preserves entitlement and committed world`() {
        val f = Fixture(); f.play()
        val crowded = f.snapshot(); val civ = crowded.getCivilization(f.rome)
        val name = civ.greatPeople.getFreeGreatPersonOptions().first { it.isCivilian() }.name
        crowded.civilizations.flatMap { it.units.getCivUnits().toList() }.forEach { it.destroy() }
        for (tile in crowded.tileMap.values) f.game.addUnit("Worker", civ, tile)
        val coordinator = MatchCoordinator(crowded, f.state())
        val before = coordinator.serializeCommittedGameForPublication()
        assertEquals(AgentCommandError.ILLEGAL_ACTION, f.reject {
            coordinator.execute(ChooseFreeGreatPersonCommand(f.rome, "no-space", coordinator.getStateRevision(), name))
        }.code)
        assertEquals(before, coordinator.serializeCommittedGameForPublication())
        assertEquals(1, coordinator.deserializeForPublication(before).getCivilization(f.rome).greatPeople.freeGreatPeople)
    }

    @Test fun `faith purchase debits faith only removes captured slot and replays without another debit`() {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_Vanilla)
        game.gameInfo.gameParameters.baseRuleset = BaseRuleset.Civ_V_Vanilla.fullName
        game.makeHexagonalMap(4)
        val building = game.createBuilding("Can be purchased for [120] [Faith] [in all cities]").apply { cost = 100 }
        val originalRuleset = RulesetCache[BaseRuleset.Civ_V_Vanilla.fullName]!!
        try {
            RulesetCache[BaseRuleset.Civ_V_Vanilla.fullName] = game.ruleset
            val roman = game.addCiv(game.ruleset.nations["Rome"]!!, isPlayer = true)
            roman.addGold(5000); roman.religionManager.storedFaith = 1000
            val city = game.addCity(roman, game.getTile(0, 0))
            city.cityConstructions.constructionQueue.clear()
            city.cityConstructions.constructionQueue.addAll(listOf("Warrior", building.name))
            game.gameInfo.currentPlayer = roman.civID; game.gameInfo.currentPlayerCiv = roman
            val match = AgentMatchState(matchId = "faith-purchase", timelineId = "t", turnPhase = TurnPhase.AWAITING_DECISIONS)
            match.controllers[roman.civID] = CivilizationController(roman.civID, ControllerKind.HUMAN)
            val coordinator = MatchCoordinator(game.gameInfo, match)
            val before = coordinator.deserializeForPublication(coordinator.serializeCommittedGameForPublication())
            val beforeCity = before.getCivilization(roman.civID).cities.first()
            val cost = before.ruleset.buildings[building.name]!!.getStatBuyCost(beforeCity, Stat.Faith)!!
            val command = CityProductionCommand(roman.civID, "faith-buy", coordinator.getStateRevision(), city.id,
                ProductionOp.PURCHASE, building.name, 1, purchaseStat = Stat.Faith)
            coordinator.execute(command); coordinator.execute(command)
            val after = coordinator.deserializeForPublication(coordinator.serializeCommittedGameForPublication())
            val afterCiv = after.getCivilization(roman.civID)
            val afterCity = afterCiv.cities.first()
            assertEquals(5000, afterCiv.gold)
            assertEquals(1000 - cost, afterCiv.religionManager.storedFaith)
            assertTrue(afterCity.cityConstructions.isBuilt(building.name))
            assertEquals(listOf("Warrior"), afterCity.cityConstructions.constructionQueue)
        } finally { RulesetCache[BaseRuleset.Civ_V_Vanilla.fullName] = originalRuleset }
    }

    @Test fun `unsupported religion decision cannot be bypassed through coordinator end turn`() {
        val f = Fixture(); val raw = f.snapshot()
        val religion = raw.getCivilization(f.rome).religionManager
        religion.javaClass.getDeclaredField("religionState").apply { isAccessible = true }
            .set(religion, ReligionState.FoundingReligion)
        val coordinator = MatchCoordinator(raw, f.state())
        assertEquals(AgentCommandError.DECISIONS_PENDING, f.reject {
            coordinator.execute(EndTurnCommand(f.rome, "unsupported-religion", coordinator.getStateRevision()))
        }.code)
    }
}
