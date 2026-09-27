package mihon.data.sync

import kotlinx.coroutines.runBlocking
import mihon.domain.sync.transport.SyncInitializationResult
import mihon.domain.sync.transport.SyncRepository
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SyncGitIndexLimitIntegrationTest {
    @Test
    fun `index entry limit accepts minus one and limit but rejects limit plus one`() = runBlocking {
        SyncGitSafetyContractTest().GitFixture().use { git ->
            val repository = SyncRepository("fixture-owner", "private-sync", "mihon-sync")
            assertTrue(git.transport().initialize(repository, "space", 1) is SyncInitializationResult.Initialized)
            val baseline = git.transport().readSnapshot(repository, "space", 1).getOrThrow()
            val baselineCount = baseline.tree.entries.count { it.path.startsWith(".mihon-sync/index/") }

            git.replaceFiles("mihon-sync", indexEntries(9_999 - baselineCount))
            val belowLimit = git.transport().readSnapshot(repository, "space", 1).exceptionOrNull()
            assertTrue(belowLimit != null)
            assertFalse(belowLimit?.message.orEmpty().contains("sync index exceeds entry limit"))

            git.replaceFile("mihon-sync", ".mihon-sync/index/test/shard-09999.bin", byteArrayOf(1))
            val atLimit = git.transport().readSnapshot(repository, "space", 1).exceptionOrNull()
            assertTrue(atLimit != null)
            assertFalse(atLimit?.message.orEmpty().contains("sync index exceeds entry limit"))

            git.replaceFile("mihon-sync", ".mihon-sync/index/test/shard-10000.bin", byteArrayOf(2))
            val aboveLimit = git.transport().readSnapshot(repository, "space", 1).exceptionOrNull()
            assertTrue(aboveLimit != null)
            assertTrue(aboveLimit?.message.orEmpty().contains("sync index exceeds entry limit"))
        }
    }

    private fun indexEntries(count: Int): Map<String, ByteArray> =
        (0 until count).associate { index ->
            ".mihon-sync/index/test/shard-${index.toString().padStart(5, '0')}.bin" to byteArrayOf(index.toByte())
        }
}
