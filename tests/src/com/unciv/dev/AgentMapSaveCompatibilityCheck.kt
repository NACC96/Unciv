package com.unciv.dev

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.headless.HeadlessFiles
import com.badlogic.gdx.utils.JsonReader
import com.badlogic.gdx.utils.JsonValue
import com.unciv.json.json
import com.unciv.logic.GameInfo
import com.unciv.logic.agents.*
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.files.FileConversions
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.TestGame
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/** Manual diagnostic against copied user data. Run from android/assets; never starts an app or provider.
 * Args: source file or copied userdata/checkpoint directory, absolute report path, optional further sources.
 * Only the report is written. No checkpoint writer is invoked and no source data is repaired or normalized on disk.
 */
object AgentMapSaveCompatibilityCheck {
    private class Source(val file: File, val recovery: FileCheckpointStore.Recovery? = null)

    @JvmStatic fun main(args: Array<String>) {
        require(args.size >= 2) { "Provide a source file/directory and absolute JSON report path" }
        val reportFile = File(args[1]).canonicalFile
        require(File(args[1]).isAbsolute) { "Report path must be absolute" }
        val requested = (listOf(args[0]) + args.drop(2)).map { File(it).canonicalFile }
        require(requested.all { it.exists() }) { "Every input must exist" }
        require(requested.none { it == reportFile || (it.isDirectory && reportFile.toPath().startsWith(it.toPath())) }) {
            "The report must be outside all read-only input paths"
        }
        Gdx.files = HeadlessFiles()
        TestGame(baseRuleset = BaseRuleset.Civ_V_Vanilla)
        val sources = requested.flatMap { discover(it) }.distinctBy { it.file.canonicalPath }
        require(sources.isNotEmpty()) { "No normal save or verified checkpoint found" }
        val results = sources.map { source ->
            try { checkSource(source) }
            catch (ex: Exception) { linkedMapOf<String, Any?>("source" to source.file.absolutePath,
                "passed" to false, "errorType" to ex.javaClass.simpleName, "error" to ex.message) }
        }
        val passed = results.all { it["passed"] == true }
        val report = linkedMapOf<String, Any?>(
            "passed" to passed, "requestedSources" to requested.map { it.absolutePath }, "results" to results,
            "limitations" to listOf("Read-only headless restoration and serialization; no desktop UI or providers exercised",
                "Ordinary GameInfo saves do not contain paired agent controllers, mail, or personality metadata",
                "Difficulty, game parameters, player categories and saved temporary uniques check bonus configuration; no turn is simulated",
                "Only the newest checksum-verified checkpoint is selected across copied match directories; older archives are not rewritten",
                "Legacy hidden exploration checks are applicable only when controlled civilizations have unseen explored tiles"))
        check(reportFile.parentFile.isDirectory || reportFile.parentFile.mkdirs()) { "Cannot create report directory" }
        reportFile.writeText(AgentJson.stringify(report), Charsets.UTF_8)
        println("Save compatibility diagnostic: ${if (passed) "PASS" else "FAIL"}; ${reportFile.absolutePath}")
        check(passed) { "Compatibility checks failed; inspect the JSON report" }
    }

    private fun discover(path: File): List<Source> {
        if (path.isFile) return listOf(Source(path))
        val result = ArrayList<Source>()
        // Copied userdata has a checkpoints subtree; an individual match directory is also accepted.
        val checkpointRoot = File(path, ".agent-data/checkpoints").takeIf { it.isDirectory } ?: path
        val generationPattern = Regex("^([A-Za-z0-9_-]+)-gen-[0-9]+\\.unciv-agent$")
        val directories = (listOf(checkpointRoot) + (checkpointRoot.listFiles()?.filter { it.isDirectory } ?: emptyList()))
        val candidates = directories.flatMap { dir ->
            (dir.listFiles()?.filter { it.isFile && generationPattern.matches(it.name) } ?: emptyList())
                .groupBy { generationPattern.matchEntire(it.name)!!.groupValues[1] }
                .map { (matchId, files) -> Triple(dir, matchId, files.maxOf { it.lastModified() }) }
        }.sortedByDescending { it.third }
        val recoveredSources = ArrayList<Source>()
        for ((dir, matchId, _) in candidates) {
            val recovered = FileCheckpointStore(dir, matchId).recover() ?: continue
            val file = File(dir, "$matchId-gen-${recovered.generation.toString().padStart(6, '0')}.unciv-agent")
            check(file.isFile) { "Recovered generation path missing: $file" }
            recoveredSources.add(Source(file, recovered))
        }
        recoveredSources.maxByOrNull { it.file.lastModified() }?.let { result.add(it) }
        val normalDir = File(path, "SaveFiles").takeIf { it.isDirectory }
            ?: path.takeIf { it.name == "SaveFiles" }
        normalDir?.listFiles()?.filter { it.isFile && !it.name.startsWith(".") }
            ?.maxByOrNull { it.lastModified() }?.let { result.add(Source(it)) }
        return result
    }

