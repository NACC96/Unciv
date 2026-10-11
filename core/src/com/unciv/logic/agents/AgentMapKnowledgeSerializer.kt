package com.unciv.logic.agents

import com.badlogic.gdx.utils.Json
import com.badlogic.gdx.utils.JsonValue
import com.unciv.logic.map.HexCoord

/** Persistence-only palettes. Wire DTOs and mutable candidate isolation stay unchanged. */
class AgentMapKnowledgeSerializer : Json.Serializer<AgentMapKnowledge> {
    private class Pool<T> {
        val values = ArrayList<T>()
        private val indices = HashMap<T, Int>()
        fun id(value: T): Int = indices.getOrPut(value) { values.size.also { values.add(value) } }
    }

    override fun write(json: Json, knowledge: AgentMapKnowledge, knownType: Class<*>?) {
        val strings = Pool<String>()
        val positions = Pool<HexCoord>()
        val facts = Pool<List<Any?>>()
        val timestamps = Pool<List<Int?>>()
        val resources = Pool<List<Int>>()
        fun text(value: String?) = value?.let { strings.id(it) } ?: -1
        fun timestamp(turn: Int?, revision: Int?) =
            if (turn == null && revision == null) -1 else timestamps.id(listOf(turn, revision))
        fun fact(tile: ObservedTile): List<Any?> = listOf(
            tile.detailsKnown, text(tile.terrain), tile.features?.map { text(it) }, text(tile.naturalWonder),
            tile.river, text(tile.ownerCivId), tile.city?.let {
                listOf(text(it.cityId), text(it.name), text(it.ownerCivId), it.health, it.population)
            }, text(tile.improvement), tile.improvementPillaged, text(tile.road), tile.roadPillaged,
            tile.yields?.let { yields -> listOf(
                if (yields.context == TileYieldContext.ACTOR_CITY) 1 else 0, text(yields.cityId),
                yields.values.entries.map { listOf(text(it.key), it.value) })
            }, tile.ownWork?.let { listOf(text(it.improvement), it.turnsRemaining) },
            tile.land, tile.water, tile.hill, tile.coast, tile.freshwater,
            tile.riverEdges?.let { listOf(it.bottomRight, it.bottom, it.bottomLeft) }
        )
        // Array/list keys use structural hashes, never serialize each tile merely to intern it.
        val civs = ArrayList<List<Any?>>()
        for ((civId, tiles) in knowledge.tilesByCiv) {
            val civ = text(civId)
            val rows = ArrayList<List<Int>>()
            for (tile in tiles.values) {
                val row = arrayListOf(
                    positions.id(HexCoord(tile.x, tile.y)), facts.id(fact(tile)),
                    timestamp(tile.lastSeenTurn, tile.lastSeenRevision)
                )
                val resource = tile.resource
                if (resource != null || tile.visibility == TileKnowledgeVisibility.VISIBLE) {
                    row.add(resource?.let { resources.id(listOf(text(it.name), it.amount)) } ?: -1)
                    row.add(resource?.let { timestamp(it.updatedTurn, it.updatedRevision) } ?: -1)
                    row.add(if (resource?.source == ResourceKnowledgeSource.TECHNOLOGY) 1 else 0)
                    if (tile.visibility == TileKnowledgeVisibility.VISIBLE) row.add(1)
                }
                rows.add(row)
            }
            civs.add(listOf(civ, rows))
        }
        json.writeObjectStart()
        json.writeValue("encodingVersion", 1)
        json.writeArrayStart("strings"); for (value in strings.values) json.writeValue(value); json.writeArrayEnd()
        writeArray(json, "positions", positions.values.map { listOf(it.x, it.y) })
        writeArray(json, "facts", facts.values)
        writeArray(json, "timestamps", timestamps.values)
        writeArray(json, "resources", resources.values)
        writeArray(json, "civs", civs)
        json.writeObjectEnd()
    }

    private fun writeArray(json: Json, name: String, values: List<*>) {
        json.writeArrayStart(name)
        for (value in values) writeValue(json, value)
        json.writeArrayEnd()
    }

    private fun writeValue(json: Json, value: Any?) {
        if (value is List<*>) {
            json.writeArrayStart()
            for (item in value) writeValue(json, item)
            json.writeArrayEnd()
        } else json.writeValue(value)
    }

