package mihon.desktop.download

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.Response
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.AtomicMoveNotSupportedException
import mihon.domain.error.AppError
import mihon.desktop.domain.DesktopNotificationService
import mihon.desktop.domain.DesktopSystemNotifier
import mihon.domain.reader.content.DownloadChapterIdentity
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.awaitCancellation
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.data.download.PersistentDownloadStore

class DesktopDownloadRetryIntegrationTest {
    @TempDir lateinit var directory: File

    @Test
    fun `page list boundary failures persist structured errors notify and retry clears them`(): Unit = runBlocking {
        val cases = listOf(
            "missing source" to SourceCase(null, AppError.Unknown::class),
            "missing chapter url" to SourceCase(PageSource { emptyList() }, AppError.MalformedData::class, chapterUrl = ""),
            "source error" to SourceCase(PageSource { throw IllegalStateException("bad payload") }, AppError.MalformedData::class),
            "empty pages" to SourceCase(PageSource { emptyList() }, AppError.MalformedData::class),
            "source timeout" to SourceCase(PageSource { awaitCancellation() }, AppError.Network::class, timeoutMs = 10),
        )
        cases.forEach { (_, case) ->
            val delivered = mutableListOf<mihon.desktop.domain.DesktopNotification>()
            val notifier = DesktopSystemNotifier(system = { delivered += it; true }, fallback = DesktopNotificationService())
            val manager = DesktopDownloadManager(
                provider = DesktopDownloadProvider(File(directory, java.util.UUID.randomUUID().toString())),
                httpClient = OkHttpClient(),
                workerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                retryDelay = {},
                taskNotifier = notifier,
                sourceResolver = { case.source },
                sourceCallTimeoutMs = case.timeoutMs,
            )
            val chapter = DownloadItem(42, "Manga", "Chapter", 91, chapterUrl = case.chapterUrl)
            manager.enqueue(chapter)
            val job = manager.start()
            awaitError(manager)
            case.errorType.java.isInstance(manager.queue.value.single().failure) shouldBe true
            delivered.size shouldBe 1
            manager.stopAndJoin()
            manager.retryItem(chapter.chapterId)
            manager.queue.value.single().failure shouldBe null
            job.join()
        }
    }

    @Test
    fun `source resolution failure survives restart and retry clears persisted cause`(): Unit = runBlocking {
        val dbFile = File(directory, "source-failure.db")
        val store = persistentStore(dbFile)
        val manager = DesktopDownloadManager(
            provider = DesktopDownloadProvider(File(directory, "source-failure-downloads")),
            httpClient = OkHttpClient(),
            workerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            store = store,
            sourceResolver = { null },
        )
        manager.enqueue(DownloadItem(42, "Manga", "Chapter", 92, chapterUrl = "/chapter"))
        val job = manager.start()
        awaitError(manager)
        job.cancel()

        (persistentStore(dbFile).entries().single().failure is AppError.Unknown) shouldBe true
        val restarted = DesktopDownloadManager(
            provider = DesktopDownloadProvider(File(directory, "source-failure-downloads")),
            store = persistentStore(dbFile),
            sourceResolver = { null },
        )
        (restarted.queue.value.single().failure is AppError.Unknown) shouldBe true
        restarted.retryItem(92)
        persistentStore(dbFile).entries().single().failure shouldBe null
    }

    @Test
    fun `HTTP failures use 2 4 8 retry policy without sleeping`(): Unit = runBlocking {
        listOf(403, 429, 500).forEach { code ->
            val server = MockWebServer().apply {
                repeat(3) { enqueue(MockResponse(code = code)) }
                enqueue(MockResponse(body = PNG))
                start()
            }
            val delays = mutableListOf<Long>()
            try {
                val manager = manager(server, delays)
                manager.enqueue(item(server))
                val job = manager.start()
                awaitEmpty(manager)
                job.cancel()
                delays shouldBe listOf(2_000L, 4_000L, 8_000L)
                server.requestCount shouldBe 4
            } finally { server.close() }
        }
    }

    @Test
    fun `connection socket failures use 2 4 8 retry policy`(): Unit = runBlocking {
        verifySocketRetries { MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build() }
    }

