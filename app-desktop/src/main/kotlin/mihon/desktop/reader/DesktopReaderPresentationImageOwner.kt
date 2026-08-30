package mihon.desktop.reader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import mihon.domain.reader.PageDecodePurpose
import mihon.domain.reader.PageSplitHalf
import mihon.domain.reader.PixelBounds
import mihon.domain.reader.ReaderPageDecodeKey
import mihon.domain.reader.content.ReaderPageContentOpenRequest
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
    private val animatedHolders = mutableSetOf<DesktopReaderAnimatedPresentationImageHolder>()
    private var minimumGeneration = 0L
    private var closed = false

    internal fun beginGeneration(generation: Long): Boolean {
        require(generation >= 0L) { "generation must be non-negative" }
        val staleHolders = synchronized(lock) {
            if (closed || generation < minimumGeneration) return false
            if (generation == minimumGeneration) return true
            minimumGeneration = generation
            val staleStatic = holders.filter { holder -> holder.identity.generation < generation }
            val staleAnimated = animatedHolders.filter { holder -> holder.identity.generation < generation }
            holders.removeAll(staleStatic.toSet())
            animatedHolders.removeAll(staleAnimated.toSet())
            staleStatic to staleAnimated
        }
        staleHolders.first.forEach(DesktopReaderPresentationImageHolder::closeFromOwner)
        staleHolders.second.forEach(DesktopReaderAnimatedPresentationImageHolder::closeFromOwner)
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

    internal fun createAnimatedHolder(
        identity: DesktopReaderPresentationImageSlotIdentity,
        contentKey: ReaderPageContentOpenRequest,
        maxWidth: Int,
        maxHeight: Int,
    ): DesktopReaderAnimatedPresentationImageHolder {
        require(identity.pageId == contentKey.pageId) {
            "Presentation and animation content page identities must match"
        }
        require(identity.generation == contentKey.generation) {
            "Presentation and animation content generations must match"
        }
        require(maxWidth > 0 && maxHeight > 0) { "Animation decode bounds must be positive" }
        val observedGeneration = synchronized(lock) {
            check(!closed) { "Desktop reader presentation image owner is closed" }
            minimumGeneration
        }
        if (identity.generation > observedGeneration) {
            check(beginGeneration(identity.generation)) {
                "Animated presentation holder generation could not become current"
            }
        }
        return synchronized(lock) {
            check(!closed) { "Desktop reader presentation image owner is closed" }
            check(identity.generation == minimumGeneration) {
                "Animated presentation holder generation must be current"
            }
            DesktopReaderAnimatedPresentationImageHolder(
                identity = identity,
                contentKey = contentKey,
                maxWidth = maxWidth,
                maxHeight = maxHeight,
                scope = scope,
                contentSession = pageImagePipeline.openAnimationSession(contentKey),
                owner = this,
            ).also(animatedHolders::add)
        }
    }

    override fun close() {
        val registeredHolders = synchronized(lock) {
            if (closed) return
            closed = true
            val registeredStatic = holders.toList()
            val registeredAnimated = animatedHolders.toList()
            holders.clear()
            animatedHolders.clear()
            registeredStatic to registeredAnimated
        }
        registeredHolders.first.forEach(DesktopReaderPresentationImageHolder::closeFromOwner)
        registeredHolders.second.forEach(DesktopReaderAnimatedPresentationImageHolder::closeFromOwner)
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
    ) {
        synchronized(lock) {
            if (closed || holder !in holders || holder.identity.generation != minimumGeneration) return
            holder.publishFailureWhileOwnerCurrent(runningJob, cause)
        }
    }

    internal fun unregister(holder: DesktopReaderPresentationImageHolder) {
        synchronized(lock) {
            holders.remove(holder)
        }
    }

    internal fun isCurrent(holder: DesktopReaderAnimatedPresentationImageHolder): Boolean = synchronized(lock) {
        !closed &&
            holder in animatedHolders &&
            holder.identity.generation == minimumGeneration
    }

    internal fun acceptAnimatedFrame(
        holder: DesktopReaderAnimatedPresentationImageHolder,
        runningJob: Job,
        key: ReaderPageDecodeKey,
        lease: DesktopReaderImageAssetLease,
    ): DesktopReaderAnimatedFrameAcceptance = synchronized(lock) {
        if (closed || holder !in animatedHolders || holder.identity.generation != minimumGeneration) {
            return DesktopReaderAnimatedFrameAcceptance.Rejected
        }
        holder.acceptFrameWhileOwnerCurrent(runningJob, key, lease)
    }

    internal fun retainAnimatedFrame(
        holder: DesktopReaderAnimatedPresentationImageHolder,
        key: ReaderPageDecodeKey,
    ): DesktopReaderImageAssetLease? = synchronized(lock) {
        if (closed || holder !in animatedHolders || holder.identity.generation != minimumGeneration) return null
        holder.retainFrameWhileOwnerCurrent(key)
    }

    internal fun publishAnimatedFrameFailure(
        holder: DesktopReaderAnimatedPresentationImageHolder,
        runningJob: Job,
        key: ReaderPageDecodeKey,
    ) {
        synchronized(lock) {
            if (closed || holder !in animatedHolders || holder.identity.generation != minimumGeneration) return
            holder.publishFrameFailureWhileOwnerCurrent(runningJob, key)
        }
    }

    internal fun unregister(holder: DesktopReaderAnimatedPresentationImageHolder) {
        synchronized(lock) {
            animatedHolders.remove(holder)
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

    internal fun createAnimatedHolder(): DesktopReaderAnimatedPresentationImageHolder = owner.createAnimatedHolder(
        identity = identity,
        contentKey = decodeKey.contentKey,
        maxWidth = decodeKey.maxWidth,
        maxHeight = decodeKey.maxHeight,
    )

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

internal data class DesktopReaderAnimatedPresentationImageSnapshot(
    val readyKey: ReaderPageDecodeKey? = null,
    val readyAsset: DesktopReaderImageAsset? = null,
    val drawToken: DesktopReaderAnimationDrawToken? = null,
    val running: Boolean = false,
    val closed: Boolean = false,
)

internal sealed interface DesktopReaderAnimatedFrameAcceptance {
    data object Rejected : DesktopReaderAnimatedFrameAcceptance

    data class Accepted(
        val previousLease: DesktopReaderImageAssetLease?,
    ) : DesktopReaderAnimatedFrameAcceptance
}

/** One mounted animation slot whose frame decodes share one encoded-content session. */
internal class DesktopReaderAnimatedPresentationImageHolder internal constructor(
    val identity: DesktopReaderPresentationImageSlotIdentity,
    private val contentKey: ReaderPageContentOpenRequest,
    private val maxWidth: Int,
    private val maxHeight: Int,
    private val scope: CoroutineScope,
    private val contentSession: DesktopReaderAnimationContentSession,
    private val owner: DesktopReaderPresentationImageOwner,
) : AutoCloseable {
    private val lock = Any()
    private val mutableState = MutableStateFlow(DesktopReaderAnimatedPresentationImageSnapshot())
    private var activeJob: Job? = null
    private var playbackJob: Job? = null
    private var playbackController: DesktopReaderAnimationPlaybackController? = null
    private var activeKey: ReaderPageDecodeKey? = null
    private var readyKey: ReaderPageDecodeKey? = null
    private var readyLease: DesktopReaderImageAssetLease? = null
    private var closed = false

    val state: StateFlow<DesktopReaderAnimatedPresentationImageSnapshot> = mutableState.asStateFlow()

    fun requestFrame(frameIndex: Int) {
        require(frameIndex >= 0) { "Animation frame must be non-negative" }
        if (!owner.isCurrent(this)) return
        val key = ReaderPageDecodeKey(
            contentKey = contentKey,
            purpose = PageDecodePurpose.ANIMATION_FRAME,
            maxWidth = maxWidth,
            maxHeight = maxHeight,
            frameIndex = frameIndex,
        )
        var previousJob: Job? = null
        lateinit var replacementJob: Job
        synchronized(lock) {
            if (closed || readyKey == key || activeKey == key) return
            previousJob = activeJob
            replacementJob = scope.launch(start = CoroutineStart.LAZY) {
                acquireFrame(key)
            }
            activeJob = replacementJob
            activeKey = key
        }
        previousJob?.cancel()
        replacementJob.start()
    }

    fun startPlayback(
        metadata: DesktopReaderAnimationMetadata,
        minimumFrameDurationMillis: Long = MINIMUM_FRAME_DURATION_MILLIS,
    ) {
        if (!owner.isCurrent(this)) return
        lateinit var job: Job
        synchronized(lock) {
            if (closed || playbackJob != null) return
            val controller = DesktopReaderAnimationPlaybackController(metadata, minimumFrameDurationMillis)
            playbackController = controller
            mutableState.value = mutableState.value.copy(
                drawToken = controller.snapshot().drawToken,
                running = true,
            )
            job = scope.launch(start = CoroutineStart.LAZY) {
                play(metadata, controller, minimumFrameDurationMillis)
            }
            playbackJob = job
        }
        job.start()
    }

    fun snapshot(): DesktopReaderAnimatedPresentationImageSnapshot = mutableState.value

    fun retainReadyFrameForRender(key: ReaderPageDecodeKey): DesktopReaderImageAssetLease? =
        owner.retainAnimatedFrame(this, key)

    override fun close() = closeInternal(unregister = true)

    internal fun closeFromOwner() = closeInternal(unregister = false)

    internal fun acceptFrameWhileOwnerCurrent(
        runningJob: Job,
        key: ReaderPageDecodeKey,
        lease: DesktopReaderImageAssetLease,
    ): DesktopReaderAnimatedFrameAcceptance = synchronized(lock) {
        if (closed || activeJob !== runningJob || activeKey != key) {
            return DesktopReaderAnimatedFrameAcceptance.Rejected
        }
        val previous = readyLease
        readyLease = lease
        readyKey = key
        activeJob = null
        activeKey = null
        mutableState.value = DesktopReaderAnimatedPresentationImageSnapshot(
            readyKey = key,
            readyAsset = lease.asset,
            drawToken = playbackController?.snapshot()?.drawToken,
            running = playbackController?.snapshot()?.running == true,
        )
        DesktopReaderAnimatedFrameAcceptance.Accepted(previous)
    }

    internal fun retainFrameWhileOwnerCurrent(key: ReaderPageDecodeKey): DesktopReaderImageAssetLease? =
        synchronized(lock) {
            if (closed || readyKey != key) return null
            readyLease?.retain()
        }

    internal fun publishFrameFailureWhileOwnerCurrent(
        runningJob: Job,
        key: ReaderPageDecodeKey,
    ) {
        synchronized(lock) {
            if (closed || activeJob !== runningJob || activeKey != key) return
            activeJob = null
            activeKey = null
            playbackController?.detach()
            mutableState.value = mutableState.value.copy(running = false)
        }
    }

    private suspend fun acquireFrame(key: ReaderPageDecodeKey) {
        val runningJob = checkNotNull(currentCoroutineContext()[Job])
        var acquiredLease: DesktopReaderImageAssetLease? = null
        var previousLease: DesktopReaderImageAssetLease? = null
        try {
            acquiredLease = contentSession.acquireFrame(key)
            val lease = acquiredLease
            if (lease == null) {
                owner.publishAnimatedFrameFailure(this, runningJob, key)
                return
            }
            when (val acceptance = owner.acceptAnimatedFrame(this, runningJob, key, lease)) {
                DesktopReaderAnimatedFrameAcceptance.Rejected -> Unit
                is DesktopReaderAnimatedFrameAcceptance.Accepted -> {
                    acquiredLease = null
                    previousLease = acceptance.previousLease
                }
            }
        } catch (error: CancellationException) {
            throw error
        } finally {
            acquiredLease?.close()
            previousLease?.close()
            synchronized(lock) {
                if (activeJob === runningJob) {
                    activeJob = null
                    activeKey = null
                }
            }
        }
    }

    private suspend fun play(
        metadata: DesktopReaderAnimationMetadata,
        controller: DesktopReaderAnimationPlaybackController,
        minimumFrameDurationMillis: Long,
    ) {
        val runningJob = checkNotNull(currentCoroutineContext()[Job])
        try {
            requestFrame(0)
            val initial = state.first { snapshot ->
                snapshot.closed || !snapshot.running || snapshot.readyKey?.frameIndex == 0
            }
            if (initial.closed || !initial.running) return
            while (controller.snapshot().running) {
                val before = controller.snapshot()
                val duration = metadata.frames[before.frameIndex].durationMillis
                    .coerceAtLeast(minimumFrameDurationMillis)
                delay(duration)
                controller.advanceBy(duration)
                val after = controller.snapshot()
                if (!after.running) {
                    synchronized(lock) {
                        if (!closed && playbackJob === runningJob) {
                            mutableState.value = mutableState.value.copy(
                                drawToken = after.drawToken,
                                running = false,
                            )
                        }
                    }
                    return
                }
                if (after.frameIndex != before.frameIndex) {
                    requestFrame(after.frameIndex)
                    val decoded = state.first { snapshot ->
                        snapshot.closed || !snapshot.running || snapshot.readyKey?.frameIndex == after.frameIndex
                    }
                    if (decoded.closed || !decoded.running) return
                }
            }
        } finally {
            synchronized(lock) {
                if (playbackJob === runningJob) playbackJob = null
            }
        }
    }

    private fun closeInternal(unregister: Boolean) {
        var jobToCancel: Job? = null
        var playbackToCancel: Job? = null
        var leaseToClose: DesktopReaderImageAssetLease? = null
        synchronized(lock) {
            if (closed) return
            closed = true
            jobToCancel = activeJob
            playbackToCancel = playbackJob
            activeJob = null
            playbackJob = null
            activeKey = null
            playbackController?.detach()
            playbackController = null
            leaseToClose = readyLease
            readyLease = null
            readyKey = null
            mutableState.value = DesktopReaderAnimatedPresentationImageSnapshot(closed = true)
        }
        jobToCancel?.cancel()
        playbackToCancel?.cancel()
        leaseToClose?.close()
        contentSession.close()
        if (unregister) owner.unregister(this)
    }

    private companion object {
        const val MINIMUM_FRAME_DURATION_MILLIS = 10L
    }
}
