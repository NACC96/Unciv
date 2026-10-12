package com.unciv.logic.agents

import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.city.CityFocus
import com.unciv.logic.map.HexCoord
import com.unciv.models.UnitActionType
import com.unciv.models.metadata.BaseRuleset
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.pickerscreens.TechPickerScreen
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActions
import com.unciv.ui.popups.ConfirmPopup
import com.unciv.ui.screens.diplomacyscreen.CityStateDiplomacyTable
import com.unciv.ui.screens.diplomacyscreen.DiplomacyScreen
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.components.input.ActivationListener
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.unciv.view.GameView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Answers
import org.mockito.Mockito

/** Exercises the real view/picker/action entry points against an isolated coordinator graph. */
@RunWith(GdxTestRunner::class)
class TakeoverHumanRoutesTest {
    private fun put(type: Class<*>, receiver: Any, name: String, value: Any?) =
        type.getDeclaredField(name).apply { isAccessible = true }.set(receiver, value)

    private inner class Fixture(configure: (Fixture) -> Unit = {}) {
        val testGame = TestGame(baseRuleset = BaseRuleset.Civ_V_Vanilla)
        val writer = InMemoryCheckpointWriter()
        val rome = testGame.addCiv(testGame.ruleset.nations["Rome"]!!, isPlayer = true)
        val greece = testGame.addCiv(testGame.ruleset.nations["Greece"]!!, isPlayer = true)
        val city: com.unciv.logic.city.City
        val settler: com.unciv.logic.map.mapunit.MapUnit
        val coordinator: MatchCoordinator
        val executor: HumanCommandExecutor
        val screen: WorldScreen
        val errors = ArrayList<String>()
        val view: GameView
        init {
            testGame.gameInfo.gameParameters.baseRuleset = BaseRuleset.Civ_V_Vanilla.fullName
            testGame.makeHexagonalMap(5)
            if (!rome.tech.isResearched("Agriculture")) rome.tech.addTechnology("Agriculture")
            city = testGame.addCity(rome, testGame.getTile(3, 0), initialPopulation = 3)
            city.cityConstructions.constructionQueue.clear()
            settler = testGame.addUnit("Settler", rome, testGame.getTile(-3, 0))
            testGame.gameInfo.currentPlayer = rome.civID
            testGame.gameInfo.currentPlayerCiv = rome
            configure(this)
            val match = AgentMatchState(matchId = "routes", timelineId = "timeline", turnPhase = TurnPhase.AWAITING_DECISIONS)
            match.controllers[rome.civID] = CivilizationController(rome.civID, ControllerKind.HUMAN, null)
            match.controllers[greece.civID] = CivilizationController(greece.civID, ControllerKind.EXTERNAL_AGENT,
                AgentProfile("g", "Bob", "codex", "m", null, "", 1))
            coordinator = MatchCoordinator(testGame.gameInfo, match, writer)
            executor = HumanCommandExecutor(coordinator)
            // The actual host owns asynchronous scheduling/publication. This seam runs only its
            // command factories synchronously, so view callbacks can prove which graph they mutate.
            screen = Mockito.mock(WorldScreen::class.java) { invocation ->
                if (invocation.method.name == "routeThroughAgentSession") {
                    @Suppress("UNCHECKED_CAST")
                    val build = invocation.arguments[0] as (Int) -> List<(Int) -> AgentCommand>
                    executor.executeAll(rome.civID, build)?.let { errors.add(it) }
                    true
                } else Answers.RETURNS_DEFAULTS.answer(invocation)
            }
            put(UncivGame::class.java, UncivGame.Current, "worldScreen", screen)
            view = GameView(testGame.gameInfo, rome)
        }
        fun committed(): GameInfo = MatchCoordinator.deserializeForPublication(coordinator.serializeCommittedGameForPublication())
        fun committedCity() = committed().getCivilization(rome.civID).cities.first { it.id == city.id }
        fun cityView() = view.getCityView(city)
        fun picker(staged: List<String>, selected: String, free: Boolean = false): TechPickerScreen {
            val picker = Mockito.mock(TechPickerScreen::class.java, Answers.CALLS_REAL_METHODS)
            put(TechPickerScreen::class.java, picker, "civInfo", rome)
            put(TechPickerScreen::class.java, picker, "civTech", rome.tech)
            put(TechPickerScreen::class.java, picker, "tempTechsToResearch", ArrayList(staged))
            put(TechPickerScreen::class.java, picker, "selectedTech", testGame.ruleset.technologies[selected])
            put(TechPickerScreen::class.java, picker, "freeTechPick", free)
            put(BaseScreen::class.java, picker, "game", Mockito.mock(UncivGame::class.java))
            return picker
        }
        fun confirm(picker: TechPickerScreen) = TechPickerScreen::class.java.getDeclaredMethod("tryExit")
            .apply { isAccessible = true }.invoke(picker)
    }

