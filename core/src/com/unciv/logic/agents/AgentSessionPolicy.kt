package com.unciv.logic.agents

/**
 * Host settings for provider session reuse, read from
 * `.agent-data/agent-settings.json` before every agent turn:
 *
 * ```
 * {"sessionRotationTurns": 1, "sessionAbTest": true}
 * ```
 *
 * Resumed sessions keep every earlier prompt and tool result, and leaders
 * reason more per turn as they grow. Rotating starts a fresh session after
 * [sessionRotationTurns] turns; continuity then comes from private notes,
 * the prompt's recent correspondence and the previous closing summary.
 */
class AgentSessionSettings(
    /** Turns one provider session serves before a fresh one; 0 never rotates. */
    val sessionRotationTurns: Int = 1,
    /** Alternate agent civs (in turn order) between rotating and never rotating, to measure the effect. */
    val sessionAbTest: Boolean = true
)

object AgentSessionPolicy {
    const val SETTINGS_FILE = "agent-settings.json"
    const val ARM_RESUME = "resume"

    /** Missing, malformed or out-of-range values fall back to the defaults. */
    fun parseSettings(text: String?): AgentSessionSettings {
        val defaults = AgentSessionSettings()
        val map = try { text?.let { AgentJson.parse(it) } as? Map<*, *> } catch (_: Exception) { null }
            ?: return defaults
        val turns = (map["sessionRotationTurns"] as? Number)?.toInt()?.takeIf { it in 0..1000 }
            ?: defaults.sessionRotationTurns
        val abTest = map["sessionAbTest"] as? Boolean ?: defaults.sessionAbTest
        return AgentSessionSettings(turns, abTest)
    }

    /** Stable label recorded with each run: "rotate-N" or [ARM_RESUME]. */
    fun armFor(civId: String, agentCivsInTurnOrder: List<String>, settings: AgentSessionSettings): String {
        if (settings.sessionRotationTurns <= 0) return ARM_RESUME
        val rotate = "rotate-${settings.sessionRotationTurns}"
        if (!settings.sessionAbTest) return rotate
        return if (agentCivsInTurnOrder.indexOf(civId) % 2 == 1) ARM_RESUME else rotate
    }

    /** Whether an existing session that already served [sessionTurns] turns should be resumed. */
    fun shouldResume(arm: String, sessionTurns: Int, settings: AgentSessionSettings): Boolean =
        arm == ARM_RESUME || sessionTurns < settings.sessionRotationTurns
}
