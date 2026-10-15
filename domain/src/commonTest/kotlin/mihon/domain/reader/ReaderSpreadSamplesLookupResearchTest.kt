package mihon.domain.reader

import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderPageId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReaderSpreadSamplesLookupResearchTest {
    private val pixels = List(50) { if (it % 2 == 0) 0xffa0a0a0.toInt() else 0xff404080.toInt() }
    private val sample = ReaderSpreadEdgeSamples(pixels, pixels)

    @Test
    fun `repeated production distance lookups do not rescan chapter content`() {
        for (count in listOf(64, 256)) {
            val ids = List(count) { ReaderPageId(ReaderChapterId(9), it) }
            val content = CountingContent(ids.map { ReaderDualPageContentIdentity(it, "content-$it") })
            val owner = ReaderSpreadSamplesOwner(ReaderDualPageIdentity(1, 1, content))
            try {
                val pair = ids.takeLast(2)
                val facts = ids.associateWith { ReaderPageFact.dimensions(100, 150) }
                pair.forEach { assertTrue(owner.accept(owner.request(it), sample)) }
                content.reads = 0
                repeat(1000) { assertEquals(0.0, owner.distance(pair[0], pair[1], ReaderDirection.RTL, facts)) }
                assertEquals(0, content.reads, "A scalar query must not traverse the chapter's $count identities")
            } finally {
                owner.close()
            }
        }
    }

    @Test
    fun `metadata updates and replacement bytes maintain correct production lookup identity`() {
        val ids = List(5) { ReaderPageId(ReaderChapterId(9), it) }
        val identity = ReaderDualPageIdentity(1, 1, ids.map { ReaderDualPageContentIdentity(it, "content-$it") })
        val owner = ReaderSpreadSamplesOwner(identity)
        val facts = ids.associateWith { ReaderPageFact.dimensions(100, 150) }.toMutableMap()
        val late = owner.request(ids[3])
        assertTrue(owner.accept(owner.request(ids[1]), sample))
        assertTrue(owner.accept(owner.request(ids[2]), sample))
        assertEquals(0.0, owner.distance(ids[1], ids[2], ReaderDirection.RTL, facts))
        val changed = identity.copy(
            content = identity.content.map {
                if (it.pageId == ids[1]) it.copy(sourceOrientation = ReaderImageOrientation.MIRROR_HORIZONTAL) else it
            },
        )
        owner.updateMetadata(changed)
        assertTrue(owner.accept(late, sample), "Other-page metadata must not revoke this original content capability")
        assertNull(owner.distance(ids[1], ids[2], ReaderDirection.RTL, facts))
        facts[ids[1]] = facts.getValue(ids[1]).copy(sourceOrientation = ReaderImageOrientation.MIRROR_HORIZONTAL)
        assertTrue(owner.accept(owner.request(ids[1]), sample))
        assertEquals(0.0, owner.distance(ids[1], ids[2], ReaderDirection.RTL, facts))
        val oldRequest = owner.request(ids[2])
        val replacement = owner.rebind(
            changed.copy(
                content = changed.content.map {
                    if (it.pageId == ids[2]) it.copy(contentVersion = "replacement") else it
                },
            ),
        )
        try {
            assertFalse(replacement.accept(oldRequest, sample))
            assertNull(replacement.distance(ids[1], ids[2], ReaderDirection.RTL, facts))
            assertTrue(replacement.accept(replacement.request(ids[2]), sample))
            assertEquals(0.0, replacement.distance(ids[1], ids[2], ReaderDirection.RTL, facts))
        } finally {
            replacement.close()
        }
    }

    private class CountingContent(
        private val values: List<ReaderDualPageContentIdentity>,
    ) : AbstractList<ReaderDualPageContentIdentity>() {
        var reads = 0
        override val size get() = values.size
        override fun get(index: Int): ReaderDualPageContentIdentity {
            reads++
            return values[index]
        }
    }
}
