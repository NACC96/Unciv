package com.unciv.ui.screens.worldscreen.presentation

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.math.Vector2
import com.unciv.logic.map.HexMath
import com.unciv.logic.presentation.*
import com.unciv.models.metadata.BaseRuleset
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onClick
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.images.atlas.*
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.view.VisualEventProjector
import java.util.ArrayDeque
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin

/** GL-owned, bounded presentation of already-committed facts. No action, engine or save ownership. */
class WorldVisualTimeline(private val world: WorldScreen) : AutoCloseable {
    enum class Mode { Normal, Fast, Reduced, Off;
        companion object { fun parse(value: String) = entries.firstOrNull { it.name == value } ?: Normal }
    }
    companion object { const val MAX_QUEUED_EVENTS = 64 }
    private val map get() = world.mapHolder
    private val queue = ArrayDeque<VisualEvent>()
    private var active: Playback? = null
    private var idle: Ghost? = null
    private var idleKey: Pair<Int, UnitVisualState>? = null
    private var idleElapsed = 0f
    private var cache: AtlasClipCache? = null
    private var invalidation: AutoCloseable? = null
    private var view = world.currentVisualViewKey()
    private var resetGeneration = world.visualResetGeneration
    private var timelineId: String? = null
    private var throughSequence = 0L
    private var mode = Mode.parse(world.game.settings.unitAnimationMode)
    private var selectedUnitSet: String? = null
    private var manualInputSerial = map.visualManualInputSerial
    private var followCooldown = 0f
    private var disposed = false
    val pendingCount: Int get() = queue.size + if (active == null) 0 else 1

    private val skipButton = "Skip to live".toTextButton().apply {
        isVisible = false
        onClick { world.resetVisualPlayback() }
    }

    init { world.stage.addActor(skipButton) }

    private fun clipCache(): AtlasClipCache {
        val existing = cache
        if (existing != null && !existing.metrics().disposed) return existing
        invalidation?.close()
        return ImageGetter.getAtlasClipCache().also {
            cache = it
            invalidation = it.onInvalidated { reconcile() }
        }
    }

    private fun clipsEnabled(): Boolean {
        if (!world.game.settings.showPixelUnits || map.currentTileSetStrings.unitSetName != "AtlasUnits") return false
        // Built-in clips cannot establish provenance for mod-provided art, even in the same namespace.
        // Keep the selected static art when any non-built-in ruleset or visual mod is active.
        return world.game.settings.visualMods.isEmpty() &&
            world.gameInfo.ruleset.mods.all { mod -> BaseRuleset.entries.any { it.fullName == mod } }
    }

    fun accept(batch: VisualBatch) {
        if (disposed || !world.isVisualTransportAvailable() || batch.view != world.currentVisualViewKey()) return
        if (batch.events.any { it.view != batch.view || it.id.gameId != world.gameInfo.gameId }) return
        if (view != batch.view) { reconcile(); view = batch.view; timelineId = null; throughSequence = 0L }
        val nextTimeline = batch.events.firstOrNull()?.id?.timelineId ?: timelineId
        if (batch.events.any { it.id.timelineId != nextTimeline }) return
        if (timelineId != null && timelineId != nextTimeline) { reconcile(); throughSequence = 0L }
        timelineId = nextTimeline
        if (batch.droppedThroughSequence > throughSequence) {
            reconcile(); throughSequence = maxOf(throughSequence, batch.throughSequence); return
        }
        val fresh = coalesceMoves(batch.events.filter { it.id.sequence > throughSequence })
        throughSequence = maxOf(throughSequence, batch.throughSequence)
        if (mode == Mode.Off || fresh.isEmpty()) return
        // A new committed action for a unit supersedes its old unplayed/part-played presentation.
        val superseded = fresh.flatMap(::unitIds).toSet()
        if (active?.event?.let { unitIds(it).any(superseded::contains) } == true) { active?.close(); active = null }
        queue.removeIf { event -> unitIds(event).any(superseded::contains) }
        if (queue.size + fresh.size + (if (active == null) 0 else 1) > MAX_QUEUED_EVENTS) { catchUp(fresh); return }
        for (event in fresh) queue.addLast(event)
        // A slow screen catches up to the installed state instead of playing seconds of stale history.
        if (queue.sumOf { duration(it, fast = true).toDouble() } > 2.4) { catchUp(emptyList()); return }
        if (queue.isNotEmpty()) { closeIdle(); Gdx.graphics.requestRendering() }
    }