    @Test fun `citizen focus and avoid growth commit without mutating the displayed city`() {
        val f = Fixture()
        assertTrue(f.cityView().trySetCityFocus(CityFocus.ProductionFocus))
        assertTrue(f.cityView().tryToggleAvoidGrowth())
        assertTrue(f.errors.toString(), f.errors.isEmpty())
        assertEquals(CityFocus.ProductionFocus, f.committedCity().getCityFocus())
        assertTrue(f.committedCity().avoidGrowth)
        assertEquals(CityFocus.NoFocus, f.city.getCityFocus())
        assertFalse(f.city.avoidGrowth)
        val checkpoint = MatchCoordinator.deserializeForPublication(f.writer.lastGameJson!!)
        assertTrue(checkpoint.getCivilization(f.rome.civID).cities.first().avoidGrowth)
    }

    @Test fun `reset citizens clears committed locks while leaving displayed locks unchanged`() {
        val f = Fixture()
        val tile = f.city.workedTiles.first()
        f.city.lockedTiles.add(tile)
        // Establish the lock on the authoritative graph using an already routed entry point.
        assertTrue(f.cityView().tryLockTile(f.view.getTile(f.testGame.getTile(tile))))
        assertTrue(f.cityView().tryReassignPopulation(resetLocked = true))
        assertTrue(f.errors.toString(), f.errors.isEmpty())
        assertTrue(f.committedCity().lockedTiles.isEmpty())
        assertEquals(setOf(tile), f.city.lockedTiles)
    }

    @Test fun `context menu additions reorder removal and disable settings survive a new snapshot`() {
        val f = Fixture(); val city = f.cityView()
        city.tryAddToQueueConstruction(f.testGame.ruleset.units["Warrior"]!!)
        city.tryAddToQueueConstruction(f.testGame.ruleset.buildings["Monument"]!!, addToTop = true)
        city.tryMoveEntryToEnd(0)
        assertEquals(listOf("Warrior", "Monument"), f.committedCity().cityConstructions.constructionQueue)
        city.tryRemoveAllByName("Warrior")
        city.tryDisableConstruction("Warrior")
        f.view.civView.tryDisableCivConstruction("Monument")
        assertTrue(f.errors.toString(), f.errors.isEmpty())
        assertEquals(listOf("Monument"), f.committedCity().cityConstructions.constructionQueue)
        assertEquals(setOf("Warrior", "Monument"), f.committedCity().disabledConstructions)
        assertTrue("Monument" in f.committed().getCivilization(f.rome.civID).disabledCityConstructions)
        assertTrue(f.city.cityConstructions.constructionQueue.isEmpty())
        assertTrue(f.city.disabledConstructions.isEmpty())
        city.tryEnableConstruction("Warrior")
        f.view.civView.tryEnableCivConstruction("Monument")
        assertTrue(f.committedCity().disabledConstructions.isEmpty())
    }

