package mihon.desktop.reader

import androidx.compose.ui.graphics.asComposeImageBitmap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mihon.domain.reader.PageDecodePurpose
import mihon.domain.reader.PixelBounds
import mihon.domain.reader.ReaderPageDecodeKey
import mihon.domain.reader.content.ReaderPageContentOpenRequest
import mihon.domain.reader.observability.ReaderIoProbe
import mihon.domain.reader.observability.ReaderIoReporter
import mihon.domain.reader.observability.ReaderMonotonicClock
import mihon.domain.reader.session.EncodedPageRef
import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderPageId
import org.jetbrains.skia.Bitmap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DesktopReaderTerminationMatrixTest {

    @Test
    fun `late FULL preview cannot publish or warm cache after presentation termination`() = runTest {
        FullTermination.entries.forEach { termination ->
            assertLateFullPreviewTermination(termination)
        }
    }

    @Test
    fun `late FRAME cannot publish and releases source after holder or pipeline close`() = runTest {
        FrameTermination.entries.forEach { termination ->
            assertLateFrameTermination(termination)
        }
    }

    @Test
    fun `same key static and region holders keep survivor ownership until final detach`() = runTest {
        assertStaticSameKeySurvivor()
        assertRegionSameKeySurvivor()
    }

    @Test
    fun `FULL FRAME and TILE external pins survive owner and pipeline close until their final close`() = runTest {
        assertFullPinSurvivesClose()
        assertFramePinSurvivesClose()
        assertTilePinSurvivesClose()
    }

    @Test
    fun `cache close attempts every lease when the first disposer throws`() {
        val firstDisposed = AtomicInteger()
        val secondDisposed = AtomicInteger()
        val cache = DesktopReaderImageCache(maxEntries = 2, maxBytes = 128L)
        val first = asset(
            sourceWidth = 2,
            sourceHeight = 2,
            onDispose = {
                firstDisposed.incrementAndGet()
                throw IllegalStateException("first disposer failed")
            },
        )
        val second = asset(
            sourceWidth = 2,
            sourceHeight = 2,
            onDispose = { secondDisposed.incrementAndGet() },
        )
        cache.commit(fullKey(pageIndex = 10), first)
        cache.commit(fullKey(pageIndex = 11), second)
        first.close()
        second.close()

        assertThrows(IllegalStateException::class.java, cache::close)

        assertEquals(1, firstDisposed.get())
        assertEquals(1, secondDisposed.get(), "A failed disposer must not skip the remaining cache leases")
        assertEquals(0, cache.snapshot().entryCount)
    }

    private suspend fun TestScope.assertLateFullPreviewTermination(termination: FullTermination) {
        val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val fixture = GatedFixture(ioScope, PageDecodePurpose.FULL_PAGE)
        try {
            fixture.owner.beginGeneration(GENERATION)
            val holder = fixture.staticHolder()
            holder.acquire()
            fixture.awaitDecodeEntered()

            when (termination) {
                FullTermination.HOLDER_DETACH -> holder.close()
                FullTermination.OWNER_CLOSE -> fixture.owner.close()
                FullTermination.RUNTIME_EQUIVALENT_CLOSE -> fixture.closeRuntimeEquivalent()
            }
            fixture.releaseDecode()
            fixture.awaitDecodeReturned()
            awaitCondition { fixture.pipeline.snapshot().inFlightCount == 0 }

            assertFalse(holder.state.value is DesktopReaderPresentationImageState.Ready)
            assertTrue(
                fixture.pipeline.snapshot().cache.keys.isEmpty(),
                "A detached $termination preview must not warm the FULL cache",
            )
            awaitCondition { fixture.assetDisposals.get() == 1 }
            assertEquals(1, fixture.contentReads.get())
            assertEquals(1, fixture.assetDisposals.get())
            assertTrue(fixture.contentOwner.snapshot().activeLeaseCounts.isEmpty())
        } finally {
            fixture.releaseDecode()
            fixture.close()
            ioScope.cancel()
        }
    }

    private suspend fun TestScope.assertLateFrameTermination(termination: FrameTermination) {
        val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val fixture = GatedFixture(ioScope, PageDecodePurpose.ANIMATION_FRAME)
        try {
            fixture.owner.beginGeneration(GENERATION)
            val holder = fixture.animatedHolder()
            holder.requestFrame(0)
            fixture.awaitDecodeEntered()

            when (termination) {
                FrameTermination.HOLDER_DETACH -> holder.close()
                FrameTermination.PIPELINE_CLOSE -> fixture.pipeline.close()
            }
            fixture.releaseDecode()
            fixture.awaitDecodeReturned()
            awaitCondition { fixture.pipeline.snapshot().inFlightCount == 0 }

            assertTrue(holder.snapshot().readyKey == null)
            assertTrue(fixture.pipeline.snapshot().cache.keys.isEmpty())
            awaitCondition { fixture.assetDisposals.get() == 1 }
            assertEquals(1, fixture.contentReads.get())
            assertEquals(1, fixture.assetDisposals.get())
            assertTrue(fixture.contentOwner.snapshot().activeLeaseCounts.isEmpty())
            holder.close()
        } finally {
            fixture.releaseDecode()
            fixture.close()
            ioScope.cancel()
        }
    }

    private suspend fun TestScope.assertStaticSameKeySurvivor() {
        val fixture = ImmediateFixture(this, largePreview = true)
        try {
            fixture.owner.beginGeneration(GENERATION)
            val detached = fixture.staticHolder()
            val survivor = fixture.staticHolder()
            detached.acquire()
            survivor.acquire()
            awaitCondition {
                detached.state.value is DesktopReaderPresentationImageState.Ready &&
                    survivor.state.value is DesktopReaderPresentationImageState.Ready
            }
            assertEquals(1, fixture.contentReads.get())
            assertEquals(1, fixture.decodeCount(PageDecodePurpose.FULL_PAGE))

            detached.close()
            val survivorDraw = requireNotNull(survivor.retainReadyAsset())
            assertEquals(LARGE_WIDTH, survivorDraw.asset.sourceWidth)
            survivorDraw.close()
            assertEquals(1, fixture.contentOwner.snapshot().activeLeaseCounts.values.single())

            survivor.close()
            assertTrue(fixture.contentOwner.snapshot().activeLeaseCounts.isEmpty())
            fixture.pipeline.clear()
            assertEquals(1, fixture.disposalCount(fullKey()))
        } finally {
            fixture.close()
        }
    }

    private suspend fun TestScope.assertRegionSameKeySurvivor() {
        val fixture = ImmediateFixture(this, largePreview = true)
        val regionOwner = DesktopReaderRegionPresentationOwner(this, fixture.pipeline)
        try {
            regionOwner.beginGeneration(GENERATION)
            val detached = regionOwner.createHolder(identity(), fullKey())
            val survivor = regionOwner.createHolder(identity(), fullKey())
            detached.acquire()
            survivor.acquire()
            awaitCondition { detached.snapshot().regionTilesEnabled && survivor.snapshot().regionTilesEnabled }
            val tileKey = tileKey()
            detached.updateViewportTiles(setOf(tileKey))
            survivor.updateViewportTiles(setOf(tileKey))
            awaitCondition {
                tileKey in detached.snapshot().readyTiles && tileKey in survivor.snapshot().readyTiles
            }
            assertEquals(1, fixture.contentReads.get())
            assertEquals(1, fixture.decodeCount(PageDecodePurpose.FULL_PAGE))
            assertEquals(1, fixture.decodeCount(PageDecodePurpose.REGION_TILE))

            detached.close()
            val previewDraw = requireNotNull(survivor.retainReadyPreviewForRender())
            val tileDraw = requireNotNull(survivor.retainReadyTileForRender(tileKey))
            assertEquals(LARGE_WIDTH, previewDraw.asset.sourceWidth)
            assertEquals(2, tileDraw.asset.bitmap.width)
            previewDraw.close()
            tileDraw.close()
            assertEquals(1, fixture.contentOwner.snapshot().activeLeaseCounts.values.single())

            survivor.close()
            assertTrue(fixture.contentOwner.snapshot().activeLeaseCounts.isEmpty())
            fixture.pipeline.close()
            assertEquals(1, fixture.disposalCount(fullKey()))
            assertEquals(1, fixture.disposalCount(tileKey))
        } finally {
            regionOwner.close()
            fixture.close()
        }
    }

    private suspend fun TestScope.assertFullPinSurvivesClose() {
        val fixture = ImmediateFixture(this, largePreview = true)
        try {
            fixture.owner.beginGeneration(GENERATION)
            val holder = fixture.staticHolder()
            holder.acquire()
            awaitCondition { holder.state.value is DesktopReaderPresentationImageState.Ready }
            val pin = requireNotNull(holder.retainReadyAsset())

            fixture.owner.close()
            fixture.pipeline.close()

            assertEquals(LARGE_WIDTH, pin.asset.sourceWidth)
            assertEquals(0, fixture.disposalCount(fullKey()))
            pin.close()
            assertEquals(1, fixture.disposalCount(fullKey()))
        } finally {
            fixture.close()
        }
    }

    private suspend fun TestScope.assertFramePinSurvivesClose() {
        val fixture = ImmediateFixture(this)
        val frameKey = frameKey()
        try {
            fixture.owner.beginGeneration(GENERATION)
            val holder = fixture.animatedHolder()
            holder.requestFrame(0)
            awaitCondition { holder.snapshot().readyKey == frameKey }
            val pin = requireNotNull(holder.retainReadyFrameForRender(frameKey))

            fixture.owner.close()
            fixture.pipeline.close()

            assertEquals(2, pin.asset.bitmap.width)
            assertEquals(0, fixture.disposalCount(frameKey))
            pin.close()
            assertEquals(1, fixture.disposalCount(frameKey))
        } finally {
            fixture.close()
        }
    }

    private suspend fun TestScope.assertTilePinSurvivesClose() {
        val fixture = ImmediateFixture(this, largePreview = true)
        val regionOwner = DesktopReaderRegionPresentationOwner(this, fixture.pipeline)
        val tileKey = tileKey()
        try {
            regionOwner.beginGeneration(GENERATION)
            val holder = regionOwner.createHolder(identity(), fullKey())
            holder.acquire()
            awaitCondition { holder.snapshot().regionTilesEnabled }
            holder.updateViewportTiles(setOf(tileKey))
            awaitCondition { tileKey in holder.snapshot().readyTiles }
            val pin = requireNotNull(holder.retainReadyTileForRender(tileKey))

            regionOwner.close()
            fixture.pipeline.close()

            assertEquals(2, pin.asset.bitmap.width)
            assertEquals(0, fixture.disposalCount(tileKey))
            pin.close()
            assertEquals(1, fixture.disposalCount(tileKey))
        } finally {
            regionOwner.close()
            fixture.close()
        }
    }

    private class GatedFixture(
        scope: CoroutineScope,
        private val gatedPurpose: PageDecodePurpose,
    ) : AutoCloseable {
        private val reporter = reporter()
        private val decodeEntered = CountDownLatch(1)
        private val releaseDecode = CountDownLatch(1)
        private val decodeReturned = CountDownLatch(1)
        val contentReads = AtomicInteger()
        val assetDisposals = AtomicInteger()
        val contentOwner = DesktopReaderPageContentOwner(
            scope = scope,
            encodedPageReader = {
                contentReads.incrementAndGet()
                ENCODED_BYTES
            },
            ioReporter = reporter,
        )
        val pipeline = DesktopReaderPageImagePipeline(
            scope = scope,
            pageContentOwner = contentOwner,
            ioReporter = reporter,
            decoder = DesktopReaderPageImageDecoder { _, key ->
                check(key.purpose == gatedPurpose)
                decodeEntered.countDown()
                check(releaseDecode.await(5, TimeUnit.SECONDS))
                asset(
                    sourceWidth = if (key.purpose == PageDecodePurpose.FULL_PAGE) LARGE_WIDTH else 2,
                    sourceHeight = if (key.purpose == PageDecodePurpose.FULL_PAGE) LARGE_HEIGHT else 2,
                    onDispose = { assetDisposals.incrementAndGet() },
                ).also { decodeReturned.countDown() }
            },
        )
        val owner = DesktopReaderPresentationImageOwner(
            scope = scope,
            pageImagePipeline = pipeline,
            pageIoObserver = ReaderPageIoObserver(reporter),
        )

        fun staticHolder(): DesktopReaderPresentationImageHolder = owner.createHolder(identity(), fullKey())

        fun animatedHolder(): DesktopReaderAnimatedPresentationImageHolder = owner.createAnimatedHolder(
            identity = identity(),
            contentKey = contentKey(),
            maxWidth = DECODE_BOUND,
            maxHeight = DECODE_BOUND,
        )

        suspend fun awaitDecodeEntered() {
            assertTrue(withContext(Dispatchers.IO) { decodeEntered.await(5, TimeUnit.SECONDS) })
        }

        suspend fun awaitDecodeReturned() {
            assertTrue(withContext(Dispatchers.IO) { decodeReturned.await(5, TimeUnit.SECONDS) })
        }

        fun releaseDecode() {
            releaseDecode.countDown()
        }

        fun closeRuntimeEquivalent() {
            owner.close()
            pipeline.close()
            contentOwner.close()
        }

        override fun close() {
            owner.close()
            pipeline.close()
            contentOwner.close()
        }
    }

    private class ImmediateFixture(
        scope: CoroutineScope,
        private val largePreview: Boolean = false,
    ) : AutoCloseable {
        private val reporter = reporter()
        val contentReads = AtomicInteger()
        val decodedKeys = CopyOnWriteArrayList<ReaderPageDecodeKey>()
        private val disposals = ConcurrentHashMap<ReaderPageDecodeKey, AtomicInteger>()
        val contentOwner = DesktopReaderPageContentOwner(
            scope = scope,
            encodedPageReader = {
                contentReads.incrementAndGet()
                ENCODED_BYTES
            },
            ioReporter = reporter,
        )
        val pipeline = DesktopReaderPageImagePipeline(
            scope = scope,
            pageContentOwner = contentOwner,
            ioReporter = reporter,
            decoder = DesktopReaderPageImageDecoder { _, key ->
                decodedKeys += key
                val disposal = disposals.computeIfAbsent(key) { AtomicInteger() }
                asset(
                    sourceWidth = if (largePreview && key.purpose == PageDecodePurpose.FULL_PAGE) LARGE_WIDTH else 2,
                    sourceHeight = if (largePreview && key.purpose == PageDecodePurpose.FULL_PAGE) LARGE_HEIGHT else 2,
                    onDispose = { disposal.incrementAndGet() },
                )
            },
        )
        val owner = DesktopReaderPresentationImageOwner(
            scope = scope,
            pageImagePipeline = pipeline,
            pageIoObserver = ReaderPageIoObserver(reporter),
        )

        fun staticHolder(): DesktopReaderPresentationImageHolder = owner.createHolder(identity(), fullKey())

        fun animatedHolder(): DesktopReaderAnimatedPresentationImageHolder = owner.createAnimatedHolder(
            identity = identity(),
            contentKey = contentKey(),
            maxWidth = DECODE_BOUND,
            maxHeight = DECODE_BOUND,
        )

        fun decodeCount(purpose: PageDecodePurpose): Int = decodedKeys.count { it.purpose == purpose }

        fun disposalCount(key: ReaderPageDecodeKey): Int = disposals[key]?.get() ?: 0

        override fun close() {
            owner.close()
            pipeline.close()
            contentOwner.close()
        }
    }

    private suspend fun TestScope.awaitCondition(condition: () -> Boolean) {
        withContext(Dispatchers.IO) {
            withTimeout(5_000) {
                while (!condition()) delay(10)
            }
        }
    }

    private enum class FullTermination { HOLDER_DETACH, OWNER_CLOSE, RUNTIME_EQUIVALENT_CLOSE }
    private enum class FrameTermination { HOLDER_DETACH, PIPELINE_CLOSE }

    private companion object {
        const val GENERATION = 1L
        const val DECODE_BOUND = 2_048
        const val LARGE_WIDTH = 4_001
        const val LARGE_HEIGHT = 4_000
        val CHAPTER_ID = ReaderChapterId(801L)
        val PAGE_ID = ReaderPageId(CHAPTER_ID, 0)
        val ENCODED_REF = EncodedPageRef("memory:termination-matrix")
        val ENCODED_BYTES = byteArrayOf(1, 2, 3, 4)

        fun reporter() = ReaderIoReporter(
            probe = ReaderIoProbe.None,
            clock = ReaderMonotonicClock(System::nanoTime),
        )

        fun contentKey(
            pageIndex: Int = 0,
            generation: Long = GENERATION,
        ) = ReaderPageContentOpenRequest(
            pageId = ReaderPageId(CHAPTER_ID, pageIndex),
            generation = generation,
            encodedPageRef = ENCODED_REF,
        )

        fun fullKey(
            pageIndex: Int = 0,
            generation: Long = GENERATION,
        ) = ReaderPageDecodeKey(
            contentKey = contentKey(pageIndex, generation),
            purpose = PageDecodePurpose.FULL_PAGE,
            maxWidth = DECODE_BOUND,
            maxHeight = DECODE_BOUND,
        )

        fun frameKey() = ReaderPageDecodeKey(
            contentKey = contentKey(),
            purpose = PageDecodePurpose.ANIMATION_FRAME,
            maxWidth = DECODE_BOUND,
            maxHeight = DECODE_BOUND,
            frameIndex = 0,
        )

        fun tileKey() = ReaderPageDecodeKey(
            contentKey = contentKey(),
            purpose = PageDecodePurpose.REGION_TILE,
            maxWidth = 1_000,
            maxHeight = 1_000,
            region = PixelBounds(0, 0, 1_000, 1_000),
        )

        fun identity() = DesktopReaderPresentationImageSlotIdentity(
            pageId = PAGE_ID,
            generation = GENERATION,
        )

        fun asset(
            sourceWidth: Int,
            sourceHeight: Int,
            onDispose: () -> Unit,
        ): DesktopReaderImageAsset {
            val bitmap = Bitmap().apply { check(allocN32Pixels(2, 2)) }
            return DesktopReaderImageAsset(
                bitmap = bitmap.asComposeImageBitmap(),
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                estimatedBytes = 16L,
                sampled = sourceWidth != 2 || sourceHeight != 2,
                disposer = {
                    bitmap.close()
                    onDispose()
                },
            )
        }
    }
}
