package tachiyomi.domain.reader.repository

import tachiyomi.domain.reader.model.ReadingProgressEvent

interface ReadingProgressRepository {
    suspend fun record(event: ReadingProgressEvent)
}
