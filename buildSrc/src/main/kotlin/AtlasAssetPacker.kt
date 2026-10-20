@file:Suppress("InvalidPackageDeclaration")
package com.unciv.build

import com.badlogic.gdx.utils.JsonReader
import com.badlogic.gdx.utils.JsonValue
import com.badlogic.gdx.utils.JsonWriter
import java.awt.image.BufferedImage
import java.io.File
import java.security.MessageDigest
import javax.imageio.ImageIO

/** Opt-in authoring pipeline. Animation PNGs never enter Images.* or Atlases.json. */
object AtlasAssetPacker {
    private val facings = listOf("E", "NE", "NW", "W", "SW", "SE")
    private val states = listOf("IDLE", "MOVE", "ATTACK", "HIT", "DEFEAT", "WORK")
    private val minimumFrames = mapOf("IDLE" to 4, "MOVE" to 8, "ATTACK" to 8,
        "HIT" to 4, "DEFEAT" to 8, "WORK" to 8)
    private val rulesets = listOf("Civ V - Vanilla", "Civ V - Gods & Kings")
    private val directions = listOf("TopRight", "BottomRight", "Bottom", "BottomLeft", "TopLeft", "Top")
    private val approvedLimits = mapOf("desktopBytes" to 128L * 1024 * 1024,
        "otherBytes" to 32L * 1024 * 1024, "pendingUploadBytes" to 8L * 1024 * 1024)

    @JvmStatic fun execute(rootPath: String, command: String, requireComplete: Boolean) {
        val root = File(rootPath).canonicalFile
        require(File(root, "android/assets/jsons").isDirectory) { "Not an Unciv checkout" }
        when (command) {
            "coverage" -> writeCoverage(root, requireComplete)
            "validate" -> {
                val authored = readAuthoring(root)
                checkProvenance(root, authored)
                checkCoverage(root, requiredCoverage(root), requireComplete)
                println("Atlas manifest structurally valid; ${authored.units.size} authored units, ${authored.clips.size} authored clips. Coverage is separate.")
            }
            "pack" -> {
                val authored = readAuthoring(root)
                checkProvenance(root, authored)
                checkCoverage(root, requiredCoverage(root), requireComplete)
                pack(root, authored, requireComplete)
            }
            else -> error("Use validate, pack or coverage")
        }
    }

    private data class Source(val path: String, val x: Int, val y: Int, val width: Int, val height: Int)
    private data class SourceMask(val id: String, val source: Source)
    private data class Frame(val duration: Int, val color: Source, val masks: List<SourceMask>, val shadow: Source?)
    private data class UnitSpec(val json: JsonValue, val name: String, val id: String,
        val width: Int, val height: Int, val anchorX: Int, val anchorY: Int, val margin: Int)
    private data class Clip(val unit: UnitSpec, val state: String, val facing: String,
        val fallback: String, val looping: Boolean, val mirror: String?, val masks: List<String>,
        val hasShadow: Boolean, val frames: List<Frame>) {
        val key get() = "${unit.name}|$state|$facing"
        val id get() = "${unit.id}-${state.lowercase()}-${facing.lowercase()}"
    }
    private data class Authoring(val json: JsonValue, val units: List<UnitSpec>, val clips: List<Clip>,
        val maxPage: Int, val padding: Int, val mipmaps: Boolean, val budgets: Map<String, Long>)

