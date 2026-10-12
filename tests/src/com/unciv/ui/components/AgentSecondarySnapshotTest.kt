package com.unciv.ui.components

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Graphics
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.dev.FontDesktop
import com.unciv.logic.agents.*
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.tilesets.TileSetCache
import com.unciv.ui.components.fonts.FontRulesetIcons
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import com.unciv.ui.audio.MusicController
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.basescreen.UncivStage
import com.unciv.ui.screens.cityscreen.CityScreen
import com.unciv.ui.screens.diplomacyscreen.DiplomacyScreen
import com.unciv.ui.screens.pickerscreens.PolicyPickerScreen
import com.unciv.ui.screens.pickerscreens.PromotionPickerScreen
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.ui.screens.worldscreen.UndoHandler
import com.unciv.ui.screens.worldscreen.unit.UnitTable
import com.unciv.ui.screens.worldscreen.worldmap.WorldMapHolder
import com.unciv.view.GameView
import org.junit.Assert.*
import org.junit.AfterClass
import com.unciv.utils.Dispatcher
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Answers
import org.mockito.Mockito

/** Real secondary screens and screen stack, real coordinator commits; only native rendering is mocked. */
@RunWith(GdxTestRunner::class)
class AgentSecondarySnapshotTest {
    companion object {
        @AfterClass @JvmStatic fun releaseTestDispatchers() {
            // Our real screens initialize the GL dispatcher on this headless app.
            // Let queued work finish, and let the next test class bind to its own app.
            Dispatcher.stopThreadPools()
        }
    }

    private fun put(type: Class<*>, name: String, target: Any, value: Any) =
        type.getDeclaredField(name).apply { isAccessible = true }.set(target, value)

