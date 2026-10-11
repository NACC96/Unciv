package com.unciv.ui.components

import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.headless.HeadlessApplication
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.dev.FontDesktop
import com.unciv.logic.agents.AgentProfile
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.files.UncivFiles
import com.unciv.models.metadata.GameSetupInfo
import com.unciv.models.metadata.Player
import com.unciv.models.UncivSound
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.ruleset.nation.Nation
import com.unciv.models.skins.SkinCache
import com.unciv.models.tilesets.TileSetCache
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import com.unciv.ui.components.fonts.FontRulesetIcons
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.components.input.ActivationListener
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.UncivStage
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.newgamescreen.NewGameScreen
import com.unciv.ui.screens.newgamescreen.GameOptionsTable
import com.unciv.ui.screens.newgamescreen.MapOptionsTable
import com.unciv.ui.screens.newgamescreen.PlayerPickerTable
import com.unciv.ui.screens.newgamescreen.StartingBonusForm
import com.unciv.ui.screens.pickerscreens.PickerPane
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito

/** Synthetic data and production widgets; no game settings, save directory or provider is opened. */
internal class SetupLayoutFixture(val width: Float, val height: Float, realStage: UncivStage? = null) {
    val game = TestGame()
    val root = realStage?.root ?: Group().apply { setSize(width, height) }
    val stage = realStage ?: Mockito.mock(UncivStage::class.java)
    val setup = GameSetupInfo()
    val player = Player("Rome", PlayerType.Human).apply { setNationTransient(game.ruleset) }
    val profile = AgentProfile.defaultFor("Rome")
    val pane: PickerPane
    val screen = Mockito.mock(NewGameScreen::class.java) { invocation ->
        when {
            invocation.method.name.startsWith("getColumnWidth") -> width
            invocation.method.name.startsWith("getPlayerPickerTable") -> if (::picker.isInitialized) picker else null
            invocation.method.name.startsWith("setStartEnabled") -> invocation.callRealMethod()
            else -> org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation)
        }
    }
    lateinit var picker: PlayerPickerTable
        private set
    var mapOptions: MapOptionsTable? = null

    init {
        Fonts.fontImplementation = FontDesktop()
        ImageGetter.setNewRuleset(game.ruleset)
        if (realStage == null) {
            Mockito.`when`(stage.width).thenReturn(width)
            Mockito.`when`(stage.height).thenReturn(height)
            Mockito.`when`(stage.root).thenReturn(root)
            Mockito.`when`(stage.actors).thenReturn(root.children)
            Mockito.`when`(stage.lastKnownVisibleArea).thenReturn(Rectangle(0f, 0f, width, height))
            Group::class.java.getDeclaredMethod("setStage", com.badlogic.gdx.scenes.scene2d.Stage::class.java)
                .apply { isAccessible = true }.invoke(root, stage)
            Mockito.doAnswer { root.addActor(it.getArgument(0)); null }
                .`when`(stage).addActor(Mockito.any(Actor::class.java))
        }
        setup.gameParameters.players.clear()
        setup.gameParameters.players.add(player)
        setup.gameParameters.players.add(Player("Korea", PlayerType.AI).apply { setNationTransient(game.ruleset) })
        pane = PickerPane()
        Mockito.`when`(screen.stage).thenReturn(stage)
        Mockito.`when`(screen.gameSetupInfo).thenReturn(setup)
        Mockito.`when`(screen.ruleset).thenReturn(game.ruleset)
        Mockito.`when`(screen.game).thenReturn(UncivGame.Current)
        Mockito.`when`(screen.pickerPane).thenReturn(pane)
        Mockito.`when`(screen.rightSideButton).thenReturn(pane.rightSideButton)
        picker = PlayerPickerTable(screen, setup.gameParameters, width - 40f)
        picker.agentProfiles[player] = profile
        picker.profileCache[player] = profile
        picker.update()
        pane.topTable.add(picker).width(width - 40f).top().row()
        pane.descriptionLabel.setText("Starting an agent game begins automated play immediately using the selected provider account.")
        setupFunction("configureSetupFooter", pane, width, height, {}, {})
        stage.addActor(pane)
        pane.setSize(width, height)
        pane.validate()
    }

    fun includeOptions(isolatedDirectory: String) {
        UncivGame.Current.files = UncivFiles(Gdx.files, isolatedDirectory)
        val options = GameOptionsTable(screen, true, { picker.update(it) }, { picker.updateRandomNumberLabel() })
        val map = MapOptionsTable(screen)
        mapOptions = map
        pane.topTable.clear()
        setupFunction("configureStackedSetup", pane.topTable, options, map, picker)
        setupFunction("polishSetupWidgets", pane.topTable)
        pane.scrollPane.setScrollingDisabled(true, false)
        pane.validate()
    }

    fun setStartEnabled(enabled: Boolean) = setupMember(screen, NewGameScreen::class.java, "setStartEnabled", enabled)

    fun cancelBackgroundJobs() {
        mapOptions?.let { setupMember(it, MapOptionsTable::class.java, "cancelBackgroundJobs") }
    }

    fun longContent() {
        val name = "The Republic of Rome and the Far Western Coastal Provinces"
        // Ruleset.clone copies its maps but shares their ruleset objects. Build a
        // fresh synthetic nation so the cached Rome object remains authoritative.
        val original = game.ruleset.nations["Rome"]!!
        game.ruleset.nations.remove("Rome")
        game.ruleset.nations[name] = Nation().apply {
            this.name = name
            leaderName = original.leaderName
            outerColor = original.outerColor.toList()
            innerColor = original.innerColor?.toList()
            cities = ArrayList(original.cities)
            uniques = ArrayList(original.uniques)
            uniqueName = original.uniqueName
            uniqueText = original.uniqueText
            startBias = ArrayList(original.startBias)
            originRuleset = original.originRuleset
            setTransients()
        }
        player.chosenCiv = name
        player.setNationTransient(game.ruleset)
        profile.displayName = "Atlas, a patient planner of the western coastline and every imperial trade route"
        profile.modelId = "a-very-long-custom-model-name-with-an-explicit-provider-and-release-version"
        player.startingBonuses.gold = 1000
        player.startingBonuses.culture = 250
        for (unit in listOf("Scout", "Warrior", "Settler", "Worker")) player.startingBonuses.units[unit] = 2
        for (resource in listOf("Iron", "Horses", "Coal", "Oil", "Aluminum", "Uranium")) player.startingBonuses.deposits[resource] = 1
        picker.update()
        pane.validate()
    }
}

