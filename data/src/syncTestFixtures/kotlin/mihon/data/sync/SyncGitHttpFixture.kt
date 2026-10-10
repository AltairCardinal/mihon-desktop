package mihon.data.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mihon.data.sync.crypto.SyncAeadEngineFactory
import mihon.data.sync.transport.GitHubSyncTransport
import mihon.domain.sync.crypto.SyncAeadEngine
import mihon.domain.sync.crypto.SyncSecret
import mihon.domain.sync.transport.SyncRepository
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okio.Buffer
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.toByteString
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Shared Git HTTP object server; no synchronization admission or recovery logic. */
internal open class SyncGitHttpFixture(
    empty: Boolean = false,
    repositoryOverride: SyncRepository? = null,
    private val realTreeOids: Boolean = empty,
    private val defaultKey: SyncSecret = SyncSecret.fromBytes(ByteArray(32) { (it + 1).toByte() }),
) : AutoCloseable {
    data class RefPostObservation(
        val branch: String,
        val commitSha: String,
        val treeSha: String,
        val treeEntries: Map<String, String>,
    )

    private var repository = repositoryOverride ?: SyncRepository("fixture-owner", "private-sync", "mihon-sync")
    fun renameRepository(value: SyncRepository) {
        repository = value
    }
    val server = MockWebServer()
    private var nextObject = 1
    private val blobs = mutableMapOf<String, ByteArray>()
    private val trees = mutableMapOf<String, Map<String, String>>()
    private val subtrees = mutableMapOf<String, String>()
    private val commits = mutableMapOf<String, Pair<String, List<String>>>()
    private val refs = mutableMapOf<String, String>()
    val otherRefs = mutableMapOf<String, String>()
    val pathWrites = mutableMapOf<String, MutableList<String>>()
    val contentsPutBodies = mutableListOf<String>()
    val refPostObservations = mutableListOf<RefPostObservation>()
    val refPatchBranches = mutableListOf<String>()
    var bootstrapCommitCount = 0
    val invalidBaseTrees = mutableListOf<String>()
    val forceFlags = mutableListOf<Boolean>()
    val conflicts = AtomicInteger()
    var corruptNextPublishedPath: String? = null
    var refUpdateBarrier: CountDownLatch? = null
    var bootstrapPutBarrier: CountDownLatch? = null

    @Volatile var nextRefReadBarrier: Pair<CountDownLatch, CountDownLatch>? = null
    var truncatedFlag: kotlinx.serialization.json.JsonElement = JsonPrimitive(false)
    var nextReadFailure: MockResponse? = null
    var snapshotReads: Int = 0
    var commitReads: Int = 0
    var blobReads: Int = 0
    val blobReadOids = mutableListOf<String>()
    fun blobOids(): Set<String> = blobs.keys.toSet()
    var nextBlobResponse: MockResponse? = null
    var nextCreateBlobResponse: MockResponse? = null
    var recursiveTreeRequests: Int = 0
    var treeRequests: Int = 0
    private val treeRequestCounts = mutableMapOf<String, Int>()
    private var delayedTreeResponse: Pair<String, Long>? = null
    private var delayedTreeResponseStarted: CompletableDeferred<Unit>? = null
    private var nextTreeResponse: Pair<String, String>? = null
    private val syntheticTrees = mutableMapOf<String, kotlinx.serialization.json.JsonObject>()
    private val extraRootDirectories = mutableMapOf<String, String>()
    var privateRepository = true
    var repositorySizeOverride: Long? = null
    var failNextBootstrapBeforeCommit = false
    var repositorySizeAfterBootstrapFailure: Long? = null
    var externalFileBeforeNextBootstrapPut: Pair<String, ByteArray>? = null
    var nextRepositoryResponse: MockResponse? = null
    var nextRefResponse: MockResponse? = null
    var loseNextBootstrapResponse = false
    var loseNextSyncBranchCreateResponse = false
    private var failNextBootstrapReadback = false
    var failReadAfterPatch = false
    var competingWrites = 0
    var emptyRefStatus = 404
    val baseUrl: String get() = server.url("/").toString().removeSuffix("/")

    init {
        if (!empty) {
            val readme = storeBlob("# Existing private repository".encodeToByteArray())
            val initialTree = storeTree(mapOf("README.md" to readme))
            refs["main"] = sha().also { commits[it] = initialTree to emptyList() }
        }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "GET" && request.url.encodedPath.contains("/git/ref/heads/")) {
                    nextRefReadBarrier?.let { (started, release) ->
                        nextRefReadBarrier = null
                        started.countDown()
                        check(release.await(5, TimeUnit.SECONDS)) { "snapshot reader was not released" }
                    }
                }
                if (request.method == "PATCH") {
                    refUpdateBarrier?.let { barrier ->
                        barrier.countDown()
                        check(barrier.await(5, TimeUnit.SECONDS)) { "initializer did not reach ref update" }
                    }
                }
                if (request.method == "PUT" &&
                    request.url.encodedPath.endsWith("/contents/.mihon-sync/bootstrap")
                ) {
                    bootstrapPutBarrier?.let { barrier ->
                        barrier.countDown()
                        check(barrier.await(5, TimeUnit.SECONDS)) { "initializers did not reach bootstrap PUT" }
                    }
                }
                return synchronized(this@SyncGitHttpFixture) { route(request) }
            }
        }
        server.start()
    }

    fun transport(
        engine: SyncAeadEngine = SyncAeadEngineFactory.create(),
        key: SyncSecret = defaultKey,
        persistentObjectCacheDirectory: okio.Path? = null,
        connectionRevision: String = "transport-instance",
        repositoryId: Long? = null,
        maxTreeEntries: Int = 20_000,
    ) = GitHubSyncTransport(
        OkHttpClient(),
        tokenProvider = { "synthetic-access-token" },
        apiBaseUrl = baseUrl,
        maxTreeEntries = maxTreeEntries,
        indexSecret = key,
        indexEngine = engine,
        persistentObjectCacheDirectory = persistentObjectCacheDirectory,
        connectionRevision = connectionRevision,
        repositoryId = repositoryId,
    )

    fun createSyncBranch() {
        refs["mihon-sync"] = refs.getValue("main")
    }

    fun file(branch: String, path: String): ByteArray? =
        refs[branch]?.let(commits::get)?.first?.let(trees::get)?.get(path)?.let(blobs::get)

    fun files(branch: String): Map<String, ByteArray> =
        trees.getValue(commits.getValue(refs.getValue(branch)).first)
            .mapValues { (_, oid) -> blobs.getValue(oid).copyOf() }

    fun head(branch: String): String = refs.getValue(branch)

    fun hasBranch(branch: String): Boolean = branch in refs

    fun removeAllRefs() {
        refs.clear()
        otherRefs.clear()
    }

    fun treeSha(branch: String): String = commits.getValue(refs.getValue(branch)).first

    fun fileAtRefPost(observation: RefPostObservation, path: String): ByteArray? =
        observation.treeEntries[path]?.let(blobs::get)
    fun overrideNextTreeResponse(treeSha: String, body: String) {
        nextTreeResponse = treeSha to body
    }

    /** Adds a small set of immutable empty tree objects whose paths may expand exponentially. */
    fun attachEmptyTreeDag(rootSha: String, directoryVisits: Int) {
        require(directoryVisits > 0)
        val byVisits = mutableMapOf<Int, String>()
        fun subtree(visits: Int): String = byVisits.getOrPut(visits) {
            val id = sha()
            val left = (visits - 1) / 2
            val right = visits - 1 - left
            syntheticTrees[id] = buildJsonObject {
                put("sha", id)
                put("truncated", false)
                put(
                    "tree",
                    buildJsonArray {
                        listOf("left" to left, "right" to right).forEach { (name, count) ->
                            if (count > 0) {
                                add(
                                    buildJsonObject {
                                        put("path", name)
                                        put("mode", "040000")
                                        put("type", "tree")
                                        put("sha", subtree(count))
                                    },
                                )
                            }
                        }
                    },
                )
            }
            id
        }
        extraRootDirectories[rootSha] = subtree(directoryVisits)
    }

    fun delayNextTreeResponse(treeSha: String, delayMillis: Long): CompletableDeferred<Unit> {
        val started = CompletableDeferred<Unit>()
        delayedTreeResponse = treeSha to delayMillis
        delayedTreeResponseStarted = started
        return started
    }

    fun treeRequestCount(treeSha: String): Int = synchronized(this) { treeRequestCounts[treeSha] ?: 0 }

    fun resetRef(branch: String, commit: String) {
        require(commit in commits) { "cannot point a ref at an unknown commit" }
        refs[branch] = commit
    }

    fun removeFile(branch: String, path: String) {
        val parent = refs.getValue(branch)
        val files = trees.getValue(commits.getValue(parent).first) - path
        val tree = storeTree(files)
        refs[branch] = sha().also { commits[it] = tree to listOf(parent) }
    }

    fun replaceFile(branch: String, path: String, value: ByteArray) {
        val parent = refs.getValue(branch)
        val files = trees.getValue(commits.getValue(parent).first) + (path to storeBlob(value))
        val tree = storeTree(files)
        refs[branch] = sha().also { commits[it] = tree to listOf(parent) }
    }

    fun replaceFiles(branch: String, values: Map<String, ByteArray>) {
        val parent = refs.getValue(branch)
        val files = trees.getValue(commits.getValue(parent).first) + values.mapValues { storeBlob(it.value) }
        val tree = storeTree(files)
        refs[branch] = sha().also { commits[it] = tree to listOf(parent) }
    }

    private fun sha(): String = (nextObject++).toString(16).padStart(40, '0')
    private fun storeTree(files: Map<String, String>): String {
        val id = if (realTreeOids) treeOid(files) else sha()
        trees[id] = files.toMap()
        return id
    }

    private fun treeOid(files: Map<String, String>): String {
        val directFiles = files.filterKeys { '/' !in it }
        val directories = files.keys.filter { '/' in it }.map { it.substringBefore('/') }.distinct()
        val entries = buildList {
            directFiles.forEach { (name, oid) -> add(Triple(name, "100644", oid)) }
            directories.forEach { name ->
                val children = files.filterKeys { it.startsWith("$name/") }
                    .mapKeys { it.key.removePrefix("$name/") }
                add(Triple(name, "40000", treeOid(children)))
            }
        }.sortedWith { a, b ->
            val left = (a.first + if (a.second == "40000") "/" else "").encodeToByteArray()
            val right = (b.first + if (b.second == "40000") "/" else "").encodeToByteArray()
            var comparison = 0
            for (index in 0 until minOf(left.size, right.size)) {
                comparison = (left[index].toInt() and 255).compareTo(right[index].toInt() and 255)
                if (comparison != 0) break
            }
            if (comparison == 0) left.size.compareTo(right.size) else comparison
        }
        val body = Buffer()
        entries.forEach { (name, mode, oid) ->
            body.writeUtf8("$mode $name").writeByte(0).write(oid.decodeHex())
        }
        val bytes = body.readByteArray()
        return ("tree ${bytes.size}\u0000".encodeToByteArray() + bytes).toByteString().sha1().hex()
    }

    private fun storeBlob(bytes: ByteArray): String {
        val header = "blob ${bytes.size}\u0000".encodeToByteArray()
        return (header + bytes).toByteString().sha1().hex().also { blobs[it] = bytes.copyOf() }
    }
    private fun respond(
        value: kotlinx.serialization.json.JsonElement,
        code: Int = 200,
    ) = MockResponse(code = code, body = value.toString())
    private fun error(code: Int, message: String) = respond(buildJsonObject { put("message", message) }, code)
    private fun ref(branch: String, commit: String) = buildJsonObject {
        put("ref", "refs/heads/$branch")
        put(
            "object",
            buildJsonObject {
                put("type", "commit")
                put("sha", commit)
            },
        )
    }

    private fun route(request: RecordedRequest): MockResponse {
        val prefix = "/repos/${repository.owner}/${repository.name}"
        val url = request.url
        val path = url.encodedPath.removePrefix(prefix)
        val method = request.method
        val body = request.body?.utf8()?.takeIf { it.isNotEmpty() }?.let { Json.parseToJsonElement(it).jsonObject }
        fun field(name: String) = body!![name]!!.jsonPrimitive.content
        if (method == "GET" && path.isEmpty()) {
            nextRepositoryResponse?.let {
                nextRepositoryResponse = null
                return it
            }
            return respond(
                buildJsonObject {
                    put("id", 99)
                    put("private", privateRepository)
                    put("archived", false)
                    put("disabled", false)
                    put(
                        "permissions",
                        buildJsonObject {
                            put("push", true)
                            put("admin", false)
                        },
                    )
                    put("default_branch", "main")
                    put("size", repositorySizeOverride ?: if (refs.isEmpty() && otherRefs.isEmpty()) 0 else 1)
                    put("name", repository.name)
                    put("full_name", repository.fullName)
                    put(
                        "owner",
                        buildJsonObject {
                            put("id", 42)
                            put("login", repository.owner)
                            put("type", "User")
                        },
                    )
                },
            )
        }
        if (method == "GET" && path == "/git/matching-refs/") {
            if (failNextBootstrapReadback) {
                failNextBootstrapReadback = false
                return MockResponse(code = 500)
            }
            return respond(
                buildJsonArray {
                    refs.keys.forEach { branch -> add(ref(branch, refs.getValue(branch))) }
                    otherRefs.forEach { (name, sha) ->
                        add(
                            buildJsonObject {
                                put("ref", name)
                                put(
                                    "object",
                                    buildJsonObject {
                                        put("type", "commit")
                                        put("sha", sha)
                                    },
                                )
                            },
                        )
                    }
                },
            )
        }
        if (method == "GET" && path.startsWith("/git/ref/heads/")) {
            if (failNextBootstrapReadback) {
                failNextBootstrapReadback = false
                return MockResponse(code = 500)
            }
            snapshotReads++
            nextReadFailure?.let {
                nextReadFailure = null
                return it
            }
            val branch = path.removePrefix("/git/ref/heads/")
            return refs[branch]?.let { respond(ref(branch, it)) }
                ?: error(
                    if (refs.isEmpty() && otherRefs.isEmpty()) emptyRefStatus else 404,
                    if (refs.isEmpty() && otherRefs.isEmpty() && emptyRefStatus == 409) {
                        "Git Repository is empty."
                    } else {
                        "Reference does not exist"
                    },
                )
        }
        if (method == "GET" && path.startsWith("/git/commits/")) {
            commitReads++
            val id = path.substringAfterLast('/')
            val commit = commits[id] ?: return error(404, "Commit not found")
            return respond(
                buildJsonObject {
                    put("sha", id)
                    put("tree", buildJsonObject { put("sha", commit.first) })
                    put(
                        "parents",
                        buildJsonArray {
                            commit.second.forEach { add(buildJsonObject { put("sha", it) }) }
                        },
                    )
                },
            )
        }
        if (method == "GET" && path.startsWith("/git/trees/")) {
            treeRequests++
            val id = path.substringAfterLast('/')
            treeRequestCounts[id] = (treeRequestCounts[id] ?: 0) + 1
            nextTreeResponse?.takeIf { it.first == id }?.let { (_, responseBody) ->
                nextTreeResponse = null
                return MockResponse(body = responseBody)
            }
            syntheticTrees[id]?.let { return respond(it) }
            val tree = trees[id] ?: return error(404, "Tree not found")
            val recursive = url.queryParameter("recursive") != null
            if (recursive) recursiveTreeRequests++
            val directories = tree.keys.flatMap { file ->
                val parts = file.split('/')
                (1 until parts.size).map { count -> parts.take(count).joinToString("/") }
            }.distinct().filter { recursive || '/' !in it }
            val treeJson = buildJsonObject {
                put("sha", id)
                put("truncated", truncatedFlag)
                put(
                    "tree",
                    buildJsonArray {
                        for (directory in directories) {
                            val subtree = tree.filterKeys { it.startsWith("$directory/") }
                                .mapKeys { it.key.removePrefix("$directory/") }
                            val subtreeKey = subtree.entries.sortedBy { it.key }
                                .joinToString("\u0000") { (path, blob) -> "$path\u0000$blob" }
                            val directorySha = subtrees.getOrPut(subtreeKey) {
                                storeTree(subtree)
                            }
                            add(
                                buildJsonObject {
                                    put("path", directory)
                                    put("mode", "040000")
                                    put("type", "tree")
                                    put("sha", directorySha)
                                },
                            )
                        }
                        tree.filterKeys { recursive || '/' !in it }.forEach { (entryPath, blob) ->
                            add(
                                buildJsonObject {
                                    put("path", entryPath)
                                    put("mode", "100644")
                                    put("type", "blob")
                                    put("sha", blob)
                                    put("size", blobs.getValue(blob).size)
                                },
                            )
                        }
                        extraRootDirectories[id]?.let { subtreeSha ->
                            add(
                                buildJsonObject {
                                    put("path", "empty-dag")
                                    put("mode", "040000")
                                    put("type", "tree")
                                    put("sha", subtreeSha)
                                },
                            )
                        }
                    },
                )
            }
            val delayed = delayedTreeResponse?.takeIf { it.first == id }
            if (delayed != null) {
                delayedTreeResponse = null
                delayedTreeResponseStarted?.complete(Unit)
                delayedTreeResponseStarted = null
                return MockResponse.Builder()
                    .headersDelay(delayed.second, TimeUnit.MILLISECONDS)
                    .body(treeJson.toString())
                    .build()
            }
            return respond(treeJson)
        }
        if (method == "GET" && path.startsWith("/git/blobs/")) {
            blobReadOids += path.substringAfterLast('/')
            blobReads++
            nextBlobResponse?.let {
                nextBlobResponse = null
                return it
            }
            val id = path.substringAfterLast('/')
            val bytes = blobs[id] ?: return error(404, "Blob not found")
            if (request.headers["Accept"] == "application/vnd.github.raw+json") {
                return MockResponse.Builder()
                    .addHeader("Content-Type", "application/octet-stream")
                    .body(Buffer().write(bytes))
                    .build()
            }
            return respond(
                buildJsonObject {
                    put("sha", id)
                    put("encoding", "base64")
                    put("size", bytes.size)
                    put("content", bytes.toByteString().base64())
                },
            )
        }
        if (method == "POST" && path == "/git/blobs") {
            val bytes = if (field("encoding") == "base64") {
                field("content").decodeBase64()!!.toByteArray()
            } else {
                field("content").encodeToByteArray()
            }
            val id = storeBlob(bytes)
            nextCreateBlobResponse?.let {
                nextCreateBlobResponse = null
                return it
            }
            return respond(buildJsonObject { put("sha", id) }, 201)
        }
        if (method == "POST" && path == "/git/trees") {
            val base = body!!["base_tree"]?.jsonPrimitive?.content
            if (base != null && base !in trees) {
                invalidBaseTrees += base
                return error(422, "base_tree must identify a tree")
            }
            val tree = (base?.let(trees::get) ?: emptyMap()).toMutableMap()
            for (entryValue in body.getValue("tree").jsonArray) {
                val entry = entryValue.jsonObject
                val entryPath = entry.getValue("path").jsonPrimitive.content
                val blob = entry["sha"]?.jsonPrimitive?.content ?: run {
                    val content = entry["content"]?.jsonPrimitive?.content
                        ?: return error(422, "Tree entry has no content")
                    storeBlob(content.encodeToByteArray())
                }
                if (blob !in blobs) return error(422, "Unknown blob")
                tree[entryPath] = blob
                pathWrites.getOrPut(entryPath) { mutableListOf() } += blobs.getValue(blob).toByteString().base64()
            }
            val id = storeTree(tree)
            return respond(buildJsonObject { put("sha", id) }, 201)
        }
        if (method == "POST" && path == "/git/commits") {
            val tree = field("tree")
            if (tree !in trees) return error(422, "Unknown tree")
            val parents = body!!["parents"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            if (parents.any { it !in commits }) return error(422, "Unknown parent")
            val id = sha().also { commits[it] = tree to parents }
            return respond(
                buildJsonObject {
                    put("sha", id)
                    put("tree", buildJsonObject { put("sha", tree) })
                    put("parents", buildJsonArray { parents.forEach { add(buildJsonObject { put("sha", it) }) } })
                },
                201,
            )
        }
        if (method == "POST" && path == "/git/refs") {
            val branch = field("ref").removePrefix("refs/heads/")
            if (branch in refs) return error(422, "Reference already exists")
            val commit = field("sha")
            if (commit !in commits) return error(422, "Unknown commit")
            val treeSha = commits.getValue(commit).first
            refPostObservations += RefPostObservation(branch, commit, treeSha, trees.getValue(treeSha).toMap())
            refs[branch] = commit
            if (branch == "mihon-sync" && loseNextSyncBranchCreateResponse) {
                loseNextSyncBranchCreateResponse = false
                return error(500, "Synthetic lost sync ref creation response")
            }
            return respond(ref(branch, commit), 201)
        }
        if (method == "PATCH" && path.startsWith("/git/refs/heads/")) {
            val branch = path.removePrefix("/git/refs/heads/")
            refPatchBranches += branch
            if (competingWrites > 0) {
                competingWrites--
                replaceFile(branch, "README.md", "Competing write $competingWrites".encodeToByteArray())
            }
            val current = refs[branch] ?: return error(404, "Reference does not exist")
            val proposed = field("sha")
            val commit = commits[proposed] ?: return error(422, "Unknown commit")
            val force = body!!["force"]?.jsonPrimitive?.content == "true"
            forceFlags += force
            if (force) return error(422, "Force updates are prohibited in this fixture")
            if (current != proposed && current !in commit.second) {
                conflicts.incrementAndGet()
                return error(409, "Update is not a fast forward")
            }
            refs[branch] = proposed
            if (failReadAfterPatch) {
                failReadAfterPatch = false
                nextReadFailure = MockResponse(code = 500)
            }
            nextRefResponse?.let {
                nextRefResponse = null
                return it
            }
            corruptNextPublishedPath?.let { damagedPath ->
                val tree = trees.getValue(commit.first).toMutableMap()
                val oldBlob = tree[damagedPath] ?: return error(500, "Requested corruption path missing")
                val old = Json.parseToJsonElement(
                    blobs.getValue(oldBlob).decodeToString(),
                ).jsonObject.toMutableMap()
                val encryptedField = old.keys.firstOrNull { it.contains("ciphertext", ignoreCase = true) }
                    ?: return error(500, "Ciphertext field missing")
                old[encryptedField] = JsonPrimitive(ByteArray(64) { 3 }.toByteString().base64())
                tree[damagedPath] = storeBlob(JsonObject(old).toString().encodeToByteArray())
                val damagedTree = storeTree(tree)
                refs[branch] = sha().also { commits[it] = damagedTree to listOf(proposed) }
                corruptNextPublishedPath = null
                return error(500, "Synthetic lost response after publishing")
            }
            return respond(ref(branch, proposed))
        }
        if (method == "GET" && path.startsWith("/contents")) {
            val branch = url.queryParameter("ref") ?: "main"
            if (branch !in refs) {
                val message = if (refs.isEmpty() &&
                    otherRefs.isEmpty()
                ) {
                    "This repository is empty."
                } else {
                    "Not Found"
                }
                return error(404, message)
            }
            return respond(
                buildJsonArray {
                    val files = trees.getValue(commits.getValue(refs.getValue(branch)).first)
                    files.keys.forEach { file ->
                        add(
                            buildJsonObject {
                                put("name", file.substringAfterLast('/'))
                                put("path", file)
                                put("type", "file")
                            },
                        )
                    }
                },
            )
        }
        if (method == "PUT" && path.startsWith("/contents/")) {
            contentsPutBodies += request.body?.utf8().orEmpty()
            val branch = body!!["branch"]?.jsonPrimitive?.content ?: "main"
            val requestedPath = path.removePrefix("/contents/")
            if (requestedPath == ".mihon-sync/bootstrap" && body.containsKey("sha")) {
                return error(422, "bootstrap creation must omit sha")
            }
            if (requestedPath == ".mihon-sync/bootstrap" && failNextBootstrapBeforeCommit) {
                failNextBootstrapBeforeCommit = false
                repositorySizeAfterBootstrapFailure?.let {
                    repositorySizeOverride = it
                    repositorySizeAfterBootstrapFailure = null
                }
                return error(500, "Synthetic failure before bootstrap commit")
            }
            if (requestedPath == ".mihon-sync/bootstrap") {
                externalFileBeforeNextBootstrapPut?.let { (externalPath, content) ->
                    externalFileBeforeNextBootstrapPut = null
                    val externalParent = refs[branch]
                    val externalTree = externalParent
                        ?.let { trees.getValue(commits.getValue(it).first).toMutableMap() }
                        ?: mutableMapOf()
                    externalTree[externalPath] = storeBlob(content)
                    val externalTreeId = sha().also { trees[it] = externalTree }
                    refs[branch] = sha().also { commits[it] = externalTreeId to listOfNotNull(externalParent) }
                }
            }
            val oldHead = refs[branch]
            if (oldHead == null &&
                (refs.isNotEmpty() || otherRefs.isNotEmpty())
            ) {
                return error(404, "Branch not found")
            }
            val tree = oldHead?.let { trees.getValue(commits.getValue(it).first).toMutableMap() } ?: mutableMapOf()
            if (requestedPath in tree) return error(422, "file already exists")
            tree[requestedPath] = storeBlob(field("content").decodeBase64()!!.toByteArray())
            val treeId = storeTree(tree)
            val commitId = sha().also { commits[it] = treeId to listOfNotNull(oldHead) }
            refs[branch] = commitId
            if (requestedPath == ".mihon-sync/bootstrap") bootstrapCommitCount++
            if (requestedPath == ".mihon-sync/bootstrap" && loseNextBootstrapResponse) {
                loseNextBootstrapResponse = false
                failNextBootstrapReadback = true
                return error(500, "Synthetic lost Contents response")
            }
            return respond(buildJsonObject { put("commit", buildJsonObject { put("sha", commitId) }) }, 201)
        }
        return error(404, "Unexpected fixture request: $method $path")
    }

    override fun close() = server.close()
}
