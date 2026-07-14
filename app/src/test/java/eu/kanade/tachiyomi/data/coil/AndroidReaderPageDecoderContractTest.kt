package eu.kanade.tachiyomi.data.coil

import coil3.request.CachePolicy
import kotlinx.coroutines.test.runTest
import mihon.domain.reader.PageDecodeCachePolicy
import mihon.domain.reader.PageDecodeRequest
import mihon.domain.reader.PageDecodeResult
import mihon.domain.reader.PageDecoder
import okio.Buffer
import okio.BufferedSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

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
        val production = source("app/src/main/java/eu/kanade/tachiyomi/data/coil/TachiyomiImageDecoder.kt")
        assertTrue(production.contains("decodeWithSharedPageDecoder"))
        assertTrue(production.contains("AndroidTachiyomiPageDecoder"))
    }

    @Test
    fun `Android reader cache adapter keeps tiled pages out of Coil decoded caches`() {
        val mapped = mapAndroidReaderCachePolicy(PageDecodeCachePolicy.TILED_READER)

        assertEquals(CachePolicy.DISABLED, mapped.memory)
        assertEquals(CachePolicy.DISABLED, mapped.disk)
        val production = source("app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/ReaderPageImageView.kt")
        val tiledReaderBranch = production
            .substringAfter("if (!isWebtoon || alwaysDecodeLongStripWithSSIV)")
            .substringBefore("private fun prepareAnimatedImageView")
        assertTrue(tiledReaderBranch.contains("applySharedReaderCachePolicy(PageDecodeCachePolicy.TILED_READER)"))
        assertTrue(!tiledReaderBranch.contains("memoryCachePolicy(CachePolicy.DISABLED)"))
    }

    private fun source(path: String): String {
        val cwd = File(System.getProperty("user.dir"))
        val root = if (File(cwd, "app").exists()) cwd else requireNotNull(cwd.parentFile)
        return File(root, path).readText()
    }
}
