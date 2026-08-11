package tachiyomi.data.manga

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.JvmDatabaseHandler
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.domain.creator.model.CreatorLibraryIndexEntry
import tachiyomi.domain.creator.repository.CreatorLibraryIndexWriter
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.LibraryMembershipUpdate

class MangaRepositoryCreatorIndexIntegrationTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var handler: JvmDatabaseHandler

    @BeforeEach
    fun setup() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        val database = Database(
            driver = driver,
            historyAdapter = tachiyomi.data.History.Adapter(last_readAdapter = DateColumnAdapter),
            mangasAdapter = tachiyomi.data.Mangas.Adapter(
                genreAdapter = StringListColumnAdapter,
                update_strategyAdapter = UpdateStrategyColumnAdapter,
            ),
        )
        handler = JvmDatabaseHandler(database, driver)
        seedManga()
    }

    @Test
    fun `explicit bibliography replacement can clear author and reaches index writer`() {
        runBlocking {
            driver.execute(null, "UPDATE mangas SET favorite = 1 WHERE _id = 1", 0)
            val writer = RecordingWriter()
            val repository = MangaRepositoryImpl(handler, writer)

            repository.update(MangaUpdate(id = 1L, author = null, updateAuthor = true)) shouldBe true

            queryString("SELECT author FROM mangas WHERE _id = 1") shouldBe null
            writer.entries.single().manga.author shouldBe null
        }
    }

    @Test
    fun `unrelated manga updates do not rescan creator bibliography`() {
        runBlocking {
            driver.execute(null, "UPDATE mangas SET favorite = 1 WHERE _id = 1", 0)
            val writer = RecordingWriter()
            val repository = MangaRepositoryImpl(handler, writer)

            repository.update(MangaUpdate(id = 1L, notes = "keep")) shouldBe true

            writer.entries shouldBe emptyList()
        }
    }

    @Test
    fun `index failure rolls back the manga membership mutation`() {
        runBlocking {
            val repository = MangaRepositoryImpl(handler, FailingWriter(IllegalStateException("index failed")))

            shouldThrow<IllegalStateException> {
                repository.updateMembershipsAtomically(
                    listOf(LibraryMembershipUpdate(1L, favorite = true, dateAdded = 1L, categoryIds = emptyList())),
                )
            }

            queryLong("SELECT favorite FROM mangas WHERE _id = 1") shouldBe 0L
        }
    }

    @Test
    fun `cancellation from index writer is propagated and rolls back`() {
        runBlocking {
            val repository = MangaRepositoryImpl(handler, FailingWriter(CancellationException("cancelled")))

            shouldThrow<CancellationException> {
                repository.update(MangaUpdate(id = 1L, favorite = true))
            }

            queryLong("SELECT favorite FROM mangas WHERE _id = 1") shouldBe 0L
        }
    }

    private fun seedManga() {
        driver.execute(
            null,
            "INSERT INTO mangas(_id, source, url, title, author, status, favorite, initialized, viewer, " +
                "chapter_flags, cover_last_modified, date_added) VALUES (1, 10, '/one', 'One', 'ONE', 0, 0, 0, 0, 0, 0, 0)",
            0,
        )
    }

    private fun queryLong(sql: String): Long = driver.executeQuery(
        null,
        sql,
        { cursor -> app.cash.sqldelight.db.QueryResult.Value(if (cursor.next().value) cursor.getLong(0)!! else -1L) },
        0,
    ).value

    private fun queryString(sql: String): String? = driver.executeQuery(
        null,
        sql,
        { cursor -> app.cash.sqldelight.db.QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null) },
        0,
    ).value

    private class RecordingWriter : CreatorLibraryIndexWriter {
        val entries = mutableListOf<CreatorLibraryIndexEntry>()

        override suspend fun indexLibraryMangaBatch(entries: List<CreatorLibraryIndexEntry>) {
            this.entries += entries
        }

        override suspend fun removeLibraryMangaIndex(mangaId: Long) = Unit

        override suspend fun removeStaleLibraryMangaIndexes() = Unit
    }

    private class FailingWriter(private val failure: Throwable) : CreatorLibraryIndexWriter {
        override suspend fun indexLibraryMangaBatch(entries: List<CreatorLibraryIndexEntry>): Unit = throw failure

        override suspend fun removeLibraryMangaIndex(mangaId: Long): Unit = throw failure

        override suspend fun removeStaleLibraryMangaIndexes(): Unit = throw failure
    }
}
