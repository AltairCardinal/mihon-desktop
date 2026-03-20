package mihon.desktop.domain

import mihon.desktop.download.DesktopDownloadManager
import mihon.desktop.download.DesktopDownloadPreferences
import mihon.desktop.settings.DesktopAppPreferences
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.history.interactor.UpsertHistory
import tachiyomi.domain.history.model.HistoryUpdate
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
    private val updateChapter: UpdateChapter,
    private val upsertHistory: UpsertHistory,
    private val appPreferences: DesktopAppPreferences? = null,
    private val downloadPreferences: DesktopDownloadPreferences? = null,
    private val downloadManager: DesktopDownloadManager? = null,
) {

    suspend fun track(
        chapterId: Long,
        lastPageRead: Int,
        totalPages: Int,
        manga: Manga? = null,
        chapterName: String? = null,
        readAt: Date = Date(),
        sessionReadDuration: Long = 0L,
    ) {
        val isRead = totalPages > 0 && lastPageRead >= totalPages - 1

        updateChapter.await(
            ChapterUpdate(
                id = chapterId,
                read = isRead,
                lastPageRead = lastPageRead.toLong(),
            ),
        )

        // Skip history in incognito mode
        val incognito = appPreferences?.incognitoMode?.get() == true
        if (!incognito) {
            upsertHistory.await(
                HistoryUpdate(
                    chapterId = chapterId,
                    readAt = readAt,
                    sessionReadDuration = sessionReadDuration,
                ),
            )
        }

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