    private fun readAuthoring(root: File): Authoring {
        val json = read(root, "art/atlas/manifest.json")
        fields(json, "schemaVersion", "packing", "budgets", "units", "clips")
        require(json.getInt("schemaVersion") == 1) { "Unsupported authoring schema" }
        val packing = json.require("packing")
        fields(packing, "maxPageSize", "padding", "duplicateEdges", "mipmaps", "filterMin", "filterMag")
        val maxPage = packing.getInt("maxPageSize")
        val padding = packing.getInt("padding")
        val mipmaps = packing.getBoolean("mipmaps")
        require(maxPage in 128..1024 && powerOfTwo(maxPage) && padding in 1..16 &&
            packing.getBoolean("duplicateEdges") && packing.getString("filterMag") == "Linear" &&
            packing.getString("filterMin") == if (mipmaps) "MipMapLinearLinear" else "Linear") { "Invalid packing settings" }
        val budgetsJson = json.require("budgets")
        fields(budgetsJson, *approvedLimits.keys.toTypedArray())
        val budgets = approvedLimits.mapValues { (key, limit) ->
            budgetsJson.getLong(key).also { require(it in 1..limit) { "Unapproved $key budget" } }
        }
        val names = requiredUnits(root)
        val units = json.require("units").toList().map { u ->
            fields(u, "unitName", "assetId", "canvasWidth", "canvasHeight", "anchorX", "anchorY",
                "staticLayers", "maskKeys", "shadowKey", "clippingMargin", "uniqueUnitIdentity", "applicableStates")
            val name = u.getString("unitName")
            val definition = requireNotNull(names[name]) { "Unknown exact built-in unit $name" }
            val id = u.getString("assetId")
            val width = u.getInt("canvasWidth"); val height = u.getInt("canvasHeight")
            require(id == slug(name) && ((width == 128 && height == 128) || (width == 192 && height == 192))) { "Invalid unit id/canvas" }
            val anchorX = u.getInt("anchorX"); val anchorY = u.getInt("anchorY"); val margin = u.getInt("clippingMargin")
            require(anchorX in 0 until width && anchorY in 0 until height && margin in 0 until minOf(width, height) / 2) { "Invalid anchor/margin $name" }
            val staticLayers = u.require("staticLayers").strings()
            val maskKeys = u.require("maskKeys").strings()
            val shadowKey = u.getString("shadowKey", null)
            require(staticLayers.isNotEmpty() && staticLayers.distinct() == staticLayers && maskKeys.distinct() == maskKeys) { "Invalid static layers" }
            for (key in staticLayers + maskKeys + listOfNotNull(shadowKey)) exactImage(root, key)
            require(u.require("applicableStates").strings() == applicableStates(definition)) { "Incomplete applicable states $name" }
            val unique = u.getString("uniqueUnitIdentity", null)
            require(unique == if (definition.has("uniqueTo")) name else null) { "Unique units must retain distinct designs" }
            UnitSpec(u, name, id, width, height, anchorX, anchorY, margin)
        }
        require(units.map { it.name }.distinct().size == units.size && units.map { it.id }.distinct().size == units.size) { "Duplicate unit" }
        val byName = units.associateBy { it.name }
        val clips = json.require("clips").toList().map { c ->
            fields(c, "unitName", "state", "facing", "staticFallbackKey", "looping", "mirroredFacing", "maskIds", "hasShadow", "frames")
            val unit = requireNotNull(byName[c.getString("unitName")]) { "Clip has no authored exact unit" }
            val state = c.getString("state"); val facing = c.getString("facing")
            require(state in unit.json.require("applicableStates").strings() && facing in facings) { "Invalid clip identity" }
            val fallback = c.getString("staticFallbackKey")
            require(fallback in unit.json.require("staticLayers").strings()) { "Unknown static fallback" }
            val masks = c.require("maskIds").strings()
            require(masks.distinct() == masks && masks.all { it.matches(Regex("[a-z0-9][a-z0-9-]*")) }) { "Invalid masks" }
            val hasShadow = c.getBoolean("hasShadow")
            val mirror = c.getString("mirroredFacing", null)
            require(mirror == null || mirror == mirrorOf(facing)) { "Only explicitly declared horizontal facing mirrors are supported" }
            val frames = c.require("frames").toList().map { f ->
                fields(f, "durationMs", "color", "masks", "shadow")
                require(f.getInt("durationMs") > 0) { "Frame durations must be positive" }
                val color = source(root, unit, f.require("color"))
                val frameMasks = f.require("masks").toList().map { m ->
                    fields(m, "id", "source"); SourceMask(m.getString("id"), source(root, unit, m.require("source")))
                }
                val shadow = f.get("shadow")?.takeUnless { it.isNull }?.let { source(root, unit, it) }
                require(frameMasks.map { it.id } == masks && (shadow != null) == hasShadow) { "Incomplete/reordered masks or shadow" }
                require((frameMasks.map { it.source } + listOfNotNull(shadow)).all {
                    it.width == color.width && it.height == color.height && it.x == color.x && it.y == color.y
                }) { "Source sheets must share frame bounds and order" }
                val sheetDimensions = pngDimensions(exactFile(root, color.path))
                require((frameMasks.map { it.source } + listOfNotNull(shadow)).all {
                    pngDimensions(exactFile(root, it.path)) == sheetDimensions
                }) { "Mask/shadow sheet dimensions must match the color sheet" }
                Frame(f.getInt("durationMs"), color, frameMasks, shadow)
            }
            require(if (mirror == null) frames.size >= minimumFrames.getValue(state) else frames.isEmpty()) { "Invalid state-specific frame count" }
            Clip(unit, state, facing, fallback, c.getBoolean("looping"), mirror, masks, hasShadow, frames)
        }
        require(clips.map { it.key }.distinct().size == clips.size) { "Duplicate clip" }
        val byKey = clips.associateBy { it.key }
        for (c in clips) if (c.mirror != null) {
            val source = requireNotNull(byKey["${c.unit.name}|${c.state}|${c.mirror}"]) { "Missing mirrored source" }
            require(source.mirror == null && source.masks == c.masks && source.hasShadow == c.hasShadow &&
                source.fallback == c.fallback && source.looping == c.looping) { "Invalid chained/incompatible mirror" }
        }
        return Authoring(json, units, clips, maxPage, padding, mipmaps, budgets)
    }

    private fun source(root: File, unit: UnitSpec, json: JsonValue): Source {
        fields(json, "path", "x", "y", "width", "height")
        val path = json.getString("path")
        require(path.startsWith("art/atlas/units/${unit.id}/frames/") && path.endsWith(".png")) { "Source must be an original PNG in the unit frames directory" }
        val file = exactFile(root, path)
        val x = json.getInt("x"); val y = json.getInt("y")
        val width = json.getInt("width"); val height = json.getInt("height")
        val dimensions = pngDimensions(file)
        require(x >= 0 && y >= 0 && width == unit.width && height == unit.height &&
            x.toLong() + width <= dimensions.first && y.toLong() + height <= dimensions.second) { "Invalid source bounds $path" }
        return Source(path, x, y, width, height)
    }

