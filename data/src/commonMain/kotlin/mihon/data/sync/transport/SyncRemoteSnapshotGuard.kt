package mihon.data.sync.transport

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mihon.data.sync.crypto.SyncAeadEngineFactory
import mihon.data.sync.journal.requireSyncExchange
import mihon.domain.sync.transport.SyncBatchIndexEntry
import mihon.domain.sync.transport.SyncGitTreeEntry
import mihon.domain.sync.transport.SyncSnapshot
import okio.ByteString.Companion.toByteString
import tachiyomi.data.DatabaseHandler

class SyncRemoteSnapshotRejected : IllegalStateException("remote sync history requires explicit recovery")

/** Anchors only transport-authenticated snapshots; the first observation cannot prove earlier history existed. */
class SyncRemoteSnapshotGuard(private val handler: DatabaseHandler) {
    private val engine = SyncAeadEngineFactory.create()

    suspend fun observe(snapshot: SyncSnapshot) {
        val evidence = evidence(snapshot)
        val blocked = handler.await(inTransaction = true) {
            requireSyncExchange(snapshot.spaceId, snapshot.generation)
            val space = sync_journalQueries.getSpace(snapshot.spaceId, snapshot.generation).executeAsOne()
            require(
                space.repository_owner == snapshot.repository.owner &&
                    space.repository_name == snapshot.repository.name &&
                    space.repository_branch == snapshot.repository.branch,
            ) {
                "snapshot repository differs from its space"
            }
            val queries = sync_remote_guardQueries
            val guard = queries.getGuard(snapshot.spaceId, snapshot.generation).executeAsOneOrNull()
            if (guard != null) {
                require(
                    guard.repository_owner == snapshot.repository.owner &&
                        guard.repository_name == snapshot.repository.name &&
                        guard.repository_branch == snapshot.repository.branch,
                ) {
                    "remote history binding differs from its space"
                }
                if (guard.blocked) return@await true
            } else {
                queries.insertGuard(
                    snapshot.spaceId,
                    snapshot.generation,
                    snapshot.repository.owner,
                    snapshot.repository.name,
                    snapshot.repository.branch,
                )
            }
            val knownHead = queries.getHead(
                snapshot.spaceId,
                snapshot.generation,
                snapshot.head,
            ).executeAsOneOrNull()
            if (!evidence.complete || (knownHead != null && knownHead != evidence.fingerprint)) {
                queries.blockGuard(snapshot.spaceId, snapshot.generation)
                return@await true
            }
            // Rehash the supplied object even on a cache hit: SyncSnapshot contains caller-owned Lists.
            if (guard?.latest_head == snapshot.head && knownHead == evidence.fingerprint) return@await false
            val observed = queries.getObjects(snapshot.spaceId, snapshot.generation).executeAsList()
            if (observed.any { evidence.objects[it.object_id] != it.fingerprint }) {
                queries.blockGuard(snapshot.spaceId, snapshot.generation)
                return@await true
            }
            val observedIds = observed.mapTo(mutableSetOf()) { it.object_id }
            evidence.objects.forEach { (identity, fingerprint) ->
                if (identity !in observedIds) {
                    queries.insertObject(snapshot.spaceId, snapshot.generation, identity, fingerprint)
                }
            }
            queries.insertHead(snapshot.spaceId, snapshot.generation, snapshot.head, evidence.fingerprint)
            queries.advanceHead(snapshot.head, snapshot.spaceId, snapshot.generation)
            false
        }
        // A rejection must not roll back the durable block together with its transaction.
        if (blocked) throw SyncRemoteSnapshotRejected()
    }

    private fun evidence(snapshot: SyncSnapshot): Evidence {
        val entries = snapshot.tree.entries.toList().sortedBy { it.path }
        val batches = snapshot.batches.toList().sortedWith(compareBy({ it.batchId }, { it.path }))
        val paths = entries.associateBy { it.path }
        val objects = linkedMapOf<String, String>()
        var complete = !snapshot.tree.truncated && paths.size == entries.size
        batches.forEach { batch ->
            val blob = paths[batch.path]
            val index = paths[batch.indexPath]
            if (blob?.type != "blob" || index?.type != "blob" ||
                batch.indexCiphertextDigestHex.isEmpty()
            ) {
                complete = false
            }
            val batchMaterial = buildJsonObject {
                put("metadata", metadata(batch))
                put("blob", treeEntry(blob))
            }
            if (objects.put("batch:${batch.batchId}", fingerprint(batchMaterial)) != null) complete = false
        }
        // The transport authenticates every shard, including the immutable bootstrap without a batch.
        entries.filter { it.type == "blob" && it.path.startsWith(".mihon-sync/index/") }.forEach {
            objects["index:${it.path}"] = fingerprint(treeEntry(it))
        }
        val material = buildJsonObject {
            put("space", snapshot.spaceId)
            put("generation", snapshot.generation)
            put("owner", snapshot.repository.owner)
            put("repository", snapshot.repository.name)
            put("branch", snapshot.repository.branch)
            put("head", snapshot.head)
            put("tree", snapshot.tree.sha)
            put("truncated", snapshot.tree.truncated)
            put("entries", JsonArray(entries.map(::treeEntry)))
            put("batches", JsonArray(batches.map(::metadata)))
        }
        return Evidence(fingerprint(material), objects, complete)
    }

    private fun metadata(batch: SyncBatchIndexEntry) = buildJsonObject {
        put("batch", batch.batchId)
        put("path", batch.path)
        put("digest", batch.digestHex)
        put("first", batch.firstSeq)
        put("last", batch.lastSeq)
        put("actor", batch.actorId)
        put("epoch", batch.epoch)
        put("index", batch.indexPath)
        put("indexDigest", batch.indexCiphertextDigestHex)
    }

    private fun treeEntry(entry: SyncGitTreeEntry?): JsonElement = entry?.let {
        buildJsonObject {
            put("path", it.path)
            put("mode", it.mode)
            put("type", it.type)
            put("sha", it.sha)
            put("size", it.size?.let(::JsonPrimitive) ?: JsonNull)
        }
    } ?: JsonNull

    private fun fingerprint(value: JsonElement): String = engine.sha256(
        value.toString().encodeToByteArray(),
    ).toByteString().hex()

    private data class Evidence(val fingerprint: String, val objects: Map<String, String>, val complete: Boolean)
}
