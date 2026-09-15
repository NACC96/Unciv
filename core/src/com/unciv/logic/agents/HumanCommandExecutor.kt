package com.unciv.logic.agents

/**
 * Human-through-coordinator execution (AI_AGENT_PLAN.md §3.2, §11.2).
 * In an agent-enabled match, a HUMAN-controlled civilization acts through
 * the same validated coordinator handlers as the external agents — the
 * legacy engine path stays for ordinary (session-less) games only.
 *
 * The desktop [com.unciv.app.desktop.agents.DesktopAgentMatch] delegates to
 * this class under its host lock; core tests exercise it headlessly.
 */
class HumanCommandExecutor(private val coordinator: MatchCoordinator) {

    /** The civ whose turn it is, when it is human-controlled and commandable. */
    fun currentHumanCivId(): String? {
        if (coordinator.getTurnPhase() != TurnPhase.AWAITING_DECISIONS) return null
        val current = coordinator.getCurrentCivId()
        return coordinator.getController(current)
            ?.takeIf { it.kind == ControllerKind.HUMAN }
            ?.let { current }
    }

    /** Whether human gameplay input may execute right now. */
    fun isHumanTurn(): Boolean = currentHumanCivId() != null

    /**
     * Executes one human gameplay command. The expected revision is stamped
     * here — UI callers never track coordinator revisions themselves.
     * Returns null on success, or a human-readable error.
     */
    fun execute(actorCivId: String?, command: AgentCommand): String? =
        executeAll(actorCivId) { listOf({ command }) }

    /**
     * Executes command factories in order; each factory is invoked with the
     * revision current at its own turn (revisions advance between commands).
     * First rejection stops the rest and its message is returned.
     */
    fun executeAll(
        actorCivId: String?,
        build: (expectedRevision: Int) -> List<(Int) -> AgentCommand>
    ): String? {
        val civId = currentHumanCivId()
            ?: return "It is not a human civilization's turn"
        if (actorCivId != null && actorCivId != civId)
            return "Action belongs to $actorCivId, but the active civilization is $civId"
        for (factory in build(coordinator.getStateRevision())) {
            try {
                coordinator.execute(factory(coordinator.getStateRevision()))
            } catch (ex: AgentCommandException) {
                return ex.message ?: "Action rejected"
            } catch (ex: Exception) {
                return "Action failed: ${ex.message}"
            }
        }
        return null
    }
}
