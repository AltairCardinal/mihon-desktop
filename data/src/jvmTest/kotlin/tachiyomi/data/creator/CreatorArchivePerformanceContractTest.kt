package tachiyomi.data.creator

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.JvmDatabaseHandler
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.creator.interactor.ExtractCreatorsFromManga
import tachiyomi.domain.creator.model.CreatorLibraryIndexEntry
import tachiyomi.domain.creator.repository.CreatorLibraryIndexWriter
import tachiyomi.domain.creator.service.CreatorLibraryIndexState
import tachiyomi.domain.creator.service.CreatorLibraryIndexer
import java.nio.file.Files
import java.util.Properties
import kotlin.io.path.absolutePathString

class CreatorArchivePerformanceContractTest {

    @Test
    fun `production backfill indexes ten thousand manga within the frozen budget and stays idempotent`() {
        val databasePath = Files.createTempFile("creator-archive-performance-", ".db")
        val driver = JdbcSqliteDriver(
            "jdbc:sqlite:${databasePath.absolutePathString()}",
            Properties().apply { setProperty("foreign_keys", "true") },
        )
        try {
            Database.Schema.create(driver)
            driver.execute(null, "PRAGMA foreign_keys = ON", 0)
            val database = Database(
                driver = driver,
                historyAdapter = tachiyomi.data.History.Adapter(last_readAdapter = DateColumnAdapter),
                mangasAdapter = tachiyomi.data.Mangas.Adapter(
                    genreAdapter = StringListColumnAdapter,
                    update_strategyAdapter = UpdateStrategyColumnAdapter,
                ),
            )
            val handler = JvmDatabaseHandler(database, driver)
            runBlocking { seedLibrary(handler, mangaCount = MANGA_COUNT) }
            var portableKey = 0L
            val archiveRepository = CreatorRepositoryImpl(
                handler = handler,
                clock = { 100L },
                portableKeyFactory = { "performance-${portableKey++}" },
            )
            val mangaRepository = MangaRepositoryImpl(handler, archiveRepository)
            val writer = BatchMeasuringWriter(archiveRepository)

            val firstRun = runBackfill(mangaRepository, writer)

            firstRun.firstProgressMillis.shouldBeLessThanOrEqual(FIRST_PROGRESS_BUDGET_MILLIS)
            firstRun.totalMillis.shouldBeLessThanOrEqual(TOTAL_BUDGET_MILLIS)
            writer.largestBatch shouldBe BATCH_SIZE
            queryLong(driver, "SELECT COUNT(*) FROM author_archive_source_works") shouldBe MANGA_COUNT.toLong()
            queryLong(driver, "SELECT COUNT(*) FROM author_archive_creators") shouldBe MENTION_COUNT.toLong()
            queryLong(driver, "SELECT COUNT(*) FROM author_archive_manga_links") shouldBe MENTION_COUNT.toLong()
            queryLong(driver, "SELECT COUNT(*) FROM author_archive_source_work_creators") shouldBe
                MENTION_COUNT.toLong()

            val countsBeforeReplay = archiveCounts(driver)
            val secondRun = runBackfill(mangaRepository, writer)

            secondRun.totalMillis.shouldBeLessThanOrEqual(TOTAL_BUDGET_MILLIS)
            archiveCounts(driver) shouldBe countsBeforeReplay
        } finally {
            driver.close()
            Files.deleteIfExists(databasePath)
        }
    }

