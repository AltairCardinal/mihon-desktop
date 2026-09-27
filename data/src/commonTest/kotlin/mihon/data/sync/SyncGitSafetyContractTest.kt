@file:Suppress("ktlint:standard:max-line-length")

package mihon.data.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
import mihon.data.sync.transport.SyncBatchSyncService
import mihon.data.sync.transport.SyncBlobCache
import mihon.data.sync.transport.SyncBlobCacheKey
import mihon.data.sync.transport.SyncGraphQlSingleBatchAdapter
import mihon.data.sync.transport.SyncTreeEntryPayloadPlanner
import mihon.data.sync.transport.SyncUploadArtifactCodec
import mihon.data.sync.transport.gitTreeOid
import mihon.domain.sync.SyncBatch
import mihon.domain.sync.SyncCategory
import mihon.domain.sync.SyncEffect
import mihon.domain.sync.SyncEffectKind
import mihon.domain.sync.SyncEventEnvelope
import mihon.domain.sync.SyncField
import mihon.domain.sync.SyncObjectKey
import mihon.domain.sync.SyncObjectType
import mihon.domain.sync.SyncOrigin
import mihon.domain.sync.crypto.SyncAeadCiphertext
import mihon.domain.sync.crypto.SyncAeadEngine
import mihon.domain.sync.crypto.SyncBatchEncryption
import mihon.domain.sync.crypto.SyncSecret
import mihon.domain.sync.transport.SyncGitTreeEntry
import mihon.domain.sync.transport.SyncInitializationResult
import mihon.domain.sync.transport.SyncPublishFailureClass
import mihon.domain.sync.transport.SyncPublishStatus
import mihon.domain.sync.transport.SyncRepository
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Headers.Companion.headersOf
import okhttp3.OkHttpClient
import okio.Buffer
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.toByteString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SyncGitSafetyContractTest {
    private val repository = SyncRepository("fixture-owner", "private-sync", "mihon-sync")
    private val secret = SyncSecret.fromBytes(ByteArray(32) { (it + 1).toByte() })

    data class RefPostObservation(
        val branch: String,
        val commitSha: String,
        val treeSha: String,
        val treeEntries: Map<String, String>,
    )

    @Test
    fun `canonical tree oid matches independently generated Git SHA1 and SHA256 vectors`() {
        val names = listOf("\uD800\uDC00", "\uE000", "a.c", "中文")
        val vectors = listOf(
            Triple(
                "ee8c1ee49b4799bbd170233915a897c19e3b55e1",
                "4b825dc642cb6eb9a060e54bf8d69288fbee4904",
                "69cf418d00c7ed155b15869acb9bd25187838d61",
            ),
            Triple(
                "fc2593998f8e1dec9c3a8be11557888134dad90ef5c7a2d6236ed75534c7698e",
                "6ef19b41225c5369f1c104d45d8d85efa9b057b53b14b4b9b939dd74decc5321",
                "b6ab1dc61af0027f736f456f18bedd3eafcb995d7bf05b0361d9208b3e273b08",
            ),
        )
        vectors.forEach { (blob, emptyTree, root) ->
            val entries = names.map { SyncGitTreeEntry(it, "100644", "blob", blob, 8) } +
                SyncGitTreeEntry("a", "040000", "tree", emptyTree, null)
            assertEquals(root, gitTreeOid(entries, blob.length))
        }
    }

    @Test
    fun `graphql adapter requires complete commit data and surfaces errors`() {
        val request = SyncGraphQlSingleBatchAdapter.mutation("owner/repo", "head-1", "batch-1")
        assertEquals("head-1", request.variables.expectedHead)
        assertEquals("batch-1", request.variables.clientMutationId)
        assertTrue(
            SyncGraphQlSingleBatchAdapter.parse(200, """{"data":{"createCommit":{"oid":"commit-1"}}}""").isSuccess,
        )
        assertTrue(
            SyncGraphQlSingleBatchAdapter.parse(
                200,
                """{"data":null,"errors":[{"message":"protected branch"}]}""",
            ).isFailure,
        )
        assertTrue(SyncGraphQlSingleBatchAdapter.parse(500, "{}").isFailure)
    }

    @Test
    fun `inline tree planner keeps UTF8 entries inline and binary entries as blobs`() {
        val entries = SyncTreeEntryPayloadPlanner.plan(
            listOf(
                "a.json" to "{\"ok\":true}".encodeToByteArray(),
                "encrypted.bin" to byteArrayOf(0x00, 0xFF.toByte(), 0x01),
            ),
        )

        assertEquals("{\"ok\":true}", entries[0].inlineContent)
        assertEquals(null, entries[1].inlineContent)
        assertEquals(2, entries.size)
    }

    @Test
    fun `inline tree planner falls back when final body exceeds budget`() {
        val entries = SyncTreeEntryPayloadPlanner.plan(
            listOf("large.json" to "x".repeat(128).encodeToByteArray()),
            maxBodyBytes = 64,
        )

        assertEquals(null, entries.single().inlineContent)
    }

    @Test
    fun `blob cache single flights concurrent loads and isolates validation context`() = runTest {
        val cache = SyncBlobCache()
        val key = SyncBlobCacheKey(
            "https://api.example",
            "owner/repo",
            "main",
            "git-sha1",
            "a".repeat(40),
            "space",
            "rev-1",
        )
        val otherScope = key.copy(validationScope = "other-space")
        val otherRevision = key.copy(connectionRevision = "rev-2")
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val jobs = (1..8).map {
            async(Dispatchers.Default) {
                cache.getOrLoad(key) {
                    calls.incrementAndGet()
                    started.complete(Unit)
                    release.await()
                    byteArrayOf(1)
                }
            }
        }
        started.await()
        release.complete(Unit)
        jobs.awaitAll().forEach { assertEquals(listOf(1.toByte()), it.toList()) }
        assertEquals(1, calls.get())
        assertEquals(
            listOf(1.toByte()),
            cache.getOrLoad(key) {
                calls.incrementAndGet()
                byteArrayOf(9)
            }.toList(),
        )
        assertEquals(1, calls.get(), "a validated object must remain available after the single flight completes")
        assertEquals(byteArrayOf(2).toList(), cache.getOrLoad(otherScope) { byteArrayOf(2) }.toList())
        assertEquals(byteArrayOf(3).toList(), cache.getOrLoad(otherRevision) { byteArrayOf(3) }.toList())
    }

    @Test
    fun `cancelling the single flight owner does not cancel another valid waiter`() = runTest {
        val cache = SyncBlobCache()
        val key =
            SyncBlobCacheKey("https://api.example", "owner/repo", "main", "git-sha1", "b".repeat(40), "space", "rev")
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val owner = async(Dispatchers.Default) {
            cache.getOrLoad(key) {
                started.complete(Unit)
                release.await()
                byteArrayOf(7)
            }
        }
        started.await()
        owner.cancel()
        val waiter = async(Dispatchers.Default) { cache.getOrLoad(key) { error("duplicate load") } }
        release.complete(Unit)
        owner.join()
        assertEquals(listOf(7.toByte()), waiter.await().toList())
    }

    @Test
    fun `create blob rejects a response whose oid does not match uploaded bytes`() = runTest {
        GitFixture().use { git ->
            val transport = git.transport()
            assertTrue(transport.initialize(repository, "space", 1) is SyncInitializationResult.Initialized)
            val snapshot = transport.readSnapshot(repository, "space", 1).getOrThrow()
            val before = git.head("mihon-sync")
            git.nextCreateBlobResponse = MockResponse(
                code = 201,
                body = buildJsonObject { put("sha", "0".repeat(40)) }.toString(),
            )
            val input = batch("device-a")
            val result = SyncBatchSyncService(transport, secret)
                .upload(repository, snapshot, input, path(input), persist = {})
            assertEquals(SyncPublishStatus.FAILED, result.publish.status)
            assertEquals(before, git.head("mihon-sync"))
        }
    }

    @Test
    fun `initialization preserves README and exchanges a real batch`() = runTest {
        GitFixture().use { git ->
            val transport = git.transport()
            val initial = transport.initialize(repository, "space", 1)
            assertTrue(
                initial is SyncInitializationResult.Initialized,
                "Explicit setup must initialize a chosen private repository containing README",
            )
            val snapshot = transport.readSnapshot(repository, "space", 1).getOrThrow()
            val service = SyncBatchSyncService(transport, secret)
            val batch = batch("device-a")
            val uploaded = service.upload(repository, snapshot, batch, path(batch), persist = {})
            assertEquals(SyncPublishStatus.PUBLISHED, uploaded.publish.status)
            val confirmed = requireNotNull(uploaded.publish.confirmedSnapshot)
            assertEquals(uploaded.publish.commitSha, confirmed.head)
            assertEquals(listOf(batch.batchId), confirmed.batches.map { it.batchId })
            val downloaded = transport.readSnapshot(repository, "space", 1).getOrThrow()
            val received = service.receive(downloaded, downloaded.batches.single())
            assertEquals(batch, received.batch)
            assertEquals("# Existing private repository", git.file("mihon-sync", "README.md")?.decodeToString())
            assertTrue(git.forceFlags.all { !it })
            assertTrue(git.invalidBaseTrees.isEmpty(), "Git tree updates must use a tree SHA, not a commit SHA")
        }
    }

    @Test
    fun `re-reading an unchanged snapshot only probes ref`() = runTest {
        GitFixture().use { git ->
            val transport = git.transport()
            assertTrue(transport.initialize(repository, "space", 1) is SyncInitializationResult.Initialized)
            transport.readSnapshot(repository, "space", 1).getOrThrow()
            val before = git.server.requestCount
            val beforeRecursive = git.recursiveTreeRequests
            val beforeTrees = git.treeRequests

            transport.readSnapshot(repository, "space", 1).getOrThrow()

            check(git.server.requestCount - before == 1) {
                "request delta=${git.server.requestCount - before}"
            }
            assertEquals(beforeRecursive, git.recursiveTreeRequests)
            assertEquals(beforeTrees, git.treeRequests)
        }
    }

    @Test
    fun `blob response with a different object id is rejected`() = runTest {
        GitFixture().use { git ->
            val transport = git.transport()
            assertTrue(transport.initialize(repository, "space", 1) is SyncInitializationResult.Initialized)
            val bootstrap = requireNotNull(git.file("mihon-sync", ".mihon-sync/index/bootstrap/0/bootstrap.bin"))
            git.nextBlobResponse = MockResponse(
                body = buildJsonObject {
                    put("sha", "0".repeat(40))
                    put("encoding", "base64")
                    put("content", bootstrap.toByteString().base64())
                }.toString(),
            )

            assertTrue(git.transport().readSnapshot(repository, "space", 1).isFailure)
        }
    }

    @Test
    fun `raw blob response is accepted and validated by git object id`() = runTest {
        GitFixture().use { git ->
            val transport = git.transport()
            assertTrue(transport.initialize(repository, "space", 1) is SyncInitializationResult.Initialized)
            val bootstrap = requireNotNull(git.file("mihon-sync", ".mihon-sync/index/bootstrap/0/bootstrap.bin"))
            git.nextBlobResponse = MockResponse(
                code = 200,
                headers = headersOf("Content-Type", "application/octet-stream"),
                body = bootstrap.decodeToString(),
            )
            assertTrue(transport.readSnapshot(repository, "space", 1).isSuccess)
        }
    }

    @Test
    fun `stale publishers retain both actors and immutable index bytes`() = runTest {
        GitFixture().use { git ->
            val first = git.transport()
            val second = git.transport()
            assertTrue(first.initialize(repository, "space", 1) is SyncInitializationResult.Initialized)
            val sameSnapshot = first.readSnapshot(repository, "space", 1).getOrThrow()
            val engine = SyncAeadEngineFactory.create()
            val batchA = batch("device-a")
            val batchB = batch("device-b")
            val encryptedA = SyncBatchEncryption.encrypt(engine, secret, batchA, path(batchA))
            val encryptedB = SyncBatchEncryption.encrypt(engine, secret, batchB, path(batchB))
            assertEquals(
                SyncPublishStatus.PUBLISHED,
                first.publish(repository, sameSnapshot, first.prepare(sameSnapshot, encryptedA)).status,
            )
            val publishedB = second.publish(repository, sameSnapshot, second.prepare(sameSnapshot, encryptedB))
            assertEquals(SyncPublishStatus.PUBLISHED, publishedB.status)
            assertTrue(git.conflicts.get() > 0, "The fixture must exercise a genuine non-fast-forward response")
            val result = first.readSnapshot(repository, "space", 1).getOrThrow()
            assertEquals(setOf(batchA.batchId, batchB.batchId), result.batches.map { it.batchId }.toSet())
            val received = result.batches.map { SyncBatchSyncService(first, secret).receive(result, it).batch }.toSet()
            assertEquals(setOf(batchA, batchB), received)
            val rewrittenB = git.pathWrites.filterKeys { it.contains(batchB.batchId) }
            assertTrue(rewrittenB.isNotEmpty())
            for ((writtenPath, contents) in rewrittenB) {
                assertEquals(1, contents.distinct().size, "Retry rewrote immutable artifact $writtenPath")
            }
            assertTrue(git.forceFlags.all { !it })
        }
    }

    @Test
    fun `lost ref response cannot confirm a different ciphertext merely from matching index metadata`() = runTest {
        GitFixture().use { git ->
            val transport = git.transport()
            assertTrue(transport.initialize(repository, "space", 1) is SyncInitializationResult.Initialized)
            val snapshot = transport.readSnapshot(repository, "space", 1).getOrThrow()
            val batch = batch("device-a")
            git.corruptNextPublishedPath = path(batch)
            val result = SyncBatchSyncService(
                transport,
                secret,
            ).upload(repository, snapshot, batch, path(batch), persist = {
            })
            assertNotEquals(SyncPublishStatus.PUBLISHED, result.publish.status)
            assertEquals(batch.batchId, result.encryptedBatch.batchId)
            assertEquals(
                batch,
                SyncBatchEncryption.decrypt(SyncAeadEngineFactory.create(), secret, result.encryptedBatch),
            )
        }
    }

    @Test
    fun `transport cancellation remains coroutine cancellation instead of a failed sync result`() = runTest {
        GitFixture().use { git ->
            val transport = GitHubSyncTransport(
                OkHttpClient(),
                tokenProvider = { throw CancellationException("synthetic cancellation") },
                apiBaseUrl = git.baseUrl,
                indexSecret = secret,
            )
            var propagated = false
            try {
                transport.readSnapshot(repository, "space", 1)
            } catch (_: CancellationException) {
                propagated = true
            }
            assertTrue(propagated)
            assertEquals(0, git.server.requestCount)
        }
    }

    @Test
    fun `legacy transport refuses to initialize an empty repository without v3 intent`() = runTest {
        GitFixture(empty = true).use { git ->
            val transport = git.transport()
            val result = transport.initialize(repository, "space", 1)

            assertTrue(result is SyncInitializationResult.NeedsExplicitAction)
            assertTrue(git.contentsPutBodies.isEmpty(), "legacy initialization must not write a fixed bootstrap marker")
            assertTrue(git.pathWrites.isEmpty())
            assertFalse(git.hasBranch("mihon-sync"))
            assertTrue(git.forceFlags.isEmpty())
        }
    }

    @Test
    fun `a different ciphertext for an already published batch cannot overwrite its immutable path`() = runTest {
        GitFixture().use { git ->
            val transport = git.transport()
            assertTrue(transport.initialize(repository, "space", 1) is SyncInitializationResult.Initialized)
            val before = transport.readSnapshot(repository, "space", 1).getOrThrow()
            val batch = batch("device-a")
            val engine = SyncAeadEngineFactory.create()
            val original = SyncBatchEncryption.encrypt(engine, secret, batch, path(batch))
            assertEquals(
                SyncPublishStatus.PUBLISHED,
                transport.publish(repository, before, transport.prepare(before, original)).status,
            )
            val current = transport.readSnapshot(repository, "space", 1).getOrThrow()
            val remoteBytes = git.file("mihon-sync", path(batch))!!.toByteString()
            val conflicting = SyncBatchEncryption.encrypt(engine, secret, batch, path(batch))
            assertNotEquals(original.ciphertext, conflicting.ciphertext)
            val result = runCatching { transport.publish(repository, current, transport.prepare(current, conflicting)) }
            assertTrue(result.isFailure || result.getOrThrow().status != SyncPublishStatus.PUBLISHED)
            assertEquals(remoteBytes, git.file("mihon-sync", path(batch))!!.toByteString())
        }
    }

    @Test
    fun `a batch bound to another space cannot be published into the current snapshot`() = runTest {
        GitFixture().use { git ->
            val transport = git.transport()
            assertTrue(transport.initialize(repository, "space", 1) is SyncInitializationResult.Initialized)
            val snapshot = transport.readSnapshot(repository, "space", 1).getOrThrow()
            val local = batch("device-a")
            val other = local.copy(
                spaceId = "other-space",
                events = local.events.map { it.copy(spaceId = "other-space") },
            )
            val outcome = runCatching {
                SyncBatchSyncService(transport, secret).upload(repository, snapshot, other, path(other), persist = {})
            }
            assertTrue(outcome.isFailure || outcome.getOrThrow().publish.status != SyncPublishStatus.PUBLISHED)
            assertTrue(transport.readSnapshot(repository, "space", 1).getOrThrow().batches.isEmpty())
        }
    }

    @Test
    fun `stale snapshot cannot overwrite a batch published with different frozen bytes`() = runTest {
        GitFixture().use { git ->
            val first = git.transport()
            assertTrue(first.initialize(repository, "space", 1) is SyncInitializationResult.Initialized)
            val stale = first.readSnapshot(repository, "space", 1).getOrThrow()
            val batch = batch("device-a")
            val engine = SyncAeadEngineFactory.create()
            val original = SyncBatchEncryption.encrypt(engine, secret, batch, path(batch))
            assertEquals(
                SyncPublishStatus.PUBLISHED,
                first.publish(repository, stale, first.prepare(stale, original)).status,
            )
            val before = git.file("mihon-sync", path(batch))!!.toByteString()
            val conflicting = SyncBatchEncryption.encrypt(engine, secret, batch, path(batch))
            val result = runCatching { first.publish(repository, stale, first.prepare(stale, conflicting)) }
            assertEquals(before, git.file("mihon-sync", path(batch))!!.toByteString())
            assertTrue(result.isFailure || result.getOrThrow().status != SyncPublishStatus.PUBLISHED)
        }
    }

    @Test
    fun `recreated service reuses immutable index bytes when retrying a saved artifact`() = runTest {
        GitFixture().use { git ->
            val first = git.transport()
            assertTrue(first.initialize(repository, "space", 1) is SyncInitializationResult.Initialized)
            val snapshot = first.readSnapshot(repository, "space", 1).getOrThrow()
            val batch = batch("device-a")
            val prepared = SyncUploadArtifactCodec.decode(
                SyncUploadArtifactCodec.encode(
                    SyncBatchSyncService(first, secret).prepare(snapshot, batch, path(batch)),
                ),
            )
            val firstResult = SyncBatchSyncService(first, secret).uploadPrepared(repository, snapshot, prepared)
            assertEquals(SyncPublishStatus.PUBLISHED, firstResult.publish.status)
            val indexPath = ".mihon-sync/index/device-a/1/${batch.batchId}.bin"
            val originalIndex = git.file("mihon-sync", indexPath)!!.toByteString()
            val restored = SyncBatchSyncService(git.transport(), secret)
            val retry = restored.uploadPrepared(repository, snapshot, prepared)
            assertEquals(SyncPublishStatus.PUBLISHED, retry.publish.status)
            assertEquals(originalIndex, git.file("mihon-sync", indexPath)!!.toByteString())
        }
    }

    @Test
    fun `an outgoing actor batch cannot skip a sequence`() = runTest {
        GitFixture().use { git ->
            val transport = git.transport()
            assertTrue(transport.initialize(repository, "space", 1) is SyncInitializationResult.Initialized)
            val batch = batch("device-a")
            val initial = transport.readSnapshot(repository, "space", 1).getOrThrow()
            val service = SyncBatchSyncService(transport, secret)
            assertEquals(
                SyncPublishStatus.PUBLISHED,
                service.upload(repository, initial, batch, path(batch), persist = {
                }).publish.status,
            )
            val current = transport.readSnapshot(repository, "space", 1).getOrThrow()
            val gap = batch.copy(
                batchId = "device-a-batch-3",
                events = batch.events.map { it.copy(seq = 3, batchId = "device-a-batch-3") },
            )
            val result = runCatching { service.upload(repository, current, gap, path(gap), persist = {}) }
            assertTrue(result.isFailure)
        }
    }

    @Test
    fun `an indexed actor without its encrypted head is not a complete snapshot`() = runTest {
        GitFixture().use { git ->
            val transport = git.transport()
            assertTrue(transport.initialize(repository, "space", 1) is SyncInitializationResult.Initialized)
            val initial = transport.readSnapshot(repository, "space", 1).getOrThrow()
            val batch = batch("device-a")
            val result = SyncBatchSyncService(
                transport,
                secret,
            ).upload(repository, initial, batch, path(batch), persist = {
            })
            assertEquals(SyncPublishStatus.PUBLISHED, result.publish.status)
            git.removeFile("mihon-sync", ".mihon-sync/heads/device-a/1.bin")
            assertTrue(transport.readSnapshot(repository, "space", 1).isFailure)
        }
    }

    @Test
    fun `prepare freezes batch index and head without starting network requests`() = runTest {
        GitFixture().use { git ->
            var encryptions = 0
            val delegate = SyncAeadEngineFactory.create()
            val engine = object : SyncAeadEngine by delegate {
                override fun encrypt(secret: SyncSecret, plaintext: ByteArray, aad: ByteArray): SyncAeadCiphertext {
                    encryptions++
                    return delegate.encrypt(secret, plaintext, aad)
                }
            }
            val transport = git.transport(engine)
            transport.initialize(repository, "space", 1)
            val snapshot = transport.readSnapshot(repository, "space", 1).getOrThrow()
            val requests = git.server.requestCount
            val before = encryptions
            val input = batch("device-a")
            SyncBatchSyncService(transport, secret, engine).prepare(snapshot, input, path(input))
            assertEquals(before + 3, encryptions)
            assertEquals(requests, git.server.requestCount)
        }
    }

    @Test
    fun `failed durable save cannot make any Git HTTP request`() = runTest {
        GitFixture().use { git ->
            val transport = git.transport()
            transport.initialize(repository, "space", 1)
            val snapshot = transport.readSnapshot(repository, "space", 1).getOrThrow()
            val requests = git.server.requestCount
            val input = batch("device-a")
            val result = runCatching {
                SyncBatchSyncService(transport, secret).upload(repository, snapshot, input, path(input)) {
                    throw IOException("synthetic disk full")
                }
            }
            assertTrue(result.isFailure)
            assertEquals(requests, git.server.requestCount)
            assertEquals(null, git.file("mihon-sync", path(input)))
        }
    }

    @Test
    fun `indexed missing batch and an actor head rolled back within a newer Git commit are rejected`() = runTest {
        for (removeBatch in listOf(true, false)) {
            GitFixture().use { git ->
                val transport = git.transport()
                transport.initialize(repository, "space", 1)
                val input = batch("device-a")
                val service = SyncBatchSyncService(transport, secret)
                service.upload(
                    repository,
                    transport.readSnapshot(
                        repository,
                        "space",
                        1,
                    ).getOrThrow(),
                    input,
                    path(input),
                    persist = {
                    },
                )
                val headPath = ".mihon-sync/heads/device-a/1.bin"
                val oldHead = requireNotNull(git.file("mihon-sync", headPath)).copyOf()
                if (removeBatch) {
                    git.removeFile("mihon-sync", path(input))
                } else {
                    val second = input.copy(
                        batchId = "batch-2",
                        events = input.events.map {
                            it.copy(seq = 2, batchId = "batch-2")
                        },
                    )
                    service.upload(
                        repository,
                        transport.readSnapshot(
                            repository,
                            "space",
                            1,
                        ).getOrThrow(),
                        second,
                        path(second),
                        persist = {
                        },
                    )
                    git.replaceFile("mihon-sync", headPath, oldHead)
                }
                assertTrue(transport.readSnapshot(repository, "space", 1).isFailure)
            }
        }
    }

    @Test
    fun `competing initializers adopt matching winner and require import for a different key`() = runTest {
        for (sameKey in listOf(true, false)) {
            GitFixture().use { git ->
                git.createSyncBranch()
                git.refUpdateBarrier = CountDownLatch(2)
                val first = git.transport()
                val otherKey = if (sameKey) secret else SyncSecret.fromBytes(ByteArray(32) { 8 })
                val second = git.transport(key = otherKey)
                val a = async(Dispatchers.IO) { first.initialize(repository, "space", 1) }
                val b = async(Dispatchers.IO) { second.initialize(repository, "space", 1) }
                val results = listOf(a.await(), b.await())
                assertEquals(1, results.count { it is SyncInitializationResult.Initialized })
                if (sameKey) {
                    assertEquals(1, results.count { it is SyncInitializationResult.Adopted })
                    assertTrue(first.readSnapshot(repository, "space", 1).isSuccess)
                } else {
                    assertEquals(1, results.count { it is SyncInitializationResult.NeedsExplicitAction })
                    assertEquals(1, listOf(first, second).count { it.readSnapshot(repository, "space", 1).isSuccess })
                }
                assertTrue(git.file("mihon-sync", "README.md") != null)
            }
        }
    }

    @Test
    fun `snapshot keeps its immutable blob identities when the branch moves`() = runTest {
        GitFixture().use { git ->
            val transport = git.transport()
            transport.initialize(repository, "space", 1)
            val input = batch("device-a")
            val service = SyncBatchSyncService(transport, secret)
            service.upload(
                repository,
                transport.readSnapshot(
                    repository,
                    "space",
                    1,
                ).getOrThrow(),
                input,
                path(input),
                persist = {
                },
            )
            val snapshot = transport.readSnapshot(repository, "space", 1).getOrThrow()
            git.replaceFile("mihon-sync", path(input), "broken later commit".encodeToByteArray())
            assertEquals(input, service.receive(snapshot, snapshot.batches.single()).batch)
            val later = transport.readSnapshot(repository, "space", 1).getOrThrow()
            assertEquals(null, service.receive(later, later.batches.single()).batch)
        }
    }

    @Test
    fun `truncated and malformed tree completion flags cannot report a complete snapshot`() = runTest {
        for (flag in listOf(JsonPrimitive(true), JsonPrimitive("false"), kotlinx.serialization.json.JsonNull)) {
            GitFixture().use { git ->
                git.transport().initialize(repository, "space", 1)
                git.truncatedFlag = flag
                // Use a fresh reader so the deliberately inconsistent fixture response is not
                // hidden by a valid immutable-tree cache entry created during initialization.
                assertTrue(git.transport().readSnapshot(repository, "space", 1).isFailure)
            }
        }
    }

    @Test
    fun `HTTP failures and malformed HEAD responses cannot become an empty successful sync`() = runTest {
        for (code in listOf(403, 429, 500, 200)) {
            GitFixture().use { git ->
                val transport = git.transport()
                transport.initialize(repository, "space", 1)
                val before = git.file("mihon-sync", ".mihon-sync/index/bootstrap/0/bootstrap.bin")!!.toByteString()
                git.nextReadFailure = MockResponse(code = code, body = "synthetic malformed response")
                assertTrue(transport.readSnapshot(repository, "space", 1).isFailure)
                assertEquals(
                    before,
                    git.file("mihon-sync", ".mihon-sync/index/bootstrap/0/bootstrap.bin")!!.toByteString(),
                )
            }
        }
    }

    @Test
    fun `an existing branch never bypasses the private repository check during initialization`() = runTest {
        GitFixture().use { git ->
            git.createSyncBranch()
            git.privateRepository = false
            val result = git.transport().initialize(repository, "space", 1)
            assertTrue(result is SyncInitializationResult.Failed)
            assertEquals(null, git.file("mihon-sync", ".mihon-sync/index/bootstrap/0/bootstrap.bin"))
        }
    }

    @Test
    fun `lost publish response is confirmed by reading the reachable frozen bytes`() = runTest {
        GitFixture().use { git ->
            val transport = git.transport()
            transport.initialize(repository, "space", 1)
            val snapshot = transport.readSnapshot(repository, "space", 1).getOrThrow()
            val input = batch("device-a")
            git.nextRefResponse = MockResponse(code = 500)
            val result = SyncBatchSyncService(
                transport,
                secret,
            ).upload(repository, snapshot, input, path(input), persist = {
            })
            assertEquals(SyncPublishStatus.PUBLISHED, result.publish.status)
            assertEquals(1, result.publish.attempts)
            assertEquals(
                result.encryptedBatch.batchId,
                transport.readSnapshot(repository, "space", 1).getOrThrow().batches.single().batchId,
            )
        }
    }

    @Test
    fun `unconfirmed publication retains its artifact and retry confirms without creating new objects`() = runTest {
        GitFixture().use { git ->
            val transport = git.transport()
            transport.initialize(repository, "space", 1)
            val snapshot = transport.readSnapshot(repository, "space", 1).getOrThrow()
            val input = batch("device-a")
            git.failReadAfterPatch = true
            val service = SyncBatchSyncService(transport, secret)
            val result = service.upload(repository, snapshot, input, path(input), persist = {})
            assertEquals(SyncPublishStatus.UNCONFIRMED, result.publish.status)
            assertEquals(null, result.publish.confirmedSnapshot)
            val writes = git.pathWrites.mapValues { it.value.toList() }
            val retry = service.uploadPrepared(
                repository,
                transport.readSnapshot(repository, "space", 1).getOrThrow(),
                result.artifact,
            )
            assertEquals(SyncPublishStatus.PUBLISHED, retry.publish.status)
            assertTrue(retry.publish.confirmedSnapshot != null)
            assertEquals(0, retry.publish.attempts)
            assertEquals(writes, git.pathWrites)
        }
    }

    @Test
    fun `continuous competing writes exhaust three attempts without replacing the saved artifact`() = runTest {
        GitFixture().use { git ->
            val transport = git.transport()
            transport.initialize(repository, "space", 1)
            val snapshot = transport.readSnapshot(repository, "space", 1).getOrThrow()
            val input = batch("device-a")
            git.competingWrites = 10
            val result = SyncBatchSyncService(
                transport,
                secret,
            ).upload(repository, snapshot, input, path(input), persist = {
            })
            assertEquals(SyncPublishStatus.CONFLICT, result.publish.status)
            assertEquals(SyncPublishFailureClass.CONFLICT, result.publish.failureClass)
            assertEquals(3, result.publish.attempts)
            assertEquals(3, git.conflicts.get())
            assertEquals(null, git.file("mihon-sync", path(input)))
            assertEquals(
                input,
                SyncBatchEncryption.decrypt(SyncAeadEngineFactory.create(), secret, result.artifact.encryptedBatch),
            )
            assertTrue(git.pathWrites.filterKeys { input.batchId in it }.values.all { it.distinct().size == 1 })
        }
    }

    @Test
    fun `initialization with a lost response recognizes its completed space`() = runTest {
        GitFixture().use { git ->
            git.nextRefResponse = MockResponse(code = 500)
            val result = git.transport().initialize(repository, "space", 1)
            assertTrue(result is SyncInitializationResult.Initialized || result is SyncInitializationResult.Adopted)
            assertTrue(git.transport().readSnapshot(repository, "space", 1).isSuccess)
        }
    }

    @Test
    fun `initialization never writes into an existing space with missing indexes`() = runTest {
        GitFixture().use { git ->
            val transport = git.transport()
            transport.initialize(repository, "space", 1)
            val snapshot = transport.readSnapshot(repository, "space", 1).getOrThrow()
            val input = batch("device-a")
            val result = SyncBatchSyncService(transport, secret)
                .upload(repository, snapshot, input, path(input), persist = {})
            git.removeFile("mihon-sync", ".mihon-sync/index/bootstrap/0/bootstrap.bin")
            git.removeFile("mihon-sync", result.artifact.indexPath)
            val previousHead = git.head("mihon-sync")
            val writes = git.pathWrites.mapValues { it.value.toList() }
            assertTrue(transport.initialize(repository, "space", 1) is SyncInitializationResult.NeedsExplicitAction)
            assertEquals(previousHead, git.head("mihon-sync"))
            assertEquals(writes, git.pathWrites)
        }
    }

    @Test
    fun `cached published snapshot cannot confirm a damaged or unreadable current ref`() = runTest {
        for (condition in listOf("damaged", "missing", "unreadable")) {
            GitFixture().use { git ->
                val transport = git.transport()
                transport.initialize(repository, "space", 1)
                val initial = transport.readSnapshot(repository, "space", 1).getOrThrow()
                val input = batch("device-a")
                val service = SyncBatchSyncService(transport, secret)
                val uploaded = service.upload(repository, initial, input, path(input), persist = {})
                val cached = transport.readSnapshot(repository, "space", 1).getOrThrow()
                when (condition) {
                    "damaged" -> git.replaceFile("mihon-sync", path(input), byteArrayOf(0))
                    "missing" -> git.removeFile("mihon-sync", path(input))
                    "unreadable" -> git.nextReadFailure = MockResponse(code = 500)
                }
                val writes = git.pathWrites.mapValues { it.value.toList() }
                val result = service.uploadPrepared(repository, cached, uploaded.artifact)
                assertNotEquals(SyncPublishStatus.PUBLISHED, result.publish.status, condition)
                assertEquals(writes, git.pathWrites)
                assertEquals(uploaded.artifact, result.artifact)
            }
        }
    }

    @Test
    fun `reader rejects a fully authenticated index chain with a sequence gap`() = runTest {
        for (sequence in listOf(2L, 3L)) {
            GitFixture().use { git ->
                val transport = git.transport()
                transport.initialize(repository, "space", 1)
                val service = SyncBatchSyncService(transport, secret)
                val first = batch("device-a")
                val initial = transport.readSnapshot(repository, "space", 1).getOrThrow()
                service.upload(repository, initial, first, path(first), persist = {})
                val second = first.copy(
                    batchId = "second",
                    events = first.events.map { it.copy(seq = 2, batchId = "second") },
                )
                val current = transport.readSnapshot(repository, "space", 1).getOrThrow()
                val uploaded = service.upload(repository, current, second, path(second), persist = {})
                val input = second.copy(events = second.events.map { it.copy(seq = sequence) })
                val engine = SyncAeadEngineFactory.create()
                val encrypted = SyncBatchEncryption.encrypt(engine, secret, input, path(input))
                assertEquals(input, SyncBatchEncryption.decrypt(engine, secret, encrypted))
                val digest = encrypted.plaintextDigest.toByteString().hex()
                git.replaceFile(
                    "mihon-sync",
                    path(input),
                    mihon.data.sync.transport.StoredSyncBatch.fromDomain(encrypted).body(),
                )
                fun rewriteManifest(manifestPath: String, bindingId: String, update: (JsonObject) -> JsonObject) {
                    val aad = mihon.domain.sync.crypto.SyncCryptoBinding(
                        1,
                        "space",
                        1,
                        bindingId,
                        manifestPath,
                    ).canonicalAad()
                    val bytes = git.file("mihon-sync", manifestPath)!!
                    val decoded = Json.parseToJsonElement(
                        engine.decrypt(secret, SyncAeadCiphertext(bytes), aad).decodeToString(),
                    ).jsonObject
                    val changed = engine.encrypt(secret, update(decoded).toString().encodeToByteArray(), aad)
                    git.replaceFile("mihon-sync", manifestPath, changed.bytes)
                }
                rewriteManifest(uploaded.artifact.indexPath, "second") { shard ->
                    val metadata = shard.getValue("batch").jsonObject + mapOf(
                        "firstSeq" to JsonPrimitive(sequence),
                        "lastSeq" to JsonPrimitive(sequence),
                        "digestHex" to JsonPrimitive(digest),
                    )
                    JsonObject(shard + ("batch" to JsonObject(metadata)))
                }
                rewriteManifest(uploaded.artifact.headPath, "head-device-a-1") { head ->
                    JsonObject(head + mapOf("lastSeq" to JsonPrimitive(sequence), "digestHex" to JsonPrimitive(digest)))
                }
                assertEquals(sequence == 2L, transport.readSnapshot(repository, "space", 1).isSuccess)
            }
        }
    }

    private fun batch(actor: String): SyncBatch {
        val id = "$actor-batch-1"
        return SyncBatch(
            1,
            "space",
            1,
            id,
            listOf(
                SyncEventEnvelope(
                    protocolVersion = 1,
                    spaceId = "space",
                    generation = 1,
                    actorId = actor,
                    epoch = 1,
                    seq = 1,
                    category = SyncCategory.FAVORITE,
                    effects = listOf(
                        SyncEffect(
                            effectId = "favorite",
                            objectKey = SyncObjectKey(
                                SyncObjectType.MANGA,
                                sourceId = "1",
                                originalUrl = "/$actor/manga",
                            ),
                            field = SyncField.FAVORITE,
                            kind = SyncEffectKind.ADD,
                        ),
                    ),
                    origin = SyncOrigin.USER,
                    batchId = id,
                ),
            ),
        )
    }

    private fun path(batch: SyncBatch): String {
        val actor = batch.events.single().actorId
        return ".mihon-sync/batches/$actor/1/${batch.batchId}.json"
    }

    /**
     * Models Git object reachability and HTTP shapes.
     * It never copies the client's sync codec or merge rules.
     */
    internal inner class GitFixture(
        empty: Boolean = false,
        repositoryOverride: SyncRepository? = null,
        private val realTreeOids: Boolean = empty,
    ) : AutoCloseable {
        private val repository = repositoryOverride ?: this@SyncGitSafetyContractTest.repository
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
                    return synchronized(this@GitFixture) { route(request) }
                }
            }
            server.start()
        }

        fun transport(
            engine: SyncAeadEngine = SyncAeadEngineFactory.create(),
            key: SyncSecret = secret,
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
}
