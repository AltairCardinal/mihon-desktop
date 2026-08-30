package mihon.desktop.reader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import mihon.domain.reader.PageDecodePurpose
import mihon.domain.reader.ReaderPageDecodeKey
import mihon.domain.reader.session.ReaderPageId

internal data class DesktopReaderRegionPresentationOwnerSnapshot(
    val minimumGeneration: Long,
    val holderCount: Int,
    val tileCache: DesktopReaderImageCacheSnapshot,
    val closed: Boolean,
)

internal data class DesktopReaderRegionPresentationSnapshot(
    val previewAsset: DesktopReaderImageAsset? = null,
    val previewFailure: Throwable? = null,
    val previewFailed: Boolean = false,
    val regionTilesEnabled: Boolean = false,
    val readyTiles: Map<ReaderPageDecodeKey, DesktopReaderImageAsset> = emptyMap(),
    val closed: Boolean = false,
)

/**
 * Owns mounted large-static preview/tile presentations while the runtime pipeline owns all I/O,
 * decode single-flight, and cache policy. A holder pins one encoded source before its preview is
 * decoded, then retains that source only when the decoded dimensions require region tiles.
 */
internal class DesktopReaderRegionPresentationOwner(
    private val scope: CoroutineScope,
    private val pageImagePipeline: DesktopReaderPageImagePipeline,
    private val managesPipelineGeneration: Boolean = true,
) : AutoCloseable {
    private val lock = Any()
    private val holders = mutableSetOf<DesktopReaderRegionPresentationHolder>()
    private val minimumPageAttempts = mutableMapOf<Pair<ReaderPageId, Long>, Long>()
    private var minimumGeneration = 0L
    private var closed = false

    fun beginGeneration(generation: Long): Boolean {
        require(generation >= 0L) { "generation must be non-negative" }
        val stale = synchronized(lock) {
            check(!closed) { "Desktop reader region presentation owner is closed" }
            if (generation < minimumGeneration) return false
            if (generation == minimumGeneration) return true
            minimumGeneration = generation
            minimumPageAttempts.keys.removeAll { (_, holderGeneration) -> holderGeneration < generation }
            holders.filterTo(mutableListOf()) { it.identity.generation < generation }.also(holders::removeAll)
        }
        stale.forEach(DesktopReaderRegionPresentationHolder::closeFromOwner)
        return !managesPipelineGeneration || pageImagePipeline.beginGeneration(generation)
    }

    fun beginPageAttempt(
        pageId: ReaderPageId,
        generation: Long,
        attemptGeneration: Long,
    ): Boolean {
        require(generation >= 0L) { "generation must be non-negative" }
        require(attemptGeneration >= 0L) { "attemptGeneration must be non-negative" }
        val stale = synchronized(lock) {
            check(!closed) { "Desktop reader region presentation owner is closed" }
            if (generation < minimumGeneration) return false
            val identity = pageId to generation
            val previousAttempt = minimumPageAttempts[identity] ?: 0L
            if (attemptGeneration < previousAttempt) return false
            if (attemptGeneration == previousAttempt) return true
            minimumPageAttempts[identity] = attemptGeneration
            holders.filterTo(mutableListOf()) { holder ->
                holder.identity.pageId == pageId &&
                    holder.identity.generation == generation &&
                    holder.identity.attemptGeneration < attemptGeneration
            }.also(holders::removeAll)
        }
        stale.forEach(DesktopReaderRegionPresentationHolder::closeFromOwner)
        return !managesPipelineGeneration ||
            pageImagePipeline.beginPageAttempt(pageId, generation, attemptGeneration)
    }

    fun createHolder(
        identity: DesktopReaderPresentationImageSlotIdentity,
        previewKey: ReaderPageDecodeKey,
    ): DesktopReaderRegionPresentationHolder {
        require(identity.generation == previewKey.generation) {
            "Preview and presentation generations must match"
        }
        require(identity.attemptGeneration == previewKey.contentKey.attemptGeneration) {
            "Preview and presentation attempts must match"
        }
        require(identity.pageId == previewKey.contentKey.pageId) {
            "Preview and presentation page identities must match"
        }
        require(previewKey.purpose == PageDecodePurpose.FULL_PAGE) {
            "Region presentation preview must use a full-page request"
        }
        val current = synchronized(lock) {
            check(!closed) { "Desktop reader region presentation owner is closed" }
            minimumGeneration to currentAttemptLocked(identity)
        }
        if (identity.generation > current.first) {
            check(beginGeneration(identity.generation)) {
                "Region presentation generation could not become current"
            }
        }
        if (identity.attemptGeneration > current.second) {
            check(beginPageAttempt(identity.pageId, identity.generation, identity.attemptGeneration)) {
                "Region presentation attempt could not become current"
            }
        }
        return synchronized(lock) {
            check(!closed) { "Desktop reader region presentation owner is closed" }
            check(identity.generation == minimumGeneration) {
                "Region presentation generation must be current"
            }
            check(identity.attemptGeneration == currentAttemptLocked(identity)) {
                "Region presentation attempt must be current"
            }
            DesktopReaderRegionPresentationHolder(
                identity = identity,
                previewKey = previewKey,
                scope = scope,
                pageImagePipeline = pageImagePipeline,
                contentSession = pageImagePipeline.openRegionSession(previewKey.contentKey),
                owner = this,
            ).also(holders::add)
        }
    }

    fun snapshot(): DesktopReaderRegionPresentationOwnerSnapshot = synchronized(lock) {
        DesktopReaderRegionPresentationOwnerSnapshot(
            minimumGeneration = minimumGeneration,
            holderCount = holders.size,
            tileCache = pageImagePipeline.snapshot().tileCache,
            closed = closed,
        )
    }

    override fun close() {
        val detached = synchronized(lock) {
            if (closed) return
            closed = true
            holders.toList().also { holders.clear() }
        }
        detached.forEach(DesktopReaderRegionPresentationHolder::closeFromOwner)
    }

    internal fun isCurrent(holder: DesktopReaderRegionPresentationHolder): Boolean = synchronized(lock) {
        isCurrentLocked(holder)
    }

    internal fun acceptPreview(
        holder: DesktopReaderRegionPresentationHolder,
        runningJob: Job,
        lease: DesktopReaderImageAssetLease,
    ): Boolean = synchronized(lock) {
        if (!isCurrentLocked(holder)) return false
        holder.acceptPreviewWhileOwnerCurrent(runningJob, lease)
    }

    internal fun publishPreviewFailure(
        holder: DesktopReaderRegionPresentationHolder,
        runningJob: Job,
        cause: Throwable? = null,
    ) {
        synchronized(lock) {
            if (!isCurrentLocked(holder)) return
            holder.publishPreviewFailureWhileOwnerCurrent(runningJob, cause)
        }
    }

    internal fun acceptTile(
        holder: DesktopReaderRegionPresentationHolder,
        runningJob: Job,
        key: ReaderPageDecodeKey,
        lease: DesktopReaderImageAssetLease,
    ): DesktopReaderImageAssetLease? = synchronized(lock) {
        if (!isCurrentLocked(holder)) return null
        holder.acceptTileWhileOwnerCurrent(runningJob, key, lease)
    }

    internal fun retainReadyTile(
        holder: DesktopReaderRegionPresentationHolder,
        key: ReaderPageDecodeKey,
    ): DesktopReaderImageAssetLease? = synchronized(lock) {
        if (!isCurrentLocked(holder)) return null
        holder.retainReadyTileWhileOwnerCurrent(key)
    }

    internal fun retainReadyPreview(
        holder: DesktopReaderRegionPresentationHolder,
    ): DesktopReaderImageAssetLease? = synchronized(lock) {
        if (!isCurrentLocked(holder)) return null
        holder.retainReadyPreviewWhileOwnerCurrent()
    }

    internal fun unregister(holder: DesktopReaderRegionPresentationHolder) {
        synchronized(lock) {
            holders.remove(holder)
        }
    }

    private fun isCurrentLocked(holder: DesktopReaderRegionPresentationHolder): Boolean =
        !closed &&
            holder in holders &&
            holder.identity.generation == minimumGeneration &&
            holder.identity.attemptGeneration == currentAttemptLocked(holder.identity)

    private fun currentAttemptLocked(identity: DesktopReaderPresentationImageSlotIdentity): Long =
        minimumPageAttempts[identity.pageId to identity.generation] ?: 0L
}

