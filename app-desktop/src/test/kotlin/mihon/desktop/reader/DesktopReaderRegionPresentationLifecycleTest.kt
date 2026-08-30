package mihon.desktop.reader

import androidx.compose.ui.graphics.asComposeImageBitmap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineExceptionHandler
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
import mihon.domain.reader.PageDecodeRequest
import mihon.domain.reader.PageDecodeResult
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** RED lifecycle matrix for the REGION_TILE path; Retry attempt identity remains RUA-04D3. */
class DesktopReaderRegionPresentationLifecycleTest {

    @Test
    fun `generation advance rejects a non cooperative late region tile and releases its source`() = runTest {
        assertNonCooperativeTileTermination(RegionTermination.GENERATION_ADVANCE)
    }

    @Test
    fun `holder detach rejects a non cooperative late region tile instead of warming stale tile cache`() = runTest {
        assertNonCooperativeTileTermination(RegionTermination.HOLDER_DETACH)
    }

    @Test
    fun `owner close rejects a non cooperative late region tile instead of warming stale tile cache`() = runTest {
        assertNonCooperativeTileTermination(RegionTermination.OWNER_CLOSE)
    }

    @Test
    fun `pipeline close rejects a non cooperative late region tile and releases its source`() = runTest {
        assertNonCooperativeTileTermination(RegionTermination.PIPELINE_CLOSE)
    }

    @Test
    fun `null thrown and unsupported tile failures retain preview and do not block another tile`() = runTest {
        val unhandledErrors = CopyOnWriteArrayList<Throwable>()
        val tileAttemptsFinished = mapOf(
            0 to CountDownLatch(1),
            1_000 to CountDownLatch(1),
            2_000 to CountDownLatch(1),
            3_000 to CountDownLatch(1),
        )
        val successfulTileDisposals = AtomicInteger()
        val previewDisposals = AtomicInteger()
        val exceptionHandler = CoroutineExceptionHandler { _, error -> unhandledErrors += error }
        val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)
        val fixture = Fixture(
            scope = ioScope,
            decoder = DesktopReaderPageImageDecoder { _, key ->
                if (key.purpose == PageDecodePurpose.FULL_PAGE) {
                    asset(
                        sourceWidth = LARGE_WIDTH,
                        sourceHeight = LARGE_HEIGHT,
                        onDispose = { previewDisposals.incrementAndGet() },
                    )
                } else {
                    try {
                        val tileX = requireNotNull(key.region).x
                        when (tileX) {
                            0 -> null
                            1_000 -> throw IllegalStateException("synthetic region decoder failure")
                            2_000 -> {
                                val unsupported = ImageIoRegionDecodeAdapter().decodeRegion(
                                    encoded = unsupportedWebpBytes(),
                                    request = PageDecodeRequest(
                                        pageIndex = key.pageIndex,
                                        generation = key.generation,
                                        maxWidth = key.maxWidth,
                                        maxHeight = key.maxHeight,
                                        region = key.region,
                                    ),
                                )
                                check(unsupported is PageDecodeResult.Failure)
                                null
                            }
                            else -> tileAsset(
                                key = key,
                                onDispose = { successfulTileDisposals.incrementAndGet() },
                            )
                        }
                    } finally {
                        tileAttemptsFinished.getValue(requireNotNull(key.region).x).countDown()
                    }
                }
            },
        )