    override fun read(json: Json, data: JsonValue, type: Class<*>?): AgentMapKnowledge {
        require(data.isObject) { "Map knowledge must be an object" }
        if (!data.has("encodingVersion")) return readLegacy(json, data)
        require(integer(data.get("encodingVersion")) == 1) { "Unsupported map knowledge encoding version" }
        fun array(name: String): JsonValue = data.get(name)?.also {
            require(it.isArray) { "Invalid compact map $name" }
        } ?: throw IllegalArgumentException("Missing compact map $name")
        val strings = array("strings").map { require(it.isString) { "Invalid map string" }; it.asString() }
        val positions = array("positions").map {
            requireArray(it, 2); HexCoord(integer(it.get(0)), integer(it.get(1)))
        }
        require(positions.size == positions.toSet().size) { "Duplicate compact map position" }
        val facts = array("facts").toList()
        val stamps = array("timestamps").map {
            requireArray(it, 2); listOf(nullableInteger(it.get(0)), nullableInteger(it.get(1)))
        }
        val resources = array("resources").map { requireArray(it, 2); it }
        fun text(node: JsonValue?): String? {
            val index = integer(node)
            if (index == -1) return null
            return strings.getOrNull(index) ?: throw IllegalArgumentException("Invalid map string reference")
        }
        fun requiredText(node: JsonValue?) = text(node) ?: throw IllegalArgumentException("Missing map string")
        fun stamp(node: JsonValue?): List<Int?> {
            val index = integer(node)
            if (index == -1) return listOf(null, null)
            return stamps.getOrNull(index) ?: throw IllegalArgumentException("Invalid map timestamp reference")
        }
        fun optionalBoolean(node: JsonValue?): Boolean? = if (node == null || node.isNull) null
            else { require(node.isBoolean) { "Invalid map boolean" }; node.asBoolean() }
        fun decodeFact(node: JsonValue, position: HexCoord): ObservedTile {
            requireArray(node, 19)
            val tile = ObservedTile(position.x, position.y)
            tile.detailsKnown = optionalBoolean(node.get(0)) ?: throw IllegalArgumentException("Missing detail flag")
            tile.terrain = text(node.get(1))
            val features = node.get(2)
            tile.features = if (features.isNull) null else {
                require(features.isArray) { "Invalid map features" }
                ArrayList(features.map { requiredText(it) })
            }
            tile.naturalWonder = text(node.get(3))
            tile.river = optionalBoolean(node.get(4))
            tile.ownerCivId = text(node.get(5))
            node.get(6).takeUnless { it.isNull }?.let {
                requireArray(it, 5)
                tile.city = ObservedPublicCity(requiredText(it.get(0)), requiredText(it.get(1)),
                    requiredText(it.get(2)), integer(it.get(3)), integer(it.get(4)))
            }
            tile.improvement = text(node.get(7))
            tile.improvementPillaged = optionalBoolean(node.get(8))
            tile.road = text(node.get(9))
            tile.roadPillaged = optionalBoolean(node.get(10))
            node.get(11).takeUnless { it.isNull }?.let {
                requireArray(it, 3)
                val context = when (integer(it.get(0))) {
                    0 -> TileYieldContext.ACTOR_GENERAL
                    1 -> TileYieldContext.ACTOR_CITY
                    else -> throw IllegalArgumentException("Invalid tile yield context")
                }
                val values = it.get(2)
                require(values.isArray) { "Invalid map yield values" }
                val yields = LinkedHashMap<String, Float>()
                for (value in values) {
                    requireArray(value, 2)
                    val name = requiredText(value.get(0))
                    require(name !in yields) { "Duplicate map yield" }
                    val number = value.get(1)
                    require(number.isNumber && number.asDouble().isFinite()) { "Invalid map yield" }
                    val amount = number.asFloat()
                    require(amount.isFinite()) { "Map yield exceeds supported range" }
                    yields[name] = amount
                }
                tile.yields = ObservedTileYields(context, text(it.get(1)), yields)
            }
            node.get(12).takeUnless { it.isNull }?.let {
                requireArray(it, 2)
                tile.ownWork = ObservedOwnWork(requiredText(it.get(0)), integer(it.get(1)))
            }
            tile.land = optionalBoolean(node.get(13)); tile.water = optionalBoolean(node.get(14))
            tile.hill = optionalBoolean(node.get(15)); tile.coast = optionalBoolean(node.get(16))
            tile.freshwater = optionalBoolean(node.get(17))
            node.get(18).takeUnless { it.isNull }?.let {
                requireArray(it, 3)
                tile.riverEdges = ObservedRiverEdges(
                    optionalBoolean(it.get(0)) ?: throw IllegalArgumentException("Missing river edge"),
                    optionalBoolean(it.get(1)) ?: throw IllegalArgumentException("Missing river edge"),
                    optionalBoolean(it.get(2)) ?: throw IllegalArgumentException("Missing river edge"))
            }
            return tile
        }
        // Parse shared fact payloads once; copy() isolates every mutable nested value per row.
        val factTemplates = facts.map { decodeFact(it, HexCoord()) }
        val result = AgentMapKnowledge()
        for (civ in array("civs")) {
            requireArray(civ, 2)
            val civId = requiredText(civ.get(0))
            require(civId !in result.tilesByCiv) { "Duplicate compact map civilization" }
            val tiles = HashMap<String, ObservedTile>()
            result.tilesByCiv[civId] = tiles
            val rows = civ.get(1)
            require(rows.isArray) { "Invalid compact map rows" }
            for (row in rows) {
                require(row.isArray && (row.size == 3 || row.size == 6 || row.size == 7)) { "Invalid compact map row" }
                val position = positions.getOrNull(integer(row.get(0)))
                    ?: throw IllegalArgumentException("Invalid map position reference")
                val fact = factTemplates.getOrNull(integer(row.get(1)))
                    ?: throw IllegalArgumentException("Invalid map fact reference")
                val tile = fact.copy().also { it.x = position.x; it.y = position.y }
                val sighting = stamp(row.get(2))
                tile.lastSeenTurn = sighting[0]; tile.lastSeenRevision = sighting[1]
                if (row.size >= 6) {
                    val resourceId = integer(row.get(3))
                    val provenance = integer(row.get(5))
                    require(provenance in 0..1) { "Invalid resource provenance" }
                    if (resourceId != -1) {
                        val resource = resources.getOrNull(resourceId)
                            ?: throw IllegalArgumentException("Invalid map resource reference")
                        val updated = stamp(row.get(4))
                        tile.resource = ObservedResource(requiredText(resource.get(0)), integer(resource.get(1)),
                            updated[0] ?: throw IllegalArgumentException("Missing resource turn"),
                            updated[1] ?: throw IllegalArgumentException("Missing resource revision"),
                            if (provenance == 1) ResourceKnowledgeSource.TECHNOLOGY else ResourceKnowledgeSource.SIGHTING)
                    } else require(integer(row.get(4)) == -1 && provenance == 0) { "Invalid absent resource" }
                }
                val visible = if (row.size == 7) integer(row.get(6)) else 0
                require(visible in 0..1) { "Invalid map visibility" }
                tile.visibility = if (visible == 1) TileKnowledgeVisibility.VISIBLE else TileKnowledgeVisibility.REMEMBERED
                val key = AgentMapKnowledge.key(position)
                require(key !in tiles) { "Duplicate compact map tile" }
                tiles[key] = tile
            }
        }
        return result
    }

