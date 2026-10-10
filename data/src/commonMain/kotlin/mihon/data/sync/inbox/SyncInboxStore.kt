package mihon.data.sync.inbox

import kotlinx.serialization.json.Json
import mihon.data.sync.journal.requireSyncExchange
import mihon.data.sync.transport.PreparedSyncSnapshotManifest
import mihon.data.sync.transport.SyncBatchSyncService
import mihon.data.sync.transport.SyncRemoteSnapshotFence
import mihon.data.sync.transport.SyncRemoteSnapshotGuard
import mihon.data.sync.transport.SyncSnapshotManifestStore
import mihon.data.sync.transport.WarmSyncSnapshotAdmission
import mihon.domain.sync.SyncBatch
import mihon.domain.sync.SyncBatchCodec
import mihon.domain.sync.SyncBatchDecodeResult
import mihon.domain.sync.SyncCodec
import mihon.domain.sync.SyncDecodeResult
import mihon.domain.sync.SyncEventEnvelope
import mihon.domain.sync.transport.SyncBatchIndexEntry
import mihon.domain.sync.transport.SyncSnapshot
import tachiyomi.data.DatabaseHandler
import java.security.MessageDigest

private data class DiscoveredBatchKey(val spaceId: String, val generation: Long, val batchId: String)

data class SyncReceptionResult(
    val accepted: Boolean,
    val duplicate: Boolean = false,
    val error: String? = null,
    val batch: SyncBatch? = null,
)
data class SyncInboxStatus(val receivedBatches: Long, val rejectedBatches: Long, val pendingDecisions: Long)

class SyncInboxStore(private val handler: DatabaseHandler) {
    internal val remoteGuard = SyncRemoteSnapshotGuard(handler)

    suspend fun canConfirmReceivedBatch(spaceId: String, generation: Long, batchId: String): Boolean =
        handler.await {
            sync_inboxQueries.canConfirmReceivedBatch(spaceId, generation, batchId).executeAsOneOrNull() != null
        }

    suspend fun observeSnapshot(snapshot: SyncSnapshot, discovery: SyncDiscoveryStore? = null) =
        observeSnapshot(snapshot, discovery, null)

    internal suspend fun observeSnapshot(
        snapshot: SyncSnapshot,
        discovery: SyncDiscoveryStore?,
        manifest: PreparedSyncSnapshotManifest?,
        excludedBatchIds: Set<String> = emptySet(),
        fence: SyncRemoteSnapshotFence? = null,
    ) = remoteGuard.observe(snapshot, fence) { newlyDiscovered, headFingerprint ->
        discovery?.observeInTransaction(
            sync_inboxQueries,
            snapshot,
            newlyDiscovered - excludedBatchIds,
        )
        if (manifest != null && manifest.spaceId == snapshot.spaceId && manifest.generation == snapshot.generation &&
            manifest.head == snapshot.head && manifest.context.repository == snapshot.repository
        ) {
            SyncSnapshotManifestStore(handler).persist(this, manifest, headFingerprint)
        }
    }

    internal suspend fun confirmWarmSnapshot(
        snapshot: SyncSnapshot,
        admission: WarmSyncSnapshotAdmission,
        fence: SyncRemoteSnapshotFence? = null,
    ): Boolean = remoteGuard.confirmWarmSnapshot(snapshot, admission, fence)

    suspend fun ingest(batch: SyncBatch): SyncReceptionResult = ingest(batch, null)

    internal suspend fun ingest(
        batch: SyncBatch,
        snapshot: SyncSnapshot,
        entry: SyncBatchIndexEntry,
    ): SyncReceptionResult = ingest(batch, DiscoveredBatchKey(snapshot.spaceId, snapshot.generation, entry.batchId))

