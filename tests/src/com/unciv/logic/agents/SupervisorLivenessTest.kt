package com.unciv.logic.agents

import com.unciv.app.desktop.agents.DesktopAgentMatch
import com.unciv.app.desktop.agents.SupervisorProcess
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.nio.file.Files
import java.io.File

class SupervisorLivenessTest {
    @Test
    fun `elapsed warning never converts a live provider into failure`() {
        val node = DesktopAgentMatch.findNode()
        assumeNotNull(node)
        val root = Files.createTempDirectory("unciv-supervisor-liveness").toFile()
        try {
            val script = File(root, "daemon.mjs").apply { writeText("""
                let buffer = '';
                process.stdin.setEncoding('utf8');
                process.stdin.on('data', chunk => {
                  buffer += chunk;
                  const lines = buffer.split('\n'); buffer = lines.pop();
                  for (const line of lines) {
                    const c = JSON.parse(line);
                    if (c.op === 'shutdown') process.exit(0);
                    if (c.op === 'run_turn') setTimeout(() => {
                      process.stdout.write(JSON.stringify({kind:'turn_event',event:'run_completed',payload:{status:'completed_turn',turnEnded:true,revision:5}})+'\n');
                    }, 350);
                  }
                });
                process.stdout.write(JSON.stringify({kind:'ready'})+'\n');
            """.trimIndent()) }
            val supervisor = SupervisorProcess(node!!, script, turnTimeoutMinutes = 0)
            try {
                val result = supervisor.runTurn(mapOf("op" to "run_turn"))
                assertEquals("completed_turn", result.status)
                assertEquals(5, result.revision)
            } finally { supervisor.shutdown() }
        } finally { root.deleteRecursively() }
    }
}
