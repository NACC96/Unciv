package com.unciv.logic.agents

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/** Real APFS symlinks exercise the same containment policy used by mounted-volume stores. */
class MessageImageStorageBoundaryTest {
    private fun requireMessageImageVolumeContainment(path: java.io.File, volume: java.io.File) {
        // :tests is a separate module; keep the production containment policy internal.
        invokePolicy("requireMessageImageVolumeContainment", path, volume)
    }

    private fun invokePolicy(name: String, first: java.io.File, second: java.io.File) {
        val method = Class.forName("com.unciv.app.desktop.agents.FileMessageImageStoreKt")
            .getDeclaredMethod(name, java.io.File::class.java, java.io.File::class.java)
        try { method.invoke(null, first, second) }
        catch (failure: java.lang.reflect.InvocationTargetException) { throw failure.targetException }
    }

    private fun assertEscapeRejected(path: Path, volume: Path) {
        try {
            requireMessageImageVolumeContainment(path.toFile(), volume.toFile())
            fail("Resolved userdata must remain inside its configured volume: $path")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("configured volume"))
        }
    }

    @Test fun userdata_and_ancestor_symlinks_cannot_redirect_existing_or_future_blob_paths_outside_volume() {
        val fixture = Files.createTempDirectory("message-image-boundary-")
        try {
            val volume = Files.createDirectory(fixture.resolve("volume"))
            val internal = Files.createDirectory(fixture.resolve("internal"))
            val userdataLink = Files.createSymbolicLink(volume.resolve("userdata"), internal)
            assertEscapeRejected(userdataLink, volume)
            assertEscapeRejected(userdataLink.resolve(".agent-data/message-images/new.png"), volume)
            val ancestorLink = Files.createSymbolicLink(volume.resolve("ancestor"), internal)
            assertEscapeRejected(ancestorLink.resolve("future-userdata/.agent-data/message-images/new.png"), volume)
            val sibling = Files.createDirectory(fixture.resolve("volume-other"))
            val siblingLink = Files.createSymbolicLink(volume.resolve("prefix-escape"), sibling)
            assertEscapeRejected(siblingLink.resolve("future/images"), volume)
            assertFalse(Files.exists(internal.resolve(".agent-data")))
            assertFalse(Files.exists(internal.resolve("future-userdata")))
        } finally {
            // Remove symlinks explicitly before walking the bounded fixture tree.
            Files.deleteIfExists(fixture.resolve("volume/userdata"))
            Files.deleteIfExists(fixture.resolve("volume/ancestor"))
            Files.deleteIfExists(fixture.resolve("volume/prefix-escape"))
            fixture.toFile().deleteRecursively()
        }
    }

    @Test fun resolved_paths_within_volume_allow_existing_and_future_userdata_and_symlink_aliases() {
        val fixture = Files.createTempDirectory("message-image-boundary-within-")
        try {
            val volume = Files.createDirectory(fixture.resolve("volume"))
            val actual = Files.createDirectory(volume.resolve("actual-userdata"))
            val alias = Files.createSymbolicLink(volume.resolve("userdata"), actual)
            requireMessageImageVolumeContainment(actual.toFile(), volume.toFile())
            requireMessageImageVolumeContainment(alias.resolve(".agent-data/message-images/new.png").toFile(), volume.toFile())
            requireMessageImageVolumeContainment(volume.resolve("future/userdata/images").toFile(), volume.toFile())
            assertFalse(Files.exists(actual.resolve(".agent-data")))
        } finally {
            Files.deleteIfExists(fixture.resolve("volume/userdata"))
            fixture.toFile().deleteRecursively()
        }
    }

    @Test fun plain_directory_on_parent_filesystem_cannot_be_admitted_as_a_mounted_volume() {
        val fixture = Files.createTempDirectory("message-image-unmounted-")
        try {
            val plainFolder = Files.createDirectory(fixture.resolve("unmounted-volume"))
            try {
                invokePolicy("requireMountedMessageImageVolume", plainFolder.toFile(), fixture.toFile())
                fail("A plain folder is not a mounted storage volume")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message!!.contains("not mounted"))
            }
        } finally { fixture.toFile().deleteRecursively() }
    }

    @Test fun macos_system_temp_directory_aliases_are_resolved_without_blanket_symlink_rejection() {
        val fixture = Files.createTempDirectory("message-image-system-alias-")
        try {
            requireMessageImageVolumeContainment(fixture.resolve("future/images").toFile(), fixture.toFile())
            assertFalse(Files.exists(fixture.resolve("future")))
        } finally { fixture.toFile().deleteRecursively() }
    }
}
