package com.unciv.logic.agents

import com.unciv.Constants
import com.unciv.logic.GameInfo
import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.stats.Stat
import java.security.MessageDigest
import java.util.Base64

const val AGENT_OBSERVATION_VERSION = 2
const val AGENT_READ_BYTE_BUDGET = 96 * 1024

enum class MapQueryMode { KNOWN, VISIBLE }
enum class TileKnowledgeVisibility { VISIBLE, REMEMBERED }
enum class ResourceKnowledgeSource { SIGHTING, TECHNOLOGY }
enum class TileYieldContext { ACTOR_CITY, ACTOR_GENERAL }

class ObservedResource(
    var name: String = "", var amount: Int = 0,
    var updatedTurn: Int = 0, var updatedRevision: Int = 0,
    var source: ResourceKnowledgeSource = ResourceKnowledgeSource.SIGHTING
) : IsPartOfGameInfoSerialization {
    fun copy() = ObservedResource(name, amount, updatedTurn, updatedRevision, source)
    fun toWire() = linkedMapOf<String, Any?>("name" to name, "amount" to amount,
        "updatedTurn" to updatedTurn, "updatedRevision" to updatedRevision, "source" to source.name)
}

class ObservedPublicCity(
    var cityId: String = "", var name: String = "", var ownerCivId: String = "",
    var health: Int = 0, var population: Int = 0
) : IsPartOfGameInfoSerialization {
    fun copy() = ObservedPublicCity(cityId, name, ownerCivId, health, population)
    fun toWire() = linkedMapOf<String, Any?>("cityId" to cityId, "name" to name,
        "ownerCivId" to ownerCivId, "health" to health, "population" to population)
}

class ObservedTileYields(
    var context: TileYieldContext = TileYieldContext.ACTOR_GENERAL,
    var cityId: String? = null, var values: LinkedHashMap<String, Float> = LinkedHashMap()
) : IsPartOfGameInfoSerialization {
    fun copy() = ObservedTileYields(context, cityId, LinkedHashMap(values))
    fun toWire() = linkedMapOf<String, Any?>("context" to context.name, "cityId" to cityId, "values" to values)
}

class ObservedOwnWork(var improvement: String = "", var turnsRemaining: Int = 0) : IsPartOfGameInfoSerialization {
    fun copy() = ObservedOwnWork(improvement, turnsRemaining)
    fun toWire() = linkedMapOf<String, Any?>("improvement" to improvement, "turnsRemaining" to turnsRemaining)
}

class ObservedRiverEdges(
    var bottomRight: Boolean = false, var bottom: Boolean = false, var bottomLeft: Boolean = false
) : IsPartOfGameInfoSerialization {
    fun copy() = ObservedRiverEdges(bottomRight, bottom, bottomLeft)
    fun toWire() = linkedMapOf<String, Any?>("bottomRight" to bottomRight, "bottom" to bottom, "bottomLeft" to bottomLeft)
}

/** Sanitized sighting, or geometry-only discovery. No live engine objects. */
class ObservedTile(
    var x: Int = 0, var y: Int = 0,
    var visibility: TileKnowledgeVisibility = TileKnowledgeVisibility.REMEMBERED,
    var detailsKnown: Boolean = false,
    var lastSeenTurn: Int? = null, var lastSeenRevision: Int? = null,
    var terrain: String? = null, var features: ArrayList<String>? = null,
    var naturalWonder: String? = null, var river: Boolean? = null,
    var resource: ObservedResource? = null, var ownerCivId: String? = null,
    var city: ObservedPublicCity? = null,
    var improvement: String? = null, var improvementPillaged: Boolean? = null,
    var road: String? = null, var roadPillaged: Boolean? = null,
    var yields: ObservedTileYields? = null, var ownWork: ObservedOwnWork? = null,
    var land: Boolean? = null, var water: Boolean? = null, var hill: Boolean? = null,
    var coast: Boolean? = null, var freshwater: Boolean? = null, var riverEdges: ObservedRiverEdges? = null
) : IsPartOfGameInfoSerialization {
    fun copy() = ObservedTile(x, y, visibility, detailsKnown, lastSeenTurn, lastSeenRevision,
        terrain, features?.let { ArrayList(it) }, naturalWonder, river, resource?.copy(), ownerCivId,
        city?.copy(), improvement, improvementPillaged, road, roadPillaged, yields?.copy(), ownWork?.copy(), land, water, hill, coast, freshwater, riverEdges?.copy())

    fun toWire(): Map<String, Any?> = linkedMapOf(
        "x" to x, "y" to y, "visibility" to visibility.name, "detailsKnown" to detailsKnown,
        "lastSeenTurn" to lastSeenTurn, "lastSeenRevision" to lastSeenRevision,
        "terrain" to terrain, "features" to features, "naturalWonder" to naturalWonder,
        "river" to river, "resource" to resource?.toWire(), "ownerCivId" to ownerCivId,
        "city" to city?.toWire(), "improvement" to improvement, "improvementPillaged" to improvementPillaged,
        "road" to road, "roadPillaged" to roadPillaged, "yields" to yields?.toWire(), "ownWork" to ownWork?.toWire(),
        "land" to land, "water" to water, "hill" to hill, "coast" to coast, "freshwater" to freshwater,
        "riverEdges" to riverEdges?.toWire())
}

