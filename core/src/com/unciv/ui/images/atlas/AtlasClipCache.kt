package com.unciv.ui.images.atlas

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.LifecycleListener
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.utils.Disposable
import java.util.concurrent.atomic.AtomicBoolean

data class ClipCacheMetrics(
    val budgetBytes: Long, val allocatedBytes: Long, val reservedBytes: Long, val pinnedBytes: Long,
    val pendingDecodedBytes: Long, val pendingDecodedBudgetBytes: Long,
    val hits: Long, val misses: Long, val evictions: Long, val rejectedRequests: Long,
    val residentPages: Int, val inFlightClips: Int, val queuedDecodeJobs: Int,
    val generation: Long, val manifestReady: Boolean, val disposed: Boolean,
    val orphanedContextBytes: Long, val lastFailure: String?
) {
    /** Includes allocations that are uploading or have been retired but not actually deleted yet. */
    val budgetedBytes: Long get() = allocatedBytes + reservedBytes
}

/**
 * Page-sharing presentation cache. request only examines metadata/pins and admits bounded work.
 * No textures, decoded images, playback positions or durable gameplay events live in save data.
 */
class AtlasClipCache(
    private val loader: AtlasClipLoader = AtlasClipLoader(),
    manifest: AtlasArtManifest? = null,
    budgetBytes: Long? = null,
    pendingDecodedBudgetBytes: Long = 8L * 1024 * 1024,
    private val onChanged: () -> Unit = {},
    private val maxInFlightClips: Int = 8,
    registerLifecycle: Boolean = true
) : Disposable, AutoCloseable {
    private val lock = Any()
    private val platformBudget = if (loader.application.type == Application.ApplicationType.Desktop)
        128L * 1024 * 1024 else 32L * 1024 * 1024
    private val requestedBudget = (budgetBytes ?: platformBudget).also { require(it in 1..platformBudget) }
    private var effectiveBudget = requestedBudget
    private val requestedDecodedBudget = pendingDecodedBudgetBytes.also { require(it in 1..8L * 1024 * 1024) }
    private var effectiveDecodedBudget = requestedDecodedBudget
    private var metadata = manifest
    private var metadataLoading = false
    private var metadataRetryAt = 0L
    private var generation = 1L
    private var contextEpoch = 1L
    private var disposed = false
    private var paused = false
    private var allocatedBytes = 0L
    private var reservedBytes = 0L
    private var pendingDecodedBytes = 0L
    private var orphanedContextBytes = 0L
    private var lastUsed = 0L
    private var hits = 0L; private var misses = 0L; private var evictions = 0L; private var rejected = 0L
    private var lastFailure: String? = null
    private var disposalScheduled = false
    private var lifecycleAttached = false
    private var listenerId = 0L
    private val invalidationListeners = linkedMapOf<Long, () -> Unit>()
    private val entries = linkedMapOf<PageId, PageEntry>()
    private val inFlight = linkedMapOf<ClipKey, ClipSpec>()
    private val failedPages = linkedMapOf<String, Long>()

    private data class PageId(val generation: Long, val name: String)
    private enum class Phase { RESERVED, DECODING, STAGED, UPLOADING, READY, RETIRING, CANCELLED }
    private class PageEntry(val id: PageId, val page: PageSpec, val contextEpoch: Long) {
        var phase = Phase.RESERVED
        var texture: Texture? = null
        var decoded: AtlasClipLoader.DecodedPage? = null
        var gpuReserved = true
        var decodeReserved = false
        var pins = 0
        var lastUsed = 0L
        val claims = hashSetOf<ClipKey>()
    }

    private val lifecycle = object : LifecycleListener {
        override fun pause() = loader.lifecycleGl {
            synchronized(lock) { paused = true }
            resetInternal(false)
            notifyInvalidated() // Playback closes its pins before the GL context can go away.
            notifyChanged()
            drainRetired(Int.MAX_VALUE)
        }
        override fun resume() = loader.lifecycleGl {
            synchronized(lock) { contextEpoch++; paused = false }
            resetInternal(false)
            notifyInvalidated()
            notifyChanged()
        }
        override fun dispose() = loader.lifecycleGl {
            disposeInternal(false)
            notifyInvalidated()
            notifyChanged()
            drainRetired(Int.MAX_VALUE)
            loader.contextGone()
            synchronized(lock) {
                // Context destruction owns any still-live driver resources. Never delete those ids later.
                allocatedBytes -= orphanedContextBytes; orphanedContextBytes = 0
            }
            drainWithoutContext()
        }
    }

    init {
        require(maxInFlightClips in 1..32)
        manifest?.let { applyManifestBudgets(it) }
        if (registerLifecycle) { loader.application.addLifecycleListener(lifecycle); lifecycleAttached = true }
    }

    /** Returns a pinned loaded exact clip, or null for missing/loading/pressure; no synchronous IO/GL. */
    fun request(key: ClipKey): ClipHandle? = synchronized(lock) {
        if (disposed || paused || !loader.hasContext()) { misses++; return@synchronized null }
        val manifest = metadata
        if (manifest == null) {
            misses++; requestMetadataLocked(); return@synchronized null
        }
        val clip = manifest.findClip(key)
        if (clip == null) { misses++; return@synchronized null }
        val pages = clip.pageNames.map { requireNotNull(manifest.findPage(it)) }
        val loaded = pages.map { entries[PageId(generation, it.name)] }
        if (loaded.all { it != null && it.phase == Phase.READY && it.texture != null }) {
            hits++
            val resident = loaded.map { requireNotNull(it) }
            for (page in resident) { page.pins++; page.lastUsed = ++lastUsed }
            return@synchronized Handle(this, generation, clip, resident)
        }
        misses++
        if (clip.gpuBytes > effectiveBudget || pages.any { it.decodedBytes > effectiveDecodedBudget }) {
            rejected++; return@synchronized null
        }
        if (inFlight.containsKey(key)) { pumpLocked(); return@synchronized null }
        if (inFlight.size >= maxInFlightClips || pages.any { (failedPages[it.name] ?: 0L) > System.nanoTime() }) {
            rejected++; return@synchronized null
        }
        // An entry being deleted must actually release its driver allocation before its replacement admits.
        if (loaded.any { it?.phase in listOf(Phase.RETIRING, Phase.CANCELLED) }) {
            rejected++; scheduleDisposalLocked(); return@synchronized null
        }
        val missing = pages.filter { entries[PageId(generation, it.name)] == null }
        val needed = missing.sumOf { it.gpuBytes }
        if (allocatedBytes + reservedBytes + needed > effectiveBudget) {
            retireForCapacityLocked(needed, clip.pageNames.toSet())
            rejected++; return@synchronized null
        }
        for (page in missing) {
            entries[PageId(generation, page.name)] = PageEntry(PageId(generation, page.name), page, contextEpoch)
            reservedBytes += page.gpuBytes
        }
        for (page in pages) entries.getValue(PageId(generation, page.name)).claims += key
        inFlight[key] = clip
        pumpLocked()
        null
    }

    /** Check in act/update before drawing, and close any handle that is no longer current. */
    fun isCurrent(handle: ClipHandle): Boolean = synchronized(lock) {
        handle is Handle && handle.owner === this && !handle.closed.get() && !disposed && !paused &&
            handle.generation == generation && handle.pages.all { it.phase == Phase.READY && it.texture != null }
    }

    /** V3 registers one playback cleanup callback; delivery is on GL outside draw, before pause teardown. */
    fun onInvalidated(listener: () -> Unit): AutoCloseable {
        val id = synchronized(lock) { (++listenerId).also { invalidationListeners[it] = listener } }
        val removed = AtomicBoolean()
        return AutoCloseable {
            if (removed.compareAndSet(false, true)) synchronized(lock) { invalidationListeners.remove(id) }
        }
    }

    private fun requestMetadataLocked() {
        if (metadataLoading || disposed || paused || metadataRetryAt > System.nanoTime()) return
        val requestedGeneration = generation
        metadataLoading = true
        val admitted = loader.loadManifest { result ->
            var changed = false
            synchronized(lock) {
                if (disposed || requestedGeneration != generation) return@loadManifest
                metadataLoading = false
                result.fold(onSuccess = {
                    metadata = it; applyManifestBudgets(it); metadataRetryAt = 0; changed = true
                }, onFailure = {
                    recordFailure(it); metadataRetryAt = System.nanoTime() + RETRY_NANOS
                })
            }
            if (changed) loader.postGl { notifyChanged() }
        }
        if (!admitted) { metadataLoading = false; rejected++ }
    }

    private fun applyManifestBudgets(manifest: AtlasArtManifest) {
        val manifestBudget = if (loader.application.type == Application.ApplicationType.Desktop)
            manifest.budgets.desktopBytes else manifest.budgets.otherBytes
        effectiveBudget = minOf(requestedBudget, manifestBudget)
        effectiveDecodedBudget = minOf(requestedDecodedBudget, manifest.budgets.pendingUploadBytes)
    }

    /** Reserve before decoding, including jobs from older generations until their native buffers close. */
    private fun pumpLocked() {
        if (disposed || paused) return
        for (entry in entries.values) {
            if (entry.id.generation != generation || entry.phase != Phase.RESERVED || entry.claims.isEmpty()) continue
            if (pendingDecodedBytes + entry.page.decodedBytes > effectiveDecodedBudget) continue
            entry.phase = Phase.DECODING; entry.decodeReserved = true
            pendingDecodedBytes += entry.page.decodedBytes
            if (!loader.decode(entry.page) { onDecoded(entry, it) }) {
                entry.phase = Phase.RESERVED; releaseDecodeLocked(entry); rejected++
                break // Queue saturation retries on later requests/completions, never blocks the caller.
            }
        }
    }

    private fun onDecoded(entry: PageEntry, result: Result<AtlasClipLoader.DecodedPage>) {
        val decoded = result.getOrNull()
        var upload = false
        val abandoned = arrayListOf<Pair<PageEntry, AtlasClipLoader.DecodedPage>>()
        synchronized(lock) {
            if (decoded != null && !disposed && !paused && entry.id.generation == generation &&
                entry.phase == Phase.DECODING && entry.claims.isNotEmpty()) {
                entry.decoded = decoded; entry.phase = Phase.STAGED; upload = true
            } else {
                if (decoded != null) abandoned += entry to decoded else releaseDecodeLocked(entry)
                if (entry.phase != Phase.CANCELLED && entry.id.generation == generation && !disposed) {
                    result.exceptionOrNull()?.let(::recordFailure)
                    failedPages[entry.page.name] = System.nanoTime() + RETRY_NANOS
                    while (failedPages.size > 64) failedPages.remove(failedPages.keys.first())
                    cancelClaimsLocked(entry, abandoned)
                }
                entry.phase = Phase.CANCELLED; releaseGpuLocked(entry)
                if (decoded == null) entries.remove(entry.id)
                pumpLocked()
                detachIfDrainedLocked()
            }
        }
        closeDecoded(abandoned)
        if (upload && !loader.postGl { upload(entry) }) {
            synchronized(lock) {
                entry.decoded?.let { abandoned += entry to it }; entry.decoded = null
                entry.phase = Phase.CANCELLED; releaseGpuLocked(entry)
                cancelClaimsLocked(entry, abandoned)
            }
            closeDecoded(abandoned)
        }
    }

    private fun upload(entry: PageEntry) {
        val decoded = synchronized(lock) {
            if (disposed || paused || entry.id.generation != generation || entry.phase != Phase.STAGED || entry.claims.isEmpty())
                return
            entry.phase = Phase.UPLOADING
            requireNotNull(entry.decoded)
        }
        val result = runCatching { loader.upload(entry.page, decoded) }
        val texture = result.getOrNull()
        var install = false
        var changed = false
        val abandoned = arrayListOf<Pair<PageEntry, AtlasClipLoader.DecodedPage>>()
        synchronized(lock) {
            if (texture != null && !disposed && !paused && entry.id.generation == generation &&
                entry.phase == Phase.UPLOADING && entry.claims.isNotEmpty()) {
                entry.texture = texture; entry.decoded = null; entry.phase = Phase.READY
                entry.lastUsed = ++lastUsed; install = true
                releaseGpuLocked(entry); allocatedBytes += entry.page.gpuBytes
                changed = finishReadyClipsLocked()
            } else {
                result.exceptionOrNull()?.let(::recordFailure)
                if (entry.id.generation == generation && !disposed) {
                    failedPages[entry.page.name] = System.nanoTime() + RETRY_NANOS
                    cancelClaimsLocked(entry, abandoned)
                }
                entry.phase = Phase.CANCELLED; entry.decoded = null
            }
        }
        // A stale allocation remains covered by its reservation until its GL deletion completes.
        if (texture != null && !install) loader.disposeTexture(texture)
        decoded.close()
        synchronized(lock) {
            releaseDecodeLocked(entry)
            if (!install) { releaseGpuLocked(entry); entries.remove(entry.id) }
            pumpLocked()
            detachIfDrainedLocked()
        }
        closeDecoded(abandoned)
        if (changed) notifyChanged()
    }

    private fun finishReadyClipsLocked(): Boolean {
        val complete = inFlight.values.filter { clip -> clip.pageNames.all {
            entries[PageId(generation, it)]?.phase == Phase.READY
        } }
        for (clip in complete) {
            inFlight.remove(clip.key)
            for (page in clip.pageNames) entries.getValue(PageId(generation, page)).claims.remove(clip.key)
        }
        return complete.isNotEmpty()
    }

    private fun cancelClaimsLocked(failed: PageEntry, abandoned: MutableList<Pair<PageEntry, AtlasClipLoader.DecodedPage>>) {
        val keys = failed.claims.toList()
        for (key in keys) {
            val clip = inFlight.remove(key) ?: continue
            for (name in clip.pageNames) entries[PageId(failed.id.generation, name)]?.claims?.remove(key)
        }
        cancelUnusedLocked(abandoned)
    }

    private fun cancelUnusedLocked(abandoned: MutableList<Pair<PageEntry, AtlasClipLoader.DecodedPage>>) {
        for (entry in entries.values.toList()) {
            if (entry.claims.isNotEmpty() || entry.pins > 0) continue
            when (entry.phase) {
                Phase.RESERVED -> { entry.phase = Phase.CANCELLED; releaseGpuLocked(entry); entries.remove(entry.id) }
                Phase.DECODING -> { entry.phase = Phase.CANCELLED; releaseGpuLocked(entry) }
                Phase.STAGED -> {
                    entry.phase = Phase.CANCELLED; releaseGpuLocked(entry)
                    entry.decoded?.let { abandoned += entry to it }; entry.decoded = null
                }
                Phase.UPLOADING -> entry.phase = Phase.CANCELLED // Actual upload may already have allocated a texture.
                else -> Unit
            }
        }
    }

    private fun closeDecoded(abandoned: Collection<Pair<PageEntry, AtlasClipLoader.DecodedPage>>) {
        for ((entry, decoded) in abandoned.distinctBy { it.first.id }) {
            decoded.close() // Native CPU buffer disposal is thread-safe through its lease; no GL call.
            synchronized(lock) {
                releaseDecodeLocked(entry)
                if (entry.phase == Phase.CANCELLED && !entry.gpuReserved && entry.texture == null) entries.remove(entry.id)
                pumpLocked()
                detachIfDrainedLocked()
            }
        }
    }

    private fun releaseGpuLocked(entry: PageEntry) {
        if (!entry.gpuReserved) return
        entry.gpuReserved = false; reservedBytes -= entry.page.gpuBytes
    }
    private fun releaseDecodeLocked(entry: PageEntry) {
        if (!entry.decodeReserved) return
        entry.decodeReserved = false; pendingDecodedBytes -= entry.page.decodedBytes
    }

    private fun retireForCapacityLocked(needed: Long, keepPages: Set<String>) {
        var potential = 0L
        for (entry in entries.values.filter { it.phase == Phase.READY && it.pins == 0 && it.claims.isEmpty() &&
            !(it.id.generation == generation && it.page.name in keepPages) }.sortedBy { it.lastUsed }) {
            entry.phase = Phase.RETIRING; evictions++; potential += entry.page.gpuBytes
            if (allocatedBytes + reservedBytes + needed - potential <= effectiveBudget) break
        }
        scheduleDisposalLocked()
    }

    /** Cancel unused pending clips and evict unpinned pages, keeping every open loaded handle alive. */
    fun trim() {
        val abandoned = arrayListOf<Pair<PageEntry, AtlasClipLoader.DecodedPage>>()
        synchronized(lock) {
            inFlight.clear(); entries.values.forEach { it.claims.clear() }
            cancelUnusedLocked(abandoned)
            for (entry in entries.values) if (entry.phase == Phase.READY && entry.pins == 0) {
                entry.phase = Phase.RETIRING; evictions++
            }
            scheduleDisposalLocked()
        }
        closeDecoded(abandoned)
    }

    /** Invalidate the generation, preserving retired pinned allocations until playback releases them. */
    fun reset() = resetInternal(true)

    private fun resetInternal(queueNotification: Boolean) {
        val abandoned = arrayListOf<Pair<PageEntry, AtlasClipLoader.DecodedPage>>()
        synchronized(lock) {
            if (disposed) return
            invalidateLocked(abandoned)
            scheduleDisposalLocked()
        }
        closeDecoded(abandoned)
        if (queueNotification) loader.postGl { notifyInvalidated(); notifyChanged() }
    }

    private fun invalidateLocked(abandoned: MutableList<Pair<PageEntry, AtlasClipLoader.DecodedPage>>) {
        generation++; metadata = null; metadataLoading = false; metadataRetryAt = 0
        inFlight.clear(); failedPages.clear(); entries.values.forEach { it.claims.clear() }
        // Retire READY before cancellation so pinned pages remain accounted and are not reused by new generations.
        for (entry in entries.values) if (entry.phase == Phase.READY) entry.phase = Phase.RETIRING
        cancelUnusedLocked(abandoned)
    }

    private fun scheduleDisposalLocked() {
        if (disposalScheduled) return
        if (entries.values.none { it.phase == Phase.RETIRING && it.pins == 0 }) { detachIfDrainedLocked(); return }
        if (!loader.hasContext()) { drainWithoutContextLocked(); return }
        disposalScheduled = true
        if (!loader.postGl { drainRetired(DISPOSALS_PER_CALLBACK) }) disposalScheduled = false
    }

    private fun drainRetired(limit: Int) {
        synchronized(lock) { disposalScheduled = false }
        for (index in 0 until limit) {
            val entry = synchronized(lock) {
                entries.values.firstOrNull { it.phase == Phase.RETIRING && it.pins == 0 }
            } ?: break
            val texture = entry.texture ?: break
            if (!loader.hasContext()) { drainWithoutContext(); return }
            if (entry.contextEpoch != synchronized(lock) { contextEpoch }) {
                // A misbehaving playback client retained its pin through pause/resume. Do not delete an old id.
                loader.abandonTexture(texture)
                synchronized(lock) {
                    orphanedContextBytes += entry.page.gpuBytes
                    entry.texture = null; entries.remove(entry.id)
                }
            } else {
                loader.disposeTexture(texture)
                synchronized(lock) {
                    allocatedBytes -= entry.page.gpuBytes; entry.texture = null; entries.remove(entry.id)
                }
            }
        }
        synchronized(lock) { scheduleDisposalLocked(); pumpLocked() }
    }

    private fun drainWithoutContext() = synchronized(lock) { drainWithoutContextLocked() }
    private fun drainWithoutContextLocked() {
        for (entry in entries.values.toList()) if (entry.phase == Phase.RETIRING && entry.pins == 0) {
            entry.texture?.let { loader.abandonTexture(it); allocatedBytes -= entry.page.gpuBytes }
            entry.texture = null; entries.remove(entry.id)
        }
        detachIfDrainedLocked()
    }

    private fun release(handle: Handle) = synchronized(lock) {
        if (!handle.closed.compareAndSet(false, true)) return@synchronized
        for (page in handle.pages) {
            check(page.pins > 0); page.pins--; page.lastUsed = ++lastUsed
        }
        scheduleDisposalLocked()
    }

    private class Handle(val owner: AtlasClipCache, val generation: Long, override val clip: ClipSpec,
        val pages: List<PageEntry>) : ClipHandle {
        val closed = AtomicBoolean()
        private val declared = clip.frames.flatMap { listOf(it.color) + it.masks.map { m -> m.region } + listOfNotNull(it.shadow) }.toSet()
        private val regions = hashMapOf<FrameRegion, TextureRegion>()
        override fun region(region: FrameRegion): TextureRegion = synchronized(owner.lock) {
            check(owner.isCurrent(this)) { "Atlas clip handle closed or invalidated; use static fallback" }
            require(region in declared) { "Region is not declared by this pinned clip" }
            regions.getOrPut(region) {
                val page = pages.first { it.page.name == region.pageName }
                TextureRegion(requireNotNull(page.texture), region.x, region.y, region.width, region.height)
            }
        }
        override fun close() { owner.release(this) }
    }

    fun metrics(): ClipCacheMetrics = synchronized(lock) {
        ClipCacheMetrics(requestedBudget, allocatedBytes, reservedBytes,
            entries.values.filter { it.pins > 0 && it.texture != null }.sumOf { it.page.gpuBytes },
            pendingDecodedBytes, requestedDecodedBudget, hits, misses, evictions, rejected,
            entries.values.count { it.texture != null }, inFlight.size, loader.queuedJobs(),
            generation, metadata != null, disposed, orphanedContextBytes, lastFailure)
    }

    /** Stops admissions immediately; queued/stale CPU callbacks still close their owned buffers. */
    override fun dispose() = disposeInternal(true)

    private fun disposeInternal(queueNotification: Boolean) {
        val abandoned = arrayListOf<Pair<PageEntry, AtlasClipLoader.DecodedPage>>()
        synchronized(lock) {
            if (disposed) return
            disposed = true; invalidateLocked(abandoned); scheduleDisposalLocked()
        }
        closeDecoded(abandoned)
        loader.close()
        synchronized(lock) { detachIfDrainedLocked() }
        if (queueNotification) loader.postGl { notifyInvalidated(); notifyChanged() }
    }
    override fun close() = dispose()

    private fun recordFailure(error: Throwable) { lastFailure = "${error.javaClass.simpleName}: ${error.message}".take(256) }
    private fun notifyInvalidated() {
        val listeners = synchronized(lock) { invalidationListeners.values.toList() }
        for (listener in listeners) runCatching(listener)
    }
    private fun detachIfDrainedLocked() {
        if (!disposed || !lifecycleAttached || entries.isNotEmpty() || pendingDecodedBytes != 0L || reservedBytes != 0L || allocatedBytes != 0L) return
        lifecycleAttached = false
        if (!loader.postGl { loader.application.removeLifecycleListener(lifecycle) })
            loader.application.removeLifecycleListener(lifecycle)
    }
    private fun notifyChanged() {
        runCatching(onChanged)
        if (loader.hasContext()) Gdx.graphics?.requestRendering()
    }

    companion object {
        private const val RETRY_NANOS = 2_000_000_000L
        private const val DISPOSALS_PER_CALLBACK = 8
    }
}
