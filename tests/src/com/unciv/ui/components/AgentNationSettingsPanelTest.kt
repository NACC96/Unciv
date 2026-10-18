package com.unciv.ui.components

import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.ui.TextField
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.utils.viewport.ScreenViewport
import com.unciv.Constants
import com.unciv.dev.FontDesktop
import com.unciv.logic.agents.*
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.components.fonts.FontRulesetIcons
import com.unciv.ui.components.input.ActivationListener
import com.unciv.ui.components.input.KeyShortcutDispatcher
import com.unciv.ui.components.input.installShortcutDispatcher
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.UncivStage
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.ui.screens.worldscreen.status.AgentMatchControlTable
import com.unciv.ui.screens.worldscreen.status.AgentNationSettingsPanel
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito

@RunWith(GdxTestRunner::class)
class AgentNationSettingsPanelTest {
    private lateinit var names: Map<String, Char>
    private lateinit var icons: Map<Char, Actor>
    @Before fun preserveIcons() {
        names = FontRulesetIcons.rulesetObjectNameToChar.toMap()
        icons = FontRulesetIcons.charToRulesetImageActor.toMap()
    }
    @After fun restoreIcons() {
        FontRulesetIcons.rulesetObjectNameToChar.clear(); FontRulesetIcons.rulesetObjectNameToChar.putAll(names)
        FontRulesetIcons.charToRulesetImageActor.clear(); FontRulesetIcons.charToRulesetImageActor.putAll(icons)
    }
    private class Fixture(val width: Float = 960f, val height: Float = 720f) {
        val game = TestGame()
        val spectator = game.addCiv(game.ruleset.nations[Constants.spectator]!!)
        val profile = AgentProfile("saved", "Patient ruler", "codex", "saved-custom-id", "high", "Protect the river", 4)
        var settings = listOf(NationSetting("Rome", "Rome", ControllerKind.HUMAN, profile, true),
            NationSetting("Greece", "Greece", ControllerKind.EXTERNAL_AGENT, AgentProfile.defaultFor("Greece"), false))
        var pending: String? = null
        var callback: ((String?) -> Unit)? = null
        data class Request(val id: String?, val change: NationControlChange, val profile: AgentProfile?)
        val requests = ArrayList<Request>()
        val root = Group()
        val stage = Mockito.mock(UncivStage::class.java)
        val screen = Mockito.mock(WorldScreen::class.java)
        val session = Mockito.mock(AgentMatchSession::class.java)
        init {
            Fonts.fontImplementation = FontDesktop(); ImageGetter.setNewRuleset(game.ruleset)
            Mockito.`when`(stage.width).thenReturn(width); Mockito.`when`(stage.height).thenReturn(height)
            Mockito.`when`(stage.root).thenReturn(root)
            Group::class.java.getDeclaredMethod("setStage", com.badlogic.gdx.scenes.scene2d.Stage::class.java)
                .apply { isAccessible = true }.invoke(root, stage)
            Mockito.doAnswer { root.addActor(it.getArgument(0)); null }.`when`(stage).addActor(Mockito.any(Actor::class.java))
            Mockito.`when`(screen.stage).thenReturn(stage); Mockito.`when`(screen.gameInfo).thenReturn(game.gameInfo)
            Mockito.`when`(screen.viewingCiv).thenReturn(spectator)
            Mockito.`when`(screen.currentViewerCivId()).thenReturn(spectator.civID)
            Mockito.`when`(screen.agentSession).thenReturn(session)
            val tech = Mockito.mock(com.unciv.ui.screens.worldscreen.TechPolicyDiplomacyButtons::class.java)
            Mockito.`when`(tech.y).thenReturn(height - 100f)
            val getter = screen.javaClass.methods.single { it.name.substringBefore('$') == "getTechPolicyAndDiplomacy" }
            Mockito.`when`(getter.invoke(screen) as com.unciv.ui.screens.worldscreen.TechPolicyDiplomacyButtons?).thenReturn(tech)
            Mockito.`when`(session.getNationSettings()).thenAnswer { settings }
            Mockito.`when`(session.getPendingNationChange()).thenAnswer { pending }
            Mockito.`when`(session.canSpectate()).thenReturn(true)
            Mockito.`when`(session.getVision()).thenReturn(MatchVision.FOG)
            Mockito.`when`(session.getRunState()).thenReturn(AgentRunState.PAUSED)
            Mockito.doAnswer {
                requests.add(Request(it.getArgument(0), it.getArgument(1), it.getArgument(2)))
                callback = it.getArgument(3); null
            }.`when`(session).requestNationChange(Mockito.nullable(String::class.java),
                Mockito.any(NationControlChange::class.java) ?: NationControlChange.SAVE_AGENT_SETTINGS, Mockito.nullable(AgentProfile::class.java),
                Mockito.any<(String?) -> Unit>() ?: {})
        }
    }
    private fun Actor.descendants(): Sequence<Actor> = sequence {
        yield(this@descendants)
        if (this@descendants is Group) children.forEach { yieldAll(it.descendants()) }
    }
    private fun Actor.named(name: String) = descendants().single { it.name == name }
    private fun Actor.press() = listeners.filterIsInstance<ActivationListener>().single()
        .tap(InputEvent().apply { listenerActor = this@press }, 1f, 1f, 1, 0)
    private fun Actor.edit(name: String, value: String) {
        (named(name) as TextField).apply {
            text = value
            fire(com.badlogic.gdx.scenes.scene2d.utils.ChangeListener.ChangeEvent())
        }
    }
    private fun Actor.select(name: String, prefix: String) {
        @Suppress("UNCHECKED_CAST") val box = named(name) as SelectBox<Any>
        box.selected = box.items.first { it.toString().startsWith(prefix) }
    }

