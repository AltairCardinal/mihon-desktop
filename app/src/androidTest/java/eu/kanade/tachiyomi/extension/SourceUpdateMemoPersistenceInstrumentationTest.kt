package eu.kanade.tachiyomi.extension

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import eu.kanade.domain.chapter.model.toDbChapter
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tachiyomi.data.AndroidDatabaseHandler
import tachiyomi.data.Database
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
import tachiyomi.domain.source.service.SourceMangaUpdateService
import java.util.UUID

/** ART storage/adapter evidence; full APK installation and reader UI are separate acceptance gates. */
class SourceUpdateMemoPersistenceInstrumentationTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val mangaMemo = Json.parseToJsonElement(
        """{"token":"中文","nested":{"id":9223372036854775807},"items":[null,true,2.5]}""",
    ).jsonObject
    private val chapterMemo = Json.parseToJsonElement("""{"pages":[{"key":"opaque"}],"empty":{}}""").jsonObject

    @Test
    fun reopenedAndroidDatabasePassesMemoToUnifiedSourceAndLegacyReaderChapter() = runBlocking {
        withDatabase { name ->
            val mangaId = open(name).use { storage ->
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
                            read = true,
                            bookmark = true,
                            lastPageRead = 7,
                            sourceOrder = 4,
                            memo = chapterMemo,
                        ),
                    ),
                )
                manga.id
            }
            open(name).use { storage ->
                val manga = storage.mangas.getMangaById(mangaId)
                val chapters = storage.chapters.getChapterByMangaId(mangaId)
                var updates = 0
                var pageRequests = 0
                val source = object : Source {
                    override val id = 42L
                    override val name = "ART memo source"

                    override suspend fun getMangaUpdate(
                        manga: SManga,
                        chapters: List<SChapter>,
                        fetchDetails: Boolean,
                        fetchChapters: Boolean,
                    ): SMangaUpdate {
                        updates++
                        assertTrue(fetchDetails && fetchChapters)
                        assertEquals(mangaMemo, manga.memo)
                        assertEquals(chapterMemo, chapters.single().memo)
                        return SMangaUpdate(manga, chapters)
                    }

                    override suspend fun getPageList(chapter: SChapter): List<Page> {
                        pageRequests++
                        assertEquals(chapterMemo, chapter.memo)
                        return listOf(Page(0, imageUrl = "https://fixture.invalid/page.png"))
                    }
                }
                SourceMangaUpdateService().await(source, manga, chapters, fetchDetails = true, fetchChapters = true)
                assertEquals(1, source.getPageList(chapters.single().toDbChapter()).size)
                assertEquals(1, updates)
                assertEquals(1, pageRequests)
                assertEquals("User title", manga.title)
                assertTrue(manga.favorite)
                assertTrue(chapters.single().read && chapters.single().bookmark)
                assertEquals(7L, chapters.single().lastPageRead)
                assertEquals(4L, chapters.single().sourceOrder)
            }
        }
    }

    @Test
    fun schemaNineteenAndroidUpgradePreservesOldDataAndInitializesMemo() = runBlocking {
        withDatabase { name ->
            // Frozen relevant schema-19 tables, not a latest-schema database relabelled as old.
            context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
                old.execSQL(
                    """CREATE TABLE mangas (
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
                    )""",
                )
                old.execSQL(
                    """CREATE TABLE chapters (
                        _id INTEGER PRIMARY KEY NOT NULL, manga_id INTEGER NOT NULL, url TEXT NOT NULL,
                        name TEXT NOT NULL, scanlator TEXT, read INTEGER NOT NULL, bookmark INTEGER NOT NULL,
                        last_page_read INTEGER NOT NULL, chapter_number REAL NOT NULL, source_order INTEGER NOT NULL,
                        date_fetch INTEGER NOT NULL, date_upload INTEGER NOT NULL,
                        last_modified_at INTEGER NOT NULL DEFAULT 0,
                        version INTEGER NOT NULL DEFAULT 0, is_syncing INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY(manga_id) REFERENCES mangas(_id) ON DELETE CASCADE
                    )""",
                )
                old.execSQL("CREATE TABLE excluded_scanlators(manga_id INTEGER NOT NULL, scanlator TEXT)")
                old.execSQL(
                    """INSERT INTO mangas (
                        _id,source,url,title,status,favorite,initialized,viewer,chapter_flags,cover_last_modified,date_added,notes
                    ) VALUES (1,42,'/old','Custom title',1,1,1,3,4,5,6,'User notes')""",
                )
                old.execSQL(
                    """INSERT INTO chapters (
                        _id,manga_id,url,name,read,bookmark,last_page_read,chapter_number,source_order,date_fetch,date_upload
                    ) VALUES (2,1,'/chapter','Chapter 1',1,1,7,1.0,4,123,456)""",
                )
                old.version = 19
            }
            open(name).use { assertTrue(it.mangas.getMangaById(1).memo.isEmpty()) }
            context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { database ->
                for (table in listOf("mangas", "chapters")) {
                    database.rawQuery("SELECT typeof(memo), hex(memo) FROM $table", null).use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals("blob", cursor.getString(0))
                        assertEquals("7B7D", cursor.getString(1))
                    }
                }
            }
            open(name).use { storage ->
                val manga = storage.mangas.getMangaById(1)
                val chapter = storage.chapters.getChapterByMangaId(1).single()
                assertTrue(manga.memo.isEmpty() && chapter.memo.isEmpty())
                assertEquals("Custom title", manga.title)
                assertEquals("User notes", manga.notes)
                assertEquals(42L, manga.source)
                assertEquals(2L, chapter.id)
                assertTrue(chapter.read && chapter.bookmark)
                assertEquals(7L, chapter.lastPageRead)
                assertEquals(4L, chapter.sourceOrder)
            }
        }
    }

    private suspend fun withDatabase(block: suspend (String) -> Unit) {
        val name = "aex03b-art-${UUID.randomUUID()}.db"
        try {
            block(name)
        } finally {
            context.deleteDatabase(name)
        }
    }

    private fun open(name: String): Storage {
        val driver = AndroidSqliteDriver(Database.Schema, context, name)
        val database = Database(
            driver,
            historyAdapter = History.Adapter(DateColumnAdapter),
            mangasAdapter = Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
        )
        return Storage(driver, AndroidDatabaseHandler(database, driver))
    }

    private class Storage(private val driver: AndroidSqliteDriver, handler: AndroidDatabaseHandler) : AutoCloseable {
        val mangas = MangaRepositoryImpl(handler, NoopCreatorLibraryIndexWriter)
        val chapters = ChapterRepositoryImpl(handler)
        override fun close() = driver.close()
    }
}
