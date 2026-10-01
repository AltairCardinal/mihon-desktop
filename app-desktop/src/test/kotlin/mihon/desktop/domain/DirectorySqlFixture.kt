package mihon.desktop.domain

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.JvmDatabaseHandler
import tachiyomi.data.Mangas
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.chapter.service.ChapterDirectoryCommit
import tachiyomi.domain.chapter.service.ChapterDirectoryResult
import tachiyomi.domain.creator.repository.NoopCreatorLibraryIndexWriter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository

/** Actual SQL repositories for the two existing directory consumer test suites. */
internal class DirectorySqlFixture(initialManga: Manga? = null) : AutoCloseable {
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    private val database = run {
        Database.Schema.create(driver)
        Database(
            driver,
            historyAdapter = History.Adapter(DateColumnAdapter),
            mangasAdapter = Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
        )
    }
    private val handler = JvmDatabaseHandler(database, driver)
    val mangas = MangaFixture(MangaRepositoryImpl(handler, NoopCreatorLibraryIndexWriter))
    val chapters = ChapterFixture(ChapterRepositoryImpl(handler))

    init {
        initialManga?.let(mangas::seed)
    }

    inner class MangaFixture(private val actual: MangaRepository) : MangaRepository by actual {
        fun seed(manga: Manga) = runBlocking {
            val inserted = actual.insertNetworkManga(listOf(manga)).single()
            if (inserted.id != manga.id) {
                driver.execute(null, "UPDATE mangas SET _id=? WHERE _id=?", 2) {
                    bindLong(0, manga.id)
                    bindLong(1, inserted.id)
                }
            }
        }
    }

    class ChapterFixture(private val actual: ChapterRepository) : ChapterRepository by actual {
        val addedChapters = mutableListOf<Chapter>()
        override suspend fun addAll(
            chapters: List<Chapter>,
        ): List<Chapter> = actual.addAll(chapters).also(addedChapters::addAll)
        override suspend fun syncDirectory(request: ChapterDirectoryCommit): ChapterDirectoryResult =
            actual.syncDirectory(request).also { addedChapters.addAll(it.added) }
    }

    override fun close() = driver.close()
}
