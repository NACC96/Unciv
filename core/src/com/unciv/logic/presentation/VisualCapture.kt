package com.unciv.logic.presentation

import com.unciv.logic.GameInfo
import com.unciv.logic.battle.CityCombatant
import com.unciv.logic.battle.ICombatant
import com.unciv.logic.city.City
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.utils.Log
import java.util.TreeMap

/**
 * The only opt-in boundary. V2 implements this using GameView and event-time visibility rules.
 * Every result must be a permitted value snapshot; null means unknown/hidden, never "infer later".
 * Methods run synchronously at the hook, including for removed entities whose tile is still known
 * to the engine. Implementations must not retain any engine argument or mutable view.
 */
interface VisualProjection {
    fun unit(game: GameInfo, view: VisualViewKey, unit: MapUnit, atTile: Tile): VisualUnitSample?
    fun combatant(game: GameInfo, view: VisualViewKey, combatant: ICombatant): VisualCombatantSample?
    fun city(game: GameInfo, view: VisualViewKey, city: City): VisualCitySample?
    fun improvement(game: GameInfo, view: VisualViewKey, tile: Tile): VisualImprovementSample?
    fun position(game: GameInfo, view: VisualViewKey, tile: Tile): VisualPosition?
}

/** No default projector or global registration: ordinary/headless games remain completely disabled. */
object VisualCapture {
    class Session(val view: VisualViewKey, val projector: VisualProjection, val journal: VisualJournal) {
        private var claimed = false
        @Synchronized internal fun claim(): Boolean {
            if (claimed) return false
            claimed = true
            return true
        }
        @Synchronized internal fun release() { claimed = false }
    }

    /** A host must explicitly supply a privacy projector before enabling ordinary-game capture. */
    fun install(game: GameInfo, session: Session?) {
        game.visualCaptureSession = session
    }

    fun begin(game: GameInfo, view: VisualViewKey): CaptureScope =
        begin(game, game.visualCaptureSession?.takeIf { it.view == view })

    /** Coordinator candidates receive an explicit session, never one copied through game serialization. */
    fun begin(game: GameInfo, session: Session?, provenance: VisualProvenance = VisualProvenance()): CaptureScope {
        if (session == null || game.visualCaptureSink != null) return CaptureScope.disabled()
        return try {
            if (session.journal.gameId != game.gameId || !session.claim()) return CaptureScope.disabled()
            val next = session.journal.nextSequence(session.view)
            if (next == null) {
                session.release()
                return CaptureScope.disabled()
            }
            CaptureScope(game, session, provenance, next).also { game.visualCaptureSink = it }
        } catch (_: Throwable) {
            session.release()
            diagnostic()
            CaptureScope.disabled()
        }
    }

    /** Nested engine helpers share the outer command/turn capture; only its successful return publishes. */
    internal inline fun <T> mutate(game: GameInfo?, action: () -> T): T {
        if (game == null || game.visualCaptureSink != null || game.visualCaptureSession == null) return action()
        val scope = begin(game, game.visualCaptureSession)
        try {
            val result = action()
            scope.publishCommitted()
            return result
        } finally {
            scope.close()
        }
    }

    internal fun diagnostic() {
        // Constant local diagnostic only: exception text, entity names, coordinates and private facts are excluded.
        try { Log.debug("Transient visual capture unavailable; committed state remains authoritative") } catch (_: Throwable) { }
    }
}

