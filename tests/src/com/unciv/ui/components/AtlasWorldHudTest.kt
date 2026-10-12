package com.unciv.ui.components

import com.badlogic.gdx.math.Rectangle
import com.unciv.ui.screens.worldscreen.WorldHudLayout
import org.junit.Assert.*
import org.junit.Test
import com.unciv.ui.components.input.onActivation

@org.junit.runner.RunWith(com.unciv.testing.GdxTestRunner::class)
class AtlasWorldHudTest {
    private lateinit var snapshot: SetupUiStateSnapshot
    @org.junit.Before fun remember() { snapshot = SetupUiStateSnapshot(); SetupLayoutFixture(800f, 500f) }
    @org.junit.After fun restore() { snapshot.restore() }

    @Test fun viewSelectionDoesNotGrantControl() {
        val game = com.unciv.testing.TestGame()
        val rome = game.addCiv(game.ruleset.nations.getValue("Rome"), isPlayer = true)
        val spectator = game.addCiv(game.ruleset.nations.getValue(com.unciv.Constants.spectator))
        com.unciv.ui.images.ImageGetter.setNewRuleset(game.ruleset)
        val screen = org.mockito.Mockito.mock(com.unciv.ui.screens.worldscreen.WorldScreen::class.java)
        val session = org.mockito.Mockito.mock(com.unciv.logic.agents.AgentMatchSession::class.java)
        org.mockito.Mockito.`when`(screen.gameInfo).thenReturn(game.gameInfo)
        org.mockito.Mockito.`when`(screen.game).thenReturn(com.unciv.UncivGame.Current)
        org.mockito.Mockito.`when`(screen.viewingCiv).thenReturn(spectator)
        org.mockito.Mockito.`when`(screen.selectedCiv).thenReturn(spectator)
        org.mockito.Mockito.`when`(screen.agentSession).thenReturn(session)
        val ribbon = com.unciv.ui.screens.worldscreen.topbar.NationTurnRibbon()
        ribbon.update(screen)
        org.mockito.Mockito.clearInvocations(session)
        fun descendants(actor: com.badlogic.gdx.scenes.scene2d.Actor): List<com.badlogic.gdx.scenes.scene2d.Actor> =
            listOf(actor) + if (actor is com.badlogic.gdx.scenes.scene2d.Group && !org.mockito.Mockito.mockingDetails(actor).isMock) actor.children.flatMap { descendants(it) } else emptyList()
        val button = descendants(ribbon).first { it.name == "NationTurnEntry:Rome" }
        button.listeners.filterIsInstance<com.unciv.ui.components.input.ActivationListener>().single().tap(
            com.badlogic.gdx.scenes.scene2d.InputEvent().apply { listenerActor = button }, 1f, 1f, 1, 0)
        org.mockito.Mockito.verify(screen).inspectNation(rome.civID)
        org.mockito.Mockito.verifyNoInteractions(session)
    }

    @Test fun humanRosterExcludesUnknownNationDataAndKeepsSavedOrder() {
        val game = com.unciv.testing.TestGame()
        val rome = game.addCiv(game.ruleset.nations.getValue("Rome"), isPlayer = true)
        game.addCiv(game.ruleset.nations.getValue("Korea"))
        game.addCiv(game.ruleset.nations.getValue("China"))
        for (civ in game.gameInfo.civilizations) game.addUnit("Settler", civ, null)
        val spectator = game.addCiv(game.ruleset.nations.getValue(com.unciv.Constants.spectator))
        val roster = com.unciv.ui.screens.worldscreen.topbar.NationTurnRoster
        assertEquals(listOf("Rome"), roster.read(game.gameInfo, rome, "Korea").map { it.civ.civID })
        assertEquals(listOf("Previous in order", "Current", "Next in order"), roster.read(game.gameInfo, spectator, "Korea").map { it.marker })
    }

    @Test fun fifteenLongNamesScrollAtLargeFontWithoutShrinking() {
        com.unciv.UncivGame.Current.settings.fontSizeMultiplier = 1.5f
        val game = com.unciv.testing.TestGame()
        for (nation in game.ruleset.nations.values.filter { it.isMajorCiv }.take(15)) game.addCiv(nation)
        val spectator = game.addCiv(game.ruleset.nations.getValue(com.unciv.Constants.spectator))
        com.unciv.ui.images.ImageGetter.setNewRuleset(game.ruleset)
        val screen = org.mockito.Mockito.mock(com.unciv.ui.screens.worldscreen.WorldScreen::class.java)
        org.mockito.Mockito.`when`(screen.gameInfo).thenReturn(game.gameInfo)
        org.mockito.Mockito.`when`(screen.game).thenReturn(com.unciv.UncivGame.Current)
        org.mockito.Mockito.`when`(screen.viewingCiv).thenReturn(spectator)
        org.mockito.Mockito.`when`(screen.selectedCiv).thenReturn(spectator)
        val ribbon = com.unciv.ui.screens.worldscreen.topbar.NationTurnRibbon()
        ribbon.update(screen)
        val content = ribbon.scroll.actor as com.badlogic.gdx.scenes.scene2d.ui.Table
        assertEquals(15, content.children.size)
        content.children.filterIsInstance<com.badlogic.gdx.scenes.scene2d.ui.TextButton>().forEach {
            it.setText("A very long translated civilization name with diacritics São Paulo")
            assertEquals(1f, it.scaleX, 0f)
        }
        ribbon.setSize(420f, 90f); ribbon.validate(); ribbon.scroll.validate()
        assertTrue(ribbon.scroll.maxX > 0f)
        ribbon.scroll.scrollX = ribbon.scroll.maxX
        assertEquals(ribbon.scroll.maxX, ribbon.scroll.scrollX, .01f)
    }