    @Test fun `research confirm commits the complete staged queue in its displayed order`() {
        val f = Fixture()
        f.confirm(f.picker(listOf("Mining", "Pottery"), "Pottery"))
        assertTrue(f.errors.toString(), f.errors.isEmpty())
        assertEquals(listOf("Mining", "Pottery"), f.committed().getCivilization(f.rome.civID).tech.techsToResearch)
        assertTrue(f.rome.tech.techsToResearch.isEmpty())
    }

    @Test fun `research multi prerequisite replacement removes the old committed queue`() {
        val f = Fixture()
        assertNull(f.executor.executeAll(f.rome.civID) { listOf({ r: Int ->
            SelectResearchCommand(f.rome.civID, "old", r, "Mining", false, false)
        }) })
        f.confirm(f.picker(listOf("Pottery", "Writing"), "Writing"))
        assertTrue(f.errors.toString(), f.errors.isEmpty())
        assertEquals(listOf("Pottery", "Writing"), f.committed().getCivilization(f.rome.civID).tech.techsToResearch)
    }

    @Test fun `found city action consumes committed settler and leaves the displayed graph intact`() {
        val f = Fixture()
        UncivGame.Current.settings.tutorialTasksCompleted.add("Found city")
        val action = UnitActions.getUnitActions(f.settler, UnitActionType.FoundCity).first()
        assertNotNull(action.action)
        action.action!!.invoke()
        assertTrue(f.errors.toString(), f.errors.isEmpty())
        assertNull(f.coordinator.getUnitPosition(f.rome.civID, f.settler.id))
        assertEquals(2, f.committed().getCivilization(f.rome.civID).cities.size)
        assertFalse(f.settler.isDestroyed)
        assertEquals(1, f.rome.cities.size)
        assertEquals(2, MatchCoordinator.deserializeForPublication(f.writer.lastGameJson!!)
            .getCivilization(f.rome.civID).cities.size)
    }

    @Test fun `sessionless citizen and context callbacks retain ordinary gameplay`() {
        val f = Fixture(); put(UncivGame::class.java, UncivGame.Current, "worldScreen", null)
        f.cityView().trySetCityFocus(CityFocus.ProductionFocus)
        f.cityView().tryToggleAvoidGrowth()
        f.cityView().tryAddToQueueConstruction(f.testGame.ruleset.units["Warrior"]!!)
        f.view.civView.tryDisableCivConstruction("Monument")
        assertEquals(CityFocus.ProductionFocus, f.city.getCityFocus())
        assertTrue(f.city.avoidGrowth)
        assertEquals(listOf("Warrior"), f.city.cityConstructions.constructionQueue)
        assertTrue("Monument" in f.rome.disabledCityConstructions)
        assertEquals(0, f.writer.writeCount)
    }

    @Test fun `manual specialist operations use committed slots and leave the display unchanged`() {
        val f = Fixture {
            it.city.cityConstructions.addBuilding(it.testGame.ruleset.buildings["Market"]!!)
            it.city.setCityFocus(CityFocus.Manual)
            it.city.workedTiles.clear()
            it.city.manualSpecialists = false
        }
        val specialist = f.city.population.getMaxSpecialists().keys.first()
        val city = f.cityView()
        assertTrue(city.tryEnableManualSpecialists())
        assertTrue(f.committedCity().manualSpecialists)
        assertTrue(city.tryAssignSpecialist(specialist))
        assertEquals(1, f.committedCity().population.specialistAllocations[specialist])
        assertTrue(city.tryUnassignSpecialist(specialist))
        assertEquals(0, f.committedCity().population.specialistAllocations[specialist])
        assertTrue(city.tryDisableManualSpecialists())
        assertFalse(f.committedCity().manualSpecialists)
        assertTrue(f.errors.toString(), f.errors.isEmpty())
        assertFalse(f.city.manualSpecialists)
        assertTrue(f.city.population.specialistAllocations.isEmpty())
    }

