package com.unciv.logic.agents

import com.unciv.json.json
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class AgentRulesCatalogTest {
    private fun game() = TestGame(baseRuleset = BaseRuleset.Civ_V_Vanilla).apply {
        gameInfo.gameParameters.baseRuleset = BaseRuleset.Civ_V_Vanilla.fullName
    }
    private fun invalid(action: () -> Unit) {
        try { action(); fail("Expected invalid query") }
        catch (e: AgentCommandException) { assertEquals(AgentCommandError.INVALID_ARGUMENT, e.code) }
    }
    @Test fun `named Farm definition contains static technology terrain yield and effects`() {
        val f = game()
        val serializer = json().apply { setUsePrototypes(false) }
        val before = serializer.toJson(f.gameInfo)
        val page = buildRulesCatalog(f.gameInfo, "pinned-1", RulesQuery("improvements", "Farm"))
        assertEquals("pinned-1", page.rulesetIdentity)
        assertEquals(1, page.entries.size)
        val farm = page.entries.single()
        assertEquals("Farm", farm["name"])
        assertEquals("Agriculture", farm["techRequired"])
        assertTrue((farm["terrainsCanBeBuiltOn"] as List<*>).isNotEmpty())
        assertTrue((farm["effects"] as List<*>).isNotEmpty())
        assertTrue((farm["yields"] as Map<*, *>).isNotEmpty())
        assertTrue(farm.containsKey("maintenance"))
        assertNull(page.nextCursor)
        assertEquals(before, serializer.toJson(f.gameInfo))
    }
    @Test fun `terrain and resources give definitions without any live location fields`() {
        val f = game()
        for (section in listOf("terrains", "resources")) {
            val page = buildRulesCatalog(f.gameInfo, "pinned-1", RulesQuery(section))
            assertTrue(page.entries.isNotEmpty())
            val encoded = AgentJson.stringify(page.toJson())
            assertFalse(encoded.contains("\"x\""))
            assertFalse(encoded.contains("\"y\""))
            assertTrue(page.entries.none { it.containsKey("location") || it.containsKey("positions") })
            assertTrue(encoded.toByteArray(Charsets.UTF_8).size <= AGENT_RULES_BYTE_BUDGET)
        }
        val iron = buildRulesCatalog(f.gameInfo, "pinned-1", RulesQuery("resources", "Iron")).entries.single()
        assertEquals("Iron Working", iron["revealedBy"])
        assertTrue((iron["improvedBy"] as List<*>).contains("Mine"))
    }
    @Test fun `invalid section name and bounds reject clearly`() {
        val f = game()
        invalid { buildRulesCatalog(f.gameInfo, "id", RulesQuery("units")) }
        invalid { buildRulesCatalog(f.gameInfo, "id", RulesQuery("terrains", "Imaginary Terrain")) }
        invalid { buildRulesCatalog(f.gameInfo, "id", RulesQuery("terrains", "")) }
        invalid { buildRulesCatalog(f.gameInfo, "id", RulesQuery("terrains", limit = 0)) }
        invalid { buildRulesCatalog(f.gameInfo, "id", RulesQuery("terrains", limit = 129)) }
    }
    @Test fun `cursor binds pinned identity section name limit and content`() {
        val f = game()
        val first = buildRulesCatalog(f.gameInfo, "id", RulesQuery("improvements", limit = 1))
        assertNotNull(first.nextCursor)
        val next = buildRulesCatalog(f.gameInfo, "id", RulesQuery("improvements", limit = 1, cursor = first.nextCursor))
        assertNotEquals(first.entries.single()["name"], next.entries.single()["name"])
        invalid { buildRulesCatalog(f.gameInfo, "other-id", RulesQuery("improvements", limit = 1, cursor = first.nextCursor)) }
        invalid { buildRulesCatalog(f.gameInfo, "id", RulesQuery("terrains", limit = 1, cursor = first.nextCursor)) }
        invalid { buildRulesCatalog(f.gameInfo, "id", RulesQuery("improvements", limit = 2, cursor = first.nextCursor)) }
        invalid { buildRulesCatalog(f.gameInfo, "id", RulesQuery("improvements", "Farm", 1, first.nextCursor)) }
        f.testMutationForCursor()
        invalid { buildRulesCatalog(f.gameInfo, "id", RulesQuery("improvements", limit = 1, cursor = first.nextCursor)) }
    }
    private fun TestGame.testMutationForCursor() {
        createTileImprovement().apply { turnsToBuild = 1; terrainsCanBeBuiltOn = listOf("Land") }
    }
    @Test fun `large UTF8 catalogs make bounded forward progress and return every entry exactly once`() {
        val f = game()
        repeat(140) {
            f.createTileImprovement("界".repeat(1000)).apply { turnsToBuild = 1; terrainsCanBeBuiltOn = listOf("Land") }
        }
        val all = ArrayList<String>()
        var cursor: String? = null
        var pages = 0
        do {
            val page = buildRulesCatalog(f.gameInfo, "id", RulesQuery("improvements", limit = 128, cursor = cursor))
            assertTrue(AgentJson.stringify(page.toJson()).toByteArray(Charsets.UTF_8).size <= AGENT_RULES_BYTE_BUDGET)
            assertTrue(page.entries.isNotEmpty())
            assertTrue(page.entries.size <= 128)
            all.addAll(page.entries.map { it["name"] as String })
            cursor = page.nextCursor
            pages++
            assertTrue("pagination must terminate", pages < 100)
        } while (cursor != null)
        assertTrue(pages > 1)
        assertEquals(f.ruleset.tileImprovements.size, all.size)
        assertEquals(all.size, all.toSet().size)
    }
    @Test fun `oversized individual rule cannot emit a cursor that makes no progress`() {
        val f = game()
        val rule = f.createTileImprovement("界".repeat(40000)).apply { turnsToBuild = 1 }
        invalid { buildRulesCatalog(f.gameInfo, "id", RulesQuery("improvements", rule.name)) }
    }
    @Test fun `malformed cursor rejects instead of restarting catalog`() {
        val f = game()
        invalid { buildRulesCatalog(f.gameInfo, "id", RulesQuery("terrains", cursor = "garbage")) }
    }
    @Test fun `quote control and Unicode heavy rules respect the escaped MCP envelope budget`() {
        val f = game()
        val effect = "\"\\\b\u0001界".repeat(1000)
        repeat(40) {
            f.createTileImprovement(effect).apply { turnsToBuild = 1; terrainsCanBeBuiltOn = listOf("Land") }
        }
        var cursor: String? = null
        val seen = HashSet<String>()
        do {
            val page = buildRulesCatalog(f.gameInfo, "id", RulesQuery("improvements", limit = 128, cursor = cursor))
            assertTrue(AgentReadBudget.fits(page.toJson()))
            val parsed = AgentJson.parse(AgentJson.stringify(page.toJson())) as Map<*, *>
            val parsedEntries = parsed["entries"] as List<*>
            for (entry in parsedEntries) {
                val map = entry as Map<*, *>
                val name = map["name"] as String
                assertTrue(seen.add(name))
                if (name.startsWith("TileImprovement-")) assertEquals(effect, (map["effects"] as List<*>).single())
            }
            cursor = page.nextCursor
        } while (cursor != null)
        assertEquals(f.ruleset.tileImprovements.size, seen.size)
    }

}
