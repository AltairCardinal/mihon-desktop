package mihon.desktop.download

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.tachiyomi.network.NetworkHelper
import io.kotest.matchers.shouldBe
import mihon.domain.download.DownloadQueueEntry
import mihon.domain.download.DownloadQueueStatus
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import mihon.domain.reader.content.DownloadChapterIdentity
import mihon.domain.reader.partial.PartialPageTable
import mihon.domain.reader.partial.PartialPageTableCompleteness
import mihon.domain.reader.partial.PartialPageTableEntry
import okhttp3.OkHttpClient
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.data.download.PersistentDownloadStore
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
    fun `versioned page metadata round trips nullable urls identity and error state`() {
        val dbFile = File(directory, "versioned-pages.db")
        val table = versionedTable()
        val identity = identity()
        persistentStore(dbFile).replaceAll(
            listOf(
                entry(DownloadQueueStatus.ERROR, 1).copy(
                    pageTable = table,
                    downloadIdentity = identity,
                    failure = AppError.Storage(IllegalStateException("offline disk")),
                ),
            ),
        )

        val restored = persistentStore(dbFile).entries().single()

        assertEquals(table, restored.pageTable)
        assertEquals(identity, restored.downloadIdentity)
        assertEquals(PartialPageTableCompleteness.COMPLETE, restored.pageTable.completeness)
        assertEquals(listOf(3, 17, 90), restored.pageTable.entries.map(PartialPageTableEntry::sourcePageIndex))
        assertEquals(listOf("https://img/1.jpg", null, "https://img/3.jpg"), restored.pageTable.entries.map(PartialPageTableEntry::imageUrl))
        assertTrue(restored.failure is AppError.Storage)
    }

    @Test
    fun `legacy future and malformed page json recover conservatively without dropping sibling rows`() {
        val dbFile = File(directory, "mixed-pages.db")
        val store = persistentStore(dbFile)
        store.replaceAll(
            listOf(
                entry(DownloadQueueStatus.QUEUED, 0, chapterId = 1L),
                entry(DownloadQueueStatus.ERROR, 0, chapterId = 2L),
                entry(DownloadQueueStatus.QUEUED, 0, chapterId = 3L),
                entry(DownloadQueueStatus.ERROR, 1, chapterId = 4L).copy(
                    pageTable = versionedTable(),
                    downloadIdentity = identity(),
                ),
            ),
        )
        updateRawPageUrls(dbFile, 1L, """["https://legacy/1.jpg","https://legacy/2.jpg"]""")
        updateRawPageUrls(dbFile, 2L, """{"type":"mihon-partial-page-table","version":99}""")
        updateRawPageUrls(dbFile, 3L, "{not-json")

        val restored = persistentStore(dbFile).entries().sortedBy { it.chapterId }

        assertEquals(listOf(1L, 2L, 3L, 4L), restored.map { it.chapterId })
        assertEquals(listOf("https://legacy/1.jpg", "https://legacy/2.jpg"), restored[0].pageUrls)
        assertEquals(PartialPageTableCompleteness.LEGACY_UNPROVEN, restored[0].pageTable.completeness)
        assertEquals(2, restored[0].pageTable.entries.size)
        assertTrue(restored[1].pageUrls.isEmpty())
        assertTrue(restored[1].pageTable.entries.isEmpty())
        assertEquals(DownloadQueueStatus.ERROR, restored[1].status)
        assertTrue(restored[2].pageUrls.isEmpty())
        assertTrue(restored[2].pageTable.entries.isEmpty())
        assertEquals(versionedTable(), restored[3].pageTable)
        assertEquals(identity(), restored[3].downloadIdentity)
    }

    @Test
    fun `process recovery preserves versioned metadata while normalizing downloading status`() {
        val dbFile = File(directory, "versioned-recovery.db")
        val expected = entry(DownloadQueueStatus.DOWNLOADING, 1).copy(
            pageTable = versionedTable(),
            downloadIdentity = identity(),
        )
        persistentStore(dbFile).replaceAll(listOf(expected))

        val recovered = persistentStore(dbFile).recover().single()

        assertEquals(DownloadQueueStatus.QUEUED, recovered.status)
        assertEquals(expected.pageTable, recovered.pageTable)
        assertEquals(expected.downloadIdentity, recovered.downloadIdentity)
        assertEquals(1, recovered.progress)
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
    fun `worker resumes valid pages removes stale tmp and downloads only missing pages`(): Unit = runBlocking {
        val server = MockWebServer().apply {
            repeat(3) { enqueue(MockResponse(body = PNG)) }
            start()
        }
        try {
            val dbFile = File(directory, "resume.db")
            val store = persistentStore(dbFile)
            store.replaceAll(listOf(entry(DownloadQueueStatus.DOWNLOADING, 1, retryCount = 2).copy(
                pageUrls = listOf(
                    server.url("/already.png").toString(),
                    server.url("/duplicate.png").toString(),
                    server.url("/corrupt.png").toString(),
                    server.url("/missing.png").toString(),
                ),
            )))
            val provider = DesktopDownloadProvider(File(directory, "downloads"))
            val tmp = provider.chapterTmpDir(3, "Manga", "Chapter").apply { mkdirs() }
            File(tmp, "001.gif").writeText(GIF)
            File(tmp, "002.gif").writeText(GIF)
            File(tmp, "002.jpg").writeText(GIF)
            File(tmp, "003.tmp").writeText("stale")
            File(tmp, "003.gif").writeText("corrupt")
            File(tmp, "005.gif").writeText(GIF)
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

            server.requestCount shouldBe 3
            retryCountsAtFinalRename shouldBe listOf(0)
            provider.isChapterDownloaded(3, "Manga", "Chapter") shouldBe true
            assertEquals(
                listOf("001.gif", "002.png", "003.png", "004.png"),
                provider.chapterDownloadDir(3, "Manga", "Chapter").listFiles().orEmpty().map(File::getName).sorted(),
            )
            persistentStore(dbFile).entries() shouldBe emptyList()
        } finally { server.close() }
    }

    @Test
    fun `recovered queue resolves canonical identity before resuming its write`(): Unit = runBlocking {
        val dbFile = File(directory, "canonical-recovery.db")
        val store = persistentStore(dbFile)
        store.replaceAll(listOf(entry(DownloadQueueStatus.QUEUED, 0).copy(
            pageUrls = listOf("https://fixture.invalid/001.jpg"),
        )))
        val provider = DesktopDownloadProvider(File(directory, "canonical-recovery-downloads"))
        val identity = DownloadChapterIdentity(
            sourceDisplayName = "Recovered Source",
            mangaTitle = "Manga",
            chapterName = "Chapter",
            scanlator = "Recovered Group",
            chapterUrl = "/chapter",
            disallowNonAsciiFilenames = false,
        )
        var resolvedChapterId: Long? = null
        val manager = DesktopDownloadManager(
            provider = provider,
            store = store,
            networkHelper = NetworkHelper(OkHttpClient()),
            workerScope = this,
            downloadIdentityResolver = { item ->
                resolvedChapterId = item.chapterId
                identity
            },
            fileOperations = object : DownloadFileOperations by DefaultDownloadFileOperations {
                override fun execute(client: OkHttpClient, url: String): Response = Response.Builder()
                    .request(Request.Builder().url(url).build())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(GIF.encodeToByteArray().toResponseBody())
                    .build()
            },
        )
        val worker = manager.start()
        try {
            withTimeout(5_000) {
                while (manager.queue.value.isNotEmpty()) delay(10)
            }

            assertEquals(1L, resolvedChapterId)
            assertTrue(provider.canonicalChapterDownloadDir(identity).isDirectory)
            assertEquals(
                provider.canonicalChapterDownloadDir(identity).absolutePath,
                provider.downloadArtifactLookup(sourceId = 3L).locate(identity)?.opaqueLocation,
            )
        } finally {
            worker.cancelAndJoin()
        }
    }

    @Test
    fun `versioned recovery reuses historical partial directory and publishes a complete canonical chapter`(): Unit =
        runBlocking {
            val server = MockWebServer().apply {
                enqueue(MockResponse(body = GIF))
                start()
            }
            try {
                val dbFile = File(directory, "historical-partial.db")
                val identity = identity()
                val table = PartialPageTable.complete(
                    listOf(
                        PartialPageTableEntry(0, 5, "/page/one", server.url("/one.jpg").toString()),
                        PartialPageTableEntry(1, 9, "/page/two", server.url("/two.jpg").toString()),
                    ),
                )
                persistentStore(dbFile).replaceAll(
                    listOf(
                        entry(DownloadQueueStatus.QUEUED, 1).copy(
                            pageTable = table,
                            downloadIdentity = identity,
                        ),
                    ),
                )
                val provider = DesktopDownloadProvider(File(directory, "historical-partial-downloads"))
                provider.chapterTmpDir(3L, identity.mangaTitle, identity.chapterName).apply {
                    mkdirs()
                    resolve("001.gif").writeText(GIF)
                }
                val manager = DesktopDownloadManager(
                    provider = provider,
                    store = persistentStore(dbFile),
                    httpClient = OkHttpClient(),
                    workerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                    retryDelay = {},
                )
                val worker = manager.start()
                try {
                    withTimeout(5_000) {
                        while (manager.queue.value.isNotEmpty()) delay(10)
                    }

                    assertEquals(1, server.requestCount)
                    assertEquals(
                        listOf("001.gif", "002.jpg"),
                        provider.canonicalChapterDownloadDir(identity).listFiles().orEmpty().map(File::getName).sorted(),
                    )
                    assertFalse(provider.chapterTmpDir(3L, identity.mangaTitle, identity.chapterName).exists())
                } finally {
                    worker.cancelAndJoin()
                    manager.stopAndJoin()
                }
            } finally {
                server.close()
            }
        }

    @Test
    fun `committed nullable image url page finalizes without resolving the source`(): Unit = runBlocking {
        val dbFile = File(directory, "committed-null-url.db")
        val identity = identity()
        persistentStore(dbFile).replaceAll(
            listOf(
                entry(DownloadQueueStatus.QUEUED, 1).copy(
                    pageTable = PartialPageTable.complete(
                        listOf(PartialPageTableEntry(0, 17, "/page/already", null)),
                    ),
                    downloadIdentity = identity,
                ),
            ),
        )
        val provider = DesktopDownloadProvider(File(directory, "committed-null-url-downloads"))
        provider.canonicalChapterTmpDir(identity).apply {
            mkdirs()
            resolve("001.gif").writeText(GIF)
        }
        val sourceCalls = AtomicInteger()
        val manager = DesktopDownloadManager(
            provider = provider,
            store = persistentStore(dbFile),
            httpClient = OkHttpClient(),
            workerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            sourceResolver = {
                sourceCalls.incrementAndGet()
                error("A committed page must not resolve its source")
            },
        )
        val worker = manager.start()
        try {
            withTimeout(5_000) {
                while (manager.queue.value.isNotEmpty()) delay(10)
            }

            assertEquals(0, sourceCalls.get())
            assertEquals(
                listOf("001.gif"),
                provider.canonicalChapterDownloadDir(identity).listFiles().orEmpty().map(File::getName),
            )
        } finally {
            worker.cancelAndJoin()
            manager.stopAndJoin()
        }
    }

    @Test
    fun `hundred page recovery reconciles once probes each candidate once and serves O one snapshots`() = runBlocking {
        val dbFile = File(directory, "indexed-recovery.db")
        val identity = identity()
        val table = PartialPageTable.complete(
            (0 until 100).map { ordinal ->
                PartialPageTableEntry(
                    readerOrdinal = ordinal,
                    sourcePageIndex = ordinal * 3 + 1,
                    pageUrl = "/page/$ordinal",
                    imageUrl = "https://img/$ordinal.jpg",
                )
            },
        )
        persistentStore(dbFile).replaceAll(
            listOf(
                entry(DownloadQueueStatus.ERROR, 37).copy(
                    pageTable = table,
                    downloadIdentity = identity,
                ),
            ),
        )
        val provider = DesktopDownloadProvider(File(directory, "indexed-recovery-downloads"))
        provider.canonicalChapterTmpDir(identity).apply {
            mkdirs()
            repeat(100) { ordinal ->
                resolve(DownloadPageFileNamingPolicy.committedFileName(ordinal, "jpg")).writeText(GIF)
            }
        }
        val listCalls = AtomicInteger()
        val headerCalls = AtomicInteger()
        val indexOperations = object : PartialDownloadIndexFileOperations {
            override fun isDirectory(directory: File): Boolean = directory.isDirectory

            override fun listFiles(directory: File): List<File> {
                listCalls.incrementAndGet()
                return directory.listFiles().orEmpty().toList()
            }

            override fun isValidCommittedPage(provider: DesktopDownloadProvider, file: File): Boolean {
                headerCalls.incrementAndGet()
                return provider.isValidDownloadedImage(file)
            }
        }
        val manager = DesktopDownloadManager(
            provider = provider,
            store = persistentStore(dbFile),
            workerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            partialIndexFileOperations = indexOperations,
        )
        try {
            manager.awaitPartialIndexRecovery()
            val snapshot = checkNotNull(manager.snapshot(1L, identity))

            assertEquals(100, snapshot.committedPages.size)
            assertEquals((0 until 100).toList(), snapshot.committedPages.map { it.readerOrdinal })
            assertEquals(1, listCalls.get())
            assertEquals(100, headerCalls.get())
            repeat(1_000) {
                assertEquals(100, manager.snapshot(1L, identity)?.committedPages?.size)
            }
            assertEquals(1, listCalls.get())
            assertEquals(100, headerCalls.get())
            assertFalse(provider.isChapterDownloaded(3L, identity))

            assertTrue(manager.retryItem(1L))
            assertNull(manager.snapshot(1L, identity), "A new generation must not expose the recovered generation index")
        } finally {
            manager.stopAndJoin()
        }
    }

    @Test
    fun `reconcile excludes staging zero corrupt out of range duplicate and mismatched identity pages`() = runBlocking {
        val dbFile = File(directory, "invalid-index-candidates.db")
        val identity = identity()
        persistentStore(dbFile).replaceAll(
            listOf(
                entry(DownloadQueueStatus.ERROR, 0).copy(
                    pageTable = versionedTable(),
                    downloadIdentity = identity,
                ),
            ),
        )
        val provider = DesktopDownloadProvider(File(directory, "invalid-index-downloads"))
        provider.canonicalChapterTmpDir(identity).apply {
            mkdirs()
            resolve("001.jpg").writeText(GIF)
            resolve("001.png").writeText(GIF)
            resolve("002.91.tmp").writeText(GIF)
            resolve("002.jpg").writeBytes(byteArrayOf())
            resolve("003.jpg").writeText("not an image")
            resolve("004.jpg").writeText(GIF)
        }
        val manager = DesktopDownloadManager(
            provider = provider,
            store = persistentStore(dbFile),
            workerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )
        try {
            manager.awaitPartialIndexRecovery()

            assertTrue(checkNotNull(manager.snapshot(1L, identity)).committedPages.isEmpty())
            assertTrue(provider.canonicalChapterTmpDir(identity).resolve("002.91.tmp").isFile)
            assertNull(manager.snapshot(1L, identity.copy(chapterUrl = "/other")))
            assertFalse(provider.isChapterDownloaded(3L, identity))
        } finally {
            manager.stopAndJoin()
        }
    }

    @Test
    fun `stale recovery reconcile cannot delete a newer generation staging file`() = runBlocking {
        val dbFile = File(directory, "stale-reconcile-generation.db")
        val identity = identity()
        persistentStore(dbFile).replaceAll(
            listOf(
                entry(DownloadQueueStatus.ERROR, 0).copy(
                    pageTable = versionedTable(),
                    downloadIdentity = identity,
                ),
            ),
        )
        val provider = DesktopDownloadProvider(File(directory, "stale-reconcile-generation-downloads"))
        val tmp = provider.canonicalChapterTmpDir(identity).apply { mkdirs() }
        val reconcileEntered = CountDownLatch(1)
        val releaseReconcile = CountDownLatch(1)
        val manager = DesktopDownloadManager(
            provider = provider,
            store = persistentStore(dbFile),
            workerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            partialIndexFileOperations = object : PartialDownloadIndexFileOperations {
                override fun isDirectory(directory: File): Boolean = directory.isDirectory

                override fun listFiles(directory: File): List<File> {
                    reconcileEntered.countDown()
                    check(releaseReconcile.await(5, TimeUnit.SECONDS))
                    return directory.listFiles().orEmpty().toList()
                }

                override fun isValidCommittedPage(provider: DesktopDownloadProvider, file: File): Boolean =
                    provider.isValidDownloadedImage(file)
            },
        )
        try {
            assertTrue(reconcileEntered.await(5, TimeUnit.SECONDS))
            assertTrue(manager.retryItem(1L))
            val newGenerationStaging = tmp.resolve(DownloadPageFileNamingPolicy.stagingFileName(0, 2L)).apply {
                writeText("new generation")
            }
            releaseReconcile.countDown()
            manager.awaitPartialIndexRecovery()

            assertTrue(newGenerationStaging.isFile)
        } finally {
            releaseReconcile.countDown()
            manager.stopAndJoin()
        }
    }

    @Test
    fun `cancel before recovery publish removes every alias before same id reenqueue`() = runBlocking {
        val dbFile = File(directory, "cancel-before-alias-publish.db")
        val identity = identity().copy(
            chapterName = "章节 中文",
            scanlator = "汉化组",
        )
        val table = PartialPageTable.complete(
            listOf(PartialPageTableEntry(0, 7, "/page/one", "https://fixture.invalid/new.jpg")),
        )
        persistentStore(dbFile).replaceAll(
            listOf(
                entry(DownloadQueueStatus.ERROR, 0).copy(
                    pageTable = table,
                    downloadIdentity = identity,
                ),
            ),
        )
        val provider = DesktopDownloadProvider(File(directory, "cancel-before-alias-publish-downloads"))
        val partialCandidates = provider.partialTmpDirectoryCandidates(3L, identity)
        assertTrue(partialCandidates.size >= 3, "Non-ASCII identity must expose distinct canonical aliases")
        partialCandidates.forEach { candidate ->
            candidate.mkdirs()
            candidate.resolve("001.gif").writeText(GIF)
        }
        assertTrue(partialCandidates.all(File::isDirectory))
        val reconcileEntered = CountDownLatch(1)
        val releaseReconcile = CountDownLatch(1)
        val cleanupFinished = CountDownLatch(1)
        val executedUrls = CopyOnWriteArrayList<String>()
        val manager = DesktopDownloadManager(
            provider = provider,
            store = persistentStore(dbFile),
            httpClient = OkHttpClient(),
            workerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            retirementCleanupFinishedObserver = { _, _ -> cleanupFinished.countDown() },
            partialIndexFileOperations = object : PartialDownloadIndexFileOperations {
                override fun isDirectory(directory: File): Boolean = directory.isDirectory

                override fun listFiles(directory: File): List<File> {
                    reconcileEntered.countDown()
                    check(releaseReconcile.await(5, TimeUnit.SECONDS))
                    return directory.listFiles().orEmpty().toList()
                }

                override fun isValidCommittedPage(provider: DesktopDownloadProvider, file: File): Boolean =
                    provider.isValidDownloadedImage(file)
            },
            fileOperations = object : DownloadFileOperations by DefaultDownloadFileOperations {
                override fun execute(client: OkHttpClient, url: String): Response {
                    executedUrls += url
                    return Response.Builder()
                        .request(Request.Builder().url(url).build())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(GIF.encodeToByteArray().toResponseBody())
                        .build()
                }
            },
        )
        try {
            assertTrue(reconcileEntered.await(5, TimeUnit.SECONDS))
            assertTrue(manager.cancel(1L))
            assertTrue(cleanupFinished.await(5, TimeUnit.SECONDS))
            manager.enqueue(
                DownloadItem(
                    sourceId = 3L,
                    mangaTitle = identity.mangaTitle,
                    chapterName = identity.chapterName,
                    chapterId = 1L,
                    chapterUrl = identity.chapterUrl,
                    pageUrls = listOf("https://fixture.invalid/new.jpg"),
                    pageTable = table,
                    downloadIdentity = identity,
                ),
            )
            releaseReconcile.countDown()
            manager.awaitPartialIndexRecovery()
            val worker = manager.start()
            try {
                withTimeout(5_000) {
                    while (manager.queue.value.singleOrNull()?.status !in setOf(null, DownloadStatus.ERROR)) delay(10)
                }
                assertTrue(manager.queue.value.isEmpty())
                assertEquals(listOf("https://fixture.invalid/new.jpg"), executedUrls)
                assertTrue(partialCandidates.none(File::exists))
            } finally {
                worker.cancelAndJoin()
            }
        } finally {
            releaseReconcile.countDown()
            manager.stopAndJoin()
        }
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

    private fun entry(
        status: DownloadQueueStatus,
        progress: Int,
        retryCount: Int = 0,
        chapterId: Long = 1L,
    ) = DownloadQueueEntry(
        chapterId = chapterId, mangaId = 2, sourceId = 3, mangaTitle = "Manga", chapterName = "Chapter",
        chapterUrl = "/chapter", pageUrls = listOf("https://example.com/1.jpg", "https://example.com/2.jpg"),
        status = status, progress = progress, position = 0, retryCount = retryCount,
    )

    private fun versionedTable() = PartialPageTable.complete(
        listOf(
            PartialPageTableEntry(0, 3, "/page/1", "https://img/1.jpg"),
            PartialPageTableEntry(1, 17, "/page/2", null),
            PartialPageTableEntry(2, 90, "/page/3", "https://img/3.jpg"),
        ),
    )

    private fun identity() = DownloadChapterIdentity(
        sourceDisplayName = "Source 中文",
        mangaTitle = "Manga",
        chapterName = "Chapter",
        scanlator = "Group",
        chapterUrl = "/chapter",
        disallowNonAsciiFilenames = false,
    )

    private fun updateRawPageUrls(file: File, chapterId: Long, rawJson: String) {
        JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use { driver ->
            driver.execute(null, "UPDATE download_queue SET page_urls = ? WHERE chapter_id = ?", 2) {
                bindString(0, rawJson)
                bindLong(1, chapterId)
            }
        }
    }

    private companion object {
        const val PNG = "GIF89aDATA"
        const val GIF = PNG
    }
}
