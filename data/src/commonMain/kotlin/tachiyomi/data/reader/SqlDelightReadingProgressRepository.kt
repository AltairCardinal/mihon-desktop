package tachiyomi.data.reader

import tachiyomi.data.Database
import tachiyomi.domain.reader.model.ReadingProgressEvent
import tachiyomi.domain.reader.repository.ReadingProgressRepository

class SqlDelightReadingProgressRepository(private val database: Database) : ReadingProgressRepository {
    override suspend fun record(event: ReadingProgressEvent) {
        database.transaction {
            database.reading_eventsQueries.insertEvent(
                event.idempotencyKey,
                event.chapterId,
                event.trackerEvent,
                event.lastPageRead.toLong(),
                event.readAt.time,
            )
            if (database.reading_eventsQueries.lastInsertWasNew().executeAsOne() == 0L) return@transaction
            database.chaptersQueries.update(
                mangaId = null,
                url = null,
                name = null,
                scanlator = null,
                read = event.isRead,
                bookmark = null,
                lastPageRead = event.lastPageRead.toLong(),
                chapterNumber = null,
                sourceOrder = null,
                dateFetch = null,
                dateUpload = null,
                chapterId = event.chapterId,
                version = null,
                isSyncing = 0,
            )
            if (event.recordHistory) {
                database.historyQueries.upsert(event.chapterId, event.readAt, event.sessionReadDuration)
            }
        }
    }
}
