package mihon.data.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mihon.data.sync.crypto.SyncSpaceCrypto
import mihon.data.sync.transport.GitHubSyncTransport
import mihon.data.sync.transport.SyncBatchSyncService
import mihon.data.sync.transport.SyncUploadArtifactCodec
import mihon.domain.sync.SyncBatch
import mihon.domain.sync.SyncCategory
import mihon.domain.sync.SyncEffect
import mihon.domain.sync.SyncEffectKind
import mihon.domain.sync.SyncEventEnvelope
import mihon.domain.sync.SyncField
import mihon.domain.sync.SyncObjectDescriptor
import mihon.domain.sync.SyncObjectKey
import mihon.domain.sync.SyncObjectType
import mihon.domain.sync.SyncOrigin
import mihon.domain.sync.crypto.SyncSpaceDescriptorCodec
import mihon.domain.sync.crypto.SyncSpaceMaterial
import mihon.domain.sync.crypto.SyncSpacePayload
import mihon.domain.sync.transport.SyncInitializationCheckpoint
import mihon.domain.sync.transport.SyncInitializationIntent
import mihon.domain.sync.transport.SyncInitializationResult
import mihon.domain.sync.transport.SyncInitializationStage
import mihon.domain.sync.transport.SyncPublishStatus
import mihon.domain.sync.transport.SyncRepository
import okhttp3.OkHttpClient
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SyncSpaceTransportContractTest {
    private val repository = SyncRepository("fixture-owner", "private-sync", "mihon-sync")

    @Test
    fun `new unencrypted space atomically publishes descriptor and plain bootstrap`() = runTest {
        initializes("")
    }

    @Test
    fun `new password space atomically publishes descriptor and protected bootstrap`() = runTest {
        initializes("密碼")
    }

    @Test
    fun `new empty GitHub repository with 409 refs initializes without existing commits`() = runTest {
        SyncGitSafetyContractTest().GitFixture(empty = true).use { git ->
            git.emptyRefStatus = 409
            val material = SyncSpaceCrypto.create("space", 1, "")
            val transport = transport(git, material)
            assertTrue(transport.initialize(repository, "space", 1) is SyncInitializationResult.Initialized)
            assertTrue(transport.readSnapshot(repository, "space", 1).isSuccess)
        }
    }

    @Test
    fun `v3 bootstrap is nonce-bound create-only and persists each confirmed stage`() = runTest {
        SyncGitSafetyContractTest().GitFixture(empty = true).use { git ->
            val material = SyncSpaceCrypto.create("space", 1, "bootstrap-password")
            val intent = initializationIntent("attempt-nonce-fixture-0001")
            val checkpoints = mutableListOf<SyncInitializationCheckpoint>()
            val transport = transport(git, material)

            val result = transport.initialize(repository, "space", 1, intent) { checkpoints += it }

            assertTrue(result is SyncInitializationResult.Initialized)
            val firstRefPost = git.refPostObservations.single()
            assertEquals("mihon-sync", firstRefPost.branch)
            assertEquals(
                setOf(
                    ".mihon-sync/bootstrap",
                    ".mihon-sync/index/bootstrap/0/bootstrap.bin",
                    SyncSpaceDescriptorCodec.PATH,
                ),
                firstRefPost.treeEntries.keys,
                "the first POST /git/refs must publish the complete initial space",
            )
            assertEquals(git.head("mihon-sync"), firstRefPost.commitSha)
            assertEquals(git.treeSha("mihon-sync"), firstRefPost.treeSha)
            assertEquals(
                git.file("main", ".mihon-sync/bootstrap")?.toList(),
                git.fileAtRefPost(firstRefPost, ".mihon-sync/bootstrap")?.toList(),
                "the first reference must contain this attempt's bootstrap",
            )
            assertEquals(
                SyncSpaceDescriptorCodec.encode(material.descriptor).toList(),
                git.fileAtRefPost(firstRefPost, SyncSpaceDescriptorCodec.PATH)?.toList(),
            )
            assertTrue(
                git.fileAtRefPost(firstRefPost, ".mihon-sync/index/bootstrap/0/bootstrap.bin")?.isNotEmpty() == true,
            )
            assertTrue(git.refPatchBranches.isEmpty(), "the first ref must not need a follow-up PATCH")
            val bootstrap = requireNotNull(git.file("main", ".mihon-sync/bootstrap")).decodeToString()
            val payload = runCatching { Json.parseToJsonElement(bootstrap).jsonObject }.getOrNull()
            assertTrue(payload != null, "bootstrap must contain structured current-attempt evidence")
            val evidence = requireNotNull(payload)
            assertEquals(1, evidence.getValue("protocolVersion").jsonPrimitive.content.toInt())
            assertEquals(42, evidence.getValue("accountId").jsonPrimitive.content.toInt())
            assertEquals(99, evidence.getValue("repositoryId").jsonPrimitive.content.toInt())
            assertEquals("attempt-nonce-fixture-0001", evidence.getValue("attemptNonce").jsonPrimitive.content)
            assertEquals("space", evidence.getValue("spaceId").jsonPrimitive.content)
            assertEquals(1, evidence.getValue("generation").jsonPrimitive.content.toInt())
            assertEquals(
                java.security.MessageDigest.getInstance("SHA-256")
                    .digest(SyncSpaceDescriptorCodec.encode(material.descriptor)).toByteString().hex(),
                evidence.getValue("descriptorSha256").jsonPrimitive.content,
            )
            assertFalse(bootstrap.contains("bootstrap-password"))
            assertFalse(bootstrap.contains(requireNotNull(material.secret).bytes.toByteString().hex()))
            val putBody = Json.parseToJsonElement(git.contentsPutBodies.single()).jsonObject
            assertFalse("sha" in putBody, "Contents bootstrap must stay create-only")
            assertEquals(
                listOf(
                    SyncInitializationStage.BOOTSTRAP_SUBMITTING,
                    SyncInitializationStage.BOOTSTRAP_CONFIRMED,
                    SyncInitializationStage.SPACE_PUBLISHING,
                    SyncInitializationStage.SPACE_CONFIRMED,
                ),
                checkpoints.map { it.stage },
            )
            val confirmedBootstrap = checkpoints.single {
                it.stage == SyncInitializationStage.BOOTSTRAP_CONFIRMED
            }
            assertEquals(git.head("main"), confirmedBootstrap.bootstrapCommitSha)
            assertEquals(git.treeSha("main"), confirmedBootstrap.bootstrapTreeSha)
            assertEquals(git.head("mihon-sync"), transport.readSnapshot(repository, "space", 1).getOrThrow().head)
            assertTrue(git.forceFlags.all { !it })
        }
    }

    @Test
    fun `v3 lost Contents response resumes by matching nonce without a second PUT`() = runTest {
        SyncGitSafetyContractTest().GitFixture(empty = true).use { git ->
            val material = SyncSpaceCrypto.create("space", 1, "bootstrap-password")
            val intent = initializationIntent("attempt-nonce-lost-response-0001")
            val checkpoints = mutableListOf<SyncInitializationCheckpoint>()
            var persisted = intent
            git.loseNextBootstrapResponse = true

            val first = transport(git, material).initialize(repository, "space", 1, intent) {
                checkpoints += it
                persisted = persisted.copy(
                    stage = it.stage,
                    bootstrapCommitSha = it.bootstrapCommitSha,
                    bootstrapTreeSha = it.bootstrapTreeSha,
                )
            }

            assertTrue(first is SyncInitializationResult.Failed)
            assertEquals(SyncInitializationStage.BOOTSTRAP_SUBMITTING, persisted.stage)
            assertEquals(1, git.contentsPutBodies.size)
            assertFalse(git.hasBranch("mihon-sync"))

            val resumed = transport(git, material).initialize(repository, "space", 1, persisted) {
                checkpoints += it
                persisted = persisted.copy(
                    stage = it.stage,
                    bootstrapCommitSha = it.bootstrapCommitSha,
                    bootstrapTreeSha = it.bootstrapTreeSha,
                )
            }

            assertTrue(resumed is SyncInitializationResult.Initialized || resumed is SyncInitializationResult.Adopted)
            assertEquals(1, git.contentsPutBodies.size, "resume must read the nonce-bound commit before retrying PUT")
            assertTrue(checkpoints.any { it.stage == SyncInitializationStage.BOOTSTRAP_CONFIRMED })
            assertEquals(git.head("main"), persisted.bootstrapCommitSha)
            assertEquals(git.treeSha("main"), persisted.bootstrapTreeSha)
            assertTrue(transport(git, material).readSnapshot(repository, "space", 1).isSuccess)
        }
    }

    @Test
    fun `v3 refuses a bootstrap owned by a different attempt nonce`() = runTest {
        SyncGitSafetyContractTest().GitFixture(empty = true).use { git ->
            val material = SyncSpaceCrypto.create("space", 1, "bootstrap-password")
            val ownerIntent = initializationIntent("attempt-nonce-owner-0001")
            val first = transport(git, material).initialize(repository, "space", 1, ownerIntent) {}
            assertTrue(first is SyncInitializationResult.Initialized)
            val originalBootstrap = git.file("main", ".mihon-sync/bootstrap")
            val originalSyncHead = git.head("mihon-sync")

            val otherAttempt = ownerIntent.copy(attemptNonce = "attempt-nonce-other-0001")
            val result = transport(git, material).initialize(repository, "space", 1, otherAttempt) {}

            assertTrue(result is SyncInitializationResult.NeedsExplicitAction)
            assertEquals(1, git.contentsPutBodies.size)
            assertEquals(originalBootstrap?.toList(), git.file("main", ".mihon-sync/bootstrap")?.toList())
            assertEquals(originalSyncHead, git.head("mihon-sync"))
        }
    }

    @Test
    fun `v3 competing different-password attempts preserve only the published winner`() = runTest {
        SyncGitSafetyContractTest().GitFixture(empty = true).use { git ->
            git.bootstrapPutBarrier = java.util.concurrent.CountDownLatch(2)
            val winnerMaterial = SyncSpaceCrypto.create("space", 1, "winner-password")
            val loserMaterial = SyncSpaceCrypto.create("space", 1, "loser-password")
            val winnerIntent = initializationIntent("attempt-nonce-password-winner-0001")
            val loserIntent = initializationIntent("attempt-nonce-password-loser-0001")
            val winnerTransport = transport(git, winnerMaterial)
            val loserTransport = transport(git, loserMaterial)

            val winner = async(Dispatchers.IO) {
                winnerTransport.initialize(repository, "space", 1, winnerIntent) {}
            }
            val loser = async(Dispatchers.IO) {
                loserTransport.initialize(repository, "space", 1, loserIntent) {}
            }
            val results = listOf(winner.await(), loser.await())

            assertEquals(1, results.count { it is SyncInitializationResult.Initialized })
            assertEquals(1, results.count { it is SyncInitializationResult.NeedsExplicitAction })
            assertEquals(2, git.contentsPutBodies.size, "each contender may make its initial create-only request")
            assertEquals(1, git.bootstrapCommitCount, "the existing bootstrap must reject the losing create")
            assertTrue(git.contentsPutBodies.all { "\"sha\"" !in it }, "both PUTs must remain create-only")
            val winnerIndex = results.indexOfFirst { it is SyncInitializationResult.Initialized }
            val publishedMaterial = if (winnerIndex == 0) winnerMaterial else loserMaterial
            val publishedIntent = if (winnerIndex == 0) winnerIntent else loserIntent
            val losingTransport = if (winnerIndex == 0) loserTransport else winnerTransport
            val bootstrap = Json.parseToJsonElement(
                requireNotNull(git.file("main", ".mihon-sync/bootstrap")).decodeToString(),
            ).jsonObject
            assertEquals(publishedIntent.attemptNonce, bootstrap.getValue("attemptNonce").jsonPrimitive.content)
            assertEquals(
                publishedMaterial.descriptor,
                SyncSpaceDescriptorCodec.decode(
                    requireNotNull(git.file("mihon-sync", SyncSpaceDescriptorCodec.PATH)),
                ).getOrThrow(),
            )
            assertTrue(transport(git, publishedMaterial).readSnapshot(repository, "space", 1).isSuccess)
            assertTrue(losingTransport.readSnapshot(repository, "space", 1).isFailure)
            assertTrue(git.forceFlags.all { !it })
        }
    }

    @Test
    fun `v3 rejects repository identity mismatch before bootstrap write`() = runTest {
        SyncGitSafetyContractTest().GitFixture(empty = true).use { git ->
            val material = SyncSpaceCrypto.create("space", 1, "bootstrap-password")
            val wrongRepository = initializationIntent("attempt-nonce-wrong-repo-0001")
                .copy(repositoryId = 100)

            val result = transport(git, material).initialize(repository, "space", 1, wrongRepository) {}

            assertFalse(result is SyncInitializationResult.Initialized)
            assertTrue(git.contentsPutBodies.isEmpty())
            assertFalse(git.hasBranch("mihon-sync"))
        }
    }

    @Test
    fun `v3 rejects the sync target branch as the default bootstrap branch`() = runTest {
        SyncGitSafetyContractTest().GitFixture(empty = true).use { git ->
            val material = SyncSpaceCrypto.create("space", 1, "bootstrap-password")
            val intent = initializationIntent("attempt-nonce-same-branch-0001")
                .copy(defaultBranch = repository.branch)

            val result = transport(git, material).initialize(repository, "space", 1, intent) {}

            assertFalse(result is SyncInitializationResult.Initialized)
            assertTrue(git.contentsPutBodies.isEmpty())
            assertFalse(git.hasBranch("mihon-sync"))
        }
    }

    @Test
    fun `v3 refuses to bootstrap when a non-head Git ref exists`() = runTest {
        SyncGitSafetyContractTest().GitFixture(empty = true).use { git ->
            git.otherRefs["refs/notes/commits"] = "0".repeat(40)
            git.repositorySizeOverride = 0
            val material = SyncSpaceCrypto.create("space", 1, "bootstrap-password")
            val intent = initializationIntent("attempt-nonce-not-empty-0001")

            val result = transport(git, material).initialize(repository, "space", 1, intent) {}

            assertFalse(result is SyncInitializationResult.Initialized)
            assertTrue(git.contentsPutBodies.isEmpty())
            assertFalse(git.hasBranch("mihon-sync"))
        }
    }

    @Test
    fun `v3 refuses nonzero repository size even when every reference is missing`() = runTest {
        SyncGitSafetyContractTest().GitFixture(empty = false).use { git ->
            git.removeAllRefs()
            git.repositorySizeOverride = 1
            val material = SyncSpaceCrypto.create("space", 1, "bootstrap-password")

            val result = transport(git, material).initialize(
                repository,
                "space",
                1,
                initializationIntent("attempt-nonce-size-history-0001"),
            ) {}

            assertFalse(result is SyncInitializationResult.Initialized)
            assertTrue(git.contentsPutBodies.isEmpty())
            assertFalse(git.hasBranch("mihon-sync"))
        }
    }

    @Test
    fun `v3 rechecks repository size before retry after a bootstrap failure`() = runTest {
        SyncGitSafetyContractTest().GitFixture(empty = false).use { git ->
            git.removeAllRefs()
            git.repositorySizeOverride = 0
            git.failNextBootstrapBeforeCommit = true
            git.repositorySizeAfterBootstrapFailure = 1
            val material = SyncSpaceCrypto.create("space", 1, "bootstrap-password")

            val result = transport(git, material).initialize(
                repository,
                "space",
                1,
                initializationIntent("attempt-nonce-size-recheck-0001"),
            ) {}

            assertFalse(result is SyncInitializationResult.Initialized)
            assertEquals(1, git.contentsPutBodies.size, "a stale empty proof must not trigger a second PUT")
            assertFalse(git.hasBranch("mihon-sync"))
            assertEquals(1L, git.repositorySizeOverride)
        }
    }

    @Test
    fun `v3 stops after a concurrent external file appears during create-only bootstrap`() = runTest {
        SyncGitSafetyContractTest().GitFixture(empty = true).use { git ->
            git.externalFileBeforeNextBootstrapPut = "README.md" to "created by another client".encodeToByteArray()
            val material = SyncSpaceCrypto.create("space", 1, "bootstrap-password")
            val intent = initializationIntent("attempt-nonce-external-file-0001")
            val checkpoints = mutableListOf<SyncInitializationCheckpoint>()

            val result = transport(git, material).initialize(repository, "space", 1, intent) {
                checkpoints += it
            }

            assertTrue(result is SyncInitializationResult.NeedsExplicitAction)
            assertEquals("created by another client", git.file("main", "README.md")?.decodeToString())
            assertTrue(
                git.file("main", ".mihon-sync/bootstrap") != null,
                "keep any bootstrap this attempt may have written",
            )
            assertFalse(git.hasBranch("mihon-sync"), "do not publish sync ref after the tree became non-empty")
            assertEquals(1, git.contentsPutBodies.size)
            val body = Json.parseToJsonElement(git.contentsPutBodies.single()).jsonObject
            assertFalse("sha" in body, "the bootstrap write must remain create-only")
            assertEquals(listOf(SyncInitializationStage.BOOTSTRAP_SUBMITTING), checkpoints.map { it.stage })
            assertTrue(git.forceFlags.all { !it })
        }
    }

    @Test
    fun `v3 recovers a lost sync ref creation response by reading back the exact branch`() = runTest {
        SyncGitSafetyContractTest().GitFixture(empty = true).use { git ->
            val material = SyncSpaceCrypto.create("space", 1, "bootstrap-password")
            val intent = initializationIntent("attempt-nonce-ref-response-0001")
            git.loseNextSyncBranchCreateResponse = true

            val result = transport(git, material).initialize(repository, "space", 1, intent) {}

            assertTrue(result is SyncInitializationResult.Initialized || result is SyncInitializationResult.Adopted)
            assertTrue(git.hasBranch("mihon-sync"))
            assertTrue(transport(git, material).readSnapshot(repository, "space", 1).isSuccess)
            assertTrue(git.forceFlags.all { !it })
        }
    }

    @Test
    fun `v2 initialization refuses an unrelated README without making any writes`() = runTest {
        for (existingBranch in listOf(false, true)) {
            SyncGitSafetyContractTest().GitFixture().use { git ->
                if (existingBranch) git.createSyncBranch()
                val before = if (existingBranch) git.head("mihon-sync") else null
                val transport = transport(git, SyncSpaceCrypto.create("space", 1, ""))
                assertFalse(transport.initialize(repository, "space", 1) is SyncInitializationResult.Initialized)
                if (existingBranch) {
                    assertEquals(before, git.head("mihon-sync"))
                } else {
                    assertThrows(NoSuchElementException::class.java) { git.head("mihon-sync") }
                }
                assertTrue(git.pathWrites.isEmpty())
            }
        }
    }

    @Test
    fun `v2 initialization verifies exact bootstrap contents and rejects extra sync branch files`() = runTest {
        for (corruptContent in listOf(false, true)) {
            SyncGitSafetyContractTest().GitFixture().use { git ->
                git.removeFile("main", "README.md")
                git.replaceFile("main", ".mihon-sync/bootstrap", "mihon-sync bootstrap".encodeToByteArray())
                git.createSyncBranch()
                if (corruptContent) {
                    git.replaceFile("mihon-sync", ".mihon-sync/bootstrap", "not app content".encodeToByteArray())
                } else {
                    git.replaceFile("mihon-sync", "user-file.txt", "user data".encodeToByteArray())
                }
                val before = git.head("mihon-sync")
                val transport = transport(git, SyncSpaceCrypto.create("space", 1, ""))
                assertFalse(transport.initialize(repository, "space", 1) is SyncInitializationResult.Initialized)
                assertEquals(before, git.head("mihon-sync"))
                assertTrue(git.pathWrites.isEmpty())
            }
        }
    }

    @Test
    fun `fixed bootstrap text cannot claim a repository for a new attempt`() = runTest {
        SyncGitSafetyContractTest().GitFixture().use { git ->
            git.removeFile("main", "README.md")
            git.replaceFile("main", ".mihon-sync/bootstrap", "mihon-sync bootstrap".encodeToByteArray())
            git.createSyncBranch()
            val mainHead = git.head("main")
            val syncHead = git.head("mihon-sync")
            val transport = transport(git, SyncSpaceCrypto.create("space", 1, ""))

            val result = transport.initialize(repository, "space", 1)

            assertTrue(result is SyncInitializationResult.NeedsExplicitAction)
            assertEquals(mainHead, git.head("main"))
            assertEquals(syncHead, git.head("mihon-sync"))
            assertTrue(git.contentsPutBodies.isEmpty())
            assertTrue(git.pathWrites.isEmpty())
        }
    }

    private suspend fun initializes(password: String) {
        SyncGitSafetyContractTest().GitFixture(empty = true).use { git ->
            val material = SyncSpaceCrypto.create("space", 1, password)
            val transport = GitHubSyncTransport(OkHttpClient(), { "synthetic" }, git.baseUrl, spaceMaterial = material)
            assertTrue(
                transport.initialize(repository, "space", 1) is SyncInitializationResult.Initialized,
                "v2 initialization must support mode ${material.descriptor.mode}",
            )
            assertEquals(
                material.descriptor,
                SyncSpaceDescriptorCodec.decode(
                    requireNotNull(git.file("mihon-sync", SyncSpaceDescriptorCodec.PATH)),
                ).getOrThrow(),
            )
            assertTrue(transport.readSnapshot(repository, "space", 1).isSuccess)
        }
    }

    @Test
    fun `both modes exchange through real codecs and restore identical upload artifacts`() = runTest {
        for (password in listOf("", "密碼")) {
            SyncGitSafetyContractTest().GitFixture(empty = true).use { git ->
                val material = SyncSpaceCrypto.create("space", 1, password)
                val transport = transport(git, material)
                assertTrue(transport.initialize(repository, "space", 1) is SyncInitializationResult.Initialized)
                val initial = transport.readSnapshot(repository, "space", 1).getOrThrow()
                val service = SyncBatchSyncService(transport, spaceMaterial = material)
                val batch = batch()
                val upload = service.prepare(initial, batch, batchPath)
                assertTrue(upload.encryptedBatch.ciphertext is SyncSpacePayload)
                val saved = SyncUploadArtifactCodec.encode(upload)
                val restored = SyncUploadArtifactCodec.decode(saved)
                assertEquals(upload, restored)
                assertEquals(
                    SyncPublishStatus.PUBLISHED,
                    service.uploadPrepared(repository, initial, restored).publish.status,
                )
                val reopened = SyncSpaceCrypto.unlock(
                    SyncSpaceDescriptorCodec.decode(SyncSpaceDescriptorCodec.encode(material.descriptor)).getOrThrow(),
                    password,
                ).getOrThrow()
                val nextTransport = transport(git, reopened)
                val next = nextTransport.readSnapshot(repository, "space", 1).getOrThrow()
                assertEquals(
                    batch,
                    SyncBatchSyncService(
                        nextTransport,
                        spaceMaterial = reopened,
                    ).receive(next, next.batches.single()).batch,
                )
                val envelope = Json.parseToJsonElement(
                    requireNotNull(git.file("mihon-sync", batchPath)).decodeToString(),
                ).jsonObject
                val payload = requireNotNull(
                    envelope.getValue("ciphertextBase64").jsonPrimitive.content.decodeBase64(),
                ).utf8()
                val paths = listOf(".mihon-sync/index/bootstrap/0/bootstrap.bin", upload.indexPath, upload.headPath)
                val payloads = paths.map { requireNotNull(git.file("mihon-sync", it)).decodeToString() } + payload
                for (wire in payloads) {
                    val parsed = Json.parseToJsonElement(wire).jsonObject
                    assertEquals(material.descriptor.mode, parsed.getValue("mode").jsonPrimitive.content)
                    assertEquals(password.isEmpty(), "plaintext" in parsed)
                    assertEquals(password.isNotEmpty(), "ciphertextBase64" in parsed)
                }
                assertTrue(git.forceFlags.all { !it })
            }
        }
    }

    @Test
    fun `v2 publishing stops when a private repository becomes public`() = runTest {
        SyncGitSafetyContractTest().GitFixture(empty = true).use { git ->
            val material = SyncSpaceCrypto.create("space", 1, "")
            val transport = transport(git, material)
            transport.initialize(repository, "space", 1)
            val snapshot = transport.readSnapshot(repository, "space", 1).getOrThrow()
            val service = SyncBatchSyncService(transport, spaceMaterial = material)
            val prepared = service.prepare(snapshot, batch(), batchPath)
            val before = git.head("mihon-sync")
            git.privateRepository = false
            assertEquals(
                SyncPublishStatus.FAILED,
                service.uploadPrepared(repository, snapshot, prepared).publish.status,
            )
            assertEquals(before, git.head("mihon-sync"))
            assertFalse(git.pathWrites.containsKey(batchPath))
        }
    }

    @Test
    fun `remote descriptor change or deletion never downgrades an existing v2 connection`() = runTest {
        SyncGitSafetyContractTest().GitFixture(empty = true).use { git ->
            val material = SyncSpaceCrypto.create("space", 1, "password")
            val transport = transport(git, material)
            transport.initialize(repository, "space", 1)
            git.replaceFile(
                "mihon-sync",
                SyncSpaceDescriptorCodec.PATH,
                SyncSpaceDescriptorCodec.encode(SyncSpaceCrypto.create("space", 1, "").descriptor),
            )
            assertTrue(transport.readSnapshot(repository, "space", 1).isFailure)
            git.removeFile("mihon-sync", SyncSpaceDescriptorCodec.PATH)
            assertTrue(transport.readSnapshot(repository, "space", 1).isFailure)
            val before = git.head("mihon-sync")
            assertFalse(transport.initialize(repository, "space", 1) is SyncInitializationResult.Initialized)
            assertEquals(before, git.head("mihon-sync"))
        }
    }

    @Test
    fun `legacy remote data cannot be adopted or overwritten by a v2 transport`() = runTest {
        SyncGitSafetyContractTest().GitFixture().use { git ->
            assertTrue(git.transport().initialize(repository, "space", 1) is SyncInitializationResult.Initialized)
            val before = git.head("mihon-sync")
            val transport = transport(git, SyncSpaceCrypto.create("space", 1, ""))
            assertTrue(transport.readSnapshot(repository, "space", 1).isFailure)
            assertFalse(transport.initialize(repository, "space", 1) is SyncInitializationResult.Initialized)
            assertEquals(before, git.head("mihon-sync"))
        }
    }

    @Test
    fun `plain index tampering and broken chains are rejected before receiving`() = runTest {
        SyncGitSafetyContractTest().GitFixture(empty = true).use { git ->
            val material = SyncSpaceCrypto.create("space", 1, "")
            val transport = transport(git, material)
            transport.initialize(repository, "space", 1)
            val snapshot = transport.readSnapshot(repository, "space", 1).getOrThrow()
            val service = SyncBatchSyncService(transport, spaceMaterial = material)
            val upload = service.prepare(snapshot, batch(), batchPath)
            service.uploadPrepared(repository, snapshot, upload)
            val index = requireNotNull(git.file("mihon-sync", upload.indexPath))
            git.replaceFile(
                "mihon-sync",
                upload.indexPath,
                index.decodeToString().replace("device-a", "other-actor").encodeToByteArray(),
            )
            assertTrue(transport.readSnapshot(repository, "space", 1).isFailure)
            git.replaceFile("mihon-sync", upload.indexPath, index)
            git.removeFile("mihon-sync", upload.indexPath)
            assertTrue(transport.readSnapshot(repository, "space", 1).isFailure)
        }
    }

    @Test
    fun `persisted payload mode tampering cannot produce a valid prepared upload`() = runTest {
        SyncGitSafetyContractTest().GitFixture(empty = true).use { git ->
            val material = SyncSpaceCrypto.create("space", 1, "")
            val transport = transport(git, material)
            transport.initialize(repository, "space", 1)
            val snapshot = transport.readSnapshot(repository, "space", 1).getOrThrow()
            val service = SyncBatchSyncService(transport, spaceMaterial = material)
            val upload = service.prepare(snapshot, batch(), batchPath)
            val changed = upload.encryptedBatch.copy(
                ciphertext = SyncSpacePayload(
                    upload.encryptedBatch.ciphertext.bytes.decodeToString().replace(
                        "\"none\"",
                        "\"password\"",
                    ).encodeToByteArray(),
                ),
            )
            assertThrows(IllegalArgumentException::class.java) { transport.prepare(snapshot, changed) }
            val badArtifact = SyncUploadArtifactCodec.encode(upload).replaceFirst("\"version\":2", "\"version\":99")
            assertThrows(IllegalArgumentException::class.java) { SyncUploadArtifactCodec.decode(badArtifact) }
        }
    }

    private fun transport(git: SyncGitSafetyContractTest.GitFixture, material: SyncSpaceMaterial) =
        GitHubSyncTransport(OkHttpClient(), { "synthetic" }, git.baseUrl, spaceMaterial = material)

    private fun initializationIntent(nonce: String) = SyncInitializationIntent(
        accountId = 42,
        repositoryId = 99,
        defaultBranch = "main",
        attemptNonce = nonce,
        stage = SyncInitializationStage.VERIFIED_EMPTY,
    )

    private val batchPath = ".mihon-sync/batches/device-a/1/batch-1.json"

    private fun batch(): SyncBatch {
        val key = SyncObjectKey(SyncObjectType.MANGA, sourceId = "1", originalUrl = "/manga")
        return SyncBatch(
            1,
            "space",
            1,
            "batch-1",
            listOf(
                SyncEventEnvelope(
                    1, "space", 1, "device-a", 1, 1, SyncCategory.FAVORITE,
                    listOf(SyncEffect("favorite", key, SyncField.FAVORITE, SyncEffectKind.ADD)),
                    origin = SyncOrigin.USER, batchId = "batch-1",
                ),
            ),
            listOf(SyncObjectDescriptor(key, "真实漫画标题")),
        )
    }
}
