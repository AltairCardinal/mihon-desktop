package mihon.desktop.domain

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.runBlocking
import mihon.desktop.domain.fakes.FakeChapterRepository
import mihon.desktop.domain.fakes.FakeMangaRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga

class AddMangaToLibraryTest {

    @Test
    fun `adds new manga as favorite with chapters`() = runBlocking<Unit> {
        val mangaRepo = FakeMangaRepository()
        val chapterRepo = FakeChapterRepository()
        val useCase = AddMangaToLibrary(NetworkToLocalManga(mangaRepo), mangaRepo, chapterRepo)

        val sManga = SManga.create().apply {
            url = "/manga/one-piece"
            title = "One Piece"
            thumbnail_url = "https://example.com/cover.jpg"
        }
        val sChapters = listOf(
            SChapter.create().apply { url = "/ch/1"; name = "Chapter 1" },
            SChapter.create().apply { url = "/ch/2"; name = "Chapter 2" },
        )

        val result = useCase.await(sManga, sourceId = 42L, sChapters = sChapters)

        assertTrue(result.favorite, "Manga must be favorited")
        assertEquals("One Piece", result.title)
        assertEquals(42L, result.source)
        assertEquals(2, chapterRepo.addedChapters.size)
        assertEquals("/ch/1", chapterRepo.addedChapters[0].url)
        assertEquals("/ch/2", chapterRepo.addedChapters[1].url)
        assertTrue(chapterRepo.addedChapters.all { it.mangaId == result.id })
    }

    @Test
    fun `is idempotent — second call does not add duplicate chapters`() = runBlocking<Unit> {
        val mangaRepo = FakeMangaRepository()
        val chapterRepo = FakeChapterRepository()
        val useCase = AddMangaToLibrary(NetworkToLocalManga(mangaRepo), mangaRepo, chapterRepo)

        val sManga = SManga.create().apply { url = "/manga/naruto"; title = "Naruto" }
        val sChapters = listOf(SChapter.create().apply { url = "/ch/1"; name = "Ch 1" })

        useCase.await(sManga, sourceId = 1L, sChapters = sChapters)
        useCase.await(sManga, sourceId = 1L, sChapters = sChapters)

        assertEquals(1, chapterRepo.addedChapters.size, "Chapters must not be duplicated on second call")
    }

    @Test
    fun `marks existing unfavorited manga as favorite`() = runBlocking<Unit> {
        val mangaRepo = FakeMangaRepository()
        mangaRepo.seed(
            Manga.create().copy(id = 99L, url = "/manga/bleach", source = 7L, title = "Bleach", favorite = false),
        )
        val chapterRepo = FakeChapterRepository()
        val useCase = AddMangaToLibrary(NetworkToLocalManga(mangaRepo), mangaRepo, chapterRepo)

        val result = useCase.await(
            SManga.create().apply { url = "/manga/bleach"; title = "Bleach" },
            sourceId = 7L,
            sChapters = emptyList(),
        )

        assertTrue(result.favorite, "Existing manga must be favorited")
        assertTrue(mangaRepo.updates.any { it.id == 99L && it.favorite == true })
    }

    @Test
    fun `skips favorite update when manga is already favorited`() = runBlocking<Unit> {
        val mangaRepo = FakeMangaRepository()
        mangaRepo.seed(
            Manga.create().copy(id = 5L, url = "/manga/hxh", source = 3L, title = "HxH", favorite = true),
        )
        val chapterRepo = FakeChapterRepository()
        val useCase = AddMangaToLibrary(NetworkToLocalManga(mangaRepo), mangaRepo, chapterRepo)

        useCase.await(
            SManga.create().apply { url = "/manga/hxh"; title = "HxH" },
            sourceId = 3L,
            sChapters = emptyList(),
        )

        assertTrue(mangaRepo.updates.none { it.id == 5L }, "No redundant update when already favorited")
    }
}
