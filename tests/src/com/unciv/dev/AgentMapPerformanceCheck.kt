package com.unciv.dev

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.headless.HeadlessFiles
import com.unciv.json.json
import com.unciv.logic.agents.*
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.stats.Stat
import com.unciv.testing.TestGame
import java.io.File
import java.nio.file.Files

/** Manual, headless benchmark. No user save, provider, window, profile or live game is opened.
 * Run with an absolute output JSON path, optionally measured-sample count and warmup count.
 * The latest three compressed checkpoint generations remain at the reported paths.
 */
object AgentMapPerformanceCheck {
    private const val WIDTH = 164
    private const val HEIGHT = 80
    private const val CIV_COUNT = 15
    private var blackhole = 0

    private class CountingWriter(private val delegate: CheckpointWriter? = null) : CheckpointWriter {
        var writes = 0
        private var lastGameJson: String? = null
        private var lastMatchJson: String? = null
        val gameBytes get() = lastGameJson?.let { utf8(it) } ?: 0
        val matchBytes get() = lastMatchJson?.let { utf8(it) } ?: 0
        override fun write(gameJson: String, matchJson: String) {
            lastGameJson = gameJson
            lastMatchJson = matchJson
            writes++
            delegate?.write(gameJson, matchJson)
        }
    }

