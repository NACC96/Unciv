package com.unciv.ui.components

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.Texture
import com.unciv.logic.agents.MessageImageClipboard
import com.unciv.logic.agents.MessageImageDraft
import com.unciv.logic.agents.DiplomaticImageAttachment
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.TextArea
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.utils.viewport.ScreenViewport
import com.badlogic.gdx.utils.Clipboard
import com.badlogic.gdx.scenes.scene2d.utils.UIUtils
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
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.components.input.KeyShortcutDispatcher
import com.unciv.ui.components.input.KeyboardBinding
import com.unciv.ui.components.input.KeyboardBindings
import com.unciv.ui.components.input.KeyboardPanningListener
import com.unciv.ui.components.input.installShortcutDispatcher
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.components.widgets.ZoomableScrollPane
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
        val images = ArrayList<MessageImageDraft?>()
        val imageReads = ArrayList<Pair<String, String>>()
        var imageReadCompletion: ((MessageImageDraft?, String?) -> Unit)? = null
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
            Mockito.doAnswer {
                requests.add(it.getArgument<String>(0) to it.getArgument<String>(1))
                images.add(it.getArgument(2))
                completion = it.getArgument(3)
                null
            }.`when`(session).sendMessage(Mockito.anyString(), Mockito.anyString(), Mockito.any(MessageImageDraft::class.java), Mockito.any<(String?) -> Unit>() ?: {})
            Mockito.doAnswer {
                imageReads.add(it.getArgument<String>(0) to it.getArgument<String>(1))
                imageReadCompletion = it.getArgument(2)
                null
            }.`when`(session).readMessageImage(Mockito.anyString(), Mockito.anyString(), Mockito.any<(MessageImageDraft?, String?) -> Unit>() ?: { _, _ -> })
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
    private class ImageClipboard : MessageImageClipboard {
        var available = true
        var reads = 0
        var completion: ((MessageImageDraft?, String?) -> Unit)? = null
        override fun hasImageContents() = available
        override fun readImage(onComplete: (MessageImageDraft?, String?) -> Unit) { reads++; completion = onComplete }
    }
    private fun imageDraft() = MessageImageDraft(java.util.Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAC0lEQVR4nGP4DwQACfsD/fteaysAAAAASUVORK5CYII="), "image/png", 1, 1)
    private fun Actor.button(label: String) = descendants().filterIsInstance<TextButton>().single { it.text.toString() == label }
    private fun Actor.labels() = descendants().filterIsInstance<Label>().map { it.text.toString() }.toList()

    /** Exercise real Scene2D focus and event bubbling; fixture mocks only game/session state. */
    private fun withComposer(block: (UncivStage, AgentMessagesPanel, TextArea) -> Unit) {
        val f = Fixture()
        // Keyboard routing needs no draw calls; headless GL cannot compile SpriteBatch's shader.
        Mockito.mockConstruction(SpriteBatch::class.java).use {
            val stage = UncivStage(ScreenViewport())
            stage.viewport.update(960, 600, true)
            Mockito.`when`(f.screen.stage).thenReturn(stage)
            val panel = AgentMessagesPanel(f.screen, f.session)
            try {
                panel.toggle()
                panel.validate()
                val editor = panel.descendants().filterIsInstance<TextArea>().single()
                stage.keyboardFocus = editor
                block(stage, panel, editor)
            } finally {
                panel.dispose()
                stage.dispose()
                stage.batch.dispose()
            }
        }
    }

    @Test fun `composer typing does not activate world or actor shortcuts`() = withComposer { stage, _, editor ->
        var globalOpens = 0
        var actorOpens = 0
        val shortcuts = KeyShortcutDispatcher().apply {
            add('t') { globalOpens++ }
            add(KeyCharAndCode.SPACE) { globalOpens++ }
            add(KeyCharAndCode.RETURN) { globalOpens++ }
            add(KeyCharAndCode(Input.Keys.LEFT)) { globalOpens++ }
        }
        stage.addActor(Actor().apply { keyShortcuts.add('h') { actorOpens++ } })
        stage.installShortcutDispatcher(shortcuts) { null }
        for ((key, char) in listOf(Input.Keys.T to 'T', Input.Keys.H to 'h', Input.Keys.E to 'e', Input.Keys.SPACE to ' ')) {
            stage.keyDown(key)
            stage.keyTyped(char)
            stage.keyUp(key)
        }
        stage.keyDown(Input.Keys.ENTER)
        stage.keyTyped('\r')
        stage.keyUp(Input.Keys.ENTER)
        val cursorBeforeLeft = editor.cursorPosition
        stage.keyDown(Input.Keys.LEFT)
        stage.keyUp(Input.Keys.LEFT)
        assertEquals("The editor must still move its caret", cursorBeforeLeft - 1, editor.cursorPosition)
        assertEquals("The \n", editor.text)
        assertEquals("Typing must not open a screen through global bindings", 0, globalOpens)
        assertEquals("Typing must not trigger another actor's binding", 0, actorOpens)
    }

    @Test fun `composer select all copy and paste stay in the text editor`() = withComposer { stage, _, editor ->
        val priorInput = Gdx.input
        try {
            // Platform input state is external to Stage.keyDown; keep native editing listeners real.
            Gdx.input = Mockito.mock(Input::class.java)
            val controlKey = if (UIUtils.isMac) Input.Keys.SYM else Input.Keys.CONTROL_LEFT
            Mockito.`when`(Gdx.input.isKeyPressed(controlKey)).thenReturn(true)
            editor.setClipboard(object : Clipboard {
                private var value = ""
                override fun hasContents() = value.isNotEmpty()
                override fun getContents() = value
                override fun setContents(content: String) { value = content }
            })
            var shortcutOpens = 0
            val shortcuts = KeyShortcutDispatcher().apply {
                for (letter in listOf('A', 'C', 'V')) {
                    add(letter) { shortcutOpens++ }
                    add(KeyCharAndCode.ctrl(letter)) { shortcutOpens++ }
                }
            }
            stage.installShortcutDispatcher(shortcuts) { null }
            editor.text = "Peaceful borders"
            stage.keyDown(Input.Keys.A)
            stage.keyUp(Input.Keys.A)
            assertEquals("Peaceful borders", editor.selection)
            stage.keyDown(Input.Keys.C)
            stage.keyUp(Input.Keys.C)
            editor.text = ""
            stage.keyDown(Input.Keys.V)
            stage.keyUp(Input.Keys.V)
            assertEquals("Peaceful borders", editor.text)
            assertEquals("Text editing chords must not activate world shortcuts", 0, shortcutOpens)
        } finally { Gdx.input = priorInput }
    }

    @Test fun `hiding composer clears focus and restores world shortcuts`() = withComposer { stage, panel, _ ->
        var globalOpens = 0
        var actorOpens = 0
        val shortcuts = KeyShortcutDispatcher().apply { add('t') { globalOpens++ } }
        stage.addActor(Actor().apply { keyShortcuts.add('h') { actorOpens++ } })
        stage.installShortcutDispatcher(shortcuts) { null }
        panel.hide()
        assertNull(stage.keyboardFocus)
        stage.keyDown(Input.Keys.T)
        stage.keyDown(Input.Keys.H)
        assertEquals(1, globalOpens)
        assertEquals(1, actorOpens)
    }

    @Test fun `composer focus still allows an earlier map pan key to be released`() = withComposer { stage, _, editor ->
        val map = ZoomableScrollPane()
        val panning = KeyboardPanningListener(map, allowWASD = true)
        stage.addActor(map)
        stage.addListener(panning)
        try {
            stage.keyboardFocus = null
            val panKey = KeyboardBindings[KeyboardBinding.PanLeft].code
            stage.keyDown(panKey)
            assertEquals(1, map.actions.size)
            stage.keyboardFocus = editor
            stage.keyUp(panKey)
            assertEquals("Release must end the map's repeat action even after composer focus", 0, map.actions.size)
        } finally { panning.dispose() }
    }

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
    @Test fun `image only draft previews removes and survives failed send`() {
        val f = Fixture()
        val clipboard = ImageClipboard()
        Mockito.mockConstruction(Texture::class.java).use {
            val panel = AgentMessagesPanel(f.screen, f.session, clipboard)
            try {
                panel.toggle()
                assertTrue(panel.button("Send").isDisabled)
                panel.button("Paste image").press()
                clipboard.completion!!(imageDraft(), null)
                assertFalse(panel.button("Send").isDisabled)
                assertNotNull(panel.descendants().singleOrNull { it.name == "MessageImagePreview" })
                panel.button("Send").press()
                assertEquals("", f.requests.single().second)
                assertEquals(1, f.images.size)
                f.completion!!("Save failed")
                assertTrue("Save failed" in panel.labels())
                assertNotNull(panel.descendants().singleOrNull { it.name == "MessageImagePreview" })
                panel.button("Remove image").press()
                assertNull(panel.descendants().singleOrNull { it.name == "MessageImagePreview" })
                assertTrue(panel.button("Send").isDisabled)
            } finally { panel.dispose() }
        }
    }

    @Test fun `failed clipboard replacement preserves draft and stale reads cannot restore it`() {
        val f = Fixture()
        val clipboard = ImageClipboard()
        Mockito.mockConstruction(Texture::class.java).use { textures ->
            val panel = AgentMessagesPanel(f.screen, f.session, clipboard)
            try {
                panel.toggle(); panel.button("Paste image").press()
                clipboard.completion!!(imageDraft(), null)
                panel.button("Paste image").press()
                clipboard.completion!!(null, "Unsupported image")
                assertNotNull(panel.descendants().singleOrNull { it.name == "MessageImagePreview" })
                panel.button("Paste image").press()
                val oldCompletion = clipboard.completion!!
                panel.button("Remove image").press()
                oldCompletion(imageDraft(), null)
                assertNull(panel.descendants().singleOrNull { it.name == "MessageImagePreview" })
                Mockito.verify(textures.constructed().single()).dispose()
            } finally { panel.dispose() }
        }
    }

    @Test fun `hidden disposed and viewer changed panels reject delayed clipboard reads`() {
        for (change in listOf("hide", "dispose", "viewer")) {
            val f = Fixture()
            val clipboard = ImageClipboard()
            val panel = AgentMessagesPanel(f.screen, f.session, clipboard)
            panel.toggle(); panel.button("Paste image").press()
            when (change) {
                "hide" -> panel.hide()
                "dispose" -> panel.dispose()
                else -> { f.viewer = f.rome; panel.refresh() }
            }
            clipboard.completion!!(imageDraft(), null)
            assertNull(panel.descendants().singleOrNull { it.name == "MessageImagePreview" })
            panel.dispose()
        }
    }

    @Test fun `image paste chord consumes image without native text paste or world shortcut`() {
        val f = Fixture()
        val clipboard = ImageClipboard()
        val priorInput = Gdx.input
        try {
            Gdx.input = Mockito.mock(Input::class.java)
            Mockito.`when`(Gdx.input.isKeyPressed(if (UIUtils.isMac) Input.Keys.SYM else Input.Keys.CONTROL_LEFT)).thenReturn(true)
            Mockito.mockConstruction(SpriteBatch::class.java).use {
                val stage = UncivStage(ScreenViewport())
                Mockito.`when`(f.screen.stage).thenReturn(stage)
                val panel = AgentMessagesPanel(f.screen, f.session, clipboard)
                try {
                    panel.toggle()
                    val editor = panel.descendants().filterIsInstance<TextArea>().single()
                    editor.setClipboard(object : Clipboard {
                        override fun hasContents() = true
                        override fun getContents() = "native paste"
                        override fun setContents(content: String?) {}
                    })
                    stage.keyboardFocus = editor
                    var shortcuts = 0
                    stage.installShortcutDispatcher(KeyShortcutDispatcher().apply { add(KeyCharAndCode.ctrl('V')) { shortcuts++ } }) { null }
                    stage.keyDown(Input.Keys.V); stage.keyUp(Input.Keys.V)
                    assertEquals(1, clipboard.reads)
                    assertEquals("", editor.text)
                    assertEquals(0, shortcuts)
                    clipboard.available = false
                    stage.keyDown(Input.Keys.V); stage.keyUp(Input.Keys.V)
                    assertEquals("native paste", editor.text)
                    assertEquals(1, clipboard.reads)
                } finally { panel.dispose(); stage.dispose(); stage.batch.dispose() }
            }
        } finally { Gdx.input = priorInput }
    }

    @Test fun `history image reads use session and stale callbacks cannot repopulate private history`() {
        val f = Fixture()
        f.messages = listOf(DiplomaticMessage("image", "Greece", "Rome", "See coastline", 1, 2).apply {
            attachments.add(DiplomaticImageAttachment("a".repeat(64), "image/png", 1, 1, 100))
        })
        val panel = AgentMessagesPanel(f.screen, f.session)
        try {
            panel.toggle()
            assertTrue(f.imageReads.contains("image" to "a".repeat(64)))
            val callback = f.imageReadCompletion!!
            f.viewer = f.rome; f.messages = emptyList(); panel.refresh()
            callback(imageDraft(), null)
            assertNull(panel.descendants().singleOrNull { it.name == "MessageHistoryImage" })
        } finally { panel.dispose() }
    }

    @Test fun `unknown vision warning persists through failure and still allows image send`() {
        val f = Fixture()
        Mockito.`when`(f.session.getAgentProfile("Greece")).thenReturn(com.unciv.logic.agents.AgentProfile(
            provider = "codex", modelId = "unknown-private-model"))
        val clipboard = ImageClipboard()
        Mockito.mockConstruction(Texture::class.java).use {
            val panel = AgentMessagesPanel(f.screen, f.session, clipboard)
            try {
                panel.toggle(); panel.button("Paste image").press(); clipboard.completion!!(imageDraft(), null)
                assertTrue(panel.labels().any { it.contains("Image support is unknown") })
                assertFalse(panel.button("Send").isDisabled)
                panel.button("Send").press(); f.completion!!("Save failed")
                assertTrue(panel.labels().any { it.contains("Image support is unknown") })
                assertTrue("Save failed" in panel.labels())
            } finally { panel.dispose() }
        }
    }

    @Test fun `image textures released when hidden recreated when shown and disposed`() {
        val f = Fixture()
        val clipboard = ImageClipboard()
        Mockito.mockConstruction(Texture::class.java).use { textures ->
            val panel = AgentMessagesPanel(f.screen, f.session, clipboard)
            panel.toggle(); panel.button("Paste image").press(); clipboard.completion!!(imageDraft(), null)
            panel.hide()
            Mockito.verify(textures.constructed().single()).dispose()
            panel.toggle()
            assertEquals(2, textures.constructed().size)
            assertFalse(panel.button("Send").isDisabled)
            panel.dispose()
            Mockito.verify(textures.constructed()[1]).dispose()
        }
    }

    @Test fun `unsupported vision warns without blocking and successful image send clears draft`() {
        val f = Fixture()
        val model = com.unciv.logic.agents.ModelSpec("ui-test-no-vision", "Test model", "ui-test-provider", emptyList(), supportsImages = false)
        com.unciv.logic.agents.AgentModelCatalog.setDiscoveredModels("ui-test-provider", listOf(model))
        Mockito.`when`(f.session.getAgentProfile("Greece")).thenReturn(com.unciv.logic.agents.AgentProfile(provider = model.provider, modelId = model.id))
        val clipboard = ImageClipboard()
        try {
            Mockito.mockConstruction(Texture::class.java).use { textures ->
                val panel = AgentMessagesPanel(f.screen, f.session, clipboard)
                try {
                    panel.toggle(); panel.button("Paste image").press(); clipboard.completion!!(imageDraft(), null)
                    assertTrue(panel.labels().any { it.contains("does not support images") })
                    assertFalse(panel.button("Send").isDisabled)
                    panel.button("Send").press(); f.completion!!(null)
                    assertNull(panel.descendants().singleOrNull { it.name == "MessageImagePreview" })
                    assertFalse(panel.labels().any { it.contains("does not support images") })
                    assertTrue(panel.button("Send").isDisabled)
                    Mockito.verify(textures.constructed().single()).dispose()
                } finally { panel.dispose() }
            }
        } finally { com.unciv.logic.agents.AgentModelCatalog.setDiscoveredModels("ui-test-provider", emptyList()) }
    }

    @Test fun `history preloads at most four recent images and older images load on demand`() {
        val f = Fixture()
        f.messages = (1..10).map { index ->
            DiplomaticMessage("image-$index", "Greece", "Rome", "Image $index", index, index).apply {
                attachments.add(DiplomaticImageAttachment(index.toString(16).padStart(64, '0'), "image/png", 1, 1, 100))
            }
        }
        val panel = AgentMessagesPanel(f.screen, f.session)
        try {
            panel.toggle()
            assertEquals(4, f.imageReads.size)
            assertEquals(listOf("image-7", "image-8", "image-9", "image-10"), f.imageReads.map { it.first })
            val view = panel.descendants().filterIsInstance<TextButton>().first { it.text.toString() == "View image" }
            view.press()
            assertEquals(5, f.imageReads.size)
            assertEquals("image-1", f.imageReads.last().first)
        } finally { panel.dispose() }
    }

    @Test fun `large image draft preview texture is bounded while send preserves original bytes and dimensions`() {
        val f = Fixture()
        val clipboard = ImageClipboard()
        val original = com.unciv.app.desktop.MessageImageNormalizer.normalize(
            java.awt.image.BufferedImage(1600, 800, java.awt.image.BufferedImage.TYPE_INT_ARGB))
        val dimensions = ArrayList<Pair<Int, Int>>()
        Mockito.mockConstruction(Texture::class.java) { _, context ->
            val pixmap = context.arguments()[0] as com.badlogic.gdx.graphics.Pixmap
            dimensions.add(pixmap.width to pixmap.height)
        }.use {
            val panel = AgentMessagesPanel(f.screen, f.session, clipboard)
            try {
                panel.toggle(); panel.button("Paste image").press(); clipboard.completion!!(original, null)
                assertEquals(listOf(512 to 256), dimensions)
                panel.button("Send").press()
                assertEquals(1600, f.images.single()!!.width)
                assertEquals(800, f.images.single()!!.height)
                assertArrayEquals(original.bytes, f.images.single()!!.bytes)
            } finally { panel.dispose() }
        }
    }

    @Test fun `pending image paste blocks send until read completes or failed replacement unlocks retained draft`() {
        val f = Fixture()
        val clipboard = ImageClipboard()
        Mockito.mockConstruction(Texture::class.java).use {
            val panel = AgentMessagesPanel(f.screen, f.session, clipboard)
            try {
                panel.toggle()
                panel.descendants().filterIsInstance<TextArea>().single().apply {
                    text = "Look at this coast"
                    fire(com.badlogic.gdx.scenes.scene2d.utils.ChangeListener.ChangeEvent())
                }
                assertFalse(panel.button("Send").isDisabled)
                panel.button("Paste image").press()
                assertTrue(panel.button("Send").isDisabled)
                assertTrue("Preparing image…" in panel.labels())
                panel.button("Send").press()
                assertTrue(f.requests.isEmpty())
                clipboard.completion!!(imageDraft(), null)
                assertFalse(panel.button("Send").isDisabled)
                panel.button("Paste image").press()
                assertTrue(panel.button("Send").isDisabled)
                clipboard.completion!!(null, "Clipboard image unavailable")
                assertFalse(panel.button("Send").isDisabled)
                assertNotNull(panel.descendants().singleOrNull { it.name == "MessageImagePreview" })
                panel.button("Send").press()
                assertEquals(1, f.images.size)
                assertEquals("Look at this coast", f.requests.single().second)
            } finally { panel.dispose() }
        }
    }

    @Test fun `history preserves bottom or earlier position through image composer changes and compact resize`() {
        val f = Fixture(420f, 600f)
        f.messages = (1..20).map { index ->
            DiplomaticMessage("message-$index", "Greece", "Rome", "Message $index about peaceful borders and future coastal trade.", index, index)
        }
        val clipboard = ImageClipboard()
        Mockito.mockConstruction(Texture::class.java).use {
            val panel = AgentMessagesPanel(f.screen, f.session, clipboard)
            try {
                panel.toggle(); panel.validate()
                val scroll = panel.descendants().filterIsInstance<AutoScrollPane>().single()
                assertTrue(scroll.maxY > 100f)
                scroll.scrollY = scroll.maxY; scroll.updateVisualScroll()
                panel.button("Paste image").press(); clipboard.completion!!(imageDraft(), null)
                panel.refresh(); panel.validate()
                assertEquals("Adding preview and vision warning must keep the newest messages in view", scroll.maxY, scroll.scrollY, 0.5f)
                f.width = 360f; f.height = 540f
                panel.refresh(); panel.validate()
                assertEquals("Compact resizing must preserve end-follow after the parent lays out", scroll.maxY, scroll.scrollY, 0.5f)
                scroll.scrollY = scroll.maxY / 2f; scroll.updateVisualScroll()
                val earlierPosition = scroll.scrollY
                panel.button("Remove image").press(); panel.refresh(); panel.validate()
                assertEquals("Removing a draft must preserve the reader's earlier scroll position", earlierPosition.coerceAtMost(scroll.maxY), scroll.scrollY, 0.5f)
            } finally { panel.dispose() }
        }
    }

}
