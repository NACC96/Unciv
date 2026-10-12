package com.unciv.ui.images.atlas

import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.utils.Json
import java.util.Collections

/** Ruleset identity, always the untranslated, case-sensitive unit name. */
typealias ArtUnitId = String

enum class HexFacing { E, NE, NW, W, SW, SE;
    fun horizontalMirror() = when (this) {
        E -> W; W -> E; NE -> NW; NW -> NE; SW -> SE; SE -> SW
    }
}

enum class UnitVisualState { IDLE, MOVE, ATTACK, HIT, DEFEAT, WORK }

data class ClipKey(val unitName: ArtUnitId, val state: UnitVisualState, val facing: HexFacing)

/** PNG coordinates use a top-left origin. Textures belong to the cache, never to this metadata. */
data class FrameRegion(val pageName: String, val regionName: String,
    val x: Int, val y: Int, val width: Int, val height: Int)

data class FrameMask(val id: String, val region: FrameRegion)

/** Anchor is relative to the trimmed image and may lie outside its opaque pixels. */
data class FrameSpec(val color: FrameRegion, val masks: List<FrameMask>, val shadow: FrameRegion?,
    val durationMs: Int, val originalWidth: Int, val originalHeight: Int,
    val trimX: Int, val trimY: Int, val anchorX: Int, val anchorY: Int)

data class PageSpec(val name: String, val fileName: String, val layerId: String,
    val width: Int, val height: Int, val mipmaps: Boolean,
    val decodedBytes: Long, val gpuBytes: Long)

data class UnitArtSpec(val unitName: ArtUnitId, val assetId: String,
    val canvasWidth: Int, val canvasHeight: Int, val anchorX: Int, val anchorY: Int,
    val staticLayers: List<String>, val maskKeys: List<String>, val shadowKey: String?,
    val clippingMargin: Int, val uniqueUnitIdentity: String?,
    val applicableStates: Set<UnitVisualState>)

data class ClipSpec(val key: ClipKey, val frames: List<FrameSpec>,
    val anchorX: Int, val anchorY: Int, val staticFallbackKey: String,
    val pageNames: List<String>, val mirroredFacing: HexFacing?, val looping: Boolean,
    val decodedBytes: Long, val decodedStagingBytes: Long, val gpuBytes: Long) {
    val durationMs: Long get() = frames.sumOf { it.durationMs.toLong() }
}

data class AtlasArtBudgets(val desktopBytes: Long = 128L * 1024 * 1024,
    val otherBytes: Long = 32L * 1024 * 1024, val pendingUploadBytes: Long = 8L * 1024 * 1024)

/** A pinned clip view. close must release its pin exactly once; only the cache disposes pages. */
interface ClipHandle : AutoCloseable {
    val clip: ClipSpec
    /** Resolve only a declared region of this pinned clip; returned textures remain cache-owned. */
    fun region(region: FrameRegion): TextureRegion
    override fun close()
}

