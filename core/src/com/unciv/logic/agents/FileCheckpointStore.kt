package com.unciv.logic.agents

import com.unciv.json.json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

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
    val matchId: String
) : CheckpointWriter {

    private val json = json()

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
    }

    override fun write(gameJson: String, matchJson: String) {
        writeBundle(json.toJson(AgentBundle(gameJson, matchJson)))
    }

    /** Publishes one pre-built bundle generation; exposed for tests. */
    fun writeBundle(bundleJson: String) {
        dir.mkdirs()
        val bytes = bundleJson.toByteArray(Charsets.UTF_8)
        val digest = sha256Hex(bytes)
        val seq = nextSequence()
        val tmp = File(dir, "$matchId-gen-${seqName(seq)}.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
        val final = File(dir, generationName(seq))
        moveIntoPlace(tmp, final)
        writeSidecar(File(dir, "${generationName(seq)}.sha256"), digest)
        fsyncDir()
    }

    fun recover(): Recovery? {
        if (!dir.isDirectory) return null
        val gens = dir.listFiles { f -> generationSequence(f.name) != null }
            ?.sortedByDescending { generationSequence(it.name)!! }
            ?: return null
        val discarded = ArrayList<Int>()
        for (file in gens) {
            val seq = generationSequence(file.name)!!
            val bytes = try { file.readBytes() } catch (ex: Exception) {
                discarded.add(seq); continue
            }
            val expected = try {
                File(dir, "${file.name}.sha256").readText().trim()
            } catch (ex: Exception) {
                discarded.add(seq); continue // no checksum, no trust
            }
            if (!timingSafeEquals(expected, sha256Hex(bytes))) {
                discarded.add(seq); continue
            }
            val bundleJson = bytes.toString(Charsets.UTF_8)
            try {
                val bundle = json.fromJson(AgentBundle::class.java, bundleJson)
                bundle.validateFormat()
                // The match envelope must parse too; deep game validation
                // happens on MatchCoordinator.loadFromJson.
                val match = json.fromJson(AgentMatchState::class.java, bundle.matchJson)
                match.validateFormat()
            } catch (ex: Exception) {
                discarded.add(seq); continue
            }
            return Recovery(bundleJson, seq, discarded.isNotEmpty(), discarded.toList())
        }
        return null
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
        // Best effort: not all filesystems allow opening directories.
        try {
            java.io.RandomAccessFile(dir, "r").use { it.fd.sync() }
        } catch (ex: Exception) {
            // ignored
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun timingSafeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    //endregion
}
