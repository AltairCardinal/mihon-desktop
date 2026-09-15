package mihon.data.sync.inbox

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import mihon.data.sync.projection.SyncProjectionUnavailable
import mihon.data.sync.projection.SyncProjectionUnavailableReason
import mihon.data.sync.projection.SyncRemoteProjectionWriter
import mihon.data.sync.projection.resolveSyncAuthorIdentity
import mihon.domain.sync.SyncCancellationDecision
import mihon.domain.sync.SyncCodec
import mihon.domain.sync.SyncDecodeResult
import mihon.domain.sync.SyncEventEnvelope
import mihon.domain.sync.SyncField
import mihon.domain.sync.SyncObjectDescriptor
import mihon.domain.sync.SyncObjectKey
import mihon.domain.sync.SyncObjectType
import mihon.domain.sync.SyncProjection
import mihon.domain.sync.SyncReadStatus
import mihon.domain.sync.SyncReadingPolicy
import mihon.domain.sync.SyncReadingSession
import mihon.domain.sync.SyncReceiver
import mihon.domain.sync.SyncReceiverDecisionResult
import mihon.domain.sync.SyncReducer
import mihon.domain.sync.SyncReduction
import tachiyomi.data.Database
import tachiyomi.data.DatabaseHandler
import tachiyomi.data.Sync_field_state
import java.util.UUID
import kotlin.coroutines.coroutineContext

data class SyncPendingItem(val id: Long, val binding: String, val objectKey: SyncObjectKey, val title: String)
data class SyncBulkProgress(val total: Long, val queued: Long, val outcomes: Map<String, Long>)

class SyncInboxProjector(private val handler: DatabaseHandler, private val writer: SyncRemoteProjectionWriter) {
    /** Receipt is durable first; each field applies atomically in a bounded transaction. */
    suspend fun project(spaceId: String, generation: Long, limit: Int = 50): Int {
        require(limit in 1..50)
        writer.prepare()
        val fields = handler.await {
            if (!active(spaceId, generation)) return@await emptyList()
            sync_inboxQueries.getDirtyFields(spaceId, generation, limit.toLong()).executeAsList()
        }
        fields.forEach { entry ->
            try {
                handler.await(inTransaction = true) {
                    if (!active(spaceId, generation)) return@await
                    val current = sync_inboxQueries.getFieldState(spaceId, generation, entry.object_key, entry.field_)
                        .executeAsOneOrNull() ?: return@await
                    if (current.dirty) projectField(current)
                }
            } catch (failure: SyncProjectionUnavailable) {
                handler.await(inTransaction = true) {
                    if (active(spaceId, generation)) finish(entry, failure.reason.name, entry.applied_heads)
                }
            }
        }
        return fields.size
    }

    suspend fun retryUnavailable(spaceId: String, generation: Long) = handler.await(inTransaction = true) {
        if (active(spaceId, generation)) sync_inboxQueries.retryUnavailable(spaceId, generation)
    }

    suspend fun pending(spaceId: String, generation: Long, limit: Int = 100, offset: Long = 0): List<SyncPendingItem> {
        require(limit in 1..100 && offset >= 0)
        return handler.await {
            if (!active(spaceId, generation)) return@await emptyList()
            sync_inboxQueries.getPendingDecisions(spaceId, generation, limit.toLong(), offset).executeAsList().map {
                SyncPendingItem(it._id, it.binding, Json.decodeFromString(it.object_json), it.title)
            }
        }
    }

    suspend fun decide(
        spaceId: String,
        generation: Long,
        item: SyncPendingItem,
        decision: SyncCancellationDecision,
    ): SyncReceiverDecisionResult {
        writer.prepare()
        return handler.await(inTransaction = true) { decideInTransaction(spaceId, generation, item, decision) }
    }

