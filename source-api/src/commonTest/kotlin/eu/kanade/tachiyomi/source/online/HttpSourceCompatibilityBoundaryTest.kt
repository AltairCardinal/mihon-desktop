package eu.kanade.tachiyomi.source.online

import eu.kanade.tachiyomi.network.SourceCompatibilityException
import eu.kanade.tachiyomi.network.SourceOperationPhase
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import rx.Observable
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Exercise the default production Rx chain on a real worker, never a global Rx/uncaught hook. */
class HttpSourceCompatibilityBoundaryTest {
    @Test
    fun `wrapped close cancellation remains control when parser has an unrelated Error`() {
        val server = HttpSourceTestServer()
        val cancellation = CancellationException("controlled close cancellation")
        val closed = AtomicInteger()
        try {
            server.enqueue(HttpTestResponse(body = "response"))
            val source = BoundarySource(
                server.baseUrl,
                trackingClient(server.client, closed, ExceptionInInitializerError(cancellation)),
                SourceOperationPhase.SEARCH,
                AssertionError("unrelated parser Error"),
            )
            val outcome = onWorker { source.operation(SourceOperationPhase.SEARCH) }
            assertSame(cancellation, outcome.error.get() ?: outcome.escaped.get())
            assertEquals(1, closed.get())
        } finally {
            server.close()
        }
    }

    @Test
    fun `fatal response close cannot be suppressed by a parser compatibility failure`() {
        val server = HttpSourceTestServer()
        val fatal = object : VirtualMachineError("controlled close fatal") {}
        val closed = AtomicInteger()
        try {
            server.enqueue(HttpTestResponse(body = "response"))
            val source = BoundarySource(
                server.baseUrl,
                trackingClient(server.client, closed, fatal),
                SourceOperationPhase.SEARCH,
                NoSuchMethodError("future parser"),
            )
            val outcome = onWorker { source.operation(SourceOperationPhase.SEARCH) }
            assertSame(fatal, outcome.escaped.get())
            assertEquals(0, outcome.errors.get())
            assertEquals(1, closed.get())
        } finally {
            server.close()
        }
    }

    @Test
    fun `parser preserves fatal cancellation and ordinary IO while closing its response`() {
        val fatal = object : VirtualMachineError("controlled parser fatal") {}
        for (expected in listOf<Throwable>(
            fatal,
            CancellationException("parser cancelled"),
            IOException("ordinary parse IO"),
        )) {
            val server = HttpSourceTestServer()
            val closed = AtomicInteger()
            try {
                server.enqueue(HttpTestResponse(body = "real response"))
                val source =
                    BoundarySource(
                        server.baseUrl,
                        trackingClient(server.client, closed),
                        SourceOperationPhase.SEARCH,
                        expected,
                    )
                val result = onWorker { source.operation(SourceOperationPhase.SEARCH) }
                assertSame(expected, result.error.get() ?: result.escaped.get())
                assertEquals(0, result.values.get())
                assertEquals(1, closed.get())
            } finally {
                server.close()
            }
        }
    }

    @Test
    fun `image request linkage is typed but successful image response stays open for caller`() {
        val server = HttpSourceTestServer()
        val closed = AtomicInteger()
        val failure = NoSuchMethodError("future image request")
        try {
            val source =
                BoundarySource(
                    server.baseUrl,
                    trackingClient(server.client, closed),
                    SourceOperationPhase.IMAGE_REQUEST,
                    failure,
                    "request",
                )
            val error = runCatching {
                runBlocking { source.getImage(Page(0, server.baseUrl, server.baseUrl)) }
            }.exceptionOrNull()
            assertInstanceOf(SourceCompatibilityException::class.java, error)
            assertSame(failure, error?.cause)
            assertEquals(SourceOperationPhase.IMAGE_REQUEST, (error as SourceCompatibilityException).phase)
            assertEquals(0, server.requestCount)
            source.failure = null
            server.enqueue(HttpTestResponse(body = "image payload"))
            runBlocking {
                source.getImage(Page(0, server.baseUrl, server.baseUrl)).use { response ->
                    assertEquals(0, closed.get())
                    assertEquals("image payload", response.body.string())
                }
            }
            assertEquals(1, closed.get())
        } finally {
            server.close()
        }
    }