/** Thread-confined, bounded and temporary. It owns only projected values between hooks. */
class CaptureScope internal constructor(
    private val game: GameInfo?,
    private val session: VisualCapture.Session?,
    private val provenance: VisualProvenance,
    private var nextSequence: Long
) : AutoCloseable {
    companion object {
        internal fun disabled() = CaptureScope(null, null, VisualProvenance(), 0L)
    }
    private val thread = Thread.currentThread()
    private val events = TreeMap<Long, VisualEvent>()
    private var finished: List<VisualEvent>? = null
    private var closed = false
    private var failed = false
    private var published = false
    private var movementKind: VisualMoveKind? = null
    private val removed = LinkedHashSet<String>()
    private val view get() = session!!.view

    private fun <T> safely(block: () -> T): T? {
        if (session == null || closed || failed || finished != null) return null
        if (Thread.currentThread() !== thread) {
            VisualCapture.diagnostic()
            return null
        }
        return try { block() } catch (_: Throwable) {
            failed = true
            events.clear()
            VisualCapture.diagnostic()
            null
        }
    }

    private fun reserveId(): VisualEventId? = safely {
        if (nextSequence == Long.MAX_VALUE) {
            failed = true
            events.clear()
            return@safely null
        }
        VisualEventId(session!!.journal.gameId, session.journal.timelineId, nextSequence++)
    }

    private fun emit(id: VisualEventId? = null, make: (VisualEventId) -> VisualEvent) {
        safely {
            val identity = id ?: reserveId() ?: return@safely
            val event = make(identity)
            events[identity.sequence] = event
            while (events.size > VisualJournal.MAX_EVENTS) events.pollFirstEntry()
        }
    }

    fun unit(unit: MapUnit, atTile: Tile = unit.currentTile): VisualUnitSample? =
        safely { session!!.projector.unit(game!!, view, unit, atTile) }
    fun city(city: City): VisualCitySample? = safely { session!!.projector.city(game!!, view, city) }
    fun improvement(tile: Tile): VisualImprovementSample? = safely { session!!.projector.improvement(game!!, view, tile) }
    private fun combatant(combatant: ICombatant?): VisualCombatantSample? = combatant?.let {
        safely { session!!.projector.combatant(game!!, view, it) }
    }
    private fun position(tile: Tile): VisualPosition? = safely { session!!.projector.position(game!!, view, tile) }

    /** Used around swaps to identify both ordinary movement calls without emitting their temporary tile shuffles. */
    fun <T> withMovementKind(kind: VisualMoveKind, action: () -> T): T {
        val previous = movementKind
        movementKind = kind
        try { return action() } finally { movementKind = previous }
    }

    /** Records projected transit samples, then commits them only when an enterable tile was actually reached. */
    inner class MoveTrace internal constructor(first: VisualUnitSample?, private val kind: VisualMoveKind) {
        private var last = first
        private val pending = ArrayList<VisualUnitSample?>()

        fun step(unit: MapUnit, atTile: Tile, reached: Boolean) {
            safely {
                pending.add(this@CaptureScope.unit(unit, atTile))
                // Avoid retaining arbitrarily long transit stretches. A missing sample splits the visible path.
                if (pending.size > VisualJournal.MAX_EVENTS) { pending.clear(); pending.add(null) }
                if (!reached) return@safely
                val samples = ArrayList<VisualUnitSample?>(pending.size + 1)
                samples.add(last)
                samples.addAll(pending)
                emitSegments(samples, kind)
                last = pending.lastOrNull()
                pending.clear()
            }
        }
    }

    fun startMove(unit: MapUnit, kind: VisualMoveKind = VisualMoveKind.WALK): MoveTrace? =
        safely { MoveTrace(this.unit(unit), movementKind ?: kind) }

    private fun emitSegments(samples: List<VisualUnitSample?>, kind: VisualMoveKind) {
        var descriptor: VisualUnitDescriptor? = null
        val path = ArrayList<VisualPosition>()
        fun flush() {
            val unit = descriptor ?: return
            if (path.isNotEmpty()) emit { VisualEvent.Move(it, view, provenance, unit, path, kind) }
            path.clear()
            descriptor = null
        }
        for (sample in samples) {
            if (sample == null) { flush(); continue }
            if (descriptor != null && descriptor!!.stableId != sample.unit.stableId) flush()
            descriptor = sample.unit
            if (path.lastOrNull() != sample.position) path.add(sample.position)
            if (sample.removed) {
                flush()
                removeUnit(sample)
            }
        }
        flush()
    }

    /** Displacement never fabricates a traversed line, even when both endpoints were visible. */
    fun displaced(before: VisualUnitSample?, unit: MapUnit, kind: VisualMoveKind) {
        safely {
            val after = this.unit(unit) ?: return@safely
            if (after.removed) { removeUnit(after); return@safely }
            if (before?.position == after.position) return@safely
            emit { VisualEvent.Move(it, view, provenance, after.unit, listOf(after.position), kind, before?.position) }
        }
    }

    fun removeUnit(sample: VisualUnitSample?) {
        if (sample == null || !sample.removed) return
        safely {
            if (!removed.add(sample.unit.stableId)) return@safely
            if (removed.size > VisualJournal.MAX_EVENTS) removed.remove(removed.first())
            emit { VisualEvent.RemoveUnit(it, view, provenance, sample.unit, sample.position) }
        }
    }

    class AttackBefore internal constructor(
        val attacker: VisualCombatantSample?, val defender: VisualCombatantSample?,
        val target: VisualPosition?, val kind: VisualAttackKind,
        internal val eventId: VisualEventId?
    )

    fun beforeAttack(attacker: ICombatant, defender: ICombatant?, target: Tile, kind: VisualAttackKind): AttackBefore? =
        safely {
            val source = combatant(attacker)
            val defending = combatant(defender)
            val destination = position(target)
            // Reserve presentation order, not outcome: after-resolution facts are filled in later.
            // This keeps the strike before post-battle advance/withdrawal events without speculative visuals.
            val id = if (source != null || destination != null || defending != null) reserveId() else null
            AttackBefore(source, defending, destination, if (source == null) VisualAttackKind.UNKNOWN else kind, id)
        }

    fun afterAttack(
        before: AttackBefore?, attacker: ICombatant, defender: ICombatant?, target: Tile,
        outcome: VisualAttackOutcome = VisualAttackOutcome.RESOLVED,
        damageToAttacker: Int? = null, damageToDefender: Int? = null
    ) {
        if (before == null) return
        safely {
            val afterAttacker = combatant(attacker)
            val afterDefender = combatant(defender)
            val targetPosition = before.target ?: position(target)
            if (before.attacker == null && targetPosition == null) return@safely
            fun permittedDamage(a: VisualCombatantSample?, b: VisualCombatantSample?, reported: Int?): Int? {
                if (a == null || b == null || a.descriptor.stableId != b.descriptor.stableId) return null
                if (a.descriptor.health == null || b.descriptor.health == null) return null
                return reported?.coerceAtLeast(0) ?: (a.descriptor.health!! - b.descriptor.health!!).coerceAtLeast(0)
            }
            // An unseen withdrawal/capture/interception is not disclosed by an outcome tag.
            val permittedOutcome = if (before.defender != null && afterDefender != null ||
                outcome == VisualAttackOutcome.INTERCEPTED && before.attacker != null && afterAttacker != null)
                outcome else VisualAttackOutcome.RESOLVED
            emit(before.eventId) { VisualEvent.Attack(it, view, provenance, before.attacker?.position, targetPosition,
                before.attacker?.descriptor, before.defender?.descriptor ?: afterDefender?.descriptor,
                permittedDamage(before.attacker, afterAttacker, damageToAttacker),
                permittedDamage(before.defender, afterDefender, damageToDefender), before.kind, permittedOutcome) }
            for (sample in listOfNotNull(afterAttacker, afterDefender)) {
                val unit = sample.descriptor as? VisualUnitDescriptor ?: continue
                removeUnit(VisualUnitSample(unit, sample.position, sample.removed))
            }
            if (defender is CityCombatant) {
                val prior = before.defender?.descriptor as? VisualCityDescriptor
                cityChanged(prior, city(defender.city))
            }
        }
    }

    fun cityFounded(city: City) {
        safely {
            val sample = this.city(city) ?: return@safely
            if (!sample.removed) emit { VisualEvent.FoundCity(it, view, provenance, sample.city, sample.position) }
        }
    }

    fun cityChanged(before: VisualCityDescriptor?, after: VisualCitySample?) {
        if (after == null) return
        safely {
            if (before != null && (before.ownerCivId != after.city.ownerCivId || after.removed))
                emit { VisualEvent.CaptureCity(it, view, provenance, before, after.city, after.position, after.removed) }
        }
    }

    fun improvementChanged(before: VisualImprovementSample?, tile: Tile) {
        safely {
            val after = improvement(tile) ?: return@safely
            if (before != after) emit { VisualEvent.ChangeImprovement(it, view, provenance, before, after) }
        }
    }

    /** Projection is already frozen at hooks; finish occurs before durable serialization/commit. */
    fun finish(committedRevision: Int? = null): List<VisualEvent> {
        if (closed || failed || session == null || Thread.currentThread() !== thread) return emptyList()
        finished?.let { return it }
        return try {
            val finalProvenance = if (committedRevision == null) provenance
                else provenance.copy(committedRevision = committedRevision)
            immutableVisualList(events.values.map { it.withProvenance(finalProvenance) })
                .also { finished = it; events.clear() }
        } catch (_: Throwable) {
            failed = true
            events.clear()
            VisualCapture.diagnostic()
            emptyList()
        }
    }

    /** Called only after the engine return or durable commit, with failures isolated from gameplay. */
    fun publishCommitted() {
        if (published || closed || session == null || Thread.currentThread() !== thread) return
        try {
            val result = finish()
            session.journal.appendCommitted(result)
            published = true
        } catch (_: Throwable) { VisualCapture.diagnostic() }
    }

    override fun close() {
        if (closed) return
        if (Thread.currentThread() !== thread) { VisualCapture.diagnostic(); return }
        try {
            if (game?.visualCaptureSink === this) game.visualCaptureSink = null
            events.clear()
            finished = null
            removed.clear()
        } finally {
            closed = true
            session?.release()
        }
    }
}