    @Test fun `free tech confirm remains one free choice and preserves ordinary research`() {
        val f = Fixture { it.rome.tech.freeTechs = 1; it.rome.tech.techsToResearch.add("Mining") }
        f.confirm(f.picker(listOf("Mining"), "Pottery", free = true))
        assertTrue(f.errors.toString(), f.errors.isEmpty())
        val tech = f.committed().getCivilization(f.rome.civID).tech
        assertTrue(tech.isResearched("Pottery"))
        assertEquals(0, tech.freeTechs)
        assertEquals(listOf("Mining"), tech.techsToResearch)
        assertFalse(f.rome.tech.isResearched("Pottery"))
        assertEquals(1, f.rome.tech.freeTechs)
        assertEquals(1, f.writer.writeCount)
    }

    @Test fun `research factories retain their copied intent after the picker draft changes`() {
        val f = Fixture()
        var pending: ((Int) -> List<(Int) -> AgentCommand>)? = null
        val screen = Mockito.mock(WorldScreen::class.java) { invocation ->
            if (invocation.method.name == "routeThroughAgentSession") {
                @Suppress("UNCHECKED_CAST")
                val build = invocation.arguments[0] as (Int) -> List<(Int) -> AgentCommand>
                pending = build
                true
            } else Answers.RETURNS_DEFAULTS.answer(invocation)
        }
        put(UncivGame::class.java, UncivGame.Current, "worldScreen", screen)
        val picker = f.picker(listOf("Mining", "Pottery"), "Pottery")
        f.confirm(picker)
        put(TechPickerScreen::class.java, picker, "tempTechsToResearch", arrayListOf("Archery"))
        put(TechPickerScreen::class.java, picker, "selectedTech", f.testGame.ruleset.technologies["Archery"])
        assertNull(f.executor.executeAll(f.rome.civID, pending!!))
        assertEquals(listOf("Mining", "Pottery"), f.committed().getCivilization(f.rome.civID).tech.techsToResearch)
    }

    @Test fun `failed citizen checkpoint rolls back gameplay and keeps the displayed city untouched`() {
        val f = Fixture(); val before = f.coordinator.serializeCommittedGameForPublication()
        f.writer.failNextWrite = true
        assertTrue(f.cityView().trySetCityFocus(CityFocus.ProductionFocus))
        assertEquals(listOf("Checkpoint persistence failed"), f.errors)
        assertEquals(before, f.coordinator.serializeCommittedGameForPublication())
        assertEquals(CityFocus.NoFocus, f.city.getCityFocus())
        assertEquals(0, f.writer.writeCount)
        assertTrue(f.coordinator.isPaused())
    }

    @Test fun `foreign and inactive views cannot apply citizen or construction settings`() {
        val f = Fixture()
        val inactive = GameView(f.testGame.gameInfo, f.greece)
        assertFalse(inactive.getCityView(f.city).trySetCityFocus(CityFocus.ProductionFocus))
        inactive.civView.tryDisableCivConstruction("Warrior")
        f.view.getCivView(f.greece).tryDisableCivConstruction("Warrior")
        assertTrue(f.greece.disabledCityConstructions.isEmpty())
        assertTrue(f.city.disabledConstructions.isEmpty())
        assertEquals(0, f.writer.writeCount)
        assertNotNull(f.executor.executeAll(f.greece.civID) { listOf({ r: Int ->
            CityCitizensCommand(f.greece.civID, "foreign", r, f.city.id, CitizenOp.SET_FOCUS, focus = "ProductionFocus")
        }) })
        assertEquals(0, f.coordinator.getStateRevision())
    }