        try {
            fixture.owner.beginGeneration(GENERATION)
            val holder = fixture.holder()
            holder.acquire()
            awaitCondition { holder.snapshot().regionTilesEnabled }
            val preview = checkNotNull(holder.snapshot().previewAsset)
            val nullKey = tileKey(x = 0)
            val thrownKey = tileKey(x = 1_000)
            val unsupportedKey = tileKey(x = 2_000)
            val successfulKey = tileKey(x = 3_000)

            listOf(nullKey, thrownKey, unsupportedKey).forEach { failedKey ->
                holder.updateViewportTiles(setOf(failedKey))
                assertTrue(
                    withContext(Dispatchers.IO) {
                        tileAttemptsFinished.getValue(requireNotNull(failedKey.region).x).await(5, TimeUnit.SECONDS)
                    },
                )
                awaitCondition { fixture.pipeline.snapshot().inFlightCount == 0 }
                val afterFailure = holder.snapshot()
                assertTrue(afterFailure.regionTilesEnabled)
                assertTrue(afterFailure.previewAsset === preview)
                assertFalse(afterFailure.previewFailed)
                assertTrue(afterFailure.previewFailure == null)
                assertTrue(afterFailure.readyTiles.isEmpty())
            }

            holder.updateViewportTiles(setOf(successfulKey))
            assertTrue(
                withContext(Dispatchers.IO) {
                    tileAttemptsFinished.getValue(requireNotNull(successfulKey.region).x).await(5, TimeUnit.SECONDS)
                },
            )
            awaitCondition { successfulKey in holder.snapshot().readyTiles }
            // Give a failed child enough wall-clock time to reach the runtime exception handler.
            withContext(Dispatchers.IO) { Thread.sleep(100) }

            val afterFailures = holder.snapshot()
            assertTrue(afterFailures.regionTilesEnabled)
            assertTrue(afterFailures.previewAsset === preview)
            assertEquals(setOf(successfulKey), afterFailures.readyTiles.keys)
            assertFalse(nullKey in afterFailures.readyTiles)
            assertFalse(thrownKey in afterFailures.readyTiles)
            assertFalse(unsupportedKey in afterFailures.readyTiles)
            assertTrue(
                unhandledErrors.isEmpty(),
                "A tile failure must degrade to the retained preview instead of escaping the holder scope",
            )

            holder.close()
            awaitCondition { fixture.contentOwner.snapshot().activeLeaseCounts.isEmpty() }
            fixture.pipeline.close()
            awaitCondition { successfulTileDisposals.get() > 0 && previewDisposals.get() > 0 }
            assertEquals(1, successfulTileDisposals.get())
            assertEquals(1, previewDisposals.get())
            assertEquals(1, fixture.contentReads.get(), "Preview and all failed/successful tiles share one source open")
        } finally {
            fixture.close()
            ioScope.cancel()
        }
    }

    private suspend fun TestScope.assertNonCooperativeTileTermination(termination: RegionTermination) {
        val tileEntered = CountDownLatch(1)
        val releaseTile = CountDownLatch(1)
        val tileReturned = CountDownLatch(1)
        val tileDisposals = AtomicInteger()
        val previewDisposals = AtomicInteger()
        val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val fixture = Fixture(
            scope = ioScope,
            decoder = DesktopReaderPageImageDecoder { _, key ->
                if (key.purpose == PageDecodePurpose.FULL_PAGE) {
                    asset(
                        sourceWidth = LARGE_WIDTH,
                        sourceHeight = LARGE_HEIGHT,
                        onDispose = { previewDisposals.incrementAndGet() },
                    )
                } else {
                    tileEntered.countDown()
                    check(releaseTile.await(5, TimeUnit.SECONDS))
                    tileAsset(key, onDispose = { tileDisposals.incrementAndGet() })
                        .also { tileReturned.countDown() }
                }
            },
        )

        try {
            fixture.owner.beginGeneration(GENERATION)
            val holder = fixture.holder()
            val key = tileKey(x = 0)
            holder.acquire()
            awaitCondition { holder.snapshot().regionTilesEnabled }
            holder.updateViewportTiles(setOf(key))
            assertTrue(withContext(Dispatchers.IO) { tileEntered.await(5, TimeUnit.SECONDS) })

            when (termination) {
                RegionTermination.GENERATION_ADVANCE -> assertTrue(
                    fixture.owner.beginGeneration(GENERATION + 1),
                )
                RegionTermination.HOLDER_DETACH -> holder.close()
                RegionTermination.OWNER_CLOSE -> fixture.owner.close()
                RegionTermination.PIPELINE_CLOSE -> fixture.pipeline.close()
            }
            releaseTile.countDown()
            assertTrue(withContext(Dispatchers.IO) { tileReturned.await(5, TimeUnit.SECONDS) })
            awaitCondition { fixture.pipeline.snapshot().inFlightCount == 0 }

            assertFalse(key in holder.snapshot().readyTiles, "A detached old holder must not publish a late tile")
            assertTrue(
                fixture.pipeline.snapshot().tileCache.keys.isEmpty(),
                "A cancelled presentation must not warm tile cache after its non-cooperative decode returns",
            )
            assertTrue(
                fixture.contentOwner.snapshot().activeLeaseCounts.isEmpty(),
                "Termination must release the retained region source even while decode ignores cancellation",
            )
            awaitCondition { tileDisposals.get() == 1 }
            assertEquals(1, tileDisposals.get(), "The rejected late tile asset must be disposed exactly once")

            when (termination) {
                RegionTermination.GENERATION_ADVANCE -> Unit
                RegionTermination.HOLDER_DETACH,
                RegionTermination.OWNER_CLOSE,
                -> fixture.pipeline.clear()
                RegionTermination.PIPELINE_CLOSE -> fixture.owner.close()
            }
            awaitCondition { previewDisposals.get() == 1 }
        } finally {
            releaseTile.countDown()
            fixture.close()
            ioScope.cancel()
        }
    }

    private class Fixture(
        scope: CoroutineScope,
        decoder: DesktopReaderPageImageDecoder,
    ) : AutoCloseable {
        val contentReads = AtomicInteger()
        private val reporter = ReaderIoReporter(
            probe = ReaderIoProbe.None,
            clock = ReaderMonotonicClock { 0L },
        )
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
            decoder = decoder,
        )
        val owner = DesktopReaderRegionPresentationOwner(
            scope = scope,
            pageImagePipeline = pipeline,
        )

        fun holder(): DesktopReaderRegionPresentationHolder = owner.createHolder(
            identity = DesktopReaderPresentationImageSlotIdentity(PAGE_ID, GENERATION),
            previewKey = fullPageKey(),
        )

        override fun close() {
            owner.close()
            pipeline.close()
            contentOwner.close()
        }
    }

    private enum class RegionTermination {
        GENERATION_ADVANCE,
        HOLDER_DETACH,
        OWNER_CLOSE,
        PIPELINE_CLOSE,
    }

    private companion object {
        const val GENERATION = 7L
        const val LARGE_WIDTH = 4_001
        const val LARGE_HEIGHT = 4_000
        val PAGE_ID = ReaderPageId(ReaderChapterId(77L), sourcePageIndex = 0)
        val ENCODED_REF = EncodedPageRef("opaque://region-lifecycle")
        val ENCODED_BYTES = byteArrayOf(0x52, 0x45, 0x47, 0x49, 0x4F, 0x4E)

        fun contentKey() = ReaderPageContentOpenRequest(PAGE_ID, GENERATION, ENCODED_REF)

        fun fullPageKey() = ReaderPageDecodeKey(
            contentKey = contentKey(),
            purpose = PageDecodePurpose.FULL_PAGE,
            maxWidth = 2_048,
            maxHeight = 2_048,
        )

        fun tileKey(x: Int) = ReaderPageDecodeKey(
            contentKey = contentKey(),
            purpose = PageDecodePurpose.REGION_TILE,
            maxWidth = 1_000,
            maxHeight = 1_000,
            region = PixelBounds(x = x, y = 0, width = 1_000, height = 1_000),
        )

        fun tileAsset(
            key: ReaderPageDecodeKey,
            onDispose: () -> Unit = {},
        ): DesktopReaderImageAsset {
            val region = requireNotNull(key.region)
            return asset(
                sourceWidth = region.width,
                sourceHeight = region.height,
                onDispose = onDispose,
            )
        }

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
                sampled = true,
                disposer = {
                    bitmap.close()
                    onDispose()
                },
            )
        }

        fun unsupportedWebpBytes(): ByteArray = ByteArray(20).apply {
            "RIFF".encodeToByteArray().copyInto(this, destinationOffset = 0)
            "WEBP".encodeToByteArray().copyInto(this, destinationOffset = 8)
            "VP8 ".encodeToByteArray().copyInto(this, destinationOffset = 12)
        }

        suspend fun awaitCondition(condition: () -> Boolean) {
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                withTimeout(5_000) {
                    while (!condition()) delay(10)
                }
            }
        }
    }
}
