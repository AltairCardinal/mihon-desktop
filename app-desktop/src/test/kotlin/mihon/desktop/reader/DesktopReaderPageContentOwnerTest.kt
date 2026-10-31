package mihon.desktop.reader

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import mihon.domain.reader.content.ReaderPageContentOpenRequest
import mihon.domain.reader.observability.ReaderIoEvent
import mihon.domain.reader.observability.ReaderIoEventType
import mihon.domain.reader.observability.ReaderIoProbe
import mihon.domain.reader.observability.ReaderIoPurpose
import mihon.domain.reader.observability.ReaderIoReporter
import mihon.domain.reader.observability.ReaderMonotonicClock
import mihon.domain.reader.session.EncodedPageRef
import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderPageId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DesktopReaderPageContentOwnerTest {

    @Test
    fun `overlapping header roles share one read and the next full open has no leftover role`() = runTest {
        val events = mutableListOf<ReaderIoEvent>()
        val readerEntered = CompletableDeferred<Unit>()
        val releaseReader = CompletableDeferred<Unit>()
        var headerReads = 0
        var fullReads = 0
        val owner = owner(
            scope = this,
            events = events,
            encodedPageReader = {
                fullReads++
                byteArrayOf(8)
            },
            encodedPrefixReader = { _, _ ->
                headerReads++
                readerEntered.complete(Unit)
                releaseReader.await()
                byteArrayOf(1)
            },
        )
        try {
            val request = request(generation = 31L)
            val first = async { owner.acquireHeader(request, ReaderIoPurpose.NORMAL_NEIGHBOUR_HEADER) }
            readerEntered.await()
            val joined = async { owner.acquireHeader(request, ReaderIoPurpose.SPREAD_SAMPLE_HEADER) }
            runCurrent()
            assertEquals(1, headerReads, "A same-key join must keep one physical header read")
            releaseReader.complete(Unit)
            val firstLease = requireNotNull(first.await())
            val joinedLease = requireNotNull(joined.await())
            assertSame(firstLease.content, joinedLease.content)
            firstLease.close()
            joinedLease.close()

            requireNotNull(owner.acquire(request)).close()

            assertEquals(1, fullReads)
            assertEquals(
                listOf(
                    ReaderIoEventType.HEADER_READ to ReaderIoPurpose.NORMAL_NEIGHBOUR_HEADER,
                    ReaderIoEventType.OPEN_PAGE to ReaderIoPurpose.CURRENT_PAGE,
                ),
                events.map { it.type to it.purpose },
            )
        } finally {
            releaseReader.complete(Unit)
            owner.close()
        }
    }

    @Test
    fun `generation attempt and max bytes remain isolated content identities`() = runTest {
        val events = mutableListOf<ReaderIoEvent>()
        var headerReads = 0
        val owner = owner(
            scope = this,
            events = events,
            encodedPageReader = { byteArrayOf(0) },
            encodedPrefixReader = { _, maxBytes ->
                headerReads++
                byteArrayOf(maxBytes.toByte())
            },
        )
        try {
            val base = request(generation = 32L).copy(maxBytes = 8)
            val variants = listOf(
                base,
                base.copy(generation = 33L),
                base.copy(attemptGeneration = 1L),
                base.copy(maxBytes = 9),
            )
            val leases = variants.map { requireNotNull(owner.acquire(it)) }

            assertEquals(4, headerReads)
            assertEquals(variants.toSet(), owner.snapshot().activeLeaseCounts.keys)
            assertEquals(setOf(32L, 33L), events.map { it.generation }.toSet())
            assertEquals(setOf(8, 9), events.mapNotNull { it.byteLimit }.toSet())
            leases.forEach { it.close() }
            assertTrue(owner.snapshot().activeLeaseCounts.isEmpty())
        } finally {
            owner.close()
        }
    }

    @Test
    fun `cancelled joining waiter leaves the creator read alive and does not taint a later open`() = runTest {
        val events = mutableListOf<ReaderIoEvent>()
        val readerEntered = CompletableDeferred<Unit>()
        val releaseReader = CompletableDeferred<Unit>()
        var headerReads = 0
        var fullReads = 0
        val owner = owner(
            scope = this,
            events = events,
            encodedPageReader = {
                fullReads++
                byteArrayOf(9)
            },
            encodedPrefixReader = { _, _ ->
                headerReads++
                withContext(NonCancellable) {
                    readerEntered.complete(Unit)
                    releaseReader.await()
                }
                byteArrayOf(2)
            },
        )
        try {
            val request = request(generation = 34L)
            val creator = async { owner.acquireHeader(request, ReaderIoPurpose.NORMAL_NEIGHBOUR_HEADER) }
            readerEntered.await()
            val cancelledWaiter = async { owner.acquireHeader(request, ReaderIoPurpose.SPREAD_SPECULATIVE_HEADER) }
            runCurrent()
            cancelledWaiter.cancelAndJoin()
            assertEquals(1, headerReads, "Cancelling one waiter must not cancel the shared physical read")

            releaseReader.complete(Unit)
            requireNotNull(creator.await()).close()
            requireNotNull(owner.acquire(request)).close()

            assertEquals(1, headerReads)
            assertEquals(1, fullReads)
            assertEquals(
                listOf(
                    ReaderIoPurpose.NORMAL_NEIGHBOUR_HEADER,
                    ReaderIoPurpose.CURRENT_PAGE,
                ),
                events.map { it.purpose },
            )
        } finally {
            releaseReader.complete(Unit)
            owner.close()
        }
    }

    @Test
    fun `close cancels an unfinished read and rejects later acquires`() = runTest {
        val events = mutableListOf<ReaderIoEvent>()
        val readerEntered = CompletableDeferred<Unit>()
        val releaseReader = CompletableDeferred<Unit>()
        var headerReads = 0
        val owner = owner(
            scope = this,
            events = events,
            encodedPageReader = { byteArrayOf(0) },
            encodedPrefixReader = { _, _ ->
                headerReads++
                readerEntered.complete(Unit)
                releaseReader.await()
                byteArrayOf(3)
            },
        )
        val pending = async { owner.acquireHeader(request(generation = 35L), ReaderIoPurpose.SPREAD_SAMPLE_HEADER) }
        try {
            readerEntered.await()
            owner.close()
            val failure = runCatching { pending.await() }.exceptionOrNull()
            assertInstanceOf(kotlinx.coroutines.CancellationException::class.java, failure)
            assertEquals(1, headerReads)
            val afterClose = runCatching { owner.acquire(request(generation = 36L)) }.exceptionOrNull()
            assertInstanceOf(IllegalStateException::class.java, afterClose)
        } finally {
            releaseReader.complete(Unit)
            pending.cancelAndJoin()
            owner.close()
        }
    }

    @Test
    fun `reader start observation follows gate admission and precedes the real reader`() = runTest {
        val events = mutableListOf<String>()
        val firstGateEntered = CompletableDeferred<Unit>()
        val firstGateRelease = CompletableDeferred<Unit>()
        val secondGateEntered = CompletableDeferred<Unit>()
        val secondGateRelease = CompletableDeferred<Unit>()
        val firstRequest = request(generation = 36L)
        val secondRequest = request(generation = 37L)
        val generationByRef = mapOf(firstRequest.encodedPageRef to 36L, secondRequest.encodedPageRef to 37L)
        val started = mutableSetOf<Long>()
        var readerCalls = 0
        val gate = object : ReaderIoGate {
            override suspend fun await(point: ReaderIoGatePoint) = Unit

            override fun registerContentOwner(): Long = 1L

            override suspend fun awaitContentRead(ownerId: Long, request: ReaderPageContentOpenRequest) {
                val generation = request.generation
                events += "gate-wait:$generation"
                val entered = if (generation == 36L) firstGateEntered else secondGateEntered
                val release = if (generation == 36L) firstGateRelease else secondGateRelease
                entered.complete(Unit)
                release.await()
            }

            override fun contentReadStarted(ownerId: Long, request: ReaderPageContentOpenRequest) {
                started += request.generation
                events += "reader-enter:${request.generation}"
            }

            override fun contentReadCompleted(
                ownerId: Long,
                request: ReaderPageContentOpenRequest,
                bytes: Int?,
                failure: Throwable?,
            ) {
                val stage = if (started.remove(request.generation)) "reader-exit" else "pre-reader-abort"
                events += "$stage:${request.generation}"
            }
        }
        val owner = DesktopReaderPageContentOwner(
            scope = this,
            encodedPageReader = { ref ->
                val generation = generationByRef.getValue(ref)
                assertEquals("reader-enter:$generation", events.last(), "The hook must precede the production reader")
                events += "reader-call:$generation"
                readerCalls++
                byteArrayOf(1)
            },
            ioReporter = ReaderIoReporter(clock = ReaderMonotonicClock { 2L }),
            ioGate = gate,
        )
        val firstWaiter = async { owner.acquire(firstRequest) }
        try {
            firstGateEntered.await()
            assertEquals(listOf("gate-wait:36"), events, "The start hook cannot run before gate admission")
            firstWaiter.cancelAndJoin()
            assertEquals(listOf("gate-wait:36"), events, "Cancelling one waiter does not enter the reader")

            firstGateRelease.complete(Unit)
            runCurrent()
            assertEquals(
                listOf("gate-wait:36", "reader-enter:36", "reader-call:36", "reader-exit:36"),
                events,
            )

            val secondWaiter = async { owner.acquire(secondRequest) }
            secondGateEntered.await()
            assertFalse("reader-enter:37" in events, "The second read remains at its gate")
            owner.close()
            val closeFailure = runCatching { secondWaiter.await() }.exceptionOrNull()
            assertInstanceOf(kotlinx.coroutines.CancellationException::class.java, closeFailure)
            assertEquals(
                listOf(
                    "gate-wait:36",
                    "reader-enter:36",
                    "reader-call:36",
                    "reader-exit:36",
                    "gate-wait:37",
                    "pre-reader-abort:37",
                ),
                events,
                "A cancellation while awaiting the gate is not a physical reader exit",
            )
            assertEquals(1, readerCalls)
        } finally {
            firstGateRelease.complete(Unit)
            secondGateRelease.complete(Unit)
            firstWaiter.cancelAndJoin()
            owner.close()
        }
    }

    private fun owner(
        scope: CoroutineScope,
        events: MutableList<ReaderIoEvent>,
        encodedPageReader: suspend (EncodedPageRef) -> ByteArray?,
        encodedPrefixReader: suspend (EncodedPageRef, Int) -> ByteArray?,
    ) = DesktopReaderPageContentOwner(
        scope = scope,
        encodedPageReader = encodedPageReader,
        ioReporter = ReaderIoReporter(
            probe = ReaderIoProbe { events += it },
            clock = ReaderMonotonicClock { 1L },
        ),
        encodedPrefixReader = encodedPrefixReader,
    )

    private fun request(
        generation: Long,
        attemptGeneration: Long = 0L,
        maxBytes: Int? = null,
    ) = ReaderPageContentOpenRequest(
        pageId = ReaderPageId(ReaderChapterId(93L), sourcePageIndex = 2),
        generation = generation,
        encodedPageRef = EncodedPageRef("opaque://owner-role-$generation"),
        attemptGeneration = attemptGeneration,
        maxBytes = maxBytes,
    )
}
