package mihon.data.sync.journal

import mihon.data.sync.crypto.SyncAeadEngineFactory
import mihon.data.sync.transport.SyncBatchSyncService
import mihon.data.sync.transport.SyncRemoteSnapshotGuard
import mihon.data.sync.transport.SyncUploadArtifactCodec
import mihon.domain.sync.SyncBatch
import mihon.domain.sync.SyncBatchCodec
import mihon.domain.sync.SyncBatchDecodeResult
import mihon.domain.sync.SyncCodec
import mihon.domain.sync.SyncDecodeResult
import mihon.domain.sync.SyncProtocol
import mihon.domain.sync.transport.SyncPreparedUpload
import mihon.domain.sync.transport.SyncPublishResult
import mihon.domain.sync.transport.SyncPublishStatus
import mihon.domain.sync.transport.SyncSnapshot
import mihon.domain.sync.transport.SyncUploadResult
import tachiyomi.data.Database
import tachiyomi.data.DatabaseHandler
import tachiyomi.data.Sync_batches

data class SyncFrozenUploadRound(val totalItems: Long, val batchIds: List<String>)

class SyncOutboxStore(private val handler: DatabaseHandler) {
    private val remoteGuard = SyncRemoteSnapshotGuard(handler)

    suspend fun observeSnapshot(snapshot: SyncSnapshot) = remoteGuard.observe(snapshot)

    /** Seals every currently open batch and records its identity in the same database transaction. */
    suspend fun freezeRound(spaceId: String, generation: Long): SyncFrozenUploadRound =
        handler.await(inTransaction = true) {
            requireActive(spaceId, generation)
            sync_journalQueries.sealSpaceBatches(spaceId, generation)
            val batches = sync_journalQueries.getPendingUploadRoundBatches(spaceId, generation).executeAsList()
            SyncFrozenUploadRound(batches.sumOf { it.event_count }, batches.map { it.batch_id })
        }

    suspend fun nextBatch(spaceId: String, generation: Long, frozenBatchId: String? = null): SyncBatch? =
        handler.await(inTransaction = true) {
            requireActive(spaceId, generation)
            val stored = if (frozenBatchId == null) {
                sync_journalQueries.getNextUploadBatch(spaceId, generation).executeAsOneOrNull()
            } else {
                sync_journalQueries.getBatch(spaceId, generation, frozenBatchId).executeAsOneOrNull()
                    ?.takeIf { it.status == "SEALED" && it.event_count > 0 }
            }
                ?: return@await null
            val batch = readBatch(stored)
            sync_journalQueries.sealBatch(spaceId, generation, stored.batch_id)
            batch
        }

    suspend fun prepared(batch: SyncBatch): SyncPreparedUpload? = handler.await {
        requireActive(batch.spaceId, batch.generation)
        val stored = requireBatch(batch)
        stored.prepared_upload?.let { SyncUploadArtifactCodec.decode(it).also { validateUpload(stored, batch, it) } }
    }

    suspend fun savePrepared(batch: SyncBatch, upload: SyncPreparedUpload): SyncPreparedUpload =
        handler.await(inTransaction = true) {
            requireActive(batch.spaceId, batch.generation)
            val stored = requireBatch(batch)
            require(stored.status == "SEALED") { "upload batch must be sealed" }
            validateUpload(stored, batch, upload)
            val existing = stored.prepared_upload
            if (existing != null) {
                return@await SyncUploadArtifactCodec.decode(existing).also { validateUpload(stored, batch, it) }
            }
            sync_journalQueries.savePreparedUpload(
                SyncUploadArtifactCodec.encode(upload),
                batch.spaceId,
                batch.generation,
                batch.batchId,
            )
            upload
        }

    suspend fun acknowledge(upload: SyncPreparedUpload, result: SyncPublishResult) = handler.await(
        inTransaction = true,
    ) {
        val encrypted = upload.encryptedBatch
        require(
            result.status == SyncPublishStatus.PUBLISHED && result.batchId == encrypted.batchId &&
                result.commitSha?.matches(Regex("[0-9a-f]{40}|[0-9a-f]{64}")) == true,
        ) { "upload is not confirmed" }
        val stored = sync_journalQueries.getBatch(encrypted.spaceId, encrypted.generation, encrypted.batchId)
            .executeAsOne()
        val saved = SyncUploadArtifactCodec.decode(requireNotNull(stored.prepared_upload))
        require(upload == saved) { "acknowledgement differs from the durable upload" }
        validateUpload(stored, readBatch(stored), upload)
        sync_journalQueries.markOutboxPublished(encrypted.spaceId, encrypted.generation, encrypted.batchId)
        sync_journalQueries.markBatchPublished(encrypted.spaceId, encrypted.generation, encrypted.batchId)
    }

