package com.unciv.dev

import com.unciv.logic.agents.*
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.TestGame

/** Small real-engine starting positions, serialized ONCE then shared by both evaluation arms. */
internal object AgentStrategyFixtures {
    val ids = setOf("stable-border", "stranded-settler", "favorable-assault", "unfavorable-assault",
        "outdated-science-notes", "diplomatic-opportunity", "near-victory", "scripted-smoke")

    fun bundle(id: String, seed: Long, profile: AgentProfile, vision: MatchVision): String {
        require(id in ids) { "Unknown fixture $id; choose ${ids.joinToString()}" }
        val test = TestGame(baseRuleset = BaseRuleset.Civ_V_Vanilla)
        val game = test.gameInfo
        game.gameId = "strategy-fixture-$id-$seed"
        game.gameParameters.baseRuleset = BaseRuleset.Civ_V_Vanilla.fullName
        game.gameParameters.victoryTypes = arrayListOf("Domination")
        game.gameParameters.maxTurns = 500
        test.setSpeed("Standard")
        test.setDifficulty("Prince")
        test.makeHexagonalMap(7, "Grassland")
        test.tileMap.mapParameters.seed = seed
        val rome = test.addCiv(test.ruleset.nations.getValue("Rome"), isPlayer = true)
        val greece = test.addCiv(test.ruleset.nations.getValue("Greece"))
        // These scenarios evaluate strategy, not an accidental zero-cash/desert start.
        rome.addGold(300)
        greece.addGold(300)
        game.currentPlayer = rome.civID
        game.currentPlayerCiv = rome
        val home = test.addCity(rome, test.getTile(-4, 0), initialPopulation = 5)
        val target = test.addCity(greece, test.getTile(3, 0), initialPopulation = 5)
        home.id = "fixture-rome-capital"
        target.id = "fixture-greece-capital"
        home.cityConstructions.constructionQueue = arrayListOf("Monument")
        target.cityConstructions.constructionQueue = arrayListOf("Monument")
        rome.tech.techsToResearch = arrayListOf("Pottery")
        greece.tech.techsToResearch = arrayListOf("Pottery")
        rome.diplomacyFunctions.makeCivilizationsMeet(greece)

        fun research(civ: com.unciv.logic.civilization.Civilization, name: String) {
            if (civ.tech.isResearched(name)) return
            test.ruleset.technologies.getValue(name).prerequisites.sorted().forEach { research(civ, it) }
            civ.tech.addTechnology(name, showNotification = false)
        }
        val assault = id in setOf("favorable-assault", "unfavorable-assault", "near-victory")
        if (assault) {
            research(rome, "Dynamite")
            research(greece, "Rifling")
            // Neutral staging ground, before any declaration. Movement/war/capture are model decisions.
            test.addUnit("Rifleman", rome, test.getTile(0, 0))
            test.addUnit("Rifleman", rome, test.getTile(0, -1))
            test.addUnit("Artillery", rome, test.getTile(-1, 0))
            if (id != "unfavorable-assault") test.addUnit("Artillery", rome, test.getTile(-1, -1))
            test.addUnit("Rifleman", greece, test.getTile(3, 0))
            if (id == "unfavorable-assault") {
                research(greece, "Replaceable Parts")
                target.cityConstructions.addBuilding("Walls")
                target.cityConstructions.addBuilding("Castle")
                // A stronger defending army; the actor can improve readiness or choose another milestone.
                test.addUnit("Infantry", greece, test.getTile(3, 1))
                test.addUnit("Infantry", greece, test.getTile(4, 0))
                test.addUnit("Artillery", greece, test.getTile(4, 1))
            }
            if (id == "near-victory") target.health = 90
            // Full-health cities in the favorable fixture, unlike a command-only one-hit capture test.
            rome.tech.techsToResearch.clear()
            greece.tech.techsToResearch.clear()
        } else {
            test.addUnit("Warrior", rome, test.getTile(-4, 0))
            test.addUnit("Warrior", greece, test.getTile(3, 0))
        }
        if (id == "stranded-settler") {
            // Two-tile island: founding is blocked by the nearby capital and embarking needs research.
            val island = setOf(home.getCenterTile(), test.getTile(-3, 0))
            val coast = island.flatMap { it.neighbors.toList() }.filter { it !in island }.toSet()
            for (tile in coast) test.setTileTerrain(tile.position, "Ocean")
            test.addUnit("Settler", rome, test.getTile(-3, 0))
        }
        if (id == "diplomatic-opportunity") {
            research(rome, "Calendar")
            research(greece, "Mining")
            test.addCity(rome, test.getTile(-4, -3), initialPopulation = 5).id = "fixture-rome-second"
            test.addCity(greece, test.getTile(3, 3), initialPopulation = 5).id = "fixture-greece-second"
            for ((city, resource, improvement) in listOf(Triple(home, "Silk", "Plantation"), Triple(target, "Gems", "Mine"))) {
                val tiles = city.getCenterTile().neighbors.filter { it.getOwner() == city.civ }
                    .sortedWith(compareBy({ it.position.x }, { it.position.y })).take(2).toList()
                check(tiles.size == 2)
                for (tile in tiles) {
                    tile.setTileResource(resource)
                    tile.setImprovement(improvement)
                }
                city.civ.cache.updateCivResources()
                check(city.civ.getCivResourcesByName().getValue(resource) == 2)
            }
        }

        val match = AgentMatchState(matchId = "fixture-$id-$seed", timelineId = "fixture-timeline-$id-$seed",
            turnPhase = TurnPhase.READY_TO_START, vision = vision)
        match.controllers[rome.civID] = CivilizationController(rome.civID, ControllerKind.EXTERNAL_AGENT, profile.copy())
        // Stable diplomacy needs another commandable leader; a builtin-only rival cannot receive agent mail.
        val diplomatic = id in setOf("stable-border", "outdated-science-notes", "diplomatic-opportunity")
        match.controllers[greece.civID] = CivilizationController(greece.civID,
            if (diplomatic) ControllerKind.EXTERNAL_AGENT else ControllerKind.BUILTIN_AI,
            if (diplomatic) profile.copy().apply { profileId += "-greece"; displayName += " Greece" } else null)
        if (id == "stable-border") match.diplomaticMessages.add(DiplomaticMessage(
            "fixture-incoming-border", greece.civID, rome.civID,
            "Our border remains peaceful. Please confirm receipt of our existing non-aggression arrangement. No new proposal or threat has arisen.", 0))
        if (id == "outdated-science-notes") match.privateNotes[rome.civID] =
            "Primary victory: Scientific. Build Apollo and spaceship parts. Avoid military operations indefinitely while researching towards space."
        if (id == "diplomatic-opportunity") {
            match.privateNotes[rome.civID] = "Last observed resources: two connected Silk near Rome; no Gems. Greece has two connected Gems near Athens and no Silk. Two cities are placing pressure on happiness. No proposal or message has been sent."
            match.privateNotes[greece.civID] = "Last observed resources: two connected Gems near Athens; no Silk. Rome has two connected Silk near Rome and no Gems. Two cities are placing pressure on happiness. No proposal or message has been sent."
        }
        return MatchCoordinator(game, match, InMemoryCheckpointWriter()).saveToJson()
    }
}
