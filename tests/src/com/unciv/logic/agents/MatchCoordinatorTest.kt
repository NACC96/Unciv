package com.unciv.logic.agents

import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith

/**
 * W2 coordinator foundation (AI_AGENT_PLAN.md §3.2, §5.1, §10):
 * single-writer execution, candidate isolation, receipt/commit discipline,
 * persistence-failure behavior, and save/load decision boundaries — all
 * against an explicit Civ V - Vanilla fixture.
 */
@RunWith(BaseTestRunner::class)
class MatchCoordinatorTest {

    private class Fixture {
        // Explicit Vanilla: TestGame defaults to G&K (plan §13.1).
        val testGame = TestGame(baseRuleset = BaseRuleset.Civ_V_Vanilla)
        val writer = InMemoryCheckpointWriter()
        lateinit var coordinator: MatchCoordinator
        var warriorId: Int = -1
        var romeId: String = ""
        var greeceId: String = ""
        var origin: HexCoord = HexCoord(0, 0)
        var destination: HexCoord = HexCoord(1, 0)

        init {
            Assert.assertEquals(
                BaseRuleset.Civ_V_Vanilla.fullName,
                testGame.ruleset.name
            )
            testGame.gameInfo.gameParameters.baseRuleset = BaseRuleset.Civ_V_Vanilla.fullName
            testGame.makeHexagonalMap(3)
            // Real Vanilla nations: coordinator candidates restore transients
            // from RulesetCache, which knows nothing of TestGame's synthetic
            // nations (MissingNationException otherwise).
            val rome = testGame.addCiv(testGame.ruleset.nations["Rome"]!!, isPlayer = true)
            romeId = rome.civID
            val greece = testGame.addCiv(testGame.ruleset.nations["Greece"]!!)
            greeceId = greece.civID
            val startTile = testGame.getTile(origin)
            val warrior = testGame.addUnit("Warrior", rome, startTile)
            warriorId = warrior.id

            val match = AgentMatchState(
                matchId = "m1",
                timelineId = "t1",
                turnPhase = TurnPhase.AWAITING_DECISIONS
            )
            match.controllers[romeId] = CivilizationController(
                romeId, ControllerKind.EXTERNAL_AGENT,
                AgentProfile("p1", "Ada", "codex", "m", null, "", 1)
            )
            match.controllers[greeceId] = CivilizationController(greeceId, ControllerKind.BUILTIN_AI, null)
            coordinator = MatchCoordinator(testGame.gameInfo, match, writer)
        }

        fun move(
            actionId: String = "a1",
            expectedRevision: Int = coordinator.getStateRevision(),
            unitId: Int = warriorId,
            dest: HexCoord = destination
        ) = MoveUnitCommand(romeId, actionId, expectedRevision, unitId, dest)
    }

    @Test
    fun `move applies through candidate and commits receipt`() {
        val f = Fixture()
        val result = f.coordinator.execute(f.move()) as UnitMovedResult

        Assert.assertEquals(1, f.coordinator.getStateRevision())
        Assert.assertEquals(f.destination, result.finalPosition)
        Assert.assertEquals(f.destination, f.coordinator.getUnitPosition(f.romeId, f.warriorId))
        Assert.assertEquals("a1", result.receipt.actionId)
        Assert.assertEquals(0, result.receipt.beforeRevision)
        Assert.assertEquals(1, result.receipt.afterRevision)
        Assert.assertEquals("applied", result.receipt.outcome)
        Assert.assertEquals(1, f.writer.writeCount)
    }

    @Test
    fun `candidate work never leaks into the source game object`() {
        val f = Fixture()
        f.coordinator.execute(f.move())
        // The original TestGame unit never moved: construction and every
        // command deep-copy through serialization, never aliasing.
        val originalUnit = f.testGame.gameInfo.getCivilization(f.romeId)
            .units.getCivUnits().first { it.id == f.warriorId }
        Assert.assertEquals(f.origin, originalUnit.getTile().position)
    }

    @Test
    fun `duplicate action id replays receipt, changed payload conflicts`() {
        val f = Fixture()
        val first = f.coordinator.execute(f.move()) as UnitMovedResult
        val writes = f.writer.writeCount

        val replay = f.coordinator.execute(f.move()) as UnitMovedResult
        Assert.assertEquals(first.receipt.afterRevision, replay.receipt.afterRevision)
        Assert.assertEquals(first.finalPosition, replay.finalPosition)
        Assert.assertEquals(1, f.coordinator.getStateRevision())
        Assert.assertEquals(writes, f.writer.writeCount)

        try {
            f.coordinator.execute(f.move(actionId = "a1", dest = HexCoord(2, 0)))
            Assert.fail("expected ACTION_ID_CONFLICT")
        } catch (ex: AgentCommandException) {
            Assert.assertEquals(AgentCommandError.ACTION_ID_CONFLICT, ex.code)
        }
    }

    @Test
    fun `stale revision is rejected with current revision`() {
        val f = Fixture()
        f.coordinator.execute(f.move())
        try {
            f.coordinator.execute(f.move(actionId = "a2", expectedRevision = 0))
            Assert.fail("expected STALE_STATE")
        } catch (ex: AgentCommandException) {
            Assert.assertEquals(AgentCommandError.STALE_STATE, ex.code)
            Assert.assertEquals(1, ex.currentRevision)
        }
    }

