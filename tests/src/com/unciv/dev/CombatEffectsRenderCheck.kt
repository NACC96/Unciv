package com.unciv.dev

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.badlogic.gdx.graphics.glutils.FrameBuffer
import com.badlogic.gdx.graphics.glutils.HdpiMode
import com.badlogic.gdx.utils.viewport.ScreenViewport
import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.presentation.VisualAttackKind
import com.unciv.logic.presentation.VisualAttackOutcome
import com.unciv.logic.presentation.VisualBatch
import com.unciv.logic.presentation.VisualDescriptor
import com.unciv.logic.presentation.VisualEvent
import com.unciv.logic.presentation.VisualEventId
import com.unciv.logic.presentation.VisualPosition
import com.unciv.logic.presentation.VisualProvenance
import com.unciv.logic.presentation.VisualUnitDescriptor
import com.unciv.logic.presentation.VisualViewKey
import com.unciv.models.skins.SkinCache
import com.unciv.models.tilesets.TileSetCache
import com.unciv.testing.TestGame
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.images.atlas.AtlasArtManifest
import com.unciv.ui.images.atlas.AtlasClipCache
import com.unciv.ui.images.atlas.ClipKey
import com.unciv.ui.images.atlas.HexFacing
import com.unciv.ui.images.atlas.UnitVisualState
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.basescreen.UncivStage
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.ui.screens.worldscreen.presentation.WorldVisualTimeline
import com.unciv.ui.screens.worldscreen.presentation.combat.CombatStyles
import com.unciv.ui.screens.worldscreen.presentation.combat.WeaponFamily
import com.unciv.ui.screens.worldscreen.topbar.WorldScreenTopBar
import com.unciv.ui.screens.worldscreen.unit.UnitTable
import com.unciv.ui.screens.worldscreen.worldmap.WorldMapHolder
import com.unciv.view.GameView
import com.unciv.view.VisualEventProjector
import org.mockito.Mockito
import java.io.File

/**
 * Sheets of frames of every weapon family's attack, played by the real world timeline over a real map with
 * Atlas art, plus a check that a busy turn keeps its visible attacks. Real GPU, synthetic game only.
 *
 * Arguments: an output directory, then optionally a comma-separated list of scenario name prefixes to draw.
 * Each scenario writes `<name>.png`, a sheet of frames read left to right and top to bottom,
 * and `<name>.txt` naming the nominal time of each frame. System properties: `fx.frames` (default 8) or
 * `fx.times` (comma-separated nominal seconds) choose the frames, and `fx.zoom` the map zoom (default 1).
 */
object CombatEffectsRenderCheck {
    private const val WIDTH = 900
    private const val HEIGHT = 640
    private const val CROP_WIDTH = 460
    private const val CROP_HEIGHT = 300
    private const val COLUMNS = 4

    /**
     * One attack to draw. Positions are (x, y) map coordinates; the map is land unless [sea], where every tile is
     * coast except [land] tiles. [kill] removes the defender, [attackerDies] the attacker; [cityTarget] puts a
     * city under the defender, [cityAttacker] makes the attack a city's bombardment at the attacking civ's [era].
     */
    class Scenario(
        val name: String,
        val attacker: String?,
        val defender: String?,
        val from: Pair<Int, Int>,
        val to: Pair<Int, Int>,
        val kind: VisualAttackKind,
        val sea: Boolean = false,
        val land: List<Pair<Int, Int>> = emptyList(),
        val kill: Boolean = false,
        val attackerDies: Boolean = false,
        val outcome: VisualAttackOutcome = VisualAttackOutcome.RESOLVED,
        val cityTarget: Boolean = false,
        val cityAttacker: Boolean = false,
        val era: String? = null,
        val damage: Int = 24,
        val counterDamage: Int = 0,
        /** The viewer never saw the attacker: only its blow on the defender is known. */
        val unseenAttacker: Boolean = false
    )

    private val melee = (-1 to 0) to (0 to 0)
    private val ranged = (-2 to 0) to (0 to 0)
    private val diagonal = (-2 to -2) to (0 to 0)
    private val far = (-3 to 0) to (0 to 0)
    private val air = (-4 to -1) to (0 to 0)
    /** Land under every attacker's tile on the west of a sea map, for shots from the shore. */
    private val COASTAL = listOf(-2 to 0, -3 to 0, -2 to -1, -3 to -1, -1 to 0, -2 to 1, -4 to -1, -4 to -2, -3 to -2, -5 to -1)

