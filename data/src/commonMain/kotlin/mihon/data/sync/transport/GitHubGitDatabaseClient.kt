@file:Suppress("ktlint:standard:filename")

package mihon.data.sync.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import mihon.data.sync.auth.hasExplicitEmptyRepositoryMessage
import mihon.data.sync.crypto.SyncAeadEngineFactory
import mihon.data.sync.http.SyncFailureDiagnostics
import mihon.data.sync.http.SyncFailurePhase
import mihon.data.sync.http.SyncHttpBodyWork
import mihon.data.sync.http.SyncHttpClient
import mihon.data.sync.http.SyncHttpException
import mihon.data.sync.http.SyncHttpFailureClass
import mihon.data.sync.http.SyncHttpRequestGate
import mihon.data.sync.http.SyncHttpResponse
import mihon.domain.sync.crypto.SyncAeadCiphertext
import mihon.domain.sync.crypto.SyncAeadEngine
import mihon.domain.sync.crypto.SyncBatchEncryption
import mihon.domain.sync.crypto.SyncCryptoBinding
import mihon.domain.sync.crypto.SyncEncryptedBatch
import mihon.domain.sync.crypto.SyncPayload
import mihon.domain.sync.crypto.SyncSecret
import mihon.domain.sync.crypto.SyncSpaceDescriptorCodec
import mihon.domain.sync.crypto.SyncSpaceMaterial
import mihon.domain.sync.crypto.SyncSpacePayload
import mihon.domain.sync.crypto.SyncSpacePayloadCodec
import mihon.domain.sync.transport.SyncBatchIndexEntry
import mihon.domain.sync.transport.SyncGitBlob
import mihon.domain.sync.transport.SyncGitCommit
import mihon.domain.sync.transport.SyncGitRef
import mihon.domain.sync.transport.SyncGitTree
import mihon.domain.sync.transport.SyncGitTreeEntry
import mihon.domain.sync.transport.SyncInitializationCheckpoint
import mihon.domain.sync.transport.SyncInitializationIntent
import mihon.domain.sync.transport.SyncInitializationResult
import mihon.domain.sync.transport.SyncInitializationStage
import mihon.domain.sync.transport.SyncPreparedUpload
import mihon.domain.sync.transport.SyncPublishFailureClass
import mihon.domain.sync.transport.SyncPublishResult
import mihon.domain.sync.transport.SyncPublishStatus
import mihon.domain.sync.transport.SyncRepository
import mihon.domain.sync.transport.SyncSnapshot
import mihon.domain.sync.transport.SyncTransportPort
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.toByteString
import okio.Path
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.collections.LinkedHashMap
import kotlin.random.Random

private val githubJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    isLenient = false
}
private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

internal fun gitBlobOid(content: ByteArray, oidLength: Int): String? {
    val header = "blob ${content.size}\u0000".encodeToByteArray()
    val objectBytes = (header + content).toByteString()
    return when (oidLength) {
        40 -> objectBytes.sha1().hex()
        64 -> objectBytes.sha256().hex()
        else -> null
    }
}

/** Git's canonical tree object: byte-sorted names, a trailing slash for tree sort order. */
internal fun gitTreeOid(entries: List<SyncGitTreeEntry>, oidLength: Int): String? {
    if (oidLength != 40 && oidLength != 64) return null
    fun sortName(entry: SyncGitTreeEntry) =
        (entry.path + if (entry.type == "tree") "/" else "").encodeToByteArray()
    val body = Buffer()
    val oidPattern = if (oidLength == 40) Regex("[0-9a-f]{40}") else Regex("[0-9a-f]{64}")
    entries.sortedWith { left, right ->
        val a = sortName(left)
        val b = sortName(right)
        var difference = 0
        for (index in 0 until minOf(a.size, b.size)) {
            difference = (a[index].toInt() and 255).compareTo(b[index].toInt() and 255)
            if (difference != 0) break
        }
        if (difference == 0) a.size.compareTo(b.size) else difference
    }.forEach { entry ->
        if (!oidPattern.matches(entry.sha) ||
            (entry.type != "tree" && entry.type != "blob")
        ) {
            return null
        }
        val mode = if (entry.type == "tree") "40000" else "100644"
        body.writeUtf8("$mode ${entry.path}").writeByte(0).write(entry.sha.decodeHex())
    }
    val bytes = body.readByteArray()
    val objectBytes = ("tree ${bytes.size}\u0000".encodeToByteArray() + bytes).toByteString()
    return if (oidLength == 40) objectBytes.sha1().hex() else objectBytes.sha256().hex()
}

/**
 * A bounded cache for immutable Git blob bytes.
 *
 * Git blob ids are content addressed, so a hit can only reuse bytes that were
 * already validated against the requested id. The cache contains no credentials
 * or durable sync state and can be dropped without changing the trust history.
 */
internal data class SyncBlobCacheKey(
    val apiOrigin: String,
    val repository: String,
    val branch: String,
    val objectFormat: String,
    val objectOid: String,
    val validationScope: String,
    val connectionRevision: String,
)

internal class SyncBlobCache(private val maxBytes: Long = 16L * 1024 * 1024) {
    private val mutex = Mutex()
    private val values = LinkedHashMap<SyncBlobCacheKey, ByteArray>(16, 0.75f, true)
    private val inFlight = mutableMapOf<SyncBlobCacheKey, CompletableDeferred<ByteArray>>()
    private var sizeBytes = 0L

    suspend fun getOrLoad(key: SyncBlobCacheKey, loader: suspend () -> ByteArray): ByteArray {
        var cached: ByteArray? = null
        var deferred: CompletableDeferred<ByteArray>? = null
        var owner = false
        mutex.withLock {
            cached = values[key]?.copyOf()
            if (cached == null) {
                deferred = inFlight[key]
                if (deferred == null) {
                    deferred = CompletableDeferred()
                    inFlight[key] = deferred!!
                    owner = true
                }
            }
        }
        cached?.let { return it }
        val flight = requireNotNull(deferred)
        if (!owner) return flight.await().copyOf()
        return try {
            withContext(NonCancellable) {
                // Keep publication and flight cleanup together: cancellation while waiting
                // for the mutex must not strand callers on an incomplete deferred.
                val loaded = loader().copyOf()
                mutex.withLock {
                    putLocked(key, loaded)
                    if (inFlight[key] === flight) inFlight.remove(key)
                }
                flight.complete(loaded.copyOf())
                loaded
            }
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                mutex.withLock {
                    if (inFlight[key] === flight) inFlight.remove(key)
                }
                flight.completeExceptionally(error)
            }
            throw error
        }
    }

    private fun putLocked(key: SyncBlobCacheKey, content: ByteArray) {
        if (content.size.toLong() > maxBytes) return
        values.remove(key)?.let { sizeBytes -= it.size }
        values[key] = content.copyOf()
        sizeBytes += content.size
        val iterator = values.entries.iterator()
        while (sizeBytes > maxBytes && iterator.hasNext()) {
            val entry = iterator.next()
            sizeBytes -= entry.value.size
            iterator.remove()
        }
    }
}

internal class SyncLoadSingleFlight<K : Any, V> {
    private val mutex = Mutex()
    private val inFlight = mutableMapOf<K, CompletableDeferred<V>>()

    suspend fun getOrLoad(key: K, loader: suspend () -> V): V {
        var owner = false
        val flight = mutex.withLock {
            inFlight[key] ?: CompletableDeferred<V>().also {
                inFlight[key] = it
                owner = true
            }
        }
        if (!owner) return flight.await()

        return try {
            withContext(NonCancellable) {
                val value = loader()
                mutex.withLock {
                    if (inFlight[key] === flight) inFlight.remove(key)
                }
                flight.complete(value)
                value
            }
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                mutex.withLock {
                    if (inFlight[key] === flight) inFlight.remove(key)
                }
                flight.completeExceptionally(error)
            }
            throw error
        }
    }
}

private data class SyncTreeCacheKey(
    val apiOrigin: String,
    val repository: String,
    val branch: String,
    val treeOid: String,
    val validationScope: String,
    val connectionRevision: String,
)

private data class CachedRawTree(val entries: List<SyncGitTreeEntry>, val truncated: Boolean)

private data class SyncSnapshotCacheKey(
    val repository: SyncRepository,
    val spaceId: String,
    val generation: Long,
    val head: String,
    val apiOrigin: String,
    val validationScope: String,
    val connectionRevision: String,
)

