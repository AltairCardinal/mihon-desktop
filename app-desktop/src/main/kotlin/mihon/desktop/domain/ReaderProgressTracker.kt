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
 * - Captures ordinary cleanup through shared preferences; the owning reader closes before deletion.
 */
class ReaderProgressTracker(
    private val recordReadingProgress: RecordReadingProgress,
    private val appPreferences: DesktopAppPreferences? = null,
    private val downloadPreferences: DesktopDownloadPreferences? = null,
    private val downloadManager: DesktopDownloadManager? = null,
    private val trackSync: ReadingProgressTrackSync? = null,
    private val extensionPackageForSource: (Long) -> String? = { null },
    private val readDownloadDeletion: mihon.desktop.download.DesktopReadDownloadDeletion? = null,
) {
    fun newDownloadDeletionSession(): ReaderReadDownloadDeletionSession? = readDownloadDeletion?.let {
        ReaderReadDownloadDeletionSession(requireNotNull(downloadPreferences).shared, it)
    }

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
        downloadDeletionSession: ReaderReadDownloadDeletionSession? = null,
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

        if (totalPages > 0 && lastPageRead >= totalPages - 1) downloadDeletionSession?.completed(chapterId)
    }
}
