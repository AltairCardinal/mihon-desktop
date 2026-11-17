package mihon.desktop.domain

import mihon.desktop.download.DesktopReadDownloadDeletion
import mihon.desktop.reader.ReaderChapterRef
import mihon.domain.reader.ChapterSkipPolicy
import mihon.domain.reader.ReaderChapterEntry
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.download.service.readDownloadDeletionCandidate
import tachiyomi.domain.download.service.readDownloadDeletionCatalog

/** Finite, in-memory ordinary-mode cleanup, consumed when this reader closes. */
class ReaderReadDownloadDeletionSession(
    private val preferences: DownloadPreferences,
    private val deletion: DesktopReadDownloadDeletion,
) {
    var chapters: () -> List<ReaderChapterRef> = { emptyList() }
    var skipPolicy: () -> ChapterSkipPolicy = { ChapterSkipPolicy() }
    private val acceptedRead = mutableSetOf<Long>()
    private val pending = linkedMapOf<Long, DesktopReadDownloadDeletion.ReaderCapture>()
    private val resourcesReleased = kotlinx.coroutines.CompletableDeferred<Unit>()
    internal fun releaseResources() {
        resourcesReleased.complete(Unit)
    }

    suspend fun completed(chapterId: Long) {
        val slots = preferences.removeAfterReadSlots().get()
        acceptedRead += chapterId
        val catalog = readDownloadDeletionCatalog(
            chapters().asReversed().map {
                ReaderChapterEntry(it.id, it.isRead, it.isFiltered, it.isDuplicate, it.chapterNumber, it.scanlator)
            },
            chapterId,
            skipPolicy(),
        )
        val candidate = readDownloadDeletionCandidate(catalog, catalog.indexOfFirst { it.id == chapterId }, slots) {
            it.isRead || it.id in acceptedRead
        } ?: return
        if (candidate.id in pending) return
        val chapter = deletion.chapter(candidate.id) ?: return
        deletion.captureReader(chapter.copy(read = true))?.let { pending[candidate.id] = it }
    }

    suspend fun close() {
        resourcesReleased.await()
        val work = pending.values.toList()
        pending.clear()
        var failure: Exception? = null
        work.forEach {
            try {
                deletion.deleteReader(it)
            } catch (error: Exception) {
                failure = failure ?: error
            }
        }
        failure?.let { throw it }
    }
}
