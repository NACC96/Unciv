package com.unciv.ui.components

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.TextArea
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.math.Vector2
import com.unciv.Constants
import com.unciv.dev.FontDesktop
import com.unciv.logic.agents.AgentMatchSession
import com.unciv.logic.agents.AgentRunState
import com.unciv.logic.agents.DiplomaticMessage
import com.unciv.logic.agents.MessageContact
import com.unciv.models.translations.tr
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.components.fonts.FontRulesetIcons
import com.unciv.ui.components.input.ActivationListener
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.UncivStage
import com.unciv.ui.screens.worldscreen.TechPolicyDiplomacyButtons
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.ui.screens.worldscreen.status.AgentMatchControlTable
import com.unciv.ui.screens.worldscreen.status.AgentMessagesPanel
import com.unciv.ui.screens.worldscreen.status.AgentActivityFeed
import com.unciv.ui.screens.worldscreen.overview.CivilizationOverviewPanel
import com.unciv.ui.screens.worldscreen.topbar.WorldScreenTopBar
import com.unciv.view.GameView
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito

@RunWith(GdxTestRunner::class)
class AgentMessagesPanelTest {
    private lateinit var priorNames: Map<String, Char>
    private lateinit var priorActors: Map<Char, Actor>
    @Before fun preserveIcons() {
        priorNames = FontRulesetIcons.rulesetObjectNameToChar.toMap()
        priorActors = FontRulesetIcons.charToRulesetImageActor.toMap()
    }
    @After fun restoreIcons() {
        FontRulesetIcons.rulesetObjectNameToChar.clear(); FontRulesetIcons.rulesetObjectNameToChar.putAll(priorNames)
        FontRulesetIcons.charToRulesetImageActor.clear(); FontRulesetIcons.charToRulesetImageActor.putAll(priorActors)
    }
    private class Fixture(var width: Float = 960f, var height: Float = 600f) {
        val game = TestGame()
        val spectator = game.addCiv(game.ruleset.nations[Constants.spectator]!!)
        val rome = game.addCiv(game.ruleset.nations["Rome"]!!)
        var viewer = spectator
        var messages = emptyList<DiplomaticMessage>()
        var contacts = listOf(MessageContact("Greece", "Athena", true))
        var completion: ((String?) -> Unit)? = null
        val requests = ArrayList<Pair<String, String>>()
        val root = Group()
        val stage = Mockito.mock(UncivStage::class.java)
        val session = Mockito.mock(AgentMatchSession::class.java)
        val screen = Mockito.mock(WorldScreen::class.java)
        init {
            Fonts.fontImplementation = FontDesktop()
            ImageGetter.setNewRuleset(game.ruleset)
            Mockito.`when`(stage.width).thenAnswer { width }
            Mockito.`when`(stage.height).thenAnswer { height }
            Mockito.`when`(stage.root).thenReturn(root)
            Group::class.java.getDeclaredMethod("setStage", com.badlogic.gdx.scenes.scene2d.Stage::class.java)
                .apply { isAccessible = true }.invoke(root, stage)
            Mockito.doAnswer { root.addActor(it.getArgument(0)); null }.`when`(stage).addActor(Mockito.any(Actor::class.java))
            Mockito.`when`(screen.stage).thenReturn(stage)
            Mockito.`when`(screen.gameInfo).thenReturn(game.gameInfo)
            Mockito.`when`(screen.viewingCiv).thenAnswer { viewer }
            Mockito.`when`(screen.currentViewerCivId()).thenAnswer { viewer.civID }
            Mockito.`when`(screen.agentSession).thenReturn(session)
            Mockito.`when`(session.getRunState()).thenReturn(AgentRunState.PAUSED)
            Mockito.`when`(session.canResume()).thenReturn(true)
            Mockito.`when`(session.getActivity()).thenReturn(emptyList())
            Mockito.`when`(session.getMessages()).thenAnswer { messages }
            Mockito.`when`(session.getMessageContacts()).thenAnswer { contacts }
            Mockito.doAnswer {
                requests.add(it.getArgument<String>(0) to it.getArgument<String>(1))
                completion = it.getArgument(2)
                null
            }.`when`(session).sendMessage(Mockito.anyString(), Mockito.anyString(), Mockito.any<(String?) -> Unit>() ?: {})
            val tech = Mockito.mock(TechPolicyDiplomacyButtons::class.java)
            Mockito.`when`(tech.y).thenAnswer { height - 100f }
            val getter = screen.javaClass.methods.single { it.name.substringBefore('$') == "getTechPolicyAndDiplomacy" }
            Mockito.`when`(getter.invoke(screen) as TechPolicyDiplomacyButtons?).thenReturn(tech)
        }
    }
    private fun Actor.descendants(): Sequence<Actor> = sequence {
        yield(this@descendants)
        if (this@descendants is Group) children.forEach { yieldAll(it.descendants()) }
    }
    private fun Actor.press() = listeners.filterIsInstance<ActivationListener>().single()
        .tap(InputEvent().apply { listenerActor = this@press }, 1f, 1f, 1, 0)
    private fun AgentMatchControlTable.openPanel(f: Fixture): AgentMessagesPanel {
        descendants().filterIsInstance<TextButton>().single { it.text.toString() == "Messages" }.press()
        return f.root.children.filterIsInstance<AgentMessagesPanel>().single()
    }
    private fun Actor.labels() = descendants().filterIsInstance<Label>().map { it.text.toString() }.toList()
    @Test fun `match controls provide a Messages button beside Activity`() {
        val f = Fixture()
        val controls = AgentMatchControlTable(f.session, f.screen)
        try {
            val buttons = controls.descendants().filterIsInstance<TextButton>().map { it.text.toString() }.toList()
            assertTrue("Messages should be accessible without leaving the live map", "Messages" in buttons)
            assertTrue("Activity" in buttons)
        } finally { controls.dispose() }
    }

