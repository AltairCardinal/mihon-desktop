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
import tachiyomi.data.Database
import tachiyomi.data.DatabaseHandler
import tachiyomi.data.Sync_remote_guards

class SyncRemoteSnapshotRejected : IllegalStateException("remote sync history requires explicit recovery")
class SyncRemoteSnapshotStaleCandidate : IllegalStateException("sync snapshot candidate was superseded")

data class SyncSnapshotWriteOwner(val runId: String, val ownerSession: String, val attemptId: Long)

data class SyncRemoteSnapshotFence(
    val spaceId: String,
    val generation: Long,
    val revision: Long,
    val owner: SyncSnapshotWriteOwner?,
)

/** Anchors only transport-authenticated snapshots; the first observation cannot prove earlier history existed. */
class SyncRemoteSnapshotGuard(private val handler: DatabaseHandler) {
    private val engine = SyncAeadEngineFactory.create()
    private val manifestStore = SyncSnapshotManifestStore(handler)
    internal var evidenceEvaluations: Long = 0
        private set

    /** Captures durable state before network I/O; the returned revision is a CAS token. */
    suspend fun captureFence(
        spaceId: String,
        generation: Long,
        owner: SyncSnapshotWriteOwner? = null,
    ): SyncRemoteSnapshotFence = handler.await(inTransaction = true) {
        requireSyncExchange(spaceId, generation)
        owner?.let { requireCurrentOwner(it, spaceId, generation) }
        val revision = sync_remote_guardQueries.getGuard(spaceId, generation).executeAsOneOrNull()?.revision ?: 0L
        SyncRemoteSnapshotFence(spaceId, generation, revision, owner)
    }

    /** Recheck a transport-issued warm admission without walking historical tree evidence. */
    internal suspend fun confirmWarmSnapshot(
        snapshot: SyncSnapshot,
        admission: WarmSyncSnapshotAdmission,
        fence: SyncRemoteSnapshotFence? = null,
    ): Boolean =
        handler.await(inTransaction = true) {
            if (snapshot !== admission.snapshot) return@await false
            val space = sync_journalQueries.getSpace(snapshot.spaceId, snapshot.generation).executeAsOneOrNull()
                ?: return@await false
            if (!space.active || !space.exchange_enabled ||
                space.repository_owner != snapshot.repository.owner ||
                space.repository_name != snapshot.repository.name ||
                space.repository_branch != snapshot.repository.branch
            ) {
                return@await false
            }
            val database = this
            if (fence != null && !isCurrentFence(fence, snapshot.spaceId, snapshot.generation)) return@await false
            manifestStore.run { database.validateWarmAdmission(snapshot, admission) }
        }

    suspend fun observe(
        snapshot: SyncSnapshot,
        fence: SyncRemoteSnapshotFence? = null,
        afterValidated: suspend Database.(Set<String>, String) -> Unit = { _, _ -> },
    ) {
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
            val expectedRevision = fence?.revision ?: guard?.revision ?: 0L
            if (fence != null && !isCurrentFence(fence, snapshot.spaceId, snapshot.generation, guard)) {
                throw SyncRemoteSnapshotStaleCandidate()
            }
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
                blockGuardAtRevision(snapshot.spaceId, snapshot.generation, expectedRevision)
                return@await true
            }
            // Rehash the supplied object even on a cache hit: SyncSnapshot contains caller-owned Lists.
            if (guard?.latest_head == snapshot.head && knownHead == evidence.fingerprint) {
                afterValidated(emptySet(), evidence.fingerprint)
                return@await false
            }
            // A head observed earlier than the current anchor is a rollback, even when its
            // sync namespace is unchanged. The full commit fingerprint includes the tree/head,
            // but accepting it here would let unrelated-file-only commits hide ref rollback.
            if (knownHead != null) {
                blockGuardAtRevision(snapshot.spaceId, snapshot.generation, expectedRevision)
                return@await true
            }
            val observed = queries.getObjects(snapshot.spaceId, snapshot.generation).executeAsList()
            if (observed.any { evidence.objects[it.object_id] != it.fingerprint }) {
                blockGuardAtRevision(snapshot.spaceId, snapshot.generation, expectedRevision)
                return@await true
            }
            val observedIds = observed.mapTo(mutableSetOf()) { it.object_id }
            val newlyDiscovered = snapshot.batches.asSequence()
                .filter { "batch:${it.batchId}" !in observedIds }
                .map { it.batchId }
                .toSet()
            evidence.objects.forEach { (identity, fingerprint) ->
                if (identity !in observedIds) {
                    queries.insertObject(snapshot.spaceId, snapshot.generation, identity, fingerprint)
                }
            }
            queries.insertHead(snapshot.spaceId, snapshot.generation, snapshot.head, evidence.fingerprint)
            if (guard?.latest_head != snapshot.head) {
                queries.advanceHead(
                    snapshot.head,
                    expectedRevision + 1,
                    snapshot.spaceId,
                    snapshot.generation,
                    expectedRevision,
                )
                val advanced = queries.getGuard(snapshot.spaceId, snapshot.generation).executeAsOneOrNull()
                if (advanced == null || advanced.latest_head != snapshot.head ||
                    advanced.revision != expectedRevision + 1
                ) {
                    throw SyncRemoteSnapshotStaleCandidate()
                }
            }
            afterValidated(newlyDiscovered, evidence.fingerprint)
            false
        }
        // A rejection must not roll back the durable block together with its transaction.
        if (blocked) throw SyncRemoteSnapshotRejected()
    }

    private fun Database.isCurrentFence(
        fence: SyncRemoteSnapshotFence,
        spaceId: String,
        generation: Long,
        guard: Sync_remote_guards? = sync_remote_guardQueries.getGuard(spaceId, generation).executeAsOneOrNull(),
    ): Boolean {
        if (fence.spaceId != spaceId || fence.generation != generation ||
            (guard?.revision ?: 0L) != fence.revision
        ) {
            return false
        }
        fence.owner?.let { owner ->
            if (!isCurrentOwner(owner, spaceId, generation)) return false
        }
        return true
    }

    private fun Database.requireCurrentOwner(owner: SyncSnapshotWriteOwner, spaceId: String, generation: Long) {
        require(isCurrentOwner(owner, spaceId, generation)) {
            "sync run no longer owns snapshot exchange"
        }
    }

    private fun Database.isCurrentOwner(owner: SyncSnapshotWriteOwner, spaceId: String, generation: Long): Boolean {
        val run = sync_runtimeQueries.getRuntimeRun(owner.runId).executeAsOneOrNull() ?: return false
        return run.space_id == spaceId && run.generation == generation && run.state == "RUNNING" &&
            run.owner_session == owner.ownerSession && run.attempt_id == owner.attemptId
    }

    private fun Database.blockGuardAtRevision(spaceId: String, generation: Long, expectedRevision: Long) {
        sync_remote_guardQueries.blockGuardAtRevision(spaceId, generation, expectedRevision)
        val guard = sync_remote_guardQueries.getGuard(spaceId, generation).executeAsOneOrNull()
        if (guard?.blocked != true || guard.revision != expectedRevision + 1) {
            throw SyncRemoteSnapshotStaleCandidate()
        }
    }

    private fun evidence(snapshot: SyncSnapshot): Evidence {
        evidenceEvaluations++
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
