package mihon.desktop.download

import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import mihon.desktop.domain.DesktopSystemNotifier
import mihon.desktop.extension.ExtensionClassLoader
import mihon.domain.download.DownloadQueueEntry
import mihon.domain.reader.content.DownloadChapterIdentity
import mihon.domain.reader.partial.PartialPageTableEntry
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.concurrent.thread

/** RED — DesktopDownloadManager does not exist yet. */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadManagerTest {

    @TempDir
    lateinit var tempDir: File

    private fun manager() = DesktopDownloadManager(
        provider = DesktopDownloadProvider(baseDir = tempDir),
    )

    @Test
    fun `migration of an originally completed artifact rejects a same bytes later completed generation`() =
        runBlocking {
            val server = mockwebserver3.MockWebServer()
            repeat(2) {
                server.enqueue(mockwebserver3.MockResponse.Builder().body(okio.Buffer().write(jpegBytes())).build())
            }
            server.start()
            val provider = DesktopDownloadProvider(File(tempDir, "migration-generation"))
            val manager = DesktopDownloadManager(provider, networkHelper = NetworkHelper(OkHttpClient()))
            val item = DownloadItem(
                42,
                "Work",
                "One",
                410,
                mangaId = 10,
                pageUrls = listOf(server.url("/same.jpg").toString()),
            )
            try {
                assertTrue(manager.enqueue(item))
                manager.start()
                withTimeout(5_000) { manager.queue.first { it.isEmpty() } }
                assertTrue(provider.isChapterDownloaded(42, "Work", "One"))
                val accepted = manager.captureMigrationGenerations(setOf(item.chapterId))
                assertTrue(provider.deleteChapterDownload(42, "Work", "One"))
                assertTrue(manager.enqueue(item))
                withTimeout(5_000) {
                    manager.queue.first { it.isEmpty() }
                    while (!provider.isChapterDownloaded(42, "Work", "One")) delay(10)
                }
                val original = provider.chapterDownloadDir(42, "Work", "One")
                org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) {
                    runBlocking {
                        manager.withMigrationArtifacts(setOf(item.chapterId), listOf(original), accepted) { }
                    }
                }
                assertTrue(provider.isChapterDownloaded(42, "Work", "One"))
                assertEquals(2, server.requestCount)
            } finally {
                manager.stopAndJoin()
                server.close()
            }
        }

    private fun manager(provider: DesktopDownloadProvider, scope: TestScope) = DesktopDownloadManager(
        provider = provider,
        networkHelper = NetworkHelper(OkHttpClient()),
        workerScope = scope,
    )

    private fun jpegBytes() = byteArrayOf(
        0xFF.toByte(),
        0xD8.toByte(),
        0xFF.toByte(),
        0xD9.toByte(),
    )

    @Test
    fun `captured retirement drains original producer and retries rejected generation without cancelling new work`() =
        runBlocking {
            val server = mockwebserver3.MockWebServer()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val requestCount = AtomicInteger()
            server.dispatcher = object : mockwebserver3.Dispatcher() {
                override fun dispatch(request: mockwebserver3.RecordedRequest): mockwebserver3.MockResponse {
                    requestCount.incrementAndGet()
                    entered.complete(Unit)
                    runBlocking { release.await() }
                    return mockwebserver3.MockResponse.Builder().body(okio.Buffer().write(jpegBytes())).build()
                }
            }
            server.start()
            val driver = app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver(
                "jdbc:sqlite:${File(tempDir, "captured.sqlite").absolutePath}",
            )
            tachiyomi.data.Database.Schema.create(driver)
            val database = tachiyomi.data.Database(
                driver,
                historyAdapter = tachiyomi.data.History.Adapter(tachiyomi.data.DateColumnAdapter),
                mangasAdapter = tachiyomi.data.Mangas.Adapter(
                    tachiyomi.data.StringListColumnAdapter,
                    tachiyomi.data.UpdateStrategyColumnAdapter,
                ),
            )
            val store = tachiyomi.data.download.PersistentDownloadStore(database)
            var rejectSecond = false
            val provider = DesktopDownloadProvider(tempDir.resolve("captured-files"))
            val manager = DesktopDownloadManager(
                provider,
                networkHelper = NetworkHelper(OkHttpClient()),
                store = store,
                queuePersister = { entries ->
                    if (rejectSecond &&
                        entries.none { it.chapterId == 2L }
                    ) {
                        throw java.io.IOException("second retirement rejected")
                    }
                    store.replaceAll(entries)
                },
            )
            try {
                val first = DownloadItem(
                    42,
                    "Captured",
                    "First",
                    1,
                    mangaId = 100,
                    pageUrls = listOf(server.url("/first.jpg").toString()),
                )
                val second = first.copy(
                    chapterId = 2,
                    chapterName = "Second",
                    pageUrls = listOf(server.url("/second.jpg").toString()),
                )
                assertTrue(manager.enqueue(first))
                assertTrue(manager.enqueue(second))
                manager.start()
                withTimeout(5_000) { entered.await() }
                manager.pauseAll()
                val captured = manager.captureDownloadAttempts(listOf(1, 2))
                rejectSecond = true
                val pending = async(Dispatchers.Default) { manager.cancelCapturedDownloadAttempts(captured) }
                withTimeout(5_000) { while (manager.queue.value.any { it.chapterId == 1L }) delay(10) }
                assertFalse(pending.isCompleted, "the accepted first retirement still drains its real HTTP producer")
                release.complete(Unit)
                val failed = withTimeout(5_000) { pending.await() }
                assertFalse(
                    provider.isChapterDownloaded(first.sourceId, first.mangaTitle, first.chapterName),
                    "the original cancelled HTTP producer has drained without publishing its chapter",
                )
                assertTrue(manager.enqueue(first), "the first completed retirement permits a later accepted generation")
                rejectSecond = false
                val retryFailed = withTimeout(5_000) {
                    manager.cancelCapturedDownloadAttempts(captured.filter { it.item.chapterId in failed })
                }
                org.junit.jupiter.api.Assertions.assertAll("finite captured retirement", {
                    assertEquals(setOf(2L), failed, "only the actual refused target remains pending")
                }, {
                    assertTrue(retryFailed.isEmpty())
                    assertEquals(
                        listOf(1L),
                        manager.queue.value.map { it.chapterId },
                        "retry must preserve the completed target's new accepted generation",
                    )
                    assertEquals(listOf(1L), store.entries().map { it.chapterId })
                })
                // Await the worker's next scheduling decision, rather than confusing a later
                // accepted producer with the original cancelled producer.
                withTimeout(5_000) {
                    while (manager.queue.value.any { it.status == DownloadStatus.DOWNLOADING }) delay(10)
                }
                withTimeoutOrNull(500) { while (requestCount.get() == 1) delay(10) }
                assertEquals(1, requestCount.get(), "pause must prevent the next producer after the active one drains")
                assertFalse(provider.isChapterDownloaded(first.sourceId, first.mangaTitle, first.chapterName))
            } finally {
                release.complete(Unit)
                manager.stopAndJoin()
                server.close()
                driver.close()
            }
        }

    @Test
    fun `captured original handle rejects an already completed replacement and preserves its real files`(): Unit =
        runBlocking {
            val server = mockwebserver3.MockWebServer()
            server.enqueue(mockwebserver3.MockResponse.Builder().body(okio.Buffer().write(jpegBytes())).build())
            server.enqueue(mockwebserver3.MockResponse.Builder().body(okio.Buffer().write(jpegBytes())).build())
            server.start()
            val provider = DesktopDownloadProvider(tempDir.resolve("completed-replacement"))
            val manager = DesktopDownloadManager(provider, networkHelper = NetworkHelper(OkHttpClient()))
            val item = DownloadItem(
                42,
                "Completed",
                "One",
                401,
                mangaId = 100,
                pageUrls = listOf(server.url("/replacement.jpg").toString()),
            )
            try {
                assertTrue(manager.enqueue(item))
                val captured = manager.captureDownloadAttempts(listOf(item.chapterId))
                manager.start()
                withTimeout(5_000) { manager.queue.first { it.isEmpty() } }
                assertTrue(provider.isChapterDownloaded(item.sourceId, item.mangaTitle, item.chapterName))
                assertTrue(provider.deleteChapterDownload(item.sourceId, item.mangaTitle, item.chapterName))
                assertTrue(manager.enqueue(item))
                withTimeout(5_000) {
                    manager.queue.first { it.isEmpty() }
                    while (!provider.isChapterDownloaded(item.sourceId, item.mangaTitle, item.chapterName)) delay(10)
                }
                assertEquals(
                    setOf(item.chapterId),
                    manager.cancelCapturedDownloadAttempts(captured),
                    "an empty queue after a replacement completes cannot prove the original generation " +
                        "owns these files",
                )
                assertTrue(provider.isChapterDownloaded(item.sourceId, item.mangaTitle, item.chapterName))
            } finally {
                manager.stopAndJoin()
                server.close()
            }
        }

    @Test
    fun `captured failed retirement reserves the existing preflight throughout retry cleanup`(): Unit = runBlocking {
        val provider = DesktopDownloadProvider(tempDir.resolve("captured-retry-reservation"))
        val item = DownloadItem(42, "Retry", "One", 301, mangaId = 100)
        val artifact = provider.chapterTmpDir(item.sourceId, item.mangaTitle, item.chapterName)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var retry = false
        val manager = DesktopDownloadManager(provider, artifactCleaner = { file ->
            if (!retry) {
                false
            } else {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                file.deleteRecursively()
            }
        })
        try {
            assertTrue(manager.enqueue(item))
            artifact.mkdirs()
            File(artifact, "old.tmp").writeBytes(jpegBytes())
            val captured = manager.captureDownloadAttempts(listOf(item.chapterId))
            assertEquals(setOf(item.chapterId), manager.cancelCapturedDownloadAttempts(captured))
            retry = true
            val completion = async(Dispatchers.Default) { manager.cancelCapturedDownloadAttempts(captured) }
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            assertFalse(
                manager.enqueue(item),
                "a replacement cannot be accepted between retry validation and file cleanup",
            )
            release.countDown()
            assertTrue(withTimeout(5_000) { completion.await() }.isEmpty())
            assertTrue(manager.enqueue(item), "the same existing reservation is released after cleanup")
            assertEquals(listOf(item.chapterId), manager.queue.value.map { it.chapterId })
        } finally {
            release.countDown()
            manager.stopAndJoin()
        }
    }

    @Test
    fun `manual priority write failure preserves pause and restart order without moving active identity`() {
        val driver = app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver(
            "jdbc:sqlite:${File(tempDir, "priority.sqlite").absolutePath}",
        )
        tachiyomi.data.Database.Schema.create(driver)
        val database = tachiyomi.data.Database(
            driver,
            historyAdapter = tachiyomi.data.History.Adapter(tachiyomi.data.DateColumnAdapter),
            mangasAdapter = tachiyomi.data.Mangas.Adapter(
                tachiyomi.data.StringListColumnAdapter,
                tachiyomi.data.UpdateStrategyColumnAdapter,
            ),
        )
        val store = tachiyomi.data.download.PersistentDownloadStore(database)
        var rejectAfterWrite = false
        val manager = DesktopDownloadManager(
            DesktopDownloadProvider(tempDir.resolve("priority-files")),
            store = store,
            queuePersister = { entries ->
                store.replaceAll(entries)
                if (rejectAfterWrite) {
                    rejectAfterWrite = false
                    throw java.io.IOException("priority commit acknowledgement failed")
                }
            },
        )
        try {
            val first = DownloadItem(42, "Manual", "Active", 1, mangaId = 100)
            val second = first.copy(chapterId = 2, chapterName = "Next")
            val third = first.copy(chapterId = 3, chapterName = "Priority")
            listOf(first, second, third).forEach { assertTrue(manager.enqueue(it)) }
            assertTrue(manager.transition(first.chapterId, mihon.domain.download.DownloadQueueStatus.DOWNLOADING))
            val active = manager.queue.value.first()
            manager.pauseAll()
            rejectAfterWrite = true
            org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException::class.java) {
                manager.startDownloadNow(third.chapterId)
            }
            assertEquals(listOf(1L, 2L, 3L), manager.queue.value.map { it.chapterId })
            assertEquals(
                listOf(1L, 2L, 3L),
                store.entries().map { it.chapterId },
                "a rejected priority request cannot reappear as accepted after restart",
            )
            assertTrue(manager.isPaused.value)
            assertTrue(manager.startDownloadNow(third.chapterId))
            assertEquals(listOf(1L, 3L, 2L), manager.queue.value.map { it.chapterId })
            assertSame(active, manager.queue.value.first())
            assertFalse(manager.isPaused.value)
            assertEquals(listOf(1L, 3L, 2L), store.entries().map { it.chapterId })
        } finally {
            driver.close()
        }
    }

    @Test
    fun `manual enqueue reports only actually accepted entries and leaves no duplicate acceptance`() {
        val manager = manager()
        val item = DownloadItem(sourceId = 42, mangaTitle = "Manual", chapterName = "One", chapterId = 991)
        assertEquals(true, manager.enqueue(item))
        assertEquals(false, manager.enqueue(item))
        assertEquals(listOf(item), manager.queue.value)
    }

    @Test
    fun `manual enqueue rejected persistent writes never publish or recover ghost work`() {
        for (afterWrite in listOf(false, true)) {
            val file = File(tempDir, "enqueue-$afterWrite.sqlite")
            val driver = app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
            tachiyomi.data.Database.Schema.create(driver)
            val database = tachiyomi.data.Database(
                driver,
                historyAdapter = tachiyomi.data.History.Adapter(tachiyomi.data.DateColumnAdapter),
                mangasAdapter = tachiyomi.data.Mangas.Adapter(
                    tachiyomi.data.StringListColumnAdapter,
                    tachiyomi.data.UpdateStrategyColumnAdapter,
                ),
            )
            val store = tachiyomi.data.download.PersistentDownloadStore(database)
            var reject = true
            val manager = DesktopDownloadManager(
                provider = DesktopDownloadProvider(File(tempDir, "files-$afterWrite")),
                store = store,
                queuePersister = { entries ->
                    if (reject && entries.isNotEmpty()) {
                        reject = false
                        if (afterWrite) store.replaceAll(entries)
                        throw java.io.IOException("queue persistence rejected")
                    }
                    store.replaceAll(entries)
                },
            )
            try {
                val item = DownloadItem(sourceId = 42, mangaTitle = "Manual", chapterName = "One", chapterId = 992)
                org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException::class.java) { manager.enqueue(item) }
                assertTrue(manager.queue.value.isEmpty(), "an unaccepted write never emits a runnable generation")
                assertTrue(store.entries().isEmpty(), "restart cannot resurrect a rejected enqueue")
                assertEquals(true, manager.enqueue(item))
                assertEquals(listOf(item.chapterId), store.entries().map { it.chapterId })
            } finally {
                driver.close()
            }
        }
    }

    @Test
    fun `initial queue is empty`() = runTest {
        assertEquals(emptyList<DownloadItem>(), manager().queue.first())
    }

    @Test
    fun `enqueue adds item to queue`() = runTest {
        val mgr = manager()
        mgr.enqueue(
            DownloadItem(
                sourceId = 1L,
                mangaTitle = "Test",
                chapterName = "Ch 1",
                chapterId = 10L,
                pageUrls = listOf("https://example.com/1.jpg"),
            ),
        )
        assertEquals(1, mgr.queue.first().size)
        assertEquals("Ch 1", mgr.queue.first()[0].chapterName)
    }

    @Test
    fun `enqueue deduplicates by chapterId`() = runTest {
        val mgr = manager()
        val item = DownloadItem(
            sourceId = 1L,
            mangaTitle = "Test",
            chapterName = "Ch 1",
            chapterId = 10L,
            pageUrls = listOf("https://example.com/1.jpg"),
        )
        mgr.enqueue(item)
        mgr.enqueue(item)
        assertEquals(1, mgr.queue.first().size)
    }

    @Test
    fun `enqueue filesystem preflight never holds the queue state lock`() = runBlocking {
        val probeEntered = CountDownLatch(1)
        val releaseProbe = CountDownLatch(1)
        val ioEvents = CopyOnWriteArrayList<DownloadIoEvent>()
        val manager = DesktopDownloadManager(
            provider = DesktopDownloadProvider(tempDir.resolve("enqueue-preflight")),
            workerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            enqueueFileOperations = object : DownloadEnqueueFileOperations {
                override fun isChapterDownloaded(provider: DesktopDownloadProvider, item: DownloadItem): Boolean {
                    probeEntered.countDown()
                    check(releaseProbe.await(5, TimeUnit.SECONDS))
                    return false
                }

                override fun cleanupTemporaryDirectory(provider: DesktopDownloadProvider, item: DownloadItem) = Unit
            },
            ioProbe = DownloadIoProbe { event -> ioEvents += event },
        )
        val item = DownloadItem(
            sourceId = 1L,
            mangaTitle = "Preflight Manga",
            chapterName = "Preflight Chapter",
            chapterId = 11L,
            pageUrls = listOf("https://example.invalid/page.jpg"),
        )
        val enqueuing = async(Dispatchers.IO) { manager.enqueue(item) }
        try {
            assertTrue(probeEntered.await(5, TimeUnit.SECONDS))
            val whileBlocked = withTimeout(1_000) { withContext(Dispatchers.Default) { manager.recover() } }
            assertTrue(whileBlocked.isEmpty())
            releaseProbe.countDown()
            enqueuing.await()

            assertEquals(listOf(item.chapterId), manager.queue.value.map(DownloadItem::chapterId))
            assertEquals(
                listOf(DownloadIoOperation.ENQUEUE_DOWNLOADED_PROBE, DownloadIoOperation.ENQUEUE_TMP_CLEANUP),
                ioEvents.map(DownloadIoEvent::operation),
            )
            assertTrue(ioEvents.all { !it.locks.queueStateLocked })
        } finally {
            releaseProbe.countDown()
            enqueuing.cancelAndJoin()
            manager.stopAndJoin()
        }
    }

    @Test
    fun `cancel removes item from queue`() = runTest {
        val mgr = manager()
        mgr.enqueue(
            DownloadItem(
                sourceId = 1L,
                mangaTitle = "Test",
                chapterName = "Ch 1",
                chapterId = 10L,
                pageUrls = listOf("https://example.com/1.jpg"),
            ),
        )
        mgr.cancel(chapterId = 10L)
        assertTrue(mgr.queue.first().isEmpty())
    }

    @Test
    fun `download enters error when server returns html instead of image`() = runTest {
        val port = ServerSocket(0).use { it.localPort }
        val server = embeddedServer(Netty, port = port) {
            routing {
                get("/001.jpg") {
                    call.respondText(
                        text = "<html>forbidden</html>",
                        contentType = ContentType.Text.Html,
                        status = HttpStatusCode.Forbidden,
                    )
                }
            }
        }.start(wait = false)
        val provider = DesktopDownloadProvider(baseDir = tempDir)
        val mgr = manager(provider, this)
        val workerJob = mgr.start()

        try {
            mgr.enqueue(
                DownloadItem(
                    sourceId = 1L,
                    mangaTitle = "Test",
                    chapterName = "Ch 1",
                    chapterId = 10L,
                    pageUrls = listOf("http://localhost:$port/001.jpg"),
                ),
            )

            advanceUntilIdle()

            assertEquals(DownloadStatus.ERROR, mgr.queue.value.single().status)
            assertFalse(provider.isChapterDownloaded(1L, "Test", "Ch 1"))
            assertTrue(provider.getDownloadedPages(1L, "Test", "Ch 1").isEmpty())
        } finally {
            workerJob.cancel()
            server.stop(gracePeriodMillis = 0, timeoutMillis = 500)
        }
    }

    @Test
    fun `download stores readable image when server returns jpeg`() = runTest {
        val port = ServerSocket(0).use { it.localPort }
        val server = embeddedServer(Netty, port = port) {
            routing {
                get("/001.jpg") {
                    call.respondBytes(
                        bytes = jpegBytes(),
                        contentType = ContentType.Image.JPEG,
                        status = HttpStatusCode.OK,
                    )
                }
            }
        }.start(wait = false)
        val provider = DesktopDownloadProvider(baseDir = tempDir)
        val mgr = manager(provider, this)
        val workerJob = mgr.start()

        try {
            mgr.enqueue(
                DownloadItem(
                    sourceId = 1L,
                    mangaTitle = "Test",
                    chapterName = "Ch 1",
                    chapterId = 10L,
                    pageUrls = listOf("http://localhost:$port/001.jpg"),
                ),
            )

            advanceUntilIdle()

            assertTrue(mgr.queue.value.isEmpty())
            assertTrue(provider.isChapterDownloaded(1L, "Test", "Ch 1"))
            assertEquals(1, provider.getDownloadedPages(1L, "Test", "Ch 1").size)
        } finally {
            workerJob.cancel()
            server.stop(gracePeriodMillis = 0, timeoutMillis = 500)
        }
    }

    @Test
    fun `page downloads use the source scoped client instead of the global client`() = runTest {
        val provider = DesktopDownloadProvider(baseDir = tempDir)
        val globalClient = OkHttpClient()
        val sourceClient = OkHttpClient()
        var observedClient: OkHttpClient? = null
        val manager = DesktopDownloadManager(
            provider = provider,
            networkHelper = NetworkHelper(globalClient) { sourceId ->
                if (sourceId == 42L) sourceClient else globalClient
            },
            workerScope = this,
            fileOperations = object : DownloadFileOperations by DefaultDownloadFileOperations {
                override fun execute(client: OkHttpClient, url: String): Response {
                    observedClient = client
                    return Response.Builder()
                        .request(Request.Builder().url(url).build())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(jpegBytes().toResponseBody())
                        .build()
                }
            },
        )
        val worker = manager.start()
        try {
            manager.enqueue(
                DownloadItem(
                    sourceId = 42L,
                    mangaTitle = "Scoped",
                    chapterName = "Chapter",
                    chapterId = 420L,
                    pageUrls = listOf("https://fixture.invalid/001.jpg"),
                ),
            )

            advanceUntilIdle()

            assertSame(sourceClient, observedClient)
            assertTrue(manager.queue.value.isEmpty())
        } finally {
            worker.cancel()
        }
    }

    @Test
    fun `manager preserves source page table lazily resolves only missing image url and publishes indexed pages`() =
        runBlocking {
            val provider = DesktopDownloadProvider(tempDir.resolve("versioned-page-table"))
            val identity = DownloadChapterIdentity(
                sourceDisplayName = "Versioned Source",
                mangaTitle = "Versioned Manga",
                chapterName = "Versioned Chapter",
                scanlator = "Group",
                chapterUrl = "/chapter/versioned",
                disallowNonAsciiFilenames = false,
            )
            val source = mockk<HttpSource>()
            val sourcePages = listOf(
                Page(4, "/page/first", "https://img.test/first.jpg"),
                Page(19, "/page/middle", null),
                Page(41, "/page/last", "https://img.test/last.jpg"),
            )
            coEvery { source.getPageList(any()) } returns sourcePages
            coEvery { source.getImageUrl(match { it.index == 19 }) } returns "https://img.test/middle.png"
            val executedUrls = CopyOnWriteArrayList<String>()
            val chapterRenameEntered = CountDownLatch(1)
            val releaseChapterRename = CountDownLatch(1)
            val ioEvents = CopyOnWriteArrayList<DownloadIoEvent>()
            val persistedQueues = CopyOnWriteArrayList<List<DownloadQueueEntry>>()
            val workerParent = SupervisorJob()
            val manager = DesktopDownloadManager(
                provider = provider,
                networkHelper = NetworkHelper(OkHttpClient()),
                workerScope = CoroutineScope(workerParent + Dispatchers.IO),
                sourceResolver = { source },
                downloadIdentityResolver = { identity },
                ioProbe = DownloadIoProbe { event -> ioEvents += event },
                queuePersister = { entries -> persistedQueues += entries },
                fileOperations = object : DownloadFileOperations by DefaultDownloadFileOperations {
                    override fun execute(client: OkHttpClient, url: String): Response {
                        executedUrls += url
                        return Response.Builder()
                            .request(Request.Builder().url(url).build())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(jpegBytes().toResponseBody())
                            .build()
                    }

                    override fun renameChapter(tmpDir: File, finalDir: File): Boolean {
                        chapterRenameEntered.countDown()
                        check(releaseChapterRename.await(5, TimeUnit.SECONDS))
                        return DefaultDownloadFileOperations.renameChapter(tmpDir, finalDir)
                    }
                },
            )
            manager.enqueue(
                DownloadItem(
                    sourceId = 42L,
                    mangaTitle = identity.mangaTitle,
                    chapterName = identity.chapterName,
                    chapterId = 4_200L,
                    chapterUrl = identity.chapterUrl,
                ),
            )
            manager.start()
            try {
                assertTrue(chapterRenameEntered.await(5, TimeUnit.SECONDS))

                val queued = manager.queue.value.single()
                val persisted = persistedQueues.last().single()
                val snapshot = checkNotNull(manager.snapshot(queued.chapterId, identity))
                assertEquals(listOf(0, 1, 2), queued.pageTable.entries.map(PartialPageTableEntry::readerOrdinal))
                assertEquals(listOf(4, 19, 41), queued.pageTable.entries.map(PartialPageTableEntry::sourcePageIndex))
                assertEquals(
                    listOf("/page/first", "/page/middle", "/page/last"),
                    queued.pageTable.entries.map(PartialPageTableEntry::pageUrl),
                )
                assertEquals(3, snapshot.pageTable.totalPageCount)
                assertEquals(queued.pageTable, persisted.pageTable)
                assertEquals(identity, persisted.downloadIdentity)
                assertEquals(
                    listOf("https://img.test/first.jpg", null, "https://img.test/last.jpg"),
                    persisted.pageTable.entries.map(PartialPageTableEntry::imageUrl),
                )
                assertEquals(listOf(0, 1, 2), snapshot.committedPages.map { it.readerOrdinal })
                val middleCandidate = checkNotNull(
                    manager.committedPageCandidate(
                        chapterId = queued.chapterId,
                        identity = identity,
                        readerOrdinal = 1,
                        sourcePageIndex = 19,
                    ),
                )
                assertEquals(snapshot.attemptGeneration, middleCandidate.attemptGeneration)
                assertEquals(1, middleCandidate.readerOrdinal)
                assertEquals(19, middleCandidate.sourcePageIndex)
                assertEquals(snapshot.committedPages[1].opaqueLocation, middleCandidate.opaqueLocation)
                assertEquals(snapshot.committedPages[1].committedRevision, middleCandidate.committedRevision)
                assertNull(
                    manager.committedPageCandidate(
                        chapterId = queued.chapterId,
                        identity = identity,
                        readerOrdinal = 1,
                        sourcePageIndex = 41,
                    ),
                )
                assertEquals(
                    snapshot.committedPages.map { it.committedRevision }.sorted(),
                    snapshot.committedPages.map { it.committedRevision },
                )
                assertTrue(
                    snapshot.committedPages.zipWithNext().all { (first, second) ->
                        first.committedRevision <
                            second.committedRevision
                    },
                )
                assertEquals(
                    listOf("001.jpg", "002.png", "003.jpg"),
                    provider.canonicalChapterTmpDir(identity).listFiles().orEmpty().map(File::getName).sorted(),
                )
                assertFalse(provider.isChapterDownloaded(42L, identity))
                assertEquals(
                    listOf("https://img.test/first.jpg", "https://img.test/middle.png", "https://img.test/last.jpg"),
                    executedUrls,
                )
                coVerify(exactly = 1) { source.getPageList(any()) }
                coVerify(exactly = 1) { source.getImageUrl(any()) }
                assertTrue(ioEvents.isNotEmpty())
                assertEquals(1, ioEvents.count { it.operation == DownloadIoOperation.INDEX_DIRECTORY_LIST })
                assertEquals(3, ioEvents.count { it.operation == DownloadIoOperation.PAGE_HEADER_PROBE })
                assertTrue(
                    ioEvents.all { event ->
                        !event.locks.queueStateLocked && !event.locks.indexLocked && !event.locks.lifecycleLocked
                    },
                )
                assertTrue(
                    setOf(
                        DownloadIoOperation.SOURCE_PAGE_LIST,
                        DownloadIoOperation.SOURCE_IMAGE_URL,
                        DownloadIoOperation.NETWORK_REQUEST,
                        DownloadIoOperation.BODY_READ,
                        DownloadIoOperation.PAGE_WRITE,
                        DownloadIoOperation.PAGE_HEADER_PROBE,
                        DownloadIoOperation.PAGE_MOVE,
                        DownloadIoOperation.CHAPTER_MOVE,
                    ).all { expected -> ioEvents.any { it.operation == expected } },
                )
            } finally {
                releaseChapterRename.countDown()
                withTimeout(5_000) { manager.stopAndJoin() }
                withTimeout(5_000) { workerParent.cancelAndJoin() }
            }
        }

    @Test
    fun `manager reflectively resolves a missing image url from a child loaded source`() = runBlocking {
        val fixtureClassName = "readerfixture.ReflectiveImageSource"
        val classResource = fixtureClassName.replace('.', '/') + ".class"
        val extensionJar = tempDir.resolve("reflective-download-source.jar")
        JarOutputStream(extensionJar.outputStream()).use { output ->
            output.putNextEntry(JarEntry(classResource))
            requireNotNull(javaClass.classLoader.getResourceAsStream(classResource)).use { it.copyTo(output) }
            output.closeEntry()
        }

        ExtensionClassLoader(extensionJar.toURI().toURL(), javaClass.classLoader).use { classLoader ->
            val source = classLoader.loadClass(
                fixtureClassName,
            ).getDeclaredConstructor().newInstance() as CatalogueSource
            assertSame(classLoader, source.javaClass.classLoader)
            assertFalse(source is HttpSource)
            val executedUrls = CopyOnWriteArrayList<String>()
            val workerParent = SupervisorJob()
            val manager = DesktopDownloadManager(
                provider = DesktopDownloadProvider(tempDir.resolve("child-loader-downloads")),
                networkHelper = NetworkHelper(OkHttpClient()),
                workerScope = CoroutineScope(workerParent + Dispatchers.IO),
                sourceResolver = { source },
                fileOperations = object : DownloadFileOperations by DefaultDownloadFileOperations {
                    override fun execute(client: OkHttpClient, url: String): Response {
                        executedUrls += url
                        return Response.Builder()
                            .request(Request.Builder().url(url).build())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(jpegBytes().toResponseBody())
                            .build()
                    }
                },
            )
            manager.enqueue(
                DownloadItem(
                    sourceId = source.id,
                    mangaTitle = "Child Loader Manga",
                    chapterName = "Child Loader Chapter",
                    chapterId = 9_901L,
                    chapterUrl = "/child-loader/chapter",
                ),
            )
            val worker = manager.start()
            try {
                withTimeout(5_000) {
                    while (manager.queue.value.singleOrNull()?.status !in setOf(null, DownloadStatus.ERROR)) delay(10)
                }

                assertTrue(manager.queue.value.isEmpty())
                assertEquals(listOf("https://child.invalid/resolved.jpg"), executedUrls)
            } finally {
                worker.cancelAndJoin()
                manager.stopAndJoin()
                workerParent.cancelAndJoin()
            }
        }
    }

    @Test
    fun `default page publication replaces an existing destination`() {
        val staging = tempDir.resolve("atomic-page.tmp").apply { writeText("replacement") }
        val destination = tempDir.resolve("001.jpg").apply { writeText("stale") }

        DefaultDownloadFileOperations.renamePage(staging, destination)

        assertFalse(staging.exists())
        assertEquals("replacement", destination.readText())
    }

    @Test
    fun `isDownloaded reflects provider state`() {
        val mgr = manager()
        assertFalse(mgr.isDownloaded(sourceId = 1L, mangaTitle = "Test", chapterName = "Ch 1"))

        // Seed a fake download on disk
        val dir = DesktopDownloadProvider(tempDir).chapterDownloadDir(1L, "Test", "Ch 1")
        dir.mkdirs()
        File(dir, "001.jpg").writeBytes(jpegBytes())

        assertTrue(mgr.isDownloaded(sourceId = 1L, mangaTitle = "Test", chapterName = "Ch 1"))
    }

    @Test
    fun `download single-writes canonical cbz and reader filter delete share the same identity`() = runTest {
        val provider = DesktopDownloadProvider(tempDir.resolve("canonical-downloads"))
        val identity = DownloadChapterIdentity(
            sourceDisplayName = "Source 中文",
            mangaTitle = "Manga 中文",
            chapterName = "Chapter 1",
            scanlator = "Group",
            chapterUrl = "/chapter/1",
            disallowNonAsciiFilenames = false,
        )
        val preferences = DesktopDownloadPreferences(InMemoryPreferenceStore()).apply {
            downloadAsCbz.set(true)
        }
        val mgr = DesktopDownloadManager(
            provider = provider,
            networkHelper = NetworkHelper(OkHttpClient()),
            downloadPreferences = preferences,
            workerScope = this,
            downloadIdentityResolver = { identity },
            fileOperations = object : DownloadFileOperations by DefaultDownloadFileOperations {
                override fun execute(client: OkHttpClient, url: String): Response = Response.Builder()
                    .request(Request.Builder().url(url).build())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(jpegBytes().toResponseBody())
                    .build()
            },
        )
        val worker = mgr.start()
        try {
            mgr.enqueue(
                DownloadItem(
                    sourceId = 42L,
                    mangaTitle = identity.mangaTitle,
                    chapterName = identity.chapterName,
                    chapterId = 420L,
                    chapterUrl = identity.chapterUrl,
                    pageUrls = listOf("https://fixture.invalid/001.jpg"),
                ),
            )
            advanceUntilIdle()

            val canonicalDirectory = provider.canonicalChapterDownloadDir(identity)
            val canonicalCbz = CbzCreator.defaultOutputFile(canonicalDirectory)
            assertFalse(canonicalDirectory.exists())
            assertTrue(canonicalCbz.isFile)
            assertFalse(provider.chapterDownloadDir(42L, identity.mangaTitle, identity.chapterName).exists())
            assertEquals(
                canonicalCbz.absolutePath,
                provider.downloadArtifactLookup(42L).locate(identity)?.opaqueLocation,
            )
            assertTrue(mgr.isDownloaded(42L, identity))
            assertTrue(provider.hasMangaDownloads(42L, identity))

            mgr.deleteDownload(42L, identity)

            assertFalse(canonicalCbz.exists())
            assertFalse(mgr.isDownloaded(42L, identity))
        } finally {
            worker.cancel()
        }
    }

    @Test
    fun `clear errors removes the canonical temporary directory resolved by the failed worker`() = runTest {
        val provider = DesktopDownloadProvider(tempDir.resolve("canonical-error-downloads"))
        val identity = DownloadChapterIdentity(
            sourceDisplayName = "Canonical Source",
            mangaTitle = "Canonical Manga",
            chapterName = "Chapter 2",
            scanlator = "Group",
            chapterUrl = "/chapter/2",
            disallowNonAsciiFilenames = false,
        )
        val manager = DesktopDownloadManager(
            provider = provider,
            networkHelper = NetworkHelper(OkHttpClient()),
            workerScope = this,
            retryDelay = { },
            downloadIdentityResolver = { identity },
            fileOperations = object : DownloadFileOperations by DefaultDownloadFileOperations {
                override fun execute(client: OkHttpClient, url: String): Response = Response.Builder()
                    .request(Request.Builder().url(url).build())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("not an image".toResponseBody())
                    .build()
            },
        )
        val worker = manager.start()
        try {
            manager.enqueue(
                DownloadItem(
                    sourceId = 42L,
                    mangaTitle = identity.mangaTitle,
                    chapterName = identity.chapterName,
                    chapterId = 421L,
                    chapterUrl = identity.chapterUrl,
                    pageUrls = listOf("https://fixture.invalid/001.jpg"),
                ),
            )
            advanceUntilIdle()

            assertEquals(DownloadStatus.ERROR, manager.queue.value.single().status)
            assertTrue(provider.canonicalChapterTmpDir(identity).isDirectory)

            manager.clearErrors()
            advanceUntilIdle()

            assertTrue(manager.queue.value.isEmpty())
            assertFalse(provider.canonicalChapterTmpDir(identity).exists())
        } finally {
            worker.cancel()
        }
    }

    @Test
    fun `cancelled worker cannot remove or overwrite an immediate same id reenqueue`() = runTest {
        val provider = DesktopDownloadProvider(tempDir.resolve("same-id-generation"))
        val identity = DownloadChapterIdentity(
            sourceDisplayName = "Generation Source",
            mangaTitle = "Generation Manga",
            chapterName = "Chapter 3",
            scanlator = null,
            chapterUrl = "/chapter/3",
            disallowNonAsciiFilenames = false,
        )
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val firstBytes = jpegBytes() + 0x01.toByte()
        val secondBytes = jpegBytes() + 0x02.toByte()
        val writtenGenerations = mutableListOf<Byte>()
        val manager = DesktopDownloadManager(
            provider = provider,
            networkHelper = NetworkHelper(OkHttpClient()),
            workerScope = this,
            downloadIdentityResolver = { identity },
            fileOperations = object : DownloadFileOperations by DefaultDownloadFileOperations {
                override fun execute(client: OkHttpClient, url: String): Response = Response.Builder()
                    .request(Request.Builder().url(url).build())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(byteArrayOf().toResponseBody())
                    .build()

                override suspend fun readBody(response: Response): ByteArray =
                    if (response.request.url.encodedPath.endsWith("first.jpg")) {
                        firstStarted.complete(Unit)
                        releaseFirst.await()
                        firstBytes
                    } else {
                        secondStarted.complete(Unit)
                        secondBytes
                    }

                override fun writePage(file: File, bytes: ByteArray) {
                    writtenGenerations += bytes.last()
                    DefaultDownloadFileOperations.writePage(file, bytes)
                }
            },
        )
        val worker = manager.start()
        try {
            manager.enqueue(
                DownloadItem(
                    sourceId = 42L,
                    mangaTitle = identity.mangaTitle,
                    chapterName = identity.chapterName,
                    chapterId = 422L,
                    chapterUrl = identity.chapterUrl,
                    pageUrls = listOf("https://fixture.invalid/first.jpg"),
                ),
            )
            withTimeout(2_000) { firstStarted.await() }

            assertTrue(manager.cancel(422L))
            manager.enqueue(
                DownloadItem(
                    sourceId = 42L,
                    mangaTitle = identity.mangaTitle,
                    chapterName = identity.chapterName,
                    chapterId = 422L,
                    chapterUrl = identity.chapterUrl,
                    pageUrls = listOf("https://fixture.invalid/second.jpg"),
                ),
            )
            releaseFirst.complete(Unit)
            advanceUntilIdle()

            withTimeout(2_000) { secondStarted.await() }
            assertTrue(manager.queue.value.isEmpty())
            assertEquals(listOf(0x02.toByte()), writtenGenerations)
            assertArrayEquals(
                secondBytes,
                File(provider.canonicalChapterDownloadDir(identity), "001.jpg").readBytes(),
            )
        } finally {
            worker.cancel()
        }
    }

    @Test
    fun `cbz packaging failure preserves private pages without exposing a transient final directory`() = runTest {
        val provider = DesktopDownloadProvider(tempDir.resolve("cbz-finalize-failure"))
        val identity = DownloadChapterIdentity(
            sourceDisplayName = "CBZ Source",
            mangaTitle = "CBZ Manga",
            chapterName = "Chapter 4",
            scanlator = null,
            chapterUrl = "/chapter/4",
            disallowNonAsciiFilenames = false,
        )
        val preferences = DesktopDownloadPreferences(InMemoryPreferenceStore()).apply {
            downloadAsCbz.set(true)
        }
        val finalDirectory = provider.canonicalChapterDownloadDir(identity)
        val blockedCbzTarget = CbzCreator.defaultOutputFile(finalDirectory).also { target ->
            target.mkdirs()
            File(target, "do-not-delete.txt").writeText("occupied")
        }
        val manager = DesktopDownloadManager(
            provider = provider,
            networkHelper = NetworkHelper(OkHttpClient()),
            downloadPreferences = preferences,
            workerScope = this,
            retryDelay = { },
            downloadIdentityResolver = { identity },
            fileOperations = object : DownloadFileOperations by DefaultDownloadFileOperations {
                override fun execute(client: OkHttpClient, url: String): Response = Response.Builder()
                    .request(Request.Builder().url(url).build())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(jpegBytes().toResponseBody())
                    .build()
            },
        )
        val worker = manager.start()
        try {
            manager.enqueue(
                DownloadItem(
                    sourceId = 42L,
                    mangaTitle = identity.mangaTitle,
                    chapterName = identity.chapterName,
                    chapterId = 423L,
                    chapterUrl = identity.chapterUrl,
                    pageUrls = listOf("https://fixture.invalid/page.jpg"),
                ),
            )
            advanceUntilIdle()

            assertEquals(DownloadStatus.ERROR, manager.queue.value.single().status)
            assertArrayEquals(jpegBytes(), File(provider.canonicalChapterTmpDir(identity), "001.jpg").readBytes())
            assertFalse(finalDirectory.exists())
            assertTrue(File(blockedCbzTarget, "do-not-delete.txt").isFile)
        } finally {
            worker.cancel()
        }
    }

    @Test
    fun `blocked page and chapter finalization do not delay cancellation`(): Unit = runBlocking {
        BlockingFinalizationStage.entries.forEach { stage ->
            newSingleThreadContext("blocked-${stage.name.lowercase()}").use { dispatcher ->
                val workerParent = SupervisorJob()
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val operationFinished = CompletableDeferred<Unit>()
                val provider = DesktopDownloadProvider(tempDir.resolve("blocked-${stage.name.lowercase()}"))
                val preferences = DesktopDownloadPreferences(InMemoryPreferenceStore()).apply {
                    downloadAsCbz.set(stage == BlockingFinalizationStage.CBZ_PACKAGE)
                }
                val manager = DesktopDownloadManager(
                    provider = provider,
                    networkHelper = NetworkHelper(OkHttpClient()),
                    downloadPreferences = preferences,
                    workerScope = CoroutineScope(workerParent + dispatcher),
                    chapterPackager = { directory, target, _ ->
                        if (stage == BlockingFinalizationStage.CBZ_PACKAGE) {
                            try {
                                entered.complete(Unit)
                                runBlocking { release.await() }
                                CbzCreator.create(directory, target)
                            } finally {
                                operationFinished.complete(Unit)
                            }
                        } else {
                            CbzCreator.create(directory, target)
                        }
                    },
                    fileOperations = object : DownloadFileOperations by DefaultDownloadFileOperations {
                        override fun execute(client: OkHttpClient, url: String): Response = Response.Builder()
                            .request(Request.Builder().url(url).build())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(jpegBytes().toResponseBody())
                            .build()

                        override fun renamePage(tmp: File, final: File) {
                            if (stage == BlockingFinalizationStage.PAGE_RENAME) {
                                try {
                                    entered.complete(Unit)
                                    runBlocking { release.await() }
                                    tmp.parentFile.mkdirs()
                                    tmp.writeBytes(jpegBytes())
                                    DefaultDownloadFileOperations.renamePage(tmp, final)
                                } finally {
                                    operationFinished.complete(Unit)
                                }
                                return
                            }
                            DefaultDownloadFileOperations.renamePage(tmp, final)
                        }

                        override fun renameChapter(tmpDir: File, finalDir: File): Boolean {
                            if (stage == BlockingFinalizationStage.CHAPTER_RENAME) {
                                return try {
                                    entered.complete(Unit)
                                    runBlocking { release.await() }
                                    tmpDir.mkdirs()
                                    File(tmpDir, "001.jpg").writeBytes(jpegBytes())
                                    DefaultDownloadFileOperations.renameChapter(tmpDir, finalDir)
                                } finally {
                                    operationFinished.complete(Unit)
                                }
                            }
                            return DefaultDownloadFileOperations.renameChapter(tmpDir, finalDir)
                        }
                    },
                )
                val item = DownloadItem(
                    sourceId = 42L,
                    mangaTitle = "Blocked Manga",
                    chapterName = stage.name,
                    chapterId = 500L + stage.ordinal,
                    pageUrls = listOf("https://fixture.invalid/page.jpg"),
                )
                manager.enqueue(item)
                manager.start()
                try {
                    withTimeout(3_000) { entered.await() }
                    val cancellation = async(Dispatchers.Default) { manager.cancel(item.chapterId) }
                    assertTrue(withTimeout(3_000) { cancellation.await() })
                    assertTrue(manager.queue.value.isEmpty())
                    release.complete(Unit)
                    withTimeout(3_000) { operationFinished.await() }
                    withTimeout(3_000) {
                        while (manager.activeJobCount > 1) delay(10)
                    }
                } finally {
                    release.complete(Unit)
                    withTimeout(3_000) { manager.stopAndJoin() }
                    withTimeout(3_000) { workerParent.cancelAndJoin() }
                }

                assertFalse(provider.chapterTmpDir(item.sourceId, item.mangaTitle, item.chapterName).exists())
                val finalDir = provider.chapterDownloadDir(item.sourceId, item.mangaTitle, item.chapterName)
                assertFalse(finalDir.exists())
                assertFalse(CbzCreator.defaultOutputFile(finalDir).exists())
            }
        }
    }

    @Test
    fun `batch retirement starts every target before waiting for blocked cleanup`(): Unit = runBlocking {
        val firstCleanupEntered = CompletableDeferred<Unit>()
        val releaseFirstCleanup = CompletableDeferred<Unit>()
        val workerParent = SupervisorJob()
        val manager = DesktopDownloadManager(
            provider = DesktopDownloadProvider(tempDir.resolve("batch-retirement")),
            networkHelper = NetworkHelper(OkHttpClient()),
            workerScope = CoroutineScope(workerParent + Dispatchers.Default),
            retirementCleanupAwaitObserver = { chapterId ->
                if (chapterId == 601L && firstCleanupEntered.complete(Unit)) {
                    runBlocking { releaseFirstCleanup.await() }
                }
            },
        )
        val first = DownloadItem(42L, "Batch", "First", 601L)
        val second = DownloadItem(42L, "Batch", "Second", 602L)
        manager.enqueue(first)
        manager.enqueue(second)

        try {
            val retirement = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                manager.cancelAndAwaitRetirements(listOf(first.chapterId, second.chapterId))
            }
            withTimeout(2_000) { firstCleanupEntered.await() }

            assertTrue(manager.queue.value.isEmpty())
            assertFalse(retirement.isCompleted)

            releaseFirstCleanup.complete(Unit)
            assertTrue(withTimeout(3_000) { retirement.await() })
        } finally {
            releaseFirstCleanup.complete(Unit)
            withTimeout(3_000) { manager.stopAndJoin() }
            withTimeout(3_000) { workerParent.cancelAndJoin() }
        }
    }

    @Test
    fun `batch retirement cancels current generation while an older generation is still retiring`(): Unit =
        runBlocking {
            val provider = DesktopDownloadProvider(tempDir.resolve("batch-retirement-generation"))
            val staleArtifact = provider.chapterTmpDir(42L, "Batch generation", "Chapter")
            val cleanupEntered = CompletableDeferred<Unit>()
            val releaseCleanup = CompletableDeferred<Unit>()
            val workerParent = SupervisorJob()
            val manager = DesktopDownloadManager(
                provider = provider,
                networkHelper = NetworkHelper(OkHttpClient()),
                workerScope = CoroutineScope(workerParent + Dispatchers.Default),
                artifactCleaner = { artifact ->
                    if (artifact == staleArtifact && cleanupEntered.complete(Unit)) {
                        runBlocking { releaseCleanup.await() }
                    }
                    artifact.deleteRecursively()
                },
            )
            val item = DownloadItem(42L, "Batch generation", "Chapter", 603L)
            manager.enqueue(item)
            staleArtifact.mkdirs()
            File(staleArtifact, "old.partial").writeText("old")

            try {
                assertTrue(manager.cancel(item.chapterId))
                withTimeout(2_000) { cleanupEntered.await() }
                manager.enqueue(item.copy(chapterUrl = "/replacement"))

                val retirement = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                    manager.cancelAndAwaitRetirements(listOf(item.chapterId))
                }
                assertTrue(manager.queue.value.isEmpty())

                releaseCleanup.complete(Unit)
                assertTrue(withTimeout(3_000) { retirement.await() })
            } finally {
                releaseCleanup.complete(Unit)
                withTimeout(3_000) { manager.stopAndJoin() }
                withTimeout(3_000) { workerParent.cancelAndJoin() }
            }
        }

    @Test
    fun `older retirement cannot hide current generation cancellation persistence failure`(): Unit = runBlocking {
        val provider = DesktopDownloadProvider(tempDir.resolve("batch-retirement-persist-failure"))
        val staleArtifact = provider.chapterTmpDir(42L, "Persist failure", "Chapter")
        val cleanupEntered = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val workerParent = SupervisorJob()
        var failEmptyQueuePersistence = false
        val manager = DesktopDownloadManager(
            provider = provider,
            networkHelper = NetworkHelper(OkHttpClient()),
            workerScope = CoroutineScope(workerParent + Dispatchers.Default),
            queuePersister = { entries ->
                if (failEmptyQueuePersistence && entries.isEmpty()) throw java.io.IOException("blocked persistence")
            },
            artifactCleaner = { artifact ->
                if (artifact == staleArtifact && cleanupEntered.complete(Unit)) {
                    runBlocking { releaseCleanup.await() }
                }
                artifact.deleteRecursively()
            },
        )
        val item = DownloadItem(42L, "Persist failure", "Chapter", 604L)
        manager.enqueue(item)
        staleArtifact.mkdirs()
        File(staleArtifact, "old.partial").writeText("old")

        try {
            assertTrue(manager.cancel(item.chapterId))
            withTimeout(2_000) { cleanupEntered.await() }
            manager.enqueue(item.copy(chapterUrl = "/replacement"))
            failEmptyQueuePersistence = true

            val retirement = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                manager.cancelAndAwaitRetirements(listOf(item.chapterId))
            }
            assertEquals(listOf(item.chapterId), manager.queue.value.map { it.chapterId })

            releaseCleanup.complete(Unit)
            assertFalse(withTimeout(3_000) { retirement.await() })
        } finally {
            releaseCleanup.complete(Unit)
            withTimeout(3_000) { manager.stopAndJoin() }
            withTimeout(3_000) { workerParent.cancelAndJoin() }
        }
    }

    @Test
    fun `blocked failure notification does not delay same id cancellation and reenqueue`(): Unit = runBlocking {
        newSingleThreadContext("notification-download-worker").use { dispatcher ->
            // This fixture deliberately blocks the notifier, so it must own and bound its worker lifecycle.
            val workerParent = SupervisorJob()
            val notificationStarted = CountDownLatch(1)
            val releaseNotification = CountDownLatch(1)
            val replacementQueued = CountDownLatch(1)
            val notifier = DesktopSystemNotifier(
                system = {
                    notificationStarted.countDown()
                    releaseNotification.await()
                    true
                },
                fallback = mihon.desktop.domain.DesktopNotificationService(),
            )
            val manager = DesktopDownloadManager(
                provider = DesktopDownloadProvider(tempDir.resolve("notification-generation")),
                networkHelper = NetworkHelper(OkHttpClient()),
                workerScope = CoroutineScope(workerParent + dispatcher),
                retryDelay = { },
                taskNotifier = notifier,
                fileOperations = object : DownloadFileOperations by DefaultDownloadFileOperations {
                    override fun execute(client: OkHttpClient, url: String): Response {
                        throw java.io.IOException("terminal failure")
                    }
                },
            )
            val item = DownloadItem(
                sourceId = 42L,
                mangaTitle = "Notification Manga",
                chapterName = "Chapter 5",
                chapterId = 424L,
                pageUrls = listOf("https://fixture.invalid/failure.jpg"),
            )
            val worker = manager.start()
            var replacement: Thread? = null
            try {
                manager.enqueue(item)
                assertTrue(notificationStarted.await(30, TimeUnit.SECONDS))
                val replacementThread = thread(name = "same-id-reenqueue", isDaemon = true) {
                    assertTrue(manager.cancel(item.chapterId))
                    manager.enqueue(item.copy(pageUrls = listOf("https://fixture.invalid/replacement.jpg")))
                    replacementQueued.countDown()
                }
                replacement = replacementThread

                assertTrue(replacementQueued.await(30, TimeUnit.SECONDS))
                replacementThread.join(30_000)
                assertFalse(replacementThread.isAlive)
                releaseNotification.countDown()
                withTimeout(30_000) {
                    manager.queue.first { items -> items.singleOrNull()?.status == DownloadStatus.ERROR }
                }
                withTimeout(30_000) {
                    while (manager.activeJobCount > 1) delay(10)
                }
            } finally {
                releaseNotification.countDown()
                replacement?.join(30_000)
                withTimeout(30_000) { manager.stopAndJoin() }
                withTimeout(30_000) { worker.cancelAndJoin() }
                withTimeout(30_000) { workerParent.cancelAndJoin() }
            }
        }
    }

    @Test
    fun `same id replacement waits for asynchronous cancellation cleanup`(): Unit = runBlocking {
        val provider = DesktopDownloadProvider(tempDir.resolve("retirement-gate"))
        val item = DownloadItem(
            sourceId = 42L,
            mangaTitle = "Retirement Manga",
            chapterName = "Chapter 6",
            chapterId = 425L,
            pageUrls = listOf("https://fixture.invalid/old.jpg"),
        )
        val oldTmp = provider.chapterTmpDir(item.sourceId, item.mangaTitle, item.chapterName)
        val cleanupEntered = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val replacementAwaitEntered = CompletableDeferred<Unit>()
        val replacementExecuted = CompletableDeferred<Unit>()
        val workerParent = SupervisorJob()
        val manager = DesktopDownloadManager(
            provider = provider,
            networkHelper = NetworkHelper(OkHttpClient()),
            workerScope = CoroutineScope(workerParent + Dispatchers.Default),
            artifactCleaner = { artifact ->
                if (artifact == oldTmp && cleanupEntered.complete(Unit)) {
                    runBlocking { releaseCleanup.await() }
                }
                artifact.deleteRecursively()
            },
            retirementAwaitObserver = { replacementAwaitEntered.complete(Unit) },
            fileOperations = object : DownloadFileOperations by DefaultDownloadFileOperations {
                override fun execute(client: OkHttpClient, url: String): Response {
                    replacementExecuted.complete(Unit)
                    return Response.Builder()
                        .request(Request.Builder().url(url).build())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(jpegBytes().toResponseBody())
                        .build()
                }
            },
        )
        manager.enqueue(item)
        oldTmp.mkdirs()
        File(oldTmp, "old.partial").writeText("old")

        try {
            assertTrue(withTimeout(2_000) { async(Dispatchers.Default) { manager.cancel(item.chapterId) }.await() })
            withTimeout(2_000) { cleanupEntered.await() }
            manager.enqueue(item.copy(pageUrls = listOf("https://fixture.invalid/replacement.jpg")))
            manager.start()
            withTimeout(2_000) {
                manager.queue.first { queued -> queued.singleOrNull()?.status == DownloadStatus.DOWNLOADING }
            }
            withTimeout(2_000) { replacementAwaitEntered.await() }

            assertFalse(replacementExecuted.isCompleted)
            releaseCleanup.complete(Unit)
            withTimeout(3_000) { replacementExecuted.await() }
            withTimeout(3_000) { manager.queue.first { it.isEmpty() } }
            assertArrayEquals(
                jpegBytes(),
                File(
                    provider.chapterDownloadDir(item.sourceId, item.mangaTitle, item.chapterName),
                    "001.jpg",
                ).readBytes(),
            )
        } finally {
            releaseCleanup.complete(Unit)
            withTimeout(3_000) { manager.stopAndJoin() }
            withTimeout(3_000) { workerParent.cancelAndJoin() }
        }
    }

    @Test
    fun `producer completion gates cleanup after an external error transition`(): Unit = runBlocking {
        val provider = DesktopDownloadProvider(tempDir.resolve("producer-retirement-gate"))
        val item = DownloadItem(
            sourceId = 42L,
            mangaTitle = "Producer Gate Manga",
            chapterName = "Chapter 6B",
            chapterId = 4_251L,
            pageUrls = listOf("https://fixture.invalid/old.jpg"),
        )
        val producerEntered = CompletableDeferred<Unit>()
        val releaseProducer = CompletableDeferred<Unit>()
        val cleanupAwaitEntered = CompletableDeferred<Unit>()
        val cleanupEntered = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val replacementAwaitEntered = CompletableDeferred<Unit>()
        val replacementExecuted = CompletableDeferred<Unit>()
        val workerParent = SupervisorJob()
        val staleFinal = provider.chapterDownloadDir(item.sourceId, item.mangaTitle, item.chapterName)
        val manager = DesktopDownloadManager(
            provider = provider,
            networkHelper = NetworkHelper(OkHttpClient()),
            workerScope = CoroutineScope(workerParent + Dispatchers.Default),
            retirementCleanupAwaitObserver = { cleanupAwaitEntered.complete(Unit) },
            retirementAwaitObserver = { replacementAwaitEntered.complete(Unit) },
            artifactCleaner = { artifact ->
                if (artifact == staleFinal && cleanupEntered.complete(Unit)) {
                    runBlocking { releaseCleanup.await() }
                }
                artifact.deleteRecursively()
            },
            fileOperations = object : DownloadFileOperations by DefaultDownloadFileOperations {
                override fun execute(client: OkHttpClient, url: String): Response {
                    if (url.endsWith("replacement.jpg")) replacementExecuted.complete(Unit)
                    return Response.Builder()
                        .request(Request.Builder().url(url).build())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(jpegBytes().toResponseBody())
                        .build()
                }

                override fun writePage(tmp: File, bytes: ByteArray) {
                    if (producerEntered.complete(Unit)) runBlocking { releaseProducer.await() }
                    tmp.parentFile.mkdirs()
                    DefaultDownloadFileOperations.writePage(tmp, bytes)
                }
            },
        )
        manager.start()
        manager.enqueue(item)
        try {
            withTimeout(2_000) { producerEntered.await() }
            assertTrue(manager.transition(item.chapterId, mihon.domain.download.DownloadQueueStatus.ERROR))
            staleFinal.mkdirs()
            File(staleFinal, "stale.jpg").writeBytes(jpegBytes())
            assertTrue(manager.cancel(item.chapterId))
            manager.enqueue(item.copy(pageUrls = listOf("https://fixture.invalid/replacement.jpg")))
            withTimeout(2_000) { cleanupAwaitEntered.await() }

            assertFalse(
                withTimeoutOrNull(200) {
                    cleanupEntered.await()
                    true
                } ?: false,
            )
            releaseProducer.complete(Unit)
            withTimeout(2_000) { cleanupEntered.await() }
            withTimeout(2_000) { replacementAwaitEntered.await() }
            assertFalse(replacementExecuted.isCompleted)

            releaseCleanup.complete(Unit)
            withTimeout(3_000) { replacementExecuted.await() }
            withTimeout(3_000) { manager.queue.first { it.isEmpty() } }
        } finally {
            releaseProducer.complete(Unit)
            releaseCleanup.complete(Unit)
            withTimeout(3_000) { manager.stopAndJoin() }
            withTimeout(3_000) { workerParent.cancelAndJoin() }
        }
    }

    @Test
    fun `failed retirement cleanup cannot be adopted as a completed replacement`(): Unit = runBlocking {
        val provider = DesktopDownloadProvider(tempDir.resolve("retirement-failure"))
        val item = DownloadItem(
            sourceId = 42L,
            mangaTitle = "Retirement Failure Manga",
            chapterName = "Chapter 7",
            chapterId = 426L,
            pageUrls = listOf("https://fixture.invalid/old.jpg"),
        )
        val staleFinal = provider.chapterDownloadDir(item.sourceId, item.mangaTitle, item.chapterName)
        var executeCalls = 0
        var cleanupAllowed = false
        val workerParent = SupervisorJob()
        val manager = DesktopDownloadManager(
            provider = provider,
            networkHelper = NetworkHelper(OkHttpClient()),
            workerScope = CoroutineScope(workerParent + Dispatchers.Default),
            retryDelay = { },
            artifactCleaner = { artifact ->
                if (artifact == staleFinal && !cleanupAllowed) false else artifact.deleteRecursively()
            },
            fileOperations = object : DownloadFileOperations by DefaultDownloadFileOperations {
                override fun execute(client: OkHttpClient, url: String): Response {
                    executeCalls++
                    return Response.Builder()
                        .request(Request.Builder().url(url).build())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(jpegBytes().toResponseBody())
                        .build()
                }
            },
        )
        manager.enqueue(item)
        staleFinal.mkdirs()
        File(staleFinal, "001.jpg").writeBytes(jpegBytes())

        try {
            assertTrue(manager.cancel(item.chapterId))
            manager.enqueue(item.copy(pageUrls = listOf("https://fixture.invalid/replacement.jpg")))
            manager.start()
            withTimeout(3_000) {
                manager.queue.first { queued -> queued.singleOrNull()?.status == DownloadStatus.ERROR }
            }

            assertEquals(0, executeCalls)
            assertTrue(staleFinal.isDirectory)
            assertTrue(manager.failures.value[item.chapterId] is mihon.domain.error.AppError.Storage)

            assertTrue(manager.cancel(item.chapterId))
            manager.enqueue(item.copy(pageUrls = listOf("https://fixture.invalid/replacement.jpg")))
            withTimeout(3_000) {
                manager.queue.first { queued -> queued.singleOrNull()?.status == DownloadStatus.ERROR }
            }
            assertEquals(0, executeCalls)

            cleanupAllowed = true
            manager.retryItem(item.chapterId)
            withTimeout(3_000) { manager.queue.first { it.isEmpty() } }
            assertEquals(1, executeCalls)
        } finally {
            withTimeout(3_000) { manager.stopAndJoin() }
            withTimeout(3_000) { workerParent.cancelAndJoin() }
        }
    }

    @Test
    fun `thrown retirement cleanup failure remains a retryable storage error`(): Unit = runBlocking {
        val provider = DesktopDownloadProvider(tempDir.resolve("retirement-throw"))
        val item = DownloadItem(
            sourceId = 42L,
            mangaTitle = "Retirement Throw Manga",
            chapterName = "Chapter 7B",
            chapterId = 4_261L,
            pageUrls = listOf("https://fixture.invalid/replacement.jpg"),
        )
        val staleFinal = provider.chapterDownloadDir(item.sourceId, item.mangaTitle, item.chapterName)
        val workerParent = SupervisorJob()
        val manager = DesktopDownloadManager(
            provider = provider,
            networkHelper = NetworkHelper(OkHttpClient()),
            workerScope = CoroutineScope(workerParent + Dispatchers.Default),
            artifactCleaner = { artifact ->
                if (artifact == staleFinal) throw java.io.IOException("cleanup failed")
                artifact.deleteRecursively()
            },
            fileOperations = object : DownloadFileOperations by DefaultDownloadFileOperations {
                override fun execute(client: OkHttpClient, url: String): Response =
                    error("replacement must not execute after cleanup throws")
            },
        )
        manager.enqueue(item)
        staleFinal.mkdirs()
        File(staleFinal, "001.jpg").writeBytes(jpegBytes())
        try {
            assertTrue(manager.cancel(item.chapterId))
            manager.enqueue(item)
            manager.start()
            withTimeout(3_000) {
                manager.queue.first { queued -> queued.singleOrNull()?.status == DownloadStatus.ERROR }
            }
            assertTrue(staleFinal.isDirectory)
            assertTrue(manager.failures.value[item.chapterId] is mihon.domain.error.AppError.Storage)
        } finally {
            withTimeout(3_000) { manager.stopAndJoin() }
            withTimeout(3_000) { workerParent.cancelAndJoin() }
        }
    }

    @Test
    fun `canonical cleanup failure survives repeated cancellation without adopting stale content`(): Unit =
        runBlocking {
            val provider = DesktopDownloadProvider(tempDir.resolve("canonical-retirement-chain"))
            val identity = DownloadChapterIdentity(
                sourceDisplayName = "Canonical Retirement Source",
                mangaTitle = "Canonical Retirement Manga",
                chapterName = "Chapter 7C",
                scanlator = null,
                chapterUrl = "/chapter/7c",
                disallowNonAsciiFilenames = false,
            )
            val item = DownloadItem(
                sourceId = 42L,
                mangaTitle = identity.mangaTitle,
                chapterName = identity.chapterName,
                chapterId = 4_262L,
                chapterUrl = identity.chapterUrl,
                pageUrls = listOf("https://fixture.invalid/old.jpg"),
            )
            val canonicalFinal = provider.canonicalChapterDownloadDir(identity)
            val firstWriteEntered = CompletableDeferred<Unit>()
            val releaseFirstWrite = CompletableDeferred<Unit>()
            val secondCleanupFinished = CompletableDeferred<Unit>()
            val cleanupRuns = AtomicInteger()
            var cleanupAllowed = false
            var executeCalls = 0
            val workerParent = SupervisorJob()
            val manager = DesktopDownloadManager(
                provider = provider,
                networkHelper = NetworkHelper(OkHttpClient()),
                workerScope = CoroutineScope(workerParent + Dispatchers.Default),
                downloadIdentityResolver = { identity },
                retirementCleanupFinishedObserver = { _, _ ->
                    if (cleanupRuns.incrementAndGet() >= 2) secondCleanupFinished.complete(Unit)
                },
                artifactCleaner = { artifact ->
                    if (artifact == canonicalFinal && !cleanupAllowed) false else artifact.deleteRecursively()
                },
                fileOperations = object : DownloadFileOperations by DefaultDownloadFileOperations {
                    override fun execute(client: OkHttpClient, url: String): Response {
                        executeCalls++
                        return Response.Builder()
                            .request(Request.Builder().url(url).build())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(jpegBytes().toResponseBody())
                            .build()
                    }

                    override fun writePage(tmp: File, bytes: ByteArray) {
                        if (firstWriteEntered.complete(Unit)) runBlocking { releaseFirstWrite.await() }
                        tmp.parentFile.mkdirs()
                        DefaultDownloadFileOperations.writePage(tmp, bytes)
                    }
                },
            )
            manager.start()
            manager.enqueue(item)
            try {
                withTimeout(2_000) { firstWriteEntered.await() }
                canonicalFinal.mkdirs()
                File(canonicalFinal, "stale.jpg").writeBytes(jpegBytes())
                assertTrue(manager.cancel(item.chapterId))
                manager.enqueue(item.copy(pageUrls = listOf("https://fixture.invalid/replacement-1.jpg")))
                releaseFirstWrite.complete(Unit)
                withTimeout(3_000) {
                    manager.queue.first { queued -> queued.singleOrNull()?.status == DownloadStatus.ERROR }
                }

                assertTrue(manager.cancel(item.chapterId))
                withTimeout(2_000) { secondCleanupFinished.await() }
                manager.enqueue(item.copy(pageUrls = listOf("https://fixture.invalid/replacement-2.jpg")))
                val repeatedResult = withTimeout(3_000) {
                    manager.queue.first { queued ->
                        queued.isEmpty() || queued.singleOrNull()?.status == DownloadStatus.ERROR
                    }
                }

                assertEquals(DownloadStatus.ERROR, repeatedResult.single().status)
                assertTrue(canonicalFinal.isDirectory)
                assertEquals(1, executeCalls)

                cleanupAllowed = true
                manager.retryItem(item.chapterId)
                withTimeout(3_000) { manager.queue.first { it.isEmpty() } }
                assertEquals(2, executeCalls)
            } finally {
                releaseFirstWrite.complete(Unit)
                withTimeout(3_000) { manager.stopAndJoin() }
                withTimeout(3_000) { workerParent.cancelAndJoin() }
            }
        }

    @Test
    fun `completion persistence failure keeps the queue generation and published artifact`() = runTest {
        val provider = DesktopDownloadProvider(tempDir.resolve("completion-persistence"))
        val item = DownloadItem(
            sourceId = 42L,
            mangaTitle = "Persistence Manga",
            chapterName = "Chapter 8",
            chapterId = 427L,
            pageUrls = listOf("https://fixture.invalid/page.jpg"),
        )
        var persistenceOutage = false
        val manager = DesktopDownloadManager(
            provider = provider,
            networkHelper = NetworkHelper(OkHttpClient()),
            workerScope = this,
            queuePersister = { entries ->
                if (entries.isEmpty()) persistenceOutage = true
                if (persistenceOutage) throw java.io.IOException("persistent queue storage outage")
            },
            fileOperations = object : DownloadFileOperations by DefaultDownloadFileOperations {
                override fun execute(client: OkHttpClient, url: String): Response = Response.Builder()
                    .request(Request.Builder().url(url).build())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(jpegBytes().toResponseBody())
                    .build()
            },
        )
        val worker = manager.start()
        try {
            manager.enqueue(item)
            advanceUntilIdle()

            assertEquals(DownloadStatus.ERROR, manager.queue.value.single().status)
            assertTrue(provider.chapterDownloadDir(item.sourceId, item.mangaTitle, item.chapterName).isDirectory)
            assertTrue(manager.failures.value[item.chapterId] is mihon.domain.error.AppError.Storage)
        } finally {
            worker.cancel()
        }
    }

    @Test
    fun `cancel persistence failure leaves state and files untouched`() {
        val provider = DesktopDownloadProvider(tempDir.resolve("cancel-persistence"))
        val item = DownloadItem(
            sourceId = 42L,
            mangaTitle = "Cancel Persistence Manga",
            chapterName = "Chapter 9",
            chapterId = 428L,
            pageUrls = listOf("https://fixture.invalid/page.jpg"),
        )
        var cleanupCalls = 0
        val manager = DesktopDownloadManager(
            provider = provider,
            queuePersister = { entries ->
                if (entries.isEmpty()) throw java.io.IOException("persist cancellation failed")
            },
            artifactCleaner = { artifact ->
                cleanupCalls++
                artifact.deleteRecursively()
            },
        )
        manager.enqueue(item)
        val tmpDir = provider.chapterTmpDir(item.sourceId, item.mangaTitle, item.chapterName)
        tmpDir.mkdirs()
        File(tmpDir, "001.tmp").writeText("partial")

        assertFalse(manager.cancel(item.chapterId))
        assertEquals(item.chapterId, manager.queue.value.single().chapterId)
        assertTrue(tmpDir.isDirectory)
        assertEquals(0, cleanupCalls)
    }

    @Test
    fun `stopAndJoin waits for an active retirement cleanup`(): Unit = runBlocking {
        val provider = DesktopDownloadProvider(tempDir.resolve("retirement-shutdown"))
        val item = DownloadItem(
            sourceId = 42L,
            mangaTitle = "Retirement Shutdown Manga",
            chapterName = "Chapter 10",
            chapterId = 429L,
            pageUrls = listOf("https://fixture.invalid/page.jpg"),
        )
        val cleanupEntered = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val workerParent = SupervisorJob()
        val manager = DesktopDownloadManager(
            provider = provider,
            workerScope = CoroutineScope(workerParent + Dispatchers.Default),
            artifactCleaner = { artifact ->
                if (cleanupEntered.complete(Unit)) runBlocking { releaseCleanup.await() }
                artifact.deleteRecursively()
            },
        )
        manager.enqueue(item)
        provider.chapterTmpDir(item.sourceId, item.mangaTitle, item.chapterName).apply {
            mkdirs()
            File(this, "partial.tmp").writeText("partial")
        }
        assertTrue(manager.cancel(item.chapterId))
        withTimeout(2_000) { cleanupEntered.await() }

        val closing = async(Dispatchers.Default) { manager.stopAndJoin() }
        try {
            assertFalse(
                withTimeoutOrNull(200) {
                    closing.await()
                    true
                } ?: false,
            )
            releaseCleanup.complete(Unit)
            withTimeout(3_000) { closing.await() }
        } finally {
            releaseCleanup.complete(Unit)
            withTimeout(3_000) { manager.stopAndJoin() }
            withTimeout(3_000) { workerParent.cancelAndJoin() }
        }
    }

    @Test
    fun `stopAndJoin suspends while active child finishes on shared single thread`(): Unit = runBlocking {
        newSingleThreadContext("download-worker").use { dispatcher ->
            val started = CompletableDeferred<Unit>()
            val finallyEntered = CompletableDeferred<Unit>()
            val releaseFinally = CompletableDeferred<Unit>()
            val mgr = DesktopDownloadManager(
                provider = DesktopDownloadProvider(tempDir),
                networkHelper = NetworkHelper(OkHttpClient()),
                workerScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + dispatcher),
                fileOperations = object : DownloadFileOperations by DefaultDownloadFileOperations {
                    override fun execute(client: OkHttpClient, url: String): Response = Response.Builder()
                        .request(Request.Builder().url(url).build())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(byteArrayOf().toResponseBody())
                        .build()

                    override suspend fun readBody(response: Response): ByteArray = try {
                        started.complete(Unit)
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) {
                            finallyEntered.complete(Unit)
                            releaseFinally.await()
                        }
                    }
                },
            )
            mgr.enqueue(DownloadItem(1, "Manga", "Chapter", 99, pageUrls = listOf("https://example.invalid/page")))
            mgr.start()
            withTimeout(2_000) { started.await() }

            val closing = async(dispatcher) { mgr.stopAndJoin() }
            withTimeout(2_000) { finallyEntered.await() }
            assertFalse(closing.isCompleted)
            releaseFinally.complete(Unit)
            withTimeout(2_000) { closing.await() }
            assertEquals(0, mgr.activeJobCount)
            assertFalse(mgr.queue.value.single().status == DownloadStatus.ERROR)
        }
    }

    @Test
    fun `close prevents concurrent enqueue from scheduling new work and is idempotent`() = runTest {
        val mgr = manager(DesktopDownloadProvider(tempDir), this)
        mgr.start()
        val first = async { mgr.stopAndJoin() }
        mgr.enqueue(DownloadItem(1, "Manga", "Chapter", 100, pageUrls = listOf("https://example.invalid/page")))
        first.await()
        mgr.stopAndJoin()
        advanceUntilIdle()
        assertEquals(0, mgr.activeJobCount)
        assertFalse(mgr.queue.value.single().status == DownloadStatus.ERROR)
    }

    private enum class BlockingFinalizationStage {
        PAGE_RENAME,
        CHAPTER_RENAME,
        CBZ_PACKAGE,
    }
}
