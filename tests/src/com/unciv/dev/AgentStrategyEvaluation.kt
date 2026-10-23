package com.unciv.dev

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.headless.HeadlessFiles
import com.unciv.app.desktop.agents.FileMessageImageStore
import com.unciv.app.desktop.agents.SupervisorProcess
import com.unciv.json.json
import com.unciv.logic.agents.*
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.TestGame
import java.io.File
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.zip.GZIPInputStream

/**
 * Opt-in headless evaluation host, using only APIs present before the strategy changes.
 * The SAME source can run against frozen baseline or candidate engine classes/runner dist.
 * See agent-runner/probes/strategy-evaluation.mjs for configuration and safe launch instructions.
 * This is not a unit test and never starts from ordinary check/test tasks.
 *
 * Copied bundles retain internal match/timeline IDs and receipts. Evaluation identity, every
 * external directory, and every provider session are independent; no source state is written.
 * Budgets govern admission only. An admitted invocation may run indefinitely and is never killed.
 */
object AgentStrategyEvaluation {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1 && File(args[0]).isAbsolute) { "Expected absolute config JSON path" }
        val config = map(AgentJson.parse(File(args[0]).readText()))
        val output = absolute(config, "outputDirectory").canonicalFile
        require(!File(output, "host-events.jsonl").exists()) { "Evaluation output already contains evidence" }
        require(output.isDirectory || output.mkdirs()) { "Cannot create evaluation output" }
        val mode = string(config, "mode")
        require(mode in setOf("prepare", "run", "scripted-smoke")) { "Explicit evaluation mode required" }
        val configuredSessions = map(config["sessionPolicy"])
        val persistentSessions = configuredSessions["mode"] == "forked-persistent"
        require(configuredSessions.isEmpty() || persistentSessions) { "Unknown evaluation session policy" }
        val forkSources = map(configuredSessions["forkFromSessionIds"]).mapValues { (_, value) ->
            (value as? String)?.takeIf { it.isNotBlank() } ?: error("A source session ID is required for every evaluated civilization")
        }
        require(!persistentSessions || forkSources.isNotEmpty() && mode != "scripted-smoke") { "Forked sessions require Codex source histories" }
        val sourceSessionIds = forkSources.values.toSet()
        val node = absolute(config, "nodeExecutable")
        val daemon = absolute(config, "daemonJs")
        val supervisorEntrypoint = if (persistentSessions) absolute(config, "evaluationDriverJs") else daemon
        require(node.canExecute() && daemon.isFile && supervisorEntrypoint.isFile) { "Existing Node executable, built daemon and selected driver are required" }
        val maxOwnTurns = positive(config, "maxOwnTurns")
        val maxRounds = positive(config, "maxGameRounds")
        val maxInvocations = positive(config, "maxAdmittedInvocations")
        val admissionMinutes = (config["maxAdmissionMinutes"] as? Number)?.toDouble()
        require(admissionMinutes == null || admissionMinutes.isFinite() && admissionMinutes > 0)
        val fixtureId = string(config, "fixtureId")
        val seed = (config["seed"] as? Number)?.toLong() ?: error("seed is required")
        val runIdentity = "evaluation-${UUID.randomUUID()}"
        val startNs = System.nanoTime()
        val eventFile = File(output, "host-events.jsonl")
        val lock = Any()
        fun emit(event: String, fields: Map<String, Any?> = emptyMap()) {
            val record = linkedMapOf<String, Any?>("event" to event, "at" to Instant.now().toString(),
                "elapsedMs" to (System.nanoTime() - startNs) / 1_000_000.0, "evaluationId" to runIdentity)
            record.putAll(fields)
            Files.writeString(eventFile.toPath(), AgentJson.stringify(record) + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        }

        Gdx.files = HeadlessFiles()
        TestGame(baseRuleset = BaseRuleset.Civ_V_Vanilla) // silent Unciv settings/rulesets; never desktop userdata
        val inputFile = config["checkpointBundle"]?.let { absolute(config, "checkpointBundle").canonicalFile }
        require(!persistentSessions || inputFile != null) { "Forked evaluation must start from a copied checkpoint" }
        if (inputFile != null) {
            require(inputFile.isFile) { "Checkpoint missing: $inputFile" }
            // In particular reject output beneath the source userdata/checkpoint tree, even via symlinks.
            val sourceRoot = generateSequence(inputFile.parentFile) { it.parentFile }
                .firstOrNull { it.name == ".agent-data" }?.parentFile ?: inputFile.parentFile
            require(!output.toPath().startsWith(sourceRoot.canonicalFile.toPath())) { "Output must be outside source state" }
        }
        val originalInputHash = inputFile?.let(::sha256)
        if (inputFile != null) require(originalInputHash == string(config, "expectedCheckpointSha256")) { "Checkpoint SHA-256 mismatch" }
        val initialProfile = profile(map(config["profile"]), null)
        val bundle = if (inputFile != null) {
            val copy = inputFile.copyTo(File(output, "source-checkpoint.bin"), overwrite = false)
            check(sha256(copy) == originalInputHash) { "Checkpoint changed while being copied" }
            decodeBundle(copy)
        } else AgentStrategyFixtures.bundle(fixtureId, seed, initialProfile,
            MatchVision.valueOf(config["vision"] as? String ?: "FULL"))
        val initial = File(output, "initial.unciv-agent")
        immutable(initial, bundle)
        val inputHash = originalInputHash ?: sha256(initial)
        val writer = FileCheckpointStore(File(output, "checkpoints"), runIdentity, maxRetainedGenerations = 3)
        val coordinator = MatchCoordinator.loadFromJson(bundle, writer)
        val bridge = AgentEngineBridge(coordinator)
        val runtimeData = File(output, "runtime-data").apply { mkdirs() }
        val imageStore = FileMessageImageStore(runtimeData)
        config["messageImagesDirectory"]?.let {
            val source = absolute(config, "messageImagesDirectory")
            val target = File(runtimeData, ".agent-data/message-images").apply { mkdirs() }
            for (file in source.listFiles().orEmpty().filter { it.isFile && it.name.matches(Regex("[a-f0-9]{64}\\.png")) })
                file.copyTo(File(target, file.name), overwrite = false)
        }
        val runnerHashes = daemon.parentFile.walkTopDown().filter { it.isFile && it.extension in setOf("js", "json") }
            .sortedBy { it.relativeTo(daemon.parentFile).path }.associate { it.relativeTo(daemon.parentFile).path to sha256(it) }
        val classpath = System.getProperty("java.class.path").split(File.pathSeparator)
        val classpathHashes = classpath.associateWith { path ->
            val file = File(path)
            if (file.isFile) sha256(file) else if (file.isDirectory) treeHash(file) else "missing"
        }
        val manifest = linkedMapOf<String, Any?>(
            "evaluationId" to runIdentity, "arm" to string(config, "arm"), "mode" to mode,
            "fixtureId" to fixtureId, "seed" to seed, "inputSha256" to inputHash,
            "initialBundleSha256" to sha256(initial), "sourceCheckpoint" to inputFile?.absolutePath,
            "matchId" to coordinator.getMatchId(), "timelineId" to coordinator.getTimelineId(),
            "identityPolicy" to if (persistentSessions) "Internal IDs and receipts preserved; independent external directories; native history forks followed by new-session resumes"
                else "Internal IDs and receipts preserved; independent external directories and fresh sessions",
            "jvmPid" to ProcessHandle.current().pid(), "command" to ProcessHandle.current().info().commandLine().orElse("unknown"),
            "cwd" to File(".").canonicalPath, "jvmArguments" to ManagementFactory.getRuntimeMXBean().inputArguments,
            "javaVersion" to System.getProperty("java.version"), "config" to config, "configPath" to args[0],
            "runnerHashes" to runnerHashes, "runtimeClasspathHashes" to classpathHashes,
            "evaluationDriver" to if (persistentSessions) mapOf("path" to supervisorEntrypoint.absolutePath, "sha256" to sha256(supervisorEntrypoint)) else null,
            "sessionPolicy" to if (persistentSessions) mapOf("mode" to "forked-persistent", "forkFromSessionIds" to forkSources,
                "compactAfterTurn" to true, "arm" to "persistent", "completion" to "natural provider and compaction exit before next admission")
                else mapOf("resumeId" to null, "compactAfterTurn" to false, "arm" to "rotate-1", "age" to 0),
            "providerMetadata" to File(output, "providers").absolutePath,
            "limitations" to listOf("Map seed fixes the starting fixture, not all model/engine randomness. Share the serialized input between arms.",
                if (persistentSessions) "Compaction settings match production; serial natural completion removes compaction overlap. Invocation time includes compaction; timeToCommit is separate. First fork token usage may inherit history and is unknown."
                else "Fresh sessions and disabled compaction intentionally differ from installed default sessions.",
                "A budget-limited run without engine victory data is inconclusive for win rate.")
        )
        immutable(File(output, "manifest.json"), AgentJson.stringify(manifest))
        emit("evaluation_started", mapOf("manifest" to File(output, "manifest.json").absolutePath))

        fun matchState(): AgentMatchState = json().fromJson(AgentMatchState::class.java, coordinator.serializeCommittedMatchForPublication())
        var snapshotIndex = 0
        fun snapshot(label: String): Map<String, Any?> {
            val saved = coordinator.saveToJson()
            val file = File(output, "snapshots/%04d-%s.unciv-agent".format(snapshotIndex, label))
            immutable(file, saved)
            val game = coordinator.deserializeForPublication(coordinator.serializeCommittedGameForPublication())
            val state = matchState()
            val capitals = game.getCities().filter { it.isOriginalCapital }.map { city ->
                mapOf("cityId" to city.id, "foundingCivId" to city.foundingCivObject?.civID,
                    "ownerCivId" to city.civ.civID, "major" to (city.foundingCivObject?.isMajorCiv() == true))
            }.toList()
            val facts = linkedMapOf<String, Any?>(
                "gameRound" to game.turns, "turnSerial" to state.turnSerial, "revision" to state.stateRevision,
                "endTurnsCommitted" to state.endTurnsCommitted, "turnPhase" to state.turnPhase.name,
                "enabledVictories" to game.gameParameters.victoryTypes, "vision" to state.vision.name,
                "victoryData" to game.victoryData?.let { mapOf("winningCiv" to it.winningCiv,
                    "victoryType" to it.victoryType, "victoryTurn" to it.victoryTurn) },
                "capitals" to capitals, "notes" to state.privateNotes,
                "civilizations" to game.civilizations.filter { it.isMajorCiv() }.map { civ -> mapOf(
                    "civId" to civ.civID, "researchedTechs" to civ.tech.techsResearched.toList().sorted(),
                    "researchQueue" to civ.tech.techsToResearch,
                    "cities" to civ.cities.map { city -> mapOf("cityId" to city.id, "health" to city.health,
                        "population" to city.population.population, "productionQueue" to city.cityConstructions.constructionQueue,
                        "productionProgress" to city.cityConstructions.inProgressConstructions) },
                    "units" to civ.units.getCivUnits().map { unit -> mapOf("unitId" to unit.id, "name" to unit.name,
                        "health" to unit.health, "x" to unit.getTile().position.x, "y" to unit.getTile().position.y,
                        "action" to unit.action) }.toList()) },
                "messages" to state.diplomaticMessages.map { mapOf("messageId" to it.messageId,
                    "targetCivId" to it.targetCivId, "deliveredTurnSerial" to it.deliveredTurnSerial,
                    "acknowledgedTurnSerial" to it.acknowledgedTurnSerial) }
            )
            val event = mapOf("index" to snapshotIndex++, "label" to label, "bundlePath" to file.absolutePath,
                "bundleSha256" to sha256(file), "facts" to facts)
            immutable(File(file.parentFile, file.nameWithoutExtension + ".json"), AgentJson.stringify(event))
            emit("snapshot", event)
            return facts
        }
        snapshot("initial")
        if (mode == "prepare") {
            emit("evaluation_finished", mapOf("reason" to "fixture-prepared", "initialBundle" to initial.absolutePath,
                "initialBundleSha256" to sha256(initial)))
            return
        }
        if (coordinator.getTurnPhase() == TurnPhase.READY_TO_START && !coordinator.isPaused()) coordinator.beginAwaitingTurn()
        val startRound = coordinator.getGameTurns()
        var admittedActor: String? = null
        var admittedRunId: String? = null
        var admittedAtNs = 0L
        var committedAtMs: Double? = null
        var callIndex = 0
        var supervisor: SupervisorProcess? = null
        var stopReason = "admission-budget"
        val ownTurns = HashMap<String, Int>()
        val continuity = HashMap<String, String>()
        val sessions = HashMap<String, String>()
        try {
            var invocations = 0
            while (true) {
                if (File(output, "stop-admission").exists()) { stopReason = "stop-admission-file"; break }
                if (coordinator.isPaused()) { stopReason = "match-paused: ${coordinator.getPausedReason()}"; break }
                if (coordinator.getTurnPhase() == TurnPhase.COMPLETED) { stopReason = "engine-completed"; break }
                if (invocations >= maxInvocations || coordinator.getGameTurns() - startRound >= maxRounds ||
                    admissionMinutes != null && (System.nanoTime() - startNs) / 60_000_000_000.0 >= admissionMinutes) break
                val actor = coordinator.getCurrentCivId()
                if ((ownTurns[actor] ?: 0) >= maxOwnTurns) break
                val controller = coordinator.getController(actor)
                if (controller?.kind != ControllerKind.EXTERNAL_AGENT) { stopReason = "non-agent-controller: $actor"; break }
                val override = map(map(config["profiles"])[actor]).ifEmpty { map(config["profile"]) }
                val selected = profile(override, controller.agentProfile)
                val provider = if (mode == "scripted-smoke") "scripted" else selected.provider
                require(provider in setOf("scripted", "codex", "claude", "opencode")) { "Unsupported provider $provider" }
                require(provider == "scripted" || selected.modelId.isNotBlank()) { "An exact model is required for $actor" }
                require(!persistentSessions || provider == "codex") { "Forked persistent evaluation requires Codex for $actor" }
                val resumeId = sessions[actor]
                val forkFromSessionId = if (persistentSessions && resumeId == null)
                    forkSources[actor] ?: error("Missing source session for $actor") else null
                val runId = "$runIdentity-${++invocations}"
                val before = matchState().endTurnsCommitted
                synchronized(lock) {
                    admittedActor = actor; admittedRunId = runId; admittedAtNs = System.nanoTime(); committedAtMs = null
                }
                if (supervisor == null) supervisor = SupervisorProcess(node, supervisorEntrypoint, activityLogsDir = File(output, "providers"),
                    answerEngineRequest = { method, params -> synchronized(lock) {
                        val fixedActor = admittedActor ?: throw EngineCallException("AUTHORITY_REVOKED", "No invocation admitted")
                        val callId = (++callIndex).toString()
                        val common = mapOf("callId" to callId, "runId" to admittedRunId, "actor" to fixedActor,
                            "timelineId" to coordinator.getTimelineId(), "gameRound" to coordinator.getGameTurns(),
                            "turnSerial" to coordinator.getTurnSerial(), "method" to method, "params" to params)
                        emit("engine_call_started", common)
                        try {
                            val actorKey = when (method) { "act" -> "actorCivId"; "rules", "action_result" -> null; else -> "civId" }
                            if (actorKey != null && params[actorKey] !is String)
                                throw EngineCallException("INVALID_ARGUMENT", "Missing string argument $actorKey")
                            if (listOf("civId", "actorCivId").any { it in params && params[it] != fixedActor })
                                throw EngineCallException("AUTHORITY_REVOKED", "Actor differs from admitted civilization")
                            val orderArgs = map(params["args"])
                            val relevant = if (method == "act" && params["type"] == "unit.order" &&
                                orderArgs["order"] in setOf("SLEEP", "FORTIFY")) bridge.inspect(fixedActor, integer(orderArgs, "unitId")) else null
                            val result = when (method) {
                                "observe" -> bridge.observe(fixedActor)
                                "inspect" -> bridge.inspect(fixedActor, integer(params, "unitId"))
                                "inspect_city" -> bridge.inspectCity(fixedActor, string(params, "cityId"))
                                "inspect_tile" -> bridge.inspectTile(fixedActor, params)
                                "map" -> bridge.map(fixedActor, params)
                                "rules" -> bridge.rules(params)
                                "legal_actions" -> bridge.legalActions(fixedActor,
                                    params["unitId"]?.let { integer(params, "unitId") }, params["cityId"]?.let { string(params, "cityId") })
                                "act" -> bridge.act(params)
                                "action_result" -> bridge.actionResult(string(params, "actionId"), fixedActor)
                                "notes_get" -> bridge.readNotes(fixedActor)
                                "notes_put" -> bridge.updateNotes(fixedActor, string(params, "notes"), integer(params, "expectedNotesRevision"))
                                "end_turn" -> bridge.endTurn(fixedActor, string(params, "actionId"), coordinator.getStateRevision())
                                "message_image" -> {
                                    val metadata = try { coordinator.getDeliveredMessageImage(fixedActor, string(params, "messageId"), string(params, "attachmentId")) }
                                    catch (ex: AgentCommandException) { throw EngineCallException(ex.code.name, ex.message ?: "Image unavailable") }
                                    val image = try { imageStore.read(metadata) } catch (_: Exception) {
                                        throw EngineCallException("ILLEGAL_ACTION", "Image absent or invalid in evaluation's isolated image store")
                                    }
                                    mapOf("attachmentId" to metadata.attachmentId, "mimeType" to metadata.mimeType,
                                        "width" to metadata.width, "height" to metadata.height,
                                        "data" to java.util.Base64.getEncoder().encodeToString(image.bytes))
                                }
                                else -> throw EngineCallException("INVALID_ARGUMENT", "Unknown method $method")
                            }
                            if (method == "end_turn" && committedAtMs == null)
                                committedAtMs = (System.nanoTime() - admittedAtNs) / 1_000_000.0
                            // Image bytes are already immutable files; retain metadata rather than duplicate base64 in logs.
                            val logged = if (method == "message_image") map(result).minus("data") else result
                            emit("engine_call_completed", common + mapOf("ok" to true, "result" to logged, "relevantState" to relevant))
                            result
                        } catch (ex: Exception) {
                            emit("engine_call_completed", common + mapOf("ok" to false,
                                "error" to mapOf("code" to ((ex as? EngineCallException)?.code ?: "INTERNAL_ERROR"), "message" to ex.message)))
                            throw ex
                        }
                    } })
                val control = mapOf("protocol" to 1, "kind" to "control", "op" to "run_turn", "runId" to runId,
                    "provider" to provider,
                    "providerConfig" to buildMap<String, Any?> {
                        put("model", selected.modelId); put("reasoning", selected.reasoning)
                        put("compactAfterTurn", persistentSessions)
                        if (resumeId != null) put("resumeId", resumeId)
                        if (forkFromSessionId != null) put("forkFromSessionId", forkFromSessionId)
                        put("extra", if (provider == "scripted") mapOf("plan" to "observe,end") else emptyMap<String, Any>())
                    },
                    "binding" to mapOf("matchId" to coordinator.getMatchId(), "timelineId" to coordinator.getTimelineId(),
                        "civId" to actor, "runId" to runId, "authorityEpoch" to 1),
                    "profile" to mapOf("displayName" to selected.displayName, "personality" to selected.personality, "modelLabel" to selected.modelId),
                    "context" to mapOf("rulesetName" to coordinator.getRulesetName(), "protocolVersion" to 1,
                        "civId" to actor, "turnSerial" to coordinator.getTurnSerial(), "gameRound" to coordinator.getGameTurns(),
                        "continuityNote" to continuity[actor],
                        "session" to mapOf("arm" to if (persistentSessions) "persistent" else "rotate-1",
                            "age" to if (persistentSessions) ownTurns[actor] ?: 0 else 0)),
                    "guidance" to coordinator.takeGuidanceForDelivery(actor))
                emit("turn_started", mapOf("runId" to runId, "actor" to actor, "gameRound" to coordinator.getGameTurns(),
                    "turnSerial" to coordinator.getTurnSerial(), "control" to control,
                    "jvmChildren" to ProcessHandle.current().children().map { it.pid() }.toList()))
                val outcome = supervisor.runTurn(control) // No timeout, control input, cancellation or forced cleanup.
                synchronized(lock) {
                    val delta = matchState().endTurnsCommitted - before
                    emit("turn_completed", mapOf("runId" to runId, "actor" to actor, "status" to outcome.status,
                        "sessionId" to outcome.sessionId, "detail" to outcome.detail, "summary" to outcome.summary,
                        "endTurnsCommittedDelta" to delta, "timeToCommitMs" to committedAtMs,
                        "durationMs" to (System.nanoTime() - admittedAtNs) / 1_000_000.0))
                    snapshot("turn-$invocations")
                    admittedActor = null; admittedRunId = null
                    if (outcome.status != "completed_turn" || delta != 1) {
                        stopReason = "provider-${outcome.status}-commits-$delta"
                    } else {
                        if (persistentSessions) {
                            val sessionId = outcome.sessionId ?: error("Forked provider returned no new session ID")
                            check(sessionId !in sourceSessionIds && (resumeId == null || sessionId == resumeId) &&
                                sessions.none { (otherActor, id) -> otherActor != actor && id == sessionId }) {
                                "Provider returned an original, changed or another civilization's session ID"
                            }
                            sessions[actor] = sessionId
                        }
                        ownTurns[actor] = (ownTurns[actor] ?: 0) + 1
                        outcome.summary?.takeIf { it.isNotBlank() }?.let { continuity[actor] = it }
                    }
                }
                if (stopReason.startsWith("provider-")) break
            }
        } finally {
            // The supervisor's normal shutdown drains and waits; it does not kill an active provider.
            supervisor?.shutdown()
        }
        synchronized(lock) {
            snapshot("final")
            if (inputFile != null) check(sha256(inputFile) == originalInputHash) { "Source input changed externally during evaluation" }
            emit("evaluation_finished", mapOf("reason" to stopReason, "ownTurns" to ownTurns,
                "gameRoundsElapsed" to coordinator.getGameTurns() - startRound, "supervisorExited" to (supervisor?.isAlive() != true),
                "retentionWarning" to writer.lastRetentionFailure, "sourceInputUnchanged" to (inputFile == null || sha256(inputFile) == originalInputHash)))
        }
    }

    private fun map(value: Any?): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return value as? Map<String, Any?> ?: emptyMap()
    }
    private fun string(values: Map<String, Any?>, key: String): String = values[key] as? String
        ?: throw EngineCallException("INVALID_ARGUMENT", "Missing string $key")
    private fun integer(values: Map<String, Any?>, key: String): Int {
        val value = values[key] as? Number ?: throw EngineCallException("INVALID_ARGUMENT", "Missing integer $key")
        if (!value.toDouble().isFinite() || value.toDouble() != value.toInt().toDouble())
            throw EngineCallException("INVALID_ARGUMENT", "Invalid integer $key")
        return value.toInt()
    }
    private fun positive(values: Map<String, Any?>, key: String) = integer(values, key).also { require(it > 0) { "$key must be positive" } }
    private fun absolute(values: Map<String, Any?>, key: String) = File(string(values, key)).also { require(it.isAbsolute) { "$key must be absolute" } }
    private fun profile(values: Map<String, Any?>, saved: AgentProfile?): AgentProfile = (saved?.copy()
        ?: AgentProfile("evaluation", "Evaluation Rome", "codex", "", null, "", 1)).apply {
        (values["provider"] as? String)?.let { provider = it }
        (values["model"] as? String)?.let { modelId = it }
        if (values.containsKey("reasoning")) reasoning = values["reasoning"] as? String
        (values["personality"] as? String)?.let { personality = it }
        (values["displayName"] as? String)?.let { displayName = it }
    }
    private fun immutable(file: File, content: String) {
        file.parentFile.mkdirs()
        Files.writeString(file.toPath(), content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
    }
    private fun decodeBundle(file: File): String = file.inputStream().buffered().use { input ->
        input.mark(2)
        val gzip = input.read() == 0x1f && input.read() == 0x8b
        input.reset()
        (if (gzip) GZIPInputStream(input) else input).reader(Charsets.UTF_8).readText()
    }
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun treeHash(directory: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (file in directory.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(directory).path }) {
            digest.update(file.relativeTo(directory).path.toByteArray(Charsets.UTF_8))
            digest.update(sha256(file).toByteArray(Charsets.UTF_8))
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
