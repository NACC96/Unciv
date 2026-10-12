package com.unciv.logic.agents

import com.badlogic.gdx.utils.Json
import com.badlogic.gdx.utils.JsonWriter
import com.unciv.json.json
import com.unciv.logic.map.HexCoord
import com.unciv.testing.BaseTestRunner
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class AgentMapKnowledgeSerializerTest {
    private fun sample() = ObservedTile(
        x = 3, y = -2, visibility = TileKnowledgeVisibility.VISIBLE, detailsKnown = true,
        lastSeenTurn = 5, lastSeenRevision = 20, terrain = "Grassland", features = arrayListOf("Forest"),
        naturalWonder = "世界", river = true,
        resource = ObservedResource("Iron", 6, 7, 25, ResourceKnowledgeSource.TECHNOLOGY),
        ownerCivId = "Rome", city = ObservedPublicCity("c", "Roma", "Rome", 180, 4),
        improvement = "Farm", improvementPillaged = false, road = "Road", roadPillaged = true,
        yields = ObservedTileYields(TileYieldContext.ACTOR_CITY, "c",
            linkedMapOf("Food" to 0f, "Production" to 2.5f)),
        ownWork = ObservedOwnWork("Mine", 3), land = true, water = false, hill = false,
        coast = null, freshwater = true, riverEdges = ObservedRiverEdges(true, false, true)
    )

    private fun encode(memory: AgentMapKnowledge) = json().apply { setUsePrototypes(false) }.toJson(memory)
    private fun decode(text: String) = json().fromJson(AgentMapKnowledge::class.java, text)

    @Test fun compact_roundtrip_preserves_all_facts_and_independent_provenance() {
        val memory = AgentMapKnowledge()
        memory.put("Rome", sample())
        val before = memory.get("Rome", HexCoord(3, -2))!!.toWire()
        val encoded = encode(memory)
        assertTrue(encoded.contains("\"encodingVersion\":1"))
        assertFalse(encoded.contains("tilesByCiv"))
        assertEquals(before, decode(encoded).get("Rome", HexCoord(3, -2))!!.toWire())
        // Serialization is pure and repeated writes of unchanged state are byte-identical.
        assertEquals(before, memory.get("Rome", HexCoord(3, -2))!!.toWire())
        assertEquals(encoded, encode(memory))
    }

    @Test fun shared_facts_keep_cross_civ_sighting_ages_and_mutable_values_isolated() {
        val memory = AgentMapKnowledge()
        memory.put("Rome", sample())
        memory.put("Greece", sample().apply {
            lastSeenTurn = 1; lastSeenRevision = 2
            resource!!.updatedTurn = 3; resource!!.updatedRevision = 8
            resource!!.source = ResourceKnowledgeSource.SIGHTING
            visibility = TileKnowledgeVisibility.REMEMBERED
        })
        val encoded = encode(memory)
        val raw = com.badlogic.gdx.utils.JsonReader().parse(encoded)
        assertEquals(1, raw.get("facts").size)
        assertEquals(1, raw.get("positions").size)
        assertEquals(1, raw.get("resources").size)
        val restored = decode(encoded)
        val rome = restored.get("Rome", HexCoord(3, -2))!!
        val greece = restored.get("Greece", HexCoord(3, -2))!!
        assertEquals(20, rome.lastSeenRevision); assertEquals(2, greece.lastSeenRevision)
        assertEquals(25, rome.resource!!.updatedRevision); assertEquals(8, greece.resource!!.updatedRevision)
        assertEquals(ResourceKnowledgeSource.TECHNOLOGY, rome.resource!!.source)
        assertEquals(ResourceKnowledgeSource.SIGHTING, greece.resource!!.source)
        greece.features!!.add("Changed"); greece.yields!!.values["Food"] = 100f
        greece.city!!.name = "Changed"; greece.ownWork!!.turnsRemaining = 999
        greece.riverEdges!!.bottom = true; greece.resource!!.amount = 999
        assertEquals(arrayListOf("Forest"), rome.features)
        assertEquals(0f, rome.yields!!.values["Food"])
        assertEquals("Roma", rome.city!!.name); assertEquals(3, rome.ownWork!!.turnsRemaining)
        assertFalse(rome.riverEdges!!.bottom); assertEquals(6, rome.resource!!.amount)
        val copy = restored.deepCopy()
        copy.get("Rome", HexCoord(3, -2))!!.features!!.add("Copy")
        assertEquals(arrayListOf("Forest"), rome.features)
    }

    @Test fun null_empty_and_partial_discovery_are_distinct_after_roundtrip() {
        val memory = AgentMapKnowledge()
        memory.put("Rome", ObservedTile(1, 2))
        memory.put("Rome", ObservedTile(2, 3, features = arrayListOf(), river = false,
            yields = ObservedTileYields(values = linkedMapOf()),
            resource = ObservedResource("Iron", 0, 0, 0, ResourceKnowledgeSource.TECHNOLOGY)))
        memory.put("Rome", ObservedTile(3, 4, lastSeenTurn = null, lastSeenRevision = 7))
        val restored = decode(encode(memory))
        for (position in listOf(HexCoord(1, 2), HexCoord(2, 3), HexCoord(3, 4)))
            assertEquals(memory.get("Rome", position)!!.toWire(), restored.get("Rome", position)!!.toWire())
        assertFalse(restored.get("Rome", HexCoord(2, 3))!!.detailsKnown)
        assertNull(restored.get("Rome", HexCoord(1, 2))!!.features)
        assertTrue(restored.get("Rome", HexCoord(2, 3))!!.features!!.isEmpty())
        assertNull(restored.get("Rome", HexCoord(2, 3))!!.lastSeenTurn)
    }

    @Test fun legacy_plain_v2_maps_load_and_rewrite_compact_without_changing_wire_data() {
        val memory = AgentMapKnowledge()
        memory.put("Rome", sample()); memory.put("Greece", ObservedTile(-1, 4))
        val legacyJson = Json(JsonWriter.OutputType.json).apply { setUsePrototypes(false) }
        val legacy = legacyJson.toJson(memory)
        assertTrue(legacy.contains("tilesByCiv"))
        val loaded = decode(legacy)
        for ((civ, tiles) in memory.tilesByCiv) for (tile in tiles.values)
            assertEquals(tile.toWire(), loaded.get(civ, HexCoord(tile.x, tile.y))!!.toWire())
        val compact = encode(loaded)
        assertTrue(compact.contains("encodingVersion"))
        assertEquals(sample().toWire(), decode(compact).get("Rome", HexCoord(3, -2))!!.toWire())
        val state = AgentMatchState(matchId = "m", timelineId = "t", mapKnowledge = memory)
        val legacyState = legacyJson.toJson(state)
        val migrated = migrateAgentBundleJson(legacyJson.toJson(AgentBundle("{}", legacyState)))
        val restored = json().fromJson(AgentMatchState::class.java, migrated.matchJson)
        assertEquals(3, restored.formatVersion)
        assertEquals(sample().toWire(), restored.mapKnowledge.get("Rome", HexCoord(3, -2))!!.toWire())
    }

    @Test fun unknown_or_malformed_compact_encoding_rejects_instead_of_discarding_memory() {
        val valid = encode(AgentMapKnowledge().apply { put("Rome", sample()) })
        for (bad in listOf(valid.replace("\"encodingVersion\":1", "\"encodingVersion\":2"),
            valid.replace("\"encodingVersion\":1,", ""),
            "{\"encodingVersion\":1}")) {
            try { decode(bad); fail("Expected compact encoding rejection") }
            catch (expected: Exception) { assertTrue(expected.message!!.isNotBlank()) }
        }
    }

    @Test fun repeated_large_map_facts_are_compact_and_wire_json_stays_expanded() {
        val memory = AgentMapKnowledge()
        repeat(8) { civ -> repeat(1000) { coordinate ->
            memory.put("civ-$civ", sample().apply {
                x = coordinate; y = 0; lastSeenRevision = civ + 30
                lastSeenTurn = civ; city = null; ownWork = null
            })
        } }
        val legacy = Json(JsonWriter.OutputType.json).apply { setUsePrototypes(false) }.toJson(memory)
        val compact = encode(memory)
        assertTrue("Compact memory should be less than one fifth of repeated plain DTO JSON",
            compact.toByteArray(Charsets.UTF_8).size * 5 < legacy.toByteArray(Charsets.UTF_8).size)
        val raw = com.badlogic.gdx.utils.JsonReader().parse(compact)
        assertEquals(1, raw.get("facts").size)
        assertEquals(1000, raw.get("positions").size)
        val restored = decode(compact)
        val tile = restored.get("civ-7", HexCoord(999, 0))!!
        assertEquals(memory.get("civ-7", HexCoord(999, 0))!!.toWire(), tile.toWire())
        val wire = AgentJson.stringify(tile.toWire())
        assertTrue(wire.contains("lastSeenRevision")); assertTrue(wire.contains("TECHNOLOGY"))
        assertFalse(wire.contains("encodingVersion"))
    }

    @Test fun finite_double_yield_that_overflows_float_is_rejected() {
        val valid = encode(AgentMapKnowledge().apply { put("Rome", sample()) })
        assertTrue(valid.contains("2.5"))
        try { decode(valid.replace("2.5", "1e39")); fail("Overflowing yield accepted") }
        catch (expected: Exception) { assertTrue(expected.message!!.isNotBlank()) }
    }
}
