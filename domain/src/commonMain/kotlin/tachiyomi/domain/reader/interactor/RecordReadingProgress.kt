package tachiyomi.domain.reader.interactor

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tachiyomi.domain.reader.model.ReadingProgressEvent
import tachiyomi.domain.reader.model.ReadingSyncSnapshot
import tachiyomi.domain.reader.repository.ReadingProgressRepository

class RecordReadingProgress(private val repository: ReadingProgressRepository) {
    suspend fun await(event: ReadingProgressEvent) = repository.record(event)

    suspend fun openSession(chapterId: Long): ReadingProgressSession =
        ReadingProgressSession(repository, repository.beginSyncSession(chapterId))
}

class ReadingProgressSession internal constructor(
    private val repository: ReadingProgressRepository,
    private var snapshot: ReadingSyncSnapshot,
) {
    private val mutex = Mutex()

    suspend fun await(event: ReadingProgressEvent) = mutex.withLock {
        snapshot = repository.record(event, snapshot)
    }
}