    /** Old v2 Gdx maps may have class tags; decode typed snapshots without recursive serializer calls. */
    private fun readLegacy(json: Json, data: JsonValue): AgentMapKnowledge {
        require(listOf("strings", "positions", "facts", "timestamps", "resources", "civs").none { data.has(it) }) {
            "Compact map knowledge is missing its encoding version"
        }
        val result = AgentMapKnowledge()
        val civs = data.get("tilesByCiv") ?: return result
        require(civs.isObject) { "Invalid legacy map knowledge" }
        for (civ in civs) {
            if (civ.name == "class") continue
            require(civ.isObject) { "Invalid legacy civilization map" }
            val tiles = HashMap<String, ObservedTile>()
            result.tilesByCiv[civ.name] = tiles
            for (entry in civ) {
                if (entry.name == "class") continue
                val tile = json.readValue(ObservedTile::class.java, entry)
                require(entry.name == AgentMapKnowledge.key(HexCoord(tile.x, tile.y))) { "Invalid legacy tile coordinate" }
                tiles[entry.name] = tile
            }
        }
        return result
    }

    private fun requireArray(node: JsonValue, size: Int) {
        require(node.isArray && node.size == size) { "Invalid compact map array" }
    }

    private fun integer(node: JsonValue?): Int {
        require(node != null && node.isNumber) { "Invalid compact map integer" }
        val value = node.asDouble()
        require(value.isFinite() && value in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()
            && value == node.asInt().toDouble()) { "Invalid compact map integer" }
        return node.asInt()
    }

    private fun nullableInteger(node: JsonValue?): Int? = if (node == null || node.isNull) null else integer(node)
}
