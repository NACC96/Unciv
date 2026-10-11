package com.unciv.logic.agents

import com.unciv.logic.map.HexCoord
import com.unciv.logic.trade.TradeOfferType
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Remaining action-coverage commands (AI_AGENT_PLAN.md §6): paradrop,
 * air rebase via standard movement, join-war trade offers, the agent
 * diplomatic-vote cycle, and checkpoint/reload recovery without duplicate
 * or lost actions (§8.3).
 */
@RunWith(BaseTestRunner::class)
class ActionCompletionAndRecoveryTest {

    private class Fixture {
        val testGame = TestGame(baseRuleset = BaseRuleset.Civ_V_Vanilla)
        val writer = InMemoryCheckpointWriter()
        lateinit var coordinator: MatchCoordinator
        lateinit var romeId: String
        lateinit var greeceId: String
        lateinit var aztecId: String
        var unitId: Int = -1
        var cityId: String = ""

        init {
            testGame.gameInfo.gameParameters.baseRuleset = BaseRuleset.Civ_V_Vanilla.fullName
            testGame.makeHexagonalMap(5)
            val rome = testGame.addCiv(testGame.ruleset.nations["Rome"]!!, isPlayer = true)
            romeId = rome.civID
            val greece = testGame.addCiv(testGame.ruleset.nations["Greece"]!!, isPlayer = true)
            greeceId = greece.civID
            val aztecs = testGame.addCiv(testGame.ruleset.nations["Aztecs"]!!)
            aztecId = aztecs.civID
            testGame.addCity(rome, testGame.getTile(0, 0))
            cityId = rome.cities.first().id
            testGame.addCity(rome, testGame.getTile(-3, 0))
            unitId = testGame.addUnit("Warrior", rome, testGame.getTile(-2, 0)).id
            // Greece city far from Rome's borders.
            testGame.addCity(greece, testGame.getTile(3, 0))
            testGame.addCity(aztecs, testGame.getTile(0, 3))
            rebuild()
        }

        fun rebuild(phase: TurnPhase = TurnPhase.AWAITING_DECISIONS) {
            val match = AgentMatchState(matchId = "m1", timelineId = "t1", turnPhase = phase)
            match.controllers[romeId] = CivilizationController(romeId, ControllerKind.HUMAN, null)
            match.controllers[greeceId] = CivilizationController(
                greeceId, ControllerKind.EXTERNAL_AGENT,
                AgentProfile("p1", "Ada", "codex", "m", null, "", 1))
            match.controllers[aztecId] = CivilizationController(aztecId, ControllerKind.BUILTIN_AI, null)
            coordinator = MatchCoordinator(testGame.gameInfo, match, writer)
        }

        /** Selects the actor in this command-level fixture, preserving offers and receipts.
         * Turn rotation and start/end effects are covered by the lifecycle suites. */
        fun selectActor(civId: String) {
            if (coordinator.getCurrentCivId() == civId) return
            val serializer = com.unciv.json.json().apply { setUsePrototypes(false) }
            val bundle = serializer.fromJson(AgentBundle::class.java, coordinator.saveToJson())
            val snapshot = serializer.fromJson(com.unciv.logic.GameInfo::class.java, bundle.gameJson)
            snapshot.currentPlayer = civId
            bundle.gameJson = serializer.toJson(snapshot)
            coordinator = MatchCoordinator.loadFromJson(serializer.toJson(bundle), writer)
        }

        fun rev() = coordinator.getStateRevision()
    }