    @Test
    fun `all default parsers deliver linkage once to subscriber and close the real response`() {
        for (phase in phases) {
            val server = HttpSourceTestServer()
            val closed = AtomicInteger()
            val failure = NoSuchMethodError("future parser ABI")
            val source = BoundarySource(server.baseUrl, trackingClient(server.client, closed), phase, failure)
            try {
                server.enqueue(HttpTestResponse(body = "real response"))
                val outcome = onWorker { source.operation(phase) }
                val error = assertInstanceOf(SourceCompatibilityException::class.java, outcome.error.get(), "$phase")
                assertSame(failure, error.cause)
                assertEquals(phase, error.phase)
                assertEquals(1, outcome.errors.get(), "$phase")
                assertEquals(0, outcome.values.get(), "$phase")
                assertEquals(null, outcome.escaped.get(), "$phase")
                assertEquals(1, closed.get(), "$phase")
                assertEquals(1, server.requestCount)

                // A failed parser must not poison the same client's subsequent healthy operation.
                source.failure = null
                server.enqueue(HttpTestResponse(body = "healthy response"))
                val healthy = onWorker { source.operation(phase) }
                assertEquals(1, healthy.values.get(), "$phase")
                assertEquals(0, healthy.errors.get(), "$phase")
                assertEquals(null, healthy.escaped.get(), "$phase")
                assertEquals(2, closed.get(), "$phase")
            } finally {
                server.close()
            }
        }
    }

    @Test
    fun `request and client getter linkage is typed before Rx without sending HTTP`() {
        for (site in listOf("request", "client")) {
            for (phase in phases) {
                val server = HttpSourceTestServer()
                val failure = NoClassDefFoundError("future request dependency")
                val source = BoundarySource(server.baseUrl, server.client, phase, failure, site)
                try {
                    val outcome = onWorker { source.operation(phase) }
                    // Most fetch methods build requests eagerly; both eager and deferred construction
                    // must preserve the typed failure before Rx can treat LinkageError as fatal.
                    val error = assertInstanceOf(
                        SourceCompatibilityException::class.java,
                        outcome.error.get() ?: outcome.escaped.get(),
                        "$site $phase",
                    )
                    assertSame(failure, error.cause)
                    assertEquals(phase, error.phase)
                    assertEquals(0, outcome.values.get())
                    assertEquals(0, server.requestCount)
                } finally {
                    server.close()
                }
            }
        }
    }

    private data class Outcome(
        val error: AtomicReference<Throwable> = AtomicReference(),
        val escaped: AtomicReference<Throwable> = AtomicReference(),
        val values: AtomicInteger = AtomicInteger(),
        val errors: AtomicInteger = AtomicInteger(),
    )