    val scenarios = listOf(
        // Ancient and medieval land
        Scenario("arrows", "Archer", "Warrior", ranged.first, ranged.second, VisualAttackKind.RANGED),
        Scenario("arrows-stones", "Slinger", "Spearman", diagonal.first, diagonal.second, VisualAttackKind.RANGED),
        Scenario("arrows-javelins", "Atlatlist", "Warrior", ranged.first, ranged.second, VisualAttackKind.RANGED),
        Scenario("arrows-bolts", "Crossbowman", "Pikeman", ranged.first, ranged.second, VisualAttackKind.RANGED),
        Scenario("arrows-repeating", "Chu-Ko-Nu", "Swordsman", ranged.first, ranged.second, VisualAttackKind.RANGED, kill = true),
        Scenario("arrows-city", null, "Swordsman", ranged.first, ranged.second, VisualAttackKind.CITY, cityAttacker = true),
        Scenario("arrows-dromon", "Dromon", "Trireme", ranged.first, ranged.second, VisualAttackKind.RANGED, sea = true),
        Scenario("arrows-at-ship", "Archer", "Galley", ranged.first, ranged.second, VisualAttackKind.RANGED, sea = true, land = COASTAL),
        Scenario("catapult", "Catapult", "Spearman", ranged.first, ranged.second, VisualAttackKind.RANGED),
        Scenario("catapult-bolt", "Ballista", "Horseman", ranged.first, ranged.second, VisualAttackKind.RANGED),
        Scenario("catapult-heavy", "Trebuchet", null, ranged.first, ranged.second, VisualAttackKind.RANGED, cityTarget = true),
        Scenario("blades", "Swordsman", "Spearman", melee.first, melee.second, VisualAttackKind.MELEE, counterDamage = 12),
        Scenario("blades-kill", "Legion", "Warrior", melee.first, melee.second, VisualAttackKind.MELEE, kill = true, counterDamage = 6),
        Scenario("blades-city", "Swordsman", null, melee.first, melee.second, VisualAttackKind.MELEE, cityTarget = true, counterDamage = 9),
        Scenario("blades-ram", "Battering Ram", null, melee.first, melee.second, VisualAttackKind.MELEE, cityTarget = true),
        Scenario("charge", "Knight", "Pikeman", melee.first, melee.second, VisualAttackKind.MELEE, counterDamage = 18),
        Scenario("charge-kill", "Horseman", "Archer", melee.first, melee.second, VisualAttackKind.MELEE, kill = true),
        // Gunpowder and modern land
        Scenario("muskets", "Musketman", "Longswordsman", melee.first, melee.second, VisualAttackKind.MELEE, counterDamage = 10),
        Scenario("muskets-kill", "Rifleman", "Musketman", melee.first, melee.second, VisualAttackKind.MELEE, kill = true),
        Scenario("rifles", "Infantry", "Rifleman", melee.first, melee.second, VisualAttackKind.MELEE, counterDamage = 8),
        Scenario("rifles-kill", "Great War Infantry", "Rifleman", melee.first, melee.second, VisualAttackKind.MELEE, kill = true),
        Scenario("machine-gun", "Machine Gun", "Infantry", melee.first, melee.second, VisualAttackKind.RANGED),
        Scenario("machine-gun-gatling", "Gatling Gun", "Rifleman", melee.first, melee.second, VisualAttackKind.RANGED, kill = true),
        Scenario("machine-gun-flak", "Anti-Aircraft Gun", "Infantry", melee.first, melee.second, VisualAttackKind.MELEE),
        Scenario("tank", "Tank", "Infantry", melee.first, melee.second, VisualAttackKind.MELEE, counterDamage = 9),
        Scenario("tank-kill", "Modern Armor", "Tank", melee.first, melee.second, VisualAttackKind.MELEE, kill = true),
        Scenario("tank-landship", "Landship", "Great War Infantry", melee.first, melee.second, VisualAttackKind.MELEE, counterDamage = 6),
        Scenario("tank-gun", "Anti-Tank Gun", "Tank", melee.first, melee.second, VisualAttackKind.MELEE),
        Scenario("laser", "Giant Death Robot", "Modern Armor", melee.first, melee.second, VisualAttackKind.MELEE, kill = true),
        // Artillery and missiles
        Scenario("cannon", "Cannon", "Musketman", ranged.first, ranged.second, VisualAttackKind.RANGED),
        Scenario("cannon-ship", "Cannon", "Frigate", ranged.first, ranged.second, VisualAttackKind.RANGED, sea = true, land = COASTAL),
        Scenario("cannon-city", null, "Musketman", ranged.first, ranged.second, VisualAttackKind.CITY, cityAttacker = true, era = "Gunpowder"),
        Scenario("artillery", "Artillery", "Infantry", far.first, far.second, VisualAttackKind.RANGED),
        Scenario("artillery-city", null, "Infantry", ranged.first, ranged.second, VisualAttackKind.CITY, cityAttacker = true, era = "Radio"),
        Scenario("artillery-hits-city", "Artillery", null, far.first, far.second, VisualAttackKind.RANGED, cityTarget = true),
        Scenario("rockets", "Rocket Artillery", "Mechanized Infantry", far.first, far.second, VisualAttackKind.RANGED),
        Scenario("rockets-fire", "Hwach'a", "Pikeman", ranged.first, ranged.second, VisualAttackKind.RANGED, kill = true),
        Scenario("missile", "Guided Missile", "Tank", air.first, air.second, VisualAttackKind.AIR, attackerDies = true, kill = true),
        Scenario("missile-cruiser", "Missile Cruiser", "Destroyer", far.first, far.second, VisualAttackKind.RANGED, sea = true),
        Scenario("missile-sam", "Mobile SAM", "Infantry", melee.first, melee.second, VisualAttackKind.MELEE),
        Scenario("missile-sam-dies", "Mobile SAM", "Infantry", melee.first, melee.second, VisualAttackKind.MELEE,
            attackerDies = true, counterDamage = 40),
        // Naval
        Scenario("ram-ship", "Trireme", "Galley", melee.first, melee.second, VisualAttackKind.MELEE, sea = true, counterDamage = 8),
        Scenario("ram-ship-kill", "Quinquereme", "Trireme", melee.first, melee.second, VisualAttackKind.MELEE, sea = true, kill = true),
        Scenario("broadside", "Frigate", "Caravel", ranged.first, ranged.second, VisualAttackKind.RANGED, sea = true),
        Scenario("broadside-heavy", "Ship of the Line", "Privateer", ranged.first, ranged.second, VisualAttackKind.RANGED, sea = true, kill = true),
        Scenario("broadside-captured", "Privateer", "Caravel", melee.first, melee.second, VisualAttackKind.MELEE, sea = true,
            kill = true, outcome = VisualAttackOutcome.CAPTURED),
        Scenario("broadside-light", "Privateer", "Caravel", melee.first, melee.second, VisualAttackKind.MELEE, sea = true, counterDamage = 10),
        Scenario("naval-guns", "Destroyer", "Ironclad", melee.first, melee.second, VisualAttackKind.MELEE, sea = true, counterDamage = 7),
        Scenario("naval-guns-heavy", "Battleship", "Destroyer", far.first, far.second, VisualAttackKind.RANGED, sea = true, kill = true),
        Scenario("naval-guns-shore", "Battleship", null, ranged.first, ranged.second, VisualAttackKind.RANGED, sea = true,
            land = listOf(0 to 0, 1 to 0, 1 to 1, 0 to 1, 1 to -1, 2 to 0), cityTarget = true),
        Scenario("torpedo", "Submarine", "Destroyer", ranged.first, ranged.second, VisualAttackKind.RANGED, sea = true, kill = true),
        // Air, and what follows a blow
        Scenario("strafe", "Fighter", "Infantry", air.first, air.second, VisualAttackKind.AIR),
        Scenario("strafe-ship", "Fighter", "Destroyer", air.first, air.second, VisualAttackKind.AIR, sea = true, land = COASTAL),
        Scenario("strafe-jet", "Jet Fighter", "Tank", air.first, air.second, VisualAttackKind.AIR),
        Scenario("strafe-biplane", "Triplane", "Rifleman", air.first, air.second, VisualAttackKind.AIR),
        Scenario("gunship", "Helicopter Gunship", "Tank", melee.first, melee.second, VisualAttackKind.MELEE, kill = true),
        Scenario("bombs", "Bomber", "Infantry", air.first, air.second, VisualAttackKind.AIR),
        Scenario("bombs-heavy", "B17", null, air.first, air.second, VisualAttackKind.AIR, cityTarget = true),
        Scenario("bombs-intercepted", "Bomber", "Infantry", air.first, air.second, VisualAttackKind.AIR,
            outcome = VisualAttackOutcome.INTERCEPTED, attackerDies = true, damage = 0),
        Scenario("withdrawn", "Swordsman", "Scout", melee.first, melee.second, VisualAttackKind.MELEE,
            outcome = VisualAttackOutcome.WITHDRAWN, damage = 0),
        Scenario("unseen", "Swordsman", "Spearman", melee.first, melee.second, VisualAttackKind.MELEE, unseenAttacker = true),
        Scenario("unseen-at-sea", "Frigate", "Caravel", ranged.first, ranged.second, VisualAttackKind.RANGED, sea = true,
            unseenAttacker = true, kill = true),
    )

