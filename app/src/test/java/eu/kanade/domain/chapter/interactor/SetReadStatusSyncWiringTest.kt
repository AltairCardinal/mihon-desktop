package eu.kanade.domain.chapter.interactor

import eu.kanade.domain.download.interactor.DeleteDownload
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import mihon.domain.sync.SyncMutationContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository

class SetReadStatusSyncWiringTest {
    @Test
    fun `explicit chapter commands retain same values without repeating download deletion`() =
        runBlocking {
            val captured = mutableListOf<List<ChapterUpdate>>()
            val read = Chapter.create().copy(id = 1, mangaId = 7, read = true)
            val unread = Chapter.create().copy(id = 2, mangaId = 7)
            val manga = Manga.create().copy(id = 7)
            val chapters = mockk<ChapterRepository> {
                coEvery { updateAll(any()) } answers { captured.add(firstArg()) }
            }
            val mangas = mockk<MangaRepository> { coEvery { getMangaById(7) } returns manga }
            val delete = mockk<DeleteDownload>(relaxed = true)
            val preferences = mockk<DownloadPreferences> { every { removeAfterMarkedAsRead().get() } returns true }
            val command = SetReadStatus(preferences, delete, mangas, chapters)

            assertEquals(SetReadStatus.Result.Success, command.await(true, read, unread, unread))
            assertEquals(listOf(1L, 2L), captured.single().map { it.id })
            assertTrue(captured.single().all { it.read == true && it.syncContext == SyncMutationContext.User })
            coVerify(exactly = 1) { delete.awaitAll(manga, unread) }
            coVerify(exactly = 0) { delete.awaitAll(manga, read) }

            assertEquals(SetReadStatus.Result.Success, command.await(false, unread))
            val reset = captured.last().single()
            assertEquals(false, reset.read)
            assertEquals(0L, reset.lastPageRead)
            assertEquals(SyncMutationContext.User, reset.syncContext)
        }

    @Test
    fun `Android whole manga command preserves explicit unread requests`() = runBlocking {
        val captured = mutableListOf<ChapterUpdate>()
        val chapters = mockk<ChapterRepository> {
            coEvery { getChapterByMangaId(7) } returns listOf(Chapter.create().copy(id = 2, mangaId = 7))
            coEvery { updateAll(any()) } answers { captured.addAll(firstArg()) }
        }
        val command = SetReadStatus(mockk(relaxed = true), mockk(), mockk(), chapters)
        assertEquals(SetReadStatus.Result.Success, command.await(Manga.create().copy(id = 7), false))
        assertEquals(SyncMutationContext.User, captured.single().syncContext)
        assertEquals(0L, captured.single().lastPageRead)
    }

    @Test
    fun `failed Android explicit write reports the failure without deleting downloads`() = runBlocking {
        val chapters = mockk<ChapterRepository> {
            coEvery { updateAll(any()) } throws IllegalStateException("write failed")
        }
        val delete = mockk<DeleteDownload>()
        val command = SetReadStatus(mockk(relaxed = true), delete, mockk(), chapters)
        assertTrue(
            command.await(true, Chapter.create().copy(id = 1, mangaId = 7)) is SetReadStatus.Result.InternalError,
        )
        coVerify(exactly = 0) { delete.awaitAll(any(), *anyVararg()) }
    }
}
