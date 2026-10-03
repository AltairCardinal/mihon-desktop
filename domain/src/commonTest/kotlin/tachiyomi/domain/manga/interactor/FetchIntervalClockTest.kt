package tachiyomi.domain.manga.interactor

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import java.time.ZonedDateTime

class FetchIntervalClockTest {
    @Test
    fun `zero window preserves an existing prediction inside the supplied date window`() = runBlocking<Unit> {
        val repository = io.mockk.mockk<tachiyomi.domain.chapter.repository.ChapterRepository>()
        val actual = FetchInterval(GetChaptersByMangaId(repository))
        val time = ZonedDateTime.parse("2001-01-01T12:00:00Z")
        val expected = java.time.Instant.parse("2001-01-01T00:00:00Z").toEpochMilli()
        val manga = tachiyomi.domain.manga.model.Manga.create().copy(id = 1, fetchInterval = -7, nextUpdate = expected)
        actual.toMangaUpdate(manga, time, 0L to 0L).nextUpdate shouldBe expected
    }

    @Test
    fun `zero window and unknown last update use the supplied time instead of wall clock`() = runBlocking<Unit> {
        val repository = io.mockk.mockk<tachiyomi.domain.chapter.repository.ChapterRepository>()
        io.mockk.coEvery { repository.getChapterByMangaId(any(), any()) } returns emptyList()
        val actual = FetchInterval(GetChaptersByMangaId(repository))
        val time = ZonedDateTime.parse("2001-01-01T12:00:00Z")
        val manga = tachiyomi.domain.manga.model.Manga.create().copy(id = 1, fetchInterval = -7)
        val explicit = actual.toMangaUpdate(manga, time, actual.getWindow(time))
        val implicit = actual.toMangaUpdate(manga, time, 0L to 0L)
        implicit.nextUpdate shouldBe explicit.nextUpdate
        implicit.nextUpdate shouldBe java.time.Instant.parse("2001-01-08T00:00:00Z").toEpochMilli()
        implicit.fetchInterval shouldBe -7
    }
}
