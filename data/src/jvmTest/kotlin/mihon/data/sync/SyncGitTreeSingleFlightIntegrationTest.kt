package mihon.data.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import mihon.data.sync.transport.SyncLoadSingleFlight
import mihon.domain.sync.transport.SyncInitializationResult
import mihon.domain.sync.transport.SyncRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SyncGitTreeSingleFlightIntegrationTest {
    @Test
    fun `concurrent cold snapshots share one request for the same tree`() = runBlocking {
        SyncGitSafetyContractTest().GitFixture().use { git ->
            val repository = SyncRepository("fixture-owner", "private-sync", "mihon-sync")
            assertTrue(git.transport().initialize(repository, "space", 1) is SyncInitializationResult.Initialized)
            val transport = git.transport()
            val treeSha = git.treeSha("mihon-sync")
            val requestsBefore = git.treeRequestCount(treeSha)
            val firstTreeRequest = git.delayNextTreeResponse(treeSha, 400)

            val reads = (1..2).map {
                async(Dispatchers.IO) { transport.readSnapshot(repository, "space", 1) }
            }
            firstTreeRequest.await()
            val results = reads.awaitAll()

            assertTrue(results.all { it.isSuccess })
            assertEquals(
                1,
                git.treeRequestCount(treeSha) - requestsBefore,
                "concurrent cold reads of one immutable tree must share one network request",
            )
        }
    }

    @Test
    fun `cancelling the flight owner preserves the shared load for its waiter`() = runBlocking {
        val flights = SyncLoadSingleFlight<String, ByteArray>()
        val loaderStarted = CompletableDeferred<Unit>()
        val releaseLoader = CompletableDeferred<Unit>()
        val owner = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            flights.getOrLoad("tree") {
                loaderStarted.complete(Unit)
                releaseLoader.await()
                byteArrayOf(7)
            }
        }
        loaderStarted.await()
        val waiter = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            flights.getOrLoad("tree") { error("waiter must join the existing load") }
        }

        owner.cancel()
        releaseLoader.complete(Unit)

        owner.join()
        assertEquals(listOf(7.toByte()), waiter.await().toList())
    }

    @Test
    fun `cancelling a flight waiter does not cancel its shared loader`() = runBlocking {
        val flights = SyncLoadSingleFlight<String, ByteArray>()
        val loaderStarted = CompletableDeferred<Unit>()
        val releaseLoader = CompletableDeferred<Unit>()
        val owner = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            flights.getOrLoad("tree") {
                loaderStarted.complete(Unit)
                releaseLoader.await()
                byteArrayOf(9)
            }
        }
        loaderStarted.await()
        val waiter = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            flights.getOrLoad("tree") { error("waiter must join the existing load") }
        }

        waiter.cancel()
        releaseLoader.complete(Unit)

        assertEquals(listOf(9.toByte()), owner.await().toList())
        waiter.join()
    }

    @Test
    fun `failed tree load is removed so a later caller can retry`() = runBlocking {
        val flights = SyncLoadSingleFlight<String, String>()
        var loads = 0

        val failed = runCatching {
            flights.getOrLoad("tree") {
                loads++
                error("temporary loader failure")
            }
        }
        val retried = flights.getOrLoad("tree") {
            loads++
            "recovered"
        }

        assertTrue(failed.isFailure)
        assertEquals("recovered", retried)
        assertEquals(2, loads)
    }
}
