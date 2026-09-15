package mihon.data.sync.journal

import kotlinx.serialization.json.Json
import mihon.domain.sync.SyncBatch
import mihon.domain.sync.SyncBatchCodec
import mihon.domain.sync.SyncCategory
import mihon.domain.sync.SyncCodec
import mihon.domain.sync.SyncEffect
import mihon.domain.sync.SyncEffectKind
import mihon.domain.sync.SyncEffectRef
import mihon.domain.sync.SyncEventEnvelope
import mihon.domain.sync.SyncField
import mihon.domain.sync.SyncFieldKey
import mihon.domain.sync.SyncMutationContext
import mihon.domain.sync.SyncObjectKey
import mihon.domain.sync.SyncObjectType
import mihon.domain.sync.SyncOrigin
import mihon.domain.sync.SyncProtocol
import tachiyomi.data.Database
import java.util.UUID

/** Called inside the existing business transaction; no separate connection, coroutine or commit. */
internal fun Database.appendSyncOperation(
    context: SyncMutationContext,
    category: SyncCategory,
    effects: List<SyncEffect>,
    occurredAt: Long = System.currentTimeMillis(),
): SyncEventEnvelope? {
    if (!context.uploadAllowed || context.origin != SyncOrigin.USER) return null
    val queries = sync_journalQueries
    val actor = queries.getActiveActor().executeAsOneOrNull() ?: return null
    require(actor.next_seq < Long.MAX_VALUE) { "sync sequence requires a new device epoch" }
    val linkedEffects = effects.map { effect ->
        val fieldKey = SyncFieldKey(effect.objectKey, effect.field)
        val observedHeads = context.observedHeads
        val heads = if (observedHeads != null) {
            observedHeads[fieldKey].orEmpty()
        } else {
            queries.getHeads(actor.space_id, actor.generation, effect.objectKey.stableKey, effect.field.name)
                .executeAsOneOrNull()?.let { Json.decodeFromString<List<SyncEffectRef>>(it) }.orEmpty()
        }
        effect.copy(parents = heads)
    }
    var open = queries.getOpenBatch(actor.space_id, actor.generation, actor.actor_id, actor.epoch).executeAsOneOrNull()
    fun envelope(batchId: String) = SyncEventEnvelope(
        protocolVersion = SyncProtocol.CURRENT_VERSION,
        spaceId = actor.space_id,
        generation = actor.generation,
        actorId = actor.actor_id,
        epoch = actor.epoch,
        seq = actor.next_seq,
        category = category,
        effects = linkedEffects,
        origin = SyncOrigin.USER,
        occurredAt = occurredAt,
        batchId = batchId,
    )
    var event = envelope(open?.batch_id ?: UUID.randomUUID().toString())
    var encoded = SyncCodec.encode(event)
    var eventBytes = encoded.encodeToByteArray().size.toLong()
    if (open != null && (
            open.event_count >= SyncProtocol.MAX_EVENTS_PER_BATCH ||
                open.plaintext_bytes + eventBytes + 1 > SyncProtocol.MAX_PLAINTEXT_BYTES_PER_BATCH
            )
    ) {
        queries.sealBatch(actor.space_id, actor.generation, open.batch_id)
        open = null
        event = envelope(UUID.randomUUID().toString())
        encoded = SyncCodec.encode(event)
        eventBytes = encoded.encodeToByteArray().size.toLong()
    }
    val batchId = requireNotNull(event.batchId)
    val previousBytes = open?.plaintext_bytes ?: SyncBatchCodec.rawEncode(
        SyncBatch(SyncProtocol.CURRENT_VERSION, actor.space_id, actor.generation, batchId, emptyList()),
    ).encodeToByteArray().size.toLong()
    val totalBytes = previousBytes + eventBytes + if ((open?.event_count ?: 0) > 0) 1 else 0
    require(totalBytes <= SyncProtocol.MAX_PLAINTEXT_BYTES_PER_BATCH) { "sync operation exceeds batch limit" }
    if (open == null) {
        queries.insertBatch(
            actor.space_id,
            actor.generation,
            batchId,
            actor.actor_id,
            actor.epoch,
            actor.next_seq,
            actor.next_seq,
            previousBytes,
        )
    }
    queries.insertEvent(
        actor.space_id, actor.generation, actor.actor_id, actor.epoch, actor.next_seq,
        category.name, SyncOrigin.USER.name, batchId, encoded, occurredAt,
    )
    queries.insertOutbox(actor.space_id, actor.generation, actor.actor_id, actor.epoch, actor.next_seq, batchId)
    linkedEffects.forEach { effect ->
        val currentHeads = queries.getHeads(
            actor.space_id,
            actor.generation,
            effect.objectKey.stableKey,
            effect.field.name,
        ).executeAsOneOrNull()?.let { Json.decodeFromString<List<SyncEffectRef>>(it) }.orEmpty()
        val remainingHeads = currentHeads.filterNot { it in effect.parents }
        queries.setHeads(
            actor.space_id,
            actor.generation,
            effect.objectKey.stableKey,
            effect.field.name,
            Json.encodeToString(remainingHeads + effect.ref(event)),
        )
    }
    queries.updateBatch(actor.next_seq, totalBytes, actor.space_id, actor.generation, batchId)
    queries.advanceSequence(actor.space_id, actor.generation, actor.actor_id, actor.epoch)
    return event
}

internal fun Database.appendFavoriteOperation(mangaId: Long, favorite: Boolean, context: SyncMutationContext) {
    if (!context.uploadAllowed || context.origin != SyncOrigin.USER) return
    val manga = mangasQueries.getMangaById(mangaId).executeAsOne()
    appendSyncOperation(
        context,
        SyncCategory.FAVORITE,
        listOf(
            SyncEffect(
                effectId = "favorite",
                objectKey = SyncObjectKey(
                    SyncObjectType.MANGA,
                    sourceId = manga.source.toString(),
                    originalUrl = manga.url,
                ),
                field = SyncField.FAVORITE,
                kind = if (favorite) SyncEffectKind.ADD else SyncEffectKind.REMOVE,
            ),
        ),
    )
}
