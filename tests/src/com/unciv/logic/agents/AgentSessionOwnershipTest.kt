package com.unciv.logic.agents

import com.badlogic.gdx.scenes.scene2d.Stage
import com.unciv.logic.event.EventBus
import com.unciv.testing.TestGame
import com.unciv.testing.BaseTestRunner
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.ui.screens.worldscreen.status.StatusButtons
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Answers
import org.mockito.Mockito

@RunWith(BaseTestRunner::class)
class AgentSessionOwnershipTest {
    @Test fun `disposing world closes its attached session once before drawing resources`() {
        val world = Mockito.mock(WorldScreen::class.java, Answers.CALLS_REAL_METHODS)
        val session = Mockito.mock(AgentMatchSession::class.java)
        val stage = Mockito.mock(Stage::class.java)
        val status = Mockito.mock(StatusButtons::class.java)
        field(BaseScreen::class.java, "stage").set(world, stage)
        field(WorldScreen::class.java, "events").set(world, EventBus.EventReceiver())
        field(WorldScreen::class.java, "statusButtons").set(world, status)
        world.agentSession = session
        world.dispose()
        world.dispose()
        assertNull(world.agentSession)
        val order = Mockito.inOrder(session, status, stage)
        order.verify(session).close()
        order.verify(status).dispose()
        order.verify(stage).dispose()
        Mockito.verify(session, Mockito.times(1)).close()
    }

    @Test fun `match factory receives the exact newly created world owner`() {
        val game = TestGame()
        val rome = game.addCiv(game.ruleset.nations["Rome"]!!, isPlayer = true)
        val setup = AgentMatchSetup().apply {
            slots[rome.nation.name] = AgentSlotConfig(rome.nation.name, AgentProfile())
        }
        val world = Mockito.mock(WorldScreen::class.java)
        val session = Mockito.mock(AgentMatchSession::class.java)
        val prior = AgentMatchSessionFactory.creator
        try {
            AgentMatchSessionFactory.creator = { info, match, config, owner ->
                assertSame(game.gameInfo, info)
                assertSame(setup, config)
                assertSame(world, owner)
                assertEquals(ControllerKind.EXTERNAL_AGENT, match.controllers[rome.civID]!!.kind)
                session
            }
            assertSame(session, AgentMatchSessionFactory.create(game.gameInfo, setup, world))
            assertNull(AgentMatchSessionFactory.create(game.gameInfo, AgentMatchSetup(), world))
        } finally { AgentMatchSessionFactory.creator = prior }
    }

    private fun field(type: Class<*>, name: String) = type.getDeclaredField(name).apply { isAccessible = true }
}
