package com.unciv.dev

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Files as GdxFiles
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.utils.Disposable
import com.badlogic.gdx.utils.viewport.ScreenViewport
import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.app.desktop.DesktopFont
import com.unciv.app.desktop.agents.SupervisorProcess
import com.unciv.app.desktop.agents.SupervisorRunner
import com.unciv.logic.agents.*
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.files.SETTINGS_FILE_NAME
import com.unciv.logic.map.MapShape
import com.unciv.logic.map.HexCoord
import com.unciv.models.tilesets.TileSetCache
import com.unciv.models.skins.SkinCache
import com.unciv.testing.TestGame
import com.unciv.ui.audio.MusicController
import com.unciv.ui.components.SetupLayoutFixture
import com.unciv.ui.components.AtlasWidgets
import com.unciv.ui.components.GameUiStyle
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toCheckBox
import com.unciv.ui.components.widgets.UncivTextField
import com.unciv.ui.components.fonts.FontRulesetIcons
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.basescreen.UncivStage
import com.unciv.ui.screens.cityscreen.CityScreen
import com.unciv.ui.screens.diplomacyscreen.DiplomacyScreen
import com.unciv.ui.screens.mainmenuscreen.MainMenuScreen
import com.unciv.ui.screens.mapeditorscreen.MapEditorScreen
import com.unciv.ui.screens.pickerscreens.*
import com.unciv.ui.screens.worldscreen.TechPolicyDiplomacyButtons
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.ui.screens.worldscreen.UndoHandler
import com.unciv.ui.screens.worldscreen.minimap.MinimapHolder
import com.unciv.ui.screens.worldscreen.overview.CivilizationOverviewPanel
import com.unciv.ui.screens.worldscreen.status.*
import com.unciv.ui.screens.worldscreen.topbar.WorldScreenTopBar
import com.unciv.ui.screens.worldscreen.unit.UnitTable
import com.unciv.ui.screens.worldscreen.worldmap.WorldMapHolder
import com.unciv.view.GameView
import com.unciv.utils.Display
import com.unciv.utils.PlatformDisplay
import org.mockito.Mockito
import java.nio.file.Files
import java.nio.file.Path
import java.util.IdentityHashMap
import kotlin.random.Random

/** Test-only bootstrap. Neither UncivGame.create nor a production provider is used. */
object AtlasFixtureCatalog {
    const val SEED = 20261012
    val nations = listOf("America", "Arabia", "Germany", "China", "Russia", "The Ottomans",
        "Persia", "England", "France", "Rome", "Korea", "Babylon", "Polynesia", "Mongolia", "Egypt")
    private val registered = linkedSetOf("world", "activity", "messages", "overview", "nation-control",
        "terrain", "units", "city", "research", "policies", "religion", "promotions", "diplomacy",
        "setup", "menu", "supporting", "supporting-atlas")
    private val scopes = IdentityHashMap<BaseScreen, AtlasDataScope>()

    fun ids(): Set<String> = registered.toSet()
    fun isolatedData(): AtlasDataScope = AtlasDataScope()
    fun dataFor(screen: BaseScreen): AtlasDataScope = scopes.getValue(screen)

    fun create(id: String, width: Int, height: Int, fontScale: Float): BaseScreen =
        createScene(id, width, height, fontScale, large = false)

    fun createPerformance(): BaseScreen = createScene("activity", 1440, 900, 1f, large = true)