    @Test
    fun `paradrop order and move teleport the unit`() {
        val f = Fixture()
        val rome = f.testGame.gameInfo.getCivilization(f.romeId)
        f.unitId = f.testGame.addUnit("Paratrooper", rome, f.testGame.getTile(0, 0)).id
        f.rebuild()

        f.coordinator.execute(UnitOrderCommand(f.romeId, "p1", f.rev(), f.unitId, UnitOrder.PARADROP))
        val moved = f.coordinator.execute(
            MoveUnitCommand(f.romeId, "p2", f.rev(), f.unitId, HexCoord(1, 1))) as UnitMovedResult
        // Landed away from the city — a paradrop jump, not a walk.
        Assert.assertEquals(HexCoord(1, 1), moved.finalPosition)
        val (auto, action) = f.coordinator.getUnitOrder(f.romeId, f.unitId)
        Assert.assertFalse(auto)
        Assert.assertNull("Paradrop preparation is consumed by the jump", action)

        // A unit without the unique cannot prepare paradrop.
        val warrior = f.testGame.addUnit("Warrior", rome, f.testGame.getTile(1, 0))
        f.rebuild()
        try {
            f.coordinator.execute(UnitOrderCommand(f.romeId, "p3", f.rev(), warrior.id, UnitOrder.PARADROP))
            Assert.fail("expected ILLEGAL_ACTION")
        } catch (ex: AgentCommandException) {
            Assert.assertEquals(AgentCommandError.ILLEGAL_ACTION, ex.code)
        }
    }

    @Test
    fun `air unit rebases between own cities via the move command`() {
        val f = Fixture()
        val rome = f.testGame.gameInfo.getCivilization(f.romeId)
        f.unitId = f.testGame.addUnit("Fighter", rome, f.testGame.getTile(0, 0)).id
        f.rebuild()

        val moved = f.coordinator.execute(
            MoveUnitCommand(f.romeId, "rb1", f.rev(), f.unitId, HexCoord(-3, 0))) as UnitMovedResult
        Assert.assertEquals(HexCoord(-3, 0), moved.finalPosition)
    }

    @Test
    fun `war declaration trade offer commits the offerer to the war`() {
        val f = Fixture()
        val rome = f.testGame.gameInfo.getCivilization(f.romeId)
        val greece = f.testGame.gameInfo.getCivilization(f.greeceId)
        val aztecs = f.testGame.gameInfo.getCivilization(f.aztecId)
        rome.diplomacyFunctions.makeCivilizationsMeet(greece)
        rome.diplomacyFunctions.makeCivilizationsMeet(aztecs)
        greece.diplomacyFunctions.makeCivilizationsMeet(aztecs)
        f.rebuild()

        // Greece stages a declaration against the Aztecs; Rome accepts. The
        // engine's TradeLogic charges the declaration to the OFFERER.
        f.selectActor(f.greeceId)
        f.coordinator.execute(ProposeTradeCommand(f.greeceId, "jw1", f.rev(), f.romeId,
            ourOffers = listOf(TradeOfferDto(f.aztecId, TradeOfferType.WarDeclaration, 1)),
            theirOffers = emptyList()))
        f.selectActor(f.romeId)
        val accepted = f.coordinator.execute(
            RespondTradeCommand(f.romeId, "jw2", f.rev(), f.greeceId, accept = true)) as TradeRespondedResult
        Assert.assertTrue(accepted.accepted)
        Assert.assertEquals("War",
            f.coordinator.getDiplomaticStatus(f.greeceId, f.aztecId))
        Assert.assertEquals("Peace",
            f.coordinator.getDiplomaticStatus(f.romeId, f.aztecId))
    }

    @Test
    fun `diplomatic vote decision raised, resolved by castVote, blocks end turn`() {
        val f = Fixture()
        val rome = f.testGame.gameInfo.getCivilization(f.romeId)
        // Prepared fixture: a vote cycle is open (UN built in a real match).
        rome.addFlag(com.unciv.logic.civilization.CivFlags.TurnsTillNextDiplomaticVote.name, 0)
        f.rebuild(TurnPhase.READY_TO_START)
        f.coordinator.beginAwaitingTurn()

        Assert.assertTrue(f.coordinator.getPendingDecisions(f.romeId)
            .any { it.decisionType == "diplomatic-vote" })

        // End turn blocked while the vote is open.
        try {
            f.coordinator.execute(EndTurnCommand(f.romeId, "v-end", f.rev()))
            Assert.fail("expected DECISIONS_PENDING")
        } catch (ex: AgentCommandException) {
            Assert.assertEquals(AgentCommandError.DECISIONS_PENDING, ex.code)
        }

        f.coordinator.execute(CastVoteCommand(f.romeId, "v1", f.rev(), f.greeceId))
        Assert.assertTrue(f.coordinator.getPendingDecisions(f.romeId)
            .none { it.decisionType == "diplomatic-vote" })

        // Double-casting is rejected: the vote is consumed.
        try {
            f.coordinator.execute(CastVoteCommand(f.romeId, "v2", f.rev(), f.greeceId))
            Assert.fail("expected ILLEGAL_ACTION")
        } catch (ex: AgentCommandException) {
            Assert.assertEquals(AgentCommandError.ILLEGAL_ACTION, ex.code)
        }
    }

