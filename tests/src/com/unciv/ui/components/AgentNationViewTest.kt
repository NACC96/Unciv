package com.unciv.ui.components

import com.badlogic.gdx.scenes.scene2d.Group
import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.logic.agents.*
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.basescreen.UncivStage
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.ui.screens.worldscreen.UndoHandler
import com.unciv.ui.screens.worldscreen.worldmap.WorldMapHolder
import com.unciv.ui.screens.worldscreen.unit.UnitTable
import com.unciv.ui.screens.worldscreen.status.AgentMessagesPanel
import com.unciv.ui.screens.worldscreen.status.AgentActivityFeed
import com.unciv.ui.screens.worldscreen.status.AgentNationSettingsPanel
import com.unciv.view.GameView
import com.unciv.ui.components.input.keyShortcuts
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Answers
import org.mockito.Mockito

@RunWith(GdxTestRunner::class)
class AgentNationViewTest {
    private fun field(type: Class<*>, name: String) = type.getDeclaredField(name).apply { isAccessible = true }
    private class Fixture {
        val game = TestGame()
        val spectator = game.addCiv(game.ruleset.nations[Constants.spectator]!!)
        val rome = game.addCiv(game.ruleset.nations["Rome"]!!, isPlayer = true)
        val screen = Mockito.mock(WorldScreen::class.java, Answers.CALLS_REAL_METHODS)
        val session = Mockito.mock(AgentMatchSession::class.java)
        val map = Mockito.mock(WorldMapHolder::class.java)
        val unit = Mockito.mock(UnitTable::class.java)
        val undo = Mockito.mock(UndoHandler::class.java)
        val messages = Mockito.mock(AgentMessagesPanel::class.java)
        val activity = Mockito.mock(AgentActivityFeed::class.java)
        val root = Group()
        init {
            fun put(type: Class<*>, name: String, value: Any) = type.getDeclaredField(name).apply { isAccessible = true }.set(screen, value)
            game.gameInfo.currentPlayer = rome.civID
            val stage = Mockito.mock(UncivStage::class.java)
            Mockito.`when`(stage.root).thenReturn(root)
            root.addActor(messages); root.addActor(activity)
            put(BaseScreen::class.java, "stage", stage)
            put(BaseScreen::class.java, "game", UncivGame.Current)
            put(WorldScreen::class.java, "gameInfo", game.gameInfo)
            put(WorldScreen::class.java, "viewingCiv", spectator)
            put(WorldScreen::class.java, "selectedCiv", spectator)
            put(WorldScreen::class.java, "selectedGameView", GameView(game.gameInfo, spectator, true))
            put(WorldScreen::class.java, "agentPublicationFence", AgentPublicationFence())
            put(WorldScreen::class.java, "mapHolder", map)
            put(WorldScreen::class.java, "bottomUnitTable", unit)
            put(WorldScreen::class.java, "undoHandler", undo)
            screen.agentSession = session
            screen.fogOfWar = false
            UncivGame::class.java.getDeclaredField("worldScreen").apply { isAccessible = true }.set(UncivGame.Current, screen)
        }
        fun snapshot() = game.gameInfo.clone().apply { setTransients() }
    }

    private fun selectedTile(map: WorldMapHolder): Any? = Mockito.mockingDetails(map).invocations
        .last { it.method.name.substringBefore('$') == "setSelectedTile" }.arguments.single()

    private fun installWithViewer(screen: WorldScreen, game: com.unciv.logic.GameInfo, selection: ResolvedSelection,
                                  generation: Long, viewer: String) {
        val method = WorldScreen::class.java.methods.firstOrNull { it.name == "installAgentSnapshot" && it.parameterCount == 4 }
        assertNotNull("Snapshot publication must accept a committed viewer", method)
        method!!.invoke(screen, game, selection, generation, viewer)
    }

    @Test fun `host always consumes Next Turn even when current viewer cannot end it`() {
        val f = Fixture()
        Mockito.`when`(f.session.onNextTurnPressed()).thenReturn(false)
        f.screen.nextTurn()
        Mockito.verify(f.session).onNextTurnPressed()
        assertFalse(f.screen.shouldUpdate)
    }

