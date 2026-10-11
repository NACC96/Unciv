package com.unciv.logic.agents

import com.unciv.logic.IsPartOfGameInfoSerialization

/**
 * Controller assignment for one existing civilization by civ ID
 * (AI_AGENT_PLAN.md §4.1). Human-rule treatment is unchanged: external
 * agents keep `PlayerType.Human`; only authority routing reads this.
 */
class CivilizationController(
    /** Existing civilization ID, e.g. "Rome". Never a display/leader name. */
    var civId: String = "",
    var kind: ControllerKind = ControllerKind.BUILTIN_AI,
    /** Snapshot copy of the agent profile; null unless kind == EXTERNAL_AGENT. */
    var agentProfile: AgentProfile? = null
) : IsPartOfGameInfoSerialization {
    @Suppress("unused") // used by json serialization
    constructor() : this("", ControllerKind.BUILTIN_AI, null)

    fun copy(): CivilizationController =
        CivilizationController(civId, kind, agentProfile?.copy())
}
