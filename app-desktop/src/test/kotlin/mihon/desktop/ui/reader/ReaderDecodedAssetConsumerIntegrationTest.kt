package mihon.desktop.ui.reader

import androidx.compose.ui.graphics.asSkiaBitmap
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import javax.imageio.ImageIO
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import mihon.desktop.reader.DesktopReaderPageContentOwner
import mihon.desktop.reader.DesktopReaderPageImagePipeline
import mihon.desktop.reader.DesktopReaderPresentationImageOwner
import mihon.desktop.reader.DesktopReaderPresentationImageSlotIdentity
import mihon.desktop.reader.DesktopReaderPresentationImageState
import mihon.desktop.reader.EdgePixelMatcher
import mihon.desktop.reader.ReaderColorFilter
import mihon.desktop.reader.ReaderPageIoObserver
import mihon.domain.reader.PageDecodePurpose
import mihon.domain.reader.PageSplitHalf
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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class ReaderDecodedAssetConsumerIntegrationTest {

    @Test
    fun `preview edge split crop and filter reuse one production decoded asset`() = runTest {
        val events = CopyOnWriteArrayList<ReaderIoEvent>()
        val clock = AtomicLong()
        val reporter = ReaderIoReporter(
            probe = ReaderIoProbe(events::add),
            clock = ReaderMonotonicClock(clock::incrementAndGet),
        )
        val encodedPageRef = EncodedPageRef("opaque://shared-consumer-page")
        val encoded = borderedPng()
        val contentOwner = DesktopReaderPageContentOwner(
            scope = this,
            encodedPageReader = { ref -> encoded.takeIf { ref == encodedPageRef } },
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
        val pageId = ReaderPageId(ReaderChapterId(7L), 1)
        val key = ReaderPageDecodeKey(
            contentKey = ReaderPageContentOpenRequest(pageId, generation = 1L, encodedPageRef),
            purpose = PageDecodePurpose.FULL_PAGE,
            maxWidth = 2_048,
            maxHeight = 2_048,
        )

        try {
            presentationOwner.beginGeneration(1L)
            val holder = presentationOwner.createHolder(
                identity = DesktopReaderPresentationImageSlotIdentity(pageId, generation = 1L),
                decodeKey = key,
            )
            val ready = try {
                holder.acquire()
                holder.state.first { state ->
                    state is DesktopReaderPresentationImageState.Ready ||
                        state is DesktopReaderPresentationImageState.Failed
                } as? DesktopReaderPresentationImageState.Ready
            } finally {
                holder.close()
            }
            assertNotNull(ready)
            val cachedLeases = presentationOwner.retainCachedFullPageAssets()
            val cachedLease = requireNotNull(cachedLeases[pageId.sourcePageIndex])
            cachedLeases.filterKeys { it != pageId.sourcePageIndex }.values.forEach(AutoCloseable::close)
            try {
                val cached = cachedLease.asset
                val visibleLease = requireNotNull(pipeline.acquire(key))
                val split = transformCachedPageBitmapWithSourceBounds(
                    bitmap = cached.bitmap,
                    splitHalf = PageSplitHalf.LEFT,
                    sourceWidth = cached.sourceWidth,
                    sourceHeight = cached.sourceHeight,
                ).bitmap
                val cropped = transformCachedPageBitmapWithSourceBounds(
                    bitmap = cached.bitmap,
                    cropBorders = true,
                    sourceWidth = cached.sourceWidth,
                    sourceHeight = cached.sourceHeight,
                ).bitmap
                try {
                    assertSame(cached.bitmap, visibleLease.asset.bitmap)
                    EdgePixelMatcher().findMatchedPairs(pageCount = 3) { index ->
                        cached.bitmap.takeIf { index == 1 || index == 2 }
                    }
                    assertNotNull(readerColorMatrix(ReaderColorFilter(grayscaleEnabled = true)))
                    assertEquals(cached.bitmap.width / 2, split.width)
                    assertEquals(cached.bitmap.width - 4, cropped.width)
                    assertEquals(1, events.count { it.type == ReaderIoEventType.OPEN_PAGE })
                    assertEquals(1, events.count { it.type == ReaderIoEventType.DECODE })
                } finally {
                    split.asSkiaBitmap().close()
                    if (cropped !== cached.bitmap) cropped.asSkiaBitmap().close()
                    visibleLease.close()
                }
            } finally {
                cachedLease.close()
            }
        } finally {
            presentationOwner.close()
            pipeline.close()
            contentOwner.close()
        }
    }

    private fun borderedPng(): ByteArray {
        val image = BufferedImage(20, 30, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                image.setRGB(x, y, if (x < 2 || x >= 18 || y < 2 || y >= 28) Color.WHITE.rgb else Color.RED.rgb)
            }
        }
        return ByteArrayOutputStream().also { output -> ImageIO.write(image, "png", output) }.toByteArray()
    }
}