    @Suppress("DEPRECATION")
    private fun onWorker(operation: () -> Observable<*>): Outcome {
        val result = Outcome()
        val finished = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "ec-default-parser").apply {
                uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error ->
                    result.escaped.set(error)
                    finished.countDown()
                }
            }
        }
        try {
            executor.execute {
                operation().subscribe(
                    { result.values.incrementAndGet() },
                    { error ->
                        result.error.set(error)
                        result.errors.incrementAndGet()
                        finished.countDown()
                    },
                    { finished.countDown() },
                )
            }
            assertTrue(finished.await(5, TimeUnit.SECONDS), "No terminal signal or worker failure")
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
        return result
    }

    private fun trackingClient(
        client: OkHttpClient,
        closed: AtomicInteger,
        closeFailure: Throwable? = null,
    ) = client.newBuilder()
        .addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            val original = response.body
            val tracked = object : ResponseBody() {
                private val stream = object : ForwardingSource(original.source()) {
                    override fun close() {
                        closed.incrementAndGet()
                        super.close()
                        closeFailure?.let { throw it }
                    }
                }.buffer()
                override fun contentType() = original.contentType()
                override fun contentLength() = original.contentLength()
                override fun source(): BufferedSource = stream
            }
            response.newBuilder().body(tracked).build()
        }.build()

    private class BoundarySource(
        override val baseUrl: String,
        private val http: OkHttpClient,
        private val phase: SourceOperationPhase,
        var failure: Throwable?,
        private val site: String = "parse",
    ) : HttpSource() {
        override val name = "Boundary fixture"
        override val lang = "en"
        override val supportsLatest = true
        override val client: OkHttpClient get() {
            checkSite("client")
            return http
        }
        private fun checkSite(value: String) {
            if (site == value) failure?.let { throw it }
        }
        private fun request(): Request {
            checkSite("request")
            return Request.Builder().url(baseUrl).build()
        }
        private fun parse(response: Response) {
            assertEquals(200, response.code)
            checkSite("parse")
            // Leave ownership to the base implementation, even if a parser does not read a body.
        }
        override fun popularMangaRequest(page: Int) = request()
        override fun searchMangaRequest(page: Int, query: String, filters: FilterList) = request()
        override fun latestUpdatesRequest(page: Int) = request()
        override fun mangaDetailsRequest(manga: SManga) = request()
        override fun chapterListRequest(manga: SManga) = request()
        override fun pageListRequest(chapter: SChapter) = request()
        override fun imageUrlRequest(page: Page) = request()
        override fun imageRequest(page: Page) = request()
        override fun popularMangaParse(
            response: Response,
        ): MangasPage {
            parse(response)
            return MangasPage(emptyList(), false)
        }
        override fun searchMangaParse(
            response: Response,
        ): MangasPage {
            parse(response)
            return MangasPage(emptyList(), false)
        }
        override fun latestUpdatesParse(
            response: Response,
        ): MangasPage {
            parse(response)
            return MangasPage(emptyList(), false)
        }
        override fun mangaDetailsParse(response: Response): SManga {
            parse(response)
            return SManga.create()
        }
        override fun chapterListParse(response: Response): List<SChapter> {
            parse(response)
            return emptyList()
        }
        override fun pageListParse(response: Response): List<Page> {
            parse(response)
            return emptyList()
        }
        override fun imageUrlParse(response: Response): String {
            parse(response)
            return "$baseUrl/image"
        }
        override fun chapterPageParse(response: Response) = SChapter.create()

        @Suppress("DEPRECATION")
        fun operation(phase: SourceOperationPhase): Observable<*> = when (phase) {
            SourceOperationPhase.POPULAR -> fetchPopularManga(1)
            SourceOperationPhase.SEARCH -> fetchSearchManga(1, "fixture", FilterList())
            SourceOperationPhase.LATEST -> fetchLatestUpdates(1)
            SourceOperationPhase.DETAILS -> fetchMangaDetails(SManga.create())
            SourceOperationPhase.CHAPTERS -> fetchChapterList(SManga.create())
            SourceOperationPhase.PAGE_LIST -> fetchPageList(SChapter.create())
            SourceOperationPhase.IMAGE_URL -> fetchImageUrl(Page(0, baseUrl))
            else -> error("Unexpected phase $phase")
        }
    }

    private companion object {
        val phases = listOf(
            SourceOperationPhase.POPULAR,
            SourceOperationPhase.SEARCH,
            SourceOperationPhase.LATEST,
            SourceOperationPhase.DETAILS,
            SourceOperationPhase.CHAPTERS,
            SourceOperationPhase.PAGE_LIST,
            SourceOperationPhase.IMAGE_URL,
        )
    }
}