    @Test fun `promise cancellation does not route founding and confirmation does`() {
        val f = Fixture {
            it.testGame.addCity(it.greece, it.testGame.getTile(1, 2))
            it.rome.diplomacyFunctions.makeCivilizationsMeet(it.greece)
            it.greece.getDiplomacyManager(it.rome)!!.setFlag(DiplomacyFlags.AgreedToNotSettleNearUs, 20)
        }
        var confirm: (() -> Unit)? = null
        Mockito.mockConstruction(ConfirmPopup::class.java) { _, context ->
            @Suppress("UNCHECKED_CAST")
            val action = context.arguments().last { it is Function0<*> } as () -> Unit
            confirm = action
        }.use {
            UnitActions.getUnitActions(f.settler, UnitActionType.FoundCity).first().action!!.invoke()
            assertNotNull(confirm)
            assertEquals(0, f.writer.writeCount)
            assertNotNull(f.coordinator.getUnitPosition(f.rome.civID, f.settler.id))
            assertFalse(f.settler.isDestroyed)
            // Cancelling simply leaves the captured callback uncalled; accepting invokes that exact callback.
            confirm!!.invoke()
        }
        assertTrue(f.errors.toString(), f.errors.isEmpty())
        assertNull(f.coordinator.getUnitPosition(f.rome.civID, f.settler.id))
        assertFalse(f.settler.isDestroyed)
    }

    @Test fun `saved promotions copy authoritative unit and checkbox preference is durable`() {
        var unitId = -1
        val f = Fixture {
            val unit = it.testGame.addUnit("Warrior", it.rome, it.city.getCenterTile())
            unit.promotions.addPromotion("Shock I")
            unitId = unit.id
        }
        assertNull(f.executor.executeAll(f.rome.civID) { listOf({ r: Int ->
            SaveUnitPromotionDefaultsCommand(f.rome.civID, "save-default", r, unitId)
        }) })
        val saved = f.committedCity()
        assertEquals(setOf("Shock I"), saved.unitToPromotions["Warrior"]!!.promotions)
        assertEquals(true, saved.unitShouldUseSavedPromotion["Warrior"])
        assertTrue(f.city.unitToPromotions.isEmpty())
        assertTrue(f.cityView().trySetUnitShouldUseSavedPromotion("Warrior", false))
        assertTrue(f.errors.toString(), f.errors.isEmpty())
        assertEquals(false, f.committedCity().unitShouldUseSavedPromotion["Warrior"])
        assertTrue(f.city.unitShouldUseSavedPromotion.isEmpty())
        val before = f.coordinator.serializeCommittedGameForPublication()
        assertNotNull(f.executor.executeAll(f.rome.civID) { listOf({ r: Int ->
            SetCityUnitSavedPromotionCommand(f.rome.civID, "invalid-default", r, f.city.id, "Scout", true)
        }) })
        assertEquals(before, f.coordinator.serializeCommittedGameForPublication())
    }

    private fun giftFixture(influence: Float = 65f, gold: Int = 500): Pair<Fixture, String> {
        var cityStateId = ""
        val f = Fixture {
            val cityState = it.testGame.addCiv(it.testGame.ruleset.nations["Genoa"]!!)
            cityStateId = cityState.civID
            val city = it.testGame.addCity(cityState, it.testGame.getTile(-1, 3))
            val tile = it.testGame.getTile(0, 3)
            it.testGame.addTileToCity(city, tile)
            tile.setOwningCity(city)
            tile.tileResource = it.testGame.ruleset.tileResources["Gold Ore"]!!
            tile.queueImprovement("Farm", 3)
            if (!cityState.tech.isResearched("Mining")) cityState.tech.addTechnology("Mining")
            it.rome.diplomacyFunctions.makeCivilizationsMeet(cityState)
            cityState.getDiplomacyManager(it.rome)!!.setInfluence(influence)
            it.rome.addGold(gold - it.rome.gold)
        }
        return f to cityStateId
    }

    private fun Actor.descendants(): Sequence<Actor> = sequence {
        yield(this@descendants)
        if (this@descendants is Group) children.forEach { yieldAll(it.descendants()) }
    }

