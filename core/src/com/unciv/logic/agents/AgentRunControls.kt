package com.unciv.logic.agents

/**
 * Match run-state machine (AI_AGENT_PLAN.md §9), engine-side authority.
 * Mirrors agent-runner/src/run-controls.ts (supervisor process management);
 * the two are paired by the transition table below, not by codegen.
 *
 * Match run state and provider process state are separate: this machine
 * decides which controls exist and what each guarantees. Pause never kills
 * providers; stop targets only the recorded process/session; step modes
 * return to Paused; controller changes revoke authority first.
 */
enum class AgentRunState {
    PAUSED,
    STARTING,
    AGENT_DECIDING,
    APPLYING,
    WAITING_HUMAN,
    PAUSING,
    NEEDS_ATTENTION,
    FINISHED;
}

enum class AgentControl {
    RUN,
    PAUSE,
    STEP_ACTION,
    STEP_TURN,
    RETRY,
    SWITCH_PROVIDER,
    TAKEOVER,
    RETURN_TO_AGENT,
    STOP;
}

class AgentTransition(val to: AgentRunState, val guarantee: String)

object AgentRunControls {
    private data class Edge(val from: AgentRunState, val control: AgentControl, val to: AgentRunState, val guarantee: String)

    private val edges = listOf(
        Edge(AgentRunState.PAUSED, AgentControl.RUN, AgentRunState.STARTING,
            "resume at the committed boundary; reuse a live invocation, never overlap"),
        Edge(AgentRunState.STARTING, AgentControl.RUN, AgentRunState.STARTING,
            "already starting / running; idempotent"),
        Edge(AgentRunState.AGENT_DECIDING, AgentControl.RUN, AgentRunState.AGENT_DECIDING,
            "already running; idempotent"),
        Edge(AgentRunState.APPLYING, AgentControl.RUN, AgentRunState.APPLYING,
            "already running; idempotent"),
        Edge(AgentRunState.PAUSED, AgentControl.PAUSE, AgentRunState.PAUSED,
            "already paused; idempotent"),
        Edge(AgentRunState.NEEDS_ATTENTION, AgentControl.RETRY, AgentRunState.STARTING,
            "re-observe committed state; resume exact identity after termination confirmed"),
        Edge(AgentRunState.NEEDS_ATTENTION, AgentControl.SWITCH_PROVIDER, AgentRunState.STARTING,
            "revoke old authority + epoch bump; new session with permitted notes/history"),
        Edge(AgentRunState.NEEDS_ATTENTION, AgentControl.TAKEOVER, AgentRunState.WAITING_HUMAN,
            "revoke old authority first, commit controller change, then enable human controls"),
        Edge(AgentRunState.PAUSED, AgentControl.TAKEOVER, AgentRunState.WAITING_HUMAN,
            "revoke old authority first, commit controller change, then enable human controls"),
        Edge(AgentRunState.WAITING_HUMAN, AgentControl.RETURN_TO_AGENT, AgentRunState.STARTING,
            "commit controller assignment at a paused boundary; new authority epoch"),
        Edge(AgentRunState.STARTING, AgentControl.PAUSE, AgentRunState.PAUSING,
            "no new mutations after the in-flight atomic op; provider NOT killed"),
        Edge(AgentRunState.AGENT_DECIDING, AgentControl.PAUSE, AgentRunState.PAUSING,
            "no new mutations after the in-flight atomic op; provider NOT killed"),
        Edge(AgentRunState.APPLYING, AgentControl.PAUSE, AgentRunState.PAUSING,
            "the atomic op commits or discards, then Paused"),
        Edge(AgentRunState.PAUSING, AgentControl.PAUSE, AgentRunState.PAUSING,
            "idempotent while settling"),
        Edge(AgentRunState.PAUSING, AgentControl.RUN, AgentRunState.PAUSING,
            "draining to safe boundary before next run request"),
        Edge(AgentRunState.PAUSED, AgentControl.STEP_ACTION, AgentRunState.PAUSED,
            "one successful gameplay mutation, then Paused; reads/rejects do not consume it"),
        Edge(AgentRunState.PAUSED, AgentControl.STEP_TURN, AgentRunState.PAUSED,
            "current civ through its end-turn checkpoint, then Paused before the next begins"),
        Edge(AgentRunState.PAUSED, AgentControl.STOP, AgentRunState.PAUSED,
            "explicit cancellation of the recorded process/session only; match stays paused"),
        Edge(AgentRunState.AGENT_DECIDING, AgentControl.STOP, AgentRunState.PAUSED,
            "explicit cancellation of the recorded process/session only; match stays paused"),
        Edge(AgentRunState.NEEDS_ATTENTION, AgentControl.STOP, AgentRunState.PAUSED,
            "explicit cancellation of the recorded process/session only; match stays paused"),
        Edge(AgentRunState.WAITING_HUMAN, AgentControl.PAUSE, AgentRunState.WAITING_HUMAN,
            "human turns have no provider to pause; stays put"),
        Edge(AgentRunState.WAITING_HUMAN, AgentControl.RUN, AgentRunState.WAITING_HUMAN,
            "human must end their turn first")
    )

    fun transition(from: AgentRunState, control: AgentControl): AgentTransition? =
        edges.firstOrNull { it.from == from && it.control == control }
            ?.let { AgentTransition(it.to, it.guarantee) }

    fun nextEpoch(current: Int): Int {
        require(current >= 0) { "Current epoch must be non-negative" }
        return current + 1
    }
}
