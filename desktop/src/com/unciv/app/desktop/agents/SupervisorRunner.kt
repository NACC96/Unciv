package com.unciv.app.desktop.agents

/**
 * Pluggable supervisor runner interface (AI_AGENT_PLAN.md §7.3, §9).
 * Real production desktop execution uses [SupervisorProcess] managing
 * the `node daemon.js` subprocess. Automated integration tests use
 * scripted or fake in-memory implementations.
 */
interface SupervisorRunner {
    fun isAlive(): Boolean
    fun runTurn(control: Map<String, Any?>): SupervisorProcess.TurnOutcome
    /** Cuts off one admitted provider run; its committed moves stay and [runTurn] returns promptly. */
    fun cancelRun(runId: String) {}
    fun shutdown()
    fun kill()
}