    private fun checkProvenance(root: File, authored: Authoring): Map<String, String> {
        val json = read(root, "art/atlas/provenance.json")
        fields(json, "schemaVersion", "entries")
        require(json.getInt("schemaVersion") == 1) { "Unsupported provenance schema" }
        val hashes = linkedMapOf<String, String>()
        val ids = hashSetOf<String>()
        for (entry in json.require("entries")) {
            fields(entry, "assetId", "creator", "source", "promptPath", "rights", "sourceHash", "outputHashes")
            require(ids.add(entry.getString("assetId")) && entry.getString("creator").isNotBlank() &&
                entry.getString("source").isNotBlank() && entry.getString("rights") in listOf("original", "original-generated") &&
                entry.getString("sourceHash").matches(Regex("[a-f0-9]{64}"))) { "Incomplete/non-original provenance" }
            exactFile(root, entry.getString("promptPath"))
            require(sha256(exactFile(root, entry.getString("source"))) == entry.getString("sourceHash")) { "Original generation/source record hash mismatch" }
            for (hash in entry.require("outputHashes")) {
                val actual = sha256(exactFile(root, hash.name))
                require(actual == hash.asString() && (hashes[hash.name] == null || hashes[hash.name] == actual)) { "Provenance hash mismatch ${hash.name}" }
                hashes[hash.name] = actual
            }
        }
        val sources = authored.units.flatMap { u ->
            (u.json.require("staticLayers").strings() + u.json.require("maskKeys").strings() +
                listOfNotNull(u.json.getString("shadowKey", null))).map { exactImage(root, it).relativeTo(root).invariantSeparatorsPath }
        } + authored.clips.flatMap { c -> c.frames.flatMap { f ->
            listOf(f.color.path) + f.masks.map { it.source.path } + listOfNotNull(f.shadow?.path)
        } }
        require(sources.all { it in hashes }) { "Every shipped source requires original provenance with verified output hashes" }
        require(authored.units.all { it.id in ids }) { "Authored unit missing provenance identity" }
        val units = authored.units.associateBy { it.name }
        for (unit in authored.units.filter { it.json.getString("uniqueUnitIdentity", null) != null }) {
            val definition = requiredUnits(root).getValue(unit.name)
            val replaced = units[definition.getString("replaces", "")] ?: continue
            val ownHashes = unit.json.require("staticLayers").strings().map { sha256(exactImage(root, it)) }
            val replacementHashes = replaced.json.require("staticLayers").strings().map { sha256(exactImage(root, it)) }
            require(ownHashes != replacementHashes) { "Unique unit ${unit.name} reuses its replacement's static design" }
        }
        return hashes
    }

    private data class Trimmed(val frame: Frame, val x: Int, val y: Int, val width: Int, val height: Int,
        val images: List<BufferedImage>)
    private data class Placement(val frameIndex: Int, val x: Int, val y: Int)
    private data class PackedPage(val placements: MutableList<Placement> = arrayListOf(),
        var cursorX: Int = 0, var cursorY: Int = 0, var rowHeight: Int = 0,
        var usedWidth: Int = 0, var usedHeight: Int = 0)

    private fun trim(root: File, unit: UnitSpec, f: Frame): Trimmed {
        fun crop(s: Source): BufferedImage {
            val sheet = requireNotNull(ImageIO.read(exactFile(root, s.path))) { "Invalid PNG ${s.path}" }
            try {
                val copy = BufferedImage(s.width, s.height, BufferedImage.TYPE_INT_ARGB)
                copy.setRGB(0, 0, s.width, s.height, sheet.getRGB(s.x, s.y, s.width, s.height, null, 0, s.width), 0, s.width)
                return copy
            } finally { sheet.flush() }
        }
        val images = (listOf(f.color) + f.masks.map { it.source } + listOfNotNull(f.shadow)).map(::crop)
        var left = unit.width; var top = unit.height; var right = -1; var bottom = -1
        var colorVisible = false
        for ((layerIndex, image) in images.withIndex()) for (y in 0 until image.height) for (x in 0 until image.width) {
            if (image.getRGB(x, y) ushr 24 == 0) continue
            if (layerIndex == 0) colorVisible = true
            left = minOf(left, x); top = minOf(top, y); right = maxOf(right, x); bottom = maxOf(bottom, y)
        }
        require(colorVisible) { "An authored color frame must not be transparent" }
        left = maxOf(0, left - unit.margin); top = maxOf(0, top - unit.margin)
        right = minOf(unit.width - 1, right + unit.margin); bottom = minOf(unit.height - 1, bottom + unit.margin)
        return Trimmed(f, left, top, right - left + 1, bottom - top + 1, images)
    }