/** Sparse manifest: absent built-in art and ordinary mod units return null, without name guessing. */
class AtlasArtManifest private constructor(
    val schemaVersion: Int, val budgets: AtlasArtBudgets,
    val pages: List<PageSpec>, val units: List<UnitArtSpec>, val clips: List<ClipSpec>
) {
    private val unitsByName = units.associateBy { it.unitName }
    private val clipsByKey = clips.associateBy { it.key }
    private val pagesByName = pages.associateBy { it.name }
    fun findUnit(name: String): UnitArtSpec? = unitsByName[name]
    fun findClip(key: ClipKey): ClipSpec? = clipsByKey[key]
    fun findPage(name: String): PageSpec? = pagesByName[name]

    companion object {
        const val RUNTIME_PATH = "atlas-animation/manifest.json"
        const val MAX_PAGE_SIZE = 1024

        fun read(file: FileHandle): AtlasArtManifest = parse(file.readString("UTF-8"))

        /** JSON DTOs are mutable only during parsing; all published collections are defensive copies. */
        fun parse(text: String): AtlasArtManifest {
            val dto = Json().apply { setIgnoreUnknownFields(false) }
                .fromJson(ManifestDto::class.java, text)
            require(dto.schemaVersion == 1) { "Unsupported Atlas schema ${dto.schemaVersion}" }
            val budgets = AtlasArtBudgets(dto.budgets.desktopBytes, dto.budgets.otherBytes,
                dto.budgets.pendingUploadBytes)
            require(budgets.desktopBytes in 1..128L * 1024 * 1024 &&
                budgets.otherBytes in 1..32L * 1024 * 1024 &&
                budgets.pendingUploadBytes in 1..8L * 1024 * 1024) { "Atlas budgets exceed approved limits" }
            require(dto.packing.maxPageSize in 1..MAX_PAGE_SIZE && dto.packing.padding >= 1 &&
                dto.packing.duplicateEdges && dto.packing.filterMag == "Linear" &&
                dto.packing.filterMin == if (dto.packing.mipmaps) "MipMapLinearLinear" else "Linear") {
                "Invalid Atlas packing/filter contract"
            }
            val pages = dto.pages.map { p ->
                require(p.name.matches(Regex("[a-z0-9][a-z0-9_-]*")) && p.fileName == "${p.name}.png") {
                    "Invalid page identity ${p.name}"
                }
                require(p.width in 1..dto.packing.maxPageSize && p.height in 1..dto.packing.maxPageSize &&
                    isPowerOfTwo(p.width) && isPowerOfTwo(p.height)) { "Invalid page bounds ${p.name}" }
                require(p.layerId == "color" || p.layerId == "shadow" || p.layerId.startsWith("mask:")) {
                    "Invalid page layer ${p.layerId}"
                }
                require(p.mipmaps == dto.packing.mipmaps && p.decodedBytes == p.width.toLong() * p.height * 4 &&
                    p.gpuBytes == rgbaGpuBytes(p.width, p.height, p.mipmaps)) { "Invalid page accounting ${p.name}" }
                PageSpec(p.name, p.fileName, p.layerId, p.width, p.height, p.mipmaps, p.decodedBytes, p.gpuBytes)
            }
            require(pages.map { it.name }.distinct().size == pages.size) { "Duplicate page" }
            val byPage = pages.associateBy { it.name }
            val units = dto.units.map { u ->
                require(u.unitName.isNotBlank() && u.assetId.matches(Regex("[a-z0-9][a-z0-9-]*"))) { "Invalid unit identity" }
                require((u.canvasWidth == 128 && u.canvasHeight == 128) ||
                    (u.canvasWidth == 192 && u.canvasHeight == 192)) { "Unsupported authoring canvas" }
                require(u.anchorX in 0 until u.canvasWidth && u.anchorY in 0 until u.canvasHeight &&
                    u.clippingMargin in 0 until minOf(u.canvasWidth, u.canvasHeight) / 2) { "Invalid unit anchor/margin" }
                require(u.staticLayers.isNotEmpty() && u.staticLayers.all(::validImageKey) &&
                    u.maskKeys.all(::validImageKey) && (u.shadowKey == null || validImageKey(u.shadowKey!!))) {
                    "Unit requires exact static fallback keys"
                }
                require(u.uniqueUnitIdentity == null || u.uniqueUnitIdentity == u.unitName) { "Unique art must retain its own identity" }
                val states = u.applicableStates.map(UnitVisualState::valueOf).toSet()
                require(states.isNotEmpty()) { "Unit has no applicable states" }
                UnitArtSpec(u.unitName, u.assetId, u.canvasWidth, u.canvasHeight, u.anchorX, u.anchorY,
                    frozen(u.staticLayers), frozen(u.maskKeys), u.shadowKey, u.clippingMargin,
                    u.uniqueUnitIdentity, Collections.unmodifiableSet(states))
            }
            require(units.map { it.unitName }.distinct().size == units.size &&
                units.map { it.assetId }.distinct().size == units.size) { "Duplicate unit identity" }
            val byUnit = units.associateBy { it.unitName }
            val clips = dto.clips.map { c ->
                val key = ClipKey(c.unitName, UnitVisualState.valueOf(c.state), HexFacing.valueOf(c.facing))
                val unit = requireNotNull(byUnit[key.unitName]) { "Clip has no exact unit ${key.unitName}" }
                require(key.state in unit.applicableStates && c.staticFallbackKey in unit.staticLayers &&
                    c.anchorX == unit.anchorX && c.anchorY == unit.anchorY) { "Clip disagrees with unit contract" }
                val mirrored = c.mirroredFacing?.let(HexFacing::valueOf)
                require(mirrored == null || mirrored == key.facing.horizontalMirror()) { "Invalid facing mirror" }
                val frames = c.frames.map { f ->
                    fun region(r: RegionDto, layer: String): FrameRegion {
                        val p = requireNotNull(byPage[r.pageName]) { "Unknown page ${r.pageName}" }
                        require(p.layerId == layer && r.regionName.isNotBlank() && r.x >= 0 && r.y >= 0 &&
                            r.width > 0 && r.height > 0 && r.x.toLong() + r.width <= p.width &&
                            r.y.toLong() + r.height <= p.height) { "Invalid frame region ${r.regionName}" }
                        return FrameRegion(r.pageName, r.regionName, r.x, r.y, r.width, r.height)
                    }
                    require(f.durationMs > 0 && f.originalWidth == unit.canvasWidth &&
                        f.originalHeight == unit.canvasHeight && f.trimX >= 0 && f.trimY >= 0 &&
                        f.anchorX == c.anchorX - f.trimX && f.anchorY == c.anchorY - f.trimY) { "Invalid duration/anchor" }
                    val color = region(f.color, "color")
                    require(f.trimX + color.width <= f.originalWidth && f.trimY + color.height <= f.originalHeight) { "Invalid trim" }
                    val masks = f.masks.map { FrameMask(it.id, region(it.region, "mask:${it.id}")) }
                    require(masks.map { it.id }.distinct().size == masks.size && masks.map { it.id } == c.maskIds) { "Incomplete/reordered masks" }
                    val shadow = f.shadow?.let { region(it, "shadow") }
                    require((shadow != null) == c.hasShadow) { "Incomplete shadow" }
                    require((masks.map { it.region } + listOfNotNull(shadow)).all {
                        it.x == color.x && it.y == color.y && it.width == color.width && it.height == color.height &&
                            it.regionName == color.regionName &&
                            byPage.getValue(it.pageName).width == byPage.getValue(color.pageName).width &&
                            byPage.getValue(it.pageName).height == byPage.getValue(color.pageName).height
                    }) { "Layer dimensions/region order must match" }
                    FrameSpec(color, frozen(masks), shadow, f.durationMs, f.originalWidth, f.originalHeight,
                        f.trimX, f.trimY, f.anchorX, f.anchorY)
                }
                require(frames.size >= 2) { "A clip must visibly animate using multiple authored frames" }
                val used = frames.flatMap { listOf(it.color.pageName) + it.masks.map { m -> m.region.pageName } +
                    listOfNotNull(it.shadow?.pageName) }.distinct().sorted()
                require(c.pageNames == used) { "Clip page list is incomplete or unordered" }
                val decoded = used.sumOf { byPage.getValue(it).decodedBytes }
                val gpu = used.sumOf { byPage.getValue(it).gpuBytes }
                val staging = used.maxOfOrNull { byPage.getValue(it).decodedBytes } ?: 0L
                require(c.decodedBytes == decoded && c.decodedStagingBytes == staging && c.gpuBytes == gpu &&
                    staging <= budgets.pendingUploadBytes && gpu <= minOf(budgets.desktopBytes, budgets.otherBytes)) {
                    "Clip exceeds staging/cache budget or has invalid accounting"
                }
                ClipSpec(key, frozen(frames), c.anchorX, c.anchorY, c.staticFallbackKey,
                    frozen(used), mirrored, c.looping, decoded, staging, gpu)
            }
            require(clips.map { it.key }.distinct().size == clips.size) { "Duplicate clip key" }
            val byClip = clips.associateBy { it.key }
            for (c in clips) if (c.mirroredFacing != null) {
                val source = requireNotNull(byClip[ClipKey(c.key.unitName, c.key.state, c.mirroredFacing)]) { "Missing mirror source" }
                require(source.mirroredFacing == null && source.frames == c.frames &&
                    source.looping == c.looping && source.staticFallbackKey == c.staticFallbackKey) { "Invalid chained/different mirror" }
            }
            return AtlasArtManifest(1, budgets, frozen(pages), frozen(units), frozen(clips))
        }

        fun rgbaGpuBytes(width: Int, height: Int, mipmaps: Boolean): Long {
            var w = width; var h = height; var bytes = w.toLong() * h * 4
            if (mipmaps) while (w > 1 || h > 1) {
                w = maxOf(1, w / 2); h = maxOf(1, h / 2); bytes += w.toLong() * h * 4
            }
            return bytes
        }
        private fun isPowerOfTwo(value: Int) = value > 0 && value and (value - 1) == 0
        private fun validImageKey(key: String) = key.isNotBlank() && !key.startsWith('/') &&
            !key.contains('\\') && key.split('/').none { it == ".." || it.isEmpty() }
        private fun <T> frozen(values: Collection<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
    }
}

// DTOs deliberately do not escape parse(). No textures, gameplay state or durable events are serialized.
private class ManifestDto {
    var schemaVersion = 0; var budgets = BudgetsDto(); var packing = PackingDto()
    var pages = ArrayList<PageDto>(); var units = ArrayList<UnitDto>(); var clips = ArrayList<ClipDto>()
}
private class BudgetsDto { var desktopBytes = 0L; var otherBytes = 0L; var pendingUploadBytes = 0L }
private class PackingDto {
    var maxPageSize = 0; var padding = 0; var duplicateEdges = false; var mipmaps = false
    var filterMin = ""; var filterMag = ""
}
private class PageDto {
    var name = ""; var fileName = ""; var layerId = ""; var width = 0; var height = 0
    var mipmaps = false; var decodedBytes = 0L; var gpuBytes = 0L
}
private class UnitDto {
    var unitName = ""; var assetId = ""; var canvasWidth = 0; var canvasHeight = 0; var anchorX = 0; var anchorY = 0
    var staticLayers = ArrayList<String>(); var maskKeys = ArrayList<String>(); var shadowKey: String? = null
    var clippingMargin = 0; var uniqueUnitIdentity: String? = null; var applicableStates = ArrayList<String>()
}
private class ClipDto {
    var unitName = ""; var state = ""; var facing = ""; var frames = ArrayList<FrameDto>()
    var anchorX = 0; var anchorY = 0; var staticFallbackKey = ""; var pageNames = ArrayList<String>()
    var mirroredFacing: String? = null; var looping = false; var decodedBytes = 0L; var decodedStagingBytes = 0L; var gpuBytes = 0L
    var maskIds = ArrayList<String>(); var hasShadow = false
}
private class FrameDto {
    var color = RegionDto(); var masks = ArrayList<MaskDto>(); var shadow: RegionDto? = null
    var durationMs = 0; var originalWidth = 0; var originalHeight = 0; var trimX = 0; var trimY = 0
    var anchorX = 0; var anchorY = 0
}
private class MaskDto { var id = ""; var region = RegionDto() }
private class RegionDto {
    var pageName = ""; var regionName = ""; var x = 0; var y = 0; var width = 0; var height = 0
}
