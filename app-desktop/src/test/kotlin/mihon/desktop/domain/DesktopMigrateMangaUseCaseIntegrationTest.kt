package mihon.desktop.domain

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.JvmDatabaseHandler
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.data.category.CategoryRepositoryImpl
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.LibraryMembershipUpdate
import tachiyomi.domain.manga.repository.MangaRepository

class DesktopMigrateMangaUseCaseIntegrationTest {

    @Test
    fun `category failure rolls back source and target migration membership`() = runTest {
        fixture(faultMembership = true).use { f ->
            val categoryId = f.insertCategory("Action")
            val source = f.insertManga("/source")
            f.mangas.update(
                MangaUpdate(
                    id = source.id,
                    chapterFlags = 0x35L,
                    viewerFlags = 0x62L,
                    notes = "source notes",
                ),
            )
            f.mangas.updateMembershipsAtomically(listOf(LibraryMembershipUpdate(source.id, true, 40, listOf(categoryId))))
            val favoriteSource = f.mangas.getMangaById(source.id)

            assertThrows(Exception::class.java) {
                kotlinx.coroutines.runBlocking {
                    f.useCase.await(favoriteSource, target("/target"), 2, emptyList(), replace = true)
                }
            }

            val target = requireNotNull(f.mangas.getMangaByUrlAndSourceId("/target", 2))
            assertEquals(false, target.favorite)
            assertEquals(0, target.dateAdded)
            assertEquals(0, target.chapterFlags)
            assertEquals(0, target.viewerFlags)
            assertEquals("", target.notes)
            assertEquals(emptyList<Long>(), f.categoryIds(target.id))
            assertEquals(true, f.mangas.getMangaById(source.id).favorite)
            assertEquals(40, f.mangas.getMangaById(source.id).dateAdded)
            assertEquals(listOf(categoryId), f.categoryIds(source.id))
        }
    }

    @Test
    fun `copy categories and replace move real library membership without half state`() = runTest {
        fixture().use { f ->
            val categoryId = f.insertCategory("Action")
            val source = f.insertManga("/source")
            f.mangas.updateMembershipsAtomically(listOf(LibraryMembershipUpdate(source.id, true, 40, listOf(categoryId))))

            val target = f.useCase.await(source, target("/target"), 2, emptyList(), replace = true)

            assertEquals(true, f.mangas.getMangaById(target.id).favorite)
            assertEquals(listOf(categoryId), f.categoryIds(target.id))
            assertEquals(false, f.mangas.getMangaById(source.id).favorite)
            assertEquals(0, f.mangas.getMangaById(source.id).dateAdded)
            assertEquals(emptyList<Long>(), f.categoryIds(source.id))
        }
    }

    @Test
    fun `copy categories false and replace false preserve source and recalculate target date`() = runTest {
        fixture().use { f ->
            val categoryId = f.insertCategory("Action")
            val source = f.insertManga("/source")
            f.mangas.updateMembershipsAtomically(listOf(LibraryMembershipUpdate(source.id, true, 40, listOf(categoryId))))

            val target = f.useCase.await(
                source,
                target("/target"),
                2,
                emptyList(),
                options = MigrationOptions(copyCategories = false),
                replace = false,
            )

            val savedTarget = f.mangas.getMangaById(target.id)
            assertEquals(true, savedTarget.favorite)
            assertEquals(true, savedTarget.dateAdded > 40)
            assertEquals(emptyList<Long>(), f.categoryIds(target.id))
            assertEquals(true, f.mangas.getMangaById(source.id).favorite)
            assertEquals(40, f.mangas.getMangaById(source.id).dateAdded)
            assertEquals(listOf(categoryId), f.categoryIds(source.id))
        }
    }

    @Test
    fun `copy categories false preserves categories already assigned to favorite target`() = runTest {
        fixture().use { f ->
            val targetCategoryId = f.insertCategory("Target category")
            val sourceCategoryId = f.insertCategory("Source category")
            val source = f.insertManga("/source")
            val target = f.insertManga("/target", sourceId = 2)
            f.mangas.updateMembershipsAtomically(
                listOf(
                    LibraryMembershipUpdate(source.id, true, 40, listOf(sourceCategoryId)),
                    LibraryMembershipUpdate(target.id, true, 20, listOf(targetCategoryId)),
                ),
            )
            f.failOnCategoryDelete(target.id)

            val migrated = f.useCase.await(
                f.mangas.getMangaById(source.id),
                target("/target"),
                2,
                emptyList(),
                options = MigrationOptions(copyCategories = false),
                replace = false,
            )

            assertEquals(listOf(targetCategoryId), f.categoryIds(migrated.id))
            assertEquals(listOf(sourceCategoryId), f.categoryIds(source.id))
        }
    }

