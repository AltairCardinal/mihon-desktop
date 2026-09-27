package mihon.data.sync.transport

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mihon.domain.sync.transport.SyncBatchIndexEntry
import mihon.domain.sync.transport.SyncGitTree
import mihon.domain.sync.transport.SyncGitTreeEntry
import mihon.domain.sync.transport.SyncRepository
import mihon.domain.sync.transport.SyncSnapshot
import okio.Buffer
import okio.ByteString.Companion.toByteString
import tachiyomi.data.Database
import tachiyomi.data.DatabaseHandler
import tachiyomi.data.Sync_snapshot_manifest_batches
import tachiyomi.data.Sync_snapshot_manifests

internal const val SYNC_SNAPSHOT_VALIDATOR_VERSION = "sync-v1"

/** Stable account/repository/key binding. Access tokens are deliberately excluded. */
internal data class SyncSnapshotManifestBinding(
    val accountId: Long,
    val repositoryId: Long,
    val connectionRevision: String,
)

internal data class SyncSnapshotManifestContext(
    val binding: SyncSnapshotManifestBinding,
    val repository: SyncRepository,
    val apiOrigin: String,
    val validatorVersion: String,
    val validationScope: String,
)

internal data class PreparedSyncSnapshotManifest(
    val context: SyncSnapshotManifestContext,
    val spaceId: String,
    val generation: Long,
    val head: String,
    val snapshot: SyncSnapshot,
    val checksum: String,
)

internal data class WarmSyncSnapshotAdmission(
    val snapshot: SyncSnapshot,
    val context: SyncSnapshotManifestContext,
    val headFingerprint: String,
    val checksum: String,
)

/** Durable, scope-bound manifest rows. Existing rows are updated only where their content changed. */
internal class SyncSnapshotManifestStore(private val handler: DatabaseHandler) {
    private val json = Json { encodeDefaults = true }

    /** A scoped prior record only disables the cold fast path; it never admits a snapshot. */
    suspend fun hasPriorManifestForScope(
        spaceId: String,
        generation: Long,
        context: SyncSnapshotManifestContext,
    ): Boolean = handler.await {
        val row = sync_remote_guardQueries.getSnapshotManifest(spaceId, generation).executeAsOneOrNull()
            ?: return@await false
        row.repository_owner == context.repository.owner && row.repository_name == context.repository.name &&
            row.repository_branch == context.repository.branch && row.account_id == context.binding.accountId &&
            row.repository_id == context.binding.repositoryId &&
            row.connection_revision == context.binding.connectionRevision && row.api_origin == context.apiOrigin &&
            row.validation_scope == context.validationScope && row.validator_version == context.validatorVersion
    }

    suspend fun findWarmSnapshot(
        repository: SyncRepository,
        spaceId: String,
        generation: Long,
        refHead: String,
        context: SyncSnapshotManifestContext,
    ): WarmSyncSnapshotAdmission? = handler.await(inTransaction = true) {
        if (context.repository != repository || context.binding.accountId <= 0 || context.binding.repositoryId <= 0) {
            return@await null
        }
        val space = sync_journalQueries.getSpace(spaceId, generation).executeAsOneOrNull() ?: return@await null
        if (!space.active || !space.exchange_enabled || space.repository_owner != repository.owner ||
            space.repository_name != repository.name || space.repository_branch != repository.branch
        ) {
            return@await null
        }
        val queries = sync_remote_guardQueries
        val cached = queries.getSnapshotManifest(spaceId, generation).executeAsOneOrNull() ?: return@await null
        val guard = queries.getGuard(spaceId, generation).executeAsOneOrNull() ?: return@await null
        if (guard.blocked || guard.latest_head != refHead || cached.head_sha != refHead) return@await null
        if (guard.repository_owner != repository.owner || guard.repository_name != repository.name ||
            guard.repository_branch != repository.branch || cached.repository_owner != repository.owner ||
            cached.repository_name != repository.name || cached.repository_branch != repository.branch
        ) {
            return@await null
        }
        if (cached.account_id != context.binding.accountId || cached.repository_id != context.binding.repositoryId ||
            cached.connection_revision != context.binding.connectionRevision ||
            cached.api_origin != context.apiOrigin ||
            cached.validator_version != context.validatorVersion || cached.validation_scope != context.validationScope
        ) {
            return@await null
        }
        if (queries.getHead(spaceId, generation, refHead).executeAsOneOrNull() != cached.head_fingerprint) {
            return@await null
        }
        // Legacy whole-document manifests are never promoted to trusted cache state. They are
        // rebuilt from the authenticated remote tree and written into normalized rows.
        if (cached.manifest_json.isNotEmpty() || cached.tree_sha.isEmpty()) return@await null
        val snapshot = readNormalizedSnapshot(this, cached, repository, spaceId, generation, refHead)
            ?: return@await null
        if (checksum(snapshot) != cached.checksum) return@await null
        if (snapshot.repository != repository || snapshot.spaceId != spaceId || snapshot.generation != generation ||
            snapshot.head != refHead
        ) {
            return@await null
        }
        WarmSyncSnapshotAdmission(snapshot, context, cached.head_fingerprint, cached.checksum)
    }