    private inner class Fixture(spectatorView: Boolean = false) {
        val test = TestGame(baseRuleset = BaseRuleset.Civ_V_Vanilla)
        val rome = test.addCiv(test.ruleset.nations["Rome"]!!, isPlayer = true)
        val greece = test.addCiv(test.ruleset.nations["Greece"]!!)
        val spectator = test.addCiv(test.ruleset.nations[Constants.spectator]!!)
        val viewer = if (spectatorView) spectator else rome
        val world = Mockito.mock(WorldScreen::class.java, Answers.CALLS_REAL_METHODS)
        val session = Mockito.mock(AgentMatchSession::class.java)
        val writer = InMemoryCheckpointWriter()
        val coordinator: MatchCoordinator
        val cityId: String
        val unitId: Int
        val app = UncivGame.Current
        init {
            test.gameInfo.gameParameters.baseRuleset = BaseRuleset.Civ_V_Vanilla.fullName
            test.makeHexagonalMap(4)
            cityId = test.addCity(rome, test.getTile(0, 0)).id
            unitId = test.addUnit("Warrior", rome, test.getTile(-1, 0)).apply { promotions.XP = 10 }.id
            rome.addGold(100)
            test.addUnit("Warrior", greece, test.getTile(3, 0))
            rome.diplomacyFunctions.makeCivilizationsMeet(greece)
            rome.policies.freePolicies = 2
            test.gameInfo.currentPlayer = rome.civID
            test.gameInfo.currentPlayerCiv = rome
            val state = AgentMatchState(matchId = "secondary", timelineId = "secondary", turnPhase = TurnPhase.AWAITING_DECISIONS)
            state.controllers[rome.civID] = CivilizationController(rome.civID, ControllerKind.HUMAN, null)
            state.controllers[greece.civID] = CivilizationController(greece.civID, ControllerKind.BUILTIN_AI, null)
            coordinator = MatchCoordinator(test.gameInfo, state, writer)
            app.settings.showTutorials = false
            app.settings.tutorialTasksCompleted.add("Enter city screen")
            app.musicController = Mockito.mock(MusicController::class.java)
            Fonts.fontImplementation = FontDesktop()
            ImageGetter.setNewRuleset(test.ruleset)
            TileSetCache.loadTileSetConfigs()
            BaseScreen.setSkin()
            val stage = UncivStage(com.badlogic.gdx.utils.viewport.ScreenViewport())
            stage.viewport.update(960, 600, true)
            put(BaseScreen::class.java, "game", world, app)
            put(BaseScreen::class.java, "stage", world, stage)
            put(WorldScreen::class.java, "gameInfo", world, test.gameInfo)
            put(WorldScreen::class.java, "viewingCiv", world, viewer)
            put(WorldScreen::class.java, "selectedCiv", world, rome)
            put(WorldScreen::class.java, "selectedGameView", world, GameView(test.gameInfo, rome, spectatorView))
            put(WorldScreen::class.java, "agentPublicationFence", world, AgentPublicationFence())
            put(WorldScreen::class.java, "mapHolder", world, Mockito.mock(WorldMapHolder::class.java))
            put(WorldScreen::class.java, "bottomUnitTable", world, Mockito.mock(UnitTable::class.java))
            put(WorldScreen::class.java, "undoHandler", world, Mockito.mock(UndoHandler::class.java))
            world.agentSession = session
            Mockito.`when`(session.isHumanInputAllowed()).thenReturn(!spectatorView)
            Mockito.doNothing().`when`(world).resize(Mockito.anyInt(), Mockito.anyInt())
            put(UncivGame::class.java, "worldScreen", app, world)
            @Suppress("UNCHECKED_CAST")
            val stack = UncivGame::class.java.getDeclaredField("screenStack").apply { isAccessible = true }.get(app) as ArrayDeque<BaseScreen>
            stack.addLast(world)
            put(com.badlogic.gdx.Game::class.java, "screen", app, world)
        }
        fun publish() {
            val payload = AgentSnapshotPublication.buildPayload(coordinator, world.currentViewerCivId())
            val committed = AgentSnapshotPublication.deserializeGame(payload)
            world.installAgentSnapshot(committed, AgentSnapshotPublication.resolveSelection(committed, payload), payload.generation, payload.viewerCivId)
        }
        fun command(command: (Int) -> AgentCommand) {
            assertNull(HumanCommandExecutor(coordinator).executeAll(rome.civID) { listOf(command) })
            assertTrue(writer.writeCount > 0)
        }
        fun openCity() = app.pushScreen { CityScreen(world.selectedGameView.getCityView(world.gameInfo.getCities().first { it.id == cityId })) }
    }
    private fun withFixture(spectator: Boolean = false, block: (Fixture) -> Unit) {
        val previousGraphics = Gdx.graphics
        val previousTileSets = TileSetCache.toMap()
        val previousIconNames = FontRulesetIcons.rulesetObjectNameToChar.toMap()
        val previousIconActors = FontRulesetIcons.charToRulesetImageActor.toMap()
        val graphics = Mockito.mock(Graphics::class.java)
        Mockito.`when`(graphics.width).thenReturn(960)
        Mockito.`when`(graphics.height).thenReturn(600)
        Gdx.graphics = graphics
        try {
            Mockito.mockConstruction(SpriteBatch::class.java).use { block(Fixture(spectator)) }
        } finally {
            Gdx.graphics = previousGraphics
            TileSetCache.clear()
            TileSetCache.putAll(previousTileSets)
            FontRulesetIcons.rulesetObjectNameToChar.clear()
            FontRulesetIcons.rulesetObjectNameToChar.putAll(previousIconNames)
            FontRulesetIcons.charToRulesetImageActor.clear()
            FontRulesetIcons.charToRulesetImageActor.putAll(previousIconActors)
        }
    }

    @Test fun `committed production refreshes retained city model and queue`() = withFixture { f ->
        val old = f.openCity()
        f.command { CityProductionCommand(f.rome.civID, "queue", it, f.cityId, ProductionOp.ADD_BOTTOM, name = "Monument") }
        assertFalse(old.cityView.constructions.constructionQueue.contains("Monument"))
        f.publish()
        val fresh = f.app.screen as CityScreen
        assertNotSame(old, fresh)
        assertSame(f.world.gameInfo, fresh.cityView.getCity().civ.gameInfo)
        assertTrue(fresh.cityView.constructions.constructionQueue.contains("Monument"))
        f.app.popScreen()
        assertSame(f.world, f.app.screen)
    }

