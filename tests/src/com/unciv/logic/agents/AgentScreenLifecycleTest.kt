package com.unciv.logic.agents

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Graphics
import com.badlogic.gdx.Input
import com.badlogic.gdx.scenes.scene2d.Stage
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.event.EventBus
import com.unciv.logic.files.Autosaves
import com.unciv.logic.files.UncivFiles
import com.unciv.models.metadata.GameSettings
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import com.unciv.ui.screens.LoadingScreen
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.cityscreen.CityScreen
import com.unciv.ui.screens.diplomacyscreen.DiplomacyScreen
import com.unciv.ui.screens.mainmenuscreen.MainMenuScreen
import com.unciv.ui.screens.newgamescreen.NewGameScreen
import com.unciv.ui.screens.pickerscreens.PolicyPickerScreen
import com.unciv.ui.screens.pickerscreens.TechPickerScreen
import com.unciv.ui.screens.savescreens.LoadGameScreen
import com.unciv.ui.screens.worldscreen.PlayerReadyScreen
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.ui.screens.worldscreen.status.StatusButtons
import com.unciv.ui.screens.worldscreen.unit.UnitTable
import com.unciv.ui.screens.worldscreen.worldmap.WorldMapHolder
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Answers
import org.mockito.Mockito

/** Exercises real app navigation and ownership; only screen drawing and the session are replaced. */
@RunWith(BaseTestRunner::class)
class AgentScreenLifecycleTest {
    private var oldApp: Application? = null
    private var oldInput: Input? = null
    private var oldGraphics: Graphics? = null

    @Before fun replaceDrawingBoundary() {
        oldApp = Gdx.app; oldInput = Gdx.input; oldGraphics = Gdx.graphics
        Gdx.app = Mockito.mock(Application::class.java)
        Gdx.input = Mockito.mock(Input::class.java)
        Gdx.graphics = Mockito.mock(Graphics::class.java)
    }

    @After fun restoreDrawingBoundary() {
        Gdx.app = oldApp; Gdx.input = oldInput; Gdx.graphics = oldGraphics
    }

    private class Fixture {
        val events = ArrayList<String>()
        val game = Mockito.spy(UncivGame())
        val session = Mockito.mock(AgentMatchSession::class.java) { invocation ->
            when (invocation.method.name) {
                "onMatchLeft" -> { events.add("left"); null }
                "onMatchEntered" -> { events.add("entered"); null }
                "resume" -> { events.add("resume"); null }
                "close" -> { events.add("close"); null }
                else -> Answers.RETURNS_DEFAULTS.answer(invocation)
            }
        }
        val world = screen(WorldScreen::class.java)
        val stack: ArrayDeque<BaseScreen>
        init {
            UncivGame.Current = game
            game.settings = GameSettings()
            field(UncivGame::class.java, "worldScreen").set(game, world)
            @Suppress("UNCHECKED_CAST")
            stack = field(UncivGame::class.java, "screenStack").get(game) as ArrayDeque<BaseScreen>
            Mockito.`when`(world.agentSession).thenReturn(session)
            stack.add(world)
            select(world)
            events.clear()
        }

        fun <T : BaseScreen> screen(type: Class<T>): T = Mockito.mock(type).also {
            Mockito.`when`(it.stage).thenReturn(Mockito.mock(Stage::class.java))
        }

        fun select(screen: BaseScreen) {
            UncivGame::class.java.getDeclaredMethod("setScreen", BaseScreen::class.java)
                .apply { isAccessible = true }.invoke(game, screen)
        }

        fun replaceRoot(screen: BaseScreen) {
            UncivGame::class.java.getDeclaredMethod("setAsRootScreen", BaseScreen::class.java)
                .apply { isAccessible = true }.invoke(game, screen)
        }
    }

    @Test fun `city research policy and diplomacy remain part of the open match`() {
        val f = Fixture()
        val related = listOf(
            f.screen(CityScreen::class.java), f.screen(TechPickerScreen::class.java),
            f.screen(PolicyPickerScreen::class.java), f.screen(DiplomacyScreen::class.java)
        )
        related.forEach { screen -> f.game.pushScreen { screen } }
        assertEquals("These screens must preserve the open-match gate", List(4) { "entered" }, f.events)
        Mockito.verify(f.session, Mockito.never()).pause()
        Mockito.verify(f.session, Mockito.never()).resume()
    }