    @Test fun `viewer change hides nation settings panel with stale drafts`() {
        val f = Fixture(); val settings = Mockito.mock(AgentNationSettingsPanel::class.java)
        f.root.addActor(settings)
        installWithViewer(f.screen, f.snapshot(), ResolvedSelection(null, null, null), 1, f.rome.civID)
        Mockito.verify(settings).hide()
    }

    @Test fun `agent session disables snapshot rewind even on a playable human turn`() {
        val game = TestGame(); val screen = Mockito.mock(WorldScreen::class.java)
        Mockito.`when`(screen.gameInfo).thenReturn(game.gameInfo)
        Mockito.`when`(screen.canChangeState).thenReturn(true)
        val undo = UndoHandler(screen)
        Mockito.`when`(screen.gameInfo).thenReturn(game.gameInfo.clone())
        Mockito.`when`(screen.agentSession).thenReturn(Mockito.mock(AgentMatchSession::class.java))
        assertFalse(undo.canUndo())
    }

    @Test fun `committed takeover resets fog selection and private panels without replacing host`() {
        val f = Fixture(); val snapshot = f.snapshot()
        Mockito.clearInvocations(f.undo)
        installWithViewer(f.screen, snapshot, ResolvedSelection(null, null, f.game.tileMap[0, 0].position), 1, f.rome.civID)
        assertSame(snapshot, f.screen.gameInfo)
        assertSame(snapshot.getCivilization(f.rome.civID), f.screen.viewingCiv)
        assertSame(f.screen.viewingCiv, f.screen.selectedCiv)
        assertTrue(f.screen.fogOfWar)
        assertFalse(f.screen.selectedGameView.spectatorMode)
        assertSame(f.screen.selectedGameView, f.screen.getGameViewConsideringForOfWar())
        assertNull(selectedTile(f.map))
        assertTrue(Mockito.mockingDetails(f.unit).invocations.any {
            it.method.name.substringBefore('$') == "reselectAfterSnapshot" && it.arguments.all { arg -> arg == null }
        })
        Mockito.verify(f.messages).hide()
        Mockito.verify(f.messages).refresh()
        AgentActivityFeed::class.java.getMethod("hide").invoke(Mockito.verify(f.activity))
        Mockito.verify(f.undo).clearCheckpoints()
        Mockito.verify(f.session, Mockito.never()).close()
        assertSame(f.session, f.screen.agentSession)
    }

    @Test fun `private nation selection cannot adopt a foreign or spectator perspective`() {
        val f = Fixture(); val snapshot = f.snapshot()
        installWithViewer(f.screen, snapshot, ResolvedSelection(null, null, null), 1, f.rome.civID)
        f.screen.setSelectedCiv(snapshot.getCivilization(f.spectator.civID))
        assertSame(f.screen.viewingCiv, f.screen.selectedCiv)
        assertSame(f.screen.viewingCiv, f.screen.selectedGameView.civView.getCiv())
        assertFalse(f.screen.selectedGameView.spectatorMode)
    }

