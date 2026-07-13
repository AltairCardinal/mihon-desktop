package mihon.desktop.download

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import mihon.domain.download.DownloadQueueEntry
import mihon.domain.download.DownloadQueueStatus
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import okhttp3.OkHttpClient
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.data.download.PersistentDownloadStore
import java.io.File
import mihon.domain.error.AppError

class DesktopDownloadRecoveryIntegrationTest {
    @TempDir lateinit var directory: File

    @Test
    fun `queue and partial page progress survive a database restart`() {
        val dbFile = File(directory, "mihon.db")
        persistentStore(dbFile).replaceAll(listOf(entry(status = DownloadQueueStatus.DOWNLOADING, progress = 1, retryCount = 2)))

        val recovered = persistentStore(dbFile).recover()

        recovered.single().status shouldBe DownloadQueueStatus.QUEUED
        recovered.single().progress shouldBe 1
        recovered.single().retryCount shouldBe 2
    }

    @Test
    fun `structured failure survives database restart and legacy null remains compatible`() {
        val dbFile = File(directory, "failure.db")
        persistentStore(dbFile).replaceAll(listOf(entry(DownloadQueueStatus.ERROR, 0).copy(
            failure = AppError.RateLimited(37, IllegalStateException("slow down")),
        )))

        val restored = persistentStore(dbFile).entries().single()
        (restored.failure as AppError.RateLimited).retryAfterSeconds shouldBe 37

        persistentStore(dbFile).replaceAll(listOf(restored.copy(failure = null)))
        persistentStore(dbFile).entries().single().failure shouldBe null
    }

    @Test
    fun `recover without downloading rows performs no queue rewrite`() {
        val dbFile = File(directory, "no-rewrite.db")
        val store = persistentStore(dbFile)
        store.replaceAll(listOf(entry(DownloadQueueStatus.QUEUED, 0)))
        val driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
        driver.execute(null, "CREATE TABLE delete_audit(count INTEGER NOT NULL)", 0)
        driver.execute(null, "INSERT INTO delete_audit VALUES(0)", 0)
        driver.execute(null, "CREATE TRIGGER audit_queue_delete AFTER DELETE ON download_queue BEGIN UPDATE delete_audit SET count = count + 1; END", 0)

        persistentStore(dbFile).recover()

        val writes = driver.executeQuery(null, "SELECT count FROM delete_audit", { cursor ->
            cursor.next()
            app.cash.sqldelight.db.QueryResult.Value(cursor.getLong(0)!!)
        }, 0).value
        writes shouldBe 0L
    }

    @Test
    fun `manager restores failure state after restart`() {
        val dbFile = File(directory, "manager-failure.db")
        persistentStore(dbFile).replaceAll(listOf(entry(DownloadQueueStatus.ERROR, 0).copy(
            failure = AppError.Storage(IllegalStateException("disk full")),
        )))

        val manager = DesktopDownloadManager(
            provider = DesktopDownloadProvider(File(directory, "downloads-failure")),
            store = persistentStore(dbFile),
        )

        (manager.queue.value.single().failure is AppError.Storage) shouldBe true
        (manager.failures.value[1] is AppError.Storage) shouldBe true
    }

    @Test
    fun `worker resumes valid pages removes stale tmp and downloads only missing pages`() = runBlocking {
        val server = MockWebServer().apply { enqueue(MockResponse(body = PNG)); start() }
        try {
            val dbFile = File(directory, "resume.db")
            val store = persistentStore(dbFile)
            store.replaceAll(listOf(entry(DownloadQueueStatus.DOWNLOADING, 1, retryCount = 2).copy(
                pageUrls = listOf(server.url("/already.png").toString(), server.url("/missing.png").toString()),
            )))
            val provider = DesktopDownloadProvider(File(directory, "downloads"))
            val tmp = provider.chapterTmpDir(3, "Manga", "Chapter").apply { mkdirs() }
            File(tmp, "001.gif").writeText(GIF)
            File(tmp, "002.tmp").writeText("stale")
            File(tmp, "002.gif").writeText("corrupt")
            val retryCountsAtFinalRename = mutableListOf<Int>()
            val operations = object : DownloadFileOperations by DefaultDownloadFileOperations {
                override fun renameChapter(tmpDir: File, finalDir: File): Boolean {
                    retryCountsAtFinalRename += persistentStore(dbFile).entries().single().retryCount
                    return DefaultDownloadFileOperations.renameChapter(tmpDir, finalDir)
                }
            }

            val manager = DesktopDownloadManager(
                provider = provider,
                httpClient = OkHttpClient(),
                store = persistentStore(dbFile),
                workerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                retryDelay = {},
                fileOperations = operations,
            )
            val job = manager.start()
            repeat(100) {
                if (manager.queue.value.isEmpty()) return@repeat
                delay(10)
            }
            job.cancel()

            server.requestCount shouldBe 1
            retryCountsAtFinalRename shouldBe listOf(0)
            provider.isChapterDownloaded(3, "Manga", "Chapter") shouldBe true
            persistentStore(dbFile).entries() shouldBe emptyList()
        } finally { server.close() }
    }

    private fun persistentStore(file: File): PersistentDownloadStore {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        runCatching { Database.Schema.create(driver) }
        return PersistentDownloadStore(
            Database(
                driver,
                historyAdapter = tachiyomi.data.History.Adapter(DateColumnAdapter),
                mangasAdapter = tachiyomi.data.Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
            ),
        )
    }

    private fun entry(status: DownloadQueueStatus, progress: Int, retryCount: Int = 0) = DownloadQueueEntry(
        chapterId = 1, mangaId = 2, sourceId = 3, mangaTitle = "Manga", chapterName = "Chapter",
        chapterUrl = "/chapter", pageUrls = listOf("https://example.com/1.jpg", "https://example.com/2.jpg"),
        status = status, progress = progress, position = 0, retryCount = retryCount,
    )

    private companion object {
        const val PNG = "GIF89aDATA"
        const val GIF = PNG
    }
}
