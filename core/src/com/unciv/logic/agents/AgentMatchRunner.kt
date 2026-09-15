package com.unciv.logic.agents

import java.util.UUID

/**
 * Scripted match driver (AI_AGENT_PLAN.md §9 step/run semantics, §13.3).
 *
 * Owns a [MatchCoordinator] and advances it turn by turn using per-civ
 * [AgentPolicy] implementations. Production wires provider-backed policies
 * (prompt → model → tool calls) behind the same interface; deterministic
 * scripted policies prove the loop headlessly. The desktop owns runner
 * instances, threads, and UI; this class owns no threads and no UI.
 *
 * One [runTurn] = one civilization's turn through its end-turn checkpoint
 * (plan "Step civilization turn"); [runRounds] repeats full rounds.
 * A quiet policy (no commands, no end-turn) leaves an incomplete turn with
 * committed moves retained — never auto-ended, never silently automated.
 */
fun interface AgentPolicy {
    /** Commands for one policy step; empty means the policy is done for now. */
    fun takeTurn(context: TurnContext): List<AgentCommand>
}

class TurnContext(val coordinator: MatchCoordinator, val civId: String) {
    fun revision(): Int = coordinator.getStateRevision()
    fun newActionId(prefix: String): String = "$prefix-${UUID.randomUUID()}"
}

/** Immediately ends the turn: the null policy for loop mechanics. */
class PassPolicy : AgentPolicy {
    override fun takeTurn(context: TurnContext): List<AgentCommand> = listOf(
        EndTurnCommand(context.civId, context.newActionId("pass"), context.revision())
    )
}

class TurnReport(
    val civId: String,
    val commandsApplied: Int,
    val endTurnCommitted: Boolean,
    val endReceipt: ActionReceipt?
)

class AgentMatchRunner(
    val coordinator: MatchCoordinator,
    private val policyFor: (civId: String) -> AgentPolicy,
    private val maxCommandsPerTurn: Int = 500
) {
    fun runTurn(): TurnReport {
        if (coordinator.getTurnPhase() == TurnPhase.READY_TO_START)
            coordinator.beginAwaitingTurn()
        check(coordinator.getTurnPhase() == TurnPhase.AWAITING_DECISIONS) {
            "runTurn requires an awaiting turn, current is ${coordinator.getTurnPhase()}"
        }
        val civId = coordinator.getCurrentCivId()
        val context = TurnContext(coordinator, civId)
        var applied = 0
        while (applied < maxCommandsPerTurn) {
            val commands = policyFor(civId).takeTurn(context)
            if (commands.isEmpty()) break
            for (command in commands) {
                val result = coordinator.execute(command)
                applied++
                if (command is EndTurnCommand)
                    return TurnReport(civId, applied, true, (result as TurnEndedResult).receipt)
            }
        }
        return TurnReport(civId, applied, false, null)
    }

    /** Advances [rounds] full rounds (game turn counter), then stops. */
    fun runRounds(rounds: Int): List<TurnReport> {
        val reports = ArrayList<TurnReport>()
        val targetTurns = coordinator.getGameTurns() + rounds
        var guard = 256
        while (coordinator.getGameTurns() < targetTurns && guard-- > 0) {
            if (coordinator.getTurnPhase() == TurnPhase.COMPLETED) break
            reports.add(runTurn())
        }
        check(coordinator.getGameTurns() >= targetTurns) {
            "rounds did not advance within the step bound"
        }
        return reports
    }
}
