package com.unciv.logic.presentation

import java.util.Collections

/** A local presentation authority. Epoch changes on load/view changes, never per frame. */
data class VisualViewKey(
    val viewerCivId: String,
    val spectatorMode: Boolean,
    val epoch: Long,
    val revealMap: Boolean = false,
    val revealUnits: Boolean = false
)
data class VisualEventId(val gameId: String, val timelineId: String, val sequence: Long)
data class VisualProvenance(val committedRevision: Int? = null, val actionId: String? = null)
data class VisualPosition(val x: Int, val y: Int)
/** Packed RGBA values, deliberately independent of graphics objects and mutable color instances. */
data class VisualOwnerColors(val innerRgba: Int, val outerRgba: Int)

sealed interface VisualDescriptor {
    val stableId: String
    val artName: String
    val ownerCivId: String?
    val colors: VisualOwnerColors?
    val health: Int?
}

data class VisualUnitDescriptor(
    val unitId: Int,
    override val artName: String,
    override val ownerCivId: String? = null,
    override val colors: VisualOwnerColors? = null,
    override val health: Int? = null
) : VisualDescriptor {
    override val stableId: String get() = "unit:$unitId"
}

data class VisualCityDescriptor(
    val cityId: String,
    override val artName: String,
    override val ownerCivId: String? = null,
    override val colors: VisualOwnerColors? = null,
    override val health: Int? = null,
    val name: String? = null
) : VisualDescriptor {
    override val stableId: String get() = "city:$cityId"
}

/** Projector-produced samples contain only facts permitted at the instant of the engine hook. */
data class VisualUnitSample(val unit: VisualUnitDescriptor, val position: VisualPosition, val removed: Boolean = false)
data class VisualCombatantSample(val descriptor: VisualDescriptor, val position: VisualPosition, val removed: Boolean = false)
data class VisualCitySample(val city: VisualCityDescriptor, val position: VisualPosition, val removed: Boolean = false)
data class VisualImprovementSample(
    val position: VisualPosition,
    val improvement: String?,
    val road: String? = null,
    val pillaged: Boolean? = null,
    val roadPillaged: Boolean? = null
)

enum class VisualMoveKind { WALK, SWAP, RETREAT, PARADROP, TELEPORT, AIR_REBASE }
enum class VisualAttackKind { UNKNOWN, MELEE, RANGED, AIR, CITY, NUCLEAR }
enum class VisualAttackOutcome { RESOLVED, WITHDRAWN, CAPTURED, INTERCEPTED }

/** Already projected immutable facts. These are never part of a save, action receipt or provider DTO. */
sealed class VisualEvent {
    abstract val id: VisualEventId
    abstract val view: VisualViewKey
    abstract val provenance: VisualProvenance

    class Move(
        override val id: VisualEventId,
        override val view: VisualViewKey,
        override val provenance: VisualProvenance,
        val unit: VisualUnitDescriptor,
        path: List<VisualPosition>,
        val kind: VisualMoveKind = VisualMoveKind.WALK,
        /** Only non-walking relocation uses this permitted endpoint. Never interpolate from it. */
        val displacementOrigin: VisualPosition? = null
    ) : VisualEvent() {
        val path: List<VisualPosition> = immutableVisualList(path)
    }

    data class Attack(
        override val id: VisualEventId,
        override val view: VisualViewKey,
        override val provenance: VisualProvenance,
        val source: VisualPosition?,
        val target: VisualPosition?,
        val attacker: VisualDescriptor?,
        val defender: VisualDescriptor?,
        val damageToAttacker: Int?,
        val damageToDefender: Int?,
        val kind: VisualAttackKind,
        val outcome: VisualAttackOutcome = VisualAttackOutcome.RESOLVED
    ) : VisualEvent()

    data class RemoveUnit(
        override val id: VisualEventId,
        override val view: VisualViewKey,
        override val provenance: VisualProvenance,
        val unit: VisualUnitDescriptor,
        val position: VisualPosition
    ) : VisualEvent()

    data class FoundCity(
        override val id: VisualEventId,
        override val view: VisualViewKey,
        override val provenance: VisualProvenance,
        val city: VisualCityDescriptor,
        val position: VisualPosition
    ) : VisualEvent()

    data class ChangeImprovement(
        override val id: VisualEventId,
        override val view: VisualViewKey,
        override val provenance: VisualProvenance,
        val before: VisualImprovementSample?,
        val after: VisualImprovementSample
    ) : VisualEvent()

    data class CaptureCity(
        override val id: VisualEventId,
        override val view: VisualViewKey,
        override val provenance: VisualProvenance,
        val before: VisualCityDescriptor?,
        val after: VisualCityDescriptor,
        val position: VisualPosition,
        val destroyed: Boolean = false
    ) : VisualEvent()

    /**
     * One nuclear weapon's detonation or interception, holding the hits, removals and city losses it caused
     * in recorded order, so the screen presents one moment instead of a queue of separate strikes.
     */
    class NuclearStrike(
        override val id: VisualEventId,
        override val view: VisualViewKey,
        override val provenance: VisualProvenance,
        val source: VisualPosition?,
        /** Ground zero. Null when it was out of sight or the weapon never detonated. */
        val target: VisualPosition?,
        val weapon: VisualDescriptor?,
        val blastRadius: Int,
        val intercepted: Boolean,
        parts: List<VisualEvent>
    ) : VisualEvent() {
        /** Part ids are local to this strike; only the strike itself takes a place in the journal. */
        val parts: List<VisualEvent> = immutableVisualList(parts)
    }
}

class VisualBatch(
    val view: VisualViewKey,
    events: List<VisualEvent>,
    val throughSequence: Long,
    val droppedThroughSequence: Long
) {
    val events: List<VisualEvent> = immutableVisualList(events)
}

internal fun <T> immutableVisualList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))

/** Final committed revision can differ from the opening revision during a multi-civ turn advance. */
internal fun VisualEvent.withProvenance(provenance: VisualProvenance): VisualEvent = when (this) {
    is VisualEvent.Move -> VisualEvent.Move(id, view, provenance, unit, path, kind, displacementOrigin)
    is VisualEvent.Attack -> copy(provenance = provenance)
    is VisualEvent.RemoveUnit -> copy(provenance = provenance)
    is VisualEvent.FoundCity -> copy(provenance = provenance)
    is VisualEvent.ChangeImprovement -> copy(provenance = provenance)
    is VisualEvent.CaptureCity -> copy(provenance = provenance)
    is VisualEvent.NuclearStrike -> VisualEvent.NuclearStrike(id, view, provenance, source, target, weapon,
        blastRadius, intercepted, parts.map { it.withProvenance(provenance) })
}

/** Only the ordinary committed-turn handoff may rebind already-permitted facts to a new screen epoch. */
internal fun VisualEvent.withView(view: VisualViewKey): VisualEvent = when (this) {
    is VisualEvent.Move -> VisualEvent.Move(id, view, provenance, unit, path, kind, displacementOrigin)
    is VisualEvent.Attack -> copy(view = view)
    is VisualEvent.RemoveUnit -> copy(view = view)
    is VisualEvent.FoundCity -> copy(view = view)
    is VisualEvent.ChangeImprovement -> copy(view = view)
    is VisualEvent.CaptureCity -> copy(view = view)
    is VisualEvent.NuclearStrike -> VisualEvent.NuclearStrike(id, view, provenance, source, target, weapon,
        blastRadius, intercepted, parts.map { it.withView(view) })
}
