package mihon.data.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import mihon.data.sync.inbox.SyncInboxStore
import mihon.data.sync.transport.SyncGitObjectKind
import mihon.data.sync.transport.SyncPersistentGitObjectCache
import mihon.data.sync.transport.SyncPersistentGitObjectKey
import mihon.data.sync.transport.SyncSnapshotManifestBinding
import mihon.data.sync.transport.SyncSnapshotManifestStore
import mihon.domain.sync.transport.SyncInitializationResult
import mihon.domain.sync.transport.SyncRepository
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.JvmDatabaseHandler
import tachiyomi.data.Mangas
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import java.nio.file.Files

class SyncPersistentGitObjectCacheIntegrationTest {
    @Test
    fun `new root tree reuses unchanged sync subtree after transport restart`() = runBlocking {
        val directory = Files.createTempDirectory("mihon-sync-object-cache-delta-").toString().toPath()
        try {
            SyncGitSafetyContractTest().GitFixture().use { git ->
                val repository = SyncRepository("fixture-owner", "private-sync", "mihon-sync")
                val revision = "account-9-repository-77-branch-main"
                val first = git.transport(
                    persistentObjectCacheDirectory = directory,
                    connectionRevision = revision,
                    repositoryId = 77,
                )
                assertTrue(first.initialize(repository, "space", 1) is SyncInitializationResult.Initialized)
                val firstSnapshot = first.readSnapshot(repository, "space", 1).getOrThrow()
                val treeRequestsBeforeExternalChange = git.treeRequests
                val blobReadsBeforeExternalChange = git.blobReads
                val requestsBeforeExternalChange = git.server.requestCount

                git.replaceFile("mihon-sync", "README.md", "external root-only change".encodeToByteArray())

                val restarted = git.transport(
                    persistentObjectCacheDirectory = directory,
                    connectionRevision = revision,
                    repositoryId = 77,
                )
                val secondSnapshot = restarted.readSnapshot(repository, "space", 1).getOrThrow()

                assertTrue(firstSnapshot.head != secondSnapshot.head)
                assertEquals(firstSnapshot.batches, secondSnapshot.batches)
                assertEquals(1, git.treeRequests - treeRequestsBeforeExternalChange)
                assertEquals(blobReadsBeforeExternalChange, git.blobReads)
                assertEquals(3, git.server.requestCount - requestsBeforeExternalChange)
                assertEquals(0, git.recursiveTreeRequests)
            }
        } finally {
            FileSystem.SYSTEM.deleteRecursively(directory)
        }
    }

    @Test
    fun `transport restart reuses validated raw trees and blobs from its private object cache`() = runBlocking {
        val directory = Files.createTempDirectory("mihon-sync-object-cache-").toString().toPath()
        try {
            SyncGitSafetyContractTest().GitFixture().use { git ->
                val repository = SyncRepository("fixture-owner", "private-sync", "mihon-sync")
                val first = git.transport(
                    persistentObjectCacheDirectory = directory,
                    connectionRevision = "account-9-repository-77-branch-main",
                    repositoryId = 77,
                )
                assertTrue(first.initialize(repository, "space", 1) is SyncInitializationResult.Initialized)
                assertTrue(first.readSnapshot(repository, "space", 1).isSuccess)
                val treeRequestsAfterColdRead = git.treeRequests
                val blobRequestsAfterColdRead = git.blobReads

                val restarted = git.transport(
                    persistentObjectCacheDirectory = directory,
                    connectionRevision = "account-9-repository-77-branch-main",
                    repositoryId = 77,
                )
                val snapshot = restarted.readSnapshot(repository, "space", 1).getOrThrow()

                assertTrue(snapshot.batches.isEmpty())
                assertEquals(treeRequestsAfterColdRead, git.treeRequests)
                assertEquals(blobRequestsAfterColdRead, git.blobReads)
            }
        } finally {
            FileSystem.SYSTEM.deleteRecursively(directory)
        }
    }