    private fun createScene(id: String, width: Int, height: Int, fontScale: Float, large: Boolean): BaseScreen {
        require(id in registered) { "Unknown Atlas fixture: $id" }
        require(width > 0 && height > 0 && fontScale > 0f)
        val scope = isolatedData()
        try {
            val fixture = scope.seedGame(large)
            val game = UncivGame.Current
            game.settings.apply {
                skin = if (id == "supporting-atlas") "Atlas" else "Minimal"
                fontSizeMultiplier = fontScale
                showTutorials = false
                enableEasterEggs = false
                tileSet = "HexaRealm"
                unitSet = "AbsoluteUnits"
                minimapSize = 12
                showTileYields = true
                showPixelImprovements = true
                continuousRendering = false
            }
            game.musicController = Mockito.mock(MusicController::class.java)
            Display.platform = object : PlatformDisplay {}
            // A new JVM per matrix cell owns Fonts, atlases and this sole GL context.
            Fonts.fontImplementation = DesktopFont()
            ImageGetter.setNewRuleset(fixture.ruleset)
            if (id == "supporting-atlas") {
                SkinCache.loadSkinConfigs()
                BaseScreen.setSkin()
                FontRulesetIcons.addRulesetImages(fixture.ruleset)
            }
            TileSetCache.loadTileSetConfigs()
            val civ = fixture.gameInfo.civilizations.first()
            val observer = fixture.gameInfo.civilizations.last()
            val humanView = GameView(fixture.gameInfo, civ, false)
            val host = AtlasActorScreen(scope)
            host.stage.viewport = ScreenViewport()
            host.stage.viewport.update(width, height, true)
            val viewer = if (id in setOf("world", "terrain", "units", "promotions", "city", "research", "policies", "religion", "diplomacy")) civ else observer
            val facade = worldFacade(host, scope, fixture, viewer)
            val screen: BaseScreen = when (id) {
                "city" -> CityScreen(humanView.getCityView(civ.cities.first()), playCelebrationSound = false)
                "research" -> TechPickerScreen(civ)
                "policies" -> PolicyPickerScreen(civ, false)
                "religion" -> PantheonPickerScreen(civ)
                "diplomacy" -> DiplomacyScreen(humanView.civView,
                    humanView.getForeignCivView(fixture.gameInfo.civilizations[1]), showTrade = true)
                "supporting" -> MapEditorScreen(fixture.tileMap)
                "menu" -> {
                    // A menu's background generation is unrelated to this seeded fixture.
                    // Use its existing unavailable-tileset guard to suppress that asynchronous
                    // source before construction, then restore the synthetic selected tileset.
                    val tileset = game.settings.tileSet
                    game.settings.tileSet = "AtlasFixtureNoBackground"
                    try { MainMenuScreen() } finally { game.settings.tileSet = tileset }
                }
                else -> host
            }
            // Real screen constructors use the production virtual-height viewport. Keep it:
            // replacing it after construction moves fixed chrome away from its layout bounds.
            screen.stage.viewport.update(width, height, true)
            if (screen !== host) {
                Mockito.`when`(facade.stage).thenReturn(screen.stage)
                host.dispose()
            }
            if (screen is AtlasActorScreen) {
                if (id == "promotions") {
                    screen.dispose()
                    val promotion = PromotionPickerScreen(civ.units.getCivUnits().first())
                    promotion.stage.viewport.update(width, height, true)
                    scopes[promotion] = scope
                    return promotion
                }
                if (id == "setup") {
                    val setup = SetupLayoutFixture(width.toFloat(), height.toFloat(), screen.stage as UncivStage, fontScale)
                    scope.installFiles()
                    setup.includeOptions(scope.dataRoot.toString())
                    setup.longContent()
                    screen.cleanup += { setup.cancelBackgroundJobs() }
                } else {
                    val holder = WorldMapHolder(facade, fixture.tileMap)
                    holder.callInternal("addTiles")
                    scope.map = holder
                    getter(facade, "getMapHolder", holder)
                    screen.stage.addActor(holder)
                    val view = GameView(fixture.gameInfo, viewer, viewer.isSpectator())
                    holder.tileGroups.values.forEach { it.update(view.civView) }
                    holder.zoom(if (large) 0.9f else 1.1f)
                    holder.layout()
                    holder.setCenterPosition(civ.cities.first().location, immediately = true, selectUnit = false)
                    holder.clearActions()
                    if (id == "supporting-atlas") {
                        addControlGallery(screen, width, height)
                        scopes[screen] = scope
                        return screen
                    }
                    val bar = WorldScreenTopBar(facade)
                    getter(facade, "getTopBar", bar)
                    screen.stage.addActor(bar)
                    bar.callInternal("update", civ)
                    val tech = TechPolicyDiplomacyButtons(facade)
                    getter(facade, "getTechPolicyAndDiplomacy", tech)
                    screen.stage.addActor(tech)
                    tech.update()
                    tech.setPosition(10f, bar.y - tech.height - 10f)
                    val minimap = MinimapHolder(holder)
                    screen.stage.addActor(minimap)
                    minimap.update(viewer)
                    minimap.setPosition(width - minimap.width - 12f, 12f)
                    if (id == "units") {
                        val unit = UnitTable(facade)
                        getter(facade, "getBottomUnitTable", unit)
                        screen.stage.addActor(unit)
                        unit.selectUnit(view.getMapUnitView(civ.units.getCivUnits().first()))
                        unit.update()
                    }
                    val panel: Disposable? = when (id) {
                        "activity" -> AgentActivityFeed(facade, scope.session)
                        "messages" -> AgentMessagesPanel(facade, scope.session).also { it.toggle() }
                        "overview" -> CivilizationOverviewPanel(facade)
                        "nation-control" -> AgentNationSettingsPanel(facade, scope.session).also { it.toggle() }
                        else -> null
                    }
                    panel?.let { screen.cleanup += { it.dispose() } }
                }
            }
            scopes[screen] = scope
            return screen
        } catch (failure: Throwable) {
            scope.close()
            throw failure
        }
    }

