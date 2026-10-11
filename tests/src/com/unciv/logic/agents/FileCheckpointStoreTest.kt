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
    private fun currentBundle(label: String): String {
        val serializer = com.unciv.json.json().apply { setUsePrototypes(false) }
        return serializer.toJson(AgentBundle(label, serializer.toJson(AgentMatchState(matchId = "m1", timelineId = "t1"))))
    }
    private fun generationFiles(dir: File) = dir.listFiles()!!.filter { it.name.endsWith(".unciv-agent") }.sortedBy { it.name }

    @Test fun `bounded compressed store retains three verified generations after twenty writes and reopen`() {
        val dir = freshDir("ckpt-bounded-")
        try {
            var store = FileCheckpointStore(dir, "m1", maxRetainedGenerations = 3)
            for (revision in 1..20) {
                if (revision == 11) store = FileCheckpointStore(dir, "m1", maxRetainedGenerations = 3)
                store.writeBundle(currentBundle("revision-$revision " + "repeated geography ".repeat(10000)))
            }
            Assert.assertEquals(listOf("m1-gen-000018.unciv-agent", "m1-gen-000019.unciv-agent", "m1-gen-000020.unciv-agent"),
                generationFiles(dir).map { it.name })
            Assert.assertEquals(3, dir.listFiles()!!.count { it.name.endsWith(".sha256") })
            val files = generationFiles(dir)
            Assert.assertTrue(files.all { it.readBytes().take(2).map { byte -> byte.toInt() and 255 } == listOf(0x1f, 0x8b) })
            Assert.assertTrue("Compressed retained disk must remain bounded", files.sumOf { it.length() } < 10000)
            Assert.assertEquals(20, store.recover()!!.generation)
            files.last().writeText("corrupt newest")
            Assert.assertEquals(19, store.recover()!!.generation)
            files[1].writeText("corrupt second newest")
            Assert.assertEquals(18, store.recover()!!.generation)
        } finally { dir.deleteRecursively() }
    }

    @Test fun `retention activation never deletes or rewrites preexisting archived generations`() {
        val dir = freshDir("ckpt-archives-")
        try {
            val legacy = FileCheckpointStore(dir, "m1")
            repeat(4) { legacy.writeBundle(currentBundle("archived-$it")) }
            val archived = dir.listFiles()!!.associate { it.name to it.readBytes().toList() }
            var store = FileCheckpointStore(dir, "m1", maxRetainedGenerations = 3)
            repeat(10) {
                if (it == 5) store = FileCheckpointStore(dir, "m1", maxRetainedGenerations = 3)
                store.writeBundle(currentBundle("bounded-$it"))
            }
            Assert.assertEquals(7, generationFiles(dir).size)
            for ((name, bytes) in archived) Assert.assertEquals(bytes, File(dir, name).readBytes().toList())
            Assert.assertEquals(14, store.recover()!!.generation)
        } finally { dir.deleteRecursively() }
    }

    @Test fun `compression recovery detects legacy plain and gzip bytes without filename changes`() {
        val dir = freshDir("ckpt-mixed-")
        try {
            FileCheckpointStore(dir, "m1").writeBundle(currentBundle("plain legacy"))
            val bounded = FileCheckpointStore(dir, "m1", maxRetainedGenerations = 3)
            bounded.writeBundle(currentBundle("compressed current"))
            Assert.assertEquals(2, FileCheckpointStore(dir, "m1").recover()!!.generation)
            File(dir, "m1-gen-000002.unciv-agent").writeText("corrupt gzip")
            val recovery = bounded.recover()!!
            Assert.assertEquals(1, recovery.generation)
            Assert.assertTrue(recovery.fellBack)
            val bundle = com.unciv.json.json().fromJson(AgentBundle::class.java, recovery.bundleJson)
            Assert.assertEquals("plain legacy", bundle.gameJson)
        } finally { dir.deleteRecursively() }
    }

    @Test fun `failed new checksum publication keeps every old recoverable generation`() {
        val dir = freshDir("ckpt-write-fail-")
        try {
            val store = FileCheckpointStore(dir, "m1", maxRetainedGenerations = 3)
            repeat(3) { store.writeBundle(currentBundle("good-$it")) }
            val blockedSidecar = File(dir, "m1-gen-000004.unciv-agent.sha256")
            blockedSidecar.mkdirs()
            File(blockedSidecar, "blocker").writeText("occupied")
            try { store.writeBundle(currentBundle("cannot publish checksum")); Assert.fail("Expected checksum publication failure") }
            catch (_: java.io.IOException) { }
            Assert.assertTrue((1..3).all { File(dir, "m1-gen-${it.toString().padStart(6, '0')}.unciv-agent").exists() })
            Assert.assertEquals(3, store.recover()!!.generation)
        } finally { dir.deleteRecursively() }
    }

    @Test fun `pruning failure does not roll back a durable verified publication`() {
        val dir = freshDir("ckpt-prune-fail-")
        try {
            val store = FileCheckpointStore(dir, "m1", maxRetainedGenerations = 3)
            repeat(3) { store.writeBundle(currentBundle("good-$it")) }
            val undeletable = File(dir, "m1-gen-000001.unciv-agent")
            undeletable.delete()
            undeletable.mkdirs()
            File(undeletable, "blocker").writeText("occupied")
            store.writeBundle(currentBundle("published despite cleanup failure"))
            Assert.assertNotNull(store.lastRetentionFailure)
            Assert.assertEquals(4, store.recover()!!.generation)
            Assert.assertEquals(listOf(4, 3, 2), store.lastRetainedGenerations)
        } finally { dir.deleteRecursively() }
    }

    @Test fun `future and mismatched new bundles cannot prune existing recovery fallback`() {
        val dir = freshDir("ckpt-version-fail-")
        try {
            val store = FileCheckpointStore(dir, "m1", maxRetainedGenerations = 3)
            repeat(3) { store.writeBundle(currentBundle("good-$it")) }
            val serializer = com.unciv.json.json().apply { setUsePrototypes(false) }
            store.writeBundle(serializer.toJson(AgentBundle("future", "{\"formatVersion\":3}", 3)))
            store.writeBundle(serializer.toJson(AgentBundle("mismatched", "{\"formatVersion\":2}", 1)))
            Assert.assertEquals(5, generationFiles(dir).size)
            val recovery = FileCheckpointStore(dir, "m1", maxRetainedGenerations = 3).recover()!!
            Assert.assertEquals(3, recovery.generation)
            Assert.assertEquals(listOf(5, 4), recovery.discardedGenerations)
            Assert.assertTrue((1..3).all { File(dir, "m1-gen-${it.toString().padStart(6, '0')}.unciv-agent").exists() })
        } finally { dir.deleteRecursively() }
    }

    @Test fun `directory sync failure retains old fallbacks after published checkpoint`() {
        val dir = freshDir("ckpt-sync-fail-")
        var failSync = false
        try {
            val store = FileCheckpointStore(dir, "m1", maxRetainedGenerations = 3,
                directorySync = { if (failSync) throw java.io.IOException("Injected directory sync failure") })
            repeat(3) { store.writeBundle(currentBundle("good-$it")) }
            failSync = true
            store.writeBundle(currentBundle("published with uncertain directory sync"))
            Assert.assertEquals(4, generationFiles(dir).size)
            Assert.assertTrue(store.lastRetentionFailure!!.contains("directory sync failed"))
            Assert.assertEquals(4, store.recover()!!.generation)
        } finally { dir.deleteRecursively() }
    }

    @Test fun `activation policy sync failure publishes no checkpoint`() {
        val dir = freshDir("ckpt-policy-sync-fail-")
        try {
            val store = FileCheckpointStore(dir, "m1", maxRetainedGenerations = 3,
                directorySync = { throw java.io.IOException("Injected policy sync failure") })
            try { store.writeBundle(currentBundle("must not publish")); Assert.fail("Expected activation failure") }
            catch (_: java.io.IOException) { }
            Assert.assertTrue(generationFiles(dir).isEmpty())
            Assert.assertNull(store.recover())
        } finally { dir.deleteRecursively() }
    }

}