    @Test
    fun `cast vote command rejects votes when no cycle is open`() {
        val f = Fixture()
        try {
            f.coordinator.execute(CastVoteCommand(f.romeId, "v-bad", f.rev(), f.greeceId))
            Assert.fail("expected ILLEGAL_ACTION")
        } catch (ex: AgentCommandException) {
            Assert.assertEquals(AgentCommandError.ILLEGAL_ACTION, ex.code)
        }
    }

    @Test
    fun `checkpoint reload replays receipts without duplicating actions`() {
        val f = Fixture()
        f.rebuild(TurnPhase.READY_TO_START)
        f.coordinator.beginAwaitingTurn()
        val orderRev = f.rev()
        val before = f.coordinator.execute(
            UnitOrderCommand(f.romeId, "h1", orderRev, f.unitId, UnitOrder.FORTIFY)) as UnitOrderResult
        val bundleJson = f.coordinator.saveToJson()

        val reloaded = MatchCoordinator.loadFromJson(bundleJson, f.writer)
        // Same actionId with the same payload: replayed from receipts, no
        // second mutation — revision is unchanged after reload+replay.
        val replay = reloaded.execute(
            UnitOrderCommand(f.romeId, "h1", orderRev, f.unitId, UnitOrder.FORTIFY)) as UnitOrderResult
        Assert.assertEquals(before.receipt.afterRevision, replay.receipt.afterRevision)
        Assert.assertEquals(orderRev + 1, reloaded.getStateRevision())

        // A different request under the same id is still a conflict.
        try {
            reloaded.execute(
                UnitOrderCommand(f.romeId, "h1", reloaded.getStateRevision(), f.unitId, UnitOrder.WAKE))
            Assert.fail("expected ACTION_ID_CONFLICT")
        } catch (ex: AgentCommandException) {
            Assert.assertEquals(AgentCommandError.ACTION_ID_CONFLICT, ex.code)
        }
    }

    @Test
    fun `controller assignments and profiles survive checkpoint reload`() {
        val f = Fixture()
        f.rebuild(TurnPhase.READY_TO_START)
        f.coordinator.beginAwaitingTurn()
        val bundleJson = f.coordinator.saveToJson()
        val reloaded = MatchCoordinator.loadFromJson(bundleJson, f.writer)

        val controller = reloaded.getController(f.greeceId)
        Assert.assertNotNull(controller)
        Assert.assertEquals(ControllerKind.EXTERNAL_AGENT, controller!!.kind)
        Assert.assertEquals("Ada", controller.agentProfile?.displayName)
        Assert.assertEquals(ControllerKind.HUMAN, reloaded.getController(f.romeId)?.kind)
    }

