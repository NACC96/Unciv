package com.unciv.logic.agents

import com.unciv.app.desktop.agents.AgentActivityJournal
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.File

class AgentActivityJournalTest {
    @Test fun `run duration entry is replaced by the final turn outcome after provider exit`() {
        val root = Files.createTempDirectory("unciv-run-outcome").toFile()
        try {
            val run = File(root, "run-a").apply { mkdirs() }
            File(run, "meta.json").writeText("{\"civId\":\"Rome\",\"turnSerial\":2}")
            val file = File(run, "activity.jsonl")
            fun append(title: String, status: String) = file.appendText(AgentJson.stringify(mapOf(
                "id" to "run-end", "kind" to "run", "title" to title, "status" to status,
                "text" to title, "durationMs" to 83000, "timestamp" to "2026-10-12T01:00:00Z")) + "\n")
            append("Provider exited in 01:23", "exited")
            val journal = AgentActivityJournal(root); journal.refresh()
            append("Provider incomplete in 01:23", "incomplete_turn"); journal.refresh()
            val entry = journal.entries().single()
            assertEquals("run", entry.kind)
            assertEquals("incomplete_turn", entry.status)
            assertEquals("Provider incomplete in 01:23", entry.title)
            assertEquals(entry.title, journal.details(entry.id))
            val reopened = AgentActivityJournal(root); reopened.refresh()
            assertEquals(listOf(entry), reopened.entries())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `journal keeps readable summary and call status separate from raw details`() {
        val root = Files.createTempDirectory("unciv-activity-summary").toFile()
        try {
            val run = File(root, "run-a").apply { mkdirs() }
            File(run, "meta.json").writeText("{\"civId\":\"Rome\",\"turnSerial\":1}")
            File(run, "activity.jsonl").writeText(AgentJson.stringify(mapOf("id" to "t", "kind" to "tool", "title" to "Read game state", "summary" to "2 units · 1 city", "status" to "completed", "text" to "Arguments and full raw result", "timestamp" to "2026-10-11T01:00:00Z")) + "\n")
            val journal = AgentActivityJournal(root)
            journal.refresh()
            val entry = journal.entries().single()
            assertEquals("2 units · 1 city", entry.summary)
            assertEquals("completed", entry.status)
            assertEquals("Arguments and full raw result", journal.details(entry.id))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `live journal waits for complete writes and keeps full unicode payload across reopen`() {
        val root = Files.createTempDirectory("unciv-activity-test").toFile()
        try {
            val run = File(root, "run-a").apply { mkdirs() }
            File(run, "meta.json").writeText(AgentJson.stringify(mapOf("civId" to "Rome", "turnSerial" to 2)))
            val log = File(run, "activity.jsonl")
            val text = "Thoughts → résultat 日本語\n".repeat(3000)
            val line = AgentJson.stringify(mapOf("id" to "tool-1", "kind" to "tool", "title" to "game.observe · started", "text" to text, "timestamp" to "2026-10-11T01:00:00Z"))
            log.writeText(line.take(line.length / 2))
            val journal = AgentActivityJournal(root)
            journal.refresh()
            assertTrue(journal.entries().isEmpty())
            log.appendText(line.drop(line.length / 2) + "\n")
            journal.refresh()
            assertEquals(1, journal.entries().size)
            assertEquals("Rome", journal.entries().single().civId)
            assertEquals(text, journal.details(journal.entries().single().id))
            log.appendText(AgentJson.stringify(mapOf("id" to "tool-1", "kind" to "tool", "title" to "game.observe · completed", "text" to "full result", "timestamp" to "2026-10-11T01:00:01Z")) + "\n")
            journal.refresh()
            assertEquals(1, journal.entries().size)
            assertEquals("full result", journal.details(journal.entries().single().id))
            val reopened = AgentActivityJournal(root)
            reopened.refresh()
            assertEquals(journal.entries(), reopened.entries())
            assertEquals("full result", reopened.details(reopened.entries().single().id))
            val other = File(root, "run-b").apply { mkdirs() }
            File(other, "meta.json").writeText("{\"civId\":\"France\",\"turnSerial\":3}")
            File(other, "activity.jsonl").writeText(line + "\n")
            journal.refresh()
            assertEquals(setOf("Rome", "France"), journal.entries().map { it.civId }.toSet())
            assertEquals(2, journal.entries().size)
        } finally { root.deleteRecursively() }
    }
}
