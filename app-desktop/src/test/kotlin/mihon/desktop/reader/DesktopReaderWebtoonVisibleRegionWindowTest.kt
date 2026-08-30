package mihon.desktop.reader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntSize
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mihon.desktop.ui.reader.ReaderRegionViewport
import mihon.desktop.ui.reader.WebtoonAutoScrollSpeed
import mihon.desktop.ui.reader.WebtoonPresentationViewer
import mihon.desktop.ui.reader.readerRegionTileIntersectsViewport
import mihon.desktop.ui.reader.resolveReaderRegionDrawGeometry
import mihon.desktop.ui.reader.resolveReaderRegionTileDrawBounds
import mihon.desktop.ui.reader.resolveReaderVisibleSourceBounds
import mihon.domain.reader.PageDecodePurpose
import mihon.domain.reader.PixelBounds
import mihon.domain.reader.ReaderPageDecodeKey
import mihon.domain.reader.observability.ReaderIoProbe
import mihon.domain.reader.observability.ReaderIoReporter
import mihon.domain.reader.observability.ReaderMonotonicClock
import org.jetbrains.skia.Bitmap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Regression contract: Webtoon region ownership follows the LazyColumn viewport, not the whole item. */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class DesktopReaderWebtoonVisibleRegionWindowTest {

    @Test
    fun `tile selection and drawing share the same rounded pixel boundary`() {
        val geometry = resolveReaderRegionDrawGeometry(
            viewportSize = IntSize(101, 101),
            renderedBitmapSize = IntSize(3, 10),
            renderedSourceBounds = PixelBounds(0, 0, 3, 10),
            contentScale = ContentScale.FillWidth,
            alignment = Alignment.Center,
        )
        val leading = resolveReaderRegionTileDrawBounds(
            geometry = geometry,
            region = PixelBounds(0, 0, 3, 4),
        )
        val trailing = resolveReaderRegionTileDrawBounds(
            geometry = geometry,
            region = PixelBounds(0, 4, 3, 4),
        )
        val boundaryViewport = ReaderRegionViewport.Visible(
            top = trailing.y,
            bottomExclusive = trailing.y + 1,
        )

        assertEquals(leading.y + leading.height, trailing.y)
        assertFalse(readerRegionTileIntersectsViewport(geometry, leading, boundaryViewport, ZoomState()))
        assertTrue(readerRegionTileIntersectsViewport(geometry, trailing, boundaryViewport, ZoomState()))
        assertEquals(
            PixelBounds(0, 4, 3, 1),
            resolveReaderVisibleSourceBounds(
                geometry = geometry,
                viewport = boundaryViewport,
                zoomState = ZoomState(),
            ),
        )
    }

    @Test
    fun `mounted long Webtoon page retains only visible region strip while scrolling`() = runTest {
        val fixture = Fixture(this)
        val scene = ImageComposeScene(SCENE_WIDTH, SCENE_HEIGHT, coroutineContext = coroutineContext) {}
        var autoScroll by mutableStateOf(false)
        try {
            fixture.owner.beginGeneration(GENERATION)
            scene.setContent {
                WebtoonPresentationViewer(
                    chapter = readerChapterSession(
                        chapterId = CHAPTER_ID,
                        generation = GENERATION,
                        pageCount = 1,
                    ),
                    currentPage = 0,
                    currentDisplayUnitId = null,
                    initialAnchor = null,
                    autoScroll = autoScroll,
                    autoScrollSpeed = WebtoonAutoScrollSpeed.Fastest,
                    presentationImageOwner = fixture.owner,
                    onViewportChanged = {},
                )
            }

            val initialTiles = pumpUntilRegionRequestsSettle(scene, fixture)
            val initialBounds = initialTiles.map { key -> requireNotNull(key.region) }

            assertTrue(
                initialTiles.size in 1..MAX_VISIBLE_TILE_REQUESTS,
                "The first ${SCENE_HEIGHT}px viewport must not request the whole $SOURCE_HEIGHT px page; " +
                    "regions=$initialBounds",
            )
            assertTrue(
                initialBounds.maxOf { bounds -> bounds.y + bounds.height } <= SOURCE_HEIGHT / 4,
                "The initial viewport must keep REGION_TILE requests near the visible leading strip; " +
                    "regions=$initialBounds",
            )

            autoScroll = true
            render(scene)
            runCurrent()
            advanceTimeBy(SCROLL_DURATION_MS)
            runCurrent()
            autoScroll = false
            render(scene)

            val afterScrollTiles = pumpUntilRegionRequestsSettle(
                scene = scene,
                fixture = fixture,
                minimumDistinctRequests = initialTiles.size + 1,
            )
            val newTiles = afterScrollTiles - initialTiles
            val initialBottom = initialBounds.maxOf { bounds -> bounds.y + bounds.height }

            assertTrue(newTiles.isNotEmpty(), "Scrolling must request a new source strip")
            assertTrue(
                newTiles.size <= MAX_VISIBLE_TILE_REQUESTS,
                "One settled viewport change must request only its new bounded strip; new=$newTiles",
            )
            assertTrue(
                newTiles.all { key -> requireNotNull(key.region).y >= initialBottom },
                "The new requests must advance beyond the initial visible strip; initial=$initialTiles new=$newTiles",
            )
            initialTiles.forEach { oldKey ->
                assertEquals(
                    1,
                    fixture.disposalCount(oldKey),
                    "Leaving the old strip must release its holder/draw leases after cache eviction: $oldKey",
                )
            }
            assertTrue(
                fixture.pipeline.snapshot().tileCache.keys.all { key -> key in newTiles },
                "The bounded tile cache must retain only the newly visible strip",
            )
            assertFalse(
                fixture.pipeline.snapshot().tileCache.keys.any { key -> key in initialTiles },
                "No initial strip may remain cache-owned after it leaves the viewport",
            )
            assertEquals(
                1,
                fixture.decodedKeys.count { key -> key.purpose == PageDecodePurpose.FULL_PAGE },
                "Viewport movement must not repeat the full-page preview decode",
            )
        } finally {
            scene.close()
            fixture.close()
        }
    }

    private fun TestScope.pumpUntilRegionRequestsSettle(
        scene: ImageComposeScene,
        fixture: Fixture,
        minimumDistinctRequests: Int = 1,
    ): Set<ReaderPageDecodeKey> {
        var previous = emptySet<ReaderPageDecodeKey>()
        var stableFrames = 0
        repeat(PUMP_LIMIT) {
            runCurrent()
            render(scene)
            runCurrent()
            val current = fixture.regionKeys()
            stableFrames = if (current == previous && current.size >= minimumDistinctRequests) {
                stableFrames + 1
            } else {
                0
            }
            if (stableFrames >= REQUIRED_STABLE_FRAMES) return current
            previous = current
        }
        throw AssertionError(
            "REGION_TILE requests did not settle with at least $minimumDistinctRequests distinct keys; " +
                "decoded=${fixture.decodedKeys}",
        )
    }

    private class Fixture(
        scope: TestScope,
    ) : AutoCloseable {
        val decodedKeys = CopyOnWriteArrayList<ReaderPageDecodeKey>()
        private val tileDisposals = ConcurrentHashMap<ReaderPageDecodeKey, AtomicInteger>()
        private val reporter = ReaderIoReporter(
            probe = ReaderIoProbe {},
            clock = ReaderMonotonicClock { 0L },
        )
        private val contentOwner = DesktopReaderPageContentOwner(
            scope = scope,
            encodedPageReader = { ENCODED_BYTES },
            ioReporter = reporter,
        )
        val pipeline = DesktopReaderPageImagePipeline(
            scope = scope,
            pageContentOwner = contentOwner,
            ioReporter = reporter,
            decoder = DesktopReaderPageImageDecoder { _, key ->
                decodedKeys += key
                if (key.purpose == PageDecodePurpose.FULL_PAGE) {
                    longPagePreviewAsset()
                } else {
                    regionTileAsset(key)
                }
            },
        )
        val owner = DesktopReaderPresentationImageOwner(
            scope = scope,
            pageImagePipeline = pipeline,
            pageIoObserver = ReaderPageIoObserver(reporter),
        )

        fun regionKeys(): Set<ReaderPageDecodeKey> = decodedKeys
            .filterTo(linkedSetOf()) { key -> key.purpose == PageDecodePurpose.REGION_TILE }

        fun disposalCount(key: ReaderPageDecodeKey): Int = tileDisposals[key]?.get() ?: 0

        private fun longPagePreviewAsset(): DesktopReaderImageAsset {
            val bitmap = Bitmap().apply { allocN32Pixels(PREVIEW_WIDTH, PREVIEW_HEIGHT) }
            return DesktopReaderImageAsset(
                bitmap = bitmap.asComposeImageBitmap(),
                sourceWidth = SOURCE_WIDTH,
                sourceHeight = SOURCE_HEIGHT,
                estimatedBytes = PREVIEW_WIDTH.toLong() * PREVIEW_HEIGHT * BYTES_PER_PIXEL,
                sampled = true,
                disposer = bitmap::close,
            )
        }

        private fun regionTileAsset(key: ReaderPageDecodeKey): DesktopReaderImageAsset {
            val region = requireNotNull(key.region)
            val bitmapWidth = TILE_FIXTURE_BITMAP_WIDTH.coerceAtMost(region.width)
            val bitmapHeight = maxOf(1, (bitmapWidth.toLong() * region.height / region.width).toInt())
            val bitmap = Bitmap().apply { allocN32Pixels(bitmapWidth, bitmapHeight) }
            val disposal = tileDisposals.computeIfAbsent(key) { AtomicInteger() }
            return DesktopReaderImageAsset(
                bitmap = bitmap.asComposeImageBitmap(),
                sourceWidth = SOURCE_WIDTH,
                sourceHeight = SOURCE_HEIGHT,
                estimatedBytes = TILE_CACHE_PRESSURE_BYTES,
                sampled = true,
                disposer = {
                    bitmap.close()
                    disposal.incrementAndGet()
                },
            )
        }

        override fun close() {
            owner.close()
            pipeline.close()
            contentOwner.close()
        }
    }

    private companion object {
        const val CHAPTER_ID = 904L
        const val GENERATION = 4L
        const val SCENE_WIDTH = 600
        const val SCENE_HEIGHT = 400
        const val SOURCE_WIDTH = 2_000
        const val SOURCE_HEIGHT = 40_000
        const val PREVIEW_WIDTH = 96
        const val PREVIEW_HEIGHT = 1_920
        const val TILE_FIXTURE_BITMAP_WIDTH = 64
        const val BYTES_PER_PIXEL = 4L
        const val TILE_CACHE_PRESSURE_BYTES = 100L * 1024L * 1024L
        const val MAX_VISIBLE_TILE_REQUESTS = 2
        const val SCROLL_DURATION_MS = 5_000L
        const val PUMP_LIMIT = 40
        const val REQUIRED_STABLE_FRAMES = 3
        val ENCODED_BYTES = byteArrayOf(9, 0, 4)

        fun render(scene: ImageComposeScene) {
            scene.render().toComposeImageBitmap().asSkiaBitmap().close()
        }
    }
}