    @Test
    fun `persistent cache is disabled when stable repository id is unavailable`() = runBlocking {
        val directory = Files.createTempDirectory("mihon-sync-object-cache-unbound-").toString().toPath()
        try {
            SyncGitSafetyContractTest().GitFixture().use { git ->
                val repository = SyncRepository("fixture-owner", "private-sync", "mihon-sync")
                val first = git.transport(
                    persistentObjectCacheDirectory = directory,
                    connectionRevision = "transport-instance",
                    repositoryId = null,
                )
                assertTrue(first.initialize(repository, "space", 1) is SyncInitializationResult.Initialized)
                assertTrue(first.readSnapshot(repository, "space", 1).isSuccess)
                val treeRequestsAfterFirst = git.treeRequests
                assertFalse(FileSystem.SYSTEM.exists(directory.resolve("github-git-objects-v1")))

                val restarted = git.transport(
                    persistentObjectCacheDirectory = directory,
                    connectionRevision = "transport-instance",
                    repositoryId = null,
                )
                assertTrue(restarted.readSnapshot(repository, "space", 1).isSuccess)
                assertTrue(git.treeRequests > treeRequestsAfterFirst, "unbound data must not be persisted or reused")
            }
        } finally {
            FileSystem.SYSTEM.deleteRecursively(directory)
        }
    }

    @Test
    fun `transport cache is isolated by revision space and generation`() = runBlocking {
        val directory = Files.createTempDirectory("mihon-sync-object-cache-scope-").toString().toPath()
        try {
            SyncGitSafetyContractTest().GitFixture().use { git ->
                val repository = SyncRepository("fixture-owner", "private-sync", "mihon-sync")
                val first = git.transport(
                    persistentObjectCacheDirectory = directory,
                    connectionRevision = "revision-one",
                    repositoryId = 77,
                )
                assertTrue(first.initialize(repository, "space", 1) is SyncInitializationResult.Initialized)
                assertTrue(first.readSnapshot(repository, "space", 1).isSuccess)

                val beforeRevision = git.treeRequests
                val secondRevision = git.transport(
                    persistentObjectCacheDirectory = directory,
                    connectionRevision = "revision-two",
                    repositoryId = 77,
                )
                assertTrue(secondRevision.readSnapshot(repository, "space", 1).isSuccess)
                assertTrue(git.treeRequests > beforeRevision, "a different connection revision must miss")

                val beforeRepository = git.treeRequests
                val otherRepositoryId = git.transport(
                    persistentObjectCacheDirectory = directory,
                    connectionRevision = "revision-one",
                    repositoryId = 78,
                )
                assertTrue(otherRepositoryId.readSnapshot(repository, "space", 1).isSuccess)
                assertTrue(git.treeRequests > beforeRepository, "a different stable repository id must miss")

                val beforeSpace = git.treeRequests
                val otherSpace = git.transport(
                    persistentObjectCacheDirectory = directory,
                    connectionRevision = "revision-one",
                    repositoryId = 77,
                )
                assertTrue(otherSpace.readSnapshot(repository, "other-space", 1).isFailure)
                assertTrue(git.treeRequests > beforeSpace, "a different space must miss before validation")

                val beforeGeneration = git.treeRequests
                val otherGeneration = git.transport(
                    persistentObjectCacheDirectory = directory,
                    connectionRevision = "revision-one",
                    repositoryId = 77,
                )
                assertTrue(otherGeneration.readSnapshot(repository, "space", 2).isFailure)
                assertTrue(git.treeRequests > beforeGeneration, "a different generation must miss before validation")
            }
        } finally {
            FileSystem.SYSTEM.deleteRecursively(directory)
        }
    }

