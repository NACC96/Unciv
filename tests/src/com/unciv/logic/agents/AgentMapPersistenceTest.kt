package com.unciv.logic.agents

import com.unciv.json.json
import com.unciv.testing.BaseTestRunner
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.file.Files

@RunWith(BaseTestRunner::class)
class AgentMapPersistenceTest {
    @Test fun verified_generation_migrates_genuine_v1_before_strict_validation() {
        val dir = Files.createTempDirectory("agent-map-v1-").toFile()
        try {
            val oldState = """{"matchId":"legacy","timelineId":"original","stateRevision":17}"""
            val legacy = AgentJson.stringify(mapOf("gameJson" to "{\"old\":true}", "matchJson" to oldState))
            val store = FileCheckpointStore(dir, "legacy")
            store.writeBundle(legacy)
            val recovered = store.recover()!!
            assertEquals(1, recovered.generation)
            assertFalse(recovered.fellBack)
            val bundle = json().fromJson(AgentBundle::class.java, recovered.bundleJson)
            bundle.validateFormat()
            val state = json().fromJson(AgentMatchState::class.java, bundle.matchJson)
            state.validateFormat()
            assertEquals(17, state.stateRevision)
            assertEquals("original", state.timelineId)
            assertTrue(state.mapKnowledge.tilesByCiv.isEmpty())
            assertEquals("{\"old\":true}", bundle.gameJson)
            assertTrue(recovered.bundleJson.contains("\"bundleVersion\":2"))
            // Checksum continues to describe the historical bytes, never rewritten by recovery.
            assertEquals(legacy, dir.resolve("legacy-gen-000001.unciv-agent").readText())
        } finally { dir.deleteRecursively() }
    }

    @Test fun v2_map_memory_survives_checkpoint_and_future_generation_falls_back() {
        val dir = Files.createTempDirectory("agent-map-v2-").toFile()
        try {
            val serializer = json().apply { setUsePrototypes(false) }
            val state = AgentMatchState(matchId = "m", timelineId = "t")
            state.mapKnowledge.put("Rome", ObservedTile(1, 2, terrain = "Grassland",
                resource = ObservedResource("Iron", 2, 3, 4, ResourceKnowledgeSource.TECHNOLOGY)))
            val store = FileCheckpointStore(dir, "m")
            store.write("{}", serializer.toJson(state))
            store.writeBundle(serializer.toJson(AgentBundle("{}", "{\"formatVersion\":3}", 3)))
            val recovery = store.recover()!!
            assertTrue(recovery.fellBack)
            assertEquals(listOf(2), recovery.discardedGenerations)
            val bundle = serializer.fromJson(AgentBundle::class.java, recovery.bundleJson)
            val restored = serializer.fromJson(AgentMatchState::class.java, bundle.matchJson)
            val tile = restored.mapKnowledge.tilesByCiv["Rome"]!!["1,2"]!!
            assertEquals("Grassland", tile.terrain)
            assertEquals(ResourceKnowledgeSource.TECHNOLOGY, tile.resource!!.source)
            assertEquals(4, tile.resource!!.updatedRevision)
            assertTrue(recovery.bundleJson.contains("\"bundleVersion\":2"))
            assertTrue(bundle.matchJson.contains("\"formatVersion\":2"))
        } finally { dir.deleteRecursively() }
    }
    @Test fun compressed_retention_preserves_map_memory_and_current_turn_receipt_window() {
        val dir = Files.createTempDirectory("agent-map-compressed-").toFile()
        try {
            val serializer = json().apply { setUsePrototypes(false) }
            val state = AgentMatchState(matchId = "m", timelineId = "t")
            state.mapKnowledge.put("Rome", ObservedTile(1, 2, detailsKnown = true,
                lastSeenTurn = 3, lastSeenRevision = 4, terrain = "Grassland", features = arrayListOf("Forest"),
                resource = ObservedResource("Iron", 2, 3, 4, ResourceKnowledgeSource.TECHNOLOGY)))
            var store = FileCheckpointStore(dir, "m", maxRetainedGenerations = 3)
            for (revision in 1..20) {
                state.stateRevision = revision
                state.currentTurnReceipts.add(ActionReceipt("action-$revision", "Rome", "t", "request-$revision", revision - 1, revision))
                if (revision == 11) store = FileCheckpointStore(dir, "m", maxRetainedGenerations = 3)
                store.write("{}", serializer.toJson(state))
            }
            assertEquals(3, dir.listFiles()!!.count { it.name.endsWith(".unciv-agent") })
            val recovery = store.recover()!!
            assertEquals(20, recovery.generation)
            val bundle = serializer.fromJson(AgentBundle::class.java, recovery.bundleJson)
            val restored = serializer.fromJson(AgentMatchState::class.java, bundle.matchJson)
            val tile = restored.mapKnowledge.get("Rome", com.unciv.logic.map.HexCoord(1, 2))!!
            assertEquals(arrayListOf("Forest"), tile.features)
            assertEquals(ResourceKnowledgeSource.TECHNOLOGY, tile.resource!!.source)
            assertEquals(4, tile.resource!!.updatedRevision)
            assertEquals(20, restored.currentTurnReceipts.size)
            assertEquals("action-1", restored.currentTurnReceipts.first().actionId)
            assertEquals("action-20", restored.currentTurnReceipts.last().actionId)
        } finally { dir.deleteRecursively() }
    }

    @Test fun compressed_genuine_v1_generation_migrates_and_future_gzip_falls_back() {
        val dir = Files.createTempDirectory("agent-map-compressed-v1-").toFile()
        try {
            val store = FileCheckpointStore(dir, "legacy", maxRetainedGenerations = 3)
            val legacy = AgentJson.stringify(mapOf("gameJson" to "{}", "matchJson" to
                "{\"matchId\":\"legacy\",\"stateRevision\":17}"))
            store.writeBundle(legacy)
            val serializer = json().apply { setUsePrototypes(false) }
            store.writeBundle(serializer.toJson(AgentBundle("{}", "{\"formatVersion\":3}", 3)))
            val recovery = store.recover()!!
            assertEquals(1, recovery.generation)
            assertEquals(listOf(2), recovery.discardedGenerations)
            val bundle = serializer.fromJson(AgentBundle::class.java, recovery.bundleJson)
            bundle.validateFormat()
            val state = serializer.fromJson(AgentMatchState::class.java, bundle.matchJson)
            state.validateFormat()
            assertEquals(17, state.stateRevision)
            assertTrue(state.mapKnowledge.tilesByCiv.isEmpty())
        } finally { dir.deleteRecursively() }
    }

}
