package tachiyomi.domain.chapter.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate

interface ChapterRepository {

    suspend fun pendingDirectoryPhase(mangaId: Long): tachiyomi.domain.chapter.service.ChapterDirectoryPhase? = null

    suspend fun acknowledgeDirectoryPhase(phase: tachiyomi.domain.chapter.service.ChapterDirectoryPhase): Unit =
        throw UnsupportedOperationException("Directory phase acknowledgements are not supported")

    suspend fun getChapterUrlIdentity(chapterId: Long): tachiyomi.domain.chapter.model.ChapterUrlIdentity? =
        getChapterById(chapterId)?.let { tachiyomi.domain.chapter.model.ChapterUrlIdentity(it.url) }

    suspend fun restoreChapterUrlIdentity(
        mangaId: Long,
        chapterId: Long,
        identity: tachiyomi.domain.chapter.model.ChapterUrlIdentity,
    ) {
        val chapter = requireNotNull(getChapterById(chapterId))
        require(chapter.mangaId == mangaId && identity.canonicalUrl == chapter.url && identity.aliases.isEmpty()) {
            "This repository cannot restore chapter URL aliases"
        }
    }

    /** Atomically commits one complete source directory, merging the latest stored user state. */
    suspend fun syncDirectory(request: tachiyomi.domain.chapter.service.ChapterDirectoryCommit):
        tachiyomi.domain.chapter.service.ChapterDirectoryResult =
        throw UnsupportedOperationException("Atomic chapter directory commits are not supported by this repository")

    suspend fun addAll(chapters: List<Chapter>): List<Chapter>

    suspend fun update(chapterUpdate: ChapterUpdate)

    suspend fun updateAll(chapterUpdates: List<ChapterUpdate>)

    suspend fun removeChaptersWithIds(chapterIds: List<Long>)

    suspend fun getChapterByMangaId(mangaId: Long, applyScanlatorFilter: Boolean = false): List<Chapter>

    suspend fun getScanlatorsByMangaId(mangaId: Long): List<String>

    fun getScanlatorsByMangaIdAsFlow(mangaId: Long): Flow<List<String>>

    suspend fun getBookmarkedChaptersByMangaId(mangaId: Long): List<Chapter>

    suspend fun getChapterById(id: Long): Chapter?

    suspend fun getChapterByMangaIdAsFlow(mangaId: Long, applyScanlatorFilter: Boolean = false): Flow<List<Chapter>>

    suspend fun getChapterByUrlAndMangaId(url: String, mangaId: Long): Chapter?
}
