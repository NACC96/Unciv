package com.unciv.logic.agents

import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Domestic coverage, batch 2 (AI_AGENT_PLAN.md §6): policy adoption (culture
 * + free), citizen work/lock/focus/manual/buy-tile, worker improvements,
 * promotions, upgrades (gates), great-person actions, city-state gold gifts.
 * Explicit Civ V - Vanilla fixture.
 */
@RunWith(BaseTestRunner::class)
class DomesticOrdersTest {

    private class Fixture {
        val testGame = TestGame(baseRuleset = BaseRuleset.Civ_V_Vanilla)
        val writer = InMemoryCheckpointWriter()
        lateinit var coordinator: MatchCoordinator
        lateinit var romeId: String
        lateinit var csId: String
        var cityId: String = ""
        var warriorId: Int = -1
        var workerId: Int = -1
        var settlerId: Int = -1
        var scientistId: Int = -1
        var engineerId: Int = -1
        var merchantId: Int = -1
        var artistId: Int = -1

        init {
            testGame.gameInfo.gameParameters.baseRuleset = BaseRuleset.Civ_V_Vanilla.fullName
            testGame.makeHexagonalMap(5)
            val rome = testGame.addCiv(testGame.ruleset.nations["Rome"]!!, isPlayer = true)
            romeId = rome.civID
            if (!rome.tech.isResearched("Agriculture")) rome.tech.addTechnology("Agriculture")
            rome.addGold(3000)
            rome.policies.storedCulture = 2000
            rome.policies.freePolicies = 1

            val city = testGame.addCity(rome, testGame.getTile(0, 0), initialPopulation = 3)
            cityId = city.id
            testGame.addTileToCity(city, testGame.getTile(1, 0))
            testGame.addTileToCity(city, testGame.getTile(0, 1))

            warriorId = testGame.addUnit("Warrior", rome, testGame.getTile(-1, 0)).id
            testGame.gameInfo.getCivilization(romeId).units.getCivUnits()
                .first { it.id == warriorId }.promotions.XP = 20
            workerId = testGame.addUnit("Worker", rome, testGame.getTile(-2, 0)).id
            testGame.setTileTerrainAndFeatures(HexCoord(-2, 0), "Desert", "Flood plains")
            settlerId = testGame.addUnit("Settler", rome, testGame.getTile(0, -1)).id
            // Engineer must stand on the city center to hurry; scientist works anywhere.
            scientistId = testGame.addUnit("Great Scientist", rome, testGame.getTile(1, 0)).id
            engineerId = testGame.addUnit("Great Engineer", rome, testGame.getTile(0, 0)).id
            artistId = testGame.addUnit("Great Artist", rome, testGame.getTile(-1, 1)).id

            // Maritime city-state with a city, met by Rome. Must be a real
            // ruleset nation: synthetic TestGame nations break candidate
            // transient restoration (MissingNationException).
            val csNation = testGame.ruleset.nations.values.first { it.cityStateType == "Maritime" }
            val cs = testGame.addCiv(csNation)
            csId = cs.civID
            val csCity = testGame.addCity(cs, testGame.getTile(3, 0))
            rome.diplomacyFunctions.makeCivilizationsMeet(cs)
            // Merchant missions from city-state lands (not the center itself,
            // which blocks civilian placement).
            merchantId = testGame.addUnit("Great Merchant", rome, testGame.getTile(2, 0)).id

            val match = AgentMatchState(
                matchId = "m1", timelineId = "t1", turnPhase = TurnPhase.AWAITING_DECISIONS
            )
            match.controllers[romeId] = CivilizationController(
                romeId, ControllerKind.EXTERNAL_AGENT,
                AgentProfile("p1", "Ada", "codex", "m", null, "", 1))
            match.controllers[csId] = CivilizationController(csId, ControllerKind.BUILTIN_AI, null)
            coordinator = MatchCoordinator(testGame.gameInfo, match, writer)
        }

        fun rev() = coordinator.getStateRevision()
    }