    /** Skips stale history to the installed state, except nuclear strikes: those are too rare and too big to miss. */
    private fun catchUp(pending: List<VisualEvent>) {
        val strikes = (queue + pending).filterIsInstance<VisualEvent.NuclearStrike>()
        val playing = active?.takeIf { it.event is VisualEvent.NuclearStrike }
        if (playing != null) active = null
        reconcile()
        active = playing
        for (strike in strikes) queue.addLast(strike)
        if (active != null || queue.isNotEmpty()) Gdx.graphics.requestRendering()
    }

    private fun coalesceMoves(events: List<VisualEvent>): List<VisualEvent> {
        val result = ArrayList<VisualEvent>()
        for (event in events) {
            val previous = result.lastOrNull()
            if (previous is VisualEvent.Move && event is VisualEvent.Move && previous.unit == event.unit &&
                previous.kind == event.kind && previous.provenance == event.provenance &&
                previous.displacementOrigin == null && event.displacementOrigin == null &&
                previous.path.lastOrNull() == event.path.firstOrNull() && previous.path.size + event.path.size <= 257) {
                result[result.lastIndex] = VisualEvent.Move(event.id, event.view, event.provenance, event.unit,
                    previous.path + event.path.drop(1), event.kind)
            } else result += event
        }
        return result
    }

    private fun unitIds(event: VisualEvent): List<Int> = when (event) {
        is VisualEvent.Move -> listOf(event.unit.unitId)
        is VisualEvent.RemoveUnit -> listOf(event.unit.unitId)
        is VisualEvent.Attack -> listOfNotNull((event.attacker as? VisualUnitDescriptor)?.unitId,
            (event.defender as? VisualUnitDescriptor)?.unitId)
        // Only the weapon: a unit caught in the blast acting later must not cancel the whole strike.
        is VisualEvent.NuclearStrike -> listOfNotNull((event.weapon as? VisualUnitDescriptor)?.unitId)
        else -> emptyList()
    }

    private fun partPosition(part: VisualEvent): VisualPosition? = when (part) {
        is VisualEvent.Attack -> part.target
        is VisualEvent.RemoveUnit -> part.position
        is VisualEvent.CaptureCity -> part.position
        is VisualEvent.ChangeImprovement -> part.after.position
        else -> null
    }

    private fun duration(event: VisualEvent, fast: Boolean): Float {
        if (event is VisualEvent.NuclearStrike) return when {
            mode == Mode.Reduced -> .6f
            fast -> 1.2f
            else -> 2.5f
        }
        if (mode == Mode.Reduced) return .28f
        return when (event) {
            is VisualEvent.Move -> if (event.kind in listOf(VisualMoveKind.TELEPORT, VisualMoveKind.PARADROP, VisualMoveKind.AIR_REBASE))
                if (fast) .15f else .3f
            else ((event.path.size - 1).coerceAtLeast(1) * if (fast) .07f else .18f).coerceAtMost(if (fast) .3f else .9f)
            is VisualEvent.Attack -> if (fast) .26f else .65f
            is VisualEvent.RemoveUnit -> if (fast) .22f else .55f
            else -> if (fast) .3f else .7f
        }
    }

    fun advance(deltaSeconds: Float) {
        if (disposed) return
        val delta = if (deltaSeconds.isFinite()) deltaSeconds.coerceIn(0f, 1f) else 0f
        val currentView = world.currentVisualViewKey()
        val currentMode = Mode.parse(world.game.settings.unitAnimationMode)
        val currentSet = map.currentTileSetStrings.unitSetName
        if (resetGeneration != world.visualResetGeneration || view != currentView || mode != currentMode || selectedUnitSet != currentSet) {
            reconcile(); throughSequence = 0L; timelineId = null
            resetGeneration = world.visualResetGeneration; view = currentView; mode = currentMode; selectedUnitSet = currentSet
        }
        if (!world.isVisualTransportAvailable()) { reconcile(); return }
        if (manualInputSerial != map.visualManualInputSerial) {
            manualInputSerial = map.visualManualInputSerial; followCooldown = 3f
            active?.followAllowed = false
        }
        followCooldown = (followCooldown - delta).coerceAtLeast(0f)
        world.takeVisualBatch()?.let(::accept)
        if (mode == Mode.Off) { reconcile(); return }
        if (active == null && queue.isNotEmpty()) {
            closeIdle()
            active = Playback(queue.removeFirst(), mode == Mode.Fast || queue.size >= 4)
        }
        active?.let {
            it.advance(delta)
            if (it.finished) { it.close(); active = null }
        }
        if (active == null && queue.isEmpty()) updateIdle(delta)
        skipButton.isVisible = active != null || queue.isNotEmpty()
        if (skipButton.isVisible) {
            skipButton.pack()
            skipButton.setPosition((world.stage.width - skipButton.width) / 2f,
                (world.topBar.y - skipButton.height - 8f).coerceAtLeast(8f))
        }
        if (active != null || queue.isNotEmpty() || idle?.actor?.ready == true && idle?.actor?.looping == true)
            Gdx.graphics.requestRendering()
    }