    private fun checkSource(source: Source): Map<String, Any?> {
        val file = source.file
        val sourceHash = hashFile(file)
        val sourceMtime = file.lastModified()
        val text = readText(file)
        val raw = try { JsonReader().parse(text) } catch (_: Exception) { null }
        val isBundle = raw?.has("gameJson") == true && raw.has("matchJson")
        return if (isBundle) checkBundle(source, text, sourceHash, sourceMtime)
        else checkNormalSave(source, text, sourceHash, sourceMtime)
    }

    private fun checkBundle(source: Source, text: String, sourceHash: String, sourceMtime: Long): Map<String, Any?> {
        val serializer = json().apply { setUsePrototypes(false) }
        val rawEnvelope = JsonReader().parse(text)
        val rawBundle = serializer.fromJson(AgentBundle::class.java, text)
        val rawState = JsonReader().parse(rawBundle.matchJson)
        val sourceVersion = rawEnvelope.getInt("bundleVersion", 1)
        val sourceStateVersion = rawState.getInt("formatVersion", 1)
        // Deserialization without transients establishes saved gameplay values and default values.
        val beforeGame = serializer.fromJson(GameInfo::class.java, rawBundle.gameJson)
        val beforeMatch = serializer.fromJson(AgentMatchState::class.java, rawBundle.matchJson)
        val writer = InMemoryCheckpointWriter()
        val coordinator = MatchCoordinator.loadFromJson(source.recovery?.bundleJson ?: text, writer)
        val saved = coordinator.saveToJson()
        val afterBundle = serializer.fromJson(AgentBundle::class.java, saved)
        val afterGame = serializer.fromJson(GameInfo::class.java, afterBundle.gameJson)
        val afterMatch = serializer.fromJson(AgentMatchState::class.java, afterBundle.matchJson)
        val gameplayChecks = compareSnapshots(gameSnapshot(beforeGame), gameSnapshot(afterGame))
        val metadataChecks = compareSnapshots(matchSnapshot(beforeMatch), matchSnapshot(afterMatch))
        val reloaded = MatchCoordinator.loadFromJson(saved)
        val roundtripBundle = serializer.fromJson(AgentBundle::class.java, reloaded.saveToJson())
        val roundtripGame = serializer.fromJson(GameInfo::class.java, roundtripBundle.gameJson)
        val roundtripMatch = serializer.fromJson(AgentMatchState::class.java, roundtripBundle.matchJson)
        val gameplayRoundtrip = compareSnapshots(gameSnapshot(afterGame), gameSnapshot(roundtripGame))
        val metadataRoundtrip = compareSnapshots(matchSnapshot(afterMatch), matchSnapshot(roundtripMatch))
        val explicitV2 = JsonReader().parse(saved).getInt("bundleVersion", -1) == 2
            && JsonReader().parse(afterBundle.matchJson).getInt("formatVersion", -1) == 2
            && JsonReader().parse(reloaded.saveToJson()).getInt("bundleVersion", -1) == 2
            && JsonReader().parse(roundtripBundle.matchJson).getInt("formatVersion", -1) == 2
        val historical = checkLegacyExploration(coordinator, afterBundle, sourceVersion == 1)
        val untouched = unchanged(source.file, sourceHash, sourceMtime)
        val recoveryMatches = source.recovery == null || canonical(JsonReader().parse(source.recovery.bundleJson)) ==
            canonical(JsonReader().parse(serializer.toJson(migrateAgentBundleJson(text))))
        val passed = allPassed(gameplayChecks) && allPassed(metadataChecks) && allPassed(gameplayRoundtrip)
            && allPassed(metadataRoundtrip) && explicitV2 && historical["passed"] == true && untouched
            && recoveryMatches && writer.writeCount == 0
        return linkedMapOf("source" to source.file.absolutePath, "kind" to "paired-agent-bundle", "passed" to passed,
            "sourceSha256" to sourceHash, "sourceBundleVersion" to sourceVersion, "sourceStateVersion" to sourceStateVersion,
            "verifiedRecovery" to source.recovery?.let { mapOf("generation" to it.generation,
                "fellBack" to it.fellBack, "discardedGenerations" to it.discardedGenerations,
                "matchesDirectMigration" to recoveryMatches) },
            "gameplayPreservation" to gameplayChecks, "metadataPreservation" to metadataChecks,
            "explicitVersion2Roundtrip" to explicitV2, "roundtripGameplay" to gameplayRoundtrip,
            "roundtripMetadata" to metadataRoundtrip, "legacyExploration" to historical,
            "controllers" to afterMatch.controllers.size,
            "externalAgentProfiles" to afterMatch.controllers.values.count { it.agentProfile != null },
            "mailMessages" to afterMatch.diplomaticMessages.size,
            "stateRevision" to afterMatch.stateRevision, "checkpointWrites" to writer.writeCount,
            "sourceBytesAndMtimeUnchanged" to untouched)
    }

