package com.unciv.logic.files

import com.badlogic.gdx.Files
import com.badlogic.gdx.files.FileHandle
import com.unciv.testing.BaseTestRunner
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import java.io.File

@RunWith(BaseTestRunner::class)
class UncivFilesStorageTest {
    private fun withStorage(preferExternal: Boolean, test: (Files, File, File, File) -> Unit) {
        val root = java.nio.file.Files.createTempDirectory("unciv-profile-storage-").toFile()
        val local = File(root, "local").apply { mkdirs() }
        val external = File(root, "external").apply { mkdirs() }
        val configured = File(root, "configured").apply { mkdirs() }
        val files = Mockito.mock(Files::class.java)
        Mockito.`when`(files.localStoragePath).thenReturn(local.absolutePath)
        Mockito.`when`(files.externalStoragePath).thenReturn(external.absolutePath)
        Mockito.`when`(files.isExternalStorageAvailable).thenReturn(true)
        Mockito.`when`(files.local(Mockito.anyString())).thenAnswer { FileHandle(File(local, it.getArgument<String>(0))) }
        Mockito.`when`(files.external(Mockito.anyString())).thenAnswer { FileHandle(File(external, it.getArgument<String>(0))) }
        Mockito.`when`(files.absolute(Mockito.anyString())).thenAnswer { FileHandle(it.getArgument<String>(0)) }
        val previous = UncivFiles.preferExternalStorage
        try {
            UncivFiles.preferExternalStorage = preferExternal
            test(files, local, external, configured)
        } finally {
            UncivFiles.preferExternalStorage = previous
            root.deleteRecursively()
        }
    }

    private fun put(directory: File, path: String, contents: String): File =
        File(directory, path).apply { parentFile.mkdirs(); writeText(contents) }

    @Test fun `configured saves write and delete only their own profile with external-only same names`() {
        withStorage(false) { files, _, external, configured ->
            val original = put(external, "SaveFiles/shared", "original external save")
            val multiplayer = put(external, "MultiplayerGames/shared", "original multiplayer save")
            val storage = UncivFiles(files, configured.absolutePath)
            val save = storage.getSave("shared")
            assertEquals(File(configured, "SaveFiles/shared").canonicalFile, save.file().canonicalFile)
            save.writeString("configured save", false)
            assertEquals("configured save", save.readString())
            assertEquals("original external save", original.readText())
            storage.getMultiplayerSave("shared").writeString("configured multiplayer", false)
            assertEquals("original multiplayer save", multiplayer.readText())
            assertTrue(storage.deleteSave("shared"))
            assertFalse(File(configured, "SaveFiles/shared").exists())
            assertEquals("original external save", original.readText())
            assertFalse(storage.deleteSave("shared")) // An absent configured save does not delete the external original.
            assertEquals("original external save", original.readText())
            Mockito.verify(files, Mockito.never()).external(Mockito.anyString())
        }
    }

    @Test fun `configured listings never merge external saves even when external is preferred`() {
        withStorage(true) { files, _, external, configured ->
            put(external, "SaveFiles/external-only", "old save")
            put(external, "SaveFiles/shared", "external shared")
            put(external, "MultiplayerGames/external-multiplayer", "old multiplayer")
            put(configured, "SaveFiles/shared", "configured shared")
            put(configured, "SaveFiles/Autosave-1", "configured autosave")
            put(configured, "MultiplayerGames/configured-multiplayer", "new multiplayer")
            val storage = UncivFiles(files, configured.absolutePath)
            assertEquals(setOf("shared", "Autosave-1"), storage.getSaves().map { it.name() }.toSet())
            assertEquals(listOf("shared"), storage.getSaves(autoSaves = false).map { it.name() }.toList())
            assertEquals(listOf("configured-multiplayer"), storage.getMultiplayerSaves().map { it.name() }.toList())
            Mockito.verify(files, Mockito.never()).external(Mockito.anyString())
        }
    }

    @Test fun `configured generic writers ignore external preference and preserve settings originals`() {
        withStorage(true) { files, _, external, configured ->
            val original = put(external, "GameSettings.json", "external settings")
            val storage = UncivFiles(files, configured.absolutePath)
            assertEquals(File(configured, "GameSettings.json").canonicalFile,
                storage.pathToFileHandle("GameSettings.json").file().canonicalFile)
            storage.fileWriter("GameSettings.json").use { it.write("configured settings") }
            assertEquals("configured settings", File(configured, "GameSettings.json").readText())
            assertEquals("external settings", original.readText())
            Mockito.verify(files, Mockito.never()).external(Mockito.anyString())
        }
    }

    @Test fun `unconfigured launches retain external save fallback and merged listings`() {
        withStorage(true) { files, local, external, _ ->
            val original = put(external, "SaveFiles/external-only", "existing external save")
            put(local, "SaveFiles/local-only", "existing local save")
            val storage = UncivFiles(files)
            assertEquals(original.canonicalFile, storage.getSave("external-only").file().canonicalFile)
            assertEquals(setOf("external-only", "local-only"), storage.getSaves().map { it.name() }.toSet())
            assertEquals(File(external, "GameSettings.json").canonicalFile,
                storage.pathToFileHandle("GameSettings.json").file().canonicalFile)
            assertEquals("existing external save", original.readText())
        }
    }
}
