package com.unciv.logic.agents

/** Counts both JSON layers in MCP text content, reserving framing and request-ID space. */
object AgentReadBudget {
    const val ENVELOPE_RESERVE = 1024

    fun contentBytes(value: Any?): Int = AgentJson.stringify(mapOf(
        "content" to listOf(mapOf("type" to "text", "text" to AgentJson.stringify(value)))
    )).toByteArray(Charsets.UTF_8).size

    fun fits(value: Any?): Boolean = contentBytes(value) <= AGENT_READ_BYTE_BUDGET - ENVELOPE_RESERVE
}
