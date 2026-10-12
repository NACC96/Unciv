package com.unciv.dev

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.badlogic.gdx.graphics.glutils.FrameBuffer
import com.badlogic.gdx.graphics.glutils.HdpiMode
import com.badlogic.gdx.graphics.glutils.HdpiUtils
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.utils.JsonWriter
import com.unciv.ui.screens.basescreen.BaseScreen
import java.nio.file.Path
import java.io.StringWriter

/** Synchronous GPU captures work even when macOS doesn't poll a hidden GLFW window. */
object AtlasRenderCheck {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 5) { "Expected fixture ID, output directory, width, height, font scale" }
        val id = args[0]
        require(id in AtlasFixtureCatalog.ids()) { "Unknown Atlas fixture: $id" }
        val output = Path.of(args[1]); require(output.isAbsolute)
        val width = args[2].toInt(); val height = args[3].toInt(); val scale = args[4].toFloat()
        val config = Lwjgl3ApplicationConfiguration().apply {
            setTitle("Unciv Atlas isolated capture: $id")
            setWindowedMode(width, height)
            setHdpiMode(HdpiMode.Pixels)
            setInitialVisible(false)
            disableAudio(true)
        }
        System.setProperty("unciv.atlas.fixtureRoot", output.resolve("fixtures").toString())
        Lwjgl3Application(object : ApplicationAdapter() {
            override fun create() {
                var screen: BaseScreen? = null
                var frame: FrameBuffer? = null
                var code = 1
                try {
                    HdpiUtils.setMode(HdpiMode.Pixels)
                    screen = AtlasFixtureCatalog.create(id, width, height, scale)
                    val scope = AtlasFixtureCatalog.dataFor(screen)
                    check(scope.dataRoot.startsWith(scope.fixtureRoot))
                    check(screen.stage.root.children.size > 0) { "$id has no production actors" }
                    frame = FrameBuffer(Pixmap.Format.RGBA8888, width, height, false)
                    var buttonStates: List<Map<String, Any?>> = emptyList()
                    var worldActions: List<Map<String, Any?>> = emptyList()
                    // Only drain setup errors. Errors from layout, drawing or readback fail the run.
                    while (Gdx.gl.glGetError() != GL20.GL_NO_ERROR) { }
                    repeat(30) { screen.stage.root.act(1f / 60f) }
                    frame.begin()
                    try {
                        draw(screen, 0f)
                        val pixels = Pixmap.createFromFrameBuffer(0, 0, width, height)
                        try {
                            check(pixels.width == width && pixels.height == height)
                            check(Gdx.gl.glGetError() == GL20.GL_NO_ERROR) { "Capture readback GL error" }
                            buttonStates = buttonStateEvidence(screen, pixels)
                            worldActions = worldActionEvidence(screen, pixels)
                            PixmapIO.writePNG(Gdx.files.absolute(output.resolve("$id-${width}x$height-${args[4]}.png").toString()), pixels, -1, true)
                        } finally { pixels.dispose() }
                    } finally { frame.end() }
                    check(scope.providerCalls == 0) { "Synthetic rendering invoked a provider" }
                    val actors = screen.stage.root.atlasDescendants().toList()
                    val labels = actors.filterIsInstance<Label>()
                    check(labels.isNotEmpty() || id == "terrain") { "Fixture lacks real content" }
                    val representative = mapOf("world" to "WorldMapHolder", "activity" to "AgentActivityFeed",
                        "messages" to "AgentMessagesPanel", "overview" to "CivilizationOverviewPanel",
                        "nation-control" to "AgentNationSettingsPanel", "terrain" to "WorldMapHolder",
                        "units" to "UnitTable", "setup" to "PickerPane")
                    representative[id]?.let { expected ->
                        check(actors.any { it.javaClass.simpleName == expected && it.isVisible }) {
                            "$id has no visible production $expected"
                        }
                    }
                    writeJson(output.resolve("$id-${width}x$height-${args[4]}.json"), linkedMapOf(
                        "fixture" to id, "sourceHash" to System.getProperty("unciv.atlas.sourceHash"),
                        "diffHash" to System.getProperty("unciv.atlas.diffHash"), "width" to width, "height" to height,
                        "harnessHash" to System.getProperty("unciv.atlas.harnessHash"),
                        "fontScale" to scale, "screenClass" to screen.javaClass.name,
                        "logicalWidth" to screen.stage.width, "logicalHeight" to screen.stage.height,
                        "viewportClass" to screen.stage.viewport.javaClass.name,
                        "actorClasses" to actors.map { it.javaClass.name }.distinct().sorted(),
                        "actorCount" to actors.size, "labelCount" to labels.size,
                        "providerCalls" to scope.providerCalls, "dataRoot" to scope.dataRoot.toString(),
                        "resolvedRoots" to scope.resolvedRoots(),
                        "fixtureSeed" to AtlasFixtureCatalog.SEED,
                        "buttonStateEvidence" to buttonStates,
                        "worldActionEvidence" to worldActions,
                        "worldLayerBounds" to if (id.startsWith("world") && id != "world-legacy") worldLayerBounds(screen) else null,
                        "worldHud" to if (id.startsWith("world") && id != "world-legacy") worldHudEvidence(screen, id == "world") else null,
                        "status" to if (id.startsWith("world") && id != "world-legacy") "synthetic Atlas world migration evidence; native input and installation pending" else if (id == "world-legacy") "synthetic Minimal compatibility evidence; native input and installation pending" else if (id in setOf("activity", "messages", "overview", "nation-control")) "synthetic Atlas panel migration evidence; final matrix and native input pending" else "baseline capture; migration and interactions remain pending"))
                    println("ATLAS_CAPTURE_OK $id ${width}x$height ${args[4]} actors=${actors.size} providerCalls=0")
                    code = 0
                } catch (failure: Throwable) { failure.printStackTrace() }
                finally {
                    runCatching {
                        frame?.dispose()
                        screen?.let { AtlasFixtureCatalog.dispose(it) }
                    }.onFailure { it.printStackTrace(); code = 1 }
                    System.out.flush(); System.err.flush()
                    kotlin.system.exitProcess(code)
                }
            }
        }, config)
    }

    private fun worldHudEvidence(screen: BaseScreen, defaultLayout: Boolean): Map<String, Any?> {
        val occupied = screen.stage.root.children.filter { it.isVisible &&
            (it.name?.startsWith("Atlas") == true || it.javaClass.simpleName in setOf("WorldScreenTopBar", "AgentActivityFeed", "AgentMatchControlTable")) }
        val rectangles = occupied.map { com.badlogic.gdx.math.Rectangle(it.x, it.y, it.width, it.height) }
        val area = com.unciv.ui.screens.worldscreen.WorldHudLayout.unionArea(rectangles)
        val fraction = 1f - area / (screen.stage.width * screen.stage.height)
        if (defaultLayout && screen.stage.width == 1440f && screen.stage.height == 900f) check(fraction >= .45f) { "Actual HUD leaves only $fraction map visible" }
        return linkedMapOf("appearance" to com.unciv.UncivGame.Current.settings.skin,
            "occupiedUnionArea" to area, "unobstructedMapFraction" to fraction,
            "actors" to occupied.map { linkedMapOf("name" to (it.name ?: it.javaClass.simpleName),
                "x" to it.x, "y" to it.y, "width" to it.width, "height" to it.height) })
    }

    private fun worldLayerBounds(screen: BaseScreen): List<Map<String, Any?>> =
        screen.stage.root.atlasDescendants().filterIsInstance<com.unciv.ui.screens.worldscreen.minimap.MapOverlayToggleButton>()
            .filter { button -> generateSequence(button as com.badlogic.gdx.scenes.scene2d.Actor?) { it.parent }.all { it.isVisible } }
            .map { button ->
                val p = button.localToStageCoordinates(Vector2())
                for (host in generateSequence(button.parent) { it.parent }.filterIsInstance<com.badlogic.gdx.scenes.scene2d.ui.ScrollPane>()) {
                    val h = host.localToStageCoordinates(Vector2())
                    check(p.x >= h.x - .5f && p.y >= h.y - .5f && p.x + button.width <= h.x + host.width + .5f && p.y + button.height <= h.y + host.height + .5f) {
                        "Map layer ${button.name} is clipped by ${host.name}"
                    }
                }
                linkedMapOf("name" to button.name, "checked" to button.isChecked,
                    "bounds" to listOf(p.x, p.y, button.width, button.height))
            }.toList()

    private fun worldActionEvidence(screen: BaseScreen, pixels: Pixmap): List<Map<String, Any?>> =
        screen.stage.root.atlasDescendants().filterIsInstance<com.unciv.ui.images.IconTextButton>()
            .filter { it is com.unciv.ui.screens.worldscreen.status.NextTurnButton || it.name?.startsWith("AtlasUnitActionEvidence:") == true }
            .filter { button -> generateSequence(button as com.badlogic.gdx.scenes.scene2d.Actor?) { it.parent }.all { it.isVisible } }
            .mapNotNull { button ->
                val position = button.localToStageCoordinates(Vector2())
                if (position.x < 0 || position.y < 0 || position.x + button.width > pixels.width || position.y + button.height > pixels.height) return@mapNotNull null
                // Ignore scrolled/clipped children: a framebuffer point outside their host
                // belongs to another surface and is not a valid button contrast sample.
                val scrollParents = generateSequence(button.parent) { it.parent }.filterIsInstance<com.badlogic.gdx.scenes.scene2d.ui.ScrollPane>()
                val clipped = scrollParents.any { parent ->
                    val p = parent.localToStageCoordinates(Vector2())
                    position.x < p.x - .5f || position.y < p.y - .5f || position.x + button.width > p.x + parent.width + .5f || position.y + button.height > p.y + parent.height + .5f
                }
                check(!clipped || button !is com.unciv.ui.screens.worldscreen.status.NextTurnButton) {
                    "Turn action ${button.name} bounds $position ${button.width}x${button.height} are clipped by its host"
                }
                if (clipped) return@mapNotNull null
                val foreground = button.label.style.fontColor.cpy().mul(button.label.color)
                val points = listOf(Vector2(position.x + 8f, position.y + 8f), Vector2(position.x + button.width - 8f, position.y + 8f))
                fun luminance(c: Color): Double {
                    fun channel(v: Float) = if (v <= .04045f) v / 12.92 else Math.pow((v + .055) / 1.055, 2.4)
                    return .2126 * channel(c.r) + .7152 * channel(c.g) + .0722 * channel(c.b)
                }
                val evidence = button.userObject as? AtlasTurnFixtures.Evidence
                linkedMapOf("name" to button.name, "caption" to button.label.text.toString(),
                    "selectedAction" to evidence?.selected, "syntheticInput" to evidence?.input,
                    "route" to evidence?.route, "disabled" to button.isDisabled,
                    "bounds" to listOf(position.x, position.y, button.width, button.height),
                    "icon" to button.iconCell.actor?.let { icon ->
                        check(icon.width > 0f && icon.height > 0f) { "${button.name} icon collapsed to ${icon.width}x${icon.height}" }
                        val p = icon.localToStageCoordinates(Vector2())
                        linkedMapOf("path" to icon.name, "tint" to icon.color.toString(),
                            "bounds" to listOf(p.x, p.y, icon.width, icon.height))
                    },
                    "foreground" to foreground.toString(), "backgroundSamples" to points.map { point ->
                        val background = Color(pixels.getPixel(point.x.toInt(), point.y.toInt()))
                        val composite = foreground.cpy().apply {
                            r = r * a + background.r * (1 - a); g = g * a + background.g * (1 - a); b = b * a + background.b * (1 - a); a = 1f
                        }
                        val fg = luminance(composite); val bg = luminance(background)
                        linkedMapOf("pixel" to listOf(point.x.toInt(), point.y.toInt()), "rgba" to background.toString(),
                            "contrast" to (maxOf(fg, bg) + .05) / (minOf(fg, bg) + .05))
                    })
            }.toList()

    /** Controlled native-state evidence, separate from keyboard/mouse interaction tests.
     * Sample empty interior padding, including its actual framebuffer compositing, before PNG flip. */
    private fun buttonStateEvidence(screen: BaseScreen, pixels: Pixmap): List<Map<String, Any?>> =
        screen.stage.root.atlasDescendants().filterIsInstance<AtlasStateButton>().map { button ->
            val position = button.localToStageCoordinates(Vector2())
            val labelPosition = button.label.localToStageCoordinates(Vector2())
            val color = button.resolvedFontColor()
            val points = listOf(Vector2(position.x + 6f, position.y + button.height / 2f),
                Vector2(position.x + button.width - 6f, position.y + button.height / 2f),
                Vector2(position.x + button.width - 6f, position.y + button.height / 2f + 4f))
            linkedMapOf("name" to button.name, "state" to button.preview.name,
                "evidenceKind" to "controlled native state selectors; no keyboard/mouse interaction",
                "bounds" to listOf(position.x, position.y, button.width, button.height),
                "labelBounds" to listOf(labelPosition.x, labelPosition.y, button.label.width, button.label.height),
                "resolvedNativeForeground" to listOf(color.r, color.g, color.b, color.a),
                "backgroundCompositeSamples" to points.map { point ->
                    check(point.x >= 0 && point.x < pixels.width && point.y >= 0 && point.y < pixels.height) {
                        "State control ${button.name} is outside the capture"
                    }
                    val sample = Color(pixels.getPixel(point.x.toInt(), point.y.toInt()))
                    mapOf("stagePixel" to listOf(point.x.toInt(), point.y.toInt()),
                        "rgba" to listOf(sample.r, sample.g, sample.b, sample.a))
                },
                "over" to button.isOver, "pressed" to button.isPressed,
                "focused" to button.hasKeyboardFocus(), "checked" to button.isChecked,
                "disabled" to button.isDisabled)
        }.toList()

    internal fun draw(screen: BaseScreen, delta: Float) {
        val stage = screen.stage
        // GLFW's Retina drawable can be larger than the requested evidence viewport.
        // Apply physical capture pixels explicitly for default-framebuffer traces too.
        Gdx.gl.glViewport(0, 0, stage.viewport.screenWidth, stage.viewport.screenHeight)
        Gdx.gl.glClearColor(0.07f, 0.1f, 0.11f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        // UncivStage.act/draw swallow exceptions by design. Evidence must propagate them.
        stage.root.act(delta)
        stage.camera.update()
        stage.batch.projectionMatrix = stage.camera.combined
        stage.batch.begin()
        try { stage.root.draw(stage.batch, 1f) } finally { stage.batch.end() }
        check(Gdx.gl.glGetError() == GL20.GL_NO_ERROR) { "Atlas draw leaked a GL error" }
    }

    internal fun writeJson(path: Path, value: Map<String, Any?>) {
        val output = StringWriter()
        val writer = JsonWriter(output).apply { setOutputType(JsonWriter.OutputType.json) }
        writeValue(writer, value)
        writer.close()
        Gdx.files.absolute(path.toString()).writeString(output.toString() + "\n", false)
    }

    private fun writeValue(writer: JsonWriter, value: Any?) {
        when (value) {
            is Map<*, *> -> {
                writer.`object`()
                value.forEach { (key, child) -> writer.name(key.toString()); writeValue(writer, child) }
                writer.pop()
            }
            is Iterable<*> -> {
                writer.array()
                value.forEach { writeValue(writer, it) }
                writer.pop()
            }
            else -> writer.value(value)
        }
    }
}