    @JvmStatic fun main(args: Array<String>) {
        require(args.isNotEmpty()) { "Provide an output directory for the PNG sheets" }
        val filters = args.getOrNull(1)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        var failure: Throwable? = null
        val config = Lwjgl3ApplicationConfiguration().apply {
            setTitle("Unciv combat effects render check")
            setWindowedMode(WIDTH, HEIGHT)
            setHdpiMode(HdpiMode.Pixels)
            setInitialVisible(false)
            disableAudio(true)
        }
        Lwjgl3Application(object : ApplicationAdapter() {
            override fun create() {
                try {
                    Gdx.files.absolute(args[0]).mkdirs()
                    val chosen = scenarios.filter { s -> filters.isEmpty() || filters.any { s.name == it || s.name.startsWith("$it-") } }
                    require(chosen.isNotEmpty()) { "No scenario matches $filters" }
                    for (scenario in chosen) drawScenario(scenario, args[0])
                    if (filters.isEmpty()) checkBusyTurn()
                    println("COMBAT_EFFECTS_RENDER_CHECK_PASSED")
                    if (System.getProperty("unciv.renderCheck.synchronous") == "true") System.exit(0)
                    Gdx.app.exit()
                } catch (ex: Throwable) {
                    failure = ex
                    ex.printStackTrace()
                    if (System.getProperty("unciv.renderCheck.synchronous") == "true") System.exit(1)
                    Gdx.app.exit()
                }
            }
        }, config)
        failure?.let { kotlin.system.exitProcess(1) }
    }

