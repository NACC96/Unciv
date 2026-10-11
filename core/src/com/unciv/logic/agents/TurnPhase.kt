package com.unciv.logic.agents

import com.unciv.logic.IsPartOfGameInfoSerialization

/**
 * Durable turn phases for agent-enabled matches (AI_AGENT_PLAN.md §4.2).
 * Start/end transitions are atomic candidate operations; there is no
 * partially-saved intermediate state, only this persisted phase plus
 * UI/process status held outside the checkpoint.
 */
enum class TurnPhase : IsPartOfGameInfoSerialization {
    /** Apply start-turn effects exactly once for the next eligible civ. */
    READY_TO_START,
    /** Commands, required decisions, or built-in automation per controller. */
    AWAITING_DECISIONS,
    /** Apply end-turn effects once, roll over round, select next civ. */
    READY_TO_END,
    /** Inspection and save/export only; "One more turn" may reopen. */
    COMPLETED;
}