    @Test
    fun `response body socket failures use 2 4 8 retry policy`(): Unit = runBlocking {
        verifySocketRetries {
            MockResponse.Builder().body(PNG.repeat(1024)).onResponseBody(SocketEffect.CloseSocket()).build()
        }
    }

    @Test
    fun `exhausted server retries expose the final AppError`(): Unit = runBlocking {
        val server = MockWebServer().apply {
            repeat(4) { enqueue(MockResponse(code = 500)) }
            start()
        }
        val delays = mutableListOf<Long>()
        try {
            val manager = manager(server, delays)
            val chapter = item(server)
            manager.enqueue(chapter)
            val job = manager.start()
            repeat(200) {
                if (manager.queue.value.singleOrNull()?.status == DownloadStatus.ERROR) return@repeat
                delay(10)
            }
            job.cancel()
            delays shouldBe listOf(2_000L, 4_000L, 8_000L)
            (manager.failures.value[chapter.chapterId] is AppError.Server) shouldBe true
            (manager.failures.value[chapter.chapterId] as AppError.Server).statusCode shouldBe 500
        } finally {
            server.close()
        }
    }

    @Test
    fun `HTTP 403 429 and 500 retain structured details on the queue item`(): Unit = runBlocking {
        listOf(
            Triple(403, null, AppError.Authentication::class),
            Triple(429, "23", AppError.RateLimited::class),
            Triple(500, null, AppError.Server::class),
        ).forEach { (code, retryAfter, type) ->
            val server = MockWebServer().apply {
                repeat(4) { enqueue(MockResponse.Builder().code(code).apply { retryAfter?.let { addHeader("Retry-After", it) } }.build()) }
                start()
            }
            try {
                val manager = manager(server, mutableListOf())
                manager.enqueue(item(server))
                val job = manager.start()
                awaitError(manager)
                job.cancel()
                val failure = manager.queue.value.single().failure
                type.java.isInstance(failure) shouldBe true
                if (failure is AppError.RateLimited) failure.retryAfterSeconds shouldBe 23
                if (failure is AppError.Server) failure.statusCode shouldBe 500
            } finally { server.close() }
        }
    }

    @Test
    fun `file permission storage and unknown failures map accurately`(): Unit = runBlocking {
        listOf(
            AccessDeniedException("blocked") to AppError.Permission::class,
            IOException("No space left on device") to AppError.Storage::class,
            IllegalStateException("unexpected") to AppError.Unknown::class,
        ).forEach { (thrown, type) ->
            val server = MockWebServer().apply { repeat(4) { enqueue(MockResponse(body = PNG)) }; start() }
            try {
                val ops = object : DownloadFileOperations by DefaultDownloadFileOperations {
                    override fun writePage(tmp: File, bytes: ByteArray): Unit = throw thrown
                }
                val manager = manager(server, mutableListOf(), ops)
                manager.enqueue(item(server))
                val job = manager.start()
                awaitError(manager)
                job.cancel()
                type.java.isInstance(manager.queue.value.single().failure) shouldBe true
            } finally { server.close() }
        }
    }

    @Test
    fun `cancelled item is not overwritten by a late worker failure`(): Unit = runBlocking {
        val server = MockWebServer().apply { enqueue(MockResponse(body = PNG)); start() }
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val writeFinished = kotlinx.coroutines.CompletableDeferred<Unit>()
        val ops = object : DownloadFileOperations by DefaultDownloadFileOperations {
            override fun writePage(tmp: File, bytes: ByteArray) {
                entered.complete(Unit)
                try {
                    runBlocking { release.await() }
                    throw IOException("late")
                } finally {
                    writeFinished.complete(Unit)
                }
            }
        }
        val manager = manager(server, mutableListOf(), ops)
        try {
            val chapter = item(server)
            manager.enqueue(chapter)
            manager.start()
            withTimeout(3_000) { entered.await() }
            val cancellation = async(Dispatchers.Default) { manager.cancel(chapter.chapterId) }
            try {
                withTimeout(3_000) { cancellation.await() } shouldBe true
            } finally {
                release.complete(Unit)
            }
            withTimeout(3_000) { writeFinished.await() }
            withTimeout(3_000) {
                while (manager.activeJobCount > 1) delay(10)
            }
            manager.queue.value shouldBe emptyList()
            manager.failures.value.containsKey(chapter.chapterId) shouldBe false
        } finally {
            release.complete(Unit)
            withTimeout(3_000) { manager.stopAndJoin() }
            server.close()
        }
    }

