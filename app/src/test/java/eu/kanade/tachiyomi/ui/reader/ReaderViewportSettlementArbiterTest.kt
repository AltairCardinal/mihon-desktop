package eu.kanade.tachiyomi.ui.reader

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReaderViewportSettlementArbiterTest {

    @Test
    fun `issuing a new token invalidates every older viewport settlement`() = runBlocking {
        val arbiter = ReaderViewportSettlementArbiter()
        val older = arbiter.nextToken()
        val latest = arbiter.nextToken()
        var staleBlockCalled = false

        assertFalse(arbiter.isLatest(older))
        assertTrue(arbiter.isLatest(latest))
        assertFalse(
            arbiter.runIfLatest(older) {
                staleBlockCalled = true
            },
        )
        assertFalse(staleBlockCalled)
        assertTrue(arbiter.runIfLatest(latest) {})
    }

    @Test
    fun `an in-flight write completes before the latest settlement enters the serialized transaction`() = runBlocking {
        val arbiter = ReaderViewportSettlementArbiter()
        val firstToken = arbiter.nextToken()
        val firstWriteEntered = CompletableDeferred<Unit>()
        val releaseFirstWrite = CompletableDeferred<Unit>()
        val writes = Channel<Long>(Channel.UNLIMITED)

        val firstWrite = launch(Dispatchers.Default) {
            arbiter.runIfLatest(firstToken) {
                firstWriteEntered.complete(Unit)
                releaseFirstWrite.await()
                writes.send(firstToken)
            }
        }
        withTimeout(5_000) { firstWriteEntered.await() }

        val latestToken = arbiter.nextToken()
        val latestWrite = launch(Dispatchers.Default) {
            arbiter.runIfLatest(latestToken) {
                writes.send(latestToken)
            }
        }

        assertNull(withTimeoutOrNull(250) { writes.receive() })
        releaseFirstWrite.complete(Unit)

        assertEquals(firstToken, withTimeout(5_000) { writes.receive() })
        assertEquals(latestToken, withTimeout(5_000) { writes.receive() })
        joinAll(firstWrite, latestWrite)
    }
}
