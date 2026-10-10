package tachiyomi.data.reader

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.runBlocking
import mihon.data.sync.journal.SyncLocalJournal
import mihon.domain.sync.transport.SyncRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
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
import tachiyomi.domain.creator.repository.NoopCreatorLibraryIndexWriter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.reader.model.ReaderChapterIdentity
import tachiyomi.domain.reader.model.ReadingProgressEvent
import java.io.File
import java.util.Date

class HistoryDataStorage(val driver: SqlDriver, val database: Database, val handler: DatabaseHandler) : AutoCloseable {
    val chapters = ChapterRepositoryImpl(handler)
    val mangas = MangaRepositoryImpl(handler, NoopCreatorLibraryIndexWriter)
    val progress = SqlDelightReadingProgressRepository(database)
    val journal = SyncLocalJournal(handler)
    suspend fun seed(connect: Boolean = true): Pair<Manga, Chapter> {
        if (connect) journal.connect("space", 1, SyncRepository("fixture", "reader", "sync"), "reader", 1)
        val manga = mangas.insertNetworkManga(
            listOf(Manga.create().copy(source = 42, url = "/manga", title = "Reader")),
        ).single()
        val chapter = chapters.addAll(
            listOf(
                Chapter.create().copy(mangaId = manga.id, url = "/chapter", name = "Chapter 2", chapterNumber = 2.0),
            ),
        ).single()
        return manga to chapter
    }
    fun identity(
        manga: Manga,
        chapter: Chapter,
    ) = ReaderChapterIdentity(manga.id, manga.source, manga.url, chapter.id, chapter.url)
    fun reading(
        chapter: Chapter,
        page: Int,
    ) = ReadingProgressEvent(
        chapter.id,
        page,
        20,
        Date(1_000),
        5,
        syncContext = mihon.domain.sync.SyncMutationContext.User,
    )
    override fun close() = driver.close()
}
fun historyTestDatabase(driver: SqlDriver): Database {
    Database.Schema.create(driver)
    driver.execute(null, "PRAGMA foreign_keys = ON", 0)
    return Database(
        driver,
        History.Adapter(DateColumnAdapter),
        Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
    )
}
