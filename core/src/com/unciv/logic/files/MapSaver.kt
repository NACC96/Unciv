package com.unciv.logic.files

import com.badlogic.gdx.files.FileHandle
import com.unciv.UncivGame
import com.unciv.json.json
import com.unciv.logic.map.MapParameters
import com.unciv.logic.map.TileMap
import com.unciv.utils.Log
import kotlinx.coroutines.CancellationException

object MapSaver {

    const val mapsFolder = "maps"
    var saveZipped = true

    private fun getMap(mapName: String) = UncivGame.Current.files.getLocalFile("$mapsFolder/$mapName")

    fun mapFromSavedString(mapString: String): TileMap {
        val unzippedJson = try {
            FileConversions.unzip(mapString.trim())
        } catch (_: Exception) {
            mapString
        }
        return mapFromJson(unzippedJson)
    }
    fun mapToSavedString(tileMap: TileMap): String {
        tileMap.assignContinents(TileMap.AssignContinentsMode.Reassign)
        val mapJson = json().toJson(tileMap)
        return if (saveZipped) FileConversions.zip(mapJson) else mapJson
    }

    fun saveMap(mapName: String, tileMap: TileMap) {
        tileMap.assignContinents(TileMap.AssignContinentsMode.Reassign)
        FileConversions.writeJson(getMap(mapName), tileMap, saveZipped)
    }

    fun loadMap(mapFile: FileHandle): TileMap {
        return FileConversions.readJson(mapFile, TileMap::class.java)!!
    }

    fun getMaps(): Array<FileHandle> = UncivGame.Current.files.getLocalFile(mapsFolder).list()

    fun getMapPreviews(mapFiles: Sequence<FileHandle>): Sequence<Pair<FileHandle, TileMap.Preview>> =
        mapFiles.filter { !it.isDirectory && !it.name().startsWith('.') }.mapNotNull { file ->
            try {
                file to loadMapPreview(file)
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                Log.error("Could not read map preview from %s: %s", file.path(), ex)
                null
            }
        }

    private fun mapFromJson(json: String): TileMap = json().fromJson(TileMap::class.java, json)

    fun loadMapParameters(mapFile: FileHandle): MapParameters {
        return loadMapPreview(mapFile).mapParameters
    }

    fun loadMapPreview(mapFile: FileHandle): TileMap.Preview {
        return FileConversions.readJson(mapFile, TileMap.Preview::class.java)!!
    }
}
