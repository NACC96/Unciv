package com.unciv.logic.agents

import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class AgentCityStatePeaceTest {
    private class Fixture(blocked: Boolean = false) {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_Vanilla)
        val writer = InMemoryCheckpointWriter()
        val actor: String
        val target: String
        val major: String
        val coordinator: MatchCoordinator
        init {
            game.gameInfo.gameParameters.baseRuleset = BaseRuleset.Civ_V_Vanilla.fullName
            game.makeHexagonalMap(5)
            val rome = game.addCiv(game.ruleset.nations["Rome"]!!, isPlayer = true)
            val geneva = game.addCiv(game.ruleset.nations["Geneva"]!!)
            val greece = game.addCiv(game.ruleset.nations["Greece"]!!, isPlayer = true)
            actor = rome.civID; target = geneva.civID; major = greece.civID
            game.addCity(rome, game.getTile(-2, 0))
            game.addCity(geneva, game.getTile(2, 0))
            game.addUnit("Warrior", greece, game.getTile(0, 4))
            rome.diplomacyFunctions.makeCivilizationsMeet(geneva)
            rome.diplomacyFunctions.makeCivilizationsMeet(greece)
            for (other in listOf(geneva, greece)) {
                rome.getDiplomacyManager(other)!!.diplomaticStatus = DiplomaticStatus.War
                other.getDiplomacyManager(rome)!!.diplomaticStatus = DiplomaticStatus.War
            }
            if (blocked) geneva.getDiplomacyManager(rome)!!.setFlag(DiplomacyFlags.DeclaredWar, 10)
            game.gameInfo.currentPlayer = actor; game.gameInfo.currentPlayerCiv = rome
            val match = AgentMatchState(matchId = "city-state-peace", timelineId = "t", turnPhase = TurnPhase.AWAITING_DECISIONS)
            match.controllers[actor] = CivilizationController(actor, ControllerKind.HUMAN)
            match.controllers[target] = CivilizationController(target, ControllerKind.BUILTIN_AI)
            match.controllers[major] = CivilizationController(major, ControllerKind.EXTERNAL_AGENT, AgentProfile.defaultFor("Greece"))
            coordinator = MatchCoordinator(game.gameInfo, match, writer)
        }
        fun snapshot() = coordinator.deserializeForPublication(coordinator.serializeCommittedGameForPublication())
        fun reject(block: () -> Unit): AgentCommandException {
            try { block(); fail("Expected rejection") } catch (error: AgentCommandException) { return error }
            throw AssertionError("unreachable")
        }
    }

    @Test fun `human city-state peace records symmetric native treaty duration and replays exactly once`() {
        val f = Fixture(); val before = f.snapshot()
        val command = NegotiateCityStatePeaceCommand(f.actor, "peace", f.coordinator.getStateRevision(), f.target)
        val result = f.coordinator.execute(command) as DiplomacyResult
        assertEquals(DiplomaticStatus.Peace.name, result.status)
        val writes = f.writer.writeCount
        assertEquals(result.receipt.afterRevision, (f.coordinator.execute(command) as DiplomacyResult).receipt.afterRevision)
        assertEquals(writes, f.writer.writeCount)
        val after = f.snapshot()
        val duration = before.speed.peaceDealDuration
        assertEquals(duration, after.getCivilization(f.actor).getDiplomacyManager(f.target)!!.turnsToPeaceTreaty())
        assertEquals(duration, after.getCivilization(f.target).getDiplomacyManager(f.actor)!!.turnsToPeaceTreaty())
        assertEquals(DiplomaticStatus.War, f.game.gameInfo.getCivilization(f.actor).getDiplomacyManager(f.target)!!.diplomaticStatus)
    }

    @Test fun `dedicated city-state peace rejects majors and native war cooldown without a checkpoint`() {
        val f = Fixture()
        assertEquals(AgentCommandError.ILLEGAL_ACTION, f.reject {
            f.coordinator.execute(NegotiateCityStatePeaceCommand(f.actor, "major", f.coordinator.getStateRevision(), f.major))
        }.code)
        assertEquals(0, f.writer.writeCount)
        val blocked = Fixture(blocked = true)
        assertEquals(AgentCommandError.ILLEGAL_ACTION, blocked.reject {
            blocked.coordinator.execute(NegotiateCityStatePeaceCommand(blocked.actor, "cooldown", blocked.coordinator.getStateRevision(), blocked.target))
        }.code)
        assertEquals(0, blocked.writer.writeCount)
    }

    @Test fun `city-state treaty checkpoint failure preserves war and prior treaty duration`() {
        val f = Fixture(); f.writer.failNextWrite = true
        f.reject { f.coordinator.execute(NegotiateCityStatePeaceCommand(f.actor, "failed-peace", f.coordinator.getStateRevision(), f.target)) }
        val after = f.snapshot()
        assertEquals(DiplomaticStatus.War, after.getCivilization(f.actor).getDiplomacyManager(f.target)!!.diplomaticStatus)
        assertEquals(0, after.getCivilization(f.actor).getDiplomacyManager(f.target)!!.turnsToPeaceTreaty())
        assertTrue(f.coordinator.isPaused())
    }
}