    /** Explicit Atlas variant over the same real mixed-biome map; supporting retains MapEditorScreen. */
    private fun addControlGallery(screen: BaseScreen, width: Int, height: Int) {
        val content = Table().apply {
            name = "AtlasControlGallery"
            background = GameUiStyle.panelBackground("Atlas/Gallery", 0.94f)
            pad(12f)
            defaults().growX().padBottom(8f)
        }
        val columnWidth = minOf(540f, width - 48f)
        content.add(AtlasWidgets.header("Atlas controls — aperçu", "Synthetic widget gallery • 日本語 / 中文 / Crème brûlée"))
            .width(columnWidth).row()
        val stats = Table()
        stats.add(AtlasWidgets.stat("Gold", "¤ 1,204")).growX()
        stats.add(AtlasWidgets.stat("Wheat", "[Wheat] 12")).growX()
        stats.add(AtlasWidgets.stat("Rome", "● Nation", Color.valueOf("A993ED"))).growX()
        content.add(stats).row()
        content.add(AtlasWidgets.tabs(listOf("Economy", "Research", "Long translated tab"), 0) {}).row()
        val field = UncivTextField("Draft", "draft • Zürich • 東京")
        content.add(AtlasWidgets.field("Message draft — keyboard focus", field)).row()
        val select = SelectBox<String>(BaseScreen.skin).apply { setItems("Peaceful trade", "Long option name with diacritics — São Paulo") }
        content.add(AtlasWidgets.field("Choice", select)).row()
        content.add("✓ Enabled • status includes text".toCheckBox(true).apply { GameUiStyle.applyWidget(this) }).row()
        content.add("✘ Disabled • unavailable".toCheckBox(false).apply { isDisabled = true; GameUiStyle.applyWidget(this) }).row()
        content.add("✘ Validation / semantic red".toLabel(Color.RED, 14).apply { wrap = true }).row()
        content.add(AtlasWidgets.disclosure("Long detail — expand for secondary context",
            "Readable long text wraps without replacing the selected platform font or ruleset symbols: [Wheat] [Iron] ¤.".toLabel(GameUiStyle.muted, 13).apply { wrap = true }, true)).row()
        val states = Table()
        val normal = GameUiStyle.button("Normal")
        val hover = GameUiStyle.button("Hover").apply { style = TextButton.TextButtonStyle(style).apply { up = over; fontColor = overFontColor } }
        val pressed = GameUiStyle.button("Pressed").apply { style = TextButton.TextButtonStyle(style).apply { up = down; fontColor = downFontColor } }
        val disabled = GameUiStyle.button("Disabled").apply { isDisabled = true }
        for (button in listOf(normal, hover, pressed, disabled)) {
            button.label.wrap = true
            states.add(button).growX().minHeight(40f).pad(2f)
        }
        content.add(states).row()
        content.add(AtlasWidgets.footer(GameUiStyle.button("Confirm ✓"), listOf(GameUiStyle.button("Cancel")))).row()
        val scroll = ScrollPane(content).apply { setScrollingDisabled(true, false); setFadeScrollBars(false) }
        scroll.setSize(columnWidth + 24f, height - 24f)
        scroll.setPosition(12f, 12f)
        screen.stage.addActor(scroll)
        scroll.validate()
        screen.stage.keyboardFocus = field
        // This complementary panel is over the map too, exposing the same opacity on a second biome.
        if (width >= 960) {
            val notes = Table().apply {
                background = GameUiStyle.panelBackground("Atlas/Gallery/SecondBiome", 0.94f)
                pad(16f)
                add(AtlasWidgets.header("Bright / dark terrain", "Ivory body • muted details • brass action\nNo blur; existing 2D map remains authoritative")).width(300f).row()
                add("日本語 中文 — Łódź — Ångström\n[Wheat] [Iron] ¤ ⚔".toLabel(GameUiStyle.ink, 14).apply { wrap = true }).width(300f).row()
            }
            notes.pack(); notes.setPosition(width - notes.width - 12f, height - notes.height - 12f)
            screen.stage.addActor(notes)
        }
    }

