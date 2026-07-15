package mihon.desktop.download

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
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
import mihon.domain.error.AppError
import mihon.desktop.domain.DesktopNotificationService
import mihon.desktop.domain.DesktopSystemNotifier
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
    fun `page list boundary failures persist structured errors notify and retry clears them`() = runBlocking {
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
    fun `source resolution failure survives restart and retry clears persisted cause`() = runBlocking {
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
    fun `HTTP failures use 2 4 8 retry policy without sleeping`() = runBlocking {
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
    fun `connection socket failures use 2 4 8 retry policy`() = runBlocking {
        verifySocketRetries { MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build() }
    }

    @Test
    fun `response body socket failures use 2 4 8 retry policy`() = runBlocking {
        verifySocketRetries {
            MockResponse.Builder().body(PNG.repeat(1024)).onResponseBody(SocketEffect.CloseSocket()).build()
        }
    }

    @Test
    fun `exhausted server retries expose the final AppError`() = runBlocking {
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
    fun `HTTP 403 429 and 500 retain structured details on the queue item`() = runBlocking {
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
    fun `file permission storage and unknown failures map accurately`() = runBlocking {
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
    fun `cancelled item is not overwritten by a late worker failure`() = runBlocking {
        val server = MockWebServer().apply { enqueue(MockResponse(body = PNG)); start() }
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val ops = object : DownloadFileOperations by DefaultDownloadFileOperations {
            override fun writePage(tmp: File, bytes: ByteArray) {
                entered.complete(Unit)
                runBlocking { release.await() }
                throw IOException("late")
            }
        }
        try {
            val manager = manager(server, mutableListOf(), ops)
            val chapter = item(server)
            manager.enqueue(chapter)
            val job = manager.start()
            entered.await()
            manager.cancel(chapter.chapterId)
            release.complete(Unit)
            delay(50)
            job.cancel()
            manager.queue.value shouldBe emptyList()
            manager.failures.value.containsKey(chapter.chapterId) shouldBe false
        } finally { server.close() }
    }

    @Test
    fun `retry clears persisted failure and a later success clears transient failure`() = runBlocking {
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
    fun `terminal failure emits actionable notification`() = runBlocking {
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
    fun `execute body read page write and final rename failures each use 2 4 8 retry policy`() = runBlocking {
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