    /** Rechecks the admission rows after the transport returns, without rebuilding per-object evidence. */
    fun Database.validateWarmAdmission(
        snapshot: SyncSnapshot,
        admission: WarmSyncSnapshotAdmission,
    ): Boolean {
        if (snapshot !== admission.snapshot || snapshot.head != admission.snapshot.head ||
            snapshot.spaceId != admission.snapshot.spaceId || snapshot.generation != admission.snapshot.generation ||
            snapshot.repository != admission.snapshot.repository
        ) {
            return false
        }
        val context = admission.context
        val manifest = sync_remote_guardQueries.getSnapshotManifest(snapshot.spaceId, snapshot.generation)
            .executeAsOneOrNull() ?: return false
        val guard = sync_remote_guardQueries.getGuard(snapshot.spaceId, snapshot.generation)
            .executeAsOneOrNull() ?: return false
        if (guard.blocked || guard.latest_head != snapshot.head || manifest.head_sha != snapshot.head ||
            guard.repository_owner != snapshot.repository.owner || guard.repository_name != snapshot.repository.name ||
            guard.repository_branch != snapshot.repository.branch ||
            manifest.repository_owner != snapshot.repository.owner ||
            manifest.repository_name != snapshot.repository.name ||
            manifest.repository_branch != snapshot.repository.branch
        ) {
            return false
        }
        val binding = context.binding
        if (manifest.account_id != binding.accountId || manifest.repository_id != binding.repositoryId ||
            manifest.connection_revision != binding.connectionRevision || manifest.api_origin != context.apiOrigin ||
            manifest.validator_version != context.validatorVersion ||
            manifest.validation_scope != context.validationScope ||
            manifest.checksum != admission.checksum || manifest.head_fingerprint != admission.headFingerprint
        ) {
            return false
        }
        if (manifest.manifest_json.isNotEmpty() || manifest.tree_sha.isEmpty()) return false
        // The admission refers to caller-owned lists, so compare its live content with the
        // authenticated checksum before trusting the normalized durable rows below.
        if (checksum(snapshot) != admission.checksum) return false
        val current = readNormalizedSnapshot(
            this,
            manifest,
            snapshot.repository,
            snapshot.spaceId,
            snapshot.generation,
            snapshot.head,
        ) ?: return false
        return sync_remote_guardQueries.getHead(snapshot.spaceId, snapshot.generation, snapshot.head)
            .executeAsOneOrNull() == admission.headFingerprint && checksum(current) == admission.checksum
    }

    fun prepare(
        snapshot: SyncSnapshot,
        context: SyncSnapshotManifestContext,
    ): PreparedSyncSnapshotManifest? {
        return PreparedSyncSnapshotManifest(
            context,
            snapshot.spaceId,
            snapshot.generation,
            snapshot.head,
            snapshot,
            checksum(snapshot),
        )
    }

