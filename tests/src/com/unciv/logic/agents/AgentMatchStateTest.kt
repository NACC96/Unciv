package com.unciv.logic.agents

import com.unciv.testing.BaseTestRunner
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith

/**
 * W2 foundation: the §4.1 minimum types must survive a serialization
 * roundtrip unchanged, deep-copy without shared mutable state (candidate
 * isolation depends on this), and reject unknown format versions instead
 * of silently converting them (AI_AGENT_PLAN.md §4.1, §10.2).
 */
@RunWith(BaseTestRunner::class)
class AgentMatchStateTest {
    private val json = com.unciv.json.json()

    private fun sampleState(): AgentMatchState {
        val state = AgentMatchState(
            matchId = "match-1",
            timelineId = "timeline-1",
            stateRevision = 42,
            turnSerial = 7,
            turnPhase = TurnPhase.AWAITING_DECISIONS
        )
        state.controllers["Rome"] = CivilizationController(
            "Rome",
            ControllerKind.EXTERNAL_AGENT,
            AgentProfile("p1", "Ada", "codex", "gpt-5.2-codex", "high", "Bold explorer", 3)
        )
        state.controllers["Greece"] = CivilizationController("Greece", ControllerKind.BUILTIN_AI, null)
        state.controllers["Egypt"] = CivilizationController("Egypt", ControllerKind.HUMAN, null)
        state.privateNotes["Rome"] = "Settle the river bend; befriend Greece."
        state.notesRevisions["Rome"] = 2
        state.pendingDecisions.add(PendingDecision(
            "d1", "conquest-disposition", "Rome", "city:Antium",
            DecisionClassification.MANDATORY,
            arrayListOf(hashMapOf("optionId" to "puppet", "label" to "Puppet"))
        ))
        state.guidanceQueue.add(GuidanceMessage("g1", "Rome", 12345L, GuidanceState.QUEUED, "Hold the pass."))
        state.currentTurnReceipts.add(ActionReceipt(
            "a1", "Rome", "timeline-1", "fp-1", 42, 43, "applied", arrayListOf("ev-9")
        ))
        return state
    }

    @Test
    fun `match state survives serialization roundtrip`() {
        val state = sampleState()
        val serialized = json.toJson(state)
        Assert.assertTrue(serialized.isNotBlank())
        val restored = json.fromJson(AgentMatchState::class.java, serialized)
        restored.validateFormat()

        Assert.assertEquals(42, restored.stateRevision)
        Assert.assertEquals(7, restored.turnSerial)
        Assert.assertEquals(TurnPhase.AWAITING_DECISIONS, restored.turnPhase)
        Assert.assertEquals(
            setOf("Rome", "Greece", "Egypt"),
            restored.controllers.keys
        )
        val rome = restored.controllers["Rome"]!!
        Assert.assertEquals(ControllerKind.EXTERNAL_AGENT, rome.kind)
        Assert.assertEquals("Ada", rome.agentProfile!!.displayName)
        Assert.assertEquals("codex", rome.agentProfile!!.provider)
        Assert.assertEquals("High".lowercase(), rome.agentProfile!!.reasoning)
        Assert.assertEquals(3, rome.agentProfile!!.profileRevision)
        Assert.assertEquals("Settle the river bend; befriend Greece.", restored.privateNotes["Rome"])
        Assert.assertEquals(2, restored.notesRevisions["Rome"])
        Assert.assertEquals(1, restored.pendingDecisions.size)
        Assert.assertEquals("d1", restored.pendingDecisions[0].decisionId)
        Assert.assertEquals("puppet", restored.pendingDecisions[0].options[0]["optionId"])
        Assert.assertEquals(GuidanceState.QUEUED, restored.guidanceQueue[0].state)
        Assert.assertEquals(43, restored.currentTurnReceipts[0].afterRevision)
        Assert.assertEquals(listOf("ev-9"), restored.currentTurnReceipts[0].committedEventIds)
    }

    @Test
    fun `deep copy shares no mutable state with the original`() {
        val state = sampleState()
        val copy = state.deepCopy()

        copy.controllers["Rome"]!!.agentProfile!!.personality = "CHANGED"
        copy.controllers["Aztec"] = CivilizationController("Aztec", ControllerKind.HUMAN, null)
        copy.privateNotes["Rome"] = "CHANGED"
        copy.notesRevisions["Rome"] = 99
        copy.pendingDecisions[0].options[0]["optionId"] = "CHANGED"
        copy.pendingDecisions.add(PendingDecision("d2", "x", "Rome", "y"))
        copy.guidanceQueue[0].state = GuidanceState.DELIVERED
        copy.currentTurnReceipts[0].committedEventIds.add("CHANGED")
        copy.stateRevision = 999

        Assert.assertEquals("Bold explorer", state.controllers["Rome"]!!.agentProfile!!.personality)
        Assert.assertEquals(3, state.controllers.size)
        Assert.assertEquals("Settle the river bend; befriend Greece.", state.privateNotes["Rome"])
        Assert.assertEquals(2, state.notesRevisions["Rome"])
        Assert.assertEquals("puppet", state.pendingDecisions[0].options[0]["optionId"])
        Assert.assertEquals(1, state.pendingDecisions.size)
        Assert.assertEquals(GuidanceState.QUEUED, state.guidanceQueue[0].state)
        Assert.assertEquals(listOf("ev-9"), state.currentTurnReceipts[0].committedEventIds)
        Assert.assertEquals(42, state.stateRevision)
    }

    @Test
    fun `profile edits do not leak into running matches without an explicit copy step`() {
        // Controllers hold snapshot copies: mutating the source profile object
        // after assignment cannot affect the match (plan §4.1).
        val profile = AgentProfile("p1", "Ada", "codex", "m1", null, "Calm", 1)
        val state = AgentMatchState()
        state.controllers["Rome"] = CivilizationController("Rome", ControllerKind.EXTERNAL_AGENT, profile.copy())
        profile.personality = "RECKLESS"
        profile.profileRevision = 2
        Assert.assertEquals("Calm", state.controllers["Rome"]!!.agentProfile!!.personality)
        Assert.assertEquals(1, state.controllers["Rome"]!!.agentProfile!!.profileRevision)
    }

    @Test
    fun `unsupported format versions are rejected explicitly`() {
        val state = sampleState()
        state.formatVersion = 999
        try {
            state.validateFormat()
            Assert.fail("expected IllegalArgumentException for format 999")
        } catch (ex: IllegalArgumentException) {
            Assert.assertTrue(ex.message!!.contains("999"))
        }
        // ...and such a payload must not validate after a roundtrip either.
        val restored = json.fromJson(
            AgentMatchState::class.java,
            json.toJson(state)
        )
        Assert.assertEquals(999, restored.formatVersion)
        try {
            restored.validateFormat()
            Assert.fail("expected IllegalArgumentException after roundtrip")
        } catch (ex: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `unknown future fields are tolerated on load`() {
        // Gdx JSON ignores unknown fields (plan §2): older builds can still
        // read the game payload portion of a newer bundle's metadata.
        val serialized = json.toJson(sampleState())
            .replaceFirst("{", "{\"someFutureField\":12345,")
        val restored = json.fromJson(AgentMatchState::class.java, serialized)
        Assert.assertEquals(42, restored.stateRevision)
    }
}
