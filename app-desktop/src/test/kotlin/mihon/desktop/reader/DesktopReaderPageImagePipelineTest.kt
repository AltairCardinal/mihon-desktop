package mihon.desktop.reader

import androidx.compose.ui.graphics.asComposeImageBitmap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mihon.domain.reader.PageDecodePurpose
import mihon.domain.reader.PixelBounds
import mihon.domain.reader.ReaderPageDecodeKey
import mihon.domain.reader.content.ReaderPageContentOpenRequest
import mihon.domain.reader.observability.ReaderIoEvent
import mihon.domain.reader.observability.ReaderIoEventType
import mihon.domain.reader.observability.ReaderIoProbe
import mihon.domain.reader.observability.ReaderIoReporter
import mihon.domain.reader.observability.ReaderMonotonicClock
import mihon.domain.reader.session.EncodedPageRef
import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderPageId
import org.jetbrains.skia.Bitmap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DesktopReaderPageImagePipelineTest {

    private fun entryIdentity(pipeline: DesktopReaderPageImagePipeline, key: ReaderPageDecodeKey): Any? {
        val lock = pipeline.javaClass.getDeclaredField("lock").apply { isAccessible = true }.get(pipeline)
        return synchronized(lock) {
            @Suppress("UNCHECKED_CAST")
            val entries = pipeline.javaClass.getDeclaredField("entries").apply { isAccessible = true }
                .get(pipeline) as Map<ReaderPageDecodeKey, Any>
            entries[key]
        }
    }

    private fun activeDecodeCount(pipeline: DesktopReaderPageImagePipeline): Int {
        val lock = pipeline.javaClass.getDeclaredField("lock").apply { isAccessible = true }.get(pipeline)
        return synchronized(lock) {
            pipeline.javaClass.getDeclaredField("activeDecodes").apply { isAccessible = true }
                .get(pipeline).let { it as Set<*> }.size
        }
    }

    private data class EncodedReadCompletion(
        val request: ReaderPageContentOpenRequest,
        val matchedStart: Boolean,
        val failureType: String?,
    )

    private class ControlledEncodedReader(private val bytes: ByteArray) {
        val release = CompletableDeferred<Unit>()
        val enteredRefs = CopyOnWriteArrayList<EncodedPageRef>()
        val exitedRefs = CopyOnWriteArrayList<EncodedPageRef>()
        val startedRequests = CopyOnWriteArrayList<ReaderPageContentOpenRequest>()
        val completions = CopyOnWriteArrayList<EncodedReadCompletion>()
        private val started = java.util.Collections.synchronizedSet(mutableSetOf<ReaderPageContentOpenRequest>())
        private val active = AtomicInteger()
        val maxConcurrent = AtomicInteger()
        val activeCount: Int get() = active.get()

        val ioGate = object : ReaderIoGate {
            override suspend fun await(point: ReaderIoGatePoint) = Unit

            override fun registerContentOwner(): Long = 91L

            override fun contentReadStarted(ownerId: Long, request: ReaderPageContentOpenRequest) {
                startedRequests += request
                started += request
            }

            override fun contentReadCompleted(
                ownerId: Long,
                request: ReaderPageContentOpenRequest,
                bytes: Int?,
                failure: Throwable?,
            ) {
                completions += EncodedReadCompletion(
                    request = request,
                    matchedStart = started.remove(request),
                    failureType = failure?.javaClass?.simpleName,
                )
            }
        }

        suspend fun read(ref: EncodedPageRef): ByteArray {
            enteredRefs += ref
            val current = active.incrementAndGet()
            maxConcurrent.updateAndGet { previous -> maxOf(previous, current) }
            try {
                withContext(NonCancellable) { release.await() }
                return bytes
            } finally {
                active.decrementAndGet()
                exitedRefs += ref
            }
        }
    }

    @Test
    fun `queued speculative demand promotes into the reserved slot without a second decode`() = runTest {
        val reporter = reporter()
        val entered = CopyOnWriteArrayList<Int>()
        val gate = CompletableDeferred<Unit>()
        val owner = contentOwner(this, reporter) { ENCODED_BYTES }
        val pipeline = DesktopReaderPageImagePipeline(
            scope = this,
            pageContentOwner = owner,
            ioReporter = reporter,
            maxConcurrentDecodes = 2,
            decoder = DesktopReaderPageImageDecoder { _, request ->
                entered += request.pageIndex
                if (request.pageIndex == 0) gate.await()
                asset(request.pageIndex)
            },
        )
        try {
            val first = async { pipeline.acquireForPairing(key(), speculative = true) }
            runCurrent()
            val demand = DesktopReaderPairingDecodeDemand(speculative = true)
            val promotedKey = key(pageIndex = 1, encodedPageRef = "opaque://promoted")
            val promoted = async { pipeline.acquireForPairing(promotedKey, demand) }
            runCurrent()
            assertEquals(listOf(0), entered)
            demand.promote()
            pipeline.promotePairing(promotedKey)
            runCurrent()
            assertEquals(listOf(0, 1), entered)
            promoted.await()?.close()
            gate.complete(Unit)
            first.await()?.close()
        } finally {
            gate.complete(Unit)
            pipeline.close()
            owner.close()
        }
    }

    @Test
    fun `promoting a content key wakes its queued region Entry while speculative decode stays held`() = runTest {
        val ioEvents = CopyOnWriteArrayList<ReaderIoEvent>()
        val reporter = reporter(ioEvents)
        val pageOwner = contentOwner(this, reporter) { ENCODED_BYTES }
        val fullEntered = CompletableDeferred<Unit>()
        val releaseFull = CompletableDeferred<Unit>()
        val regionEntered = CompletableDeferred<Unit>()
        val releaseRegion = CompletableDeferred<Unit>()
        val decoded = CopyOnWriteArrayList<Pair<Int, PageDecodePurpose>>()
        val pipeline = DesktopReaderPageImagePipeline(
            scope = this,
            pageContentOwner = pageOwner,
            ioReporter = reporter,
            maxConcurrentDecodes = 2,
            decoder = DesktopReaderPageImageDecoder { _, request ->
                decoded += request.pageIndex to request.purpose
                if (request.purpose == PageDecodePurpose.FULL_PAGE && request.pageIndex == 0) {
                    fullEntered.complete(Unit)
                    releaseFull.await()
                } else if (request.purpose == PageDecodePurpose.REGION_TILE) {
                    regionEntered.complete(Unit)
                    releaseRegion.await()
                }
                asset(decoded.size)
            },
        )
        val speculativeFullKey = key(pageIndex = 0, encodedPageRef = "opaque://running-full")
        val promotedFullKey = key(pageIndex = 1, encodedPageRef = "opaque://region-only")
        val regionKey = promotedFullKey.copy(
            purpose = PageDecodePurpose.REGION_TILE,
            region = PixelBounds(0, 0, 1, 1),
        )
        val regionSession = pipeline.openRegionSession(regionKey.contentKey)
        val fullDemand = DesktopReaderPairingDecodeDemand(speculative = true)
        val regionDemand = DesktopReaderPairingDecodeDemand(speculative = true)
        try {
            val full = async { pipeline.acquireForPairing(speculativeFullKey, fullDemand) }
            fullEntered.await()
            val region = async { regionSession.acquireForPairing(regionKey, regionDemand) }
            runCurrent()
            val entry = requireNotNull(entryIdentity(pipeline, regionKey))
            assertTrue(
                decoded.none { it.second == PageDecodePurpose.REGION_TILE },
                "The speculative full decode occupies the optional slot, leaving the reserved slot free",
            )
            assertNull(
                entryIdentity(pipeline, promotedFullKey),
                "The content promotion must find the region Entry when no full-page Entry exists",
            )
            assertTrue(fullDemand.speculative)
            assertTrue(regionDemand.speculative)

            // This is the same full-content key the model has after materialization. Its Entry is
            // absent, so promotion must still reach the queued region consumer for this content.
            pipeline.promotePairing(promotedFullKey)
            runCurrent()
            assertFalse(regionDemand.speculative, "The real content promotion changes the region demand")
            assertSame(entry, entryIdentity(pipeline, regionKey), "The queued Entry wakes without replacement")
            regionEntered.await()
            assertFalse(releaseFull.isCompleted, "The region starts while speculative native work is still held")
            assertFalse(full.isCompleted, "Promotion does not wait for the unrelated full decoder to exit")
            assertEquals(1, decoded.count { it == (1 to PageDecodePurpose.REGION_TILE) })

            releaseRegion.complete(Unit)
            requireNotNull(region.await()).close()
            releaseFull.complete(Unit)
            requireNotNull(full.await()).close()
            assertEquals(0, pipeline.foregroundWork.value)
            assertEquals(0, pipeline.optionalWork.value)
        } finally {
            releaseFull.complete(Unit)
            releaseRegion.complete(Unit)
            regionSession.close()
            pipeline.close()
            pageOwner.close()
        }
    }

    @Test
    fun `pairing region consumer shares a mounted tile and cannot cancel its normal owner`() = runTest {
        val reporter = reporter()
        val gate = CompletableDeferred<Unit>()
        var decodes = 0
        val owner = contentOwner(this, reporter) { ENCODED_BYTES }
        val pipeline = DesktopReaderPageImagePipeline(
            scope = this,
            pageContentOwner = owner,
            ioReporter = reporter,
            decoder = DesktopReaderPageImageDecoder { _, _ ->
                decodes++
                gate.await()
                asset(1)
            },
        )
        val session = pipeline.openRegionSession(key().contentKey)
        val tile = key().copy(purpose = PageDecodePurpose.REGION_TILE, region = PixelBounds(0, 0, 1, 1))
        try {
            val pairing = async { session.acquireForPairing(tile, DesktopReaderPairingDecodeDemand(true)) }
            runCurrent()
            val mounted = async { session.acquireTile(tile) }
            runCurrent()
            pairing.cancelAndJoin()
            gate.complete(Unit)
            val lease = requireNotNull(mounted.await())
            assertEquals(1, decodes)
            lease.close()
        } finally {
            gate.complete(Unit)
            session.close()
            pipeline.close()
            owner.close()
        }
    }

    @Test
    fun `cancelled queued pairing consumer never starts ahead of a new visible page`() = runTest {
        val reporter = reporter()
        val entered = CopyOnWriteArrayList<Int>()
        val gate = CompletableDeferred<Unit>()
        val owner = contentOwner(this, reporter) { ENCODED_BYTES }
        val pipeline = DesktopReaderPageImagePipeline(
            scope = this,
            pageContentOwner = owner,
            ioReporter = reporter,
            maxConcurrentDecodes = 1,
            decoder = DesktopReaderPageImageDecoder { _, request ->
                entered += request.pageIndex
                if (request.pageIndex == 0) gate.await()
                asset(request.pageIndex)
            },
        )
        try {
            val first = async { pipeline.acquire(key()) }
            runCurrent()
            assertEquals(listOf(0), entered)
            val obsolete = async {
                pipeline.acquireForPairing(key(pageIndex = 1, encodedPageRef = "opaque://old"), speculative = false)
            }
            runCurrent()
            obsolete.cancelAndJoin()
            val visible = async { pipeline.acquire(key(pageIndex = 2, encodedPageRef = "opaque://new")) }
            runCurrent()
            gate.complete(Unit)
            first.await()?.close()
            visible.await()?.close()
            assertEquals(listOf(0, 2), entered, "Withdrawn pairing must never enter the actual decoder")
        } finally {
            gate.complete(Unit)
            pipeline.close()
            owner.close()
        }
    }

    @Test
    fun `speculative decodes leave a physical slot for a new visible page`() = runTest {
        val reporter = reporter()
        val entered = CopyOnWriteArrayList<Int>()
        val gate = CompletableDeferred<Unit>()
        val owner = contentOwner(this, reporter) { ENCODED_BYTES }
        val pipeline = DesktopReaderPageImagePipeline(
            scope = this,
            pageContentOwner = owner,
            ioReporter = reporter,
            maxConcurrentDecodes = 2,
            decoder = DesktopReaderPageImageDecoder { _, request ->
                entered += request.pageIndex
                if (request.pageIndex != 2) gate.await()
                asset(request.pageIndex)
            },
        )
        try {
            val first = async {
                pipeline.acquireForPairing(key(), speculative = true)
            }
            runCurrent()
            val queued = async {
                pipeline.acquireForPairing(
                    key(pageIndex = 1, encodedPageRef = "opaque://speculative"),
                    speculative = true,
                )
            }
            runCurrent()
            val visible = async { pipeline.acquire(key(pageIndex = 2, encodedPageRef = "opaque://visible")) }
            runCurrent()
            assertEquals(listOf(0, 2), entered, "Speculation cannot occupy every physical decode slot")
            assertTrue(visible.isCompleted)
            visible.await()?.close()
            queued.cancelAndJoin()
            first.cancelAndJoin()
        } finally {
            gate.complete(Unit)
            pipeline.close()
            owner.close()
        }
    }

    @Test
    fun `visible consumer retains a shared pairing decode when the pairing consumer leaves`() = runTest {
        val reporter = reporter()
        val gate = CompletableDeferred<Unit>()
        var decodes = 0
        var opens = 0
        val owner = contentOwner(this, reporter) {
            opens++
            ENCODED_BYTES
        }
        val pipeline = DesktopReaderPageImagePipeline(
            scope = this,
            pageContentOwner = owner,
            ioReporter = reporter,
            decoder = DesktopReaderPageImageDecoder { _, _ ->
                decodes++
                gate.await()
                asset(1)
            },
        )
        try {
            val pairing = async { pipeline.acquireForPairing(key(), speculative = true) }
            runCurrent()
            val visible = async { pipeline.acquire(key()) }
            runCurrent()
            pairing.cancelAndJoin()
            assertEquals(1, pipeline.foregroundWork.value)
            gate.complete(Unit)
            val lease = requireNotNull(visible.await())
            assertEquals(1, decodes)
            assertEquals(1, opens)
            lease.close()
            assertEquals(0, pipeline.foregroundWork.value)
        } finally {
            gate.complete(Unit)
            pipeline.close()
            owner.close()
        }
    }

    @Test
    fun `cancelled physical pairing decode exits before the same key can reopen`() = runTest {
        val reporter = reporter()
        val gate = CompletableDeferred<Unit>()
        var decodes = 0
        var disposed = 0
        val owner = contentOwner(this, reporter) { ENCODED_BYTES }
        val pipeline = DesktopReaderPageImagePipeline(
            scope = this,
            pageContentOwner = owner,
            ioReporter = reporter,
            decoder = DesktopReaderPageImageDecoder { _, _ ->
                decodes++
                if (decodes == 1) withContext(kotlinx.coroutines.NonCancellable) { gate.await() }
                asset(decodes) { disposed++ }
            },
        )
        try {
            val pairing = async { pipeline.acquireForPairing(key(), speculative = true) }
            runCurrent()
            pairing.cancelAndJoin()
            assertEquals(1, decodes, "The physical decoder has entered before its speculative consumer withdraws")
            assertFalse(gate.isCompleted, "The native decoder remains held after the pairing consumer withdraws")
            assertEquals(1, pipeline.snapshot().inFlightCount, "The executing Entry remains present until native exit")
            assertEquals(0, pipeline.foregroundWork.value, "Withdrawn pure speculation is no longer foreground")
            assertEquals(
                1,
                pipeline.optionalWork.value,
                "The still-running speculative Entry remains counted until physical exit",
            )
            val visible = async { pipeline.acquire(key()) }
            runCurrent()
            assertEquals(1, decodes, "The same key cannot run a second decoder before actual old exit")
            gate.complete(Unit)
            val lease = requireNotNull(visible.await())
            assertEquals(2, decodes, "A withdrawn operation must not revive for a new consumer")
            assertEquals(1, disposed)
            lease.close()
            assertEquals(0, pipeline.foregroundWork.value)
            assertEquals(0, pipeline.optionalWork.value)
        } finally {
            gate.complete(Unit)
            pipeline.close()
            owner.close()
        }
    }

    @Test
    fun `cancelling the sole pairing waiter keeps an entered encoded read busy until actual exit`() = runTest {
        val reporter = reporter()
        val encodedReader = ControlledEncodedReader(ENCODED_BYTES)
        val owner = DesktopReaderPageContentOwner(
            scope = this,
            encodedPageReader = encodedReader::read,
            ioReporter = reporter,
            ioGate = encodedReader.ioGate,
        )
        val decodeCalls = AtomicInteger()
        val pipeline = DesktopReaderPageImagePipeline(
            scope = this,
            pageContentOwner = owner,
            ioReporter = reporter,
            decoder = DesktopReaderPageImageDecoder { _, _ ->
                decodeCalls.incrementAndGet()
                asset(1)
            },
        )
        val key = key(pageIndex = 4, encodedPageRef = "opaque://entered-sole-pairing-reader")
        val pairing = async { pipeline.acquireForPairing(key, speculative = true) }
        var realConsumer: kotlinx.coroutines.Deferred<DesktopReaderImageAssetLease?>? = null
        try {
            runCurrent()
            assertEquals(listOf(key.contentKey.encodedPageRef), encodedReader.enteredRefs)
            assertTrue(encodedReader.startedRequests.contains(key.contentKey))
            assertTrue(encodedReader.exitedRefs.isEmpty(), "The real encoded reader body is still held")
            assertEquals(1, encodedReader.activeCount)
            val originalEntry = requireNotNull(entryIdentity(pipeline, key))
            assertEquals(1, pipeline.snapshot().inFlightCount)
            assertEquals(0, activeDecodeCount(pipeline), "Encoded reading has not reached native decode admission")
            assertEquals(0, decodeCalls.get())

            pairing.cancelAndJoin()

            assertTrue(encodedReader.exitedRefs.isEmpty(), "Cancelling the waiter cannot fake physical reader exit")
            assertEquals(1, encodedReader.activeCount)
            assertEquals(1, pipeline.snapshot().inFlightCount, "The live encoded operation retains its Entry")
            assertSame(originalEntry, entryIdentity(pipeline, key))
            assertEquals(1, pipeline.optionalWork.value, "Pure speculative work remains counted until reader exit")
            assertEquals(0, pipeline.foregroundWork.value)
            assertEquals(0, activeDecodeCount(pipeline))
            assertEquals(0, decodeCalls.get())
            assertTrue(encodedReader.completions.isEmpty())

            realConsumer = async { pipeline.acquire(key) }
            runCurrent()
            assertFalse(realConsumer.isCompleted, "The replacement consumer waits on the still-entered read")
            assertEquals(1, encodedReader.enteredRefs.size, "A same-key consumer cannot overlap a second encoded read")
            assertEquals(1, encodedReader.maxConcurrent.get())
            assertEquals(0, decodeCalls.get(), "The second native decode cannot begin before the original read exits")

            encodedReader.release.complete(Unit)
            val lease = requireNotNull(realConsumer.await())
            lease.close()
            runCurrent()

            assertEquals(encodedReader.enteredRefs.size, encodedReader.exitedRefs.size)
            assertEquals(encodedReader.enteredRefs.size, encodedReader.completions.size)
            assertTrue(encodedReader.completions.all { it.matchedStart })
            assertEquals(1, encodedReader.maxConcurrent.get(), "Any retry starts only after the original physical exit")
            assertEquals(0, encodedReader.activeCount)
            assertEquals(0, activeDecodeCount(pipeline))
            assertEquals(0, pipeline.snapshot().inFlightCount)
            assertEquals(0, pipeline.foregroundWork.value)
            assertEquals(0, pipeline.optionalWork.value)
        } finally {
            encodedReader.release.complete(Unit)
            pairing.cancelAndJoin()
            realConsumer?.cancelAndJoin()
            runCurrent()
            pipeline.close()
            owner.close()
        }
    }

    @Test
    fun `cancelling pairing preserves a normal consumer of the same entered encoded read`() = runTest {
        val reporter = reporter()
        val encodedReader = ControlledEncodedReader(ENCODED_BYTES)
        val owner = DesktopReaderPageContentOwner(
            scope = this,
            encodedPageReader = encodedReader::read,
            ioReporter = reporter,
            ioGate = encodedReader.ioGate,
        )
        val decodeCalls = AtomicInteger()
        val pipeline = DesktopReaderPageImagePipeline(
            scope = this,
            pageContentOwner = owner,
            ioReporter = reporter,
            decoder = DesktopReaderPageImageDecoder { _, _ ->
                decodeCalls.incrementAndGet()
                asset(2)
            },
        )
        val key = key(pageIndex = 5, encodedPageRef = "opaque://shared-entered-reader")
        val pairing = async { pipeline.acquireForPairing(key, speculative = true) }
        var ordinaryConsumer: kotlinx.coroutines.Deferred<DesktopReaderImageAssetLease?>? = null
        try {
            runCurrent()
            assertEquals(listOf(key.contentKey.encodedPageRef), encodedReader.enteredRefs)
            assertTrue(encodedReader.startedRequests.contains(key.contentKey))
            val sharedEntry = requireNotNull(entryIdentity(pipeline, key))
            assertEquals(1, encodedReader.activeCount)
            assertTrue(encodedReader.exitedRefs.isEmpty())
            assertEquals(0, activeDecodeCount(pipeline))

            ordinaryConsumer = async { pipeline.acquire(key) }
            runCurrent()
            assertSame(sharedEntry, entryIdentity(pipeline, key))
            assertEquals(1, encodedReader.enteredRefs.size, "The ordinary consumer shares the entered owner read")
            assertFalse(ordinaryConsumer.isCompleted)

            pairing.cancelAndJoin()

            assertSame(sharedEntry, entryIdentity(pipeline, key), "Withdrawing pairing must retain ordinary ownership")
            assertTrue(encodedReader.exitedRefs.isEmpty(), "The ordinary consumer keeps the physical read alive")
            assertEquals(1, encodedReader.activeCount)
            assertEquals(1, pipeline.foregroundWork.value)
            assertEquals(0, pipeline.optionalWork.value)
            assertFalse(ordinaryConsumer.isCompleted)
            assertEquals(0, decodeCalls.get(), "The shared encoded read has not reached native decode")

            encodedReader.release.complete(Unit)
            val lease = requireNotNull(ordinaryConsumer.await())
            lease.close()
            runCurrent()

            assertEquals(listOf(key.contentKey.encodedPageRef), encodedReader.enteredRefs)
            assertEquals(encodedReader.enteredRefs, encodedReader.exitedRefs)
            assertEquals(1, encodedReader.completions.size)
            assertTrue(encodedReader.completions.single().matchedStart)
            assertEquals(1, decodeCalls.get(), "Shared ownership performs one physical decode")
            assertEquals(0, encodedReader.activeCount)
            assertEquals(0, pipeline.snapshot().inFlightCount)
            assertEquals(0, pipeline.foregroundWork.value)
            assertEquals(0, pipeline.optionalWork.value)
        } finally {
            encodedReader.release.complete(Unit)
            pairing.cancelAndJoin()
            ordinaryConsumer?.cancelAndJoin()
            runCurrent()
            pipeline.close()
            owner.close()
        }
    }

    @Test
    fun `closing pipeline keeps an entered encoded read busy until actual exit`() = runTest {
        assertEnteredReaderSurvivesPipelineInvalidation { pipeline, _ -> pipeline.close() }
    }

    @Test
    fun `advancing generation keeps an entered encoded read busy until actual exit`() = runTest {
        assertEnteredReaderSurvivesPipelineInvalidation { pipeline, _ ->
            assertTrue(pipeline.beginGeneration(2L))
        }
    }

    @Test
    fun `cancelling pipeline parent keeps an entered encoded read busy until actual exit`() = runTest {
        assertEnteredReaderSurvivesPipelineInvalidation { _, pipelineScope -> pipelineScope.cancel() }
    }

    private suspend fun TestScope.assertEnteredReaderSurvivesPipelineInvalidation(
        invalidate: (DesktopReaderPageImagePipeline, CoroutineScope) -> Unit,
    ) {
        val reporter = reporter()
        val encodedReader = ControlledEncodedReader(ENCODED_BYTES)
        val owner = DesktopReaderPageContentOwner(
            scope = this,
            encodedPageReader = encodedReader::read,
            ioReporter = reporter,
            ioGate = encodedReader.ioGate,
        )
        val decodeCalls = AtomicInteger()
        val pipelineScope = CoroutineScope(coroutineContext + SupervisorJob())
        val pipeline = DesktopReaderPageImagePipeline(
            scope = pipelineScope,
            pageContentOwner = owner,
            ioReporter = reporter,
            decoder = DesktopReaderPageImageDecoder { _, _ ->
                decodeCalls.incrementAndGet()
                asset(3)
            },
        )
        val key = key(pageIndex = 9, encodedPageRef = "opaque://invalidation-entered-reader")
        val pairing = async { pipeline.acquireForPairing(key, speculative = true) }
        try {
            runCurrent()
            assertEquals(listOf(key.contentKey.encodedPageRef), encodedReader.enteredRefs)
            assertTrue(encodedReader.startedRequests.contains(key.contentKey))
            assertTrue(encodedReader.exitedRefs.isEmpty(), "The encoded reader body is still held")
            assertEquals(1, encodedReader.activeCount)
            assertTrue(entryIdentity(pipeline, key) != null)
            assertEquals(1, pipeline.optionalWork.value)
            assertEquals(0, pipeline.foregroundWork.value)
            assertEquals(0, activeDecodeCount(pipeline))
            assertEquals(0, decodeCalls.get())

            invalidate(pipeline, pipelineScope)
            runCurrent()

            assertTrue(encodedReader.exitedRefs.isEmpty(), "Invalidation cannot fake encoded reader exit")
            assertEquals(1, encodedReader.activeCount)
            assertEquals(1, pipeline.optionalWork.value, "Physical work stays counted until the reader exits")
            assertEquals(0, pipeline.foregroundWork.value)
            assertEquals(0, activeDecodeCount(pipeline))
            assertEquals(0, decodeCalls.get(), "A withdrawn request cannot reach native decode")

            encodedReader.release.complete(Unit)
            runCurrent()

            val staleLease = runCatching { pairing.await() }.getOrNull()
            staleLease?.close()
            assertNull(staleLease, "A withdrawn request cannot publish a lease")
            assertEquals(listOf(key.contentKey.encodedPageRef), encodedReader.exitedRefs)
            assertEquals(1, encodedReader.completions.size)
            assertTrue(encodedReader.completions.single().matchedStart)
            assertEquals(0, encodedReader.activeCount)
            assertEquals(0, decodeCalls.get())
            assertTrue(
                owner.snapshot().activeLeaseCounts.isEmpty(),
                "The cancelled acquire cannot leak a content lease",
            )
            assertTrue(pipeline.snapshot().cache.keys.isEmpty())
            assertEquals(0, pipeline.snapshot().inFlightCount)
            assertEquals(0, activeDecodeCount(pipeline))
            assertEquals(0, pipeline.foregroundWork.value)
            assertEquals(0, pipeline.optionalWork.value)
        } finally {
            encodedReader.release.complete(Unit)
            pairing.cancelAndJoin()
            runCurrent()
            pipeline.close()
            pipelineScope.cancel()
            owner.close()
        }
    }

    private suspend fun acquireOriginal(
        pipeline: DesktopReaderPageImagePipeline,
        request: ReaderPageDecodeKey,
        optional: Boolean,
    ): DesktopReaderImageAssetLease? = if (optional) {
        pipeline.openRegionSession(request.contentKey).use {
            it.acquireTile(
                request.copy(purpose = PageDecodePurpose.REGION_TILE, region = PixelBounds(0, 0, 1, 1)),
                true,
            )
        }
    } else {
        pipeline.acquire(request)
    }

    @Test
    fun `concurrent acquire single flights one content open and one decode`() = runTest {
        val events = CopyOnWriteArrayList<ReaderIoEvent>()
        val reporter = reporter(events)
        val decodeEntered = CompletableDeferred<Unit>()
        val releaseDecode = CompletableDeferred<Unit>()
        var contentOpenCalls = 0
        var decodeCalls = 0
        val contentOwner = contentOwner(this, reporter) {
            contentOpenCalls++
            ENCODED_BYTES
        }
        val pipeline = DesktopReaderPageImagePipeline(
            scope = this,
            pageContentOwner = contentOwner,
            ioReporter = reporter,
            decoder = DesktopReaderPageImageDecoder { _, _ ->
                decodeCalls++
                decodeEntered.complete(Unit)
                releaseDecode.await()
                asset(tag = 1)
            },
        )
        val key = key()

        try {
            val first = async { pipeline.acquire(key) }
            decodeEntered.await()
            val second = async { pipeline.acquire(key) }
            runCurrent()

            assertEquals(1, contentOpenCalls)
            assertEquals(1, decodeCalls)
            assertEquals(1, pipeline.foregroundWork.value, "Two callers share one original foreground task")
            releaseDecode.complete(Unit)

            val firstLease = requireNotNull(first.await())
            val secondLease = requireNotNull(second.await())
            assertSame(firstLease.asset, secondLease.asset)
            assertEquals(1, events.count { it.matches(ReaderIoEventType.OPEN_PAGE, key) })
            assertEquals(1, events.count { it.matches(ReaderIoEventType.DECODE, key) })
            assertEquals(0, pipeline.snapshot().inFlightCount)
            assertEquals(listOf(key), pipeline.snapshot().cache.keys)
            assertEquals(0, pipeline.foregroundWork.value, "Completed retained images do not keep optional work paused")

            firstLease.close()
            secondLease.close()
        } finally {
            releaseDecode.complete(Unit)
            pipeline.close()
            contentOwner.close()
        }
    }

    @Test
    fun `cache hit adds no content open or decode`() = runTest {
        val events = CopyOnWriteArrayList<ReaderIoEvent>()
        val reporter = reporter(events)
        var decodeCalls = 0
        val contentOwner = contentOwner(this, reporter) { ENCODED_BYTES }
        val pipeline = DesktopReaderPageImagePipeline(
            scope = this,
            pageContentOwner = contentOwner,
            ioReporter = reporter,
            decoder = DesktopReaderPageImageDecoder { _, _ -> asset(tag = ++decodeCalls) },
        )
        val key = key()

        try {
            val first = requireNotNull(pipeline.acquire(key))
            val eventCountAfterMiss = events.size
            first.close()

            val cached = requireNotNull(pipeline.acquire(key))
            try {
                assertSame(first.asset, cached.asset)
                assertEquals(eventCountAfterMiss, events.size)
                assertEquals(1, decodeCalls)
                assertEquals(0, pipeline.foregroundWork.value, "The original cache hit does not enqueue decode work")
                assertEquals(1, events.count { it.matches(ReaderIoEventType.OPEN_PAGE, key) })
                assertEquals(1, events.count { it.matches(ReaderIoEventType.DECODE, key) })
            } finally {
                cached.close()
            }
        } finally {
            pipeline.close()
            contentOwner.close()
        }
    }

    @Test
    fun `queued image cancellation releases foreground qualification before body starts`() = runTest {
        for (optional in listOf(false, true)) {
            val reporter = reporter()
            val contentOwner = contentOwner(this, reporter) { ENCODED_BYTES }
            var decodes = 0
            val pipeline = DesktopReaderPageImagePipeline(
                scope = this,
                pageContentOwner = contentOwner,
                ioReporter = reporter,
                decoder = DesktopReaderPageImageDecoder { _, _ -> asset(tag = ++decodes) },
            )
            try {
                val queued = async(start = CoroutineStart.UNDISPATCHED) { acquireOriginal(pipeline, key(), optional) }
                assertEquals(1, if (optional) pipeline.optionalWork.value else pipeline.foregroundWork.value)
                pipeline.clear()
                runCurrent()
                assertEquals(0, if (optional) pipeline.optionalWork.value else pipeline.foregroundWork.value)
                assertEquals(0, decodes)
                assertNull(runCatching { queued.await() }.getOrNull())
            } finally {
                pipeline.close()
                contentOwner.close()
            }
        }
    }

    @Test
    fun `visible caller promotes the same optional tile exactly once until actual completion`() = runTest {
        val reporter = reporter()
        val contentOwner = contentOwner(this, reporter) { ENCODED_BYTES }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val pipeline = DesktopReaderPageImagePipeline(
            scope = this,
            pageContentOwner = contentOwner,
            ioReporter = reporter,
            decoder = DesktopReaderPageImageDecoder { _, _ ->
                entered.complete(Unit)
                release.await()
                asset(tag = 1)
            },
        )
        val tile = key().copy(purpose = PageDecodePurpose.REGION_TILE, region = PixelBounds(0, 0, 1, 1))
        val session = pipeline.openRegionSession(tile.contentKey)
        try {
            val sample = async { session.acquireTile(tile, optional = true) }
            entered.await()
            assertEquals(0, pipeline.foregroundWork.value)
            assertEquals(1, pipeline.optionalWork.value)
            val visible = async { session.acquireTile(tile) }
            val anotherVisible = async { session.acquireTile(tile) }
            runCurrent()
            assertEquals(1, pipeline.foregroundWork.value)
            assertEquals(1, pipeline.optionalWork.value, "Promotion retains the original optional task exactly once")
            release.complete(Unit)
            listOf(sample, visible, anotherVisible).forEach { requireNotNull(it.await()).close() }
            assertEquals(0, pipeline.foregroundWork.value)
            assertEquals(0, pipeline.optionalWork.value)
        } finally {
            release.complete(Unit)
            session.close()
            pipeline.close()
            contentOwner.close()
        }
    }

    @Test
    fun `different optional entry is paused atomically while same key shares the original work`() = runTest {
        val reporter = reporter()
        val contentOwner = contentOwner(this, reporter) { ENCODED_BYTES }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var decodes = 0
        val tile = key().copy(purpose = PageDecodePurpose.REGION_TILE, region = PixelBounds(0, 0, 1, 1))
        val pipeline = DesktopReaderPageImagePipeline(
            scope = this,
            pageContentOwner = contentOwner,
            ioReporter = reporter,
            decoder = DesktopReaderPageImageDecoder { _, request ->
                decodes++
                if (request == tile) {
                    entered.complete(Unit)
                    withContext(kotlinx.coroutines.NonCancellable) { release.await() }
                }
                asset(tag = decodes)
            },
        )
        val session = pipeline.openRegionSession(tile.contentKey)
        val first = async { session.acquireTile(tile, true) }
        val replacement = DesktopReaderPageImagePipeline(
            scope = this,
            pageContentOwner = contentOwner,
            ioReporter = reporter,
            decoder = DesktopReaderPageImageDecoder { _, _ ->
                decodes++
                asset(tag = decodes)
            },
        )
        val replacementSession = replacement.openRegionSession(tile.contentKey)
        try {
            entered.await()
            val shared = async { session.acquireTile(tile, true) }
            runCurrent()
            assertEquals(1, decodes)
            assertEquals(1, pipeline.optionalWork.value)
            val competing = runCatching {
                session.acquireTile(tile.copy(region = PixelBounds(1, 0, 2, 1)), true)?.close()
            }
            assertTrue(
                competing.exceptionOrNull() is DesktopReaderOptionalWorkPaused,
                "A different optional key must pause at the original Entry creation point; decodes=$decodes",
            )
            assertEquals(1, decodes, "Conflicting admission never enters the decoder")
            assertEquals(1, replacement.optionalWork.value, "Replacement pipelines observe original physical work")
            val acrossRuntime = runCatching {
                replacementSession.acquireTile(tile.copy(region = PixelBounds(2, 0, 1, 1)), true)?.close()
            }
            assertTrue(acrossRuntime.exceptionOrNull() is DesktopReaderOptionalWorkPaused)
            assertEquals(1, decodes, "A replacement Entry cannot bypass the original atomic optional admission")
            release.complete(Unit)
            requireNotNull(first.await()).close()
            requireNotNull(shared.await()).close()
            assertEquals(0, pipeline.optionalWork.value)
        } finally {
            release.complete(Unit)
            first.cancelAndJoin()
            session.close()
            replacementSession.close()
            replacement.close()
            pipeline.close()
            contentOwner.close()
        }
    }

    @Test
    fun `successful decode releases encoded content before returning the image lease`() = runTest {
        val reporter = reporter()
        val contentOwner = contentOwner(this, reporter) { ENCODED_BYTES }
        val pipeline = DesktopReaderPageImagePipeline(
            scope = this,
            pageContentOwner = contentOwner,
            ioReporter = reporter,
            decoder = DesktopReaderPageImageDecoder { _, _ -> asset(tag = 1) },
        )

        try {
            val lease = requireNotNull(pipeline.acquire(key()))
            try {
                assertTrue(contentOwner.snapshot().activeLeaseCounts.isEmpty())
            } finally {
                lease.close()
            }
        } finally {
            pipeline.close()
            contentOwner.close()
        }
    }

    @Test
    fun `pipeline close preserves a caller lease until its final close`() = runTest {
        val reporter = reporter()
        val disposeCalls = AtomicInteger()
        val contentOwner = contentOwner(this, reporter) { ENCODED_BYTES }
        val pipeline = DesktopReaderPageImagePipeline(
            scope = this,
            pageContentOwner = contentOwner,
            ioReporter = reporter,
            decoder = DesktopReaderPageImageDecoder { _, _ ->
                asset(tag = 1, disposer = { disposeCalls.incrementAndGet() })
            },
        )
        val lease = requireNotNull(pipeline.acquire(key()))

        try {
            pipeline.close()
            assertEquals(0, disposeCalls.get())

            lease.close()
            lease.close()
            assertEquals(1, disposeCalls.get())
        } finally {
            lease.close()
            pipeline.close()
            contentOwner.close()
        }
    }

    @Test
    fun `chapter generation encoded ref and decode profile remain distinct cache identities`() = runTest {
        val reporter = reporter()
        var decodeCalls = 0
        val contentOwner = contentOwner(this, reporter) { ENCODED_BYTES }
        val pipeline = DesktopReaderPageImagePipeline(
            scope = this,
            pageContentOwner = contentOwner,
            ioReporter = reporter,
            decoder = DesktopReaderPageImageDecoder { _, _ -> asset(tag = ++decodeCalls) },
            maxEntries = 10,
        )
        val baseline = key()
        val keys = listOf(
            baseline,
            key(chapterId = 2L),
            key(encodedPageRef = "opaque://replacement"),
            key(maxWidth = 1_024),
            key(generation = 2L),
        )

        try {
            val leases = keys.map { requireNotNull(pipeline.acquire(it)) }
            try {
                assertEquals(keys.size, decodeCalls)
                assertEquals(keys, pipeline.snapshot().cache.keys)
                leases.indices.forEach { left ->
                    leases.indices.drop(left + 1).forEach { right ->
                        assertNotSame(leases[left].asset, leases[right].asset)
                    }
                }
            } finally {
                leases.forEach(DesktopReaderImageAssetLease::close)
            }
        } finally {
            pipeline.close()
            contentOwner.close()
        }
    }

    @Test
    fun `content open failure never invokes decoder or populates cache`() = runTest {
        val events = CopyOnWriteArrayList<ReaderIoEvent>()
        val reporter = reporter(events)
        var decodeCalls = 0
        val contentOwner = contentOwner(this, reporter) { null }
        val pipeline = DesktopReaderPageImagePipeline(
            scope = this,
            pageContentOwner = contentOwner,
            ioReporter = reporter,
            decoder = DesktopReaderPageImageDecoder { _, _ ->
                decodeCalls++
                asset(tag = 1)
            },
        )
        val key = key()

        try {
            assertNull(pipeline.acquire(key))
            assertEquals(0, decodeCalls)
            assertEquals(1, events.count { it.matches(ReaderIoEventType.OPEN_PAGE, key) })
            assertEquals(0, events.count { it.matches(ReaderIoEventType.DECODE, key) })
            assertTrue(pipeline.snapshot().cache.keys.isEmpty())
            assertEquals(0, pipeline.snapshot().inFlightCount)
        } finally {
            pipeline.close()
            contentOwner.close()
        }
    }

    @Test
    fun `decode failure is not cached and the same key can retry`() = runTest {
        val events = CopyOnWriteArrayList<ReaderIoEvent>()
        val reporter = reporter(events)
        var decodeCalls = 0
        val contentOwner = contentOwner(this, reporter) { ENCODED_BYTES }
        val pipeline = DesktopReaderPageImagePipeline(
            scope = this,
            pageContentOwner = contentOwner,
            ioReporter = reporter,
            decoder = DesktopReaderPageImageDecoder { _, _ ->
                decodeCalls++
                if (decodeCalls == 1) null else asset(tag = decodeCalls)
            },
        )
        val key = key()

        try {
            assertNull(pipeline.acquire(key))
            assertTrue(pipeline.snapshot().cache.keys.isEmpty())
            assertEquals(0, pipeline.snapshot().inFlightCount)

            val retried = requireNotNull(pipeline.acquire(key))
            try {
                assertEquals(2, decodeCalls)
                assertEquals(listOf(key), pipeline.snapshot().cache.keys)
                assertEquals(2, events.count { it.matches(ReaderIoEventType.DECODE, key) })
            } finally {
                retried.close()
            }
        } finally {
            pipeline.close()
            contentOwner.close()
        }
    }

    @Test
    fun `generation fence rejects and releases a non cooperative stale decode`() = runTest {
        for (optional in listOf(false, true)) {
            val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val reporter = reporter()
            val decodeEntered = CountDownLatch(1)
            val releaseDecode = CountDownLatch(1)
            val decodeReturned = CountDownLatch(1)
            val disposed = CountDownLatch(1)
            val disposeCalls = AtomicInteger()
            val contentOwner = contentOwner(ioScope, reporter) { ENCODED_BYTES }
            val pipeline = DesktopReaderPageImagePipeline(
                scope = ioScope,
                pageContentOwner = contentOwner,
                ioReporter = reporter,
                decoder = DesktopReaderPageImageDecoder { _, _ ->
                    decodeEntered.countDown()
                    check(releaseDecode.await(5, TimeUnit.SECONDS))
                    asset(tag = 1, disposer = {
                        disposeCalls.incrementAndGet()
                        disposed.countDown()
                    }).also { decodeReturned.countDown() }
                },
            )
            val staleKey = key(generation = 1L)
            val acquiring = async { acquireOriginal(pipeline, staleKey, optional) }

            try {
                assertTrue(withContext(Dispatchers.IO) { decodeEntered.await(5, TimeUnit.SECONDS) })
                assertEquals(1, if (optional) pipeline.optionalWork.value else pipeline.foregroundWork.value)
                pipeline.beginGeneration(2L)
                assertEquals(
                    1,
                    if (optional) pipeline.optionalWork.value else pipeline.foregroundWork.value,
                    "A detached non-cooperative original decoder is still busy",
                )
                releaseDecode.countDown()
                assertTrue(withContext(Dispatchers.IO) { decodeReturned.await(5, TimeUnit.SECONDS) })

                val result = runCatching { withTimeout(5_000) { acquiring.await() } }.getOrNull()
                result?.close()
                assertNull(result)
                assertTrue(withContext(Dispatchers.IO) { disposed.await(5, TimeUnit.SECONDS) })
                withTimeout(5_000) {
                    (if (optional) pipeline.optionalWork else pipeline.foregroundWork).first { it == 0 }
                }
                assertEquals(1, disposeCalls.get())
                assertTrue(pipeline.snapshot().cache.keys.isEmpty())
                assertEquals(0, pipeline.snapshot().inFlightCount)
                assertEquals(0, if (optional) pipeline.optionalWork.value else pipeline.foregroundWork.value)
            } finally {
                releaseDecode.countDown()
                acquiring.cancelAndJoin()
                pipeline.close()
                contentOwner.close()
                ioScope.cancel()
            }
        }
    }

    @Test
    fun `attempt and generation fences recycle full tile inflight and shared content after disposer failures`() =
        runTest {
            PipelineFence.entries.forEach { fence -> assertExceptionCompleteFence(fence) }
        }

    @Test
    fun `close rejects and releases a non cooperative late decoded asset`() = runTest {
        for (optional in listOf(false, true)) {
            val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val reporter = reporter()
            val decodeEntered = CountDownLatch(1)
            val releaseDecode = CountDownLatch(1)
            val decodeReturned = CountDownLatch(1)
            val disposeCalls = AtomicInteger()
            val contentOwner = contentOwner(ioScope, reporter) { ENCODED_BYTES }
            val pipeline = DesktopReaderPageImagePipeline(
                scope = ioScope,
                pageContentOwner = contentOwner,
                ioReporter = reporter,
                decoder = DesktopReaderPageImageDecoder { _, _ ->
                    decodeEntered.countDown()
                    check(releaseDecode.await(5, TimeUnit.SECONDS))
                    asset(tag = 1, disposer = { disposeCalls.incrementAndGet() }).also { decodeReturned.countDown() }
                },
            )
            val acquiring = async { acquireOriginal(pipeline, key(), optional) }

            try {
                assertTrue(withContext(Dispatchers.IO) { decodeEntered.await(5, TimeUnit.SECONDS) })
                pipeline.close()
                pipeline.close()
                assertEquals(
                    1,
                    if (optional) pipeline.optionalWork.value else pipeline.foregroundWork.value,
                    "Close cannot claim a running native task has finished",
                )
                releaseDecode.countDown()
                assertTrue(withContext(Dispatchers.IO) { decodeReturned.await(5, TimeUnit.SECONDS) })

                val result = runCatching { withTimeout(5_000) { acquiring.await() } }.getOrNull()
                result?.close()
                assertNull(result)
                assertEquals(1, disposeCalls.get())
                assertTrue(pipeline.snapshot().cache.keys.isEmpty())
                assertEquals(0, pipeline.snapshot().inFlightCount)
            } finally {
                releaseDecode.countDown()
                acquiring.cancelAndJoin()
                pipeline.close()
                contentOwner.close()
                ioScope.cancel()
            }
        }
    }

    @Test
    fun `decode concurrency is bounded and a stale queued request never starts`() = runTest {
        val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val reporter = reporter()
        val twoDecodesEntered = CountDownLatch(2)
        val releaseDecodes = CountDownLatch(1)
        val decodeCalls = AtomicInteger()
        val activeDecodes = AtomicInteger()
        val peakActiveDecodes = AtomicInteger()
        val contentOwner = contentOwner(ioScope, reporter) { ENCODED_BYTES }
        val pipeline = DesktopReaderPageImagePipeline(
            scope = ioScope,
            pageContentOwner = contentOwner,
            ioReporter = reporter,
            decoder = DesktopReaderPageImageDecoder { _, _ ->
                decodeCalls.incrementAndGet()
                val active = activeDecodes.incrementAndGet()
                peakActiveDecodes.updateAndGet { previous -> maxOf(previous, active) }
                twoDecodesEntered.countDown()
                try {
                    check(releaseDecodes.await(5, TimeUnit.SECONDS))
                    asset(tag = active)
                } finally {
                    activeDecodes.decrementAndGet()
                }
            },
            maxConcurrentDecodes = 2,
        )
        val acquiring = (0..2).map { pageIndex ->
            async { runCatching { pipeline.acquire(key(pageIndex = pageIndex)) }.getOrNull() }
        }

        try {
            assertTrue(withContext(Dispatchers.IO) { twoDecodesEntered.await(5, TimeUnit.SECONDS) })
            withContext(Dispatchers.IO) { Thread.sleep(100) }
            assertEquals(2, decodeCalls.get())
            assertEquals(2, peakActiveDecodes.get())

            pipeline.beginGeneration(2L)
            releaseDecodes.countDown()
            acquiring.forEach { request -> request.await()?.close() }

            assertEquals(2, decodeCalls.get(), "The stale request waiting for a permit must never decode")
            assertEquals(0, pipeline.snapshot().inFlightCount)
        } finally {
            releaseDecodes.countDown()
            acquiring.forEach { it.cancelAndJoin() }
            pipeline.close()
            contentOwner.close()
            ioScope.cancel()
        }
    }

    private fun contentOwner(
        scope: CoroutineScope,
        reporter: ReaderIoReporter,
        reader: suspend (EncodedPageRef) -> ByteArray?,
    ) = DesktopReaderPageContentOwner(
        scope = scope,
        encodedPageReader = reader,
        ioReporter = reporter,
    )

    private suspend fun assertExceptionCompleteFence(fence: PipelineFence) = coroutineScope {
        val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val reporter = reporter()
        val frameEntered = CountDownLatch(1)
        val releaseFrame = CountDownLatch(1)
        val frameReturned = CountDownLatch(1)
        val fullDisposals = AtomicInteger()
        val tileDisposals = AtomicInteger()
        val frameDisposals = AtomicInteger()
        val firstFailure = IllegalStateException("$fence-full-disposer")
        val laterFailure = IllegalArgumentException("$fence-tile-disposer")
        val contentKey = ReaderPageContentOpenRequest(
            pageId = ReaderPageId(ReaderChapterId(44L), 0),
            generation = 1L,
            encodedPageRef = EncodedPageRef("opaque://exception-complete-$fence"),
        )
        val fullKey = ReaderPageDecodeKey(
            contentKey = contentKey,
            purpose = PageDecodePurpose.FULL_PAGE,
            maxWidth = 2_048,
            maxHeight = 2_048,
        )
        val tileKey = ReaderPageDecodeKey(
            contentKey = contentKey,
            purpose = PageDecodePurpose.REGION_TILE,
            maxWidth = 1_000,
            maxHeight = 1_000,
            region = PixelBounds(0, 0, 1_000, 1_000),
        )
        val frameKey = ReaderPageDecodeKey(
            contentKey = contentKey,
            purpose = PageDecodePurpose.ANIMATION_FRAME,
            maxWidth = 2_048,
            maxHeight = 2_048,
            frameIndex = 0,
        )
        val contentOwner = contentOwner(ioScope, reporter) { ENCODED_BYTES }
        val pipeline = DesktopReaderPageImagePipeline(
            scope = ioScope,
            pageContentOwner = contentOwner,
            ioReporter = reporter,
            decoder = DesktopReaderPageImageDecoder { _, key ->
                when (key.purpose) {
                    PageDecodePurpose.FULL_PAGE -> asset(
                        tag = 1,
                        disposer = {
                            fullDisposals.incrementAndGet()
                            throw firstFailure
                        },
                    )
                    PageDecodePurpose.REGION_TILE -> asset(
                        tag = 2,
                        disposer = {
                            tileDisposals.incrementAndGet()
                            throw laterFailure
                        },
                    )
                    PageDecodePurpose.ANIMATION_FRAME -> {
                        frameEntered.countDown()
                        check(releaseFrame.await(5, TimeUnit.SECONDS))
                        asset(tag = 3, disposer = { frameDisposals.incrementAndGet() })
                            .also { frameReturned.countDown() }
                    }
                }
            },
        )
        pipeline.beginGeneration(1L)
        val regionSession = pipeline.openRegionSession(contentKey)
        val animationSession = pipeline.openAnimationSession(contentKey)
        val acquiringFrame = async {
            runCatching { animationSession.acquireFrame(frameKey) }.getOrNull()?.close()
        }

        try {
            regionSession.pin()
            requireNotNull(pipeline.acquire(fullKey)).close()
            val tileLease = requireNotNull(regionSession.acquireTile(tileKey))
            assertTrue(pipeline.commitRegionTile(tileKey, tileLease))
            tileLease.close()
            assertTrue(withContext(Dispatchers.IO) { frameEntered.await(5, TimeUnit.SECONDS) })

            val thrown = assertThrows(IllegalStateException::class.java) {
                when (fence) {
                    PipelineFence.ATTEMPT -> pipeline.beginPageAttempt(contentKey.pageId, 1L, 1L)
                    PipelineFence.GENERATION -> pipeline.beginGeneration(2L)
                }
            }

            assertSame(firstFailure, thrown)
            assertEquals(listOf(laterFailure), thrown.suppressed.toList())
            assertEquals(1, fullDisposals.get())
            assertEquals(1, tileDisposals.get())
            assertTrue(pipeline.snapshot().cache.keys.isEmpty())
            assertTrue(pipeline.snapshot().tileCache.keys.isEmpty())
            assertEquals(0, pipeline.snapshot().inFlightCount)
            assertTrue(contentOwner.snapshot().activeLeaseCounts.isEmpty())

            releaseFrame.countDown()
            assertTrue(withContext(Dispatchers.IO) { frameReturned.await(5, TimeUnit.SECONDS) })
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                withTimeout(5_000) { acquiringFrame.await() }
            }
            assertEquals(1, frameDisposals.get())
        } finally {
            releaseFrame.countDown()
            acquiringFrame.cancelAndJoin()
            runCatching { regionSession.close() }
            runCatching { animationSession.close() }
            runCatching { pipeline.close() }
            contentOwner.close()
            ioScope.cancel()
        }
    }

    private fun reporter(events: MutableList<ReaderIoEvent> = CopyOnWriteArrayList()): ReaderIoReporter {
        val now = AtomicLong()
        return ReaderIoReporter(
            probe = ReaderIoProbe(events::add),
            clock = ReaderMonotonicClock(now::incrementAndGet),
        )
    }

    private fun key(
        chapterId: Long = 1L,
        pageIndex: Int = 0,
        generation: Long = 1L,
        encodedPageRef: String = "opaque://page-0",
        maxWidth: Int = 2_048,
        maxHeight: Int = 2_048,
    ) = ReaderPageDecodeKey(
        contentKey = ReaderPageContentOpenRequest(
            pageId = ReaderPageId(ReaderChapterId(chapterId), pageIndex),
            generation = generation,
            encodedPageRef = EncodedPageRef(encodedPageRef),
        ),
        purpose = PageDecodePurpose.FULL_PAGE,
        maxWidth = maxWidth,
        maxHeight = maxHeight,
    )

    private fun asset(
        tag: Int,
        disposer: () -> Unit = {},
    ): DesktopReaderImageAsset {
        val width = 2 + (tag and 1)
        val bitmap = Bitmap().apply {
            allocN32Pixels(width, 2)
        }.asComposeImageBitmap()
        return DesktopReaderImageAsset(
            bitmap = bitmap,
            sourceWidth = width,
            sourceHeight = 2,
            estimatedBytes = width * 2L * 4L,
            sampled = false,
            disposer = disposer,
        )
    }

    private fun ReaderIoEvent.matches(type: ReaderIoEventType, key: ReaderPageDecodeKey): Boolean =
        this.type == type && pageId == key.contentKey.pageId && generation == key.generation

    private enum class PipelineFence { ATTEMPT, GENERATION }

    private companion object {
        val ENCODED_BYTES = byteArrayOf(1, 2, 3, 4)
    }
}