    @Test fun `a default display name follows the model while a custom name is kept`() {
        val f = Fixture(); val panel = AgentNationSettingsPanel(f.screen, f.session)
        try {
            panel.toggle(); panel.select("NationPicker", "Greece")
            val name = panel.named("NationDisplayName") as TextField
            @Suppress("UNCHECKED_CAST") val model = panel.named("NationModel") as SelectBox<Any>
            fun otherModel() = model.items.first { it.toString() != "Custom model…" && it != model.selected }
            val other = otherModel()
            model.selected = other
            assertEquals("$other Greece", name.text)
            panel.named("NationSave").press()
            assertEquals("$other Greece", f.requests.single().profile!!.displayName)
            f.callback!!(null)
            panel.select("NationPicker", "Rome")
            model.selected = otherModel()
            assertEquals("Patient ruler", name.text)
        } finally { panel.dispose() }
    }

    @Test fun selectedNationImmediatelyReportsItsController() {
        val f = Fixture(); val panel = AgentNationSettingsPanel(f.screen, f.session)
        try {
            panel.toggle(); panel.select("NationPicker", "Greece")
            assertTrue((panel.named("NationController") as Label).text.toString().contains("Controller: Agent · Greece"))
        } finally { panel.dispose() }
    }

    @Test fun pendingControlClearsWhenAuthoritySettles() {
        val f = Fixture(); val panel = AgentNationSettingsPanel(f.screen, f.session)
        try {
            panel.toggle(); f.pending = "Pending takeover of Rome"; panel.refresh()
            assertTrue((panel.named("NationStatus") as Label).text.toString().contains("Pending takeover"))
            f.pending = null; panel.refresh()
            assertFalse("An old pending change must not remain after settlement", (panel.named("NationStatus") as Label).text.toString().contains("Pending takeover"))
            assertTrue(panel.descendants().filterIsInstance<Label>().any { it.text.toString().contains("Controller: Human") })
        } finally { panel.dispose() }
    }

    @Test fun `saved custom model row is visible and directly editable on opening`() {
        val f = Fixture(); val panel = AgentNationSettingsPanel(f.screen, f.session)
        try {
            panel.toggle()
            val input = panel.named("NationCustomModel") as TextField
            assertTrue(input.isVisible)
            assertEquals("saved-custom-id", input.text)
        } finally { panel.dispose() }
    }