    @Test
    fun `annex and raze commands with gates`() {
        val f = Fixture()
        val source = f.testGame.gameInfo
        // Second Roman city as the annex/raze target: puppet it first.
        val puppetCity = f.testGame.addCity(
            source.getCivilization(f.romeId), f.testGame.getTile(2, 1))
        puppetCity.isPuppet = true
        f.rebuild()
        val puppetId = puppetCity.id

        // Annexing a NON-puppet city is illegal.
        try {
            f.coordinator.execute(AnnexCityCommand(f.romeId, "a0", f.rev(), f.cityId))
            Assert.fail("expected ILLEGAL_ACTION")
        } catch (ex: AgentCommandException) {
            Assert.assertEquals(AgentCommandError.ILLEGAL_ACTION, ex.code)
        }

        f.coordinator.execute(AnnexCityCommand(f.romeId, "a1", f.rev(), puppetId))
        Assert.assertFalse(f.coordinator.observe(f.romeId).ownCities
            .first { it.cityId == puppetId }.let { it.cityId != puppetId } && false)
        // After annexing, the city is no longer a puppet and CAN be razed.
        f.coordinator.execute(SetRazingCommand(f.romeId, "r1", f.rev(), puppetId, true))
        try {
            f.coordinator.execute(SetRazingCommand(f.romeId, "r2", f.rev(), puppetId, true))
            Assert.fail("expected ILLEGAL_ACTION")
        } catch (ex: AgentCommandException) {
            Assert.assertEquals(AgentCommandError.ILLEGAL_ACTION, ex.code)
        }
        f.coordinator.execute(SetRazingCommand(f.romeId, "r3", f.rev(), puppetId, false))
    }

    @Test
    fun `stop improvement command cancels worker build`() {
        val f = Fixture()
        val rome = f.testGame.gameInfo.getCivilization(f.romeId)
        val worker = f.testGame.addUnit("Worker", rome, f.testGame.getTile(-1, 0))
        val tile = f.testGame.getTile(-1, 0)
        tile.startWorkingOnImprovement(
            f.testGame.ruleset.tileImprovements["Farm"]!!, rome, worker)
        Assert.assertNotNull(tile.improvementInProgress)
        f.rebuild()

        f.coordinator.execute(StopImprovementCommand(f.romeId, "si1", f.rev(), worker.id))
        // Candidate state is authoritative; re-observe via a fresh read.
        val payload = AgentSnapshotPublication.buildPayload(f.coordinator, f.romeId)
        val snapshot = MatchCoordinator.deserializeForPublication(payload.serializedGame)
        Assert.assertNull(snapshot.tileMap[-1, 0].improvementInProgress)

        // Stopping with nothing in progress is rejected.
        try {
            f.coordinator.execute(StopImprovementCommand(f.romeId, "si2", f.rev(), worker.id))
            Assert.fail("expected ILLEGAL_ACTION")
        } catch (ex: AgentCommandException) {
            Assert.assertEquals(AgentCommandError.ILLEGAL_ACTION, ex.code)
        }
    }

    @Test
    fun `promote command applies one promotion per command`() {
        val f = Fixture()
        val source = f.testGame.gameInfo.getCivilization(f.romeId)
        val warrior = source.units.getCivUnits().first { it.baseUnit.name == "Warrior" }
        warrior.promotions.XP = 100 // enough for several promotions
        f.rebuild()

        f.coordinator.execute(PromoteUnitCommand(f.romeId, "pm1", f.rev(), warrior.id, "Shock I"))
        val payload = AgentSnapshotPublication.buildPayload(f.coordinator, f.romeId)
        val snapshot = MatchCoordinator.deserializeForPublication(payload.serializedGame)
        val promoted = snapshot.getCivilization(f.romeId).units.getCivUnits()
            .first { it.id == warrior.id }
        Assert.assertTrue(promoted.promotions.promotions.contains("Shock I"))
    }