    fun dispose(screen: BaseScreen) {
        try { screen.dispose() } finally { scopes.remove(screen)?.close() }
    }

    private fun worldFacade(host: BaseScreen, scope: AtlasDataScope, fixture: TestGame, viewer: Civilization): WorldScreen {
        val screen = Mockito.mock(WorldScreen::class.java)
        Mockito.`when`(screen.stage).thenReturn(host.stage)
        Mockito.`when`(screen.game).thenReturn(UncivGame.Current)
        Mockito.`when`(screen.gameInfo).thenReturn(fixture.gameInfo)
        Mockito.`when`(screen.viewingCiv).thenReturn(viewer)
        val selected = if (viewer.isSpectator()) fixture.gameInfo.civilizations.first() else viewer
        Mockito.`when`(screen.selectedCiv).thenReturn(selected)
        Mockito.`when`(screen.selectedGameView).thenReturn(GameView(fixture.gameInfo, selected, viewer.isSpectator()))
        Mockito.`when`(screen.currentViewerCivId()).thenReturn(viewer.civID)
        Mockito.`when`(screen.agentSession).thenReturn(scope.session)
        getter(screen, "getBottomUnitTable", Mockito.mock(UnitTable::class.java))
        getter(screen, "getUndoHandler", Mockito.mock(UndoHandler::class.java))
        UncivGame::class.java.getDeclaredField("worldScreen").apply { isAccessible = true }.set(UncivGame.Current, screen)
        return screen
    }

    private fun getter(screen: WorldScreen, name: String, value: Any) {
        val method = screen.javaClass.methods.single { it.name.substringBefore('$') == name }
        Mockito.`when`(method.invoke(screen)).thenReturn(value)
    }
}

private class AtlasActorScreen(scope: AtlasDataScope) : BaseScreen() {
    val cleanup = ArrayList<() -> Unit>()
    init { scope.installFiles() }
    override fun dispose() {
        cleanup.forEach { it() }
        super.dispose()
    }
}