internal class DesktopReaderRegionPresentationHolder internal constructor(
    val identity: DesktopReaderPresentationImageSlotIdentity,
    private val previewKey: ReaderPageDecodeKey,
    private val scope: CoroutineScope,
    private val pageImagePipeline: DesktopReaderPageImagePipeline,
    private val contentSession: DesktopReaderRegionContentSession,
    private val owner: DesktopReaderRegionPresentationOwner,
) : AutoCloseable {
    private val lock = Any()
    private val mutableState = MutableStateFlow(DesktopReaderRegionPresentationSnapshot())
    private val requestedTiles = linkedSetOf<ReaderPageDecodeKey>()
    private val tileJobs = mutableMapOf<ReaderPageDecodeKey, Job>()
    private val readyTileLeases = mutableMapOf<ReaderPageDecodeKey, DesktopReaderImageAssetLease>()
    private var previewJob: Job? = null
    private var previewLease: DesktopReaderImageAssetLease? = null
    private var regionSourceRetained = true
    private var closed = false

    val state: StateFlow<DesktopReaderRegionPresentationSnapshot> = mutableState.asStateFlow()

    fun acquire() {
        if (!owner.isCurrent(this)) return
        lateinit var job: Job
        synchronized(lock) {
            if (closed || previewJob != null || previewLease != null) return
            job = scope.launch(start = CoroutineStart.LAZY) { acquirePreview() }
            previewJob = job
        }
        job.start()
    }

    fun updateViewportTiles(keys: Set<ReaderPageDecodeKey>) {
        keys.forEach(::validateTileKey)
        if (!owner.isCurrent(this)) return
        val removedJobs = mutableListOf<Job>()
        val removedLeases = mutableListOf<DesktopReaderImageAssetLease>()
        val additions = mutableListOf<ReaderPageDecodeKey>()
        synchronized(lock) {
            if (closed) return
            val removed = requestedTiles - keys
            removed.forEach { key ->
                tileJobs.remove(key)?.let(removedJobs::add)
                readyTileLeases.remove(key)?.let(removedLeases::add)
            }
            requestedTiles.clear()
            requestedTiles += keys
            if (mutableState.value.regionTilesEnabled) {
                keys.filterTo(additions) { it !in tileJobs && it !in readyTileLeases }
            }
            publishStateLocked()
        }
        removedJobs.forEach(Job::cancel)
        removedLeases.forEach(DesktopReaderImageAssetLease::close)
        additions.forEach(::startTileAcquire)
    }

    fun snapshot(): DesktopReaderRegionPresentationSnapshot = mutableState.value

    fun retainReadyTileForRender(key: ReaderPageDecodeKey): DesktopReaderImageAssetLease? =
        owner.retainReadyTile(this, key)

    fun retainReadyPreviewForRender(): DesktopReaderImageAssetLease? = owner.retainReadyPreview(this)

    override fun close() = closeInternal(unregister = true)

    internal fun closeFromOwner() = closeInternal(unregister = false)

    internal fun acceptPreviewWhileOwnerCurrent(
        runningJob: Job,
        lease: DesktopReaderImageAssetLease,
    ): Boolean {
        val enableRegion = DesktopReaderLargeImagePolicy.requiresRegionTiles(
            width = lease.asset.sourceWidth,
            height = lease.asset.sourceHeight,
        ) && lease.asset.animationMetadata == null
        val additions = mutableListOf<ReaderPageDecodeKey>()
        synchronized(lock) {
            if (closed || previewJob !== runningJob) return false
            previewLease = lease
            if (enableRegion) {
                requestedTiles.filterTo(additions) { it !in tileJobs && it !in readyTileLeases }
            } else {
                regionSourceRetained = false
            }
            mutableState.value = DesktopReaderRegionPresentationSnapshot(
                previewAsset = lease.asset,
                regionTilesEnabled = enableRegion,
            )
        }
        if (!enableRegion) contentSession.close()
        additions.forEach(::startTileAcquire)
        return true
    }

    internal fun publishPreviewFailureWhileOwnerCurrent(runningJob: Job, cause: Throwable? = null) {
        var closeSource = false
        synchronized(lock) {
            if (closed || previewJob !== runningJob) return
            if (regionSourceRetained) {
                regionSourceRetained = false
                closeSource = true
            }
            mutableState.value = DesktopReaderRegionPresentationSnapshot(
                previewFailure = cause,
                previewFailed = true,
            )
        }
        if (closeSource) contentSession.close()
    }

    internal fun acceptTileWhileOwnerCurrent(
        runningJob: Job,
        key: ReaderPageDecodeKey,
        lease: DesktopReaderImageAssetLease,
    ): DesktopReaderImageAssetLease? = synchronized(lock) {
        if (
            closed ||
            !mutableState.value.regionTilesEnabled ||
            key !in requestedTiles ||
            tileJobs[key] !== runningJob
        ) {
            return null
        }
        if (!pageImagePipeline.commitRegionTile(key, lease)) return null
        tileJobs.remove(key)
        readyTileLeases.put(key, lease).also { publishStateLocked() }
    }

    internal fun retainReadyTileWhileOwnerCurrent(key: ReaderPageDecodeKey): DesktopReaderImageAssetLease? =
        synchronized(lock) {
            if (closed || key !in requestedTiles) return null
            readyTileLeases[key]?.retain()
        }

    internal fun retainReadyPreviewWhileOwnerCurrent(): DesktopReaderImageAssetLease? = synchronized(lock) {
        if (closed || mutableState.value.previewAsset == null) return null
        previewLease?.retain()
    }

    private suspend fun acquirePreview() {
        val runningJob = checkNotNull(currentCoroutineContext()[Job])
        var acquiredLease: DesktopReaderImageAssetLease? = null
        try {
            contentSession.pin()
            acquiredLease = pageImagePipeline.acquire(previewKey)
            val lease = acquiredLease
            if (lease == null) {
                owner.publishPreviewFailure(this, runningJob)
                return
            }
            if (owner.acceptPreview(this, runningJob, lease)) acquiredLease = null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            owner.publishPreviewFailure(this, runningJob, error)
        } finally {
            acquiredLease?.close()
            synchronized(lock) {
                if (previewJob === runningJob) previewJob = null
            }
        }
    }

    private fun startTileAcquire(key: ReaderPageDecodeKey) {
        lateinit var job: Job
        synchronized(lock) {
            if (
                closed ||
                !mutableState.value.regionTilesEnabled ||
                key !in requestedTiles ||
                key in tileJobs ||
                key in readyTileLeases
            ) {
                return
            }
            job = scope.launch(start = CoroutineStart.LAZY) { acquireTile(key) }
            tileJobs[key] = job
        }
        job.start()
    }

    private suspend fun acquireTile(key: ReaderPageDecodeKey) {
        val runningJob = checkNotNull(currentCoroutineContext()[Job])
        var acquiredLease: DesktopReaderImageAssetLease? = null
        var previousLease: DesktopReaderImageAssetLease? = null
        try {
            acquiredLease = contentSession.acquireTile(key)
            val lease = acquiredLease ?: return
            previousLease = owner.acceptTile(this, runningJob, key, lease)
            if (previousLease != null || snapshot().readyTiles[key] === lease.asset) acquiredLease = null
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            // A region failure is local: keep the preview and allow later tile requests to proceed.
        } finally {
            acquiredLease?.close()
            previousLease?.close()
            synchronized(lock) {
                if (tileJobs[key] === runningJob) tileJobs.remove(key)
            }
        }
    }

    private fun validateTileKey(key: ReaderPageDecodeKey) {
        require(key.purpose == PageDecodePurpose.REGION_TILE) { "Viewport tiles must use REGION_TILE" }
        require(key.contentKey == previewKey.contentKey) { "Viewport tile and preview identities must match" }
    }

    private fun publishStateLocked() {
        val previous = mutableState.value
        mutableState.value = previous.copy(
            readyTiles = readyTileLeases.mapValues { it.value.asset },
        )
    }

    private fun closeInternal(unregister: Boolean) {
        val jobs: List<Job>
        val leases: List<DesktopReaderImageAssetLease>
        val closeSource: Boolean
        synchronized(lock) {
            if (closed) return
            closed = true
            jobs = listOfNotNull(previewJob) + tileJobs.values
            previewJob = null
            tileJobs.clear()
            leases = listOfNotNull(previewLease) + readyTileLeases.values
            previewLease = null
            readyTileLeases.clear()
            requestedTiles.clear()
            closeSource = regionSourceRetained
            regionSourceRetained = false
            mutableState.value = DesktopReaderRegionPresentationSnapshot(closed = true)
        }
        jobs.forEach(Job::cancel)
        leases.forEach(DesktopReaderImageAssetLease::close)
        if (closeSource) contentSession.close()
        if (unregister) owner.unregister(this)
    }
}