    /** Must be called from the guard transaction after the snapshot has passed validation. */
    fun persist(database: Database, prepared: PreparedSyncSnapshotManifest, headFingerprint: String) {
        val context = prepared.context
        val binding = context.binding
        database.sync_remote_guardQueries.upsertSnapshotManifest(
            space_id = prepared.spaceId,
            generation = prepared.generation,
            account_id = binding.accountId,
            repository_id = binding.repositoryId,
            repository_owner = context.repository.owner,
            repository_name = context.repository.name,
            repository_branch = context.repository.branch,
            api_origin = context.apiOrigin,
            validator_version = context.validatorVersion,
            validation_scope = context.validationScope,
            connection_revision = binding.connectionRevision,
            head_sha = prepared.head,
            head_fingerprint = headFingerprint,
            checksum = prepared.checksum,
            tree_sha = prepared.snapshot.tree.sha,
            truncated = prepared.snapshot.tree.truncated,
            manifest_json = "",
        )
        updateEntries(database, prepared.spaceId, prepared.generation, prepared.snapshot.tree.entries)
        updateBatches(database, prepared.spaceId, prepared.generation, prepared.snapshot.batches)
    }

    private fun readNormalizedSnapshot(
        database: Database,
        manifest: Sync_snapshot_manifests,
        repository: SyncRepository,
        spaceId: String,
        generation: Long,
        head: String,
    ): SyncSnapshot? {
        val entries = database.sync_remote_guardQueries.getSnapshotManifestEntries(spaceId, generation).executeAsList()
        val batches = database.sync_remote_guardQueries.getSnapshotManifestBatches(spaceId, generation).executeAsList()
        if (entries.isEmpty()) return null
        return SyncSnapshot(
            repository = repository,
            head = head,
            tree = SyncGitTree(
                manifest.tree_sha,
                entries.map {
                    SyncGitTreeEntry(it.path, it.mode, it.type, it.sha, it.size, it.content)
                },
                manifest.truncated,
            ),
            spaceId = spaceId,
            generation = generation,
            batches = batches.map {
                SyncBatchIndexEntry(
                    it.batch_id,
                    it.path,
                    it.digest_hex,
                    it.first_seq,
                    it.last_seq,
                    it.actor_id,
                    it.epoch,
                    it.index_path,
                    it.index_ciphertext_digest_hex,
                )
            },
        )
    }

    private fun checksum(snapshot: SyncSnapshot): String {
        val buffer = Buffer()
        fun append(value: String) {
            val bytes = value.encodeToByteArray()
            buffer.writeInt(bytes.size).write(bytes)
        }
        append(
            json.encodeToString(
                SnapshotIdentity(
                    snapshot.repository.owner,
                    snapshot.repository.name,
                    snapshot.repository.branch,
                    snapshot.head,
                    snapshot.spaceId,
                    snapshot.generation,
                    snapshot.tree.sha,
                    snapshot.tree.truncated,
                ),
            ),
        )
        snapshot.tree.entries.sortedBy { it.path }.forEach { append(json.encodeToString(it.toManifestEntry())) }
        snapshot.batches.sortedBy { it.batchId }.forEach { append(json.encodeToString(it.toManifestBatch())) }
        return SyncSnapshotManifestCrypto.sha256(buffer.readByteArray())
    }

    private fun updateEntries(database: Database, spaceId: String, generation: Long, entries: List<SyncGitTreeEntry>) {
        // SQLite's default text collation orders UTF-8 code points while Kotlin String ordering is UTF-16.
        // Normalize both sides to Kotlin order before merging, including supplementary-plane paths.
        val existing = database.sync_remote_guardQueries.getSnapshotManifestEntries(spaceId, generation)
            .executeAsList()
            .sortedBy { it.path }
        val current = entries.sortedBy { it.path }
        var oldIndex = 0
        var newIndex = 0
        while (oldIndex < existing.size || newIndex < current.size) {
            val old = existing.getOrNull(oldIndex)
            val new = current.getOrNull(newIndex)
            when {
                new == null || (old != null && old.path < new.path) -> {
                    database.sync_remote_guardQueries.deleteSnapshotManifestEntry(spaceId, generation, old!!.path)
                    oldIndex++
                }
                old == null || new.path < old.path -> {
                    database.insertManifestEntry(spaceId, generation, new)
                    newIndex++
                }
                else -> {
                    if (old.mode != new.mode || old.type != new.type || old.sha != new.sha ||
                        old.size != new.size || old.content != new.content
                    ) {
                        database.insertManifestEntry(spaceId, generation, new)
                    }
                    oldIndex++
                    newIndex++
                }
            }
        }
    }

