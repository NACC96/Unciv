package com.unciv.logic.agents

import com.unciv.testing.BaseTestRunner
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith

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
    fun `garbage never parses`() {
        Assert.assertNull(try {
            AgentJson.parse("{oops")
            "parsed"
        } catch (ex: Exception) {
            null
        })
    }
}
