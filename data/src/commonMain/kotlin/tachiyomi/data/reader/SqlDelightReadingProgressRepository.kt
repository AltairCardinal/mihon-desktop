package tachiyomi.data.reader

import kotlinx.serialization.json.Json
import mihon.data.sync.journal.appendReadingOperation
import mihon.data.sync.journal.readingObjectKeys
import mihon.domain.sync.SyncCodec
import mihon.domain.sync.SyncDecodeResult
import mihon.domain.sync.SyncEffectMetadata
import mihon.domain.sync.SyncEffectRef
import mihon.domain.sync.SyncField
import mihon.domain.sync.SyncFieldKey
import mihon.domain.sync.SyncObjectKey
import mihon.domain.sync.SyncObjectType
import mihon.domain.sync.SyncOrigin
import mihon.domain.sync.SyncProjection
import mihon.domain.sync.SyncReadingPolicy
import mihon.domain.sync.SyncReadingSession
import tachiyomi.data.Database
import tachiyomi.domain.reader.model.ReadingProgressEvent
import tachiyomi.domain.reader.model.ReadingResumePosition
import tachiyomi.domain.reader.model.ReadingSyncScope
import tachiyomi.domain.reader.model.ReadingSyncSnapshot
import tachiyomi.domain.reader.repository.ReadingProgressRepository

class SqlDelightReadingProgressRepository(private val database: Database) : ReadingProgressRepository {
    override suspend fun resumePosition(mangaId: Long): ReadingResumePosition? = database.transactionWithResult {
        val scope = currentSyncScope() ?: return@transactionWithResult null
        val manga = database.mangasQueries.getMangaById(mangaId).executeAsOneOrNull()
            ?: return@transactionWithResult null
        val key = SyncObjectKey(SyncObjectType.MANGA, sourceId = manga.source.toString(), originalUrl = manga.url)
        val refs = database.sync_journalQueries.getHeads(
            scope.spaceId,
            scope.generation,
            key.stableKey,
            SyncField.RESUME_POSITION.name,
        ).executeAsOneOrNull()?.let { Json.decodeFromString<List<SyncEffectRef>>(it) }.orEmpty()
        if (refs.isEmpty()) return@transactionWithResult null
        // Heads are already validated by the journal/projector. Read their envelopes, not the entire history.
        val events = refs.map { it.eventId.stableKey }.distinct().chunked(256).flatMap { chunk ->
            database.sync_inboxQueries.getEventsByKey(scope.spaceId, scope.generation, chunk).executeAsList()
        }.mapNotNull { (SyncCodec.decode(it.event_json) as? SyncDecodeResult.Accepted)?.event }
            .associateBy { it.eventId }
        val effects = refs.mapNotNull { ref ->
            events[ref.eventId]?.effects?.find { it.effectId == ref.effectId }?.takeIf {
                it.objectKey == key && it.field == SyncField.RESUME_POSITION
            }?.let { ref to it }
        }.toMap()
        val projection = SyncProjection(
            SyncFieldKey(key, SyncField.RESUME_POSITION),
            refs,
            effects.values.toList(),
            metadata = refs.mapNotNull { ref ->
                events[ref.eventId]?.let { ref to SyncEffectMetadata(it.occurredAt, it.origin) }
            }.toMap(),
            effectsByRef = effects,
        )
        val position = SyncReadingPolicy.chooseResume(projection, SyncReadingSession(key, "", 0, ""))
            .nextPosition ?: return@transactionWithResult null
        val chapter = database.chaptersQueries.getChaptersByMangaId(mangaId, 0).executeAsList().find {
            SyncObjectKey(
                SyncObjectType.CHAPTER,
                sourceId = manga.source.toString(),
                originalUrl = it.url,
                parentUrl = manga.url,
            ).stableKey ==
                position.chapterKey
        } ?: return@transactionWithResult null
        ReadingResumePosition(chapter._id, position.pageIndex, syncSnapshot(chapter._id))
    }

    override suspend fun record(event: ReadingProgressEvent) {
        recordTransaction(event, null)
    }

    override suspend fun beginSyncSession(chapterId: Long): ReadingSyncSnapshot =
        database.transactionWithResult { syncSnapshot(chapterId) }

    private fun syncSnapshot(chapterId: Long): ReadingSyncSnapshot {
        val scope = currentSyncScope() ?: return ReadingSyncSnapshot()
        val (chapterKey, mangaKey) = database.readingObjectKeys(chapterId)
        val fields = listOf(
            SyncFieldKey(chapterKey, SyncField.READ_STATUS),
            SyncFieldKey(mangaKey, SyncField.RESUME_POSITION),
            SyncFieldKey(mangaKey, SyncField.READING_SUMMARY),
        )
        return ReadingSyncSnapshot(
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
            if (!event.syncContext.uploadAllowed) {
                database.sync_importQueries.markPrivateReading(event.chapterId)
            } else if (event.syncContext.origin == SyncOrigin.USER) {
                database.sync_importQueries.advancePublicReading(
                    finished = if (event.totalPages > 0 && event.lastPageRead >= event.totalPages - 1) 1L else 0L,
                    page = event.lastPageRead.toLong(),
                    readAt = if (event.recordHistory) event.readAt.time else -1L,
                    chapterId = event.chapterId,
                )
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
