package mihon.desktop.reader

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import mihon.domain.reader.PageDecodePurpose
import mihon.domain.reader.PageDecodeRequest
import mihon.domain.reader.PageDecodeResult
import mihon.domain.reader.PageDecoder
import mihon.domain.reader.ReaderPageDecodeKey
import mihon.domain.reader.content.ReaderPageContentLease
import mihon.domain.reader.content.ReaderPageContentOpenRequest
import mihon.domain.reader.observability.ReaderIoEventType
import mihon.domain.reader.observability.ReaderIoPurpose
import mihon.domain.reader.observability.ReaderIoReporter
import mihon.domain.reader.session.ReaderEncodedPageProvenance
import mihon.domain.reader.session.ReaderPageId
import java.util.concurrent.atomic.AtomicReference
import org.jetbrains.skia.Bitmap as SkiaBitmap
import org.jetbrains.skia.Canvas as SkiaCanvas
import org.jetbrains.skia.Codec as SkiaCodec
import org.jetbrains.skia.Data as SkiaData
import org.jetbrains.skia.Image as SkiaImage
import org.jetbrains.skia.Rect as SkiaRect

/** Platform decoder boundary owned exclusively by [DesktopReaderPageImagePipeline]. */
internal fun interface DesktopReaderPageImageDecoder {
    suspend fun decode(
        encoded: ByteArray,
        key: ReaderPageDecodeKey,
    ): DesktopReaderImageAssetLease?
}

/** Production Skia adapter for ordinary full pages and purpose-keyed animation frames. */
internal class SkiaDesktopReaderPageImageDecoder(
    private val decoder: PageDecoder<ByteArray, ImageBitmap> = SkiaPageDecoder(),
    private val regionDecoder: mihon.domain.reader.RegionDecoder<ByteArray, ImageBitmap> = SkiaRegionPageDecoder(),
    private val memoryAuthority: DesktopReaderImageMemoryAuthority? = null,
) : DesktopReaderPageImageDecoder {
    override suspend fun decode(
        encoded: ByteArray,
        key: ReaderPageDecodeKey,
    ): DesktopReaderImageAssetLease? {
        if (key.purpose == PageDecodePurpose.REGION_TILE) return decodeRegion(encoded, key)
        if (key.purpose == PageDecodePurpose.ANIMATION_FRAME) return decodeAnimationFrame(encoded, key)
        val sourceSize = SkiaImageDecoder.peekSize(encoded) ?: return null
        val animationMetadata = inspectAnimationMetadata(encoded)
        if (decoder is SkiaPageDecoder && animationMetadata == null &&
            SkiaImageDecoder.originalDecodePeakBytes(
                sourceSize.first,
                sourceSize.second,
                key.maxWidth,
                key.maxHeight,
            ) <=
            (memoryAuthority?.maxBytes ?: DesktopReaderImageMemoryAuthority().maxBytes)
        ) {
            val (bitmap, samples) = decoder.decodeWithOriginal(
                encoded,
                PageDecodeRequest(key.pageIndex, key.generation, key.maxWidth, key.maxHeight),
                { bytes ->
                    val authority = memoryAuthority
                    if (authority == null) {
                        AutoCloseable {}
                    } else {
                        val lease = authority.register(
                            DesktopReaderImageMemoryKind.DERIVED,
                            bytes,
                            DesktopReaderImageMemoryRetention.IN_FLIGHT,
                        ) {
                        }
                        if (authority.snapshot().overBudgetBytes > 0) {
                            lease.close()
                            throw DesktopReaderOptionalWorkPaused()
                        }
                        lease
                    }
                },
            )
            return DesktopReaderImageAsset(
                bitmap = bitmap,
                sourceWidth = sourceSize.first,
                sourceHeight = sourceSize.second,
                estimatedBytes = bitmap.width.toLong() * bitmap.height * BYTES_PER_PIXEL,
                sampled = bitmap.width != sourceSize.first || bitmap.height != sourceSize.second,
                originalSamples = samples,
                sourceOrientation = mihon.domain.reader.readImageOrientation(encoded),
                disposer = bitmap.asSkiaBitmap()::close,
            )
        }
        return when (
            val result = decoder.decode(
                encoded,
                PageDecodeRequest(
                    pageIndex = key.pageIndex,
                    generation = key.generation,
                    maxWidth = key.maxWidth,
                    maxHeight = key.maxHeight,
                ),
            )
        ) {
            is PageDecodeResult.Failure -> null
            is PageDecodeResult.Success -> DesktopReaderImageAsset(
                bitmap = result.value,
                sourceWidth = sourceSize.first,
                sourceHeight = sourceSize.second,
                estimatedBytes = result.estimatedBytes,
                sampled = result.isSampled,
                animationMetadata = animationMetadata,
                sourceOrientation = mihon.domain.reader.readImageOrientation(encoded),
                disposer = result.value.asSkiaBitmap()::close,
            )
        }
    }

    private suspend fun decodeRegion(
        encoded: ByteArray,
        key: ReaderPageDecodeKey,
    ): DesktopReaderImageAssetLease? {
        val sourceSize = SkiaImageDecoder.peekSize(encoded) ?: return null
        return when (
            val result = regionDecoder.decodeRegion(
                encoded,
                PageDecodeRequest(
                    pageIndex = key.pageIndex,
                    generation = key.generation,
                    maxWidth = key.maxWidth,
                    maxHeight = key.maxHeight,
                    region = key.region,
                ),
            )
        ) {
            is PageDecodeResult.Failure -> null
            is PageDecodeResult.Success -> DesktopReaderImageAsset(
                bitmap = result.value,
                sourceWidth = sourceSize.first,
                sourceHeight = sourceSize.second,
                estimatedBytes = result.estimatedBytes,
                sampled = result.isSampled,
                disposer = result.value.asSkiaBitmap()::close,
            )
        }
    }

    private fun decodeAnimationFrame(
        encoded: ByteArray,
        key: ReaderPageDecodeKey,
    ): DesktopReaderImageAssetLease? = try {
        SkiaData.makeFromBytes(encoded).use { data ->
            SkiaCodec.makeFromData(data).use { codec ->
                val frameIndex = requireNotNull(key.frameIndex)
                require(frameIndex < codec.frameCount) { "Animation frame exceeds source frame count" }
                val decoded = SkiaBitmap()
                check(decoded.allocPixels(codec.imageInfo)) { "Unable to allocate animation frame bitmap" }
                try {
                    codec.readPixels(decoded, frameIndex)
                    val sampleSize = boundedSampleSize(codec.width, codec.height, key.maxWidth, key.maxHeight)
                    val output = if (sampleSize == 1) {
                        decoded
                    } else {
                        val targetWidth = ceilDiv(codec.width, sampleSize)
                        val targetHeight = ceilDiv(codec.height, sampleSize)
                        val target = SkiaBitmap()
                        try {
                            check(target.allocN32Pixels(targetWidth, targetHeight)) {
                                "Unable to allocate sampled animation frame bitmap"
                            }
                            SkiaCanvas(target).use { canvas ->
                                canvas.clear(0x00000000)
                                SkiaImage.makeFromBitmap(decoded).use { image ->
                                    canvas.drawImageRect(
                                        image,
                                        SkiaRect.makeWH(codec.width.toFloat(), codec.height.toFloat()),
                                        SkiaRect.makeWH(targetWidth.toFloat(), targetHeight.toFloat()),
                                    )
                                }
                            }
                            decoded.close()
                            target
                        } catch (error: Throwable) {
                            target.close()
                            throw error
                        }
                    }
                    try {
                        DesktopReaderImageAsset(
                            bitmap = output.asComposeImageBitmap(),
                            sourceWidth = codec.width,
                            sourceHeight = codec.height,
                            estimatedBytes = output.width.toLong() * output.height * BYTES_PER_PIXEL,
                            sampled = sampleSize > 1,
                            animationMetadata = animationMetadata(codec),
                            disposer = output::close,
                        )
                    } catch (error: Throwable) {
                        output.close()
                        throw error
                    }
                } catch (error: Throwable) {
                    if (!decoded.isClosed) decoded.close()
                    throw error
                }
            }
        }
    } catch (_: Exception) {
        null
    }

    private fun inspectAnimationMetadata(encoded: ByteArray): DesktopReaderAnimationMetadata? = try {
        SkiaData.makeFromBytes(encoded).use { data ->
            SkiaCodec.makeFromData(data).use(::animationMetadata)
        }
    } catch (_: Exception) {
        null
    }

    private fun animationMetadata(codec: SkiaCodec): DesktopReaderAnimationMetadata? {
        if (codec.frameCount < 2) return null
        val bounds = mihon.domain.reader.PixelBounds(0, 0, codec.width, codec.height)
        return DesktopReaderAnimationMetadata(
            frames = codec.framesInfo.map { frame ->
                DesktopReaderAnimationFrameMetadata(
                    durationMillis = frame.duration.toLong().coerceAtLeast(0L),
                    bounds = bounds,
                )
            },
            repeatCount = codec.repetitionCount.takeIf { it >= 0 },
        )
    }

    private fun boundedSampleSize(
        width: Int,
        height: Int,
        maxWidth: Int,
        maxHeight: Int,
    ): Int = maxOf(
        ceilDiv(width, maxWidth),
        ceilDiv(height, maxHeight),
        1,
    )

    private fun ceilDiv(value: Int, divisor: Int): Int =
        ((value.toLong() + divisor - 1L) / divisor).toInt()

    private companion object {
        const val BYTES_PER_PIXEL = 4L
    }
}

