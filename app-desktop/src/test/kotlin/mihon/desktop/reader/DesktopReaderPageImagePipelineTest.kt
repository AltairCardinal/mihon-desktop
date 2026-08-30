package mihon.desktop.reader

import androidx.compose.ui.graphics.asComposeImageBitmap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
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
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DesktopReaderPageImagePipelineTest {

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
            releaseDecode.complete(Unit)

            val firstLease = requireNotNull(first.await())
            val secondLease = requireNotNull(second.await())
            assertSame(firstLease.asset, secondLease.asset)
            assertEquals(1, events.count { it.matches(ReaderIoEventType.OPEN_PAGE, key) })
            assertEquals(1, events.count { it.matches(ReaderIoEventType.DECODE, key) })
            assertEquals(0, pipeline.snapshot().inFlightCount)
            assertEquals(listOf(key), pipeline.snapshot().cache.keys)

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
        val staleKey = key(generation = 1L)
        val acquiring = async { pipeline.acquire(staleKey) }

        try {
            assertTrue(withContext(Dispatchers.IO) { decodeEntered.await(5, TimeUnit.SECONDS) })
            pipeline.beginGeneration(2L)
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

    @Test
    fun `attempt and generation fences recycle full tile inflight and shared content after disposer failures`() =
        runTest {
            PipelineFence.entries.forEach { fence -> assertExceptionCompleteFence(fence) }
        }

    @Test
    fun `close rejects and releases a non cooperative late decoded asset`() = runTest {
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
        val acquiring = async { pipeline.acquire(key()) }

        try {
            assertTrue(withContext(Dispatchers.IO) { decodeEntered.await(5, TimeUnit.SECONDS) })
            pipeline.close()
            pipeline.close()
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
