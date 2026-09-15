package com.unciv.logic.agents

import com.unciv.testing.BaseTestRunner
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith

/** Engine-side run-state machine (AI_AGENT_PLAN.md §9), paired with the TS copy. */
@RunWith(BaseTestRunner::class)
class AgentRunControlsTest {

    @Test
    fun `pause never kills, steps return to paused`() {
        Assert.assertEquals(AgentRunState.PAUSING,
            AgentRunControls.transition(AgentRunState.AGENT_DECIDING, AgentControl.PAUSE)!!.to)
        Assert.assertEquals(AgentRunState.PAUSED,
            AgentRunControls.transition(AgentRunState.PAUSED, AgentControl.STEP_ACTION)!!.to)
        Assert.assertEquals(AgentRunState.PAUSED,
            AgentRunControls.transition(AgentRunState.PAUSED, AgentControl.STEP_TURN)!!.to)
        Assert.assertEquals(AgentRunState.STARTING,
            AgentRunControls.transition(AgentRunState.PAUSED, AgentControl.RUN)!!.to)
        Assert.assertNull(AgentRunControls.transition(AgentRunState.FINISHED, AgentControl.RUN))
        Assert.assertEquals(AgentRunState.WAITING_HUMAN,
            AgentRunControls.transition(AgentRunState.WAITING_HUMAN, AgentControl.PAUSE)!!.to)
    }

    @Test
    fun `recovery controls keep their guarantees`() {
        val retry = AgentRunControls.transition(AgentRunState.NEEDS_ATTENTION, AgentControl.RETRY)!!
        Assert.assertTrue(retry.guarantee.contains("exact identity"))
        val takeover = AgentRunControls.transition(AgentRunState.PAUSED, AgentControl.TAKEOVER)!!
        Assert.assertEquals(AgentRunState.WAITING_HUMAN, takeover.to)
        Assert.assertTrue(takeover.guarantee.contains("Revoke old authority first"))
    }

    @Test
    fun `epochs advance monotonically`() {
        Assert.assertEquals(2, AgentRunControls.nextEpoch(1))
        try {
            AgentRunControls.nextEpoch(-1)
            Assert.fail("expected IllegalArgumentException")
        } catch (ex: IllegalArgumentException) {
            // expected
        }
    }
}