/** Files, settings and provider boundaries are explicit and inspectable by headless tests. */
class AtlasDataScope : AutoCloseable {
    private val originalFiles = Gdx.files
    val fixtureRoot: Path = System.getProperty("unciv.atlas.fixtureRoot")?.let {
        val parent = Path.of(it); Files.createDirectories(parent); Files.createTempDirectory(parent, "atlas-")
    } ?: Files.createTempDirectory("unciv-atlas-")
    val dataRoot: Path = Files.createDirectories(fixtureRoot.resolve("data"))
    val saveRoot: Path = Files.createDirectories(dataRoot.resolve("SaveFiles"))
    val agentDataRoot: Path = Files.createDirectories(dataRoot.resolve("agent-activity"))
    private val scopedFiles = object : GdxFiles by originalFiles {
        private fun scoped(path: String): FileHandle {
            val resolved = dataRoot.resolve(path).normalize()
            require(resolved.startsWith(dataRoot)) { "Fixture storage path escapes its root: $path" }
            return originalFiles.absolute(resolved.toString())
        }
        override fun local(path: String) = scoped(path)
        override fun external(path: String) = scoped(path)
        override fun getLocalStoragePath() = dataRoot.toString() + "/"
        override fun getExternalStoragePath() = dataRoot.toString() + "/"
        override fun getFileHandle(path: String, type: GdxFiles.FileType): FileHandle = when (type) {
            GdxFiles.FileType.Local, GdxFiles.FileType.External -> scoped(path)
            else -> originalFiles.getFileHandle(path, type)
        }
    }
    init {
        // Install this before TestGame, image/font bootstrap or any screen constructor.
        // Internal/classpath assets still resolve from the worktree; mutable storage does not.
        Gdx.files = scopedFiles
    }
    var providerCalls = 0
        private set
    val provider = object : SupervisorRunner {
        override fun isAlive() = false
        override fun runTurn(control: Map<String, Any?>): SupervisorProcess.TurnOutcome {
            providerCalls++
            error("Atlas fixture attempted a provider invocation")
        }
        override fun shutdown() = Unit
        override fun kill() = error("Atlas fixture must never control a provider process")
    }
    val session: AgentMatchSession = Mockito.mock(AgentMatchSession::class.java)
    lateinit var fixture: TestGame
        private set
    var map: WorldMapHolder? = null

    fun installFiles() { UncivGame.Current.files = UncivFiles(Gdx.files, dataRoot.toString()) }

    fun resolvedRoots(): Map<String, Any?> {
        val files = UncivGame.Current.files
        val settings = Path.of(files.getLocalFile(SETTINGS_FILE_NAME).path()).toAbsolutePath().normalize()
        val saves = Path.of(files.getSave("atlas-root-probe").parent().path()).toAbsolutePath().normalize()
        val modFonts = Path.of(files.getLocalFile("mods/synthetic/fonts").path()).toAbsolutePath().normalize()
        val activity = Path.of(session.getActivityDirectory()!!).toAbsolutePath().normalize()
        for (path in listOf(settings, saves, modFonts, activity)) check(path.startsWith(fixtureRoot))
        check(UncivGame.Current.settings.fontFamilyData.filePath == null)
        return linkedMapOf("settingsFile" to settings.toString(), "saveRoot" to saves.toString(),
            "modFontRoot" to modFonts.toString(), "selectedFont" to "AWT system default; no application font file",
            "agentActivityRoot" to activity.toString(), "providerWorkingDirectory" to null,
            "backendLocalRoot" to Gdx.files.localStoragePath, "backendExternalRoot" to Gdx.files.externalStoragePath)
    }