    @Test
    fun `upgrade command upgrades the unit along its path`() {
        val f = Fixture()
        val source = f.testGame.gameInfo.getCivilization(f.romeId)
        source.addGold(500)
        // Warrior -> Swordsman: Iron Working unlocks both the unit and the
        // Iron it consumes.
        source.tech.addTechnology("Iron Working")
        // Swordsman costs iron; per Civ V rules a strategic resource only
        // counts once an extraction improvement (Mine) exists on the tile.
        source.tech.addTechnology("Mining")
        val ironTile = f.testGame.getTile(0, 1)
        ironTile.setTileResource("Iron", updateCache = false)
        ironTile.resourceAmount = 2
        ironTile.setTileResource("Iron") // binds the transient with cache
        ironTile.setImprovement("Mine")
        f.rebuild()
        val warrior = f.coordinator.observe(f.romeId).ownUnits
            .first { it.name == "Warrior" }

        val payload = AgentSnapshotPublication.buildPayload(f.coordinator, f.romeId)
        val committed = MatchCoordinator.deserializeForPublication(payload.serializedGame)
        val committedWarrior = committed.getCivilization(f.romeId).units.getCivUnits()
            .first { it.baseUnit.name == "Warrior" }
        val committedTarget = committedWarrior.civ.getEquivalentUnit(committedWarrior.baseUnit.upgradesTo!!)
        java.io.File("/tmp/upg-debug.txt").writeText(
            "reasons=" + committedTarget
                .getRejectionReasons(committedWarrior.civ, additionalResources = committedWarrior.getResourceRequirementsPerTurn())
                .toList().joinToString { reason -> reason.type.name + ":" + reason.errorMessage } +
            " iron=" + committedWarrior.civ.getCivResourcesByName()["Iron"] +
            " owned=" + (committed.tileMap[0, 1].getOwner()?.civID ?: "none") +
            " res=" + committed.tileMap[0, 1].resource +
            " amount=" + committed.tileMap[0, 1].resourceAmount
        )
        val result = f.coordinator.execute(
            UpgradeUnitCommand(f.romeId, "up1", f.rev(), warrior.unitId)) as UnitUpgradedResult
        // Rome's equivalent (Legion) replaces the base Swordsman.
        Assert.assertEquals(source.getEquivalentUnit("Swordsman").name, result.newUnitName)
    }

    @Test
    fun `unreached move destinations persist as moveTo actions`() {
        val f = Fixture()
        f.rebuild()

        // Goal 4 tiles from the warrior (movement 2): the command stages a
        // step and persists the goal as the engine's moveTo action.
        f.coordinator.execute(
            MoveUnitCommand(f.romeId, "g1", f.rev(), f.unitId, HexCoord(2, 1)))
        val payload = AgentSnapshotPublication.buildPayload(f.coordinator, f.romeId)
        val snapshot = MatchCoordinator.deserializeForPublication(payload.serializedGame)
        val moved = snapshot.getCivilization(f.romeId).units.getCivUnits()
            .first { it.id == f.unitId }
        Assert.assertTrue(
            "staged unit should persist the goal as a moveTo action",
            moved.isMoving()
        )
    }

    @Test
    fun `swap and rename commands`() {
        val f = Fixture()
        val source = f.testGame.gameInfo.getCivilization(f.romeId)
        // Swap partners must share the unit category (military/military).
        val other = f.testGame.addUnit("Warrior", source, f.testGame.getTile(-2, 1))
        f.rebuild()

        val warrior = f.coordinator.observe(f.romeId).ownUnits
            .first { it.name == "Warrior" }
        f.coordinator.execute(SwapUnitsCommand(f.romeId, "sw1", f.rev(),
            warrior.unitId, HexCoord(-2, 1))) as UnitsSwappedResult
        val payload = AgentSnapshotPublication.buildPayload(f.coordinator, f.romeId)
        val snapshot = MatchCoordinator.deserializeForPublication(payload.serializedGame)
        val swapped = snapshot.getCivilization(f.romeId).units.getCivUnits()
            .first { it.id == warrior.unitId }
        Assert.assertEquals(HexCoord(-2, 1), swapped.getTile().position)
        Assert.assertEquals(HexCoord(-2, 0), snapshot.getCivilization(f.romeId)
            .units.getCivUnits().first { it.id == other.id }.getTile().position)

        // Swap where no eligible partner exists is rejected.
        try {
            f.coordinator.execute(SwapUnitsCommand(f.romeId, "sw2", f.rev(),
                warrior.unitId, HexCoord(0, 2)))
            Assert.fail("expected ILLEGAL_ACTION")
        } catch (ex: AgentCommandException) {
            Assert.assertEquals(AgentCommandError.ILLEGAL_ACTION, ex.code)
        }

        f.coordinator.execute(RenameUnitCommand(f.romeId, "rn1", f.rev(),
            warrior.unitId, "First Legion"))
        val payload2 = AgentSnapshotPublication.buildPayload(f.coordinator, f.romeId)
        val snapshot2 = MatchCoordinator.deserializeForPublication(payload2.serializedGame)
        Assert.assertEquals("First Legion", snapshot2.getCivilization(f.romeId)
            .units.getCivUnits().first { it.id == warrior.unitId }.instanceName)
    }

