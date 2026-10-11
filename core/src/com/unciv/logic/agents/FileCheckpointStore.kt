package com.unciv.logic.agents

import com.unciv.json.json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Durable checkpoint generations on a plain directory (AI_AGENT_PLAN.md §10.1).
 *
 * Each [CheckpointWriter.write] publishes one immutable numbered generation:
 * `<matchId>-gen-<seq>.unciv-agent` plus a `<...>.sha256` sidecar. The payload
 * is written to a same-directory temp file, flushed and fsynced, then moved
 * into place — atomically where the filesystem supports it, by plain move
 * otherwise. Numbered generations make both paths safe: recovery never trusts
 * a latest-pointer or a partially written file, only the newest generation
 * whose checksum verifies AND whose bundle envelope parses.
 *
 * This is the ExFAT-tolerant half of §10.1 step 3–4: the same code runs on
 * the Lexar volume (proven by [FileCheckpointStoreTest]) and on ordinary
 * filesystems. The desktop checkpoint store reuses this class with the
 * project-owned data directory.
 */
class FileCheckpointStore(
    /** Directory holding generations; created on demand. */
    val dir: File,
    val matchId: String,
    /** Null preserves legacy unbounded/plain generations; bounded policies require at least three. */
    val maxRetainedGenerations: Int? = null,
    private val directorySync: (File) -> Unit = { directory ->
        FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
    }
) : CheckpointWriter {

    private val json = json().apply { setUsePrototypes(false) }
    private val verifiedFormats = HashMap<String, String>()

    /** Cleanup is best effort after publication; callers must not roll back a durable generation. */
    var lastRetentionFailure: String? = null
        private set
    var lastRetainedGenerations: List<Int> = emptyList()
        private set

    /** Whether the last publish used an atomic move (false = plain-move fallback). */
    var lastWriteUsedAtomicMove: Boolean = false
        private set

    /** Newest verified recovery, or null when no generation verifies. */
    class Recovery(
        /** Bundle JSON as produced by [MatchCoordinator.saveToJson]. */
        val bundleJson: String,
        val generation: Int,
        /** True when a newer generation existed but failed verification. */
        val fellBack: Boolean,
        /** Newer generations that failed verification (newest first). */
        val discardedGenerations: List<Int>
    )

    init {
        require(matchId.matches(Regex("[A-Za-z0-9_-]+"))) { "matchId must be filename-safe" }
        require(maxRetainedGenerations == null || maxRetainedGenerations >= 3) { "Retain at least three verified generations" }
    }

    override fun write(gameJson: String, matchJson: String) {
        // The coordinator supplies serialized payloads. Validate only metadata here: do not read
        // or parse a second enormous game JSON during each write merely to permit cleanup.
        val eligible = if (maxRetainedGenerations == null) false else try {
            com.badlogic.gdx.utils.JsonReader().parse(matchJson).getInt("formatVersion", 1) == AGENT_MATCH_FORMAT_VERSION
        } catch (_: Exception) { false }
        publishBundle(json.toJson(AgentBundle(gameJson, matchJson)), eligible)
    }

    /** Publishes a pre-built bundle; legacy/manual inputs receive full envelope validation. */
    fun writeBundle(bundleJson: String) {
        val eligible = maxRetainedGenerations != null && try {
            normalizeBundle(bundleJson)
            true
        } catch (_: Exception) { false }
        publishBundle(bundleJson, eligible)
    }

    private fun publishBundle(bundleJson: String, formatVerified: Boolean) {
        check(dir.isDirectory || dir.mkdirs()) { "Cannot create checkpoint directory $dir" }
        lastRetentionFailure = null
        val seq = nextSequence()
        val firstManaged = if (maxRetainedGenerations != null) retentionBoundary(seq) else null
        val tmp = File(dir, "$matchId-gen-${seqName(seq)}.tmp")
        FileOutputStream(tmp).use { out ->
            if (maxRetainedGenerations != null) {
                // Encode incrementally; avoid a full uncompressed UTF-8 byte array.
                GZIPOutputStream(out).use { gzip ->
                    val writer = gzip.writer(Charsets.UTF_8)
                    writer.write(bundleJson)
                    writer.flush()
                    gzip.finish()
                    out.flush()
                    out.fd.sync()
                }
            } else {
                val writer = out.writer(Charsets.UTF_8)
                writer.write(bundleJson)
                writer.flush()
                out.fd.sync()
            }
        }
        val digest = sha256File(tmp)
        val final = File(dir, generationName(seq))
        moveIntoPlace(tmp, final)
        writeSidecar(File(dir, "${generationName(seq)}.sha256"), digest)
        val directorySynced = try {
            fsyncDir()
            true
        } catch (ex: Exception) {
            lastRetentionFailure = "Checkpoint published; directory sync failed, retained all prior generations: ${ex.message}"
            false
        }
        if (formatVerified) verifiedFormats[final.name] = digest
        if (firstManaged != null && directorySynced) {
            try {
                // If publication cannot be verified, preserve every previous fallback.
                if (!formatVerified || verifiedDigest(final) != digest) {
                    lastRetentionFailure = "New generation is not verified; retained all prior generations"
                } else retainVerified(firstManaged, seq)
            } catch (ex: Exception) {
                lastRetentionFailure = "Checkpoint published; retention cleanup failed: ${ex.message}"
            }
        }
    }

    fun recover(): Recovery? {
        if (!dir.isDirectory) return null
        val discarded = ArrayList<Int>()
        for (file in generations()) {
            val seq = generationSequence(file.name)!!
            val digest = verifiedDigest(file)
            if (digest == null) { discarded.add(seq); continue }
            val normalized = try { normalizeBundle(readBundle(file)) } catch (_: Exception) {
                discarded.add(seq); continue
            }
            verifiedFormats[file.name] = digest
            return Recovery(normalized, seq, discarded.isNotEmpty(), discarded.toList())
        }
        return null
    }

    private fun normalizeBundle(text: String): String {
        val bundle = migrateAgentBundleJson(text)
        bundle.validateFormat()
        json.fromJson(AgentMatchState::class.java, bundle.matchJson).validateFormat()
        return json.toJson(bundle)
    }

    /** GZIP magic, not filename extension, controls decoding. Old plain archives still recover. */
    private fun readBundle(file: File): String = file.inputStream().buffered().use { input ->
        input.mark(2)
        val gzip = input.read() == 0x1f && input.read() == 0x8b
        input.reset()
        val decoded = if (gzip) GZIPInputStream(input) else input
        decoded.reader(Charsets.UTF_8).use { it.readText() }
    }

    private fun generations(): List<File> = dir.listFiles { f -> generationSequence(f.name) != null }
        ?.sortedByDescending { generationSequence(it.name)!! } ?: emptyList()

    /** Persistent activation boundary: never prune generations present before this policy. */
    private fun retentionBoundary(firstNewSequence: Int): Int {
        val policy = File(dir, "$matchId-retention-v1.policy")
        if (policy.exists()) {
            val lines = policy.readLines()
            check(lines.firstOrNull() == "unciv-checkpoint-retention-v1") { "Unknown checkpoint retention policy" }
            return lines.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 }
                ?: error("Invalid checkpoint retention boundary")
        }
        writeSidecar(policy, "unciv-checkpoint-retention-v1\n$firstNewSequence\n")
        fsyncDir()
        return firstNewSequence
    }

    private fun retainVerified(firstManaged: Int, newSequence: Int) {
        val managed = generations().filter { generationSequence(it.name)!! >= firstManaged }
        val keep = ArrayList<Int>()
        for (file in managed) {
            val digest = verifiedDigest(file) ?: continue
            if (verifiedFormats[file.name] != digest) {
                try { normalizeBundle(readBundle(file)) } catch (_: Exception) { continue }
                verifiedFormats[file.name] = digest
            }
            keep.add(generationSequence(file.name)!!)
            if (keep.size == maxRetainedGenerations) break
        }
        lastRetainedGenerations = keep.toList()
        if (keep.size < maxRetainedGenerations!! || newSequence !in keep) return
        val oldestKept = keep.last()
        // Invalid/future newer generations remain available for diagnosis; they age out only
        // after three later verified writes. Pre-policy files are immutable archive history.
        for (file in managed.filter { generationSequence(it.name)!! < oldestKept }) {
            Files.deleteIfExists(file.toPath())
            Files.deleteIfExists(File(dir, "${file.name}.sha256").toPath())
            verifiedFormats.remove(file.name)
        }
        fsyncDir()
    }

    private fun verifiedDigest(file: File): String? = try {
        val expected = File(dir, "${file.name}.sha256").readText().trim()
        if (!expected.matches(Regex("[0-9a-f]{64}"))) null
        else sha256File(file).takeIf { timingSafeEquals(expected, it) }
    } catch (_: Exception) { null }

    private fun sha256File(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    //region Internals

    private fun seqName(seq: Int) = seq.toString().padStart(6, '0')
    private fun generationName(seq: Int) = "$matchId-gen-${seqName(seq)}.unciv-agent"

    private fun generationSequence(fileName: String): Int? {
        if (!fileName.startsWith("$matchId-gen-") || !fileName.endsWith(".unciv-agent")) return null
        return fileName.removePrefix("$matchId-gen-").removeSuffix(".unciv-agent").toIntOrNull()
    }

    private fun nextSequence(): Int {
        val max = dir.listFiles { f -> generationSequence(f.name) != null }
            ?.mapNotNull { generationSequence(it.name) }?.maxOrNull() ?: 0
        return max + 1
    }

    private fun moveIntoPlace(tmp: File, final: File) {
        try {
            Files.move(tmp.toPath(), final.toPath(), StandardCopyOption.ATOMIC_MOVE)
            lastWriteUsedAtomicMove = true
        } catch (ex: Exception) {
            // AtomicMoveNotSupportedException (ExFAT) or anything else: the
            // numbered-generation scheme stays safe under plain moves.
            Files.move(tmp.toPath(), final.toPath(), StandardCopyOption.REPLACE_EXISTING)
            lastWriteUsedAtomicMove = false
        }
    }

    private fun writeSidecar(file: File, content: String) {
        val tmp = File(dir, "${file.name}.tmp")
        FileOutputStream(tmp).use {
            it.write(content.toByteArray(Charsets.UTF_8))
            it.flush()
            it.fd.sync()
        }
        moveIntoPlace(tmp, file)
    }

    private fun fsyncDir() {
        // FileChannel can open directories on the desktop platforms; RandomAccessFile cannot.
        // Callers must preserve fallbacks when a filesystem refuses directory synchronization.
        directorySync(dir)
    }

    private fun timingSafeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    //endregion
}
