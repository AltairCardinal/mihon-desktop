package mihon.domain.reader.progress

import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderPageId

sealed interface ReaderProgressSignal {
    data class ViewportSettled(
        val activeChapterId: ReaderChapterId,
        val chapterId: ReaderChapterId,
        val visiblePageIds: Set<ReaderPageId>,
        val totalPages: Int,
        val wasRead: Boolean,
        val sessionId: String,
        val settlementSequence: Long,
    ) : ReaderProgressSignal {
        init {
            require(totalPages >= 0) { "totalPages must be non-negative" }
            require(sessionId.isNotBlank()) { "sessionId must not be blank" }
            require(settlementSequence >= 0) { "settlementSequence must be non-negative" }
            require(visiblePageIds.all { it.chapterId == chapterId }) {
                "Every visible page must belong to the settled chapter"
            }
            require(visiblePageIds.all { it.sourcePageIndex < totalPages }) {
                "Every visible page must be within the settled chapter page list"
            }
        }
    }

    data class ChapterOpened(val chapterId: ReaderChapterId) : ReaderProgressSignal

    data class PagePrepared(val pageId: ReaderPageId) : ReaderProgressSignal
}

data class ReaderProgressEffect(
    val chapterId: ReaderChapterId,
    val settledPageId: ReaderPageId,
    val totalPages: Int,
    val wasRead: Boolean,
    val idempotencyKey: String,
) {
    val lastPageRead: Int get() = settledPageId.sourcePageIndex
    val reachedLastPage: Boolean get() = totalPages > 0 && lastPageRead >= totalPages - 1
    val isRead: Boolean get() = wasRead || reachedLastPage
}

object ReaderProgressPolicy {
    fun reduce(signal: ReaderProgressSignal): ReaderProgressEffect? = when (signal) {
        is ReaderProgressSignal.ViewportSettled -> settle(signal)
        is ReaderProgressSignal.ChapterOpened,
        is ReaderProgressSignal.PagePrepared,
        -> null
    }

    private fun settle(signal: ReaderProgressSignal.ViewportSettled): ReaderProgressEffect? {
        if (signal.chapterId != signal.activeChapterId) return null
        val settledPageId = signal.visiblePageIds.maxByOrNull(ReaderPageId::sourcePageIndex) ?: return null
        return ReaderProgressEffect(
            chapterId = signal.chapterId,
            settledPageId = settledPageId,
            totalPages = signal.totalPages,
            wasRead = signal.wasRead,
            idempotencyKey = buildString {
                append("reader-progress:")
                append(signal.sessionId)
                append(':')
                append(signal.chapterId.value)
                append(':')
                append(settledPageId.sourcePageIndex)
                append(':')
                append(signal.settlementSequence)
            },
        )
    }
}
