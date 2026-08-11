package mihon.desktop.backup

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.JvmDatabaseHandler
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.data.creator.CreatorRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.manga.model.Manga

class DesktopBackupBibliographyIntegrationTest {

    @Test
    fun `newer Desktop backup clears null bibliography through the real manga index transaction`() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
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
        val creatorRepository = CreatorRepositoryImpl(handler)
        val mangaRepository = MangaRepositoryImpl(handler, creatorRepository)
        val existing = mangaRepository.insertNetworkManga(
            listOf(
                Manga.create().copy(
                    source = 42L,
                    url = "/restore-null",
                    title = "Restore null",
                    author = "Old Author",
                    artist = "Old Artist",
                    favorite = true,
                    initialized = true,
                    version = 1L,
                ),
            ),
        ).single()
        val categoryRepository = mockk<CategoryRepository>()
        coEvery { categoryRepository.getAll() } returns emptyList()

        val result = DesktopBackupRestorer(
            mangaRepository = mangaRepository,
            chapterRepository = mockk<ChapterRepository>(),
            categoryRepository = categoryRepository,
            historyRepository = mockk<HistoryRepository>(),
        ).restore(
            mihon.desktop.backup.models.Backup(
                backupManga = listOf(
                    mihon.desktop.backup.models.BackupManga(
                        source = 42L,
                        url = "/restore-null",
                        title = "Restore null",
                        author = null,
                        artist = null,
                        favorite = true,
                        initialized = true,
                        version = 2L,
                    ),
                ),
            ),
        )

        result.successCount shouldBe 1
        mangaRepository.getMangaById(existing.id).let { restored ->
            restored.author shouldBe null
            restored.artist shouldBe null
        }
        queryLong(driver, "SELECT COUNT(*) FROM author_archive_manga_links WHERE manga_id = ${existing.id}") shouldBe 0L
        queryLong(
            driver,
            "SELECT COUNT(*) FROM author_archive_source_work_creators SWC " +
                "JOIN author_archive_source_works SW ON SW._id = SWC.source_work_id " +
                "WHERE SW.manga_id = ${existing.id}",
        ) shouldBe 0L
        driver.close()
    }

    private fun queryLong(driver: JdbcSqliteDriver, sql: String): Long = driver.executeQuery(
        null,
        sql,
        { cursor -> app.cash.sqldelight.db.QueryResult.Value(if (cursor.next().value) cursor.getLong(0)!! else -1L) },
        0,
    ).value
}
