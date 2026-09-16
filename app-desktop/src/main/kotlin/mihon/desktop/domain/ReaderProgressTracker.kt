package mihon.desktop.domain

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import mihon.desktop.download.DesktopDownloadManager
import mihon.desktop.download.DesktopDownloadPreferences
import mihon.desktop.settings.DesktopAppPreferences
import mihon.domain.reader.content.DownloadChapterIdentity
import mihon.domain.sync.SyncMutationContext
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.reader.interactor.ReadingProgressSession
import tachiyomi.domain.reader.interactor.RecordReadingProgress
import tachiyomi.domain.reader.model.ReadingProgressEvent
import tachiyomi.domain.track.interactor.ReadingProgressTrackSync
import tachiyomi.domain.track.interactor.TrackerSyncRequest
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
    private val trackSync: ReadingProgressTrackSync? = null,
    private val extensionPackageForSource: (Long) -> String? = { null },
) {

    suspend fun openSession(
        chapterId: Long,
        snapshot: tachiyomi.domain.reader.model.ReadingSyncSnapshot? = null,
    ): ReadingProgressSession = recordReadingProgress.openSession(chapterId, snapshot)

    fun isIncognito(sourceId: Long?): Boolean {
        val extensionPackage = sourceId?.let(extensionPackageForSource)
        return appPreferences?.incognitoMode?.get() == true ||
            (extensionPackage != null && extensionPackage in appPreferences?.incognitoExtensions?.get().orEmpty())
    }

    suspend fun track(
        eventId: String,
        chapterId: Long,
        lastPageRead: Int,
        totalPages: Int,
        sourceId: Long?,
        manga: Manga? = null,
        chapterName: String? = null,
        mangaId: Long = 0L,
        chapterNumber: Double? = null,
        wasRead: Boolean = false,
        readAt: Date = Date(),
        sessionReadDuration: Long = 0L,
        downloadIdentity: DownloadChapterIdentity? = null,
        readingSession: ReadingProgressSession? = null,
        incognitoAtAcceptance: Boolean? = null,
    ) {
        val isRead = wasRead || (totalPages > 0 && lastPageRead >= totalPages - 1)

        val incognito = incognitoAtAcceptance ?: isIncognito(sourceId)
        val event = ReadingProgressEvent(
            chapterId = chapterId,
            lastPageRead = lastPageRead,
            totalPages = totalPages,
            readAt = readAt,
            sessionReadDuration = sessionReadDuration,
            trackerEvent = if (isRead) "finished" else "progress",
            recordHistory = !incognito,
            idempotencyKey = eventId,
            wasRead = wasRead,
            syncContext = if (incognito) SyncMutationContext.LocalOnly else SyncMutationContext.User,
        )
        if (readingSession == null) recordReadingProgress.await(event) else readingSession.await(event)

        val autoUpdateTrack = appPreferences?.autoUpdateTrack?.get() != false
        if (!incognito && autoUpdateTrack && isRead && mangaId > 0 && chapterNumber != null && chapterNumber >= 0) {
            withContext(NonCancellable) {
                trackSync?.sync(TrackerSyncRequest(eventId, mangaId, chapterNumber))
            }
        }

        // Auto-delete downloaded chapter when fully read
        if (isRead) {
            val shouldDelete = downloadPreferences?.deleteAfterRead?.get() == true
            if (shouldDelete) {
                when {
                    sourceId != null && downloadIdentity != null ->
                        downloadManager?.deleteDownload(sourceId, downloadIdentity)
                    manga != null && chapterName != null ->
                        downloadManager?.deleteDownload(
                            sourceId = manga.source,
                            mangaTitle = manga.title,
                            chapterName = chapterName,
                        )
                }
            }
        }
    }
}
