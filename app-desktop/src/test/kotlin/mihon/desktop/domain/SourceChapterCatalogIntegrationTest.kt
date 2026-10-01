package mihon.desktop.domain

import app.cash.sqldelight.db.SqlDriver
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.runBlocking
import mihon.desktop.di.initDesktopDIForTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.UUID
import java.util.prefs.Preferences

@Isolated
class SourceChapterCatalogIntegrationTest {
    @Test
    fun `existing listed entry keeps work identity and publishes an explicit catalogue preparation failure`(@TempDir folder: File) = fixture(folder) {
        val manga = seed()
        val chapter = Injekt.get<ChapterRepository>().addAll(listOf(Chapter.create().copy(mangaId = manga.id, url = "/2", name = "Chapter 2", bookmark = true, lastPageRead = 8))).single()
        val other = Injekt.get<MangaRepository>().insertNetworkManga(listOf(Manga.create().copy(source = 42, url = "/other", title = "Other"))).single()
        Injekt.get<tachiyomi.domain.creator.repository.CreatorArchiveRepository>().upsertSourceWork(42, manga.url, other.id, "Other", null, null, null, detailsFetchedAt = null)
        val owner = Injekt.get<SaveSourceMangaForDetails>()
        val result = org.junit.jupiter.api.Assertions.assertDoesNotThrow(
            org.junit.jupiter.api.function.ThrowingSupplier { runBlocking { owner.awaitListedForDetails(listed(), 42) } },
            "Existing listing must retain its ID and publish preparation failure for detail feedback",
        )
        assertEquals(manga, result.manga)
        assertFalse(result.needsRefresh, "A failed local precheck must not request a speculative source refresh")
        assertInstanceOf(mihon.domain.error.AppError.Storage::class.java, result.preparationError)
        val failure = assertInstanceOf(SourceMangaRefreshState.Failure::class.java, owner.refreshStates.value[SourceMangaRefreshKey(42, manga.url)])
        assertInstanceOf(mihon.domain.error.AppError.Storage::class.java, failure.error)
        assertEquals(listOf(chapter), Injekt.get<ChapterRepository>().getChapterByMangaId(manga.id))
        assertEquals(other.id, Injekt.get<DatabaseHandler>().await { author_archiveQueries.getArchiveSourceWorkByKey(42, manga.url).executeAsOne().manga_id })
    }

    @Test
    fun `transaction rejects rebound source work but associates an unbound discovery`(@TempDir folder: File) = fixture(folder) {
        val seeded = seed()
        Injekt.get<MangaRepository>().update(tachiyomi.domain.manga.model.MangaUpdate(seeded.id, favorite = true))
        val manga = Injekt.get<MangaRepository>().getMangaById(seeded.id)
        val repository = Injekt.get<tachiyomi.domain.creator.repository.CreatorArchiveRepository>()
        val owner = Injekt.get<SaveSourceMangaForDetails>()
        val other = Injekt.get<MangaRepository>().insertNetworkManga(listOf(Manga.create().copy(source = 42, url = "/other", title = "Other"))).single()
        repository.upsertSourceWork(42, manga.url, null, manga.title, null, null, null, detailsFetchedAt = null)
        owner.await(listed(), 42, remote(), fetchDetails = false)
        assertEquals(manga.id, Injekt.get<DatabaseHandler>().await { author_archiveQueries.getArchiveSourceWorkByKey(42, manga.url).executeAsOne().manga_id })
        repository.upsertSourceWork(42, manga.url, other.id, "Rebound", null, null, null, detailsFetchedAt = null)
        val before = Injekt.get<ChapterRepository>().getChapterByMangaId(manga.id)
        assertThrows(Exception::class.java) {
            runBlocking {
                owner.await(
                    listed().apply { title = "Must roll back" },
                    42,
                    remote() + SChapter.create().apply {
                        url = "/0"
                        name = "Chapter 0"
                    },
                )
            }
        }
        assertEquals(manga, Injekt.get<MangaRepository>().getMangaById(manga.id))
        assertEquals(before, Injekt.get<ChapterRepository>().getChapterByMangaId(manga.id))
        assertEquals(other.id, Injekt.get<DatabaseHandler>().await { author_archiveQueries.getArchiveSourceWorkByKey(42, manga.url).executeAsOne().manga_id })
    }

