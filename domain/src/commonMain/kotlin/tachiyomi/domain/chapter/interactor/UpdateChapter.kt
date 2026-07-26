package tachiyomi.domain.chapter.interactor

import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository

class UpdateChapter(
    private val chapterRepository: ChapterRepository,
) {

    suspend fun awaitOrThrow(chapterUpdate: ChapterUpdate) {
        chapterRepository.update(chapterUpdate)
    }

    suspend fun awaitAllOrThrow(chapterUpdates: List<ChapterUpdate>) {
        chapterRepository.updateAll(chapterUpdates)
    }

    suspend fun await(chapterUpdate: ChapterUpdate) {
        try {
            awaitOrThrow(chapterUpdate)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
        }
    }

    suspend fun awaitAll(chapterUpdates: List<ChapterUpdate>) {
        try {
            awaitAllOrThrow(chapterUpdates)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
        }
    }
}
