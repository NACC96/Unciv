package com.unciv.dev

import com.unciv.logic.GameInfo
import com.unciv.logic.agents.AgentMatchSession
import com.unciv.logic.agents.AgentRunState
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.ui.screens.worldscreen.status.NextTurnAction
import com.unciv.ui.screens.worldscreen.status.NextTurnButton
import com.unciv.ui.screens.worldscreen.status.SmallUnitButton
import com.unciv.ui.screens.worldscreen.unit.AutoPlay
import com.unciv.view.CityView
import com.unciv.view.CivView
import com.unciv.view.GameView
import org.mockito.Mockito

/** Synthetic predicate inputs, resolved by the production NextTurnButton choice path.
 * No actions execute and no provider/session authority is changed. */
internal object AtlasTurnFixtures {
    val cases = NextTurnAction.entries.filter { it !in setOf(NextTurnAction.Default, NextTurnAction.AgentMatch) }
        .map { it.name } + listOf("AgentMatchPaused", "AgentMatchHuman", "Autosave", "AgentPickTech")

    data class Evidence(val input: String, val selected: String, val route: String)

    fun button(case: String, host: WorldScreen): NextTurnButton {
        require(case in cases)
        val game = host.game
        val stage = host.stage
        val parameters = host.gameInfo.gameParameters
        val screen = Mockito.mock(WorldScreen::class.java)
        fun stub(name: String, value: Any) {
            val method = screen.javaClass.methods.single { it.name.substringBefore('$') == name }
            Mockito.`when`(method.invoke(screen)).thenReturn(value)
        }
        val view = Mockito.mock(GameView::class.java)
        val civ = Mockito.mock(CivView::class.java)
        val info = Mockito.mock(GameInfo::class.java)
        val auto = Mockito.mock(AutoPlay::class.java)
        Mockito.`when`(screen.game).thenReturn(game)
        Mockito.`when`(screen.stage).thenReturn(stage)
        Mockito.`when`(screen.gameInfo).thenReturn(info)
        Mockito.`when`(info.gameParameters).thenReturn(parameters)
        Mockito.`when`(screen.selectedGameView).thenReturn(view)
        Mockito.`when`(view.civView).thenReturn(civ)
        Mockito.`when`(civ.cities()).thenReturn(emptyList())
        Mockito.`when`(screen.autoPlay).thenReturn(auto)
        Mockito.`when`(screen.isPlayersTurn).thenReturn(true)
        val small = screen.javaClass.methods.single { it.name.substringBefore('$') == "getSmallUnitButton" }
        Mockito.`when`(small.invoke(screen)).thenReturn(Mockito.mock(SmallUnitButton::class.java))
        val expected = when (case) {
            "AgentMatchPaused", "AgentMatchHuman" -> NextTurnAction.AgentMatch
            "Autosave" -> NextTurnAction.NextTurn
            "AgentPickTech" -> NextTurnAction.PickTech
            else -> NextTurnAction.valueOf(case)
        }
        if (case.startsWith("Agent")) {
            val session = Mockito.mock(AgentMatchSession::class.java)
            Mockito.`when`(screen.agentSession).thenReturn(session)
            Mockito.`when`(session.isHumanInputAllowed()).thenReturn(case != "AgentMatchPaused")
            Mockito.`when`(session.statusText()).thenReturn(if (case == "AgentMatchPaused") "Paused · America" else "Your turn · America")
            // The turn button shows the short shared "state · nation"; statusText() stays in logs.
            Mockito.`when`(session.getRunState()).thenReturn(if (case == "AgentMatchPaused") AgentRunState.PAUSED else AgentRunState.WAITING_HUMAN)
            Mockito.`when`(session.getActiveCivId()).thenReturn("America")
        }
        when (expected) {
            NextTurnAction.AgentUnsupportedChoice -> Mockito.`when`(civ.canFoundPantheon()).thenReturn(true)
            NextTurnAction.RetryUpload -> Mockito.`when`(screen.failedUpload).thenReturn(true)
            NextTurnAction.AutoPlay -> Mockito.`when`(auto.isAutoPlaying()).thenReturn(true)
            NextTurnAction.Working -> stub("isNextTurnUpdateRunning", true)
            NextTurnAction.Waiting -> Mockito.`when`(screen.isPlayersTurn).thenReturn(false)
            NextTurnAction.PickGreatPerson -> stub("hasPendingFreeGreatPerson", true)
            NextTurnAction.PickConstruction -> {
                val city = Mockito.mock(CityView::class.java)
                Mockito.`when`(city.currentConstructionName()).thenReturn("")
                Mockito.`when`(civ.cities()).thenReturn(listOf(city))
            }
            NextTurnAction.PickTech -> Mockito.`when`(civ.shouldOpenTechPicker()).thenReturn(true)
            NextTurnAction.PickPolicy -> Mockito.`when`(civ.shouldShowPolicyPicker()).thenReturn(true)
            NextTurnAction.MoveSpies -> {
                Mockito.`when`(info.isEspionageEnabled()).thenReturn(true)
                Mockito.`when`(civ.shouldShowMoveSpies()).thenReturn(true)
            }
            NextTurnAction.FoundPantheon -> Mockito.`when`(civ.canFoundPantheon()).thenReturn(true)
            NextTurnAction.ExpandPantheon -> Mockito.`when`(civ.canExpandPantheon()).thenReturn(true)
            NextTurnAction.FoundReligion -> Mockito.`when`(civ.isFoundingReligion()).thenReturn(true)
            NextTurnAction.EnhanceReligion -> Mockito.`when`(civ.isEnhancingReligion()).thenReturn(true)
            NextTurnAction.ReformReligion -> Mockito.`when`(civ.hasFreeBeliefs()).thenReturn(true)
            NextTurnAction.WorldCongressVote -> Mockito.`when`(civ.mayVoteForDiplomaticVictory()).thenReturn(true)
            NextTurnAction.NextUnit -> {
                host.game.settings.checkForDueUnits = true
                Mockito.`when`(civ.dueUnitsCount()).thenReturn(12)
            }
            NextTurnAction.MoveAutomatedUnits -> {
                host.game.settings.automatedUnitsMoveOnTurnStart = false
                Mockito.`when`(civ.hasUnitsReadyToAutomate()).thenReturn(true)
            }
            else -> Unit
        }
        if (case == "Autosave") stub("getWaitingForAutosave", true)
        val button = NextTurnButton(screen)
        button.update()
        val selected = NextTurnButton::class.java.getDeclaredField("nextTurnAction").apply { isAccessible = true }.get(button) as NextTurnAction
        check(selected == expected) { "$case resolved to $selected, expected $expected" }
        button.name = "AtlasTurnEvidence:$case"
        button.userObject = Evidence(case, selected.name, if (case.startsWith("Agent")) "agent session predicate" else "native predicate")
        return button
    }
}