    private fun checkNormalSave(source: Source, text: String, sourceHash: String, sourceMtime: Long): Map<String, Any?> {
        // Use the actual normal-save API for zipped/plain data and transient restoration.
        val fixed = text.trim().replace("\r", "").replace("\n", "")
        val plain = try { FileConversions.unzip(fixed) } catch (_: Exception) { fixed }
        val before = json().fromJson(GameInfo::class.java, plain)
        val loaded = UncivFiles.gameInfoFromString(text)
        val sourceChecks = compareSnapshots(gameSnapshot(before), gameSnapshot(loaded))
        val normalized = UncivFiles.gameInfoToString(loaded, forceZip = false)
        val reloaded = UncivFiles.gameInfoFromString(normalized)
        val checks = compareSnapshots(gameSnapshot(loaded), gameSnapshot(reloaded))
        val untouched = unchanged(source.file, sourceHash, sourceMtime)
        return linkedMapOf("source" to source.file.absolutePath, "kind" to "normal-game-save",
            "passed" to (allPassed(sourceChecks) && allPassed(checks) && untouched), "sourceSha256" to sourceHash,
            "normalLoaderSourcePreservation" to sourceChecks,
            "normalLoaderRoundtrip" to checks, "civilizations" to loaded.civilizations.size,
            "units" to loaded.tileMap.values.sumOf { it.getUnits().count() },
            "agentMetadataChecks" to "NOT_APPLICABLE: normal GameInfo save has no paired agent metadata",
            "sourceBytesAndMtimeUnchanged" to untouched)
    }

    private fun checkLegacyExploration(coordinator: MatchCoordinator, bundle: AgentBundle, legacy: Boolean): Map<String, Any?> {
        if (!legacy) return mapOf("passed" to true, "applicable" to false, "reason" to "Source already version 2")
        val game = MatchCoordinator.deserializeForPublication(bundle.gameJson)
        val match = json().fromJson(AgentMatchState::class.java, bundle.matchJson)
        var hiddenExplored = 0
        var violations = 0
        var readChecks = 0
        val beforeRead = coordinator.saveToJson()
        for (civId in match.controllers.keys) {
            val civ = game.getCivilizationOrNull(civId) ?: continue
            if (civ.isSpectator()) continue
            val visible = normalViewableTiles(civ)
            for (tile in game.tileMap.values) {
                if (tile in visible || !tile.isExploredNormally(civ)) continue
                hiddenExplored++
                val observed = match.mapKnowledge.get(civId, tile.position)
                // Restoration may retain geometry, but cannot synthesize historical resource/terrain/work facts.
                if (observed == null || !incompleteLegacyTile(observed)) violations++
                if (readChecks < 32) {
                    val inspected = coordinator.inspectTile(civId, tile.position)
                    if (inspected == null || !incompleteLegacyTile(inspected)) violations++
                    readChecks++
                }
            }
        }
        val readsUnchanged = beforeRead == coordinator.saveToJson()
        return mapOf("passed" to (violations == 0 && readsUnchanged), "applicable" to (hiddenExplored > 0),
            "hiddenExploredTilesAcrossControllers" to hiddenExplored, "inventedHistoricalDetailViolations" to violations,
            "publicInspectSamples" to readChecks, "readsLeftSerializedStateUnchanged" to readsUnchanged)
    }