    private fun runBackfill(
        mangaRepository: MangaRepositoryImpl,
        writer: CreatorLibraryIndexWriter,
    ): BackfillTiming = runBlocking {
        coroutineScope {
            val indexer = CreatorLibraryIndexer(
                mangaSource = mangaRepository,
                indexWriter = writer,
                extractCreators = ExtractCreatorsFromManga(),
                batchSize = BATCH_SIZE.toLong(),
            )
            val firstProgress = async(start = CoroutineStart.UNDISPATCHED) {
                indexer.state.filterIsInstance<CreatorLibraryIndexState.Indexing>().first()
            }
            val startedAt = System.nanoTime()
            indexer.start(this)
            withTimeout(FIRST_PROGRESS_TIMEOUT_MILLIS) { firstProgress.await() }
            val firstProgressMillis = elapsedMillis(startedAt)
            withTimeout(TEST_TIMEOUT_MILLIS) {
                indexer.state.filterIsInstance<CreatorLibraryIndexState.Ready>().first()
            }
            BackfillTiming(
                firstProgressMillis = firstProgressMillis,
                totalMillis = elapsedMillis(startedAt),
            )
        }
    }

    private suspend fun seedLibrary(handler: JvmDatabaseHandler, mangaCount: Int) {
        handler.await(inTransaction = true) {
            repeat(mangaCount) { offset ->
                val id = offset + 1L
                mangasQueries.insert(
                    source = 10L,
                    url = "/performance/$id",
                    artist = "Artist $id",
                    author = "Author $id",
                    description = null,
                    genre = null,
                    title = "Performance Manga $id",
                    status = 0L,
                    thumbnailUrl = null,
                    favorite = true,
                    lastUpdate = 0L,
                    nextUpdate = 0L,
                    initialized = false,
                    viewerFlags = 0L,
                    chapterFlags = 0L,
                    coverLastModified = 0L,
                    dateAdded = 1L,
                    updateStrategy = eu.kanade.tachiyomi.source.model.UpdateStrategy.ALWAYS_UPDATE,
                    calculateInterval = 0L,
                    version = 1L,
                    notes = "",
                )
            }
        }
    }

    private fun archiveCounts(driver: JdbcSqliteDriver): List<Long> = listOf(
        queryLong(driver, "SELECT COUNT(*) FROM author_archive_creators"),
        queryLong(driver, "SELECT COUNT(*) FROM author_archive_aliases"),
        queryLong(driver, "SELECT COUNT(*) FROM author_archive_source_works"),
        queryLong(driver, "SELECT COUNT(*) FROM author_archive_manga_links"),
        queryLong(driver, "SELECT COUNT(*) FROM author_archive_source_work_creators"),
    )

    private fun queryLong(driver: JdbcSqliteDriver, sql: String): Long = driver.executeQuery(
        null,
        sql,
        { cursor ->
            app.cash.sqldelight.db.QueryResult.Value(if (cursor.next().value) cursor.getLong(0)!! else -1L)
        },
        0,
    ).value

    private fun elapsedMillis(startedAt: Long): Long = (System.nanoTime() - startedAt) / 1_000_000L

    private data class BackfillTiming(
        val firstProgressMillis: Long,
        val totalMillis: Long,
    )

    private class BatchMeasuringWriter(
        private val delegate: CreatorLibraryIndexWriter,
    ) : CreatorLibraryIndexWriter {
        var largestBatch = 0
            private set

        override suspend fun indexLibraryMangaBatch(entries: List<CreatorLibraryIndexEntry>) {
            largestBatch = maxOf(largestBatch, entries.size)
            delegate.indexLibraryMangaBatch(entries)
        }

        override suspend fun removeLibraryMangaIndex(mangaId: Long) {
            delegate.removeLibraryMangaIndex(mangaId)
        }

        override suspend fun removeStaleLibraryMangaIndexes() {
            delegate.removeStaleLibraryMangaIndexes()
        }
    }

    private companion object {
        const val MANGA_COUNT = 10_000
        const val MENTION_COUNT = MANGA_COUNT * 2
        const val BATCH_SIZE = 250
        const val FIRST_PROGRESS_BUDGET_MILLIS = 500L
        const val TOTAL_BUDGET_MILLIS = 15_000L
        const val FIRST_PROGRESS_TIMEOUT_MILLIS = 5_000L
        const val TEST_TIMEOUT_MILLIS = 60_000L
    }
}
