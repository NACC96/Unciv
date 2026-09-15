package com.unciv.logic.agents

import com.unciv.logic.civilization.diplomacy.DeclareWarReason
import com.unciv.logic.civilization.diplomacy.WarType
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Endgame dynamics (AI_AGENT_PLAN.md §6 conquest/rewards + victory rows):
 * ruins auto-rewards through movement, third-party liberation incl.
 * resurrection, domination by capitals, and time victory at the turn limit.
 * Explicit Civ V - Vanilla fixtures.
 *
 * Setup discipline (learned in CP-016/017): cities, units, meets and wars go
 * on the SOURCE game first (units placed after war declarations to dodge the
 * border teleport); the coordinator is (re)built last via [rebuild].
 */
@RunWith(BaseTestRunner::class)
class EndgameDynamicsTest {

    private open class Base(val maxTurns: Int = 500) {
        val testGame = TestGame(baseRuleset = BaseRuleset.Civ_V_Vanilla)
        lateinit var coordinator: MatchCoordinator
        lateinit var romeId: String
        lateinit var greeceId: String
        var aztecId: String? = null
        val warriorIds = ArrayList<Int>()
        val cityIds = ArrayList<String>()
        var n = 0
        fun aid(prefix: String) = "$prefix-${n++}"

        init {
            testGame.gameInfo.gameParameters.baseRuleset = BaseRuleset.Civ_V_Vanilla.fullName
            testGame.gameInfo.gameParameters.maxTurns = maxTurns
            // TestGame bypasses new-game setup, which would fill these.
            testGame.gameInfo.gameParameters.victoryTypes =
                ArrayList(testGame.ruleset.victories.keys)
            testGame.makeHexagonalMap(5)
            romeId = testGame.addCiv(testGame.ruleset.nations["Rome"]!!, isPlayer = true).civID
            greeceId = testGame.addCiv(testGame.ruleset.nations["Greece"]!!).civID
        }

        /** (Re)builds the coordinator after source-game setup is complete. */
        fun rebuild() {
            val match = AgentMatchState(matchId = "m1", timelineId = "t1", turnPhase = TurnPhase.READY_TO_START)
            match.controllers[romeId] = CivilizationController(romeId, ControllerKind.EXTERNAL_AGENT,
                AgentProfile("p1", "Ada", "codex", "m", null, "", 1))
            match.controllers[greeceId] = CivilizationController(greeceId, ControllerKind.BUILTIN_AI, null)
            aztecId?.let {
                match.controllers[it] = CivilizationController(it, ControllerKind.BUILTIN_AI, null)
            }
            coordinator = MatchCoordinator(testGame.gameInfo, match, InMemoryCheckpointWriter())
        }

        fun endTurnRome() = coordinator.execute(
            EndTurnCommand(romeId, aid("e"), coordinator.getStateRevision())) as TurnEndedResult

        private fun isAdjacent(a: HexCoord, b: HexCoord): Boolean =
            testGame.tileMap[a].neighbors.any { it.position == b }

        /** Focus-fires [targetCity] with every living Roman warrior until decided. */
        fun captureWithForce(targetCity: HexCoord, targetId: String): PendingDecision {
            repeat(20) {
                coordinator.getPendingDecisions(romeId)
                    .firstOrNull { it.sourceRef == "city:$targetId" }?.let { return it }
                for (warrior in warriorIds.toList()) {
                    coordinator.getPendingDecisions(romeId)
                        .firstOrNull { it.sourceRef == "city:$targetId" }?.let { return it }
                    val pos = coordinator.getUnitPosition(romeId, warrior) ?: continue
                    if (!isAdjacent(pos, targetCity)) {
                        try {
                            coordinator.execute(MoveUnitCommand(romeId, aid("mv"),
                                coordinator.getStateRevision(), warrior, targetCity))
                        } catch (ex: AgentCommandException) {
                            if (ex.code != AgentCommandError.ILLEGAL_ACTION) throw ex
                        }
                        continue
                    }
                    try {
                        coordinator.execute(AttackCommand(romeId, aid("atk"),
                            coordinator.getStateRevision(), attackerUnitId = warrior, target = targetCity))
                    } catch (ex: AgentCommandException) {
                        if (ex.code != AgentCommandError.ILLEGAL_ACTION) throw ex
                    }
                }
                if (coordinator.getPendingDecisions(romeId).none { it.decisionType == "conquest-disposition" })
                    endTurnRome()
            }
            throw AssertionError("city $targetId never fell")
        }

        fun resolve(decision: PendingDecision, disposition: ConquestDisposition) {
            coordinator.execute(ResolveConquestCommand(romeId, aid("res"),
                coordinator.getStateRevision(), decision.decisionId, disposition))
        }
    }

    @Test
    fun `ruins reward through movement, no command needed`() {
        val ruinsTiles = listOf(HexCoord(2, 0), HexCoord(2, 1), HexCoord(2, -1))
        val scoutTiles = listOf(HexCoord(1, 0), HexCoord(1, 1), HexCoord(1, -1))
        val f = object : Base() {
            init {
                val rome = testGame.gameInfo.getCivilization(romeId)
                testGame.addCity(rome, testGame.getTile(-3, 0))
                for (tilePos in ruinsTiles) testGame.getTile(tilePos).setImprovement("Ancient ruins")
                for (tilePos in scoutTiles)
                    warriorIds.add(testGame.addUnit("Scout", rome, testGame.getTile(tilePos)).id)
                rebuild()
            }
        }
        f.coordinator.beginAwaitingTurn()
        val goldBefore = f.coordinator.getGold(f.romeId)
        val unitsBefore = f.coordinator.observe(f.romeId).empire.unitCount
        val techsBefore = f.coordinator.getResearchedTechs(f.romeId).toSet()
        for ((i, tilePos) in ruinsTiles.withIndex()) {
            f.coordinator.execute(MoveUnitCommand(f.romeId, "mv$i", f.coordinator.getStateRevision(),
                f.warriorIds[i], tilePos))
            // Every ruin is consumed by entry.
            Assert.assertNull(f.coordinator.getTileImprovement(tilePos.x, tilePos.y))
        }
        // Rewards never repeat back-to-back, so across three tiles at least
        // one material change (gold, units, techs) must land.
        val obs = f.coordinator.observe(f.romeId)
        Assert.assertTrue(
            f.coordinator.getGold(f.romeId) != goldBefore
                || obs.empire.unitCount != unitsBefore
                || f.coordinator.getResearchedTechs(f.romeId).toSet() != techsBefore)
    }

