package tachiyomi.data.extension

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
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
import tachiyomi.domain.creator.repository.NoopCreatorLibraryIndexWriter
import tachiyomi.domain.manga.model.Manga
import java.nio.file.Files

abstract class SourceUpdateMemoPersistenceIntegrationTest {
    protected abstract fun open(path: String, create: Boolean): Storage

    @Test
    fun `schema 19 migration initializes memo without changing old chapter progress`() = runBlocking {
        val path = Files.createTempFile("source-memo-v19-", ".db")
        try {
            open(path.toString(), false).use { storage ->
                storage.driver.execute(
                    null,
                    """
                    CREATE TABLE mangas (
                        _id INTEGER PRIMARY KEY NOT NULL, source INTEGER NOT NULL, url TEXT NOT NULL,
                        artist TEXT, author TEXT, description TEXT, genre TEXT, title TEXT NOT NULL,
                        status INTEGER NOT NULL, thumbnail_url TEXT, favorite INTEGER NOT NULL,
                        last_update INTEGER, next_update INTEGER, initialized INTEGER NOT NULL,
                        viewer INTEGER NOT NULL, chapter_flags INTEGER NOT NULL,
                        cover_last_modified INTEGER NOT NULL, date_added INTEGER NOT NULL,
                        update_strategy INTEGER NOT NULL DEFAULT 0, calculate_interval INTEGER NOT NULL DEFAULT 0,
                        last_modified_at INTEGER NOT NULL DEFAULT 0, favorite_modified_at INTEGER,
                        version INTEGER NOT NULL DEFAULT 0, is_syncing INTEGER NOT NULL DEFAULT 0,
                        notes TEXT NOT NULL DEFAULT ''
                    )
                    """.trimIndent(),
                    0,
                )
                storage.driver.execute(
                    null,
                    """
                    CREATE TABLE chapters (
                        _id INTEGER PRIMARY KEY NOT NULL, manga_id INTEGER NOT NULL, url TEXT NOT NULL,
                        name TEXT NOT NULL, scanlator TEXT, read INTEGER NOT NULL, bookmark INTEGER NOT NULL,
                        last_page_read INTEGER NOT NULL, chapter_number REAL NOT NULL, source_order INTEGER NOT NULL,
                        date_fetch INTEGER NOT NULL, date_upload INTEGER NOT NULL, last_modified_at INTEGER NOT NULL DEFAULT 0,
                        version INTEGER NOT NULL DEFAULT 0, is_syncing INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY(manga_id) REFERENCES mangas(_id) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                    0,
                )
                storage.driver.execute(
                    null,
                    "CREATE TABLE excluded_scanlators(manga_id INTEGER NOT NULL, scanlator TEXT)",
                    0,
                )
                storage.driver.execute(
                    null,
                    """
                    INSERT INTO mangas (_id,source,url,title,status,favorite,initialized,viewer,chapter_flags,cover_last_modified,date_added,notes)
                    VALUES (1,42,'/old','Custom title',1,1,1,3,4,5,6,'User notes')
                    """.trimIndent(),
                    0,
                )
                storage.driver.execute(
                    null,
                    """
                    INSERT INTO chapters (_id,manga_id,url,name,read,bookmark,last_page_read,chapter_number,source_order,date_fetch,date_upload)
                    VALUES (2,1,'/chapter','Chapter 1',1,1,7,1.0,4,123,456)
                    """.trimIndent(),
                    0,
                )
                Database.Schema.migrate(storage.driver, 19, 20)
            }
            open(path.toString(), false).use { storage ->
                val manga = storage.mangas.getMangaById(1)
                val chapter = storage.chapters.getChapterByMangaId(1).single()
                assertEquals(Json.parseToJsonElement("{}").jsonObject, manga.memo)
                assertEquals(Json.parseToJsonElement("{}").jsonObject, chapter.memo)
                assertEquals("Custom title", manga.title)
                assertEquals("User notes", manga.notes)
                assertEquals(42, manga.source)
                assertEquals(2, chapter.id)
                assertEquals(true, chapter.read)
                assertEquals(true, chapter.bookmark)
                assertEquals(7, chapter.lastPageRead)
                assertEquals(4, chapter.sourceOrder)
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun `repository persists opaque manga and chapter memo across storage reopen with user state`() = runBlocking {
        val path = Files.createTempFile("source-memo-", ".db")
        val mangaMemo = Json.parseToJsonElement(
            """{"token":"中文","nested":{"id":9223372036854775807},"items":[null,true,2.5]}""",
        ).jsonObject
        val chapterMemo = Json.parseToJsonElement("""{"pages":[{"key":"secret","number":1}],"empty":{}}""").jsonObject
        try {
            val mangaId = open(path.toString(), true).use { storage ->
                val manga = storage.mangas.insertNetworkManga(
                    listOf(
                        Manga.create().copy(
                            source = 42,
                            url = "/manga",
                            title = "User title",
                            favorite = true,
                            memo = mangaMemo,
                        ),
                    ),
                ).single()
                storage.chapters.addAll(
                    listOf(
                        Chapter.create().copy(
                            mangaId = manga.id,
                            url = "/chapter",
                            name = "Chapter 1",
                            memo = chapterMemo,
                            read = true,
                            bookmark = true,
                            lastPageRead = 7,
                            sourceOrder = 4,
                        ),
                    ),
                )
                manga.id
            }
            open(path.toString(), false).use { storage ->
                val manga = storage.mangas.getMangaById(mangaId)
                val chapter = storage.chapters.getChapterByMangaId(mangaId).single()
                assertEquals(mangaMemo, manga.memo)
                assertEquals(chapterMemo, chapter.memo)
                assertEquals("User title", manga.title)
                assertEquals(42, manga.source)
                assertEquals(true, manga.favorite)
                assertEquals(true, chapter.read)
                assertEquals(true, chapter.bookmark)
                assertEquals(7, chapter.lastPageRead)
                assertEquals(4, chapter.sourceOrder)
                val empty = Json.parseToJsonElement("{}").jsonObject
                storage.mangas.update(tachiyomi.domain.manga.model.MangaUpdate(manga.id, memo = empty))
                storage.chapters.update(tachiyomi.domain.chapter.model.ChapterUpdate(chapter.id, memo = empty))
            }
            open(path.toString(), false).use { storage ->
                assertEquals(Json.parseToJsonElement("{}").jsonObject, storage.mangas.getMangaById(mangaId).memo)
                val chapter = storage.chapters.getChapterByMangaId(mangaId).single()
                assertEquals(Json.parseToJsonElement("{}").jsonObject, chapter.memo)
                assertEquals(true, chapter.read)
                assertEquals(true, chapter.bookmark)
                assertEquals(7, chapter.lastPageRead)
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    protected fun database(driver: SqlDriver, create: Boolean): Database {
        if (create) Database.Schema.create(driver)
        return Database(
            driver,
            historyAdapter = History.Adapter(DateColumnAdapter),
            mangasAdapter = Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
        )
    }

    protected class Storage(val driver: SqlDriver, handler: DatabaseHandler) : AutoCloseable {
        val mangas = MangaRepositoryImpl(handler, NoopCreatorLibraryIndexWriter)
        val chapters = ChapterRepositoryImpl(handler)
        override fun close() = driver.close()
    }
}