    @JvmStatic fun main(args: Array<String>) {
        require(args.isNotEmpty()) { "Provide an absolute JSON report path" }
        val output = File(args[0])
        require(output.isAbsolute) { "The report path must be absolute" }
        val samples = args.getOrNull(1)?.toInt() ?: 5
        val warmups = args.getOrNull(2)?.toInt() ?: 2
        require(samples in 3..20 && warmups in 0..10) { "Samples must be 3..20 and warmups 0..10" }
        Gdx.files = HeadlessFiles()
        val fixture = TestGame(baseRuleset = BaseRuleset.Civ_V_Vanilla)
        fixture.gameInfo.gameParameters.baseRuleset = BaseRuleset.Civ_V_Vanilla.fullName
        fixture.makeRectangularMap(HEIGHT, WIDTH)
        val tiles = fixture.tileMap.values.toList().sortedWith(compareBy({ it.position.x }, { it.position.y }))
        check(tiles.size == WIDTH * HEIGHT) { "Expected ${WIDTH * HEIGHT} tiles, found ${tiles.size}" }
        for (tile in tiles) {
            tile.baseTerrain = "Grassland"
            tile.setTerrainTransients()
        }
        val nations = fixture.ruleset.nations.values.filter {
            it.cityStateType == null && !it.isBarbarian && !it.isSpectator
        }.take(CIV_COUNT)
        check(nations.size == CIV_COUNT)
        val civs = nations.map { fixture.addCiv(it, isPlayer = true) }
        val units = civs.mapIndexed { index, civ ->
            fixture.addUnit("Warrior", civ, tiles[(index + 1) * tiles.size / (CIV_COUNT + 1)])
        }
        val actor = civs.first()
        fixture.gameInfo.currentPlayer = actor.civID
        fixture.gameInfo.currentPlayerCiv = actor
        fixture.gameInfo.agentNormalVisibility = true
        val gameJson = json().toJson(fixture.gameInfo)
        val initialVisible = civs.associate { it.civID to normalViewableTiles(it).size }
        val checkpointRoot = Files.createTempDirectory(output.parentFile.toPath(), "checkpoints-").toFile()
        val cases = ArrayList<Map<String, Any?>>()
        for ((mode, fraction) in listOf("empty" to 0.0, "partial" to 0.25, "full" to 1.0, "full-diverse" to 1.0)) {
            println("Agent map benchmark: $mode knowledge")
            val match = AgentMatchState(matchId = "benchmark", timelineId = "benchmark-timeline",
                turnPhase = TurnPhase.AWAITING_DECISIONS)
            for (civ in civs) match.controllers[civ.civID] = CivilizationController(civ.civID, ControllerKind.HUMAN)
            val selected = if (fraction == 0.0) emptyList() else tiles.filterIndexed { index, _ ->
                fraction == 1.0 || index % 4 == 0
            }
            for ((civIndex, civ) in civs.withIndex()) for ((tileIndex, tile) in selected.withIndex()) {
                // Synthetic historical sightings of this uniform fixture, not a visibility override.
                match.mapKnowledge.put(civ.civID, ObservedTile(
                    x = tile.position.x, y = tile.position.y, detailsKnown = true,
                    lastSeenTurn = 0, lastSeenRevision = 0, terrain = "Grassland",
                    features = ArrayList(), river = false, improvementPillaged = false,
                    road = "None", roadPillaged = false, land = true, water = false,
                    hill = false, coast = false, freshwater = false,
                    riverEdges = ObservedRiverEdges(),
                    yields = ObservedTileYields(values = LinkedHashMap<String, Float>().also { values ->
                        for (stat in Stat.entries) values[stat.name] = if (stat == Stat.Food) 2f else 0f
                    })
                ).apply {
                    if (mode == "full-diverse") {
                        // A second synthetic fixture avoids relying only on identical shared facts.
                        terrain = listOf("Grassland", "Plains", "Desert", "Tundra", "Coast", "Ocean")[tileIndex % 6]
                        land = tileIndex % 6 < 4; water = !land!!; coast = tileIndex % 6 == 4
                        hill = land!! && tileIndex % 7 == 0
                        features = if (tileIndex % 9 == 0) arrayListOf("Forest") else arrayListOf()
                        ownerCivId = if (tileIndex % 5 == 0) civs[tileIndex % CIV_COUNT].civID else null
                        improvement = if (tileIndex % 5 == 0) "Farm" else null
                        lastSeenTurn = tileIndex % 100 + civIndex
                        lastSeenRevision = tileIndex % 1000 + civIndex * 1000
                        resource = if (tileIndex % 17 == 0) ObservedResource("Iron", 2 + tileIndex % 5,
                            tileIndex % 100 + civIndex, tileIndex % 1000 + civIndex * 1000,
                            if (tileIndex % 2 == 0) ResourceKnowledgeSource.SIGHTING else ResourceKnowledgeSource.TECHNOLOGY) else null
                        city = if (tileIndex % 1000 == 0) ObservedPublicCity("city-$tileIndex", "City $tileIndex",
                            civs[tileIndex % CIV_COUNT].civID, 160 + tileIndex % 40, 2 + tileIndex % 12) else null
                        yields!!.values[Stat.Food.name] = (tileIndex % 4).toFloat()
                        yields!!.values[Stat.Production.name] = if (hill!!) 2f else 0f
                    }
                })
            }
            val copiedMetadata = timed(samples, warmups) {
                val copy = match.deepCopy()
                blackhole = copy.mapKnowledge.tilesByCiv.size
            }
            val serializedMetadata = timed(samples, warmups) {
                blackhole = json().apply { setUsePrototypes(false) }.toJson(match).length
            }
            val noOp = CountingWriter()
            val cpu = MatchCoordinator(fixture.gameInfo, match, noOp)
            val initialSizes = snapshotSizes(cpu)
            val mapPage = cpu.map(actor.civID, MapQuery(limit = 128)).toWire()
            val visiblePage = cpu.map(actor.civID, MapQuery(mode = MapQueryMode.VISIBLE, limit = 128)).toWire()
            val localPage = cpu.map(actor.civID, MapQuery(center = units.first().currentTile.position, radius = 8, limit = 128)).toWire()
            val rulesPage = buildRulesCatalog(fixture.gameInfo, fixture.ruleset.name,
                RulesQuery(section = "improvements", limit = 128)).toJson()
            val query = mapOf("known" to querySizes(mapPage), "visible" to querySizes(visiblePage),
                "radius8" to querySizes(localPage), "rulesImprovements" to querySizes(rulesPage))
            File(output.parentFile, "schema-map-$mode.json").writeText(AgentJson.stringify(mapPage))
            File(output.parentFile, "schema-tile-$mode.json").writeText(AgentJson.stringify(
                requireNotNull(cpu.inspectTile(actor.civID, units.first().currentTile.position)).toWire()))
            var sequence = 0
            val cpuMutation = timed(samples, warmups) {
                sequence++
                cpu.execute(RenameUnitCommand(actor.civID, "cpu-$sequence", cpu.getStateRevision(),
                    units.first().id, "benchmark-$sequence"))
            }
            val checkpointDir = File(checkpointRoot, mode)
            val store = FileCheckpointStore(checkpointDir, "benchmark", maxRetainedGenerations = 3)
            val diskWriter = CountingWriter(store)
            val disk = MatchCoordinator(fixture.gameInfo, match, diskWriter)
            val diskMutation = timed(samples, warmups) {
                sequence++
                disk.execute(RenameUnitCommand(actor.civID, "disk-$sequence", disk.getStateRevision(),
                    units.first().id, "benchmark-$sequence"))
            }
            val generations = checkpointDir.listFiles()?.filter { !it.name.startsWith("._") && it.name.endsWith(".unciv-agent") }
                ?.sortedBy { it.name } ?: emptyList()
            val sidecars = checkpointDir.listFiles()?.filter { !it.name.startsWith("._") && it.name.endsWith(".sha256") } ?: emptyList()
            check(generations.size == 3 && store.lastRetentionFailure == null) { "Recovery must retain exactly three verified generations" }
            val generationSizes = generations.map { it.length() }
            val generationMedian = median(generationSizes.map { it.toDouble() })
            val sidecarMedian = median(sidecars.map { it.length().toDouble() })
            val generationIncrement = if (generationSizes.size > 1)
                (generationSizes.last() - generationSizes.first()).toDouble() / (generationSizes.size - 1) else 0.0
            val bytesPerAction = generationMedian + sidecarMedian
            check(json().toJson(fixture.gameInfo) == gameJson) { "Benchmark cases must retain an identical game baseline" }
            check(civs.associate { it.civID to normalViewableTiles(it).size } == initialVisible) { "Normal actor visibility changed" }
            val summary = cpu.observe(actor.civID).mapSummary
            val result = linkedMapOf<String, Any?>(
                "mode" to mode, "seededFraction" to fraction,
                "seededTilesPerCiv" to selected.size, "seededTotalEntries" to selected.size * CIV_COUNT,
                "committedKnownTilesForActor" to summary.knownTileCount,
                "committedVisibleTilesForActor" to summary.visibleTileCount,
                "normalVisibleCountsBefore" to initialVisible,
                "inputGameAndNormalVisibilityUnchanged" to true,
                "queryBytes" to query, "initialSnapshotBytes" to initialSizes,
                "inputMatchDeepCopyMs" to copiedMetadata,
                "inputMatchSerializationMs" to serializedMetadata,
                "mutationWithNoOpCheckpointMs" to cpuMutation,
                "mutationWithFsyncedCheckpointMs" to diskMutation,
                "lastNoOpWriterGameBytes" to noOp.gameBytes,
                "lastNoOpWriterMatchBytes" to noOp.matchBytes,
                "checkpointDirectory" to checkpointDir.absolutePath,
                "checkpointGenerations" to generations.size,
                "actualRetainedLogicalDiskBytes" to (generationSizes.sum() + sidecars.sumOf { it.length() }),
                "filesystemCompanionLogicalBytes" to (checkpointDir.listFiles()?.filter { it.name.startsWith("._") }?.sumOf { it.length() } ?: 0),
                "generationBundleBytes" to generationSizes,
                "medianGenerationBundleBytes" to generationMedian,
                "medianChecksumSidecarBytes" to sidecarMedian,
                "observedWithinTurnReceiptGrowthBytesPerAction" to generationIncrement,
                "recoveryPolicy" to "Latest three verified compressed managed checkpoints",
                "steadyRecoveryWindowBytes" to (bytesPerAction * 3).toLong(),
                "lastWriteUsedAtomicMove" to store.lastWriteUsedAtomicMove,
                "lastRetentionFailure" to store.lastRetentionFailure
            )
            cases.add(result)
            writeReport(output, report(samples, warmups, checkpointRoot, utf8(gameJson), cases))
            println("Agent map benchmark: $mode complete; fsynced median ${diskMutation["median"]} ms")
        }
        val baseline = cases.first()
        fun latency(item: Map<String, Any?>, key: String): Double =
            (item[key] as Map<*, *>)["median"] as Double
        val ratios = cases.drop(1).map { item ->
            val cpuRatio = latency(item, "mutationWithNoOpCheckpointMs") / latency(baseline, "mutationWithNoOpCheckpointMs")
            val diskRatio = latency(item, "mutationWithFsyncedCheckpointMs") / latency(baseline, "mutationWithFsyncedCheckpointMs")
            mapOf("mode" to item["mode"], "noOpMedianRatio" to cpuRatio, "fsyncedMedianRatio" to diskRatio,
                "requiresOver20PercentInvestigation" to (cpuRatio > 1.2 || diskRatio > 1.2))
        }
        val final = report(samples, warmups, checkpointRoot, utf8(gameJson), cases).toMutableMap()
        final["latencyComparisonAgainstEmptyKnowledge"] = ratios
        final["requiresInvestigation"] = ratios.any { it["requiresOver20PercentInvestigation"] == true }
        writeReport(output, final)
        println("Agent map benchmark report: ${output.absolutePath}")
    }