class MapQuery(
    val mode: MapQueryMode = MapQueryMode.KNOWN, val center: HexCoord? = null,
    val radius: Int? = null, val limit: Int = 64, val cursor: String? = null
)

class MapPage(
    var protocolVersion: Int = 1, var observationVersion: Int = AGENT_OBSERVATION_VERSION,
    var matchId: String = "", var timelineId: String = "", var revision: Int = 0,
    var tiles: ArrayList<ObservedTile> = ArrayList(), var nextCursor: String? = null
) {
    fun toWire(): Map<String, Any?> = linkedMapOf("protocolVersion" to protocolVersion,
        "observationVersion" to observationVersion, "matchId" to matchId, "timelineId" to timelineId,
        "revision" to revision, "tiles" to tiles.map { it.toWire() }, "nextCursor" to nextCursor)
}

class MapSummary(
    var observationVersion: Int = AGENT_OBSERVATION_VERSION, var coordinateSystem: String = "UNCIV_HEX",
    var knownTileCount: Int = 0, var visibleTileCount: Int = 0, var worldWrap: Boolean = false
)

fun normalViewableTiles(civ: Civilization): Set<Tile> = civ.cache.normalViewableTiles()

/** Caller establishes normal sight. Hidden resources are removed even from yield filter contexts. */
internal fun exportVisibleTile(game: GameInfo, civ: Civilization, tile: Tile, revision: Int): ObservedTile {
    val resource = tile.tileResource?.takeIf { civ.canSeeResource(it) }
    // The regular one-argument stats helper uses tile.getCity(), leaking foreign city bonuses.
    val ownCity = tile.getCity()?.takeIf { it.civ.civID == civ.civID }
    val sanitized = tile.cloneForObservation(ownCity)
    if (resource == null) sanitized.setTileResource(null)
    val stats = sanitized.stats.getTileStats(ownCity, civ)
    val publicCity = tile.getCity()?.takeIf { tile.isCityCenter() }
    return ObservedTile(
        tile.position.x, tile.position.y, TileKnowledgeVisibility.VISIBLE, true,
        game.turns, revision, tile.baseTerrain, ArrayList(tile.terrainFeatures), tile.naturalWonder,
        tile.isAdjacentToRiver(), resource?.let { ObservedResource(it.name, tile.resourceAmount,
            game.turns, revision, ResourceKnowledgeSource.SIGHTING) }, tile.getOwner()?.civID,
        publicCity?.let { ObservedPublicCity(it.id, it.name, it.civ.civID, it.health, it.population.population) },
        tile.improvement, tile.improvementIsPillaged, tile.roadStatus.name, tile.roadIsPillaged,
        ObservedTileYields(if (ownCity != null) TileYieldContext.ACTOR_CITY else TileYieldContext.ACTOR_GENERAL,
            ownCity?.id, LinkedHashMap<String, Float>().also { map ->
                for (stat in Stat.entries) map[stat.name] = stats[stat]
            }),
        tile.improvementInProgress?.takeIf { tile.getUnits().any { it.civ == civ && it.hasUnique(UniqueType.BuildImprovements) } }
            ?.let { ObservedOwnWork(it, tile.turnsToImprovement) },
        tile.isLand, tile.isWater, tile.isHill(), tile.getBaseTerrain().isCoast,
        tile.isAdjacentTo(Constants.freshWater, civ),
        ObservedRiverEdges(tile.hasBottomRightRiver, tile.hasBottomRiver, tile.hasBottomLeftRiver)
    )
}

fun buildMapSummary(game: GameInfo, match: AgentMatchState, actorCivId: String): MapSummary {
    val civ = game.getCivilizationOrNull(actorCivId) ?: invalidMap("Unknown civilization")
    val visible = normalViewableTiles(civ)
    return MapSummary(knownTileCount = permittedPositions(game, match, civ, visible).size,
        visibleTileCount = visible.size, worldWrap = game.tileMap.mapParameters.worldWrap)
}