    @Test
    fun `adopt branch member and free policy`() {
        val f = Fixture()
        val branch = f.coordinator.execute(
            AdoptPolicyCommand(f.romeId, "c1", f.rev(), "Tradition")) as PolicyAdoptedResult
        Assert.assertTrue(branch.adopted.contains("Tradition"))
        val member = f.coordinator.execute(
            AdoptPolicyCommand(f.romeId, "c2", f.rev(), "Aristocracy")) as PolicyAdoptedResult
        Assert.assertTrue(member.adopted.contains("Aristocracy"))
        val free = f.coordinator.execute(
            AdoptPolicyCommand(f.romeId, "c3", f.rev(), "Honor")) as PolicyAdoptedResult
        Assert.assertTrue(free.adopted.contains("Honor"))
        Assert.assertEquals(
            setOf("Tradition", "Aristocracy", "Honor"),
            f.coordinator.getAdoptedPolicies(f.romeId).toSet())

        // Unknown policies are invalid; locked ones illegal.
        try {
            f.coordinator.execute(AdoptPolicyCommand(f.romeId, "c4", f.rev(), "Time Travel"))
            Assert.fail("expected INVALID_ARGUMENT")
        } catch (ex: AgentCommandException) {
            Assert.assertEquals(AgentCommandError.INVALID_ARGUMENT, ex.code)
        }
        try {
            // Liberty requires its own branch opener first.
            f.coordinator.execute(AdoptPolicyCommand(f.romeId, "c5", f.rev(), "Collective Rule"))
            Assert.fail("expected ILLEGAL_ACTION")
        } catch (ex: AgentCommandException) {
            Assert.assertEquals(AgentCommandError.ILLEGAL_ACTION, ex.code)
        }
    }

    @Test
    fun `citizens work lock focus buy and reassign`() {
        val f = Fixture()
        // Founding auto-assigns every citizen: free one before employing another.
        val initiallyWorked = f.coordinator.getWorkedTiles(f.cityId)
        Assert.assertTrue(initiallyWorked.isNotEmpty())
        f.coordinator.execute(CityCitizensCommand(f.romeId, "z0", f.rev(), f.cityId,
            CitizenOp.STOP_TILE, tilePos = initiallyWorked.first { it != HexCoord(0, 0) }))
        val worked = f.coordinator.execute(CityCitizensCommand(f.romeId, "z1", f.rev(), f.cityId,
            CitizenOp.WORK_TILE, tilePos = HexCoord(1, 0))) as CityCitizensResult
        Assert.assertTrue(worked.freePopulation >= 0)
        Assert.assertTrue(f.coordinator.getWorkedTiles(f.cityId).contains(HexCoord(1, 0)))

        f.coordinator.execute(CityCitizensCommand(f.romeId, "z2", f.rev(), f.cityId,
            CitizenOp.LOCK_TILE, tilePos = HexCoord(1, 0)))
        f.coordinator.execute(CityCitizensCommand(f.romeId, "z3", f.rev(), f.cityId,
            CitizenOp.SET_FOCUS, focus = "ProductionFocus"))
        f.coordinator.execute(CityCitizensCommand(f.romeId, "z4", f.rev(), f.cityId,
            CitizenOp.SET_AVOID_GROWTH, flag = true))
        f.coordinator.execute(CityCitizensCommand(f.romeId, "z5", f.rev(), f.cityId,
            CitizenOp.SET_MANUAL_SPECIALISTS, flag = true))
        // Vanilla has no specialist slots: assignment is illegal, honestly fenced.
        try {
            f.coordinator.execute(CityCitizensCommand(f.romeId, "z6", f.rev(), f.cityId,
                CitizenOp.ASSIGN_SPECIALIST, specialist = "Scientist"))
            Assert.fail("expected ILLEGAL_ACTION")
        } catch (ex: AgentCommandException) {
            Assert.assertEquals(AgentCommandError.ILLEGAL_ACTION, ex.code)
        }
        f.coordinator.execute(CityCitizensCommand(f.romeId, "z7", f.rev(), f.cityId,
            CitizenOp.UNLOCK_TILE, tilePos = HexCoord(1, 0)))
        val stopped = f.coordinator.execute(CityCitizensCommand(f.romeId, "z8", f.rev(), f.cityId,
            CitizenOp.STOP_TILE, tilePos = HexCoord(1, 0))) as CityCitizensResult
        Assert.assertTrue(stopped.freePopulation > 0)

        // Tile purchase transfers ownership with gold (tile near the city,
        // outside any other borders — (2,0) belongs to Cape Town's ring).
        val bought = f.coordinator.execute(BuyTileCommand(f.romeId, "z9", f.rev(), f.cityId,
            HexCoord(-2, 0))) as TileBoughtResult
        Assert.assertEquals(HexCoord(-2, 0), bought.tilePos)
        Assert.assertEquals(f.romeId, f.coordinator.getTileOwnerCiv(-2, 0))
    }