    @Test
    fun `migration persists source chapter and viewer flags on target`() = runTest {
        fixture().use { f ->
            val source = f.insertManga("/source")
            f.mangas.update(MangaUpdate(id = source.id, chapterFlags = 0x35L, viewerFlags = 0x62L))

            val migrated = f.useCase.await(
                f.mangas.getMangaById(source.id),
                target("/target"),
                2,
                emptyList(),
                replace = false,
            )

            val savedTarget = f.mangas.getMangaById(migrated.id)
            assertEquals(0x35L, savedTarget.chapterFlags)
            assertEquals(0x62L, savedTarget.viewerFlags)
        }
    }

    @Test
    fun `copy notes true replaces target notes with source notes`() = runTest {
        fixture().use { f ->
            val source = f.insertManga("/source")
            val target = f.insertManga("/target", sourceId = 2)
            f.mangas.update(MangaUpdate(id = source.id, notes = "source notes"))
            f.mangas.update(MangaUpdate(id = target.id, notes = "target notes"))

            val migrated = f.useCase.await(
                f.mangas.getMangaById(source.id),
                target("/target"),
                2,
                emptyList(),
                options = MigrationOptions(copyNotes = true),
                replace = false,
            )

            assertEquals("source notes", f.mangas.getMangaById(migrated.id).notes)
        }
    }

    @Test
    fun `copy notes false preserves existing target notes`() = runTest {
        fixture().use { f ->
            val source = f.insertManga("/source")
            val target = f.insertManga("/target", sourceId = 2)
            f.mangas.update(MangaUpdate(id = source.id, notes = "source notes"))
            f.mangas.update(MangaUpdate(id = target.id, notes = "target notes"))

            val migrated = f.useCase.await(
                f.mangas.getMangaById(source.id),
                target("/target"),
                2,
                emptyList(),
                options = MigrationOptions(copyNotes = false),
                replace = false,
            )

            assertEquals("target notes", f.mangas.getMangaById(migrated.id).notes)
        }
    }

    @Test
    fun `copy migration replaces existing target date with invocation time`() = runTest {
        fixture().use { f ->
            val source = f.insertManga("/source")
            val target = f.insertManga("/target", sourceId = 2)
            f.mangas.updateMembershipsAtomically(
                listOf(
                    LibraryMembershipUpdate(source.id, true, 40, emptyList()),
                    LibraryMembershipUpdate(target.id, true, 5, emptyList()),
                ),
            )
            val beforeInvocation = System.currentTimeMillis()

            val migrated = f.useCase.await(
                f.mangas.getMangaById(source.id),
                target("/target"),
                2,
                emptyList(),
                replace = false,
            )
            val afterInvocation = System.currentTimeMillis()

            val targetDateAdded = f.mangas.getMangaById(migrated.id).dateAdded
            assertEquals(true, targetDateAdded in beforeInvocation..afterInvocation)
        }
    }

    @Test
    fun `replace migration returns the final persisted source date and flags`() = runTest {
        fixture().use { f ->
            val source = f.insertManga("/source")
            f.mangas.update(MangaUpdate(id = source.id, chapterFlags = 0x35L, viewerFlags = 0x62L))
            f.mangas.updateMembershipsAtomically(
                listOf(LibraryMembershipUpdate(source.id, true, 40, emptyList())),
            )

            val returned = f.useCase.await(
                f.mangas.getMangaById(source.id),
                target("/target"),
                2,
                emptyList(),
                replace = true,
            )

            val persisted = f.mangas.getMangaById(returned.id)
            assertEquals(40, persisted.dateAdded)
            assertEquals(
                listOf(persisted.dateAdded, persisted.chapterFlags, persisted.viewerFlags),
                listOf(returned.dateAdded, returned.chapterFlags, returned.viewerFlags),
            )
        }
    }

