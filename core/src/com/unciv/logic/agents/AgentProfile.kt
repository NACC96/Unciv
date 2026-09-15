package com.unciv.logic.agents

import com.unciv.logic.IsPartOfGameInfoSerialization

/**
 * An agent identity reusable across matches (AI_AGENT_PLAN.md §4.1).
 * Controllers hold a COPY of the profile revision used when the match (or the
 * controller assignment) was created: later profile edits never silently
 * alter an already-started match; applying them commits an explicit
 * configuration-change event and starts a fresh provider session.
 *
 * `provider` is a String ("codex" | "claude" | "opencode" today), not an
 * enum, so old saves still load if the provider set ever grows.
 */
class AgentProfile(
    var profileId: String = "",
    var displayName: String = "",
    var provider: String = "",
    var modelId: String = "",
    /** Provider-supported reasoning setting, e.g. effort level; null = default. */
    var reasoning: String? = null,
    var personality: String = "",
    var profileRevision: Int = 0
) : IsPartOfGameInfoSerialization {
    @Suppress("unused") // used by json serialization
    constructor() : this("", "", "", "", null, "", 0)

    fun copy(): AgentProfile = AgentProfile(
        profileId, displayName, provider, modelId, reasoning, personality, profileRevision
    )

    companion object {
        fun defaultFor(civName: String = "", provider: String = "codex"): AgentProfile {
            val model = AgentModelCatalog.getDefaultModel(provider)
            val name = if (civName.isNotBlank()) "${model.label} $civName" else model.label
            return AgentProfile(
                profileId = java.util.UUID.randomUUID().toString(),
                displayName = name,
                provider = provider,
                modelId = model.id,
                reasoning = model.defaultThinkingLevel.wireValue,
                personality = "",
                profileRevision = 1
            )
        }
    }
}