    @Test fun `city state improvement gift button commits cost resource and cleared work together`() {
        val (f, cityStateId) = giftFixture()
        Fonts.fontImplementation = com.unciv.dev.FontDesktop()
        ImageGetter.setNewRuleset(f.testGame.ruleset)
        val diplomacy = Mockito.mock(DiplomacyScreen::class.java) { invocation ->
            when (invocation.method.name.substringBefore('$')) {
                "getViewingCiv" -> f.rome
                "getRelationshipTable" -> Table()
                "rightSideLabelWidth" -> 300f
                else -> Answers.RETURNS_DEFAULTS.answer(invocation)
            }
        }
        val tableBuilder = CityStateDiplomacyTable(diplomacy)
        val cityState = f.testGame.gameInfo.getCivilization(cityStateId)
        val table = CityStateDiplomacyTable::class.java.getDeclaredMethod("getImprovementGiftTable",
            com.unciv.logic.civilization.Civilization::class.java).apply { isAccessible = true }
            .invoke(tableBuilder, cityState) as Table
        val button = table.descendants().filterIsInstance<TextButton>().first { it.text.toString().startsWith("Build ") }
        button.listeners.filterIsInstance<ActivationListener>().single()
            .tap(InputEvent().apply { listenerActor = button }, 1f, 1f, 1, 0)
        assertTrue(f.errors.toString(), f.errors.isEmpty())
        val committed = f.committed()
        assertEquals(300, committed.getCivilization(f.rome.civID).gold)
        assertEquals("Mine", committed.tileMap[HexCoord(0, 3)].improvement)
        assertFalse(committed.tileMap[HexCoord(0, 3)].hasImprovementInProgress())
        assertEquals(500, f.rome.gold)
        assertNull(f.testGame.getTile(0, 3).improvement)
        assertTrue(f.testGame.getTile(0, 3).hasImprovementInProgress())
        val saved = MatchCoordinator.deserializeForPublication(f.writer.lastGameJson!!)
        assertEquals("Mine", saved.tileMap[HexCoord(0, 3)].improvement)
        assertEquals(committed.getCivilization(cityStateId).detailedCivResources.map { it.toString() },
            saved.getCivilization(cityStateId).detailedCivResources.map { it.toString() })
        assertEquals(1, f.writer.writeCount)
    }

    @Test fun `invalid city state gifts and checkpoint failure preserve the whole prior game`() {
        for ((influence, gold) in listOf(59f to 500, 65f to 199)) {
            val (f, cityStateId) = giftFixture(influence, gold)
            val before = f.coordinator.serializeCommittedGameForPublication()
            assertNotNull(f.executor.executeAll(f.rome.civID) { listOf({ r: Int ->
                GiftImprovementToCityStateCommand(f.rome.civID, "invalid-gift", r, cityStateId, HexCoord(0, 3), "Mine")
            }) })
            assertEquals(before, f.coordinator.serializeCommittedGameForPublication())
            assertEquals(0, f.writer.writeCount)
        }
        val (f, cityStateId) = giftFixture()
        val before = f.coordinator.serializeCommittedGameForPublication()
        for ((tile, improvement) in listOf(HexCoord(3, 0) to "Mine", HexCoord(0, 3) to "Farm")) {
            assertNotNull(f.executor.executeAll(f.rome.civID) { listOf({ r: Int ->
                GiftImprovementToCityStateCommand(f.rome.civID, "bad-$tile", r, cityStateId, tile, improvement)
            }) })
            assertEquals(before, f.coordinator.serializeCommittedGameForPublication())
        }
        f.writer.failNextWrite = true
        assertNotNull(f.executor.executeAll(f.rome.civID) { listOf({ r: Int ->
            GiftImprovementToCityStateCommand(f.rome.civID, "failed-gift", r, cityStateId, HexCoord(0, 3), "Mine")
        }) })
        assertEquals(before, f.coordinator.serializeCommittedGameForPublication())
        assertEquals(0, f.writer.writeCount)
    }
}
