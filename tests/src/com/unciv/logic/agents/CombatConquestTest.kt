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
 * Combat and conquest coverage (AI_AGENT_PLAN.md §6): unit attacks with
 * damage receipts, city capture raising MANDATORY conquest decisions (never
 * auto-selected), and puppet/annex/raze dispositions across real multi-turn
 * coordinator play. Explicit Civ V - Vanilla fixture.
 */
@RunWith(BaseTestRunner::class)
class CombatConquestTest {

    private class Fixture {
        val testGame = TestGame(baseRuleset = BaseRuleset.Civ_V_Vanilla)
        val writer = InMemoryCheckpointWriter()
        lateinit var coordinator: MatchCoordinator
        lateinit var romeId: String
        lateinit var greeceId: String
        val warriorIds = ArrayList<Int>()
        /** First living Roman warrior, or null when the invasion force is spent. */
        fun livingWarrior(): Int? = warriorIds.firstOrNull {
            coordinator.getUnitPosition(romeId, it) != null
        }
        val cityTiles = listOf(HexCoord(2, 0), HexCoord(3, 0), HexCoord(4, 0))
        val cityIds = ArrayList<String>()

        init {
            testGame.gameInfo.gameParameters.baseRuleset = BaseRuleset.Civ_V_Vanilla.fullName
            testGame.makeHexagonalMap(4)
            val rome = testGame.addCiv(testGame.ruleset.nations["Rome"]!!, isPlayer = true)
            romeId = rome.civID
            val greece = testGame.addCiv(testGame.ruleset.nations["Greece"]!!)
            greeceId = greece.civID
            // An invasion force: lone warriors die storming even weakened
            // cities (garrison counter + same-turn bombardment from
            // neighboring cities outpaces healing), so bring enough for
            // same-turn focus fire. All tiles are neutral ground (no
            // declaration teleport, no turn-start ejection at war).
            for (tilePos in listOf(
                HexCoord(-1, 0), HexCoord(-1, 1), HexCoord(-1, -1), HexCoord(0, -1),
                HexCoord(0, 1), HexCoord(-2, 0), HexCoord(-2, 1), HexCoord(-2, -1)
            )) warriorIds.add(testGame.addUnit("Warrior", rome, testGame.getTile(tilePos)).id)
            for (tilePos in cityTiles) {
                val city = testGame.addCity(greece, testGame.getTile(tilePos))
                // Barely standing: focus fire captures before the garrison's
                // counter-bombardment grinds the invasion down, and per-turn
                // healing cannot outrun same-turn focus fire.
                city.health = 1
                cityIds.add(city.id)
            }
            rome.diplomacyFunctions.makeCivilizationsMeet(greece)
            rome.getDiplomacyManager(greece)!!
                .declareWar(DeclareWarReason(WarType.DirectWar))

            val match = AgentMatchState(
                matchId = "m1", timelineId = "t1", turnPhase = TurnPhase.READY_TO_START
            )
            match.controllers[romeId] = CivilizationController(
                romeId, ControllerKind.EXTERNAL_AGENT,
                AgentProfile("p1", "Ada", "codex", "m", null, "", 1))
            match.controllers[greeceId] = CivilizationController(greeceId, ControllerKind.BUILTIN_AI, null)
            coordinator = MatchCoordinator(testGame.gameInfo, match, writer)
        }

        fun attack(target: HexCoord, actionId: String, unitId: Int) = AttackCommand(
            romeId, actionId, coordinator.getStateRevision(),
            attackerUnitId = unitId, target = target
        )

        fun endTurn(actionId: String) =
            EndTurnCommand(romeId, actionId, coordinator.getStateRevision())

        /** Plays coordinator turns until Rome holds a conquest decision for [cityIndex], or fails. */
        fun captureCity(cityIndex: Int): PendingDecision {
            val target = cityTiles[cityIndex]
            fun decision(): PendingDecision? = coordinator.getPendingDecisions(romeId)
                .firstOrNull { it.decisionType == "conquest-disposition" && it.sourceRef == "city:${cityIds[cityIndex]}" }
            var n = 0
            fun aid(prefix: String): String = "$prefix-$cityIndex-${n++}"
            repeat(16) {
                decision()?.let { return it }
                // Focus fire: every living adjacent warrior attacks.
                for (warrior in warriorIds.toList()) {
                    decision()?.let { return it }
                    val pos = coordinator.getUnitPosition(romeId, warrior) ?: continue
                    if (!isAdjacent(pos, target)) continue
                    try {
                        coordinator.execute(attack(target, aid("atk"), warrior))
                    } catch (ex: AgentCommandException) {
                        if (ex.code != AgentCommandError.ILLEGAL_ACTION) throw ex
                        // Spent or otherwise unable — next warrior.
                    }
                }
                decision()?.let { return it }
                // March everyone else one step closer.
                for (warrior in warriorIds.toList()) {
                    val pos = coordinator.getUnitPosition(romeId, warrior) ?: continue
                    if (isAdjacent(pos, target)) continue
                    try {
                        coordinator.execute(MoveUnitCommand(romeId, aid("mv"),
                            coordinator.getStateRevision(), warrior, target))
                    } catch (ex: AgentCommandException) {
                        if (ex.code != AgentCommandError.ILLEGAL_ACTION) throw ex
                    }
                }
                if (coordinator.getPendingDecisions(romeId).none { it.decisionType == "conquest-disposition" })
                    coordinator.execute(endTurn(aid("e")))
            }
            throw AssertionError("city $cityIndex was not captured within 16 rounds")
        }

