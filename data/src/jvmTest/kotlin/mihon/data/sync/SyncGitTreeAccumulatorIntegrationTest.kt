package mihon.data.sync

import kotlinx.coroutines.runBlocking
import mihon.domain.sync.transport.SyncInitializationResult
import mihon.domain.sync.transport.SyncRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SyncGitTreeAccumulatorIntegrationTest {
    @Test
    fun `nested leaves are materialized once into the complete result`() = runBlocking {
        SyncGitSafetyContractTest().GitFixture().use { git ->
            val repository = SyncRepository("fixture-owner", "private-sync", "mihon-sync")
            assertTrue(git.transport().initialize(repository, "space", 1) is SyncInitializationResult.Initialized)
            val transport = git.transport()
            val depth = 12
            val width = 5
            val prefix = (0 until depth).joinToString("/") { "level-$it" }
            git.replaceFiles(
                "mihon-sync",
                (0 until width).associate { "$prefix/file-$it.bin" to byteArrayOf(it.toByte()) },
            )
            val before = transport.treePathMaterializations

            val snapshot = transport.readSnapshot(repository, "space", 1).getOrThrow()

            assertEquals(width, snapshot.tree.entries.count { it.path.startsWith("$prefix/") })
            val nestedLeafCount = snapshot.tree.entries.count { '/' in it.path }
            assertEquals(
                nestedLeafCount.toLong(),
                transport.treePathMaterializations - before,
                "nested full paths should be built once, without copying each intermediate subtree",
            )
        }
    }
}