internal data class DesktopReaderPageImagePipelineSnapshot(
    val minimumGeneration: Long,
    val cache: DesktopReaderImageCacheSnapshot,
    val tileCache: DesktopReaderImageCacheSnapshot,
    val memory: DesktopReaderImageMemorySnapshot,
    val inFlightCount: Int,
    val closed: Boolean,
)

/** A pairing consumer may become required while its existing decode is queued. */
internal class DesktopReaderPairingDecodeDemand(speculative: Boolean) {
    @Volatile
    var speculative = speculative
        private set

    fun promote() {
        speculative = false
    }
}

/**
 * Unique Desktop owner for encoded-content acquisition, full-page decode single-flight, and decoded caching.
 *
 * Presentation and transform consumers receive independently closeable leases. Static encoded content is
 * released immediately after decode; animated content is pinned by one bounded, reference-counted session
 * shared by mounted presentations and released with the final animation lease.
 */
class DesktopReaderPageImagePipeline internal constructor(
    private val scope: CoroutineScope,
    private val pageContentOwner: DesktopReaderPageContentOwner,
    private val ioReporter: ReaderIoReporter,
    decoder: DesktopReaderPageImageDecoder? = null,
    maxEntries: Int = DEFAULT_CACHE_ENTRIES,
    maxBytes: Long = DEFAULT_CACHE_BYTES,
    internal val memoryAuthority: DesktopReaderImageMemoryAuthority = DesktopReaderImageMemoryAuthority(
        if (maxBytes == DEFAULT_CACHE_BYTES) DEFAULT_TOTAL_IMAGE_BYTES else maxBytes,
    ),
    maxConcurrentDecodes: Int = DEFAULT_CONCURRENT_DECODES,
    private val partialDecodeFailureHandler: suspend (ReaderPageContentOpenRequest) -> Boolean = { false },
) : AutoCloseable {
    private val decoder: DesktopReaderPageImageDecoder =
        decoder ?: SkiaDesktopReaderPageImageDecoder(memoryAuthority = memoryAuthority)
    private data class PageGenerationIdentity(
        val pageId: ReaderPageId,
        val generation: Long,
    )

    private class Entry {
        lateinit var deferred: Deferred<DesktopReaderImageAssetLease?>
        var pendingAcquires: Int = 0
        var decodedLease: DesktopReaderImageAssetLease? = null
        var fullCacheClaimed: Boolean = false
        var foreground = false
        var optional = false
        var optionalAdmission = false
        var withdrawnForeground = false
        var realForeground = false
        var withdrawnOptional = false
        var ordinaryOwned = false
        var visibleOwned = false
        var withdrawn = false
        var contentOpenPending = false
        var startedSpeculative = false
        val pairingConsumers = mutableMapOf<Any, DesktopReaderPairingDecodeDemand>()

        fun speculativeOnly() =
            !ordinaryOwned && pairingConsumers.isNotEmpty() && pairingConsumers.values.all { it.speculative }
        fun priority() = when {
            visibleOwned -> 0
            pairingConsumers.values.any { !it.speculative } -> 1
            else -> 2
        }
    }

    private class DecodeWaiter(val key: ReaderPageDecodeKey, val entry: Entry) {
        val ready = CompletableDeferred<Unit>()
    }

    private class ContentSessionEntry(
        val content: DesktopReaderSharedPageContent,
        var references: Int = 0,
    )

    private val lock = Any()
    private val mutableCacheRevision = MutableStateFlow(0L)
    private val cache = DesktopReaderImageCache(
        maxEntries = maxEntries,
        maxBytes = memoryAuthority.maxBytes,
        memoryAuthority = memoryAuthority,
        ownsMemoryAuthority = false,
        onAuthorityEviction = { mutableCacheRevision.value++ },
    )
    private val tileCache = DesktopReaderImageCache(
        maxEntries = DEFAULT_TILE_CACHE_ENTRIES,
        maxBytes = memoryAuthority.maxBytes,
        memoryAuthority = memoryAuthority,
        ownsMemoryAuthority = false,
    )
    private val decodeLimit = maxConcurrentDecodes
    private val decodeWaiters = mutableListOf<DecodeWaiter>()
    private val activeDecodes = mutableSetOf<Entry>()
    private val entries = mutableMapOf<ReaderPageDecodeKey, Entry>()
    private val contentSessions = mutableMapOf<ReaderPageContentOpenRequest, ContentSessionEntry>()
    private val minimumPageAttempts = mutableMapOf<PageGenerationIdentity, Long>()
    private var minimumGeneration = 0L
    private var clearing = false
    private var closed = false

    init {
        require(maxConcurrentDecodes > 0) { "maxConcurrentDecodes must be positive" }
    }

    internal val cacheRevision: StateFlow<Long> = mutableCacheRevision.asStateFlow()

    /** Queued or executing original work; completed cache and presentation leases are not busy. */
    internal val foregroundWork: StateFlow<Int> = mutableForegroundWork.asStateFlow()

    /**
     * The foreground work a user visible decode owns.
     *
     * [foregroundWork] also counts the publisher's own pairing consumers, so a pairing
     * capability that waits for an idle foreground would wait for itself. This counter
     * only counts the work a visible page owns, which is the work a pairing capability
     * must not compete with. It is shared by every runtime, because a closed runtime
     * cannot end native work that is still executing.
     */
    internal val visibleForegroundWork: StateFlow<Int> = mutableVisibleForegroundWork.asStateFlow()

    /** Optional-role Entries remain counted until the underlying decode actually completes. */
    internal val optionalWork: StateFlow<Int> = mutableOptionalWork.asStateFlow()

    internal suspend fun acquire(key: ReaderPageDecodeKey): DesktopReaderImageAssetLease? =
        acquireWithLoader(key) { entry -> load(key, entry) }

    internal suspend fun acquireForPairing(
        key: ReaderPageDecodeKey,
        speculative: Boolean,
    ): DesktopReaderImageAssetLease? =
        acquireForPairing(key, DesktopReaderPairingDecodeDemand(speculative))

    internal suspend fun acquireForPairing(
        key: ReaderPageDecodeKey,
        demand: DesktopReaderPairingDecodeDemand,
    ): DesktopReaderImageAssetLease? =
        acquireWithLoader(key, pairingDemand = demand) { entry -> load(key, entry) }

    /** Promotion keeps the existing physical operation and its content/decode identity. */
    internal fun promotePairing(key: ReaderPageDecodeKey) {
        val admitted = synchronized(lock) {
            if (closed || clearing) return
            entries.forEach { (candidate, entry) ->
                if (candidate.contentKey == key.contentKey && entries[candidate] === entry && !entry.withdrawn &&
                    entry.pairingConsumers.isNotEmpty()
                ) {
                    entry.pairingConsumers.values.forEach { it.promote() }
                    entry.startedSpeculative = false
                    updateEntryWorkRoleLocked(entry)
                }
            }
            admitDecodesLocked()
        }
        signalDecodes(admitted)
    }

    /** Promotes the live pairing demand across its full-page or region Entries for this exact content. */
    internal fun promotePairing(
        contentKey: ReaderPageContentOpenRequest,
        demand: DesktopReaderPairingDecodeDemand,
    ) {
        val admitted = synchronized(lock) {
            if (closed || clearing) return
            entries.forEach { (key, entry) ->
                if (key.contentKey == contentKey && entries[key] === entry && !entry.withdrawn &&
                    entry.pairingConsumers.values.any { it === demand }
                ) {
                    entry.startedSpeculative = false
                    updateEntryWorkRoleLocked(entry)
                }
            }
            admitDecodesLocked()
        }
        signalDecodes(admitted)
    }

    internal fun openAnimationSession(
        contentKey: ReaderPageContentOpenRequest,
    ): DesktopReaderAnimationContentSession = synchronized(lock) {
        val entry = retainContentSessionLocked(contentKey, "Animation")
        DesktopReaderAnimationContentSession(
            contentKey = contentKey,
            sharedContent = entry.content,
            pipeline = this,
        )
    }

    internal fun openRegionSession(
        contentKey: ReaderPageContentOpenRequest,
    ): DesktopReaderRegionContentSession = synchronized(lock) {
        val entry = retainContentSessionLocked(contentKey, "Region")
        DesktopReaderRegionContentSession(
            contentKey = contentKey,
            sharedContent = entry.content,
            pipeline = this,
        )
    }

    internal suspend fun acquireAnimationFrame(
        session: DesktopReaderAnimationContentSession,
        key: ReaderPageDecodeKey,
    ): DesktopReaderImageAssetLease? {
        require(key.purpose == PageDecodePurpose.ANIMATION_FRAME) {
            "Animation sessions only accept animation-frame requests"
        }
        require(key.contentKey == session.contentKey) {
            "Animation frame and source session identities must match"
        }
        return acquireWithLoader(key) { entry ->
            session.withContent { encoded -> decodeContent(key, encoded, entry) }
        }
    }

    internal suspend fun acquireRegionTile(
        session: DesktopReaderRegionContentSession,
        key: ReaderPageDecodeKey,
        optional: Boolean = false,
        pairingDemand: DesktopReaderPairingDecodeDemand? = null,
    ): DesktopReaderImageAssetLease? {
        require(key.purpose == PageDecodePurpose.REGION_TILE) {
            "Region sessions only accept region-tile requests"
        }
        require(key.contentKey == session.contentKey) {
            "Region tile and source session identities must match"
        }
        return acquireWithLoader(key, optional, pairingDemand) { entry ->
            session.withContent { encoded -> decodeContent(key, encoded, entry) }
        }
    }

    /** Caches a region only after its mounted presentation has accepted ownership. */
    internal fun commitRegionTile(
        key: ReaderPageDecodeKey,
        acceptedLease: DesktopReaderImageAssetLease,
    ): Boolean {
        require(key.purpose == PageDecodePurpose.REGION_TILE) {
            "Only accepted region tiles may enter the tile cache"
        }
        val current = synchronized(lock) {
            !closed && !clearing && isCurrentAttemptLocked(key.contentKey)
        }
        if (!current) return false
        if (!tileCache.commit(key, acceptedLease)) return false
        val stillCurrent = synchronized(lock) {
            !closed && !clearing && isCurrentAttemptLocked(key.contentKey)
        }
        if (stillCurrent) return true
        tileCache.removeWhere { candidate -> candidate == key }
        return false
    }

    private suspend fun acquireWithLoader(
        key: ReaderPageDecodeKey,
        optional: Boolean = false,
        pairingDemand: DesktopReaderPairingDecodeDemand? = null,
        loader: suspend (Entry) -> DesktopReaderImageAssetLease?,
    ): DesktopReaderImageAssetLease? {
        val consumer = Any()
        lateinit var entry: Entry
        while (true) {
            var waitForExit = false
            var admitted = emptyList<DecodeWaiter>()
            entry = synchronized(lock) {
                check(!closed) { "Desktop reader page image pipeline is closed" }
                if (clearing || !isCurrentAttemptLocked(key.contentKey)) return null
                cacheFor(key)?.acquire(key)?.let { return it }
                if (pairingDemand?.speculative == true && decodeLimit == 1) return null
                val existing = entries[key]
                if (existing?.withdrawn == true) {
                    waitForExit = true
                    existing
                } else {
                    (
                        existing ?: run {
                            createEntry(key, loader, optional && pairingDemand == null).also { entries[key] = it }
                        }
                        ).also {
                        if (pairingDemand == null) {
                            // Preserve ordinary unfinished-owner semantics, including optional mounted tiles.
                            it.ordinaryOwned = true
                            if (!optional) it.visibleOwned = true
                        } else {
                            it.pairingConsumers[consumer] = pairingDemand
                        }
                        if (!it.speculativeOnly()) it.startedSpeculative = false
                        updateEntryWorkRoleLocked(it)
                        it.pendingAcquires++
                        admitted = admitDecodesLocked()
                    }
                }
            }
            if (waitForExit) {
                // A cancelled coroutine can still own an executing native decode. Never revive it.
                entry.deferred.join()
                synchronized(lock) { if (entries[key] === entry) entries.remove(key) }
                continue
            }
            signalDecodes(admitted)
            entry.deferred.start()
            break
        }
        val decoded = try {
            entry.deferred.await()
        } catch (error: Throwable) {
            releasePending(key, entry, consumer)
            throw error
        }
        if (decoded == null) {
            releasePending(key, entry, consumer)
            return null
        }

        var entryLeaseToClose: DesktopReaderImageAssetLease? = null
        var shouldCommitFull = false
        var callerAcquireFailure: Throwable? = null
        val callerLease = synchronized(lock) {
            entry.pendingAcquires--
            entry.pairingConsumers.remove(consumer)
            val accepted = !closed &&
                !clearing &&
                isCurrentAttemptLocked(key.contentKey) &&
                entries[key] === entry
            val lease = if (accepted) {
                try {
                    decoded.retain()
                } catch (error: Throwable) {
                    callerAcquireFailure = error
                    null
                }
            } else {
                null
            }
            if (
                lease != null &&
                key.purpose == PageDecodePurpose.FULL_PAGE &&
                decoded.asset.animationMetadata == null &&
                !entry.fullCacheClaimed
            ) {
                entry.fullCacheClaimed = true
                shouldCommitFull = true
            }
            entryLeaseToClose = removeIfUnusedLocked(key, entry)
            lease
        }
        callerAcquireFailure?.let { error ->
            try {
                entryLeaseToClose?.close()
            } catch (closeError: Throwable) {
                if (closeError !== error) error.addSuppressed(closeError)
            }
            throw error
        }
        if (callerLease == null) {
            entryLeaseToClose?.close()
            throw CancellationException("Desktop reader page image request became stale")
        }
        var failure: Throwable? = null
        if (shouldCommitFull) {
            try {
                if (cache.commit(key, callerLease)) {
                    val stillCurrent = synchronized(lock) {
                        !closed && !clearing && isCurrentAttemptLocked(key.contentKey)
                    }
                    if (stillCurrent) {
                        mutableCacheRevision.value++
                    } else {
                        cache.removeWhere { candidate -> candidate == key }
                    }
                }
            } catch (error: Throwable) {
                failure = error
            }
        }
        entryLeaseToClose?.let { lease ->
            try {
                lease.close()
            } catch (closeError: Throwable) {
                val first = failure
                if (first == null) {
                    failure = closeError
                } else if (closeError !== first) {
                    first.addSuppressed(closeError)
                }
            }
        }
        if (failure != null) {
            try {
                callerLease.close()
            } catch (closeError: Throwable) {
                val first = checkNotNull(failure)
                if (closeError !== first) first.addSuppressed(closeError)
            }
        }
        failure?.let { throw it }
        return callerLease
    }

    /** Shutdown cancels optional scalar inspection rather than starting missing work on a closed runtime. */
    internal fun acquireCachedForAnalysis(
        key: ReaderPageDecodeKey,
    ): DesktopReaderImageAssetLease? = synchronized(lock) {
        if (closed) throw CancellationException("Desktop reader page image pipeline closed")
        acquireCached(key)
    }

    internal fun acquireCached(key: ReaderPageDecodeKey): DesktopReaderImageAssetLease? = synchronized(lock) {
        check(!closed) { "Desktop reader page image pipeline is closed" }
        if (clearing || !isCurrentAttemptLocked(key.contentKey)) return null
        cacheFor(key)?.acquire(key)
    }

    /** Retains the most recently used current FULL_PAGE asset for every cached logical page. */
    internal fun retainCachedFullPageAssets(): Map<Int, DesktopReaderImageAssetLease> {
        val keys = synchronized(lock) {
            check(!closed) { "Desktop reader page image pipeline is closed" }
            if (clearing) return emptyMap()
            cache.snapshot().keys.filter { key ->
                key.purpose == PageDecodePurpose.FULL_PAGE && isCurrentAttemptLocked(key.contentKey)
            }
        }
        val retained = linkedMapOf<Int, DesktopReaderImageAssetLease>()
        try {
            keys.forEach { key ->
                val lease = acquireCached(key) ?: return@forEach
                retained.put(key.pageIndex, lease)?.close()
            }
        } catch (error: Throwable) {
            try {
                runResourceActions(retained.values.map { lease -> lease::close })
            } catch (closeError: Throwable) {
                if (closeError !== error) error.addSuppressed(closeError)
            }
            throw error
        }
        return retained
    }

    /** Advances Retry identity for one logical page without disturbing another page or generation. */
    internal fun beginPageAttempt(
        pageId: ReaderPageId,
        generation: Long,
        attemptGeneration: Long,
    ): Boolean {
        require(generation >= 0L) { "generation must be non-negative" }
        require(attemptGeneration >= 0L) { "attemptGeneration must be non-negative" }
        val detached = synchronized(lock) {
            check(!closed) { "Desktop reader page image pipeline is closed" }
            if (generation < minimumGeneration) return false
            val identity = PageGenerationIdentity(pageId, generation)
            val previousAttempt = minimumPageAttempts[identity] ?: 0L
            if (attemptGeneration < previousAttempt) return false
            if (attemptGeneration == previousAttempt) return true
            minimumPageAttempts[identity] = attemptGeneration

            val staleEntries = entries.filterKeys { key ->
                key.contentKey.isOlderAttempt(pageId, generation, attemptGeneration)
            }
            staleEntries.keys.forEach(entries::remove)
            val staleSessions = contentSessions.filterKeys { contentKey ->
                contentKey.isOlderAttempt(pageId, generation, attemptGeneration)
            }
            staleSessions.keys.forEach(contentSessions::remove)
            staleEntries.values.toList() to staleSessions.values.map(ContentSessionEntry::content)
        }

        val staleKey: (ReaderPageDecodeKey) -> Boolean = { key ->
            key.contentKey.isOlderAttempt(pageId, generation, attemptGeneration)
        }
        val hadFullCacheEntries = cache.snapshot().keys.any(staleKey)
        runResourceActions(
            buildList {
                add { cache.removeWhere(staleKey) }
                add { tileCache.removeWhere(staleKey) }
                if (hadFullCacheEntries) {
                    add {
                        synchronized(lock) {
                            if (!closed) mutableCacheRevision.value++
                        }
                    }
                }
                detached.first.forEach { entry -> add { entry.deferred.cancel() } }
                add { releaseDetachedCompleted(detached.first) }
                detached.second.forEach { content -> add(content::close) }
            },
        )
        return true
    }

    /** Advances the accepted generation and rejects all older cache and in-flight results. */
    internal fun beginGeneration(generation: Long): Boolean {
        require(generation >= 0L) { "generation must be non-negative" }
        val stale = synchronized(lock) {
            check(!closed) { "Desktop reader page image pipeline is closed" }
            if (generation < minimumGeneration) return false
            if (generation == minimumGeneration) return true
            minimumGeneration = generation
            minimumPageAttempts.keys.removeAll { identity -> identity.generation < generation }
            val stale = entries.filterKeys { it.generation < generation }
            stale.keys.forEach(entries::remove)
            mutableCacheRevision.value++
            val staleSessions = contentSessions.filterKeys { it.generation < generation }
            staleSessions.keys.forEach(contentSessions::remove)
            stale.values.toList() to staleSessions.values.map(ContentSessionEntry::content)
        }
        runResourceActions(
            buildList {
                add { cache.removeWhere { key -> key.generation < generation } }
                add { tileCache.removeWhere { key -> key.generation < generation } }
                stale.first.forEach { entry -> add { entry.deferred.cancel() } }
                add { releaseDetachedCompleted(stale.first) }
                stale.second.forEach { content -> add(content::close) }
            },
        )
        return true
    }

    internal fun clear() {
        val detachedEntries = synchronized(lock) {
            check(!closed) { "Desktop reader page image pipeline is closed" }
            clearing = true
            val detached = entries.values.toList()
            entries.clear()
            mutableCacheRevision.value++
            detached
        }
        try {
            runResourceActions(
                buildList {
                    detachedEntries.forEach { entry -> add { entry.deferred.cancel() } }
                    add { releaseDetachedCompleted(detachedEntries) }
                    add(cache::clear)
                    add(tileCache::clear)
                },
            )
        } finally {
            synchronized(lock) { clearing = false }
        }
    }

    internal fun snapshot(): DesktopReaderPageImagePipelineSnapshot = synchronized(lock) {
        DesktopReaderPageImagePipelineSnapshot(
            minimumGeneration = minimumGeneration,
            cache = cache.snapshot(),
            tileCache = tileCache.snapshot(),
            memory = memoryAuthority.snapshot(),
            inFlightCount = entries.size,
            closed = closed,
        )
    }

    override fun close() {
        val detached = synchronized(lock) {
            if (closed) return
            closed = true
            val detachedEntries = entries.values.toList()
            val detachedSessions = contentSessions.values.map(ContentSessionEntry::content)
            entries.clear()
            contentSessions.clear()
            mutableCacheRevision.value++
            detachedEntries to detachedSessions
        }
        runResourceActions(
            buildList {
                detached.first.forEach { entry -> add { entry.deferred.cancel() } }
                add { releaseDetachedCompleted(detached.first) }
                detached.second.forEach { content -> add(content::close) }
                add(cache::close)
                add(tileCache::close)
                add(memoryAuthority::close)
            },
        )
    }

    private fun createEntry(
        key: ReaderPageDecodeKey,
        loader: suspend (Entry) -> DesktopReaderImageAssetLease?,
        optional: Boolean,
    ): Entry {
        val entry = Entry()
        if (optional) {
            // The explicit optional-work token is distinct from a speculative pairing role. Both
            // are counted as optional work, but only this token participates in admission throttling.
            if (!activeOptionalAdmission.compareAndSet(null, entry)) {
                throw DesktopReaderOptionalWorkPaused()
            }
            entry.optionalAdmission = true
        }
        entry.deferred = scope.async(start = CoroutineStart.LAZY) {
            withRegionDecodeSlot(key.purpose == PageDecodePurpose.REGION_TILE) {
                val decoded = loader(entry)
                try {
                    decoded?.asset?.attachMemoryAuthority(
                        authority = memoryAuthority,
                        kind = key.purpose.toImageMemoryKind(),
                        retention = DesktopReaderImageMemoryRetention.IN_FLIGHT,
                    )
                } catch (error: Throwable) {
                    try {
                        decoded?.close()
                    } catch (closeError: Throwable) {
                        if (closeError !== error) error.addSuppressed(closeError)
                    }
                    if (synchronized(lock) { closed }) {
                        throw CancellationException("Desktop reader page image pipeline closed").apply {
                            initCause(error)
                        }
                    }
                    throw error
                }
                synchronized(lock) {
                    entry.decodedLease = decoded
                }
                decoded
            }
        }
        entry.deferred.invokeOnCompletion {
            var leaseToClose: DesktopReaderImageAssetLease? = null
            synchronized(lock) {
                if (entry.optionalAdmission) {
                    entry.optionalAdmission = false
                    check(activeOptionalAdmission.compareAndSet(entry, null)) {
                        "Optional admission token no longer belongs to its Entry"
                    }
                }
                updateEntryWorkRoleLocked(entry)
                leaseToClose = removeIfUnusedLocked(key, entry)
            }
            leaseToClose?.close()
        }
        return entry
    }

    /**
     * Runs one decode while holding the shared native region decoder.
     *
     * The slot belongs to the decode itself: it is released when the decode ends, so a
     * cancelled caller never frees a resource that the native work still holds. The
     * waiting decode is cancelled together with its pipeline.
     */
    private suspend fun <T> withRegionDecodeSlot(region: Boolean, block: suspend () -> T): T {
        if (!region) return block()
        val token = Any()
        while (!activeRegionDecode.compareAndSet(null, token)) {
            delay(REGION_DECODE_POLL_MILLIS)
        }
        return try {
            block()
        } finally {
            activeRegionDecode.compareAndSet(token, null)
        }
    }

    /** Counts each live physical Entry by its current role; optional admission is a separate token. */
    private fun updateEntryWorkRoleLocked(entry: Entry) {
        val active = !entry.deferred.isCompleted
        val foreground = active && (
            entry.visibleOwned || entry.pairingConsumers.values.any { !it.speculative } ||
                entry.withdrawnForeground
            )
        val optional = active && (
            entry.optionalAdmission ||
                (!foreground && (entry.speculativeOnly() || entry.withdrawnOptional))
            )
        if (entry.foreground != foreground) {
            entry.foreground = foreground
            mutableForegroundWork.update { count -> count + if (foreground) 1 else -1 }
        }
        val realForeground = active && entry.visibleOwned
        if (entry.realForeground != realForeground) {
            entry.realForeground = realForeground
            mutableVisibleForegroundWork.update { count -> count + if (realForeground) 1 else -1 }
        }
        if (entry.optional != optional) {
            entry.optional = optional
            mutableOptionalWork.update { count -> count + if (optional) 1 else -1 }
        }
    }

    private suspend fun load(key: ReaderPageDecodeKey, entry: Entry): DesktopReaderImageAssetLease? {
        val canOpenContent = synchronized(lock) {
            if (closed || clearing || entry.withdrawn || entries[key] !== entry) {
                false
            } else {
                entry.contentOpenPending = true
                true
            }
        }
        if (!canOpenContent) throw CancellationException("Desktop reader content request was withdrawn before open")
        var contentLease: ReaderPageContentLease<ByteArray>? = null
        var contentOwnershipTransferred = false
        try {
            try {
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) {
                    // Keep the physical owner read and its Entry role alive through the real reader exit.
                    // Capture the lease inside the bridge so prompt cancellation cannot lose it on return.
                    contentLease = pageContentOwner.acquire(key.contentKey)
                }
            } finally {
                synchronized(lock) {
                    entry.contentOpenPending = false
                    updateEntryWorkRoleLocked(entry)
                }
            }
            currentCoroutineContext().ensureActive()
            val withdrawn = synchronized(lock) {
                closed || clearing || entry.withdrawn || entries[key] !== entry
            }
            if (withdrawn) {
                throw CancellationException("Desktop reader content request was withdrawn before decode")
            }
            val acquiredContentLease = contentLease
            if (acquiredContentLease == null) {
                handlePartialDecodeFailure(key)
                return null
            }
            return decodeContent(key, acquiredContentLease.content, entry)?.also { decoded ->
                if (decoded.asset.animationMetadata != null) {
                    decoded.asset.addFinalDisposer(acquiredContentLease::close)
                    contentOwnershipTransferred = true
                }
            }
        } finally {
            if (!contentOwnershipTransferred) contentLease?.close()
        }
    }

    private suspend fun decodeContent(
        key: ReaderPageDecodeKey,
        encoded: ByteArray,
        entry: Entry,
    ): DesktopReaderImageAssetLease? = withDecodeAdmission(key, entry) {
        ioReporter.report(
            type = ReaderIoEventType.DECODE,
            chapterId = key.contentKey.pageId.chapterId,
            pageId = key.contentKey.pageId,
            generation = key.generation,
            purpose = ReaderIoPurpose.VISIBLE_DECODE,
        )
        try {
            ReaderStartupTrace.mark("DECODER_CALL_BEGIN", key.contentKey.pageId, key.generation)
            decoder.decode(encoded, key).also { decoded ->
                ReaderStartupTrace.mark("DECODER_CALL_END", key.contentKey.pageId, key.generation)

                if (decoded == null) handlePartialDecodeFailure(key)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (!handlePartialDecodeFailure(key)) throw error
            null
        }
    }

    private suspend fun handlePartialDecodeFailure(key: ReaderPageDecodeKey): Boolean =
        key.purpose == PageDecodePurpose.FULL_PAGE &&
            key.contentKey.encodedPageProvenance is ReaderEncodedPageProvenance.Partial &&
            partialDecodeFailureHandler(key.contentKey)

    private fun releasePending(key: ReaderPageDecodeKey, entry: Entry, consumer: Any) {
        var leaseToClose: DesktopReaderImageAssetLease? = null
        var cancel: Deferred<DesktopReaderImageAssetLease?>? = null
        synchronized(lock) {
            if (entry.pendingAcquires > 0) entry.pendingAcquires--
            entry.pairingConsumers.remove(consumer)
            if (!entry.ordinaryOwned && entry.pendingAcquires == 0 && !entry.deferred.isCompleted) {
                entry.withdrawn = true
                if (entry in activeDecodes || entry.contentOpenPending) {
                    entry.withdrawnForeground = entry.foreground
                    entry.withdrawnOptional = entry.optional
                }
                if (!entry.contentOpenPending) cancel = entry.deferred
            }
            updateEntryWorkRoleLocked(entry)
            leaseToClose = removeIfUnusedLocked(key, entry)
        }
        cancel?.cancel()
        leaseToClose?.close()
    }

    /** Called only under lock; continuations are signalled outside it. */
    private fun admitDecodesLocked(): List<DecodeWaiter> = buildList {
        while (activeDecodes.size < decodeLimit) {
            val speculativeActive = activeDecodes.count { it.startedSpeculative }
            val next = decodeWaiters.filter {
                !it.entry.withdrawn && entries[it.key] === it.entry &&
                    (!it.entry.speculativeOnly() || speculativeActive < decodeLimit - 1)
            }.minByOrNull { it.entry.priority() } ?: break
            decodeWaiters.remove(next)
            next.entry.startedSpeculative = next.entry.speculativeOnly()
            activeDecodes.add(next.entry)
            add(next)
        }
    }

    private fun signalDecodes(admitted: List<DecodeWaiter>) {
        admitted.forEach { it.ready.complete(Unit) }
    }

    private suspend fun <T> withDecodeAdmission(
        key: ReaderPageDecodeKey,
        entry: Entry,
        block: suspend () -> T,
    ): T {
        val waiter = DecodeWaiter(key, entry)
        val admitted = synchronized(lock) {
            if (closed || clearing || entry.withdrawn || entries[key] !== entry) {
                throw CancellationException("Desktop reader decode became stale before admission")
            }
            decodeWaiters.add(waiter)
            admitDecodesLocked()
        }
        signalDecodes(admitted)
        try {
            waiter.ready.await()
            currentCoroutineContext().ensureActive()
            synchronized(lock) {
                if (entry.withdrawn || entries[key] !== entry) {
                    throw CancellationException("Desktop reader decode became stale after admission")
                }
            }
            return block()
        } finally {
            val next = synchronized(lock) {
                decodeWaiters.remove(waiter)
                activeDecodes.remove(entry)
                admitDecodesLocked()
            }
            signalDecodes(next)
        }
    }

    private fun removeIfUnusedLocked(
        key: ReaderPageDecodeKey,
        entry: Entry,
    ): DesktopReaderImageAssetLease? {
        if (!entry.deferred.isCompleted || entry.pendingAcquires != 0) return null
        if (entries[key] === entry) entries.remove(key)
        return entry.decodedLease.also { entry.decodedLease = null }
    }

    private fun releaseDetachedCompleted(detachedEntries: List<Entry>) {
        val leases = detachedEntries.mapNotNull { entry ->
            synchronized(lock) {
                if (entry.deferred.isCompleted && entry.pendingAcquires == 0) {
                    entry.decodedLease.also { entry.decodedLease = null }
                } else {
                    null
                }
            }
        }
        runResourceActions(leases.map { lease -> lease::close })
    }

    private fun retainContentSessionLocked(
        contentKey: ReaderPageContentOpenRequest,
        purpose: String,
    ): ContentSessionEntry {
        check(!closed) { "Desktop reader page image pipeline is closed" }
        check(isCurrentAttemptLocked(contentKey)) { "$purpose session identity is stale" }
        return contentSessions.getOrPut(contentKey) {
            ContentSessionEntry(
                DesktopReaderSharedPageContent(
                    contentKey = contentKey,
                    scope = scope,
                    pageContentOwner = pageContentOwner,
                ),
            )
        }.also { it.references++ }
    }

    private fun cacheFor(key: ReaderPageDecodeKey): DesktopReaderImageCache? = when (key.purpose) {
        PageDecodePurpose.FULL_PAGE -> cache
        PageDecodePurpose.REGION_TILE -> tileCache
        PageDecodePurpose.ANIMATION_FRAME -> null
    }

    private fun isCurrentAttemptLocked(contentKey: ReaderPageContentOpenRequest): Boolean {
        if (contentKey.generation < minimumGeneration) return false
        val minimumAttempt = minimumPageAttempts[
            PageGenerationIdentity(contentKey.pageId, contentKey.generation),
        ] ?: 0L
        return contentKey.attemptGeneration == minimumAttempt
    }

    private fun ReaderPageContentOpenRequest.isOlderAttempt(
        pageId: ReaderPageId,
        generation: Long,
        attemptGeneration: Long,
    ): Boolean = this.pageId == pageId &&
        this.generation == generation &&
        this.attemptGeneration < attemptGeneration

    internal fun releaseAnimationSession(session: DesktopReaderAnimationContentSession) {
        releaseContentSession(session.contentKey, session.sharedContent)
    }

    internal fun releaseRegionSession(session: DesktopReaderRegionContentSession) {
        releaseContentSession(session.contentKey, session.sharedContent)
    }

    private fun releaseContentSession(
        contentKey: ReaderPageContentOpenRequest,
        sharedContent: DesktopReaderSharedPageContent,
    ) {
        val contentToClose = synchronized(lock) {
            val entry = contentSessions[contentKey]
            if (entry == null || entry.content !== sharedContent) return
            check(entry.references > 0) { "Content session reference count underflow" }
            entry.references--
            if (entry.references == 0) {
                contentSessions.remove(contentKey)
                entry.content
            } else {
                null
            }
        }
        contentToClose?.close()
    }

    companion object {
        // Closing a runtime cannot end native work still executing on the original shared dispatcher.
        private val mutableForegroundWork = MutableStateFlow(0)
        private val mutableVisibleForegroundWork = MutableStateFlow(0)
        private val mutableOptionalWork = MutableStateFlow(0)
        private val activeOptionalAdmission = AtomicReference<Entry?>(null)

        // The native region decoder is one bounded resource that every runtime shares.
        private val activeRegionDecode = AtomicReference<Any?>(null)
        private const val REGION_DECODE_POLL_MILLIS = 16L
        const val DEFAULT_CACHE_ENTRIES = 7
        const val DEFAULT_CACHE_BYTES = 128L * 1024L * 1024L
        const val DEFAULT_TILE_CACHE_ENTRIES = 8
        const val DEFAULT_TILE_CACHE_BYTES = 64L * 1024L * 1024L
        const val DEFAULT_CONCURRENT_DECODES = 3
        const val DEFAULT_TOTAL_IMAGE_BYTES = DesktopReaderImageMemoryAuthority.DEFAULT_MAX_BYTES
    }
}

