package mihon.desktop.reader

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.test.runTest
import mihon.domain.reader.PageDecodePurpose
import mihon.domain.reader.ReaderPageDecodeKey
import mihon.domain.reader.content.ReaderPageContentOpenRequest
import mihon.domain.reader.observability.ReaderIoProbe
import mihon.domain.reader.observability.ReaderIoReporter
import mihon.domain.reader.observability.ReaderMonotonicClock
import mihon.domain.reader.session.EncodedPageRef
import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderPageId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.imageio.ImageIO

class PagePreloaderTest {

    @Test
    fun `adapter exposes no bitmap before the visible page is requested`() = runTest {
        val preloader = createTestPagePreloader(encodedPageReader = { null })

        assertNull(preloader.get(0))
        assertTrue(preloader.cacheSnapshot().keys.isEmpty())

        preloader.close()
    }

    @Test
    fun `adapter requests only the current visible page and not a private decode window`() = runTest {
        val opened = mutableListOf<EncodedPageRef>()
        val bytes = pngBytes(4, 4)
        val refs = (0..4).map { EncodedPageRef("page-$it") }
        val preloader = createTestPagePreloader(
            encodedPageReader = { ref ->
                opened += ref
                bytes
            },
            windowSize = 2,
        )

        preloader.preloadEncoded(currentPage = 2, encodedPageRefs = refs)

        assertEquals(listOf(refs[2]), opened)
        assertNotNull(preloader.get(2))
        assertNull(preloader.get(1))
        assertNull(preloader.get(3))
        preloader.close()
    }

    @Test
    fun `same stable page and generation reuse the pipeline asset without another open`() = runTest {
        var openCalls = 0
        val ref = EncodedPageRef("same-page")
        val pageId = ReaderPageId(ReaderChapterId(7L), 0)
        val preloader = createTestPagePreloader(
            encodedPageReader = {
                openCalls++
                pngBytes(4, 4)
            },
            windowSize = 0,
        )

        preloader.preloadEncoded(0, listOf(ref), listOf(pageId), sessionGeneration = 3L)
        val first = requireNotNull(preloader.get(0))
        preloader.preloadEncoded(0, listOf(ref), listOf(pageId), sessionGeneration = 3L)
        val second = requireNotNull(preloader.get(0))

        assertEquals(1, openCalls)
        assertSame(first, second)
        assertEquals(setOf(0), preloader.cacheSnapshot().keys)
        preloader.close()
    }

    @Test
    fun `decoded dimensions and byte accounting stay within configured bounds`() = runTest {
        val preloader = createTestPagePreloader(
            encodedPageReader = { pngBytes(300, 200) },
            windowSize = 0,
            maxDecodedWidth = 200,
            maxDecodedHeight = 200,
            maxCacheBytes = 200L * 200L * 4L,
        )

        preloader.preloadEncoded(0, listOf(EncodedPageRef("bounded")))

        val bitmap = requireNotNull(preloader.get(0))
        assertTrue(bitmap.width <= 200)
        assertTrue(bitmap.height <= 200)
        assertTrue(preloader.cacheSnapshot().usedBytes <= preloader.cacheSnapshot().maxBytes)
        preloader.close()
    }

    @Test
    fun `entry budget evicts the previous page without invalidating the new visible asset`() = runTest {
        val refs = listOf(EncodedPageRef("first"), EncodedPageRef("second"))
        val pageIds = listOf(
            ReaderPageId(ReaderChapterId(8L), 0),
            ReaderPageId(ReaderChapterId(8L), 1),
        )
        val preloader = createTestPagePreloader(
            encodedPageReader = { pngBytes(4, 4) },
            windowSize = 0,
        )

        preloader.preloadEncoded(0, refs, pageIds, sessionGeneration = 5L)
        assertNotNull(preloader.get(0))
        preloader.preloadEncoded(1, refs, pageIds, sessionGeneration = 5L)

        assertNull(preloader.get(0))
        assertNotNull(preloader.get(1))
        assertEquals(setOf(1), preloader.cacheSnapshot().keys)
        preloader.close()
    }

    @Test
    fun `visible compatibility pin survives cache eviction until the visible page changes`() = runTest {
        val refs = listOf(EncodedPageRef("first"), EncodedPageRef("second"))
        val pageIds = listOf(
            ReaderPageId(ReaderChapterId(9L), 0),
            ReaderPageId(ReaderChapterId(9L), 1),
        )
        val encoded = pngBytes(4, 4)
        val clock = AtomicLong()
        val reporter = ReaderIoReporter(
            probe = ReaderIoProbe.None,
            clock = ReaderMonotonicClock(clock::incrementAndGet),
        )
        val contentOwner = DesktopReaderPageContentOwner(this, { encoded }, reporter)
        val pipeline = DesktopReaderPageImagePipeline(
            scope = this,
            pageContentOwner = contentOwner,
            ioReporter = reporter,
            maxEntries = 1,
        )
        val preloader = PagePreloader(pipeline, windowSize = 0)

        try {
            preloader.preloadEncoded(0, refs, pageIds, sessionGeneration = 6L)
            val visibleBitmap = requireNotNull(preloader.get(0))
            val secondLease = requireNotNull(
                pipeline.acquire(
                    ReaderPageDecodeKey(
                        contentKey = ReaderPageContentOpenRequest(pageIds[1], 6L, refs[1]),
                        purpose = PageDecodePurpose.FULL_PAGE,
                        maxWidth = 2_048,
                        maxHeight = 2_048,
                    ),
                ),
            )
            secondLease.close()

            assertEquals(listOf(1), pipeline.snapshot().cache.keys.map(ReaderPageDecodeKey::pageIndex))
            assertSame(visibleBitmap, preloader.get(0))

            preloader.preloadEncoded(1, refs, pageIds, sessionGeneration = 6L)
            assertNull(preloader.get(0))
            assertNotNull(preloader.get(1))
        } finally {
            preloader.close()
            pipeline.close()
            contentOwner.close()
        }
    }

    @Test
    fun `missing content and malformed bytes do not populate the decoded cache`() = runTest {
        val missing = createTestPagePreloader(encodedPageReader = { null }, windowSize = 0)
        val malformed = createTestPagePreloader(
            encodedPageReader = { "broken".toByteArray() },
            windowSize = 0,
        )

        missing.preloadEncoded(0, listOf(EncodedPageRef("missing")))
        malformed.preloadEncoded(0, listOf(EncodedPageRef("malformed")))

        assertNull(missing.get(0))
        assertNull(malformed.get(0))
        assertTrue(missing.cacheSnapshot().keys.isEmpty())
        assertTrue(malformed.cacheSnapshot().keys.isEmpty())
        missing.close()
        malformed.close()
    }

    @Test
    fun `clear releases pinned assets and empties the unique pipeline cache`() = runTest {
        val preloader = createTestPagePreloader(
            encodedPageReader = { pngBytes(4, 4) },
            windowSize = 0,
        )
        preloader.preloadEncoded(0, listOf(EncodedPageRef("clear-me")))
        assertNotNull(preloader.get(0))

        preloader.clear()

        assertNull(preloader.get(0))
        assertEquals(0, preloader.cacheSize())
        preloader.close()
    }

    private fun pngBytes(width: Int, height: Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        image.createGraphics().run {
            color = Color(120, 40, 20)
            fillRect(0, 0, width, height)
            dispose()
        }
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }
}
