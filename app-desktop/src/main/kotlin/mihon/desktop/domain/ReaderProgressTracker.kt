package mihon.desktop.domain

import mihon.desktop.download.DesktopDownloadManager
import mihon.desktop.download.DesktopDownloadPreferences
import mihon.desktop.settings.DesktopAppPreferences
import tachiyomi.domain.reader.interactor.RecordReadingProgress
import tachiyomi.domain.reader.model.ReadingProgressEvent
import tachiyomi.domain.manga.model.Manga
import java.util.Date

/**
 * Records reading progress when the reader exits or finishes a chapter.
 *
 * - Marks the chapter as read when [lastPageRead] reaches the last page.
 * - Always persists [lastPageRead] so the reader can resume later.
 * - Records history unless incognito mode is enabled.
 * - Deletes downloaded chapter after read when [DesktopDownloadPreferences.deleteAfterRead] is true.
 */
class ReaderProgressTracker(
    private val recordReadingProgress: RecordReadingProgress,
    private val appPreferences: DesktopAppPreferences? = null,
    private val downloadPreferences: DesktopDownloadPreferences? = null,
    private val downloadManager: DesktopDownloadManager? = null,
) {

    suspend fun track(
        eventId: String,
        chapterId: Long,
        lastPageRead: Int,
        totalPages: Int,
        manga: Manga? = null,
        chapterName: String? = null,
        mangaId: Long = 0L,
        readAt: Date = Date(),
        sessionReadDuration: Long = 0L,
    ) {
        val isRead = totalPages > 0 && lastPageRead >= totalPages - 1

        val incognito = appPreferences?.incognitoMode?.get() == true
        recordReadingProgress.await(
            ReadingProgressEvent(
                chapterId = chapterId,
                lastPageRead = lastPageRead,
                totalPages = totalPages,
                readAt = readAt,
                sessionReadDuration = sessionReadDuration,
                trackerEvent = if (isRead) "finished" else "progress",
                recordHistory = !incognito,
                idempotencyKey = eventId,
            ),
        )

        // Auto-delete downloaded chapter when fully read
        if (isRead && manga != null && chapterName != null) {
            val shouldDelete = downloadPreferences?.deleteAfterRead?.get() == true
            if (shouldDelete) {
                downloadManager?.deleteDownload(
                    sourceId = manga.source,
                    mangaTitle = manga.title,
                    chapterName = chapterName,
                )
            }
        }
    }
}
