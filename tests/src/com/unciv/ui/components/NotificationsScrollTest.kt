package com.unciv.ui.components

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.utils.viewport.ScreenViewport
import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.dev.FontDesktop
import com.unciv.logic.agents.AgentMatchSession
import com.unciv.logic.civilization.Notification
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import com.unciv.ui.components.fonts.FontRulesetIcons
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.components.input.ActivationListener
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.UncivStage
import com.unciv.ui.screens.worldscreen.NotificationsScroll
import com.unciv.ui.screens.worldscreen.WorldScreen
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito

private fun Actor.descendants(): Sequence<Actor> = sequence {
    yield(this@descendants)
    if (this@descendants is Group) children.forEach { yieldAll(it.descendants()) }
}

/** Agent matches republish the game many times per turn; a list the user hid must stay hidden. */
@RunWith(GdxTestRunner::class)
class NotificationsScrollTest {
    private lateinit var priorNames: Map<String, Char>
    private lateinit var priorActors: Map<Char, Actor>
    private lateinit var priorSetting: String
    @Before fun preserveGlobals() {
        priorNames = FontRulesetIcons.rulesetObjectNameToChar.toMap()
        priorActors = FontRulesetIcons.charToRulesetImageActor.toMap()
        priorSetting = UncivGame.Current.settings.notificationScroll
    }
    @After fun restoreGlobals() {
        FontRulesetIcons.rulesetObjectNameToChar.clear(); FontRulesetIcons.rulesetObjectNameToChar.putAll(priorNames)
        FontRulesetIcons.charToRulesetImageActor.clear(); FontRulesetIcons.charToRulesetImageActor.putAll(priorActors)
        UncivGame.Current.settings.notificationScroll = priorSetting
    }

    private class Fixture(agentMatch: Boolean) {
        val game = TestGame()
        val spectator = game.addCiv(game.ruleset.nations[Constants.spectator]!!)
        val rome = game.addCiv(game.ruleset.nations["Rome"]!!, isPlayer = true)
        var viewer = spectator
        val screen: WorldScreen = Mockito.mock(WorldScreen::class.java)
        lateinit var stage: UncivStage
        lateinit var scroll: NotificationsScroll

        init {
            Fonts.fontImplementation = FontDesktop()
            ImageGetter.setNewRuleset(game.ruleset)
            UncivGame.Current.settings.notificationScroll = NotificationsScroll.UserSetting.Visible.name
            game.gameInfo.currentPlayer = rome.civID
            Mockito.`when`(screen.gameInfo).thenReturn(game.gameInfo)
            Mockito.`when`(screen.viewingCiv).thenAnswer { viewer }
            Mockito.`when`(screen.selectedCiv).thenAnswer { viewer }
            Mockito.`when`(screen.agentSession).thenReturn(
                if (agentMatch) Mockito.mock(AgentMatchSession::class.java) else null)
        }

        fun <T> use(block: Fixture.() -> T): T =
            // Headless GL cannot compile SpriteBatch's shader; layout and input need no draw calls.
            Mockito.mockConstruction(SpriteBatch::class.java).use {
                stage = UncivStage(ScreenViewport()).apply { viewport.update(960, 600, true) }
                Mockito.`when`(screen.stage).thenReturn(stage)
                scroll = NotificationsScroll(screen)
                stage.addActor(scroll)
                try { block() } finally { stage.dispose() }
            }

        /** Every snapshot deserializes fresh notification objects, even when nothing changed. */
        fun diplomacy(vararg texts: String) = texts.map {
            Notification(it, arrayOf(), null, NotificationCategory.Diplomacy)
        }

        // WorldScreen's internal entry point; the test module cannot call it directly.
        fun update(notifications: List<Notification>) {
            NotificationsScroll::class.java.methods.single { it.name.substringBefore('$') == "update" && it.parameterCount == 3 }
                .invoke(scroll, notifications, 0f, 0f)
        }

        fun hide() {
            val hide = scroll.descendants().first { actor ->
                actor.listeners.any { it is ActivationListener } &&
                    actor.descendants().any { it is Label && it.text.toString() == "Hide" }
            }
            // The click sound lazily creates the shared GL dispatcher. Bind it to an app that is
            // never disposed, as the desktop tests do, or this runner's shutdown cancels later tests' GL work.
            val runnerApp = Gdx.app
            Gdx.app = Mockito.mock(Application::class.java)
            try {
                hide.listeners.filterIsInstance<ActivationListener>().single()
                    .tap(InputEvent().apply { listenerActor = hide }, 1f, 1f, 1, 0)
            } finally { Gdx.app = runnerApp }
        }
    }

    @Test fun `hide control hides the list and republished notifications never reopen it`() = Fixture(agentMatch = true).use {
        update(diplomacy("Russia and The Ottomans have signed a Declaration of Friendship!"))
        assertFalse("A fresh Visible list starts shown", scroll.isHidden)
        hide()
        assertTrue(scroll.isHidden)
        assertEquals("Hiding persists as the user's choice", NotificationsScroll.UserSetting.Hidden.name,
            UncivGame.Current.settings.notificationScroll)
        repeat(3) { update(diplomacy("Russia and The Ottomans have signed a Declaration of Friendship!")) }
        assertTrue("Identical notifications reloaded by a snapshot are not news", scroll.isHidden)
        update(diplomacy("Korea has declared war on Japan!",
            "Russia and The Ottomans have signed a Declaration of Friendship!"))
        assertTrue("Spectating, other nations' diplomacy only updates the bell", scroll.isHidden)
    }

    @Test fun `a hidden list reopens when the viewer's own human turn starts`() = Fixture(agentMatch = true).use {
        update(diplomacy("Germany and England have signed a Declaration of Friendship!"))
        hide()
        viewer = rome
        update(diplomacy("Germany and England have signed a Declaration of Friendship!"))
        assertTrue("The same turn without new notifications stays hidden", scroll.isHidden)
        game.gameInfo.turns++
        update(diplomacy("Your scout found ancient ruins!"))
        assertFalse("A new turn of your own with news reopens the list", scroll.isHidden)
    }

    @Test fun `outside agent matches new notifications still reopen a hidden list`() = Fixture(agentMatch = false).use {
        viewer = rome
        update(diplomacy("France has declared war on Songhai!"))
        hide()
        update(diplomacy("France has declared war on Songhai!"))
        assertTrue(scroll.isHidden)
        update(diplomacy("Songhai and France have signed a Peace Treaty!"))
        assertFalse(scroll.isHidden)
    }
}