    @Test
    fun `failed persistence discards candidate and pauses`() {
        val f = Fixture()
        f.writer.failNextWrite = true
        try {
            f.coordinator.execute(f.move())
            Assert.fail("expected INTERNAL_ERROR")
        } catch (ex: AgentCommandException) {
            Assert.assertEquals(AgentCommandError.INTERNAL_ERROR, ex.code)
        }
        // No acknowledgement, no effect, no checkpoint write.
        Assert.assertEquals(0, f.coordinator.getStateRevision())
        Assert.assertEquals(f.origin, f.coordinator.getUnitPosition(f.romeId, f.warriorId))
        Assert.assertEquals(0, f.writer.writeCount)
        Assert.assertTrue(f.coordinator.isPaused())
        Assert.assertTrue(f.coordinator.getPausedReason()!!.contains("persistence"))

        // Paused matches reject everything until recovery.
        try {
            f.coordinator.execute(f.move(actionId = "a2"))
            Assert.fail("expected MATCH_PAUSED")
        } catch (ex: AgentCommandException) {
            Assert.assertEquals(AgentCommandError.MATCH_PAUSED, ex.code)
        }
    }

    @Test
    fun `save and load restore an identical live decision boundary`() {
        val f = Fixture()
        f.coordinator.execute(f.move())
        f.coordinator.execute(UpdateNotesCommand(f.romeId, "River bend first.", 0))
        val bundle = f.coordinator.saveToJson()

        val loaded = MatchCoordinator.loadFromJson(bundle, InMemoryCheckpointWriter())
        Assert.assertEquals(1, loaded.getStateRevision())
        Assert.assertEquals(TurnPhase.AWAITING_DECISIONS, loaded.getTurnPhase())
        Assert.assertEquals(f.destination, loaded.getUnitPosition(f.romeId, f.warriorId))
        Assert.assertEquals("River bend first." to 1, loaded.getNotes(f.romeId))

        // The loaded coordinator keeps working from the boundary ...
        val moved = loaded.execute(MoveUnitCommand(f.romeId, "a2", 1, f.warriorId, HexCoord(2, 0))) as UnitMovedResult
        Assert.assertEquals(2, loaded.getStateRevision())
        Assert.assertEquals(HexCoord(2, 0), moved.finalPosition)
        // ... and still honors the old idempotency scope.
        val replay = loaded.execute(MoveUnitCommand(f.romeId, "a1", 0, f.warriorId, f.destination)) as UnitMovedResult
        Assert.assertEquals(1, replay.receipt.afterRevision)
        Assert.assertEquals(2, loaded.getStateRevision())
    }

    @Test
    fun `notes write bumps only the notes revision`() {
        val f = Fixture()
        val result = f.coordinator.execute(UpdateNotesCommand(f.romeId, "Watch the north.", 0)) as NotesUpdatedResult
        Assert.assertEquals(1, result.notesRevision)
        Assert.assertEquals(0, f.coordinator.getStateRevision())
        Assert.assertEquals("Watch the north." to 1, f.coordinator.getNotes(f.romeId))
        try {
            f.coordinator.execute(UpdateNotesCommand(f.romeId, "Stale.", 0))
            Assert.fail("expected STALE_STATE")
        } catch (ex: AgentCommandException) {
            Assert.assertEquals(AgentCommandError.STALE_STATE, ex.code)
        }
    }

    @Test
    fun `authority and legality gates hold`() {
        val f = Fixture()
        // Built-in AI civs take no coordinator commands.
        try {
            f.coordinator.execute(MoveUnitCommand(f.greeceId, "g1", 0, 999, f.destination))
            Assert.fail("expected AUTHORITY_REVOKED")
        } catch (ex: AgentCommandException) {
            Assert.assertEquals(AgentCommandError.AUTHORITY_REVOKED, ex.code)
        }
        // Unknown civs are invalid arguments.
        try {
            f.coordinator.execute(MoveUnitCommand("Atlantis", "x1", 0, 999, f.destination))
            Assert.fail("expected INVALID_ARGUMENT")
        } catch (ex: AgentCommandException) {
            Assert.assertEquals(AgentCommandError.INVALID_ARGUMENT, ex.code)
        }
        // Hidden and nonexistent units share one safe response ...
        try {
            f.coordinator.execute(f.move(actionId = "a9", unitId = 424242))
            Assert.fail("expected ILLEGAL_ACTION")
        } catch (ex: AgentCommandException) {
            Assert.assertEquals(AgentCommandError.ILLEGAL_ACTION, ex.code)
        }
        // ... as do off-map destinations.
        try {
            f.coordinator.execute(f.move(actionId = "a8", dest = HexCoord(99, 99)))
            Assert.fail("expected ILLEGAL_ACTION")
        } catch (ex: AgentCommandException) {
            Assert.assertEquals(AgentCommandError.ILLEGAL_ACTION, ex.code)
        }
        // Commands outside the decision phase are not turns.
        val ended = AgentMatchState(matchId = "m1", timelineId = "t1", turnPhase = TurnPhase.COMPLETED)
        ended.controllers[f.romeId] = CivilizationController(f.romeId, ControllerKind.EXTERNAL_AGENT, null)
        val done = MatchCoordinator(f.testGame.gameInfo, ended, InMemoryCheckpointWriter())
        try {
            done.execute(MoveUnitCommand(f.romeId, "z1", 0, f.warriorId, f.destination))
            Assert.fail("expected NOT_YOUR_TURN")
        } catch (ex: AgentCommandException) {
            Assert.assertEquals(AgentCommandError.NOT_YOUR_TURN, ex.code)
        }
        // Nothing committed by any rejected command.
        Assert.assertEquals(0, f.coordinator.getStateRevision())
        Assert.assertEquals(0, f.writer.writeCount)
    }
}
