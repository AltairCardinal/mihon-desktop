package mihon.desktop.ui.reader.presentation

import mihon.domain.reader.ChapterPairingRecord
import mihon.domain.reader.ChapterPairingSnapshot
import mihon.domain.reader.PageRotation
import mihon.domain.reader.ReaderDirection
import mihon.domain.reader.ReaderDualPageContentIdentity
import mihon.domain.reader.ReaderDualPageIdentity
import mihon.domain.reader.ReaderPageFact
import mihon.domain.reader.ReaderPageFactState
import mihon.domain.reader.ReaderPageSize
import mihon.domain.reader.ReaderSpreadEdgeSamples
import mihon.domain.reader.SharedDualPageSession
import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderChapterLoadState
import mihon.domain.reader.session.ReaderChapterSession
import mihon.domain.reader.session.ReaderPageId
import mihon.domain.reader.session.ReaderPageLoadState
import mihon.domain.reader.session.ReaderPageSession
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SharedDualPageDesktopPresentationWiringTest {
    @Test
    fun `dual presentation requires the actual shared owner grouping`() {
        assertThrows(IllegalArgumentException::class.java) {
            DualPagedPresentation.present(request(5).copy(dualPageUnits = null))
        }
    }

    @Test
    fun `synthetic wide fourteen cannot split the stronger fifteen sixteen match`() {
        val request = request(17, wide = setOf(13)).withSharedOwner(matchedPairs = setOf(14 to 15))
        val snapshot = DualPagedPresentation.present(request)
        assertEquals(listOf(listOf(14, 15), listOf(16)), snapshot.groups().takeLast(2))
    }

    @Test
    fun `missing dimensions retain local pending pairs without claiming any image ready`() {
        val snapshot = DualPagedPresentation.present(request(5).copy(pageSizes = emptyMap()).withSharedOwner())
        assertEquals(listOf(listOf(0), listOf(1, 2), listOf(3, 4)), snapshot.groups())
        assertEquals(List(3) { 2 }, snapshot.displayUnits.map { it.slots.size })
        snapshot.displayUnits.flatMap { it.slots }.mapNotNull { it.page }.forEach {
            assertEquals(ReaderPageLoadState.Queued, it.loadState)
        }
    }

    @Test
    fun `wide cover fills complete viewport for one and many page chapters`() {
        for (count in listOf(1, 4)) {
            val cover = DualPagedPresentation.present(
                request(count, wide = setOf(0)).withSharedOwner(),
            ).displayUnits.first()
            assertEquals(listOf(id(0)), cover.slots.map { it.page?.id })
        }
    }

    @Test
    fun `later wide facts do not push parity backwards across a correct earlier pair`() {
        val snapshot = DualPagedPresentation.present(request(10, wide = setOf(3, 7)).withSharedOwner())
        assertEquals(
            listOf(listOf(0), listOf(1, 2), listOf(3), listOf(4, 5), listOf(6), listOf(7), listOf(8, 9)),
            snapshot.groups(),
        )
    }

    @Test
    fun `portrait between two wide pages has a full centered slot`() {
        val snapshot = DualPagedPresentation.present(request(9, wide = setOf(3, 5)).withSharedOwner())
        val middle = snapshot.displayUnits.single { it.slots.any { slot -> slot.page?.id == id(4) } }
        assertEquals(listOf(id(4)), middle.slots.map { it.page?.id })
    }

    private fun request(count: Int, wide: Set<Int> = emptySet()): ReaderPresentationRequest = ReaderPresentationRequest(
        chapter = ReaderChapterSession(
            id = chapterId,
            generation = 1,
            loadState = ReaderChapterLoadState.Loaded,
            pages = List(count) { index ->
                ReaderPageSession(id(index), "/page/$index", null, null, ReaderPageLoadState.Queued)
            },
        ),
        direction = ReaderDirection.RTL,
        pageSizes = (0 until count).associate { index ->
            id(index) to if (index in wide) ReaderPageSize(1500, 1060) else ReaderPageSize(1130, 1596)
        },
        dualPagedOptions = DualPagedPresentationOptions(spreadPageIds = wide.mapTo(linkedSetOf(), ::id)),
    )

    private fun ReaderPresentationSnapshot.groups(): List<List<Int>> = displayUnits.map { unit ->
        unit.slots.mapNotNull { it.page?.id?.sourcePageIndex }.distinct().sorted()
    }

    private fun id(index: Int) = ReaderPageId(chapterId, index)

    private val chapterId = ReaderChapterId(9)
}

/** Test inputs enter the real owner; no grouping, scoring or expected slots are reproduced here. */
internal fun ReaderPresentationRequest.withSharedOwner(
    forcedSingles: Set<Int> = emptySet(),
    matchedPairs: Set<Pair<Int, Int>> = emptySet(),
): ReaderPresentationRequest {
    val ids = chapter.pages.map { it.id }
    val identity = ReaderDualPageIdentity(
        chapter.generation,
        1,
        ids.map { ReaderDualPageContentIdentity(it, "presentation-fixture") },
    )
    val owner = SharedDualPageSession(identity, direction, ids.firstOrNull(), 1)
    try {
        owner.configure(direction, automaticMatching = matchedPairs.isNotEmpty())
        owner.acceptPairingRestore(
            requireNotNull(owner.beginPairingRestore()),
            ChapterPairingSnapshot(
                forcedSingles.takeIf {
                    it.isNotEmpty()
                }?.let { ChapterPairingRecord(1, ids.size, it) },
                1,
            ),
        )
        ids.forEach { id ->
            val size = pageSizes[id]
            val fact = if (size == null) {
                ReaderPageFact(ReaderPageFactState.UNKNOWN)
            } else {
                ReaderPageFact.dimensions(size.width, size.height, pageRotations[id] ?: PageRotation.NONE)
            }
            check(owner.acceptFact(owner.requestFact(id), fact))
        }
        var samples = 0
        // Real score work is local to a requested window. Visit each intended fixture window,
        // then restore the original cold projection; loading every page's dimensions is insufficient.
        for ((first, _) in matchedPairs.sortedBy { it.first }) {
            check(owner.goToPage(ids[first]))
            while (true) {
                val id = owner.spreadSampleDependencies().firstOrNull() ?: break
                check(samples++ <= ids.size * 4)
                val original = requireNotNull(owner.requestSpreadSample(id))
                fun edge(left: Boolean): List<Int> {
                    val pair = matchedPairs.firstOrNull { (first, second) ->
                        (id.sourcePageIndex == first && left == (direction == ReaderDirection.RTL)) ||
                            (id.sourcePageIndex == second && left == (direction == ReaderDirection.LTR))
                    }
                    return if (pair == null) {
                        List(50) { 0xff404040.toInt() }
                    } else {
                        List(50) { point ->
                            0xff000000.toInt() or ((60 + point * 3) shl 16) or ((60 + pair.first * 8) shl 8) or
                                100
                        }
                    }
                }
                check(owner.acceptSpreadSample(original, ReaderSpreadEdgeSamples(edge(true), edge(false))))
            }
        }
        if (matchedPairs.isNotEmpty()) check(owner.goToPage(ids.first()))
        return copy(dualPageUnits = owner.units)
    } finally {
        owner.close()
    }
}