    @Test
    fun `late page write cannot overwrite an immediate same id reenqueue`(): Unit = runBlocking {
        val firstBody = "${PNG}1"
        val replacementBody = "${PNG}2"
        val server = MockWebServer().apply {
            enqueue(MockResponse(body = firstBody))
            enqueue(MockResponse(body = replacementBody))
            start()
        }
        val provider = DesktopDownloadProvider(File(directory, "same-id-write"))
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val firstWriteFinished = kotlinx.coroutines.CompletableDeferred<Unit>()
        var writes = 0
        val ops = object : DownloadFileOperations by DefaultDownloadFileOperations {
            override fun writePage(tmp: File, bytes: ByteArray) {
                if (++writes == 1) {
                    entered.complete(Unit)
                    try {
                        runBlocking { release.await() }
                        tmp.parentFile.mkdirs()
                        DefaultDownloadFileOperations.writePage(tmp, bytes)
                    } finally {
                        firstWriteFinished.complete(Unit)
                    }
                    return
                }
                DefaultDownloadFileOperations.writePage(tmp, bytes)
            }
        }
        val manager = DesktopDownloadManager(
            provider = provider,
            httpClient = OkHttpClient.Builder().retryOnConnectionFailure(false).build(),
            workerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            retryDelay = {},
            fileOperations = ops,
        )
        val chapter = item(server).copy(pageUrls = listOf(server.url("/page.png").toString()))
        manager.enqueue(chapter)
        manager.start()
        try {
            withTimeout(3_000) { entered.await() }
            val cancellation = async(Dispatchers.Default) { manager.cancel(chapter.chapterId) }
            withTimeout(3_000) { cancellation.await() } shouldBe true
            manager.enqueue(chapter)
            manager.queue.value.single().chapterId shouldBe chapter.chapterId
            release.complete(Unit)
            withTimeout(3_000) { firstWriteFinished.await() }
            withTimeout(5_000) {
                while (manager.queue.value.isNotEmpty()) delay(10)
            }

            val finalPage = File(
                provider.chapterDownloadDir(chapter.sourceId, chapter.mangaTitle, chapter.chapterName),
                "001.png",
            )
            finalPage.readBytes().toList() shouldBe replacementBody.encodeToByteArray().toList()
            provider.chapterTmpDir(chapter.sourceId, chapter.mangaTitle, chapter.chapterName).exists() shouldBe false
            manager.failures.value.containsKey(chapter.chapterId) shouldBe false
        } finally {
            release.complete(Unit)
            withTimeout(3_000) { manager.stopAndJoin() }
            server.close()
        }
    }

    @Test
    fun `retry clears persisted failure and a later success clears transient failure`(): Unit = runBlocking {
        val server = MockWebServer().apply {
            repeat(4) { enqueue(MockResponse(code = 500)) }
            enqueue(MockResponse(body = PNG))
            start()
        }
        try {
            val manager = manager(server, mutableListOf())
            val chapter = item(server)
            manager.enqueue(chapter)
            val job = manager.start()
            awaitError(manager)
            manager.retryItem(chapter.chapterId)
            manager.queue.value.single().failure shouldBe null
            awaitEmpty(manager)
            manager.failures.value.containsKey(chapter.chapterId) shouldBe false
            job.cancel()
        } finally { server.close() }
    }

    @Test
    fun `terminal failure emits actionable notification`(): Unit = runBlocking {
        val server = MockWebServer().apply { repeat(4) { enqueue(MockResponse(code = 403)) }; start() }
        val delivered = mutableListOf<mihon.desktop.domain.DesktopNotification>()
        try {
            val notifier = DesktopSystemNotifier(system = { delivered += it; true }, fallback = DesktopNotificationService())
            val manager = manager(server, mutableListOf(), notifier = notifier)
            manager.enqueue(item(server))
            val job = manager.start()
            awaitError(manager)
            job.cancel()
            delivered.single().message shouldBe "服务器拒绝访问（HTTP 403），请检查登录或源设置后重试"
        } finally { server.close() }
    }

