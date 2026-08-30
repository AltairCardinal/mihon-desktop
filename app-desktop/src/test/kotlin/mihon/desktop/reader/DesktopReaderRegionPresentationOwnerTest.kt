package mihon.desktop.reader

import androidx.compose.ui.graphics.asComposeImageBitmap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * RUA-04D2 contract for the large-static preview/region path.
 * Retry attempt identity belongs to 04D3; non-cooperative close races and unified budgets belong to 04D4.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopReaderRegionPresentationOwnerTest {

    @Test
    fun `4001 by 4000 static page enables region tiles only after its full preview is ready`() = runTest {
        val previewEntered = CompletableDeferred<Unit>()
        val releasePreview = CompletableDeferred<Unit>()
        val decodedKeys = CopyOnWriteArrayList<ReaderPageDecodeKey>()
        val fixture = fixture(
            scope = this,
            decoder = DesktopReaderPageImageDecoder { _, key ->
                decodedKeys += key
                if (key.purpose == PageDecodePurpose.FULL_PAGE) {
                    previewEntered.complete(Unit)
                    releasePreview.await()
                    asset(sourceWidth = 4_001, sourceHeight = 4_000)
                } else {
                    tileAsset(key)
                }
            },
        )

        try {
            fixture.owner.beginGeneration(1L)
            val holder = fixture.holder(generation = 1L)
            val tileKey = tileKey(generation = 1L, index = 0)

            holder.acquire()
            previewEntered.await()
            holder.updateViewportTiles(setOf(tileKey))
            runCurrent()

            assertFalse(holder.snapshot().regionTilesEnabled)
            assertTrue(decodedKeys.none { it.purpose == PageDecodePurpose.REGION_TILE })

            releasePreview.complete(Unit)
            awaitCondition {
                holder.snapshot().previewAsset != null && tileKey in holder.snapshot().readyTiles
            }

            assertTrue(holder.snapshot().regionTilesEnabled)
            assertEquals(
                listOf(PageDecodePurpose.FULL_PAGE, PageDecodePurpose.REGION_TILE),
                decodedKeys.map(ReaderPageDecodeKey::purpose),
            )
        } finally {
            releasePreview.complete(Unit)
            fixture.close()
        }
    }

    @Test
    fun `preview and viewport tiles share one physical open and same tile key single flights`() = runTest {
        val decodedKeys = CopyOnWriteArrayList<ReaderPageDecodeKey>()
        val fixture = fixture(
            scope = this,
            decoder = DesktopReaderPageImageDecoder { _, key ->
                decodedKeys += key
                if (key.purpose == PageDecodePurpose.FULL_PAGE) {
                    asset(sourceWidth = 4_001, sourceHeight = 4_000)
                } else {
                    tileAsset(key)
                }
            },
        )

        try {
            fixture.owner.beginGeneration(1L)
            val first = fixture.holder(generation = 1L)
            val second = fixture.holder(generation = 1L)
            first.acquire()
            second.acquire()
            awaitCondition { first.snapshot().previewAsset != null && second.snapshot().previewAsset != null }

            val viewportTiles = setOf(
                tileKey(generation = 1L, index = 0),
                tileKey(generation = 1L, index = 1),
                tileKey(generation = 1L, index = 2),
            )
            first.updateViewportTiles(viewportTiles)
            second.updateViewportTiles(viewportTiles)
            awaitCondition {
                first.snapshot().readyTiles.keys.containsAll(viewportTiles) &&
                    second.snapshot().readyTiles.keys.containsAll(viewportTiles)
            }

            assertEquals(1, fixture.contentReads.get(), "Preview and tiles must retain one encoded source")
            assertEquals(1, fixture.events.count { it.type == ReaderIoEventType.OPEN_PAGE })
            assertEquals(1, decodedKeys.count { it.purpose == PageDecodePurpose.FULL_PAGE })
            viewportTiles.forEach { key ->
                assertEquals(1, decodedKeys.count { it == key }, "Same tile key must single-flight")
                assertSame(first.snapshot().readyTiles[key], second.snapshot().readyTiles[key])
            }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `unified image budget keeps region tiles under an 8 entry LRU and draw lease survives eviction`() = runTest {
        val decodedKeys = CopyOnWriteArrayList<ReaderPageDecodeKey>()
        val disposals = mutableMapOf<ReaderPageDecodeKey, AtomicInteger>()
        val fixture = fixture(
            scope = this,
            decoder = DesktopReaderPageImageDecoder { _, key ->
                decodedKeys += key
                if (key.purpose == PageDecodePurpose.FULL_PAGE) {
                    asset(sourceWidth = 4_001, sourceHeight = 4_000)
                } else {
                    val disposal = disposals.getOrPut(key) { AtomicInteger() }
                    tileAsset(
                        key = key,
                        estimatedBytes = TILE_BYTES,
                        onDispose = { disposal.incrementAndGet() },
                    )
                }
            },
        )

        try {
            fixture.owner.beginGeneration(1L)
            val holder = fixture.holder(generation = 1L)
            holder.acquire()
            awaitCondition { holder.snapshot().regionTilesEnabled }

            val initialKeys = (0 until 8).map { tileKey(generation = 1L, index = it) }
            initialKeys.forEach { key ->
                holder.updateViewportTiles(setOf(key))
                awaitCondition { key in holder.snapshot().readyTiles }
                holder.updateViewportTiles(emptySet())
                awaitCondition { holder.snapshot().readyTiles.isEmpty() }
            }

            val initialCache = fixture.owner.snapshot().tileCache
            assertEquals(8, initialCache.maxEntries)
            assertEquals(192L * MIB, initialCache.maxBytes)
            assertEquals(8, initialCache.entryCount)
            assertEquals(64L * MIB, initialCache.usedBytes)
            assertTrue(initialCache.keys.all { it.purpose == PageDecodePurpose.REGION_TILE })
            assertTrue(
                fixture.pipeline.snapshot().cache.keys.none { it.purpose == PageDecodePurpose.REGION_TILE },
                "REGION_TILE must never enter the ordinary decoded cache",
            )

            val pinnedKey = initialKeys.first()
            holder.updateViewportTiles(setOf(pinnedKey))
            awaitCondition { pinnedKey in holder.snapshot().readyTiles }
            assertEquals(1, decodedKeys.count { it == pinnedKey }, "Cache hit must not decode the tile again")
            val drawLease = checkNotNull(holder.retainReadyTileForRender(pinnedKey))
            holder.updateViewportTiles(emptySet())
            awaitCondition { holder.snapshot().readyTiles.isEmpty() }

            val ninthKey = tileKey(generation = 1L, index = 8)
            holder.updateViewportTiles(setOf(ninthKey))
            awaitCondition { ninthKey in holder.snapshot().readyTiles }
            holder.updateViewportTiles(emptySet())
            awaitCondition { holder.snapshot().readyTiles.isEmpty() }
            val afterLruTouch = fixture.owner.snapshot().tileCache.keys
            assertTrue(pinnedKey in afterLruTouch, "A cache hit must refresh access order")
            assertFalse(initialKeys[1] in afterLruTouch, "The untouched least-recent tile must be evicted")

            (9..15).forEach { index ->
                val key = tileKey(generation = 1L, index = index)
                holder.updateViewportTiles(setOf(key))
                awaitCondition { key in holder.snapshot().readyTiles }
                holder.updateViewportTiles(emptySet())
                awaitCondition { holder.snapshot().readyTiles.isEmpty() }
            }
            assertFalse(pinnedKey in fixture.owner.snapshot().tileCache.keys)
            assertEquals(0, disposals.getValue(pinnedKey).get(), "An in-progress draw must pin an evicted tile")

            drawLease.close()
            assertEquals(1, disposals.getValue(pinnedKey).get())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `generation advance and final holder detach release region source and tile ownership`() = runTest {
        val disposals = AtomicInteger()
        val fixture = fixture(
            scope = this,
            decoder = DesktopReaderPageImageDecoder { _, key ->
                if (key.purpose == PageDecodePurpose.FULL_PAGE) {
                    asset(
                        sourceWidth = 4_001,
                        sourceHeight = 4_000,
                        onDispose = { disposals.incrementAndGet() },
                    )
                } else {
                    tileAsset(key, onDispose = { disposals.incrementAndGet() })
                }
            },
        )

        try {
            fixture.owner.beginGeneration(1L)
            val staleHolder = fixture.holder(generation = 1L)
            val staleTile = tileKey(generation = 1L, index = 0)
            staleHolder.acquire()
            staleHolder.updateViewportTiles(setOf(staleTile))
            awaitCondition { staleTile in staleHolder.snapshot().readyTiles }
            assertEquals(mapOf(contentKey(1L) to 1), fixture.contentOwner.snapshot().activeLeaseCounts)

            assertTrue(fixture.owner.beginGeneration(2L))
            awaitCondition { staleHolder.snapshot().closed }
            assertTrue(fixture.owner.snapshot().tileCache.keys.isEmpty())
            assertTrue(fixture.contentOwner.snapshot().activeLeaseCounts.isEmpty())
            assertEquals(2, disposals.get(), "Generation advance must release preview and tile leases")

            val detached = fixture.holder(generation = 2L)
            detached.acquire()
            awaitCondition { detached.snapshot().previewAsset != null }
            assertEquals(mapOf(contentKey(2L) to 1), fixture.contentOwner.snapshot().activeLeaseCounts)
            detached.close()
            assertTrue(detached.snapshot().closed)
            assertTrue(fixture.contentOwner.snapshot().activeLeaseCounts.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `ordinary static and animated previews never construct a region tile path`() = runTest {
        val testScope = this

        suspend fun assertNoRegionPath(sourceWidth: Int, animated: Boolean) {
            val decodedKeys = CopyOnWriteArrayList<ReaderPageDecodeKey>()
            val fixture = fixture(
                scope = testScope,
                decoder = DesktopReaderPageImageDecoder { _, key ->
                    decodedKeys += key
                    check(key.purpose == PageDecodePurpose.FULL_PAGE) {
                        "Non-large static and animated pages must not request REGION_TILE"
                    }
                    asset(
                        sourceWidth = sourceWidth,
                        sourceHeight = 4_000,
                        animationMetadata = if (animated) animationMetadata(sourceWidth, 4_000) else null,
                    )
                },
            )
            try {
                fixture.owner.beginGeneration(1L)
                val holder = fixture.holder(generation = 1L)
                holder.acquire()
                awaitCondition { holder.snapshot().previewAsset != null }
                holder.updateViewportTiles(setOf(tileKey(generation = 1L, index = 0)))
                testScope.runCurrent()

                assertFalse(holder.snapshot().regionTilesEnabled)
                assertTrue(holder.snapshot().readyTiles.isEmpty())
                assertEquals(listOf(PageDecodePurpose.FULL_PAGE), decodedKeys.map(ReaderPageDecodeKey::purpose))
            } finally {
                fixture.close()
            }
        }

        assertNoRegionPath(sourceWidth = 4_000, animated = false)
        assertNoRegionPath(sourceWidth = 4_001, animated = true)
    }

    private inner class Fixture(
        scope: kotlinx.coroutines.CoroutineScope,
        decoder: DesktopReaderPageImageDecoder,
    ) : AutoCloseable {
        val events = CopyOnWriteArrayList<ReaderIoEvent>()
        val contentReads = AtomicInteger()
        private val now = AtomicLong()
        private val reporter = ReaderIoReporter(
            probe = ReaderIoProbe(events::add),
            clock = ReaderMonotonicClock(now::incrementAndGet),
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

        fun holder(generation: Long) = owner.createHolder(
            identity = identity(generation),
            previewKey = fullPageKey(generation),
        )

        override fun close() {
            owner.close()
            pipeline.close()
            contentOwner.close()
        }
    }

    private fun fixture(
        scope: kotlinx.coroutines.CoroutineScope,
        decoder: DesktopReaderPageImageDecoder,
    ) = Fixture(scope, decoder)

    private fun identity(generation: Long) = DesktopReaderPresentationImageSlotIdentity(
        pageId = PAGE_ID,
        generation = generation,
    )

    private fun contentKey(generation: Long) = ReaderPageContentOpenRequest(
        pageId = PAGE_ID,
        generation = generation,
        encodedPageRef = ENCODED_REF,
    )

    private fun fullPageKey(generation: Long) = ReaderPageDecodeKey(
        contentKey = contentKey(generation),
        purpose = PageDecodePurpose.FULL_PAGE,
        maxWidth = 2_048,
        maxHeight = 2_048,
    )

    private fun tileKey(generation: Long, index: Int): ReaderPageDecodeKey {
        val column = index % 4
        val row = index / 4
        return ReaderPageDecodeKey(
            contentKey = contentKey(generation),
            purpose = PageDecodePurpose.REGION_TILE,
            maxWidth = 1_000,
            maxHeight = 1_000,
            region = PixelBounds(column * 1_000, row * 1_000, 1_000, 1_000),
        )
    }

    private fun tileAsset(
        key: ReaderPageDecodeKey,
        estimatedBytes: Long = 4L * MIB,
        onDispose: () -> Unit = {},
    ): DesktopReaderImageAsset {
        val region = checkNotNull(key.region)
        return asset(
            sourceWidth = region.width,
            sourceHeight = region.height,
            estimatedBytes = estimatedBytes,
            onDispose = onDispose,
        )
    }

    private fun asset(
        sourceWidth: Int,
        sourceHeight: Int,
        estimatedBytes: Long = 16L,
        animationMetadata: DesktopReaderAnimationMetadata? = null,
        onDispose: () -> Unit = {},
    ): DesktopReaderImageAsset {
        val bitmap = Bitmap().apply { allocN32Pixels(2, 2) }
        return DesktopReaderImageAsset(
            bitmap = bitmap.asComposeImageBitmap(),
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            estimatedBytes = estimatedBytes,
            sampled = true,
            animationMetadata = animationMetadata,
            disposer = {
                bitmap.close()
                onDispose()
            },
        )
    }

    private fun animationMetadata(width: Int, height: Int) = DesktopReaderAnimationMetadata(
        frames = List(2) {
            DesktopReaderAnimationFrameMetadata(
                durationMillis = 20L,
                bounds = PixelBounds(0, 0, width, height),
            )
        },
        repeatCount = null,
    )

    private suspend fun awaitCondition(condition: () -> Boolean) {
        withTimeout(5_000) {
            while (!condition()) delay(10)
        }
    }

    private companion object {
        const val MIB = 1024L * 1024L
        const val TILE_BYTES = 8L * MIB
        val PAGE_ID = ReaderPageId(ReaderChapterId(1L), 0)
        val ENCODED_REF = EncodedPageRef("opaque://large-static-page")
        val ENCODED_BYTES = byteArrayOf(0x4C, 0x41, 0x52, 0x47, 0x45)
    }
}