    @Test
    fun `corrupted object cache records are fetched again and replaced`() = runBlocking {
        val directory = Files.createTempDirectory("mihon-sync-object-cache-corrupt-").toString().toPath()
        try {
            SyncGitSafetyContractTest().GitFixture().use { git ->
                val repository = SyncRepository("fixture-owner", "private-sync", "mihon-sync")
                val revision = "account-9-repository-77-branch-main"
                val first = git.transport(
                    persistentObjectCacheDirectory = directory,
                    connectionRevision = revision,
                    repositoryId = 77,
                )
                assertTrue(first.initialize(repository, "space", 1) is SyncInitializationResult.Initialized)
                assertTrue(first.readSnapshot(repository, "space", 1).isSuccess)

                val objectDirectory = directory.resolve("github-git-objects-v1")
                val objects = FileSystem.SYSTEM.list(objectDirectory).filter { it.name.endsWith(".obj") }
                assertTrue(objects.isNotEmpty(), "cold transport read must persist verified objects")
                objects.forEach { path ->
                    FileSystem.SYSTEM.write(path) { write("corrupt cache record".encodeToByteArray()) }
                }

                val treeRequestsBeforeRepair = git.treeRequests
                val blobRequestsBeforeRepair = git.blobReads
                val repaired = git.transport(
                    persistentObjectCacheDirectory = directory,
                    connectionRevision = revision,
                    repositoryId = 77,
                )
                assertTrue(repaired.readSnapshot(repository, "space", 1).isSuccess)
                assertTrue(git.treeRequests > treeRequestsBeforeRepair)
                assertTrue(git.blobReads > blobRequestsBeforeRepair)

                val treeRequestsAfterRepair = git.treeRequests
                val blobRequestsAfterRepair = git.blobReads
                val warm = git.transport(
                    persistentObjectCacheDirectory = directory,
                    connectionRevision = revision,
                    repositoryId = 77,
                )
                assertTrue(warm.readSnapshot(repository, "space", 1).isSuccess)
                assertEquals(treeRequestsAfterRepair, git.treeRequests)
                assertEquals(blobRequestsAfterRepair, git.blobReads)
            }
        } finally {
            FileSystem.SYSTEM.deleteRecursively(directory)
        }
    }

    @Test
    fun `newer L2 objects cannot warm admit a snapshot ahead of its persisted manifest`() = runBlocking {
        val directory = Files.createTempDirectory("mihon-sync-object-cache-stale-guard-").toString().toPath()
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        val database = Database(
            driver,
            History.Adapter(DateColumnAdapter),
            Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
        )
        try {
            SyncRuntimeStorageContract.Storage(driver, JvmDatabaseHandler(database, driver)).use { storage ->
                val repository = SyncRepository("fixture-owner", "private-sync", "mihon-sync")
                storage.connect("receiver", repository)
                SyncGitSafetyContractTest().GitFixture(realTreeOids = true).use { git ->
                    val revision = "account-9-repository-77-branch-main"
                    val binding = SyncSnapshotManifestBinding(1, 77, revision)
                    val initial = git.transport(
                        persistentObjectCacheDirectory = directory,
                        connectionRevision = revision,
                        repositoryId = 77,
                    ).also {
                        it.installSnapshotManifestStore(SyncSnapshotManifestStore(storage.handler), binding)
                        it.initialize(repository, "space", 1)
                    }
                    val inbox = SyncInboxStore(storage.handler)
                    val accepted = initial.readSnapshot(repository, "space", 1).getOrThrow()
                    inbox.observeSnapshot(
                        accepted,
                        discovery = null,
                        manifest = initial.takeSnapshotManifest(accepted),
                    )
                    val acceptedGuard = storage.handler.await {
                        sync_remote_guardQueries.getGuard("space", 1).executeAsOne()
                    }
                    val acceptedManifest = storage.handler.await {
                        sync_remote_guardQueries.getSnapshotManifest("space", 1).executeAsOne()
                    }
                    assertEquals(accepted.head, acceptedGuard.latest_head)
                    assertEquals(accepted.head, acceptedManifest.head_sha)

                    git.replaceFile("mihon-sync", "README.md", "newer root and blob objects".encodeToByteArray())
                    val seed = git.transport(
                        persistentObjectCacheDirectory = directory,
                        connectionRevision = revision,
                        repositoryId = 77,
                    )
                    val newer = seed.readSnapshot(repository, "space", 1).getOrThrow()
                    assertTrue(newer.head != accepted.head)
                    val newerTreeRequests = git.treeRequests
                    val newerBlobReads = git.blobReads
                    val commitReadsBeforeStaleAdmission = git.commitReads
                    val evidenceBeforeStaleAdmission = inbox.remoteGuard.evidenceEvaluations

                    val stale = git.transport(
                        persistentObjectCacheDirectory = directory,
                        connectionRevision = revision,
                        repositoryId = 77,
                    ).also {
                        it.installSnapshotManifestStore(SyncSnapshotManifestStore(storage.handler), binding)
                    }
                    val fullyValidated = stale.readSnapshot(repository, "space", 1).getOrThrow()

                    assertEquals(newer.head, fullyValidated.head)
                    assertNull(
                        stale.takeWarmAdmission(fullyValidated),
                        "the old persisted head cannot admit newer L2 bytes",
                    )
                    assertTrue(
                        git.commitReads > commitReadsBeforeStaleAdmission,
                        "stale DB state must take the full snapshot path",
                    )
                    assertEquals(newerTreeRequests, git.treeRequests, "the newer tree bytes should be served by L2")
                    assertEquals(newerBlobReads, git.blobReads, "newer and unchanged blob bytes should be served by L2")
                    inbox.observeSnapshot(
                        fullyValidated,
                        discovery = null,
                        manifest = stale.takeSnapshotManifest(fullyValidated),
                    )

                    assertTrue(
                        inbox.remoteGuard.evidenceEvaluations > evidenceBeforeStaleAdmission,
                        "newer raw cache objects must still pass full guard evidence validation",
                    )
                    assertEquals(
                        newer.head,
                        storage.handler.await {
                            sync_remote_guardQueries.getGuard("space", 1).executeAsOne().latest_head
                        },
                    )
                }
            }
        } finally {
            FileSystem.SYSTEM.deleteRecursively(directory)
        }
    }