    suspend fun startBulk(
        spaceId: String,
        generation: Long,
        decision: SyncCancellationDecision,
        selected: List<Long>? = null,
    ): String = handler.await(inTransaction = true) {
        require(active(spaceId, generation)) { "sync space is not active" }
        val job = UUID.randomUUID().toString()
        sync_inboxQueries.insertBulkJob(job, spaceId, generation, decision.name)
        if (selected == null) {
            sync_inboxQueries.freezeAllPending(job, spaceId, generation)
        } else {
            selected.distinct().chunked(256).forEach {
                sync_inboxQueries.freezeSelectedPending(job, spaceId, generation, it)
            }
        }
        job
    }

    suspend fun processBulk(jobId: String, limit: Int = 50): SyncBulkProgress {
        require(limit in 1..50)
        writer.prepare()
        repeat(limit) {
            var itemId: Long? = null
            try {
                val processed = handler.await(inTransaction = true) {
                    val job = sync_inboxQueries.getBulkJob(jobId).executeAsOne()
                    if (!active(job.space_id, job.generation)) return@await false
                    val item = sync_inboxQueries.getBulkItems(jobId, 1).executeAsOneOrNull() ?: return@await false
                    itemId = item.pending_id
                    val outcome = decideInTransaction(
                        job.space_id,
                        job.generation,
                        SyncPendingItem(item.pending_id, item.binding, Json.decodeFromString(item.object_json), ""),
                        SyncCancellationDecision.valueOf(job.decision),
                    )
                    sync_inboxQueries.finishBulkItem(outcome.name, jobId, item.pending_id)
                    true
                }
                if (!processed) return bulkProgress(jobId)
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                coroutineContext.ensureActive()
                val failedId = itemId ?: throw failure
                // The decision transaction has rolled back. Keep its pending row and report this item independently.
                handler.await(inTransaction = true) {
                    sync_inboxQueries.finishBulkItem("FAILED", jobId, failedId)
                }
            }
        }
        return bulkProgress(jobId)
    }

    private suspend fun bulkProgress(job: String): SyncBulkProgress = handler.await {
        val outcomes = sync_inboxQueries.countBulkOutcomes(job).executeAsList().associate { it.outcome to it.count }
        SyncBulkProgress(outcomes.values.sum(), outcomes["QUEUED"] ?: 0, outcomes)
    }

    private suspend fun Database.projectField(state: Sync_field_state) {
        val key = Json.decodeFromString<SyncObjectKey>(state.object_json)
        val field = SyncField.valueOf(state.field_)
        val reduced = reduction(state.space_id, state.generation, key, field)
        val projection = reduced.projection(key, field)
        val heads = Json.encodeToString(projection.heads)
        sync_journalQueries.setHeads(state.space_id, state.generation, key.stableKey, field.name, heads)
        sync_inboxQueries.clearPendingField(state.space_id, state.generation, key.stableKey, field.name)
        if (projection.pending) {
            finish(state, "DEPENDENCY", state.applied_heads)
            return
        }
        if (projection.heads.isEmpty()) {
            finish(state, "EMPTY", state.applied_heads)
            return
        }
        when (field) {
            SyncField.FAVORITE, SyncField.FOLLOWING -> {
                val local = writer.localMembership(key)
                if (projection.value == true) {
                    if (local != true) {
                        writer.applyMembership(key, true, descriptionLookup(state.space_id, state.generation))
                    }
                } else if (projection.value == false && local == true) {
                    val request = SyncReceiver.pendingCancellation(reduced, key, field, true).singleOrNull()
                    if (request != null &&
                        sync_inboxQueries.getDecision(state.space_id, state.generation, request.binding)
                            .executeAsOneOrNull() == null
                    ) {
                        val title = descriptionLookup(state.space_id, state.generation)(key)?.title
                            ?: key.originalUrl ?: requireNotNull(key.portableKey)
                        sync_inboxQueries.upsertPendingDecision(
                            state.space_id,
                            state.generation,
                            key.stableKey,
                            field.name,
                            state.object_json,
                            request.binding,
                            title,
                            if (key.type == SyncObjectType.AUTHOR) {
                                resolveSyncAuthorIdentity(requireNotNull(key.portableKey))
                            } else {
                                null
                            },
                        )
                        finish(state, "DECISION", heads)
                        return
                    }
                }
            }
            SyncField.READ_STATUS -> if (state.applied_heads != heads) {
                projection.readStatus?.let {
                    writer.applyReadStatus(
                        key,
                        it == SyncReadStatus.READ,
                        descriptionLookup(state.space_id, state.generation),
                    )
                }
            }
            SyncField.RESUME_POSITION -> if (state.applied_heads != heads) {
                val choice = SyncReadingPolicy.chooseResume(projection, SyncReadingSession(key, "", 0, ""))
                choice.nextPosition?.let {
                    writer.applyResume(
                        key,
                        decodeChapterKey(it.chapterKey),
                        it.pageIndex,
                        descriptionLookup(state.space_id, state.generation),
                    )
                }
            }
            SyncField.READING_SUMMARY -> projectHistory(state, reduced, projection)
        }
        finish(state, "APPLIED", heads)
    }