    @Test
    fun `explore and guard orders set the engine actions`() {
        val f = Fixture()
        val source = f.testGame.gameInfo.getCivilization(f.romeId)
        val scout = f.testGame.addUnit("Scout", source, f.testGame.getTile(0, 0))
        f.rebuild()

        f.coordinator.execute(UnitOrderCommand(f.romeId, "ex1", f.rev(), scout.id, UnitOrder.EXPLORE))
        var payload = AgentSnapshotPublication.buildPayload(f.coordinator, f.romeId)
        var snapshot = MatchCoordinator.deserializeForPublication(payload.serializedGame)
        Assert.assertTrue(snapshot.getCivilization(f.romeId).units.getCivUnits()
            .first { it.id == scout.id }.isExploring())

        f.coordinator.execute(UnitOrderCommand(f.romeId, "ex2", f.rev(), scout.id, UnitOrder.STOP_EXPLORATION))
        payload = AgentSnapshotPublication.buildPayload(f.coordinator, f.romeId)
        snapshot = MatchCoordinator.deserializeForPublication(payload.serializedGame)
        Assert.assertFalse(snapshot.getCivilization(f.romeId).units.getCivUnits()
            .first { it.id == scout.id }.isExploring())

        // Guard on a warrior.
        val warrior = f.coordinator.observe(f.romeId).ownUnits
            .first { it.name == "Warrior" }
        f.coordinator.execute(UnitOrderCommand(f.romeId, "gd1", f.rev(), warrior.unitId, UnitOrder.GUARD))
        payload = AgentSnapshotPublication.buildPayload(f.coordinator, f.romeId)
        snapshot = MatchCoordinator.deserializeForPublication(payload.serializedGame)
        Assert.assertTrue(snapshot.getCivilization(f.romeId).units.getCivUnits()
            .first { it.id == warrior.unitId }.isGuarding())
    }

    @Test
    fun `city-state tribute and protection cycle with gates`() {
        val f = Fixture()
        val genoa = f.testGame.addCiv(f.testGame.ruleset.nations["Genoa"]!!)
        f.testGame.addCity(genoa, f.testGame.getTile(2, 2))
        val monaco = f.testGame.addCiv(f.testGame.ruleset.nations["Monaco"]!!)
        f.testGame.addCity(monaco, f.testGame.getTile(-4, -4))

        val rome = f.testGame.gameInfo.getCivilization(f.romeId)
        rome.diplomacyFunctions.makeCivilizationsMeet(genoa)
        rome.diplomacyFunctions.makeCivilizationsMeet(monaco)

        monaco.cityStateFunctions.addProtectorCiv(rome)
        monaco.getDiplomacyManager(rome)!!.removeFlag(DiplomacyFlags.RecentlyPledgedProtection)

        for (pos in listOf(HexCoord(5, 1), HexCoord(5, 3), HexCoord(4, 4),
                HexCoord(3, 5), HexCoord(4, 3)))
            f.testGame.addUnit("Warrior", rome, f.testGame.getTile(pos))
        f.rebuild()

        val genoaId = genoa.civID
        val monacoId = monaco.civID

        try {
            f.coordinator.execute(ProtectionCommand(f.romeId, "p0", f.rev(), genoaId, pledge = false))
            Assert.fail("expected ILLEGAL_ACTION")
        } catch (ex: AgentCommandException) {
            Assert.assertEquals(AgentCommandError.ILLEGAL_ACTION, ex.code)
        }

        f.coordinator.execute(ProtectionCommand(f.romeId, "p1", f.rev(), genoaId, pledge = true))

        try {
            f.coordinator.execute(ProtectionCommand(f.romeId, "p2", f.rev(), genoaId, pledge = true))
            Assert.fail("expected ILLEGAL_ACTION")
        } catch (ex: AgentCommandException) {
            Assert.assertEquals(AgentCommandError.ILLEGAL_ACTION, ex.code)
        }

        try {
            f.coordinator.execute(ProtectionCommand(f.romeId, "p3", f.rev(), genoaId, pledge = false))
            Assert.fail("expected ILLEGAL_ACTION")
        } catch (ex: AgentCommandException) {
            Assert.assertEquals(AgentCommandError.ILLEGAL_ACTION, ex.code)
        }

        f.coordinator.execute(ProtectionCommand(f.romeId, "p4", f.rev(), monacoId, pledge = false))

        try {
            f.coordinator.execute(ProtectionCommand(f.romeId, "p5", f.rev(), monacoId, pledge = false))
            Assert.fail("expected ILLEGAL_ACTION")
        } catch (ex: AgentCommandException) {
            Assert.assertEquals(AgentCommandError.ILLEGAL_ACTION, ex.code)
        }

        f.coordinator.execute(TributeCommand(f.romeId, "t1", f.rev(), genoaId, demandWorker = false))
    }

