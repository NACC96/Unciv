package com.unciv.app.desktop.agents

import com.unciv.logic.agents.AgentJson
import com.unciv.logic.agents.EngineCallException
import com.unciv.utils.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.PrintWriter
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Supervisor child-process manager (AI_AGENT_PLAN.md §7.3, §9).
 * One `node dist/daemon.js` per agent match. Speaks the versioned stdio
 * protocol with [AgentJson] framing (shared with core/tests, so no second
 * JSON dialect): stdout carries engine requests + turn events, stdin carries
 * responses + controls, stderr is logs only and never parsed.
 *
 * Liveness is read-only (process handle, event timestamps); silence alone is
 * never failure. Cancellation is explicit ([kill]); graceful shutdown
 * ([shutdown]) drains first. A [turnTimeoutMinutes]-silent turn is reported,
 * never auto-completed.
 */
class SupervisorProcess(
    nodeExecutable: File,
    daemonJs: File,
    private val turnTimeoutMinutes: Long = 10,
    private val answerEngineRequest: (method: String, params: Map<String, Any?>) -> Any? = { _, _ -> null }
) {
    data class TurnOutcome(
        val status: String,
        val sessionId: String?,
        val revision: Int?,
        val turnEnded: Boolean,
        val toolCalls: List<String>,
        val detail: String
    )

    private val process: Process = ProcessBuilder(
        nodeExecutable.absolutePath, daemonJs.absolutePath)
        .redirectErrorStream(false)
        .start()
    private val writer = PrintWriter(process.outputStream, true)
    private val events = LinkedBlockingQueue<Map<String, Any?>>()
    private val logLines = LinkedBlockingQueue<String>()
    @Volatile private var lastEventAtMillis = System.currentTimeMillis()

    init {
        Thread({
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
                    AgentJson.parse(line) as Map<String, Any?>
                } catch (ex: Exception) {
                    Log.error("Supervisor sent unparseable line", ex)
                    continue
                }
                lastEventAtMillis = System.currentTimeMillis()
                when (frame["kind"]) {
                    "turn_event", "ready" -> events.add(frame)
                    "engine_request" -> answerEngineRequestFrame(frame)
                }
            }
        }, "supervisor-stdout").apply { isDaemon = true; start() }
        Thread({
            val err = BufferedReader(InputStreamReader(process.errorStream))
            while (true) {
                val line = try {
                    err.readLine()
                } catch (ex: Exception) {
                    return@Thread
                } ?: return@Thread
                logLines.add(line)
                if (logLines.size > 500) logLines.poll()
            }
        }, "supervisor-stderr").apply { isDaemon = true; start() }
    }

    fun isAlive(): Boolean = process.isAlive

    fun millisSinceLastEvent(): Long = System.currentTimeMillis() - lastEventAtMillis

    fun recentLogs(): List<String> = logLines.toList()

    private fun answerEngineRequestFrame(frame: Map<String, Any?>) {
        @Suppress("UNCHECKED_CAST")
        val id = frame["id"] as? String ?: return
        val method = frame["method"] as? String ?: return
        @Suppress("UNCHECKED_CAST")
        val params = (frame["params"] as? Map<String, Any?>) ?: emptyMap()
        try {
            val result = answerEngineRequest(method, params)
            sendFrame(mapOf("protocol" to 1, "kind" to "engine_response",
                "id" to id, "ok" to true, "result" to result))
        } catch (ex: EngineCallException) {
            sendFrame(mapOf("protocol" to 1, "kind" to "engine_response",
                "id" to id, "ok" to false,
                "error" to mapOf("code" to ex.code, "message" to (ex.message ?: ex.code))))
        } catch (ex: Exception) {
            sendFrame(mapOf("protocol" to 1, "kind" to "engine_response",
                "id" to id, "ok" to false,
                "error" to mapOf("code" to "INTERNAL_ERROR", "message" to (ex.message ?: "engine error"))))
        }
    }

    @Synchronized
    private fun sendFrame(frame: Map<String, Any?>) {
        writer.println(AgentJson.stringify(frame))
    }

    /**
     * Runs one provider turn: sends the control frame, then consumes turn
     * events until the outcome arrives. Engine RPCs are answered inline on
     * the reader thread (never queued behind the outcome wait).
     */
    fun runTurn(control: Map<String, Any?>): TurnOutcome {
        // Drain stale events from any previous run.
        events.clear()
        sendFrame(control)
        var sessionId: String? = null
        val deadline = System.currentTimeMillis() + turnTimeoutMinutes * 60 * 1000
        while (System.currentTimeMillis() < deadline) {
            val frame = events.poll(200, TimeUnit.MILLISECONDS) ?: continue
            if (frame["kind"] != "turn_event") continue
            @Suppress("UNCHECKED_CAST")
            val payload = (frame["payload"] as? Map<String, Any?>) ?: emptyMap()
            if (frame["event"] == "session_bound" && payload["sessionId"] is String)
                sessionId = payload["sessionId"] as String
            when (frame["event"]) {
                "run_completed", "run_failed" -> return TurnOutcome(
                    status = (payload["status"] as? String)
                        ?: if (frame["event"] == "run_completed") "completed_turn" else "failed",
                    sessionId = sessionId ?: (payload["sessionId"] as? String),
                    revision = (payload["revision"] as? Number)?.toInt(),
                    turnEnded = payload["turnEnded"] as? Boolean ?: false,
                    toolCalls = (payload["toolCalls"] as? List<*>)?.map { it.toString() } ?: emptyList(),
                    detail = (payload["detail"] as? String) ?: (payload["reason"] as? String ?: "")
                )
            }
        }
        return TurnOutcome("failed", sessionId, null, false, emptyList(),
            "no turn outcome within $turnTimeoutMinutes minutes (see supervisor logs)")
    }

    /** Graceful shutdown: EOF semantics via control, then bounded wait. */
    fun shutdown() {
        try {
            sendFrame(mapOf("protocol" to 1, "kind" to "control", "op" to "shutdown"))
        } catch (ex: Exception) {
            Log.error("Supervisor shutdown write failed", ex)
        }
        if (!process.waitFor(15, TimeUnit.SECONDS)) process.destroyForcibly()
    }

    fun kill() {
        process.destroyForcibly()
    }
}
