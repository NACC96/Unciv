package com.unciv.ui.screens.worldscreen.overview

import com.unciv.logic.GameInfo
import com.unciv.logic.agents.AgentMatchSession
import com.unciv.logic.agents.AgentRunState
import com.unciv.logic.civilization.Civilization
import com.unciv.ui.screens.victoryscreen.RankingType
import com.unciv.ui.screens.victoryscreen.VictoryScreen
import kotlin.math.roundToInt

/** Values copied from the published game, with private information removed before it reaches UI. */
data class CivilizationOverviewData(val turn: Int, val year: Int, val civilizations: List<CivilizationSummary>, val runState: AgentRunState?) {
    companion object {
        fun read(game: GameInfo, viewer: Civilization, session: AgentMatchSession?): CivilizationOverviewData {
            val spectator = viewer.isSpectator()
            val active = session?.getActiveCivId() ?: game.currentPlayer
            val rows = game.civilizations.filter {
                !it.isBarbarian && !it.isSpectator() && (spectator || it.civID == viewer.civID || viewer.knows(it))
            }.map { civ ->
                val rankings = VictoryScreen.canViewCivStats(game, viewer, civ)
                // The ranking option does not authorize research queues, economy, units or city details.
                val full = spectator || civ.civID == viewer.civID
                val profile = session?.getAgentProfile(civ.civID)
                val stats = civ.stats.statsForNextTurn
                val research = if (full) civ.tech.currentTechnologyName() else null
                CivilizationSummary(
                    civ.civID, civ.isCityState, civ.isDefeated(), civ.civID == active,
                    profile?.displayName?.takeIf { it.isNotBlank() },
                    profile?.let { "${it.modelId} · ${it.reasoning ?: "Default"}" } ?: if (civ.isHuman()) "Human" else "AI",
                    if (rankings) civ.getStatForRanking(RankingType.Score) else null,
                    if (full) civ.cities.size else null,
                    if (rankings) civ.getStatForRanking(RankingType.Population) else null,
                    if (full) civ.gold else null, if (full) stats.gold.roundToInt() else null,
                    if (full) stats.science.roundToInt() else null, if (full) stats.culture.roundToInt() else null,
                    if (full) stats.production.roundToInt() else null, if (full) stats.faith.roundToInt() else null,
                    if (full) civ.getHappiness() else null,
                    if (rankings) civ.getStatForRanking(RankingType.Force) else null,
                    if (rankings) civ.tech.researchedTechnologies.size else null,
                    if (full) civ.tech.era.name else null, research,
                    if (research != null) civ.tech.researchOfTech(research) else null,
                    if (research != null) civ.tech.costOfTech(research) else null,
                    if (research != null) civ.tech.turnsToTech(research) else null,
                    if (full) civ.policies.storedCulture else null,
                    if (full) civ.cities.map { CitySummary(it.name, it.population.population, it.cityConstructions.currentConstructionName()) } else null,
                    if (full) civ.units.getCivUnits().groupingBy { it.name }.eachCount().toSortedMap() else null,
                    if (full) civ.getCivResourcesByName().filterValues { it != 0 }.toSortedMap() else null,
                    if (full) civ.getKnownCivs().filter { civ.isAtWarWith(it) }.map { it.civID }.sorted().toList() else null,
                    if (full) civ.pendingStartingUnits.toSortedMap() else null
                )
            }
            return CivilizationOverviewData(game.turns, game.getYear(), rows, session?.getRunState())
        }
    }
}

data class CitySummary(val name: String, val population: Int, val production: String)
data class CivilizationSummary(
    val id: String, val cityState: Boolean, val defeated: Boolean, val active: Boolean,
    val agentName: String?, val controller: String,
    val score: Int?, val cities: Int?, val population: Int?, val gold: Int?, val income: Int?,
    val science: Int?, val culture: Int?, val production: Int?, val faith: Int?, val happiness: Int?,
    val strength: Int?, val technologies: Int?, val era: String?, val research: String?,
    val researchProgress: Int?, val researchCost: Int?, val researchTurns: String?, val storedCulture: Int?,
    val cityDetails: List<CitySummary>?, val units: Map<String, Int>?, val resources: Map<String, Int>?, val wars: List<String>?,
    val pendingStartingUnits: Map<String, Int>? = null
)
