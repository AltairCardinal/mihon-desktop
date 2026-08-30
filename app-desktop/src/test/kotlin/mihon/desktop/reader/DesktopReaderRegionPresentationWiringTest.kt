package mihon.desktop.reader

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mihon.desktop.ui.reader.WebtoonPresentationViewer
import mihon.desktop.ui.reader.ZoomablePagerViewer
import mihon.domain.reader.PageDecodePurpose
import mihon.domain.reader.PixelBounds
import mihon.domain.reader.ReaderPageDecodeKey
import mihon.domain.reader.observability.ReaderIoEvent
import mihon.domain.reader.observability.ReaderIoEventType
import mihon.domain.reader.observability.ReaderIoProbe
import mihon.domain.reader.observability.ReaderIoReporter
import mihon.domain.reader.observability.ReaderMonotonicClock
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Proves that region ownership is mounted by all three production presentation selectors. */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class DesktopReaderRegionPresentationWiringTest {

    @Test
    fun `mounted Single production presentation submits visible region tiles`() = runTest {
        val fixture = Fixture(this)
        val scene = ImageComposeScene(800, 600, coroutineContext = coroutineContext) {}
        try {
            fixture.owner.beginGeneration(GENERATION)
            scene.setContent {
                ZoomablePagerViewer(
                    chapter = readerChapterSession(
                        chapterId = CHAPTER_ID,
                        generation = GENERATION,
                        pageCount = 1,
                    ),
                    currentPage = 0,
                    isRtl = false,
                    isDualPage = false,
                    zoomState = ZoomState(scale = 2f),
                    presentationImageOwner = fixture.owner,
                    onPageChange = {},
                    onZoomChange = {},
                )
            }

            pumpUntilRegionPixels(scene, fixture, expectedPageIndexes = setOf(0)) { frame ->
                sameRgb(frame.getColor(400, 300), TILE_GREEN)
            }
        } finally {
            scene.close()
            fixture.close()
        }
    }

    @Test
    fun `mounted Dual production presentation submits tiles for both physical slots`() = runTest {
        val fixture = Fixture(this)
        val scene = ImageComposeScene(800, 600, coroutineContext = coroutineContext) {}
        try {
            fixture.owner.beginGeneration(GENERATION)
            scene.setContent {
                ZoomablePagerViewer(
                    chapter = readerChapterSession(
                        chapterId = CHAPTER_ID,
                        generation = GENERATION,
                        pageCount = 3,
                    ),
                    currentPage = 1,
                    isRtl = false,
                    isDualPage = true,
                    zoomState = ZoomState(scale = 2f),
                    presentationImageOwner = fixture.owner,
                    onPageChange = {},
                    onZoomChange = {},
                )
            }

            pumpUntilRegionPixels(scene, fixture, expectedPageIndexes = setOf(1, 2)) { frame ->
                frame.containsAllRgb(setOf(TILE_GREEN, TILE_BLUE))
            }
        } finally {
            scene.close()
            fixture.close()
        }
    }

    @Test
    fun `mounted Webtoon production presentation submits visible region tiles`() = runTest {
        val fixture = Fixture(this)
        val scene = ImageComposeScene(800, 600, coroutineContext = coroutineContext) {}
        try {
            fixture.owner.beginGeneration(GENERATION)
            scene.setContent {
                WebtoonPresentationViewer(
                    chapter = readerChapterSession(
                        chapterId = CHAPTER_ID,
                        generation = GENERATION,
                        pageCount = 2,
                    ),
                    currentPage = 0,
                    currentDisplayUnitId = null,
                    initialAnchor = null,
                    presentationImageOwner = fixture.owner,
                    onViewportChanged = {},
                )
            }

            pumpUntilRegionPixels(scene, fixture, expectedPageIndexes = setOf(0)) { frame ->
                sameRgb(frame.getColor(400, 300), TILE_GREEN)
            }
        } finally {
            scene.close()
            fixture.close()
        }
    }

    @Test
    fun `split and cropped Single presentation draws tiles aligned to transformed source bounds`() = runTest {
        val fixture = Fixture(this) { key ->
            if (key.purpose == PageDecodePurpose.FULL_PAGE) {
                splitCropPreviewAsset()
            } else {
                coloredAsset(TILE_GREEN, sourceWidth = 8_002, sourceHeight = 2_000)
            }
        }
        val scene = ImageComposeScene(800, 600, coroutineContext = coroutineContext) {}
        try {
            fixture.owner.beginGeneration(GENERATION)
            scene.setContent {
                ZoomablePagerViewer(
                    chapter = readerChapterSession(
                        chapterId = CHAPTER_ID,
                        generation = GENERATION,
                        pageCount = 1,
                    ),
                    currentPage = 0,
                    isRtl = false,
                    isDualPage = false,
                    autoSplitPages = true,
                    cropBorders = true,
                    splitPageIndices = setOf(0),
                    zoomState = ZoomState(scale = 2f),
                    presentationImageOwner = fixture.owner,
                    onPageChange = {},
                    onZoomChange = {},
                )
            }

            pumpUntilRegionPixels(scene, fixture, expectedPageIndexes = setOf(0)) { frame ->
                sameRgb(frame.getColor(400, 300), TILE_GREEN)
            }

            val leftHalfRegions = fixture.decodedKeys
                .asSequence()
                .filter { key -> key.purpose == PageDecodePurpose.REGION_TILE }
                .mapNotNull(ReaderPageDecodeKey::region)
                .filter { region -> region.x < 4_001 }
                .toSet()
            assertEquals(
                setOf(
                    PixelBounds(500, 500, 2_048, 1_000),
                    PixelBounds(2_548, 500, 953, 1_000),
                ),
                leftHalfRegions,
                "Split+crop tiles must use cropped original-source bounds, not preview bitmap coordinates",
            )
        } finally {
            scene.close()
            fixture.close()
        }
    }

    private fun TestScope.pumpUntilRegionPixels(
        scene: ImageComposeScene,
        fixture: Fixture,
        expectedPageIndexes: Set<Int>,
        tilePixelsPresent: (Bitmap) -> Boolean,
    ) {
        repeat(PUMP_LIMIT) {
            runCurrent()
            val frame = scene.render().toComposeImageBitmap().asSkiaBitmap()
            val accepted = try {
                fixture.regionPageIndexes().containsAll(expectedPageIndexes) && tilePixelsPresent(frame)
            } finally {
                frame.close()
            }
            if (accepted) {
                expectedPageIndexes.forEach { pageIndex ->
                    assertTrue(
                        fixture.decodedKeys.count {
                            it.pageIndex == pageIndex && it.purpose == PageDecodePurpose.FULL_PAGE
                        } == 1,
                        "Production façade must keep exactly one full decode for page $pageIndex",
                    )
                    assertTrue(
                        fixture.events.count {
                            it.type == ReaderIoEventType.OPEN_PAGE && it.pageId?.sourcePageIndex == pageIndex
                        } == 1,
                        "Preview and tiles must keep one encoded source session for page $pageIndex",
                    )
                }
                return
            }
        }
        assertTrue(
            false,
            "Mounted production presentation did not draw REGION_TILE pixels for $expectedPageIndexes; " +
                "decoded=${fixture.decodedKeys}",
        )
    }

    private fun Bitmap.containsAllRgb(expectedColors: Set<Int>): Boolean {
        val remaining = expectedColors.mapTo(mutableSetOf(), ::rgb)
        for (y in 0 until height) {
            for (x in 0 until width) {
                remaining.remove(rgb(getColor(x, y)))
                if (remaining.isEmpty()) return true
            }
        }
        return false
    }

    private class Fixture(
        scope: TestScope,
        assetFactory: (ReaderPageDecodeKey) -> DesktopReaderImageAsset = ::ordinaryAsset,
    ) : AutoCloseable {
        val decodedKeys = CopyOnWriteArrayList<ReaderPageDecodeKey>()
        val events = CopyOnWriteArrayList<ReaderIoEvent>()
        private val reporter = ReaderIoReporter(
            probe = ReaderIoProbe(events::add),
            clock = ReaderMonotonicClock { 0L },
        )
        private val contentOwner = DesktopReaderPageContentOwner(
            scope = scope,
            encodedPageReader = { ENCODED_BYTES },
            ioReporter = reporter,
        )
        private val pipeline = DesktopReaderPageImagePipeline(
            scope = scope,
            pageContentOwner = contentOwner,
            ioReporter = reporter,
            decoder = DesktopReaderPageImageDecoder { _, key ->
                decodedKeys += key
                assetFactory(key)
            },
        )
        val owner = DesktopReaderPresentationImageOwner(
            scope = scope,
            pageImagePipeline = pipeline,
            pageIoObserver = ReaderPageIoObserver(reporter),
        )

        fun regionPageIndexes(): Set<Int> = decodedKeys
            .filter { key -> key.purpose == PageDecodePurpose.REGION_TILE }
            .mapTo(linkedSetOf(), ReaderPageDecodeKey::pageIndex)

        override fun close() {
            owner.close()
            pipeline.close()
            contentOwner.close()
        }
    }

    private companion object {
        const val CHAPTER_ID = 902L
        const val GENERATION = 3L
        const val PUMP_LIMIT = 80
        const val RGB_MASK = 0x00FFFFFF
        const val PREVIEW_RED = -65_536
        const val TILE_GREEN = -16_711_936
        const val TILE_BLUE = -16_776_961
        val ENCODED_BYTES = byteArrayOf(1, 2, 3)

        fun ordinaryAsset(key: ReaderPageDecodeKey): DesktopReaderImageAsset = coloredAsset(
            color = when {
                key.purpose == PageDecodePurpose.FULL_PAGE -> PREVIEW_RED
                key.pageIndex == 2 -> TILE_BLUE
                else -> TILE_GREEN
            },
            sourceWidth = 4_001,
            sourceHeight = 4_000,
        )

        fun coloredAsset(
            color: Int,
            sourceWidth: Int,
            sourceHeight: Int,
            bitmapWidth: Int = 32,
            bitmapHeight: Int = 32,
        ): DesktopReaderImageAsset {
            val bitmap = Bitmap().apply { allocN32Pixels(bitmapWidth, bitmapHeight) }
            Canvas(bitmap).use { canvas -> canvas.clear(color) }
            return DesktopReaderImageAsset(
                bitmap = bitmap.asComposeImageBitmap(),
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                estimatedBytes = bitmapWidth.toLong() * bitmapHeight * 4L,
                sampled = true,
                disposer = bitmap::close,
            )
        }

        fun splitCropPreviewAsset(): DesktopReaderImageAsset {
            val bitmap = Bitmap().apply { allocN32Pixels(80, 20) }
            Canvas(bitmap).use { canvas ->
                canvas.clear(-1)
                val paint = Paint().apply { color = PREVIEW_RED }
                try {
                    canvas.drawRect(Rect.makeXYWH(5f, 5f, 30f, 10f), paint)
                } finally {
                    paint.close()
                }
            }
            return DesktopReaderImageAsset(
                bitmap = bitmap.asComposeImageBitmap(),
                sourceWidth = 8_002,
                sourceHeight = 2_000,
                estimatedBytes = 80L * 20L * 4L,
                sampled = true,
                disposer = bitmap::close,
            )
        }

        fun rgb(color: Int): Int = color and RGB_MASK
        fun sameRgb(actual: Int, expected: Int): Boolean = rgb(actual) == rgb(expected)
    }
}
