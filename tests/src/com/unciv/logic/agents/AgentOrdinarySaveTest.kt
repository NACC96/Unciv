package com.unciv.logic.agents

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.utils.Json
import com.badlogic.gdx.utils.JsonReader
import com.badlogic.gdx.utils.JsonWriter
import com.unciv.UncivGame
import com.unciv.app.desktop.agents.DesktopAgentMatch
import com.unciv.json.json
import com.unciv.logic.GameInfo
import com.unciv.logic.map.HexCoord
import com.unciv.utils.DebugUtils
import com.unciv.logic.files.UncivFiles
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.app.desktop.agents.SupervisorProcess
import com.unciv.app.desktop.agents.SupervisorRunner
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.mockito.Mockito
import org.mockito.Answers
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.worldscreen.unit.UnitTable
import com.unciv.ui.screens.worldscreen.worldmap.WorldMapHolder

@RunWith(BaseTestRunner::class)
class AgentOrdinarySaveTest {
    private class Fixture {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_Vanilla)
        val writer = InMemoryCheckpointWriter()
        val civId: String
        val unitId: Int
        val workerId: Int
        val coordinator: MatchCoordinator

        init {
            game.gameInfo.gameParameters.baseRuleset = BaseRuleset.Civ_V_Vanilla.fullName
            game.makeHexagonalMap(4)
            val civ = game.addCiv(game.ruleset.nations["Rome"]!!, isPlayer = true)
            civId = civ.civID
            game.addCity(civ, game.getTile(0, 0))
            unitId = game.addUnit("Warrior", civ, game.getTile(-1, 0)).id
            workerId = game.addUnit("Worker", civ, game.getTile(0, -1)).apply {
                currentMovement = 1f
                health = 83
            }.id
            game.getTile(0, -1).queueImprovement("Farm", 2)
            civ.addGold(600)
            val match = AgentMatchState(matchId = "saved-match", timelineId = "saved-timeline",
                stateRevision = 7, turnSerial = 3, turnPhase = TurnPhase.AWAITING_DECISIONS)
            match.controllers[civId] = CivilizationController(civId, ControllerKind.EXTERNAL_AGENT,
                AgentProfile("profile-a", "Ada", "codex", "gpt-5.6-luna", "low", "Explore carefully", 4))
            match.privateNotes[civId] = "snapshot A"
            match.notesRevisions[civId] = 2
            coordinator = MatchCoordinator(game.gameInfo, match, writer)
        }

