package com.unciv.logic.agents

import org.junit.Assert.*
import org.junit.Test

class AgentRunTimingTest {
    @Test fun `timer records only the admitted invocation and resets for the next run`() {
        var nanos = 5_000_000_000L
        val timer = AgentRunTimer { nanos }
        assertNull(timer.snapshot())
        timer.start("run-1", "Rome")
        assertEquals(AgentRunTiming("run-1", "Rome", 0), timer.snapshot())
        nanos += 65_400_000_000L
        val first = timer.snapshot()!!
        assertEquals(65_400L, first.elapsedMillis)
        timer.clear()
        assertNull(timer.snapshot())
        nanos += 9_000_000_000L // Paused time outside a provider invocation does not count.
        timer.start("run-2", "Korea")
        assertEquals(AgentRunTiming("run-2", "Korea", 0), timer.snapshot())
        assertEquals(65_400L, first.elapsedMillis) // The returned value is a snapshot.
    }

    @Test fun `clock values may be negative or wrap without corrupting elapsed time`() {
        var nanos = Long.MAX_VALUE - 500_000_000L
        val timer = AgentRunTimer { nanos }
        timer.start("run", "Rome")
        nanos += 1_200_000_000L
        assertEquals(1_200L, timer.snapshot()!!.elapsedMillis)
    }

    @Test fun `elapsed snapshots stay nonnegative and never go backwards`() {
        var nanos = -4_000_000_000L
        val timer = AgentRunTimer { nanos }
        timer.start("run", "Rome")
        nanos += 2_000_000_000L
        assertEquals(2_000L, timer.snapshot()!!.elapsedMillis)
        nanos -= 3_000_000_000L
        assertEquals(2_000L, timer.snapshot()!!.elapsedMillis)
    }

    @Test fun `duration formatting covers minute and hour boundaries`() {
        assertEquals("00:00", formatAgentRunDuration(-1))
        assertEquals("00:00", formatAgentRunDuration(999))
        assertEquals("01:05", formatAgentRunDuration(65_000))
        assertEquals("59:59", formatAgentRunDuration(3_599_999))
        assertEquals("1:00:00", formatAgentRunDuration(3_600_000))
        assertEquals("12:34:56", formatAgentRunDuration(45_296_000))
    }
}
