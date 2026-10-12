package com.unciv.ui.components

import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.ui.TextField
import com.unciv.dev.FontDesktop
import com.unciv.UncivGame
import com.badlogic.gdx.Gdx
import com.unciv.logic.files.SetupPreset
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.agents.AgentProfile
import com.unciv.logic.civilization.PlayerType
import com.unciv.models.metadata.GameSetupInfo
import com.unciv.models.metadata.Player
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.components.input.ActivationListener
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.UncivStage
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.newgamescreen.AgentProfilePopup
import com.unciv.ui.screens.newgamescreen.IPreviousScreen
import com.unciv.ui.screens.newgamescreen.NationSettingsPopup
import com.unciv.ui.screens.newgamescreen.NewGameScreen
import com.unciv.ui.screens.newgamescreen.PlayerPickerTable
import com.unciv.ui.screens.newgamescreen.SetupPresetsPopup
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.Mockito

/** Real editor widgets and save callbacks; only the native stage boundary is mocked. */
@RunWith(GdxTestRunner::class)
class AgentProfilePopupTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var priorUiState: SetupUiStateSnapshot

    @Before
    fun rememberFontIcons() {
        priorUiState = SetupUiStateSnapshot()
    }

    @After
    fun restoreFontIcons() {
        priorUiState.restore()
    }

    private class Fixture(width: Float, height: Float) {
        val game = TestGame()
        val profile = AgentProfile.defaultFor("Rome")
        val root = Group().apply { setSize(width, height) }
        val stage = Mockito.mock(UncivStage::class.java)
        val picker: PlayerPickerTable
        var saves = 0
        val popup: AgentProfilePopup

        init {
            Fonts.fontImplementation = FontDesktop()
            ImageGetter.setNewRuleset(game.ruleset)
            Mockito.`when`(stage.width).thenReturn(width)
            Mockito.`when`(stage.height).thenReturn(height)
            Mockito.`when`(stage.root).thenReturn(root)
            Mockito.`when`(stage.actors).thenReturn(root.children)
            Mockito.`when`(stage.lastKnownVisibleArea).thenReturn(Rectangle(0f, 0f, width, height))
            Group::class.java.getDeclaredMethod("setStage", com.badlogic.gdx.scenes.scene2d.Stage::class.java)
                .apply { isAccessible = true }.invoke(root, stage)
            Mockito.doAnswer { root.addActor(it.getArgument(0)); null }
                .`when`(stage).addActor(Mockito.any(Actor::class.java))
            val setup = GameSetupInfo()
            val player = Player("Rome").apply { setNationTransient(game.ruleset) }
            setup.gameParameters.players.clear()
            setup.gameParameters.players.add(player)
            val previous = object : IPreviousScreen {
                override val gameSetupInfo = setup
                override val stage = this@Fixture.stage
                override val ruleset = game.ruleset
            }
            picker = PlayerPickerTable(previous, setup.gameParameters)
            picker.agentProfiles[player] = profile
            picker.profileCache[player] = profile
            popup = AgentProfilePopup(picker, player, profile) { saves++ }
            popup.validate()
        }

        fun reopen() = AgentProfilePopup(picker, picker.gameParameters.players.first(), profile)
    }

    private fun Actor.descendants(): Sequence<Actor> = sequence {
        yield(this@descendants)
        if (this@descendants is Group)
            for (child in children) yieldAll(child.descendants())
    }

    private fun AgentProfilePopup.boxes() = descendants().filterIsInstance<SelectBox<*>>().toList()

    @Suppress("UNCHECKED_CAST")
    private fun SelectBox<*>.choose(label: String) {
        val box = this as SelectBox<Any>
        box.selection.choose(box.items.first { it.toString() == label })
    }

    private fun Actor.press(text: String) {
        val button = descendants().filterIsInstance<TextButton>().single { it.text.toString() == text }
        button.silenceSetupActivationSounds()
        val event = InputEvent().apply { listenerActor = button }
        button.listeners.filterIsInstance<ActivationListener>().single().tap(event, 1f, 1f, 1, 0)
    }

    private fun Actor.field(name: String) = descendants().filterIsInstance<TextField>().single { it.name == name }

    @Test
    fun `opening and hovering over the model preserves saved XHigh reasoning`() {
        val fixture = Fixture(800f, 600f)
        val model = fixture.popup.boxes()[1]
        val thinking = fixture.popup.boxes()[2]
        assertEquals("XHigh", thinking.selected.toString())
        thinking.choose("Max")
        model.fire(InputEvent().apply { type = InputEvent.Type.enter })
        assertEquals("Max", thinking.selected.toString())
        assertEquals("xhigh", fixture.profile.reasoning)
    }

    @Test
    fun `saving a changed model and reasoning survives reopening`() {
        val fixture = Fixture(800f, 600f)
        fixture.popup.boxes()[1].choose("GPT-6.1 Sol (Catalog)")
        fixture.popup.boxes()[2].choose("Max")
        fixture.popup.press("Save")
        assertEquals("gpt-6.1-sol", fixture.profile.modelId)
        assertEquals("max", fixture.profile.reasoning)
        assertEquals(1, fixture.saves)
        assertNull(fixture.popup.parent)
        val reopened = fixture.reopen()
        assertEquals("Max", reopened.boxes()[2].selected.toString())
        reopened.close()
    }

    @Test
    fun `Save and Cancel remain below scrollable fields in a small window`() {
        val fixture = Fixture(520f, 360f)
        val pane = fixture.popup.getScrollPane()!!
        assertTrue("All fields must be inside the popup scroll area", pane.actor.descendants().filterIsInstance<TextField>().count() >= 3)
        val buttons = fixture.popup.descendants().filterIsInstance<TextButton>()
            .filter { it.text.toString() in listOf("Save", "Cancel") }.toList()
        assertEquals(2, buttons.size)
        val paneOrigin = pane.localToStageCoordinates(Vector2())
        for (button in buttons) {
            val origin = button.localToStageCoordinates(Vector2())
            assertTrue("${button.text} must remain within the window", origin.x >= 0f && origin.y >= 0f && origin.x + button.width <= 520f && origin.y + button.height <= 360f)
            assertTrue("Buttons belong below the scrolling form", origin.y + button.height <= paneOrigin.y)
        }
        fixture.popup.press("Save")
        assertEquals(1, fixture.saves)
    }

    @Test
    fun `setup fields and model dropdowns use copied styles without changing the skin`() {
        val fixture = Fixture(800f, 500f)
        val sharedField = BaseScreen.skin.get(TextField.TextFieldStyle::class.java)
        val sharedSelect = BaseScreen.skin.get(SelectBox.SelectBoxStyle::class.java)
        val sharedFieldBackground = sharedField.background
        val sharedSelectBackground = sharedSelect.background
        val sharedListBackground = sharedSelect.listStyle.background
        for (field in fixture.popup.descendants().filterIsInstance<TextField>()) {
            assertNotSame(sharedField, field.style)
            assertEquals(GameUiStyle.ink, field.style.fontColor)
        }
        for (box in fixture.popup.boxes()) {
            assertNotSame(sharedSelect, box.style)
            assertNotSame(sharedSelect.listStyle, box.style.listStyle)
            assertNotSame(sharedSelect.scrollStyle, box.style.scrollStyle)
            assertEquals(GameUiStyle.ink, box.style.fontColor)
        }
        fixture.reopen().close()
        assertSame(sharedFieldBackground, sharedField.background)
        assertSame(sharedSelectBackground, sharedSelect.background)
        assertSame(sharedListBackground, sharedSelect.listStyle.background)
        fixture.popup.close()
    }

    @Test
    fun `opened model list fits a narrow viewport while preserving the full custom model`() {
        val fixture = Fixture(520f, 360f)
        fixture.popup.close()
        val modelId = "custom-model-" + "very-long-provider-release-name-".repeat(12)
        fixture.profile.modelId = modelId
        val popup = fixture.reopen()
        popup.validate()
        val model = popup.boxes()[1]
        val pane = popup.getScrollPane()!!
        val position = model.localToAscendantCoordinates(pane.actor, Vector2())
        pane.scrollY = (pane.actor.height - position.y - model.height).coerceIn(0f, pane.maxY)
        pane.updateVisualScroll()
        pane.invalidate()
        pane.validate()
        model.showList()
        val menu = model.scrollPane
        val origin = menu.localToStageCoordinates(Vector2())
        assertTrue("The opened list must be no wider than the viewport", menu.width <= 504f)
        assertTrue("The entire list must remain inside the viewport", origin.x >= 8f && origin.x + menu.width <= 512f)
        assertTrue("Ellipsis must only affect the displayed label", model.selected.toString().contains(modelId))
        assertEquals(modelId, fixture.profile.modelId)
        model.hideList()
        popup.close()
    }

    @Test
    fun `cancel discards model and reasoning edits`() {
        val fixture = Fixture(800f, 600f)
        fixture.popup.boxes()[1].choose("GPT-6.1 Sol (Catalog)")
        fixture.popup.boxes()[2].choose("Max")
        fixture.popup.press("Cancel")
        assertEquals("gpt-6-luna", fixture.profile.modelId)
        assertEquals("xhigh", fixture.profile.reasoning)
        assertEquals(0, fixture.saves)
    }

    @Test
    fun `agent Save commits bonuses and Cancel leaves the saved values alone`() {
        val fixture = Fixture(800f, 600f)
        fixture.popup.field("starting-gold").text = "500"
        fixture.popup.field("starting-culture").text = "25"
        fixture.popup.boxes()[3].choose("Scout")
        fixture.popup.press("Add unit")
        fixture.popup.field("unit-Scout").text = "2"
        fixture.popup.boxes()[4].choose("Iron")
        fixture.popup.press("Add deposit")
        fixture.popup.field("deposit-Iron").text = "1"
        fixture.popup.press("Save")
        val bonuses = fixture.picker.gameParameters.players.first().startingBonuses
        assertEquals(500, bonuses.gold)
        assertEquals(25, bonuses.culture)
        assertEquals(2, bonuses.units["Scout"])
        assertEquals(1, bonuses.deposits["Iron"])
        val reopened = fixture.reopen()
        assertEquals("2", reopened.field("unit-Scout").text)
        reopened.field("starting-gold").text = "1000"
        reopened.field("unit-Scout").text = "0"
        reopened.press("Cancel")
        assertEquals(500, bonuses.gold)
        assertEquals(2, bonuses.units["Scout"])
    }

    @Test
    fun `invalid bonuses keep the editor open without partially saving agent changes`() {
        val fixture = Fixture(800f, 600f)
        fixture.popup.boxes()[1].choose("GPT-6.1 Sol (Catalog)")
        fixture.popup.boxes()[3].choose("Scout")
        fixture.popup.press("Add unit")
        fixture.popup.field("unit-Scout").text = "51"
        fixture.popup.press("Save")
        assertNotNull(fixture.popup.parent)
        assertEquals("gpt-6-luna", fixture.profile.modelId)
        assertTrue(fixture.picker.gameParameters.players.first().startingBonuses.units.isEmpty())
        fixture.popup.close()
    }

    @Test
    fun `ordinary nations can save starting bonuses through their own editor`() {
        val fixture = Fixture(520f, 360f)
        fixture.popup.close()
        val player = fixture.picker.gameParameters.players.first()
        fixture.picker.agentProfiles.clear()
        player.playerType = PlayerType.AI
        val popup = NationSettingsPopup(fixture.picker, player)
        popup.validate()
        popup.field("starting-gold").text = "300"
        popup.press("Save")
        assertEquals(300, player.startingBonuses.gold)
        assertEquals(PlayerType.AI, player.playerType)
        assertNull(popup.parent)
    }

    @Test
    fun `restoring agent slots keeps names reasoning cached profiles and shuffle options`() {
        val fixture = Fixture(800f, 600f)
        fixture.popup.close()
        val picker = fixture.picker
        picker.gameParameters.shufflePlayerOrder = true
        fixture.profile.displayName = "Atlas"
        fixture.profile.reasoning = "max"
        val inactive = Player("Korea", PlayerType.AI).apply { setNationTransient(fixture.game.ruleset) }
        picker.gameParameters.players.add(inactive)
        picker.profileCache[inactive] = AgentProfile.defaultFor("Korea").apply { reasoning = "low" }
        val slots = picker.snapshotAgentSlots()
        val restored = PlayerPickerTable(picker.previousScreen, picker.gameParameters)
        restored.restoreAgentSlots(slots)
        assertTrue(restored.gameParameters.shufflePlayerOrder)
        val profile = restored.agentProfiles[picker.gameParameters.players.first()]!!
        assertEquals("Atlas", profile.displayName)
        assertEquals("max", profile.reasoning)
        assertEquals("low", restored.profileCache[inactive]!!.reasoning)
        assertFalse(inactive in restored.agentProfiles)
        profile.reasoning = "high"
        assertEquals("max", slots.first().profile.reasoning)
    }

    @Test
    fun `preset popup saves and loads the full setup with accessible footer controls`() {
        val fixture = Fixture(520f, 360f)
        fixture.popup.close()
        fixture.profile.displayName = "Atlas"
        fixture.profile.reasoning = "max"
        val setup = fixture.picker.previousScreen.gameSetupInfo
        setup.mapParameters.seed = 55L
        setup.gameParameters.players.first().startingBonuses.gold = 450
        var loaded: SetupPreset? = null
        val screen = Mockito.mock(NewGameScreen::class.java) { invocation ->
            when {
                invocation.method.name.startsWith("getPlayerPickerTable") -> fixture.picker
                invocation.method.name.startsWith("loadPreset") -> { loaded = invocation.getArgument(0); null }
                else -> org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation)
            }
        }
        val game = Mockito.mock(UncivGame::class.java)
        Mockito.`when`(game.files).thenReturn(UncivFiles(Gdx.files, temporary.newFolder().absolutePath))
        Mockito.`when`(screen.game).thenReturn(game)
        Mockito.`when`(screen.stage).thenReturn(fixture.stage)
        Mockito.`when`(screen.gameSetupInfo).thenReturn(setup)
        val popup = SetupPresetsPopup(screen)
        popup.validate()
        val loadButton = popup.descendants().filterIsInstance<TextButton>().single { it.text.toString() == "Load" }
        assertTrue("Empty presets must disable the visible Load action", loadButton.isDisabled)
        popup.press("Load")
        assertNull(loaded)
        assertNotNull(popup.parent)
        popup.descendants().filterIsInstance<TextField>().single().text = "Atlas test"
        popup.press("Save preset")
        setup.mapParameters.seed = 99L
        fixture.profile.reasoning = "low"
        popup.validate()
        val paneOrigin = popup.getScrollPane()!!.localToStageCoordinates(Vector2())
        for (button in popup.descendants().filterIsInstance<TextButton>()) {
            val position = button.localToStageCoordinates(Vector2())
            assertTrue("Preset footer controls must stay within the window", position.y >= 0f && position.y + button.height <= paneOrigin.y && position.x >= 0f && position.x + button.width <= 520f)
        }
        popup.press("Load")
        assertEquals(55L, loaded!!.setup.mapParameters.seed)
        assertEquals(450, loaded!!.setup.gameParameters.players.first().startingBonuses.gold)
        assertEquals("Atlas", loaded!!.agents.single().profile.displayName)
        assertEquals("max", loaded!!.agents.single().profile.reasoning)
        assertNull(popup.parent)
    }
}
