package com.unciv.ui.components

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.unciv.UncivGame
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.UnitAction
import com.unciv.models.UnitActionType
import com.unciv.models.skins.SkinCache
import com.unciv.testing.GdxTestRunner
import com.unciv.ui.components.input.ActivationListener
import com.unciv.ui.images.IconTextButton
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.ui.screens.worldscreen.minimap.MinimapHolder
import com.unciv.ui.screens.worldscreen.unit.AutoPlay
import com.unciv.ui.screens.worldscreen.unit.UnitTable
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActions
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActionsTable
import com.unciv.ui.screens.worldscreen.worldmap.WorldMapHolder
import com.unciv.view.GameView
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito

@RunWith(GdxTestRunner::class)
class AtlasWorldStateTest {
    private lateinit var snapshot: SetupUiStateSnapshot
    private lateinit var fixture: SetupLayoutFixture
    private lateinit var screen: WorldScreen

    @Before fun setup() {
        snapshot = SetupUiStateSnapshot()
        fixture = SetupLayoutFixture(1440f, 900f)
        SkinCache.loadSkinConfigs()
        UncivGame.Current.settings.skin = "Atlas"
        BaseScreen.setSkin()
        screen = Mockito.mock(WorldScreen::class.java)
        Mockito.`when`(screen.stage).thenReturn(fixture.stage)
        Mockito.`when`(screen.game).thenReturn(UncivGame.Current)
        Mockito.`when`(screen.autoPlay).thenReturn(Mockito.mock(AutoPlay::class.java))
        getter(screen, "getBottomUnitTable", Mockito.mock(UnitTable::class.java))
        getter(screen, "getUndoHandler", Mockito.mock(com.unciv.ui.screens.worldscreen.UndoHandler::class.java))
        UncivGame::class.java.getDeclaredField("worldScreen").apply { isAccessible = true }.set(UncivGame.Current, screen)
    }
    @After fun restore() { snapshot.restore() }

    @Test fun fortifiedCurrentActionKeepsDisabledForegroundReadable() {
        val civ = fixture.game.addCiv(fixture.game.ruleset.nations.getValue("Rome"), isPlayer = true)
        val unit = fixture.game.addUnit("Warrior", civ, fixture.game.getTile(0, 0))
        unit.fortify()
        assertCurrentDisabledButton(unit, UnitActionType.Fortify)
    }

    @Test fun alreadySetUpSiegeActionKeepsDisabledForegroundReadable() {
        val civ = fixture.game.addCiv(fixture.game.ruleset.nations.getValue("Rome"), isPlayer = true)
        val unit = fixture.game.addUnit("Catapult", civ, fixture.game.getTile(0, 0))
        unit.action = UnitActionType.SetUp.value
        assertCurrentDisabledButton(unit, UnitActionType.SetUp)
    }

    private fun assertCurrentDisabledButton(unit: MapUnit, type: UnitActionType) {
        val action = UnitActions.getUnitActions(unit, type).first()
        assertTrue(action.isCurrentAction)
        assertNull(action.action)
        val method = UnitActionsTable::class.java.getDeclaredMethod("getUnitActionButton", MapUnit::class.java, UnitAction::class.java).apply { isAccessible = true }
        val button = method.invoke(UnitActionsTable(screen), unit, action) as IconTextButton
        assertTrue(button.isDisabled)
        assertEquals(Color.WHITE, button.label.color)
        assertEquals(GameUiStyle.muted, button.label.style.fontColor)
        assertSame(BaseScreen.skin.get("disabled", com.badlogic.gdx.scenes.scene2d.ui.TextButton.TextButtonStyle::class.java).up, button.style.up)
    }

    @Test fun minimapExpandReduceRestoresFitWithoutOtherInvalidation() = assertMinimapRoundTrip(1440f, 900f, 30)

    @Test fun minimapNarrowViewportRestoresSameFittedSize() = assertMinimapRoundTrip(960f, 720f, 12)

