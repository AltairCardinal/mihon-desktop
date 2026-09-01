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
        val pageIdsInOrder: List<ReaderPageId> = List(totalPages.coerceAtLeast(0)) { ordinal ->
            ReaderPageId(chapterId, ordinal)
        },
    ) : ReaderProgressSignal {
        init {
            require(totalPages >= 0) { "totalPages must be non-negative" }
            require(sessionId.isNotBlank()) { "sessionId must not be blank" }
            require(settlementSequence >= 0) { "settlementSequence must be non-negative" }
            require(visiblePageIds.all { it.chapterId == chapterId }) {
                "Every visible page must belong to the settled chapter"
            }
            require(pageIdsInOrder.size == totalPages) {
                "Ordered page identities must match the settled chapter page count"
            }
            require(pageIdsInOrder.distinct().size == pageIdsInOrder.size) {
                "Ordered page identities must be unique"
            }
            require(pageIdsInOrder.all { it.chapterId == chapterId }) {
                "Every ordered page must belong to the settled chapter"
            }
            require(visiblePageIds.all { it in pageIdsInOrder }) {
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
    val settledPageOrdinal: Int,
) {
    val lastPageRead: Int get() = settledPageOrdinal
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
        val ordinalByPageId = signal.pageIdsInOrder.withIndex().associate { (ordinal, pageId) -> pageId to ordinal }
        val settledPageId = signal.visiblePageIds.maxByOrNull { pageId -> ordinalByPageId.getValue(pageId) }
            ?: return null
        val settledPageOrdinal = ordinalByPageId.getValue(settledPageId)
        return ReaderProgressEffect(
            chapterId = signal.chapterId,
            settledPageId = settledPageId,
            totalPages = signal.totalPages,
            wasRead = signal.wasRead,
            settledPageOrdinal = settledPageOrdinal,
            idempotencyKey = buildString {
                append("reader-progress:")
                append(signal.sessionId)
                append(':')
                append(signal.chapterId.value)
                append(':')
                append(settledPageOrdinal)
                append(':')
                append(signal.settlementSequence)
            },
        )
    }
}
