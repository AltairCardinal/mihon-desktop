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
    fun `Android reader ceiling sample keeps non power of two image within both requested bounds`() {
        val cases = listOf(
            BoundedSampleCase(4095, 1000, 2048, 2048, expectedSample = 2),
            BoundedSampleCase(5000, 1000, 2048, 2048, expectedSample = 3),
            BoundedSampleCase(1000, 10_001, 2048, 2048, expectedSample = 5),
            BoundedSampleCase(5000, 5000, 2048, 1024, expectedSample = 5),
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
            assertTrue(ceilingDivide(case.sourceWidth, sample) <= case.maxWidth)
            assertTrue(ceilingDivide(case.sourceHeight, sample) <= case.maxHeight)
        }
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
}
