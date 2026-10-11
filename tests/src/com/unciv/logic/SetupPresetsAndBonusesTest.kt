package com.unciv.logic

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.files.FileHandle
import com.unciv.UncivGame
import com.unciv.json.json
import com.unciv.logic.agents.AgentProfile
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.civilization.managers.TurnManager
import com.unciv.logic.files.SetupPresetStore
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.map.MapParameters
import com.unciv.logic.map.MapShape
import com.unciv.logic.map.MapSize
import com.unciv.models.metadata.*
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(GdxTestRunner::class)
class SetupPresetsAndBonusesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `preset roundtrip keeps full setup and isolated agent profiles and grants`() {
        val setup = GameSetupInfo().apply {
            gameParameters.players = arrayListOf(Player("Spectator", PlayerType.Human), Player("Rome", PlayerType.Human), Player("Korea", PlayerType.AI))
            gameParameters.speed = "Quick"
            gameParameters.startingEra = "Industrial era"
            gameParameters.shufflePlayerOrder = true
            gameParameters.godMode = true
            mapParameters.seed = 4422L
            mapParameters.worldWrap = true
            mapParameters.mapSize = MapSize.Huge
        }
        val rome = setup.gameParameters.players[1]
        rome.startingBonuses.gold = 900
        rome.startingBonuses.units["Scout"] = 2
        rome.startingBonuses.deposits["Iron"] = 1
        val profile = AgentProfile.defaultFor("Rome").apply { modelId = "gpt-6.1-sol"; reasoning = "max"; displayName = "Atlas"; personality = "Explore first" }
        val cached = AgentProfile.defaultFor("Korea").apply { reasoning = "low" }
        val store = SetupPresetStore(FileHandle(temporary.newFolder()))
        store.save("Industrial rivals", setup, mapOf(rome to profile), mapOf(setup.gameParameters.players[2] to cached))
        profile.reasoning = "low"
        rome.startingBonuses.units["Scout"] = 20
        setup.mapParameters.seed = 0L
        val loaded = store.list().first.single()
        assertEquals("Quick", loaded.setup.gameParameters.speed)
        assertEquals("Industrial era", loaded.setup.gameParameters.startingEra)
        assertTrue(loaded.setup.gameParameters.shufflePlayerOrder)
        assertTrue(loaded.setup.gameParameters.godMode)
        assertEquals(4422L, loaded.setup.mapParameters.seed)
        assertEquals("Huge", loaded.setup.mapParameters.mapSize.name)
        assertTrue(loaded.setup.mapParameters.worldWrap)
        assertEquals(listOf("Spectator", "Rome", "Korea"), loaded.setup.gameParameters.players.map { it.chosenCiv })
        assertEquals(900, loaded.setup.gameParameters.players[1].startingBonuses.gold)
        assertEquals(2, loaded.setup.gameParameters.players[1].startingBonuses.units["Scout"])
        assertEquals("max", loaded.agents.first { it.active }.profile.reasoning)
        assertEquals("gpt-6.1-sol", loaded.agents.first { it.active }.profile.modelId)
        assertEquals("Explore first", loaded.agents.first { it.active }.profile.personality)
        assertFalse(loaded.agents.first { it.index == 2 }.active)
    }

    @Test fun `updating a named preset preserves a backup and unreadable files are not lost`() {
        val directory = FileHandle(temporary.newFolder())
        val store = SetupPresetStore(directory)
        val setup = GameSetupInfo()
        val first = store.save("My game", setup, emptyMap())
        setup.gameParameters.difficulty = "King"
        val second = store.save("MY GAME", setup, emptyMap())
        assertEquals(first.id, second.id)
        assertEquals("King", store.list().first.single().setup.gameParameters.difficulty)
        assertEquals(1, directory.list().count { it.extension() == "backup" })
        directory.child("broken.json").writeString("{bad", false)
        assertEquals(1, store.list().second.size)
        assertTrue(directory.child("broken.json").exists())
    }

    @Test fun `custom map reference survives save and gives an actionable missing-map error`() {
        val map = FileHandle(temporary.newFile("map.json"))
        val setup = GameSetupInfo().apply { mapFile = map; mapParameters.name = "map.json" }
        val store = SetupPresetStore(FileHandle(temporary.newFolder()))
        val saved = store.save("Custom map", setup, emptyMap())
        saved.restoreMapFile()
        assertEquals(map.file().absolutePath, saved.setup.mapFile!!.file().absolutePath)
        map.delete()
        try { saved.restoreMapFile(); fail("A missing custom map must not silently generate another map") }
        catch (ex: IllegalArgumentException) { assertTrue(ex.message!!.contains("missing")) }
    }

    @Test fun `setup clones cannot mutate original nation bonuses`() {
        val original = GameSetupInfo()
        original.gameParameters.players.first().startingBonuses.units["Scout"] = 1
        val copy = GameSetupInfo(original)
        copy.gameParameters.players.first().startingBonuses.units["Scout"] = 9
        assertEquals(1, original.gameParameters.players.first().startingBonuses.units["Scout"])
    }

    @Test fun `generated preset ignores an old custom-map reference`() {
        val map = FileHandle(temporary.newFile("unused-map.json"))
        val setup = GameSetupInfo().apply { mapFile = map }
        val store = SetupPresetStore(FileHandle(temporary.newFolder()))
        val saved = store.save("Generated map", setup, emptyMap())
        map.delete()
        saved.restoreMapFile()
        assertNull(saved.setup.mapFile)
    }

    @Test fun `starting grants place real units and eligible deposits without replacing existing resources`() {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_Vanilla)
        game.makeHexagonalMap(5, "Grassland")
        val civ = game.addCiv(game.ruleset.nations["Rome"]!!, isPlayer = true)
        val start = game.tileMap.values.first { it.position.x == 0 && it.position.y == 0 }
        val wheat = start.neighbors.first()
        wheat.setTileResource(game.ruleset.tileResources["Wheat"]!!)
        val bonuses = StartingBonuses().apply { gold = 250; culture = 30; units["Scout"] = 2; deposits["Horses"] = 2 }
        StartingBonusApplicator.apply(civ, start, bonuses, listOf(start))
        assertEquals(250, civ.gold)
        assertEquals(30, civ.policies.storedCulture)
        assertEquals(2, civ.units.getCivUnits().count { it.name == "Scout" })
        val deposits = game.tileMap.values.filter { it.resource == "Horses" }
        assertEquals(2, deposits.size)
        assertTrue(deposits.all { it.aerialDistanceTo(start) in 1..4 && it.resourceAmount > 0 && game.ruleset.tileResources["Horses"]!!.generatesNaturallyOn(it) })
        assertEquals("Wheat", wheat.resource)
        assertTrue(deposits.none { it.improvement != null })
        assertEquals(0, civ.getResourceAmount("Horses"))
    }

    @Test fun `impossible deposits fail visibly before applying bonuses`() {
        val game = TestGame()
        game.makeHexagonalMap(4, "Grassland")
        val civ = game.addCiv(game.ruleset.nations["Rome"]!!, isPlayer = true)
        val onlyWater = game.createResource().apply { resourceType = ResourceType.Strategic; terrainsCanBeFoundOn = listOf("Ocean") }
        val bonuses = StartingBonuses().apply { gold = 250; units["Scout"] = 2; deposits["Horses"] = 1; deposits[onlyWater.name] = 1 }
        try { StartingBonusApplicator.apply(civ, game.tileMap.values.first(), bonuses, emptyList()); fail("Impossible grants must not disappear silently") }
        catch (ex: UncivShowableException) { assertTrue(ex.message!!.contains("Cannot place")) }
        assertEquals(0, civ.gold)
        assertEquals(0, civ.units.getCivUnits().count())
        assertTrue("A failed request must not leave the earlier deposits behind", game.tileMap.values.none { it.resource != null })
    }

    @Test fun `deposits prefer natural locations and remove terrain features only when extraction requires it`() {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_Vanilla)
        game.makeHexagonalMap(6, "Grassland")
        val civ = game.addCiv(game.ruleset.nations["America"]!!, isPlayer = true)
        val start = game.getTile(0, 0)
        val forest = game.getTile(2, 0).apply { setTerrainFeatures(listOf("Forest")) }
        val protectedForest = game.getTile(3, 0).apply {
            setTerrainFeatures(listOf("Forest")); setTileResource(game.ruleset.tileResources["Deer"]!!)
        }
        val mountain = start.neighbors.first().apply { setBaseTerrain(game.ruleset.terrains["Mountain"]!!) }
        StartingBonusApplicator.apply(civ, start, StartingBonuses().apply { deposits["Uranium"] = 1 }, listOf(start))
        assertEquals("Natural Forest should be chosen before closer Grassland", "Uranium", forest.resource)
        assertTrue("Mine extraction requires clearing this Forest", forest.terrainFeatures.isEmpty())
        assertEquals(listOf("Forest"), protectedForest.terrainFeatures)
        assertEquals("Deer", protectedForest.resource)
        assertNull(mountain.resource)
        assertEquals("Mountain", mountain.baseTerrain)
        assertNull(start.resource)
    }

    @Test fun `bonus deposits use workable empty tiles when natural terrain is missing and can be extracted normally`() {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_Vanilla)
        game.makeHexagonalMap(6, "Grassland")
        val civ = game.addCiv(game.ruleset.nations["America"]!!, isPlayer = true)
        val start = game.getTile(0, 0)
        val wheat = start.neighbors.first()
        wheat.setTileResource(game.ruleset.tileResources["Wheat"]!!)
        val bonuses = StartingBonuses().apply { deposits.putAll(linkedMapOf("Uranium" to 4, "Oil" to 4, "Aluminum" to 4, "Coal" to 4)) }
        StartingBonusApplicator.apply(civ, start, bonuses, listOf(start))
        assertEquals("Wheat", wheat.resource)
        assertNull(start.resource)
        val city = game.addCity(civ, start)
        for ((name, count) in bonuses.deposits) {
            val resource = game.ruleset.tileResources[name]!!
            val deposits = game.tileMap.values.filter { it.resource == name }
            assertEquals(count, deposits.size)
            assertTrue(deposits.all { it.aerialDistanceTo(start) in 1..4 && it.baseTerrain == "Grassland" && it.resourceAmount > 0 })
            assertEquals("Deposits still need ownership and improvements", 0, civ.getResourceAmount(name))
            resource.revealedBy?.let { civ.tech.addTechnology(it) }
            for (tile in deposits) {
                city.expansion.takeOwnership(tile)
                for (improvement in resource.getImprovements().mapNotNull { game.ruleset.tileImprovements[it] })
                    improvement.techRequired?.let { civ.tech.addTechnology(it) }
                val improvement = resource.getImprovingImprovement(tile, GameContext(civInfo = civ, tile = tile, city = city))
                assertNotNull("$name must have a legal extraction improvement", improvement)
                tile.setImprovement(improvement!!.name, civ)
            }
            civ.cache.updateCivResources()
            assertTrue("Improved $name deposits must supply the resource", civ.getResourceAmount(name) > 0)
        }
    }

    @Test fun `aircraft and missiles wait for a founded city while ordinary bonuses arrive immediately`() {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_Vanilla)
        game.makeHexagonalMap(6, "Grassland")
        val civ = game.addCiv(game.ruleset.nations["America"]!!, isPlayer = true)
        val start = game.tileMap.values.first { it.position.x == 0 && it.position.y == 0 }
        val bonuses = StartingBonuses().apply {
            gold = 1500; culture = 80
            units["Nuclear Missile"] = 1; units["Bomber"] = 2; units["Rifleman"] = 2
        }
        StartingBonusApplicator.apply(civ, start, bonuses, listOf(start))
        assertEquals(1500, civ.gold)
        assertEquals(80, civ.policies.storedCulture)
        assertTrue(civ.cities.isEmpty())
        assertEquals(2, civ.units.getCivUnits().count { it.name == "Rifleman" })
        assertFalse(civ.units.getCivUnits().any { it.baseUnit.isAirUnit() })

        val city = game.addCity(civ, start)
        assertEquals(1, city.getCenterTile().airUnits.count { it.name == "Nuclear Missile" })
        assertEquals(2, city.getCenterTile().airUnits.count { it.name == "Bomber" })
        assertTrue(city.getCenterTile().airUnits.all { !it.isTransported && it.civ == civ })
        assertEquals(1500, civ.gold)
        assertEquals(80, civ.policies.storedCulture)
    }

    @Test fun `pending aircraft survive save reload and game clones without duplicate delivery`() {
        val fixture = TestGame(baseRuleset = BaseRuleset.Civ_V_Vanilla)
        fixture.makeHexagonalMap(6, "Grassland")
        val civ = fixture.addCiv(fixture.ruleset.nations["America"]!!, isPlayer = true)
        val start = fixture.tileMap.values.first { it.position.x == 0 && it.position.y == 0 }
        StartingBonusApplicator.apply(civ, start, StartingBonuses().apply { units["Nuclear Missile"] = 2 }, listOf(start))
        val snapshot = fixture.gameInfo.clone().also { it.setTransients() }
        val restored = json().fromJson(GameInfo::class.java, json().toJson(fixture.gameInfo)).also { it.setTransients() }
        val restoredCiv = restored.getCivilization(civ.civID)
        restoredCiv.addCity(start.position, suppressAutomation = true)
        assertEquals(2, restoredCiv.units.getCivUnits().count { it.name == "Nuclear Missile" })
        restoredCiv.addCity(start.getTilesInDistance(5).first { it.aerialDistanceTo(start) == 5 }.position, suppressAutomation = true)
        assertEquals(2, restoredCiv.units.getCivUnits().count { it.name == "Nuclear Missile" })
        assertFalse(civ.units.getCivUnits().any { it.baseUnit.isAirUnit() })
        val snapshotCiv = snapshot.getCivilization(civ.civID)
        snapshotCiv.addCity(start.position, suppressAutomation = true)
        assertEquals(2, snapshotCiv.units.getCivUnits().count { it.name == "Nuclear Missile" })
    }

    @Test fun `excess aircraft respect city capacity and arrive after space opens or a second city is founded`() {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_Vanilla)
        game.makeHexagonalMap(7, "Grassland")
        val civ = game.addCiv(game.ruleset.nations["America"]!!, isPlayer = true)
        val start = game.tileMap.values.first { it.position.x == 0 && it.position.y == 0 }
        val capacity = game.ruleset.modOptions.constants.cityAirUnitCapacity
        StartingBonusApplicator.apply(civ, start, StartingBonuses().apply { units["Bomber"] = capacity + 3 }, listOf(start))
        val firstCity = game.addCity(civ, start)
        assertEquals(capacity, firstCity.getCenterTile().airUnits.size)
        firstCity.getCenterTile().airUnits.first().destroy()
        TurnManager(civ).startTurn(suppressAutomation = true)
        assertEquals(capacity, firstCity.getCenterTile().airUnits.size)
        val secondCity = game.addCity(civ, start.getTilesInDistance(5).first { it.aerialDistanceTo(start) == 5 })
        assertEquals(2, secondCity.getCenterTile().airUnits.size)
        TurnManager(civ).startTurn(suppressAutomation = true)
        assertEquals(capacity + 2, civ.units.getCivUnits().count { it.name == "Bomber" })
        assertTrue(civ.cities.all { it.getCenterTile().airUnits.size <= it.getMaxAirUnits() })
    }

    @Test fun `save after partial delivery preserves only the remaining aircraft grants`() {
        val fixture = TestGame(baseRuleset = BaseRuleset.Civ_V_Vanilla)
        fixture.makeHexagonalMap(7, "Grassland")
        val civ = fixture.addCiv(fixture.ruleset.nations["America"]!!, isPlayer = true)
        val start = fixture.getTile(0, 0)
        val capacity = fixture.ruleset.modOptions.constants.cityAirUnitCapacity
        StartingBonusApplicator.apply(civ, start, StartingBonuses().apply { units["Nuclear Missile"] = capacity + 3 }, listOf(start))
        fixture.addCity(civ, start)
        assertEquals(3, civ.pendingStartingUnits["Nuclear Missile"])
        val restored = json().fromJson(GameInfo::class.java, json().toJson(fixture.gameInfo)).also { it.setTransients() }
        val restoredCiv = restored.getCivilization(civ.civID)
        assertEquals(capacity, restoredCiv.units.getCivUnits().count { it.name == "Nuclear Missile" })
        assertEquals(3, restoredCiv.pendingStartingUnits["Nuclear Missile"])
        restoredCiv.addCity(start.getTilesInDistance(5).first { it.aerialDistanceTo(start) == 5 }.position, suppressAutomation = true)
        TurnManager(restoredCiv).startTurn(suppressAutomation = true)
        assertEquals(capacity + 3, restoredCiv.units.getCivUnits().count { it.name == "Nuclear Missile" })
        assertTrue(restoredCiv.pendingStartingUnits.isEmpty())
        assertEquals(3, civ.pendingStartingUnits["Nuclear Missile"])
    }

    @Test fun `generated game transfers random-slot grants and save reload does not reapply them`() {
        TestGame(baseRuleset = BaseRuleset.Civ_V_Vanilla)
        UncivGame.Current.files = UncivFiles(Gdx.files, temporary.newFolder().absolutePath)
        val parameters = GameParameters().apply {
            baseRuleset = BaseRuleset.Civ_V_Vanilla.fullName; numberOfCityStates = 0; noBarbarians = true
            players = arrayListOf(Player("Rome", PlayerType.Human), Player(playerType = PlayerType.AI, playerId = "bonus-slot").apply {
                startingBonuses.gold = 777; startingBonuses.units["Scout"] = 3
            })
        }
        val game = GameStarter.startNewGame(GameSetupInfo(parameters, MapParameters().apply { mapSize = MapSize.Tiny; seed = 42L }))
        val recipient = game.civilizations.single { it.playerId == "bonus-slot" }
        assertEquals(777, recipient.gold)
        assertEquals(3, recipient.units.getCivUnits().count { it.baseUnit.name == "Scout" })
        val reloaded = json().fromJson(GameInfo::class.java, json().toJson(game))
        reloaded.setTransients()
        val again = reloaded.civilizations.single { it.civID == recipient.civID }
        assertEquals(777, again.gold)
        assertEquals(3, again.units.getCivUnits().count { it.name == "Scout" })
    }

    @Test fun `modern nine-nation game starts with mixed land aircraft and missile bonuses`() {
        TestGame(baseRuleset = BaseRuleset.Civ_V_Vanilla)
        UncivGame.Current.files = UncivFiles(Gdx.files, temporary.newFolder().absolutePath)
        val names = listOf("America", "Arabia", "Germany", "China", "Russia", "The Ottomans", "Persia", "England", "France")
        val parameters = GameParameters().apply {
            baseRuleset = BaseRuleset.Civ_V_Vanilla.fullName
            startingEra = "Modern era"; speed = "Quick"; numberOfCityStates = 8
            players = ArrayList(names.mapIndexed { index, name -> Player(name, if (index == 0) PlayerType.Human else PlayerType.AI) })
            players[0].startingBonuses.apply { gold = 1500; units["Nuclear Missile"] = 2; units["Bomber"] = 4 }
            players[6].startingBonuses.apply { units["Rocket Artillery"] = 2; units["Rifleman"] = 5 }
            players[7].startingBonuses.apply { units["Nuclear Missile"] = 2; units["Bomber"] = 4 }
            players[8].startingBonuses.apply { units["Nuclear Missile"] = 1; units["Foreign Legion"] = 6; deposits["Uranium"] = 1 }
        }
        val game = GameStarter.startNewGame(GameSetupInfo(parameters, MapParameters().apply { mapSize = MapSize.Large; worldWrap = true; seed = 42L }))
        val america = game.getCivilization("America")
        assertEquals(2, america.pendingStartingUnits["Nuclear Missile"])
        assertEquals(4, america.pendingStartingUnits["Bomber"])
        assertTrue(america.cities.isEmpty())
        assertEquals(1500 + (200 * game.speed.goldCostModifier).toInt(), america.gold)
        assertEquals(2, game.getCivilization("Persia").units.getCivUnits().count { it.name == "Rocket Artillery" })
        assertEquals(6 + game.ruleset.eras["Modern era"]!!.startingMilitaryUnitCount,
            game.getCivilization("France").units.getCivUnits().count { it.name == "Foreign Legion" })
        val original = america.units.getCivUnits().first { it.baseUnit.isCityFounder() }.getTile()
        america.addCity(original.position, suppressAutomation = true)
        assertEquals(2, america.units.getCivUnits().count { it.name == "Nuclear Missile" })
        assertEquals(4, america.units.getCivUnits().count { it.name == "Bomber" })
        assertTrue(america.pendingStartingUnits.isEmpty())
        assertEquals(2, game.getCivilization("England").pendingStartingUnits["Nuclear Missile"])
        assertEquals(1, game.getCivilization("France").pendingStartingUnits["Nuclear Missile"])
    }

    @Test fun `resource-heavy modern spectator roster starts without losing large missile grants`() {
        TestGame(baseRuleset = BaseRuleset.Civ_V_Vanilla)
        UncivGame.Current.files = UncivFiles(Gdx.files, temporary.newFolder().absolutePath)
        val names = listOf("Spectator", "America", "Arabia", "Germany", "China", "Russia", "The Ottomans", "Persia", "England", "France")
        val parameters = GameParameters().apply {
            baseRuleset = BaseRuleset.Civ_V_Vanilla.fullName
            startingEra = "Modern era"; speed = "Quick"; numberOfCityStates = 8
            players = ArrayList(names.map { Player(it, PlayerType.Human) })
        }
        fun grant(name: String, gold: Int = 0, units: Map<String, Int> = emptyMap(), deposits: Map<String, Int> = emptyMap()) {
            parameters.players.single { it.chosenCiv == name }.startingBonuses.apply {
                this.gold = gold; this.units.putAll(units); this.deposits.putAll(deposits)
            }
        }
        grant("America", 10000, linkedMapOf("Nuclear Missile" to 10, "Anti-Aircraft Gun" to 1, "Bomber" to 1,
            "Atomic Bomb" to 1, "Helicopter Gunship" to 1, "Mechanized Infantry" to 1, "Infantry" to 5,
            "Stealth Bomber" to 1, "Rocket Artillery" to 1), linkedMapOf("Uranium" to 4, "Oil" to 4, "Aluminum" to 4, "Coal" to 4))
        grant("Arabia", 9000, deposits = mapOf("Oil" to 10))
        grant("Germany", units = mapOf("Tank" to 6), deposits = mapOf("Oil" to 3, "Coal" to 3))
        grant("China", 3000, linkedMapOf("Nuclear Missile" to 4, "Anti-Aircraft Gun" to 4, "Infantry" to 10), mapOf("Uranium" to 4))
        grant("Russia", 2000, linkedMapOf("Nuclear Missile" to 10, "Infantry" to 5, "Tank" to 10,
            "Helicopter Gunship" to 1, "Rocket Artillery" to 4, "Anti-Aircraft Gun" to 3), mapOf("Oil" to 5, "Uranium" to 4))
        grant("Persia", units = mapOf("Rocket Artillery" to 5, "Rifleman" to 5))
        grant("England", units = linkedMapOf("Nuclear Missile" to 2, "Bomber" to 4))
        grant("France", units = linkedMapOf("Nuclear Missile" to 1, "Foreign Legion" to 6), deposits = mapOf("Uranium" to 1))
        val game = GameStarter.startNewGame(GameSetupInfo(parameters, MapParameters().apply {
            shape = MapShape.rectangular; mapSize = MapSize.Large; worldWrap = true; legendaryStart = true; seed = 1791692596446L
        }))
        val america = game.getCivilization("America")
        assertEquals(13, america.pendingStartingUnits.values.sum())
        assertEquals(10, game.getCivilization("Russia").pendingStartingUnits["Nuclear Missile"])
        assertEquals(10000 + (200 * game.speed.goldCostModifier).toInt(), america.gold)
        val first = america.units.getCivUnits().first { it.baseUnit.isCityFounder() }.getTile()
        val city = america.addCity(first.position, suppressAutomation = true)
        assertEquals(city.getMaxAirUnits(), city.getCenterTile().airUnits.size)
        assertEquals(13 - city.getMaxAirUnits(), america.pendingStartingUnits.values.sum())
    }
}
