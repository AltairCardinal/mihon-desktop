package tachiyomi.domain.chapter.interactor

import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository

class GetBookmarkedChaptersByMangaId(
    private val chapterRepository: ChapterRepository,
) {

    suspend fun awaitOrThrow(mangaId: Long): List<Chapter> {
        return chapterRepository.getBookmarkedChaptersByMangaId(mangaId)
    }

    suspend fun await(mangaId: Long): List<Chapter> {
        return try {
            awaitOrThrow(mangaId)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            emptyList()
        }
    }
}