    private fun pack(root: File, authored: Authoring, coverageComplete: Boolean) {
        val destination = File(root, "android/assets/atlas-animation")
        require(!destination.isSymbolicLink()) { "Animation output cannot be a symlink" }
        val scratch = File(root, "android/assets/atlas-animation.new")
        require(!scratch.exists()) { "Previous interrupted packing output exists: $scratch" }
        scratch.mkdirs()
        val pages = arrayListOf<Map<String, Any?>>()
        val clips = linkedMapOf<String, Map<String, Any?>>()
        val appearance = File(root, "android/assets/atlas-appearance.json")
        require(!appearance.isSymbolicLink()) { "Appearance metadata cannot be a symlink" }
        fun writeAppearance(ready: Boolean) = write(appearance, linkedMapOf(
            "schemaVersion" to 1, "coverageComplete" to ready,
            "skin" to "Atlas", "tileSet" to "AtlasTerrain", "unitSet" to "AtlasUnits"))
        try {
            // Candidate packing cannot retain an earlier release's opt-in readiness marker.
            writeAppearance(false)
            for (clip in authored.clips.filter { it.mirror == null }.sortedBy { it.key }) {
                val frames = clip.frames.map { trim(root, clip.unit, it) }
                try {
                    require(frames.map { pixelHash(it.images.first()) }.distinct().size >= 2) { "Repeated static frames do not complete animation ${clip.key}" }
                    val packed = arrayListOf<PackedPage>()
                    var page = PackedPage().also { packed += it }
                    // Ordered shelf placement, never rotated. Every mask/shadow uses the same placements.
                    for ((index, frame) in frames.withIndex()) {
                        val w = frame.width + authored.padding * 2; val h = frame.height + authored.padding * 2
                        require(w <= authored.maxPage && h <= authored.maxPage) { "Frame exceeds page cap" }
                        if (page.cursorX + w > authored.maxPage) {
                            page.cursorX = 0; page.cursorY += page.rowHeight; page.rowHeight = 0
                        }
                        if (page.cursorY + h > authored.maxPage) page = PackedPage().also { packed += it }
                        page.placements += Placement(index, page.cursorX + authored.padding, page.cursorY + authored.padding)
                        page.cursorX += w; page.rowHeight = maxOf(page.rowHeight, h)
                        page.usedWidth = maxOf(page.usedWidth, page.cursorX); page.usedHeight = maxOf(page.usedHeight, page.cursorY + h)
                    }
                    val frameOutput = arrayOfNulls<Map<String, Any?>>(frames.size)
                    val layerIds = listOf("color") + clip.masks.map { "mask:$it" } + if (clip.hasShadow) listOf("shadow") else emptyList()
                    val clipPages = arrayListOf<Map<String, Any?>>()
                    for ((pageIndex, p) in packed.withIndex()) {
                        val width = nextPowerOfTwo(p.usedWidth); val height = nextPowerOfTwo(p.usedHeight)
                        val names = layerIds.map { layer -> "${clip.id}-${pageIndex.toString().padStart(3, '0')}-${layer.replace(':', '-')}" }
                        for ((layerIndex, layer) in layerIds.withIndex()) {
                            val name = names[layerIndex]
                            val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
                            try {
                                for (placement in p.placements) drawPadded(image, frames[placement.frameIndex],
                                    layerIndex, placement.x, placement.y, authored.padding)
                                require(ImageIO.write(image, "png", File(scratch, "$name.png"))) { "PNG writer unavailable" }
                            } finally { image.flush() }
                            clipPages += linkedMapOf("name" to name, "fileName" to "$name.png", "layerId" to layer,
                                "width" to width, "height" to height, "mipmaps" to authored.mipmaps,
                                "decodedBytes" to width.toLong() * height * 4, "gpuBytes" to gpuBytes(width, height, authored.mipmaps))
                        }
                        for (placement in p.placements) {
                            val frame = frames[placement.frameIndex]
                            val regionName = "${clip.id}-${placement.frameIndex.toString().padStart(4, '0')}"
                            fun region(layer: Int) = linkedMapOf("pageName" to names[layer], "regionName" to regionName,
                                "x" to placement.x, "y" to placement.y, "width" to frame.width, "height" to frame.height)
                            frameOutput[placement.frameIndex] = linkedMapOf("color" to region(0),
                                "masks" to clip.masks.mapIndexed { i, id -> linkedMapOf("id" to id, "region" to region(i + 1)) },
                                "shadow" to if (clip.hasShadow) region(names.lastIndex) else null,
                                "durationMs" to frame.frame.duration, "originalWidth" to clip.unit.width,
                                "originalHeight" to clip.unit.height, "trimX" to frame.x, "trimY" to frame.y,
                                "anchorX" to clip.unit.anchorX - frame.x, "anchorY" to clip.unit.anchorY - frame.y)
                        }
                    }
                    val decoded = clipPages.sumOf { it.getValue("decodedBytes") as Long }
                    val staging = clipPages.maxOf { it.getValue("decodedBytes") as Long }
                    val gpu = clipPages.sumOf { it.getValue("gpuBytes") as Long }
                    require(staging <= authored.budgets.getValue("pendingUploadBytes") &&
                        gpu <= minOf(authored.budgets.getValue("desktopBytes"), authored.budgets.getValue("otherBytes"))) { "Clip exceeds approved memory budgets ${clip.key}" }
                    pages += clipPages
                    clips[clip.key] = clipOutput(clip, frameOutput.map { requireNotNull(it) },
                        clipPages.map { it.getValue("name") as String }.sorted(), decoded, staging, gpu)
                } finally { frames.forEach { f -> f.images.forEach { it.flush() } } }
            }
            for (clip in authored.clips.filter { it.mirror != null }.sortedBy { it.key }) {
                val source = clips.getValue("${clip.unit.name}|${clip.state}|${clip.mirror}")
                clips[clip.key] = LinkedHashMap(source).apply { put("facing", clip.facing); put("mirroredFacing", clip.mirror) }
            }
            val runtime = linkedMapOf("schemaVersion" to 1, "packing" to authored.json.require("packing"),
                "budgets" to authored.json.require("budgets"), "units" to authored.units.sortedBy { it.name }.map { it.json },
                "pages" to pages.sortedBy { it.getValue("name") as String }, "clips" to clips.toSortedMap().values.toList())
            write(File(scratch, "manifest.json"), runtime)
            write(File(scratch, "packing-report.json"), linkedMapOf("schemaVersion" to 1,
                "sourceManifestHash" to sha256(File(root, "art/atlas/manifest.json")), "pageCount" to pages.size,
                "clipCount" to clips.size, "unitCount" to authored.units.size, "coverageComplete" to coverageComplete,
                "outputs" to scratch.listFiles()!!.sortedBy { it.name }.associate { it.name to sha256(it) }))
            // Replace only this pipeline's output. Fail rather than deleting an unrelated directory.
            if (destination.exists()) {
                val oldManifest = File(destination, "manifest.json")
                require(oldManifest.isFile) { "Refusing to replace unowned animation output" }
                val old = JsonReader().parse(oldManifest.readText())
                val owned = old.require("pages").map { it.getString("fileName") }.toSet() + setOf("manifest.json", "packing-report.json")
                require(destination.listFiles()!!.all { it.isFile && it.name in owned && !it.isSymbolicLink() }) { "Unowned files in animation output" }
                require(destination.deleteRecursively()) { "Could not replace animation output" }
            }
            require(scratch.renameTo(destination)) { "Could not install packed source output" }
            writeAppearance(coverageComplete)
            println("Packed ${clips.size} clips / ${pages.size} separate animation pages. Production coverage is not certified by packing.")
        } catch (ex: Throwable) {
            scratch.deleteRecursively()
            throw ex
        }
    }