    @Test
    fun `chapter write failure also rolls back existing details and newly inserted manga`(@TempDir folder: File) = fixture(folder) {
        val old = seed()
        val owner = Injekt.get<SaveSourceMangaForDetails>()
        val driver = Injekt.get<SqlDriver>()
        driver.execute(null, "CREATE TRIGGER reject_full_catalog BEFORE INSERT ON chapters WHEN NEW.url = '/1' BEGIN SELECT RAISE(ABORT, 'injected catalog failure'); END", 0)
        val changed = listed().apply {
            title = "Changed details"
            description = "Changed description"
        }
        assertThrows(Exception::class.java) { runBlocking { owner.await(changed, 42, remote()) } }
        assertEquals(old, Injekt.get<MangaRepository>().getMangaById(old.id))
        assertTrue(Injekt.get<ChapterRepository>().getChapterByMangaId(old.id).isEmpty())
        assertThrows(Exception::class.java) { runBlocking { owner.await(listed().apply { url = "/uncreated" }, 42, remote()) } }
        assertNull(Injekt.get<MangaRepository>().getMangaByUrlAndSourceId("/uncreated", 42))
        driver.execute(null, "DROP TRIGGER reject_full_catalog", 0)
        owner.await(changed, 42, remote())
        assertEquals("Changed details", Injekt.get<MangaRepository>().getMangaById(old.id).title)
        assertEquals(3, Injekt.get<ChapterRepository>().getChapterByMangaId(old.id).size)
    }

    @Test
    fun `real SQLite query failure reports storage and preserves the historical row`(@TempDir folder: File) = fixture(folder) {
        val manga = seed()
        val chapter = Injekt.get<ChapterRepository>().addAll(listOf(Chapter.create().copy(mangaId = manga.id, url = "/2", name = "Chapter 2"))).single()
        Injekt.get<tachiyomi.domain.history.interactor.UpsertHistory>().await(tachiyomi.domain.history.model.HistoryUpdate(chapter.id, java.util.Date(), 123))
        val model = mihon.desktop.history.HistoryScreenModelFactory.create()
        model.loadHistory()
        val item = model.state.value.items.single()
        val driver = Injekt.get<SqlDriver>()
        driver.execute(null, "ALTER TABLE chapters RENAME TO unavailable_chapters", 0)
        try {
            val result = Injekt.get<SaveSourceMangaForDetails>().awaitPrepared(null, manga)
            assertTrue(result is mihon.desktop.extension.SourceCallResult.Error && result.error is mihon.domain.error.AppError.Storage)
            assertNull(model.readerRequestFor(item))
            assertEquals(mihon.desktop.history.HistoryReadFailure.STORAGE, model.state.value.readStatus?.failure)
            val owner = Injekt.get<SaveSourceMangaForDetails>()
            owner.refreshFromSource(mihon.desktop.domain.fakes.FakeCatalogueSource(listed(), remote()), listed()).join()
            val refresh = assertInstanceOf(SourceMangaRefreshState.Failure::class.java, owner.refreshStates.value[SourceMangaRefreshKey(42, manga.url)])
            assertInstanceOf(mihon.domain.error.AppError.Storage::class.java, refresh.error, "Manual refresh must classify a real local SQLite query failure as storage")
        } finally {
            driver.execute(null, "ALTER TABLE unavailable_chapters RENAME TO chapters", 0)
        }
        assertEquals(chapter, Injekt.get<ChapterRepository>().getChapterByMangaId(manga.id).single())
        assertEquals(item.id, Injekt.get<tachiyomi.domain.history.interactor.GetHistory>().await(manga.id).single().id)
    }

    @Test
    fun `single chapter complete observation survives database reopen`(@TempDir folder: File) {
        fixture(folder) {
            seed()
            Injekt.get<SaveSourceMangaForDetails>().await(listed(), 42, remote().take(1), fetchDetails = false)
        }
        fixture(folder) {
            val owner = Injekt.get<SaveSourceMangaForDetails>()
            assertFalse(owner.awaitListedForDetails(listed(), 42).needsRefresh)
            assertEquals(1, Injekt.get<ChapterRepository>().getChapterByMangaId(Injekt.get<MangaRepository>().getMangaByUrlAndSourceId("/work", 42)!!.id).size)
        }
    }

    @Test
    fun `update and observation SQL failures roll back complete catalogue`(@TempDir folder: File) = fixture(folder) {
        val manga = seed()
        val owner = Injekt.get<SaveSourceMangaForDetails>()
        owner.await(listed(), 42, remote(), fetchDetails = false)
        val chapters = Injekt.get<ChapterRepository>()
        val before = chapters.getChapterByMangaId(manga.id)
        val driver = Injekt.get<SqlDriver>()
        driver.execute(null, "CREATE TRIGGER reject_update BEFORE UPDATE ON chapters BEGIN SELECT RAISE(ABORT, 'injected update failure'); END", 0)
        assertThrows(Exception::class.java) { runBlocking { owner.await(listed(), 42, remote(), fetchDetails = false) } }
        assertEquals(before, chapters.getChapterByMangaId(manga.id))
        driver.execute(null, "DROP TRIGGER reject_update", 0)
        driver.execute(null, "CREATE TRIGGER reject_observation BEFORE UPDATE ON author_archive_source_works WHEN NEW.chapter_count_state = 'COMPLETE' BEGIN SELECT RAISE(ABORT, 'injected observation failure'); END", 0)
        assertThrows(Exception::class.java) {
            runBlocking {
                owner.await(
                    listed(),
                    42,
                    remote() + SChapter.create().apply {
                        url = "/0"
                        name = "Chapter 0"
                    },
                    fetchDetails = false,
                )
            }
        }
        assertEquals(before, chapters.getChapterByMangaId(manga.id))
        assertEquals(3L, Injekt.get<DatabaseHandler>().await { author_archiveQueries.getArchiveSourceWorkByKey(42, "/work").executeAsOne().catalog_chapter_count })
    }