/** Restore the UI singleton references and mappings that fixture bootstrap replaces. */
internal class SetupUiStateSnapshot {
    private fun field(owner: Class<*>, name: String) = owner.getDeclaredField(name).apply { isAccessible = true }
    private val fields = listOf(
        field(UncivGame::class.java, "Current"),
        field(ImageGetter::class.java, "ruleset"),
        field(Fonts::class.java, "fontImplementation"),
        field(Fonts::class.java, "font"),
        field(BaseScreen::class.java, "skin"),
        field(BaseScreen::class.java, "skinStrings"),
        field(BaseScreen::class.java, "clearColor"),
        field(FontRulesetIcons::class.java, "nextUnusedCharacterNumber")
    ).map { it to it.get(null) }

    @Suppress("UNCHECKED_CAST")
    private fun mapRestore(mapping: MutableMap<*, *>): () -> Unit {
        val target = mapping as MutableMap<Any?, Any?>
        val original = target.toMap()
        return { target.clear(); target.putAll(original) }
    }

    private val maps = listOf(
        mapRestore(FontRulesetIcons.rulesetObjectNameToChar),
        mapRestore(FontRulesetIcons.charToRulesetImageActor),
        mapRestore(field(ImageGetter::class.java, "textureRegionDrawables").get(null) as MutableMap<*, *>),
        mapRestore(field(ImageGetter::class.java, "ninePatchDrawables").get(null) as MutableMap<*, *>),
        mapRestore(SkinCache)
    )
    private val tileConfigs = TileSetCache.values.map { it to it.config }

