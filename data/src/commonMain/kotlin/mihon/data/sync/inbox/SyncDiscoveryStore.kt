package mihon.data.sync.inbox

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import mihon.domain.sync.transport.SyncBatchIndexEntry
import mihon.domain.sync.transport.SyncSnapshot
import tachiyomi.data.DatabaseHandler
import tachiyomi.data.Sync_inboxQueries

/** Durable discovered-batch queue. Discovery is rebuildable; receipt remains authoritative. */
class SyncDiscoveryStore(private val handler: DatabaseHandler) {
    suspend fun observe(snapshot: SyncSnapshot, batchIds: Set<String> = snapshot.batches.map { it.batchId }.toSet()) =
        handler.await(inTransaction = true) {
            observeInTransaction(sync_inboxQueries, snapshot, batchIds)
        }

    internal fun observeInTransaction(queries: Sync_inboxQueries, snapshot: SyncSnapshot, batchIds: Set<String>) {
        snapshot.batches.asSequence().filter { it.batchId in batchIds }.forEach { entry ->
            queries.insertDiscoveredBatch(
                snapshot.spaceId,
                snapshot.generation,
                entry.batchId,
                encode(entry),
                snapshot.spaceId,
                snapshot.generation,
                entry.batchId,
            )
        }
    }

    suspend fun pending(
        spaceId: String,
        generation: Long,
        limit: Long = 128,
    ): List<SyncBatchIndexEntry> = handler.await {
        sync_inboxQueries.getDiscoveredBatches(spaceId, generation, limit.coerceIn(1, 128))
            .executeAsList()
            .map(::decode)
    }

    suspend fun complete(spaceId: String, generation: Long, batchId: String) = handler.await {
        sync_inboxQueries.deleteDiscoveredBatch(spaceId, generation, batchId)
    }

    private fun encode(entry: SyncBatchIndexEntry): String = buildJsonObject {
        put("batchId", entry.batchId)
        put("path", entry.path)
        put("digestHex", entry.digestHex)
        put("firstSeq", entry.firstSeq)
        put("lastSeq", entry.lastSeq)
        put("actorId", entry.actorId)
        put("epoch", entry.epoch)
        put("indexPath", entry.indexPath)
        put("indexCiphertextDigestHex", entry.indexCiphertextDigestHex)
    }.toString()

    private fun decode(value: String): SyncBatchIndexEntry {
        val json = Json.parseToJsonElement(value).jsonObject
        return SyncBatchIndexEntry(
            json.getValue("batchId").jsonPrimitive.content,
            json.getValue("path").jsonPrimitive.content,
            json.getValue("digestHex").jsonPrimitive.content,
            json.getValue("firstSeq").jsonPrimitive.long,
            json.getValue("lastSeq").jsonPrimitive.long,
            json.getValue("actorId").jsonPrimitive.content,
            json.getValue("epoch").jsonPrimitive.long,
            json.getValue("indexPath").jsonPrimitive.content,
            json.getValue("indexCiphertextDigestHex").jsonPrimitive.content,
        )
    }
}