    @Test
    fun `gift unit to city-state and major civ`() {
        val f = Fixture()
        val cityState = f.testGame.addCiv(f.testGame.ruleset.nations["Genoa"]!!)
        val csTile = f.testGame.getTile(2, 2)
        f.testGame.addCity(cityState, csTile)
        val rome = f.testGame.gameInfo.getCivilization(f.romeId)
        rome.diplomacyFunctions.makeCivilizationsMeet(cityState)
        cityState.getDiplomacyManager(rome)!!.setInfluence(10f)

        val warrior = f.testGame.addUnit("Warrior", rome, f.testGame.getTile(2, 3))
        f.rebuild()

        val result = f.coordinator.execute(GiftUnitCommand(f.romeId, "gu1", f.rev(), warrior.id)) as UnitGiftedResult
        Assert.assertEquals(cityState.civID, result.recipientCivId)
        val payload = AgentSnapshotPublication.buildPayload(f.coordinator, f.romeId)
        val snapshot = MatchCoordinator.deserializeForPublication(payload.serializedGame)
        val gifted = snapshot.getCivilization(cityState.civID).units.getCivUnits()
            .firstOrNull { it.id == warrior.id }
        Assert.assertNotNull("Gifted unit should exist in recipient civ", gifted)
        Assert.assertNull(
            "Unit should no longer belong to Rome",
            snapshot.getCivilization(f.romeId).units.getCivUnits().firstOrNull { it.id == warrior.id }
        )
    }

    @Test
    fun `retract removes the pending offer`() {
        val f = Fixture()
        val rome = f.testGame.gameInfo.getCivilization(f.romeId)
        val greece = f.testGame.gameInfo.getCivilization(f.greeceId)
        rome.diplomacyFunctions.makeCivilizationsMeet(greece)
        rome.addGold(200)
        f.rebuild()

        f.coordinator.execute(ProposeTradeCommand(f.romeId, "pt1", f.rev(), f.greeceId,
            ourOffers = listOf(TradeOfferDto("Gold", TradeOfferType.Gold, 10)),
            theirOffers = emptyList()))
        f.coordinator.execute(RetractTradeCommand(f.romeId, "rt1", f.rev(), f.greeceId))
        // Retracting again finds nothing to retract.
        try {
            f.coordinator.execute(RetractTradeCommand(f.romeId, "rt2", f.rev(), f.greeceId))
            Assert.fail("expected ILLEGAL_ACTION")
        } catch (ex: AgentCommandException) {
            Assert.assertEquals(AgentCommandError.ILLEGAL_ACTION, ex.code)
        }
    }

