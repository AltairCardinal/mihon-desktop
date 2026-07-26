package tachiyomi.domain.chapter.interactor

import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate

class SetChapterReadStatus(
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val updateChapter: UpdateChapter,
) {
    fun filterToUpdate(chapters: List<Chapter>, read: Boolean): List<Chapter> =
        chapters.filter { chapter ->
            if (read) {
                !chapter.read
            } else {
                chapter.read || chapter.lastPageRead > 0
            }
        }

    suspend fun awaitOrThrow(mangaId: Long, read: Boolean) {
        awaitOrThrow(getChaptersByMangaId.awaitOrThrow(mangaId), read)
    }

    suspend fun awaitOrThrow(chapters: List<Chapter>, read: Boolean) {
        val updates = filterToUpdate(chapters, read).map { chapter ->
            ChapterUpdate(
                id = chapter.id,
                read = read,
                lastPageRead = if (read) null else 0L,
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
