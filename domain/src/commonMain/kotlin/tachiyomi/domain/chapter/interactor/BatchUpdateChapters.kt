package tachiyomi.domain.chapter.interactor

import tachiyomi.domain.chapter.model.Chapter

class BatchUpdateChapters {
    suspend fun await(chapters: List<Chapter>, action: suspend (Chapter) -> Unit): BatchChapterResult {
        if (chapters.isEmpty()) return BatchChapterResult.Empty
        val succeeded = mutableListOf<Long>()
        val failed = mutableListOf<BatchChapterFailure>()
        chapters.forEach { chapter ->
            try {
                action(chapter)
                succeeded += chapter.id
            } catch (e: Exception) {
                failed += BatchChapterFailure(chapter.id, e.message ?: e::class.simpleName ?: "Unknown error")
            }
        }
        return BatchChapterResult(succeeded, failed)
    }
}

data class BatchChapterResult(val succeededIds: List<Long>, val failures: List<BatchChapterFailure>) {
    companion object {
        val Empty = BatchChapterResult(emptyList(), emptyList())
    }
}

data class BatchChapterFailure(val id: Long, val message: String)
