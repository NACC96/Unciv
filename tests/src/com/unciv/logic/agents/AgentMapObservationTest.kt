package com.unciv.logic.agents

import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import com.unciv.utils.DebugUtils
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class AgentMapObservationTest {
    private fun fixture(): TestGame = TestGame(baseRuleset = BaseRuleset.Civ_V_Vanilla).apply {
        makeHexagonalMap(5)
        gameInfo.agentNormalVisibility = true
    }

    @Test fun city_sightings_distinguish_original_and_replacement_capitals_and_keep_founding_identity() {
        val f = fixture()
        val rome = f.addCiv(f.ruleset.nations["Rome"]!!, isPlayer = true)
        val greece = f.addCiv(f.ruleset.nations["Greece"]!!, isPlayer = true)
        val original = f.addCity(greece, f.getTile(0, 0))
        val replacement = f.addCity(greece, f.getTile(3, 0))
        original.moveToCiv(rome)
        assertTrue(replacement.isCapital())
        val state = AgentMatchState(vision = MatchVision.FULL)
        fun cityAt(position: HexCoord) = buildPermittedTile(f.gameInfo, state, rome.civID, position)!!.city!!.toWire()
        assertEquals(true, cityAt(original.location)["isOriginalCapital"])
        assertEquals(greece.civID, cityAt(original.location)["foundingCivId"])
        assertEquals(false, cityAt(replacement.location)["isOriginalCapital"])
        val copy = buildPermittedTile(f.gameInfo, state, rome.civID, original.location)!!.copy()
        assertEquals(cityAt(original.location), copy.city!!.toWire())
    }

    @Test fun visible_tile_has_actor_permitted_terrain() {
        val f = fixture()
        val civ = f.addCiv(f.ruleset.nations["Rome"]!!, isPlayer = true)
        val tile = f.getTile(0, 0)
        f.addUnit("Scout", civ, tile)
        civ.cache.updateViewableTiles()
        val dto = buildPermittedTile(f.gameInfo, AgentMatchState(), civ.civID, tile.position)!!
        assertEquals(tile.baseTerrain, dto.terrain)
        assertEquals(TileKnowledgeVisibility.VISIBLE, dto.visibility)
        assertTrue(dto.detailsKnown)
        assertEquals(f.gameInfo.turns, dto.lastSeenTurn)
        assertFalse(AgentJson.stringify(dto.toWire()).contains("class"))
        assertTrue(dto.toWire()["x"] is Int)
    }

    @Test fun unrevealed_resource_worlds_have_identical_exports() {
        val f = fixture()
        val civ = f.addCiv(f.ruleset.nations["Rome"]!!, isPlayer = true)
        val tile = f.getTile(0, 0)
        f.addUnit("Scout", civ, tile)
        val state = AgentMatchState(stateRevision = 4)
        val before = buildPermittedTile(f.gameInfo, state, civ.civID, tile.position)!!
        val iron = f.ruleset.tileResources["Iron"]!!
        assertFalse(civ.canSeeResource(iron))
        tile.setTileResource("Iron")
        tile.resourceAmount = 17
        val after = buildPermittedTile(f.gameInfo, state, civ.civID, tile.position)!!
        assertEquals(AgentJson.stringify(before.toWire()), AgentJson.stringify(after.toWire()))
        assertNull(after.resource)
    }

    @Test fun foreign_city_modifiers_are_not_exported() {
        val f = fixture()
        val rome = f.addCiv(f.ruleset.nations["Rome"]!!, isPlayer = true)
        val greece = f.addCiv(f.ruleset.nations["Greece"]!!, isPlayer = true)
        val tile = f.getTile(0, 0)
        val city = f.addCity(greece, tile)
        f.addUnit("Scout", rome, f.getTile(-2, 0))
        val before = buildPermittedTile(f.gameInfo, AgentMatchState(), rome.civID, tile.position)!!
        val privateBuilding = f.createBuilding("[+99 Production] from [All] tiles [in this city]")
        city.cityConstructions.addBuilding(privateBuilding.name)
        val after = buildPermittedTile(f.gameInfo, AgentMatchState(), rome.civID, tile.position)!!
        assertEquals(TileYieldContext.ACTOR_GENERAL, after.yields!!.context)
        assertNull(after.yields!!.cityId)
        assertEquals(before.yields!!.values, after.yields!!.values)
        assertEquals(city.name, after.city!!.name)
    }

    @Test fun spectator_and_debug_views_do_not_expand_agent_knowledge() {
        val f = fixture()
        val civ = f.addCiv(f.ruleset.nations["Rome"]!!, isPlayer = true)
        f.addUnit("Scout", civ, f.getTile(0, 0))
        civ.cache.updateViewableTiles()
        val state = AgentMatchState()
        val before = buildMapSummary(f.gameInfo, state, civ.civID)
        val old = DebugUtils.VISIBLE_MAP
        try {
            DebugUtils.VISIBLE_MAP = true
            civ.cache.updateViewableTiles()
            val after = buildMapSummary(f.gameInfo, state, civ.civID)
            assertEquals(before.visibleTileCount, after.visibleTileCount)
            assertEquals(before.knownTileCount, after.knownTileCount)
            assertNull(buildPermittedTile(f.gameInfo, state, civ.civID, HexCoord(5, 0)))
            assertNull(buildPermittedTile(f.gameInfo, state, civ.civID, HexCoord(999, 999)))
        } finally { DebugUtils.VISIBLE_MAP = old }
    }

    @Test fun map_pages_traverse_once_and_bind_actor_query_revision() {
        val f = fixture()
        val civ = f.addCiv(f.ruleset.nations["Rome"]!!, isPlayer = true)
        val other = f.addCiv(f.ruleset.nations["Greece"]!!, isPlayer = true)
        f.addUnit("Scout", civ, f.getTile(0, 0))
        civ.cache.updateViewableTiles()
        val state = AgentMatchState(matchId = "m", timelineId = "t")
        val expected = buildMapPage(f.gameInfo, state, civ.civID, MapQuery()).tiles.map { HexCoord(it.x, it.y) }
        val all = ArrayList<HexCoord>()
        var cursor: String? = null
        do {
            val page = buildMapPage(f.gameInfo, state, civ.civID, MapQuery(limit = 1, cursor = cursor))
            all.addAll(page.tiles.map { HexCoord(it.x, it.y) })
            cursor = page.nextCursor
        } while (cursor != null)
        assertEquals(expected, all)
        assertEquals(all.size, all.toSet().size)
        val first = buildMapPage(f.gameInfo, state, civ.civID, MapQuery(limit = 1))
        fun expect(code: AgentCommandError, actor: String, query: MapQuery) {
            try { buildMapPage(f.gameInfo, state, actor, query); fail("Expected $code") }
            catch (ex: AgentCommandException) { assertEquals(code, ex.code) }
        }
        expect(AgentCommandError.INVALID_ARGUMENT, other.civID, MapQuery(limit = 1, cursor = first.nextCursor))
        expect(AgentCommandError.INVALID_ARGUMENT, civ.civID, MapQuery(limit = 2, cursor = first.nextCursor))
        state.stateRevision++
        expect(AgentCommandError.STALE_STATE, civ.civID, MapQuery(limit = 1, cursor = first.nextCursor))
    }

    @Test fun map_cursors_are_invalidated_by_each_vision_change_without_a_gameplay_revision() {
        val f = fixture()
        val civ = f.addCiv(f.ruleset.nations["Rome"]!!, isPlayer = true)
        f.addUnit("Scout", civ, f.getTile(0, 0))
        civ.cache.updateViewableTiles()
        val state = AgentMatchState(matchId = "m", timelineId = "t", stateRevision = 4)
        for (before in MatchVision.entries) for (after in MatchVision.entries) {
            if (before == after) continue
            state.vision = before
            val first = buildMapPage(f.gameInfo, state, civ.civID, MapQuery(limit = 1))
            assertNotNull(first.nextCursor)
            state.vision = after
            try {
                buildMapPage(f.gameInfo, state, civ.civID, MapQuery(limit = 1, cursor = first.nextCursor))
                fail("Accepted a $before cursor after changing vision to $after")
            } catch (error: AgentCommandException) {
                assertEquals(AgentCommandError.INVALID_ARGUMENT, error.code)
            }
            assertEquals(4, state.stateRevision)
            assertNotNull(buildMapPage(f.gameInfo, state, civ.civID, MapQuery(limit = 1)).nextCursor)
        }
    }

    @Test fun map_bounds_and_radius_are_checked() {
        val f = fixture(); val civ = f.addCiv(f.ruleset.nations["Rome"]!!, isPlayer = true)
        f.addUnit("Scout", civ, f.getTile(0, 0)); civ.cache.updateViewableTiles()
        val state = AgentMatchState()
        assertEquals(1, buildMapPage(f.gameInfo, state, civ.civID, MapQuery(center = HexCoord(), radius = 0)).tiles.size)
        assertTrue(buildMapPage(f.gameInfo, state, civ.civID, MapQuery(radius = 8, center = HexCoord(), limit = 128)).tiles.isNotEmpty())
        for (query in listOf(MapQuery(radius = 1), MapQuery(center = HexCoord()), MapQuery(limit = 0), MapQuery(radius = 9, center = HexCoord()))) {
            try { buildMapPage(f.gameInfo, state, civ.civID, query); fail("Expected bounds error") }
            catch (ex: AgentCommandException) { assertEquals(AgentCommandError.INVALID_ARGUMENT, ex.code) }
        }
    }

    @Test fun utf8_budget_paginates_without_omission() {
        val f = fixture(); val civ = f.addCiv(f.ruleset.nations["Rome"]!!, isPlayer = true)
        val state = AgentMatchState(matchId = "m", timelineId = "t")
        val positions = listOf(HexCoord(3, 0), HexCoord(4, 0), HexCoord(5, 0))
        for (position in positions) state.mapKnowledge.put(civ.civID,
            ObservedTile(position.x, position.y, terrain = "世".repeat(14000)))
        val first = buildMapPage(f.gameInfo, state, civ.civID, MapQuery(limit = 128))
        assertTrue(first.tiles.size in 1..2)
        assertNotNull(first.nextCursor)
        assertTrue(AgentJson.stringify(first.toWire()).toByteArray(Charsets.UTF_8).size < AGENT_READ_BYTE_BUDGET)
        val all = ArrayList(first.tiles.map { HexCoord(it.x, it.y) })
        var cursor = first.nextCursor
        while (cursor != null) {
            val next = buildMapPage(f.gameInfo, state, civ.civID, MapQuery(limit = 128, cursor = cursor))
            assertTrue(next.tiles.isNotEmpty())
            assertNotEquals(cursor, next.nextCursor)
            all.addAll(next.tiles.map { HexCoord(it.x, it.y) }); cursor = next.nextCursor
        }
        assertEquals(positions, all)
    }

    @Test fun escaped_unicode_pages_fit_actual_mcp_text_content_budget() {
        val f = fixture(); val civ = f.addCiv(f.ruleset.nations["Rome"]!!, isPlayer = true)
        val state = AgentMatchState(matchId = "m", timelineId = "t")
        val positions = listOf(HexCoord(3, 0), HexCoord(4, 0), HexCoord(5, 0))
        val name = ("\"" + 1.toChar() + "世").repeat(4000)
        for (position in positions) state.mapKnowledge.put(civ.civID,
            ObservedTile(position.x, position.y, terrain = name))
        val first = buildMapPage(f.gameInfo, state, civ.civID, MapQuery(limit = 128))
        // Two entries fit in logical JSON but exceed the escaped MCP text-content frame.
        assertEquals(1, first.tiles.size)
        val logicalTwo = MapPage(matchId = "m", timelineId = "t", tiles = arrayListOf(
            state.mapKnowledge.get(civ.civID, positions[0])!!.copy(),
            state.mapKnowledge.get(civ.civID, positions[1])!!.copy()))
        assertTrue(AgentJson.stringify(logicalTwo.toWire()).toByteArray(Charsets.UTF_8).size < AGENT_READ_BYTE_BUDGET)
        val all = ArrayList<HexCoord>()
        var page = first
        while (true) {
            val text = AgentJson.stringify(page.toWire())
            val content = mapOf("content" to listOf(mapOf("type" to "text", "text" to text)))
            assertTrue(AgentJson.stringify(content).toByteArray(Charsets.UTF_8).size + 1024 <= AGENT_READ_BYTE_BUDGET)
            assertEquals(name, page.tiles.single().terrain)
            assertTrue(AgentJson.parse(text) is Map<*, *>)
            all.addAll(page.tiles.map { HexCoord(it.x, it.y) })
            val cursor = page.nextCursor ?: break
            page = buildMapPage(f.gameInfo, state, civ.civID, MapQuery(limit = 128, cursor = cursor))
            assertNotEquals(cursor, page.nextCursor)
        }
        assertEquals(positions, all)
    }

    @Test fun wrapped_seam_uses_engine_distance_and_canonical_coordinates() {
        val f = TestGame(baseRuleset = BaseRuleset.Civ_V_Vanilla)
        f.makeRectangularMap(8, 16)
        f.gameInfo.agentNormalVisibility = true
        f.tileMap.mapParameters.worldWrap = true
        val civ = f.addCiv(f.ruleset.nations["Rome"]!!, isPlayer = true)
        val left = f.tileMap.values.minBy { it.position.x - it.position.y }
        val opposite = left.getTilesAtDistance(1).first { kotlin.math.abs(it.position.x - left.position.x) > 2 }
        f.addUnit("Scout", civ, left); civ.cache.updateViewableTiles()
        val state = AgentMatchState()
        val page = buildMapPage(f.gameInfo, state, civ.civID,
            MapQuery(center = left.position, radius = 1, limit = 128))
        assertTrue(page.tiles.any { HexCoord(it.x, it.y) == opposite.position })
        assertEquals(page.tiles.size, page.tiles.map { HexCoord(it.x, it.y) }.toSet().size)
    }

    @Test fun oversized_unicode_entry_returns_bounded_error_without_skipping() {
        val f = fixture(); val civ = f.addCiv(f.ruleset.nations["Rome"]!!, isPlayer = true)
        val tile = f.getTile(4, 0)
        tile.setExplored(civ, true)
        val state = AgentMatchState()
        state.mapKnowledge.put(civ.civID, ObservedTile(tile.position.x, tile.position.y,
            terrain = "世".repeat(40000)))
        try { buildMapPage(f.gameInfo, state, civ.civID, MapQuery()); fail("Expected byte budget error") }
        catch (ex: AgentCommandException) {
            assertEquals(AgentCommandError.INVALID_ARGUMENT, ex.code)
            assertTrue(ex.message!!.toByteArray(Charsets.UTF_8).size < 1024)
        }
        assertEquals("世".repeat(40000), state.mapKnowledge.get(civ.civID, tile.position)!!.terrain)
    }
}
