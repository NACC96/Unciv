package com.unciv.logic.agents

import com.unciv.testing.BaseTestRunner
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Shared protocol framing (AI_AGENT_PLAN.md §7.3): exactness incl. integrals. */
@RunWith(BaseTestRunner::class)
class AgentJsonTest {

    @Test
    fun `round-trips primitives maps lists with integral numbers`() {
        val original: Map<String, Any?> = mapOf(
            "revision" to 0,
            "active" to true,
            "name" to "Rome",
            "nothing" to null,
            "gold" to 2000,
            "ratio" to 1.5,
            "queue" to listOf("a", "b"),
            "pos" to mapOf("x" to 2, "y" to -1),
            "text" to "say \"hi\"\nbye"
        )
        @Suppress("UNCHECKED_CAST")
        val parsed = AgentJson.parse(AgentJson.stringify(original)) as Map<String, Any?>
        Assert.assertEquals(original, parsed)
        Assert.assertEquals(0, parsed["revision"])
        Assert.assertEquals(2, (parsed["pos"] as Map<*, *>)["x"])
    }

    @Test
    fun `control characters round trip without invalid json`() {
        val text = (0 until 32).map { it.toChar() }.joinToString("") + "地形\\\""
        Assert.assertEquals(text, AgentJson.parse(AgentJson.stringify(text)))
        Assert.assertFalse(AgentJson.stringify(text).any { it.code < 32 })
    }

    @Test
    fun `read budget counts escaped mcp text rather than only the dto`() {
        val value = mapOf("name" to "\"".repeat(30000))
        Assert.assertTrue(AgentJson.stringify(value).toByteArray(Charsets.UTF_8).size < AGENT_READ_BYTE_BUDGET)
        Assert.assertFalse(AgentReadBudget.fits(value))
    }

    @Test
    fun `concurrent parses of different documents all round trip`() {
        // The supervisor reader thread, journals and the bridge parse at the same time.
        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        try {
            val results = (0 until threads).map { thread ->
                pool.submit<Int> {
                    start.await()
                    var parsed = 0
                    repeat(500) { i ->
                        val document = mapOf("thread" to thread, "i" to i, "name" to "civ-$thread-$i",
                            "moveTargets" to listOf(mapOf("x" to thread, "y" to -i), mapOf("x" to i, "y" to thread)),
                            "nested" to mapOf("ok" to (i % 2 == 0), "nothing" to null, "ratio" to i + 0.5))
                        Assert.assertEquals(document, AgentJson.parse(AgentJson.stringify(document)))
                        parsed++
                    }
                    parsed
                }
            }
            start.countDown()
            for (result in results) Assert.assertEquals(500, result.get(60, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `garbage never parses`() {
        Assert.assertNull(try {
            AgentJson.parse("{oops")
            "parsed"
        } catch (ex: Exception) {
            null
        })
    }
}
