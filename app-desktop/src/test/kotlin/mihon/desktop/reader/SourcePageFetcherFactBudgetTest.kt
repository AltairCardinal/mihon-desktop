package mihon.desktop.reader

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.Page
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import mihon.domain.reader.ReaderDualPageFactBudget
import mihon.domain.reader.ReaderDualPageFactLimits
import mihon.domain.reader.ReaderDualPageFactReadPermit
import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderPageId
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.imageio.ImageIO

class SourcePageFetcherFactBudgetTest {
    @TempDir lateinit var directory: File

    @Test
    fun `actual optional HTTP reads stop at shared byte quota while cached and normal reads survive`() = runBlocking {
        val encoded = png() + ByteArray((ReaderDualPageFactLimits.MAX_NEW_BYTES + 1024 * 1024).toInt())
        val readBytes = AtomicLong()
        val server = MockWebServer().apply { start() }
        val client = countedClient(readBytes)
        val fetcher = SourcePageFetcher(mockk<CatalogueSource>(relaxed = true), client)
        val page = Page(0, imageUrl = server.url("/quota.png").toString())
        val optionalFile = directory.resolve("optional.png")
        val normalFile = directory.resolve("normal.png")
        val budget = ReaderDualPageFactBudget()
        val id = ReaderPageId(ReaderChapterId(1), 0)
        budget.reserve(listOf(id), nowMillis())
        val permit = ReaderDualPageFactReadPermit(budget, id, ::nowMillis)
        try {
            server.enqueue(MockResponse.Builder().chunkedBody(Buffer().write(encoded), 4_864).build())
            var cancelled = false
            try {
                fetcher.fetchToDestination(page, optionalFile, permit)
            } catch (_: CancellationException) {
                cancelled = true
            }
            assertTrue(cancelled, "An optional response must stop reading before exceeding its quota")
            assertEquals(
                ReaderDualPageFactLimits.MAX_NEW_BYTES,
                readBytes.get(),
                "Count actual network-body source reads, not the returned encoded array",
            )
            assertFalse(optionalFile.exists(), "Interrupted encoded writes must be discarded")
            server.enqueue(MockResponse.Builder().body(Buffer().write(encoded)).build())
            readBytes.set(0)
            assertInstanceOf(SourcePageFetchResult.Success::class.java, fetcher.fetchToDestination(page, normalFile))
            assertEquals(
                encoded.size.toLong(),
                readBytes.get(),
                "Normal visible loading has its existing unbounded image path",
            )
            budget.close()
            assertInstanceOf(
                SourcePageFetchResult.Success::class.java,
                fetcher.fetchToDestination(page, normalFile, permit),
            )
            assertEquals(
                2,
                server.requestCount,
                "An existing valid encoded cache must not consume optional network quota",
            )
            val smallEncoded = png()
            val prefixFile = directory.resolve("prefilled.png")
            val prefixBudget = ReaderDualPageFactBudget()
            val prefixId = ReaderPageId(ReaderChapterId(1), 1)
            prefixBudget.reserve(listOf(prefixId), nowMillis())
            val prefixPermit = ReaderDualPageFactReadPermit(prefixBudget, prefixId, ::nowMillis)
            try {
                server.enqueue(
                    MockResponse.Builder().body(Buffer().write(smallEncoded))
                        .addHeader("X-Reader-Body", "prefilled").build(),
                )
                readBytes.set(0)
                assertInstanceOf(
                    SourcePageFetchResult.Success::class.java,
                    fetcher.fetchToDestination(page, prefixFile, prefixPermit),
                )
                org.junit.jupiter.api.Assertions.assertArrayEquals(smallEncoded, prefixFile.readBytes())
                assertEquals(smallEncoded.size.toLong(), readBytes.get())
            } finally {
                prefixBudget.close()
            }

            val opaqueFile = directory.resolve("opaque.png")
            val opaqueBudget = ReaderDualPageFactBudget()
            val opaqueId = ReaderPageId(ReaderChapterId(1), 2)
            opaqueBudget.reserve(listOf(opaqueId), nowMillis())
            val opaquePermit = ReaderDualPageFactReadPermit(opaqueBudget, opaqueId, ::nowMillis)
            try {
                server.enqueue(
                    MockResponse.Builder().body(Buffer().write(smallEncoded))
                        .addHeader("X-Reader-Body", "opaque").build(),
                )
                readBytes.set(0)
                assertInstanceOf(
                    SourcePageFetchResult.Failure::class.java,
                    fetcher.fetchToDestination(page, opaqueFile, opaquePermit),
                )
                assertEquals(0L, readBytes.get(), "An opaque optional body must fail before any adapter body read")
                assertFalse(opaqueFile.exists())
                server.enqueue(
                    MockResponse.Builder().body(Buffer().write(smallEncoded))
                        .addHeader("X-Reader-Body", "opaque").build(),
                )
                assertInstanceOf(
                    SourcePageFetchResult.Success::class.java,
                    fetcher.fetchToDestination(page, opaqueFile),
                )
                org.junit.jupiter.api.Assertions.assertArrayEquals(smallEncoded, opaqueFile.readBytes())
                assertEquals(smallEncoded.size.toLong(), readBytes.get())
            } finally {
                opaqueBudget.close()
            }

            val excessFile = directory.resolve("preconsumed-excess.png")
            val excessBudget = ReaderDualPageFactBudget()
            val excessId = ReaderPageId(ReaderChapterId(1), 3)
            excessBudget.reserve(listOf(excessId), nowMillis())
            val excessPermit = ReaderDualPageFactReadPermit(excessBudget, excessId, ::nowMillis)
            excessPermit.reserve(ReaderDualPageFactLimits.MAX_NEW_BYTES - 1)
            try {
                server.enqueue(
                    MockResponse.Builder().body(Buffer().write(smallEncoded))
                        .addHeader("X-Reader-Body", "prefilled").build(),
                )
                readBytes.set(0)
                var excessCancelled = false
                try {
                    fetcher.fetchToDestination(page, excessFile, excessPermit)
                } catch (_: CancellationException) {
                    excessCancelled = true
                }
                assertTrue(excessCancelled)
                assertFalse(excessFile.exists())
                assertEquals(
                    smallEncoded.size.toLong(),
                    readBytes.get(),
                    "Original response prebuffering already consumed these bytes; adapter must not continue reading",
                )
            } finally {
                excessBudget.close()
            }
        } finally {
            budget.close()
            server.close()
        }
    }