    @Test
    fun `execute body read page write and final rename failures each use 2 4 8 retry policy`(): Unit = runBlocking {
        FailurePoint.entries.forEach { point ->
            val server = MockWebServer().apply { repeat(4) { enqueue(MockResponse(body = PNG)) }; start() }
            val delays = mutableListOf<Long>()
            try {
                val operations = FaultOperations(point)
                val manager = manager(server, delays, operations)
                manager.enqueue(item(server))
                val job = manager.start()
                awaitEmpty(manager)
                job.cancel()
                delays shouldBe listOf(2_000L, 4_000L, 8_000L)
                operations.failedStageAttempts shouldBe 4
            } finally { server.close() }
        }
    }

    @Test
    fun `conflicting existing final is never deleted and returns a typed publish error`(): Unit = runBlocking {
        val server = MockWebServer().apply {
            enqueue(MockResponse(body = PNG))
            start()
        }
        val provider = DesktopDownloadProvider(File(directory, "chapter-final-conflict"))
        val identity = downloadIdentity("conflict")
        val finalDirectory = provider.canonicalChapterDownloadDir(identity)
        val conflictingBytes = "GIF89aCONFLICT".toByteArray()
        val workerParent = SupervisorJob()
        val manager = DesktopDownloadManager(
            provider = provider,
            httpClient = OkHttpClient(),
            workerScope = CoroutineScope(workerParent + Dispatchers.Default),
            retryDelay = {},
            downloadIdentityResolver = { identity },
            ioProbe = DownloadIoProbe { event ->
                if (event.operation == DownloadIoOperation.CHAPTER_MOVE && !finalDirectory.exists()) {
                    finalDirectory.mkdirs()
                    File(finalDirectory, "001.gif").writeBytes(conflictingBytes)
                }
            },
        )
        val chapter = DownloadItem(
            sourceId = 42L,
            mangaTitle = identity.mangaTitle,
            chapterName = identity.chapterName,
            chapterId = 8_101L,
            chapterUrl = identity.chapterUrl,
            pageUrls = listOf(server.url("/page.gif").toString()),
        )
        manager.enqueue(chapter)
        manager.start()
        try {
            awaitError(manager)

            val failure = manager.failures.value.getValue(chapter.chapterId)
            (failure is AppError.Storage) shouldBe true
            (failure.cause is ChapterPublishConflictException) shouldBe true
            File(finalDirectory, "001.gif").readBytes().contentEquals(conflictingBytes) shouldBe true
            File(provider.canonicalChapterTmpDir(identity), "001.gif").readBytes().contentEquals(PNG.toByteArray()) shouldBe true
            server.requestCount shouldBe 1
        } finally {
            manager.stopAndJoin()
            workerParent.cancelAndJoin()
            server.close()
        }
    }

    @Test
    fun `identical existing final is adopted without replacing it`() = runBlocking {
        val server = MockWebServer().apply {
            enqueue(MockResponse(body = PNG))
            start()
        }
        val provider = DesktopDownloadProvider(File(directory, "chapter-final-identical"))
        val identity = downloadIdentity("identical")
        val finalDirectory = provider.canonicalChapterDownloadDir(identity)
        var renameCalls = 0
        val workerParent = SupervisorJob()
        val manager = DesktopDownloadManager(
            provider = provider,
            httpClient = OkHttpClient(),
            workerScope = CoroutineScope(workerParent + Dispatchers.Default),
            downloadIdentityResolver = { identity },
            ioProbe = DownloadIoProbe { event ->
                if (event.operation == DownloadIoOperation.CHAPTER_MOVE && !finalDirectory.exists()) {
                    finalDirectory.mkdirs()
                    File(finalDirectory, "001.gif").writeBytes(PNG.toByteArray())
                }
            },
            fileOperations = object : DownloadFileOperations by DefaultDownloadFileOperations {
                override fun renameChapter(tmpDir: File, finalDir: File): Boolean {
                    renameCalls++
                    return DefaultDownloadFileOperations.renameChapter(tmpDir, finalDir)
                }
            },
        )
        manager.enqueue(
            DownloadItem(
                sourceId = 42L,
                mangaTitle = identity.mangaTitle,
                chapterName = identity.chapterName,
                chapterId = 8_102L,
                chapterUrl = identity.chapterUrl,
                pageUrls = listOf(server.url("/page.gif").toString()),
            ),
        )
        manager.start()
        try {
            awaitEmpty(manager)

            renameCalls shouldBe 0
            File(finalDirectory, "001.gif").readBytes().contentEquals(PNG.toByteArray()) shouldBe true
            provider.canonicalChapterTmpDir(identity).exists() shouldBe false
            server.requestCount shouldBe 1
        } finally {
            manager.stopAndJoin()
            workerParent.cancelAndJoin()
            server.close()
        }
    }

