package tachiyomi.data.manga

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.JvmDatabaseHandler
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.LibraryMembershipUpdate

class MangaRepositoryMembershipIntegrationTest {

    @Test
    fun `membership update commits favorite date and categories together`() = runTest {
        databaseFixture().use { fixture ->
            val manga = fixture.insertManga("/target")
            val categoryId = fixture.insertCategory("Action")

            fixture.repository.updateMembershipsAtomically(
                listOf(LibraryMembershipUpdate(manga.id, true, 123, listOf(categoryId))),
            )

            assertEquals(true, fixture.repository.getMangaById(manga.id).favorite)
            assertEquals(123, fixture.repository.getMangaById(manga.id).dateAdded)
            assertEquals(listOf(categoryId), fixture.categoryIds(manga.id))
        }
    }

    @Test
    fun `invalid category rolls back every manga membership update`() = runTest {
        databaseFixture().use { fixture ->
            val source = fixture.insertManga("/source")
            val target = fixture.insertManga("/target")
            val categoryId = fixture.insertCategory("Action")
            fixture.repository.updateMembershipsAtomically(
                listOf(LibraryMembershipUpdate(source.id, true, 50, listOf(categoryId))),
            )

            assertThrows(Exception::class.java) {
                kotlinx.coroutines.runBlocking {
                    fixture.repository.updateMembershipsAtomically(
                        listOf(
                            LibraryMembershipUpdate(target.id, true, 100, listOf(categoryId)),
                            LibraryMembershipUpdate(source.id, false, 0, listOf(999)),
                        ),
                    )
                }
            }

            assertEquals(false, fixture.repository.getMangaById(target.id).favorite)
            assertEquals(0, fixture.repository.getMangaById(target.id).dateAdded)
            assertEquals(emptyList<Long>(), fixture.categoryIds(target.id))
            assertEquals(true, fixture.repository.getMangaById(source.id).favorite)
            assertEquals(50, fixture.repository.getMangaById(source.id).dateAdded)
            assertEquals(listOf(categoryId), fixture.categoryIds(source.id))
        }
    }

    private fun databaseFixture(): Fixture {
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
        return Fixture(driver, handler, MangaRepositoryImpl(handler))
    }

    private class Fixture(
        private val driver: JdbcSqliteDriver,
        private val handler: JvmDatabaseHandler,
        val repository: MangaRepositoryImpl,
    ) : AutoCloseable {
        suspend fun insertManga(url: String): Manga = repository.insertNetworkManga(
            listOf(Manga.create().copy(source = 1, url = url, title = url)),
        ).single()

        suspend fun insertCategory(name: String): Long = handler.await {
            categoriesQueries.insert(name, 0, 0)
        }.value

        suspend fun categoryIds(mangaId: Long): List<Long> = handler.awaitList {
            categoriesQueries.getCategoriesByMangaId(mangaId) { id, _, _, _ -> id }
        }

        override fun close() = driver.close()
    }
}
