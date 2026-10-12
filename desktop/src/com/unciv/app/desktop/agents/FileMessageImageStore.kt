package com.unciv.app.desktop.agents

import com.unciv.app.desktop.DesktopDataDirectory
import com.unciv.logic.agents.DiplomaticImageAttachment
import com.unciv.logic.agents.MessageImageDraft
import com.unciv.logic.agents.MessageImageLimits
import javax.imageio.stream.MemoryCacheImageInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileStore
import java.nio.file.LinkOption
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import javax.imageio.ImageIO

/** Host-owned, content-addressed PNGs. No save/checkpoint or observation contains image bytes. */
class FileMessageImageStore(
    private val dataDir: File,
    private val directorySync: (File) -> Unit = { directory ->
        FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
    }
) {
    private val directory = File(dataDir, ".agent-data/message-images")

    @Synchronized
    fun store(image: MessageImageDraft): DiplomaticImageAttachment {
        requireStorageAvailable()
        val draft = image.copy()
        validatePng(draft)
        val metadata = DiplomaticImageAttachment(sha256(draft.bytes), draft.mimeType,
            draft.width, draft.height, draft.bytes.size)
        ensureDirectory()
        val final = File(directory, "${metadata.attachmentId}.png")
        if (final.exists()) {
            read(metadata) // A corrupt pre-existing blob is an error, never silently overwritten.
            directorySync(directory)
            requireStorageAvailable(final)
            return metadata
        }
        requireStorageAvailable(final)
        val temporary = Files.createTempFile(directory.toPath(), ".message-image-", ".tmp").toFile()
        try {
            requireStorageAvailable(temporary, final)
            FileOutputStream(temporary).use {
                it.write(draft.bytes)
                it.flush()
                it.fd.sync()
            }
            requireStorageAvailable(temporary, final)
            try { Files.move(temporary.toPath(), final.toPath(), StandardCopyOption.ATOMIC_MOVE) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(temporary.toPath(), final.toPath()) }
            // Required, including on ExFAT. An orphan is safe; a premature metadata reference is not.
            directorySync(directory)
            read(metadata)
            return metadata
        } finally { Files.deleteIfExists(temporary.toPath()) }
    }

    @Synchronized
    fun read(metadata: DiplomaticImageAttachment): MessageImageDraft {
        requireStorageAvailable()
        metadata.validate()
        require(!Files.isSymbolicLink(directory.toPath()) &&
            !Files.isSymbolicLink(directory.parentFile.toPath())) { "Message image directory must not be a symbolic link" }
        val file = File(directory, "${metadata.attachmentId}.png")
        requireStorageAvailable(file)
        check(file.isFile && !Files.isSymbolicLink(file.toPath())) { "Message image is missing: ${metadata.attachmentId}" }
        check(file.length() == metadata.byteLength.toLong()) { "Message image byte length is corrupt" }
        val bytes = file.inputStream().use { input ->
            // A concurrent replacement must not make readBytes allocate an unbounded buffer.
            val result = input.readNBytes(MessageImageLimits.MAX_BYTES + 1)
            check(result.size == metadata.byteLength) { "Message image byte length is corrupt" }
            result
        }
        requireStorageAvailable(file)
        check(sha256(bytes) == metadata.attachmentId) { "Message image SHA-256 is corrupt" }
        return MessageImageDraft(bytes, metadata.mimeType, metadata.width, metadata.height).also { validatePng(it) }
    }

    private fun ensureDirectory() {
        requireStorageAvailable()
        val missing = ArrayList<File>()
        var ancestor = directory
        while (!ancestor.exists()) {
            missing.add(ancestor)
            ancestor = ancestor.parentFile ?: error("Cannot locate image storage parent")
        }
        require(!Files.isSymbolicLink(directory.toPath()) &&
            !Files.isSymbolicLink(directory.parentFile.toPath())) { "Message image directory must not be a symbolic link" }
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create message image directory" }
        requireStorageAvailable()
        // Persist each newly-created entry in its parent before promising durable blob metadata.
        for (created in missing.asReversed()) directorySync(created.parentFile)
        // A prior failed publication may have left these directories present but unsynced.
        directorySync(dataDir)
        directorySync(directory.parentFile)
    }

    /** Required external-volume checks apply to the resolved path, never just its lexical prefix. */
    private fun requireStorageAvailable(vararg targets: File) {
        DesktopDataDirectory.requireAvailableVolume(dataDir.absoluteFile)
        val volumes = File("/Volumes").toPath().toAbsolutePath().normalize()
        val configured = dataDir.toPath().toAbsolutePath().normalize()
        if (!configured.startsWith(volumes)) return
        val relative = volumes.relativize(configured)
        check(relative.nameCount > 0 && relative.getName(0).toString().isNotEmpty()) {
            "Select a mounted volume for message image storage"
        }
        val volume = volumes.resolve(relative.getName(0)).toFile()
        requireMountedMessageImageVolume(volume, volumes.toFile())
        requireMessageImageVolumeContainment(dataDir, volume)
        requireMessageImageVolumeContainment(directory, volume)
        for (target in targets) requireMessageImageVolumeContainment(target, volume)
    }

    private fun validatePng(draft: MessageImageDraft) {
        require(draft.mimeType == "image/png") { "Message images must be PNG" }
        require(draft.bytes.size in 1..MessageImageLimits.MAX_BYTES) { "Message image must fit within 4 MiB" }
        val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        require(draft.bytes.size >= 33 && draft.bytes.take(8).toByteArray().contentEquals(signature)) {
            "Message image is not a PNG"
        }
        MemoryCacheImageInputStream(ByteArrayInputStream(draft.bytes)).use { input ->
            val readers = ImageIO.getImageReaders(input)
            require(readers.hasNext()) { "Message image is not a supported PNG" }
            val reader = readers.next()
            try {
                require(reader.formatName.equals("png", ignoreCase = true)) { "Message image must be PNG" }
                reader.input = input
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                require(width in 1..MessageImageLimits.MAX_EDGE && height in 1..MessageImageLimits.MAX_EDGE &&
                    width.toLong() * height <= MessageImageLimits.MAX_SOURCE_PIXELS) { "Message image dimensions exceed limits" }
                require(width == draft.width && height == draft.height) { "Message image dimensions do not match its metadata" }
                // Dimensions are bounded before decoding, so malformed/expanding images cannot allocate freely.
                val decoded = reader.read(0)
                require(decoded != null && decoded.width == width && decoded.height == height) { "Message PNG cannot be decoded" }
            } finally { reader.dispose() }
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}

/** Plain fallback folders must not be mistaken for an available external storage volume. */
internal fun requireMountedMessageImageVolume(volume: File, volumesDirectory: File) {
    val volumeStore = Files.getFileStore(volume.toPath().toRealPath())
    val parentStore = Files.getFileStore(volumesDirectory.toPath().toRealPath())
    check(!sameMessageImageFileStore(volumeStore, parentStore)) {
        "Message image configured volume is not mounted: $volume"
    }
}

/** Resolve existing ancestry without creating anything; retain OS symlink and '..' semantics. */
internal fun requireMessageImageVolumeContainment(path: File, volume: File) {
    val root = volume.toPath().toRealPath()
    var existing = path.toPath().toAbsolutePath()
    val missing = java.util.ArrayDeque<java.nio.file.Path>()
    while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
        missing.addFirst(existing.fileName)
        existing = existing.parent ?: error("Cannot resolve message image configured volume ancestry")
    }
    val resolvedParent = existing.toRealPath()
    var resolved = resolvedParent
    for (component in missing) resolved = resolved.resolve(component)
    check(resolved.normalize().startsWith(root) &&
        sameMessageImageFileStore(Files.getFileStore(resolvedParent), Files.getFileStore(root))) {
        "Message image storage path escapes its configured volume: $path"
    }
}

// FileStore objects are not guaranteed to implement meaningful object equality on every provider.
private fun sameMessageImageFileStore(first: FileStore, second: FileStore): Boolean =
    first.name() == second.name() && first.type() == second.type()
