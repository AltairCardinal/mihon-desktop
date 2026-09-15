@file:Suppress("ktlint:standard:filename")

package mihon.data.sync.transport

import kotlinx.coroutines.CancellationException
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
import mihon.data.sync.crypto.SyncAeadEngineFactory
import mihon.data.sync.http.SyncHttpClient
import mihon.data.sync.http.SyncHttpException
import mihon.data.sync.http.SyncHttpResponse
import mihon.domain.sync.crypto.SyncAeadCiphertext
import mihon.domain.sync.crypto.SyncAeadEngine
import mihon.domain.sync.crypto.SyncBatchEncryption
import mihon.domain.sync.crypto.SyncCryptoBinding
import mihon.domain.sync.crypto.SyncEncryptedBatch
import mihon.domain.sync.crypto.SyncSecret
import mihon.domain.sync.transport.SyncBatchIndexEntry
import mihon.domain.sync.transport.SyncGitBlob
import mihon.domain.sync.transport.SyncGitCommit
import mihon.domain.sync.transport.SyncGitRef
import mihon.domain.sync.transport.SyncGitTree
import mihon.domain.sync.transport.SyncGitTreeEntry
import mihon.domain.sync.transport.SyncInitializationResult
import mihon.domain.sync.transport.SyncPreparedUpload
import mihon.domain.sync.transport.SyncPublishResult
import mihon.domain.sync.transport.SyncPublishStatus
import mihon.domain.sync.transport.SyncRepository
import mihon.domain.sync.transport.SyncSnapshot
import mihon.domain.sync.transport.SyncTransportPort
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString

private val githubJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    isLenient = false
}
private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