    @Test
    fun `cache directory failures do not fail validated remote reads`() = runBlocking {
        val file = Files.createTempFile("mihon-sync-object-cache-not-directory-", ".tmp").toString().toPath()
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        val database = Database(
            driver,
            History.Adapter(DateColumnAdapter),
            Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
        )
        try {
            SyncRuntimeStorageContract.Storage(driver, JvmDatabaseHandler(database, driver)).use { storage ->
                FileSystem.SYSTEM.write(file) { write("this path is a file".encodeToByteArray()) }
                val repository = SyncRepository("fixture-owner", "private-sync", "mihon-sync")
                storage.connect("receiver", repository)
                SyncGitSafetyContractTest().GitFixture(realTreeOids = true).use { git ->
                    val revision = "account-9-repository-77-branch-main"
                    val transport = git.transport(
                        persistentObjectCacheDirectory = file,
                        connectionRevision = revision,
                        repositoryId = 77,
                    )
                    transport.installSnapshotManifestStore(
                        SyncSnapshotManifestStore(storage.handler),
                        SyncSnapshotManifestBinding(1, 77, revision),
                    )
                    assertTrue(transport.initialize(repository, "space", 1) is SyncInitializationResult.Initialized)
                    val snapshot = transport.readSnapshot(repository, "space", 1).getOrThrow()
                    val inbox = SyncInboxStore(storage.handler)
                    inbox.observeSnapshot(
                        snapshot,
                        discovery = null,
                        manifest = transport.takeSnapshotManifest(snapshot),
                    )
                    assertTrue(git.treeRequests > 0)
                    assertTrue(git.blobReads > 0)
                    assertEquals(
                        snapshot.head,
                        storage.handler.await {
                            sync_remote_guardQueries.getGuard("space", 1).executeAsOne().latest_head
                        },
                    )

                    val evidenceBeforeWarmRead = inbox.remoteGuard.evidenceEvaluations
                    val warm = transport.readSnapshot(repository, "space", 1).getOrThrow()
                    val admission = transport.takeWarmAdmission(warm)
                    assertTrue(admission != null, "the valid database manifest must remain usable after cache failures")
                    assertTrue(inbox.confirmWarmSnapshot(warm, requireNotNull(admission)))
                    assertEquals(evidenceBeforeWarmRead, inbox.remoteGuard.evidenceEvaluations)
                    assertFalse(
                        storage.handler.await {
                            sync_remote_guardQueries.getGuard("space", 1).executeAsOne().blocked
                        },
                    )
                }
            }
        } finally {
            FileSystem.SYSTEM.delete(file)
        }
    }

