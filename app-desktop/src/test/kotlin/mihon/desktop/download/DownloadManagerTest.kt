package mihon.desktop.download

import eu.kanade.tachiyomi.network.NetworkHelper
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import mihon.desktop.domain.DesktopSystemNotifier
import okhttp3.Response
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import mihon.domain.reader.content.DownloadChapterIdentity
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** RED — DesktopDownloadManager does not exist yet. */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadManagerTest {

    @TempDir
    lateinit var tempDir: File

    private fun manager() = DesktopDownloadManager(
        provider = DesktopDownloadProvider(baseDir = tempDir),
    )

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
            sourceId = 1L, mangaTitle = "Test", chapterName = "Ch 1",
            chapterId = 10L, pageUrls = listOf("https://example.com/1.jpg"),
        )
        mgr.enqueue(item)
        mgr.enqueue(item)
        assertEquals(1, mgr.queue.first().size)
    }

    @Test
    fun `cancel removes item from queue`() = runTest {
        val mgr = manager()
        mgr.enqueue(
            DownloadItem(
                sourceId = 1L, mangaTitle = "Test", chapterName = "Ch 1",
                chapterId = 10L, pageUrls = listOf("https://example.com/1.jpg"),
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
            assertEquals(canonicalCbz.absolutePath, provider.downloadArtifactLookup(42L).locate(identity)?.opaqueLocation)
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
    fun `cbz packaging failure after chapter rename preserves completed page files`() = runTest {
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
            assertArrayEquals(jpegBytes(), File(finalDirectory, "001.jpg").readBytes())
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
                    chapterPackager = { directory, target ->
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
                    check(releaseNotification.await(5, TimeUnit.SECONDS))
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
                assertTrue(notificationStarted.await(2, TimeUnit.SECONDS))
                val replacementThread = thread(name = "same-id-reenqueue", isDaemon = true) {
                    assertTrue(manager.cancel(item.chapterId))
                    manager.enqueue(item.copy(pageUrls = listOf("https://fixture.invalid/replacement.jpg")))
                    replacementQueued.countDown()
                }
                replacement = replacementThread

                assertTrue(replacementQueued.await(2, TimeUnit.SECONDS))
                replacementThread.join(2_000)
                assertFalse(replacementThread.isAlive)
                releaseNotification.countDown()
            } finally {
                releaseNotification.countDown()
                replacement?.join(2_000)
                withTimeout(2_000) { manager.stopAndJoin() }
                withTimeout(2_000) { worker.cancelAndJoin() }
                withTimeout(2_000) { workerParent.cancelAndJoin() }
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
                File(provider.chapterDownloadDir(item.sourceId, item.mangaTitle, item.chapterName), "001.jpg").readBytes(),
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

            assertFalse(withTimeoutOrNull(200) { cleanupEntered.await(); true } ?: false)
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
    fun `canonical cleanup failure survives repeated cancellation without adopting stale content`(): Unit = runBlocking {
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
            assertFalse(withTimeoutOrNull(200) { closing.await(); true } ?: false)
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