    private suspend fun Database.projectHistory(
        state: Sync_field_state,
        reduced: SyncReduction,
        projection: SyncProjection,
    ) {
        val queue = ArrayDeque(projection.heads)
        val seen = mutableSetOf<String>()
        while (queue.isNotEmpty()) {
            val ref = queue.removeFirst()
            if (!seen.add(ref.stableKey)) continue
            val effect = reduced.events[ref.eventId]?.effects?.firstOrNull { it.effectId == ref.effectId } ?: continue
            queue.addAll(effect.parents)
            if (sync_inboxQueries.getProjectedHistory(state.space_id, state.generation, ref.stableKey)
                    .executeAsOneOrNull() != null
            ) {
                continue
            }
            val chapter = (effect.payload["chapterKey"] as JsonPrimitive).content
            val readAt = (effect.payload["readAt"] as JsonPrimitive).content.toLong()
            writer.applyHistory(
                projection.key.objectKey,
                decodeChapterKey(chapter),
                readAt,
                descriptionLookup(state.space_id, state.generation),
            )
            sync_inboxQueries.recordProjectedHistory(state.space_id, state.generation, ref.stableKey)
        }
    }

    /** Length prefixes permit colons and non-ASCII URLs; the writer validates exact parent/source identity. */
    private fun decodeChapterKey(encoded: String): SyncObjectKey {
        fun invalid(): Nothing = throw SyncProjectionUnavailable(SyncProjectionUnavailableReason.IDENTITY)
        var offset = 0
        val parts = List(5) {
            val colon = encoded.indexOf(':', offset)
            if (colon < offset) invalid()
            val size = encoded.substring(offset, colon).toIntOrNull() ?: invalid()
            if (size < 0 || size > encoded.length - colon - 1) invalid()
            offset = colon + 1
            encoded.substring(offset, offset + size).also { offset += size }
        }
        if (offset != encoded.length || parts[0] != SyncObjectType.CHAPTER.name) invalid()
        val key = SyncObjectKey(
            SyncObjectType.CHAPTER,
            parts[1].ifEmpty { null },
            parts[2].ifEmpty { null },
            parts[3].ifEmpty { null },
            parts[4].ifEmpty { null },
        )
        if (key.stableKey != encoded) invalid()
        return key
    }

