package tachiyomi.domain.chapter.interactor

import mihon.domain.sync.SyncMutationContext
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate

class SetChapterReadStatus(
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val updateChapter: UpdateChapter,
) {
    // An explicit command still matters when another device has a different state.
    @Suppress("UNUSED_PARAMETER")
    fun filterToUpdate(chapters: List<Chapter>, read: Boolean): List<Chapter> = chapters.distinctBy { it.id }

    suspend fun awaitOrThrow(mangaId: Long, read: Boolean) {
        awaitOrThrow(getChaptersByMangaId.awaitOrThrow(mangaId), read)
    }

    suspend fun awaitOrThrow(chapters: List<Chapter>, read: Boolean) {
        val updates = filterToUpdate(chapters, read).map { chapter ->
            ChapterUpdate(
                id = chapter.id,
                read = read,
                lastPageRead = if (read) null else 0L,
                syncContext = SyncMutationContext.User,
            )
        }
        if (updates.isNotEmpty()) {
            updateChapter.awaitAllOrThrow(updates)
        }
    }

    suspend fun awaitOrThrow(chapter: Chapter, read: Boolean) {
        awaitOrThrow(listOf(chapter), read)
    }
}
