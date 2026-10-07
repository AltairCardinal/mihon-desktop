package mihon.data.sync.inbox

import kotlinx.serialization.json.Json
import mihon.data.sync.journal.requireSyncExchange
import mihon.domain.sync.SyncField
import mihon.domain.sync.SyncObjectDescriptor
import mihon.domain.sync.SyncObjectKey
import mihon.domain.sync.transport.SyncSnapshot
import tachiyomi.data.DatabaseHandler

private data class ProjectionRepairTarget(
    val objectKey: String,
    val field: String,
    val revision: Long,
    val objectJson: String,
)

data class SyncRejectedBatch(val batchId: String, val path: String, val reason: String, val evidenceId: String)
data class SyncRejectedEvent(val eventId: String, val reason: String, val batchId: String?)
data class SyncProjectionFailure(
    val objectKey: SyncObjectKey?,
    val field: SyncField?,
    val title: String,
    val reason: String,
    val objectIdentity: String,
)
data class SyncFailedBulkItem(
    val jobId: String,
    val pendingId: Long,
    val objectKey: SyncObjectKey?,
    val title: String,
    val reason: String = "decision could not be applied",
)
data class SyncRepairCounts(
    val rejectedBatches: Long,
    val projectionFields: Long,
    val missingDependencies: Long,
    val failedBulkItems: Long,
    val rejectedEvents: Long = 0,
) {
    val total: Long get() = rejectedBatches + projectionFields + missingDependencies + failedBulkItems + rejectedEvents
}

/** Facts survive process restarts and space changes. Original quarantine data stays private in storage. */
data class SyncRepairReport(
    val spaceId: String,
    val generation: Long,
    val batches: List<SyncRejectedBatch>,
    val fields: List<SyncProjectionFailure>,
    val missingDependencies: List<String>,
    val failedBulkItems: List<SyncFailedBulkItem>,
    val truncated: Boolean = false,
    val counts: SyncRepairCounts = SyncRepairCounts(
        batches.map { it.batchId }.distinct().size.toLong(),
        fields.size.toLong(),
        missingDependencies.size.toLong(),
        failedBulkItems.map { it.pendingId }.distinct().size.toLong(),
    ),
    val offset: Long = 0,
    val nextOffset: Long? = null,
    val events: List<SyncRejectedEvent> = emptyList(),
) {
    val remaining: Long get() = counts.total
}

