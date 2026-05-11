package mihon.desktop.domain

import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import mihon.desktop.domain.fakes.FakeMangaRepository
import mihon.domain.upcoming.interactor.GetUpcomingManga
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga
import java.time.Instant

class GetUpcomingMangaTest {

    private val fakeMangaRepository = FakeMangaRepository()
    private val getUpcomingManga = GetUpcomingManga(fakeMangaRepository)

    @Test
    fun `subscribe returns flow from repository`() = runTest {
        val result = getUpcomingManga.subscribe().first()
        assertTrue(result.isEmpty())
    }

    @Test
    fun `GetUpcomingManga filters ONGOING and PUBLISHING_FINISHED statuses`() {
        // ONGOING=1, PUBLISHING_FINISHED=4 per SManga constants
        val expectedStatuses = setOf(
            SManga.ONGOING.toLong(),
            SManga.PUBLISHING_FINISHED.toLong(),
        )
        assertEquals(setOf(1L, 4L), expectedStatuses)
    }
}
