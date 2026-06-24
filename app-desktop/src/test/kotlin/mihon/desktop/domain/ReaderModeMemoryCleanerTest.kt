package mihon.desktop.domain

import kotlinx.coroutines.test.runTest
import mihon.desktop.domain.fakes.FakeMangaRepository
import mihon.desktop.reader.viewerFlagsWithDualPage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga

class ReaderModeMemoryCleanerTest {

    @Test
    fun `startup cleanup clears viewer flags only for non favorite manga`() = runTest {
        val repo = FakeMangaRepository()
        repo.seed(Manga.create().copy(id = 1L, favorite = true, viewerFlags = viewerFlagsWithDualPage(2L, false)))
        repo.seed(Manga.create().copy(id = 2L, favorite = false, viewerFlags = viewerFlagsWithDualPage(5L, true)))

        ReaderModeMemoryCleaner(repo).clearNonFavoriteManga()

        assertEquals(viewerFlagsWithDualPage(2L, false), repo.get(1L)?.viewerFlags)
        assertEquals(0L, repo.get(2L)?.viewerFlags)
    }
}
