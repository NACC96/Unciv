package com.unciv.logic.agents

/**
 * Host settings for provider session reuse, read from
 * `.agent-data/agent-settings.json` before every agent turn:
 *
 * ```
 * {"sessionRotationTurns": 0, "sessionAbTest": false, "postTurnCompaction": true}
 * ```
 *
 * Resumed sessions keep every earlier prompt and tool result, and leaders
 * reason more per turn as they grow. Post-turn compaction (the default)
 * resumes each leader's session but compacts it after every turn, in the
 * background while the other leaders play. Rotating instead starts a fresh
 * session after [AgentSessionSettings.sessionRotationTurns] turns; continuity
 * then comes from private notes, the prompt's recent correspondence and the
 * previous closing summary.
 */
class AgentSessionSettings(
    /** Turns one provider session serves before a fresh one; 0 never rotates. */
    val sessionRotationTurns: Int = 0,
    /** Alternate agent civs (in turn order) between rotating and never rotating, to measure the effect. */
    val sessionAbTest: Boolean = false,
    /** Compact a session after each turn it will be resumed from (providers that support it). */
    val postTurnCompaction: Boolean = true
)

object AgentSessionPolicy {
    const val SETTINGS_FILE = "agent-settings.json"
    const val ARM_RESUME = "resume"
    /** Never rotates; the session is compacted after every turn. */
    const val ARM_COMPACT = "compact"

    /** Missing, malformed or out-of-range values fall back to the defaults. */
    fun parseSettings(text: String?): AgentSessionSettings {
        val defaults = AgentSessionSettings()
        val map = try { text?.let { AgentJson.parse(it) } as? Map<*, *> } catch (_: Exception) { null }
            ?: return defaults
        val turns = (map["sessionRotationTurns"] as? Number)?.toInt()?.takeIf { it in 0..1000 }
            ?: defaults.sessionRotationTurns
        val abTest = map["sessionAbTest"] as? Boolean ?: defaults.sessionAbTest
        val compaction = map["postTurnCompaction"] as? Boolean ?: defaults.postTurnCompaction
        return AgentSessionSettings(turns, abTest, compaction)
    }

    /** Stable label recorded with each run: "rotate-N", [ARM_COMPACT] or [ARM_RESUME]. */
    fun armFor(civId: String, agentCivsInTurnOrder: List<String>, settings: AgentSessionSettings): String {
        val neverRotate = if (settings.postTurnCompaction) ARM_COMPACT else ARM_RESUME
        if (settings.sessionRotationTurns <= 0) return neverRotate
        val rotate = "rotate-${settings.sessionRotationTurns}"
        if (!settings.sessionAbTest) return rotate
        return if (agentCivsInTurnOrder.indexOf(civId) % 2 == 1) neverRotate else rotate
    }

    /** Whether an existing session that already served [sessionTurns] turns should be resumed. */
    fun shouldResume(arm: String, sessionTurns: Int, settings: AgentSessionSettings): Boolean =
        arm == ARM_RESUME || arm == ARM_COMPACT || sessionTurns < settings.sessionRotationTurns

    /**
     * Whether to compact after a turn that leaves its session at [sessionTurnsAfter]
     * turns: only a session the next turn will resume is worth compacting.
     */
    fun compactAfterTurn(arm: String, sessionTurnsAfter: Int, settings: AgentSessionSettings): Boolean =
        settings.postTurnCompaction && shouldResume(arm, sessionTurnsAfter, settings)
}
