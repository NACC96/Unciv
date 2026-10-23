package com.unciv.logic.agents

import com.unciv.logic.GameInfo
import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.models.ruleset.MilestoneType

data class AgentVictoryProgressRecord(
    var metric: String = "",
    var current: Int = 0,
    var trackedSinceGameTurn: Int = 0,
    var unchangedSinceGameTurn: Int = 0,
    /** Null until a change was actually observed; loading an old save cannot reconstruct it. */
    var lastChangedGameTurn: Int? = null
) : IsPartOfGameInfoSerialization

data class AgentVictoryHistory(
    var routes: HashMap<String, AgentVictoryProgressRecord> = HashMap()
) : IsPartOfGameInfoSerialization {
    fun detachedCopy() = copy(routes = HashMap(routes.mapValues { it.value.copy() }))
}

class ObservedVictoryProgress(
    var metric: String = "",
    var current: Int = 0,
    var target: Int? = null,
    var trackedSinceGameTurn: Int = 0,
    var unchangedSinceGameTurn: Int = 0,
    var lastChangedGameTurn: Int? = null
)

/** Called only on transaction-owned state, including initial load; observation tools stay pure. */
fun captureVictoryProgress(game: GameInfo, match: AgentMatchState) {
    for (civ in game.civilizations.filter { it.isMajorCiv() && it.civID in match.controllers }) {
        val routes = match.victoryProgress.getOrPut(civ.civID) { AgentVictoryHistory() }.routes
        val victories = civ.victoryManager.getAvailableVictories()
        val tracked = HashSet<String>()
        for (victory in victories) {
            val milestones = victory.milestoneObjects
            if (milestones.any { it.type == null }) continue
            val capital = milestones.firstOrNull { it.type == MilestoneType.CaptureAllCapitals }
            val metric = if (capital != null) "ORIGINAL_CAPITALS" else "COMPLETED_MILESTONES"
            val value = capital?.originalMajorCapitalsOwned(civ)
                ?: milestones.count { it.hasBeenCompletedBy(civ) }
            tracked.add(victory.name)
            val prior = routes[victory.name]
            if (prior == null || prior.metric != metric) {
                routes[victory.name] = AgentVictoryProgressRecord(metric, value, game.turns, game.turns)
            } else if (prior.current != value) {
                prior.current = value
                prior.unchangedSinceGameTurn = game.turns
                prior.lastChangedGameTurn = game.turns
            }
        }
        routes.keys.retainAll(tracked)
    }
}