    private suspend fun ingest(batch: SyncBatch, discoveredBatch: DiscoveredBatchKey?): SyncReceptionResult {
        val encoded = SyncBatchCodec.rawEncode(batch)
        val scope = discoveredBatch ?: handler.await {
            sync_journalQueries.getActiveSpace().executeAsOneOrNull()?.let {
                DiscoveredBatchKey(it.space_id, it.generation, batch.batchId)
            }
        } ?: DiscoveredBatchKey(batch.spaceId, batch.generation, batch.batchId)
        val error = when {
            batch.spaceId != scope.spaceId -> "WRONG_SPACE"
            batch.generation != scope.generation -> "WRONG_GENERATION"
            else -> batchValidationError(batch, encoded)
        }
        if (error != null) {
            recordRejected(scope.spaceId, scope.generation, scope.batchId, "", error, encoded)
            if (discoveredBatch != null) deleteDiscoveredBatch(discoveredBatch)
            return SyncReceptionResult(false, error = error)
        }
        val active = handler.await {
            sync_journalQueries.getSpace(batch.spaceId, batch.generation).executeAsOneOrNull()?.let {
                it.active && it.exchange_enabled
            } == true
        }
        if (!active) {
            if (discoveredBatch != null) deleteDiscoveredBatch(discoveredBatch)
            return SyncReceptionResult(false, error = "sync space is not active")
        }
        val queueKey = discoveredBatch ?: DiscoveredBatchKey(batch.spaceId, batch.generation, batch.batchId)
        indexExisting(batch.spaceId, batch.generation)
        return handler.await(inTransaction = true) {
            requireSyncExchange(batch.spaceId, batch.generation)
            val previous = sync_inboxQueries.getReceivedBatch(batch.spaceId, batch.generation, batch.batchId)
                .executeAsOneOrNull()
            if (previous == encoded) {
                sync_repairQueries.resolveBatchReadFailure(batch.spaceId, batch.generation, batch.batchId)
                sync_inboxQueries.deleteDiscoveredBatch(queueKey.spaceId, queueKey.generation, queueKey.batchId)
                return@await SyncReceptionResult(true, duplicate = true, batch = batch)
            }
            val conflicts = batch.events.filter { event ->
                val existing = sync_inboxQueries.getEvent(batch.spaceId, batch.generation, event.eventId.stableKey)
                    .executeAsOneOrNull()?.let { (SyncCodec.decode(it) as? SyncDecodeResult.Accepted)?.event }
                existing != null && SyncCodec.canonical(existing) != SyncCodec.canonical(event)
            }
            if (previous != null || conflicts.isNotEmpty()) {
                conflicts.forEach {
                    sync_inboxQueries.invalidateEvent(
                        batch.spaceId,
                        batch.generation,
                        it.eventId.stableKey,
                        "conflicting immutable event",
                    )
                }
                dirtySyncDependents(batch.spaceId, batch.generation, conflicts.map { it.eventId.stableKey })
                sync_inboxQueries.saveInboxBatch(
                    batch.spaceId,
                    batch.generation,
                    batch.batchId,
                    "REJECTED",
                    encoded,
                    "conflicting immutable batch or event",
                )
                recordSyncRepairFailure(
                    batch.spaceId,
                    batch.generation,
                    "BATCH",
                    batch.batchId,
                    "",
                    "conflicting immutable batch or event",
                    encoded,
                )
                sync_inboxQueries.deleteDiscoveredBatch(queueKey.spaceId, queueKey.generation, queueKey.batchId)
                return@await SyncReceptionResult(false, error = "conflicting immutable batch or event")
            }
            val inserted = mutableListOf<String>()
            batch.events.forEach { event ->
                if (sync_inboxQueries.getEvent(batch.spaceId, batch.generation, event.eventId.stableKey)
                        .executeAsOneOrNull() == null
                ) {
                    sync_journalQueries.insertEvent(
                        event.spaceId, event.generation, event.actorId, event.epoch,
                        event.seq, event.category.name, event.origin.name, batch.batchId,
                        SyncCodec.encode(event), event.occurredAt,
                    )
                    indexSyncEvent(event)
                    inserted += event.eventId.stableKey
                }
            }
            batch.objects.forEach { descriptor ->
                sync_inboxQueries.insertDescription(
                    batch.spaceId,
                    batch.generation,
                    descriptor.objectKey.stableKey,
                    Json.encodeToString(descriptor),
                )
            }
            dirtySyncDependents(batch.spaceId, batch.generation, inserted)
            sync_inboxQueries.saveInboxBatch(batch.spaceId, batch.generation, batch.batchId, "RECEIVED", encoded, null)
            sync_repairQueries.resolveBatchReadFailure(batch.spaceId, batch.generation, batch.batchId)
            sync_inboxQueries.deleteDiscoveredBatch(queueKey.spaceId, queueKey.generation, queueKey.batchId)
            SyncReceptionResult(true, batch = batch)
        }
    }

    internal suspend fun recordRejected(
        spaceId: String,
        generation: Long,
        batchId: String,
        path: String,
        reason: String,
        original: String,
    ) = handler.await(inTransaction = true) {
        recordSyncRepairFailure(spaceId, generation, "BATCH", batchId, path, reason, original)
        sync_inboxQueries.saveInboxBatch(spaceId, generation, batchId, "REJECTED", original, reason)
    }