    @Test
    fun `liberation returns a third-party capital`() {
        val f = object : Base() {
            init {
                val aztecs = testGame.addCiv(testGame.ruleset.nations["Aztecs"]!!)
                aztecId = aztecs.civID
                // Populous capital so Greece annexes instead of razing.
                val aztecCity = testGame.addCity(aztecs, testGame.getTile(4, 0), initialPopulation = 5)
                aztecCity.health = 1
                cityIds.add(aztecCity.id)
                val rome = testGame.gameInfo.getCivilization(romeId)
                val greece = testGame.gameInfo.getCivilization(greeceId)
                rome.diplomacyFunctions.makeCivilizationsMeet(greece)
                greece.diplomacyFunctions.makeCivilizationsMeet(aztecs)
                rome.diplomacyFunctions.makeCivilizationsMeet(aztecs)
                greece.getDiplomacyManager(aztecs)!!
                    .declareWar(DeclareWarReason(WarType.DirectWar))
                rome.getDiplomacyManager(greece)!!
                    .declareWar(DeclareWarReason(WarType.DirectWar))
                // Expedition AFTER the wars (border teleport). The invasion
                // starts close but neutral: adjacent tiles are in bombardment
                // range, and piecemeal marches lose the attrition race.
                warriorIds.add(testGame.addUnit("Warrior", greece, testGame.getTile(3, 0)).id)
                for (tilePos in listOf(
                    HexCoord(1, 0), HexCoord(1, 1), HexCoord(1, -1), HexCoord(0, 0),
                    HexCoord(0, 1), HexCoord(0, -1), HexCoord(-1, 0), HexCoord(-1, 1)
                )) warriorIds.add(testGame.addUnit("Warrior", rome, testGame.getTile(tilePos)).id)
                rebuild()
            }
        }
        f.coordinator.beginAwaitingTurn()

        // Wait for Greece to take the Aztec capital, then assault at once:
        // every idle round heals the city faster than bombardment attrition
        // allows. captureWithForce marches, focus-fires, and waits safely
        // (pre-capture attacks on the peaceful Aztec city are illegal-skipped).
        var greekCity: String? = null
        repeat(12) {
            f.endTurnRome()
            if (greekCity == null) greekCity = f.cityIds.mapNotNull { id ->
                f.coordinator.getCityOwner(id)?.let { owner -> if (owner == f.greeceId) id else null }
            }.firstOrNull()
            if (greekCity != null) {
                val decision = f.captureWithForce(HexCoord(4, 0), greekCity!!)
                f.resolve(decision, ConquestDisposition.LIBERATE)
                Assert.assertEquals(f.aztecId, f.coordinator.getCityOwner(greekCity!!))
                return
            }
        }
        Assert.fail("Greece never took the Aztec capital: owner=${f.coordinator.getCityOwner(f.cityIds[0])}")
    }

    @Test
    fun `domination by capitals completes the match`() {
        val f = object : Base() {
            init {
                val rome = testGame.gameInfo.getCivilization(romeId)
                testGame.addCity(rome, testGame.getTile(-3, 0))
                val greece = testGame.gameInfo.getCivilization(greeceId)
                val capital = testGame.addCity(greece, testGame.getTile(2, 0))
                capital.health = 1
                cityIds.add(capital.id)
                for (tilePos in listOf(HexCoord(-1, 0), HexCoord(-1, 1), HexCoord(-1, -1), HexCoord(0, -1)))
                    warriorIds.add(testGame.addUnit("Warrior", rome, testGame.getTile(tilePos)).id)
                rome.diplomacyFunctions.makeCivilizationsMeet(greece)
                rome.getDiplomacyManager(greece)!!
                    .declareWar(DeclareWarReason(WarType.DirectWar))
                rebuild()
            }
        }
        f.coordinator.beginAwaitingTurn()
        val decision = f.captureWithForce(HexCoord(2, 0), f.cityIds[0])
        f.resolve(decision, ConquestDisposition.PUPPET)
        f.endTurnRome()
        Assert.assertEquals(TurnPhase.COMPLETED, f.coordinator.getTurnPhase())
        Assert.assertEquals("Domination", f.coordinator.getVictoryType())
    }

    @Test
    fun `time victory at the turn limit completes the match`() {
        val f = object : Base(maxTurns = 2) {
            init {
                // Someone must be alive to take turns.
                val rome = testGame.gameInfo.getCivilization(romeId)
                warriorIds.add(testGame.addUnit("Warrior", rome, testGame.getTile(0, 0)).id)
                rebuild()
            }
        }
        f.coordinator.beginAwaitingTurn()
        repeat(6) {
            if (f.coordinator.getTurnPhase() == TurnPhase.COMPLETED) return@repeat
            f.endTurnRome()
        }
        Assert.assertEquals(TurnPhase.COMPLETED, f.coordinator.getTurnPhase())
        Assert.assertEquals("Time", f.coordinator.getVictoryType())
    }
}