    @Test fun `menu setup and load screens leave a retained match`() {
        val boundaries: List<Class<out BaseScreen>> = listOf(
            MainMenuScreen::class.java, NewGameScreen::class.java, LoadGameScreen::class.java
        )
        boundaries.forEach { type ->
            val f = Fixture()
            f.game.pushScreen { f.screen(type) }
            assertEquals("Leaving for ${type.simpleName} must pause scheduling", listOf("left"), f.events)
            Mockito.verify(f.world, Mockito.never()).dispose()
        }
    }

    @Test fun `loading pauses even when it has not been added to the screen stack`() {
        val f = Fixture()
        f.select(f.screen(LoadingScreen::class.java))
        assertEquals(listOf("left"), f.events)
        assertSame("Loading keeps the prior world until replacement is ready", f.world, f.game.worldScreen)
    }

    @Test fun `a related screen above the main menu cannot reopen a retained match`() {
        val f = Fixture()
        f.game.pushScreen { f.screen(MainMenuScreen::class.java) }
        f.events.clear()
        f.game.pushScreen { f.screen(DiplomacyScreen::class.java) }
        assertEquals(listOf("left"), f.events)
    }

    @Test fun `returning to the match opens its gate without automatically resuming`() {
        val f = Fixture()
        f.game.pushScreen { f.screen(MainMenuScreen::class.java) }
        f.events.clear()
        f.game.popScreen()
        assertEquals(listOf("entered"), f.events)
        Mockito.verify(f.session, Mockito.never()).resume()
        Mockito.verify(f.session, Mockito.never()).startAutomaticPlay()
    }

    @Test fun `player ready wrapper retains the same open match`() {
        val f = Fixture()
        f.stack.clear()
        val ready = f.screen(PlayerReadyScreen::class.java)
        f.stack.add(ready)
        f.select(ready)
        assertEquals(listOf("entered"), f.events)
        Mockito.verify(f.session, Mockito.never()).resume()
    }

    @Test fun `main menu pauses before requesting its autosave`() {
        val f = Fixture()
        val info = GameInfo()
        val files = Mockito.mock(UncivFiles::class.java)
        val autosaves = Mockito.mock(Autosaves::class.java)
        val menu = f.screen(MainMenuScreen::class.java)
        f.game.gameInfo = info; f.game.files = files
        Mockito.`when`(files.autosaves).thenReturn(autosaves)
        Mockito.doAnswer {
            f.events.add("autosave")
            Mockito.mock(Job::class.java)
        }.`when`(autosaves).requestAutoSaveUnCloned(info, false)
        // Avoid constructing the real menu; the actual goToMainMenu method and ordering remain exercised.
        Mockito.doReturn(menu).`when`(f.game).pushScreen(Mockito.any<() -> MainMenuScreen>() ?: { menu })
        f.game.goToMainMenu()
        assertEquals(listOf("left", "autosave"), f.events)
    }

    @Test fun `root replacement disposes a world retained behind a player ready wrapper`() {
        val f = Fixture()
        val ready = f.screen(PlayerReadyScreen::class.java)
        f.stack.clear(); f.stack.add(ready)
        f.replaceRoot(f.screen(MainMenuScreen::class.java))
        Mockito.verify(ready).dispose()
        Mockito.verify(f.world).dispose()
        assertNull("An owner disposed outside the stack must not remain reachable as a loaded match", f.game.worldScreen)
    }

    @Test fun `root replacement disposes a stacked world only once`() {
        val f = Fixture()
        f.replaceRoot(f.screen(MainMenuScreen::class.java))
        Mockito.verify(f.world, Mockito.times(1)).dispose()
        assertNull(f.game.worldScreen)
    }

