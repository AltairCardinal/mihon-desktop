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
import mihon.domain.reader.PageSplitHalf
import mihon.domain.reader.PixelBounds
import mihon.domain.reader.ReaderPageDecodeKey
import mihon.domain.reader.session.ReaderPageId

/** Stable identity of one presentation slot, independent from its shared full-page decode. */
internal data class DesktopReaderPresentationImageSlotIdentity(
    val pageId: ReaderPageId,
    val generation: Long,
    val splitHalf: PageSplitHalf? = null,
    val sourceBounds: PixelBounds? = null,
) {
    init {
        require(generation >= 0L) { "generation must be non-negative" }
        sourceBounds?.let { bounds ->
            require(bounds.x >= 0 && bounds.y >= 0 && bounds.width > 0 && bounds.height > 0) {
                "source bounds must have a non-negative origin and positive size"
            }
        }
    }
}

internal sealed interface DesktopReaderPresentationImageState {
    val identity: DesktopReaderPresentationImageSlotIdentity

    data class Idle(
        override val identity: DesktopReaderPresentationImageSlotIdentity,
    ) : DesktopReaderPresentationImageState

    data class Loading(
        override val identity: DesktopReaderPresentationImageSlotIdentity,
    ) : DesktopReaderPresentationImageState

    data class Ready(
        override val identity: DesktopReaderPresentationImageSlotIdentity,
        val asset: DesktopReaderImageAsset,
    ) : DesktopReaderPresentationImageState

    data class Failed(
        override val identity: DesktopReaderPresentationImageSlotIdentity,
        val cause: Throwable? = null,
    ) : DesktopReaderPresentationImageState

    data class Closed(
        override val identity: DesktopReaderPresentationImageSlotIdentity,
    ) : DesktopReaderPresentationImageState
}

/**
 * Owns presentation leases for one reader runtime and fences them by chapter generation.
 *
 * The decoded pipeline remains runtime-owned. Closing this owner releases only presentation leases.
 */
class DesktopReaderPresentationImageOwner internal constructor(
    private val scope: CoroutineScope,
    private val pageImagePipeline: DesktopReaderPageImagePipeline,
    private val pageIoObserver: ReaderPageIoObserver,
) : AutoCloseable {
    private val lock = Any()
    private val holders = mutableSetOf<DesktopReaderPresentationImageHolder>()
    private var minimumGeneration = 0L
    private var closed = false

    internal fun beginGeneration(generation: Long): Boolean {
        require(generation >= 0L) { "generation must be non-negative" }
        val staleHolders = synchronized(lock) {
            if (closed || generation < minimumGeneration) return false
            if (generation == minimumGeneration) return true
            minimumGeneration = generation
            holders.filter { holder -> holder.identity.generation < generation }.also { stale ->
                holders.removeAll(stale.toSet())
            }
        }
        staleHolders.forEach(DesktopReaderPresentationImageHolder::closeFromOwner)
        return pageImagePipeline.beginGeneration(generation)
    }

    internal fun createHolder(
        identity: DesktopReaderPresentationImageSlotIdentity,
        decodeKey: ReaderPageDecodeKey,
    ): DesktopReaderPresentationImageHolder {
        require(identity.pageId == decodeKey.contentKey.pageId) {
            "Presentation and decode page identities must match"
        }
        require(identity.generation == decodeKey.generation) {
            "Presentation and decode generations must match"
        }
        require(decodeKey.purpose == PageDecodePurpose.FULL_PAGE) {
            "Presentation holders must share a full-page decode"
        }
        val observedGeneration = synchronized(lock) {
            check(!closed) { "Desktop reader presentation image owner is closed" }
            minimumGeneration
        }
        if (identity.generation > observedGeneration) {
            check(beginGeneration(identity.generation)) {
                "Presentation holder generation could not become current"
            }
        }
        return synchronized(lock) {
            check(!closed) { "Desktop reader presentation image owner is closed" }
            check(identity.generation == minimumGeneration) {
                "Presentation holder generation must be current"
            }
            DesktopReaderPresentationImageHolder(
                identity = identity,
                decodeKey = decodeKey,
                scope = scope,
                pageImagePipeline = pageImagePipeline,
                pageIoObserver = pageIoObserver,
                owner = this,
            ).also(holders::add)
        }
    }

    override fun close() {
        val registeredHolders = synchronized(lock) {
            if (closed) return
            closed = true
            holders.toList().also { holders.clear() }
        }
        registeredHolders.forEach(DesktopReaderPresentationImageHolder::closeFromOwner)
    }

    internal fun isCurrent(holder: DesktopReaderPresentationImageHolder): Boolean = synchronized(lock) {
        !closed &&
            holder in holders &&
            holder.identity.generation == minimumGeneration
    }

    internal fun acknowledgeDraw(
        holder: DesktopReaderPresentationImageHolder,
        candidateIdentity: DesktopReaderPresentationImageSlotIdentity,
    ): Boolean = synchronized(lock) {
        if (closed || holder !in holders || holder.identity.generation != minimumGeneration) return false
        holder.acknowledgeDrawWhileOwnerCurrent(candidateIdentity)
    }

    internal fun retainReadyAsset(
        holder: DesktopReaderPresentationImageHolder,
        candidateIdentity: DesktopReaderPresentationImageSlotIdentity,
    ): DesktopReaderImageAssetLease? = synchronized(lock) {
        if (closed || holder !in holders || holder.identity.generation != minimumGeneration) return null
        holder.retainReadyAssetWhileOwnerCurrent(candidateIdentity)
    }

    internal fun acceptReadyLease(
        holder: DesktopReaderPresentationImageHolder,
        runningJob: Job,
        lease: DesktopReaderImageAssetLease,
    ): Boolean = synchronized(lock) {
        if (closed || holder !in holders || holder.identity.generation != minimumGeneration) return false
        holder.acceptReadyLeaseWhileOwnerCurrent(runningJob, lease)
    }

    internal fun publishFailure(
        holder: DesktopReaderPresentationImageHolder,
        runningJob: Job,
        cause: Throwable? = null,
    ) = synchronized(lock) {
        if (closed || holder !in holders || holder.identity.generation != minimumGeneration) return
        holder.publishFailureWhileOwnerCurrent(runningJob, cause)
    }

    internal fun unregister(holder: DesktopReaderPresentationImageHolder) {
        synchronized(lock) {
            holders.remove(holder)
        }
    }
}