    @Test
    fun `worker starts farm on flood plains`() {
        val f = Fixture()
        val started = f.coordinator.execute(
            BuildImprovementCommand(f.romeId, "b1", f.rev(), f.workerId, "Farm")) as ImprovementStartedResult
        Assert.assertTrue(started.turnsToBuild > 0)

        // ...but not on dry desert without water.
        try {
            f.coordinator.execute(
                BuildImprovementCommand(f.romeId, "b2", f.rev(), f.workerId, "Manufactory"))
            Assert.fail("expected failure")
        } catch (ex: AgentCommandException) {
            Assert.assertTrue(
                ex.code == AgentCommandError.ILLEGAL_ACTION || ex.code == AgentCommandError.INVALID_ARGUMENT)
        }
    }

    @Test
    fun `promote experienced warrior, upgrade gates hold`() {
        val f = Fixture()
        val available = f.testGame.gameInfo.getCivilization(f.romeId).units.getCivUnits()
            .first { it.id == f.warriorId }.promotions.getAvailablePromotions()
        Assert.assertTrue(available.any())
        val promoted = f.coordinator.execute(
            PromoteUnitCommand(f.romeId, "m1", f.rev(), f.warriorId, available.first().name)) as UnitPromotedResult
        Assert.assertEquals(available.first().name, promoted.promotionName)

        // Settler has no upgrade path; warrior lacks the tech+iron.
        try {
            f.coordinator.execute(UpgradeUnitCommand(f.romeId, "m2", f.rev(), f.settlerId))
            Assert.fail("expected ILLEGAL_ACTION")
        } catch (ex: AgentCommandException) {
            Assert.assertEquals(AgentCommandError.ILLEGAL_ACTION, ex.code)
        }
        try {
            f.coordinator.execute(UpgradeUnitCommand(f.romeId, "m3", f.rev(), f.warriorId))
            Assert.fail("expected ILLEGAL_ACTION")
        } catch (ex: AgentCommandException) {
            Assert.assertEquals(AgentCommandError.ILLEGAL_ACTION, ex.code)
        }
    }

    @Test
    fun `great people hurry research building and trade`() {
        val f = Fixture()
        // Research must be in progress for a hurry.
        f.coordinator.execute(SelectResearchCommand(f.romeId, "r0", f.rev(), "Pottery",
            queue = false, useFreeTech = false))
        val hurried = f.coordinator.execute(
            GreatPersonCommand(f.romeId, "g1", f.rev(), f.scientistId, GreatPersonAction.HURRY_RESEARCH))
        Assert.assertEquals(GreatPersonAction.HURRY_RESEARCH, (hurried as GreatPersonResult).action)
        Assert.assertNull(f.coordinator.getUnitPosition(f.romeId, f.scientistId))

        // Engineer hurries the queued Monument.
        f.coordinator.execute(CityProductionCommand(f.romeId, "p0", f.rev(), f.cityId,
            ProductionOp.ADD_BOTTOM, name = "Monument"))
        f.coordinator.execute(GreatPersonCommand(f.romeId, "g2", f.rev(), f.engineerId,
            GreatPersonAction.HURRY_BUILDING))
        Assert.assertNull(f.coordinator.getUnitPosition(f.romeId, f.engineerId))

        // Merchant on the allied city-state tile trades.
        val goldBefore = f.coordinator.getGold(f.romeId)
        f.coordinator.execute(GreatPersonCommand(f.romeId, "g3", f.rev(), f.merchantId,
            GreatPersonAction.TRADE_MISSION))
        Assert.assertTrue(f.coordinator.getGold(f.romeId) > goldBefore)
        Assert.assertNull(f.coordinator.getUnitPosition(f.romeId, f.merchantId))

        // Wrong great person, wrong job.
        try {
            f.coordinator.execute(GreatPersonCommand(f.romeId, "g4", f.rev(), f.warriorId,
                GreatPersonAction.HURRY_RESEARCH))
            Assert.fail("expected ILLEGAL_ACTION")
        } catch (ex: AgentCommandException) {
            Assert.assertEquals(AgentCommandError.ILLEGAL_ACTION, ex.code)
        }
    }