    private fun report(samples: Int, warmups: Int, root: File, gameBytes: Int, cases: List<Map<String, Any?>>): Map<String, Any?> = linkedMapOf(
        "fixture" to mapOf("width" to WIDTH, "height" to HEIGHT, "tileCount" to WIDTH * HEIGHT,
            "civilizationCount" to CIV_COUNT, "ruleset" to BaseRuleset.Civ_V_Vanilla.fullName,
            "terrain" to "Grassland", "cities" to 0, "unitsPerCiv" to 1),
        "measuredSamples" to samples, "warmupSamples" to warmups,
        "serializedEquivalentGameBytes" to gameBytes,
        "checkpointRoot" to root.absolutePath,
        "checkpointFileSystem" to Files.getFileStore(root.toPath()).type(),
        "javaVersion" to System.getProperty("java.version"),
        "maxHeapBytes" to Runtime.getRuntime().maxMemory(),
        "baselineDefinition" to "Same current pipeline and identical serialized game with initially empty map knowledge. Normal coordinator visible capture remains enabled.",
        "limitations" to listOf(
            "Synthetic Earth-sized rectangle with uniform terrain and no cities; not real Earth-map gameplay or whole-game FPS.",
            "Rich historical snapshots are seeded into memory; actor normal sight and spectator settings are unchanged.",
            "The full-diverse case adds varied historical terrain, borders, cities, resources, yields and per-civ ages; it is synthetic and does not claim to reproduce a played Earth match.",
            "Constructor/deserialization setup is excluded from mutation timing; mutation includes game candidate clone, sightings, metadata copy, serialization and writer.",
            "The baseline is current code with empty initial memory, not a pre-feature build; it still runs the new visibility pipeline.",
            "Checkpoint timings use the reported filesystem; benchmark output must be on Lexar to measure its storage cost.",
            "Recovery window includes three checksum sidecars; it omits allocation rounding, filesystem overhead and further receipt/sighting growth.",
            "Old benchmark generations are pruned using the same policy as the desktop; no user archives are opened.",
            "Modes run sequentially in one JVM; warmup reduces but does not eliminate JIT, GC, filesystem cache and ordering effects."
        ), "cases" to cases)