    private suspend fun Database.decideInTransaction(
        spaceId: String,
        generation: Long,
        item: SyncPendingItem,
        requested: SyncCancellationDecision,
    ): SyncReceiverDecisionResult {
        if (!active(spaceId, generation)) return SyncReceiverDecisionResult.INVALIDATED
        val key = item.objectKey
        val field = when (key.type) {
            SyncObjectType.MANGA -> SyncField.FAVORITE
            SyncObjectType.AUTHOR -> SyncField.FOLLOWING
            else -> return SyncReceiverDecisionResult.INVALIDATED
        }
        val reduced = reduction(spaceId, generation, key, field)
        val currentRequest = SyncReceiver.pendingCancellation(reduced, key, field, true).singleOrNull()
        val stored = sync_inboxQueries.getDecision(spaceId, generation, item.binding).executeAsOneOrNull()
        if (stored == "INVALIDATED") return SyncReceiverDecisionResult.INVALIDATED
        val row = sync_inboxQueries.getPendingDecision(item.id).executeAsOneOrNull()
        val matches = row?.space_id == spaceId && row.generation == generation && row.binding == item.binding &&
            row.object_key == key.stableKey && row.field_ == field.name
        if (currentRequest?.binding != item.binding || (stored == null && !matches)) {
            if (matches) sync_inboxQueries.clearPendingField(spaceId, generation, key.stableKey, field.name)
            return SyncReceiverDecisionResult.INVALIDATED
        }
        val decision = stored?.let(SyncCancellationDecision::valueOf) ?: requested
        val result = SyncReceiver.decide(currentRequest, decision, reduced)
        if (stored == null) {
            if (result.result == SyncReceiverDecisionResult.APPLIED && writer.localMembership(key) == true) {
                writer.applyMembership(key, false, descriptionLookup(spaceId, generation))
            }
            sync_inboxQueries.insertDecision(spaceId, generation, item.binding, decision.name)
        }
        if (matches) sync_inboxQueries.clearPendingField(spaceId, generation, key.stableKey, field.name)
        return result.result
    }

    /** Load one field and complete envelope parent closure; all validity remains in SyncReducer. */
    private fun Database.reduction(
        spaceId: String,
        generation: Long,
        key: SyncObjectKey,
        field: SyncField,
    ): SyncReduction {
        val events = linkedMapOf<String, SyncEventEnvelope>()
        val visited = mutableSetOf<String>()
        var next = sync_inboxQueries.getFieldEvents(spaceId, generation, key.stableKey, field.name)
            .executeAsList().map { it.event_key }
        while (next.isNotEmpty()) {
            val following = mutableSetOf<String>()
            next.chunked(256).forEach { chunk ->
                visited += chunk
                val invalid = sync_inboxQueries.getInvalidEventKeys(spaceId, generation, chunk).executeAsList().toSet()
                sync_inboxQueries.getEventsByKey(spaceId, generation, chunk).executeAsList().forEach { stored ->
                    if (stored.event_key !in invalid) {
                        val event = (SyncCodec.decode(stored.event_json) as? SyncDecodeResult.Accepted)?.event
                        if (event != null) {
                            events[stored.event_key] = event
                            event.effects.flatMap { it.parents }.forEach { following += it.eventId.stableKey }
                        }
                    }
                }
            }
            next = following.filterNot { it in visited }
        }
        val reduced = SyncReducer.reduce(events.values, spaceId, generation)
        val rejected = reduced.rejections.mapNotNull { it.eventId?.stableKey }.distinct()
        rejected.forEach { sync_inboxQueries.invalidateEvent(spaceId, generation, it, "invalid causal event") }
        dirtySyncDependents(spaceId, generation, rejected)
        return reduced
    }

    private fun Database.descriptionLookup(
        spaceId: String,
        generation: Long,
    ): (SyncObjectKey) -> SyncObjectDescriptor? = { key ->
        sync_inboxQueries.getDescription(spaceId, generation, key.stableKey).executeAsOneOrNull()
            ?.let { Json.decodeFromString<SyncObjectDescriptor>(it) }
    }

    private fun Database.finish(state: Sync_field_state, status: String, heads: String?) =
        sync_inboxQueries.setFieldState(
            status,
            heads,
            state.space_id,
            state.generation,
            state.object_key,
            state.field_,
            state.revision,
        )

    private fun Database.active(spaceId: String, generation: Long): Boolean =
        sync_journalQueries.getSpace(spaceId, generation).executeAsOneOrNull()?.active == true
}
