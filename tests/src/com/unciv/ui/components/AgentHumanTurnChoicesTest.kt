package com.unciv.ui.components

import com.unciv.logic.agents.AgentMatchSession
import com.unciv.UncivGame
import com.unciv.models.metadata.GameSettings
import com.unciv.testing.GdxTestRunner
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.ui.screens.worldscreen.status.NextTurnAction
import com.unciv.ui.screens.worldscreen.status.NextTurnButton
import com.unciv.view.CivView
import com.unciv.view.GameView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Answers
import org.mockito.Mockito

@RunWith(GdxTestRunner::class)
class AgentHumanTurnChoicesTest {
    private class Fixture {
        val screen = Mockito.mock(WorldScreen::class.java)
        val session = Mockito.mock(AgentMatchSession::class.java)
        val civ = Mockito.mock(CivView::class.java)
        init {
            val game = Mockito.mock(UncivGame::class.java)
            Mockito.`when`(game.settings).thenReturn(GameSettings().apply { checkForDueUnits = false })
            Mockito.`when`(screen.game).thenReturn(game)
            val gameInfo = Mockito.mock(com.unciv.logic.GameInfo::class.java)
            Mockito.`when`(screen.gameInfo).thenReturn(gameInfo)
            val view = Mockito.mock(GameView::class.java)
            Mockito.`when`(screen.agentSession).thenReturn(session)
            Mockito.`when`(screen.selectedGameView).thenReturn(view)
            Mockito.`when`(view.civView).thenReturn(civ)
            Mockito.`when`(civ.cities()).thenReturn(emptyList())
            Mockito.`when`(session.isHumanInputAllowed()).thenReturn(true)
        }
        fun choice(): NextTurnAction {
            val button = Mockito.mock(NextTurnButton::class.java, Answers.CALLS_REAL_METHODS)
            val method = NextTurnButton::class.java.getDeclaredMethod("getNextTurnAction", WorldScreen::class.java)
                .apply { isAccessible = true }
            return method.invoke(button, screen) as NextTurnAction
        }
    }

    @Test fun `active human can open mandatory world leader vote`() {
        val f = Fixture()
        Mockito.`when`(f.civ.mayVoteForDiplomaticVictory()).thenReturn(true)
        assertEquals(NextTurnAction.WorldCongressVote, f.choice())
    }

    @Test fun `active human can select needed research before session end turn`() {
        val f = Fixture()
        Mockito.`when`(f.civ.shouldOpenTechPicker()).thenReturn(true)
        assertEquals(NextTurnAction.PickTech, f.choice())
    }

    @Test fun `unsupported pantheon choice blocks session end turn explicitly`() {
        val f = Fixture(); Mockito.`when`(f.civ.canFoundPantheon()).thenReturn(true)
        assertNotEquals(NextTurnAction.AgentMatch, f.choice())
        assertTrue(f.choice().getText(f.screen).contains("Unsupported"))
    }

    @Test fun `unsupported spy choice blocks session end turn explicitly`() {
        val f = Fixture()
        Mockito.`when`(f.screen.gameInfo.isEspionageEnabled()).thenReturn(true)
        Mockito.`when`(f.civ.shouldShowMoveSpies()).thenReturn(true)
        assertNotEquals(NextTurnAction.AgentMatch, f.choice())
        assertTrue(f.choice().getText(f.screen).contains("Unsupported"))
    }

    @Test fun `nonhuman view retains session status even with pending choices`() {
        val f = Fixture()
        Mockito.`when`(f.session.isHumanInputAllowed()).thenReturn(false)
        Mockito.`when`(f.civ.mayVoteForDiplomaticVictory()).thenReturn(true)
        assertEquals(NextTurnAction.AgentMatch, f.choice())
    }

    @Test fun `human with no outstanding choice still ends through session`() {
        val f = Fixture()
        assertEquals(NextTurnAction.AgentMatch, f.choice())
    }
}
