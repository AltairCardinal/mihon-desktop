package tachiyomi.domain.reader.interactor

import tachiyomi.domain.reader.model.ReadingProgressEvent
import tachiyomi.domain.reader.repository.ReadingProgressRepository

class RecordReadingProgress(private val repository: ReadingProgressRepository) {
    suspend fun await(event: ReadingProgressEvent) = repository.record(event)
}
