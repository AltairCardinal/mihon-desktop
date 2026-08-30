package mihon.desktop.reader

import androidx.compose.ui.graphics.asComposeImageBitmap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopReaderRegionCacheRevisionTest {

    @Test
    fun `region commit hit and eviction do not invalidate ordinary decoded cache observers`() = runTest {
        val decodedKeys = CopyOnWriteArrayList<ReaderPageDecodeKey>()
        val reporter = ReaderIoReporter(
            probe = ReaderIoProbe.None,
            clock = ReaderMonotonicClock { 0L },
        )
        val contentOwner = DesktopReaderPageContentOwner(
            scope = this,
            encodedPageReader = { ENCODED_BYTES },
            ioReporter = reporter,
        )
        val pipeline = DesktopReaderPageImagePipeline(
            scope = this,
            pageContentOwner = contentOwner,
            ioReporter = reporter,
            decoder = DesktopReaderPageImageDecoder { _, key ->
                decodedKeys += key
                asset(
                    estimatedBytes = if (key.purpose == PageDecodePurpose.REGION_TILE) 16L * MIB else 16L,
                )
            },
        )
        var session: DesktopReaderRegionContentSession? = null
        try {
            pipeline.beginGeneration(GENERATION)
            val baselineRevision = pipeline.cacheRevision.value
            session = pipeline.openRegionSession(CONTENT_KEY)

            val firstTile = tileKey(index = 0)
            session.acquireAndCommit(pipeline, firstTile)
            assertEquals(
                baselineRevision,
                pipeline.cacheRevision.value,
                "Tile commit is not an ordinary-cache mutation",
            )

            checkNotNull(session.acquireTile(firstTile)).close()
            assertEquals(1, decodedKeys.count { key -> key == firstTile }, "Tile cache hit must not decode again")
            assertEquals(baselineRevision, pipeline.cacheRevision.value, "Tile cache hit must stay revision-neutral")

            (1..8).forEach { index ->
                session.acquireAndCommit(pipeline, tileKey(index))
            }
            val tileCache = pipeline.snapshot().tileCache
            assertEquals(8, tileCache.entryCount, "The ninth tile must evict the first at the unified cache entry limit")
            assertFalse(firstTile in tileCache.keys)
            assertEquals(baselineRevision, pipeline.cacheRevision.value, "Tile eviction must stay revision-neutral")

            checkNotNull(pipeline.acquire(fullPageKey())).close()
            assertEquals(
                baselineRevision + 1L,
                pipeline.cacheRevision.value,
                "A normal FULL_PAGE cache commit must still invalidate ordinary-cache observers",
            )
        } finally {
            session?.close()
            pipeline.close()
            contentOwner.close()
        }
    }

    private fun tileKey(index: Int) = ReaderPageDecodeKey(
        contentKey = CONTENT_KEY,
        purpose = PageDecodePurpose.REGION_TILE,
        maxWidth = 2_048,
        maxHeight = 2_048,
        region = PixelBounds(index * 2_048, 0, 2_048, 2_048),
    )

    private fun fullPageKey() = ReaderPageDecodeKey(
        contentKey = CONTENT_KEY,
        purpose = PageDecodePurpose.FULL_PAGE,
        maxWidth = 2_048,
        maxHeight = 2_048,
    )

    private suspend fun DesktopReaderRegionContentSession.acquireAndCommit(
        pipeline: DesktopReaderPageImagePipeline,
        key: ReaderPageDecodeKey,
    ) {
        val lease = checkNotNull(acquireTile(key))
        try {
            assertTrue(pipeline.commitRegionTile(key, lease))
        } finally {
            lease.close()
        }
    }

    private fun asset(estimatedBytes: Long): DesktopReaderImageAsset {
        val bitmap = Bitmap().apply { allocN32Pixels(2, 2) }
        return DesktopReaderImageAsset(
            bitmap = bitmap.asComposeImageBitmap(),
            sourceWidth = 12_000,
            sourceHeight = 4_000,
            estimatedBytes = estimatedBytes,
            sampled = true,
            disposer = bitmap::close,
        )
    }

    private companion object {
        const val GENERATION = 1L
        const val MIB = 1024L * 1024L
        val PAGE_ID = ReaderPageId(ReaderChapterId(903L), 0)
        val ENCODED_REF = EncodedPageRef("test:region-cache-revision")
        val ENCODED_BYTES = byteArrayOf(1, 2, 3)
        val CONTENT_KEY = ReaderPageContentOpenRequest(PAGE_ID, GENERATION, ENCODED_REF)
    }
}