    /** A synthetic world with the scenario's units, a timeline over it, and the events to play. */
    private class Stage(scenario: Scenario) : AutoCloseable {
        val game = TestGame()
        val rome: Civilization
        val greece: Civilization
        val view: VisualViewKey
        val stage = UncivStage(ScreenViewport())
        val frame = FrameBuffer(Pixmap.Format.RGBA8888, WIDTH, HEIGHT, false)
        val holder: WorldMapHolder
        val timeline: WorldVisualTimeline
        private val screen: WorldScreen = Mockito.mock(WorldScreen::class.java)
        var nextBatch: VisualBatch? = null
        private var sequence = 0L

        init {
            game.makeHexagonalMap(7)
            // The helper's own terrain argument doesn't reach the new map's tiles, so each one is set here.
            for (tile in game.tileMap.values.toList()) {
                val land = !scenario.sea || tile.position.x to tile.position.y in scenario.land
                game.setTileTerrain(tile.position, if (land) Constants.grassland else Constants.coast)
            }
            rome = game.addCiv(game.ruleset.nations["Rome"]!!, isPlayer = true)
            greece = game.addCiv(game.ruleset.nations["Greece"]!!)
            game.gameInfo.currentPlayerCiv = rome
            UncivGame.Current.settings.apply {
                minimapSize = 12; showTileYields = false; showResourcesAndImprovements = false
                showWorkedTiles = false; showUnitMovements = false; showPixelImprovements = true
                tileSet = "AtlasTerrain"; unitSet = "AtlasUnits"; skin = "Atlas"
                unitAnimationMode = "Normal"; followVisibleActions = false
            }
            Fonts.fontImplementation = FontDesktop()
            ImageGetter.setNewRuleset(game.ruleset)
            SkinCache.loadSkinConfigs()
            BaseScreen.setSkin()
            TileSetCache.loadTileSetConfigs()
            view = VisualViewKey(rome.civID, false, 1L, revealMap = true, revealUnits = true)
            for (tile in game.tileMap.values) tile.setExplored(rome, true)
            rome.viewableTiles = game.tileMap.values.toSet()

            stage.viewport.update(WIDTH, HEIGHT, true)
            val played = GameView(game.gameInfo, rome)
            Mockito.`when`(screen.stage).thenReturn(stage)
            Mockito.`when`(screen.game).thenReturn(UncivGame.Current)
            Mockito.`when`(screen.gameInfo).thenReturn(game.gameInfo)
            Mockito.`when`(screen.selectedCiv).thenReturn(rome)
            Mockito.`when`(screen.viewingCiv).thenReturn(rome)
            Mockito.`when`(screen.selectedGameView).thenReturn(played)
            Mockito.`when`(screen.getGameViewConsideringForOfWar()).thenReturn(played)
            Mockito.`when`(screen.currentVisualViewKey()).thenReturn(view)
            Mockito.`when`(screen.isVisualTransportAvailable()).thenReturn(true)
            Mockito.`when`(screen.takeVisualBatch()).thenAnswer { nextBatch.also { nextBatch = null } }
            fun getter(name: String) = screen.javaClass.methods.single { it.name.substringBefore('$') == name }
            Mockito.`when`(getter("getBottomUnitTable").invoke(screen) as UnitTable?).thenReturn(Mockito.mock(UnitTable::class.java))
            Mockito.`when`(getter("getTopBar").invoke(screen) as WorldScreenTopBar?).thenReturn(Mockito.mock(WorldScreenTopBar::class.java))
            holder = WorldMapHolder(screen, game.tileMap)
            Mockito.`when`(screen.mapHolder).thenReturn(holder)
            timeline = WorldVisualTimeline(screen)
        }