        fun snapshot(): GameInfo = AgentSnapshotPublication.deserializeGame(
            AgentSnapshotPublication.buildPayload(coordinator, civId))
    }

    private fun savedMatch(game: GameInfo): AgentMatchState {
        val root = JsonReader().parse(UncivFiles.gameInfoToString(game, false))
        val encoded = root.getString("agentMatchStateJson", null)
        assertNotNull("Ordinary saves must contain their paired agent state", encoded)
        return json().fromJson(AgentMatchState::class.java, encoded)
    }

    @Test fun `published ordinary save and its clone retain the exact agent state`() {
        val f = Fixture()
        for (snapshot in listOf(f.snapshot(), f.snapshot().clone())) {
            val saved = savedMatch(snapshot)
            assertEquals("saved-match", saved.matchId)
            assertEquals("saved-timeline", saved.timelineId)
            assertEquals(7, saved.stateRevision)
            assertEquals(3, saved.turnSerial)
            assertEquals("snapshot A", saved.privateNotes[f.civId])
            assertEquals(4, saved.controllers[f.civId]!!.agentProfile!!.profileRevision)
        }
    }

    @Test fun `agent ordinary saves use a compatibility floor newer than old readers`() {
        val f = Fixture()
        val root = JsonReader().parse(UncivFiles.gameInfoToString(f.snapshot(), false))
        assertTrue("Old readers must reject protected saves", root.get("version").getInt("number") >= 5)
    }

    @Test fun `ordinary string loader rejects malformed marked metadata rather than dropping control`() {
        val f = Fixture()
        val root = JsonReader().parse(UncivFiles.gameInfoToString(f.snapshot(), false))
        // Current readers used to ignore this field and enter a plain Human game.
        root.remove("agentMatchStateJson")
        root.addChild("agentMatchStateJson", com.badlogic.gdx.utils.JsonValue("{}"))
        assertThrows(Exception::class.java) { UncivFiles.gameInfoFromString(root.toJson(com.badlogic.gdx.utils.JsonWriter.OutputType.json)) }
    }

    @Test fun `frozen save A stays paired after newer gameplay and notes checkpoint B`() {
        val f = Fixture()
        f.coordinator.execute(UnitOrderCommand(f.civId, "order-a", 7, f.unitId, UnitOrder.FORTIFY))
        val snapshotA = f.snapshot()
        val actionA = snapshotA.getCivilization(f.civId).units.getCivUnits().first().action
        val a = snapshotA.clone()
        val savedA = UncivFiles.gameInfoToString(a, false)
        f.coordinator.execute(UpdateNotesCommand(f.civId, "snapshot B", 2))
        f.coordinator.execute(UnitOrderCommand(f.civId, "order-b", 8, f.unitId, UnitOrder.WAKE))
        assertEquals("snapshot B", savedMatch(f.snapshot()).privateNotes[f.civId])
        val restoredA = UncivFiles.gameInfoFromString(savedA)
        val restoredState = savedMatch(restoredA)
        assertEquals(8, restoredState.stateRevision)
        assertEquals("snapshot A", restoredState.privateNotes[f.civId])
        assertEquals(listOf("order-a"), restoredState.currentTurnReceipts.map { it.actionId })
        assertEquals(actionA, restoredA.getCivilization(f.civId).units.getCivUnits().first().action)
        assertEquals(savedA, UncivFiles.gameInfoToString(a, false))
        val restoredCoordinator = MatchCoordinator(restoredA, restoredState)
        assertEquals("snapshot A" to 2, restoredCoordinator.getNotes(f.civId))
        assertNotNull(restoredCoordinator.getReceipt("order-a"))
        assertNull(restoredCoordinator.getReceipt("order-b"))
    }

    @Test fun `file and compressed string loaders suppress their first production automation`() {
        val f = Fixture()
        val prior = UncivGame.Current.settings.autoAssignCityProduction
        val directory = Files.createTempDirectory("unciv-ordinary-save").toFile()
        val files = UncivFiles(Gdx.files, directory.absolutePath)
        val file = FileHandle(directory.resolve("agent-save.json"))
        try {
            UncivGame.Current.settings.autoAssignCityProduction = true
            val snapshot = f.snapshot().clone()
            files.saveGame(snapshot, file)
            for (restored in listOf(files.loadGameFromFile(file),
                UncivFiles.gameInfoFromString(UncivFiles.gameInfoToString(snapshot, true)))) {
                assertTrue(restored.getCivilization(f.civId).cities.first().cityConstructions.constructionQueue.isEmpty())
                assertEquals(5, restored.version.number)
                assertEquals("snapshot A", savedMatch(restored).privateNotes[f.civId])
            }
            val plain = f.snapshot().apply { agentMatchStateJson = null }
            val restoredPlain = UncivFiles.gameInfoFromString(UncivFiles.gameInfoToString(plain, false))
            assertEquals(4, restoredPlain.version.number)
            assertFalse(restoredPlain.getCivilization(f.civId).cities.first().cityConstructions.constructionQueue.isEmpty())
        } finally {
            UncivGame.Current.settings.autoAssignCityProduction = prior
            directory.deleteRecursively()
        }
    }

    @Test fun `checkpoint worlds contain no embedded match and repeated restore does not nest`() {
        val f = Fixture()
        var snapshot = f.snapshot()
        repeat(4) {
            val coordinator = MatchCoordinator(snapshot, savedMatch(snapshot))
            val bundle = json().fromJson(AgentBundle::class.java, coordinator.saveToJson())
            assertNull(JsonReader().parse(bundle.gameJson).get("agentMatchStateJson"))
            assertNull(JsonReader().parse(bundle.matchJson).get("gameJson"))
            assertEquals(AGENT_MATCH_FORMAT_VERSION, JsonReader().parse(bundle.matchJson).getInt("formatVersion"))
            snapshot = AgentSnapshotPublication.deserializeGame(AgentSnapshotPublication.buildPayload(coordinator, f.civId))
        }
        assertEquals(UncivFiles.gameInfoToString(f.snapshot(), false), UncivFiles.gameInfoToString(snapshot, false))
    }

    @Test fun `desktop restore stays idle and preserves completed and failure states`() {
        val f = Fixture()
        val calls = AtomicInteger()
        val directory = Files.createTempDirectory("unciv-paused-restore").toFile()
        try {
            for (phase in TurnPhase.entries) {
                val snapshot = f.snapshot()
                val match = savedMatch(snapshot).apply { turnPhase = phase }
                val host = DesktopAgentMatch(snapshot, match, AgentOrdinarySave.setupFor(snapshot, match), directory,
                    supervisorFactory = { calls.incrementAndGet(); error("Restore must not start a runner") })
                assertEquals(if (phase == TurnPhase.COMPLETED) AgentRunState.FINISHED else AgentRunState.PAUSED, host.getRunState())
                assertEquals(phase, host.coordinator.getTurnPhase())
                assertEquals(7, host.coordinator.getStateRevision())
                assertEquals("snapshot A" to 2, host.coordinator.getNotes(f.civId))
                val canonical = AgentSnapshotPublication.deserializeGame(AgentSnapshotPublication.buildPayload(host.coordinator, f.civId))
                val worker = canonical.getCivilization(f.civId).units.getCivUnits().first { it.id == f.workerId }
                assertEquals(1f, worker.currentMovement)
                assertEquals(83, worker.health)
                assertEquals("Farm", worker.getTile().improvementInProgress)
                assertEquals(2, worker.getTile().turnsToImprovement)
                assertEquals(600, canonical.getCivilization(f.civId).gold)
                assertFalse(directory.resolve(".agent-data").exists())
                assertEquals(0, calls.get())
            }
            val snapshot = f.snapshot()
            val failed = savedMatch(snapshot).apply { matchPaused = true; matchPausedReason = "Storage unavailable" }
            val host = DesktopAgentMatch(snapshot, failed, AgentOrdinarySave.setupFor(snapshot, failed), directory,
                supervisorFactory = { calls.incrementAndGet(); error("Restore must remain idle") })
            assertEquals(AgentRunState.NEEDS_ATTENTION, host.getRunState())
            assertFalse(host.canResume())
            assertEquals("Storage unavailable", host.getFailureReason())
            assertEquals(0, calls.get())
        } finally { directory.deleteRecursively() }
    }

    @Test fun `restore factory bypasses new-game setup and missing hosts fail closed`() {
        val f = Fixture()
        val snapshot = f.snapshot()
        val match = savedMatch(snapshot)
        val world = Mockito.mock(WorldScreen::class.java)
        val session = Mockito.mock(AgentMatchSession::class.java)
        Mockito.`when`(session.getCommittedMatchStateJson()).thenReturn(AgentOrdinarySave.encode(match))
        val oldCreator = AgentMatchSessionFactory.creator
        val oldRestorer = AgentMatchSessionFactory.restorer
        try {
            AgentMatchSessionFactory.creator = { _, _, _, _ -> error("Restore must bypass new-game creation") }
            AgentMatchSessionFactory.restorer = null
            assertThrows(Exception::class.java) { AgentMatchSessionFactory.restore(snapshot, match, world) }
            AgentMatchSessionFactory.restorer = { game, restored, setup, owner ->
                assertSame(snapshot, game)
                assertSame(world, owner)
                assertEquals("saved-match", restored.matchId)
                assertEquals("saved-timeline", restored.timelineId)
                assertEquals(7, restored.stateRevision)
                assertEquals(TurnPhase.AWAITING_DECISIONS, restored.turnPhase)
                assertEquals("Explore carefully", setup.slots["Rome"]!!.profile.personality)
                assertEquals("low", setup.slots["Rome"]!!.profile.reasoning)
                session
            }
            assertSame(session, AgentMatchSessionFactory.restore(snapshot, match, world))
            Mockito.verify(session).getCommittedMatchStateJson()
            Mockito.verifyNoMoreInteractions(session)
            AgentMatchSessionFactory.restorer = { _, _, _, _ -> error("Restore failure") }
            assertThrows(IllegalStateException::class.java) { AgentMatchSessionFactory.restore(snapshot, match, world) }
        } finally {
            AgentMatchSessionFactory.creator = oldCreator
            AgentMatchSessionFactory.restorer = oldRestorer
        }
    }

    @Test fun `future blank missing and mismatched save metadata fail before controls`() {
        val f = Fixture()
        val base = f.snapshot()
        val variants = listOf("", " ", "null", "[]", "{}", "{broken",
            AgentOrdinarySave.encode(savedMatch(base).apply { formatVersion = 999 }),
            base.agentMatchStateJson!!.replace("\"formatVersion\":3", "\"formatVersion\":1.5"),
            base.agentMatchStateJson!!.replace("\"formatVersion\":3", "\"formatVersion\":\"2\""),
            base.agentMatchStateJson!!.replace("\"encodingVersion\":1", "\"encodingVersion\":999"),
            AgentOrdinarySave.encode(savedMatch(base).apply { controllers.clear() }),
            AgentOrdinarySave.encode(savedMatch(base).apply { matchId = "../escaped" }),
            AgentOrdinarySave.encode(savedMatch(base).apply { currentTurnReceipts.add(ActionReceipt(actorCivId = f.civId, timelineId = "other")) }))
        for (encoded in variants) {
            val invalid = base.clone().apply { agentMatchStateJson = encoded }
            val text = json().toJson(invalid)
            assertThrows("Metadata: $encoded", Exception::class.java) { UncivFiles.gameInfoFromString(text) }
        }
        val missing = base.clone().apply { agentMatchStateJson = null; version = com.unciv.logic.CompatibilityVersion.CURRENT_COMPATIBILITY_VERSION }
        assertThrows(Exception::class.java) { UncivFiles.gameInfoFromString(json().toJson(missing)) }
        val historical = base.clone().apply {
            // Genuine v1 had neither a format field nor map knowledge. Keep its saved identities/controllers.
            val root = JsonReader().parse(agentMatchStateJson)
            root.remove("formatVersion")
            root.remove("mapKnowledge")
            agentMatchStateJson = root.toJson(JsonWriter.OutputType.json)
        }
        val migrated = AgentOrdinarySave.read(UncivFiles.gameInfoFromString(json().toJson(historical)))!!
        assertEquals(AGENT_MATCH_FORMAT_VERSION, migrated.formatVersion)
        assertTrue(migrated.mapKnowledge.tilesByCiv.isEmpty())
        assertEquals("saved-match", migrated.matchId)
        assertEquals("snapshot A", migrated.privateNotes[f.civId])
    }

    @Test fun `ordinary compact and legacy-v2 memories preserve hidden facts and resource provenance`() {
        val f = Fixture()
        val base = f.snapshot()
        val memory = ObservedTile(4, 0, detailsKnown = true, lastSeenTurn = 1,
            lastSeenRevision = 2, terrain = "Grassland", features = arrayListOf("Forest"),
            resource = ObservedResource("Iron", 6, 2, 4, ResourceKnowledgeSource.TECHNOLOGY),
            ownerCivId = f.civId, improvement = "Mine", land = true, water = false)
        val state = savedMatch(base).apply { mapKnowledge.put(f.civId, memory) }
        val compact = AgentOrdinarySave.encode(state)
        val legacy = Json(JsonWriter.OutputType.json).apply { setUsePrototypes(false) }.toJson(state)
        assertTrue(compact.contains("\"encodingVersion\":1"))
        assertTrue(legacy.contains("tilesByCiv"))
        for (encoded in listOf(compact, legacy)) {
            val selected = base.clone().apply { agentMatchStateJson = encoded }
            val loaded = UncivFiles.gameInfoFromString(UncivFiles.gameInfoToString(selected, false))
            val restored = AgentOrdinarySave.read(loaded)!!
            assertEquals(AGENT_MATCH_FORMAT_VERSION, restored.formatVersion)
            assertEquals(memory.toWire(), restored.mapKnowledge.get(f.civId, HexCoord(4, 0))!!.toWire())
            val host = MatchCoordinator(loaded, restored)
            val remembered = host.inspectTile(f.civId, HexCoord(4, 0))!!
            assertEquals(TileKnowledgeVisibility.REMEMBERED, remembered.visibility)
            assertEquals(2, remembered.lastSeenRevision)
            assertEquals(ResourceKnowledgeSource.TECHNOLOGY, remembered.resource!!.source)
            assertEquals(4, remembered.resource!!.updatedRevision)
            val rewritten = AgentSnapshotPublication.buildPayload(host, f.civId).serializedMatchState
            assertTrue(rewritten.contains("\"encodingVersion\":1"))
        }
    }

    @Test fun `ordinary loading uses normal visibility before the first transients even with debug reveal`() {
        val f = Fixture()
        val saved = UncivFiles.gameInfoToString(f.snapshot(), false)
        val previous = DebugUtils.VISIBLE_MAP
        try {
            DebugUtils.VISIBLE_MAP = true
            val restored = UncivFiles.gameInfoFromString(saved)
            assertTrue(restored.agentNormalVisibility)
            assertFalse(restored.tileMap[HexCoord(4, 0)].isExploredNormally(restored.getCivilization(f.civId)))
            assertNull(MatchCoordinator(restored, AgentOrdinarySave.read(restored)!!).inspectTile(f.civId, HexCoord(4, 0)))
        } finally { DebugUtils.VISIBLE_MAP = previous }
    }

    @Test fun `initial factory metadata comes from constructed canonical knowledge`() {
        val f = Fixture()
        val setup = AgentOrdinarySave.setupFor(f.game.gameInfo, savedMatch(f.snapshot()))
        val original = AgentMatchSessionFactory.creator
        val session = Mockito.mock(AgentMatchSession::class.java)
        try {
            AgentMatchSessionFactory.creator = { game, match, _, _ ->
                assertTrue(match.mapKnowledge.tilesByCiv.isEmpty())
                val canonical = MatchCoordinator(game, match)
                val captured = canonical.serializeCommittedMatchForPublication()
                val initial = json().fromJson(AgentMatchState::class.java, captured)
                assertFalse(initial.mapKnowledge.tilesByCiv[f.civId]!!.isEmpty())
                Mockito.`when`(session.getCommittedMatchStateJson()).thenReturn(captured)
                session
            }
            assertSame(session, AgentMatchSessionFactory.create(f.game.gameInfo, setup, Mockito.mock(WorldScreen::class.java)))
            val saved = AgentOrdinarySave.read(f.game.gameInfo)!!
            assertFalse(saved.mapKnowledge.tilesByCiv[f.civId]!!.isEmpty())
            assertEquals("Grassland", saved.mapKnowledge.get(f.civId, HexCoord())!!.terrain)
        } finally { AgentMatchSessionFactory.creator = original }
    }

    @Test fun `initial factory refuses uncaptured metadata instead of saving raw setup state`() {
        val f = Fixture()
        val setup = AgentOrdinarySave.setupFor(f.game.gameInfo, savedMatch(f.snapshot()))
        val original = AgentMatchSessionFactory.creator
        try {
            AgentMatchSessionFactory.creator = { _, _, _, _ -> Mockito.mock(AgentMatchSession::class.java) }
            assertThrows(Exception::class.java) {
                AgentMatchSessionFactory.create(f.game.gameInfo, setup, Mockito.mock(WorldScreen::class.java))
            }
            assertNull(f.game.gameInfo.agentMatchStateJson)
        } finally { AgentMatchSessionFactory.creator = original }
    }

    @Test fun `frozen ordinary save retains map mail acknowledgement and private receipts before later changes`() {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_Vanilla).apply {
            makeHexagonalMap(7)
            gameInfo.gameParameters.baseRuleset = BaseRuleset.Civ_V_Vanilla.fullName
        }
        val rome = game.addCiv(game.ruleset.nations["Rome"]!!, isPlayer = true)
        val greece = game.addCiv(game.ruleset.nations["Greece"]!!, isPlayer = true)
        val china = game.addCiv(game.ruleset.nations["China"]!!, isPlayer = true)
        for ((civ, x) in listOf(rome to 0, greece to 3, china to -5)) {
            game.addCity(civ, game.getTile(x, 0))
            game.addUnit("Warrior", civ, game.getTile(x, -1))
        }
        rome.diplomacyFunctions.makeCivilizationsMeet(greece)
        game.gameInfo.currentPlayer = rome.civID
        val state = AgentMatchState(matchId = "mail-save", timelineId = "mail-timeline",
            turnSerial = 5, turnPhase = TurnPhase.AWAITING_DECISIONS)
        for (civ in listOf(rome, greece, china)) state.controllers[civ.civID] = CivilizationController(
            civ.civID, ControllerKind.EXTERNAL_AGENT, AgentProfile("p-${civ.civID}", civ.civName, "codex", "gpt-6-luna", "xhigh", "", 1))
        state.diplomaticMessages.add(DiplomaticMessage("incoming", greece.civID, rome.civID,
            "Please protect the border.", 2, 5))
        state.mapKnowledge.put(rome.civID, ObservedTile(7, 0, detailsKnown = true,
            lastSeenTurn = 1, lastSeenRevision = 0, terrain = "Desert"))
        val coordinator = MatchCoordinator(game.gameInfo, state)
        val bridge = AgentEngineBridge(coordinator)
        val newlyVisible = HexCoord(-3, -1)
        assertNull(coordinator.inspectTile(rome.civID, newlyVisible))
        bridge.act(mapOf("protocolVersion" to 1, "actorCivId" to rome.civID, "actionId" to "ack-a",
            "expectedRevision" to 0, "type" to "diplomacy.acknowledgeMessage",
            "args" to mapOf("messageId" to "incoming", "text" to "I read your request.")))
        val a = AgentSnapshotPublication.deserializeGame(AgentSnapshotPublication.buildPayload(coordinator, rome.civID))
        val frozenA = UncivFiles.gameInfoToString(a.clone(), false)
        coordinator.queueGuidance(rome.civID, "Newer guidance")
        coordinator.execute(UpdateNotesCommand(rome.civID, "Newer notes", 0))
        bridge.act(mapOf("protocolVersion" to 1, "actorCivId" to rome.civID, "actionId" to "send-b",
            "expectedRevision" to 1, "type" to "diplomacy.message",
            "args" to mapOf("targetCivId" to greece.civID, "text" to "Newer message B")))
        coordinator.execute(MoveUnitCommand(rome.civID, "move-b", 2,
            rome.units.getCivUnits().single().id, HexCoord(-2, -1)))
        assertNotNull(coordinator.inspectTile(rome.civID, newlyVisible))
        val loadedA = UncivFiles.gameInfoFromString(frozenA)
        val savedA = AgentOrdinarySave.read(loadedA)!!
        val restored = MatchCoordinator(loadedA, savedA)
        assertEquals(1, restored.getStateRevision())
        assertEquals("I read your request.", restored.getMessageHistory(rome.civID).single().acknowledgementText)
        assertTrue(restored.getMessageHistory(china.civID).isEmpty())
        assertNotNull(restored.getReceipt("ack-a"))
        assertNull(restored.getReceipt("send-b"))
        assertTrue(savedA.guidanceQueue.isEmpty())
        assertTrue(savedA.privateNotes.isEmpty())
        assertEquals("Desert", restored.inspectTile(rome.civID, HexCoord(7, 0))!!.terrain)
        assertNull(restored.inspectTile(rome.civID, newlyVisible))
        assertNull(restored.getReceipt("move-b"))
        assertEquals(mapOf("status" to "rejected", "actionId" to "ack-a"),
            AgentEngineBridge(restored).actionResult("ack-a", china.civID))
        // HashMap key order is not part of save semantics; every nested world field must still match.
        assertEquals(AgentJson.parse(frozenA), AgentJson.parse(UncivFiles.gameInfoToString(a, false)))
        assertEquals(JsonReader().parse(frozenA).getString("agentMatchStateJson"), a.agentMatchStateJson)
    }

    @Test fun `default and numbered autosaves preserve frozen agent metadata in isolated storage`() {
        val f = Fixture()
        val directory = Files.createTempDirectory("unciv-agent-autosave").toFile()
        val isolatedFiles = object : com.badlogic.gdx.Files by Gdx.files {
            override fun isExternalStorageAvailable() = false
        }
        val files = UncivFiles(isolatedFiles, directory.absolutePath)
        val prior = UncivFiles.preferExternalStorage
        try {
            UncivFiles.preferExternalStorage = false
            val frozen = f.snapshot().clone()
            f.coordinator.execute(UpdateNotesCommand(f.civId, "newer unsaved notes", 2))
            files.autosaves.autoSave(frozen, nextTurn = true)
            val saves = directory.resolve("SaveFiles").listFiles()!!
            assertEquals(2, saves.size)
            for (file in saves) {
                val restored = files.loadGameFromFile(FileHandle(file))
                assertEquals(5, restored.version.number)
                assertEquals("snapshot A", savedMatch(restored).privateNotes[f.civId])
            }
        } finally {
            UncivFiles.preferExternalStorage = prior
            directory.deleteRecursively()
        }
    }

    @Test fun `older save resumes with fresh provider bindings and isolated recovery lineage`() {
        val f = Fixture()
        val directory = Files.createTempDirectory("unciv-agent-lineage").toFile()
        try {
            val saveA = f.snapshot()
            val matchA = savedMatch(saveA)
            val original = DesktopAgentMatch(saveA, matchA, AgentOrdinarySave.setupFor(saveA, matchA), directory)
            original.coordinator.execute(UpdateNotesCommand(f.civId, "newer checkpoint B", 2))
            val originalFiles = original.recoveryDirectory.listFiles()!!.filter { it.isFile }.associate { it.name to it.readBytes() }
            val calls = AtomicInteger()
            val invoked = CountDownLatch(1)
            lateinit var restored: DesktopAgentMatch
            restored = DesktopAgentMatch.restore(saveA, matchA, directory, supervisorFactory = {
                calls.incrementAndGet()
                object : SupervisorRunner {
                    override fun isAlive() = true
                    override fun shutdown() {}
                    override fun kill() { error("No provider may be killed") }
                    override fun runTurn(control: Map<String, Any?>): SupervisorProcess.TurnOutcome {
                        try {
                            val config = control["providerConfig"] as Map<*, *>
                            assertNull(config["resumeId"])
                            assertEquals("gpt-5.6-luna", config["model"])
                            assertEquals("low", config["reasoning"])
                            restored.answerEngineRequest("notes_put", mapOf("civId" to f.civId,
                                "notes" to "restored branch C", "expectedNotesRevision" to 2))
                            return SupervisorProcess.TurnOutcome("no_commit", null, null, false, emptyList(), "scripted test stops here")
                        } finally { invoked.countDown() }
                    }
                }
            })
            assertEquals(AgentRunState.PAUSED, restored.getRunState())
            assertEquals(0, calls.get())
            assertFalse(restored.recoveryDirectory.exists())
            assertTrue(restored.recoveryDirectory.toPath().startsWith(original.recoveryDirectory.toPath().resolve("restores")))
            val otherRestore = DesktopAgentMatch.restore(saveA, matchA, directory)
            assertNotEquals(restored.recoveryDirectory, otherRestore.recoveryDirectory)
            restored.resume()
            assertTrue("The scripted runner should run only after explicit resume", invoked.await(5, TimeUnit.SECONDS))
            assertEquals(1, calls.get())
            assertEquals("restored branch C" to 3, restored.coordinator.getNotes(f.civId))
            assertTrue(restored.recoveryDirectory.listFiles()!!.any { it.isFile })
            for ((name, bytes) in originalFiles) assertArrayEquals(bytes, original.recoveryDirectory.resolve(name).readBytes())
            assertEquals("saved-match", restored.matchId)
            assertEquals("saved-timeline", restored.coordinator.getTimelineId())
            assertEquals(7, restored.coordinator.getStateRevision())
        } finally { directory.deleteRecursively() }
    }

    @Test fun `durable guidance restores for one next invocation and metadata snapshots stay independent`() {
        val f = Fixture()
        val before = f.snapshot()
        f.coordinator.queueGuidance(f.civId, "Avoid starting a war")
        val queued = f.snapshot()
        assertTrue(savedMatch(before).guidanceQueue.isEmpty())
        assertEquals(7, savedMatch(queued).stateRevision)
        val restored = MatchCoordinator(queued, savedMatch(queued))
        assertEquals(listOf("Avoid starting a war"), restored.takeGuidanceForDelivery(f.civId))
        assertTrue(restored.takeGuidanceForDelivery(f.civId).isEmpty())
        val delivered = AgentSnapshotPublication.deserializeGame(AgentSnapshotPublication.buildPayload(restored, f.civId))
        assertEquals(GuidanceState.DELIVERED, savedMatch(delivered).guidanceQueue.single().state)
        assertEquals(GuidanceState.QUEUED, savedMatch(queued).guidanceQueue.single().state)
    }

    @Test fun `metadata publication includes direct phase changes and failed candidate pause only`() {
        val f = Fixture()
        val before = f.snapshot()
        f.coordinator.forcePhaseForTest(TurnPhase.COMPLETED)
        assertEquals(TurnPhase.COMPLETED, savedMatch(f.snapshot()).turnPhase)
        assertEquals(TurnPhase.AWAITING_DECISIONS, savedMatch(before).turnPhase)
        f.coordinator.forcePhaseForTest(TurnPhase.AWAITING_DECISIONS)
        f.writer.failNextWrite = true
        assertThrows(AgentCommandException::class.java) {
            f.coordinator.execute(UpdateNotesCommand(f.civId, "uncommitted candidate", 2))
        }
        val after = savedMatch(f.snapshot())
        assertTrue(after.matchPaused)
        assertTrue(after.matchPausedReason!!.contains("Checkpoint persistence failed"))
        assertEquals("snapshot A", after.privateNotes[f.civId])
        assertEquals(7, after.stateRevision)
    }

    @Test fun `controller changes are paired with the same game revision in new snapshots`() {
        val f = Fixture()
        val before = f.snapshot()
        f.coordinator.execute(SetControllerCommand(f.civId, "takeover-a", 7, f.civId, ControllerKind.HUMAN))
        val after = f.snapshot()
        val match = savedMatch(after)
        assertEquals(ControllerKind.EXTERNAL_AGENT, savedMatch(before).controllers[f.civId]!!.kind)
        assertEquals(ControllerKind.HUMAN, match.controllers[f.civId]!!.kind)
        assertNull(match.controllers[f.civId]!!.agentProfile)
        assertEquals(8, match.stateRevision)
        assertEquals("takeover-a", match.currentTurnReceipts.single().actionId)
        assertEquals(ControllerKind.HUMAN, AgentOrdinarySave.read(UncivFiles.gameInfoFromString(UncivFiles.gameInfoToString(after, false)))!!.controllers[f.civId]!!.kind)
    }

    @Test fun `old same-id screen publication cannot overwrite the game currently being loaded`() {
        val f = Fixture()
        val old = f.snapshot()
        val selectedSave = f.snapshot()
        val queued = f.snapshot()
        val screen = Mockito.mock(WorldScreen::class.java, Answers.CALLS_REAL_METHODS)
        fun setField(type: Class<*>, name: String, value: Any?) {
            type.getDeclaredField(name).apply { isAccessible = true }.set(screen, value)
        }
        setField(BaseScreen::class.java, "game", UncivGame.Current)
        setField(WorldScreen::class.java, "gameInfo", old)
        setField(WorldScreen::class.java, "viewingCiv", old.getCivilization(f.civId))
        setField(WorldScreen::class.java, "selectedCiv", old.getCivilization(f.civId))
        setField(WorldScreen::class.java, "mapHolder", Mockito.mock(WorldMapHolder::class.java))
        setField(WorldScreen::class.java, "bottomUnitTable", Mockito.mock(UnitTable::class.java))
        screen.agentSession = Mockito.mock(AgentMatchSession::class.java)
        UncivGame::class.java.getDeclaredField("worldScreen").apply { isAccessible = true }.set(UncivGame.Current, screen)
        UncivGame.Current.gameInfo = selectedSave
        screen.installAgentSnapshot(queued, ResolvedSelection(null, null, null))
        assertSame(selectedSave, UncivGame.Current.gameInfo)
        assertSame(old, screen.gameInfo)
    }

    @Test fun `failed durable guidance delivery leaves attention state without invoking the runner`() {
        val f = Fixture()
        val directory = Files.createTempDirectory("unciv-guidance-failure").toFile()
        val calls = AtomicInteger()
        try {
            val snapshot = f.snapshot()
            val host = DesktopAgentMatch.restore(snapshot, savedMatch(snapshot), directory,
                supervisorFactory = {
                    object : SupervisorRunner {
                        override fun isAlive() = true
                        override fun shutdown() {}
                        override fun kill() { error("No cancellation") }
                        override fun runTurn(control: Map<String, Any?>): SupervisorProcess.TurnOutcome {
                            calls.incrementAndGet()
                            return SupervisorProcess.TurnOutcome("no_commit", null, null, false, emptyList(), "Unexpected invocation")
                        }
                    }
                })
            host.queueGuidance(f.civId, "Persist this message")
            host.recoveryDirectory.deleteRecursively()
            host.recoveryDirectory.writeText("A file blocks the next checkpoint directory")
            host.resume()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (!host.coordinator.isPaused() && System.nanoTime() < deadline) Thread.sleep(5)
            assertTrue(host.coordinator.isPaused())
            // Let the run loop's failure handling settle after the checkpoint rejects.
            while (host.getRunState() == AgentRunState.AGENT_DECIDING && System.nanoTime() < deadline) Thread.sleep(5)
            assertEquals(AgentRunState.NEEDS_ATTENTION, host.getRunState())
            assertTrue(host.getFailureReason()!!.contains("Checkpoint"))
            assertEquals(0, calls.get())
            val state = savedMatch(AgentSnapshotPublication.deserializeGame(AgentSnapshotPublication.buildPayload(host.coordinator, f.civId)))
            assertTrue(state.matchPaused)
            assertEquals(GuidanceState.QUEUED, state.guidanceQueue.single().state)
        } finally { directory.deleteRecursively() }
    }

    @Test fun `newer same-revision metadata frame cannot be rolled back by an older queued frame`() {
        val f = Fixture()
        val initial = f.snapshot()
        val olderPayload = AgentSnapshotPublication.buildPayload(f.coordinator, f.civId)
        f.coordinator.execute(UpdateNotesCommand(f.civId, "newer frame B", 2))
        val newerPayload = AgentSnapshotPublication.buildPayload(f.coordinator, f.civId)
        assertEquals(olderPayload.revision, newerPayload.revision)
        assertTrue(newerPayload.generation > olderPayload.generation)
        val screen = Mockito.mock(WorldScreen::class.java, Answers.CALLS_REAL_METHODS)
        fun setField(type: Class<*>, name: String, value: Any?) {
            type.getDeclaredField(name).apply { isAccessible = true }.set(screen, value)
        }
        val map = Mockito.mock(WorldMapHolder::class.java)
        setField(BaseScreen::class.java, "game", UncivGame.Current)
        setField(WorldScreen::class.java, "gameInfo", initial)
        setField(WorldScreen::class.java, "viewingCiv", initial.getCivilization(f.civId))
        setField(WorldScreen::class.java, "selectedCiv", initial.getCivilization(f.civId))
        setField(WorldScreen::class.java, "mapHolder", map)
        setField(WorldScreen::class.java, "bottomUnitTable", Mockito.mock(UnitTable::class.java))
        setField(WorldScreen::class.java, "agentPublicationFence", AgentPublicationFence())
        screen.agentSession = Mockito.mock(AgentMatchSession::class.java)
        UncivGame::class.java.getDeclaredField("worldScreen").apply { isAccessible = true }.set(UncivGame.Current, screen)
        UncivGame.Current.gameInfo = initial
        val newer = AgentSnapshotPublication.deserializeGame(newerPayload)
        val previousGraphics = Gdx.graphics
        Gdx.graphics = Mockito.mock(com.badlogic.gdx.Graphics::class.java)
        try {
            screen.installAgentSnapshot(newer, ResolvedSelection(null, null, null), newerPayload.generation)
            screen.installAgentSnapshot(AgentSnapshotPublication.deserializeGame(olderPayload),
                ResolvedSelection(null, null, null), olderPayload.generation)
            assertSame(newer, screen.gameInfo)
            assertSame(newer, UncivGame.Current.gameInfo)
            assertEquals("newer frame B", savedMatch(UncivGame.Current.gameInfo!!).privateNotes[f.civId])
            val rebinds = Mockito.mockingDetails(map).invocations.filter { it.method.name.startsWith("rebindToSnapshot") }
            assertEquals(1, rebinds.size)
            assertSame(newer.tileMap, rebinds.single().arguments.single())
        } finally { Gdx.graphics = previousGraphics }
    }

    @Test fun `GUI restored-world attachment leaves no resumable uncontrolled world on failure`() {
        val f = Fixture()
        val snapshot = f.snapshot()
        val world = Mockito.mock(WorldScreen::class.java)
        Mockito.`when`(world.gameInfo).thenReturn(snapshot)
        val method = UncivGame::class.java.getDeclaredMethod("attachRestoredAgentWorld", WorldScreen::class.java, AgentMatchState::class.java)
            .apply { isAccessible = true }
        val oldRestorer = AgentMatchSessionFactory.restorer
        try {
            AgentMatchSessionFactory.restorer = { _, _, _, _ -> error("Scripted restore failure") }
            val failure = assertThrows(java.lang.reflect.InvocationTargetException::class.java) {
                method.invoke(UncivGame.Current, world, savedMatch(snapshot))
            }
            assertEquals("Scripted restore failure", failure.cause!!.message)
            assertNull(UncivGame.Current.worldScreen)
            assertFalse(com.unciv.GUI.isWorldLoaded())
            Mockito.verify(world).dispose()
            val session = Mockito.mock(AgentMatchSession::class.java)
            Mockito.`when`(session.getCommittedMatchStateJson()).thenReturn(snapshot.agentMatchStateJson)
            AgentMatchSessionFactory.restorer = { _, _, _, _ -> session }
            method.invoke(UncivGame.Current, world, savedMatch(snapshot))
            assertSame(world, UncivGame.Current.worldScreen)
            Mockito.verify(world).agentSession = session
            Mockito.verify(session).getCommittedMatchStateJson()
            Mockito.verifyNoMoreInteractions(session)
        } finally { AgentMatchSessionFactory.restorer = oldRestorer }
    }
}