    private fun assertMinimapRoundTrip(width: Float, height: Float, requestedSize: Int) {
        Mockito.`when`(fixture.stage.width).thenReturn(width)
        Mockito.`when`(fixture.stage.height).thenReturn(height)
        fixture.root.setSize(width, height)
        val game = fixture.game
        game.makeRectangularMap(20, 32)
        game.tileMap.mapParameters.shape = com.unciv.logic.map.MapShape.rectangular
        game.tileMap.mapParameters.worldWrap = true
        val viewer = game.addCiv(game.ruleset.nations.getValue(com.unciv.Constants.spectator))
        Mockito.`when`(screen.selectedGameView).thenReturn(GameView(game.gameInfo, viewer, true))
        val map = Mockito.mock(WorldMapHolder::class.java)
        getter(map, "getWorldScreen", screen)
        getter(map, "getTileMap", game.tileMap)
        UncivGame.Current.settings.minimapSize = requestedSize
        val holder = MinimapHolder(map)
        fixture.stage.addActor(holder)
        holder.update(viewer)
        val before = Vector2(holder.width, holder.height)
        val allocation = com.unciv.ui.screens.worldscreen.WorldHudLayout.calculate(width, height, 1f, true).minimap
        // The layer toggles live in a popover and tile info has its own card: the map card uses the whole host.
        assertTrue("map card exceeds its host: ${holder.height}", holder.height <= allocation.height + .5f)
        fun pressToggle() {
            val toggle = descendants(holder).filterIsInstance<com.badlogic.gdx.scenes.scene2d.ui.Table>().first { actor -> actor.listeners.any { it is ActivationListener } }
            toggle.listeners.filterIsInstance<ActivationListener>().single().tap(InputEvent().apply { listenerActor = toggle }, 1f, 1f, 1, 0)
        }
        pressToggle()
        assertTrue(holder.isMaximized)
        assertTrue(holder.width > before.x)
        pressToggle()
        assertFalse(holder.isMaximized)
        holder.update(viewer)
        assertEquals(before.x, holder.width, .01f)
        assertEquals(before.y, holder.height, .01f)
        pressToggle()
        val expandedMap = holder.minimap
        expandedMap.listeners.filterIsInstance<ActivationListener>().single().tap(InputEvent().apply { listenerActor = expandedMap }, 1f, 1f, 1, 0)
        assertFalse(holder.isMaximized)
        assertEquals(before.x, holder.width, .01f)
        assertEquals(before.y, holder.height, .01f)
        assertEquals(requestedSize, UncivGame.Current.settings.minimapSize)
        assertSame(game.tileMap, map.javaClass.methods.single { it.name.substringBefore('$') == "getTileMap" }.invoke(map))
        assertEquals(width, screen.stage.width, 0f)
        assertEquals(height, screen.stage.height, 0f)
        assertFalse(Mockito.mockingDetails(map).invocations.any { it.method.name.startsWith("setCenterPosition") })
        val settings = UncivGame.Current.settings
        val readers = listOf<() -> Boolean>({ settings.showUnitMovements }, { settings.showTileYields },
            { settings.showWorkedTiles }, { settings.showResourcesAndImprovements }, { settings.showPixelImprovements })
        for ((button, read) in holder.buttons.zip(readers)) {
            val beforeFlag = read()
            button.toggle(); button.update()
            assertEquals(!beforeFlag, read())
            assertEquals(read(), button.isChecked)
            button.toggle(); button.update()
            assertEquals(beforeFlag, read())
        }
    }

    @Test fun allTurnFixturesUseProductionChoiceResolution() {
        Mockito.`when`(screen.gameInfo).thenReturn(fixture.game.gameInfo)
        for (case in com.unciv.dev.AtlasTurnFixtures.cases) {
            val button = com.unciv.dev.AtlasTurnFixtures.button(case, screen)
            assertTrue(button.label.text.isNotEmpty())
            // Agent turns show the short shared "state · nation", never the session's debug statusText().
            if (case == "AgentMatchPaused") assertEquals("Paused · America", button.label.text.toString())
            assertEquals(case in setOf("Working", "Waiting", "AgentMatchPaused", "Autosave"), button.isDisabled)
            assertEquals(if (button.isDisabled) GameUiStyle.muted else GameUiStyle.surface, button.label.style.fontColor)
        }
    }

    @Test fun turnActionFitsFractionalWidthAtNarrowDesktopSize() {
        Mockito.`when`(screen.gameInfo).thenReturn(fixture.game.gameInfo)
        Mockito.`when`(fixture.stage.width).thenReturn(960f)
        for (scale in listOf(1f, 1.5f)) {
            UncivGame.Current.settings.fontSizeMultiplier = scale
            val button = com.unciv.dev.AtlasTurnFixtures.button("RetryUpload", screen)
            val status = com.unciv.ui.screens.worldscreen.status.StatusButtons(button, screen)
            status.update(false)
            val region = com.unciv.ui.screens.worldscreen.WorldHudLayout.calculate(960f, 900f, scale, false).turnAction
            status.setSize(region.width, region.height)
            status.validate()
            assertTrue("Button width ${button.width} exceeds host ${status.width}", button.x >= 0f && button.x + button.width <= status.width + .01f)
        }
    }

    private fun getter(target: Any, name: String, value: Any) {
        val method = target.javaClass.methods.single { it.name.substringBefore('$') == name }
        Mockito.`when`(method.invoke(target)).thenReturn(value)
    }
    private fun descendants(actor: Actor): List<Actor> = listOf(actor) + if (actor is Group) actor.children.flatMap(::descendants) else emptyList()
}