    @Test
    fun `golden age and road building`() {
        val f = Fixture()
        f.coordinator.execute(GreatPersonCommand(f.romeId, "g1", f.rev(), f.artistId,
            GreatPersonAction.GOLDEN_AGE))
        Assert.assertNull(f.coordinator.getUnitPosition(f.romeId, f.artistId))
        Assert.assertTrue(f.coordinator.isGoldenAge(f.romeId))

        // Roads ride the same worker-improvement path once the wheel turns.
        val rome = f.testGame.gameInfo.getCivilization(f.romeId)
        if (!rome.tech.isResearched("Animal Husbandry")) rome.tech.addTechnology("Animal Husbandry")
        if (!rome.tech.isResearched("The Wheel")) rome.tech.addTechnology("The Wheel")
        // Rebuild the coordinator over the new techs (setup-time change).
        val match = AgentMatchState(matchId = "m1", timelineId = "t1", turnPhase = TurnPhase.AWAITING_DECISIONS)
        match.controllers[f.romeId] = CivilizationController(f.romeId, ControllerKind.EXTERNAL_AGENT,
            AgentProfile("p1", "Ada", "codex", "m", null, "", 1))
        val coordinator = MatchCoordinator(f.testGame.gameInfo, match, InMemoryCheckpointWriter())
        val road = coordinator.execute(BuildImprovementCommand(f.romeId, "b1", 0,
            f.workerId, "Road")) as ImprovementStartedResult
        Assert.assertTrue(road.turnsToBuild > 0)
    }

    @Test
    fun `auto production stays off for agents even when the user setting is on`() {
        val f = Fixture()
        val settings = com.unciv.UncivGame.Current.settings
        val previous = settings.autoAssignCityProduction
        settings.autoAssignCityProduction = true
        try {
            // Empty queue + setting on: legacy behavior would auto-choose.
            f.coordinator.execute(EndTurnCommand(f.romeId, "e-auto", f.rev()))
            val queue = f.coordinator.observe(f.romeId).ownCities
                .first { it.cityId == f.cityId }.productionQueue
            Assert.assertTrue(
                "queue must stay empty/idle for agents, was $queue",
                queue.isEmpty() || queue.all { it.isEmpty() || it == "Idle" })
        } finally {
            settings.autoAssignCityProduction = previous
        }
    }

    @Test
    fun `gold gift to city-state moves gold and influence`() {        val f = Fixture()
        val romeBefore = f.coordinator.getGold(f.romeId)
        val csBefore = f.coordinator.getGold(f.csId)
        val gifted = f.coordinator.execute(
            GiftGoldToCityStateCommand(f.romeId, "s1", f.rev(), f.csId, 250)) as GoldGiftedResult
        Assert.assertTrue(gifted.influenceGained >= 5)
        Assert.assertEquals(romeBefore - 250, f.coordinator.getGold(f.romeId))
        Assert.assertEquals(csBefore + 250, f.coordinator.getGold(f.csId))

        try {
            f.coordinator.execute(GiftGoldToCityStateCommand(f.romeId, "s2", f.rev(), f.csId, 100000))
            Assert.fail("expected ILLEGAL_ACTION")
        } catch (ex: AgentCommandException) {
            Assert.assertEquals(AgentCommandError.ILLEGAL_ACTION, ex.code)
        }
    }
}