        /** Builds the map's tile groups once every unit and city is in place, and only then installs the screen. */
        fun showMap(center: Pair<Int, Int>) {
            UncivGame::class.java.getDeclaredField("worldScreen").apply { isAccessible = true }.set(UncivGame.Current, screen)
            holder.javaClass.methods.single { it.name.substringBefore('$') == "addTiles" }.invoke(holder)
            stage.addActor(holder)
            val played = GameView(game.gameInfo, rome)
            for (tile in holder.tileGroups.values) tile.update(played.civView)
            holder.rebuildVisualUnitIndex()
            // -Dfx.zoom=1.5 shows more detail; the game opens at zoom 1.
            holder.zoom(System.getProperty("fx.zoom")?.toFloatOrNull() ?: 1f)
            holder.layout()
            holder.setCenterPosition(game.getTile(center.first, center.second).position, immediately = true, selectUnit = false)
            holder.clearActions()
        }

        fun nextId() = VisualEventId(game.gameInfo.gameId, "render-check", ++sequence)

        override fun close() {
            timeline.dispose()
            UncivGame::class.java.getDeclaredField("worldScreen").apply { isAccessible = true }.set(UncivGame.Current, null)
            frame.dispose()
            stage.dispose()
        }
    }

    private fun drawScenario(scenario: Scenario, output: String) {
        Stage(scenario).use { world ->
            val game = world.game
            val (fromX, fromY) = scenario.from
            val (toX, toY) = scenario.to
            val fromTile = game.getTile(fromX, fromY)
            val toTile = game.getTile(toX, toY)
            if (scenario.era != null) world.rome.tech.addTechnology(scenario.era, showNotification = false)
            val attackerCity = if (scenario.cityAttacker) game.addCity(world.rome, fromTile, initialPopulation = 5) else null
            if (scenario.kind == VisualAttackKind.AIR && !scenario.cityAttacker) game.addCity(world.rome, fromTile, initialPopulation = 3)
            // Units go in before the defender's city claims the tiles around it, which some may not enter in peace.
            val attackerUnit = scenario.attacker?.let { game.addUnit(it, world.rome, fromTile) }
            val defenderCity = if (scenario.cityTarget) game.addCity(world.greece, toTile, initialPopulation = 6) else null
            val defenderUnit = scenario.defender?.let { game.addUnit(it, world.greece, toTile) }
            for (civ in listOf(world.rome, world.greece)) civ.cache.updateSightAndResources()
            for (tile in game.tileMap.values) tile.setExplored(world.rome, true)
            world.rome.viewableTiles = game.tileMap.values.toSet()

            fun descriptor(unit: com.unciv.logic.map.mapunit.MapUnit?): VisualUnitDescriptor? =
                unit?.let { VisualEventProjector.unit(game.gameInfo, world.view, it, it.currentTile)?.unit }
            val attacker: VisualDescriptor? = attackerCity?.let { VisualEventProjector.city(game.gameInfo, world.view, it)?.city }
                ?: descriptor(attackerUnit)
            val defender: VisualDescriptor? = defenderUnit?.let { descriptor(it) }
                ?: defenderCity?.let { VisualEventProjector.city(game.gameInfo, world.view, it)?.city }
            check(scenario.attacker == null || attacker != null) { "${scenario.name}: the attacker must be seen" }
            val source = VisualPosition(fromX, fromY)
            val target = VisualPosition(toX, toY)
            val events = ArrayList<VisualEvent>()
            // An unseen attacker reaches the screen as the projector reports it: no attacker, no source, no kind.
            events += if (scenario.unseenAttacker) VisualEvent.Attack(world.nextId(), world.view, VisualProvenance(), null, target,
                null, defender, null, scenario.damage, VisualAttackKind.UNKNOWN, scenario.outcome)
            else VisualEvent.Attack(world.nextId(), world.view, VisualProvenance(), source, target, attacker, defender,
                scenario.counterDamage.takeIf { it > 0 }, scenario.damage.takeIf { it > 0 }, scenario.kind, scenario.outcome)
            // The map already shows the outcome: the dead are gone before their deaths are played.
            if (scenario.kill && defenderUnit != null) {
                events += VisualEvent.RemoveUnit(world.nextId(), world.view, VisualProvenance(), descriptor(defenderUnit)!!, target)
                defenderUnit.destroy()
            }
            if (scenario.attackerDies && attackerUnit != null) {
                events += VisualEvent.RemoveUnit(world.nextId(), world.view, VisualProvenance(), descriptor(attackerUnit)!!, source)
                attackerUnit.destroy()
            }
            // Removing the dead recomputes sight; the viewer keeps seeing everything.
            world.rome.viewableTiles = game.tileMap.values.toSet()
            val center = ((fromX + toX) / 2) to ((fromY + toY) / 2)
            world.showMap(center)
            warmClips(events.flatMap { event ->
                when (event) {
                    is VisualEvent.Attack -> listOfNotNull(
                        (event.attacker as? VisualUnitDescriptor)?.let { it.artName to UnitVisualState.ATTACK },
                        (event.defender as? VisualUnitDescriptor)?.let { it.artName to UnitVisualState.HIT })
                    is VisualEvent.RemoveUnit -> listOf(event.unit.artName to UnitVisualState.DEFEAT)
                    else -> emptyList()
                }
            })
            world.nextBatch = VisualBatch(world.view, events, events.last().id.sequence, 0L)

            val step = 1 / 60f
            world.timeline.advance(step)
            check(world.timeline.pendingCount >= 1) { "${scenario.name}: the attack must play" }
            // Frames at even fractions of the attack's playback, which is 1 s, or 1.3 s when someone dies.
            val length = if (events.size > 1) 1.3f else 1f
            val count = System.getProperty("fx.frames")?.toIntOrNull() ?: 8
            val times = System.getProperty("fx.times")?.split(',')?.mapNotNull { it.trim().toFloatOrNull() }
                ?: (0 until count).map { (it + 1) * length / (count + 1) }
            val crops = ArrayList<Pixmap>()
            var time = step
            try {
                for (at in times) {
                    while (time + step / 2 < at) { world.timeline.advance(step); time += step }
                    while (Gdx.gl.glGetError() != GL20.GL_NO_ERROR) { }
                    crops += capture(world.stage, world.frame)
                }
                while (world.timeline.pendingCount > 0 && time < 3f) { world.timeline.advance(step); time += step }
                check(world.timeline.pendingCount == 0) { "${scenario.name}: playback must finish" }
                check(world.holder.visualShake.isZero) { "${scenario.name}: the map must settle after the attack" }
                writeSheet(crops, "$output/${scenario.name}.png")
                File("$output/${scenario.name}.txt").writeText(buildString {
                    val style = if (scenario.unseenAttacker) "unseen attacker" else attacker?.let { describe(game, it, scenario) } ?: "unseen"
                    appendLine("${scenario.name}: $style; frames at nominal seconds, left to right, top to bottom:")
                    appendLine(times.joinToString(", ") { "%.2f".format(it) })
                })
            } finally {
                crops.forEach { it.dispose() }
            }
        }
    }

