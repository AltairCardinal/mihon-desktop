package mihon.desktop.reader

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
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
import org.junit.jupiter.api.Test

class DesktopReaderContentRoleRecoveryAcceptanceTest {

    @Test
    fun `shared header join cannot relabel the next full open`() = runTest {
        val events = mutableListOf<ReaderIoEvent>()
        var headerCalls = 0
        val owner = DesktopReaderPageContentOwner(
            scope = this,
            encodedPageReader = { byteArrayOf(1, 2, 3) },
            ioReporter = reporter(events),
            encodedPrefixReader = { _, _ ->
                headerCalls++
                byteArrayOf(1)
            },
        )
        try {
            val request = request(1L)
            val first = requireNotNull(owner.acquireHeader(request, ReaderIoPurpose.NORMAL_NEIGHBOUR_HEADER))
            val joined = requireNotNull(owner.acquireHeader(request, ReaderIoPurpose.SPREAD_SPECULATIVE_HEADER))
            assertEquals(1, headerCalls, "Different consumers must retain one shared physical header read")
            first.close()
            joined.close()
            requireNotNull(owner.acquire(request)).close()
            assertEquals(
                listOf(
                    ReaderIoEventType.HEADER_READ to ReaderIoPurpose.NORMAL_NEIGHBOUR_HEADER,
                    ReaderIoEventType.OPEN_PAGE to ReaderIoPurpose.CURRENT_PAGE,
                ),
                events.map { it.type to it.purpose },
                "A joined consumer must not leave a role for an unrelated later operation",
            )
        } finally {
            owner.close()
        }
    }

    @Test
    fun `queued header claims stay bound to their complete content identity`() = runTest {
        val events = mutableListOf<ReaderIoEvent>()
        var headerCalls = 0
        val owner = DesktopReaderPageContentOwner(
            scope = this,
            encodedPageReader = { byteArrayOf(2) },
            ioReporter = reporter(events),
            encodedPrefixReader = { _, _ ->
                headerCalls++
                byteArrayOf(1)
            },
        )
        try {
            // Both callers register before the scope starts either lazy physical open.
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                owner.acquireHeader(request(1L), ReaderIoPurpose.NORMAL_NEIGHBOUR_HEADER)
            }
            val second = async(start = CoroutineStart.UNDISPATCHED) {
                owner.acquireHeader(request(2L).copy(attemptGeneration = 1L), ReaderIoPurpose.SPREAD_SAMPLE_HEADER)
            }
            requireNotNull(first.await()).close()
            requireNotNull(second.await()).close()
            assertEquals(2, headerCalls, "Distinct content identities require distinct physical reads")
            assertEquals(
                mapOf(1L to ReaderIoPurpose.NORMAL_NEIGHBOUR_HEADER, 2L to ReaderIoPurpose.SPREAD_SAMPLE_HEADER),
                events.associate { it.generation to it.purpose },
                "Page identity alone cannot bind concurrent operation roles",
            )
        } finally {
            owner.close()
        }
    }

    private fun reporter(events: MutableList<ReaderIoEvent>) = ReaderIoReporter(
        probe = ReaderIoProbe { events += it },
        clock = ReaderMonotonicClock { 1L },
    )

    private fun request(generation: Long) = ReaderPageContentOpenRequest(
        pageId = ReaderPageId(ReaderChapterId(7L), 1),
        generation = generation,
        encodedPageRef = EncodedPageRef("opaque://role-recovery"),
    )
}
