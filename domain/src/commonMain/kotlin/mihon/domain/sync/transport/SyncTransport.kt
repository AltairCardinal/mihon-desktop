package mihon.domain.sync.transport

import kotlinx.serialization.Serializable
import mihon.domain.sync.SyncBatch
import mihon.domain.sync.crypto.SyncAeadCiphertext
import mihon.domain.sync.crypto.SyncEncryptedBatch
import mihon.domain.sync.crypto.SyncPayload

object SyncRepositoryTarget {
    const val NAME = "mihon-sync"
    const val BRANCH = "mihon-sync-v1"
}

data class SyncRepository(
    val owner: String,
    val name: String,
    val branch: String,
) {
    init {
        require(owner.matches(Regex("[A-Za-z0-9_.-]{1,100}"))) { "invalid repository owner" }
        require(name.matches(Regex("[A-Za-z0-9_.-]{1,100}"))) { "invalid repository name" }
        require(branch.matches(Regex("[A-Za-z0-9._/-]{1,200}")) && !branch.contains("..")) {
            "invalid repository branch"
        }
    }

    val fullName: String get() = "$owner/$name"
}

data class SyncGitRef(val name: String, val objectSha: String)

data class SyncGitCommit(
    val sha: String,
    val treeSha: String,
    val parents: List<String>,
)

data class SyncGitTreeEntry(
    val path: String,
    val mode: String,
    val type: String,
    val sha: String,
    val size: Long? = null,
)

data class SyncGitTree(
    val sha: String,
    val entries: List<SyncGitTreeEntry>,
    val truncated: Boolean,
)

data class SyncGitBlob(val sha: String, val content: ByteArray)

data class SyncBatchIndexEntry(
    val batchId: String,
    val path: String,
    val digestHex: String,
    val firstSeq: Long,
    val lastSeq: Long,
    val actorId: String = "unknown",
    val epoch: Long = 0,
    val indexPath: String = "",
    val indexCiphertextDigestHex: String = "",
)

data class SyncSnapshot(
    val repository: SyncRepository,
    val head: String,
    val tree: SyncGitTree,
    val spaceId: String,
    val generation: Long,
    val batches: List<SyncBatchIndexEntry>,
) {
    /** Source-compatible constructor for callers that only have an unbound snapshot. */
    constructor(
        repository: SyncRepository,
        head: String,
        tree: SyncGitTree,
        batches: List<SyncBatchIndexEntry>,
    ) : this(repository, head, tree, "", 0, batches)
}

enum class SyncPublishStatus {
    PUBLISHED,
    UNCONFIRMED,
    CONFLICT,
    FAILED,
}

data class SyncPublishResult(
    val status: SyncPublishStatus,
    val batchId: String,
    val commitSha: String? = null,
    val error: String? = null,
    val attempts: Int = 0,
)

sealed interface SyncInitializationResult {
    data class Initialized(val head: String, val spaceId: String) : SyncInitializationResult
    data class Adopted(val head: String, val spaceId: String) : SyncInitializationResult
    data class NeedsExplicitAction(val reason: String) : SyncInitializationResult
    data class Failed(val reason: String) : SyncInitializationResult
}

@Serializable
enum class SyncInitializationStage {
    VERIFIED_EMPTY,
    BOOTSTRAP_SUBMITTING,
    BOOTSTRAP_CONFIRMED,
    SPACE_PUBLISHING,
    SPACE_CONFIRMED,
    CONNECTED,
}

data class SyncInitializationIntent(
    val accountId: Long,
    val repositoryId: Long,
    val defaultBranch: String,
    val attemptNonce: String,
    val stage: SyncInitializationStage,
    val bootstrapCommitSha: String? = null,
    val bootstrapTreeSha: String? = null,
)

data class SyncInitializationCheckpoint(
    val stage: SyncInitializationStage,
    val bootstrapCommitSha: String? = null,
    val bootstrapTreeSha: String? = null,
)

interface SyncTransportPort {
    fun prepare(snapshot: SyncSnapshot, encryptedBatch: SyncEncryptedBatch): SyncPreparedUpload

    suspend fun readSnapshot(
        repository: SyncRepository,
        expectedSpaceId: String,
        expectedGeneration: Long,
    ): Result<SyncSnapshot>

    suspend fun publish(
        repository: SyncRepository,
        snapshot: SyncSnapshot,
        upload: SyncPreparedUpload,
        observeSnapshot: suspend (SyncSnapshot) -> Unit = {},
    ): SyncPublishResult

    suspend fun readBatch(snapshot: SyncSnapshot, entry: SyncBatchIndexEntry): Result<SyncEncryptedBatch> =
        Result.failure(UnsupportedOperationException("batch reading is not implemented"))

    suspend fun initialize(
        repository: SyncRepository,
        spaceId: String,
        generation: Long,
    ): SyncInitializationResult

    suspend fun initialize(
        repository: SyncRepository,
        spaceId: String,
        generation: Long,
        intent: SyncInitializationIntent,
        saveCheckpoint: suspend (SyncInitializationCheckpoint) -> Unit,
    ): SyncInitializationResult = initialize(repository, spaceId, generation)
}

data class SyncUploadResult(
    val artifact: SyncPreparedUpload,
    val publish: SyncPublishResult,
) {
    val encryptedBatch: SyncEncryptedBatch get() = artifact.encryptedBatch
}

/** A durable retry contains the complete batch, immutable index and replaceable actor head. */
data class SyncPreparedUpload(
    val repository: SyncRepository,
    val baseHead: String,
    val encryptedBatch: SyncEncryptedBatch,
    val previousIndexPath: String?,
    val previousLastSeq: Long,
    val indexCiphertext: SyncPayload,
    val headCiphertext: SyncPayload,
) {
    val indexPath: String get() =
        ".mihon-sync/index/${encryptedBatch.actorId}/${encryptedBatch.epoch}/${encryptedBatch.batchId}.bin"
    val headPath: String get() = ".mihon-sync/heads/${encryptedBatch.actorId}/${encryptedBatch.epoch}.bin"
}

data class SyncReceiveResult(
    val batch: SyncBatch?,
    val error: String? = null,
)