    private fun updateIdle(delta: Float) {
        if (!clipsEnabled() || mode == Mode.Reduced || mode == Mode.Off) { closeIdle(); return }
        val selected = world.bottomUnitTable.selectedUnit ?: run { closeIdle(); return }
        if (selected.hasDisappeared()) { closeIdle(); return }
        val tile = selected.getTile().position()
        val position = VisualPosition(tile.x, tile.y)
        if (!map.isVisualOnScreen(position)) { closeIdle(); return }
        val unit = selected.getUnit()
        val sample = runCatching { VisualEventProjector.unit(world.gameInfo, view, unit, unit.currentTile) }
            .getOrNull() ?: run { closeIdle(); return }
        val state = if (selected.canBuildCurrentImprovement() && !selected.isSleeping() && !selected.isMoving())
            UnitVisualState.WORK else UnitVisualState.IDLE
        val key = sample.unit.unitId to state
        if (idleKey != key || idle?.actor?.descriptor != sample.unit) {
            closeIdle(); idleKey = key; idleElapsed = 0f
            idle = ghost(sample.unit, state, position, HexFacing.E)
        }
        idleElapsed += delta * if (mode == Mode.Fast) 1.5f else 1f
        val point = map.visualPoint(position) ?: run { closeIdle(); return }
        idle?.update(idleElapsed, point)
    }

    private fun closeIdle() { idle?.close(); idle = null; idleKey = null }

    /** Idempotent cleanup before map rebind, view changes, cache teardown, skip and screen disposal. */
    fun reconcile() {
        queue.clear(); active?.close(); active = null; closeIdle(); skipButton.isVisible = false
        cache?.trim()
    }

    fun dispose() {
        if (disposed) return
        disposed = true; reconcile(); invalidation?.close(); invalidation = null; cache = null; skipButton.remove()
    }
    override fun close() = dispose()

    private fun facing(source: VisualPosition, target: VisualPosition?): HexFacing {
        val a = map.visualPoint(source) ?: return HexFacing.E
        val b = target?.let { map.visualPoint(it, a) } ?: return HexFacing.E
        val index = floor((b.sub(a).angleDeg() + 30f) / 60f).toInt() % 6
        return HexFacing.entries[index]
    }

    private fun ghost(unit: VisualUnitDescriptor, state: UnitVisualState, position: VisualPosition, facing: HexFacing): Ghost? {
        if (!clipsEnabled()) return null
        val tile = map.visualTile(position) ?: return null
        val liveTile = map.visualUnitTile(unit.unitId)
        val liveUnit = liveTile?.tileView?.getVisibleUnits()?.firstOrNull { it.id == unit.unitId }
        if (liveUnit != null && liveUnit.name != unit.artName) return null
        val actor = UnitVisualActor(unit, clipCache(), ClipKey(unit.artName, state, facing), tile.hexagonImageWidth,
            tile.hexagonImagePosition.first, tile.hexagonImagePosition.second)
        return Ghost(actor)
    }

    private inner class Ghost(val actor: UnitVisualActor) : AutoCloseable {
        private var mask: AutoCloseable? = null
        private var attached = false
        fun update(elapsed: Float, point: Vector2) {
            if (!actor.prepare(elapsed)) {
                mask?.close(); mask = null
                actor.isVisible = false
                return
            }
            actor.setPosition(point.x, point.y)
            actor.isVisible = true
            if (!attached) { map.addVisualActor(actor); attached = true }
            if (mask == null) mask = map.visualUnitTile(actor.descriptor.unitId)?.layerUnitArt?.maskForVisual(actor.descriptor.unitId)
        }
        override fun close() { mask?.close(); mask = null; actor.close() }
    }

    /** Strike feedback that waits for the shockwave to reach its tile. */
    private class StrikeMark(val position: VisualPosition, val start: Float,
                             val effect: VisualEffects? = null, val ghost: Ghost? = null, val removed: Boolean = false)

