package mihon.desktop.reader

import kotlinx.coroutines.test.runTest
import mihon.domain.reader.ReaderImageOrientation
import mihon.domain.reader.ReaderOriginalPageSamples
import mihon.domain.reader.ReaderPageSideHint
import mihon.domain.reader.ReaderPageSideHints
import mihon.domain.reader.ReaderSpreadEdgeSamples
import mihon.domain.reader.SharedSpreadMatching
import mihon.domain.reader.content.ReaderPageContentOpenRequest
import mihon.domain.reader.observability.ReaderIoReporter
import mihon.domain.reader.observability.ReaderMonotonicClock
import mihon.domain.reader.session.EncodedPageRef
import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderPageId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import javax.imageio.ImageIO

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DesktopReaderSpreadSamplerTest {
    @Test
    fun `actual ICC JPEG region leases expose fixed sRGB rather than untagged linear channels`() = runTest {
        val profiled = requireNotNull(javaClass.classLoader?.getResourceAsStream("reader/linear-rgb-profile.jpg"))
            .use { it.readBytes() }
        val control = requireNotNull(javaClass.classLoader?.getResourceAsStream("reader/linear-rgb-control.jpg"))
            .use { it.readBytes() }
        val indices = listOf(0, 10, 25, 49)
        // Frozen by the synthetic fixture's independent CS_LINEAR_RGB.toRGB golden; see provenance.
        val srgb = listOf(-6780485, -5535301, -3896900, -1799748)
        val untagged = listOf(-11517825, -9944961, -7585664, -3850111)
        fun originalSide(encoded: ByteArray): ReaderPageSideHint {
            val image = ImageIO.read(encoded.inputStream())
            return ReaderPageSideHints.classify(image.width, image.height, image::getRGB)
        }
        sample(profiled, 400, 600, ReaderImageOrientation.NORMAL, originalSide(profiled)) { edges ->
            assertEquals(srgb, indices.map { edges.right[it] })
        }
        sample(control, 400, 600, ReaderImageOrientation.NORMAL, originalSide(control)) { edges ->
            assertEquals(untagged, indices.map { edges.right[it] })
        }
    }

    @Test
    fun `large legacy original edges remain available when combined side sampling is resource limited`() = runTest {
        val encoded = largePng(8192, 12000)
        sample(encoded, 8192, 12000, ReaderImageOrientation.NORMAL, legacy = true) { edges ->
            assertEquals(List(50) { 0xff204080.toInt() }, edges.left)
            assertEquals(List(50) { 0xff204080.toInt() }, edges.right)
        }
        sample(encoded, 8192, 12000, ReaderImageOrientation.NORMAL, expectLimited = true) {
            error("Resource-limited sampling must not manufacture computed side evidence")
        }
    }

    @Test
    fun `thin original margin produces page side evidence through the production region leases`() = runTest {
        val image = BufferedImage(400, 600, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                image.setRGB(x, y, if (x <= 14) -1 else 0xff202020.toInt())
            }
        }
        val encoded = ByteArrayOutputStream().also { assertTrue(ImageIO.write(image, "png", it)) }.toByteArray()
        sample(encoded, 400, 600, ReaderImageOrientation.NORMAL, ReaderPageSideHint.RIGHT) { edges ->
            assertEquals(List(50) { -1 }, edges.left)
            assertEquals(List(50) { 0xff202020.toInt() }, edges.right)
        }
        sample(encoded, 400, 600, ReaderImageOrientation.MIRROR_HORIZONTAL, ReaderPageSideHint.LEFT) { edges ->
            assertEquals(List(50) { 0xff202020.toInt() }, edges.left)
            assertEquals(List(50) { -1 }, edges.right)
        }
    }

    @Test
    fun `original PNG region samples preserve straight alpha and normalized mirrored sides`() = runTest {
        val image = BufferedImage(40, 60, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until 60) {
            image.setRGB(2, y, 0x80ff0000.toInt())
            image.setRGB(37, y, 0)
        }
        val encoded = ByteArrayOutputStream().also { assertTrue(ImageIO.write(image, "png", it)) }.toByteArray()
        sample(encoded, 40, 60, ReaderImageOrientation.NORMAL) { edges ->
            assertEquals(List(50) { 0x80ff0000.toInt() }, edges.left)
            assertEquals(List(50) { 0xffff7f7f.toInt() }, edges.left.map(SharedSpreadMatching::normalizeSample))
            assertEquals(List(50) { 0xffffffff.toInt() }, edges.right.map(SharedSpreadMatching::normalizeSample))
        }
        sample(encoded, 40, 60, ReaderImageOrientation.MIRROR_HORIZONTAL) { edges ->
            assertEquals(List(50) { 0xffffffff.toInt() }, edges.left.map(SharedSpreadMatching::normalizeSample))
            assertEquals(List(50) { 0xffff7f7f.toInt() }, edges.right.map(SharedSpreadMatching::normalizeSample))
        }
    }

    @Test
    fun `actual EXIF JPEG sampling uses raw region coordinates and normalizes only once`() = runTest {
        val raw = requireNotNull(javaClass.classLoader?.getResourceAsStream("reader/exif-corners-raw.jpg"))
            .use { it.readBytes() }
        val cornerColors = mapOf(
            ReaderImageOrientation.CLOCKWISE to listOf(0xff0000ff, 0xffffff00, 0xffff0000, 0xff00ff00),
            ReaderImageOrientation.TRANSPOSE to listOf(0xffff0000, 0xff00ff00, 0xff0000ff, 0xffffff00),
            ReaderImageOrientation.COUNTER_CLOCKWISE to listOf(0xff00ff00, 0xffff0000, 0xffffff00, 0xff0000ff),
            ReaderImageOrientation.TRANSVERSE to listOf(0xffffff00, 0xff0000ff, 0xff00ff00, 0xffff0000),
        )
        for ((orientation, expected) in cornerColors) {
            val exif = "45786966000049492a0008000000010012010300010000000600000000000000"
                .chunked(2).map { it.toInt(16).toByte() }.toByteArray().also { it[24] = orientation.exifValue.toByte() }
            val encoded = raw.copyOfRange(0, 2) + byteArrayOf(-1, -31, 0, (exif.size + 2).toByte()) + exif +
                raw.copyOfRange(2, raw.size)
            assertEquals(orientation, mihon.domain.reader.readImageOrientation(encoded))
            assertEquals(40 to 60, SkiaImageDecoder.peekSize(encoded))
            sample(encoded, 60, 40, orientation) { edges ->
                val actual = listOf(edges.left.first(), edges.left.last(), edges.right.first(), edges.right.last())
                actual.zip(expected).forEach { (pixel, color) ->
                    for (shift in listOf(0, 8, 16)) {
                        assertTrue(
                            kotlin.math.abs((pixel ushr shift and 255) - (color ushr shift and 255).toInt()) < 30,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `large original PNG is sampled with region leases and no retained decoded page`() = runTest {
        val encoded = largePng(4096, 4097)
        assertEquals(4096 to 4097, SkiaImageDecoder.peekSize(encoded))
        sample(encoded, 4096, 4097, ReaderImageOrientation.NORMAL) { edges ->
            assertEquals(List(50) { 0xff204080.toInt() }, edges.left)
            assertEquals(List(50) { 0xff204080.toInt() }, edges.right)
        }
    }

    private suspend fun sample(
        encoded: ByteArray,
        rawWidth: Int,
        rawHeight: Int,
        orientation: ReaderImageOrientation,
        expectedSide: ReaderPageSideHint = ReaderPageSideHint.UNKNOWN,
        legacy: Boolean = false,
        expectLimited: Boolean = false,
        verify: (ReaderSpreadEdgeSamples) -> Unit,
    ) = kotlinx.coroutines.coroutineScope {
        var opens = 0
        val reporter = ReaderIoReporter(clock = ReaderMonotonicClock(System::nanoTime))
        val contentOwner = DesktopReaderPageContentOwner(this, {
            opens++
            encoded
        }, reporter)
        val pipeline = DesktopReaderPageImagePipeline(this, contentOwner, reporter)
        val request = ReaderPageContentOpenRequest(
            ReaderPageId(ReaderChapterId(87), 1),
            3,
            EncodedPageRef("cached://raw"),
        )
        try {
            pipeline.openRegionSession(request).use { session ->
                val result = if (legacy) {
                    DesktopReaderSpreadSampler.sample(pipeline, session, rawWidth, rawHeight, orientation)
                        ?.let { ReaderOriginalPageSamples(ReaderPageSideHint.UNKNOWN, it) }
                } else {
                    DesktopReaderSpreadSampler.sampleOriginal(
                        pipeline,
                        session,
                        rawWidth,
                        rawHeight,
                        orientation,
                    )
                }
                if (expectLimited) {
                    assertNull(result)
                    assertEquals(0, opens, "The limit must be determined before opening encoded content")
                    return@use
                }
                requireNotNull(result)
                assertEquals(expectedSide, result.sideHint)
                val edges = requireNotNull(result.edges)
                assertEquals(50, edges.left.size)
                assertEquals(50, edges.right.size)
                verify(edges)
                assertEquals(1, opens, "Both original edges share the same encoded lease")
                assertEquals(0L, pipeline.memoryAuthority.snapshot().residentBytes)
                assertEquals(0, pipeline.snapshot().inFlightCount)
            }
            assertTrue(contentOwner.snapshot().activeLeaseCounts.isEmpty())
        } finally {
            pipeline.close()
            contentOwner.close()
        }
    }

    /** Legal streaming fixture; building a large original fixture never allocates its full raster. */
    private fun largePng(width: Int, height: Int): ByteArray {
        val compressed = ByteArrayOutputStream()
        DeflaterOutputStream(compressed).use { deflater ->
            val row = ByteArray(width * 4 + 1)
            repeat(width) { x ->
                row[1 + x * 4] = 0x20
                row[2 + x * 4] = 0x40
                row[3 + x * 4] = 0x80.toByte()
                row[4 + x * 4] = 0xff.toByte()
            }
            repeat(height) { deflater.write(row) }
        }
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.write(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
            fun chunk(type: String, content: ByteArray) {
                val name = type.toByteArray(Charsets.US_ASCII)
                output.writeInt(content.size)
                output.write(name)
                output.write(content)
                val crc = CRC32().also {
                    it.update(name)
                    it.update(content)
                }
                output.writeInt(crc.value.toInt())
            }
            val header = ByteArrayOutputStream().also {
                DataOutputStream(it).use { data ->
                    data.writeInt(width)
                    data.writeInt(height)
                    data.write(byteArrayOf(8, 6, 0, 0, 0))
                }
            }.toByteArray()
            chunk("IHDR", header)
            chunk("IDAT", compressed.toByteArray())
            chunk("IEND", byteArrayOf())
        }
        return bytes.toByteArray()
    }
}
