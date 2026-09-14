package com.unciv.logic.agents

import com.unciv.logic.IsPartOfGameInfoSerialization

/**
 * Who controls a major civilization in an agent-enabled match.
 * Independent of [com.unciv.logic.civilization.PlayerType]: external agents
 * stay in the Human rule category (starting units, bonuses, research costs),
 * this category only routes turn authority and presentation.
 * (AI_AGENT_PLAN.md §4.1)
 */
enum class ControllerKind : IsPartOfGameInfoSerialization {
    HUMAN,
    BUILTIN_AI,
    EXTERNAL_AGENT;
}
