package mihon.desktop.ui.library

import mihon.desktop.domain.SourceMangaRefreshState
import mihon.domain.error.AppError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga

class MangaDetailSourceRefreshTest {

    @Test
    fun `loading and failed remote details do not look like an empty chapter list`() {
        assertEquals(
            MangaDetailChapterContentState.LOADING,
            mangaDetailChapterContentState(SourceMangaRefreshState.Loading, chapterCount = 0),
        )
        assertEquals(
            MangaDetailChapterContentState.FAILURE,
            mangaDetailChapterContentState(
                SourceMangaRefreshState.Failure(AppError.MalformedData()),
                chapterCount = 0,
            ),
        )
        assertEquals(
            MangaDetailChapterContentState.CONTENT,
            mangaDetailChapterContentState(
                SourceMangaRefreshState.Failure(AppError.MalformedData()),
                chapterCount = 2,
            ),
        )
        assertEquals(
            MangaDetailChapterContentState.CONTENT,
            mangaDetailChapterContentState(refreshState = null, chapterCount = 0),
        )
    }

    @Test
    fun `manual source refresh preserves persisted manga identity and metadata`() {
        val manga = Manga.create().copy(
            id = 7L,
            source = 42L,
            url = "/comic/shimazaki",
            title = "致和平之国的岛崎",
            thumbnailUrl = "https://cdn.example/cover.jpg",
            author = "滨田轰天",
            artist = "濑下猛",
            description = "description",
            genre = listOf("动作", "剧情"),
            status = 1L,
            initialized = true,
        )

        val sourceManga = manga.toSourceMangaForRefresh()

        assertEquals(manga.url, sourceManga.url)
        assertEquals(manga.title, sourceManga.title)
        assertEquals(manga.thumbnailUrl, sourceManga.thumbnail_url)
        assertEquals(manga.author, sourceManga.author)
        assertEquals(manga.artist, sourceManga.artist)
        assertEquals(manga.description, sourceManga.description)
        assertEquals("动作, 剧情", sourceManga.genre)
        assertEquals(manga.status.toInt(), sourceManga.status)
        assertEquals(manga.initialized, sourceManga.initialized)
    }
}
