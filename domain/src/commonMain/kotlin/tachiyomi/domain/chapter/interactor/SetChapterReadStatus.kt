package tachiyomi.domain.chapter.interactor

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mihon.domain.sync.SyncMutationContext
import tachiyomi.core.common.feedback.DiagnosticCategory
import tachiyomi.core.common.feedback.DiagnosticOperation
import tachiyomi.core.common.feedback.diagnosticOperation
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate

class SetChapterReadStatus(
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val updateChapter: UpdateChapter,
    private val prepareReadCleanup: (suspend (List<Chapter>) -> suspend () -> Unit)? = null,
) {
    private val manualCommandLock = Mutex()

    // An explicit command still matters when another device has a different state.
    @Suppress("UNUSED_PARAMETER")
    fun filterToUpdate(chapters: List<Chapter>, read: Boolean): List<Chapter> = chapters.distinctBy { it.id }

    suspend fun awaitOrThrow(mangaId: Long, read: Boolean) {
        awaitOrThrow(getChaptersByMangaId.awaitOrThrow(mangaId), read)
    }

    suspend fun awaitOrThrow(chapters: List<Chapter>, read: Boolean) {
        if (prepareReadCleanup == null) {
            applyReadStatus(chapters, read)
        } else {
            manualCommandLock.withLock { applyReadStatus(chapters, read) }
        }
    }

    private suspend fun applyReadStatus(chapters: List<Chapter>, read: Boolean) {
        val newlyRead = if (read &&
            prepareReadCleanup != null
        ) {
            chapters.groupBy { it.mangaId }.flatMap { (mangaId, selected) ->
                val ids = selected.mapTo(mutableSetOf()) { it.id }
                getChaptersByMangaId.awaitOrThrow(mangaId).filter { it.id in ids && !it.read }
            }
        } else {
            emptyList()
        }
        val cleanup = if (newlyRead.isNotEmpty()) prepareReadCleanup?.invoke(newlyRead) else null
        val updates = filterToUpdate(chapters, read).map { chapter ->
            ChapterUpdate(
                id = chapter.id,
                read = read,
                lastPageRead = if (read) null else 0L,
                syncContext = SyncMutationContext.User,
            )
        }
        if (updates.isNotEmpty()) {
            diagnosticOperation(DiagnosticCategory.LIBRARY_PROGRESS, DiagnosticOperation.PROGRESS) {
                updateChapter.awaitAllOrThrow(updates)
            }
            cleanup?.invoke()
        }
    }

    suspend fun awaitOrThrow(chapter: Chapter, read: Boolean) {
        awaitOrThrow(listOf(chapter), read)
    }
}
