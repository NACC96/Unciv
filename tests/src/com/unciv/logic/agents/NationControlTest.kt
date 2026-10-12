package com.unciv.logic.agents

import com.unciv.Constants
import com.unciv.json.json
import com.unciv.logic.GameInfo
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.ruleset.nation.Nation
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class NationControlTest {
    private class Fixture(ownHuman: Boolean = false, online: Boolean = false) {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_Vanilla)
        val writer = InMemoryCheckpointWriter()
        val rome: String
        val greece: String
        val spectator: String
        val coordinator: MatchCoordinator
        init {
            game.gameInfo.gameParameters.baseRuleset = BaseRuleset.Civ_V_Vanilla.fullName
            game.gameInfo.gameParameters.isOnlineMultiplayer = online
            game.makeHexagonalMap(4)
            val r = game.addCiv(game.ruleset.nations["Rome"]!!, isPlayer = true)
            val g = game.addCiv(game.ruleset.nations["Greece"]!!, isPlayer = true)
            val s = game.addCiv(game.ruleset.nations[Constants.spectator]!!, isPlayer = true)
            game.addUnit("Warrior", r, game.getTile(0, 0))
            game.addUnit("Warrior", g, game.getTile(2, 0))
            rome = r.civID; greece = g.civID; spectator = s.civID
            game.gameInfo.currentPlayer = rome; game.gameInfo.currentPlayerCiv = r
            val match = AgentMatchState(matchId = "nation-control", timelineId = "t1",
                turnPhase = TurnPhase.AWAITING_DECISIONS,
                localControlCivId = if (ownHuman) rome else spectator,
                localViewingCivId = if (ownHuman) rome else spectator)
            match.controllers[rome] = CivilizationController(rome,
                if (ownHuman) ControllerKind.HUMAN else ControllerKind.EXTERNAL_AGENT,
                if (ownHuman) null else AgentProfile.defaultFor("Rome").apply { personality = "Remember every promise" })
            match.controllers[greece] = CivilizationController(greece, ControllerKind.EXTERNAL_AGENT, AgentProfile.defaultFor("Greece"))
            coordinator = MatchCoordinator(game.gameInfo, match, writer)
        }
        fun saved(): AgentMatchState = json().fromJson(AgentMatchState::class.java,
            coordinator.serializeCommittedMatchForPublication())
        fun play(civId: String) = coordinator.applyNationChange(civId, NationControlChange.PLAY_AS, null)
        fun rejected(civId: String?, change: NationControlChange = NationControlChange.PLAY_AS, profile: AgentProfile? = null) {
            val before = coordinator.getStateRevision()
            try { coordinator.applyNationChange(civId, change, profile); fail("Unauthorized change applied") }
            catch (_: IllegalArgumentException) {}
            catch (_: IllegalStateException) {}
            assertEquals(before, coordinator.getStateRevision())
        }
    }

    @Test fun `spectator may play inactive nation and preserves prior human assignment`() {
        val f = Fixture()
        f.play(f.rome); f.play(f.greece)
        assertEquals(ControllerKind.HUMAN, f.coordinator.getController(f.rome)!!.kind)
        assertEquals(ControllerKind.HUMAN, f.coordinator.getController(f.greece)!!.kind)
        assertEquals(f.spectator, f.saved().localControlCivId)
        assertEquals(f.greece, f.saved().localViewingCivId)
        assertEquals(f.rome, f.coordinator.getCurrentCivId())
        assertEquals(2, f.coordinator.getStateRevision())
        assertEquals(2, f.coordinator.getNationSettings().size)
    }

    @Test fun `human settings retain personality through explicit handback and spectator view`() {
        val f = Fixture()
        f.play(f.rome)
        val edited = f.coordinator.getNationSettings().first { it.civId == f.rome }.profile!!
        edited.modelId = "custom-model"; edited.reasoning = "high"; edited.personality = "Keep this strategy"
        f.coordinator.applyNationChange(f.rome, NationControlChange.SAVE_AGENT_SETTINGS, edited)
        edited.personality = "caller mutation"
        assertEquals(ControllerKind.HUMAN, f.coordinator.getController(f.rome)!!.kind)
        f.coordinator.applyNationChange(null, NationControlChange.SPECTATE, null)
        assertEquals(ControllerKind.HUMAN, f.coordinator.getController(f.rome)!!.kind)
        f.coordinator.applyNationChange(f.rome, NationControlChange.HAND_TO_AGENT, null)
        assertEquals("Keep this strategy", f.coordinator.getController(f.rome)!!.agentProfile!!.personality)
        assertEquals("custom-model", f.coordinator.getController(f.rome)!!.agentProfile!!.modelId)
        assertEquals(f.spectator, f.saved().localViewingCivId)
    }

    @Test fun `ordinary human cannot manage foreign nation or spectate`() {
        val f = Fixture(ownHuman = true)
        assertEquals(listOf(f.rome), f.coordinator.getNationSettings().map { it.civId })
        f.rejected(f.greece); f.rejected(null, NationControlChange.SPECTATE)
        assertFalse(f.coordinator.canSpectate())
    }

    @Test fun `online native AI dead barbarian and city state are not eligible`() {
        Fixture(online = true).also { it.rejected(it.rome); assertTrue(it.coordinator.getNationSettings().isEmpty()) }
        val f = Fixture()
        val snapshot = json().fromJson(GameInfo::class.java, f.coordinator.serializeCommittedGameForPublication())
        val match = f.saved()
        match.controllers[f.greece]!!.kind = ControllerKind.BUILTIN_AI
        val native = MatchCoordinator(snapshot, match)
        assertFalse(native.getNationSettings().any { it.civId == f.greece })
        try { native.applyNationChange(f.greece, NationControlChange.PLAY_AS, null); fail("Built-in AI was converted") }
        catch (_: IllegalArgumentException) {}
        for (name in listOf("Egypt", Constants.barbarians, "Geneva")) {
            val civ = f.game.addCiv(f.game.ruleset.nations[name]!!, isPlayer = true)
            val state = f.saved(); state.controllers[civ.civID] = CivilizationController(civ.civID, ControllerKind.HUMAN)
            val c = MatchCoordinator(f.game.gameInfo, state)
            assertFalse(c.getNationSettings().any { it.civId == civ.civID })
            try { c.applyNationChange(civ.civID, NationControlChange.PLAY_AS, null); fail("Ineligible nation $name") }
            catch (_: IllegalArgumentException) {}
        }
    }

    @Test fun `failed checkpoint preserves assignment view profile and revision`() {
        val f = Fixture(); val before = f.saved()
        f.writer.failNextWrite = true
        try { f.play(f.greece); fail("Checkpoint failure acknowledged") } catch (_: AgentCommandException) {}
        val after = f.saved()
        assertEquals(before.localViewingCivId, after.localViewingCivId)
        assertEquals(before.stateRevision, after.stateRevision)
        assertEquals(ControllerKind.EXTERNAL_AGENT, after.controllers[f.greece]!!.kind)
        assertTrue(f.coordinator.isPaused())
    }

    @Test fun `saved identities and retained profiles round trip without aliases`() {
        val f = Fixture(); f.play(f.rome)
        val match = f.saved(); val copy = match.deepCopy()
        copy.retainedAgentProfiles[f.rome]!!.personality = "changed copy"
        assertEquals("Remember every promise", match.retainedAgentProfiles[f.rome]!!.personality)
        val game = json().fromJson(GameInfo::class.java, f.coordinator.serializeCommittedGameForPublication())
        game.agentMatchStateJson = AgentOrdinarySave.encode(match)
        val restored = AgentOrdinarySave.read(game)!!
        assertEquals(f.spectator, restored.localControlCivId)
        assertEquals(f.rome, NationControl.savedViewerCivId(game, restored))
        assertEquals("Remember every promise", restored.retainedAgentProfiles[f.rome]!!.personality)
    }

    @Test fun `restoring retained profiles preserves historical catalog choices but new edits validate`() {
        val f = Fixture(); f.play(f.rome)
        val match = f.saved()
        match.retainedAgentProfiles[f.rome]!!.apply {
            provider = "historical-provider"; modelId = "retired-model"; reasoning = "historical-effort"
        }
        val game = json().fromJson(GameInfo::class.java, f.coordinator.serializeCommittedGameForPublication())
        game.agentMatchStateJson = AgentOrdinarySave.encode(match)
        val restored = AgentOrdinarySave.read(game)!!
        assertEquals("historical-provider", restored.retainedAgentProfiles[f.rome]!!.provider)
        try { NationControl.validateProfile(restored.retainedAgentProfiles[f.rome]!!); fail("New request accepted unsupported profile") }
        catch (_: IllegalArgumentException) {}
    }

    @Test fun `raw saves resolve extension mod major nations for origin and viewer`() {
        val f = Fixture(); val modName = "nation-control-extension-test"
        val previous = RulesetCache[modName]
        try {
            val nation = Nation().apply {
                name = "Extension Major"
                outerColor = listOf(90, 120, 150)
                cities = arrayListOf("Extension Capital")
            }
            RulesetCache[modName] = Ruleset().apply { name = modName; nations[nation.name] = nation }
            f.game.gameInfo.gameParameters.mods.add(modName)
            f.game.ruleset.nations[nation.name] = nation
            val civ = f.game.addCiv(nation, isPlayer = true)
            f.game.addUnit("Warrior", civ, f.game.getTile(-2, 0))
            val match = f.saved().apply { controllers[civ.civID] = CivilizationController(civ.civID, ControllerKind.HUMAN) }
            val coordinator = MatchCoordinator(f.game.gameInfo, match)
            coordinator.applyNationChange(civ.civID, NationControlChange.PLAY_AS, null)
            for (origin in listOf(f.spectator, civ.civID)) {
                val saved = json().fromJson(AgentMatchState::class.java, coordinator.serializeCommittedMatchForPublication()).apply {
                    localControlCivId = origin
                }
                val raw = json().fromJson(GameInfo::class.java, coordinator.serializeCommittedGameForPublication())
                raw.agentMatchStateJson = AgentOrdinarySave.encode(saved)
                val restored = AgentOrdinarySave.read(raw)!!
                assertEquals(origin, restored.localControlCivId)
                assertEquals(civ.civID, NationControl.savedViewerCivId(raw, restored))
            }
        } finally {
            if (previous == null) RulesetCache.remove(modName) else RulesetCache[modName] = previous
        }
    }

    @Test fun `attached ordinary human binds own scope even with spectator in roster`() {
        val f = Fixture(ownHuman = true)
        val match = f.saved().apply { localControlCivId = null; localViewingCivId = null }
        NationControl.initialize(f.game.gameInfo, match, f.rome)
        assertEquals(f.rome, match.localControlCivId)
        assertEquals(f.rome, match.localViewingCivId)
        val localHost = MatchCoordinator(f.game.gameInfo, match)
        assertEquals(listOf(f.rome), localHost.getNationSettings().map { it.civId })
        assertFalse(localHost.canSpectate())
    }

    @Test fun `old metadata without local fields initializes spectator and retained profile before transients`() {
        val f = Fixture()
        val raw = com.badlogic.gdx.utils.JsonReader().parse(AgentOrdinarySave.encode(f.saved()))
        raw.remove("localControlCivId"); raw.remove("localViewingCivId"); raw.remove("retainedAgentProfiles")
        val game = json().fromJson(GameInfo::class.java, f.coordinator.serializeCommittedGameForPublication())
        game.agentMatchStateJson = raw.toJson(com.badlogic.gdx.utils.JsonWriter.OutputType.json)
        val restored = AgentOrdinarySave.read(game)!!
        assertEquals(f.spectator, restored.localControlCivId)
        assertEquals(f.spectator, restored.localViewingCivId)
        assertEquals("Remember every promise", restored.retainedAgentProfiles[f.rome]!!.personality)
    }

    @Test fun `missing old identities choose spectator or unique human and fail closed when ambiguous`() {
        val f = Fixture(); val match = f.saved()
        match.localControlCivId = null; match.localViewingCivId = null
        assertEquals(f.spectator, NationControl.savedViewerCivId(f.game.gameInfo, match))
        val noSpectator = f.game.gameInfo.clone()
        noSpectator.civilizations.removeAll { it.civName == Constants.spectator }
        assertNull(NationControl.savedViewerCivId(noSpectator, match))
        match.controllers[f.rome]!!.kind = ControllerKind.HUMAN
        assertEquals(f.rome, NationControl.savedViewerCivId(noSpectator, match))
        match.controllers[f.greece]!!.kind = ControllerKind.HUMAN
        assertNull(NationControl.savedViewerCivId(noSpectator, match))
    }
}
