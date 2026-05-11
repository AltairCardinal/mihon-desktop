package mihon.desktop.domain

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.repository.ChapterRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate

class ScanlatorFilterTest {

    private val fakeChapterRepo = object : ChapterRepository {
        override suspend fun getScanlatorsByMangaId(mangaId: Long): List<String> =
            listOf("GroupA", "GroupB", "", "GroupC")
        override fun getScanlatorsByMangaIdAsFlow(mangaId: Long): Flow<List<String>> =
            flowOf(listOf("GroupA", "GroupB", "", "GroupC"))
        override suspend fun addAll(chapters: List<Chapter>): List<Chapter> = emptyList()
        override suspend fun update(chapterUpdate: ChapterUpdate) {}
        override suspend fun updateAll(chapterUpdates: List<ChapterUpdate>) {}
        override suspend fun getChapterByMangaId(mangaId: Long, applyScanlatorFilter: Boolean): List<Chapter> = emptyList()
        override suspend fun getChapterById(id: Long): Chapter? = null
        override suspend fun getChapterByUrlAndMangaId(url: String, mangaId: Long): Chapter? = null
        override suspend fun removeChaptersWithIds(chapterIds: List<Long>) {}
        override suspend fun getBookmarkedChaptersByMangaId(mangaId: Long): List<Chapter> = emptyList()
        override suspend fun getChapterByMangaIdAsFlow(mangaId: Long, applyScanlatorFilter: Boolean): Flow<List<Chapter>> = flowOf(emptyList())
    }

    private val getAvailableScanlators = GetAvailableScanlators(fakeChapterRepo)

    @Test
    fun `await filters out blank scanlators`() = runTest {
        val result = getAvailableScanlators.await(mangaId = 1L)
        assertEquals(setOf("GroupA", "GroupB", "GroupC"), result)
    }

    @Test
    fun `subscribe filters out blank scanlators`() = runTest {
        val result = getAvailableScanlators.subscribe(mangaId = 1L).first()
        assertEquals(setOf("GroupA", "GroupB", "GroupC"), result)
    }

    @Test
    fun `await returns empty set when no scanlators`() = runTest {
        val emptyRepo = object : ChapterRepository by fakeChapterRepo {
            override suspend fun getScanlatorsByMangaId(mangaId: Long): List<String> = emptyList()
        }
        val result = GetAvailableScanlators(emptyRepo).await(mangaId = 1L)
        assertTrue(result.isEmpty())
    }
}