    private fun clipOutput(c: Clip, frames: List<Map<String, Any?>>, pages: List<String>, decoded: Long, staging: Long, gpu: Long) =
        linkedMapOf<String, Any?>("unitName" to c.unit.name, "state" to c.state, "facing" to c.facing,
            "frames" to frames, "anchorX" to c.unit.anchorX, "anchorY" to c.unit.anchorY,
            "staticFallbackKey" to c.fallback, "pageNames" to pages, "mirroredFacing" to c.mirror,
            "looping" to c.looping, "maskIds" to c.masks, "hasShadow" to c.hasShadow,
            "decodedBytes" to decoded, "decodedStagingBytes" to staging, "gpuBytes" to gpu)

    private fun drawPadded(target: BufferedImage, frame: Trimmed, layer: Int, x: Int, y: Int, padding: Int) {
        val source = frame.images[layer]
        for (dy in -padding until frame.height + padding) for (dx in -padding until frame.width + padding) {
            val sx = frame.x + dx.coerceIn(0, frame.width - 1)
            val sy = frame.y + dy.coerceIn(0, frame.height - 1)
            target.setRGB(x + dx, y + dy, source.getRGB(sx, sy))
        }
    }

    private fun requiredUnits(root: File): Map<String, JsonValue> {
        val union = linkedMapOf<String, JsonValue>()
        for (ruleset in rulesets) for (unit in read(root, "android/assets/jsons/$ruleset/Units.json")) {
            val name = unit.getString("name")
            val prior = union[name]
            if (prior == null) union[name] = unit
            else {
                // Merge capabilities across rulesets, retaining the exact unique identity.
                val mergedStates = (applicableStates(prior) + applicableStates(unit)).distinct().sortedBy { states.indexOf(it) }
                val copy = JsonReader().parse(unit.toJson(JsonWriter.OutputType.json))
                copy.addChild("atlasApplicableStates", value(mergedStates)); union[name] = copy
            }
        }
        require(union.size == 127) { "Built-in unit inventory changed: ${union.size}; revise the approved coverage contract" }
        require(union.keys.map(::slug).distinct().size == union.size) { "Unit asset id collision" }
        return union.toSortedMap()
    }

    private fun applicableStates(unit: JsonValue): List<String> {
        unit.get("atlasApplicableStates")?.let { return it.strings() }
        val result = mutableListOf("IDLE", "MOVE", "HIT", "DEFEAT")
        if (unit.getInt("strength", 0) > 0 || unit.getInt("rangedStrength", 0) > 0) result += "ATTACK"
        if (unit.get("uniques")?.strings()?.any {
            it.startsWith("Can build ") || it.startsWith("Can instantly construct a ")
        } == true) result += "WORK"
        return result.sortedBy { states.indexOf(it) }
    }