    @Test fun `Messages and Overview controls show only their chosen detail panel`() {
        val f = Fixture()
        Mockito.`when`(f.screen.selectedCiv).thenReturn(f.viewer)
        Mockito.`when`(f.screen.selectedGameView).thenReturn(GameView(f.game.gameInfo, f.viewer, true))
        Mockito.`when`(f.session.getActiveCivId()).thenReturn(f.rome.civID)
        Mockito.`when`(f.session.getMessages()).thenReturn(emptyList())
        Mockito.doCallRealMethod().`when`(f.screen).toggleCivilizationOverview()
        Mockito.doCallRealMethod().`when`(f.screen).hideCivilizationOverview()
        val controls = AgentMatchControlTable(f.session, f.screen)
        val bar = WorldScreenTopBar(f.screen)
        f.root.addActor(controls)
        f.root.addActor(bar)
        bar.javaClass.methods.single { it.name.substringBefore('$') == "update" }.invoke(bar, f.viewer)
        val overviewButton = bar.descendants().filterIsInstance<TextButton>().single { it.text.toString() == "Overview" }
        var overview: CivilizationOverviewPanel? = null
        try {
            overviewButton.press()
            overview = f.root.children.filterIsInstance<CivilizationOverviewPanel>().single()
            assertTrue(overview.isVisible)
            val messages = controls.openPanel(f)
            assertTrue(messages.isVisible)
            assertFalse("Opening Messages must close the existing civilization overview", overview.isVisible)
            assertTrue("Activity can remain alongside the chosen detail panel",
                f.root.children.filterIsInstance<AgentActivityFeed>().single().isVisible)
            overview.refresh(); controls.update()
            assertFalse("An ordinary refresh must not reopen Overview", overview.isVisible)
            overviewButton.press()
            assertTrue(overview.isVisible)
            assertFalse("Opening Overview must close Messages", messages.isVisible)
            messages.refresh(); controls.update()
            assertFalse("An ordinary refresh must not reopen Messages", messages.isVisible)
            controls.openPanel(f)
            assertTrue(messages.isVisible)
            assertFalse(overview.isVisible)
            controls.openPanel(f)
            assertFalse(messages.isVisible)
            assertFalse("Closing Messages must not resurrect Overview", overview.isVisible)
        } finally { overview?.dispose(); controls.dispose() }
    }

    @Test fun `viewer switch removes previous private messages even when the new inbox is empty`() {
        val f = Fixture()
        f.messages = listOf(DiplomaticMessage("secret", "Greece", "Korea", "Private treaty secret", 1, 2))
        val controls = AgentMatchControlTable(f.session, f.screen)
        try {
            val panel = controls.openPanel(f)
            assertTrue("Private treaty secret" in panel.labels())
            f.viewer = f.rome; f.messages = emptyList(); f.contacts = emptyList()
            panel.refresh()
            assertFalse("Previous viewer's private history must be removed", "Private treaty secret" in panel.labels())
            assertTrue(panel.labels().any { it.startsWith("No messages yet") })
        } finally { controls.dispose() }
    }

    @Test fun `history refresh distinguishes delivery from saved agent acknowledgement`() {
        val f = Fixture()
        val message = DiplomaticMessage("guide", Constants.spectator, "Rome", "Please confirm receipt", 1, null, "SPECTATOR")
        f.messages = listOf(message)
        val controls = AgentMatchControlTable(f.session, f.screen)
        try {
            val panel = controls.openPanel(f)
            assertTrue("Queued · next turn" in panel.labels())
            assertFalse(panel.labels().any { it.startsWith("Acknowledged") })
            message.deliveredTurnSerial = 2
            panel.refresh()
            assertTrue("Delivered · turn 2" in panel.labels())
            assertFalse(panel.labels().any { it.startsWith("Acknowledged") })
            message.acknowledgedTurnSerial = 2
            message.acknowledgementText = "Message received, acknowledged."
            panel.refresh()
            assertTrue("Acknowledged · turn 2" in panel.labels())
            assertTrue("Rome acknowledged receipt" in panel.labels())
            assertTrue("Message received, acknowledged." in panel.labels())
            assertFalse("Delivered · turn 2" in panel.labels())
        } finally { controls.dispose() }
    }

