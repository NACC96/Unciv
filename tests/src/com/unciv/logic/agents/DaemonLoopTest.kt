package com.unciv.logic.agents

import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.PrintWriter
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Desktop-side loop proof (AI_AGENT_PLAN.md §7.3, §3.1): this test plays the
 * desktop half of the stdio protocol — spawns the real supervisor daemon,
 * answers engine RPCs from a REAL MatchCoordinator through AgentEngineBridge,
 * and drives a scripted provider turn to completion. No models involved.
 *
 * Skipped when Node or the built runner is absent (documented prerequisites).
 */
@RunWith(BaseTestRunner::class)
class DaemonLoopTest {

    private class Fixture {
        val testGame = TestGame(baseRuleset = BaseRuleset.Civ_V_Vanilla)
        lateinit var coordinator: MatchCoordinator
        lateinit var bridge: AgentEngineBridge
        lateinit var romeId: String
        var warriorId: Int = -1
        val engineCalls = java.util.concurrent.ConcurrentHashMap<String, Int>()

        init {
            testGame.gameInfo.gameParameters.baseRuleset = BaseRuleset.Civ_V_Vanilla.fullName
            testGame.makeHexagonalMap(4)
            val rome = testGame.addCiv(testGame.ruleset.nations["Rome"]!!, isPlayer = true)
            romeId = rome.civID
            warriorId = testGame.addUnit("Warrior", rome, testGame.getTile(0, 0)).id
            val match = AgentMatchState(
                matchId = "m1", timelineId = "t1", turnPhase = TurnPhase.AWAITING_DECISIONS)
            match.controllers[romeId] = CivilizationController(
                romeId, ControllerKind.EXTERNAL_AGENT,
                AgentProfile("p1", "Ada", "codex", "m", null, "", 1))
            coordinator = MatchCoordinator(testGame.gameInfo, match, InMemoryCheckpointWriter())
            bridge = AgentEngineBridge(coordinator)
        }
    }

    // Protocol framing shares core AgentJson (single implementation for
    // tests and the desktop host).
    private fun emitJson(value: Any?): String = AgentJson.stringify(value)

