package com.unciv.app.desktop

import java.io.File
import java.nio.file.Files

/** A configured external data directory must never silently fall back to internal storage. */
object DesktopDataDirectory {
    fun resolve(arguments: Array<String>, nativeLaunch: Boolean,
                configured: String? = System.getProperty("unciv.data.dir")): String? {
        val selected = arguments.firstOrNull { it.startsWith("--data-dir=") }
            ?.removePrefix("--data-dir=") ?: configured
            ?: if (nativeLaunch) File(System.getProperty("user.home"),
                "Library/Application Support/Unciv").absolutePath else return null
        val directory = File(selected).toPath().toAbsolutePath().normalize().toFile()
        requireAvailableVolume(directory)
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create Unciv data directory: $directory" }
        check(directory.canWrite()) { "Unciv data directory is not writable: $directory" }
        return directory.absolutePath
    }

    /** Rechecked before checkpoints so ejecting a drive cannot create a local substitute. */
    fun requireAvailableVolume(directory: File) {
        val volumes = File("/Volumes").toPath()
        val normalized = directory.toPath().toAbsolutePath().normalize()
        if (normalized.startsWith(volumes)) {
            val relative = volumes.relativize(normalized)
            require(relative.nameCount > 0) { "Select a mounted volume for Unciv storage" }
            val volume = File("/Volumes", relative.getName(0).toString())
            check(volume.isDirectory && Files.getFileStore(volume.toPath()) != Files.getFileStore(volumes)) {
                "Unciv storage volume is unavailable: ${volume.absolutePath}. Reconnect it before opening Unciv."
            }
        }
    }
}
