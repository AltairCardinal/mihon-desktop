package mihon.desktop.domain

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import kotlinx.coroutines.runBlocking
import mihon.desktop.domain.fakes.FakeChapterRepository
import mihon.desktop.domain.fakes.FakeCatalogueSource
import mihon.desktop.domain.fakes.FakeMangaRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga

class SaveSourceMangaForDetailsTest {

    @Test
    fun `search results use canonical mapping deduplicate per source and preserve existing state`() = runBlocking<Unit> {
        val mangaRepo = FakeMangaRepository()
        val useCase = SaveSourceMangaForDetails(NetworkToLocalManga(mangaRepo), mangaRepo, FakeChapterRepository())
        mangaRepo.seed(
            Manga.create().copy(
                id = 7,
                source = 42,
                url = "/same",
                title = "Existing",
                favorite = true,
                initialized = true,
            ),
        )
        val listed = SManga.create().apply {
            url = "/same"
            title = "Listed"
            artist = "Artist"
            author = "Author"
            description = "Description"
            genre = "Drama, Action"
            status = SManga.COMPLETED
            thumbnail_url = "https://example.invalid/cover.jpg"
            update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
            initialized = false
        }

        val sameSource = useCase.awaitSearchResults(listOf(listed, listed), 42)
        val otherSource = useCase.awaitSearchResults(listOf(listed), 43).single()

        assertEquals(listOf(7L), sameSource.map(Manga::id))
        assertEquals(true, sameSource.single().favorite)
        assertEquals(true, sameSource.single().initialized)
        assertEquals(43L, otherSource.source)
        assertEquals("Artist", otherSource.artist)
        assertEquals("Author", otherSource.author)
        assertEquals("Description", otherSource.description)
        assertEquals(listOf("Drama", "Action"), otherSource.genre)
        assertEquals(SManga.COMPLETED.toLong(), otherSource.status)
        assertEquals("https://example.invalid/cover.jpg", otherSource.thumbnailUrl)
        assertEquals(UpdateStrategy.ONLY_FETCH_ONCE, otherSource.updateStrategy)
    }

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
    fun `recognizes chapter numbers from source chapter names when saving details`() = runBlocking<Unit> {
        val mangaRepo = FakeMangaRepository()
        val chapterRepo = FakeChapterRepository()
        val useCase = SaveSourceMangaForDetails(NetworkToLocalManga(mangaRepo), mangaRepo, chapterRepo)

        useCase.await(
            sManga = SManga.create().apply {
                url = "/manga/soul-eater"
                title = "SOUL EATER噬魂者"
            },
            sourceId = 42L,
            sChapters = listOf(
                SChapter.create().apply { url = "/chapter/16"; name = "第16卷" },
                SChapter.create().apply { url = "/chapter/22"; name = "第22卷" },
            ),
        )

        assertEquals(listOf(16.0, 22.0), chapterRepo.addedChapters.map { it.chapterNumber })
    }

    @Test
    fun `updates existing unrecognized chapter numbers when saving details again`() = runBlocking<Unit> {
        val mangaRepo = FakeMangaRepository()
        val chapterRepo = FakeChapterRepository()
        val useCase = SaveSourceMangaForDetails(NetworkToLocalManga(mangaRepo), mangaRepo, chapterRepo)
        val existing = Manga.create().copy(
            id = 7L,
            source = 42L,
            url = "/manga/soul-eater",
            title = "SOUL EATER噬魂者",
            initialized = true,
        )
        mangaRepo.seed(existing)
        chapterRepo.addAll(
            listOf(
                Chapter.create().copy(id = 100L, mangaId = 7L, url = "/chapter/16", name = "第16卷", chapterNumber = -1.0),
                Chapter.create().copy(id = 101L, mangaId = 7L, url = "/chapter/22", name = "第22卷", chapterNumber = -1.0),
            ),
        )

        useCase.await(
            sManga = SManga.create().apply {
                url = "/manga/soul-eater"
                title = "SOUL EATER噬魂者"
            },
            sourceId = 42L,
            sChapters = listOf(
                SChapter.create().apply { url = "/chapter/16"; name = "第16卷" },
                SChapter.create().apply { url = "/chapter/22"; name = "第22卷" },
            ),
        )

        assertEquals(listOf(16.0, 22.0), chapterRepo.getChapterByMangaId(7L).map { it.chapterNumber })
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

    @Test
    fun `listed detail open requests refresh for existing initialized manga with no chapters`() = runBlocking<Unit> {
        val mangaRepo = FakeMangaRepository()
        val chapterRepo = FakeChapterRepository()
        val useCase = SaveSourceMangaForDetails(NetworkToLocalManga(mangaRepo), mangaRepo, chapterRepo)
        val existing = Manga.create().copy(
            id = 7L,
            source = 42L,
            url = "/manga/empty-old-record",
            title = "Empty Old Record",
            initialized = true,
        )
        mangaRepo.seed(existing)

        val result = useCase.awaitListedForDetails(
            sManga = SManga.create().apply {
                url = "/manga/empty-old-record"
                title = "Empty Old Record"
            },
            sourceId = 42L,
        )

        assertEquals(7L, result.manga.id)
        assertEquals(true, result.manga.initialized)
        assertEquals(true, result.needsRefresh)
        assertEquals(true, mangaRepo.get(7L)?.initialized)
    }

    @Test
    fun `listed detail open does not request refresh for existing initialized manga with chapters`() = runBlocking<Unit> {
        val mangaRepo = FakeMangaRepository()
        val chapterRepo = FakeChapterRepository()
        val useCase = SaveSourceMangaForDetails(NetworkToLocalManga(mangaRepo), mangaRepo, chapterRepo)
        val existing = Manga.create().copy(
            id = 7L,
            source = 42L,
            url = "/manga/already-has-chapters",
            title = "Already Has Chapters",
            initialized = true,
        )
        mangaRepo.seed(existing)
        chapterRepo.addAll(
            listOf(
                Chapter.create().copy(mangaId = 7L, url = "/chapter/1", name = "Chapter 1"),
            ),
        )

        val result = useCase.awaitListedForDetails(
            sManga = SManga.create().apply {
                url = "/manga/already-has-chapters"
                title = "Already Has Chapters"
            },
            sourceId = 42L,
        )

        assertEquals(false, result.needsRefresh)
    }
}
