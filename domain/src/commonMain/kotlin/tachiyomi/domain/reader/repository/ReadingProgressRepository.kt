package tachiyomi.domain.reader.repository

import tachiyomi.domain.reader.model.ReadingProgressEvent
import tachiyomi.domain.reader.model.ReadingSyncSnapshot

interface ReadingProgressRepository {
    suspend fun record(event: ReadingProgressEvent)

    suspend fun beginSyncSession(chapterId: Long): ReadingSyncSnapshot = ReadingSyncSnapshot()

    suspend fun record(event: ReadingProgressEvent, snapshot: ReadingSyncSnapshot): ReadingSyncSnapshot {
        record(event)
        return snapshot
    }
}