    @Test fun `custom model and reasoning survive refresh while drafts remain isolated`() {
        val f = Fixture(); val panel = AgentNationSettingsPanel(f.screen, f.session)
        try {
            panel.toggle()
            assertTrue((panel.named("NationModel") as SelectBox<*>).selected.toString().contains("saved-custom-id"))
            assertEquals("High", (panel.named("NationReasoning") as SelectBox<*>).selected.toString())
            panel.edit("NationPersonality", "Draft strategy")
            panel.refresh(); panel.hide(); panel.toggle()
            assertEquals("Draft strategy", (panel.named("NationPersonality") as TextField).text)
            assertEquals("Protect the river", f.profile.personality)
            panel.select("NationPicker", "Greece"); panel.select("NationPicker", "Rome")
            assertEquals("Draft strategy", (panel.named("NationPersonality") as TextField).text)
            panel.named("NationSave").press()
            assertEquals("saved-custom-id", f.requests.single().profile!!.modelId)
            assertEquals("high", f.requests.single().profile!!.reasoning)
            assertEquals("Draft strategy", f.requests.single().profile!!.personality)
            assertEquals(NationControlChange.SAVE_AGENT_SETTINGS, f.requests.single().change)
        } finally { panel.dispose() }
    }

    @Test fun `historical saved provider model and reasoning remain visible without silent substitution`() {
        val f = Fixture()
        f.profile.provider = "historical-provider"; f.profile.modelId = "retired-model"; f.profile.reasoning = "historical-effort"
        val panel = AgentNationSettingsPanel(f.screen, f.session)
        try {
            panel.toggle(); panel.refresh()
            assertEquals("historical-provider", (panel.named("NationProvider") as SelectBox<*>).selected.toString())
            assertTrue((panel.named("NationModel") as SelectBox<*>).selected.toString().contains("retired-model"))
            assertTrue((panel.named("NationReasoning") as SelectBox<*>).selected.toString().contains("historical-effort"))
            panel.named("NationPlay").press()
            assertNull(f.requests.single().profile)
            assertEquals("historical-effort", f.profile.reasoning)
        } finally { panel.dispose() }
    }

    @Test fun `pending disables duplicate actions and failures retain the editable draft`() {
        val f = Fixture(); val panel = AgentNationSettingsPanel(f.screen, f.session)
        try {
            panel.toggle(); panel.edit("NationDisplayName", "Unsaved name")
            panel.named("NationHandback").press()
            f.pending = "Waiting for admitted agent turn"; panel.refresh()
            panel.named("NationHandback").press(); panel.named("NationPlay").press()
            assertEquals(1, f.requests.size)
            assertTrue((panel.named("NationStatus") as Label).text.toString().contains("Waiting"))
            f.pending = null; f.callback!!("Disk full")
            assertEquals("Unsaved name", (panel.named("NationDisplayName") as TextField).text)
            assertEquals("Disk full", (panel.named("NationStatus") as Label).text.toString())
            assertFalse((panel.named("NationHandback") as TextButton).isDisabled)
            assertEquals(NationControlChange.HAND_TO_AGENT, f.requests.single().change)
        } finally { panel.dispose() }
    }

    @Test fun `play and spectate are separate explicit actions`() {
        val f = Fixture(); val panel = AgentNationSettingsPanel(f.screen, f.session)
        try {
            panel.toggle(); panel.named("NationPlay").press(); f.callback!!(null)
            panel.named("NationSpectate").press()
            assertEquals(listOf(NationControlChange.PLAY_AS, NationControlChange.SPECTATE), f.requests.map { it.change })
            assertNull(f.requests.last().id)
            assertNull(f.requests.last().profile)
        } finally { panel.dispose() }
    }