private fun PageDecodePurpose.toImageMemoryKind(): DesktopReaderImageMemoryKind = when (this) {
    PageDecodePurpose.FULL_PAGE -> DesktopReaderImageMemoryKind.FULL
    PageDecodePurpose.ANIMATION_FRAME -> DesktopReaderImageMemoryKind.FRAME
    PageDecodePurpose.REGION_TILE -> DesktopReaderImageMemoryKind.TILE
}

/**
 * One mounted animation's encoded-content lease. Frame requests still use the runtime pipeline's
 * single-flight/cache; this session only prevents reopening the encoded source for every frame.
 */
internal class DesktopReaderAnimationContentSession(
    val contentKey: ReaderPageContentOpenRequest,
    internal val sharedContent: DesktopReaderSharedPageContent,
    private val pipeline: DesktopReaderPageImagePipeline,
) : AutoCloseable {
    private val lock = Any()
    private var closed = false

    suspend fun acquireFrame(key: ReaderPageDecodeKey): DesktopReaderImageAssetLease? {
        synchronized(lock) {
            check(!closed) { "Desktop reader animation content session is closed" }
        }
        return pipeline.acquireAnimationFrame(this, key)
    }

    suspend fun <T> withContent(block: suspend (ByteArray) -> T): T = sharedContent.withContent(block)

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        pipeline.releaseAnimationSession(this)
    }
}