    @Test fun `world disposal closes and detaches its session before releasing its stage`() {
        val world = Mockito.mock(WorldScreen::class.java, Answers.CALLS_REAL_METHODS)
        val session = Mockito.mock(AgentMatchSession::class.java)
        val stage = Mockito.mock(Stage::class.java)
        world.agentSession = session
        field(BaseScreen::class.java, "stage").set(world, stage)
        field(WorldScreen::class.java, "events").set(world, Mockito.mock(EventBus.EventReceiver::class.java))
        field(WorldScreen::class.java, "statusButtons").set(world, Mockito.mock(StatusButtons::class.java))
        world.dispose()
        val order = Mockito.inOrder(session, stage)
        order.verify(session).close()
        order.verify(stage).dispose()
        assertNull(world.agentSession)
        world.dispose()
        Mockito.verify(session, Mockito.times(1)).close()
    }

    @Test fun `loading retires the old host before exposing the replacement game`() {
        val f = Fixture()
        val prior = GameInfo()
        val replacement = GameInfo()
        f.game.gameInfo = prior
        Mockito.doAnswer {
            assertSame("The retired host must not observe or publish over the replacement", prior, f.game.gameInfo)
            f.events.add("retired")
            null
        }.`when`(f.world).closeAgentSession()
        UncivGame::class.java.getDeclaredMethod("installLoadedGameInfo", GameInfo::class.java)
            .apply { isAccessible = true }.invoke(f.game, replacement)
        assertEquals(listOf("retired"), f.events)
        assertSame(replacement, f.game.gameInfo)
    }

    private class SnapshotFixture {
        val testGame = TestGame()
        val game = UncivGame.Current
        val prior = testGame.gameInfo
        val world = Mockito.mock(WorldScreen::class.java, Answers.CALLS_REAL_METHODS)
        val snapshot: GameInfo
        init {
            val rome = testGame.addCiv(testGame.ruleset.nations["Rome"]!!, isPlayer = true)
            prior.currentPlayer = rome.civID; prior.currentPlayerCiv = rome
            field(BaseScreen::class.java, "game").set(world, game)
            field(WorldScreen::class.java, "gameInfo").set(world, prior)
            field(WorldScreen::class.java, "viewingCiv").set(world, rome)
            field(WorldScreen::class.java, "selectedCiv").set(world, rome)
            field(WorldScreen::class.java, "mapHolder").set(world, Mockito.mock(WorldMapHolder::class.java))
            field(WorldScreen::class.java, "bottomUnitTable").set(world, Mockito.mock(UnitTable::class.java))
            field(UncivGame::class.java, "worldScreen").set(game, world)
            // This test isolates publication ownership; immutable fixture entities can be shared.
            snapshot = GameInfo().apply {
                gameId = prior.gameId; civilizations = prior.civilizations
                tileMap = prior.tileMap; ruleset = prior.ruleset
            }
        }
        fun publish() = world.installAgentSnapshot(snapshot, ResolvedSelection(null, null, null))
    }

    @Test fun `a final snapshot cannot overwrite a separately loaded instance of the same game`() {
        val f = SnapshotFixture()
        val rewind = GameInfo().apply { gameId = f.prior.gameId }
        f.game.gameInfo = rewind
        f.publish()
        assertSame("Same game ID does not grant authority over a save reload", rewind, f.game.gameInfo)
        assertSame("The retained screen may finish its own render snapshot", f.snapshot, f.world.gameInfo)
    }

    @Test fun `a detached world cannot publish over the current loaded game`() {
        val f = SnapshotFixture()
        field(UncivGame::class.java, "worldScreen").set(f.game, Mockito.mock(WorldScreen::class.java))
        f.publish()
        assertSame(f.prior, f.game.gameInfo)
        assertSame(f.snapshot, f.world.gameInfo)
    }

    @Test fun `an owned retained world still publishes its final committed snapshot`() {
        val f = SnapshotFixture()
        f.publish()
        assertSame(f.snapshot, f.world.gameInfo)
        assertSame(f.snapshot, f.game.gameInfo)
    }

    private companion object {
        fun field(owner: Class<*>, name: String) = owner.getDeclaredField(name).apply { isAccessible = true }
    }
}