    private fun snapshotSizes(coordinator: MatchCoordinator): Map<String, Any?> {
        val bundleText = coordinator.saveToJson()
        val bundle = json().fromJson(AgentBundle::class.java, bundleText)
        val match = json().fromJson(AgentMatchState::class.java, bundle.matchJson)
        return mapOf("gameBytes" to utf8(bundle.gameJson), "matchBytes" to utf8(bundle.matchJson),
            "bundleBytes" to utf8(bundleText), "committedMemoryEntries" to match.mapKnowledge.tilesByCiv.values.sumOf { it.size })
    }
    private fun querySizes(value: Map<String, Any?>): Map<String, Any?> {
        val logical = AgentJson.stringify(value)
        val frame = AgentJson.stringify(mapOf("jsonrpc" to "2.0", "id" to "benchmark-request-000001",
            "result" to mapOf("content" to listOf(mapOf("type" to "text", "text" to logical)))))
        return mapOf("logicalJsonBytes" to utf8(logical), "mcpTextContentBytes" to AgentReadBudget.contentBytes(value),
            "fullJsonRpcMcpBytes" to utf8(frame), "fitsReadBudget" to AgentReadBudget.fits(value))
    }
    private fun timed(samples: Int, warmups: Int, action: () -> Unit): Map<String, Any?> {
        repeat(warmups) { action() }
        val values = (0 until samples).map {
            val start = System.nanoTime()
            action()
            (System.nanoTime() - start) / 1e6
        }
        return mapOf("median" to median(values), "min" to values.min(), "max" to values.max(), "samples" to values)
    }
    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        return if (sorted.size % 2 == 1) sorted[sorted.size / 2]
            else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
    }
    private fun utf8(text: String) = text.toByteArray(Charsets.UTF_8).size
    private fun writeReport(output: File, value: Map<String, Any?>) {
        output.parentFile.mkdirs()
        output.writeText(AgentJson.stringify(value) + "\n", Charsets.UTF_8)
    }
}