/** One mounted large-static presentation's encoded source shared by its preview and region tiles. */
internal class DesktopReaderRegionContentSession(
    val contentKey: ReaderPageContentOpenRequest,
    internal val sharedContent: DesktopReaderSharedPageContent,
    private val pipeline: DesktopReaderPageImagePipeline,
) : AutoCloseable {
    private val lock = Any()
    private var closed = false

    suspend fun pin() {
        withContent { Unit }
    }

    suspend fun acquireTile(
        key: ReaderPageDecodeKey,
        optional: Boolean = false,
        pairingDemand: DesktopReaderPairingDecodeDemand? = null,
    ): DesktopReaderImageAssetLease? {
        synchronized(lock) {
            check(!closed) { "Desktop reader region content session is closed" }
        }
        return pipeline.acquireRegionTile(this, key, optional, pairingDemand)
    }

    internal suspend fun acquireForPairing(
        key: ReaderPageDecodeKey,
        demand: DesktopReaderPairingDecodeDemand,
    ): DesktopReaderImageAssetLease? {
        synchronized(lock) { check(!closed) { "Desktop reader region content session is closed" } }
        return pipeline.acquireRegionTile(this, key, pairingDemand = demand)
    }

    suspend fun <T> withContent(block: suspend (ByteArray) -> T): T {
        synchronized(lock) {
            check(!closed) { "Desktop reader region content session is closed" }
        }
        return sharedContent.withContent(block)
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        pipeline.releaseRegionSession(this)
    }
}