    @Test
    fun `initialized synchronized chapter still needs directory and matching order is repaired`(@TempDir folder: File) = fixture(folder) {
        val manga = seed()
        val chapters = Injekt.get<ChapterRepository>()
        val middle = chapters.addAll(listOf(Chapter.create().copy(mangaId = manga.id, url = "/2", name = "Chapter 2", chapterNumber = 2.0, sourceOrder = 9, read = true, bookmark = true, lastPageRead = 8))).single()
        val owner = Injekt.get<SaveSourceMangaForDetails>()
        assertTrue(owner.awaitListedForDetails(listed(), 42).needsRefresh)
        owner.await(listed(), 42, remote(), fetchDetails = false)
        val repaired = requireNotNull(chapters.getChapterById(middle.id))
        assertEquals(1L, repaired.sourceOrder)
        assertTrue(repaired.dateFetch > 0)
        assertTrue(repaired.read)
        assertTrue(repaired.bookmark)
        assertEquals(8L, repaired.lastPageRead)
        assertFalse(owner.awaitListedForDetails(listed(), 42).needsRefresh)
    }

    @Test
    fun `authorless nonfavorite work has real complete observation`(@TempDir folder: File) = fixture(folder) {
        val manga = seed()
        Injekt.get<SaveSourceMangaForDetails>().await(listed(), 42, remote(), fetchDetails = false)
        val row = Injekt.get<DatabaseHandler>().await { author_archiveQueries.getArchiveSourceWorkByKey(42, "/work").executeAsOneOrNull() }
        assertNotNull(row)
        assertEquals(manga.id, row?.manga_id)
        assertEquals("COMPLETE", row?.chapter_count_state)
        assertEquals(3L, row?.catalog_chapter_count)
        assertNull(row?.details_fetched_at)
        assertFalse(Injekt.get<MangaRepository>().getMangaById(manga.id).favorite)
    }

    @Test
    fun `swallowed chapter insertion failure rolls back matched updates and observation`(@TempDir folder: File) = fixture(folder) {
        val manga = seed()
        val chapters = Injekt.get<ChapterRepository>()
        val old = chapters.addAll(listOf(Chapter.create().copy(mangaId = manga.id, url = "/2", name = "Chapter 2", sourceOrder = 9))).single()
        Injekt.get<SqlDriver>().execute(null, "CREATE TRIGGER reject_catalog BEFORE INSERT ON chapters WHEN NEW.url = '/1' BEGIN SELECT RAISE(ABORT, 'injected catalog insert failure'); END", 0)
        assertThrows(Exception::class.java) { runBlocking { Injekt.get<SaveSourceMangaForDetails>().await(listed(), 42, remote(), fetchDetails = false) } }
        assertEquals(old, chapters.getChapterById(old.id))
        assertEquals(listOf(old.id), chapters.getChapterByMangaId(manga.id).map { it.id })
        assertNull(Injekt.get<DatabaseHandler>().await { author_archiveQueries.getArchiveSourceWorkByKey(42, "/work").executeAsOneOrNull() })
    }

    private fun fixture(folder: File, action: suspend () -> Unit) = runBlocking {
        val node = Preferences.userRoot().node("mihon-catalog-test-" + UUID.randomUUID())
        val context = initDesktopDIForTest(folder, DesktopPreferenceStore(node))
        try {
            action()
        } finally {
            context.closeAndJoin()
            node.removeNode()
        }
    }

    private suspend fun seed() = Injekt.get<MangaRepository>().insertNetworkManga(listOf(Manga.create().copy(source = 42, url = "/work", title = "Work", initialized = true))).single()
    private fun listed() = SManga.create().apply {
        url = "/work"
        title = "Work"
        initialized = true
    }
    private fun remote() = listOf(3, 2, 1).map { n ->
        SChapter.create().apply {
            url = "/$n"
            name = "Chapter $n"
            chapter_number = n.toFloat()
        }
    }
}