/** Repairs reuse receipt, causal reduction and immutable snapshot admission; no alternate exchange engine. */
class SyncDataRepairService(
    private val handler: DatabaseHandler,
    private val inbox: SyncInboxStore,
    private val projector: SyncInboxProjector,
) {
    suspend fun inspect(
        spaceId: String,
        generation: Long,
        offset: Long = 0,
        limit: Int = 100,
    ): SyncRepairReport =
        handler.await(inTransaction = true) {
            require(offset >= 0 && limit in 1..100)
            val failures = sync_repairQueries.getFailures(
                spaceId,
                generation,
                limit.toLong() + 1,
                offset,
            ).executeAsList()
            val fields = sync_repairQueries.getProjectionFailures(
                spaceId,
                generation,
                limit.toLong() + 1,
                offset,
            )
                .executeAsList()
            val dependencies = sync_repairQueries.getMissingDependencies(
                spaceId,
                generation,
                limit.toLong() + 1,
                offset,
            )
                .executeAsList()
            val bulk = sync_repairQueries.getFailedBulkItems(
                spaceId,
                generation,
                limit.toLong() + 1,
                offset,
            )
                .executeAsList()
            val events = sync_repairQueries.getRejectedEvents(
                spaceId,
                generation,
                limit.toLong() + 1,
                offset,
            )
                .executeAsList()
            val count = sync_repairQueries.getRepairCounts(spaceId, generation).executeAsOne()
            val more = listOf(failures.size, fields.size, dependencies.size, bulk.size, events.size).any { it > limit }
            SyncRepairReport(
                spaceId,
                generation,
                failures.take(limit).filter { it.kind == "BATCH" }.map {
                    SyncRejectedBatch(it.target, it.path, it.reason, it.evidence_id)
                },
                fields.take(limit).map {
                    val key = runCatching { Json.decodeFromString<SyncObjectKey>(it.object_json) }.getOrNull()
                    val field = runCatching { SyncField.valueOf(it.field_) }.getOrNull()
                    val title = runCatching {
                        Json.decodeFromString<SyncObjectDescriptor>(it.description).title
                    }.getOrNull()
                        ?: key?.originalUrl ?: key?.portableKey ?: it.object_key
                    SyncProjectionFailure(
                        key,
                        field,
                        title,
                        if (key == null || field == null) "UNREADABLE" else it.status,
                        it.object_key,
                    )
                },
                dependencies.take(limit),
                bulk.take(limit).map {
                    val key = runCatching { Json.decodeFromString<SyncObjectKey>(it.object_json) }.getOrNull()
                    SyncFailedBulkItem(
                        it.job_id,
                        it.pending_id,
                        key,
                        it.title.ifEmpty {
                            key?.originalUrl
                                ?: it.object_key
                        },
                    )
                },
                more,
                SyncRepairCounts(count.batches, count.fields, count.dependencies, count.bulk, count.events),
                offset,
                if (more) offset + limit else null,
                events.take(limit).map { SyncRejectedEvent(it.event_key, it.reason, it.batch_id) },
            )
        }

    suspend fun reproject(
        spaceId: String,
        generation: Long,
        objectKey: SyncObjectKey? = null,
        field: SyncField? = null,
        offset: Long = 0,
    ): SyncRepairReport {
        handler.await(inTransaction = true) {
            requireSyncExchange(spaceId, generation)
            val targets = if (objectKey != null) {
                sync_repairQueries.getObjectProjectionFailures(spaceId, generation, objectKey.stableKey)
                    .executeAsList().map {
                        ProjectionRepairTarget(it.object_key, it.field_, it.revision, it.object_json)
                    }
            } else {
                sync_repairQueries.getProjectionFailures(spaceId, generation, 100, offset).executeAsList().map {
                    ProjectionRepairTarget(it.object_key, it.field_, it.revision, it.object_json)
                }
            }
            targets.filter { field == null || it.field == field.name }.forEach {
                // Unreadable original identity is preserved, never replaced by a guessed key.
                if (runCatching { Json.decodeFromString<SyncObjectKey>(it.objectJson) }.isSuccess &&
                    runCatching { SyncField.valueOf(it.field) }.isSuccess
                ) {
                    sync_repairQueries.retryProjection(spaceId, generation, it.objectKey, it.field, it.revision)
                }
            }
        }
        // A bounded user operation. Remaining dirty/unavailable objects are retained for the next run.
        repeat(20) { if (projector.project(spaceId, generation) == 0) return inspect(spaceId, generation) }
        return inspect(spaceId, generation)
    }

    suspend fun refetch(
        snapshot: SyncSnapshot,
        exchange: SyncInboxExchange,
        batchIds: Set<String>,
        offset: Long = 0,
    ): SyncRepairReport {
        require(batchIds.size <= 128 && offset >= 0)
        inbox.observeSnapshot(snapshot)
        val knownFailures = if (batchIds.isEmpty()) {
            emptySet()
        } else {
            handler.await {
                sync_repairQueries.getRejectedTargets(snapshot.spaceId, snapshot.generation, batchIds)
                    .executeAsList().toSet()
            }
        }
        val missing = inspect(snapshot.spaceId, snapshot.generation, offset).missingDependencies.mapNotNull {
            val parts = it.split(':')
            if (parts.size != 3) return@mapNotNull null
            val epoch = parts[1].toLongOrNull() ?: return@mapNotNull null
            val sequence = parts[2].toLongOrNull() ?: return@mapNotNull null
            Triple(parts[0], epoch, sequence)
        }
        snapshot.batches.filter { entry ->
            entry.batchId in knownFailures || missing.any { (actor, epoch, sequence) ->
                entry.actorId == actor && entry.epoch == epoch && sequence in entry.firstSeq..entry.lastSeq
            }
        }.take(128).forEach {
            exchange.receive(snapshot, it, snapshotAlreadyObserved = true)
        }
        return inspect(snapshot.spaceId, snapshot.generation)
    }
}
