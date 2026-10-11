package com.unciv.logic.agents

/**
 * Execution policy installed on unpublished candidates BEFORE transient
 * restoration and rule execution (AI_AGENT_PLAN.md §3.3, §1.2).
 *
 * Game-rule code must read this explicit context — never global UI autoplay
 * or user settings — to decide whether strategic automation may act.
 * Enforcement points (auto-production, auto-bombard, auto-explore, worker
 * automation, global unsolicited choices) are wired during the W4 automation
 * audit; the flags and the coordinator gate exist now so no call site can
 * later infer policy from globals.
 */
class AgentExecutionPolicy(
    /** Suppress side-effecting choices during transient restoration. */
    val suppressUnsolicitedChoicesOnRestore: Boolean = true,
    /** No global auto-production for external-agent civs. */
    val suppressAutoProduction: Boolean = true,
    /** No auto-bombardment for external-agent civs. */
    val suppressAutoBombard: Boolean = true,
    /** No auto-explore for external-agent civs. */
    val suppressAutoExplore: Boolean = true,
    /** No worker automation for external-agent civs. */
    val suppressWorkerAutomation: Boolean = true
) {
    companion object {
        /** Ordinary (non-agent) games: no policy installed, legacy behavior. */
        val LEGACY = AgentExecutionPolicy(false, false, false, false, false)
    }
}
