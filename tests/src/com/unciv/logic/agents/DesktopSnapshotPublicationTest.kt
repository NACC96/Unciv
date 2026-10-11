package com.unciv.logic.agents

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Graphics
import com.unciv.app.desktop.agents.DesktopAgentMatch
import com.unciv.logic.GameInfo
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import com.unciv.ui.screens.worldscreen.WorldScreen
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Answers
import org.mockito.Mockito
import java.nio.file.Files

/** Real engine/checkpoint/publication flow; only the GL drawing boundary is replaced. */
@RunWith(BaseTestRunner::class)
class DesktopSnapshotPublicationTest {
    private var previousApp: Application? = null
    private var previousGraphics: Graphics? = null

    @Before
    fun runPresentationTasksWithoutAWindow() {
        previousApp = Gdx.app
        previousGraphics = Gdx.graphics
        Gdx.app = Mockito.mock(Application::class.java)
        Gdx.graphics = Mockito.mock(Graphics::class.java)
        Mockito.doAnswer { invocation ->
            invocation.getArgument<Runnable>(0).run()
            null
        }.`when`(Gdx.app).postRunnable(Mockito.any(Runnable::class.java))
    }

    @After
    fun restoreGraphicsBoundary() {
        Gdx.app = previousApp
        Gdx.graphics = previousGraphics
    }

    private class Fixture {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_Vanilla)
        val published = ArrayList<GameInfo>()
        val host: DesktopAgentMatch
        val romeId: String
        val warriorId: Int
        val settlerId: Int

        init {
            game.gameInfo.gameParameters.baseRuleset = BaseRuleset.Civ_V_Vanilla.fullName
            game.makeHexagonalMap(4)
            val rome = game.addCiv(game.ruleset.nations["Rome"]!!, isPlayer = true)
            romeId = rome.civID
            warriorId = game.addUnit("Warrior", rome, game.getTile(0, 0)).id
            settlerId = game.addUnit("Settler", rome, game.getTile(0, 0)).id
            val match = AgentMatchState(matchId = "publication-test", timelineId = "t1",
                turnPhase = TurnPhase.AWAITING_DECISIONS)
            match.controllers[romeId] = CivilizationController(romeId, ControllerKind.EXTERNAL_AGENT,
                AgentProfile("p1", "Rome agent", "codex", "gpt-6-luna", "xhigh"))
            val screen = Mockito.mock(WorldScreen::class.java) { invocation ->
                if (invocation.method.name == "installAgentSnapshot") {
                    published.add(invocation.getArgument(0))
                    null
                } else Answers.RETURNS_DEFAULTS.answer(invocation)
            }
            Mockito.`when`(screen.currentViewerCivId()).thenReturn(romeId)
            host = DesktopAgentMatch(game.gameInfo, match, AgentMatchSetup(),
                Files.createTempDirectory("unciv-publication-").toFile(), worldScreen = screen)
        }

        fun act(id: String, type: String, args: Map<String, Any?>): Any? = host.answerEngineRequest("act",
            mapOf("protocolVersion" to 1, "actorCivId" to romeId, "actionId" to id,
                "expectedRevision" to host.coordinator.getStateRevision(), "type" to type, "args" to args))
    }

    @Test
    fun `committed warrior movement reaches the map before the provider ends its turn`() {
        val f = Fixture()
        f.act("move-1", "unit.move", mapOf("unitId" to f.warriorId,
            "destination" to mapOf("x" to 1, "y" to 0)))

        Assert.assertTrue("Every committed move must publish a render snapshot immediately", f.published.isNotEmpty())
        val unit = f.published.last().getCivilization(f.romeId).units.getCivUnits().first { it.id == f.warriorId }
        Assert.assertEquals(HexCoord(1, 0), unit.getTile().position)
        Assert.assertEquals("The rendered source must stay isolated from engine mutations", HexCoord.Zero,
            f.game.gameInfo.getCivilization(f.romeId).units.getCivUnits().first { it.id == f.warriorId }.getTile().position)
    }

    @Test
    fun `founding replaces the settler with a city in the published snapshot`() {
        val f = Fixture()
        f.act("found-1", "unit.found", mapOf("unitId" to f.settlerId))

        Assert.assertTrue("A founded city must appear without waiting for the full turn", f.published.isNotEmpty())
        val rome = f.published.last().getCivilization(f.romeId)
        Assert.assertEquals(HexCoord.Zero, rome.cities.single().location)
        Assert.assertFalse(rome.units.getCivUnits().any { it.id == f.settlerId })
    }

    @Test
    fun `reads and rejected mutations leave the rendered snapshot alone`() {
        val f = Fixture()
        f.host.answerEngineRequest("observe", mapOf("civId" to f.romeId))
        try {
            f.act("invalid-move", "unit.move", mapOf("unitId" to -1,
                "destination" to mapOf("x" to 1, "y" to 0)))
            Assert.fail("A nonexistent unit must be rejected")
        } catch (_: EngineCallException) {
            // The renderer must not receive uncommitted or fabricated state.
        }
        Assert.assertTrue(f.published.isEmpty())
    }

    @Test
    fun `a snapshot queued before close never updates the detached screen`() {
        val queued = ArrayList<Runnable>()
        Mockito.doAnswer { invocation ->
            queued.add(invocation.getArgument(0))
            null
        }.`when`(Gdx.app).postRunnable(Mockito.any(Runnable::class.java))
        val f = Fixture()
        f.act("move-before-close", "unit.move", mapOf("unitId" to f.warriorId,
            "destination" to mapOf("x" to 1, "y" to 0)))
        Assert.assertTrue("The regression requires a pending GL publication", queued.isNotEmpty())

        f.host.close()
        queued.forEach { it.run() }

        Assert.assertTrue("Disposed screens must not receive previously queued snapshots", f.published.isEmpty())
    }
}
