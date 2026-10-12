package com.unciv.logic.agents

import com.unciv.json.json
import com.unciv.logic.GameInfo
import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.tile.Tile

/** Durable, per-civilization sanitized snapshots; no shared engine references. */
class AgentMapKnowledge(
    var tilesByCiv: HashMap<String, HashMap<String, ObservedTile>> = HashMap()
) : IsPartOfGameInfoSerialization {
    fun deepCopy() = AgentMapKnowledge(HashMap(tilesByCiv.mapValues { (_, tiles) ->
        HashMap(tiles.mapValues { it.value.copy() })
    }))
    fun put(civId: String, tile: ObservedTile) {
        tilesByCiv.getOrPut(civId) { HashMap() }[key(HexCoord(tile.x, tile.y))] = tile.copy()
    }
    fun get(civId: String, position: HexCoord) = tilesByCiv[civId]?.get(key(position))
    companion object { fun key(position: HexCoord) = "${position.x},${position.y}" }
}

fun buildPermittedTile(game: GameInfo, match: AgentMatchState, actorCivId: String, position: HexCoord): ObservedTile? {
    val civ = game.getCivilizationOrNull(actorCivId) ?: return null
    return buildPermittedTile(game, match, actorCivId, position, normalViewableTiles(civ))
}

internal fun buildPermittedTile(
    game: GameInfo, match: AgentMatchState, actorCivId: String, position: HexCoord, visible: Set<Tile>
): ObservedTile? {
    val civ = game.getCivilizationOrNull(actorCivId) ?: return null
    val tile = game.tileMap.getIfTileExistsOrNull(position.x, position.y) ?: return null
    if (tile in visible) return exportVisibleTile(game, civ, tile, match.stateRevision)
    match.mapKnowledge.get(actorCivId, tile.position)?.let { known ->
        return known.copy().also { it.visibility = TileKnowledgeVisibility.REMEMBERED }
    }
    // Legacy/map-trade exploration has no recoverable detail provenance.
    if (tile.isExploredNormally(civ)) return ObservedTile(tile.position.x, tile.position.y)
    return null
}

/** Candidate-only sighting journal. Flushing is part of the same checkpoint as the game action. */
class AgentMapSightings {
    private val sightings = AgentMapKnowledge()
    private val partialResources = HashMap<String, HashMap<String, ObservedResource>>()

    /** Called at every normal visibility update, including intermediate movement steps. */
    fun recordVisible(civ: Civilization) {
        if (civ.isSpectator()) return
        for (tile in normalViewableTiles(civ))
            sightings.put(civ.civID, exportVisibleTile(civ.gameInfo, civ, tile, 0))
    }

    /** Final sweep captures stationary inactive observers and explicit partial discoveries. */
    fun captureAll(game: GameInfo, match: AgentMatchState) {
        for (civId in match.controllers.keys) {
            val civ = game.getCivilizationOrNull(civId) ?: continue
            if (civ.isSpectator()) continue
            recordVisible(civ)
            val finalVisible = normalViewableTiles(civ)
            val known = match.mapKnowledge.tilesByCiv[civId]
            val fresh = sightings.tilesByCiv.getOrPut(civId) { HashMap() }
            for (tile in game.tileMap.values) {
                val key = AgentMapKnowledge.key(tile.position)
                if (!tile.isExploredNormally(civ) && known?.containsKey(key) != true) continue
                if (tile in finalVisible) continue
                if (key !in fresh && known?.containsKey(key) != true)
                    fresh[key] = ObservedTile(tile.position.x, tile.position.y)
                // Technology reveals only this fact; never refresh unseen terrain/borders/yields/work.
                val resource = tile.tileResource ?: continue
                if (resource.revealedBy == null || !civ.canSeeResource(resource)) continue
                if ((fresh[key] ?: known?.get(key))?.resource != null) continue
                partialResources.getOrPut(civId) { HashMap() }[key] = ObservedResource(
                    resource.name, tile.resourceAmount, game.turns, 0, ResourceKnowledgeSource.TECHNOLOGY)
            }
        }
    }