    @Test fun compactTabsRetainActorsScrollPositionAndReachableAction() {
        val fixture = SetupLayoutFixture(420f, 600f, fontSizeMultiplier = 1.5f)
        val screen = org.mockito.Mockito.mock(com.unciv.ui.screens.worldscreen.WorldScreen::class.java)
        org.mockito.Mockito.`when`(screen.stage).thenReturn(fixture.stage)
        org.mockito.Mockito.`when`(screen.game).thenReturn(com.unciv.UncivGame.Current)
        val top = org.mockito.Mockito.mock(com.unciv.ui.screens.worldscreen.topbar.WorldScreenTopBar::class.java)
        val getter = screen.javaClass.methods.single { it.name.substringBefore('$') == "getTopBar" }
        org.mockito.Mockito.`when`(getter.invoke(screen)).thenReturn(top)
        val selectedActor = com.badlogic.gdx.scenes.scene2d.ui.Table().apply {
            add(GameUiStyle.button("Selected committed Warrior")).width(1000f)
        }
        val next = GameUiStyle.button("Next decision")
        var invoked = 0
        next.onActivation { invoked++ }
        val hud = com.unciv.ui.screens.worldscreen.AtlasWorldHud(screen,
            com.badlogic.gdx.scenes.scene2d.ui.Table(), selectedActor, com.badlogic.gdx.scenes.scene2d.ui.Table(),
            org.mockito.Mockito.mock(com.unciv.ui.screens.worldscreen.minimap.MinimapHolder::class.java), next,
            com.badlogic.gdx.scenes.scene2d.ui.Table())
        hud.layout()
        fun descendants(actor: com.badlogic.gdx.scenes.scene2d.Actor): List<com.badlogic.gdx.scenes.scene2d.Actor> =
            listOf(actor) + if (actor is com.badlogic.gdx.scenes.scene2d.Group && !org.mockito.Mockito.mockingDetails(actor).isMock) actor.children.flatMap { descendants(it) } else emptyList()
        fun press(actor: com.badlogic.gdx.scenes.scene2d.Actor) = actor.listeners.filterIsInstance<com.unciv.ui.components.input.ActivationListener>().single().tap(
            com.badlogic.gdx.scenes.scene2d.InputEvent().apply { listenerActor = actor }, 1f, 1f, 1, 0)
        fun tab(name: String) = descendants(fixture.root).filterIsInstance<com.badlogic.gdx.scenes.scene2d.ui.TextButton>().single { it.text.toString().removePrefix("✓ ") == name }
        press(tab("Selection"))
        val dock = fixture.root.children.single { it.name == "AtlasUnitDock" } as com.unciv.ui.components.widgets.AutoScrollPane
        dock.scrollX = 120f
        press(tab("Research")); press(tab("Selection"))
        assertTrue(selectedActor.isDescendantOf(dock))
        assertEquals(120f, dock.scrollX, .01f)
        assertTrue(dock.isVisible)
        val position = next.localToStageCoordinates(com.badlogic.gdx.math.Vector2())
        assertTrue(position.x >= 0 && position.y >= 0 && position.x + next.width <= 420f && position.y + next.height <= 600f)
        press(next)
        assertEquals(1, invoked)
    }

    @Test fun primaryTurnActionUsesContrastingTextForEnabledAndDisabledStates() {
        com.unciv.models.skins.SkinCache.loadSkinConfigs()
        com.unciv.UncivGame.Current.settings.skin = "Atlas"
        com.unciv.ui.screens.basescreen.BaseScreen.setSkin()
        val screen = org.mockito.Mockito.mock(com.unciv.ui.screens.worldscreen.WorldScreen::class.java)
        val button = com.unciv.ui.screens.worldscreen.status.NextTurnButton(screen)
        button.isDisabled = false
        assertEquals(GameUiStyle.surface, button.label.style.fontColor)
        button.isDisabled = true
        assertEquals(GameUiStyle.muted, button.label.style.fontColor)
        button.isDisabled = false
        assertEquals(GameUiStyle.surface, button.label.style.fontColor)
    }

    @Test fun defaultHudLeavesMapVisible() {
        val layout = WorldHudLayout.calculate(1440f, 900f, 1f, true)
        assertTrue(layout.unobstructedMapFraction >= .45f)
        assertTrue(WorldHudLayout.calculate(1440f, 900f, 1.5f, true).unobstructedMapFraction >= .45f)
    }

    @Test fun smallHudKeepsNextTaskReachable() {
        for ((width, height) in listOf(1440f to 900f, 1920f to 1080f, 960f to 720f, 800f to 500f, 420f to 600f))
            for (scale in listOf(1f, 1.5f)) {
                val layout = WorldHudLayout.calculate(width, height, scale, true)
                for (r in layout.occupied) {
                    assertTrue("$width x $height @ $scale: $r", r.x >= 0 && r.y >= 0 && r.x + r.width <= width && r.y + r.height <= height)
                }
                assertTrue(layout.turnAction.height >= 44f)
                assertFalse(layout.turnAction.overlaps(layout.header))
            }
    }

    @Test fun occupiedAreaCountsOverlapsOnlyOnce() {
        assertEquals(150f, WorldHudLayout.unionArea(listOf(Rectangle(0f, 0f, 10f, 10f), Rectangle(5f, 0f, 10f, 10f))), .01f)
        assertEquals(100f, WorldHudLayout.unionArea(listOf(Rectangle(0f, 0f, 10f, 10f), Rectangle(2f, 2f, 2f, 2f))), .01f)
    }
}
