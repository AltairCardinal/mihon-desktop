package eu.kanade.tachiyomi.ui.reader.viewer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.runBlocking
import mihon.domain.reader.ReaderDirection
import mihon.domain.reader.ReaderDualPageContentIdentity
import mihon.domain.reader.ReaderDualPageIdentity
import mihon.domain.reader.ReaderImageOrientation
import mihon.domain.reader.ReaderPageFact
import mihon.domain.reader.SharedDualPageSession
import mihon.domain.reader.SharedSpreadMatching
import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderPageId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AndroidReaderSpreadSamplerTest {
    @Test
    fun `original encoded lease preserves mirrored alpha samples and releases regions`() = runBlocking {
        val bitmap = Bitmap.createBitmap(40, 60, Bitmap.Config.ARGB_8888)
        for (y in 0 until 60) {
            bitmap.setPixel(2, y, 0x80ff0000.toInt())
            bitmap.setPixel(37, y, 0)
        }
        val encoded = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        for (orientation in listOf(ReaderImageOrientation.NORMAL, ReaderImageOrientation.MIRROR_HORIZONTAL)) {
            var closed = 0
            var recycled = 0
            val page = ReaderPage(1, stream = {
                object : java.io.ByteArrayInputStream(encoded) {
                    override fun close() {
                        closed++
                        super.close()
                    }
                }
            })
            val session = session(page, orientation)
            val samples = AndroidReaderSpreadSampler.sample(
                page,
                requireNotNull(session.requestSpreadSample(id(1))),
                40,
                60,
                { stream ->
                    decodedRegionFactory(stream) { recycled++ }
                },
            )
            assertNotNull("Production sampler must execute the common original-coordinate plan", samples)
            val normalizedLeft = requireNotNull(samples).left.map(SharedSpreadMatching::normalizeSample)
            val normalizedRight = samples.right.map(SharedSpreadMatching::normalizeSample)
            assertEquals(
                List(50) { if (orientation == ReaderImageOrientation.NORMAL) 0xffff7f7f.toInt() else -1 },
                normalizedLeft,
            )
            assertEquals(
                List(50) { if (orientation == ReaderImageOrientation.NORMAL) -1 else 0xffff7f7f.toInt() },
                normalizedRight,
            )
            assertEquals(1, closed)
            assertEquals(1, recycled)
            assertTrue(session.acceptSpreadSample(requireNotNull(session.requestSpreadSample(id(1))), samples))
            session.close()
        }
    }

    @Test
    fun `decode failure releases original stream and decoder without a fallback full bitmap`() = runBlocking {
        var opened = 0
        var closed = 0
        var recycled = 0
        val page = ReaderPage(1, stream = {
            opened++
            object : java.io.ByteArrayInputStream(byteArrayOf()) {
                override fun close() {
                    closed++
                    super.close()
                }
            }
        })
        val session = session(page)
        val sample = runCatching {
            AndroidReaderSpreadSampler.sample(page, requireNotNull(session.requestSpreadSample(id(1))), 40, 60, {
                object : ReaderPageRegionDecoder {
                    override fun decode(region: Rect): Bitmap = error("native failure")
                    override fun recycle() {
                        recycled++
                    }
                }
            })
        }
        assertTrue(sample.isFailure)
        assertEquals(1, opened)
        assertEquals(1, closed)
        assertEquals(1, recycled)
        session.close()
    }

    private fun session(page: ReaderPage, orientation: ReaderImageOrientation = ReaderImageOrientation.NORMAL) =
        SharedDualPageSession(
            ReaderDualPageIdentity(
                1,
                1,
                List(3) { index ->
                    ReaderDualPageContentIdentity(
                        id(index),
                        if (index == 1) page.encodedContentIdentity() else "other-$index",
                        sourceOrientation = orientation,
                    )
                },
            ),
            ReaderDirection.RTL,
        ).apply {
            identity.pageIds.forEach {
                acceptFact(
                    requestFact(it),
                    ReaderPageFact.dimensions(
                        40,
                        60,
                        sourceOrientation = orientation,
                    ),
                )
            }
            configure(ReaderDirection.RTL, automaticMatching = true)
        }

    private fun id(index: Int) = ReaderPageId(ReaderChapterId(87), index)
}

/** Windows tests replace only the unavailable JNI region boundary, using real encoded PNG pixels. */
internal fun decodedRegionFactory(stream: InputStream, onRecycle: () -> Unit = {}): ReaderPageRegionDecoder {
    val source = checkNotNull(BitmapFactory.decodeStream(stream))
    return object : ReaderPageRegionDecoder {
        override fun decode(region: Rect): Bitmap = Bitmap.createBitmap(
            source,
            region.left,
            region.top,
            region.width(),
            region.height(),
        )
        override fun recycle() {
            source.recycle()
            onRecycle()
        }
    }
}
