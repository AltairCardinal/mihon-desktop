package mihon.desktop.domain

import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.history.interactor.UpsertHistory
import tachiyomi.domain.history.model.HistoryUpdate
import java.util.Date

/**
 * Records reading progress when the reader exits or finishes a chapter.
 *
 * - Marks the chapter as read when [lastPageRead] reaches the last page.
 * - Always persists [lastPageRead] so the reader can resume later.
 * - Always records a [HistoryUpdate] for the reading history screen.
 */
class ReaderProgressTracker(
    private val updateChapter: UpdateChapter,
    private val upsertHistory: UpsertHistory,
) {

    suspend fun track(
        chapterId: Long,
        lastPageRead: Int,
        totalPages: Int,
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

        upsertHistory.await(
            HistoryUpdate(
                chapterId = chapterId,
                readAt = readAt,
                sessionReadDuration = sessionReadDuration,
            ),
        )
    }
}
