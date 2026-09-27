package mihon.data.sync

import kotlinx.coroutines.runBlocking
import mihon.domain.sync.transport.SyncInitializationResult
import mihon.domain.sync.transport.SyncRepository
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SyncGitTreeCacheFailureIntegrationTest {
    @Test
    fun `invalid duplicate paths are not retained in the tree cache`() = runBlocking {
        SyncGitSafetyContractTest().GitFixture().use { git ->
            val repository = SyncRepository("fixture-owner", "private-sync", "mihon-sync")
            assertTrue(git.transport().initialize(repository, "space", 1) is SyncInitializationResult.Initialized)
            val transport = git.transport()
            val treeSha = git.treeSha("mihon-sync")
            val duplicatePath = "poisoned-entry"
            val fakeBlob = "a".repeat(40)
            git.overrideNextTreeResponse(
                treeSha,
                """{"sha":"$treeSha","truncated":false,"tree":[
                    {"path":"$duplicatePath","mode":"100644","type":"blob","sha":"$fakeBlob","size":1},
                    {"path":"$duplicatePath","mode":"100644","type":"blob","sha":"$fakeBlob","size":1}
                ]}""",
            )

            assertTrue(transport.readSnapshot(repository, "space", 1).isFailure)
            assertTrue(
                transport.readSnapshot(repository, "space", 1).isSuccess,
                "retry after malformed tree response must fetch a valid tree instead of reusing it",
            )
        }
    }

    @Test
    fun `cyclic tree responses are evicted so a repaired response can be read`() = runBlocking {
        SyncGitSafetyContractTest().GitFixture().use { git ->
            val repository = SyncRepository("fixture-owner", "private-sync", "mihon-sync")
            assertTrue(git.transport().initialize(repository, "space", 1) is SyncInitializationResult.Initialized)
            val transport = git.transport()
            val treeSha = git.treeSha("mihon-sync")
            git.overrideNextTreeResponse(
                treeSha,
                """{"sha":"$treeSha","truncated":false,"tree":[
                    {"path":"loop","mode":"040000","type":"tree","sha":"$treeSha"}
                ]}""",
            )

            assertTrue(transport.readSnapshot(repository, "space", 1).isFailure)
            assertTrue(
                transport.readSnapshot(repository, "space", 1).isSuccess,
                "retry after a cyclic tree response must fetch a valid tree instead of reusing it",
            )
        }
    }
}