    fun flushInto(match: AgentMatchState, revision: Int) {
        for ((civId, tiles) in sightings.tilesByCiv) {
            if (civId !in match.controllers) continue
            for (tile in tiles.values) {
                val snapshot = tile.copy()
                if (snapshot.detailsKnown) {
                    snapshot.lastSeenRevision = revision
                    snapshot.resource?.updatedRevision = revision
                }
                match.mapKnowledge.put(civId, snapshot)
            }
        }
        for ((civId, resources) in partialResources) {
            if (civId !in match.controllers) continue
            for ((key, resource) in resources) {
                val current = match.mapKnowledge.tilesByCiv[civId]?.get(key) ?: continue
                current.resource = resource.copy().also { it.updatedRevision = revision }
            }
        }
    }
}

/** Historical serializers omitted default version 1; never interpret omission as the v2 prototype default. */
fun migrateAgentMatchStateJson(matchJson: String, expectedVersion: Int? = null): AgentMatchState {
    val raw = com.badlogic.gdx.utils.JsonReader().parse(matchJson)
    require(raw.isObject) { "Agent match metadata must be an object" }
    val version = rawAgentVersion(raw, "formatVersion")
    require(expectedVersion == null || expectedVersion == version) { "Agent bundle/state versions do not match" }
    require(version in 1..AGENT_MATCH_FORMAT_VERSION) { "Unsupported agent match format version $version" }
    val state = json().fromJson(AgentMatchState::class.java, matchJson)
    state.formatVersion = version
    if (version == 1) {
        // v1 exploration survives in the world; complete historical sightings cannot be invented.
        state.mapKnowledge = AgentMapKnowledge()
    }
    if (version < 3) {
        val receipts = state.diplomaticMessageReceipts.values + state.currentTurnReceipts
        val serializer = json()
        for (message in state.diplomaticMessages) {
            if (message.acknowledgedTurnSerial == null) continue
            val fingerprint = "diplomacy.acknowledgeMessage|" + serializer.toJson(arrayListOf(
                message.targetCivId, message.messageId, message.acknowledgementText))
            message.acknowledgedRevision = receipts.filter {
                it.outcome == "applied" && it.actorCivId == message.targetCivId &&
                    it.resultJson == message.messageId && it.requestFingerprint == fingerprint
            }.minOfOrNull { it.afterRevision }
        }
    }
    state.formatVersion = AGENT_MATCH_FORMAT_VERSION
    state.validateFormat()
    return state
}

/** Explicit matching v1/v2->v3 migration, before strict current-version validation. */
fun migrateAgentBundle(bundle: AgentBundle): AgentBundle {
    require(bundle.bundleVersion in 1..AGENT_MATCH_FORMAT_VERSION) {
        "Unsupported agent bundle version ${bundle.bundleVersion}"
    }
    val state = migrateAgentMatchStateJson(bundle.matchJson, bundle.bundleVersion)
    val serializer = json().apply { setUsePrototypes(false) }
    return AgentBundle(bundle.gameJson, serializer.toJson(state), AGENT_MATCH_FORMAT_VERSION)
}

/** Raw bundle and state defaults are resolved before parsing under today's prototypes. */
fun migrateAgentBundleJson(bundleJson: String): AgentBundle {
    val raw = com.badlogic.gdx.utils.JsonReader().parse(bundleJson)
    require(raw.isObject) { "Agent bundle must be an object" }
    val bundle = json().fromJson(AgentBundle::class.java, bundleJson)
    bundle.bundleVersion = rawAgentVersion(raw, "bundleVersion")
    return migrateAgentBundle(bundle)
}

private fun rawAgentVersion(raw: com.badlogic.gdx.utils.JsonValue, field: String): Int {
    val value = raw.get(field) ?: return 1
    require(value.isNumber) { "Invalid agent $field" }
    val number = value.asDouble()
    require(number.isFinite() && number in 1.0..AGENT_MATCH_FORMAT_VERSION.toDouble()
        && number == value.asInt().toDouble()) { "Unsupported agent $field $number" }
    return value.asInt()
}