        /** Engine adjacency (Unciv hex coords are not axial — never hand-roll). */
        private fun isAdjacent(a: HexCoord, b: HexCoord): Boolean =
            testGame.tileMap[a].neighbors.any { it.position == b }
    }

    @Test
    fun `unit attack deals damage with receipts`() {
        val f = Fixture()
        f.coordinator.beginAwaitingTurn()
        // March to the city walls first (move-toward stops adjacent), then
        // wait a turn: the march spends all movement points.
        val warrior = f.livingWarrior()!!
        val marched = f.coordinator.execute(MoveUnitCommand(f.romeId, "mv-0",
            f.coordinator.getStateRevision(), warrior, HexCoord(2, 0))) as UnitMovedResult
        Assert.assertEquals(HexCoord(1, 0), marched.finalPosition)
        f.coordinator.execute(f.endTurn("e-0"))
        val revBeforeAttack = f.coordinator.getStateRevision()
        // First blood on the capital's doorstep, no capture yet.
        val result = f.coordinator.execute(
            f.attack(HexCoord(2, 0), "atk-0", f.livingWarrior()!!)) as AttackResult
        Assert.assertTrue(result.attackerDamageDealt > 0)
        Assert.assertEquals(revBeforeAttack + 1, f.coordinator.getStateRevision())
    }

    @Test
    fun `capture raises mandatory decision, puppet and annex resolve`() {
        val f = Fixture()
        f.coordinator.beginAwaitingTurn()

        val decision1 = f.captureCity(0)
        // End-turn is blocked until the conquest is disposed.
        try {
            f.coordinator.execute(f.endTurn("e-blocked"))
            Assert.fail("expected DECISIONS_PENDING")
        } catch (ex: AgentCommandException) {
            Assert.assertEquals(AgentCommandError.DECISIONS_PENDING, ex.code)
        }
        val puppet = f.coordinator.execute(
            ResolveConquestCommand(f.romeId, "res-0", f.coordinator.getStateRevision(),
                decision1.decisionId, ConquestDisposition.PUPPET)
        ) as ConquestResolvedResult
        Assert.assertEquals(f.cityIds[0], puppet.cityId)
        Assert.assertEquals(f.romeId, f.coordinator.getCityOwner(f.cityIds[0]))
        Assert.assertTrue(f.coordinator.isCityPuppet(f.cityIds[0]))
        Assert.assertTrue(f.coordinator.getPendingDecisions(f.romeId).isEmpty())
        // ...and the turn can end again.
        f.coordinator.execute(f.endTurn("e-after-puppet"))

        val decision2 = f.captureCity(1)
        f.coordinator.execute(
            ResolveConquestCommand(f.romeId, "res-1", f.coordinator.getStateRevision(),
                decision2.decisionId, ConquestDisposition.ANNEX))
        Assert.assertEquals(f.romeId, f.coordinator.getCityOwner(f.cityIds[1]))
        Assert.assertFalse(f.coordinator.isCityPuppet(f.cityIds[1]))
    }

    @Test
    fun `raze burns a non-capital`() {
        val f = Fixture()
        f.coordinator.beginAwaitingTurn()
        // Capture the first two cities out of the way (puppet both).
        f.captureCity(0)
        f.coordinator.execute(ResolveConquestCommand(f.romeId, "res-0",
            f.coordinator.getStateRevision(),
            f.coordinator.getPendingDecisions(f.romeId).single().decisionId,
            ConquestDisposition.PUPPET))
        val decision = f.captureCity(2)
        f.coordinator.execute(
            ResolveConquestCommand(f.romeId, "res-2", f.coordinator.getStateRevision(),
                decision.decisionId, ConquestDisposition.RAZE))
        Assert.assertTrue(f.coordinator.isCityBeingRazed(f.cityIds[2]))
    }

