package tachiyomi.data.reader

import kotlinx.serialization.json.Json
import mihon.data.sync.journal.appendReadingOperation
import mihon.data.sync.journal.readingObjectKeys
import mihon.domain.sync.SyncEffectRef
import mihon.domain.sync.SyncField
import mihon.domain.sync.SyncFieldKey
import tachiyomi.data.Database
import tachiyomi.domain.reader.model.ReadingProgressEvent
import tachiyomi.domain.reader.model.ReadingSyncScope
import tachiyomi.domain.reader.model.ReadingSyncSnapshot
import tachiyomi.domain.reader.repository.ReadingProgressRepository

class SqlDelightReadingProgressRepository(private val database: Database) : ReadingProgressRepository {
    override suspend fun record(event: ReadingProgressEvent) {
        recordTransaction(event, null)
    }

    override suspend fun beginSyncSession(chapterId: Long): ReadingSyncSnapshot = database.transactionWithResult {
        val scope = currentSyncScope() ?: return@transactionWithResult ReadingSyncSnapshot()
        val (chapterKey, mangaKey) = database.readingObjectKeys(chapterId)
        val fields = listOf(
            SyncFieldKey(chapterKey, SyncField.READ_STATUS),
            SyncFieldKey(mangaKey, SyncField.RESUME_POSITION),
            SyncFieldKey(mangaKey, SyncField.READING_SUMMARY),
        )
        ReadingSyncSnapshot(
            scope,
            fields.associateWith { field ->
                database.sync_journalQueries.getHeads(
                    scope.spaceId,
                    scope.generation,
                    field.objectKey.stableKey,
                    field.field.name,
                ).executeAsOneOrNull()?.let { Json.decodeFromString<List<SyncEffectRef>>(it) }.orEmpty()
            },
        )
    }

    override suspend fun record(event: ReadingProgressEvent, snapshot: ReadingSyncSnapshot): ReadingSyncSnapshot =
        requireNotNull(recordTransaction(event, snapshot))

    private fun recordTransaction(event: ReadingProgressEvent, snapshot: ReadingSyncSnapshot?): ReadingSyncSnapshot? =
        database.transactionWithResult {
            database.reading_eventsQueries.insertEvent(
                event.idempotencyKey,
                event.chapterId,
                event.trackerEvent,
                event.lastPageRead.toLong(),
                event.readAt.time,
            )
            if (database.reading_eventsQueries.lastInsertWasNew().executeAsOne() == 0L) {
                return@transactionWithResult snapshot
            }
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
            val scopedEvent = if (snapshot == null) {
                event
            } else {
                event.copy(
                    syncContext = event.syncContext.copy(
                        uploadAllowed = event.syncContext.uploadAllowed && snapshot.scope != null &&
                            snapshot.scope == currentSyncScope(),
                        observedHeads = snapshot.heads,
                    ),
                )
            }
            val recorded = database.appendReadingOperation(scopedEvent)
            if (snapshot == null || recorded == null) return@transactionWithResult snapshot
            snapshot.copy(
                heads = snapshot.heads + recorded.effects.associate {
                    SyncFieldKey(it.objectKey, it.field) to listOf(it.ref(recorded))
                },
            )
        }

    private fun currentSyncScope(): ReadingSyncScope? {
        val actor = database.sync_journalQueries.getActiveActor().executeAsOneOrNull() ?: return null
        return ReadingSyncScope(actor.space_id, actor.generation, actor.actor_id, actor.epoch)
    }
}