    private fun Database.requireActive(spaceId: String, generation: Long) {
        requireSyncExchange(spaceId, generation)
    }

    private fun Database.requireBatch(batch: SyncBatch): Sync_batches {
        val stored = sync_journalQueries.getBatch(batch.spaceId, batch.generation, batch.batchId).executeAsOne()
        require(readBatch(stored) == batch) { "upload differs from the frozen batch" }
        return stored
    }

    private fun Database.readBatch(stored: Sync_batches): SyncBatch {
        val events = sync_journalQueries.getBatchEvents(stored.space_id, stored.generation, stored.batch_id)
            .executeAsList().map {
                (SyncCodec.decode(it) as? SyncDecodeResult.Accepted)?.event
                    ?: error("stored sync event is invalid")
            }
        val batch = SyncBatch(
            SyncProtocol.CURRENT_VERSION,
            stored.space_id,
            stored.generation,
            stored.batch_id,
            events,
            SyncObjectDescriptions.decode(stored.objects_json),
        )
        require(SyncBatchCodec.decode(SyncBatchCodec.rawEncode(batch)) is SyncBatchDecodeResult.Accepted)
        require(
            events.size.toLong() == stored.event_count && events.first().seq == stored.first_seq &&
                events.last().seq == stored.last_seq &&
                events.all { it.actorId == stored.actor_id && it.epoch == stored.epoch } &&
                events.zipWithNext().all { (a, b) -> b.seq == a.seq + 1 },
        ) { "stored sync sequence is invalid" }
        return batch
    }

    private fun Database.validateUpload(stored: Sync_batches, batch: SyncBatch, upload: SyncPreparedUpload) {
        val space = sync_journalQueries.getSpace(batch.spaceId, batch.generation).executeAsOne()
        val encrypted = upload.encryptedBatch
        require(
            upload.repository.owner == space.repository_owner && upload.repository.name == space.repository_name &&
                upload.repository.branch == space.repository_branch,
        ) { "upload repository differs from its space" }
        require(
            encrypted.protocolVersion == batch.protocolVersion && encrypted.spaceId == batch.spaceId &&
                encrypted.generation == batch.generation && encrypted.batchId == batch.batchId &&
                encrypted.actorId == stored.actor_id && encrypted.epoch == stored.epoch &&
                encrypted.firstSeq == stored.first_seq && encrypted.lastSeq == stored.last_seq &&
                encrypted.path == ".mihon-sync/batches/${stored.actor_id}/${stored.epoch}/${stored.batch_id}.json",
        ) {
            "upload scope differs from its batch"
        }
        require(
            SyncAeadEngineFactory.create().sha256(SyncBatchCodec.rawEncode(batch).encodeToByteArray())
                .contentEquals(encrypted.plaintextDigest),
        ) { "upload content differs from its batch" }
    }
}

/** One bounded upload; application scheduling and receiving are separate responsibilities. */
class SyncOutboxExchange(private val store: SyncOutboxStore, private val service: SyncBatchSyncService) {
    suspend fun uploadNext(
        snapshot: SyncSnapshot,
        observeSnapshot: suspend (SyncSnapshot, String?) -> Unit = { current, _ -> store.observeSnapshot(current) },
        onPreparingBatch: (SyncBatch) -> Unit = {},
        onPreparedBatch: (SyncBatch) -> Unit = {},
        onConfirmedBatch: (SyncBatch) -> Unit = {},
        frozenBatchId: String? = null,
    ): SyncUploadResult? {
        observeSnapshot(snapshot, null)
        val batch = store.nextBatch(snapshot.spaceId, snapshot.generation, frozenBatchId) ?: return null
        runCatching { onPreparingBatch(batch) }
        val upload = store.prepared(batch) ?: store.savePrepared(
            batch,
            service.prepare(
                snapshot,
                batch,
                ".mihon-sync/batches/${batch.events.first().actorId}/" +
                    "${batch.events.first().epoch}/${batch.batchId}.json",
            ),
        )
        runCatching { onPreparedBatch(batch) }
        val result = service.uploadPrepared(snapshot.repository, snapshot, upload) { confirmed ->
            // The snapshot can contain this upload before the outbox transaction marks it PUBLISHED.
            // Exclude only its known batch ID from discovery during that confirmation window.
            observeSnapshot(confirmed, batch.batchId)
        }
        if (result.publish.status == SyncPublishStatus.PUBLISHED) {
            store.acknowledge(upload, result.publish)
            runCatching { onConfirmedBatch(batch) }
        }
        return result.copy(batch = batch)
    }
}