    @Test fun `active agent settings retain next turn copy but takeover applies immediately`() {
        val f = Fixture()
        f.settings = f.settings.map { it.copy(isActive = it.civId == "Greece") }
        val panel = AgentNationSettingsPanel(f.screen, f.session)
        try {
            panel.toggle(); panel.select("NationPicker", "Greece")
            panel.named("NationSave").press(); f.callback!!(null)
            assertEquals("Saved. The agent finishes this turn with its previous settings.",
                (panel.named("NationStatus") as Label).text.toString())
            panel.named("NationPlay").press(); f.callback!!(null)
            assertEquals("Applied.", (panel.named("NationStatus") as Label).text.toString())
            assertFalse("Immediate takeover must not claim to pause the match",
                panel.descendants().filterIsInstance<Label>().any { it.text.toString().contains("match is paused") })
            assertEquals(listOf(NationControlChange.SAVE_AGENT_SETTINGS, NationControlChange.PLAY_AS), f.requests.map { it.change })
        } finally { panel.dispose() }
    }

    @Test fun `custom entry and provider catalog maintain supported effort choices`() {
        val f = Fixture(); val panel = AgentNationSettingsPanel(f.screen, f.session)
        try {
            panel.toggle(); panel.select("NationProvider", "opencode")
            panel.select("NationModel", "OpenCode Go: DeepSeek")
            panel.select("NationReasoning", "Max")
            panel.named("NationSave").press()
            assertEquals("opencode-go/deepseek-v4.1-flash", f.requests.single().profile!!.modelId)
            assertEquals("max", f.requests.single().profile!!.reasoning)
            f.callback!!(null); panel.select("NationModel", "Custom model")
            panel.edit("NationCustomModel", "local/my-model"); panel.select("NationReasoning", "High")
            panel.named("NationSave").press()
            assertEquals("local/my-model", f.requests.last().profile!!.modelId)
            assertEquals("high", f.requests.last().profile!!.reasoning)
        } finally { panel.dispose() }
    }

    @Test fun `editing custom ID from nonthinking model restores custom reasoning choices`() {
        val f = Fixture(); f.profile.modelId = "gpt-4o"; f.profile.reasoning = null
        val panel = AgentNationSettingsPanel(f.screen, f.session)
        try {
            panel.toggle(); panel.select("NationModel", "Custom model")
            panel.edit("NationCustomModel", "local/my-model")
            val choices = (panel.named("NationReasoning") as SelectBox<*>).items.map { it.toString() }
            assertEquals(listOf("Provider default", "Low", "Medium", "High"), choices)
            panel.select("NationReasoning", "High"); panel.named("NationSave").press()
            assertEquals("high", f.requests.single().profile!!.reasoning)
            assertTrue(AgentModelCatalog.validateProfile(f.requests.single().profile!!).isEmpty())
        } finally { panel.dispose() }
    }

    @Test fun `editing custom ID revalidates Luna XHigh so save uses actual capabilities`() {
        val f = Fixture(); f.profile.modelId = "gpt-6-luna"; f.profile.reasoning = "xhigh"
        val panel = AgentNationSettingsPanel(f.screen, f.session)
        try {
            panel.toggle(); panel.select("NationModel", "Custom model")
            panel.edit("NationCustomModel", "local/my-model")
            assertFalse((panel.named("NationReasoning") as SelectBox<*>).items.any { it.toString() == "XHigh" })
            panel.named("NationSave").press()
            assertEquals(1, f.requests.size)
            assertTrue(AgentModelCatalog.validateProfile(f.requests.single().profile!!).isEmpty())
            f.callback!!(null)
            panel.edit("NationCustomModel", "gpt-4o")
            assertEquals(listOf("Provider default"), (panel.named("NationReasoning") as SelectBox<*>).items.map { it.toString() })
        } finally { panel.dispose() }
    }

    @Test fun `settings first shown and refreshed fit below laid out match controls`() {
        val f = Fixture(420f, 600f)
        val anchor = Actor().apply { setBounds(20f, 420f, 380f, 100f) }
        f.root.addActor(anchor)
        val panel = AgentNationSettingsPanel(f.screen, f.session, anchor)
        try {
            // Hidden construction must not freeze its position before the controls are laid out.
            anchor.y = 390f; panel.toggle(); panel.validate()
            assertTrue(panel.y + panel.height <= anchor.y - 8f)
            assertTrue(panel.y >= 0f)
            anchor.y = 370f; panel.refresh()
            assertTrue(panel.y + panel.height <= anchor.y - 8f)
            val header = panel.children.first()
            val drag = header.listeners.filterIsInstance<com.badlogic.gdx.scenes.scene2d.InputListener>().single()
            drag.touchDown(InputEvent().apply { target = header; stageX = 100f; stageY = 100f }, 0f, 0f, 0, 0)
            drag.touchDragged(InputEvent().apply { stageX = 100f; stageY = 120f }, 0f, 0f, 0)
            val draggedY = panel.y
            anchor.y = 340f; panel.refresh()
            assertEquals("Explicit dragging retains panel position", draggedY, panel.y)
        } finally { panel.dispose() }
    }

