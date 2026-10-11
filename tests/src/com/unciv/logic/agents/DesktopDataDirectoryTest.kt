package com.unciv.logic.agents

import com.unciv.app.desktop.DesktopDataDirectory
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class DesktopDataDirectoryTest {
    @Test fun `explicit data directory overrides configured path`() {
        val directory = Files.createTempDirectory("unciv-data-path-").resolve("profile").toFile()
        assertEquals(directory.absolutePath,
            DesktopDataDirectory.resolve(arrayOf("--data-dir=${directory.absolutePath}"), true, "/unused"))
        assertTrue(directory.isDirectory)
    }
    @Test fun `missing external volume fails without creating a fallback`() {
        val absent = "/Volumes/UncivMissingVolumeForTest/userdata"
        try { DesktopDataDirectory.resolve(emptyArray(), true, absent); fail("Missing volume accepted") }
        catch (expected: IllegalStateException) { assertTrue(expected.message!!.contains("Reconnect")) }
        assertFalse(java.io.File(absent).exists())
    }
    @Test fun `unconfigured development launch retains local directory behavior`() {
        assertNull(DesktopDataDirectory.resolve(emptyArray(), false, null))
    }
}
