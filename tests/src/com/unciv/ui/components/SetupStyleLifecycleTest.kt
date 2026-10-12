package com.unciv.ui.components

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.ui.TextField
import com.unciv.UncivGame
import com.unciv.logic.multiplayer.FriendList
import com.unciv.testing.GdxTestRunner
import com.unciv.ui.components.extensions.toCheckBox
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.ActivationListener
import com.unciv.ui.components.input.onChange
import com.unciv.ui.components.widgets.ExpanderTab
import com.unciv.ui.components.widgets.TranslatedSelectBox
import com.unciv.ui.components.widgets.UncivTextField
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.newgamescreen.GameOptionsTable
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito

/** Real option rebuilds and detached content, without opening maps or provider accounts. */
@RunWith(GdxTestRunner::class)
class SetupStyleLifecycleTest {
    private lateinit var priorUiState: SetupUiStateSnapshot
    private lateinit var priorExpandedStates: Map<String, Boolean>

    @Suppress("UNCHECKED_CAST")
    private fun expandedStates() = ExpanderTab::class.java.getDeclaredField("persistedStates")
        .apply { isAccessible = true }.get(null) as MutableMap<String, Boolean>

    @Before fun rememberUiState() {
        priorUiState = SetupUiStateSnapshot()
        priorExpandedStates = expandedStates().toMap()
    }

    @After fun restoreUiState() {
        expandedStates().apply { clear(); putAll(priorExpandedStates) }
        priorUiState.restore()
    }

    private fun polish(actor: Actor) {
        // The tests module is not a Kotlin friend of core.
        Class.forName("com.unciv.ui.screens.newgamescreen.NewGameScreenKt").declaredMethods
            .single { it.name.startsWith("polishSetupWidgets") && it.parameterCount == 1 }
            .apply { isAccessible = true }.invoke(null, actor)
    }

    @Test fun `closed expander content is styled before opening and attached content is visited once`() {
        SetupLayoutFixture(800f, 500f)
        UncivGame.Current.settings.continuousRendering = false
        var checkboxChanges = 0
        var fieldChanges = 0
        val checkbox = "Keep option".toCheckBox(true) { checkboxChanges++ }.apply { isDisabled = true }
        val field = UncivTextField("Seed", "1234").apply {
            setProgrammaticChangeEvents(true)
            onChange { fieldChanges++ }
        }
        val semantic = "Extra gold".toLabel(Color.GOLD)
        val button = Mockito.spy(TextButton("Action", BaseScreen.skin)).apply { isDisabled = true }
        val expander = ExpanderTab("Detached options", startsOutOpened = false) {
            it.add(checkbox).row()
            it.add(field).row()
            it.add(semantic).row()
            it.add(button).row()
        }
        val checkboxListeners = checkbox.listeners.toList()
        val fieldListeners = field.listeners.toList()
        assertNull(expander.innerTable.parent)

        Mockito.clearInvocations(button)
        polish(expander)
        Mockito.verify(button, Mockito.times(1)).setStyle(Mockito.any(Button.ButtonStyle::class.java))
        assertEquals(GameUiStyle.ink, checkbox.label.style.fontColor)
        assertEquals(GameUiStyle.ink, field.style.fontColor)
        assertEquals(Color.GOLD, semantic.style.fontColor)
        assertTrue(checkbox.isChecked)
        assertTrue(checkbox.isDisabled)
        assertTrue(button.isDisabled)
        assertEquals(Touchable.enabled, button.touchable)
        assertEquals("1234", field.text)
        assertEquals(checkboxListeners, checkbox.listeners.toList())
        assertEquals(fieldListeners, field.listeners.toList())

        val styledButton = button.style
        expander.isOpen = true
        assertTrue(button.isDescendantOf(expander))
        assertSame(styledButton, button.style)
        Mockito.clearInvocations(button)
        polish(expander)
        Mockito.verify(button, Mockito.times(1)).setStyle(Mockito.any(Button.ButtonStyle::class.java))

        checkbox.isDisabled = false
        checkbox.isChecked = false
        field.text = "5678"
        assertEquals(1, checkboxChanges)
        assertEquals(1, fieldChanges)
    }