    fun seedGame(large: Boolean = false): TestGame {
        val fixture = TestGame()
        this.fixture = fixture
        installFiles()
        val width = if (large) 164 else 32
        val height = if (large) 80 else 20
        fixture.tileMap.mapParameters.worldWrap = true
        fixture.makeRectangularMap(height, width)
        fixture.tileMap.mapParameters.apply { shape = MapShape.rectangular; worldWrap = true; seed = AtlasFixtureCatalog.SEED.toLong() }
        val random = Random(AtlasFixtureCatalog.SEED)
        val biomes = listOf("Grassland", "Plains", "Desert", "Tundra", "Snow", "Coast", "Ocean")
        fixture.tileMap.values.forEachIndexed { index, tile ->
            tile.baseTerrain = biomes[(index / 31 + random.nextInt(3)) % biomes.size]
            tile.setTerrainFeatures(if (tile.baseTerrain in listOf("Grassland", "Plains") && index % 3 == 0)
                listOf(if (index % 2 == 0) "Forest" else "Hill") else emptyList())
            tile.setTileResource(if (tile.baseTerrain == "Grassland" && index % 17 == 0) "Wheat" else null, updateCache = false)
            tile.improvement = if (tile.resource != null) "Farm" else null
            tile.setTerrainTransients()
        }
        if (large) {
            for (position in AtlasActionSchedule.moves.flatMap { listOf(it.origin, it.destination) }) {
                val tile = fixture.getTile(position.x, position.y)
                tile.baseTerrain = Constants.grassland
                tile.setTerrainFeatures(emptyList()); tile.setTileResource(null, updateCache = false)
                tile.improvement = null
                tile.setTerrainTransients()
            }
        }
        val slots = fixture.tileMap.values.filter { it.isLand && (!large ||
            kotlin.math.abs(it.position.x) > 9 || kotlin.math.abs(it.position.y) > 9) }.toList()
        for ((index, nation) in AtlasFixtureCatalog.nations.withIndex()) {
            val civ = fixture.addCiv(fixture.ruleset.nations.getValue(nation), isPlayer = index == 0)
            val tile = slots[index * slots.size / 15]
            tile.baseTerrain = Constants.grassland; tile.setTerrainFeatures(emptyList()); tile.setTileResource(null, updateCache = false)
            tile.setTerrainTransients()
            val city = fixture.addCity(civ, tile, initialPopulation = 5 + index % 6)
            city.cityConstructions.addToQueue("Scout")
            civ.addGold(200 + index * 40)
            civ.tech.techsToResearch.add("Pottery")
            repeat(if (large) 12 else 2) { unit ->
                val location = if (large && index == 0 && unit < AtlasActionSchedule.moves.size)
                    AtlasActionSchedule.moves[unit].origin.let { fixture.getTile(it.x, it.y) }
                else slots[(index * slots.size / 15 + unit * 7 + 2) % slots.size]
                fixture.addUnit(if (unit % 2 == 0) "Warrior" else "Scout", civ, location)
            }
            civ.updateStatsForNextTurn()
        }
        val observer = fixture.addCiv(fixture.ruleset.nations.getValue(Constants.spectator))
        observer.cache.updateViewableTiles()
        fixture.gameInfo.currentPlayerCiv = fixture.gameInfo.civilizations.first()
        fixture.gameInfo.turns = 32
        // DiplomacyScreen must have a real known nation, with no provider or agreement actions.
        fixture.gameInfo.civilizations[0].diplomacyFunctions.makeCivilizationsMeet(fixture.gameInfo.civilizations[1])
        val profiles = AtlasFixtureCatalog.nations.associateWith { AgentProfile.defaultFor(it) }
        Mockito.`when`(session.getActiveCivId()).thenReturn("America")
        Mockito.`when`(session.getActivityDirectory()).thenReturn(agentDataRoot.toString())
        Mockito.`when`(session.getRunState()).thenReturn(AgentRunState.PAUSED)
        Mockito.`when`(session.statusText()).thenReturn("Synthetic paused baseline")
        Mockito.`when`(session.getActiveAgentProfile()).thenReturn(profiles.getValue("America"))
        Mockito.`when`(session.getAgentProfile(Mockito.anyString())).thenAnswer { profiles[it.getArgument<String>(0)] }
        Mockito.`when`(session.isSpectatorMode()).thenReturn(true)
        Mockito.`when`(session.canSpectate()).thenReturn(true)
        Mockito.`when`(session.getMessageContacts()).thenReturn(profiles.map { (id, profile) -> MessageContact(id, profile.displayName, true) })
        Mockito.`when`(session.getNationSettings()).thenReturn(profiles.map { (id, profile) ->
            NationSetting(id, id, ControllerKind.EXTERNAL_AGENT, profile, id == "America") })
        Mockito.`when`(session.getMessages()).thenReturn(listOf(
            DiplomaticMessage("baseline-delivered", "America", "Arabia", "Let us discuss peaceful trade across the river.", 30, 31,
                acknowledgedTurnSerial = 31, acknowledgementText = "Received. I will consider the proposal."),
            DiplomaticMessage("baseline-queued", observer.civID, "America", "Explore the coast before choosing the next city.", 32, null, "SPECTATOR")))
        Mockito.`when`(session.getActivity()).thenReturn(listOf(
            AgentActivityEntry("baseline-speech", "America", 32, "message", "", "", "2026-10-12T00:00:00Z"),
            AgentActivityEntry("baseline-tool", "America", 32, "tool", "Inspect cities and units", "15 nations, synthetic map", "2026-10-12T00:00:01Z", "succeeded")))
        Mockito.`when`(session.getActivityDetails(Mockito.anyString())).thenReturn("We will explore the coastline and consider the trade proposal before expanding inland.")
        Mockito.doAnswer { provider.runTurn(emptyMap()); null }.`when`(session).startAutomaticPlay()
        Mockito.doAnswer { provider.runTurn(emptyMap()); null }.`when`(session).resume()
        Mockito.doAnswer { provider.runTurn(emptyMap()); null }.`when`(session).retry()
        return fixture
    }

