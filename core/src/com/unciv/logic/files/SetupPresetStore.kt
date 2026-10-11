package com.unciv.logic.files

import com.badlogic.gdx.files.FileHandle
import com.unciv.json.json
import com.unciv.logic.agents.AgentProfile
import com.unciv.models.metadata.GameSetupInfo
import com.unciv.models.metadata.Player
import java.util.UUID

class PresetAgentSlot {
    var index = 0
    var active = true
    var profile = AgentProfile()
}

class SetupPreset {
    var version = 1
    var id = ""
    var name = ""
    var setup = GameSetupInfo()
    var mapPath: String? = null
    var agents = ArrayList<PresetAgentSlot>()
    override fun toString() = name

    fun restoreMapFile() {
        setup.mapFile = mapPath?.let { path ->
            FileHandle(path).also { require(it.exists() && !it.isDirectory) { "The preset's map file is missing: $path" } }
        }
        require(setup.mapParameters.name.isEmpty() || setup.mapFile != null) { "The preset's selected map file is missing." }
    }
}

/** Named local snapshots. A replacement preserves the previous file as a backup. */
class SetupPresetStore(private val directory: FileHandle) {
    fun list(): Pair<List<SetupPreset>, List<String>> {
        val errors = ArrayList<String>()
        val presets = directory.list().filter { it.extension() == "json" && !it.name().startsWith("._") }.mapNotNull { file ->
            try { read(file) } catch (ex: Exception) { errors.add("${file.name()}: ${ex.message}"); null }
        }.sortedBy { it.name.lowercase() }
        return presets to errors
    }

    private fun read(file: FileHandle): SetupPreset = json().fromJson(SetupPreset::class.java, file).also {
        require(it.version == 1) { "Unsupported preset version ${it.version}" }
        require(it.id == file.nameWithoutExtension() && it.id.matches(Regex("[0-9a-f-]{36}"))) { "Invalid preset identity" }
        require(it.name.isNotBlank()) { "Preset name is missing" }
        require(it.agents.map { slot -> slot.index }.distinct().size == it.agents.size) { "Duplicate agent slots" }
        require(it.agents.all { slot -> slot.index in it.setup.gameParameters.players.indices }) { "Agent slot no longer exists" }
    }

    fun save(name: String, setup: GameSetupInfo, profiles: Map<Player, AgentProfile>, cache: Map<Player, AgentProfile> = profiles): SetupPreset {
        require(name.trim().isNotEmpty()) { "Give the preset a name." }
        val prior = list().first.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
        val preset = SetupPreset().apply {
            id = prior?.id ?: UUID.randomUUID().toString()
            this.name = name.trim()
            this.setup = json().fromJson(GameSetupInfo::class.java, json().toJson(setup))
            mapPath = setup.mapFile?.takeIf { setup.mapParameters.name.isNotEmpty() }?.file()?.absolutePath
            for ((index, player) in setup.gameParameters.players.withIndex()) {
                val savedProfile = profiles[player] ?: cache[player] ?: continue
                agents.add(PresetAgentSlot().also {
                    it.index = index; it.active = player in profiles; it.profile = savedProfile.copy()
                })
            }
        }
        directory.mkdirs()
        val target = directory.child("${preset.id}.json")
        val temporary = directory.child("${preset.id}.tmp")
        temporary.writeString(json().prettyPrint(preset), false, "UTF-8")
        // Parse before replacing a valid saved setup. Names never become file paths.
        json().fromJson(SetupPreset::class.java, temporary)
        if (target.exists()) target.copyTo(directory.child("${preset.id}-${UUID.randomUUID()}.backup"))
        temporary.moveTo(target)
        return read(target)
    }
}
