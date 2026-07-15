package eu.kanade.tachiyomi.data.coil

import android.graphics.Bitmap
import coil3.Image
import coil3.decode.ImageSource
import coil3.request.CachePolicy
import coil3.request.Options
import coil3.size.Scale
import coil3.size.Size
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import mihon.domain.reader.PageDecodeCachePolicy
import mihon.domain.reader.PageDecodeRequest
import mihon.domain.reader.PageDecodeResult
import mihon.domain.reader.PageDecoder
import okio.Buffer
import okio.BufferedSource
import okio.FileSystem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AndroidReaderPageDecoderContractTest {

    @Test
    fun `Android production decode delegate forwards buffered source and shared request without copying`() = runTest {
        val encoded = Buffer().writeUtf8("encoded-page")
        val request = PageDecodeRequest(pageIndex = 7, generation = 3L, maxWidth = 1200, maxHeight = 1800)
        var receivedSource: BufferedSource? = null
        var receivedRequest: PageDecodeRequest? = null
        val decoder = object : PageDecoder<BufferedSource, String> {
            override suspend fun decode(
                encoded: BufferedSource,
                request: PageDecodeRequest,
            ): PageDecodeResult<String> {
                receivedSource = encoded
                receivedRequest = request
                return PageDecodeResult.Success(
                    generation = request.generation,
                    value = "bitmap",
                    width = 600,
                    height = 900,
                    estimatedBytes = 2_160_000,
                    isSampled = true,
                )
            }
        }

        val result = decodeWithSharedPageDecoder(encoded, request, decoder)

        assertSame(encoded, receivedSource)
        assertEquals(request, receivedRequest)
        assertEquals("bitmap", (result as PageDecodeResult.Success).value)
        assertEquals(3L, result.generation)
        assertTrue(result.isSampled)
    }

    @Test
    fun `production Tachiyomi decoder forwards reader request identity through decode`() = runTest {
        val options = mockk<Options>()
        every { options.size } returns Size.ORIGINAL
        every { options.scale } returns Scale.FIT
        val bitmap = mockk<Bitmap>()
        val image = mockk<Image>()
        var receivedRequest: PageDecodeRequest? = null
        val pageDecoder = object : PageDecoder<BufferedSource, Bitmap> {
            override suspend fun decode(
                encoded: BufferedSource,
                request: PageDecodeRequest,
            ): PageDecodeResult<Bitmap> {
                receivedRequest = request
                return PageDecodeResult.Success(request.generation, bitmap, 10, 20, 800)
            }
        }
        val decoder = TachiyomiImageDecoder(
            resources = ImageSource(Buffer().writeUtf8("encoded"), FileSystem.SYSTEM),
            options = options,
            identity = DecodeRequestIdentity(pageIndex = 7, generation = 3L) { true },
            pageDecoder = pageDecoder,
            imageMapper = { image },
        )

        val result = decoder.decode()

        assertEquals(PageDecodeRequest(7, 3L, Int.MAX_VALUE, Int.MAX_VALUE), receivedRequest)
        assertSame(image, result.image)
    }

    @Test
    fun `production Tachiyomi decoder rejects a stale shared result before image submission`() = runTest {
        val options = mockk<Options>()
        every { options.size } returns Size.ORIGINAL
        every { options.scale } returns Scale.FIT
        val bitmap = mockk<Bitmap>()
        val pageDecoder = object : PageDecoder<BufferedSource, Bitmap> {
            override suspend fun decode(
                encoded: BufferedSource,
                request: PageDecodeRequest,
            ) = PageDecodeResult.Success(request.generation, bitmap, 10, 20, 800)
        }
        val decoder = TachiyomiImageDecoder(
            resources = ImageSource(Buffer().writeUtf8("encoded"), FileSystem.SYSTEM),
            options = options,
            identity = DecodeRequestIdentity(pageIndex = 7, generation = 3L) { false },
            pageDecoder = pageDecoder,
            imageMapper = { error("stale bitmap must not be submitted") },
        )

        val failure = runCatching { decoder.decode() }.exceptionOrNull()
        assertInstanceOf(CancellationException::class.java, failure)
    }

    @Test
    fun `Android reader cache adapter keeps tiled pages out of Coil decoded caches`() {
        val mapped = mapAndroidReaderCachePolicy(PageDecodeCachePolicy.TILED_READER)

        assertEquals(CachePolicy.DISABLED, mapped.memory)
        assertEquals(CachePolicy.DISABLED, mapped.disk)
    }

    @Test
    fun `Android reader sample is a power of two that keeps output within both requested bounds`() {
        val cases = listOf(
            BoundedSampleCase(4095, 1000, 2048, 2048, expectedSample = 2),
            BoundedSampleCase(5000, 1000, 2048, 2048, expectedSample = 4),
            BoundedSampleCase(1000, 10_001, 2048, 2048, expectedSample = 8),
            BoundedSampleCase(5000, 5000, 2048, 1024, expectedSample = 8),
            BoundedSampleCase(1000, 900, 2048, 2048, expectedSample = 1),
        )

        cases.forEach { case ->
            val sample = calculateBoundedReaderSampleSize(
                sourceWidth = case.sourceWidth,
                sourceHeight = case.sourceHeight,
                maxWidth = case.maxWidth,
                maxHeight = case.maxHeight,
            )

            assertEquals(case.expectedSample, sample)
            assertEquals(1, Integer.bitCount(sample))
            assertTrue(ceilingDivide(case.sourceWidth, sample) <= case.maxWidth)
            assertTrue(ceilingDivide(case.sourceHeight, sample) <= case.maxHeight)
        }
    }

    @Test
    fun `Android reader sample rejects bounds that require an unrepresentable power of two`() {
        assertThrows(IllegalArgumentException::class.java) {
            calculateBoundedReaderSampleSize(
                sourceWidth = Int.MAX_VALUE,
                sourceHeight = 1,
                maxWidth = 1,
                maxHeight = 1,
            )
        }
    }

    @Test
    fun `Android production page decoder passes bounded power of two samples to native decoder`() = runTest {
        val cases = listOf(
            BoundedSampleCase(5000, 1000, 2048, 2048, expectedSample = 4),
            BoundedSampleCase(1000, 10_001, 2048, 2048, expectedSample = 8),
        )

        cases.forEach { case ->
            val bitmap = mockk<Bitmap>()
            every { bitmap.width } returns ceilingDivide(case.sourceWidth, case.expectedSample)
            every { bitmap.height } returns ceilingDivide(case.sourceHeight, case.expectedSample)
            every { bitmap.allocationByteCount } returns 1024
            val nativeDecoder = FakeAndroidReaderNativeDecoder(case.sourceWidth, case.sourceHeight, bitmap)
            val decoder = AndroidTachiyomiPageDecoder(
                cropBorders = false,
                displayProfile = null,
                bitmapConfig = Bitmap.Config.ARGB_8888,
                nativeDecoderFactory = AndroidReaderNativeDecoderFactory { _, _, _ -> nativeDecoder },
            )

            val result = decoder.decode(
                Buffer().writeUtf8("encoded"),
                PageDecodeRequest(0, 1L, case.maxWidth, case.maxHeight),
            )

            assertInstanceOf(PageDecodeResult.Success::class.java, result)
            assertEquals(listOf(case.expectedSample), nativeDecoder.decodeSamples)
            assertEquals(1, Integer.bitCount(nativeDecoder.decodeSamples.single()))
            assertEquals(1, nativeDecoder.recycleCalls)
        }
    }

    @Test
    fun `Android production page decoder rejects unrepresentable sample without invoking native decode`() = runTest {
        val nativeDecoder = FakeAndroidReaderNativeDecoder(Int.MAX_VALUE, 1, mockk())
        val decoder = AndroidTachiyomiPageDecoder(
            cropBorders = false,
            displayProfile = null,
            bitmapConfig = Bitmap.Config.ARGB_8888,
            nativeDecoderFactory = AndroidReaderNativeDecoderFactory { _, _, _ -> nativeDecoder },
        )

        val result = decoder.decode(
            Buffer().writeUtf8("encoded"),
            PageDecodeRequest(0, 1L, maxWidth = 1, maxHeight = 1),
        )

        assertInstanceOf(PageDecodeResult.Failure::class.java, result)
        assertTrue(nativeDecoder.decodeSamples.isEmpty())
        assertEquals(1, nativeDecoder.recycleCalls)
    }

    private data class BoundedSampleCase(
        val sourceWidth: Int,
        val sourceHeight: Int,
        val maxWidth: Int,
        val maxHeight: Int,
        val expectedSample: Int,
    )

    private fun ceilingDivide(value: Int, divisor: Int): Int =
        ((value.toLong() + divisor - 1L) / divisor).toInt()

    private class FakeAndroidReaderNativeDecoder(
        override val width: Int,
        override val height: Int,
        private val bitmap: Bitmap,
    ) : AndroidReaderNativeDecoder {
        val decodeSamples = mutableListOf<Int>()
        var recycleCalls = 0

        override fun decode(sampleSize: Int): Bitmap {
            decodeSamples += sampleSize
            return bitmap
        }

        override fun recycle() {
            recycleCalls++
        }
    }
}
