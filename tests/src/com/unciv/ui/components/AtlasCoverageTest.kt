package com.unciv.ui.components

import com.badlogic.gdx.utils.JsonReader
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.utils.viewport.ScreenViewport
import com.unciv.ui.screens.basescreen.BaseScreen
import org.mockito.Mockito
import com.unciv.UncivGame
import com.unciv.dev.AtlasFixtureCatalog
import com.unciv.dev.AtlasDataScope
import com.unciv.dev.AtlasRenderCheck
import com.unciv.dev.AtlasActionSchedule
import com.unciv.logic.files.SETTINGS_FILE_NAME
import com.unciv.testing.BaseTestRunner
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.file.Files
import java.nio.file.Path

@RunWith(BaseTestRunner::class)
class AtlasCoverageTest {
    private val repository = Path.of("../..").toAbsolutePath().normalize()

    @Test fun coverageInventoryHasAnEntryPointAndFixtureForEveryScreenFamily() {
        val inventory = repository.resolve("docs/atlas/screen-coverage.json")
        assertTrue("Atlas screen inventory is missing", Files.isRegularFile(inventory))
        val entries = JsonReader().parse(Files.readString(inventory)).get("families").toList()
        val requiredFamilies = setOf("World and agent viewing", "Agent management", "City",
            "Research and choices", "Diplomacy", "Empire information", "New game",
            "General navigation", "Supporting tools")
        val uncoveredFamilies = requiredFamilies - entries.map { it.getString("family") }.toSet()
        assertEquals(emptySet<String>(), uncoveredFamilies)
        val catalog = Class.forName("com.unciv.dev.AtlasFixtureCatalog")
        val instance = catalog.getField("INSTANCE").get(null)
        @Suppress("UNCHECKED_CAST")
        val ids = catalog.getMethod("ids").invoke(instance) as Set<String>
        val baselineIds = setOf("world", "activity", "messages", "overview", "nation-control", "terrain",
            "units", "city", "research", "policies", "religion", "promotions", "diplomacy",
            "setup", "menu", "supporting")
        assertTrue(ids.containsAll(baselineIds))
        assertTrue(ids.containsAll(setOf("supporting-atlas", "supporting-atlas-end", "supporting-atlas-states")))
        for (entry in entries) {
            assertTrue(entry.getString("status").isNotBlank())
            assertTrue(entry.get("sourcePaths").size > 0)
            assertTrue(entry.get("fixtureIds").size > 0)
            entry.get("sourcePaths").forEach {
                assertTrue("Missing entry point ${it.asString()}", Files.exists(repository.resolve(it.asString())))
            }
            entry.get("fixtureIds").forEach { assertTrue("Unregistered fixture ${it.asString()}", it.asString() in ids) }
        }
        JsonReader().parse(Files.readString(inventory)).get("additionalFlows").forEach {
            assertTrue(Files.isRegularFile(repository.resolve(it.getString("sourcePath"))))
            assertTrue(it.getString("status").startsWith("pending"))
        }
    }

    @Test fun fixturesNeverUseLiveDataOrProviders() {
        val catalogClass = runCatching { Class.forName("com.unciv.dev.AtlasFixtureCatalog") }.getOrNull()
        assertNotNull("Isolated atlas fixtures are missing", catalogClass)
        val catalog = catalogClass!!.getField("INSTANCE").get(null)
        val scope = catalogClass.getMethod("isolatedData").invoke(catalog)
        try {
            val fixtureRoot = scope.javaClass.getMethod("getFixtureRoot").invoke(scope) as Path
            val dataRoot = scope.javaClass.getMethod("getDataRoot").invoke(scope) as Path
            val saveRoot = scope.javaClass.getMethod("getSaveRoot").invoke(scope) as Path
            assertTrue(dataRoot.startsWith(fixtureRoot))
            assertTrue(saveRoot.startsWith(fixtureRoot))
            assertTrue(Files.isDirectory(dataRoot))
            assertTrue(Files.isDirectory(saveRoot))
            val fixture = (scope as AtlasDataScope).seedGame()
            assertTrue(Path.of(UncivGame.Current.files.getLocalFile(SETTINGS_FILE_NAME).path()).startsWith(fixtureRoot))
            assertTrue(Path.of(UncivGame.Current.files.getSave("synthetic").path()).startsWith(saveRoot))
            assertEquals(15, fixture.gameInfo.civilizations.count { it.isMajorCiv() && !it.isSpectator() })
            val providerCalls = scope.javaClass.getMethod("getProviderCalls").invoke(scope) as Int
            assertEquals(0, providerCalls)
        } finally {
            (scope as AutoCloseable).close()
        }
    }

