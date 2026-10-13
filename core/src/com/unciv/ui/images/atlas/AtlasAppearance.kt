package com.unciv.ui.images.atlas

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.utils.JsonReader
import com.unciv.models.metadata.GameSettings

/** The release packer enables this only after complete accepted art coverage. */
internal object AtlasAppearance {
    const val skin = "Atlas"
    const val tileSet = "AtlasTerrain"
    const val unitSet = "AtlasUnits"

    fun isAvailable(): Boolean = runCatching {
        val file = Gdx.files.internal("atlas-appearance.json")
        if (!file.exists() || file.length() !in 1..4096) return@runCatching false
        val metadata = JsonReader().parse(file.readString("UTF-8"))
        metadata.getInt("schemaVersion") == 1 && metadata.getBoolean("coverageComplete") &&
            metadata.getString("skin") == skin && metadata.getString("tileSet") == tileSet &&
            metadata.getString("unitSet") == unitSet
    }.getOrDefault(false)

    /** Existing settings remain intact except for these three reversible appearance choices. */
    fun apply(settings: GameSettings) {
        settings.skin = skin
        settings.tileSet = tileSet
        settings.unitSet = unitSet
    }
}