    private fun Database.insertManifestEntry(spaceId: String, generation: Long, entry: SyncGitTreeEntry) {
        sync_remote_guardQueries.upsertSnapshotManifestEntry(
            spaceId,
            generation,
            entry.path,
            entry.mode,
            entry.type,
            entry.sha,
            entry.size,
            entry.content,
        )
    }

    private fun updateBatches(
        database: Database,
        spaceId: String,
        generation: Long,
        batches: List<SyncBatchIndexEntry>,
    ) {
        val existing = database.sync_remote_guardQueries.getSnapshotManifestBatches(spaceId, generation).executeAsList()
        val current = batches.sortedBy { it.batchId }
        var oldIndex = 0
        var newIndex = 0
        while (oldIndex < existing.size || newIndex < current.size) {
            val old = existing.getOrNull(oldIndex)
            val new = current.getOrNull(newIndex)
            when {
                new == null || (old != null && old.batch_id < new.batchId) -> {
                    database.sync_remote_guardQueries.deleteSnapshotManifestBatch(spaceId, generation, old!!.batch_id)
                    oldIndex++
                }
                old == null || new.batchId < old.batch_id -> {
                    database.insertManifestBatch(spaceId, generation, new)
                    newIndex++
                }
                else -> {
                    val oldValue = old.toDomain()
                    if (oldValue != new) database.insertManifestBatch(spaceId, generation, new)
                    oldIndex++
                    newIndex++
                }
            }
        }
    }

    private fun Database.insertManifestBatch(spaceId: String, generation: Long, batch: SyncBatchIndexEntry) {
        sync_remote_guardQueries.upsertSnapshotManifestBatch(
            spaceId, generation, batch.batchId, batch.path, batch.digestHex, batch.firstSeq, batch.lastSeq,
            batch.actorId, batch.epoch, batch.indexPath, batch.indexCiphertextDigestHex,
        )
    }

    private fun Sync_snapshot_manifest_batches.toDomain() = SyncBatchIndexEntry(
        batch_id, path, digest_hex, first_seq, last_seq, actor_id, epoch, index_path, index_ciphertext_digest_hex,
    )

    private fun SyncGitTreeEntry.toManifestEntry() = SnapshotTreeEntry(path, mode, type, sha, size, content)

    private fun SyncBatchIndexEntry.toManifestBatch() = SnapshotBatch(
        batchId,
        path,
        digestHex,
        firstSeq,
        lastSeq,
        actorId,
        epoch,
        indexPath,
        indexCiphertextDigestHex,
    )

    @Serializable
    private data class SnapshotIdentity(
        val owner: String,
        val repository: String,
        val branch: String,
        val head: String,
        val spaceId: String,
        val generation: Long,
        val treeSha: String,
        val truncated: Boolean,
    )

    @Serializable
    private data class SnapshotTreeEntry(
        val path: String,
        val mode: String,
        val type: String,
        val sha: String,
        val size: Long?,
        val content: String?,
    )

    @Serializable
    private data class SnapshotBatch(
        val batchId: String,
        val path: String,
        val digestHex: String,
        val firstSeq: Long,
        val lastSeq: Long,
        val actorId: String,
        val epoch: Long,
        val indexPath: String,
        val indexCiphertextDigestHex: String,
    )
}

internal object SyncSnapshotManifestCrypto {
    fun sha256(bytes: ByteArray): String =
        mihon.data.sync.crypto.SyncAeadEngineFactory.create().sha256(bytes).toByteString().hex()
}