    @Test
    fun `unsupported atomic chapter move keeps private pages and exposes typed storage failure`() = runBlocking {
        val server = MockWebServer().apply {
            enqueue(MockResponse(body = PNG))
            start()
        }
        val provider = DesktopDownloadProvider(File(directory, "chapter-atomic-move"))
        val identity = downloadIdentity("atomic-move")
        val workerParent = SupervisorJob()
        val manager = DesktopDownloadManager(
            provider = provider,
            httpClient = OkHttpClient(),
            workerScope = CoroutineScope(workerParent + Dispatchers.Default),
            retryDelay = {},
            downloadIdentityResolver = { identity },
            fileOperations = object : DownloadFileOperations by DefaultDownloadFileOperations {
                override fun renameChapter(tmpDir: File, finalDir: File): Boolean {
                    throw AtomicMoveNotSupportedException(tmpDir.path, finalDir.path, "fixture")
                }
            },
        )
        val chapter = DownloadItem(
            sourceId = 42L,
            mangaTitle = identity.mangaTitle,
            chapterName = identity.chapterName,
            chapterId = 8_103L,
            chapterUrl = identity.chapterUrl,
            pageUrls = listOf(server.url("/page.gif").toString()),
        )
        manager.enqueue(chapter)
        manager.start()
        try {
            awaitError(manager)

            val failure = manager.failures.value.getValue(chapter.chapterId)
            (failure is AppError.Storage) shouldBe true
            (failure.cause is ChapterAtomicPublishException) shouldBe true
            (failure.cause?.cause is AtomicMoveNotSupportedException) shouldBe true
            File(provider.canonicalChapterTmpDir(identity), "001.gif").isFile shouldBe true
            provider.canonicalChapterDownloadDir(identity).exists() shouldBe false
        } finally {
            manager.stopAndJoin()
            workerParent.cancelAndJoin()
            server.close()
        }
    }

    @Test
    fun `final appearing after preflight is rechecked and never replaced by atomic directory move`() {
        val staging = File(directory, "late-final/Chapter_tmp").apply { mkdirs() }
        val stagingPage = File(staging, "001.gif").apply { writeText(PNG) }
        val target = File(staging.parentFile, "Chapter")
        val existingBytes = "GIF89aLATE-FINAL"
        val publisher = ChapterDirectoryPublisher(
            object : DownloadFileOperations by DefaultDownloadFileOperations {
                override fun renameChapter(tmpDir: File, finalDir: File): Boolean {
                    finalDir.mkdirs()
                    File(finalDir, "001.gif").writeText(existingBytes)
                    return DefaultDownloadFileOperations.renameChapter(tmpDir, finalDir)
                }
            },
        )

        val error = runCatching { publisher.publish(staging, target) }.exceptionOrNull()

        (error is ChapterPublishConflictException) shouldBe true
        stagingPage.readText() shouldBe PNG
        File(target, "001.gif").readText() shouldBe existingBytes
    }

    private fun manager(
        server: MockWebServer,
        delays: MutableList<Long>,
        ops: DownloadFileOperations = DefaultDownloadFileOperations,
        notifier: DesktopSystemNotifier? = null,
    ) =
        DesktopDownloadManager(
            DesktopDownloadProvider(File(directory, server.port.toString())),
            httpClient = OkHttpClient.Builder().retryOnConnectionFailure(false).build(),
            workerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            retryDelay = { delays += it },
            fileOperations = ops,
            taskNotifier = notifier,
        )