    @Test fun `committed policy rebuilds choices from adopted state`() = withFixture { f ->
        val old = f.app.pushScreen { PolicyPickerScreen(f.rome, true, "Tradition") }
        f.command { AdoptPolicyCommand(f.rome.civID, "policy", it, "Tradition") }
        f.publish()
        val fresh = f.app.screen as PolicyPickerScreen
        assertNotSame(old, fresh)
        assertTrue(fresh.viewingCiv.policies.isAdopted("Tradition"))
        assertEquals(1, fresh.viewingCiv.policies.freePolicies)
    }

    @Test fun `committed unit rename refreshes promotion picker unit`() = withFixture { f ->
        val unit = f.rome.units.getCivUnits().first { it.id == f.unitId }
        val old = f.app.pushScreen { PromotionPickerScreen(unit, false) }
        f.command { RenameUnitCommand(f.rome.civID, "rename", it, f.unitId, "Veteran") }
        f.publish()
        val fresh = f.app.screen as PromotionPickerScreen
        assertNotSame(old, fresh)
        assertEquals("Veteran", fresh.unit.instanceName)
        assertSame(f.world.gameInfo, fresh.unit.civ.gameInfo)
    }

    @Test fun `committed diplomacy screen uses current civilization graph`() = withFixture { f ->
        val old = f.app.pushScreen { DiplomacyScreen(f.world.selectedGameView.civView, f.world.selectedGameView.getForeignCivView(f.greece)) }
        f.command { DeclareWarCommand(f.rome.civID, "war", it, f.greece.civID) }
        f.publish()
        val fresh = f.app.screen as DiplomacyScreen
        assertNotSame(old, fresh)
        val viewField = DiplomacyScreen::class.java.getDeclaredField("viewingCivView").apply { isAccessible = true }
        val civ = (viewField.get(fresh) as com.unciv.view.CivView).getCiv()
        assertSame(f.world.gameInfo, civ.gameInfo)
        assertTrue(civ.isAtWarWith(f.world.gameInfo.getCivilization(f.greece.civID)))
    }

    @Test fun `closed screen cannot reopen and covered screen refreshes on return`() = withFixture { f ->
        val city = f.openCity()
        val cover = f.app.pushScreen { PolicyPickerScreen(f.rome, true) }
        f.command { CityProductionCommand(f.rome.civID, "covered", it, f.cityId, ProductionOp.ADD_BOTTOM, name = "Monument") }
        f.publish()
        assertNotSame(cover, f.app.screen)
        f.app.popScreen()
        val fresh = f.app.screen as CityScreen
        assertNotSame(city, fresh)
        assertTrue(fresh.cityView.constructions.constructionQueue.contains("Monument"))
        f.command { RenameUnitCommand(f.rome.civID, "late", it, f.unitId, "Late result") }
        val queued = AgentSnapshotPublication.buildPayload(f.coordinator, f.rome.civID)
        val deliveredLater = AgentSnapshotPublication.deserializeGame(queued)
        f.app.popScreen()
        f.world.installAgentSnapshot(deliveredLater, AgentSnapshotPublication.resolveSelection(deliveredLater, queued), queued.generation, queued.viewerCivId)
        assertSame(f.world, f.app.screen)
        assertSame(deliveredLater, f.world.gameInfo)
    }

    @Test fun `rejected command keeps open city and selections`() = withFixture { f ->
        val city = f.openCity()
        city.selectConstruction("Warrior")
        val error = HumanCommandExecutor(f.coordinator).executeAll(f.rome.civID) { listOf({ r ->
            CityProductionCommand(f.rome.civID, "bad", r, "removed-city", ProductionOp.ADD_BOTTOM, name = "Monument")
        }) }
        assertNotNull(error)
        assertSame(city, f.app.screen)
        assertEquals("Warrior", city.selectedConstruction?.name)
        assertEquals(0, f.writer.writeCount)
    }