    fun restore() {
        // Activation sounds are GL-thread coroutines queued before callbacks run.
        // Flush them while the fixture's zero-volume game is still authoritative,
        // before restoring a prior game whose optional services may be uninitialized.
        if (Gdx.app is HeadlessApplication) {
            val execute = HeadlessApplication::class.java.getDeclaredMethod("executeRunnables")
                .apply { isAccessible = true }
            var rounds = 0
            while (execute.invoke(Gdx.app) as Boolean) {
                check(++rounds < 100) { "Headless fixture cleanup could not drain GL tasks" }
            }
        }
        fields.forEach { (field, value) -> field.set(null, value) }
        maps.forEach { it() }
        tileConfigs.forEach { (tileset, config) -> tileset.config = config }
    }
}

/** Keep real activation/disabled callbacks while removing this fixture's asynchronous audio work. */
internal fun Actor.silenceSetupActivationSounds() {
    val attachments = userObject ?: return
    val actions = attachments.javaClass.getDeclaredField("activationActions")
        .apply { isAccessible = true }.get(attachments) as? Map<*, *> ?: return
    for (actionList in actions.values.filterNotNull()) {
        actionList.javaClass.getDeclaredField("sound").apply { isAccessible = true }
            .set(actionList, UncivSound.Silent)
    }
}

/** The tests module is not a Kotlin friend of core; retain production internal visibility. */
private fun setupFunction(name: String, vararg arguments: Any?) =
    Class.forName("com.unciv.ui.screens.newgamescreen.NewGameScreenKt").declaredMethods
        .single { it.name.startsWith(name) && it.parameterCount == arguments.size }
        .apply { isAccessible = true }.invoke(null, *arguments)

private fun setupMember(target: Any, owner: Class<*>, name: String, vararg arguments: Any?) =
    owner.declaredMethods.single { it.name.startsWith(name) && it.parameterCount == arguments.size }
        .apply { isAccessible = true }.invoke(target, *arguments)

private fun usesStackedLayout(width: Float, height: Float) =
    setupMember(NewGameScreen.Companion, NewGameScreen.Companion::class.java, "usesStackedLayout", width, height) as Boolean

internal fun Actor.setupDescendants(): Sequence<Actor> = sequence {
    yield(this@setupDescendants)
    if (this@setupDescendants is Group) children.forEach { yieldAll(it.setupDescendants()) }
}

@RunWith(GdxTestRunner::class)
class SetupLayoutTest {
    private lateinit var priorUiState: SetupUiStateSnapshot

    @Before fun rememberIcons() {
        priorUiState = SetupUiStateSnapshot()
    }
    @After fun restoreIcons() {
        priorUiState.restore()
    }

    @Test fun `short landscape windows use readable stacked options`() {
        assertTrue(usesStackedLayout(800f, 500f))
        assertTrue(usesStackedLayout(520f, 360f))
        assertTrue(usesStackedLayout(1200f, 1000f))
        assertFalse(usesStackedLayout(1200f, 720f))
    }

    @Test fun `nation identities actions and long summaries stay inside their cards`() {
        for ((width, height) in listOf(800f to 500f, 520f to 360f)) {
            val fixture = SetupLayoutFixture(width, height)
            fixture.longContent()
            val card = fixture.picker.setupDescendants().filterIsInstance<Table>().single { it.name == "SetupNation-${fixture.player.chosenCiv}" }
            card.pack()
            val identity = card.setupDescendants().filterIsInstance<Label>().single { it.name == "SetupNationName" }
            val actions = card.setupDescendants().filterIsInstance<Table>().single { it.name == "SetupNationActions" }
            assertTrue("Actions must follow the identity row", actions.y + actions.height <= identity.parent.y + 0.5f)
            for (actor in card.setupDescendants().filter { it is Label || it is TextButton }) {
                val position = actor.localToAscendantCoordinates(card, Vector2())
                assertTrue("${actor.name ?: actor.javaClass.simpleName} overflows its card", position.x >= 0f && position.x + actor.width <= card.width + 0.5f)
            }
            for (label in card.setupDescendants().filterIsInstance<Label>().filter { it.name?.endsWith("Summary") == true }) {
                assertTrue(label.wrap)
                assertTrue("Long metadata needs multiple readable lines", label.height > 15f)
            }
        }
    }

