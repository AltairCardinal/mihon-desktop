package mihon.desktop.ui.reader

import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ContextMenuRepresentation
import androidx.compose.foundation.ContextMenuState
import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.asComposeImageBitmap
import io.mockk.every
import io.mockk.mockk
import java.awt.image.BufferedImage
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.domain.DesktopNotificationService
import mihon.desktop.platform.DesktopClipboardPort
import mihon.desktop.platform.DesktopRevealPort
import mihon.desktop.platform.DesktopShareService
import mihon.desktop.reader.DesktopReaderImageAsset
import mihon.desktop.reader.DesktopReaderPageContentOwner
import mihon.desktop.reader.DesktopReaderPageImageDecoder
import mihon.desktop.reader.DesktopReaderPageImagePipeline
import mihon.desktop.reader.DesktopReaderPresentationImageHolder
import mihon.desktop.reader.DesktopReaderPresentationImageOwner
import mihon.desktop.reader.DesktopReaderPresentationImageSlotIdentity
import mihon.desktop.reader.DesktopReaderPresentationImageState
import mihon.desktop.reader.ReaderPageIoObserver
import mihon.desktop.reader.ZoomState
import mihon.desktop.ui.reader.presentation.DisplaySlot
import mihon.desktop.ui.reader.presentation.DisplaySlotId
import mihon.desktop.ui.reader.presentation.DisplayUnit
import mihon.desktop.ui.reader.presentation.DisplayUnitId
import mihon.desktop.ui.reader.presentation.ReaderPresentationMode
import mihon.desktop.ui.reader.presentation.ReaderPresentationSnapshot
import mihon.domain.reader.PageDecodePurpose
import mihon.domain.reader.PageSplitHalf
import mihon.domain.reader.PixelBounds
import mihon.domain.reader.ReaderPageDecodeKey
import mihon.domain.reader.content.ReaderPageContentOpenRequest
import mihon.domain.reader.observability.ReaderIoProbe
import mihon.domain.reader.observability.ReaderIoReporter
import mihon.domain.reader.observability.ReaderMonotonicClock
import mihon.domain.reader.session.EncodedPageRef
import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderChapterLoadState
import mihon.domain.reader.session.ReaderChapterSession
import mihon.domain.reader.session.ReaderPageId
import mihon.domain.reader.session.ReaderPageLoadState
import mihon.domain.reader.session.ReaderPageSession
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@OptIn(ExperimentalComposeUiApi::class)
class ReaderPresentationContextMenuProductionTest {

