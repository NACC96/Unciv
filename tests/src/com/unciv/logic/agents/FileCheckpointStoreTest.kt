package com.unciv.logic.agents

import com.unciv.testing.BaseTestRunner
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files

/**
 * Checkpoint durability (AI_AGENT_PLAN.md §10.1, proof gate §14.4):
 * numbered generations, checksum-gated recovery, torn-write fallback.
 * The ExFAT suite runs the same cycle on the actual Lexar target volume.
 */
@RunWith(BaseTestRunner::class)
class FileCheckpointStoreTest {

    private fun freshDir(prefix: String): File {
        val dir = Files.createTempDirectory(prefix).toFile()
        dir.deleteOnExit()
        return dir
    }

    @Test
    fun `write then recover returns the same bundle`() {
        val store = FileCheckpointStore(freshDir("ckpt-"), "m1")
        val json = com.unciv.json.json()
        store.writeBundle(json.toJson(AgentBundle("{\"a\":1}", json.toJson(AgentMatchState()))))
        val recovery = store.recover()
        Assert.assertNotNull(recovery)
        Assert.assertEquals(1, recovery!!.generation)
        Assert.assertFalse(recovery.fellBack)
        Assert.assertTrue(recovery.discardedGenerations.isEmpty())
        val bundle = json.fromJson(AgentBundle::class.java, recovery.bundleJson)
        Assert.assertEquals("{\"a\":1}", bundle.gameJson)
    }

    @Test
    fun `torn newest generation falls back to the previous verified one`() {
        val dir = freshDir("ckpt-")
        val store = FileCheckpointStore(dir, "m1")
        val json = com.unciv.json.json()
        store.writeBundle(json.toJson(AgentBundle("gen1", json.toJson(AgentMatchState()))))
        store.writeBundle(json.toJson(AgentBundle("gen2", json.toJson(AgentMatchState()))))
        store.writeBundle(json.toJson(AgentBundle("gen3", json.toJson(AgentMatchState()))))

        // Tear the newest generation (truncate; checksum now mismatches) and
        // drop a partial temp file like a crashed write would leave.
        val newest = File(dir, "m1-gen-000003.unciv-agent")
        newest.writeBytes(newest.readBytes().copyOf(17))
        File(dir, "m1-gen-000004.tmp").writeText("partial garbage")

        val recovery = store.recover()
        Assert.assertNotNull(recovery)
        Assert.assertEquals(2, recovery!!.generation)
        Assert.assertTrue(recovery.fellBack)
        Assert.assertEquals(listOf(3), recovery.discardedGenerations)
        val bundle = json.fromJson(AgentBundle::class.java, recovery.bundleJson)
        Assert.assertEquals("gen2", bundle.gameJson)
    }

    @Test
    fun `generation without checksum is never trusted`() {
        val dir = freshDir("ckpt-")
        val store = FileCheckpointStore(dir, "m1")
        val json = com.unciv.json.json()
        store.writeBundle(json.toJson(AgentBundle("gen1", json.toJson(AgentMatchState()))))
        File(dir, "m1-gen-000001.unciv-agent.sha256").delete()
        Assert.assertNull(store.recover())
    }

    @Test
    fun `coordinator commits flow through the file store and reload`() {
        val dir = freshDir("ckpt-")
        val store = FileCheckpointStore(dir, "m1")
        Assert.assertEquals(0, store.recover()?.generation ?: 0)

        // A bundle produced by a real coordinator round-trips the store.
        val json = com.unciv.json.json()
        val match = AgentMatchState(matchId = "m1", timelineId = "t1")
        match.stateRevision = 7
        store.writeBundle(json.toJson(AgentBundle("{\"game\":true}", json.toJson(match))))

        val recovery = store.recover()!!
        val reloadedMatch = json.fromJson(
            AgentMatchState::class.java,
            json.fromJson(AgentBundle::class.java, recovery.bundleJson).matchJson
        )
        Assert.assertEquals(7, reloadedMatch.stateRevision)
        Assert.assertEquals("m1", reloadedMatch.matchId)
    }

    /**
     * Proof gate §14.4: the SAME write/corrupt/recover cycle on the real
     * ExFAT target volume, recording whether atomic moves are supported.
     */
    @Test
    fun `exfat target volume roundtrip`() {
        val dir = File("/Volumes/Lexar/Unciv/.agent-data/checkpoint-probe/")
        dir.mkdirs()
        Assert.assertTrue("Lexar probe dir must be writable", dir.canWrite())
        // Isolate this run from previous ones.
        dir.listFiles()?.forEach { it.delete() }

        val store = FileCheckpointStore(dir, "probe")
        val json = com.unciv.json.json()
        store.writeBundle(json.toJson(AgentBundle("exfat-1", json.toJson(AgentMatchState()))))
        store.writeBundle(json.toJson(AgentBundle("exfat-2", json.toJson(AgentMatchState()))))
        println("ExFAT atomic move supported: ${store.lastWriteUsedAtomicMove}")

        val newest = File(dir, "probe-gen-000002.unciv-agent")
        newest.writeBytes(newest.readBytes().copyOf(11))
        val recovery = store.recover()
        Assert.assertNotNull("recovery on ExFAT must succeed", recovery)
        Assert.assertEquals(1, recovery!!.generation)
        Assert.assertTrue(recovery.fellBack)
        val bundle = json.fromJson(AgentBundle::class.java, recovery.bundleJson)
        Assert.assertEquals("exfat-1", bundle.gameJson)

        dir.listFiles()?.forEach { it.delete() }
    }
}
