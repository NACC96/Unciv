package com.unciv.ui.components

import com.unciv.logic.agents.DiplomaticMessage
import com.unciv.logic.agents.orderedDiplomaticMessages
import org.junit.Assert.*
import org.junit.Test

class DiplomaticMessageOrderingTest {
    private fun message(id: String, turn: Int?, revision: Int? = null) = DiplomaticMessage(messageId = id).apply {
        acknowledgedTurnSerial = turn; acknowledgedRevision = revision
    }
    @Test fun `reading order uses acknowledgement turn and revision with unread last`() {
        val messages = listOf(message("unread oldest", null), message("read later", 4, 90),
            message("read first", 3, 88), message("read second", 3, 89), message("unread latest", null))
        assertEquals(listOf("read first", "read second", "read later", "unread oldest", "unread latest"),
            orderedDiplomaticMessages(messages).map { it.messageId })
    }
    @Test fun `legacy acknowledgement fallback and revision ties preserve original order`() {
        val messages = listOf(message("legacy first", 2), message("legacy second", 2),
            message("revision first", 3, 12), message("revision second", 3, 12), message("pending", null))
        assertEquals(messages, orderedDiplomaticMessages(messages))
    }
}
