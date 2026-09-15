package com.unciv.logic.agents

/**
 * User guidance queue (AI_AGENT_PLAN.md §8.2), engine side.
 * Delivered only at invocation boundaries; personality/profile changes are
 * not guidance (they start fresh sessions). Paired with the supervisor copy
 * (agent-runner/src/guidance.ts), which enforces the same states.
 */
class AgentGuidanceMessage(
    var guidanceId: String = "",
    var targetCivId: String = "",
    var text: String = "",
    var state: String = "queued"
) {
    @Suppress("unused")
    constructor() : this("", "", "", "queued")
}

class AgentGuidanceQueue {
    private val messages = ArrayList<AgentGuidanceMessage>()
    private var counter = 0

    fun queue(targetCivId: String, text: String): AgentGuidanceMessage {
        val message = AgentGuidanceMessage("guidance-${++counter}", targetCivId, text, "queued")
        messages.add(message)
        return message
    }

    fun pendingFor(civId: String): List<AgentGuidanceMessage> =
        messages.filter { it.targetCivId == civId && it.state == "queued" }

    /** Drains pending guidance for one invocation boundary, in order. */
    fun takeForDelivery(civId: String): List<String> {
        val pending = pendingFor(civId)
        pending.forEach { it.state = "delivered" }
        return pending.map { it.text }
    }

    fun supersede(civId: String, keepId: String) {
        pendingFor(civId).filter { it.guidanceId != keepId }.forEach { it.state = "superseded" }
    }
}
