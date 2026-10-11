package com.unciv.logic.agents

import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Worker orders beyond building (AI_AGENT_PLAN.md §6): pillaging with the
 * shared loot mechanics, repair queueing. Vanilla fixture.
 */
@RunWith(BaseTestRunner::class)
class RaidAndRepairTest {

    private class Fixture {
        val testGame = TestGame(baseRuleset = BaseRuleset.Civ_V_Vanilla)
        val writer = InMemoryCheckpointWriter()
        lateinit var coordinator: MatchCoordinator
        lateinit var romeId: String
        lateinit var greeceId: String
        var warriorId: Int = -1
        var raiderId: Int = -1
        var workerId: Int = -1
        val farmPos = HexCoord(2, 0)
        val neutralFarmPos = HexCoord(0, -1)

        init {
            testGame.gameInfo.gameParameters.baseRuleset = BaseRuleset.Civ_V_Vanilla.fullName
            testGame.makeHexagonalMap(4)
            val rome = testGame.addCiv(testGame.ruleset.nations["Rome"]!!, isPlayer = true)
            romeId = rome.civID
            val greece = testGame.addCiv(testGame.ruleset.nations["Greece"]!!)
            greeceId = greece.civID
            testGame.addCity(greece, testGame.getTile(3, 0))
            // War prize in Greek lands, plus a neutral farm for the repair half
            // (repairs are barred in enemy territory; civilians can't pillage).
            testGame.getTile(farmPos).setImprovement("Farm", greece)
            testGame.getTile(neutralFarmPos).setImprovement("Farm")
            warriorId = testGame.addUnit("Warrior", rome, testGame.getTile(1, 0)).id
            raiderId = testGame.addUnit("Warrior", rome, testGame.getTile(-1, 0)).id
            workerId = testGame.addUnit("Worker", rome, testGame.getTile(-1, -1)).id
            rome.diplomacyFunctions.makeCivilizationsMeet(greece)
            rome.getDiplomacyManager(greece)!!.declareWar(
                com.unciv.logic.civilization.diplomacy.DeclareWarReason(
                    com.unciv.logic.civilization.diplomacy.WarType.DirectWar))

            val match = AgentMatchState(
                matchId = "m1", timelineId = "t1", turnPhase = TurnPhase.AWAITING_DECISIONS
            )
            match.controllers[romeId] = CivilizationController(
                romeId, ControllerKind.EXTERNAL_AGENT,
                AgentProfile("p1", "Ada", "codex", "m", null, "", 1))
            match.controllers[greeceId] = CivilizationController(greeceId, ControllerKind.BUILTIN_AI, null)
            coordinator = MatchCoordinator(testGame.gameInfo, match, writer)
        }

        fun rev() = coordinator.getStateRevision()
    }

    @Test
    fun `pillage burns the farm and queues repair`() {
        val f = Fixture()
        // March onto the Greek farm first (one step at war).
        f.coordinator.execute(MoveUnitCommand(f.romeId, "m1", f.rev(), f.warriorId, f.farmPos))
        Assert.assertEquals(f.farmPos, f.coordinator.getUnitPosition(f.romeId, f.warriorId))

        val pillaged = f.coordinator.execute(
            PillageCommand(f.romeId, "p1", f.rev(), f.warriorId)) as PillagedResult
        Assert.assertEquals("Farm", pillaged.pillaged)
        Assert.assertTrue(f.coordinator.isTilePillaged(2, 0))

        // A second pillage finds nothing left to burn.
        try {
            f.coordinator.execute(PillageCommand(f.romeId, "p2", f.rev(), f.warriorId))
            Assert.fail("expected ILLEGAL_ACTION")
        } catch (ex: AgentCommandException) {
            Assert.assertEquals(AgentCommandError.ILLEGAL_ACTION, ex.code)
        }

        // Second warrior burns the neutral farm; the worker repairs it.
        // Marches may take several turns; Greek bombardment cannot reach
        // these tiles, so extra rounds are safe.
        march(f, f.raiderId, f.neutralFarmPos, "rm")
        f.coordinator.execute(PillageCommand(f.romeId, "p3", f.rev(), f.raiderId))
        Assert.assertTrue(f.coordinator.isTilePillaged(0, -1))
        march(f, f.workerId, f.neutralFarmPos, "wm")
        val repair = f.coordinator.execute(
            RepairCommand(f.romeId, "r1", f.rev(), f.workerId)) as RepairStartedResult
        Assert.assertTrue(repair.turnsToRepair > 0)
        Assert.assertEquals("Repair", f.coordinator.getImprovementInProgress(0, -1))
    }

    companion object {
        private var step = 0
        /**
         * Marches a unit to [dest] across as many turns as needed, ending
         * with a refill turn so the caller can act immediately.
         */
        private fun march(f: Fixture, unitId: Int, dest: HexCoord, tag: String) {
            repeat(6) {
                val pos = f.coordinator.getUnitPosition(f.romeId, unitId)!!
                if (pos == dest) {
                    f.coordinator.execute(EndTurnCommand(f.romeId, "e-${step++}", f.coordinator.getStateRevision()))
                    return
                }
                try {
                    f.coordinator.execute(MoveUnitCommand(f.romeId, "$tag-${step++}",
                        f.coordinator.getStateRevision(), unitId, dest))
                } catch (ex: AgentCommandException) {
                    if (ex.code != AgentCommandError.ILLEGAL_ACTION) throw ex
                    // Out of movement: next turn.
                }
                f.coordinator.execute(EndTurnCommand(f.romeId, "e-${step++}", f.coordinator.getStateRevision()))
            }
            throw AssertionError("unit $unitId never reached $dest")
        }
    }
}