    @Test
    fun `persistent cache evicts objects within its configured file budget`() = runBlocking {
        val directory = Files.createTempDirectory("mihon-sync-object-cache-budget-").toString().toPath()
        try {
            val cache = SyncPersistentGitObjectCache(directory, maxBytes = 300)
            val firstContent = "first".encodeToByteArray()
            val secondContent = "second".encodeToByteArray()
            val firstKey = blobKey(firstContent, "repo-a", "main", "revision-1")
            val secondKey = blobKey(secondContent, "repo-b", "release", "revision-2")
            cache.write(firstKey, firstContent)
            cache.write(secondKey, secondContent)

            val results = listOf(cache.read(firstKey), cache.read(secondKey))
            assertEquals(1, results.count { it == null }, "one old object should be evicted to stay under the budget")
            val objectDirectory = directory.resolve("github-git-objects-v1")
            val cacheBytes = FileSystem.SYSTEM.list(objectDirectory)
                .sumOf { FileSystem.SYSTEM.metadata(it).size ?: 0L }
            assertTrue(cacheBytes <= 300, "persistent cache files exceeded the configured cap: $cacheBytes")
        } finally {
            FileSystem.SYSTEM.deleteRecursively(directory)
        }
    }

    @Test
    fun `reading a hot object protects it from the next capacity eviction`() = runBlocking {
        val directory = Files.createTempDirectory("mihon-sync-object-cache-lru-").toString().toPath()
        try {
            val cache = SyncPersistentGitObjectCache(directory, maxBytes = 480)
            val firstContent = "first".encodeToByteArray()
            val secondContent = "second".encodeToByteArray()
            val thirdContent = "third".encodeToByteArray()
            val firstKey = blobKey(firstContent, "repo-a", "main", "revision-1")
            val secondKey = blobKey(secondContent, "repo-b", "release", "revision-2")
            val thirdKey = blobKey(thirdContent, "repo-c", "main", "revision-3")

            cache.write(firstKey, firstContent)
            Thread.sleep(20)
            cache.write(secondKey, secondContent)
            assertEquals(firstContent.toList(), cache.read(firstKey)?.toList())
            Thread.sleep(20)
            cache.write(thirdKey, thirdContent)

            assertEquals(firstContent.toList(), cache.read(firstKey)?.toList(), "recently read object should stay hot")
            assertEquals(null, cache.read(secondKey), "least recently used object should be evicted")
            assertEquals(thirdContent.toList(), cache.read(thirdKey)?.toList(), "new object should be retained")
        } finally {
            FileSystem.SYSTEM.deleteRecursively(directory)
        }
    }

    private fun blobKey(content: ByteArray, repository: String, branch: String, revision: String) =
        SyncPersistentGitObjectKey(
            apiOrigin = "https://api.example.test",
            repositoryId = "77",
            repository = repository,
            branch = branch,
            kind = SyncGitObjectKind.BLOB,
            objectFormat = "git-sha-40",
            objectOid = blobOid(content),
            validationScope = "space=space;generation=1;validator=sync-v1",
            connectionRevision = revision,
        )

    private fun blobOid(content: ByteArray): String {
        val objectBytes = ("blob ${content.size}\u0000".encodeToByteArray() + content).toByteString()
        return objectBytes.sha1().hex()
    }
}
