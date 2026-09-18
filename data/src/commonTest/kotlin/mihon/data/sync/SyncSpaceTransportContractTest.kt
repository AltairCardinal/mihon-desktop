package mihon.data.sync

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
import mihon.domain.sync.transport.SyncInitializationResult
import mihon.domain.sync.transport.SyncPublishStatus
import mihon.domain.sync.transport.SyncRepository
import okhttp3.OkHttpClient
import okio.ByteString.Companion.decodeBase64
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
    fun `v2 initialization resumes only the exact bootstrap left on its sync branch`() = runTest {
        SyncGitSafetyContractTest().GitFixture().use { git ->
            git.removeFile("main", "README.md")
            git.replaceFile("main", ".mihon-sync/bootstrap", "mihon-sync bootstrap".encodeToByteArray())
            git.createSyncBranch()
            val transport = transport(git, SyncSpaceCrypto.create("space", 1, ""))
            assertTrue(transport.initialize(repository, "space", 1) is SyncInitializationResult.Initialized)
            assertTrue(transport.readSnapshot(repository, "space", 1).isSuccess)
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
        SyncGitSafetyContractTest().GitFixture(empty = true).use { git ->
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