    @Test fun benchmarkUsesTheSeededEarthSizeAndFixedUnitCounts() {
        AtlasFixtureCatalog.isolatedData().use { scope ->
            val fixture = scope.seedGame(large = true)
            assertEquals(13120, fixture.tileMap.values.count())
            assertEquals(164, fixture.tileMap.mapParameters.mapSize.width)
            assertEquals(80, fixture.tileMap.mapParameters.mapSize.height)
            assertEquals(20261012L, fixture.tileMap.mapParameters.seed)
            assertEquals(180, fixture.gameInfo.civilizations.sumOf { it.units.getCivUnits().count() })
            assertTrue(fixture.tileMap.values.map { it.baseTerrain }.toSet().size >= 7)
            assertEquals(0, scope.providerCalls)
            val failure = runCatching { scope.session.resume() }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertEquals(1, scope.providerCalls)
        }
    }

    @Test fun captureMetadataUsesPlainJsonValues() {
        AtlasFixtureCatalog.isolatedData().use { scope ->
            val file = scope.fixtureRoot.resolve("metadata.json")
            AtlasRenderCheck.writeJson(file, linkedMapOf("sourceHash" to "abc123", "frames" to 3,
                "gpuMs" to null, "camera" to listOf(0.25, 0.35, 1.1)))
            val metadata = JsonReader().parse(Files.readString(file))
            assertEquals("abc123", metadata.getString("sourceHash"))
            assertEquals(3, metadata.getInt("frames"))
            assertTrue(metadata.get("gpuMs").isNull)
            assertEquals(0.25, metadata.get("camera").getDouble(0), 0.0)
        }
    }

    @Test fun allResolvedStorageRootsAreIsolatedBeforeBootstrap() {
        AtlasFixtureCatalog.isolatedData().use { scope ->
            assertTrue(Path.of(Gdx.files.local("probe").path()).toAbsolutePath().startsWith(scope.fixtureRoot))
            assertTrue(Path.of(Gdx.files.external("probe").path()).toAbsolutePath().startsWith(scope.fixtureRoot))
            scope.seedGame()
            val files = UncivGame.Current.files
            assertTrue(Path.of(files.getModsFolder().path()).startsWith(scope.fixtureRoot))
            assertTrue(Path.of(files.getLocalFile("mods/synthetic/fonts/body.ttf").path()).startsWith(scope.fixtureRoot))
            assertNull(UncivGame.Current.settings.fontFamilyData.filePath)
            assertNotNull(scope.session.getActivityDirectory())
            assertTrue(Path.of(scope.session.getActivityDirectory()!!).startsWith(scope.fixtureRoot))
        }
    }

    @Test fun actionScheduleMovesOnTraceTimeWithDeterministicFinalState() {
        fun run(times: List<Double>): Pair<List<Double>, List<String>> {
            AtlasFixtureCatalog.isolatedData().use { scope ->
                val schedule = AtlasActionSchedule(scope.seedGame(large = true))
                for (time in times) schedule.executeThrough(time)
                assertEquals(0, scope.providerCalls)
                return schedule.executed.map { it.seconds } to schedule.finalState()
            }
        }
        val expectedState = listOf("0:America:Warrior:-5,-4:100:1.0", "1:America:Scout:-3,-2:100:1.0",
            "2:America:Warrior:1,0:100:1.0", "3:America:Scout:3,4:100:1.0",
            "4:America:Warrior:5,6:100:1.0", "5:America:Scout:-5,6:100:1.0")
        val fine = run(listOf(0.0, 2.9, 3.0, 7.9, 8.0, 13.0, 18.0, 23.0, 28.0, 30.0))
        val coarse = run(listOf(0.0, 14.5, 30.0))
        assertEquals(listOf(3.0, 8.0, 13.0, 18.0, 23.0, 28.0), fine.first)
        assertEquals(expectedState, fine.second)
        assertEquals(fine, coarse)
    }

    @Test fun rendererAppliesRequestedPixelViewportOnRetina() {
        com.badlogic.gdx.utils.GdxNativesLoader.load()
        val priorGl = Gdx.gl
        val gl = Mockito.mock(GL20::class.java)
        Gdx.gl = gl
        try {
            val screen = Mockito.mock(BaseScreen::class.java)
            val stage = Mockito.mock(Stage::class.java)
            Mockito.`when`(screen.stage).thenReturn(stage)
            Mockito.`when`(stage.viewport).thenReturn(ScreenViewport().apply { setScreenBounds(0, 0, 1440, 900) })
            Mockito.`when`(stage.root).thenReturn(Group().apply { isTransform = false })
            Mockito.`when`(stage.camera).thenReturn(OrthographicCamera(1440f, 900f))
            Mockito.`when`(stage.batch).thenReturn(Mockito.mock(Batch::class.java))
            AtlasRenderCheck.draw(screen, 0f)
            Mockito.verify(gl).glViewport(0, 0, 1440, 900)
        } finally { Gdx.gl = priorGl }
    }
}
