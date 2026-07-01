package mihon.desktop.domain

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.runBlocking
import mihon.desktop.domain.fakes.FakeChapterRepository
import mihon.desktop.domain.fakes.FakeCatalogueSource
import mihon.desktop.domain.fakes.FakeMangaRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga

class SaveSourceMangaForDetailsTest {

    @Test
    fun `saves source manga as non favorite with chapters`() = runBlocking<Unit> {
        val mangaRepo = FakeMangaRepository()
        val chapterRepo = FakeChapterRepository()
        val useCase = SaveSourceMangaForDetails(NetworkToLocalManga(mangaRepo), mangaRepo, chapterRepo)

        val result = useCase.await(
            sManga = SManga.create().apply {
                url = "/manga/chainsaw-man"
                title = "Chainsaw Man"
                author = "Tatsuki Fujimoto"
            },
            sourceId = 42L,
            sChapters = listOf(
                SChapter.create().apply { url = "/chapter/1"; name = "Chapter 1" },
                SChapter.create().apply { url = "/chapter/2"; name = "Chapter 2" },
            ),
        )

        assertFalse(result.favorite)
        assertEquals("Chainsaw Man", result.title)
        assertEquals(42L, result.source)
        assertEquals(2, chapterRepo.addedChapters.size)
        assertEquals(result.id, chapterRepo.addedChapters.first().mangaId)
    }

    @Test
    fun `does not duplicate chapters on repeated source detail open`() = runBlocking<Unit> {
        val mangaRepo = FakeMangaRepository()
        val chapterRepo = FakeChapterRepository()
        val useCase = SaveSourceMangaForDetails(NetworkToLocalManga(mangaRepo), mangaRepo, chapterRepo)
        val sManga = SManga.create().apply {
            url = "/manga/chainsaw-man"
            title = "Chainsaw Man"
        }
        val chapters = listOf(SChapter.create().apply { url = "/chapter/1"; name = "Chapter 1" })

        val first = useCase.await(sManga, sourceId = 42L, sChapters = chapters)
        val second = useCase.await(sManga, sourceId = 42L, sChapters = chapters)

        assertEquals(first.id, second.id)
        assertEquals(1, chapterRepo.addedChapters.size)
    }

    @Test
    fun `fetches source details and chapters before opening unified manga detail`() = runBlocking<Unit> {
        val mangaRepo = FakeMangaRepository()
        val chapterRepo = FakeChapterRepository()
        val useCase = SaveSourceMangaForDetails(NetworkToLocalManga(mangaRepo), mangaRepo, chapterRepo)
        val source = FakeCatalogueSource(
            details = SManga.create().apply {
                // url intentionally omitted by the source detail response
                title = "Detailed Title"
                author = "Author A"
                thumbnail_url = "https://example.invalid/detail-cover.jpg"
            },
            chapters = listOf(SChapter.create().apply { url = "/chapter/1"; name = "Chapter 1" }),
        )

        val result = useCase.awaitFromSource(
            source = source,
            listedManga = SManga.create().apply {
                url = "/manga/source-result"
                title = "Listing Title"
                thumbnail_url = "https://example.invalid/list-cover.jpg"
            },
        )

        assertEquals("/manga/source-result", result.url)
        assertEquals("Detailed Title", result.title)
        assertEquals("Author A", result.author)
        assertEquals("https://example.invalid/detail-cover.jpg", result.thumbnailUrl)
        assertEquals(1, chapterRepo.addedChapters.size)
        assertEquals(result.id, chapterRepo.addedChapters.single().mangaId)
    }

    @Test
    fun `saves listed manga without fetching source before navigation`() = runBlocking<Unit> {
        val mangaRepo = FakeMangaRepository()
        val chapterRepo = FakeChapterRepository()
        val useCase = SaveSourceMangaForDetails(NetworkToLocalManga(mangaRepo), mangaRepo, chapterRepo)
        val listed = SManga.create().apply {
            url = "/manga/fast-open"
            title = "Fast Open"
            thumbnail_url = "https://example.invalid/list-cover.jpg"
        }

        val result = useCase.awaitListed(listed, sourceId = 42L)

        assertEquals("/manga/fast-open", result.url)
        assertEquals("Fast Open", result.title)
        assertEquals("https://example.invalid/list-cover.jpg", result.thumbnailUrl)
        assertFalse(result.initialized)
        assertEquals(0, chapterRepo.addedChapters.size)
    }

    @Test
    fun `listed save returns existing initialized manga without downgrading it`() = runBlocking<Unit> {
        val mangaRepo = FakeMangaRepository()
        val chapterRepo = FakeChapterRepository()
        val useCase = SaveSourceMangaForDetails(NetworkToLocalManga(mangaRepo), mangaRepo, chapterRepo)
        val existing = Manga.create().copy(
            id = 7L,
            source = 42L,
            url = "/manga/already-opened",
            title = "Already Opened",
            initialized = true,
        )
        mangaRepo.seed(existing)

        val result = useCase.awaitListed(
            sManga = SManga.create().apply {
                url = "/manga/already-opened"
                title = "Already Opened"
            },
            sourceId = 42L,
        )

        assertEquals(7L, result.id)
        assertEquals(true, result.initialized)
        assertEquals(true, mangaRepo.get(7L)?.initialized)
    }
}