    @Test
    fun `actual hanging HTTP body is interrupted at optional fifteen second deadline`() = runBlocking {
        val server = MockWebServer().apply { start() }
        val fetcher =
            SourcePageFetcher(
                mockk<CatalogueSource>(relaxed = true),
                OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build(),
            )
        val budget = ReaderDualPageFactBudget()
        val id = ReaderPageId(ReaderChapterId(2), 0)
        val destination = directory.resolve("hanging.png")
        val start = nowMillis()
        budget.reserve(listOf(id), start)
        val permit = ReaderDualPageFactReadPermit(budget, id, ::nowMillis)
        server.enqueue(MockResponse.Builder().body(Buffer().write(png())).bodyDelay(20, TimeUnit.SECONDS).build())
        try {
            val result = fetcher.fetchToDestination(
                Page(0, imageUrl = server.url("/hanging.png").toString()),
                destination,
                permit,
            )
            assertInstanceOf(SourcePageFetchResult.Failure::class.java, result)
            val elapsed = nowMillis() - start
            assertTrue(
                elapsed in 12_000L..18_000L,
                "Actual body read must stop at the 15-second optional deadline, elapsed=$elapsed",
            )
            assertFalse(destination.exists())
            assertEquals(0L, budget.remainingWaitMillis(nowMillis()))
        } finally {
            budget.close()
            server.close()
        }
    }

    private fun countedClient(bytes: AtomicLong) = OkHttpClient.Builder().addNetworkInterceptor { chain ->
        val response = chain.proceed(chain.request())
        val body = response.body
        val counted = object : ForwardingSource(body.source()) {
            override fun read(
                sink: Buffer,
                byteCount: Long,
            ): Long = super.read(sink, byteCount).also { if (it > 0) bytes.addAndGet(it) }
        }.buffer()
        if (response.header("X-Reader-Body") == "prefilled") counted.request(1)
        val adapted = if (response.header("X-Reader-Body") == "opaque") {
            java.lang.reflect.Proxy.newProxyInstance(
                okio.BufferedSource::class.java.classLoader,
                arrayOf(okio.BufferedSource::class.java),
            ) { _, method, args -> method.invoke(counted, *(args ?: emptyArray())) } as okio.BufferedSource
        } else {
            counted
        }
        response.newBuilder().body(object : ResponseBody() {
            override fun contentType() = body.contentType()
            override fun contentLength() = body.contentLength()
            override fun source() = adapted
        }).build()
    }.build()

    private fun nowMillis() = System.nanoTime() / 1_000_000L
    private fun png(): ByteArray = ByteArrayOutputStream().also {
        ImageIO.write(BufferedImage(40, 60, BufferedImage.TYPE_INT_RGB), "png", it)
    }.toByteArray()
}