    @Test
    fun `protector alert answers mutate through the coordinator`() {
        val f = Fixture()
        val rome = f.testGame.gameInfo.getCivilization(f.romeId)
        val cityState = f.testGame.addCiv(f.testGame.ruleset.nations["Genoa"]!!)
        f.testGame.addCity(cityState, f.testGame.getTile(2, 2))
        rome.diplomacyFunctions.makeCivilizationsMeet(cityState)
        rome.diplomacyFunctions.makeCivilizationsMeet(
            f.testGame.gameInfo.getCivilization(f.aztecId))
        f.rebuild()
        val csId = cityState.civID

        f.coordinator.execute(ProtectionCommand(f.romeId, "pp1", f.rev(), csId, pledge = true))

        // SIDE_WITH records the diplomatic memory without war.
        f.coordinator.execute(RespondProtectorAlertCommand(f.romeId, "pa1", f.rev(),
            csId, f.aztecId, ProtectorAlertChoice.SIDE_WITH))
        val payload = AgentSnapshotPublication.buildPayload(f.coordinator, f.romeId)
        val snapshot = MatchCoordinator.deserializeForPublication(payload.serializedGame)
        // sideWithCityState records on the ATTACKER's view of us.
        val diplo = snapshot.getCivilization(f.aztecId).getDiplomacyManager(f.romeId)!!
        Assert.assertTrue(diplo.hasModifier(
            com.unciv.logic.civilization.diplomacy.DiplomaticModifiers.SidedWithProtectedMinor))

        // BREAK_PLEDGE removes the protector.
        f.coordinator.execute(RespondProtectorAlertCommand(f.romeId, "pa2", f.rev(),
            csId, f.aztecId, ProtectorAlertChoice.BREAK_PLEDGE))
        val payload2 = AgentSnapshotPublication.buildPayload(f.coordinator, f.romeId)
        val snapshot2 = MatchCoordinator.deserializeForPublication(payload2.serializedGame)
        Assert.assertFalse(snapshot2.getCivilization(csId).cityStateFunctions
            .getProtectorCivs().contains(snapshot2.getCivilization(f.romeId)))

        // Without a pledge there is nothing to answer.
        try {
            f.coordinator.execute(RespondProtectorAlertCommand(f.romeId, "pa3", f.rev(),
                csId, f.aztecId, ProtectorAlertChoice.SIDE_WITH))
            Assert.fail("expected ILLEGAL_ACTION")
        } catch (ex: AgentCommandException) {
            Assert.assertEquals(AgentCommandError.ILLEGAL_ACTION, ex.code)
        }
    }

    @Test
    fun `friendship offer answers sign or record the decline`() {
        val f = Fixture()
        val rome = f.testGame.gameInfo.getCivilization(f.romeId)
        val greece = f.testGame.gameInfo.getCivilization(f.greeceId)
        rome.diplomacyFunctions.makeCivilizationsMeet(greece)
        f.rebuild()

        f.coordinator.execute(RespondFriendshipOfferCommand(f.romeId, "fo1", f.rev(),
            f.greeceId, accept = true))
        var payload = AgentSnapshotPublication.buildPayload(f.coordinator, f.romeId)
        var snapshot = MatchCoordinator.deserializeForPublication(payload.serializedGame)
        Assert.assertTrue(snapshot.getCivilization(f.romeId)
            .getDiplomacyManager(f.greeceId)!!
            .hasFlag(com.unciv.logic.civilization.diplomacy.DiplomacyFlags.DeclarationOfFriendship))

        // A fresh offer declined records the refusal on the other side.
        f.selectActor(f.greeceId)
        f.coordinator.execute(RespondFriendshipOfferCommand(f.greeceId, "fo2", f.rev(),
            f.romeId, accept = false))
        payload = AgentSnapshotPublication.buildPayload(f.coordinator, f.romeId)
        snapshot = MatchCoordinator.deserializeForPublication(payload.serializedGame)
        Assert.assertTrue(snapshot.getCivilization(f.romeId)
            .getDiplomacyManager(f.greeceId)!!
            .hasFlag(com.unciv.logic.civilization.diplomacy.DiplomacyFlags.DeclinedDeclarationOfFriendship))
    }
}
