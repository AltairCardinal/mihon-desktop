package mihon.data.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import mihon.data.sync.http.SyncHttpBodyDirection
import mihon.data.sync.http.SyncHttpBodyObserver
import mihon.data.sync.http.SyncHttpBodyWork
import mihon.data.sync.http.SyncHttpClient
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SyncHttpBodyProgressIntegrationTest {
    @Test
    fun `logical body work tag reaches live request and response callbacks`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse(body = "answer"))
            val work = mutableListOf<Triple<SyncHttpBodyDirection, String?, Long>>()
            val observer = object : SyncHttpBodyObserver {
                override fun onBodyBytes(
                    direction: SyncHttpBodyDirection,
                    requestId: Long,
                    bytes: Long,
                    total: Long?,
                ) = Unit

                override fun onBodyWorkBytes(
                    direction: SyncHttpBodyDirection,
                    requestId: Long,
                    bytes: Long,
                    total: Long?,
                    workKey: String?,
                ) {
                    work += Triple(direction, workKey, bytes)
                }
            }
            val http = SyncHttpClient(OkHttpClient(), setOf(server.hostName), bodyObserver = observer)
            val request = http.request(server.url("/tagged").toString(), "POST", body = "payload".toRequestBody())
                .newBuilder().tag(SyncHttpBodyWork::class.java, SyncHttpBodyWork("batch-a")).build()

            assertEquals(200, http.execute(request).code)
            assertTrue(work.contains(Triple(SyncHttpBodyDirection.UPLOAD, "batch-a", 7L)))
            assertTrue(work.contains(Triple(SyncHttpBodyDirection.DOWNLOAD, "batch-a", 6L)))
        }
    }

    @Test
    fun `slow response reports body bytes before request completes`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder().body("x".repeat(24_576)).throttleBody(8_192, 1, TimeUnit.SECONDS).build(),
            )
            val firstChunk = CountDownLatch(1)
            val observations = AtomicInteger()
            val observer = object : SyncHttpBodyObserver {
                override fun onBodyBytes(direction: SyncHttpBodyDirection, requestId: Long, bytes: Long, total: Long?) {
                    if (direction == SyncHttpBodyDirection.DOWNLOAD && bytes > 0) {
                        observations.incrementAndGet()
                        firstChunk.countDown()
                    }
                }
            }
            val http = SyncHttpClient(OkHttpClient(), setOf(server.hostName), bodyObserver = observer)
            val response = async(Dispatchers.IO) { http.execute(http.request(server.url("/slow").toString(), "GET")) }

            assertTrue(firstChunk.await(5, TimeUnit.SECONDS))
            assertTrue(!response.isCompleted, "progress must be visible during the slow body")
            assertEquals(24_576, response.await().body.size)
            assertTrue(observations.get() >= 3)
        }
    }

    @Test
    fun `request body reports write bytes separately from response bytes`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse(body = "ok"))
            val upload = mutableListOf<Long>()
            val download = mutableListOf<Long>()
            val observer = object : SyncHttpBodyObserver {
                override fun onBodyBytes(direction: SyncHttpBodyDirection, requestId: Long, bytes: Long, total: Long?) {
                    when (direction) {
                        SyncHttpBodyDirection.UPLOAD -> upload += bytes
                        SyncHttpBodyDirection.DOWNLOAD -> download += bytes
                    }
                }
            }
            val http = SyncHttpClient(OkHttpClient(), setOf(server.hostName), bodyObserver = observer)
            http.execute(http.request(server.url("/write").toString(), "POST", body = "payload".toRequestBody()))

            assertEquals(7L, upload.last())
            assertEquals(2L, download.last())
        }
    }

    @Test
    fun `large upload reports a body chunk before its request finishes`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse(body = "ok"))
            val firstChunk = CountDownLatch(1)
            val continueWrite = CountDownLatch(1)
            val observer = object : SyncHttpBodyObserver {
                override fun onBodyBytes(direction: SyncHttpBodyDirection, requestId: Long, bytes: Long, total: Long?) {
                    if (direction == SyncHttpBodyDirection.UPLOAD && bytes >= 8_192L) firstChunk.countDown()
                }
            }
            val body = object : RequestBody() {
                override fun contentType() = null

                override fun contentLength() = 16_384L

                override fun writeTo(sink: BufferedSink) {
                    sink.write(ByteArray(8_192))
                    check(continueWrite.await(5, TimeUnit.SECONDS))
                    sink.write(ByteArray(8_192))
                }
            }
            val http = SyncHttpClient(OkHttpClient(), setOf(server.hostName), bodyObserver = observer)
            val response =
                async(Dispatchers.IO) {
                    http.execute(http.request(server.url("/upload").toString(), "POST", body = body))
                }
            try {
                assertTrue(firstChunk.await(5, TimeUnit.SECONDS))
                assertTrue(!response.isCompleted)
            } finally {
                continueWrite.countDown()
            }
            assertEquals(200, response.await().code)
        }
    }
}
