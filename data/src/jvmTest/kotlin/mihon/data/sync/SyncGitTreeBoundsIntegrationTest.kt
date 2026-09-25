package mihon.data.sync

import kotlinx.coroutines.runBlocking
import mihon.domain.sync.transport.SyncInitializationResult
import mihon.domain.sync.transport.SyncRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SyncGitTreeBoundsIntegrationTest {
    @Test
    fun `tree entry limit accepts limit and rejects only limit plus one`() = runBlocking {
        SyncGitSafetyContractTest().GitFixture().use { git ->
            val repository = SyncRepository("fixture-owner", "private-sync", "mihon-sync")
            assertTrue(git.transport().initialize(repository, "space", 1) is SyncInitializationResult.Initialized)
            val entryCount = git.transport().readSnapshot(repository, "space", 1).getOrThrow().tree.entries.size

            val below = git.transport(maxTreeEntries = entryCount - 1).readSnapshot(repository, "space", 1)
            val atLimit = git.transport(maxTreeEntries = entryCount).readSnapshot(repository, "space", 1)
            val above = git.transport(maxTreeEntries = entryCount + 1).readSnapshot(repository, "space", 1)

            assertTrue(below.isFailure, "one below the complete tree size must be rejected")
            assertEquals(entryCount, atLimit.getOrThrow().tree.entries.size)
            assertEquals(entryCount, above.getOrThrow().tree.entries.size)
        }
    }

    @Test
    fun `tree traversal accepts depth 256 and rejects depth 257`() = runBlocking {
        SyncGitSafetyContractTest().GitFixture().use { git ->
            val repository = SyncRepository("fixture-owner", "private-sync", "mihon-sync")
            assertTrue(git.transport().initialize(repository, "space", 1) is SyncInitializationResult.Initialized)
            val transport = git.transport()
            val depth256Path = (0 until 256).joinToString("/") { "d" } + "/at-limit.bin"
            git.replaceFiles("mihon-sync", mapOf(depth256Path to byteArrayOf(1)))

            assertTrue(transport.readSnapshot(repository, "space", 1).isSuccess)

            val depth257Path = (0 until 257).joinToString("/") { "d" } + "/over-limit.bin"
            git.replaceFiles("mihon-sync", mapOf(depth257Path to byteArrayOf(2)))
            val tooDeep = transport.readSnapshot(repository, "space", 1)

            assertTrue(tooDeep.isFailure)
            assertTrue(tooDeep.exceptionOrNull()?.message.orEmpty().contains("depth exceeds limit"))
        }
    }

    @Test
    fun `empty directory DAG accepts path visit budget and rejects one more without publishing`() = runBlocking {
        for (excess in 0..1) {
            SyncGitSafetyContractTest().GitFixture().use { git ->
                val repository = SyncRepository("fixture-owner", "private-sync", "mihon-sync")
                assertTrue(git.transport().initialize(repository, "space", 1) is SyncInitializationResult.Initialized)
                val requestsBeforeSnapshot = git.treeRequests
                val entryCount = git.transport().readSnapshot(repository, "space", 1).getOrThrow().tree.entries.size
                val baseTreeVisits = git.treeRequests - requestsBeforeSnapshot
                val maxTreeEntries = 20
                val budget = maxTreeEntries * 4 + 256
                val head = git.head("mihon-sync")
                git.attachEmptyTreeDag(git.treeSha("mihon-sync"), budget - baseTreeVisits + excess)

                val result = git.transport(maxTreeEntries = maxTreeEntries).readSnapshot(repository, "space", 1)

                if (excess == 0) {
                    assertTrue(result.isSuccess, "exact directory visit budget must remain valid: $result")
                    assertEquals(entryCount, result.getOrThrow().tree.entries.size)
                } else {
                    assertTrue(result.isFailure, "one extra empty directory expansion must fail")
                    assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("directory path budget"))
                }
                assertEquals(head, git.head("mihon-sync"), "tree reads must not advance the ref")
            }
        }
    }

    @Test
    fun `multiple immutable tree oids stay within LRU budget and evicted trees reload completely`() = runBlocking {
        SyncGitSafetyContractTest().GitFixture().use { git ->
            val repository = SyncRepository("fixture-owner", "private-sync", "mihon-sync")
            assertTrue(git.transport().initialize(repository, "space", 1) is SyncInitializationResult.Initialized)
            val transport = git.transport()
            val initialFiles = (0 until 4_000).associate { index ->
                "bulk/file-${index.toString().padStart(5, '0')}-xxxxxxxx.txt" to byteArrayOf(index.toByte())
            }
            git.replaceFiles("mihon-sync", initialFiles)
            val firstSnapshot = transport.readSnapshot(repository, "space", 1).getOrThrow()
            val firstHead = git.head("mihon-sync")
            assertEquals(4_000, firstSnapshot.tree.entries.count { it.path.startsWith("bulk/") })

            repeat(9) { revision ->
                git.replaceFile(
                    "mihon-sync",
                    "bulk/revision-$revision.bin",
                    byteArrayOf(revision.toByte()),
                )
                val snapshot = transport.readSnapshot(repository, "space", 1).getOrThrow()
                assertEquals(4_001 + revision, snapshot.tree.entries.count { it.path.startsWith("bulk/") })
            }
            assertTrue(cachedTreeWeightBytes(transport) <= TREE_CACHE_BUDGET_BYTES)

            git.resetRef("mihon-sync", firstHead)
            git.replaceFile("mihon-sync", "README.md", "new external readme".encodeToByteArray())
            val requestsBeforeReload = git.treeRequests
            val restored = transport.readSnapshot(repository, "space", 1).getOrThrow()

            assertEquals(4_000, restored.tree.entries.count { it.path.startsWith("bulk/") })
            assertTrue(
                git.treeRequests >= requestsBeforeReload + 2,
                "the evicted bulk tree must be fetched again while the complete snapshot is reconstructed",
            )
            assertTrue(cachedTreeWeightBytes(transport) <= TREE_CACHE_BUDGET_BYTES)
        }
    }

    private fun cachedTreeWeightBytes(transport: Any): Long =
        transport.javaClass.getDeclaredField("treeCacheWeightBytes").apply { isAccessible = true }
            .getLong(transport)

    private companion object {
        const val TREE_CACHE_BUDGET_BYTES = 4L * 1024 * 1024
    }
}
