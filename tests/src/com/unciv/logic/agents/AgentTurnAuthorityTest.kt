package com.unciv.logic.agents

import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class AgentTurnAuthorityTest {
    private class Fixture {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_Vanilla)
        val writer = InMemoryCheckpointWriter()
        val coordinator: MatchCoordinator
        val romeId: String
        val greeceId: String
        val greeceUnitId: Int
        init {
            game.gameInfo.gameParameters.baseRuleset = BaseRuleset.Civ_V_Vanilla.fullName
            game.makeHexagonalMap(4)
            val rome = game.addCiv(game.ruleset.nations["Rome"]!!, isPlayer = true)
            val greece = game.addCiv(game.ruleset.nations["Greece"]!!, isPlayer = true)
            romeId = rome.civID; greeceId = greece.civID
            game.addUnit("Warrior", rome, game.getTile(0, 0))
            greeceUnitId = game.addUnit("Warrior", greece, game.getTile(3, 0)).id
            game.gameInfo.currentPlayer = romeId; game.gameInfo.currentPlayerCiv = rome
            val match = AgentMatchState(matchId = "m", timelineId = "t", turnPhase = TurnPhase.AWAITING_DECISIONS)
            match.controllers[romeId] = CivilizationController(romeId, ControllerKind.HUMAN)
            match.controllers[greeceId] = CivilizationController(greeceId, ControllerKind.HUMAN)
            coordinator = MatchCoordinator(game.gameInfo, match, writer)
        }
    }

    @Test fun `inactive actors cannot change gameplay or answer diplomacy`() {
        val commands: List<(Fixture) -> AgentCommand> = listOf(
            { MoveUnitCommand(it.greeceId, "move", 0, it.greeceUnitId, HexCoord(2, 0)) },
            { SelectResearchCommand(it.greeceId, "research", 0, "Pottery", false, false) },
            { RenameUnitCommand(it.greeceId, "rename", 0, it.greeceUnitId, "Pretender") },
            { EndTurnCommand(it.greeceId, "end", 0) },
            { RespondTradeCommand(it.greeceId, "trade", 0, it.romeId, true) },
            { RespondFriendshipOfferCommand(it.greeceId, "friendship", 0, it.romeId, true) }
        )
        commands.forEach { build ->
            val f = Fixture()
            val before = f.coordinator.saveToJson()
            try { f.coordinator.execute(build(f)); fail("inactive actor command applied") }
            catch (ex: AgentCommandException) { assertEquals(AgentCommandError.NOT_YOUR_TURN, ex.code) }
            assertEquals(before, f.coordinator.saveToJson())
            assertEquals(0, f.writer.writeCount)
            assertFalse(f.coordinator.isPaused())
        }
    }

    @Test fun `end turn receipt replays after its actor becomes inactive`() {
        val f = Fixture()
        val command = EndTurnCommand(f.romeId, "end", 0)
        val first = f.coordinator.execute(command) as TurnEndedResult
        assertEquals(f.greeceId, f.coordinator.getCurrentCivId())
        val writes = f.writer.writeCount
        val replay = f.coordinator.execute(command) as TurnEndedResult
        assertEquals(first.receipt.afterRevision, replay.receipt.afterRevision)
        assertEquals(writes, f.writer.writeCount)
    }

    @Test fun `host controller metadata may change for an inactive actor`() {
        val f = Fixture()
        f.coordinator.execute(SetControllerCommand(f.greeceId, "host-control", 0, f.greeceId, ControllerKind.HUMAN, null))
        assertEquals(1, f.coordinator.getStateRevision())
        assertEquals(f.romeId, f.coordinator.getCurrentCivId())
    }

    @Test fun `inactive leaders may update private notes with their own revision`() {
        val f = Fixture()
        f.coordinator.execute(UpdateNotesCommand(f.greeceId, "Wait for my turn", 0))
        assertEquals("Wait for my turn" to 1, f.coordinator.getNotes(f.greeceId))
        assertEquals(0, f.coordinator.getStateRevision())
        assertEquals(f.romeId, f.coordinator.getCurrentCivId())
    }
}