    private suspend fun verifySocketRetries(response: () -> MockResponse) {
        val server = MockWebServer().apply {
            repeat(3) { enqueue(response()) }
            enqueue(MockResponse(body = PNG))
            start()
        }
        val delays = mutableListOf<Long>()
        try {
            val manager = manager(server, delays)
            manager.enqueue(item(server))
            val job = manager.start()
            awaitEmpty(manager)
            job.cancel()
            delays shouldBe listOf(2_000L, 4_000L, 8_000L)
            server.requestCount shouldBe 4
        } finally {
            server.close()
        }
    }

    private fun item(server: MockWebServer) = DownloadItem(1, "Manga", "Chapter", server.port.toLong(), pageUrls = listOf(server.url("/page.gif").toString()))
    private fun downloadIdentity(suffix: String) = DownloadChapterIdentity(
        sourceDisplayName = "Retry Source $suffix",
        mangaTitle = "Retry Manga $suffix",
        chapterName = "Retry Chapter $suffix",
        scanlator = null,
        chapterUrl = "/chapter/$suffix",
        disallowNonAsciiFilenames = false,
    )
    private fun persistentStore(file: File): PersistentDownloadStore {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        runCatching { Database.Schema.create(driver) }
        return PersistentDownloadStore(Database(
            driver,
            historyAdapter = tachiyomi.data.History.Adapter(DateColumnAdapter),
            mangasAdapter = tachiyomi.data.Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
        ))
    }
    private suspend fun awaitEmpty(manager: DesktopDownloadManager) { repeat(200) { if (manager.queue.value.isEmpty()) return; delay(10) } }
    private suspend fun awaitError(manager: DesktopDownloadManager) { repeat(200) { if (manager.queue.value.singleOrNull()?.status == DownloadStatus.ERROR) return; delay(10) } }

    private enum class FailurePoint { EXECUTE, BODY, WRITE, RENAME }
    private data class SourceCase(
        val source: CatalogueSource?,
        val errorType: kotlin.reflect.KClass<out AppError>,
        val chapterUrl: String = "/chapter",
        val timeoutMs: Long = 30_000,
    )

    private class PageSource(private val pages: suspend () -> List<Page>) : CatalogueSource {
        override val id = 42L
        override val name = "pages"
        override val lang = "en"
        override val supportsLatest = false
        override suspend fun getPageList(chapter: SChapter) = pages()
        override suspend fun getMangaDetails(manga: SManga) = manga
        override suspend fun getChapterList(manga: SManga) = emptyList<SChapter>()
        override suspend fun getPopularManga(page: Int) = MangasPage(emptyList(), false)
        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList) = MangasPage(emptyList(), false)
        override suspend fun getLatestUpdates(page: Int) = MangasPage(emptyList(), false)
        override fun getFilterList() = FilterList()
    }
    private class FaultOperations(private val point: FailurePoint) : DownloadFileOperations {
        var failedStageAttempts = 0
        private fun failAt(stage: FailurePoint) {
            if (point == stage && ++failedStageAttempts <= 3) throw IOException(stage.name)
        }
        override fun execute(client: OkHttpClient, url: String): Response {
            failAt(FailurePoint.EXECUTE)
            return DefaultDownloadFileOperations.execute(client, url)
        }
        override suspend fun readBody(response: Response): ByteArray {
            failAt(FailurePoint.BODY)
            return DefaultDownloadFileOperations.readBody(response)
        }
        override fun writePage(tmp: File, bytes: ByteArray) {
            failAt(FailurePoint.WRITE)
            DefaultDownloadFileOperations.writePage(tmp, bytes)
        }
        override fun renamePage(tmp: File, final: File) = DefaultDownloadFileOperations.renamePage(tmp, final)
        override fun renameChapter(tmpDir: File, finalDir: File): Boolean {
            failAt(FailurePoint.RENAME)
            return DefaultDownloadFileOperations.renameChapter(tmpDir, finalDir)
        }
    }

    private companion object {
        const val PNG = "GIF89aDATA"
    }
}