    @Test fun `composer waits for acknowledgement preserves failures and clears only successful sends`() {
        val f = Fixture()
        val controls = AgentMatchControlTable(f.session, f.screen)
        try {
            val panel = controls.openPanel(f)
            val body = panel.descendants().filterIsInstance<TextArea>().single()
            val send = panel.descendants().filterIsInstance<TextButton>().single { it.name == "MessageSend" }
            assertTrue(send.isDisabled)
            body.text = "Peace?\nLet us leave our borders open."
            body.fire(com.badlogic.gdx.scenes.scene2d.utils.ChangeListener.ChangeEvent())
            send.press()
            assertEquals(listOf("Greece" to body.text), f.requests)
            assertEquals("Saving…", send.text.toString())
            assertTrue(send.isDisabled)
            assertTrue(body.text.startsWith("Peace?"))
            f.completion!!("Disk unavailable")
            assertEquals("Disk unavailable", panel.labels().last())
            assertTrue(body.text.startsWith("Peace?"))
            assertFalse(send.isDisabled)
            send.press(); f.completion!!(null)
            assertEquals("", body.text)
            assertTrue(panel.labels().any { it.contains("Queued for Greece") })
        } finally { controls.dispose() }
    }

    @Test fun `mailbox is modeless and its composer and controls fit a small window`() {
        val f = Fixture(width = 420f, height = 600f)
        val controls = AgentMatchControlTable(f.session, f.screen)
        try {
            val panel = controls.openPanel(f)
            panel.validate()
            assertEquals(2, f.root.children.size) // feed and mailbox; no modal input blocker
            assertTrue(panel.x >= 0f && panel.x + panel.width <= f.width)
            assertTrue(panel.y >= 0f && panel.y + panel.height <= f.height)
            val send = panel.descendants().single { it.name == "MessageSend" }
            val position = send.localToStageCoordinates(com.badlogic.gdx.math.Vector2())
            assertTrue(position.x >= panel.x && position.x + send.width <= panel.x + panel.width)
            assertTrue(position.y >= panel.y && position.y + send.height <= panel.y + panel.height)
            assertTrue(controls.width <= f.width - 20f)
        } finally { controls.dispose() }
    }

    @Test fun `spectator and large roster senders have visible message identity colors`() {
        val f = Fixture()
        for (nation in listOf("America", "Arabia", "China", "England", "France", "Germany", "Korea", "Russia"))
            f.game.addCiv(f.game.ruleset.nations[nation]!!)
        f.messages = listOf(
            DiplomaticMessage("guide", Constants.spectator, "Rome", "Explore the coastline", 1, null, "SPECTATOR"),
            DiplomaticMessage("ninth", "Russia", "Rome", "Trade proposal", 1, null))
        val controls = AgentMatchControlTable(f.session, f.screen)
        try {
            val panel = controls.openPanel(f)
            for (sender in listOf("Spectator", "Russia")) {
                val heading = panel.descendants().filterIsInstance<Label>().single { it.text.toString() == "$sender → Rome" }
                assertTrue("$sender identity must remain legible", heading.style.fontColor.a > 0.5f)
            }
        } finally { controls.dispose() }
    }

    @Test fun `long message identities receipts and acknowledgement wrap inside a narrow history`() {
        val f = Fixture()
        val target = "The Ottomans · Keeper of the distant coastlines and trade routes"
        f.messages = listOf(
            DiplomaticMessage("acknowledged", "Rome", target, "Please confirm our peaceful borders.", 1234, 1235).apply {
                acknowledgedTurnSerial = 1235
                acknowledgementText = "Received. I will keep our borders peaceful while expanding toward the coast."
            },
            DiplomaticMessage("queued", Constants.spectator, target, "Consider another Scout.", 1235, null, "SPECTATOR"))
        val controls = AgentMatchControlTable(f.session, f.screen)
        try {
            val panel = controls.openPanel(f)
            for (width in listOf(960f, 420f, 360f, 960f)) {
                f.width = width
                panel.refresh(); panel.validate()
                val history = panel.descendants().filterIsInstance<AutoScrollPane>().single()
                assertEquals("Long message identities must wrap rather than create horizontal scrolling", 0f, history.maxX, 0.5f)
                for (label in history.descendants().filterIsInstance<Label>()) {
                    val point = label.localToAscendantCoordinates(panel, Vector2())
                    assertTrue("History label starts outside the panel at $width: ${label.text}", point.x >= -0.5f)
                    assertTrue("History label extends outside the panel at $width: ${label.text}",
                        point.x + label.width <= panel.width + 0.5f)
                }
                val labels = panel.labels()
                // Ordinary game labels localize four-digit turn numbers as well as their text.
                for (receipt in listOf("Sent turn 1234", "Acknowledged · turn 1235", "Queued · next turn"))
                    assertTrue("Receipt missing at $width: $receipt in $labels", receipt.tr() in labels)
                assertTrue("$target acknowledged receipt".tr(hideIcons = true) in labels)
                assertTrue("Received. I will keep our borders peaceful while expanding toward the coast." in labels)
            }
        } finally { controls.dispose() }
    }
}
