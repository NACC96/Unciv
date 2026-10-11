package com.unciv.logic.agents

/** Index entry only; full provider content is loaded from the journal on selection. */
data class AgentActivityEntry(
    val id: String,
    val civId: String,
    val turnSerial: Int,
    val kind: String,
    val title: String,
    val summary: String,
    val timestamp: String,
    val status: String = ""
)