    private inner class Playback(val event: VisualEvent, private val fast: Boolean) : AutoCloseable {
        private val reduced = mode == Mode.Reduced
        private val duration = duration(event, fast)
        private var elapsed = 0f
        private var cancelled = false
        var followAllowed = world.game.settings.followVisibleActions && !reduced && followCooldown <= 0f
        val finished get() = cancelled || elapsed >= duration
        private val ghosts = ArrayList<Ghost>()
        private val effects = ArrayList<Pair<VisualPosition, VisualEffects>>()
        private var projectile: VisualEffects? = null
        private var moveGhost: Ghost? = null
        private var attackerGhost: Ghost? = null
        private var defenderGhost: Ghost? = null
        private var blast: NuclearBlast? = null
        /** The strike's flight is drawn only over a corridor of seen tiles, as for any projectile. */
        private var strikeFlight = false
        private val strikeMarks = ArrayList<StrikeMark>()
        private val focus: VisualPosition? = when (event) {
            is VisualEvent.Move -> event.path.lastOrNull()
            is VisualEvent.Attack -> event.target ?: event.source
            is VisualEvent.RemoveUnit -> event.position
            is VisualEvent.FoundCity -> event.position
            is VisualEvent.ChangeImprovement -> event.after.position
            is VisualEvent.CaptureCity -> event.position
            is VisualEvent.NuclearStrike -> event.target ?: event.source ?: event.parts.firstNotNullOfOrNull(::partPosition)
        }

        init {
            fun addGhost(unit: VisualDescriptor?, state: UnitVisualState, position: VisualPosition?, target: VisualPosition? = null): Ghost? {
                if (reduced || unit !is VisualUnitDescriptor || position == null) return null
                return ghost(unit, state, position, facing(position, target))?.also { ghosts += it }
            }
            fun effect(position: VisualPosition?, kind: VisualEffects.Kind, text: String? = null, tint: Color = Color(0xe9cf92ff.toInt())) {
                if (position == null || !map.isVisualTileVisible(position)) return
                VisualEffects(kind, text, tint, reduced).also { map.addVisualActor(it); effects += position to it }
            }
            when (event) {
                is VisualEvent.Move -> {
                    val start = event.path.firstOrNull()
                    moveGhost = addGhost(event.unit, UnitVisualState.MOVE, start, event.path.getOrNull(1))
                    effect(event.path.lastOrNull(), VisualEffects.Kind.MOVE)
                }
                is VisualEvent.Attack -> {
                    attackerGhost = addGhost(event.attacker, UnitVisualState.ATTACK, event.source, event.target)
                    defenderGhost = addGhost(event.defender, UnitVisualState.HIT, event.target, event.source)
                    effect(event.target, if (event.kind == VisualAttackKind.NUCLEAR) VisualEffects.Kind.NUCLEAR else VisualEffects.Kind.HIT,
                        event.damageToDefender?.takeIf { it > 0 }?.let { "−$it" }, Color(0xffbd91ff.toInt()))
                    effect(event.source, VisualEffects.Kind.HIT,
                        event.damageToAttacker?.takeIf { it > 0 }?.let { "−$it" }, Color(0xffbd91ff.toInt()))
                    if (!reduced && event.source != null && event.target != null &&
                        event.kind in listOf(VisualAttackKind.RANGED, VisualAttackKind.AIR, VisualAttackKind.CITY) &&
                        map.canDrawVisualProjectile(event.source, event.target)) {
                        val unitType = world.gameInfo.ruleset.units[event.attacker?.artName]?.unitType.orEmpty().lowercase()
                        val style = when {
                            event.kind == VisualAttackKind.AIR -> VisualEffects.Projectile.AIR
                            "siege" in unitType || event.kind == VisualAttackKind.CITY -> VisualEffects.Projectile.SHELL
                            "submarine" in unitType -> VisualEffects.Projectile.TORPEDO
                            "gun" in unitType || "armor" in unitType -> VisualEffects.Projectile.SHOT
                            else -> VisualEffects.Projectile.ARROW
                        }
                        projectile = VisualEffects(VisualEffects.Kind.PROJECTILE, projectileStyle = style).also(map::addVisualActor)
                    }
                }
                is VisualEvent.RemoveUnit -> {
                    moveGhost = addGhost(event.unit, UnitVisualState.DEFEAT, event.position)
                    effect(event.position, VisualEffects.Kind.DEFEAT)
                }
                is VisualEvent.FoundCity -> effect(event.position, VisualEffects.Kind.FOUND, "City founded")
                is VisualEvent.ChangeImprovement -> effect(event.after.position, VisualEffects.Kind.WORK,
                    if (event.after.pillaged == true || event.after.roadPillaged == true) "Pillaged" else "Improved")
                is VisualEvent.CaptureCity -> effect(event.position, VisualEffects.Kind.CAPTURE,
                    if (event.destroyed) "City lost" else "City captured")
                is VisualEvent.NuclearStrike -> setUpStrike(event, ::addGhost, ::effect)
            }
        }

        private fun setUpStrike(
            event: VisualEvent.NuclearStrike,
            addGhost: (VisualDescriptor?, UnitVisualState, VisualPosition?, VisualPosition?) -> Ghost?,
            effect: (VisualPosition?, VisualEffects.Kind, String?, Color) -> Unit
        ) {
            val damageTint = Color(0xffbd91ff.toInt())
            val target = event.target
            attackerGhost = addGhost(event.weapon, UnitVisualState.ATTACK, event.source, target)
            if (event.intercepted) effect(event.source, VisualEffects.Kind.HIT, "Intercepted", damageTint)
            if (target != null) {
                strikeFlight = !reduced && event.source != null && map.canDrawVisualProjectile(event.source, target)
                blast = NuclearBlast(event.blastRadius, reduced, dropIn = !strikeFlight).also(map::addVisualActor)
            }
            fun mark(position: VisualPosition, kind: VisualEffects.Kind, text: String?, start: Float) {
                if (!map.isVisualTileVisible(position)) return
                val feedback = VisualEffects(kind, text, damageTint, reduced).also { it.isVisible = false; map.addVisualActor(it) }
                strikeMarks += StrikeMark(position, start, effect = feedback)
            }
            fun ghostMark(unit: VisualDescriptor?, state: UnitVisualState, position: VisualPosition, start: Float, removed: Boolean) {
                if (reduced || unit !is VisualUnitDescriptor) return
                val ghost = ghost(unit, state, position, HexFacing.E)?.also { ghosts += it } ?: return
                strikeMarks += StrikeMark(position, start, ghost = ghost, removed = removed)
            }
            val weaponId = (event.weapon as? VisualUnitDescriptor)?.unitId
            val destroyed = event.parts.filterIsInstance<VisualEvent.RemoveUnit>().map { it.unit.unitId }.toSet()
            for (part in event.parts) {
                val position = partPosition(part) ?: continue
                // Each tile reacts as the shockwave reaches it; reduced motion shows every outcome at once.
                val start = if (reduced) 0f else blast?.arrival(target?.let {
                    HexMath.getDistance(it.x, it.y, position.x, position.y) } ?: 0) ?: NuclearBlast.DETONATION
                when (part) {
                    is VisualEvent.Attack -> {
                        val defender = part.defender
                        if (defender !is VisualUnitDescriptor || defender.unitId !in destroyed)
                            ghostMark(defender, UnitVisualState.HIT, position, start, removed = false)
                        mark(position, VisualEffects.Kind.HIT, part.damageToDefender?.takeIf { it > 0 }?.let { "−$it" }, start)
                    }
                    is VisualEvent.RemoveUnit -> if (part.unit.unitId != weaponId) {
                        ghostMark(part.unit, UnitVisualState.DEFEAT, position, start, removed = true)
                        mark(position, VisualEffects.Kind.DEFEAT, null, start)
                    }
                    is VisualEvent.CaptureCity -> mark(position, VisualEffects.Kind.CAPTURE,
                        if (part.destroyed) "City lost" else "City captured", start)
                    else -> Unit // Pillage and fallout show on the map itself once the strike settles.
                }
            }
        }

        private fun advanceStrike(event: VisualEvent.NuclearStrike, progress: Float) {
            val source = event.source?.let(map::visualPoint)
            val target = event.target?.let { map.visualPoint(it, source) }
            // The weapon launches from its base and is gone by the moment of detonation.
            if (source != null) {
                attackerGhost?.actor?.opacity = 1f - (progress / NuclearBlast.DETONATION).coerceIn(0f, 1f)
                attackerGhost?.update(elapsed, source)
            } else attackerGhost?.close()
            blast?.let {
                it.isVisible = target != null
                if (target != null) it.update(progress, target, source?.takeIf { strikeFlight })
            }
            shake(event, progress)
            for (mark in strikeMarks) {
                val point = map.visualPoint(mark.position, target ?: source)
                val started = progress >= mark.start
                val local = ((progress - mark.start) / (1f - mark.start)).coerceIn(0f, 1f)
                mark.effect?.let {
                    it.isVisible = point != null && started
                    if (point != null) it.update(local, point)
                }
                val ghost = mark.ghost ?: continue
                if (point == null) continue
                if (mark.removed) ghost.actor.opacity = if (started) 1f - local else 1f
                else ghost.actor.hitAmount = if (started) sin(local * Math.PI).toFloat().coerceAtLeast(0f) else 0f
                ghost.update((elapsed - mark.start * duration).coerceAtLeast(0f), point)
            }
        }

        /** A short drawing-only jolt of the map at detonation, never in reduced motion. */
        private fun shake(event: VisualEvent.NuclearStrike, progress: Float) {
            val since = (progress - NuclearBlast.DETONATION) * duration
            val window = if (fast) .45f else .7f
            if (reduced || blast == null || since < 0f || since > window || event.target?.let(map::isVisualOnScreen) != true) {
                map.visualShake.setZero(); return
            }
            val amplitude = (if (fast) 3f else 6f) * (1f - since / window).pow(2)
            map.visualShake.set(sin(since * 61f) * amplitude, cos(since * 47f) * amplitude * .6f)
        }

        fun advance(delta: Float) {
            elapsed += delta
            val progress = (elapsed / duration).coerceIn(0f, 1f)
            if (focus == null || !map.isVisualTileVisible(focus)) { cancelled = true; return }
            if (!map.isVisualOnScreen(focus) && !followAllowed) { cancelled = true; return }
            if (followAllowed) map.followVisual(focus, delta)
            for ((position, effect) in effects) {
                val point = map.visualPoint(position)
                effect.isVisible = point != null
                if (point != null) effect.update(progress, point)
            }
            when (event) {
                is VisualEvent.Move -> {
                    if (reduced) return
                    val points = ArrayList<Vector2>()
                    for (position in event.path) {
                        val point = map.visualPoint(position, points.lastOrNull()) ?: run { cancelled = true; return }
                        points += point
                    }
                    if (points.isEmpty()) { cancelled = true; return }
                    val interpolated = event.kind in listOf(VisualMoveKind.WALK, VisualMoveKind.SWAP, VisualMoveKind.RETREAT)
                    val point = if (!interpolated || points.size == 1) points.last() else {
                        val location = progress * (points.size - 1)
                        val index = floor(location).toInt().coerceAtMost(points.size - 2)
                        points[index].cpy().lerp(points[index + 1], (location - index).coerceIn(0f, 1f))
                    }
                    moveGhost?.actor?.opacity = if (interpolated) 1f else sin(progress * Math.PI).toFloat().coerceAtLeast(0f)
                    moveGhost?.update(elapsed, point)
                }
                is VisualEvent.Attack -> {
                    val source = event.source?.let(map::visualPoint)
                    val target = event.target?.let { map.visualPoint(it, source) }
                    if (source != null) {
                        val point = source.cpy()
                        if (!reduced && target != null) {
                            val recoil = sin(progress * Math.PI * 2).toFloat() * if (event.kind == VisualAttackKind.MELEE) 6f else -2f
                            point.add(target.cpy().sub(source).nor().scl(recoil))
                        }
                        attackerGhost?.update(elapsed, point)
                    } else attackerGhost?.close()
                    if (target != null) {
                        defenderGhost?.actor?.hitAmount = sin(progress * Math.PI).toFloat().coerceAtLeast(0f)
                        defenderGhost?.update(elapsed, target)
                    } else defenderGhost?.close()
                    projectile?.let {
                        it.isVisible = source != null && target != null && progress <= .6f &&
                            event.source?.let { from -> event.target?.let { to -> map.canDrawVisualProjectile(from, to) } } == true
                        if (source != null && target != null) it.update((progress / .6f).coerceAtMost(1f), source, target)
                    }
                }
                is VisualEvent.RemoveUnit -> {
                    moveGhost?.actor?.opacity = 1f - progress
                    map.visualPoint(event.position)?.let { moveGhost?.update(elapsed, it) }
                }
                is VisualEvent.NuclearStrike -> advanceStrike(event, progress)
                else -> Unit
            }
        }

        override fun close() {
            ghosts.forEach { it.close() }; effects.forEach { it.second.close() }; projectile?.close()
            strikeMarks.forEach { it.effect?.close() }; blast?.close()
            if (blast != null) map.visualShake.setZero()
        }
    }
}