    @Test fun `an internal game option callback keeps replacement controls styled and selected`() {
        val fixture = SetupLayoutFixture(800f, 500f)
        val parameters = fixture.setup.gameParameters
        val options = GameOptionsTable(fixture.screen, true,
            { fixture.picker.update(it) }, { fixture.picker.updateRandomNumberLabel() })
        fun difficulty() = options.setupDescendants().filterIsInstance<TranslatedSelectBox>()
            .single { it.selected.value == parameters.difficulty }
        fun statsCheckbox(): CheckBox {
            val advanced = options.setupDescendants().filterIsInstance<ExpanderTab>()
                .single { it.title == "Advanced Settings" }
            return advanced.innerTable.setupDescendants().filterIsInstance<CheckBox>()
                .single { it.text.toString() == "Show Civilization Stats" }
        }
        polish(options)
        val previousDifficulty = difficulty()
        val selectedDifficulty = previousDifficulty.selected.value
        val previousStats = statsCheckbox()
        val showStats = !previousStats.isChecked

        // This production listener calls GameOptionsTable.update directly.
        previousStats.isChecked = showStats
        val replacementDifficulty = difficulty()
        val replacementStats = statsCheckbox()
        assertNotSame(previousDifficulty, replacementDifficulty)
        assertNotSame(previousStats, replacementStats)
        assertEquals(selectedDifficulty, replacementDifficulty.selected.value)
        assertEquals(GameUiStyle.ink, replacementDifficulty.style.fontColor)
        assertEquals(GameUiStyle.ink, replacementStats.label.style.fontColor)
        assertEquals(showStats, parameters.showCivilizationStats)
        assertEquals(showStats, replacementStats.isChecked)

        replacementStats.isChecked = !showStats
        assertEquals(!showStats, parameters.showCivilizationStats)
        assertEquals(GameUiStyle.ink, difficulty().style.fontColor)
    }

    @Test fun `multiplayer cards style player ID controls while retaining validation and button callbacks`() {
        val fixture = SetupLayoutFixture(800f, 500f)
        val userId = "2ddb3a34-0699-4126-b7a5-38603e665928"
        val multiplayer = UncivGame.Current.settings.multiplayer
        multiplayer.setUserId(userId)
        multiplayer.friendList.add(FriendList.Friend("Test friend", "9e37e983-a676-4ecc-800e-ef8ec721a9b9"))
        fixture.setup.gameParameters.isOnlineMultiplayer = true
        fixture.player.playerId = "invalid-id"
        fixture.picker.update()

        val card = fixture.picker.setupDescendants().filterIsInstance<Table>()
            .single { it.name == "SetupNation-${fixture.player.chosenCiv}" }
        val field = card.setupDescendants().filterIsInstance<TextField>().single()
        val validation = card.setupDescendants().filterIsInstance<Label>().single { it.text.toString() == "✘" }
        val actions = card.setupDescendants().filterIsInstance<TextButton>().filter {
            it.text.toString() in listOf("Set current user", "Player ID from clipboard", "Player ID from friends list")
        }.toList()
        assertEquals(3, actions.size)
        assertEquals(GameUiStyle.ink, field.style.fontColor)
        actions.forEach { assertEquals(GameUiStyle.ink, it.label.style.fontColor) }
        assertEquals(Color.RED, validation.style.fontColor)

        val currentUser = actions.single { it.text.toString() == "Set current user" }
        currentUser.silenceSetupActivationSounds()
        currentUser.listeners.filterIsInstance<ActivationListener>().single()
            .tap(InputEvent().apply { listenerActor = currentUser }, 1f, 1f, 1, 0)
        assertEquals(userId, field.text)
        assertEquals(userId, fixture.player.playerId)
        assertEquals("✔", validation.text.toString())
        assertEquals(Color.GREEN, validation.style.fontColor)

        field.setProgrammaticChangeEvents(true)
        field.text = "invalid-id"
        assertEquals("✘", validation.text.toString())
        assertEquals(Color.RED, validation.style.fontColor)
    }
}
