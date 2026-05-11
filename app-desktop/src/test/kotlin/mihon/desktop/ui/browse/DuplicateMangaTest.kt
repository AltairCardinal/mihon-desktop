package mihon.desktop.ui.browse

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaWithChapterCount

class DuplicateMangaTest {

    private fun mangaWithCount(id: Long): MangaWithChapterCount =
        MangaWithChapterCount(
            manga = Manga.create().copy(id = id, title = "Same Title"),
            chapterCount = 5L,
        )

    @Test
    fun `shouldShowDuplicateWarning returns false when list is empty`() {
        assertFalse(shouldShowDuplicateWarning(emptyList()))
    }

    @Test
    fun `shouldShowDuplicateWarning returns true when duplicates exist`() {
        assertTrue(shouldShowDuplicateWarning(listOf(mangaWithCount(1L))))
    }

    @Test
    fun `shouldShowDuplicateWarning returns true for multiple duplicates`() {
        assertTrue(shouldShowDuplicateWarning(listOf(mangaWithCount(1L), mangaWithCount(2L))))
    }
}