    @Test
    fun `chapter adapter preserves target read progress beyond source and unknown numbers`() = runTest {
        fixture().use { f ->
            val source = f.insertManga("/source", sourceId = 1)
            val target = f.insertManga("/target", sourceId = 2)
            f.chapters.addAll(
                listOf(
                    Chapter.create().copy(
                        mangaId = source.id,
                        url = "/source-2",
                        name = "Source 2",
                        chapterNumber = 2.0,
                        read = true,
                    ),
                    Chapter.create().copy(
                        mangaId = target.id,
                        url = "/target-1",
                        name = "Target 1",
                        chapterNumber = 1.0,
                        read = false,
                    ),
                    Chapter.create().copy(
                        mangaId = target.id,
                        url = "/target-3",
                        name = "Target 3",
                        chapterNumber = 3.0,
                        read = true,
                    ),
                    Chapter.create().copy(
                        mangaId = target.id,
                        url = "/target-unknown",
                        name = "Target unknown",
                        chapterNumber = -1.0,
                        read = true,
                    ),
                ),
            )

            f.useCase.await(
                source,
                target("/target"),
                2,
                listOf(
                    sourceChapter("/target-1", "Target 1", 1.0),
                    sourceChapter("/target-3", "Target 3", 3.0),
                    sourceChapter("/target-unknown", "Target unknown", -1.0),
                ),
                replace = false,
            )

            val readByUrl = f.chapters.getChapterByMangaId(target.id).associate { it.url to it.read }
            assertEquals(true, readByUrl.getValue("/target-1"))
            assertEquals(true, readByUrl.getValue("/target-3"))
            assertEquals(true, readByUrl.getValue("/target-unknown"))
        }
    }

    private fun target(url: String) = SManga.create().apply {
        this.url = url
        title = "Target"
    }

    private fun sourceChapter(url: String, name: String, number: Double) =
        eu.kanade.tachiyomi.source.model.SChapter.create().apply {
            this.url = url
            this.name = name
            chapter_number = number.toFloat()
        }

    private fun fixture(faultMembership: Boolean = false): Fixture {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        val database = Database(
            driver,
            tachiyomi.data.History.Adapter(DateColumnAdapter),
            tachiyomi.data.Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
        )
        val handler = JvmDatabaseHandler(database, driver)
        val mangas = MangaRepositoryImpl(handler)
        val migrationMangas: MangaRepository = if (faultMembership) FaultingMangaRepository(mangas) else mangas
        val chapters = ChapterRepositoryImpl(handler)
        val categories = CategoryRepositoryImpl(handler)
        return Fixture(
            driver,
            handler,
            mangas,
            chapters,
            DesktopMigrateMangaUseCase(
                SaveSourceMangaForDetails(NetworkToLocalManga(migrationMangas), migrationMangas, chapters),
                GetChaptersByMangaId(chapters),
                UpdateChapter(chapters),
                GetCategories(categories),
                migrationMangas,
            ),
        )
    }

    private class FaultingMangaRepository(private val delegate: MangaRepository) : MangaRepository by delegate {
        private var singleUpdates = 0

        override suspend fun updateAtomically(update: LibraryMembershipUpdate) {
            singleUpdates++
            if (singleUpdates == 2) error("controlled membership failure")
            delegate.updateAtomically(update)
        }

        override suspend fun updateMembershipsAtomically(updates: List<LibraryMembershipUpdate>) {
            val invalid = updates.mapIndexed { index, update ->
                if (index == updates.lastIndex) update.copy(categoryIds = listOf(999)) else update
            }
            delegate.updateMembershipsAtomically(invalid)
        }
    }

    private class Fixture(
        private val driver: JdbcSqliteDriver,
        private val handler: JvmDatabaseHandler,
        val mangas: MangaRepositoryImpl,
        val chapters: ChapterRepositoryImpl,
        val useCase: DesktopMigrateMangaUseCase,
    ) : AutoCloseable {
        suspend fun insertManga(url: String, sourceId: Long = 1): Manga = mangas.insertNetworkManga(
            listOf(Manga.create().copy(source = sourceId, url = url, title = url)),
        ).single()

        suspend fun insertCategory(name: String): Long = handler.await {
            categoriesQueries.insert(name, 0, 0)
        }.value

        suspend fun categoryIds(mangaId: Long): List<Long> = handler.awaitList {
            categoriesQueries.getCategoriesByMangaId(mangaId) { id, _, _, _ -> id }
        }

        fun failOnCategoryDelete(mangaId: Long) {
            driver.execute(
                null,
                """
                CREATE TRIGGER abort_target_category_delete
                BEFORE DELETE ON mangas_categories
                WHEN OLD.manga_id = $mangaId
                BEGIN
                    SELECT RAISE(ABORT, 'target categories must not be rewritten');
                END
                """.trimIndent(),
                0,
            )
        }

        override fun close() = driver.close()
    }
}