    @Test fun `private technology shortcut remains usable without toggling spectator fog`() {
        val f = Fixture()
        f.game.addCity(f.rome, f.game.tileMap[0, 0])
        // Synthetic contact discovery must not try to persist tutorial preferences.
        UncivGame.Current.settings.tutorialTasksCompleted.add("Meet another civilization")
        com.unciv.ui.components.fonts.Fonts.fontImplementation = com.unciv.dev.FontDesktop()
        com.unciv.ui.images.ImageGetter.setNewRuleset(f.game.ruleset)
        val snapshot = f.snapshot()
        installWithViewer(f.screen, snapshot, ResolvedSelection(null, null, null), 1, f.rome.civID)
        val game = Mockito.mock(UncivGame::class.java)
        Mockito.`when`(game.gameInfo).thenReturn(snapshot)
        field(BaseScreen::class.java, "game").set(f.screen, game)
        val controls = com.unciv.ui.screens.worldscreen.TechPolicyDiplomacyButtons(f.screen)
        for (name in listOf("updateFogOfWarButton", "updateTechButton"))
            controls.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(controls)
        val fog = field(controls.javaClass, "fogOfWarButtonHolder").get(controls) as com.badlogic.gdx.scenes.scene2d.Actor
        val tech = field(controls.javaClass, "techButtonHolder").get(controls) as com.badlogic.gdx.scenes.scene2d.Actor
        val resolver = com.unciv.ui.components.input.KeyShortcutDispatcher.Resolver(
            com.unciv.ui.components.input.KeyboardBindings[com.unciv.ui.components.input.KeyboardBinding.TechnologyTree])
        resolver.updateFor(fog.keyShortcuts)
        resolver.updateFor(tech.keyShortcuts)
        resolver.triggeredActions.forEach { it() }
        assertTrue("Technology shortcut must retain ordinary fog", f.screen.fogOfWar)
        assertEquals(1, Mockito.mockingDetails(game).invocations.count { it.method.name == "pushScreen" })
    }

    @Test fun `spectator selection keeps fog visible and its shortcut exclusive`() {
        val f = Fixture()
        f.game.addCity(f.rome, f.game.tileMap[0, 0])
        UncivGame.Current.settings.tutorialTasksCompleted.add("Meet another civilization")
        com.unciv.ui.components.fonts.Fonts.fontImplementation = com.unciv.dev.FontDesktop()
        com.unciv.ui.images.ImageGetter.setNewRuleset(f.game.ruleset)
        f.screen.setSelectedCiv(f.rome)
        val game = Mockito.mock(UncivGame::class.java)
        Mockito.`when`(game.gameInfo).thenReturn(f.game.gameInfo)
        field(BaseScreen::class.java, "game").set(f.screen, game)
        val controls = com.unciv.ui.screens.worldscreen.TechPolicyDiplomacyButtons(f.screen)
        for (name in listOf("updateFogOfWarButton", "updateTechButton"))
            controls.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(controls)
        val fog = field(controls.javaClass, "fogOfWarButtonHolder").get(controls) as com.badlogic.gdx.scenes.scene2d.Actor
        val tech = field(controls.javaClass, "techButtonHolder").get(controls) as com.badlogic.gdx.scenes.scene2d.Actor
        assertTrue("Selecting a nation must preserve the actual spectator's fog control", fog.isVisible)
        val resolver = com.unciv.ui.components.input.KeyShortcutDispatcher.Resolver(
            com.unciv.ui.components.input.KeyboardBindings[com.unciv.ui.components.input.KeyboardBinding.TechnologyTree])
        resolver.updateFor(fog.keyShortcuts)
        resolver.updateFor(tech.keyShortcuts)
        resolver.triggeredActions.forEach { it() }
        assertTrue(f.screen.fogOfWar)
        assertEquals("Spectator T must only toggle fog", 0,
            Mockito.mockingDetails(game).invocations.count { it.method.name == "pushScreen" })
    }

    private fun pressCapitalShortcut(f: Fixture, renderedGame: com.unciv.logic.GameInfo): List<com.unciv.view.CityView> {
        val game = Mockito.mock(UncivGame::class.java)
        Mockito.`when`(game.gameInfo).thenReturn(renderedGame)
        field(BaseScreen::class.java, "game").set(f.screen, game)
        val shortcuts = com.unciv.ui.components.input.KeyShortcutDispatcher()
        field(BaseScreen::class.java, "globalShortcuts").set(f.screen, shortcuts)
        WorldScreen::class.java.getDeclaredMethod("addKeyboardPresses").apply { isAccessible = true }.invoke(f.screen)
        val cities = ArrayList<com.unciv.view.CityView>()
        Mockito.mockConstruction(com.unciv.ui.screens.cityscreen.CityScreen::class.java) { _, context ->
            cities.add(context.arguments().first() as com.unciv.view.CityView)
        }.use {
            Mockito.doAnswer { invocation -> invocation.getArgument<() -> BaseScreen>(0).invoke() }.`when`(game)
                .pushScreen(Mockito.any<() -> BaseScreen>() ?: { Mockito.mock(BaseScreen::class.java) })
            val resolver = com.unciv.ui.components.input.KeyShortcutDispatcher.Resolver(
                com.unciv.ui.components.input.KeyboardBindings[com.unciv.ui.components.input.KeyboardBinding.ViewCapitalCity])
            resolver.updateFor(shortcuts); resolver.triggeredActions.forEach { it() }
        }
        return cities
    }

