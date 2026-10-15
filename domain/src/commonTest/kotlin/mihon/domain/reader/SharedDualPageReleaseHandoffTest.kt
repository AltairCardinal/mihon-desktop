package mihon.domain.reader

import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderPageId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SharedDualPageReleaseHandoffTest {
    @Test
    fun `closing an inherited neighbor before recapture releases its physical claim through either owner`() {
        for (releaseThroughOriginal in listOf(true, false)) {
            val original = session()
            val ids = original.identity.pageIds
            val current = original.captureViewport()!!
            val neighbor = original.captureViewport(ids[3])!!
            assertTrue(original.reportDrawn(current, setOf(ids[1])))
            assertTrue(original.reportDrawn(neighbor, setOf(ids[3])))
            val next = original.rebindContent(replace(original.identity, 5))
            try {
                val renewed = next.captureViewport()!!
                assertEquals(current.frameId, renewed.frameId)
                original.close() // Repeated old-owner disposal cannot revoke its successor's release route.
                assertEquals(setOf(ids[1], ids[2], ids[3], ids[4]), next.visibleContentPageIds)
                assertFalse(next.canRebindContent(replace(next.identity, 3)))
                assertFalse(next.preparePage(neighbor, ids[3]))
                assertFalse(next.reportDrawn(neighbor, setOf(ids[3])))
                assertNull(next.settle(event(neighbor, ids[3]), false, "old-neighbor"))

                val releaseOwner = if (releaseThroughOriginal) original else next
                assertTrue(
                    releaseOwner.releaseViewport(neighbor),
                    "Closing the actual old mount must release its claim",
                )
                assertEquals(setOf(ids[1], ids[2]), next.visibleContentPageIds)
                assertTrue(next.canRebindContent(replace(next.identity, 3)))
                assertEquals(current.frameId, next.frame(renewed)!!.id)
                assertFalse(original.releaseViewport(neighbor), "Repeated old cleanup must be inert")
                assertFalse(next.releaseViewport(neighbor), "Cleanup through the other route is also inert")
            } finally {
                next.close()
                original.close()
            }
        }
    }

    @Test
    fun `late original cleanup cannot revoke a physical frame already taken over by a new ticket`() {
        for (releaseThroughOriginal in listOf(true, false)) {
            val original = session()
            val ids = original.identity.pageIds
            val old = original.captureViewport(ids[3])!!
            assertTrue(original.reportDrawn(old, setOf(ids[3])))
            val next = original.rebindContent(replace(original.identity, 5))
            try {
                val renewed = next.captureViewport(ids[3])!!
                assertEquals(old.frameId, renewed.frameId)
                assertFalse(old == renewed)
                val releaseOwner = if (releaseThroughOriginal) original else next
                assertTrue(releaseOwner.releaseViewport(old), "Only the original cleanup handle is released")
                assertFalse(releaseOwner.releaseViewport(old), "The original cleanup handle is consumed once")
                assertNotNull(next.frame(renewed))
                assertTrue(next.visibleContentPageIds.containsAll(setOf(ids[3], ids[4])))
                assertFalse(next.preparePage(old, ids[4]))
                assertFalse(next.reportDrawn(old, setOf(ids[4])))
                assertTrue(next.preparePage(renewed, ids[4]))
                assertTrue(next.reportDrawn(renewed, setOf(ids[4])))
                assertTrue(next.releaseViewport(renewed))
                assertTrue(next.visibleContentPageIds.isEmpty())
                assertTrue(next.canRebindContent(replace(next.identity, 3)))
            } finally {
                next.close()
                original.close()
            }
        }
    }

    @Test
    fun `uncaptured physical lease can be released after consecutive unrelated content replacements`() {
        for (releaseThroughOriginal in listOf(true, false)) {
            val original = session()
            val ids = original.identity.pageIds
            val old = original.captureViewport(ids[3])!!
            assertTrue(original.reportDrawn(old, setOf(ids[3])))
            val middle = original.rebindContent(replace(original.identity, 5))
            val latest = middle.rebindContent(replace(middle.identity, 6))
            try {
                assertEquals(setOf(ids[3], ids[4]), latest.visibleContentPageIds)
                assertFalse(latest.canRebindContent(replace(latest.identity, 3)))
                val releaseOwner = if (releaseThroughOriginal) original else latest
                assertTrue(releaseOwner.releaseViewport(old))
                assertTrue(latest.visibleContentPageIds.isEmpty())
                assertTrue(latest.canRebindContent(replace(latest.identity, 3)))
                assertFalse(middle.releaseViewport(old))
                assertFalse(latest.reportDrawn(old, setOf(ids[3])))
            } finally {
                latest.close()
                middle.close()
                original.close()
            }
        }
    }

    @Test
    fun `closing the renewed ticket first still protects the original mount until its own close`() {
        val original = session()
        val ids = original.identity.pageIds
        val old = original.captureViewport(ids[3])!!
        val next = original.rebindContent(replace(original.identity, 5))
        try {
            val renewed = next.captureViewport(ids[3])!!
            assertTrue(next.releaseViewport(renewed))
            assertEquals(setOf(ids[3], ids[4]), next.visibleContentPageIds)
            assertFalse(next.canRebindContent(replace(next.identity, 3)))
            assertTrue(original.releaseViewport(old))
            assertTrue(next.visibleContentPageIds.isEmpty())
            assertTrue(next.canRebindContent(replace(next.identity, 3)))
        } finally {
            next.close()
            original.close()
        }
    }

    private fun session(): SharedDualPageSession {
        val ids = List(8) { ReaderPageId(ReaderChapterId(40), it) }
        return SharedDualPageSession(
            ReaderDualPageIdentity(
                1,
                1,
                ids.map {
                    ReaderDualPageContentIdentity(it, "original-${it.sourcePageIndex}")
                },
            ),
            ReaderDirection.RTL,
            ids[1],
        ).also { owner ->
            ids.forEach { assertTrue(owner.acceptFact(owner.requestFact(it), ReaderPageFact.dimensions(100, 150))) }
        }
    }

    private fun replace(identity: ReaderDualPageIdentity, index: Int): ReaderDualPageIdentity = identity.copy(
        content = identity.content.map {
            if (it.pageId.sourcePageIndex == index) it.copy(contentVersion = "replacement-$index") else it
        },
    )

    private fun event(ticket: ReaderDualPageViewportTicket, page: ReaderPageId) = ReaderDualPageSettlement(
        ticket.identity, ticket.layoutRevision, ticket.locationSequence, ticket.unitId,
        setOf(page), true, ReaderDualPageSettlementReason.USER_NAVIGATION, 1, ticket,
    )
}
