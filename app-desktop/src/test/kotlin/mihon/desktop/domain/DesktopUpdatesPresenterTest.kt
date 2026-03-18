package mihon.desktop.domain

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import mihon.desktop.domain.fakes.FakeUpdatesRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.updates.interactor.GetUpdates
import tachiyomi.domain.updates.model.UpdatesWithRelations
import java.time.Instant

class DesktopUpdatesPresenterTest {

    private lateinit var updatesRepo: FakeUpdatesRepository
    private lateinit var getUpdates: GetUpdates

    @BeforeEach
    fun setUp() {
        updatesRepo = FakeUpdatesRepository()
        getUpdates = GetUpdates(updatesRepo)
    }

    @Test
    fun `getUpdates returns empty when no updates`() = runBlocking {
        val result = getUpdates.await(read = false, after = 0L)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `getUpdates returns unread updates`() = runBlocking {
        updatesRepo.addUpdate(sampleUpdate(chapterId = 1, read = false))
        updatesRepo.addUpdate(sampleUpdate(chapterId = 2, read = true))

        val unread = getUpdates.await(read = false, after = 0L)
        assertEquals(1, unread.size)
        assertEquals(1L, unread[0].chapterId)
    }

    @Test
    fun `getUpdates subscribe emits updates after timestamp`() = runBlocking {
        val now = Instant.now()
        updatesRepo.addUpdate(
            sampleUpdate(chapterId = 1, dateFetch = now.toEpochMilli()),
        )

        val result = getUpdates.subscribe(
            instant = now.minusSeconds(3600),
            unread = null,
            started = null,
            bookmarked = null,
            hideExcludedScanlators = false,
        ).first()

        assertEquals(1, result.size)
    }

    @Test
    fun `getUpdates subscribe filters by unread`() = runBlocking {
        val now = Instant.now()
        updatesRepo.addUpdate(
            sampleUpdate(chapterId = 1, read = false, dateFetch = now.toEpochMilli()),
        )
        updatesRepo.addUpdate(
            sampleUpdate(chapterId = 2, read = true, dateFetch = now.toEpochMilli()),
        )

        val result = getUpdates.subscribe(
            instant = now.minusSeconds(3600),
            unread = true,
            started = null,
            bookmarked = null,
            hideExcludedScanlators = false,
        ).first()

        assertEquals(1, result.size)
        assertEquals(1L, result[0].chapterId)
    }

    private fun sampleUpdate(
        chapterId: Long = 1,
        mangaId: Long = 10,
        read: Boolean = false,
        dateFetch: Long = System.currentTimeMillis(),
    ) = UpdatesWithRelations(
        mangaId = mangaId,
        mangaTitle = "Test Manga",
        chapterId = chapterId,
        chapterName = "Chapter $chapterId",
        scanlator = null,
        chapterUrl = "/chapter/$chapterId",
        read = read,
        bookmark = false,
        lastPageRead = 0L,
        sourceId = 1L,
        dateFetch = dateFetch,
        coverData = MangaCover(
            mangaId = mangaId,
            sourceId = 1L,
            isMangaFavorite = true,
            url = "https://example.com/cover.jpg",
            lastModified = 0L,
        ),
    )
}
