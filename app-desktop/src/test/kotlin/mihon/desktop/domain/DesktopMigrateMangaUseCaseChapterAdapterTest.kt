package mihon.desktop.domain

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.test.runTest
import mihon.desktop.domain.fakes.FakeCategoryRepository
import mihon.desktop.domain.fakes.FakeChapterRepository
import mihon.desktop.domain.fakes.FakeMangaRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga

class DesktopMigrateMangaUseCaseChapterAdapterTest {
    @Test
    fun `Desktop production adapter preserves all target read states when source contains read NaN`() = runTest {
        val mangas = FakeMangaRepository()
        val chapters = FakeChapterRepository()
        val source = Manga.create().copy(id = 10, source = 1, url = "/source", title = "Source", favorite = true)
        mangas.seed(source)
        chapters.seed(chapter(1, source.id, 2.0, read = true))
        chapters.seed(chapter(2, source.id, Double.NaN, read = true))
        val useCase = DesktopMigrateMangaUseCase(
            saveSourceMangaForDetails = SaveSourceMangaForDetails(
                NetworkToLocalManga(mangas),
                mangas,
                chapters,
            ),
            getChaptersByMangaId = GetChaptersByMangaId(chapters),
            updateChapter = UpdateChapter(chapters),
            getCategories = GetCategories(FakeCategoryRepository()),
            mangaRepository = mangas,
        )

        useCase.await(
            sourceManga = source,
            targetSManga = SManga.create().apply {
                url = "/target"
                title = "Target"
            },
            targetSourceId = 2,
            targetChapters = listOf(sourceChapter("/1", 1f), sourceChapter("/2", 2f), sourceChapter("/3", 3f)),
            options = MigrationOptions(copyCategories = false, copyNotes = false),
            replace = false,
        )

        assertEquals(3, chapters.updates.size)
        assertEquals(listOf(null, null, null), chapters.updates.map { it.read })
    }

    private fun chapter(id: Long, mangaId: Long, number: Double, read: Boolean) = Chapter.create().copy(
        id = id,
        mangaId = mangaId,
        url = "/source-$id",
        name = "Source $id",
        chapterNumber = number,
        read = read,
    )

    private fun sourceChapter(url: String, number: Float) = SChapter.create().apply {
        this.url = url
        name = url
        chapter_number = number
    }
}
