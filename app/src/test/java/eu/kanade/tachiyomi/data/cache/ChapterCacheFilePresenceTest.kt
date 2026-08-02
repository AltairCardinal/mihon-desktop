package eu.kanade.tachiyomi.data.cache

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

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

    @Test
    fun `concurrent editor for the same image reports that the second write was not committed`() {
        val context = mockk<Context>()
        every { context.cacheDir } returns cacheDirectory.toFile()
        val cache = ChapterCache(context, Json)
        val imageUrl = "https://example.test/contention"
        val editorAcquired = CountDownLatch(1)
        val releaseFirstWriter = CountDownLatch(1)
        val firstResult = AtomicReference<Boolean>()
        val firstResponse = response(
            imageUrl,
            object : ResponseBody() {
                override fun contentType(): MediaType? = null

                override fun contentLength(): Long = -1

                override fun source(): BufferedSource {
                    val bytes = Buffer().writeUtf8("first image")
                    return object : ForwardingSource(bytes) {
                        private var waiting = true

                        override fun read(sink: Buffer, byteCount: Long): Long {
                            if (waiting) {
                                waiting = false
                                editorAcquired.countDown()
                                check(releaseFirstWriter.await(5, TimeUnit.SECONDS))
                            }
                            return super.read(sink, byteCount)
                        }
                    }.buffer()
                }
            },
        )

        val firstWriter = thread(name = "chapter-cache-first-writer") {
            firstResult.set(cache.putImageToCache(imageUrl, firstResponse))
        }
        try {
            assertTrue(editorAcquired.await(5, TimeUnit.SECONDS))

            val secondCommitted = cache.putImageToCache(
                imageUrl,
                response(imageUrl, "second image".toResponseBody()),
            )

            assertFalse(secondCommitted)
        } finally {
            releaseFirstWriter.countDown()
            firstWriter.join(5_000)
            assertFalse(firstWriter.isAlive)
            cache.close()
        }
        assertTrue(firstResult.get())
    }

    private fun response(imageUrl: String, body: ResponseBody): Response = Response.Builder()
        .request(Request.Builder().url(imageUrl).build())
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .body(body)
        .build()
}
