package mihon.desktop.history

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import mihon.desktop.domain.PreparedChapterCatalog
import mihon.desktop.domain.fakes.FakeChapterRepository
import mihon.desktop.domain.fakes.FakeHistoryRepository
import mihon.desktop.domain.fakes.FakeMangaRepository
import mihon.desktop.extension.SourceCallResult
import mihon.domain.error.AppError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.interactor.RemoveHistory
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaCover
import java.util.Date

class HistoryPreparationLifecycleTest {
    @Test
    fun `completed entry cannot publish twice before history returns`() = runTest {
        val fixture = fixture()
        assertNotNull(fixture.model.readerRequestFor(fixture.item))
        assertNull(fixture.model.readerRequestFor(fixture.item))
        fixture.model.cancelRead()
        assertNotNull(fixture.model.readerRequestFor(fixture.item))
    }

    @Test
    fun `cancel remove and dispose revoke late result without losing history query`() = runTest {
        for (action in listOf("cancel", "remove", "dispose")) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val fixture = fixture(prepare = { manga, chapters ->
                entered.complete(Unit)
                release.await()
                SourceCallResult.Success(PreparedChapterCatalog(manga, chapters))
            })
            fixture.model.loadHistory("Work")
            val request = async { fixture.model.readerRequestFor(fixture.item) }
            entered.await()
            assertNull(fixture.model.readerRequestFor(fixture.item))
            when (action) {
                "remove" -> fixture.model.removeHistory(fixture.item)
                "dispose" -> fixture.model.onDispose()
                else -> fixture.model.cancelRead()
            }
            release.complete(Unit)
            assertNull(request.await(), action)
            assertEquals("Work", fixture.model.state.value.searchQuery)
            assertNull(fixture.model.state.value.readStatus)
        }
    }

    @Test
    fun `network failure permits explicit existing directory and missing remote target has no fake adjacent`() = runTest {
        val failed = fixture(prepare = { _, _ -> SourceCallResult.Error(AppError.Server(500)) })
        assertNull(failed.model.readerRequestFor(failed.item))
        assertEquals(HistoryReadFailure.SOURCE, failed.model.state.value.readStatus?.failure)
        assertTrue(failed.model.state.value.readStatus?.canUseExisting == true)
        assertEquals(3, failed.model.readerRequestFor(failed.item, useExisting = true)?.chapters?.size)
        val missing = fixture(prepare = { manga, chapters -> SourceCallResult.Success(PreparedChapterCatalog(manga, chapters.filterNot { it.id == 2L })) })
        assertNull(missing.model.readerRequestFor(missing.item))
        assertEquals(HistoryReadFailure.TARGET_MISSING, missing.model.state.value.readStatus?.failure)
        assertEquals(listOf(2L), missing.model.readerRequestFor(missing.item, useExisting = true)?.chapters?.map { it.id })
    }

    @Test
    fun `directory readiness finishes before adopting latest synchronized chapter page and snapshot`() = runTest {
        var resume: tachiyomi.domain.reader.model.ReadingResumePosition? = null
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val fixture = fixture(
            { manga, chapters ->
                entered.complete(Unit)
                release.await()
                SourceCallResult.Success(PreparedChapterCatalog(manga, chapters))
            },
            tachiyomi.domain.reader.interactor.RecordReadingProgress(object : tachiyomi.domain.reader.repository.ReadingProgressRepository {
                override suspend fun record(event: tachiyomi.domain.reader.model.ReadingProgressEvent) = Unit
                override suspend fun resumePosition(mangaId: Long) = resume
            }),
        )
        val request = async { fixture.model.readerRequestFor(fixture.item) }
        entered.await()
        val snapshot = tachiyomi.domain.reader.model.ReadingSyncSnapshot()
        resume = tachiyomi.domain.reader.model.ReadingResumePosition(3, 4, snapshot)
        release.complete(Unit)
        val entry = requireNotNull(request.await())
        assertEquals(3L, entry.chapterId)
        assertEquals(0, entry.currentChapterIndex)
        assertEquals(4, entry.initialPage)
        assertEquals(snapshot, entry.resumeSnapshot)
        resume = tachiyomi.domain.reader.model.ReadingResumePosition(1, 0, snapshot)
        assertEquals(3L, entry.toReaderScreen().chapterId)
        assertEquals(4, entry.toReaderScreen().initialPage)
    }

    private data class Fixture(val model: HistoryScreenModel, val item: HistoryWithRelations)
    private fun fixture(
        prepare: suspend (Manga, List<Chapter>) -> SourceCallResult<PreparedChapterCatalog> = { manga, chapters -> SourceCallResult.Success(PreparedChapterCatalog(manga, chapters)) },
        progress: tachiyomi.domain.reader.interactor.RecordReadingProgress? = null,
    ): Fixture {
        val mangas = FakeMangaRepository().apply { seed(Manga.create().copy(id = 10, source = 42, url = "/work", title = "Work")) }
        val chapters = FakeChapterRepository().apply { listOf(3, 2, 1).forEachIndexed { index, n -> seed(Chapter.create().copy(id = n.toLong(), mangaId = 10, url = "/$n", name = "Chapter $n", sourceOrder = index.toLong())) } }
        val item = HistoryWithRelations(1, 2, 10, "Work", 2.0, Date(), 1, MangaCover(10, 42, false, null, 0))
        val history = FakeHistoryRepository().apply { addHistory(item) }
        return Fixture(
            HistoryScreenModel(
                GetHistory(history),
                RemoveHistory(history),
                GetChapter(chapters),
                GetManga(mangas),
                progress,
                getChapters = GetChaptersByMangaId(chapters),
                prepareDirectory = { prepare(it, chapters.getChapterByMangaId(it.id)) },
            ),
            item,
        )
    }
}