    @Test fun `compact panel and wrapped match controls stay in the viewport`() {
        val f = Fixture(420f, 600f)
        f.profile.displayName = "A very long imperial identity that must fit within the available glass"
        val panel = AgentNationSettingsPanel(f.screen, f.session)
        val controls = AgentMatchControlTable(f.session, f.screen)
        try {
            panel.toggle(); panel.validate(); controls.validate()
            assertTrue(panel.x >= 0 && panel.y >= 0 && panel.x + panel.width <= f.width && panel.y + panel.height <= f.height)
            assertTrue(controls.width <= f.width - 16f)
            for (name in listOf("MatchVision", "NationPicker", "NationProvider", "NationModel", "NationDisplayName", "NationPersonality")) {
                val actor = panel.named(name); val pos = actor.localToAscendantCoordinates(panel, Vector2())
                assertTrue("$name horizontal bounds", pos.x >= 0 && pos.x + actor.width <= panel.width)
            }
        } finally { controls.dispose(); panel.dispose() }
    }

    @Test fun `profile typing stays in text editor and does not invoke world shortcuts`() {
        val f = Fixture()
        Mockito.mockConstruction(SpriteBatch::class.java).use {
            val stage = UncivStage(ScreenViewport()); stage.viewport.update(960, 720, true)
            Mockito.`when`(f.screen.stage).thenReturn(stage)
            val panel = AgentNationSettingsPanel(f.screen, f.session)
            try {
                panel.toggle(); panel.validate()
                var opens = 0
                stage.installShortcutDispatcher(KeyShortcutDispatcher().apply { add('t') { opens++ } }) { null }
                val editor = panel.named("NationDisplayName") as TextField
                editor.text = ""; stage.keyboardFocus = editor
                stage.keyDown(Input.Keys.T); stage.keyTyped('t'); stage.keyUp(Input.Keys.T)
                assertEquals("t", editor.text); assertEquals(0, opens)
                panel.hide(); assertNull(stage.keyboardFocus)
            } finally { panel.dispose(); stage.dispose(); stage.batch.dispose() }
        }
    }

    @Test fun `map vision picker shows the match setting and commits only the user's pick`() {
        val f = Fixture()
        var vision = MatchVision.FULL
        val changes = ArrayList<MatchVision>()
        var done: ((String?) -> Unit)? = null
        Mockito.`when`(f.session.getVision()).thenAnswer { vision }
        Mockito.doAnswer { changes.add(it.getArgument(0)); done = it.getArgument(1); null }
            .`when`(f.session).setVision(Mockito.any(MatchVision::class.java) ?: MatchVision.FOG, Mockito.any<(String?) -> Unit>() ?: {})
        val panel = AgentNationSettingsPanel(f.screen, f.session)
        try {
            panel.toggle()
            val picker = panel.named("MatchVision") as SelectBox<*>
            assertEquals("Everything", picker.selected.toString())
            assertTrue("Opening and refreshing never commits", changes.isEmpty())
            panel.select("MatchVision", "Map revealed")
            assertEquals(listOf(MatchVision.MAP), changes)
            assertTrue("One change at a time", picker.isDisabled)
            vision = MatchVision.MAP
            done!!(null)
            assertFalse(picker.isDisabled)
            assertTrue((panel.named("NationStatus") as Label).text.toString().startsWith("Map vision: Map revealed"))
            vision = MatchVision.FOG
            panel.refresh()
            assertEquals("Another change to the match is followed", "Fog of war", picker.selected.toString())
            assertEquals(1, changes.size)
        } finally { panel.dispose() }
    }
}