    private fun requiredCoverage(root: File): List<Map<String, Any?>> {
        val rows = linkedMapOf<String, Map<String, Any?>>()
        fun row(name: String, category: String, key: String, source: String, ruleset: String,
            required: Boolean = true, details: Map<String, Any?> = emptyMap()) {
            val id = "$category|$key"
            val oldRulesets = rows[id]?.get("rulesets") as? List<*> ?: emptyList<Any>()
            rows[id] = linkedMapOf<String, Any?>("id" to id, "rulesetKey" to name, "category" to category,
                "assetKey" to key, "rulesets" to (oldRulesets + ruleset).distinct().sortedBy { it.toString() },
                "intendedSource" to source, "required" to required, "reviewState" to "missing", "evidence" to emptyList<Any>()).apply { putAll(details) }
        }
        val unitUnion = requiredUnits(root)
        for (ruleset in rulesets) {
            val directory = "android/assets/jsons/$ruleset"
            for (unit in read(root, "$directory/Units.json")) {
                val name = unit.getString("name"); val id = slug(name)
                val details = linkedMapOf<String, Any?>("uniqueTo" to unit.getString("uniqueTo", null),
                    "replaces" to unit.getString("replaces", null), "uniqueUnitIdentity" to if (unit.has("uniqueTo")) name else null)
                row(name, "unit-static", "TileSets/AtlasUnits/Units/$name", "android/Images.AtlasUnits/TileSets/AtlasUnits/Units/$name.png", ruleset, details = details)
                for (state in applicableStates(unitUnion.getValue(name))) for (facing in facings)
                    row(name, "unit-clip", "$name|$state|$facing", "art/atlas/units/$id/frames/${state.lowercase()}-${facing.lowercase()}.png", ruleset,
                        details = details + mapOf("unitName" to name, "state" to state, "facing" to facing, "minimumFrames" to minimumFrames.getValue(state)))
            }
            val terrains = read(root, "$directory/Terrains.json").toList()
            for (terrain in terrains) {
                val name = terrain.getString("name")
                val category = when (terrain.getString("type")) { "NaturalWonder" -> "natural-wonder"; "TerrainFeature" -> "terrain-feature"; else -> "terrain" }
                row(name, category, "TileSets/AtlasTerrain/Tiles/$name", "android/Images.AtlasTerrain/TileSets/AtlasTerrain/Tiles/$name.png", ruleset)
            }
            for (resource in read(root, "$directory/TileResources.json")) {
                val name = resource.getString("name")
                row(name, "resource", "TileSets/AtlasTerrain/Tiles/$name", "android/Images.AtlasTerrain/TileSets/AtlasTerrain/Tiles/$name.png", ruleset)
            }
            for (improvement in read(root, "$directory/TileImprovements.json")) {
                val name = improvement.getString("name")
                val action = name.startsWith("Remove ") || name in listOf("Repair", "Cancel improvement order")
                val road = name in listOf("Road", "Railroad")
                val key = "TileSets/AtlasTerrain/" + (if (road) name else "Tiles/$name")
                row(name, if (action) "improvement-action" else "improvement", key,
                    "android/Images.AtlasTerrain/$key.png", ruleset, required = !action,
                    details = if (action) mapOf("reason" to "Ruleset command; never a persistent shown improvement") else emptyMap())
                if (!action && !road) row(name, "pillaged-improvement", "$key-Pillaged",
                    "android/Images.AtlasTerrain/$key-Pillaged.png", ruleset)
            }
            val styles = read(root, "$directory/Nations.json").filter { it.getString("name") != "Spectator" }.map {
                it.getString("style", "").ifEmpty { it.getString("name") }
            }.distinct().sorted()
            for (era in read(root, "$directory/Eras.json")) {
                val name = era.getString("name")
                val variants = listOf("") + styles
                for (style in variants) {
                    val key = "TileSets/AtlasTerrain/Tiles/City center" + (if (style.isEmpty()) "" else "-$style") + "-$name"
                    row("City center|$style|$name", if (style.isEmpty()) "era-city" else "era-city-style", key,
                        "android/Images.AtlasTerrain/$key.png", ruleset, required = style.isEmpty(),
                        details = mapOf("era" to name, "style" to style,
                            "fallbackKey" to "TileSets/AtlasTerrain/Tiles/City center-$name"))
                }
            }
            // Minimal edge filters recognized by TileLayerTerrain: exact terrain names and Land/Water types.
            val filters = terrains.filter { it.getString("type") != "NaturalWonder" }.map { it.getString("name") } + listOf("Land", "Water")
            for (origin in filters.distinct().sorted()) for (destination in filters.distinct().sorted()) {
                if (origin == destination) continue
                for (direction in directions) {
                    val key = "TileSets/AtlasTerrain/Edges/Transition-$origin-$destination-$direction"
                    row("$origin|$destination|$direction", "neighbor-transition", key, "android/Images.AtlasTerrain/$key.png", ruleset,
                        details = mapOf("originFilter" to origin, "destinationFilter" to destination, "direction" to direction))
                }
            }
        }
        val shared = listOf("Hexagon", "CrosshatchHexagon", "UnexploredTile", "Crosshair", "Highlight",
            "Tiles/NaturalWonder", "Tiles/River-BottomRight", "Tiles/River-Bottom", "Tiles/River-BottomLeft")
        for (key in shared) row(key, "map-overlay", "TileSets/AtlasTerrain/$key", "android/Images.AtlasTerrain/TileSets/AtlasTerrain/$key.png", "layer-lookup")
        for (kind in listOf("Military", "Civilian")) row("EmbarkedUnit-$kind", "embarked-unit",
            "TileSets/AtlasUnits/Units/EmbarkedUnit-$kind", "android/Images.AtlasUnits/TileSets/AtlasUnits/Units/EmbarkedUnit-$kind.png", "layer-lookup")
        // Border shapes are the exact suffixes emitted by TileLayerBorders.
        for (shape in listOf("Concave", "Convex", "ConcaveConvex", "ConvexConcave")) for (side in listOf("Inner", "Outer")) {
            val key = "TileSets/AtlasTerrain/Borders/$shape$side"
            row("$shape$side", "border", key, "android/Images.AtlasTerrain/$key.png", "layer-lookup")
        }
        return rows.toSortedMap().values.toList()
    }