    override fun close() {
        if (Gdx.files === scopedFiles) Gdx.files = originalFiles
        fixtureRoot.toFile().deleteRecursively()
    }
}

internal fun Any.callInternal(name: String, vararg args: Any?) {
    javaClass.methods.single { it.name.substringBefore('$') == name && it.parameterCount == args.size }.invoke(this, *args)
}

internal fun Actor.atlasDescendants(): Sequence<Actor> = sequence {
    yield(this@atlasDescendants)
    if (this@atlasDescendants is Group) children.forEach { yieldAll(it.atlasDescendants()) }
}

/** Fixed engine workload, indexed by trace seconds rather than frame count. */
class AtlasActionSchedule(private val fixture: TestGame) {
    data class Move(val seconds: Double, val unitIndex: Int, val origin: HexCoord, val destination: HexCoord)
    companion object {
        val moves = listOf(
            Move(3.0, 0, HexCoord(-5, -5), HexCoord(-5, -4)),
            Move(8.0, 1, HexCoord(-3, -3), HexCoord(-3, -2)),
            Move(13.0, 2, HexCoord(0, 0), HexCoord(1, 0)),
            Move(18.0, 3, HexCoord(3, 3), HexCoord(3, 4)),
            Move(23.0, 4, HexCoord(5, 5), HexCoord(5, 6)),
            Move(28.0, 5, HexCoord(-5, 5), HexCoord(-5, 6)))
    }
    private val units = fixture.gameInfo.civilizations.first().units.getCivUnits().take(6).toList()
    val executed = ArrayList<Move>()
    init { check(units.map { it.getTile().position } == moves.map { it.origin }) }

    fun executeThrough(traceSeconds: Double): List<Move> {
        val applied = ArrayList<Move>()
        while (executed.size < moves.size && moves[executed.size].seconds <= traceSeconds) {
            val move = moves[executed.size]
            val unit = units[move.unitIndex]
            val destination = fixture.getTile(move.destination.x, move.destination.y)
            check(unit.getTile().position == move.origin)
            check(unit.movement.canMoveTo(destination)) { "Illegal scheduled destination: $move" }
            unit.movement.moveToTile(destination)
            check(unit.getTile().position == move.destination) { "Engine did not commit the scheduled move" }
            executed += move; applied += move
        }
        return applied
    }

    fun finalState(): List<String> = units.mapIndexed { index, unit ->
        "$index:${unit.civ.civID}:${unit.name}:${unit.getTile().position.x},${unit.getTile().position.y}:${unit.health}:${unit.currentMovement}"
    }
    fun finalStateHash(): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(finalState().joinToString("\n").toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
