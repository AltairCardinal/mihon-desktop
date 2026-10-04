package eu.kanade.tachiyomi.ui.reader.loader

import android.content.Context
import android.graphics.Bitmap
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.publishLoadedPageListForTest
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import mihon.domain.reader.ReaderPageFactState
import mihon.domain.reader.materialize.ReaderChapterContentRequest
import mihon.domain.reader.scheduler.ReaderPageMaterializeRunner
import mihon.domain.reader.session.EncodedPageRef
import mihon.domain.reader.session.ReaderChapterId
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tachiyomi.domain.chapter.model.Chapter
import uy.kohesive.injekt.api.addSingleton
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class ReaderPageFactProductionWiringTest {
    @Test
    fun `cancelling a same ref writer wait closes the unconsumed original HTTP response`() = runBlocking {
        val directory = Files.createTempDirectory("reader-http-cache-wait-cancel").toFile()
        val context = mockk<Context>()
        every { context.cacheDir } returns directory
        val cache = ChapterCache(context, Json)
        val store = AndroidReaderEncodedPageStore(cache)
        val writerEntered = CompletableDeferred<Unit>()
        val releaseWriter = CompletableDeferred<Unit>()
        val responseClosed = AtomicBoolean()
        val bodyReads = AtomicInteger()
        val baselinePhysical = ReaderPageMaterializeRunner.processPhysicalWorkCount.value
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            val original = response.body
            val source = object : ForwardingSource(original.source()) {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    bodyReads.incrementAndGet()
                    return super.read(sink, byteCount)
                }
            }.buffer()
            response.newBuilder().body(object : ResponseBody() {
                override fun contentType() = original.contentType()
                override fun contentLength() = original.contentLength()
                override fun source() = source
                override fun close() {
                    try {
                        original.close()
                    } finally {
                        responseClosed.set(true)
                    }
                }
            }).build()
        }.build()
        MockWebServer().also { it.start() }.use { server ->
            server.enqueue(MockResponse(body = "current encoded bytes"))
            val imageUrl = server.url("/same-image").toString()
            val ref = EncodedPageRef(imageUrl)
            val held = async(Dispatchers.IO) {
                runCatching {
                    store.store(ref) {
                        writerEntered.complete(Unit)
                        releaseWriter.await()
                        assertTrue(cache.putImageToCache(imageUrl, responseForImage(imageUrl, "old encoded bytes")))
                        cache.getImageFile(imageUrl).length()
                    }
                }
            }
            writerEntered.await()
            val chapter = ReaderChapter(Chapter.create().copy(id = 7, mangaId = 1))
            val page = ReaderPage(0, imageUrl = imageUrl).apply { this.chapter = chapter }
            chapter.publishLoadedPageListForTest(listOf(page))
            val loader = HttpPageLoader(
                chapter,
                HttpReaderMaterializeAdapterIntegrationTest.TestHttpSource(server, client),
                cache,
                dispatcher = Dispatchers.IO,
                encodedPageStore = store,
            )
            try {
                loader.onPageSelected(page)
                val storeLock = store.javaClass.getDeclaredField("stateLock").run {
                    isAccessible = true
                    get(store)
                }
                withTimeout(5_000) {
                    while (synchronized(storeLock) {
                            val writers = store.javaClass.getDeclaredField("refWriters").run {
                                isAccessible = true
                                get(store) as Map<*, *>
                            }
                            val lease = checkNotNull(writers[ref])
                            lease.javaClass.getDeclaredField("callers").run {
                                isAccessible = true
                                getInt(lease)
                            } != 2
                        }
                    ) {
                        delay(10)
                    }
                }
                assertEquals(0, bodyReads.get())
                loader.recycle()
                withTimeout(5_000) {
                    while (ReaderPageMaterializeRunner.processPhysicalWorkCount.value != baselinePhysical) delay(10)
                }
                assertTrue(
                    "A cancelled cache waiter must close its received, unconsumed HTTP response",
                    responseClosed.get(),
                )
                assertEquals(0, bodyReads.get())
            } finally {
                loader.recycle()
                releaseWriter.complete(Unit)
                held.await()
                cache.close()
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
                directory.deleteRecursively()
            }
        }
    }

    private fun responseForImage(imageUrl: String, bytes: String): okhttp3.Response = okhttp3.Response.Builder()
        .request(okhttp3.Request.Builder().url(imageUrl).build())
        .protocol(okhttp3.Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .body(bytes.toResponseBody())
        .build()

    @Test
    fun `replacement generation waits for same cache writer without reporting a storage error`() = runBlocking {
        val directory = Files.createTempDirectory("reader-http-cache-generation").toFile()
        val context = mockk<Context>()
        every { context.cacheDir } returns directory
        val cache = ChapterCache(context, Json)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val responses = AtomicInteger()
        val replacementReads = AtomicInteger()
        val replacementClosed = CountDownLatch(1)
        val png = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/a9sAAAAASUVORK5CYII=",
        )
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            val attempt = responses.incrementAndGet()
            val originalBody = response.body
            val bodySource = object : ForwardingSource(originalBody.source()) {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    if (attempt == 1) {
                        entered.countDown()
                        check(release.await(15, TimeUnit.SECONDS)) { "Original HTTP read gate expired" }
                    } else {
                        replacementReads.incrementAndGet()
                    }
                    return super.read(sink, byteCount)
                }

                override fun close() {
                    try {
                        super.close()
                    } finally {
                        if (attempt != 1) replacementClosed.countDown()
                    }
                }
            }.buffer()
            response.newBuilder().body(object : ResponseBody() {
                override fun contentType() = originalBody.contentType()
                override fun contentLength() = originalBody.contentLength()
                override fun source() = bodySource
            }).build()
        }.build()
        MockWebServer().also { it.start() }.use { server ->
            repeat(2) { server.enqueue(MockResponse.Builder().body(Buffer().write(png)).build()) }
            val source = HttpReaderMaterializeAdapterIntegrationTest.TestHttpSource(server, client)
            val chapter = ReaderChapter(Chapter.create().copy(id = 7, mangaId = 1))
            val page = ReaderPage(0, imageUrl = server.url("/same-image").toString()).apply {
                this.chapter = chapter
            }
            chapter.publishLoadedPageListForTest(listOf(page))
            val loader = HttpPageLoader(chapter, source, cache, dispatcher = Dispatchers.IO)
            try {
                loader.onPageSelected(page)
                assertTrue("Original cache write must enter real HTTP body", entered.await(5, TimeUnit.SECONDS))
                val diskCache = ChapterCache::class.java.getDeclaredField("diskCache").run {
                    isAccessible = true
                    get(cache)
                }
                val key = cache.getImageFile(checkNotNull(page.imageUrl)).nameWithoutExtension
                val originalEditor = synchronized(diskCache) {
                    val entries = diskCache.javaClass.getDeclaredField("lruEntries").run {
                        isAccessible = true
                        get(diskCache) as Map<*, *>
                    }
                    val entry = checkNotNull(entries[key])
                    entry.javaClass.getDeclaredField("currentEditor").run {
                        isAccessible = true
                        get(entry)
                    }
                }
                assertNotNull("Held original response must own the actual same-key cache editor", originalEditor)

                loader.onPageSelected(page)
                withTimeoutOrNull(3_000) {
                    while (page.status !is Page.State.Error && replacementClosed.count != 0L) delay(10)
                }
                val failure = (page.status as? Page.State.Error)?.error
                println(
                    "same-key replacement status=${page.status} editor=$originalEditor " +
                        "responses=${responses.get()} replacementReads=${replacementReads.get()} failure=$failure",
                )
                failure?.let { println(it.stackTraceToString()) }
                assertFalse("An occupied same-key editor is not a storage failure", page.status is Page.State.Error)
                release.countDown()
                withTimeout(5_000) {
                    while (page.status != Page.State.Ready && page.status !is Page.State.Error) delay(10)
                }
                (page.status as? Page.State.Error)?.error?.let { println(it.stackTraceToString()) }
                assertEquals(
                    "Current generation must become Ready after original IO ends",
                    Page.State.Ready,
                    page.status,
                )
                assertArrayEquals(png, cache.getImageFile(checkNotNull(page.imageUrl)).readBytes())
                assertNotNull(page.encodedPageRef)
            } finally {
                release.countDown()
                withTimeout(5_000) {
                    while (loader.hasForegroundWork) delay(10)
                }
                loader.recycle()
                cache.close()
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
                directory.deleteRecursively()
            }
        }
    }

    @Test
    fun `download directory and archive loader streams publish dimensions without native holders`() = runTest {
        val directory = File.createTempFile("reader-fact-routes", ".tmp").apply {
            delete()
            mkdir()
        }
        val previousInjekt = uy.kohesive.injekt.Injekt
        uy.kohesive.injekt.Injekt =
            uy.kohesive.injekt.api.InjektScope(uy.kohesive.injekt.registry.default.DefaultRegistrar())
        uy.kohesive.injekt.Injekt.addSingleton<android.app.Application>(
            org.robolectric.RuntimeEnvironment.getApplication(),
        )
        try {
            val files = listOf(File(directory, "01.png"), File(directory, "02.png"))
            files[0].writeBytes(png(30, 50))
            files[1].writeBytes(png(50, 30))
            val chapter = ReaderChapter(Chapter.create().copy(id = 7, mangaId = 1))
            val source = mockk<eu.kanade.tachiyomi.source.Source>()
            val manga = mockk<tachiyomi.domain.manga.model.Manga>()
            every { manga.title } returns "Fact route fixture"
            val provider = mockk<eu.kanade.tachiyomi.data.download.DownloadProvider>()
            every {
                provider.findChapterDir(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } returns requireNotNull(UniFile.fromFile(directory))
            val manager = mockk<eu.kanade.tachiyomi.data.download.DownloadManager>()
            every { manager.buildPageList(any(), any(), any()) } returns files.mapIndexed { index, file ->
                Page(index).apply { uri = android.net.Uri.fromFile(file) }
            }
            val download = DownloadPageLoader(chapter, manga, source, manager, provider)
            val downloadPort = AndroidReaderChapterContentPort(chapter, download)
            downloadPort.loadChapterContent(ReaderChapterContentRequest(ReaderChapterId(7), 1))
            assertEquals(
                listOf(ReaderPageFactState.PORTRAIT, ReaderPageFactState.SPREAD),
                downloadPort.materializedPages.map { it.pageFact.value.state },
            )
            download.recycle()

            val archive = File(directory, "chapter.cbz")
            java.util.zip.ZipOutputStream(archive.outputStream()).use { output ->
                files.forEach { file ->
                    output.putNextEntry(java.util.zip.ZipEntry(file.name))
                    output.write(file.readBytes())
                    output.closeEntry()
                }
            }
            java.util.zip.ZipFile(archive).use { zip ->
                // Windows Robolectric has no Android libarchive JNI; only this native stream boundary is adapted.
                val reader = mockk<mihon.core.archive.ArchiveReader>(relaxed = true)
                every { reader.useEntries<List<ReaderPage>>(any()) } answers {
                    firstArg<(Sequence<mihon.core.archive.ArchiveEntry>) -> List<ReaderPage>>()(
                        zip.entries().asSequence().map {
                            mihon.core.archive.ArchiveEntry(
                                it.name,
                                isFile =
                                !it.isDirectory,
                            )
                        },
                    )
                }
                every { reader.getInputStream(any()) } answers { zip.getInputStream(zip.getEntry(firstArg<String>())) }
                val archiveLoader = ArchivePageLoader(reader)
                val archivePort = AndroidReaderChapterContentPort(chapter, archiveLoader)
                archivePort.loadChapterContent(ReaderChapterContentRequest(ReaderChapterId(7), 1))
                assertEquals(
                    listOf(ReaderPageFactState.PORTRAIT, ReaderPageFactState.SPREAD),
                    archivePort.materializedPages.map { it.pageFact.value.state },
                )
                archiveLoader.recycle()
            }
        } finally {
            uy.kohesive.injekt.Injekt = previousInjekt
            directory.listFiles()?.forEach { it.delete() }
            directory.delete()
        }
    }

    @Test
    fun `cancelling an optional HTTP consumer cannot cancel the existing visible scheduler request`() = runTest {
        val server = MockWebServer().apply { start() }
        val encodedFile = File.createTempFile("reader-fact-consumer", ".png")
        var loader: HttpPageLoader? = null
        try {
            server.enqueue(MockResponse(body = "page:/resolve"))
            server.enqueue(MockResponse(body = server.url("/image").toString()))
            server.enqueue(
                MockResponse.Builder().body(Buffer().write(png(30, 50)))
                    .bodyDelay(500, java.util.concurrent.TimeUnit.MILLISECONDS).build(),
            )
            val cache = mockk<ChapterCache>(relaxed = true)
            every { cache.getPageListFromCache(any()) } throws IOException("cold cache")
            every { cache.isImageInCache(any()) } answers { encodedFile.length() > 0 }
            every { cache.getImageFile(any()) } returns encodedFile
            every { cache.putImageToCache(any(), any()) } answers {
                secondArg<okhttp3.Response>().use { response ->
                    encodedFile.outputStream().use { output -> response.body.byteStream().use { it.copyTo(output) } }
                }
                true
            }
            val chapter = ReaderChapter(Chapter.create().copy(id = 7, mangaId = 1, url = "/chapter"))
            val httpLoader = HttpPageLoader(
                chapter,
                HttpReaderMaterializeAdapterIntegrationTest.TestHttpSource(server),
                cache,
            )
                .also { loader = it }
            val port = AndroidReaderChapterContentPort(chapter, httpLoader)
            port.loadChapterContent(ReaderChapterContentRequest(ReaderChapterId(7), 1))
            val page = port.materializedPages.single()
            chapter.publishLoadedPageListForTest(listOf(page))
            httpLoader.onPageSelected(page)
            withContext(Dispatchers.IO) { repeat(3) { server.takeRequest() } }
            val pageId = mihon.domain.reader.session.ReaderPageId(ReaderChapterId(7), 0)
            val budget = mihon.domain.reader.ReaderDualPageFactBudget()
            budget.reserve(listOf(pageId), 0)
            val optional = async(Dispatchers.IO) {
                httpLoader.loadFactPage(page, mihon.domain.reader.ReaderDualPageFactReadPermit(budget, pageId) { 0 })
            }
            withContext(Dispatchers.IO) { delay(50) }
            org.junit.Assert.assertFalse(
                "The optional consumer must await the same active request",
                optional.isCompleted,
            )
            optional.cancelAndJoin()
            withContext(Dispatchers.IO) { withTimeout(5000) { page.statusFlow.first { it == Page.State.Ready } } }
            assertEquals(ReaderPageFactState.PORTRAIT, page.pageFact.value.state)
            assertEquals(3, server.requestCount)
        } finally {
            loader?.recycle()
            server.close()
            encodedFile.delete()
        }
    }

    @Test
    fun `extra real HTTP encoded reads stop at quota without blocking visible or cached reads`() = runTest {
        val server = MockWebServer().apply { start() }
        val encodedFile = File.createTempFile("reader-fact-read-quota", ".png")
        try {
            server.enqueue(MockResponse.Builder().body(Buffer().write(ByteArray(33 * 1024 * 1024))).build())
            val cache = mockk<ChapterCache>(relaxed = true)
            every { cache.isImageInCache(any()) } answers { encodedFile.length() > 0 }
            every { cache.getImageFile(any()) } returns encodedFile
            every { cache.putImageToCache(any(), any()) } answers {
                secondArg<okhttp3.Response>().use { response ->
                    encodedFile.outputStream().use { output -> response.body.byteStream().use { it.copyTo(output) } }
                }
                true
            }
            val source = HttpReaderMaterializeAdapterIntegrationTest.TestHttpSource(server)
            val pageId = mihon.domain.reader.session.ReaderPageId(ReaderChapterId(7), 0)
            val page = ReaderPage(0, imageUrl = server.url("/extra").toString())
            val budget = mihon.domain.reader.ReaderDualPageFactBudget()
            budget.reserve(listOf(pageId), 0)
            val permit = mihon.domain.reader.ReaderDualPageFactReadPermit(budget, pageId) { 0 }
            val request = mihon.domain.reader.materialize.ReaderPageFetchRequest(
                pageId,
                1,
                "",
                page.imageUrl,
                factReadPermit = permit,
            )
            var exhausted = false
            try {
                withContext(Dispatchers.IO) {
                    AndroidReaderPageFetchPort(page, source, cache).fetchEncodedPage(request)
                }
            } catch (_: CancellationException) {
                exhausted = true
            }
            org.junit.Assert.assertTrue(
                "An extra oversized encoded response must stop while it is being read",
                exhausted,
            )
            assertEquals(32L * 1024 * 1024, encodedFile.length())
            encodedFile.writeBytes(byteArrayOf())
            server.enqueue(MockResponse.Builder().body(Buffer().write(png(30, 50))).build())
            page.imageUrl = server.url("/visible").toString()
            val normal = request.copy(imageUrl = page.imageUrl, factReadPermit = null)
            val port = AndroidReaderPageFetchPort(page, source, cache)
            val encoded = withContext(Dispatchers.IO) { port.fetchEncodedPage(normal) }
            assertEquals(encoded, port.findEncodedPage(normal.copy(factReadPermit = permit)))
            assertEquals(listOf("/extra", "/visible"), List(2) { server.takeRequest().url.encodedPath })
        } finally {
            server.close()
            encodedFile.delete()
        }
    }

    @Test
    fun `malformed image metadata consumes at most a bounded header and closes its stream`() {
        val bytes = ByteArrayOutputStream().also { output ->
            DataOutputStream(output).use { data ->
                data.writeShort(0xffd8)
                repeat(24) {
                    data.writeShort(0xffe1)
                    data.writeShort(65534)
                    data.write(ByteArray(65532))
                }
                data.writeShort(0xffd9)
            }
        }.toByteArray()
        var consumed = 0L
        var closed = false
        val page = ReaderPage(0, stream = {
            object : FilterInputStream(bytes.inputStream()) {
                override fun read(): Int = `in`.read().also { if (it >= 0) consumed++ }
                override fun read(target: ByteArray, offset: Int, length: Int): Int =
                    `in`.read(target, offset, length).also { if (it > 0) consumed += it }
                override fun skip(count: Long): Long = `in`.skip(count).also { consumed += it }
                override fun close() {
                    closed = true
                    super.close()
                }
            }
        })
        page.publishHeaderFact()
        assertEquals(ReaderPageFactState.FAILED, page.pageFact.value.state)
        org.junit.Assert.assertTrue("Header reads must stay below 512 KiB, consumed=$consumed", consumed <= 512 * 1024)
        org.junit.Assert.assertTrue(closed)
    }

    @Test
    fun `late header of replaced content cannot publish dimensions for the new stream`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val portrait = png(30, 50)
        val wide = png(50, 30)
        val page = ReaderPage(0, stream = {
            entered.complete(Unit)
            release.await()
            portrait.inputStream()
        })
        val oldHeader = async(Dispatchers.IO) { page.publishHeaderFact() }
        try {
            entered.await()
            page.stream = { wide.inputStream() }
            release.countDown()
            oldHeader.await()
            assertEquals(ReaderPageFactState.UNKNOWN, page.pageFact.value.state)
            page.publishHeaderFact()
            assertEquals(ReaderPageFactState.SPREAD, page.pageFact.value.state)
        } finally {
            release.countDown()
            oldHeader.cancel()
        }
    }

    @Test
    fun `cancelled local header propagates cancellation without publishing a failed fact`() = runTest {
        val cancellation = CancellationException("disposed reader")
        val page = ReaderPage(0, stream = { throw cancellation }).apply { status = Page.State.Ready }
        val loader = object : PageLoader() {
            override var isLocal = true
            override suspend fun getPages() = listOf(page)
        }
        val chapter = ReaderChapter(Chapter.create().copy(id = 7, mangaId = 1))
        val port = AndroidReaderChapterContentPort(chapter, loader)
        try {
            port.loadChapterContent(ReaderChapterContentRequest(ReaderChapterId(7), 1))
            fail("cancelled metadata must not finish chapter materialization")
        } catch (error: CancellationException) {
            assertSame(cancellation, error)
        }
        assertEquals(ReaderPageFactState.UNKNOWN, page.pageFact.value.state)
    }

    @Test
    fun `real HTTP source resolves encoded content and publishes portrait facts before a holder exists`() = runTest {
        val server = MockWebServer()
        server.start()
        val encodedFile = File.createTempFile("reader-http-fact", ".png")
        var loader: HttpPageLoader? = null
        try {
            server.enqueue(MockResponse(body = "page:/resolve"))
            server.enqueue(MockResponse(body = server.url("/image").toString()))
            server.enqueue(MockResponse.Builder().body(Buffer().write(png(30, 50))).build())
            val cache = mockk<ChapterCache>(relaxed = true)
            every { cache.getPageListFromCache(any()) } throws IOException("cold cache")
            every { cache.isImageInCache(any()) } answers { encodedFile.length() > 0 }
            every { cache.getImageFile(any()) } returns encodedFile
            every { cache.putImageToCache(any(), any()) } answers {
                secondArg<okhttp3.Response>().use { response ->
                    encodedFile.outputStream().use { output -> response.body.byteStream().use { it.copyTo(output) } }
                }
                true
            }
            val chapter = ReaderChapter(Chapter.create().copy(id = 7, mangaId = 1, url = "/chapter"))
            val httpLoader = HttpPageLoader(
                chapter,
                HttpReaderMaterializeAdapterIntegrationTest.TestHttpSource(server),
                cache,
            ).also { loader = it }
            val port = AndroidReaderChapterContentPort(chapter, httpLoader)
            port.loadChapterContent(ReaderChapterContentRequest(ReaderChapterId(7), 1))
            val page = port.materializedPages.single()
            chapter.publishLoadedPageListForTest(listOf(page))
            httpLoader.onPageSelected(page)
            withContext(Dispatchers.IO) {
                withTimeout(5000) { page.statusFlow.first { it == Page.State.Ready } }
            }

            assertEquals(ReaderPageFactState.PORTRAIT, page.pageFact.value.state)
            assertEquals(30, page.pageFact.value.width)
            assertEquals(listOf("/chapter", "/resolve", "/image"), List(3) { server.takeRequest().url.encodedPath })
        } finally {
            loader?.recycle()
            server.close()
            encodedFile.delete()
        }
    }

    @Test
    fun `directory content publishes header facts before any native holder exists`() = runTest {
        val directory = File.createTempFile("reader-page-facts", ".tmp").apply {
            delete()
            mkdir()
        }
        try {
            writePng(File(directory, "01.png"), 30, 50)
            writePng(File(directory, "02.png"), 50, 30)
            val chapter = ReaderChapter(Chapter.create().copy(id = 7, mangaId = 1))
            val loader = DirectoryPageLoader(requireNotNull(UniFile.fromFile(directory)))
            val port = AndroidReaderChapterContentPort(chapter, loader)
            port.loadChapterContent(ReaderChapterContentRequest(ReaderChapterId(7), 1))

            assertEquals(
                listOf(ReaderPageFactState.PORTRAIT, ReaderPageFactState.SPREAD),
                port.materializedPages.map { it.pageFact.value.state },
            )
            assertEquals(listOf(30, 50), port.materializedPages.map { it.pageFact.value.width })
            loader.recycle()
        } finally {
            directory.listFiles()?.forEach { it.delete() }
            directory.delete()
        }
    }

    private fun writePng(file: File, width: Int, height: Int) {
        file.writeBytes(png(width, height))
    }

    private fun png(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            return ByteArrayOutputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                it.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }
}
