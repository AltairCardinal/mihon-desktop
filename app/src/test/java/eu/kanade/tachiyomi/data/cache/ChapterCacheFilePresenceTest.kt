package eu.kanade.tachiyomi.data.cache

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class ChapterCacheFilePresenceTest {

    @TempDir
    lateinit var cacheDirectory: Path

    @Test
    fun `journal entry without its image file is not a cache hit`() {
        val context = mockk<Context>()
        every { context.cacheDir } returns cacheDirectory.toFile()
        val cache = ChapterCache(context, Json)
        val imageUrl = "https://example.test/image"
        val response = Response.Builder()
            .request(Request.Builder().url(imageUrl).build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body("image bytes".toResponseBody())
            .build()

        try {
            cache.putImageToCache(imageUrl, response)
            assertTrue(cache.isImageInCache(imageUrl))
            assertTrue(cache.getImageFile(imageUrl).delete())

            assertFalse(cache.isImageInCache(imageUrl))
        } finally {
            cache.close()
        }
    }
}
