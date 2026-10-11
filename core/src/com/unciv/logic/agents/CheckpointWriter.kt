package com.unciv.logic.agents

/**
 * Durable-write boundary for coordinator commits (AI_AGENT_PLAN.md §10.1).
 * The coordinator serializes a complete new generation and calls [write]
 * BEFORE acknowledging success; if [write] throws, the candidate is
 * discarded and no success receipt escapes. Production implementation
 * (temp file + flush + checksum + atomic move, ExFAT fallback to immutable
 * generations) arrives with the desktop checkpoint store; tests use
 * [InMemoryCheckpointWriter], including its failure injection.
 */
interface CheckpointWriter {
    @Throws(Exception::class)
    fun write(gameJson: String, matchJson: String)
}

/** Test/embedded writer: keeps the latest generation in memory. */
class InMemoryCheckpointWriter : CheckpointWriter {
    var lastGameJson: String? = null
        private set
    var lastMatchJson: String? = null
        private set
    var writeCount: Int = 0
        private set
    /** When true, the next [write] throws instead of storing. */
    var failNextWrite: Boolean = false

    override fun write(gameJson: String, matchJson: String) {
        if (failNextWrite) {
            failNextWrite = false
            throw java.io.IOException("injected checkpoint failure")
        }
        lastGameJson = gameJson
        lastMatchJson = matchJson
        writeCount++
    }
}