internal fun permittedPositions(game: GameInfo, match: AgentMatchState, civ: Civilization, visible: Set<Tile>): Set<HexCoord> {
    val positions = HashSet<HexCoord>()
    positions.addAll(visible.map { it.position })
    positions.addAll(game.tileMap.values.asSequence().filter { it.isExploredNormally(civ) }.map { it.position })
    positions.addAll(match.mapKnowledge.tilesByCiv[civ.civID]?.values?.map { HexCoord(it.x, it.y) } ?: emptyList())
    return positions.filterTo(HashSet()) { game.tileMap.getIfTileExistsOrNull(it.x, it.y)?.position == it }
}

private fun invalidMap(message: String): Nothing = throw AgentCommandException(AgentCommandError.INVALID_ARGUMENT, message)
private fun digest(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

/** Stable canonical ordering; cursor binds actor, timeline, revision and every query argument. */
fun buildMapPage(game: GameInfo, match: AgentMatchState, actorCivId: String, query: MapQuery): MapPage {
    if (query.limit !in 1..128 || query.radius?.let { it !in 0..8 } == true)
        invalidMap("Map limit must be 1..128 and radius 0..8")
    if ((query.center == null) != (query.radius == null)) invalidMap("center and radius must be supplied together")
    val civ = game.getCivilizationOrNull(actorCivId) ?: invalidMap("Unknown civilization")
    val binding = digest(AgentJson.stringify(listOf(match.matchId, match.timelineId, actorCivId,
        query.mode.name, query.center?.x, query.center?.y, query.radius, query.limit)))
    var start = 0
    query.cursor?.let { cursor ->
        if (cursor.length > 512) invalidMap("Invalid map cursor")
        val parts = try { String(Base64.getUrlDecoder().decode(cursor), Charsets.UTF_8).split(':') }
            catch (ex: IllegalArgumentException) { invalidMap("Invalid map cursor") }
        if (parts.size != 3 || parts[0] != binding) invalidMap("Cursor does not belong to this actor, timeline or query")
        val revision = parts[1].toIntOrNull() ?: invalidMap("Invalid map cursor")
        if (revision != match.stateRevision) throw AgentCommandException(AgentCommandError.STALE_STATE,
            "Map cursor revision is stale", match.stateRevision)
        start = parts[2].toIntOrNull()?.takeIf { it >= 0 } ?: invalidMap("Invalid map cursor")
    }
    val visible = normalViewableTiles(civ)
    val positions = if (query.mode == MapQueryMode.VISIBLE) visible.map { it.position }.toSet()
        else permittedPositions(game, match, civ, visible)
    val center = query.center?.let { position ->
        val tile = game.tileMap.getIfTileExistsOrNull(position.x, position.y)
        if (tile == null || buildPermittedTile(game, match, actorCivId, position, visible) == null)
            invalidMap("Unknown map center")
        tile
    }
    val ordered = positions.asSequence().filter { position ->
        query.center == null || center != null && game.tileMap.getIfTileExistsOrNull(position.x, position.y)!!
            .aerialDistanceTo(center) <= query.radius!!
    }.sortedWith(compareBy<HexCoord> { it.x }.thenBy { it.y }).toList()
    if (start > ordered.size) invalidMap("Cursor is outside this map result")
    fun cursorFor(offset: Int) = Base64.getUrlEncoder().withoutPadding().encodeToString(
        "$binding:${match.stateRevision}:$offset".toByteArray(Charsets.UTF_8))
    val page = MapPage(matchId = match.matchId, timelineId = match.timelineId, revision = match.stateRevision)
    var index = start
    // Account for the MCP text-content string, including its second JSON escaping pass.
    while (index < ordered.size && page.tiles.size < query.limit) {
        val tile = buildPermittedTile(game, match, actorCivId, ordered[index], visible)!!
        page.tiles.add(tile)
        page.nextCursor = if (index + 1 < ordered.size) cursorFor(index + 1) else null
        if (!AgentReadBudget.fits(page.toWire())) {
            page.tiles.removeAt(page.tiles.lastIndex)
            if (page.tiles.isEmpty()) invalidMap("A map entry exceeds the response byte budget")
            break
        }
        index++
    }
    page.nextCursor = if (index < ordered.size) cursorFor(index) else null
    if (!AgentReadBudget.fits(page.toWire()))
        invalidMap("Map envelope exceeds the response byte budget")
    return page
}
