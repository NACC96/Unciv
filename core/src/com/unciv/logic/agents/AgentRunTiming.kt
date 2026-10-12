package com.unciv.logic.agents

/** A copied, transient measurement of an admitted invocation; never checkpoint metadata. */
data class AgentRunTiming(val runId: String, val civId: String, val elapsedMillis: Long)

/** Monotonic elapsed time, independent of wall time, UI visibility, and requested pauses. */
class AgentRunTimer(private val clockNanos: () -> Long = System::nanoTime) {
    private data class Active(val runId: String, val civId: String, val startedNanos: Long, var elapsedMillis: Long = 0)
    private var active: Active? = null

    @Synchronized fun start(runId: String, civId: String) {
        active = Active(runId, civId, clockNanos())
    }

    @Synchronized fun clear() { active = null }

    @Synchronized fun snapshot(): AgentRunTiming? {
        val run = active ?: return null
        // Subtraction handles nanoTime's signed origin and rollover for normal invocation lengths.
        val elapsed = ((clockNanos() - run.startedNanos) / 1_000_000).coerceAtLeast(0)
        run.elapsedMillis = maxOf(run.elapsedMillis, elapsed)
        return AgentRunTiming(run.runId, run.civId, run.elapsedMillis)
    }
}

fun formatAgentRunDuration(elapsedMillis: Long): String {
    val seconds = elapsedMillis.coerceAtLeast(0) / 1_000
    val minutes = seconds / 60
    val secondsText = (seconds % 60).toString().padStart(2, '0')
    return if (minutes < 60) "${minutes.toString().padStart(2, '0')}:$secondsText"
    else "${minutes / 60}:${(minutes % 60).toString().padStart(2, '0')}:$secondsText"
}
