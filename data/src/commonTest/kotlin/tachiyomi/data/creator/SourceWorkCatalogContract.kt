package tachiyomi.data.creator

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.data.Database
import tachiyomi.data.DatabaseHandler
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.Mangas
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.creator.model.ChapterCatalogCompleteness
import tachiyomi.domain.creator.model.SourceWorkNaturalKey
import tachiyomi.domain.manga.model.Manga

/** Same repository contract on Android and JVM production database handlers. */
abstract class SourceWorkCatalogContract {
    protected abstract fun open(): Storage

    @Test
    fun `catalogue lookup uses exact source identity and unbound discovery may be linked`() = runBlocking {
        open().use { storage ->
            val mangas =
                MangaRepositoryImpl(storage.handler, tachiyomi.domain.creator.repository.NoopCreatorLibraryIndexWriter)
            val manga = mangas.insertNetworkManga(
                listOf(Manga.create().copy(source = 42, url = "/work", title = "Work")),
            ).single()
            val archive = CreatorRepositoryImpl(storage.handler)
            val key = SourceWorkNaturalKey(42, "/work")
            assertNull(archive.getSourceWorkCatalog(key, manga.id))
            archive.upsertSourceWork(42, "/work", null, "Work", null, null, null, null)
            assertNull(archive.getSourceWorkCatalog(key, manga.id)?.mangaId)
            archive.upsertSourceWork(42, "/work", manga.id, "Work", null, null, null, null)
            archive.updateSourceWorkCatalog(key, 1, ChapterCatalogCompleteness.COMPLETE, null, 100, manga.id)
            val result = requireNotNull(archive.getSourceWorkCatalog(key, manga.id))
            assertEquals(manga.id, result.mangaId)
            assertEquals(1L, result.chapterCount)
            assertEquals(ChapterCatalogCompleteness.COMPLETE, result.completeness)
            assertNull(archive.getSourceWorkCatalog(SourceWorkNaturalKey(43, "/work"), manga.id))
            assertNull(archive.getSourceWorkCatalog(SourceWorkNaturalKey(42, "/other"), manga.id))
            assertThrows(IllegalStateException::class.java) {
                runBlocking {
                    archive.getSourceWorkCatalog(
                        key,
                        manga.id + 1,
                    )
                }
            }
            assertEquals(result, archive.getSourceWorkCatalog(key, manga.id))
        }
    }

    @Test
    fun `source metadata update preserves latest user state and transaction rolls back evidence`() = runBlocking {
        open().use { storage ->
            val manga = MangaRepositoryImpl(
                storage.handler,
                tachiyomi.domain.creator.repository.NoopCreatorLibraryIndexWriter,
            ).insertNetworkManga(listOf(Manga.create().copy(source = 42, url = "/work", title = "Work"))).single()
            val chapters = ChapterRepositoryImpl(storage.handler)
            val chapter = chapters.addAll(
                listOf(
                    Chapter.create().copy(
                        mangaId = manga.id,
                        url = "/2",
                        name = "Chapter 2",
                        read = true,
                        bookmark = true,
                        lastPageRead = 8,
                        dateFetch = 50,
                    ),
                ),
            ).single()
            chapters.update(ChapterUpdate(chapter.id, sourceOrder = 1, chapterNumber = 2.0))
            val after = requireNotNull(chapters.getChapterById(chapter.id))
            assertTrue(after.read)
            assertTrue(after.bookmark)
            assertEquals(8L, after.lastPageRead)
            assertEquals(50L, after.dateFetch)
            val archive = CreatorRepositoryImpl(storage.handler)
            assertThrows(IllegalStateException::class.java) {
                runBlocking {
                    storage.handler.await(inTransaction = true) {
                        archive.upsertSourceWork(42, "/work", manga.id, "Work", null, null, null, null)
                        archive.updateSourceWorkCatalog(
                            SourceWorkNaturalKey(42, "/work"),
                            1,
                            ChapterCatalogCompleteness.COMPLETE,
                            null,
                            100,
                            manga.id,
                        )
                        chapters.update(ChapterUpdate(chapter.id, sourceOrder = 7))
                        error("injected transaction failure")
                    }
                }
            }
            assertEquals(after, chapters.getChapterById(chapter.id))
            assertNull(archive.getSourceWorkCatalog(SourceWorkNaturalKey(42, "/work"), manga.id))
        }
    }

    protected fun database(driver: SqlDriver): Database {
        Database.Schema.create(driver)
        return Database(
            driver,
            History.Adapter(DateColumnAdapter),
            Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
        )
    }
    protected class Storage(val driver: SqlDriver, val handler: DatabaseHandler) : AutoCloseable {
        override fun close() = driver.close()
    }
}
