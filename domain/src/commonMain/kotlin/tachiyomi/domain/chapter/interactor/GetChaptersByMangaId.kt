package tachiyomi.domain.chapter.interactor

import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository

class GetChaptersByMangaId(
    private val chapterRepository: ChapterRepository,
) {

    suspend fun awaitOrThrow(mangaId: Long, applyScanlatorFilter: Boolean = false): List<Chapter> {
        return chapterRepository.getChapterByMangaId(mangaId, applyScanlatorFilter)
    }

    suspend fun await(mangaId: Long, applyScanlatorFilter: Boolean = false): List<Chapter> {
        return try {
            awaitOrThrow(mangaId, applyScanlatorFilter)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            emptyList()
        }
    }
}