    private fun parseJsonFrame(text: String): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return AgentJson.parse(text) as Map<String, Any?>
    }

    @Test
    fun `scripted provider turn completes through the daemon against the real coordinator`() {
        val node = File(System.getenv("UNCIV_TEST_NODE")
            ?: "/Users/noahkenny/.nvm/versions/node/v24.13.1/bin/node")
        Assume.assumeTrue("node required", node.canExecute())
        // Tests run with CWD android/assets; the runner builds to agent-runner/dist.
        val daemonJs = File("../../agent-runner/dist/daemon.js").canonicalFile
        Assume.assumeTrue("built runner required (npm run build)", daemonJs.isFile)

        val f = Fixture()
        val process = ProcessBuilder(node.absolutePath, daemonJs.absolutePath)
            .redirectErrorStream(false)
            .start()
        try {
            val writer = PrintWriter(process.outputStream, true)
            val events = LinkedBlockingQueue<Map<String, Any?>>()
            val readerThread = Thread({
                val lines = BufferedReader(InputStreamReader(process.inputStream))
                while (true) {
                    val line = try {
                        lines.readLine()
                    } catch (ex: Exception) {
                        return@Thread
                    } ?: return@Thread
                    if (line.isBlank()) continue
                    @Suppress("UNCHECKED_CAST")
                    val frame = try {
                        parseJsonFrame(line)
                    } catch (ex: Exception) {
                        continue
                    }
                    when (frame["kind"]) {
                        "ready" -> events.add(frame)
                        "turn_event" -> events.add(frame)
                        "engine_request" -> answerEngineRequest(f, writer, frame)
                    }
                }
            }, "daemon-rpc")
            readerThread.isDaemon = true
            readerThread.start()

            val ready = pollFor(events, 15) { it["kind"] == "ready" }
            Assert.assertNotNull("daemon never became ready", ready)

            writer.println(emitJson(mapOf(
                "protocol" to 1,
                "kind" to "control",
                "op" to "run_turn",
                "runId" to "run-1",
                "provider" to "scripted",
                "providerConfig" to mapOf("extra" to mapOf(
                    "unitId" to f.warriorId, "destX" to 1, "destY" to 0)),
                "binding" to mapOf("matchId" to "m1", "timelineId" to "t1",
                    "civId" to f.romeId, "runId" to "run-1", "authorityEpoch" to 1),
                "profile" to mapOf("displayName" to "Ada", "personality" to "Bold.",
                    "modelLabel" to "scripted"),
                "context" to mapOf("rulesetName" to BaseRuleset.Civ_V_Vanilla.fullName,
                    "protocolVersion" to 1, "civId" to f.romeId, "turnSerial" to 1),
                "guidance" to emptyList<Any?>()
            )))

            val completed = pollFor(events, 90) {
                it["kind"] == "turn_event" &&
                    (it["event"] == "run_completed" || it["event"] == "run_failed")
            }
            Assert.assertNotNull("no turn outcome in 90s: $events", completed)
            @Suppress("UNCHECKED_CAST")
            val payload = completed!!["payload"] as Map<String, Any?>
            Assert.assertEquals(
                "outcome ${payload["status"]}: ${payload["detail"]} " +
                    "engine(rev=${f.coordinator.getStateRevision()} " +
                    "phase=${f.coordinator.getTurnPhase()} " +
                    "decisions=${f.coordinator.getPendingDecisions(f.romeId).map { it.decisionId }})",
                "completed_turn", payload["status"])
            Assert.assertEquals(true, payload["turnEnded"])
            @Suppress("UNCHECKED_CAST")
            val tools = payload["toolCalls"] as List<String>
            Assert.assertTrue(tools.containsAll(listOf("game.observe", "game.act", "game.end_turn")))
            // The act mutation really applied on the coordinator side: act
            // bumps one revision, and the atomic end-turn commits both its
            // end receipt and the next turn's begin receipt (rev 1 -> 3).
            Assert.assertEquals(
                "engine calls were ${f.engineCalls}",
                3, f.coordinator.getStateRevision())

            writer.println(emitJson(mapOf("protocol" to 1, "kind" to "control", "op" to "shutdown")))
            Assert.assertTrue("daemon did not exit", process.waitFor(15, TimeUnit.SECONDS))
            Assert.assertEquals(0, process.exitValue())
        } finally {
            process.destroyForcibly()
        }
    }

    private fun answerEngineRequest(
        f: Fixture,
        writer: PrintWriter,
        frame: Map<String, Any?>
    ) {
        val id = frame["id"] as String
        val method = frame["method"] as String
        f.engineCalls.merge(method, 1, Int::plus)
        @Suppress("UNCHECKED_CAST")
        val params = (frame["params"] as? Map<String, Any?>) ?: emptyMap()
        try {
            val result: Any? = when (method) {
                "observe" -> f.bridge.observe(params["civId"] as String)
                "inspect" -> f.bridge.inspect(
                    params["civId"] as String, (params["unitId"] as Number).toInt())
                "rules" -> f.bridge.rules()
                "legal_actions" -> f.bridge.legalActions(
                    params["civId"] as String,
                    (params["unitId"] as? Number)?.toInt(),
                    params["cityId"] as? String)
                "act" -> f.bridge.act(params)
                "action_result" -> f.bridge.actionResult(params["actionId"] as String)
                "notes_get" -> f.bridge.readNotes(params["civId"] as String)
                "notes_put" -> f.bridge.updateNotes(
                    params["civId"] as String, params["notes"] as String,
                    (params["expectedNotesRevision"] as Number).toInt())
                "end_turn" -> f.bridge.endTurn(
                    params["civId"] as String,
                    params["actionId"] as String,
                    f.coordinator.getStateRevision())
                else -> throw EngineCallException("INVALID_ARGUMENT", "Unknown method $method")
            }
            writer.println(emitJson(mapOf(
                "protocol" to 1, "kind" to "engine_response",
                "id" to id, "ok" to true, "result" to result)))
        } catch (ex: EngineCallException) {
            writer.println(emitJson(mapOf(
                "protocol" to 1, "kind" to "engine_response",
                "id" to id, "ok" to false,
                "error" to mapOf("code" to ex.code, "message" to (ex.message ?: ex.code),
                    "currentRevision" to ex.currentRevision))))
        } catch (ex: Exception) {
            writer.println(emitJson(mapOf(
                "protocol" to 1, "kind" to "engine_response",
                "id" to id, "ok" to false,
                "error" to mapOf("code" to "INTERNAL_ERROR", "message" to (ex.message ?: "error")))))
        }
    }

    private fun pollFor(
        events: LinkedBlockingQueue<Map<String, Any?>>,
        timeoutSeconds: Long,
        match: (Map<String, Any?>) -> Boolean
    ): Map<String, Any?>? {
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000
        val seen = ArrayList<Map<String, Any?>>()
        while (System.currentTimeMillis() < deadline) {
            val event = events.poll(200, TimeUnit.MILLISECONDS) ?: continue
            if (match(event)) {
                events.addAll(seen)
                return event
            }
            seen.add(event)
        }
        events.addAll(seen)
        return null
    }
}