    @Test fun `long setup names never mutate the cached nation or a subsequent fixture`() {
        val fixture = SetupLayoutFixture(520f, 360f)
        val cached = RulesetCache[BaseRuleset.Civ_V_GnK.fullName]!!.nations["Rome"]!!
        fixture.longContent()
        assertEquals("Rome", cached.name)
        assertNotSame(cached, fixture.game.ruleset.nations[fixture.player.chosenCiv])
        val subsequent = SetupLayoutFixture(800f, 500f)
        assertEquals("Rome", subsequent.game.ruleset.nations["Rome"]!!.name)
        assertEquals("Rome", subsequent.player.chosenCiv)
    }

    @Test fun `footer keeps the validated Start widget visible and disabled input consumed`() {
        for ((width, height) in listOf(800f to 500f, 520f to 360f)) {
            val fixture = SetupLayoutFixture(width, height)
            val start = fixture.pane.rightSideButton
            assertSame(start, fixture.pane.setupDescendants().filterIsInstance<TextButton>().single { it.name == "SetupStart" })
            var starts = 0
            start.listeners.filterIsInstance<ActivationListener>().forEach { start.removeListener(it) }
            start.onActivation(sound = UncivSound.Silent) { starts++ }
            fixture.setup.gameParameters.players.removeAt(1)
            fixture.picker.update()
            assertTrue("One player must fail the original readiness check", start.isDisabled)
            assertEquals(Touchable.enabled, start.touchable)
            start.listeners.filterIsInstance<ActivationListener>().single().tap(InputEvent().apply { listenerActor = start }, 1f, 1f, 1, 0)
            assertEquals(0, starts)
            fixture.setup.gameParameters.players.add(Player("Korea", PlayerType.AI))
            fixture.picker.update()
            assertFalse(start.isDisabled)
            start.listeners.filterIsInstance<ActivationListener>().single().tap(InputEvent().apply { listenerActor = start }, 1f, 1f, 1, 0)
            assertEquals(1, starts)
            fixture.pane.validate()
            for (button in fixture.pane.bottomTable.setupDescendants().filterIsInstance<TextButton>()) {
                val position = button.localToStageCoordinates(Vector2())
                assertTrue("${button.text} must stay in the viewport", position.x >= 0f && position.y >= 0f && position.x + button.width <= width + 0.5f && position.y + button.height <= height + 0.5f)
            }
            val description = fixture.pane.descriptionLabel
            val descriptionPosition = description.localToStageCoordinates(Vector2())
            val actions = fixture.pane.bottomTable.setupDescendants().filterIsInstance<Table>().single { it.name == "SetupFooterActions" }
            val actionPosition = actions.localToStageCoordinates(Vector2())
            val optionsPosition = fixture.pane.scrollPane.localToStageCoordinates(Vector2())
            assertTrue("The entire wrapped description must remain visible", description.height + 0.5f >= description.prefHeight)
            assertTrue("Description must not overlap actions", descriptionPosition.y >= actionPosition.y + actions.height)
            assertTrue("Description must not overlap options", descriptionPosition.y + description.height <= optionsPosition.y + 0.5f)
            assertTrue("Description must remain inside the viewport", descriptionPosition.y >= 0f && descriptionPosition.y + description.height <= height)
            GameUiStyle.setEnabled(start, false)
            start.setText(Constants.working)
            assertTrue(start.isDisabled)
            assertEquals(Constants.working, start.text.toString())
            start.listeners.filterIsInstance<ActivationListener>().single().tap(InputEvent().apply { listenerActor = start }, 1f, 1f, 1, 0)
            assertEquals(1, starts)
        }
    }

    @Test fun `narrow bonus pickers stack Add actions inside the form width`() {
        val fixture = SetupLayoutFixture(520f, 360f)
        val form = StartingBonusForm(fixture.player, fixture.game.ruleset, 220f)
        form.pack()
        for (controls in form.setupDescendants().filterIsInstance<Table>().filter { it.name?.startsWith("SetupBonusPicker-") == true }) {
            val button = controls.setupDescendants().filterIsInstance<TextButton>().single()
            assertTrue(button.x >= 0f && button.x + button.width <= 220f + 0.5f)
            assertTrue("The picker belongs above its Add action", controls.children.first().y >= button.y + button.height)
        }
    }
}