    private fun writeCoverage(root: File, requireComplete: Boolean) {
        val required = requiredCoverage(root)
        val existing = File(root, "art/atlas/coverage.json").takeIf { it.isFile }?.let {
            JsonReader().parse(it.readText()).require("rows").associateBy { r -> r.getString("id") }
        } ?: emptyMap()
        val rows = required.map { r -> LinkedHashMap(r).apply {
            existing[r.getValue("id")]?.let { old ->
                put("reviewState", old.getString("reviewState")); put("evidence", old.require("evidence"))
            }
        } }
        val sourceFiles = rulesets.flatMap { r -> listOf("Units", "Terrains", "TileResources", "TileImprovements", "Eras", "Nations")
            .map { "android/assets/jsons/$r/$it.json" } } + listOf(
            "core/src/com/unciv/ui/components/tilegroups/TileSetStrings.kt",
            "core/src/com/unciv/ui/components/tilegroups/layers/TileLayerTerrain.kt",
            "core/src/com/unciv/ui/components/tilegroups/layers/TileLayerBorders.kt")
        val baseline = File(root, "art/atlas/baseline-inventory.json")
        val unitNames = requiredUnits(root).keys.toList()
        val comparison = if (baseline.isFile) {
            val prior = JsonReader().parse(baseline.readText()).require("rulesets")
                .flatMap { it.require("Units").map { u -> u.getString("name") } }.distinct().sorted()
            linkedMapOf("path" to "art/atlas/baseline-inventory.json", "sha256" to sha256(baseline),
                "addedUnitNames" to unitNames.filter { it !in prior }, "removedUnitNames" to prior.filter { it !in unitNames },
                "certifiesFinalArt" to false)
        } else null
        write(File(root, "art/atlas/coverage.json"), linkedMapOf("schemaVersion" to 1,
            "status" to "Original Atlas production art awaits authoring and acceptance; legacy fallback is not coverage",
            "requiredUnitNames" to unitNames, "sourceHashes" to sourceFiles.associateWith { sha256(exactFile(root, it)) },
            "baselineInventory" to comparison, "rows" to rows))
        for ((name, unit) in requiredUnits(root)) {
            val id = slug(name); val metadata = File(root, "art/atlas/units/$id/authoring.json")
            val generated = linkedMapOf<String, Any?>("schemaVersion" to 1, "unitName" to name,
                "assetId" to id, "reviewState" to "missing", "canvasWidth" to 128, "canvasHeight" to 128,
                "anchorX" to 64, "anchorY" to 96, "clippingMargin" to 2,
                "uniqueUnitIdentity" to if (unit.has("uniqueTo")) name else null,
                "uniqueTo" to unit.getString("uniqueTo", null), "replaces" to unit.getString("replaces", null),
                "applicableStates" to applicableStates(unit), "facings" to facings,
                "minimumFrames" to applicableStates(unit).associateWith { minimumFrames.getValue(it) },
                "staticFallbackKey" to "TileSets/AtlasUnits/Units/$name", "framesDirectory" to "frames/")
            if (!metadata.exists()) write(metadata, generated)
            else {
                val existingMetadata = JsonReader().parse(metadata.readText())
                for (key in listOf("applicableStates", "facings", "minimumFrames")) {
                    existingMetadata.remove(key); existingMetadata.addChild(key, value(generated[key]))
                }
                write(metadata, existingMetadata)
            }
        }
        checkCoverage(root, required, requireComplete)
    }

    private fun checkCoverage(root: File, required: List<Map<String, Any?>>, requireComplete: Boolean) {
        val coverage = read(root, "art/atlas/coverage.json")
        require(coverage.getInt("schemaVersion") == 1) { "Unsupported coverage schema" }
        require(coverage.require("requiredUnitNames").strings() == requiredUnits(root).keys.toList()) { "Stale unit inventory; run coverage" }
        for (hash in coverage.require("sourceHashes")) require(sha256(exactFile(root, hash.name)) == hash.asString()) { "Stale inventory source ${hash.name}; run coverage" }
        val rows = coverage.require("rows").toList()
        val byId = rows.associateBy { it.getString("id") }
        require(byId.size == rows.size && byId.keys == required.map { it.getValue("id") }.toSet()) { "Stale/duplicate coverage rows; run coverage" }
        var missing = 0
        for (expected in required) {
            val row = byId.getValue(expected.getValue("id") as String)
            for ((key, expectedValue) in expected.filterKeys { it !in listOf("reviewState", "evidence") })
                require(row.require(key).toJson(JsonWriter.OutputType.json) == value(expectedValue).toJson(JsonWriter.OutputType.json)) { "Stale coverage $key in ${row.getString("id")}" }
            val state = row.getString("reviewState")
            require(state in listOf("missing", "candidate", "accepted")) { "Invalid review state" }
            val evidence = row.require("evidence").toList()
            if (state == "accepted") {
                require(evidence.isNotEmpty() && evidence.any { it.getString("kind") == "art-review" }) { "Accepted art requires recorded human/independent art review" }
                require(evidence.any { it.getString("kind") == "source" }) { "Accepted art requires source evidence" }
                if (row.getString("category") != "unit-clip") require(evidence.any {
                    it.getString("kind") == "source" && it.getString("path") == row.getString("intendedSource")
                }) { "Accepted source evidence must identify this exact asset" }
                for (entry in evidence) {
                    fields(entry, "kind", "path", "sha256", "reviewer", "note")
                    require(entry.getString("reviewer", "").isNotBlank() && entry.getString("note", "").isNotBlank()) { "Incomplete acceptance evidence" }
                    require(sha256(exactFile(root, entry.getString("path"))) == entry.getString("sha256")) { "Stale acceptance evidence" }
                }
                if (row.getString("category") != "unit-clip") exactFile(root, row.getString("intendedSource"))
            } else if (row.getBoolean("required")) missing++
        }
        if (requireComplete) {
            require(missing == 0) { "$missing required Atlas coverage rows lack accepted original art" }
            val authored = readAuthoring(root)
            val originalSources = checkProvenance(root, authored)
            for (row in rows.filter { it.getBoolean("required") && it.getString("category") != "unit-clip" }) {
                require(row.getString("intendedSource") in originalSources) { "Required static art lacks original provenance ${row.getString("id")}" }
            }
            require(authored.units.map { it.name }.toSet() == requiredUnits(root).keys) { "Incomplete authored unit manifest" }
            val requiredClips = required.filter { it["category"] == "unit-clip" }.map { it.getValue("assetKey") as String }.toSet()
            require(authored.clips.map { it.key }.toSet() == requiredClips) { "Incomplete authored clip manifest" }
            val byClip = authored.clips.associateBy { it.key }
            for (clip in authored.clips) {
                val frames = if (clip.mirror == null) clip.frames else byClip.getValue("${clip.unit.name}|${clip.state}|${clip.mirror}").frames
                val neededSources = frames.flatMap { listOf(it.color.path) + it.masks.map { m -> m.source.path } + listOfNotNull(it.shadow?.path) }.toSet()
                val evidenceSources = byId.getValue("unit-clip|${clip.key}").require("evidence")
                    .filter { it.getString("kind") == "source" }.map { it.getString("path") }.toSet()
                require(evidenceSources.containsAll(neededSources)) { "Clip acceptance lacks exact source evidence ${clip.key}" }
            }
        }
        println("Inventory: 127 exact unit names, ${rows.size} rows, $missing required rows not accepted.")
    }

