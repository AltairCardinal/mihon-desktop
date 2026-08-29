package mihon.domain.reader.content

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import mihon.domain.reader.session.EncodedPageRef
import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderPageId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReaderPageContentOpenCoordinatorTest {

    @Test
    fun `concurrent leases single flight the same page generation and opaque ref`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val released = CompletableDeferred<Unit>()
        var openCalls = 0
        val content = byteArrayOf(1, 2, 3)
        val coordinator = ReaderPageContentOpenCoordinator(
            scope = this,
            port = ReaderPageContentOpenPort<ByteArray> {
                openCalls += 1
                entered.complete(Unit)
                released.await()
                content
            },
        )
        val request = request(generation = 7L)

        val first = async { coordinator.acquire(request) }
        entered.await()
        val second = async { coordinator.acquire(request) }
        runCurrent()

        assertEquals(1, openCalls)
        released.complete(Unit)
        val firstLease = requireNotNull(first.await())
        val secondLease = requireNotNull(second.await())
        assertSame(firstLease.content, secondLease.content)
        assertEquals(2, coordinator.snapshot().activeLeaseCounts.getValue(request))

        firstLease.close()
        assertEquals(1, coordinator.snapshot().activeLeaseCounts.getValue(request))
        secondLease.close()
        assertTrue(coordinator.snapshot().activeLeaseCounts.isEmpty())
        coordinator.close()
    }

    @Test
    fun `generation and opaque ref remain part of the content ownership key`() = runTest {
        var openCalls = 0
        val coordinator = ReaderPageContentOpenCoordinator(
            scope = this,
            port = ReaderPageContentOpenPort<ByteArray> { byteArrayOf((++openCalls).toByte()) },
        )
        val first = requireNotNull(coordinator.acquire(request(generation = 1L)))
        val second = requireNotNull(coordinator.acquire(request(generation = 2L)))
        val replacement = requireNotNull(
            coordinator.acquire(
                request(
                    generation = 2L,
                    ref = EncodedPageRef("opaque://replacement"),
                ),
            ),
        )

        assertEquals(3, openCalls)
        assertEquals(setOf(1, 2, 3), listOf(first, second, replacement).map { it.content.single().toInt() }.toSet())

        first.close()
        second.close()
        replacement.close()
        coordinator.close()
    }

    @Test
    fun `cancelled last waiter leaves an unfinished physical open available to the next acquire`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val released = CompletableDeferred<Unit>()
        var openCalls = 0
        val coordinator = ReaderPageContentOpenCoordinator(
            scope = this,
            port = ReaderPageContentOpenPort<ByteArray> {
                openCalls++
                withContext(NonCancellable) {
                    entered.complete(Unit)
                    released.await()
                }
                byteArrayOf(4, 5, 6)
            },
        )
        val request = request(generation = 11L)

        val cancelled = async { coordinator.acquire(request) }
        entered.await()
        cancelled.cancelAndJoin()
        val replacement = async { coordinator.acquire(request) }
        try {
            runCurrent()

            assertEquals(1, openCalls)
            released.complete(Unit)
            requireNotNull(replacement.await()).close()
        } finally {
            released.complete(Unit)
            replacement.cancelAndJoin()
            coordinator.close()
        }
    }

    @Test
    fun `close during successful open conversion reports cooperative cancellation`() = runTest {
        val ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val entered = CompletableDeferred<Unit>()
        val released = CompletableDeferred<Unit>()
        val coordinator = ReaderPageContentOpenCoordinator(
            scope = ownerScope,
            port = ReaderPageContentOpenPort<ByteArray> {
                entered.complete(Unit)
                released.await()
                byteArrayOf(7, 8, 9)
            },
        )

        val acquiring = async { coordinator.acquire(request(generation = 12L)) }
        try {
            runCurrent()
            entered.await()
            released.complete(Unit)
            coordinator.close()
            runCurrent()

            val error = runCatching { acquiring.await() }.exceptionOrNull()
            assertInstanceOf(kotlinx.coroutines.CancellationException::class.java, error)
        } finally {
            released.complete(Unit)
            acquiring.cancelAndJoin()
            coordinator.close()
            ownerScope.cancel()
        }
    }

    private fun request(
        generation: Long,
        ref: EncodedPageRef = EncodedPageRef("opaque://page-0"),
    ) = ReaderPageContentOpenRequest(
        pageId = ReaderPageId(ReaderChapterId(9L), sourcePageIndex = 0),
        generation = generation,
        encodedPageRef = ref,
    )
}