    @Test
    fun `unknown decision ids are invalid`() {
        val f = Fixture()
        f.coordinator.beginAwaitingTurn()
        try {
            f.coordinator.execute(ResolveConquestCommand(f.romeId, "res-x",
                f.coordinator.getStateRevision(), "conquest:nowhere", ConquestDisposition.PUPPET))
            Assert.fail("expected INVALID_ARGUMENT")
        } catch (ex: AgentCommandException) {
            Assert.assertEquals(AgentCommandError.INVALID_ARGUMENT, ex.code)
        }
    }

    /** Minimal air/nuke fixture: fighter or missile for Rome, target for Greece. */
    private class AirFixture {
        val testGame = TestGame(baseRuleset = BaseRuleset.Civ_V_Vanilla)
        val writer = InMemoryCheckpointWriter()
        lateinit var coordinator: MatchCoordinator
        lateinit var romeId: String
        lateinit var greeceId: String
        var attackerId = -1

        init {
            testGame.gameInfo.gameParameters.baseRuleset = BaseRuleset.Civ_V_Vanilla.fullName
            testGame.gameInfo.gameParameters.nuclearWeaponsEnabled = true
            testGame.makeHexagonalMap(4)
            val rome = testGame.addCiv(testGame.ruleset.nations["Rome"]!!, isPlayer = true)
            romeId = rome.civID
            val greece = testGame.addCiv(testGame.ruleset.nations["Greece"]!!)
            greeceId = greece.civID
            // Air units base in cities: Rome's city hosts the attacker,
            // Greece's city hosts the bomber target.
            testGame.addCity(rome, testGame.getTile(-2, 0))
            testGame.addCity(greece, testGame.getTile(1, 0))
            attackerId = testGame.addUnit("Fighter", rome, testGame.getTile(-2, 0)).id
            testGame.addUnit("Bomber", greece, testGame.getTile(1, 0))
            rome.diplomacyFunctions.makeCivilizationsMeet(greece)
            rome.getDiplomacyManager(greece)!!
                .declareWar(DeclareWarReason(WarType.DirectWar))

            val match = AgentMatchState(
                matchId = "m1", timelineId = "t1", turnPhase = TurnPhase.AWAITING_DECISIONS
            )
            match.controllers[romeId] = CivilizationController(
                romeId, ControllerKind.EXTERNAL_AGENT,
                AgentProfile("p1", "Ada", "codex", "m", null, "", 1))
            match.controllers[greeceId] = CivilizationController(greeceId, ControllerKind.BUILTIN_AI, null)
            coordinator = MatchCoordinator(testGame.gameInfo, match, writer)
        }

        /** Rebuilds the coordinator to pick up source-game unit additions. */
        fun rebuild() {
            val match = AgentMatchState(
                matchId = "m1", timelineId = "t1", turnPhase = TurnPhase.AWAITING_DECISIONS
            )
            match.controllers[romeId] = CivilizationController(
                romeId, ControllerKind.EXTERNAL_AGENT,
                AgentProfile("p1", "Ada", "codex", "m", null, "", 1))
            match.controllers[greeceId] = CivilizationController(greeceId, ControllerKind.BUILTIN_AI, null)
            coordinator = MatchCoordinator(testGame.gameInfo, match, writer)
        }
    }

    @Test
    fun `air unit strikes from range through the standard attack command`() {
        val f = AirFixture()
        val result = f.coordinator.execute(AttackCommand(
            f.romeId, "air1", f.coordinator.getStateRevision(),
            attackerUnitId = f.attackerId, target = HexCoord(1, 0))) as AttackResult
        Assert.assertTrue(result.attackerAlive)
        Assert.assertEquals("applied", result.receipt.outcome)
    }

    @Test
    fun `nuclear missile strike consumes the missile and hits the blast`() {
        val f = AirFixture()
        // Swap the fighter for a nuclear missile on the same tile.
        val source = f.testGame.gameInfo.getCivilization(f.romeId)
        val missile = f.testGame.addUnit("Nuclear Missile", source, f.testGame.getTile(-2, 0))
        f.rebuild()

        val result = f.coordinator.execute(AttackCommand(
            f.romeId, "nuke1", f.coordinator.getStateRevision(),
            attackerUnitId = missile.id, target = HexCoord(1, 0))) as AttackResult
        Assert.assertEquals("applied", result.receipt.outcome)
        // Missiles self-destruct on use.
        Assert.assertNull(f.coordinator.getUnitPosition(f.romeId, missile.id))
        // The blast gutted the defender. Rome has no uranium stockpile for
        // the missile, so the engine halves the blast damage (ground zero
        // 100 -> 50): the bomber survives, gutted but alive.
        val bomber = f.coordinator.observe(f.romeId).visibleForeignUnits
            .firstOrNull { it.name == "Bomber" }
        Assert.assertNotNull("Bomber should still be observable", bomber)
        Assert.assertTrue("Bomber should be gutted", bomber!!.health < 100)
    }
}