    @Test
    fun `stable context menu binding retains the holder and carries its exact virtual region`() = runTest {
        val fixture = ImageOwnerFixture(this)
        val bounds = PixelBounds(x = 0, y = 2, width = 5, height = 2)
        try {
            val presentationImage = fixture.readyPresentationImage(
                splitHalf = PageSplitHalf.LEFT,
                sourceBounds = bounds,
            )

            val binding = readerPageContextMenuBinding(presentationImage)
            val actionLease = requireNotNull(binding.imageLeaseProvider())
            try {
                assertEquals(PageSplitHalf.LEFT, binding.splitHalf)
                assertEquals(bounds, binding.sourceBounds)
                val visibleRegion = requireNotNull(
                    loadPageContextMenuImage(
                        asset = actionLease.asset,
                        splitHalf = binding.splitHalf,
                        sourceBounds = binding.sourceBounds,
                    ),
                )
                assertExpectedBottomRegion(visibleRegion)
            } finally {
                actionLease.close()
            }
            assertEquals(1, fixture.decodeCalls.get())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `mounted production single renderer copies the exact source bounds through its holder menu`() = runBlocking {
        val fixture = ImageOwnerFixture(this)
        val copiedImages = mutableListOf<BufferedImage>()
        val desktopShareService = DesktopShareService(
            clipboardPort = object : DesktopClipboardPort {
                override fun copyText(text: String) = Unit
                override fun copyImage(image: BufferedImage) {
                    synchronized(copiedImages) { copiedImages += image }
                }
            },
            isHeadless = { false },
            revealPort = DesktopRevealPort {},
        )
        val dependencies = mockk<DesktopUiDependencies> {
            every { shareService } returns desktopShareService
            every { notificationService } returns DesktopNotificationService()
        }
        var capturedItems: List<ContextMenuItem>? = null
        val representation = object : ContextMenuRepresentation {
            @Composable
            override fun Representation(state: ContextMenuState, items: () -> List<ContextMenuItem>) {
                capturedItems = items()
            }
        }
        val scene = ImageComposeScene(640, 480, coroutineContext = coroutineContext) {}

        try {
            scene.setContent {
                CompositionLocalProvider(
                    LocalDesktopUiDependencies provides dependencies,
                    LocalContextMenuRepresentation provides representation,
                ) {
                    MaterialTheme {
                        SinglePagePagerViewer(
                            presentation = fixture.singleVirtualRegionPresentation(),
                            currentPageId = PAGE_ID,
                            currentDisplayUnitId = null,
                            isRtl = false,
                            zoomState = ZoomState(),
                            contextMenuScope = this@runBlocking,
                            mangaTitle = "Manga",
                            chapterTitle = "Chapter",
                            presentationImageOwner = fixture.owner,
                            navigationMode = NavigationMode.RightAndLeft,
                            onVisiblePagesChanged = {},
                            onZoomChange = {},
                            onRetryPage = {},
                            generation = GENERATION,
                        )
                    }
                }
            }

            var renderPumps = 0
            while (capturedItems == null && renderPumps < 100) {
                scene.render()
                yield()
                renderPumps += 1
            }
            val items = requireNotNull(capturedItems) {
                "The mounted production renderer did not expose its page menu"
            }
            assertEquals(3, items.size)

            items[1].onClick()
            withTimeout(5_000) {
                while (synchronized(copiedImages) { copiedImages.isEmpty() }) delay(10)
            }

            val copied = synchronized(copiedImages) { copiedImages.single() }
            assertExpectedBottomRegion(copied)
            assertEquals(1, fixture.decodeCalls.get(), "The menu action must retain the visible holder without a second decode")
        } finally {
            scene.close()
            fixture.close()
        }
        assertEquals(1, fixture.disposeCalls.get())
    }

    private fun assertExpectedBottomRegion(image: BufferedImage) {
        assertEquals(5, image.width)
        assertEquals(2, image.height)
        assertEquals(pixelColor(0, 2), image.getRGB(0, 0))
        assertEquals(pixelColor(4, 3), image.getRGB(4, 1))
    }

    private class ImageOwnerFixture(
        scope: CoroutineScope,
    ) : AutoCloseable {
        private val reporter = ReaderIoReporter(
            probe = ReaderIoProbe.None,
            clock = ReaderMonotonicClock { 0L },
        )
        private val contentOwner = DesktopReaderPageContentOwner(
            scope = scope,
            encodedPageReader = { ENCODED_BYTES },
            ioReporter = reporter,
        )
        val decodeCalls = AtomicInteger()
        val disposeCalls = AtomicInteger()
        private val pipeline = DesktopReaderPageImagePipeline(
            scope = scope,
            pageContentOwner = contentOwner,
            ioReporter = reporter,
            decoder = DesktopReaderPageImageDecoder { _, _ ->
                decodeCalls.incrementAndGet()
                coloredAsset { disposeCalls.incrementAndGet() }
            },
        )
        val owner = DesktopReaderPresentationImageOwner(
            scope = scope,
            pageImagePipeline = pipeline,
            pageIoObserver = ReaderPageIoObserver(reporter) { _, _ -> },
        )

        suspend fun readyPresentationImage(
            splitHalf: PageSplitHalf,
            sourceBounds: PixelBounds,
        ): ReaderPresentationImage {
            owner.beginGeneration(GENERATION)
            val holder = holder(splitHalf, sourceBounds)
            holder.acquire()
            val ready = withTimeout(5_000) {
                holder.state.filterIsInstance<DesktopReaderPresentationImageState.Ready>().first()
            }
            return ReaderPresentationImage(holder, ready)
        }

        fun singleVirtualRegionPresentation(): ReaderPresentationSnapshot {
            val page = readyPage()
            val slotId = DisplaySlotId(PAGE_ID, PageSplitHalf.LEFT)
            val slot = DisplaySlot(
                id = slotId,
                page = page,
                splitHalf = PageSplitHalf.LEFT,
                sourceBounds = SOURCE_BOUNDS,
            )
            return ReaderPresentationSnapshot(
                mode = ReaderPresentationMode.SINGLE_PAGED,
                displayUnits = listOf(
                    DisplayUnit(
                        id = DisplayUnitId(ReaderPresentationMode.SINGLE_PAGED, listOf(slotId)),
                        slots = listOf(slot),
                    ),
                ),
            )
        }

        private fun holder(
            splitHalf: PageSplitHalf,
            sourceBounds: PixelBounds,
        ): DesktopReaderPresentationImageHolder = owner.createHolder(
            identity = DesktopReaderPresentationImageSlotIdentity(
                pageId = PAGE_ID,
                generation = GENERATION,
                splitHalf = splitHalf,
                sourceBounds = sourceBounds,
            ),
            decodeKey = decodeKey(),
        )

        override fun close() {
            owner.close()
            pipeline.close()
            contentOwner.close()
        }
    }

    companion object {
        private const val GENERATION = 1L
        private val CHAPTER_ID = ReaderChapterId(401L)
        private val PAGE_ID = ReaderPageId(CHAPTER_ID, 0)
        private val ENCODED_REF = EncodedPageRef("memory://context-menu-page")
        private val ENCODED_BYTES = byteArrayOf(1, 2, 3)
        private val SOURCE_BOUNDS = PixelBounds(x = 0, y = 2, width = 5, height = 2)

        private fun readyPage() = ReaderPageSession(
            id = PAGE_ID,
            url = "/page/0",
            imageUrl = null,
            encodedPageRef = ENCODED_REF,
            loadState = ReaderPageLoadState.Ready,
        )

        private fun decodeKey() = ReaderPageDecodeKey(
            contentKey = ReaderPageContentOpenRequest(
                pageId = PAGE_ID,
                generation = GENERATION,
                encodedPageRef = ENCODED_REF,
            ),
            purpose = PageDecodePurpose.FULL_PAGE,
            maxWidth = 2_048,
            maxHeight = 2_048,
        )

        private fun coloredAsset(onDispose: () -> Unit): DesktopReaderImageAsset {
            val bitmap = Bitmap().apply { allocN32Pixels(5, 4) }
            val canvas = Canvas(bitmap)
            val paint = Paint()
            try {
                repeat(4) { y ->
                    repeat(5) { x ->
                        paint.color = pixelColor(x, y)
                        canvas.drawRect(Rect.makeXYWH(x.toFloat(), y.toFloat(), 1f, 1f), paint)
                    }
                }
            } finally {
                paint.close()
            }
            return DesktopReaderImageAsset(
                bitmap = bitmap.asComposeImageBitmap(),
                sourceWidth = 5,
                sourceHeight = 4,
                estimatedBytes = 5L * 4L * 4L,
                sampled = false,
                disposer = {
                    bitmap.close()
                    onDispose()
                },
            )
        }

        private fun pixelColor(x: Int, y: Int): Int =
            0xFF000000.toInt() or (x shl 16) or (y shl 8) or ((x + y) and 0xFF)
    }
}