/** Pipeline-owned source lease shared by every mounted presentation of the same animated page. */
internal class DesktopReaderSharedPageContent(
    val contentKey: ReaderPageContentOpenRequest,
    scope: CoroutineScope,
    pageContentOwner: DesktopReaderPageContentOwner,
) : AutoCloseable {
    private val lock = Any()
    private val content = scope.async(start = CoroutineStart.LAZY) {
        pageContentOwner.acquire(contentKey)
    }
    private var closed = false
    private var contentLeaseClosed = false

    init {
        content.invokeOnCompletion { closeCompletedContentIfNeeded() }
    }

    suspend fun <T> withContent(block: suspend (ByteArray) -> T): T {
        synchronized(lock) {
            if (closed) throw CancellationException("Desktop reader shared content is closed")
            content.start()
        }
        val lease = content.await() ?: throw CancellationException("Reader page content is unavailable")
        synchronized(lock) {
            if (closed) throw CancellationException("Desktop reader shared content is closed")
        }
        return block(lease.content)
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        content.cancel()
        closeCompletedContentIfNeeded()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun closeCompletedContentIfNeeded() {
        val lease = synchronized(lock) {
            if (!closed || contentLeaseClosed || !content.isCompleted) return
            val completed = runCatching { content.getCompleted() }.getOrNull() ?: return
            contentLeaseClosed = true
            completed
        }
        lease.close()
    }
}
