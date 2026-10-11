package com.unciv.app.desktop.agents

import com.unciv.logic.agents.AgentActivityEntry
import com.unciv.logic.agents.AgentJson
import java.io.File
import java.io.RandomAccessFile

/** Incremental reader: incomplete writes wait for a newline; full bodies remain on disk. */
class AgentActivityJournal(val directory: File) {
    private data class Run(val file: File, val civId: String, val turnSerial: Int, var offset: Long = 0)
    private data class Record(val entry: AgentActivityEntry, val file: File, val offset: Long)
    private val runs = LinkedHashMap<String, Run>()
    private val records = LinkedHashMap<String, Record>()
    @Volatile private var snapshot: List<AgentActivityEntry> = emptyList()

    fun entries(): List<AgentActivityEntry> = snapshot

    @Synchronized
    fun refresh() {
        for (dir in directory.listFiles().orEmpty().filter { it.isDirectory }) {
            if (dir.name in runs) continue
            val meta = try { AgentJson.parse(File(dir, "meta.json").readText()) as? Map<*, *> } catch (_: Exception) { null } ?: continue
            val file = File(dir, "activity.jsonl")
            if (!file.isFile) continue
            runs[dir.name] = Run(file, meta["civId"] as? String ?: "Agent", (meta["turnSerial"] as? Number)?.toInt() ?: 0)
        }
        var changed = false
        for ((runId, run) in runs) {
            if (run.file.length() <= run.offset) continue
            try {
                RandomAccessFile(run.file, "r").use { stream ->
                    val length = stream.length()
                    stream.seek(length - 1)
                    val completeTail = stream.read() == 10
                    stream.seek(run.offset)
                    while (stream.filePointer < length) {
                        val start = stream.filePointer
                        val line = stream.readLine() ?: break
                        if (stream.filePointer > length || (stream.filePointer >= length && !completeTail)) break
                        run.offset = stream.filePointer
                        val parsed = try { AgentJson.parse(decode(line)) as? Map<*, *> } catch (_: Exception) { null } ?: continue
                        val id = "$runId/${parsed["id"]}"
                        val text = parsed["text"] as? String ?: ""
                        val entry = AgentActivityEntry(id, run.civId, run.turnSerial,
                            parsed["kind"] as? String ?: "system", parsed["title"] as? String ?: "Activity",
                            (parsed["summary"] as? String ?: text).replace('\n', ' ').take(140),
                            parsed["timestamp"] as? String ?: "", parsed["status"] as? String ?: "")
                        records[id] = Record(entry, run.file, start)
                        changed = true
                    }
                }
            } catch (_: Exception) { /* A concurrent write is retried on the next refresh. */ }
        }
        if (changed) snapshot = records.values.map { it.entry }.sortedWith(compareBy({ it.timestamp }, { it.id }))
    }

    @Synchronized
    fun details(id: String): String {
        val record = records[id] ?: return "Waiting for activity…"
        return try {
            RandomAccessFile(record.file, "r").use { stream ->
                stream.seek(record.offset)
                val parsed = AgentJson.parse(decode(stream.readLine() ?: "")) as? Map<*, *>
                parsed?.get("text") as? String ?: ""
            }
        } catch (_: Exception) { "Activity is being written; try again shortly." }
    }

    private fun decode(line: String) = String(line.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
}