    private fun describe(game: TestGame, attacker: VisualDescriptor, scenario: Scenario): String {
        if (scenario.cityAttacker) return "city bombardment"
        val style = CombatStyles.forUnit(game.ruleset.units[attacker.artName], scenario.kind, game.ruleset)
        return "${style.family}${style.variant?.let { " ($it)" } ?: ""}${if (style.heavy) " heavy" else ""}, era ${style.era}"
    }

    /**
     * Loads the unit animations playback will ask for before it starts. The clip cache decodes in the background and
     * uploads through the app's runnable queue, which never runs while this check holds the GL thread, so it is
     * pumped here as the app's loop would between frames.
     */
    private fun warmClips(wanted: List<Pair<String, UnitVisualState>>) {
        val cache = ImageGetter.getAtlasClipCache()
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!cache.metrics().manifestReady && System.nanoTime() < deadline) { cache.request(ClipKey("Warrior", UnitVisualState.IDLE, HexFacing.E))?.close(); pumpGl(); Thread.sleep(5) }
        val manifest = AtlasClipCache::class.java.getDeclaredField("metadata").apply { isAccessible = true }.get(cache) as? AtlasArtManifest ?: return
        val keys = wanted.distinct().flatMap { (unit, state) -> HexFacing.entries.map { ClipKey(unit, state, it) } }
            .filter { manifest.findClip(it) != null }.toMutableList()
        while (keys.isNotEmpty() && System.nanoTime() < deadline) {
            keys.removeAll { key -> cache.request(key)?.also { it.close() } != null }
            pumpGl(); Thread.sleep(5)
        }
    }

    private fun pumpGl() {
        val app = Gdx.app
        @Suppress("UNCHECKED_CAST")
        val queue = app.javaClass.getDeclaredField("runnables").apply { isAccessible = true }.get(app) as com.badlogic.gdx.utils.Array<Runnable>
        val pending = synchronized(queue) { queue.toList().also { queue.clear() } }
        for (runnable in pending) runnable.run()
    }

    /** Draws the stage and keeps the middle of the frame, where the camera centers the attack. */
    private fun capture(stage: UncivStage, frame: FrameBuffer): Pixmap {
        frame.begin()
        try {
            Gdx.gl.glClearColor(0.07f, 0.1f, 0.11f, 1f)
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
            stage.root.act(0f)
            stage.camera.update()
            stage.batch.projectionMatrix = stage.camera.combined
            // Keep the frame opaque, as a window is: blending translucent smoke would otherwise lower the stored alpha
            // and darken the sheet where frames are laid over black.
            Gdx.gl.glColorMask(true, true, true, false)
            stage.batch.begin()
            try { stage.root.draw(stage.batch, 1f) }
            finally { stage.batch.end(); Gdx.gl.glColorMask(true, true, true, true) }
            check(Gdx.gl.glGetError() == GL20.GL_NO_ERROR) { "Capture leaked a GL error" }
            val whole = Pixmap.createFromFrameBuffer(0, 0, WIDTH, HEIGHT)
            try {
                val crop = Pixmap(CROP_WIDTH, CROP_HEIGHT, Pixmap.Format.RGBA8888)
                crop.blending = Pixmap.Blending.None
                crop.drawPixmap(whole, (WIDTH - CROP_WIDTH) / 2, (HEIGHT - CROP_HEIGHT) / 2, CROP_WIDTH, CROP_HEIGHT, 0, 0, CROP_WIDTH, CROP_HEIGHT)
                return crop
            } finally { whole.dispose() }
        } finally { frame.end() }
    }

    /** Frames left to right, top to bottom, with a thin gap; framebuffer rows are bottom-up, so the sheet is flipped on write. */
    private fun writeSheet(frames: List<Pixmap>, path: String) {
        val rows = (frames.size + COLUMNS - 1) / COLUMNS
        val gap = 4
        val sheet = Pixmap(COLUMNS * CROP_WIDTH + (COLUMNS - 1) * gap, rows * CROP_HEIGHT + (rows - 1) * gap, Pixmap.Format.RGBA8888)
        try {
            sheet.setColor(0f, 0f, 0f, 1f); sheet.fill()
            sheet.blending = Pixmap.Blending.None
            frames.forEachIndexed { index, crop ->
                val column = index % COLUMNS
                val row = rows - 1 - index / COLUMNS
                sheet.drawPixmap(crop, column * (CROP_WIDTH + gap), row * (CROP_HEIGHT + gap))
            }
            PixmapIO.writePNG(Gdx.files.absolute(path), sheet, -1, true)
        } finally { sheet.dispose() }
    }

    /**
     * A busy turn: far more routine movement than the screen plays, with visible attacks and, last, attacks on a tile
     * the viewer can't see. The screen must drop the moves and the unseen attacks, keep the visible ones with the death
     * the last one caused folded into it, and play them at fast speed. Then a batch too long to queue at all must still
     * keep its attack.
     */
    private fun checkBusyTurn() {
        val scenario = Scenario("busy", "Archer", "Warrior", ranged.first, ranged.second, VisualAttackKind.RANGED)
        Stage(scenario).use { world ->
            val game = world.game
            val archer = game.addUnit("Archer", world.rome, game.getTile(-2, 0))
            val target = game.addUnit("Warrior", world.greece, game.getTile(0, 0))
            val doomed = game.addUnit("Spearman", world.greece, game.getTile(0, 1))
            for (civ in listOf(world.rome, world.greece)) civ.cache.updateSightAndResources()
            for (tile in game.tileMap.values) tile.setExplored(world.rome, true)
            val hidden = VisualPosition(5, -2)
            val archerSample = VisualEventProjector.unit(game.gameInfo, world.view, archer, archer.currentTile)!!.unit
            val targetSample = VisualEventProjector.unit(game.gameInfo, world.view, target, target.currentTile)!!.unit
            val doomedSample = VisualEventProjector.unit(game.gameInfo, world.view, doomed, doomed.currentTile)!!.unit
            doomed.destroy()
            world.rome.viewableTiles = game.tileMap.values.filter { it.position.x != hidden.x || it.position.y != hidden.y }.toSet()
            world.showMap(-1 to 0)
            val scout = VisualUnitDescriptor(9999, "Scout", world.rome.civID)
            fun move() = VisualEvent.Move(world.nextId(), world.view, VisualProvenance(), scout,
                listOf(VisualPosition(-3, 1), VisualPosition(-2, 1), VisualPosition(-1, 1)))
            fun attack(to: VisualPosition, defender: VisualUnitDescriptor) = VisualEvent.Attack(world.nextId(), world.view,
                VisualProvenance(), VisualPosition(-2, 0), to, archerSample, defender, null, 10, VisualAttackKind.RANGED)
            val visible = 10
            val events = ArrayList<VisualEvent>()
            repeat(30) { events += move() }
            repeat(visible) { index ->
                val last = index == visible - 1
                events += attack(VisualPosition(0, if (last) 1 else 0), if (last) doomedSample else targetSample)
                if (last) events += VisualEvent.RemoveUnit(world.nextId(), world.view, VisualProvenance(), doomedSample, VisualPosition(0, 1))
                events += move()
            }
            repeat(5) { events += attack(hidden, targetSample) }
            world.nextBatch = VisualBatch(world.view, events, events.last().id.sequence, 0L)
            world.timeline.advance(1 / 60f)
            check(world.timeline.pendingCount == visible + 1) {
                "A busy turn must keep its $visible visible attacks and the death, dropping moves and unseen attacks; kept ${world.timeline.pendingCount}"
            }
            var time = 0f
            var framesAtTwo = 0
            while (world.timeline.pendingCount > 0 && time < 20f) {
                world.timeline.advance(1 / 60f); time += 1 / 60f
                if (world.timeline.pendingCount == 2) framesAtTwo++
            }
            check(world.timeline.pendingCount == 0) { "The kept attacks must all play" }
            // Between attacks the count reads 2 for a frame: the last attack and its death, waiting. Folded, the death
            // leaves with the attack; played on its own, it would hold the count at 2 for the whole last attack.
            check(framesAtTwo <= 1) { "The death must play as part of the attack that caused it, but the count stayed at 2 for $framesAtTwo frames" }
            check(time > visible * .45f * .9f && time < visible * .6f) { "Kept attacks must play at fast speed, took $time s" }

            val flood = ArrayList<VisualEvent>()
            repeat(WorldVisualTimeline.MAX_QUEUED_EVENTS + 6) { flood += move() }
            flood += attack(VisualPosition(0, 0), targetSample)
            world.nextBatch = VisualBatch(world.view, flood, flood.last().id.sequence, 0L)
            world.timeline.advance(1 / 60f)
            check(world.timeline.pendingCount == 1) { "A batch too long to queue must still keep its attack, kept ${world.timeline.pendingCount}" }
        }
    }

    private const val MAX_ATTACKS = WorldVisualTimeline.MAX_KEPT_ATTACKS

    /** Every family has at least one scenario, so no weapon goes unseen by the check. */
    init {
        check(WeaponFamily.entries.all { family -> scenarios.any { it.name == family.sheetName() || it.name.startsWith(family.sheetName() + "-") } })
    }

    private fun WeaponFamily.sheetName() = name.lowercase().replace('_', '-')
}