    private fun incompleteLegacyTile(tile: ObservedTile) = tile.toWire().all { (key, value) ->
        when (key) {
            "x", "y", "visibility" -> true
            "detailsKnown" -> value == false
            // Current researched technology can establish a resource fact without a historical sighting.
            "resource" -> tile.resource == null || tile.resource!!.source == ResourceKnowledgeSource.TECHNOLOGY
            else -> value == null
        }
    }

    private fun matchSnapshot(match: AgentMatchState): Map<String, Any?> {
        val raw = JsonReader().parse(json().apply { setUsePrototypes(false) }.toJson(match))
        return children(raw).filter { it.name !in setOf("formatVersion", "mapKnowledge") }
            .associate { it.name to canonical(it) }.toSortedMap()
    }

    private fun gameSnapshot(game: GameInfo): Map<String, Any?> {
        val raw = JsonReader().parse(json().apply { setUsePrototypes(false) }.toJson(game))
        val civs = children(raw.get("civilizations"))
        val tiles = children(raw.get("tileMap").get("tileList"))
        fun civFields(vararg fields: String) = civs.map { civ ->
            linkedMapOf<String, Any?>("civID" to canonical(civ.get("civID"))).also { result ->
                for (field in fields) result[field] = canonical(civ.get(field))
            }
        }
        val resourceTiles = tiles.filter { it.get("resource")?.isNull == false }.map {
            mapOf("position" to canonical(it.get("position")), "resource" to canonical(it.get("resource")),
                "amount" to canonical(it.get("resourceAmount")))
        }
        val units = tiles.filter { tile ->
            tile.get("militaryUnit")?.isNull == false || tile.get("civilianUnit")?.isNull == false
                || (tile.get("airUnits")?.size ?: 0) > 0
        }.map { tile -> mapOf("position" to canonical(tile.get("position")),
            "military" to canonical(tile.get("militaryUnit")), "civilian" to canonical(tile.get("civilianUnit")),
            "air" to canonical(tile.get("airUnits"))) }
        return linkedMapOf("gameIdentityAndTurn" to listOf("gameId", "turns", "currentPlayer", "lastUnitId").associateWith { canonical(raw.get(it)) },
            "difficultyAndBonusConfiguration" to mapOf("difficulty" to canonical(raw.get("difficulty")),
                "parameters" to canonical(raw.get("gameParameters")),
                "civPlayerCategoriesAndBonuses" to civFields("civName", "playerType", "temporaryUniques", "pendingStartingUnits")),
            "civEconomyAndResources" to civFields("gold", "resourceStockpiles", "tech", "policies"),
            "resourceTiles" to resourceTiles, "unitsIncludingChargesAndActions" to units,
            "cities" to civFields("cities"))
    }

    private fun compareSnapshots(before: Map<String, Any?>, after: Map<String, Any?>): Map<String, Any?> =
        (before.keys + after.keys).sorted().associateWith { field ->
            val beforeJson = AgentJson.stringify(before[field])
            val afterJson = AgentJson.stringify(after[field])
            mapOf("preserved" to (beforeJson == afterJson), "beforeSha256" to hash(beforeJson), "afterSha256" to hash(afterJson))
        }

    private fun allPassed(checks: Map<String, Any?>) = checks.values.all { (it as Map<*, *>)["preserved"] == true }

    /** Normalize object ordering; retain array order and scalar values. Avoid dumping private notes or personality text. */
    private fun canonical(value: JsonValue?): Any? = when {
        value == null || value.isNull -> null
        value.isObject -> children(value).sortedBy { it.name }.associate { it.name to canonical(it) }
        value.isArray -> children(value).map { canonical(it) }
        value.isBoolean -> value.asBoolean()
        value.isLong -> value.asLong()
        value.isDouble -> value.asDouble()
        else -> value.asString()
    }

    private fun children(value: JsonValue?): List<JsonValue> {
        val result = ArrayList<JsonValue>()
        var child = value?.child
        while (child != null) { result.add(child); child = child.next }
        return result
    }

    private fun readText(file: File): String = file.inputStream().buffered().use { input ->
        input.mark(2)
        val gzip = input.read() == 0x1f && input.read() == 0x8b
        input.reset()
        (if (gzip) GZIPInputStream(input) else input).reader(Charsets.UTF_8).use { it.readText() }
    }

    private fun unchanged(file: File, sha: String, mtime: Long) = file.lastModified() == mtime && hashFile(file) == sha
    private fun hash(text: String) = hex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)))
    private fun hashFile(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val read = input.read(buffer); if (read < 0) break; digest.update(buffer, 0, read) }
        }
        return hex(digest.digest())
    }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}
