package com.unciv.logic.files

import com.badlogic.gdx.files.FileHandle
import com.unciv.testing.BaseTestRunner
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class MapDiscoveryTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun map(directory: FileHandle, name: String, zipped: Boolean): FileHandle =
        directory.child(name).also {
            val contents = """{"mapParameters":{"name":"$name","baseRuleset":"Civ V - Vanilla"}}"""
            it.writeString(if (zipped) FileConversions.zip(contents) else contents, false, "UTF-8")
        }

    @Test fun `newer metadata and unreadable map do not hide later local and mod maps`() {
        val local = FileHandle(temporary.newFolder("maps"))
        val mod = FileHandle(temporary.newFolder("mod-maps"))
        val earth = map(local, "Legendary Earth - Vanilla TSL", zipped = true)
        val modMap = map(mod, "Another map.json", zipped = false)
        val metadata = local.child("._${earth.name()}").apply { writeBytes(byteArrayOf(0, 5, 22, 7), false) }
        val finder = local.child(".DS_Store").apply { writeString("Finder metadata", false) }
        val corrupt = local.child("damaged-map").apply { writeString("{not a valid map", false) }
        val directory = local.child("folder").apply { mkdirs() }
        val before = listOf(earth, modMap, metadata, finder, corrupt).associateWith { it.readBytes().toList() }

        // The UI turns a terminated discovery stream into an empty/partial selector.
        val previews = runCatching {
            MapSaver.getMapPreviews(sequenceOf(metadata, finder, directory, corrupt, earth, modMap)).toList()
        }.getOrDefault(emptyList())
        assertEquals(listOf(earth, modMap), previews.map { it.first })
        assertEquals(listOf(earth.name(), modMap.name()), previews.map { it.second.mapParameters.name })
        before.forEach { (file, bytes) -> assertEquals(bytes, file.readBytes().toList()) }
        assertTrue(directory.isDirectory)
    }

    @Test fun `a bad entry between valid maps does not truncate discovery`() {
        val directory = FileHandle(temporary.newFolder())
        val first = map(directory, "First map", zipped = false)
        val corrupt = directory.child("empty map").apply { writeString("", false) }
        val last = map(directory, "Last map", zipped = true)
        val found = ArrayList<FileHandle>()
        runCatching {
            MapSaver.getMapPreviews(sequenceOf(first, corrupt, last)).forEach { found.add(it.first) }
        }
        assertEquals(listOf(first, last), found)
        assertTrue(corrupt.exists())
    }
}
