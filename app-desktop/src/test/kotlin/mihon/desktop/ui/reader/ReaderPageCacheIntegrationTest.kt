package mihon.desktop.ui.reader

import androidx.compose.ui.graphics.asSkiaBitmap
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mihon.desktop.reader.DesktopReaderImageAssetLease
import mihon.desktop.reader.DesktopReaderPageContentOwner
import mihon.desktop.reader.DesktopReaderPageImagePipeline
import mihon.desktop.reader.DesktopReaderPresentationImageOwner
import mihon.desktop.reader.DesktopReaderPresentationImageSlotIdentity
import mihon.desktop.reader.DesktopReaderPresentationImageState
import mihon.desktop.reader.EdgePixelMatcher
import mihon.desktop.reader.ReaderPageIoObserver
import mihon.desktop.reader.SkiaImageDecoder
import mihon.domain.reader.PageDecodePurpose
import mihon.domain.reader.PageRotation
import mihon.domain.reader.PageSplitHalf
import mihon.domain.reader.PixelBounds
import mihon.domain.reader.ReaderPageDecodeKey
import mihon.domain.reader.content.ReaderPageContentOpenRequest
import mihon.domain.reader.observability.ReaderIoProbe
import mihon.domain.reader.observability.ReaderIoReporter
import mihon.domain.reader.observability.ReaderMonotonicClock
import mihon.domain.reader.splitPageBounds
import mihon.domain.reader.session.EncodedPageRef
import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderPageId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReaderPageCacheIntegrationTest {

    @Test
    fun `cache revision observer matches late pages preserves pairs after eviction and never loads`() = runTest {
        val width = 40
        val height = 100
        fun seamColor(y: Int): Int {
            val red = 40 + (y * 170 / (height - 1))
            val green = 220 - (y * 150 / (height - 1))
            return (0xFF shl 24) or (red shl 16) or (green shl 8) or 90
        }
        val refs = listOf("cover", "left-half", "right-half").map(::EncodedPageRef)
        val bytesByRef = mapOf(
            refs[0] to pngBytes(width, height) { _, _ -> 0xFF777777.toInt() },
            refs[1] to pngBytes(width, height) { x, y -> if (x >= width - 5) seamColor(y) else RED },
            refs[2] to pngBytes(width, height) { x, y -> if (x < 5) seamColor(y) else BLUE },
        )
        val fetchCounts = mutableMapOf<EncodedPageRef, Int>()
        val reporter = ReaderIoReporter(ReaderIoProbe.None, ReaderMonotonicClock(System::nanoTime))
        val contentOwner = DesktopReaderPageContentOwner(
            scope = this,
            encodedPageReader = { ref ->
                synchronized(fetchCounts) { fetchCounts[ref] = fetchCounts.getOrDefault(ref, 0) + 1 }
                bytesByRef.getValue(ref)
            },
            ioReporter = reporter,
        )
        val pipeline = DesktopReaderPageImagePipeline(
            scope = this,
            pageContentOwner = contentOwner,
            ioReporter = reporter,
        )
        val presentationOwner = DesktopReaderPresentationImageOwner(
            scope = this,
            pageImagePipeline = pipeline,
            pageIoObserver = ReaderPageIoObserver(reporter),
        )
        val updates = Channel<Pair<Long, Set<Pair<Int, Int>>>>(Channel.UNLIMITED)

        try {
            presentationOwner.beginGeneration(GENERATION)
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                observeDesktopMatchedPairs(
                    presentationImageOwner = presentationOwner,
                    autoSpreadMatching = true,
                    dualPageMode = true,
                    pageCount = refs.size,
                    retainedMatchedPairs = emptySet(),
                    findMatchedPairs = { count, pageAt -> EdgePixelMatcher().findMatchedPairs(count, pageAt) },
                ) { pairs ->
                    updates.trySend(presentationOwner.cacheRevision.value to pairs)
                }
            }

            suspend fun awaitUpdate(revision: Long, expected: Set<Pair<Int, Int>>) {
                withContext(Dispatchers.Default) {
                    withTimeout(5_000) {
                        while (true) {
                            val (observedRevision, pairs) = updates.receive()
                            if (observedRevision >= revision && pairs == expected) return@withTimeout
                        }
                    }
                }
            }

            awaitUpdate(presentationOwner.cacheRevision.value, emptySet())
            assertTrue(fetchCounts.isEmpty(), "The matcher must not load an uncached page")

            refs.indices.forEach { pageIndex ->
                val pageId = ReaderPageId(ReaderChapterId(1L), pageIndex)
                val key = fullPageKey(pageId, refs[pageIndex])
                val holder = presentationOwner.createHolder(
                    identity = presentationIdentity(pageId),
                    decodeKey = key,
                )
                try {
                    holder.acquire()
                    assertTrue(holder.state.first(::isTerminalPresentation) is DesktopReaderPresentationImageState.Ready)
                } finally {
                    holder.close()
                }
            }
            awaitUpdate(presentationOwner.cacheRevision.value, setOf(1 to 2))
            assertEquals(3, synchronized(fetchCounts) { fetchCounts.values.sum() })

            pipeline.clear()
            presentationOwner.retainCachedFullPageAssets().useAll { cached -> assertTrue(cached.isEmpty()) }
            awaitUpdate(presentationOwner.cacheRevision.value, setOf(1 to 2))
            assertEquals(3, synchronized(fetchCounts) { fetchCounts.values.sum() })
            assertEquals(refs.toSet(), synchronized(fetchCounts) { fetchCounts.keys.toSet() })
        } finally {
            presentationOwner.close()
            pipeline.close()
            contentOwner.close()
        }
    }

    @Test
    fun `retry revision suppresses a stale edge match that completes after invalidation`() = runTest {
        val refs = listOf(EncodedPageRef("left"), EncodedPageRef("right"))
        val bytesByRef = refs.associateWith { pngBytes(8, 12) { _, _ -> RED } }
        val reporter = ReaderIoReporter(ReaderIoProbe.None, ReaderMonotonicClock(System::nanoTime))
        val contentOwner = DesktopReaderPageContentOwner(
            scope = this,
            encodedPageReader = bytesByRef::get,
            ioReporter = reporter,
        )
        val pipeline = DesktopReaderPageImagePipeline(
            scope = this,
            pageContentOwner = contentOwner,
            ioReporter = reporter,
        )
        val presentationOwner = DesktopReaderPresentationImageOwner(
            scope = this,
            pageImagePipeline = pipeline,
            pageIoObserver = ReaderPageIoObserver(reporter),
        )
        val matcherEntered = CompletableDeferred<Unit>()
        val releaseStaleMatcher = CompletableDeferred<Unit>()
        val updates = Channel<Set<Pair<Int, Int>>>(Channel.UNLIMITED)

        try {
            presentationOwner.beginGeneration(GENERATION)
            refs.forEachIndexed { pageIndex, ref ->
                val pageId = ReaderPageId(ReaderChapterId(1L), pageIndex)
                val holder = presentationOwner.createHolder(
                    identity = presentationIdentity(pageId),
                    decodeKey = fullPageKey(pageId, ref),
                )
                try {
                    holder.acquire()
                    assertTrue(holder.state.first(::isTerminalPresentation) is DesktopReaderPresentationImageState.Ready)
                } finally {
                    holder.close()
                }
            }

            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                var invocation = 0
                observeDesktopMatchedPairs(
                    presentationImageOwner = presentationOwner,
                    autoSpreadMatching = true,
                    dualPageMode = true,
                    pageCount = refs.size,
                    retainedMatchedPairs = emptySet(),
                    findMatchedPairs = { _, _ ->
                        if (invocation++ == 0) {
                            matcherEntered.complete(Unit)
                            withContext(NonCancellable) { releaseStaleMatcher.await() }
                            setOf(0 to 1)
                        } else {
                            emptySet()
                        }
                    },
                    onMatchedPairsChanged = { pairs -> updates.trySend(pairs) },
                )
            }

            matcherEntered.await()
            assertTrue(
                pipeline.beginPageAttempt(
                    pageId = ReaderPageId(ReaderChapterId(1L), 0),
                    generation = GENERATION,
                    attemptGeneration = 1L,
                ),
            )
            releaseStaleMatcher.complete(Unit)

            assertEquals(emptySet<Pair<Int, Int>>(), withTimeout(5_000) { updates.receive() })
            val snapshot = pipeline.snapshot()
            assertEquals(1, snapshot.cache.entryCount)
            assertEquals(0L, snapshot.memory.pinnedBytes)
            assertEquals(snapshot.memory.cacheBytes, snapshot.memory.residentBytes)
        } finally {
            releaseStaleMatcher.complete(Unit)
            presentationOwner.close()
            pipeline.close()
            contentOwner.close()
        }
    }

    @Test
    fun `cache hit applies left right and rotated virtual page source bounds`() {
        val horizontal = decodeBitmap(
            width = 6,
            height = 2,
            colorAt = { x, _ -> if (x < 3) RED else BLUE },
        )

        val left = transformCachedPageBitmapWithSourceBounds(horizontal, splitHalf = PageSplitHalf.LEFT).bitmap
        val right = transformCachedPageBitmapWithSourceBounds(horizontal, splitHalf = PageSplitHalf.RIGHT).bitmap

        assertEquals(3, left.width)
        assertEquals(RED, left.asSkiaBitmap().getColor(0, 0))
        assertEquals(3, right.width)
        assertEquals(BLUE, right.asSkiaBitmap().getColor(0, 0))

        val vertical = decodeBitmap(
            width = 2,
            height = 6,
            colorAt = { _, y -> if (y < 3) GREEN else MAGENTA },
        )
        val rotatedLeftBounds = requireNotNull(
            splitPageBounds(2, 6, PageSplitHalf.LEFT, PageRotation.CLOCKWISE_90),
        )
        val rotatedRightBounds = requireNotNull(
            splitPageBounds(2, 6, PageSplitHalf.RIGHT, PageRotation.CLOCKWISE_90),
        )

        val rotatedLeft = transformCachedPageBitmapWithSourceBounds(
            vertical,
            splitHalf = PageSplitHalf.LEFT,
            sourceBounds = rotatedLeftBounds,
        ).bitmap
        val rotatedRight = transformCachedPageBitmapWithSourceBounds(
            vertical,
            splitHalf = PageSplitHalf.RIGHT,
            sourceBounds = rotatedRightBounds,
        ).bitmap

        assertEquals(3, rotatedLeft.height)
        assertEquals(MAGENTA, rotatedLeft.asSkiaBitmap().getColor(0, 0))
        assertEquals(3, rotatedRight.height)
        assertEquals(GREEN, rotatedRight.asSkiaBitmap().getColor(0, 0))
    }

    @Test
    fun `cache hit applies pager border crop before display`() {
        val bordered = decodeBitmap(
            width = 6,
            height = 6,
            colorAt = { x, y -> if (x in 2..3 && y in 1..4) BLACK else WHITE },
        )

        val cropped = transformCachedPageBitmapWithSourceBounds(bordered, cropBorders = true).bitmap

        assertEquals(2, cropped.width)
        assertEquals(4, cropped.height)
        assertEquals(BLACK, cropped.asSkiaBitmap().getColor(0, 0))
    }

    @Test
    fun `split and source bounds are applied before border crop for ordinary and cached rendering`() {
        val borderedSpread = decodeBitmap(
            width = 10,
            height = 6,
            colorAt = { x, y ->
                val xWithinHalf = x % 5
                if (xWithinHalf in 1..3 && y in 1..4) BLACK else WHITE
            },
        )
        val ordinarySplit = transformCachedPageBitmapWithSourceBounds(
            bitmap = borderedSpread,
            splitHalf = PageSplitHalf.LEFT,
            cropBorders = true,
        ).bitmap
        val cachedBounds = transformCachedPageBitmapWithSourceBounds(
            bitmap = borderedSpread,
            sourceBounds = PixelBounds(x = 5, y = 0, width = 5, height = 6),
            cropBorders = true,
            sourceWidth = borderedSpread.width,
            sourceHeight = borderedSpread.height,
        ).bitmap

        listOf(ordinarySplit, cachedBounds).forEach { transformed ->
            assertEquals(3, transformed.width)
            assertEquals(4, transformed.height)
            assertEquals(BLACK, transformed.asSkiaBitmap().getColor(0, 0))
            assertEquals(BLACK, transformed.asSkiaBitmap().getColor(2, 3))
        }
    }

    @Test
    fun `downsampled cache maps rotated odd virtual halves in original coordinates`() = runTest {
        val bytes = pngBytes(
            width = 8,
            height = 15,
            colorAt = { _, y -> if (y < 8) GREEN else MAGENTA },
        )
        val cached = cacheDecodedAsset(
            bytes = bytes,
            maxDecodedWidth = 4,
            maxDecodedHeight = 8,
        )
        try {
            val asset = cached.lease.asset
            assertEquals(4, asset.bitmap.width)
            assertEquals(7, asset.bitmap.height)
            assertEquals(8, asset.sourceWidth)
            assertEquals(15, asset.sourceHeight)

            fun transform(half: PageSplitHalf, rotation: PageRotation) = transformCachedPageBitmapWithSourceBounds(
                bitmap = asset.bitmap,
                sourceBounds = requireNotNull(splitPageBounds(8, 15, half, rotation)),
                sourceWidth = asset.sourceWidth,
                sourceHeight = asset.sourceHeight,
            ).bitmap

            val clockwiseLeft = transform(PageSplitHalf.LEFT, PageRotation.CLOCKWISE_90)
            val clockwiseRight = transform(PageSplitHalf.RIGHT, PageRotation.CLOCKWISE_90)
            val counterClockwiseLeft = transform(PageSplitHalf.LEFT, PageRotation.COUNTER_CLOCKWISE_90)
            val counterClockwiseRight = transform(PageSplitHalf.RIGHT, PageRotation.COUNTER_CLOCKWISE_90)

            assertEquals(3, clockwiseLeft.height)
            assertEquals(MAGENTA, clockwiseLeft.asSkiaBitmap().getColor(0, clockwiseLeft.height - 1))
            assertEquals(4, clockwiseRight.height)
            assertEquals(GREEN, clockwiseRight.asSkiaBitmap().getColor(0, 0))
            assertEquals(3, counterClockwiseLeft.height)
            assertEquals(GREEN, counterClockwiseLeft.asSkiaBitmap().getColor(0, 0))
            assertEquals(4, counterClockwiseRight.height)
            assertEquals(MAGENTA, counterClockwiseRight.asSkiaBitmap().getColor(0, counterClockwiseRight.height - 1))
        } finally {
            cached.close()
        }
    }

    @Test
    fun `downsampled cache keeps ordinary split pixels and dimensions`() = runTest {
        val cached = cacheDecodedAsset(
            width = 14,
            height = 8,
            maxWidth = 7,
            maxHeight = 4,
            colorAt = { x, _ -> if (x < 7) RED else BLUE },
        )

        try {
            val asset = cached.lease.asset
            val left = transformCachedPageBitmapWithSourceBounds(
                bitmap = asset.bitmap,
                splitHalf = PageSplitHalf.LEFT,
                sourceWidth = asset.sourceWidth,
                sourceHeight = asset.sourceHeight,
            ).bitmap
            val right = transformCachedPageBitmapWithSourceBounds(
                bitmap = asset.bitmap,
                splitHalf = PageSplitHalf.RIGHT,
                sourceWidth = asset.sourceWidth,
                sourceHeight = asset.sourceHeight,
            ).bitmap

            assertEquals(3, left.width)
            assertEquals(RED, left.asSkiaBitmap().getColor(0, 0))
            assertEquals(4, right.width)
            assertEquals(BLUE, right.asSkiaBitmap().getColor(right.width - 1, 0))
        } finally {
            cached.close()
        }
    }

    @Test
    fun `downsampled cache keeps pager border crop`() = runTest {
        val cached = cacheDecodedAsset(
            width = 12,
            height = 12,
            maxWidth = 6,
            maxHeight = 6,
            colorAt = { x, y -> if (x in 2..9 && y in 4..7) BLACK else WHITE },
        )

        try {
            val asset = cached.lease.asset
            val cropped = transformCachedPageBitmapWithSourceBounds(
                bitmap = asset.bitmap,
                cropBorders = true,
                sourceWidth = asset.sourceWidth,
                sourceHeight = asset.sourceHeight,
            ).bitmap

            assertEquals(4, cropped.width)
            assertEquals(2, cropped.height)
            assertEquals(BLACK, cropped.asSkiaBitmap().getColor(0, 0))
        } finally {
            cached.close()
        }
    }

    @Test
    fun `downsampled cache rejects source bounds outside original dimensions`() = runTest {
        val cached = cacheDecodedAsset(
            width = 8,
            height = 15,
            maxWidth = 4,
            maxHeight = 8,
            colorAt = { _, _ -> RED },
        )

        try {
            val asset = cached.lease.asset
            assertThrows(IllegalArgumentException::class.java) {
                transformCachedPageBitmapWithSourceBounds(
                    bitmap = asset.bitmap,
                    sourceBounds = PixelBounds(0, 0, 8, 16),
                    sourceWidth = asset.sourceWidth,
                    sourceHeight = asset.sourceHeight,
                )
            }
        } finally {
            cached.close()
        }
    }

    private suspend fun CoroutineScope.cacheDecodedAsset(
        width: Int,
        height: Int,
        maxWidth: Int,
        maxHeight: Int,
        colorAt: (x: Int, y: Int) -> Int,
    ): CachedAssetFixture {
        val bytes = pngBytes(width, height, colorAt)
        return cacheDecodedAsset(bytes, maxWidth, maxHeight)
    }

    private suspend fun CoroutineScope.cacheDecodedAsset(
        bytes: ByteArray,
        maxDecodedWidth: Int,
        maxDecodedHeight: Int,
    ): CachedAssetFixture {
        val reporter = ReaderIoReporter(ReaderIoProbe.None, ReaderMonotonicClock(System::nanoTime))
        val contentOwner = DesktopReaderPageContentOwner(
            scope = this,
            encodedPageReader = { bytes },
            ioReporter = reporter,
        )
        val pipeline = DesktopReaderPageImagePipeline(
            scope = this,
            pageContentOwner = contentOwner,
            ioReporter = reporter,
        )
        val presentationOwner = DesktopReaderPresentationImageOwner(
            scope = this,
            pageImagePipeline = pipeline,
            pageIoObserver = ReaderPageIoObserver(reporter),
        )
        val pageId = ReaderPageId(ReaderChapterId(1L), 0)
        val key = fullPageKey(
            pageId = pageId,
            encodedPageRef = EncodedPageRef("downsampled"),
            maxWidth = maxDecodedWidth,
            maxHeight = maxDecodedHeight,
        )
        return try {
            presentationOwner.beginGeneration(GENERATION)
            val holder = presentationOwner.createHolder(presentationIdentity(pageId), key)
            try {
                holder.acquire()
                assertTrue(holder.state.first(::isTerminalPresentation) is DesktopReaderPresentationImageState.Ready)
            } finally {
                holder.close()
            }
            val retained = presentationOwner.retainCachedFullPageAssets()
            val lease = requireNotNull(retained[pageId.sourcePageIndex])
            retained.filterKeys { it != pageId.sourcePageIndex }.values.forEach(AutoCloseable::close)
            CachedAssetFixture(lease, presentationOwner, pipeline, contentOwner)
        } catch (error: Throwable) {
            presentationOwner.close()
            pipeline.close()
            contentOwner.close()
            throw error
        }
    }

    private fun fullPageKey(
        pageId: ReaderPageId,
        encodedPageRef: EncodedPageRef,
        maxWidth: Int = 2_048,
        maxHeight: Int = 2_048,
    ) = ReaderPageDecodeKey(
        contentKey = ReaderPageContentOpenRequest(pageId, GENERATION, encodedPageRef),
        purpose = PageDecodePurpose.FULL_PAGE,
        maxWidth = maxWidth,
        maxHeight = maxHeight,
    )

    private fun presentationIdentity(pageId: ReaderPageId) = DesktopReaderPresentationImageSlotIdentity(
        pageId = pageId,
        generation = GENERATION,
    )

    private fun isTerminalPresentation(state: DesktopReaderPresentationImageState): Boolean =
        state is DesktopReaderPresentationImageState.Ready || state is DesktopReaderPresentationImageState.Failed

    private inline fun Map<Int, DesktopReaderImageAssetLease>.useAll(
        block: (Map<Int, DesktopReaderImageAssetLease>) -> Unit,
    ) {
        try {
            block(this)
        } finally {
            values.forEach(AutoCloseable::close)
        }
    }

    private data class CachedAssetFixture(
        val lease: DesktopReaderImageAssetLease,
        val presentationOwner: DesktopReaderPresentationImageOwner,
        val pipeline: DesktopReaderPageImagePipeline,
        val contentOwner: DesktopReaderPageContentOwner,
    ) : AutoCloseable {
        override fun close() {
            lease.close()
            presentationOwner.close()
            pipeline.close()
            contentOwner.close()
        }
    }

    private fun decodeBitmap(
        width: Int,
        height: Int,
        colorAt: (x: Int, y: Int) -> Int,
    ) = SkiaImageDecoder.decode(pngBytes(width, height, colorAt))

    private fun pngBytes(
        width: Int,
        height: Int,
        colorAt: (x: Int, y: Int) -> Int,
    ) = ByteArrayOutputStream().use { output ->
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        repeat(height) { y ->
            repeat(width) { x -> image.setRGB(x, y, colorAt(x, y)) }
        }
        ImageIO.write(image, "png", output)
        output.toByteArray()
    }

    private companion object {
        const val GENERATION = 1L
        const val RED: Int = 0xFFFF0000.toInt()
        const val BLUE: Int = 0xFF0000FF.toInt()
        const val GREEN: Int = 0xFF00FF00.toInt()
        const val MAGENTA: Int = 0xFFFF00FF.toInt()
        const val BLACK: Int = 0xFF000000.toInt()
        const val WHITE: Int = 0xFFFFFFFF.toInt()
    }
}
