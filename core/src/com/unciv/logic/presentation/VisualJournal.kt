package com.unciv.logic.presentation

import java.util.ArrayDeque
import java.util.UUID

/** One local viewer's already-permitted events. Never attach this object to a serialized model. */
class VisualJournal(
    val gameId: String,
    val timelineId: String = UUID.randomUUID().toString(),
    capacity: Int = MAX_EVENTS
) {
    companion object { const val MAX_EVENTS = 256 }
    private val capacity = capacity.coerceIn(1, MAX_EVENTS)
    private val events = ArrayDeque<VisualEvent>()
    private var view: VisualViewKey? = null
    private var throughSequence = 0L
    private var droppedThroughSequence = 0L

    @Synchronized
    fun reset(view: VisualViewKey) {
        events.clear()
        this.view = view
        // Keep sequence monotonic within the session, but make reconciliation across resets explicit.
        droppedThroughSequence = throughSequence
    }

    /** Entire input is checked before insertion. Replays do not append or advance the watermark. */
    @Synchronized
    fun appendCommitted(events: List<VisualEvent>) {
        if (events.isEmpty()) return
        val current = view ?: return
        if (events.any { it.view != current }) return // a completed old-view capture may arrive after reset
        require(events.all { it.id.gameId == gameId && it.id.timelineId == timelineId })
        require(events.zipWithNext().all { (a, b) -> a.id.sequence < b.id.sequence })
        require(events.all { it.id.sequence > 0L })
        for (event in events) {
            if (event.id.sequence <= throughSequence) continue
            if (event.id.sequence > throughSequence + 1L)
                droppedThroughSequence = maxOf(droppedThroughSequence, event.id.sequence - 1L)
            this.events.addLast(event)
            throughSequence = event.id.sequence
            while (this.events.size > capacity)
                droppedThroughSequence = maxOf(droppedThroughSequence, this.events.removeFirst().id.sequence)
        }
    }

    @Synchronized
    fun readAfter(sequence: Long, view: VisualViewKey): VisualBatch {
        if (view != this.view) return VisualBatch(view, emptyList(), 0L, 0L)
        return VisualBatch(view, events.filter { it.id.sequence > sequence }, throughSequence, droppedThroughSequence)
    }

    @Synchronized
    internal fun nextSequence(view: VisualViewKey): Long? =
        if (this.view == view && throughSequence < Long.MAX_VALUE) throughSequence + 1L else null
}