    @Test fun `spectator selected nation screens remain read only and refresh as actual spectator`() = withFixture(spectator = true) { f ->
        val constructors: List<() -> BaseScreen> = listOf(
            { CityScreen(f.world.selectedGameView.getCityView(f.world.gameInfo.getCities().first { it.id == f.cityId })) },
            { DiplomacyScreen(f.world.selectedGameView.civView, f.world.selectedGameView.getForeignCivView(f.world.gameInfo.getCivilization(f.greece.civID))) },
            { PolicyPickerScreen(f.world.selectedCiv, false) },
            { PromotionPickerScreen(f.world.selectedCiv.units.getCivUnits().first { it.id == f.unitId }, false) }
        )
        constructors.forEachIndexed { index, constructor ->
            val old = f.app.pushScreen(constructor)
            assertSame(old, f.app.screen)
            f.command { RenameUnitCommand(f.rome.civID, "spectator-$index", it, f.unitId, "Veteran $index") }
            f.publish()
            val fresh = f.app.screen!!
            assertEquals(old.javaClass, fresh.javaClass)
            assertNotSame(old, fresh)
            val bound = fresh as com.unciv.ui.screens.worldscreen.AgentSnapshotScreen
            assertEquals(f.spectator.civID, bound.snapshotViewerCivId)
            assertSame(f.world.gameInfo, bound.snapshotGameInfo)
            assertFalse(f.world.canChangeState)
            f.app.popScreen()
        }
    }

    @Test fun `viewer switch closes private secondary and late publication cannot resurrect it`() = withFixture { f ->
        f.openCity()
        val before = AgentSnapshotPublication.buildPayload(f.coordinator, f.rome.civID)
        val committed = AgentSnapshotPublication.deserializeGame(before)
        f.world.installAgentSnapshot(committed, ResolvedSelection(null, null, null), before.generation + 1, f.greece.civID)
        assertSame(f.world, f.app.screen)
        assertEquals(f.greece.civID, f.world.currentViewerCivId())
        f.world.installAgentSnapshot(AgentSnapshotPublication.deserializeGame(before), ResolvedSelection(null, null, null), before.generation, f.rome.civID)
        assertSame(f.world, f.app.screen)
        assertEquals(f.greece.civID, f.world.currentViewerCivId())
    }

    @Test fun `removed unit closes promotion screen instead of retaining orphan`() = withFixture { f ->
        f.app.pushScreen { PromotionPickerScreen(f.rome.units.getCivUnits().first { it.id == f.unitId }, false) }
        f.command { UnitOrderCommand(f.rome.civID, "remove", it, f.unitId, UnitOrder.DISBAND) }
        f.publish()
        assertSame(f.world, f.app.screen)
    }


    @Test fun `committed promotion refreshes eligibility and adopted choice`() = withFixture { f ->
        val old = f.app.pushScreen { PromotionPickerScreen(f.rome.units.getCivUnits().first { it.id == f.unitId }, false) }
        assertTrue(old.rightSideButton.isVisible)
        f.command { PromoteUnitCommand(f.rome.civID, "promote", it, f.unitId, "Shock I") }
        f.publish()
        val fresh = f.app.screen as PromotionPickerScreen
        assertNotSame(old, fresh)
        assertTrue("Shock I" in fresh.unit.promotions.promotions)
        assertFalse(fresh.unit.promotions.canBePromoted())
        assertFalse(fresh.rightSideButton.isVisible)
    }

    @Test fun `committed trade refresh shows pending offer and retract action`() = withFixture { f ->
        val old = f.app.pushScreen { DiplomacyScreen(f.world.selectedGameView.civView,
            f.world.selectedGameView.getForeignCivView(f.greece), showTrade = true) }
        f.command { ProposeTradeCommand(f.rome.civID, "trade", it, f.greece.civID,
            listOf(TradeOfferDto("Gold", com.unciv.logic.trade.TradeOfferType.Gold, 10)), emptyList()) }
        f.publish()
        val fresh = f.app.screen as DiplomacyScreen
        assertNotSame(old, fresh)
        assertTrue(fresh.stage.root.descendants().filterIsInstance<TextButton>().any { it.text.toString() == "Retract offer" })
        assertEquals(1, f.world.gameInfo.getCivilization(f.greece.civID).tradeRequests.size)
        val trade = DiplomacyScreen::class.java.getDeclaredField("activeTrade").apply { isAccessible = true }.get(fresh)
        val view = com.unciv.ui.screens.diplomacyscreen.TradeTable::class.java.getDeclaredField("tradeView")
            .apply { isAccessible = true }.get(trade) as com.unciv.view.TradeView
        assertEquals(10, view.ourStagedOffers().first { it.name == "Gold" }.amount)
    }

    private fun Actor.descendants(): Sequence<Actor> = sequence {
        yield(this@descendants)
        if (this@descendants is Group) for (child in children) yieldAll(child.descendants())
    }

}
