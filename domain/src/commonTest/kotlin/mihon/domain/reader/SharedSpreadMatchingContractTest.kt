package mihon.domain.reader

import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderPageId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SharedSpreadMatchingContractTest {
    private val pages = List(5) { ReaderPageId(ReaderChapterId(41), it) }
    private val artwork = List(50) { if (it % 2 == 0) 0xffa0a0a0.toInt() else 0xff404080.toInt() }

    @Test
    fun `fifty source samples follow normalized EXIF spine sides including squares`() {
        val turned = requireNotNull(SharedSpreadMatching.plan(60, 40, ReaderImageOrientation.CLOCKWISE))
        assertEquals(50, turned.left.size)
        assertEquals(0 to 37, turned.left.first())
        assertEquals(58 to 37, turned.left.last())
        assertEquals(0 to 2, turned.right.first())
        val mirrored = requireNotNull(SharedSpreadMatching.plan(40, 60, ReaderImageOrientation.MIRROR_HORIZONTAL))
        assertEquals(37 to 0, mirrored.left.first())
        assertEquals(2 to 58, mirrored.right.last())
        assertNotNull(SharedSpreadMatching.plan(13, 13))
        assertNull(SharedSpreadMatching.plan(60, 40))
        assertNull(SharedSpreadMatching.plan(0, 40))
    }

    @Test
    fun `transparent sRGB samples composite onto fixed white independently of theme`() {
        assertEquals(0xffffffff.toInt(), SharedSpreadMatching.normalizeSample(0x00000000))
        assertEquals(0xff7f7f7f.toInt(), SharedSpreadMatching.normalizeSample(0x80000000.toInt()))
        assertEquals(0xff284060.toInt(), SharedSpreadMatching.normalizeSample(0xff284060.toInt()))
    }

    @Test
    fun `only the reading direction spine can prove a continuous spread`() {
        val first = ReaderSpreadEdgeSamples(artwork, List(50) { 0xffee2020.toInt() })
        val second = ReaderSpreadEdgeSamples(List(50) { 0xff2020ee.toInt() }, artwork)
        assertEquals(0.0, SharedSpreadMatching.score(first, second, ReaderDirection.RTL))
        assertNull(SharedSpreadMatching.score(first, second, ReaderDirection.LTR))
    }

    @Test
    fun `existing white dark flat and strict color threshold guards reject false evidence`() {
        val matched = ReaderSpreadEdgeSamples(artwork, artwork)
        assertEquals(0.0, SharedSpreadMatching.score(matched, matched, ReaderDirection.RTL))
        for (color in listOf(0xffffffff.toInt(), 0xff101010.toInt(), 0xff808080.toInt(), 0)) {
            val margins = ReaderSpreadEdgeSamples(List(50) { color }, List(50) { color })
            assertNull(SharedSpreadMatching.score(margins, margins, ReaderDirection.RTL))
        }
        fun shifted(by: Int) = artwork.map { it + (by shl 16) + (by shl 8) + by }
        assertEquals(
            15.0,
            SharedSpreadMatching.score(matched, ReaderSpreadEdgeSamples(artwork, shifted(5)), ReaderDirection.RTL),
        )
        assertNull(
            SharedSpreadMatching.score(matched, ReaderSpreadEdgeSamples(artwork, shifted(10)), ReaderDirection.RTL),
        )
    }

    @Test
    fun `strongest complete candidate wins and manual masking restores a valid alternative`() {
        val candidates = listOf(
            ReaderSpreadCandidate(pages[1], pages[2], 10.0),
            ReaderSpreadCandidate(pages[2], pages[3], 1.0),
            ReaderSpreadCandidate(pages[3], pages[4], 12.0),
        )
        assertEquals(setOf(pages[2] to pages[3]), SharedSpreadMatching.select(pages, candidates))
        assertEquals(
            setOf(pages[1] to pages[2]),
            SharedSpreadMatching.select(pages, candidates, forcedSinglePages = setOf(pages[3])),
        )
        assertEquals(
            emptySet<Pair<ReaderPageId, ReaderPageId>>(),
            SharedSpreadMatching.select(pages, candidates, enabled = false),
        )
    }

    @Test
    fun `equal strength resolves by earlier source order independent of arrival order`() {
        val candidates = (1..3).map { ReaderSpreadCandidate(pages[it], pages[it + 1], 5.0) }
        val expected = setOf(pages[1] to pages[2], pages[3] to pages[4])
        assertEquals(expected, SharedSpreadMatching.select(pages, candidates.reversed()))
        assertEquals(expected, SharedSpreadMatching.select(pages, candidates))
        val invalid = listOf(
            ReaderSpreadCandidate(pages[0], pages[1], 0.0),
            ReaderSpreadCandidate(pages[1], pages[3], 0.0),
            ReaderSpreadCandidate(pages[3], ReaderPageId(ReaderChapterId(42), 4), 0.0),
        )
        assertEquals(emptySet<Pair<ReaderPageId, ReaderPageId>>(), SharedSpreadMatching.select(pages, invalid))
    }

    private fun identity() = ReaderDualPageIdentity(
        7,
        3,
        pages.map { ReaderDualPageContentIdentity(it, "physical:${it.sourcePageIndex}") },
    )

    private fun portraitFacts() = pages.associateWith { ReaderPageFact.dimensions(40, 60) }

    @Test
    fun `bounded sample repair completes the same candidates for adversarial arrival order`() {
        val samples = ReaderSpreadEdgeSamples(artwork, artwork)
        fun complete(order: List<Int>): Set<Pair<ReaderPageId, ReaderPageId>> {
            val owner = ReaderSpreadSamplesOwner(identity(), sampleCapacity = 2)
            order.forEach { assertTrue(owner.accept(owner.request(pages[it]), samples)) }
            var repairs = 0
            while (true) {
                val missing = owner.requiredSamples(portraitFacts())
                if (missing.isEmpty()) break
                assertTrue(missing.size <= 2)
                assertTrue(++repairs <= 3, "Every repair must finish a previously unscored adjacent edge")
                missing.forEach { assertTrue(owner.accept(owner.request(it), samples)) }
                assertTrue(owner.cachedSampleCount <= 2)
            }
            assertEquals(3, owner.confirmedEdgeCount, "Eviction cannot silently lose an adjacent candidate")
            return owner.selected(ReaderDirection.RTL, portraitFacts()).also { owner.close() }
        }
        val expected = setOf(pages[1] to pages[2], pages[3] to pages[4])
        assertEquals(expected, complete(listOf(1, 2, 3, 4)))
        assertEquals(expected, complete(listOf(1, 3, 4, 2)))
    }

    @Test
    fun `accepted samples defensively copy mutable input before a neighbor arrives`() {
        val owner = ReaderSpreadSamplesOwner(identity())
        val mutable = artwork.toMutableList()
        assertTrue(owner.accept(owner.request(pages[1]), ReaderSpreadEdgeSamples(mutable, mutable)))
        mutable.indices.forEach { mutable[it] = 0xffffffff.toInt() }
        assertTrue(owner.accept(owner.request(pages[2]), ReaderSpreadEdgeSamples(artwork, artwork)))
        assertEquals(setOf(pages[1] to pages[2]), owner.selected(ReaderDirection.RTL, portraitFacts()))
        owner.close()
    }

    @Test
    fun `confirmed scores survive bounded sample eviction and local manual direction changes`() {
        val owner = ReaderSpreadSamplesOwner(identity(), sampleCapacity = 2)
        val shifted = artwork.map { it + 0x050505 }
        val other = artwork.map { it + 0x202020 }
        val edges = listOf(
            ReaderSpreadEdgeSamples(artwork, List(50) { 0xffee2020.toInt() }),
            ReaderSpreadEdgeSamples(artwork, shifted),
            ReaderSpreadEdgeSamples(other, artwork),
            ReaderSpreadEdgeSamples(List(50) { 0xff2020ee.toInt() }, other),
        )
        edges.forEachIndexed { index, samples -> assertTrue(owner.accept(owner.request(pages[index + 1]), samples)) }
        assertEquals(2, owner.cachedSampleCount)
        assertEquals(3, owner.confirmedEdgeCount)
        assertEquals(setOf(pages[2] to pages[3]), owner.selected(ReaderDirection.RTL, portraitFacts()))
        assertEquals(
            setOf(pages[1] to pages[2]),
            owner.selected(ReaderDirection.RTL, portraitFacts(), setOf(pages[3])),
        )
        assertEquals(emptySet<Pair<ReaderPageId, ReaderPageId>>(), owner.selected(ReaderDirection.LTR, portraitFacts()))
        assertEquals(
            emptySet<Pair<ReaderPageId, ReaderPageId>>(),
            owner.selected(ReaderDirection.RTL, portraitFacts(), enabled = false),
        )
        owner.close()
        assertEquals(0, owner.cachedSampleCount)
    }

    @Test
    fun `sample capabilities reject new owners and changed bytes without cancelling unchanged page metadata work`() {
        val owner = ReaderSpreadSamplesOwner(identity())
        val first = owner.request(pages[1])
        val second = owner.request(pages[2])
        val samples = ReaderSpreadEdgeSamples(artwork, artwork)
        assertFalse(ReaderSpreadSamplesOwner(owner.identity).accept(first, samples))
        assertTrue(owner.accept(first, samples))
        owner.updateMetadata(
            owner.identity.copy(
                content = owner.identity.content.map {
                    if (it.pageId == pages[1]) it.copy(sourceOrientation = ReaderImageOrientation.CLOCKWISE) else it
                },
            ),
        )
        assertFalse(owner.accept(first, samples))
        assertTrue(owner.accept(second, samples))
        val replacement = owner.rebind(
            owner.identity.copy(
                content = owner.identity.content.map {
                    if (it.pageId == pages[2]) it.copy(contentVersion = "physical:replacement") else it
                },
            ),
        )
        assertFalse(replacement.accept(second, samples))
        assertFalse(owner.accept(second, samples))
        assertTrue(replacement.accept(replacement.request(pages[2]), samples))
        replacement.close()
    }

    @Test
    fun `sampleable square and unknown pages cannot become production matching candidates`() {
        val owner = ReaderSpreadSamplesOwner(identity())
        val samples = ReaderSpreadEdgeSamples(artwork, artwork)
        assertTrue(owner.accept(owner.request(pages[1]), samples))
        assertTrue(owner.accept(owner.request(pages[2]), samples))
        val facts = portraitFacts().toMutableMap()
        facts[pages[1]] = ReaderPageFact.dimensions(13, 13)
        assertEquals(ReaderPageFactState.SPREAD, facts.getValue(pages[1]).state)
        assertEquals(emptySet<Pair<ReaderPageId, ReaderPageId>>(), owner.selected(ReaderDirection.RTL, facts))
        facts[pages[1]] = ReaderPageFact(ReaderPageFactState.UNKNOWN)
        assertEquals(emptySet<Pair<ReaderPageId, ReaderPageId>>(), owner.selected(ReaderDirection.RTL, facts))
        facts[pages[1]] = ReaderPageFact.dimensions(40, 60)
        assertEquals(setOf(pages[1] to pages[2]), owner.selected(ReaderDirection.RTL, facts))
        owner.close()
    }
}
