package mihon.desktop.reader

import androidx.compose.ui.graphics.asSkiaBitmap
import kotlinx.coroutines.test.runTest
import mihon.domain.reader.PageDecodePurpose
import mihon.domain.reader.PageRotation
import mihon.domain.reader.ReaderImageOrientation
import mihon.domain.reader.ReaderPageDecodeKey
import mihon.domain.reader.ReaderPageSideHint
import mihon.domain.reader.content.ReaderPageContentOpenRequest
import mihon.domain.reader.session.EncodedPageRef
import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderPageId
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class DesktopOriginalDecodeEvidenceTest {
    @Test
    fun `normal Codec measures real raster cost and accounts its simultaneous allocations`() {
        val inputRoot = System.getenv("MIHON_PAIRING_R3_INPUTS")?.let { java.io.File(it) }
        val sources = if (inputRoot != null) {
            requireNotNull(inputRoot.listFiles { file -> file.extension == "webp" }).sortedBy { it.name }.map {
                it.name to
                    it.readBytes()
            }
                .also { assertEquals(23, it.size, "The frozen research corpus must be complete") }
        } else {
            val image = BufferedImage(980, 1403, BufferedImage.TYPE_INT_RGB)
            for (y in 0 until image.height) for (x in 0 until image.width) image.setRGB(x, y, 0xff204080.toInt())
            listOf(
                "synthetic-980x1403.png" to
                    ByteArrayOutputStream().also { assertTrue(ImageIO.write(image, "png", it)) }.toByteArray(),
            )
        }
        val authority = DesktopReaderImageMemoryAuthority()
        try {
            for ((name, encoded) in sources) {
                val baseline = mutableListOf<Long>()
                val candidate = mutableListOf<Long>()
                var registrations = 0
                var peak = 0L
                fun old(): Long {
                    val start = System.nanoTime()
                    val bitmap = SkiaImageDecoder.decodeDownsampled(encoded, 2048, 2048)
                    try {
                        bitmap.asSkiaBitmap().getColor(0, 0)
                    } finally {
                        bitmap.asSkiaBitmap().close()
                    }
                    return System.nanoTime() - start
                }
                fun next(): Long {
                    val start = System.nanoTime()
                    val (bitmap, evidence) = SkiaImageDecoder.decodeDownsampledWithOriginal(
                        encoded,
                        2048,
                        2048,
                    ) { bytes ->
                        registrations++
                        val lease = authority.register(
                            DesktopReaderImageMemoryKind.DERIVED,
                            bytes,
                            DesktopReaderImageMemoryRetention.IN_FLIGHT,
                        ) {
                        }
                        peak = maxOf(peak, authority.snapshot().residentBytes)
                        assertEquals(0L, authority.snapshot().overBudgetBytes)
                        lease
                    }
                    try {
                        assertEquals(PageRotation.entries.toSet(), evidence.keys)
                        val edges = requireNotNull(evidence.getValue(PageRotation.NONE).edges)
                        assertEquals(50, edges.left.size)
                        assertEquals(50, edges.right.size)
                        bitmap.asSkiaBitmap().getColor(0, 0)
                    } finally {
                        bitmap.asSkiaBitmap().close()
                    }
                    assertEquals(0L, authority.snapshot().residentBytes)
                    return System.nanoTime() - start
                }
                repeat(2) {
                    old()
                    next()
                }
                repeat(20) { index ->
                    if (index % 2 ==
                        0
                    ) {
                        baseline += old()
                        candidate += next()
                    } else {
                        candidate += next()
                        baseline += old()
                    }
                }
                assertEquals(22, registrations)
                println(
                    "R3_CODEC_COST name=$name clock=nanoTime baselineNs=$baseline candidateNs=$candidate accountedRasterPeakBytes=$peak",
                )
            }
        } finally {
            authority.close()
        }
    }

    @Test
    fun `normal sampled WebP decode retains fixed original evidence`() = runTest {
        val raw = BufferedImage(400, 600, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 600) for (x in 0 until 400) raw.setRGB(x, y, if (x < 50) -1 else 0xff000000.toInt())
        val png = ByteArrayOutputStream().also { ImageIO.write(raw, "png", it) }.toByteArray()
        val webp = Image.makeFromEncoded(png).use { image ->
            requireNotNull(image.encodeToData(EncodedImageFormat.WEBP, 100)).use { it.bytes }
        }
        decode(webp) { asset ->
            assertTrue(asset.bitmap.width < 400 && asset.bitmap.height < 600)
            val evidence = requireNotNull(asset.originalSamples[PageRotation.NONE])
            assertEquals(ReaderPageSideHint.RIGHT, evidence.sideHint)
            assertEquals(List(50) { -1 }, evidence.edges!!.left)
            assertEquals(List(50) { 0xff000000.toInt() }, evidence.edges!!.right)
            assertEquals(ReaderPageSideHint.LEFT, asset.originalSamples[PageRotation.HALF_TURN]!!.sideHint)
            val rendered = asset.bitmap.asSkiaBitmap()
            assertEquals(-1, rendered.getColor(0, rendered.height / 2))
            assertEquals(0xff000000.toInt(), rendered.getColor(rendered.width - 1, rendered.height / 2))
        }
    }

    @Test
    fun `normal decode original samples preserve independent straight RGBA golden`() = runTest {
        val encoded = resource("reader/mixed-alpha-original.png")
        decode(encoded) { asset ->
            val evidence = requireNotNull(asset.originalSamples[PageRotation.NONE])
            assertEquals(ReaderPageSideHint.UNKNOWN, evidence.sideHint)
            assertEquals(List(50) { 0x804080c0.toInt() }, evidence.edges!!.left)
            assertEquals(List(50) { 0xefffffff.toInt() }, evidence.edges!!.right)
        }
    }

    @Test
    fun `normal ICC decode original samples use fixed sRGB`() = runTest {
        decode(resource("reader/linear-rgb-profile.jpg")) { asset ->
            val edges = requireNotNull(asset.originalSamples[PageRotation.NONE]).edges!!
            listOf(0, 10, 25, 49).zip(listOf(-6780485, -5535301, -3896900, -1799748)) { index, golden ->
                for (shift in listOf(0, 8, 16)) {
                    assertTrue(
                        kotlin.math.abs((edges.right[index] ushr shift and 255) - (golden ushr shift and 255)) <= 2,
                    )
                }
            }
        }
    }

    @Test
    fun `normal EXIF image coordinates are normalized exactly once`() = runTest {
        val raw = resource("reader/exif-corners-raw.jpg")
        val colors = mapOf(
            ReaderImageOrientation.CLOCKWISE to listOf(0xff0000ff, 0xffffff00, 0xffff0000, 0xff00ff00),
            ReaderImageOrientation.TRANSPOSE to listOf(0xffff0000, 0xff00ff00, 0xff0000ff, 0xffffff00),
            ReaderImageOrientation.COUNTER_CLOCKWISE to listOf(0xff00ff00, 0xffff0000, 0xffffff00, 0xff0000ff),
            ReaderImageOrientation.TRANSVERSE to listOf(0xffffff00, 0xff0000ff, 0xff00ff00, 0xffff0000),
        )
        for ((orientation, golden) in colors) {
            val exif = "45786966000049492a0008000000010012010300010000000600000000000000"
                .chunked(2).map { it.toInt(16).toByte() }.toByteArray().also { it[24] = orientation.exifValue.toByte() }
            val encoded = raw.copyOfRange(0, 2) + byteArrayOf(-1, -31, 0, (exif.size + 2).toByte()) + exif +
                raw.copyOfRange(2, raw.size)
            decode(encoded) { asset ->
                assertEquals(40, asset.sourceWidth)
                assertEquals(60, asset.sourceHeight)
                val edges = requireNotNull(asset.originalSamples[PageRotation.NONE]).edges!!
                val actual = listOf(edges.left.first(), edges.left.last(), edges.right.first(), edges.right.last())
                actual.zip(golden).forEach { (pixel, color) ->
                    for (shift in listOf(0, 8, 16)) {
                        assertTrue(
                            kotlin.math.abs((pixel ushr shift and 255) - (color ushr shift and 255).toInt()) < 30,
                        )
                    }
                }
                val display = asset.bitmap.asSkiaBitmap()
                listOf(
                    display.getColor(2, 0),
                    display.getColor(2, display.height - 12),
                    display.getColor(display.width - 3, 0),
                    display.getColor(display.width - 3, display.height - 12),
                )
                    .zip(golden).forEach { (pixel, color) ->
                        for (shift in listOf(0, 8, 16)) {
                            assertTrue(
                                kotlin.math.abs((pixel ushr shift and 255) - (color ushr shift and 255).toInt()) < 30,
                            )
                        }
                    }
            }
        }
    }

    private suspend fun decode(encoded: ByteArray, verify: (DesktopReaderImageAsset) -> Unit) {
        val content =
            ReaderPageContentOpenRequest(ReaderPageId(ReaderChapterId(73002), 1), 1, EncodedPageRef("synthetic"))
        val lease = SkiaDesktopReaderPageImageDecoder().decode(
            encoded,
            ReaderPageDecodeKey(content, PageDecodePurpose.FULL_PAGE, 100, 150),
        )
        assertNotNull(lease)
        lease!!.use { verify(it.asset) }
    }

    private fun resource(path: String): ByteArray = requireNotNull(
        javaClass.classLoader.getResourceAsStream(path),
    ).use {
        it.readBytes()
    }
}