class GitHubSyncTransport(
    productionClient: OkHttpClient,
    private val tokenProvider: suspend () -> String,
    private val apiBaseUrl: String = "https://api.github.com",
    private val maxTreeEntries: Int = 20_000,
    private val indexSecret: SyncSecret? = null,
    private val indexEngine: SyncAeadEngine = SyncAeadEngineFactory.create(),
) : SyncTransportPort {
    private val http = SyncHttpClient(productionClient, setOf(apiBaseUrl.hostOrNull() ?: "api.github.com"))

    override suspend fun readSnapshot(
        repository: SyncRepository,
        expectedSpaceId: String,
        expectedGeneration: Long,
    ): Result<SyncSnapshot> = suspendResult {
        val ref = getRef(repository)
        val commit = getCommit(repository, ref.objectSha)
        val tree = getTree(repository, commit.treeSha)
        require(!tree.truncated && tree.entries.size <= maxTreeEntries) { "sync tree is truncated or oversized" }
        val files = tree.entries.associateBy { it.path }
        require(files.size == tree.entries.size) { "sync tree contains duplicate paths" }
        val indexEntries = tree.entries.filter { it.type == "blob" && it.path.startsWith(".mihon-sync/index/") }
        require(indexEntries.isNotEmpty()) { "sync index is missing" }
        require(indexEntries.size <= 10_000) { "sync index exceeds entry limit" }
        val shards = indexEntries.map { entry ->
            require(entry.mode == "100644") { "sync index must be a regular file" }
            decryptShard(getBlob(repository, entry.sha).content, entry.path, expectedSpaceId, expectedGeneration)
        }
        val bootstrap = shards.filter { it.batch == null }
        require(
            bootstrap.size == 1 && bootstrap.single().path == ".mihon-sync/index/bootstrap/0/bootstrap.bin" &&
                bootstrap.single().previousPath == null,
        ) { "sync bootstrap is missing or invalid" }
        val paths = shards.associateBy { it.path }
        val chains = shards.filter { it.batch != null }.groupBy { it.actorId to it.epoch }
        val heads = tree.entries.filter { it.type == "blob" && it.path.startsWith(".mihon-sync/heads/") }.map { entry ->
            require(entry.mode == "100644") { "sync head must be a regular file" }
            decryptHead(getBlob(repository, entry.sha).content, entry.path, expectedSpaceId, expectedGeneration)
        }
        require(heads.size == chains.size && heads.map { it.actorId to it.epoch }.toSet() == chains.keys) {
            "sync actor head is missing or duplicated"
        }
        // Walk each actor once from its authenticated head, rejecting gaps, forks and unreachable shards.
        for (head in heads) {
            val chain = chains.getValue(head.actorId to head.epoch)
            val visited = mutableSetOf<String>()
            var node = paths[head.indexPath] ?: error("sync head points to a missing index")
            val latest = requireNotNull(node.batch)
            require(
                latest.batchId == head.batchId && latest.lastSeq == head.lastSeq && latest.digestHex == head.digestHex,
            ) {
                "sync head does not match its index"
            }
            while (true) {
                require(visited.add(node.path)) { "sync index chain contains a cycle" }
                require(node.actorId == head.actorId && node.epoch == head.epoch) { "sync index chain crosses actor" }
                val batch = requireNotNull(node.batch) { "sync actor chain enters bootstrap" }
                val previousPath = node.previousPath
                if (previousPath == null) {
                    require(batch.firstSeq == 1L) { "sync actor chain has a missing prefix" }
                    break
                }
                val previous = paths[previousPath] ?: error("sync index chain is broken")
                val previousBatch = requireNotNull(previous.batch)
                require(previousBatch.lastSeq < Long.MAX_VALUE && previousBatch.lastSeq + 1 == batch.firstSeq) {
                    "sync index sequence chain is incomplete"
                }
                node = previous
            }
            require(visited.size == chain.size) { "sync index contains an unreachable branch" }
        }
        val index = shards.mapNotNull { shard ->
            shard.batch?.let { batch ->
                require(batch.path == ".mihon-sync/batches/${batch.actorId}/${batch.epoch}/${batch.batchId}.json") {
                    "sync batch path is invalid"
                }
                require(
                    batch.digestHex.matches(Regex("[0-9a-f]{64}")) &&
                        batch.firstSeq > 0 && batch.lastSeq >= batch.firstSeq,
                ) {
                    "sync index batch metadata is invalid"
                }
                val file = files[batch.path]
                require(file != null && file.type == "blob" && file.mode == "100644") {
                    "sync indexed batch is missing"
                }
                SyncBatchIndexEntry(
                    batch.batchId, batch.path, batch.digestHex, batch.firstSeq, batch.lastSeq,
                    batch.actorId, batch.epoch, shard.path, shard.ciphertextDigestHex,
                )
            }
        }
        require(index.map { it.batchId }.toSet().size == index.size) { "sync batch ids are duplicated" }
        val storedPaths = tree.entries
            .filter { it.type == "blob" && it.path.startsWith(".mihon-sync/batches/") }
            .map { it.path }.toSet()
        require(storedPaths == index.map { it.path }.toSet()) { "sync batch has no authenticated index" }
        SyncSnapshot(repository, ref.objectSha, tree, expectedSpaceId, expectedGeneration, index)
    }

    suspend fun readEncryptedBatch(snapshot: SyncSnapshot, entry: SyncBatchIndexEntry): SyncEncryptedBatch {
        val treeEntry = snapshot.tree.entries.firstOrNull { it.path == entry.path }
            ?: throw IllegalStateException("sync batch is missing")
        val stored = decodeStoredBatch(getBlob(snapshot.repository, treeEntry.sha).content)
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
        val secret = indexSecret ?: error("sync index key is unavailable")
        require(snapshot.spaceId == encryptedBatch.spaceId && snapshot.generation == encryptedBatch.generation) {
            "sync batch scope does not match snapshot"
        }
        SyncBatchEncryption.decrypt(indexEngine, secret, encryptedBatch)
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
            indexEngine.encrypt(
                secret,
                encodeManifestShard(shard).encodeToByteArray(),
                encryptedBatch.binding().copy(path = indexPath).canonicalAad(),
            ),
            indexEngine.encrypt(
                secret,
                encodeManifestHead(head).encodeToByteArray(),
                SyncCryptoBinding(
                    1,
                    snapshot.spaceId,
                    snapshot.generation,
                    "head-${encryptedBatch.actorId}-${encryptedBatch.epoch}",
                    headPath,
                ).canonicalAad(),
            ),
        )
    }

    override suspend fun publish(
        repository: SyncRepository,
        snapshot: SyncSnapshot,
        upload: SyncPreparedUpload,
    ): SyncPublishResult {
        val batch = upload.encryptedBatch
        require(repository == snapshot.repository && repository == upload.repository) {
            "sync upload repository mismatch"
        }
        require(batch.spaceId == snapshot.spaceId && batch.generation == snapshot.generation) {
            "sync upload scope mismatch"
        }
        validatePrepared(upload)
        var current = snapshot
        var blobs: List<SyncGitTreeEntry>? = null
        for (attempt in 1..MAX_PUBLISH_ATTEMPTS) {
            val existing = current.batches.firstOrNull { it.batchId == batch.batchId }
            if (existing != null) {
                require(matches(current, upload)) { "sync batch id already contains different bytes" }
                // A caller may pass a historical snapshot after a restart. Only the live ref can confirm delivery.
                val observed = readSnapshot(repository, snapshot.spaceId, snapshot.generation).getOrNull()
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
                )
            }
            val commit = try {
                val entries = blobs ?: listOf(
                    batch.path to StoredSyncBatch.fromDomain(batch).body(),
                    upload.indexPath to upload.indexCiphertext.bytes,
                    upload.headPath to upload.headCiphertext.bytes,
                ).map { (path, bytes) ->
                    SyncGitTreeEntry(path, "100644", "blob", createBlob(repository, bytes).sha, bytes.size.toLong())
                }.also { blobs = it }
                val tree = createTree(repository, current.tree.sha, entries)
                createCommit(repository, tree.sha, current.head)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return SyncPublishResult(
                    SyncPublishStatus.FAILED,
                    batch.batchId,
                    error = "upload objects could not be created",
                    attempts = attempt,
                )
            }
            var refError: Exception? = null
            try {
                updateRef(repository, commit.sha)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                refError = error
            }
            val observed = readSnapshot(repository, snapshot.spaceId, snapshot.generation).getOrNull()
            if (observed != null && suspendResult { matches(observed, upload) }.getOrDefault(false)) {
                return SyncPublishResult(SyncPublishStatus.PUBLISHED, batch.batchId, observed.head, attempts = attempt)
            }
            if (refError is SyncHttpException && refError.code in listOf(409, 422)) {
                if (observed == null || observed.head == current.head || attempt == MAX_PUBLISH_ATTEMPTS) {
                    return SyncPublishResult(
                        SyncPublishStatus.CONFLICT,
                        batch.batchId,
                        error = "ref update could not be safely retried",
                        attempts = attempt,
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
        val secret = indexSecret ?: error("sync index key is unavailable")
        SyncBatchEncryption.decrypt(indexEngine, secret, batch)
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
                        if (error.message == "sync index is missing") {
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
                if (error.code != 404 || info.size > 0) {
                    return SyncInitializationResult.Failed("default branch lookup failed")
                }
                null
            }
            val sourceRef = if (defaultRef != null) {
                defaultRef
            } else {
                val bootstrap = getContents(repository, info.defaultBranch)
                if (bootstrap.code !in 200..299 && bootstrap.code != 404) {
                    return SyncInitializationResult.Failed("repository bootstrap was rejected")
                }
                if (bootstrap.code == 200) {
                    val body = bootstrap.body.decodeToString()
                    val contents = runCatching { githubJson.parseToJsonElement(body) }.getOrNull()
                    if (contents !is JsonArray || contents.isNotEmpty()) {
                        return SyncInitializationResult.NeedsExplicitAction(
                            "empty repository contents response is invalid",
                        )
                    }
                }
                val bootstrapSha = putContentsBootstrap(repository, info.defaultBranch)
                getRef(repository, info.defaultBranch).also {
                    require(it.objectSha == bootstrapSha || it.objectSha.isNotBlank()) {
                        "bootstrap commit could not be read"
                    }
                }
            }
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

    private suspend fun initializeOnHead(
        repository: SyncRepository,
        baseRef: SyncGitRef,
        spaceId: String,
        generation: Long,
    ): SyncInitializationResult {
        return try {
            val secret = indexSecret ?: return SyncInitializationResult.Failed("sync index key is unavailable")
            val baseCommit = getCommit(repository, baseRef.objectSha)
            val baseTree = getTree(repository, baseCommit.treeSha)
            require(!baseTree.truncated && baseTree.entries.size <= maxTreeEntries) {
                "initialization tree is incomplete"
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
            val ciphertext = indexEngine.encrypt(
                secret,
                encodeManifestShard(shard).encodeToByteArray(),
                SyncCryptoBinding(1, spaceId, generation, "bootstrap", indexPath).canonicalAad(),
            )
            val blob = createBlob(repository, ciphertext.bytes)
            val tree =
                createTree(
                    repository,
                    baseCommit.treeSha,
                    listOf(SyncGitTreeEntry(indexPath, "100644", "blob", blob.sha)),
                )
            val commit = createCommit(repository, tree.sha, baseRef.objectSha)
            var refError: Exception? = null
            try {
                updateRef(repository, commit.sha)
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

    private suspend fun getTree(repository: SyncRepository, sha: String): SyncGitTree {
        val response = call(repository, "GET", "/git/trees/$sha?recursive=1")
        val json = response.requireSuccess().json()
        val entries = json.array("tree").take(maxTreeEntries + 1).map { item ->
            val obj = item.jsonObject
            SyncGitTreeEntry(
                obj.string("path"),
                obj.string("mode"),
                obj.string("type"),
                obj.string("sha"),
                obj.long("size"),
            )
        }
        return SyncGitTree(sha, entries, json.boolean("truncated") || entries.size > maxTreeEntries)
    }

    private suspend fun getBlob(repository: SyncRepository, sha: String): SyncGitBlob {
        val json = call(repository, "GET", "/git/blobs/$sha").requireSuccess().json()
        require(json.string("encoding") == "base64") { "sync blob encoding is invalid" }
        val content = json.string("content").replace("\n", "").decodeBase64()?.toByteArray()
            ?: throw IllegalStateException("sync blob content is invalid")
        require(content.size <= 2 * 1024 * 1024) { "sync blob exceeds limit" }
        return SyncGitBlob(sha, content)
    }

    private suspend fun createBlob(repository: SyncRepository, content: ByteArray): SyncGitBlob {
        val payload = buildJsonObject {
            put("content", JsonPrimitive(content.toByteString().base64()))
            put("encoding", JsonPrimitive("base64"))
        }
        val json = call(repository, "POST", "/git/blobs", payload).requireSuccess().json()
        return SyncGitBlob(json.string("sha"), content)
    }

    private suspend fun createTree(
        repository: SyncRepository,
        baseTree: String?,
        entries: List<SyncGitTreeEntry>,
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
                                put("sha", JsonPrimitive(entry.sha))
                            },
                        )
                    }
                },
            )
        }
        val json = call(repository, "POST", "/git/trees", payload).requireSuccess().json()
        return SyncGitTree(json.string("sha"), emptyList(), false)
    }

    private suspend fun createCommit(repository: SyncRepository, tree: String, parent: String?): SyncGitCommit {
        val payload = buildJsonObject {
            put("message", JsonPrimitive("mihon sync batch"))
            put("tree", JsonPrimitive(tree))
            if (parent != null) put("parents", buildJsonArray { add(JsonPrimitive(parent)) })
        }
        val json = call(repository, "POST", "/git/commits", payload).requireSuccess().json()
        return SyncGitCommit(json.string("sha"), tree, listOfNotNull(parent))
    }

    private suspend fun updateRef(repository: SyncRepository, commit: String) {
        val payload = buildJsonObject {
            put("sha", JsonPrimitive(commit))
            put("force", JsonPrimitive(false))
        }
        call(repository, "PATCH", "/git/refs/heads/${repository.branch}", payload).requireSuccess()
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
        val size: Long,
    )

    private suspend fun getRepositoryInfo(repository: SyncRepository): RepositoryInfo {
        val json = call(repository, "GET", "").requireSuccess().json()
        return RepositoryInfo(
            json.string("default_branch"),
            json.boolean("private"),
            json.long("size") ?: 0,
        )
    }

    private suspend fun getContents(repository: SyncRepository, branch: String): SyncHttpResponse =
        call(repository, "GET", "/contents/?ref=$branch")

    private suspend fun putContentsBootstrap(repository: SyncRepository, branch: String): String {
        val payload = buildJsonObject {
            put("message", JsonPrimitive("initialize mihon sync"))
            put("content", JsonPrimitive("bWlob24tc3luYyBib290c3RyYXA="))
            put("branch", JsonPrimitive(branch))
        }
        val json = call(repository, "PUT", "/contents/.mihon-sync/bootstrap", payload).requireSuccess().json()
        return json.obj("commit").string("sha")
    }

    private suspend fun call(
        repository: SyncRepository,
        method: String,
        path: String,
        body: JsonObject? = null,
    ): SyncHttpResponse {
        val token = tokenProvider().takeIf { it.isNotBlank() } ?: throw IllegalStateException("authorization required")
        val requestBody = body?.let {
            githubJson.encodeToString(JsonObject.serializer(), it).toRequestBody(jsonMediaType)
        }
        return http.execute(
            http.request(
                "$apiBaseUrl/repos/${repository.fullName}$path",
                method,
                headers = mapOf(
                    "Accept" to "application/vnd.github+json",
                    "Authorization" to "Bearer $token",
                    "X-GitHub-Api-Version" to "2026-03-10",
                ),
                body = requestBody,
            ),
        )
    }

    private fun SyncHttpResponse.requireSuccess(): SyncHttpResponse {
        if (code !in 200..299) {
            throw SyncHttpException(
                code,
                "GitHub sync request failed",
                retryable =
                code == 409 || code == 429 || code >= 500,
            )
        }
        return this
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
        val secret = indexSecret ?: throw IllegalStateException("sync index key is unavailable")
        require(path.startsWith(".mihon-sync/index/")) { "sync index path is invalid" }
        val pathParts = path.removePrefix(".mihon-sync/index/").split('/')
        require(pathParts.size == 3 && pathParts[1].toLongOrNull() != null) { "sync index path is invalid" }
        val indexBatchId = pathParts[2].removeSuffix(".bin")
        val plaintext = indexEngine.decrypt(
            secret,
            SyncAeadCiphertext(bytes),
            SyncCryptoBinding(1, expectedSpaceId, expectedGeneration, indexBatchId, path).canonicalAad(),
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
        val secret = indexSecret ?: throw IllegalStateException("sync index key is unavailable")
        val pathParts = path.removePrefix(".mihon-sync/heads/").split('/')
        require(pathParts.size == 2 && pathParts[0].isNotBlank()) { "sync head path is invalid" }
        val epoch = pathParts[1].removeSuffix(".bin").toLongOrNull()
            ?: throw IllegalStateException("sync head epoch is invalid")
        require(epoch >= 0) { "sync head epoch is invalid" }
        val plaintext = indexEngine.decrypt(
            secret,
            SyncAeadCiphertext(bytes),
            SyncCryptoBinding(
                1,
                expectedSpaceId,
                expectedGeneration,
                "head-${pathParts[0]}-$epoch",
                path,
            ).canonicalAad(),
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

    private companion object {
        const val MAX_PUBLISH_ATTEMPTS = 3
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