    private suspend fun deleteDiscoveredBatch(batch: DiscoveredBatchKey) = handler.await(inTransaction = true) {
        sync_inboxQueries.deleteDiscoveredBatch(batch.spaceId, batch.generation, batch.batchId)
    }

    suspend fun completeDiscovery(spaceId: String, generation: Long, batchId: String) = handler.await {
        sync_inboxQueries.deleteDiscoveredBatch(spaceId, generation, batchId)
    }

    suspend fun status(spaceId: String, generation: Long): SyncInboxStatus = handler.await {
        val counts = sync_inboxQueries.countInboxBatches(spaceId, generation).executeAsList().associate {
            it.status to
                it.count
        }
        SyncInboxStatus(
            counts["RECEIVED"] ?: 0,
            counts["REJECTED"] ?: 0,
            sync_inboxQueries.countPendingDecisions(spaceId, generation).executeAsOne(),
        )
    }

    /** Old local envelopes are indexed in bounded transactions without changing their identities. */
    private suspend fun indexExisting(spaceId: String, generation: Long) {
        while (true) {
            val count = handler.await(inTransaction = true) {
                val entries = sync_inboxQueries.getUnindexedEvents(spaceId, generation, 256).executeAsList()
                entries.forEach { entry ->
                    val event = (SyncCodec.decode(entry.event_json) as? SyncDecodeResult.Accepted)?.event
                    if (event == null) {
                        sync_inboxQueries.invalidateEvent(spaceId, generation, entry.event_key, "invalid stored event")
                        sync_inboxQueries.markEventIndexed(spaceId, generation, entry.event_key)
                    } else {
                        indexSyncEvent(event)
                    }
                }
                entries.size
            }
            if (count < 256) return
        }
    }

    private fun batchValidationError(batch: SyncBatch, encoded: String): String? {
        val decoded = SyncBatchCodec.decode(encoded)
        if (decoded is SyncBatchDecodeResult.Rejected) return "${decoded.reason}: ${decoded.message}"
        val first = batch.events.firstOrNull() ?: return "BATCH_TOO_LARGE: batch is empty"
        if (!batch.batchId.matches(Regex("[A-Za-z0-9_-]{1,128}")) ||
            !first.actorId.matches(Regex("[A-Za-z0-9_-]{1,128}"))
        ) {
            return "INVALID_SEQUENCE: invalid batch or actor identity"
        }
        if (batch.events.any {
                it.batchId != batch.batchId || it.actorId != first.actorId || it.epoch != first.epoch
            }
        ) {
            return "INVALID_SEQUENCE: batch actor or epoch differs"
        }
        return if (batch.events.sortedBy(SyncEventEnvelope::seq).zipWithNext().all { (a, b) -> b.seq == a.seq + 1 }) {
            null
        } else {
            "INVALID_SEQUENCE: batch sequence is not contiguous"
        }
    }
}

class SyncInboxExchange(private val store: SyncInboxStore, private val service: SyncBatchSyncService) {
    suspend fun receive(snapshot: SyncSnapshot, entry: SyncBatchIndexEntry): SyncReceptionResult =
        receive(snapshot, entry, snapshotAlreadyObserved = false)

    internal suspend fun receive(
        snapshot: SyncSnapshot,
        entry: SyncBatchIndexEntry,
        snapshotAlreadyObserved: Boolean,
    ): SyncReceptionResult {
        if (!snapshotAlreadyObserved) store.observeSnapshot(snapshot)
        val received = service.receive(snapshot, entry)
        val batch = received.batch ?: run {
            // A structurally invalid batch is terminal for this discovered entry; network
            // failures throw before this branch and leave it durable for a later retry.
            store.recordRejected(
                snapshot.spaceId,
                snapshot.generation,
                entry.batchId,
                entry.path,
                received.error ?: "sync batch rejected",
                received.evidence ?: entry.digestHex,
            )
            store.completeDiscovery(snapshot.spaceId, snapshot.generation, entry.batchId)
            return SyncReceptionResult(false, error = received.error)
        }
        // Receipt, rejection, and discovery completion commit together in ingest. Repeating the
        // delete here added a transaction for every normal batch without strengthening recovery.
        return store.ingest(batch, snapshot, entry)
    }
}

internal fun tachiyomi.data.Database.recordSyncRepairFailure(
    spaceId: String,
    generation: Long,
    kind: String,
    target: String,
    path: String,
    reason: String,
    original: String,
) {
    val digest = MessageDigest.getInstance("SHA-256").digest((reason + "\n" + original).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    sync_repairQueries.recordFailure(spaceId, generation, kind, target, digest, path, reason, original)
}