    @Test fun `private Home targets own capital while another nation owns the active turn`() {
        val f = Fixture(); f.game.makeHexagonalMap(5, Constants.grassland)
        val greece = f.game.addCiv(f.game.ruleset.nations["Greece"]!!)
        f.game.addCity(f.rome, f.game.tileMap[0, 0]); f.game.addCity(greece, f.game.tileMap[3, 0])
        f.game.gameInfo.currentPlayer = greece.civID
        UncivGame.Current.settings.tutorialTasksCompleted.add("Meet another civilization")
        val snapshot = f.snapshot()
        installWithViewer(f.screen, snapshot, ResolvedSelection(null, null, null), 1, f.rome.civID)
        val cities = pressCapitalShortcut(f, snapshot)
        val center = Mockito.mockingDetails(f.map).invocations.single { it.method.name == "setCenterPosition" }.arguments.first()
        assertEquals(snapshot.getCivilization(f.rome.civID).getCapital()!!.location.toHexCoord(), center)
        assertEquals(listOf(f.rome.civID), cities.map { it.owningCiv().getCiv().civID })
    }

    @Test fun `private Home without own capital does not center or open active foreign capital`() {
        val f = Fixture(); f.game.makeHexagonalMap(5, Constants.grassland)
        val greece = f.game.addCiv(f.game.ruleset.nations["Greece"]!!)
        f.game.addCity(greece, f.game.tileMap[3, 0]); f.game.gameInfo.currentPlayer = greece.civID
        UncivGame.Current.settings.tutorialTasksCompleted.add("Meet another civilization")
        val snapshot = f.snapshot()
        installWithViewer(f.screen, snapshot, ResolvedSelection(null, null, null), 1, f.rome.civID)
        assertTrue(pressCapitalShortcut(f, snapshot).isEmpty())
        assertFalse(Mockito.mockingDetails(f.map).invocations.any { it.method.name == "setCenterPosition" })
    }

    @Test fun `spectator Home still uses active nation capital`() {
        val f = Fixture(); f.game.addCity(f.rome, f.game.tileMap[0, 0])
        UncivGame.Current.settings.tutorialTasksCompleted.add("Meet another civilization")
        val snapshot = f.snapshot()
        f.screen.installAgentSnapshot(snapshot, ResolvedSelection(null, null, null), 1)
        assertEquals(listOf(f.rome.civID), pressCapitalShortcut(f, snapshot).map { it.owningCiv().getCiv().civID })
    }

    @Test fun `same viewer snapshot keeps selection and older publications cannot switch back`() {
        val f = Fixture(); val snapshot = f.snapshot()
        f.screen.installAgentSnapshot(snapshot, ResolvedSelection(null, null, f.game.tileMap[0, 0].position), 2)
        assertNotNull(selectedTile(f.map))
        assertFalse(f.screen.fogOfWar)
        Mockito.verify(f.messages, Mockito.never()).hide()
        val newer = f.snapshot()
        installWithViewer(f.screen, newer, ResolvedSelection(null, null, null), 3, f.rome.civID)
        installWithViewer(f.screen, f.snapshot(), ResolvedSelection(null, null, null), 2, f.spectator.civID)
        assertSame(newer, f.screen.gameInfo)
        assertEquals(f.rome.civID, f.screen.currentViewerCivId())
    }
}