class GitHubSyncTransport(
    productionClient: OkHttpClient,
    private val tokenProvider: suspend () -> String,
    private val apiBaseUrl: String = "https://api.github.com",
    private val maxTreeEntries: Int = 20_000,
    private val indexSecret: SyncSecret? = null,
    private val indexEngine: SyncAeadEngine = SyncAeadEngineFactory.create(),
    private val spaceMaterial: SyncSpaceMaterial? = null,
    connectionRevision: String = "transport-instance",
    persistentObjectCacheDirectory: Path? = null,
    requestGate: SyncHttpRequestGate? = null,
    bodyObserver: mihon.data.sync.http.SyncHttpBodyObserver? = null,
    repositoryId: Long? = null,
) : SyncTransportPort {
    private var connectionRevision = connectionRevision
    private var stableRepositoryId = repositoryId
    private val http = SyncHttpClient(
        productionClient,
        setOf(apiBaseUrl.hostOrNull() ?: "api.github.com"),
        requestGate = requestGate,
        bodyObserver = bodyObserver,
    )
    private val persistentObjectCache = SyncPersistentGitObjectCache(persistentObjectCacheDirectory)
    private val blobCache = SyncBlobCache()
    private val treeLoads = SyncLoadSingleFlight<SyncTreeCacheKey, CachedRawTree>()
    private val treeCacheMutex = Mutex()
    private val treeCache = mutableMapOf<SyncTreeCacheKey, CachedRawTree>()
    private val treeCacheOrder = mutableListOf<SyncTreeCacheKey>()
    private var hasValidatedSnapshot = false
    private var treeCacheWeightBytes = 0L
    private val maxTreeCacheWeightBytes = 4L * 1024 * 1024
    internal var treePathMaterializations: Long = 0
        private set
    private var snapshotCache: Pair<SyncSnapshotCacheKey, SyncSnapshot>? = null
    private val manifestCandidateMutex = Mutex()
    private val manifestCandidates = mutableListOf<Pair<SyncSnapshot, PreparedSyncSnapshotManifest>>()
    private val warmAdmissionMutex = Mutex()
    private val warmAdmissions = mutableListOf<Pair<SyncSnapshot, WarmSyncSnapshotAdmission>>()
    private val snapshotFenceMutex = Mutex()
    private val snapshotFences = mutableListOf<Pair<SyncSnapshot, SyncRemoteSnapshotFence>>()
    private var snapshotFenceProvider: suspend (String, Long) -> SyncRemoteSnapshotFence? = { _, _ -> null }
    private var batchObjectsUploaded: (String) -> Unit = {}
    private var batchTransferResumed: (String) -> Unit = {}

    /** Called after all batch/index/tree/commit bodies are accepted, before ref publication. */
    fun onBatchObjectsUploaded(observer: (String) -> Unit) {
        batchObjectsUploaded = observer
    }

    /** A new publish attempt can transfer different tree/commit bodies after a ref conflict. */
    fun onBatchTransferResumed(observer: (String) -> Unit) {
        batchTransferResumed = observer
    }
    private var snapshotManifestStore: SyncSnapshotManifestStore? = null
    private var snapshotManifestBinding: SyncSnapshotManifestBinding? = null
    private val apiOrigin: String by lazy {
        val url = apiBaseUrl.toHttpUrl()
        "${url.scheme}://${url.host}:${url.port}"
    }

    internal fun installSnapshotManifestStore(
        store: SyncSnapshotManifestStore,
        binding: SyncSnapshotManifestBinding,
    ) {
        snapshotManifestStore = store
        snapshotManifestBinding = binding
        connectionRevision = binding.connectionRevision
        stableRepositoryId = binding.repositoryId
    }

    internal fun installSnapshotFenceProvider(provider: suspend (String, Long) -> SyncRemoteSnapshotFence?) {
        snapshotFenceProvider = provider
    }

    internal suspend fun takeSnapshotManifest(snapshot: SyncSnapshot): PreparedSyncSnapshotManifest? =
        manifestCandidateMutex.withLock {
            val index = manifestCandidates.indexOfFirst { it.first === snapshot }
            if (index < 0) null else manifestCandidates.removeAt(index).second
        }

    internal suspend fun takeWarmAdmission(snapshot: SyncSnapshot): WarmSyncSnapshotAdmission? =
        warmAdmissionMutex.withLock {
            val index = warmAdmissions.indexOfFirst { it.first === snapshot }
            if (index < 0) null else warmAdmissions.removeAt(index).second
        }

    internal suspend fun takeSnapshotFence(snapshot: SyncSnapshot): SyncRemoteSnapshotFence? =
        snapshotFenceMutex.withLock {
            val index = snapshotFences.indexOfLast { it.first === snapshot }
            if (index < 0) {
                null
            } else {
                val newest = snapshotFences[index].second
                // The in-memory snapshot cache can return the same object for several reads.
                // Once consumed, older fences for that object must not escape on a later observe.
                snapshotFences.removeAll { it.first === snapshot }
                newest
            }
        }

    override suspend fun readCurrentHead(
        repository: SyncRepository,
        expectedSpaceId: String,
        expectedGeneration: Long,
    ): Result<String> = suspendResult {
        require(expectedSpaceId.isNotBlank() && expectedGeneration > 0) { "invalid sync snapshot scope" }
        val ref = getRef(repository)
        require(ref.name == "refs/heads/${repository.branch}") { "GitHub returned a different sync ref" }
        ref.objectSha
    }

    override suspend fun readSnapshot(
        repository: SyncRepository,
        expectedSpaceId: String,
        expectedGeneration: Long,
    ): Result<SyncSnapshot> = suspendResult {
        // Capture durable ownership before starting the ref/tree network read.
        val snapshotFence = snapshotFenceProvider(expectedSpaceId, expectedGeneration)
        val validatorVersion = SYNC_SNAPSHOT_VALIDATOR_VERSION
        val validationScope = "space=$expectedSpaceId;generation=$expectedGeneration;validator=$validatorVersion"
        val ref = getRef(repository)
        val manifestContext = snapshotManifestBinding?.let { binding ->
            SyncSnapshotManifestContext(
                binding,
                repository,
                apiOrigin,
                validatorVersion,
                validationScope,
            )
        }
        val manifestStore = snapshotManifestStore
        if (manifestStore != null && manifestContext != null) {
            val warmAdmission = manifestStore.findWarmSnapshot(
                repository,
                expectedSpaceId,
                expectedGeneration,
                ref.objectSha,
                manifestContext,
            )
            if (warmAdmission != null) {
                treeCacheMutex.withLock { hasValidatedSnapshot = true }
                snapshotFence?.let { rememberSnapshotFence(warmAdmission.snapshot, it) }
                warmAdmissionMutex.withLock { warmAdmissions += warmAdmission.snapshot to warmAdmission }
                return@suspendResult warmAdmission.snapshot
            }
        } else {
            val key = SyncSnapshotCacheKey(
                repository,
                expectedSpaceId,
                expectedGeneration,
                ref.objectSha,
                apiOrigin,
                validationScope,
                connectionRevision,
            )
            treeCacheMutex.withLock { snapshotCache?.takeIf { it.first == key }?.second }?.let {
                snapshotFence?.let { fence -> rememberSnapshotFence(it, fence) }
                return@suspendResult it
            }
        }
        val commit = getCommit(repository, ref.objectSha)
        val coldTree = manifestStore != null && manifestContext != null &&
            treeCacheMutex.withLock { !hasValidatedSnapshot } &&
            !manifestStore.hasPriorManifestForScope(expectedSpaceId, expectedGeneration, manifestContext)
        val tree = getTree(repository, commit.treeSha, validationScope, coldTree)
        requireRemoteData(!tree.truncated && tree.entries.size <= maxTreeEntries) {
            "sync tree is truncated or oversized"
        }
        val files = tree.entries.associateBy { it.path }
        requireRemoteData(files.size == tree.entries.size) { "sync tree contains duplicate paths" }
        spaceMaterial?.let { material ->
            require(
                material.descriptor.spaceId == expectedSpaceId && material.descriptor.generation == expectedGeneration,
            ) {
                "sync space identity mismatch"
            }
            val descriptor = files[SyncSpaceDescriptorCodec.PATH]
                ?: throw SyncRemoteDataInvalid("sync space descriptor is missing")
            requireRemoteData(descriptor.type == "blob" && descriptor.mode == "100644") {
                "space descriptor must be a regular file"
            }
            val remote = SyncSpaceDescriptorCodec.decode(
                getBlob(repository, descriptor.sha, validationScope = validationScope).content,
            ).getOrThrow()
            if (remote != material.descriptor) throw SyncRemoteDataInvalid("sync space descriptor changed")
        }
        val indexEntries = tree.entries.filter { it.type == "blob" && it.path.startsWith(".mihon-sync/index/") }
        if (indexEntries.isEmpty()) throw SyncRemoteDataInvalid("sync index is missing")
        requireRemoteData(indexEntries.size <= 10_000) { "sync index exceeds entry limit" }
        val shards = indexEntries.map { entry ->
            requireRemoteData(entry.mode == "100644") { "sync index must be a regular file" }
            val bytes = getBlob(repository, entry.sha, validationScope = validationScope).content
            decodeRemoteData { decryptShard(bytes, entry.path, expectedSpaceId, expectedGeneration) }
        }
        val bootstrap = shards.filter { it.batch == null }
        requireRemoteData(
            bootstrap.size == 1 && bootstrap.single().path == ".mihon-sync/index/bootstrap/0/bootstrap.bin" &&
                bootstrap.single().previousPath == null,
        ) { "sync bootstrap is missing or invalid" }
        val paths = shards.associateBy { it.path }
        val chains = shards.filter { it.batch != null }.groupBy { it.actorId to it.epoch }
        val heads = tree.entries.filter { it.type == "blob" && it.path.startsWith(".mihon-sync/heads/") }.map { entry ->
            requireRemoteData(entry.mode == "100644") { "sync head must be a regular file" }
            val bytes = getBlob(repository, entry.sha, validationScope = validationScope).content
            decodeRemoteData { decryptHead(bytes, entry.path, expectedSpaceId, expectedGeneration) }
        }
        requireRemoteData(heads.size == chains.size && heads.map { it.actorId to it.epoch }.toSet() == chains.keys) {
            "sync actor head is missing or duplicated"
        }
        // Walk each actor once from its authenticated head, rejecting gaps, forks and unreachable shards.
        for (head in heads) {
            val chain = chains.getValue(head.actorId to head.epoch)
            val visited = mutableSetOf<String>()
            var node = paths[head.indexPath] ?: throw SyncRemoteDataInvalid("sync head points to a missing index")
            val latest = requireRemoteValue(node.batch)
            requireRemoteData(
                latest.batchId == head.batchId && latest.lastSeq == head.lastSeq && latest.digestHex == head.digestHex,
            ) {
                "sync head does not match its index"
            }
            while (true) {
                requireRemoteData(visited.add(node.path)) { "sync index chain contains a cycle" }
                requireRemoteData(node.actorId == head.actorId && node.epoch == head.epoch) {
                    "sync index chain crosses actor"
                }
                val batch = requireRemoteValue(node.batch) { "sync actor chain enters bootstrap" }
                val previousPath = node.previousPath
                if (previousPath == null) {
                    requireRemoteData(batch.firstSeq == 1L) { "sync actor chain has a missing prefix" }
                    break
                }
                val previous = paths[previousPath] ?: throw SyncRemoteDataInvalid("sync index chain is broken")
                val previousBatch = requireRemoteValue(previous.batch)
                requireRemoteData(
                    previousBatch.lastSeq < Long.MAX_VALUE && previousBatch.lastSeq + 1 == batch.firstSeq,
                ) {
                    "sync index sequence chain is incomplete"
                }
                node = previous
            }
            requireRemoteData(visited.size == chain.size) { "sync index contains an unreachable branch" }
        }
        val index = shards.mapNotNull { shard ->
            shard.batch?.let { batch ->
                requireRemoteData(
                    batch.path == ".mihon-sync/batches/${batch.actorId}/${batch.epoch}/${batch.batchId}.json",
                ) {
                    "sync batch path is invalid"
                }
                requireRemoteData(
                    batch.digestHex.matches(Regex("[0-9a-f]{64}")) &&
                        batch.firstSeq > 0 && batch.lastSeq >= batch.firstSeq,
                ) {
                    "sync index batch metadata is invalid"
                }
                val file = files[batch.path]
                requireRemoteData(file != null && file.type == "blob" && file.mode == "100644") {
                    "sync indexed batch is missing"
                }
                SyncBatchIndexEntry(
                    batch.batchId, batch.path, batch.digestHex, batch.firstSeq, batch.lastSeq,
                    batch.actorId, batch.epoch, shard.path, shard.ciphertextDigestHex,
                )
            }
        }
        requireRemoteData(index.map { it.batchId }.toSet().size == index.size) { "sync batch ids are duplicated" }
        val storedPaths = tree.entries
            .filter { it.type == "blob" && it.path.startsWith(".mihon-sync/batches/") }
            .map { it.path }.toSet()
        requireRemoteData(storedPaths == index.map { it.path }.toSet()) { "sync batch has no authenticated index" }
        val snapshot = SyncSnapshot(repository, ref.objectSha, tree, expectedSpaceId, expectedGeneration, index)
        snapshotFence?.let { rememberSnapshotFence(snapshot, it) }
        if (manifestStore != null && manifestContext != null) {
            manifestStore.prepare(snapshot, manifestContext)?.let { prepared ->
                manifestCandidateMutex.withLock { manifestCandidates += snapshot to prepared }
            }
        } else {
            val key = SyncSnapshotCacheKey(
                repository,
                expectedSpaceId,
                expectedGeneration,
                ref.objectSha,
                apiOrigin,
                validationScope,
                connectionRevision,
            )
            treeCacheMutex.withLock { snapshotCache = key to snapshot }
        }
        treeCacheMutex.withLock { hasValidatedSnapshot = true }
        snapshot
    }.recoverCatching { failure ->
        if (failure is SyncHttpException && failure.code == 404) {
            throw mihon.data.sync.http.SyncRequiredResourceUnavailable(
                mihon.data.sync.http.SyncRequiredResource.SPACE_DATA,
            )
        }
        throw failure
    }

    private suspend fun rememberSnapshotFence(snapshot: SyncSnapshot, fence: SyncRemoteSnapshotFence) {
        snapshotFenceMutex.withLock { snapshotFences += snapshot to fence }
    }

    suspend fun readEncryptedBatch(snapshot: SyncSnapshot, entry: SyncBatchIndexEntry): SyncEncryptedBatch {
        val treeEntry = snapshot.tree.entries.firstOrNull { it.path == entry.path }
            ?: throw IllegalStateException("sync batch is missing")
        val stored = decodeStoredBatch(
            getBlob(
                snapshot.repository,
                treeEntry.sha,
                cache = false,
                validationScope = "space=${snapshot.spaceId};generation=${snapshot.generation};validator=sync-v1",
                bodyWorkKey = "download:${entry.batchId}:${treeEntry.sha}",
            ).content,
        )
        require(stored.batchId == entry.batchId) { "sync batch id does not match index" }
        require(stored.plaintextDigestHex == entry.digestHex) { "sync batch digest does not match index" }
        require(stored.spaceId == snapshot.spaceId && stored.generation == snapshot.generation) {
            "sync batch scope does not match snapshot"
        }
        require(stored.path == entry.path && stored.actorId == entry.actorId && stored.epoch == entry.epoch) {
            "sync batch metadata does not match index"
        }
        require(stored.firstSeq == entry.firstSeq && stored.lastSeq == entry.lastSeq) {
            "sync batch sequence does not match index"
        }
        return stored.toDomain()
    }

    override fun prepare(snapshot: SyncSnapshot, encryptedBatch: SyncEncryptedBatch): SyncPreparedUpload {
        val secret = spaceMaterial?.secret ?: indexSecret
        require(spaceMaterial != null || secret != null) { "sync index key is unavailable" }
        require(snapshot.spaceId == encryptedBatch.spaceId && snapshot.generation == encryptedBatch.generation) {
            "sync batch scope does not match snapshot"
        }
        SyncBatchEncryption.decrypt(indexEngine, secret, encryptedBatch, spaceMaterial)
        val previous = snapshot.batches
            .filter { it.actorId == encryptedBatch.actorId && it.epoch == encryptedBatch.epoch }
            .maxByOrNull { it.lastSeq }
        val previousSeq = previous?.lastSeq ?: 0
        require(previousSeq < Long.MAX_VALUE && encryptedBatch.firstSeq == previousSeq + 1) {
            "sync upload does not extend the actor sequence"
        }
        val indexPath =
            ".mihon-sync/index/${encryptedBatch.actorId}/${encryptedBatch.epoch}/${encryptedBatch.batchId}.bin"
        val headPath = ".mihon-sync/heads/${encryptedBatch.actorId}/${encryptedBatch.epoch}.bin"
        val digest = encryptedBatch.plaintextDigest.toByteString().hex()
        val shard = ManifestShard(
            1,
            snapshot.spaceId,
            snapshot.generation,
            encryptedBatch.actorId,
            encryptedBatch.epoch,
            previous?.indexPath,
            ManifestBatch(
                encryptedBatch.batchId,
                encryptedBatch.path,
                digest,
                encryptedBatch.firstSeq,
                encryptedBatch.lastSeq,
                encryptedBatch.actorId,
                encryptedBatch.epoch,
            ),
        )
        val head = ManifestHead(
            1, snapshot.spaceId, snapshot.generation, encryptedBatch.actorId, encryptedBatch.epoch,
            indexPath, encryptedBatch.batchId, digest, encryptedBatch.lastSeq,
        )
        return SyncPreparedUpload(
            snapshot.repository,
            snapshot.head,
            encryptedBatch,
            previous?.indexPath,
            previousSeq,
            protect(
                encodeManifestShard(shard).encodeToByteArray(),
                encryptedBatch.binding().copy(path = indexPath),
            ),
            protect(
                encodeManifestHead(head).encodeToByteArray(),
                SyncCryptoBinding(
                    1,
                    snapshot.spaceId,
                    snapshot.generation,
                    "head-${encryptedBatch.actorId}-${encryptedBatch.epoch}",
                    headPath,
                ),
            ),
        )
    }

    override suspend fun publish(
        repository: SyncRepository,
        snapshot: SyncSnapshot,
        upload: SyncPreparedUpload,
        observeSnapshot: suspend (SyncSnapshot) -> Unit,
    ): SyncPublishResult {
        val batch = upload.encryptedBatch
        require(repository == snapshot.repository && repository == upload.repository) {
            "sync upload repository mismatch"
        }
        require(batch.spaceId == snapshot.spaceId && batch.generation == snapshot.generation) {
            "sync upload scope mismatch"
        }
        validatePrepared(upload)
        if (spaceMaterial != null) {
            val private = try {
                getRepositoryInfo(repository).private
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                return SyncPublishResult(
                    SyncPublishStatus.FAILED,
                    batch.batchId,
                    error = "private repository access could not be verified",
                    failureClass = publishFailureClass(error),
                    retryAfterMillis = (error as? SyncHttpException)?.retryAfterMillis,
                    networkPhase = (error as? SyncHttpException)?.networkPhase,
                    httpStatus = (error as? SyncHttpException)?.code,
                )
            }
            if (!private) {
                return SyncPublishResult(
                    SyncPublishStatus.FAILED,
                    batch.batchId,
                    error = "private repository access could not be verified",
                    failureClass = SyncPublishFailureClass.AUTHORIZATION,
                )
            }
        }
        var current = snapshot
        var blobs: List<SyncGitTreeEntry>? = null
        for (attempt in 1..MAX_PUBLISH_ATTEMPTS) {
            val existing = current.batches.firstOrNull { it.batchId == batch.batchId }
            if (existing != null) {
                require(matches(current, upload)) { "sync batch id already contains different bytes" }
                // A caller may pass a historical snapshot after a restart. Only the live ref can confirm delivery.
                val observed = readSnapshot(repository, snapshot.spaceId, snapshot.generation).getOrNull()
                if (observed != null) observeSnapshot(observed)
                if (observed == null || !suspendResult { matches(observed, upload) }.getOrDefault(false)) {
                    return SyncPublishResult(
                        SyncPublishStatus.UNCONFIRMED,
                        batch.batchId,
                        error = "current ref could not confirm the saved upload",
                        attempts = attempt - 1,
                    )
                }
                return SyncPublishResult(
                    SyncPublishStatus.PUBLISHED,
                    batch.batchId,
                    observed.head,
                    attempts = attempt - 1,
                    confirmedSnapshot = observed,
                )
            }
            val previous = current.batches.filter { it.actorId == batch.actorId && it.epoch == batch.epoch }
                .maxByOrNull { it.lastSeq }
            if (previous?.indexPath != upload.previousIndexPath || (previous?.lastSeq ?: 0) != upload.previousLastSeq) {
                return SyncPublishResult(
                    SyncPublishStatus.CONFLICT,
                    batch.batchId,
                    error = "sync actor advanced; saved upload retained",
                    attempts = attempt - 1,
                    failureClass = SyncPublishFailureClass.CONFLICT,
                )
            }
            if (attempt > 1) runCatching { batchTransferResumed(batch.batchId) }
            val commit = try {
                val payloads = listOf(
                    batch.path to StoredSyncBatch.fromDomain(batch).body(),
                    upload.indexPath to upload.indexCiphertext.bytes,
                    upload.headPath to upload.headCiphertext.bytes,
                )
                val entries = blobs ?: SyncTreeEntryPayloadPlanner.plan(
                    payloads,
                    baseTreeSha = current.tree.sha,
                ).map { planned ->
                    val sha = planned.inlineContent?.let { "" }
                        ?: createBlob(repository, planned.bytes, "${batch.batchId}:blob:${planned.path}").sha
                    SyncGitTreeEntry(
                        planned.path,
                        "100644",
                        "blob",
                        sha,
                        planned.bytes.size.toLong(),
                        planned.inlineContent,
                    )
                }.also { blobs = it }
                val tree = createTree(
                    repository,
                    current.tree.sha,
                    entries,
                    "${batch.batchId}:tree:${current.tree.sha}",
                )
                createCommit(repository, tree.sha, current.head, "${batch.batchId}:commit:${tree.sha}:${current.head}")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                return SyncPublishResult(
                    SyncPublishStatus.FAILED,
                    batch.batchId,
                    error = "upload objects could not be created",
                    attempts = attempt,
                    failureClass = publishFailureClass(error),
                    retryAfterMillis = (error as? SyncHttpException)?.retryAfterMillis,
                    networkPhase = (error as? SyncHttpException)?.networkPhase,
                    httpStatus = (error as? SyncHttpException)?.code,
                )
            }
            var refError: Exception? = null
            runCatching { batchObjectsUploaded(batch.batchId) }
            try {
                updateRef(repository, commit.sha, "${batch.batchId}:ref:${commit.sha}")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                refError = error
            }
            val observed = readSnapshot(repository, snapshot.spaceId, snapshot.generation).getOrNull()
            if (observed != null) observeSnapshot(observed)
            if (observed != null && suspendResult { matches(observed, upload) }.getOrDefault(false)) {
                return SyncPublishResult(
                    SyncPublishStatus.PUBLISHED,
                    batch.batchId,
                    observed.head,
                    attempts = attempt,
                    confirmedSnapshot = observed,
                )
            }
            if (refError is SyncHttpException && refError.code in listOf(409, 422)) {
                if (observed == null || observed.head == current.head || attempt == MAX_PUBLISH_ATTEMPTS) {
                    return SyncPublishResult(
                        SyncPublishStatus.CONFLICT,
                        batch.batchId,
                        error = "ref update could not be safely retried",
                        attempts = attempt,
                        failureClass = SyncPublishFailureClass.CONFLICT,
                    )
                }
                current = observed
                kotlinx.coroutines.delay(100L * attempt)
                continue
            }
            return SyncPublishResult(
                SyncPublishStatus.UNCONFIRMED,
                batch.batchId,
                commit.sha,
                error = "publish response was not confirmed",
                attempts = attempt,
                failureClass = publishFailureClass(refError),
                retryAfterMillis = (refError as? SyncHttpException)?.retryAfterMillis,
                networkPhase = (refError as? SyncHttpException)?.networkPhase,
                httpStatus = (refError as? SyncHttpException)?.code,
            )
        }
        error("unreachable publish loop")
    }

    private suspend fun matches(snapshot: SyncSnapshot, upload: SyncPreparedUpload): Boolean {
        val entry = snapshot.batches.firstOrNull { it.batchId == upload.encryptedBatch.batchId } ?: return false
        val indexDigest = indexEngine.sha256(upload.indexCiphertext.bytes).toByteString().hex()
        return entry.indexCiphertextDigestHex == indexDigest &&
            readEncryptedBatch(snapshot, entry) == upload.encryptedBatch
    }

    private fun validatePrepared(upload: SyncPreparedUpload) {
        val batch = upload.encryptedBatch
        val secret = spaceMaterial?.secret ?: indexSecret
        require(spaceMaterial != null || secret != null) { "sync index key is unavailable" }
        SyncBatchEncryption.decrypt(indexEngine, secret, batch, spaceMaterial)
        val shard = decryptShard(upload.indexCiphertext.bytes, upload.indexPath, batch.spaceId, batch.generation)
        val head = decryptHead(upload.headCiphertext.bytes, upload.headPath, batch.spaceId, batch.generation)
        require(
            shard.previousPath == upload.previousIndexPath && upload.previousLastSeq < Long.MAX_VALUE &&
                upload.previousLastSeq + 1 == batch.firstSeq,
        ) { "saved upload predecessor is invalid" }
        val expected = ManifestBatch(
            batch.batchId,
            batch.path,
            batch.plaintextDigest.toByteString().hex(),
            batch.firstSeq,
            batch.lastSeq,
            batch.actorId,
            batch.epoch,
        )
        require(
            shard.batch == expected && head.batchId == batch.batchId && head.lastSeq == batch.lastSeq &&
                head.digestHex == expected.digestHex,
        ) { "saved upload manifest does not match its batch" }
    }

    override suspend fun readBatch(
        snapshot: SyncSnapshot,
        entry: SyncBatchIndexEntry,
    ): Result<SyncEncryptedBatch> = try {
        Result.success(readEncryptedBatch(snapshot, entry))
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Result.failure(error)
    }

    override suspend fun initialize(
        repository: SyncRepository,
        spaceId: String,
        generation: Long,
    ): SyncInitializationResult {
        if (spaceMaterial == null) return initializeLegacy(repository, spaceId, generation)
        val identity = try {
            getRepositoryIdentity(repository)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return SyncInitializationResult.Failed("repository lookup failed")
        }
        val nonce = ByteArray(32).also { Random.Default.nextBytes(it) }.toByteString().hex()
        return initialize(
            repository,
            spaceId,
            generation,
            SyncInitializationIntent(
                accountId = identity.ownerId,
                repositoryId = identity.id,
                defaultBranch = identity.defaultBranch,
                attemptNonce = nonce,
                stage = SyncInitializationStage.VERIFIED_EMPTY,
            ),
        ) {}
    }

    override suspend fun initialize(
        repository: SyncRepository,
        spaceId: String,
        generation: Long,
        intent: SyncInitializationIntent,
        saveCheckpoint: suspend (SyncInitializationCheckpoint) -> Unit,
    ): SyncInitializationResult = try {
        initializeAttempt(repository, spaceId, generation, intent, saveCheckpoint)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        SyncFailureDiagnostics.record(SyncFailurePhase.RESUME_INITIALIZE, error, stage = intent.stage)
        SyncInitializationResult.Failed("sync initialization could not be confirmed")
    }

    private suspend fun initializeLegacy(
        repository: SyncRepository,
        spaceId: String,
        generation: Long,
    ): SyncInitializationResult {
        val info = try {
            getRepositoryInfo(repository)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return SyncInitializationResult.Failed("repository lookup failed")
        }
        if (!info.private) return SyncInitializationResult.Failed("sync repository must be private")
        val existing = try {
            getRef(repository)
        } catch (error: SyncHttpException) {
            if (error.code != 404) return SyncInitializationResult.Failed("branch lookup failed")
            null
        }
        if (existing != null) {
            return suspendResult { readSnapshot(repository, spaceId, generation).getOrThrow() }
                .fold(
                    onSuccess = { SyncInitializationResult.Adopted(it.head, it.spaceId) },
                    onFailure = { error ->
                        if (error.message == "sync index is missing" ||
                            error.message == "sync space descriptor is missing"
                        ) {
                            initializeOnHead(repository, existing, spaceId, generation)
                        } else {
                            SyncInitializationResult.Failed("existing sync space requires explicit import")
                        }
                    },
                )
        }
        return try {
            val defaultRef = try {
                getRef(repository, info.defaultBranch)
            } catch (error: SyncHttpException) {
                if (error.code != 404) {
                    return SyncInitializationResult.Failed("default branch lookup failed")
                }
                null
            }
            val sourceRef = defaultRef ?: return SyncInitializationResult.NeedsExplicitAction(
                "legacy sync requires an existing default branch",
            )
            val targetRef = try {
                createRef(repository, sourceRef.objectSha)
                getRef(repository)
            } catch (error: SyncHttpException) {
                if (error.code != 422) return SyncInitializationResult.Failed("sync branch creation failed")
                val winner = getRef(repository)
                suspendResult { readSnapshot(repository, spaceId, generation).getOrThrow() }.fold(
                    onSuccess = { return SyncInitializationResult.Adopted(it.head, it.spaceId) },
                    onFailure = {
                        return SyncInitializationResult.NeedsExplicitAction(
                            "sync branch already exists; import matching recovery material",
                        )
                    },
                )
                winner
            }
            initializeOnHead(repository, targetRef, spaceId, generation)
        } catch (error: SyncHttpException) {
            SyncInitializationResult.Failed(error.message ?: "initialization failed")
        } catch (error: CancellationException) {
            throw error
        }
    }

    private suspend fun initializeAttempt(
        repository: SyncRepository,
        spaceId: String,
        generation: Long,
        intent: SyncInitializationIntent,
        saveCheckpoint: suspend (SyncInitializationCheckpoint) -> Unit,
    ): SyncInitializationResult {
        val material = spaceMaterial ?: return SyncInitializationResult.Failed("space material is unavailable")
        if (material.descriptor.spaceId != spaceId || material.descriptor.generation != generation) {
            return SyncInitializationResult.NeedsExplicitAction("initialization space identity does not match")
        }
        if (intent.accountId <= 0 || intent.repositoryId <= 0 ||
            !intent.attemptNonce.matches(Regex("[A-Za-z0-9_-]{16,128}"))
        ) {
            return SyncInitializationResult.NeedsExplicitAction("initialization attempt identity is invalid")
        }

        val identity = getRepositoryIdentity(repository)
        if (identity.id != intent.repositoryId || identity.ownerId != intent.accountId ||
            !identity.owner.equals(repository.owner, ignoreCase = true) || identity.name != repository.name
        ) {
            return SyncInitializationResult.NeedsExplicitAction("repository identity changed")
        }
        if (!identity.private) return SyncInitializationResult.Failed("sync repository must be private")
        if (identity.archived ||
            identity.disabled
        ) {
            return SyncInitializationResult.Failed("sync repository is unavailable")
        }
        if (!identity.writable) return SyncInitializationResult.Failed("sync repository is not writable")
        if (identity.defaultBranch.isBlank() || identity.defaultBranch != intent.defaultBranch ||
            identity.defaultBranch == repository.branch
        ) {
            return SyncInitializationResult.NeedsExplicitAction("repository default branch changed")
        }
        if (intent.stage == SyncInitializationStage.VERIFIED_EMPTY && identity.size != 0L) {
            return SyncInitializationResult.NeedsExplicitAction("repository is not reported empty")
        }
        val attemptBootstrap = encodeAttemptBootstrap(material, intent)
        var stage = intent.stage
        val confirmed = if (stage == SyncInitializationStage.VERIFIED_EMPTY ||
            stage == SyncInitializationStage.BOOTSTRAP_SUBMITTING
        ) {
            if (stage == SyncInitializationStage.VERIFIED_EMPTY) {
                if (!verifyEmptyRepository(repository, identity)) {
                    return SyncInitializationResult.NeedsExplicitAction(
                        "repository is no longer verified empty",
                    )
                }
                saveCheckpoint(SyncInitializationCheckpoint(SyncInitializationStage.BOOTSTRAP_SUBMITTING))
                stage = SyncInitializationStage.BOOTSTRAP_SUBMITTING
                try {
                    putAttemptBootstrap(repository, identity.defaultBranch, attemptBootstrap)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // Contents responses can be lost after the commit is reachable; read back before retrying.
                }
            }
            var value = readAttemptBootstrap(repository, identity.defaultBranch, attemptBootstrap)
            if (value == null && verifyEmptyRepository(repository, identity)) {
                try {
                    putAttemptBootstrap(repository, identity.defaultBranch, attemptBootstrap)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // A second read determines whether a create-only retry committed despite its response.
                }
                value = readAttemptBootstrap(repository, identity.defaultBranch, attemptBootstrap)
            }
            if (value == null) {
                return SyncInitializationResult.NeedsExplicitAction(
                    "bootstrap ownership could not be confirmed for this attempt",
                )
            }
            saveCheckpoint(
                SyncInitializationCheckpoint(
                    SyncInitializationStage.BOOTSTRAP_CONFIRMED,
                    value.sha,
                    value.treeSha,
                ),
            )
            stage = SyncInitializationStage.BOOTSTRAP_CONFIRMED
            value
        } else {
            val expectedCommit = intent.bootstrapCommitSha
                ?: return SyncInitializationResult.NeedsExplicitAction("confirmed bootstrap commit is missing")
            val expectedTree = intent.bootstrapTreeSha
                ?: return SyncInitializationResult.NeedsExplicitAction("confirmed bootstrap tree is missing")
            if (!isGitSha(expectedCommit) || !isGitSha(expectedTree)) {
                return SyncInitializationResult.NeedsExplicitAction("confirmed bootstrap identity is malformed")
            }
            val value = readAttemptBootstrap(repository, identity.defaultBranch, attemptBootstrap)
                ?: return SyncInitializationResult.NeedsExplicitAction("confirmed bootstrap changed")
            if (value.sha != expectedCommit || value.treeSha != expectedTree) {
                return SyncInitializationResult.NeedsExplicitAction("confirmed bootstrap changed")
            }
            value
        }

        if (stage.ordinal < SyncInitializationStage.SPACE_PUBLISHING.ordinal) {
            saveCheckpoint(
                SyncInitializationCheckpoint(
                    SyncInitializationStage.SPACE_PUBLISHING,
                    confirmed.sha,
                    confirmed.treeSha,
                ),
            )
            stage = SyncInitializationStage.SPACE_PUBLISHING
        }

        val target = readRefOrNull(repository, repository.branch)
        val syncRef = if (target == null) {
            val publication = initializeOnHead(
                repository,
                SyncGitRef("refs/heads/${identity.defaultBranch}", confirmed.sha),
                spaceId,
                generation,
                attemptBootstrap,
                refPublisher = { commit -> createRef(repository, commit) },
            )
            val readback = readRefOrNull(repository, repository.branch) ?: return if (
                publication is SyncInitializationResult.Initialized || publication is SyncInitializationResult.Adopted
            ) {
                SyncInitializationResult.Failed("sync branch creation could not be confirmed")
            } else {
                publication
            }
            if (readback.objectSha == confirmed.sha) {
                val recovery = initializeOnHead(
                    repository,
                    readback,
                    spaceId,
                    generation,
                    attemptBootstrap,
                )
                if (recovery !is SyncInitializationResult.Initialized &&
                    recovery !is SyncInitializationResult.Adopted
                ) {
                    return recovery
                }
                readRefOrNull(repository, repository.branch)
                    ?: return SyncInitializationResult.Failed("sync branch creation could not be confirmed")
            } else {
                readback
            }
        } else {
            target
        }

        if (syncRef.objectSha == confirmed.sha) {
            val result = initializeOnHead(
                repository,
                syncRef,
                spaceId,
                generation,
                attemptBootstrap,
            )
            if (result !is SyncInitializationResult.Initialized && result !is SyncInitializationResult.Adopted) {
                return result
            }
        }

        val snapshot = readSnapshot(repository, spaceId, generation).getOrNull()
            ?: return SyncInitializationResult.NeedsExplicitAction(
                "sync branch does not contain the current attempt's initialized space",
            )
        if (!containsAttemptBootstrap(repository, snapshot, attemptBootstrap) ||
            !hasAncestor(repository, snapshot.head, confirmed.sha)
        ) {
            return SyncInitializationResult.NeedsExplicitAction(
                "sync branch is not based on the confirmed bootstrap commit",
            )
        }
        if (stage.ordinal < SyncInitializationStage.SPACE_CONFIRMED.ordinal) {
            saveCheckpoint(
                SyncInitializationCheckpoint(
                    SyncInitializationStage.SPACE_CONFIRMED,
                    confirmed.sha,
                    confirmed.treeSha,
                ),
            )
        }
        return if (target == null || syncRef.objectSha == confirmed.sha) {
            SyncInitializationResult.Initialized(snapshot.head, snapshot.spaceId)
        } else {
            SyncInitializationResult.Adopted(snapshot.head, snapshot.spaceId)
        }
    }

    private data class RepositoryIdentity(
        val id: Long,
        val ownerId: Long,
        val owner: String,
        val name: String,
        val defaultBranch: String,
        val size: Long,
        val private: Boolean,
        val archived: Boolean,
        val disabled: Boolean,
        val writable: Boolean,
    )

    private suspend fun getRepositoryIdentity(repository: SyncRepository): RepositoryIdentity {
        val json = call(repository, "GET", "").requireSuccess().json()
        val owner = json.obj("owner")
        val permissions = json.obj("permissions")
        return RepositoryIdentity(
            id = json.long("id")?.takeIf { it > 0 } ?: error("repository id is invalid"),
            ownerId = owner.long("id")?.takeIf { it > 0 } ?: error("repository owner id is invalid"),
            owner = owner.string("login"),
            name = json.string("name"),
            defaultBranch = json.string("default_branch"),
            size = json.long("size")?.takeIf { it >= 0 } ?: error("repository size is invalid"),
            private = json.boolean("private"),
            archived = json.boolean("archived"),
            disabled = json.boolean("disabled"),
            writable = permissions.boolean("push") || permissions.boolean("admin"),
        ).also {
            require(owner.string("type") == "User") { "sync repository owner is not a user" }
            require(json.string("full_name").equals(repository.fullName, ignoreCase = true)) {
                "sync repository name changed"
            }
        }
    }

    private fun encodeAttemptBootstrap(
        material: SyncSpaceMaterial,
        intent: SyncInitializationIntent,
    ): ByteArray = buildJsonObject {
        put("protocolVersion", JsonPrimitive(1))
        put("accountId", JsonPrimitive(intent.accountId))
        put("repositoryId", JsonPrimitive(intent.repositoryId))
        put("attemptNonce", JsonPrimitive(intent.attemptNonce))
        put("spaceId", JsonPrimitive(material.descriptor.spaceId))
        put("generation", JsonPrimitive(material.descriptor.generation))
        put(
            "descriptorSha256",
            JsonPrimitive(
                indexEngine.sha256(SyncSpaceDescriptorCodec.encode(material.descriptor)).toByteString().hex(),
            ),
        )
    }.toString().encodeToByteArray()

    private suspend fun verifyEmptyRepository(repository: SyncRepository, identity: RepositoryIdentity): Boolean {
        val currentIdentity = getRepositoryIdentity(repository)
        if (currentIdentity != identity || currentIdentity.size != 0L) return false

        val refs = call(repository, "GET", "/git/matching-refs/?per_page=100")
        val noRefs = when {
            refs.code == 409 -> refs.hasExplicitEmptyRepositoryMessage()
            refs.code !in 200..299 -> false
            refs.headers["link"]?.contains("rel=\"next\"") == true -> false
            else -> runCatching { githubJson.parseToJsonElement(refs.body.decodeToString()).jsonArray.isEmpty() }
                .getOrDefault(false)
        }
        if (!noRefs) return false

        val defaultRef = call(repository, "GET", "/git/ref/heads/${identity.defaultBranch}")
        when (defaultRef.code) {
            200 -> return false
            404 -> Unit
            409 -> if (!defaultRef.hasExplicitEmptyRepositoryMessage()) return false
            else -> return false
        }

        val contents = call(repository, "GET", "/contents/?ref=${identity.defaultBranch}")
        return when (contents.code) {
            200 -> contents.headers["link"]?.contains("rel=\"next\"") != true &&
                runCatching { githubJson.parseToJsonElement(contents.body.decodeToString()).jsonArray.isEmpty() }
                    .getOrDefault(false)
            404 -> contents.hasExplicitEmptyRepositoryMessage()
            else -> false
        }
    }

    private suspend fun putAttemptBootstrap(repository: SyncRepository, branch: String, content: ByteArray) {
        val payload = buildJsonObject {
            put("message", JsonPrimitive("Initialize private Mihon sync"))
            put("content", JsonPrimitive(content.toByteString().base64()))
            put("branch", JsonPrimitive(branch))
        }
        call(repository, "PUT", "/contents/.mihon-sync/bootstrap", payload).requireSuccess()
    }

    private suspend fun readAttemptBootstrap(
        repository: SyncRepository,
        branch: String,
        content: ByteArray,
    ): SyncGitCommit? {
        val response = call(repository, "GET", "/git/ref/heads/$branch")
        if (response.code == 404) return null
        if (response.code == 409 && response.hasExplicitEmptyRepositoryMessage()) return null
        val ref = response.requireSuccess().json()
        if (ref.string("ref") != "refs/heads/$branch") return null
        val commit = getCommit(repository, ref.obj("object").string("sha"))
        val tree = getTree(repository, commit.treeSha)
        return commit.takeIf { isExactBootstrapTree(repository, tree, content) }
    }

    private suspend fun readRefOrNull(repository: SyncRepository, branch: String): SyncGitRef? {
        val response = call(repository, "GET", "/git/ref/heads/$branch")
        if (response.code == 404) return null
        val json = response.requireSuccess().json()
        val name = json.string("ref")
        if (name != "refs/heads/$branch") return null
        return SyncGitRef(name, json.obj("object").string("sha"))
    }

    private suspend fun containsAttemptBootstrap(
        repository: SyncRepository,
        snapshot: SyncSnapshot,
        expectedContent: ByteArray,
    ): Boolean {
        val entry = snapshot.tree.entries.singleOrNull { it.path == ".mihon-sync/bootstrap" } ?: return false
        if (entry.type != "blob" || entry.mode != "100644") return false
        return getBlob(repository, entry.sha).content.contentEquals(expectedContent)
    }

    private suspend fun hasAncestor(repository: SyncRepository, descendant: String, ancestor: String): Boolean {
        val pending = mutableListOf(descendant)
        val visited = mutableSetOf<String>()
        while (pending.isNotEmpty() && visited.size < MAX_INITIALIZATION_ANCESTRY) {
            val sha = pending.removeAt(pending.lastIndex)
            if (sha == ancestor) return true
            if (!isGitSha(sha) || !visited.add(sha)) continue
            getCommit(repository, sha).parents.forEach(pending::add)
        }
        return false
    }

    private fun isGitSha(value: String) = value.matches(Regex("[0-9a-f]{40,64}"))

    private suspend fun initializeOnHead(
        repository: SyncRepository,
        baseRef: SyncGitRef,
        spaceId: String,
        generation: Long,
        expectedBootstrap: ByteArray? = null,
        refPublisher: (suspend (String) -> Unit)? = null,
    ): SyncInitializationResult {
        return try {
            if (spaceMaterial == null &&
                indexSecret == null
            ) {
                return SyncInitializationResult.Failed("sync index key is unavailable")
            }
            spaceMaterial?.let {
                require(it.descriptor.spaceId == spaceId && it.descriptor.generation == generation) {
                    "initialization space identity mismatch"
                }
            }
            val baseCommit = getCommit(repository, baseRef.objectSha)
            val baseTree = getTree(repository, baseCommit.treeSha)
            require(!baseTree.truncated && baseTree.entries.size <= maxTreeEntries) {
                "initialization tree is incomplete"
            }
            if (spaceMaterial != null &&
                (expectedBootstrap == null || !isExactBootstrapTree(repository, baseTree, expectedBootstrap))
            ) {
                return SyncInitializationResult.NeedsExplicitAction("initial sync branch contains unrecognized data")
            }
            if (baseTree.entries.any {
                    it.path.startsWith(".mihon-sync/") && it.path != ".mihon-sync/bootstrap"
                }
            ) {
                return SyncInitializationResult.NeedsExplicitAction(
                    "existing sync artifacts require recovery; initialization made no changes",
                )
            }
            val indexPath = ".mihon-sync/index/bootstrap/0/bootstrap.bin"
            val shard = ManifestShard(1, spaceId, generation, "bootstrap", 0, null, null)
            val ciphertext = protect(
                encodeManifestShard(shard).encodeToByteArray(),
                SyncCryptoBinding(1, spaceId, generation, "bootstrap", indexPath),
            )
            val blob = createBlob(repository, ciphertext.bytes)
            val initialEntries = mutableListOf(SyncGitTreeEntry(indexPath, "100644", "blob", blob.sha))
            spaceMaterial?.let {
                val descriptorBlob = createBlob(repository, SyncSpaceDescriptorCodec.encode(it.descriptor))
                initialEntries += SyncGitTreeEntry(SyncSpaceDescriptorCodec.PATH, "100644", "blob", descriptorBlob.sha)
            }
            val tree =
                createTree(
                    repository,
                    baseCommit.treeSha,
                    initialEntries,
                )
            val commit = createCommit(repository, tree.sha, baseRef.objectSha)
            var refError: Exception? = null
            try {
                if (refPublisher == null) {
                    updateRef(repository, commit.sha)
                } else {
                    refPublisher(commit.sha)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                refError = error
            }
            readSnapshot(repository, spaceId, generation).fold(
                onSuccess = {
                    if (it.head == commit.sha) {
                        SyncInitializationResult.Initialized(it.head, it.spaceId)
                    } else {
                        SyncInitializationResult.Adopted(it.head, it.spaceId)
                    }
                },
                onFailure = {
                    if (refError is SyncHttpException && refError.code in listOf(409, 422)) {
                        SyncInitializationResult.NeedsExplicitAction(
                            "sync space already exists; import matching recovery material",
                        )
                    } else {
                        SyncInitializationResult.Failed("initialization ref update failed")
                    }
                },
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: SyncHttpException) {
            SyncInitializationResult.Failed(error.message ?: "initialization failed")
        } catch (_: Exception) {
            SyncInitializationResult.Failed("initialization failed")
        }
    }

    /** Require the exact attempt-owned bootstrap and no other content in the initial tree. */
    private suspend fun isExactBootstrapTree(
        repository: SyncRepository,
        tree: SyncGitTree,
        expectedBootstrap: ByteArray,
    ): Boolean {
        if (tree.truncated || tree.entries.size > 2 || tree.entries.map { it.path }.toSet().size != tree.entries.size) {
            return false
        }
        val bootstrap = tree.entries.singleOrNull { it.path == ".mihon-sync/bootstrap" } ?: return false
        if (bootstrap.type != "blob" || bootstrap.mode != "100644") return false
        if (tree.entries.any {
                it != bootstrap && !(it.path == ".mihon-sync" && it.type == "tree" && it.mode == "040000")
            }
        ) {
            return false
        }
        return getBlob(repository, bootstrap.sha).content.contentEquals(expectedBootstrap)
    }

    private suspend fun getRef(repository: SyncRepository, branch: String = repository.branch): SyncGitRef {
        val response = call(repository, "GET", "/git/ref/heads/$branch")
        return response.requireSuccess().json().let { SyncGitRef(it.string("ref"), it.obj("object").string("sha")) }
    }

    private suspend fun getCommit(repository: SyncRepository, sha: String): SyncGitCommit {
        val response = call(repository, "GET", "/git/commits/$sha")
        val json = response.requireSuccess().json()
        return SyncGitCommit(
            sha,
            json.obj("tree").string("sha"),
            json.array("parents").map { it.jsonObject.string("sha") },
        )
    }

    /** Recursive GitHub response is only a cold hint until every direct tree object hashes to its declared OID. */
    private suspend fun verifiedRecursiveTree(repository: SyncRepository, rootSha: String): SyncGitTree? {
        val response = try {
            call(repository, "GET", "/git/trees/$rootSha?recursive=1").requireSuccess()
        } catch (failure: SyncHttpException) {
            if (failure.code == 200 && failure.message == "sync response exceeds limit") return null
            throw failure
        }
        val json = response.json()
        require(json.string("sha") == rootSha) { "recursive sync tree id does not match requested object" }
        if (json.boolean("truncated")) return null
        val oidLength = rootSha.length
        require(oidLength == 40 || oidLength == 64) { "sync tree object id has invalid length" }
        val objects = linkedMapOf<String, SyncGitTreeEntry>()
        val oidPattern = if (oidLength == 40) Regex("[0-9a-f]{40}") else Regex("[0-9a-f]{64}")
        var blobs = 0
        var directories = 1L
        json.array("tree").forEach { value ->
            val item = value.jsonObject
            val path = item.string("path")
            val parts = path.split('/')
            require(
                path.isNotEmpty() && '\\' !in path && '\u0000' !in path &&
                    path.encodeToByteArray().decodeToString() == path &&
                    parts.all { it.isNotEmpty() && it != "." && it != ".." },
            ) { "recursive sync tree path is invalid" }
            val type = item.string("type")
            val mode = item.string("mode")
            require(parts.size <= if (type == "tree") 256 else 257) { "recursive sync tree depth exceeds limit" }
            require((type == "tree" && mode == "040000") || (type == "blob" && mode == "100644")) {
                "recursive sync tree entry type or mode is invalid"
            }
            val entrySha = item.string("sha")
            require(oidPattern.matches(entrySha)) { "recursive sync tree object id is invalid" }
            require(path !in objects) { "recursive sync tree contains duplicate paths" }
            objects[path] = SyncGitTreeEntry(path, mode, type, entrySha, item.long("size"))
            if (type == "blob") blobs++
            if (type == "tree") directories++
            require(blobs <= maxTreeEntries) { "recursive sync tree exceeds file limit" }
            require(directories <= maxTreeEntries.toLong() * 4 + 256) {
                "recursive sync tree directory path budget exceeded"
            }
        }
        objects.forEach { (path, entry) ->
            val parent = path.substringBeforeLast('/', "")
            if (parent.isNotEmpty()) {
                require(objects[parent]?.type == "tree") { "recursive sync tree parent is missing" }
            }
            if (entry.type == "tree") {
                var ancestor = parent
                while (ancestor.isNotEmpty()) {
                    require(objects[ancestor]?.sha != entry.sha) { "recursive sync tree contains a cycle" }
                    ancestor = ancestor.substringBeforeLast('/', "")
                }
                require(entry.sha != rootSha) { "recursive sync tree contains a cycle" }
            }
        }
        val children = objects.values.groupBy { it.path.substringBeforeLast('/', "") }
        require(children.values.all { it.size <= maxTreeEntries }) { "recursive sync tree directory exceeds limit" }
        val directoryPaths = objects.values.filter { it.type == "tree" }.map { it.path }
            .sortedByDescending { it.count { character -> character == '/' } } + ""
        val resolved = mutableMapOf<String, String>()
        directoryPaths.forEach { directory ->
            val direct = children[directory].orEmpty().map { entry ->
                val name = entry.path.substringAfterLast('/')
                entry.copy(
                    path = name,
                    sha = if (entry.type ==
                        "tree"
                    ) {
                        requireNotNull(resolved[entry.path])
                    } else {
                        entry.sha
                    },
                )
            }
            val actual = requireNotNull(gitTreeOid(direct, oidLength))
            val declared = if (directory.isEmpty()) rootSha else requireNotNull(objects[directory]).sha
            require(actual == declared) { "recursive sync tree object hash does not match declared id" }
            resolved[directory] = actual
        }
        return SyncGitTree(rootSha, objects.values.filter { it.type == "blob" }, truncated = false)
    }

    private suspend fun getTree(
        repository: SyncRepository,
        sha: String,
        validationScope: String = "unbound",
        allowVerifiedRecursive: Boolean = false,
    ): SyncGitTree {
        if (allowVerifiedRecursive) {
            verifiedRecursiveTree(repository, sha)?.let { return it }
        }
        val activeTrees = mutableSetOf<String>()
        val visitedTrees = linkedMapOf<SyncTreeCacheKey, SyncPersistentGitObjectKey?>()
        val flattened = mutableListOf<SyncGitTreeEntry>()
        val directoryPathBudget = maxTreeEntries.toLong() * 4 + 256
        var directoryPathVisits = 0L
        var truncated = false

        suspend fun appendSubtree(treeSha: String, depth: Int, prefix: String) {
            require(depth <= 256) { "sync tree depth exceeds limit" }
            directoryPathVisits++
            require(directoryPathVisits <= directoryPathBudget) { "sync tree directory path budget exceeded" }
            require(activeTrees.add(treeSha)) { "sync tree contains a cycle" }
            val key = SyncTreeCacheKey(
                apiOrigin,
                repository.fullName,
                repository.branch,
                treeSha,
                validationScope,
                connectionRevision,
            )
            val persistentKey = persistentGitObjectKey(
                repository = repository,
                kind = SyncGitObjectKind.TREE,
                objectOid = treeSha,
                validationScope = validationScope,
            )
            visitedTrees[key] = persistentKey
            try {
                val direct = getCachedTree(key) ?: treeLoads.getOrLoad(key) {
                    getCachedTree(key) ?: persistentKey?.let { loadPersistentTree(it, treeSha) }?.also {
                        putCachedTree(key, it)
                    } ?: run {
                        val response = call(repository, "GET", "/git/trees/$treeSha").requireSuccess()
                        val raw = response.body
                        val parsed = parseTree(treeSha, raw)
                        if (!parsed.truncated) {
                            persistentKey?.let { persistentObjectCache.write(it, raw) }
                            putCachedTree(key, parsed)
                        }
                        parsed
                    }
                }
                if (direct.truncated) {
                    truncated = true
                    return
                }
                for (entry in direct.entries) {
                    if (flattened.size > maxTreeEntries) {
                        truncated = true
                        break
                    }
                    val path = if (prefix.isEmpty()) entry.path else "$prefix/${entry.path}"
                    if (entry.type == "tree") {
                        appendSubtree(entry.sha, depth + 1, path)
                        if (truncated) break
                    } else {
                        flattened += if (prefix.isEmpty()) entry else materializeTreePath(entry, path)
                    }
                }
            } finally {
                activeTrees.remove(treeSha)
            }
        }

        try {
            appendSubtree(sha, 0, "")
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                for ((key, persistentKey) in visitedTrees) {
                    removeCachedTree(key)
                    persistentKey?.let { persistentObjectCache.remove(it) }
                }
            }
            throw error
        }
        return SyncGitTree(sha, flattened.take(maxTreeEntries + 1), truncated || flattened.size > maxTreeEntries)
    }

    private suspend fun removeCachedTree(key: SyncTreeCacheKey) = treeCacheMutex.withLock {
        treeCache.remove(key)?.let { removed -> treeCacheWeightBytes -= treeCacheWeight(key, removed) }
        treeCacheOrder.remove(key)
    }

    private fun materializeTreePath(entry: SyncGitTreeEntry, path: String): SyncGitTreeEntry {
        treePathMaterializations++
        return entry.copy(path = path)
    }

    private suspend fun getCachedTree(key: SyncTreeCacheKey): CachedRawTree? = treeCacheMutex.withLock {
        treeCache[key]?.also {
            treeCacheOrder.remove(key)
            treeCacheOrder += key
        }
    }

    private suspend fun loadPersistentTree(
        key: SyncPersistentGitObjectKey,
        requestedSha: String,
    ): CachedRawTree? {
        val raw = persistentObjectCache.read(key) ?: return null
        val parsed = runCatching { parseTree(requestedSha, raw) }.getOrNull()
        if (parsed == null || parsed.truncated) {
            persistentObjectCache.remove(key)
            return null
        }
        return parsed
    }

    private fun parseTree(requestedSha: String, raw: ByteArray): CachedRawTree {
        val json = githubJson.parseToJsonElement(raw.decodeToString()).jsonObject
        require(json.string("sha") == requestedSha) { "sync tree id does not match requested object" }
        var truncated = json.boolean("truncated")
        val entries = mutableListOf<SyncGitTreeEntry>()
        val paths = mutableSetOf<String>()
        json.array("tree").forEach { item ->
            if (entries.size > maxTreeEntries) {
                truncated = true
                return@forEach
            }
            val obj = item.jsonObject
            val type = obj.string("type")
            val path = obj.string("path")
            require(type == "tree" || type == "blob") { "sync tree entry type is invalid" }
            require(
                path.isNotEmpty() && !path.startsWith("/") && '/' !in path && '\\' !in path &&
                    path.split('/').none { it.isEmpty() || it == "." || it == ".." },
            ) { "sync tree entry path is invalid" }
            require(paths.add(path)) { "sync tree contains duplicate paths" }
            val mode = obj.string("mode")
            require(if (type == "tree") mode == "040000" else mode == "100644") {
                "sync tree entry mode is invalid"
            }
            entries += SyncGitTreeEntry(path, mode, type, obj.string("sha"), obj.long("size"))
        }
        return CachedRawTree(entries, truncated || entries.size > maxTreeEntries)
    }

    private suspend fun putCachedTree(key: SyncTreeCacheKey, tree: CachedRawTree) {
        val weight = treeCacheWeight(key, tree)
        if (weight > maxTreeCacheWeightBytes) return
        treeCacheMutex.withLock {
            treeCache.remove(key)?.let { previous -> treeCacheWeightBytes -= treeCacheWeight(key, previous) }
            treeCacheOrder.remove(key)
            treeCache[key] = tree
            treeCacheOrder += key
            treeCacheWeightBytes += weight
            while (treeCacheWeightBytes > maxTreeCacheWeightBytes && treeCacheOrder.isNotEmpty()) {
                val oldest = treeCacheOrder.removeAt(0)
                treeCache.remove(oldest)?.let { removed -> treeCacheWeightBytes -= treeCacheWeight(oldest, removed) }
            }
        }
    }

    private fun treeCacheWeight(key: SyncTreeCacheKey, tree: CachedRawTree): Long =
        128L + key.apiOrigin.byteSize() + key.repository.byteSize() + key.branch.byteSize() +
            key.treeOid.byteSize() + key.validationScope.byteSize() + key.connectionRevision.byteSize() +
            tree.entries.sumOf { entry ->
                64L + entry.path.byteSize() + entry.mode.byteSize() + entry.type.byteSize() + entry.sha.byteSize() +
                    (entry.size?.toString()?.byteSize() ?: 0)
            }

    private fun String.byteSize(): Int = encodeToByteArray().size

    private suspend fun getBlob(
        repository: SyncRepository,
        sha: String,
        cache: Boolean = true,
        validationScope: String = "unbound",
        bodyWorkKey: String? = null,
    ): SyncGitBlob {
        val cacheKey = SyncBlobCacheKey(
            apiOrigin = apiBaseUrl,
            repository = repository.fullName,
            branch = repository.branch,
            objectFormat = "git-sha-${sha.length}",
            objectOid = sha,
            validationScope = validationScope,
            connectionRevision = connectionRevision,
        )
        val persistentKey = persistentGitObjectKey(
            repository = repository,
            kind = SyncGitObjectKind.BLOB,
            objectOid = sha,
            validationScope = validationScope,
        )
        suspend fun load(): ByteArray {
            val response = call(
                repository,
                "GET",
                "/git/blobs/$sha",
                headers = mapOf("Accept" to "application/vnd.github.raw+json"),
                bodyWorkKey = bodyWorkKey ?: "blob:$sha",
            ).requireSuccess()
            val jsonEnvelope = runCatching { response.json() }.getOrNull()
                ?.takeIf { it["sha"] != null && it["encoding"] != null && it["content"] != null }
            val content = if (jsonEnvelope != null) {
                val json = jsonEnvelope
                require(json.string("sha") == sha) { "sync blob id does not match requested object" }
                require(json.string("encoding") == "base64") { "sync blob encoding is invalid" }
                json.string("content").replace("\n", "").decodeBase64()?.toByteArray()
                    ?: throw IllegalStateException("sync blob content is invalid")
            } else {
                response.body
            }
            require(content.size <= 2 * 1024 * 1024) { "sync blob exceeds limit" }
            require(gitBlobOid(content, sha.length) == sha) {
                "sync blob content does not match its Git object id"
            }
            return content
        }
        suspend fun loadWithPersistentCache(): ByteArray {
            persistentKey?.let { key ->
                persistentObjectCache.read(key)?.let { cached ->
                    if (cached.size <= 2 * 1024 * 1024 && gitBlobOid(cached, sha.length) == sha) return cached
                    persistentObjectCache.remove(key)
                }
            }
            val content = load()
            persistentKey?.let { persistentObjectCache.write(it, content) }
            return content
        }
        val content = if (cache) blobCache.getOrLoad(cacheKey, ::loadWithPersistentCache) else load()
        return SyncGitBlob(sha, content)
    }

    private fun persistentGitObjectKey(
        repository: SyncRepository,
        kind: SyncGitObjectKind,
        objectOid: String,
        validationScope: String,
    ): SyncPersistentGitObjectKey? = stableRepositoryId?.let { repositoryId ->
        SyncPersistentGitObjectKey(
            apiOrigin = apiOrigin,
            repositoryId = repositoryId.toString(),
            repository = repository.fullName,
            branch = repository.branch,
            kind = kind,
            objectFormat = "git-sha-${objectOid.length}",
            objectOid = objectOid,
            validationScope = validationScope,
            connectionRevision = connectionRevision,
        )
    }

    private suspend fun createBlob(
        repository: SyncRepository,
        content: ByteArray,
        bodyWorkKey: String? = null,
    ): SyncGitBlob {
        val payload = buildJsonObject {
            put("content", JsonPrimitive(content.toByteString().base64()))
            put("encoding", JsonPrimitive("base64"))
        }
        val json = call(repository, "POST", "/git/blobs", payload, bodyWorkKey = bodyWorkKey).requireSuccess().json()
        val returnedSha = json.string("sha")
        require(gitBlobOid(content, returnedSha.length) == returnedSha) {
            "sync blob response does not match uploaded bytes"
        }
        return SyncGitBlob(returnedSha, content)
    }

    private suspend fun createTree(
        repository: SyncRepository,
        baseTree: String?,
        entries: List<SyncGitTreeEntry>,
        bodyWorkKey: String? = null,
    ): SyncGitTree {
        val payload = buildJsonObject {
            if (baseTree != null) put("base_tree", JsonPrimitive(baseTree))
            put(
                "tree",
                buildJsonArray {
                    entries.forEach { entry ->
                        add(
                            buildJsonObject {
                                put("path", JsonPrimitive(entry.path))
                                put("mode", JsonPrimitive(entry.mode))
                                put("type", JsonPrimitive(entry.type))
                                if (entry.content != null) {
                                    put("content", JsonPrimitive(entry.content))
                                } else {
                                    put("sha", JsonPrimitive(entry.sha))
                                }
                            },
                        )
                    }
                },
            )
        }
        val json = call(repository, "POST", "/git/trees", payload, bodyWorkKey = bodyWorkKey).requireSuccess().json()
        return SyncGitTree(json.string("sha"), emptyList(), false)
    }

    private suspend fun createCommit(
        repository: SyncRepository,
        tree: String,
        parent: String?,
        bodyWorkKey: String? = null,
    ): SyncGitCommit {
        val payload = buildJsonObject {
            put("message", JsonPrimitive("mihon sync batch"))
            put("tree", JsonPrimitive(tree))
            if (parent != null) put("parents", buildJsonArray { add(JsonPrimitive(parent)) })
        }
        val json = call(repository, "POST", "/git/commits", payload, bodyWorkKey = bodyWorkKey).requireSuccess().json()
        return SyncGitCommit(json.string("sha"), tree, listOfNotNull(parent))
    }

    private suspend fun updateRef(repository: SyncRepository, commit: String, bodyWorkKey: String? = null) {
        val payload = buildJsonObject {
            put("sha", JsonPrimitive(commit))
            put("force", JsonPrimitive(false))
        }
        call(repository, "PATCH", "/git/refs/heads/${repository.branch}", payload, bodyWorkKey = bodyWorkKey)
            .requireSuccess()
    }

    private suspend fun createRef(repository: SyncRepository, commit: String) {
        val payload = buildJsonObject {
            put("ref", JsonPrimitive("refs/heads/${repository.branch}"))
            put("sha", JsonPrimitive(commit))
        }
        call(repository, "POST", "/git/refs", payload).requireSuccess()
    }

    private data class RepositoryInfo(
        val defaultBranch: String,
        val private: Boolean,
    )

    private suspend fun getRepositoryInfo(repository: SyncRepository): RepositoryInfo {
        val json = call(repository, "GET", "").requireSuccess().json()
        return RepositoryInfo(
            json.string("default_branch"),
            json.boolean("private"),
        )
    }

    private suspend fun call(
        repository: SyncRepository,
        method: String,
        path: String,
        body: JsonObject? = null,
        headers: Map<String, String> = emptyMap(),
        bodyWorkKey: String? = null,
    ): SyncHttpResponse {
        val token = tokenProvider().takeIf { it.isNotBlank() } ?: throw IllegalStateException("authorization required")
        val requestBody = body?.let {
            githubJson.encodeToString(JsonObject.serializer(), it).toRequestBody(jsonMediaType)
        }
        val request = http.request(
            "$apiBaseUrl/repos/${repository.fullName}$path",
            method,
            headers = mapOf(
                "Accept" to "application/vnd.github+json",
                "Authorization" to "Bearer $token",
                "X-GitHub-Api-Version" to "2026-03-10",
            ) + headers,
            body = requestBody,
        )
        return http.execute(
            if (bodyWorkKey == null) {
                request
            } else {
                request.newBuilder()
                    .tag(SyncHttpBodyWork::class.java, SyncHttpBodyWork(bodyWorkKey)).build()
            },
        )
    }

    private fun SyncHttpResponse.requireSuccess(): SyncHttpResponse {
        if (code !in 200..299) {
            val message = runCatching { json().string("message") }.getOrNull()?.lowercase().orEmpty()
            val secondaryRateLimit = message.contains("secondary rate") || message.contains("abuse detection")
            val failureClass = when {
                code == 401 -> SyncHttpFailureClass.AUTHORIZATION
                code == 403 && (headers["x-ratelimit-remaining"] == "0" || secondaryRateLimit) ->
                    SyncHttpFailureClass.RATE_LIMITED
                code == 403 -> SyncHttpFailureClass.AUTHORIZATION
                code == 409 -> SyncHttpFailureClass.CONFLICT
                code == 422 -> SyncHttpFailureClass.INVALID_REQUEST
                code == 429 -> SyncHttpFailureClass.RATE_LIMITED
                code != null && code >= 500 -> SyncHttpFailureClass.SERVER
                else -> SyncHttpFailureClass.UNKNOWN
            }
            val resetEpochSeconds = headers["x-ratelimit-reset"]?.toLongOrNull()?.takeIf { it > 0L }
            val primaryRateLimitExhausted = headers["x-ratelimit-remaining"] == "0"
            val nowMillis = System.currentTimeMillis()
            val retryAfterDeadlineMillis = headers["retry-after"]?.let { value ->
                value.trim().toLongOrNull()?.let { seconds ->
                    seconds.coerceAtLeast(0L).saturatedMultiply(1_000L).saturatedAdd(nowMillis)
                } ?: runCatching {
                    ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME)
                        .toInstant()
                        .toEpochMilli()
                }.getOrNull()
            }
            val resetDeadlineMillis = resetEpochSeconds.takeIf { primaryRateLimitExhausted }
                ?.let { it.saturatedMultiply(1_000L) }
            val secondaryFallbackDeadlineMillis = if ((secondaryRateLimit || code == 429) &&
                retryAfterDeadlineMillis == null
            ) {
                nowMillis.saturatedAdd(60_000L)
            } else {
                null
            }
            val retryDeadlineMillis = listOfNotNull(
                retryAfterDeadlineMillis,
                resetDeadlineMillis,
                secondaryFallbackDeadlineMillis,
            ).maxOrNull()
            val retryAfterMillis = retryDeadlineMillis?.let { deadline ->
                if (deadline <= nowMillis) 0L else deadline - nowMillis
            }
            throw SyncHttpException(
                code,
                "GitHub sync request failed",
                retryable = code == 409 || failureClass == SyncHttpFailureClass.RATE_LIMITED || code >= 500,
                failureClass = failureClass,
                retryAfterMillis = retryAfterMillis,
                rateLimitResetEpochSeconds = resetEpochSeconds,
                networkPhase = mihon.domain.sync.runtime.SyncNetworkFailurePhase.HTTP_RESPONSE,
            )
        }
        return this
    }

    private fun Long.saturatedMultiply(multiplier: Long): Long =
        if (this > Long.MAX_VALUE / multiplier) Long.MAX_VALUE else this * multiplier

    private fun Long.saturatedAdd(other: Long): Long =
        if (other > 0L && this > Long.MAX_VALUE - other) Long.MAX_VALUE else this + other

    private fun publishFailureClass(error: Exception?): SyncPublishFailureClass = when (error) {
        is SyncHttpException -> when (error.failureClass) {
            SyncHttpFailureClass.NETWORK,
            SyncHttpFailureClass.SERVER,
            -> SyncPublishFailureClass.NETWORK
            SyncHttpFailureClass.AUTHORIZATION -> SyncPublishFailureClass.AUTHORIZATION
            SyncHttpFailureClass.RATE_LIMITED -> SyncPublishFailureClass.RATE_LIMITED
            SyncHttpFailureClass.CONFLICT -> SyncPublishFailureClass.CONFLICT
            SyncHttpFailureClass.INVALID_REQUEST -> SyncPublishFailureClass.INVALID_REQUEST
            SyncHttpFailureClass.UNKNOWN ->
                if (error.retryable) SyncPublishFailureClass.NETWORK else SyncPublishFailureClass.UNKNOWN
        }
        is java.io.IOException -> SyncPublishFailureClass.NETWORK
        null -> SyncPublishFailureClass.NETWORK
        else -> SyncPublishFailureClass.UNKNOWN
    }

    private fun SyncHttpResponse.json(): JsonObject =
        runCatching { githubJson.parseToJsonElement(body.decodeToString()).jsonObject }
            .getOrElse { throw IllegalStateException("GitHub sync response malformed") }

    @Serializable
    private data class ManifestShard(
        val formatVersion: Int,
        val spaceId: String,
        val generation: Long,
        val actorId: String,
        val epoch: Long,
        val previousPath: String?,
        val batch: ManifestBatch?,
    )

    @Serializable
    private data class ManifestBatch(
        val batchId: String,
        val path: String,
        val digestHex: String,
        val firstSeq: Long,
        val lastSeq: Long,
        val actorId: String,
        val epoch: Long,
    )

    private data class DecodedShard(
        val path: String,
        val actorId: String,
        val epoch: Long,
        val previousPath: String?,
        val batch: ManifestBatch?,
        val ciphertextDigestHex: String,
    )

    @Serializable
    private data class ManifestHead(
        val formatVersion: Int,
        val spaceId: String,
        val generation: Long,
        val actorId: String,
        val epoch: Long,
        val indexPath: String,
        val batchId: String,
        val digestHex: String,
        val lastSeq: Long,
    )

    private data class DecodedHead(
        val actorId: String,
        val epoch: Long,
        val indexPath: String,
        val batchId: String,
        val digestHex: String,
        val lastSeq: Long,
    )

    private fun encodeManifestShard(value: ManifestShard): String = githubJson.encodeToString(value)

    private fun encodeManifestHead(value: ManifestHead): String = githubJson.encodeToString(value)

    private fun decryptShard(
        bytes: ByteArray,
        path: String,
        expectedSpaceId: String,
        expectedGeneration: Long,
    ): DecodedShard {
        require(path.startsWith(".mihon-sync/index/")) { "sync index path is invalid" }
        val pathParts = path.removePrefix(".mihon-sync/index/").split('/')
        require(pathParts.size == 3 && pathParts[1].toLongOrNull() != null) { "sync index path is invalid" }
        val indexBatchId = pathParts[2].removeSuffix(".bin")
        val plaintext = unprotect(
            bytes,
            SyncCryptoBinding(1, expectedSpaceId, expectedGeneration, indexBatchId, path),
        )
        val shard = runCatching { githubJson.decodeFromString<ManifestShard>(plaintext.decodeToString()) }
            .getOrElse { throw IllegalStateException("sync index shard is malformed") }
        require(
            shard.formatVersion == 1 && shard.spaceId == expectedSpaceId && shard.generation == expectedGeneration,
        ) {
            "sync index shard binding is invalid"
        }
        require(pathParts.size == 3 && pathParts[0] == shard.actorId && pathParts[1].toLongOrNull() == shard.epoch) {
            "sync index actor or epoch is invalid"
        }
        require(path == ".mihon-sync/index/${shard.actorId}/${shard.epoch}/$indexBatchId.bin")
        require(
            shard.actorId.matches(Regex("[A-Za-z0-9_-]{1,128}")) &&
                indexBatchId.matches(Regex("[A-Za-z0-9_-]{1,128}")),
        )
        shard.batch?.let {
            require(it.batchId == indexBatchId) { "sync index filename does not match batch" }
            require(it.actorId == shard.actorId && it.epoch == shard.epoch) { "sync index batch identity is invalid" }
        }
        return DecodedShard(
            path,
            shard.actorId,
            shard.epoch,
            shard.previousPath,
            shard.batch,
            indexEngine.sha256(bytes).toByteString().hex(),
        )
    }

    private fun decryptHead(
        bytes: ByteArray,
        path: String,
        expectedSpaceId: String,
        expectedGeneration: Long,
    ): DecodedHead {
        val pathParts = path.removePrefix(".mihon-sync/heads/").split('/')
        require(pathParts.size == 2 && pathParts[0].isNotBlank()) { "sync head path is invalid" }
        val epoch = pathParts[1].removeSuffix(".bin").toLongOrNull()
            ?: throw IllegalStateException("sync head epoch is invalid")
        require(epoch >= 0) { "sync head epoch is invalid" }
        val plaintext = unprotect(
            bytes,
            SyncCryptoBinding(
                1,
                expectedSpaceId,
                expectedGeneration,
                "head-${pathParts[0]}-$epoch",
                path,
            ),
        )
        val head = runCatching { githubJson.decodeFromString<ManifestHead>(plaintext.decodeToString()) }
            .getOrElse { throw IllegalStateException("sync head is malformed") }
        require(
            head.formatVersion == 1 && head.spaceId == expectedSpaceId &&
                head.generation == expectedGeneration && head.actorId == pathParts[0] && head.epoch == epoch,
        ) { "sync head binding is invalid" }
        require(head.indexPath == ".mihon-sync/index/${head.actorId}/${head.epoch}/${head.batchId}.bin") {
            "sync head index path is invalid"
        }
        require(head.digestHex.matches(Regex("[0-9a-fA-F]{64}")) && head.lastSeq >= 0) {
            "sync head metadata is invalid"
        }
        return DecodedHead(
            head.actorId,
            head.epoch,
            head.indexPath,
            head.batchId,
            head.digestHex.lowercase(),
            head.lastSeq,
        )
    }

    private fun decodeStoredBatch(bytes: ByteArray): StoredSyncBatch =
        runCatching { githubJson.decodeFromString<StoredSyncBatch>(bytes.decodeToString()) }
            .getOrElse { throw IllegalStateException("sync batch is malformed") }

    private fun protect(plaintext: ByteArray, binding: SyncCryptoBinding): SyncPayload = spaceMaterial?.let {
        SyncSpacePayloadCodec.encode(indexEngine, it, binding, plaintext)
    } ?: indexEngine.encrypt(requireNotNull(indexSecret), plaintext, binding.canonicalAad())

    private fun unprotect(bytes: ByteArray, binding: SyncCryptoBinding): ByteArray = spaceMaterial?.let {
        SyncSpacePayloadCodec.decode(indexEngine, it, binding, SyncSpacePayload(bytes))
    } ?: indexEngine.decrypt(requireNotNull(indexSecret), SyncAeadCiphertext(bytes), binding.canonicalAad())

    private companion object {
        const val MAX_PUBLISH_ATTEMPTS = 3
        const val MAX_INITIALIZATION_ANCESTRY = 1_000
    }
}

private fun String.hostOrNull(): String? = runCatching { toHttpUrl().host.lowercase() }.getOrNull()

private suspend fun <T> suspendResult(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (error: CancellationException) {
    throw error
} catch (error: Exception) {
    Result.failure(error)
}

private fun JsonObject.string(name: String): String = this[name]?.jsonPrimitive?.contentOrNull
    ?: throw IllegalStateException("GitHub sync response missing field")

private fun JsonObject.long(name: String): Long? = this[name]?.jsonPrimitive?.longOrNull

private fun JsonObject.obj(name: String): JsonObject = this[name]?.jsonObject
    ?: throw IllegalStateException("GitHub sync response missing object")

private fun JsonObject.array(name: String): JsonArray = this[name]?.jsonArray
    ?: throw IllegalStateException("GitHub sync response missing array")

private fun JsonObject.boolean(name: String): Boolean {
    val value = this[name] as? JsonPrimitive
    return value?.takeUnless { it.isString }?.booleanOrNull
        ?: throw IllegalStateException("GitHub sync response missing boolean")
}