/** One presentation slot and its independently closeable decoded-image lease. */
internal class DesktopReaderPresentationImageHolder internal constructor(
    val identity: DesktopReaderPresentationImageSlotIdentity,
    val decodeKey: ReaderPageDecodeKey,
    private val scope: CoroutineScope,
    private val pageImagePipeline: DesktopReaderPageImagePipeline,
    private val pageIoObserver: ReaderPageIoObserver,
    private val owner: DesktopReaderPresentationImageOwner,
) : AutoCloseable {
    private val lock = Any()
    private val mutableState = MutableStateFlow<DesktopReaderPresentationImageState>(
        DesktopReaderPresentationImageState.Idle(identity),
    )
    private var acquireJob: Job? = null
    private var presentationLease: DesktopReaderImageAssetLease? = null
    private var drawAcknowledged = false
    private var closed = false

    val state: StateFlow<DesktopReaderPresentationImageState> = mutableState.asStateFlow()

    fun acquire() {
        if (!owner.isCurrent(this)) return
        var jobToStart: Job? = null
        synchronized(lock) {
            if (closed || acquireJob != null || presentationLease != null) return
            mutableState.value = DesktopReaderPresentationImageState.Loading(identity)
            jobToStart = scope.launch(start = CoroutineStart.LAZY) { acquirePresentationLease() }
            acquireJob = jobToStart
        }
        jobToStart?.start()
    }

    fun acknowledgeDraw(candidateIdentity: DesktopReaderPresentationImageSlotIdentity): Boolean =
        owner.acknowledgeDraw(this, candidateIdentity)

    internal fun acknowledgeDrawWhileOwnerCurrent(
        candidateIdentity: DesktopReaderPresentationImageSlotIdentity,
    ): Boolean = synchronized(lock) {
        if (
            closed ||
            candidateIdentity != identity ||
            drawAcknowledged ||
            mutableState.value !is DesktopReaderPresentationImageState.Ready
        ) {
            return false
        }
        drawAcknowledged = true
        pageIoObserver.pagePresented(identity.pageId, identity.generation)
        true
    }

    internal fun retainReadyAssetForRender(
        candidateIdentity: DesktopReaderPresentationImageSlotIdentity,
    ): DesktopReaderImageAssetLease? = owner.retainReadyAsset(this, candidateIdentity)

    internal fun retainReadyAssetWhileOwnerCurrent(
        candidateIdentity: DesktopReaderPresentationImageSlotIdentity,
    ): DesktopReaderImageAssetLease? = synchronized(lock) {
        if (
            closed ||
            candidateIdentity != identity ||
            mutableState.value !is DesktopReaderPresentationImageState.Ready
        ) {
            return null
        }
        presentationLease?.retain()
    }

    internal fun retainReadyAsset(): DesktopReaderImageAssetLease? = owner.retainReadyAsset(this, identity)

    override fun close() = closeInternal(unregister = true)

    internal fun closeFromOwner() = closeInternal(unregister = false)

    private suspend fun acquirePresentationLease() {
        val runningJob = checkNotNull(currentCoroutineContext()[Job])
        var acquiredLease: DesktopReaderImageAssetLease? = null
        try {
            acquiredLease = pageImagePipeline.acquire(decodeKey)
            val lease = acquiredLease
            if (lease == null) {
                owner.publishFailure(this, runningJob)
                return
            }
            val accepted = owner.acceptReadyLease(this, runningJob, lease)
            if (accepted) acquiredLease = null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            owner.publishFailure(this, runningJob, error)
        } finally {
            acquiredLease?.close()
            synchronized(lock) {
                if (acquireJob === runningJob) acquireJob = null
            }
        }
    }

    internal fun acceptReadyLeaseWhileOwnerCurrent(
        runningJob: Job,
        lease: DesktopReaderImageAssetLease,
    ): Boolean = synchronized(lock) {
        if (closed || acquireJob !== runningJob) return false
        presentationLease = lease
        mutableState.value = DesktopReaderPresentationImageState.Ready(identity, lease.asset)
        true
    }

    internal fun publishFailureWhileOwnerCurrent(
        runningJob: Job,
        cause: Throwable?,
    ) = synchronized(lock) {
        if (!closed && acquireJob === runningJob) {
            mutableState.value = DesktopReaderPresentationImageState.Failed(identity, cause)
        }
    }

    private fun closeInternal(unregister: Boolean) {
        var jobToCancel: Job? = null
        var leaseToClose: DesktopReaderImageAssetLease? = null
        synchronized(lock) {
            if (closed) return
            closed = true
            jobToCancel = acquireJob
            acquireJob = null
            leaseToClose = presentationLease
            presentationLease = null
            drawAcknowledged = false
            mutableState.value = DesktopReaderPresentationImageState.Closed(identity)
        }
        jobToCancel?.cancel()
        leaseToClose?.close()
        if (unregister) owner.unregister(this)
    }
}