    private fun exactImage(root: File, key: String): File {
        require(key.startsWith("TileSets/AtlasUnits/Units/")) { "Atlas unit layers must use AtlasUnits exact keys" }
        return exactFile(root, "android/Images.AtlasUnits/$key.png")
    }
    private fun exactFile(root: File, path: String): File {
        require(path.isNotBlank() && !File(path).isAbsolute && !path.contains('\\') &&
            path.split('/').none { it.isEmpty() || it == "." || it == ".." }) { "Unsafe source path $path" }
        var file = root
        for (part in path.split('/')) {
            require(file.list()?.contains(part) == true) { "Missing or incorrectly cased path $path" }
            file = File(file, part)
            require(!file.isSymbolicLink()) { "Source symlinks are not accepted $path" }
        }
        require(file.isFile && file.canonicalPath.startsWith(root.path + File.separator)) { "Source outside checkout $path" }
        return file
    }
    private fun File.isSymbolicLink() = java.nio.file.Files.isSymbolicLink(toPath())
    private fun pngDimensions(file: File): Pair<Int, Int> = ImageIO.createImageInputStream(file).use { input ->
        val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: error("Unreadable PNG $file")
        try { reader.input = input; reader.getWidth(0) to reader.getHeight(0) } finally { reader.dispose() }
    }
    private fun fields(json: JsonValue, vararg allowed: String) {
        require(json.isObject && json.all { it.name in allowed }) { "Unknown or invalid fields: ${json.name}" }
    }
    private fun read(root: File, path: String) = JsonReader().parse(exactFile(root, path).readText())
    private fun JsonValue.strings() = map { it.asString() }
    private fun slug(name: String) = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
    private fun mirrorOf(facing: String) = mapOf("E" to "W", "W" to "E", "NE" to "NW", "NW" to "NE", "SW" to "SE", "SE" to "SW").getValue(facing)
    private fun powerOfTwo(n: Int) = n > 0 && n and (n - 1) == 0
    private fun nextPowerOfTwo(n: Int): Int { var p = 1; while (p < n) p *= 2; return p }
    private fun gpuBytes(width: Int, height: Int, mipmaps: Boolean): Long {
        var w = width; var h = height; var bytes = w.toLong() * h * 4
        if (mipmaps) while (w > 1 || h > 1) { w = maxOf(1, w / 2); h = maxOf(1, h / 2); bytes += w.toLong() * h * 4 }
        return bytes
    }
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val bytes = ByteArray(64 * 1024); while (true) {
            val read = input.read(bytes); if (read < 0) break; digest.update(bytes, 0, read)
        } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun pixelHash(image: BufferedImage): Int = image.getRGB(0, 0, image.width, image.height, null, 0, image.width).contentHashCode()
    private fun value(any: Any?): JsonValue = when (any) {
        null -> JsonValue(JsonValue.ValueType.nullValue)
        is JsonValue -> JsonReader().parse(any.toJson(JsonWriter.OutputType.json))
        is String -> JsonValue(any)
        is Boolean -> JsonValue(any)
        is Number -> JsonValue(any.toLong())
        is Map<*, *> -> JsonValue(JsonValue.ValueType.`object`).apply { any.forEach { (k, v) -> addChild(k as String, value(v)) } }
        is Iterable<*> -> JsonValue(JsonValue.ValueType.array).apply { any.forEach { addChild(value(it)) } }
        else -> error("Unsupported JSON value ${any.javaClass}")
    }
    private fun write(file: File, any: Any?) {
        file.parentFile.mkdirs()
        file.writeText(value(any).prettyPrint(JsonWriter.OutputType.json, 100) + "\n")
    }
}
