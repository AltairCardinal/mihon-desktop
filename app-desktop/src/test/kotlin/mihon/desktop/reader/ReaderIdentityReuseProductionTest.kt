package mihon.desktop.reader

import mihon.desktop.ui.reader.ReaderScreenModel
import mihon.domain.reader.ReaderDualPageContentIdentity
import mihon.domain.reader.ReaderImageOrientation
import mihon.domain.reader.ReaderPageFact
import mihon.domain.reader.SharedDualPageSession
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReaderIdentityReuseProductionTest {
    @Test
    fun `same order session acceptance bounds old identity reads for short and full chapters`() {
        // Count accesses to the actual previous identity, including publication after acceptance.
        // The wrapper preserves every element and the identity itself; no lookup is duplicated here.
        val counts = mutableListOf<Pair<Int, Int>>()
        for (pageCount in listOf(8, 187)) {
            val input = desktopReaderSessionState(pageCount = pageCount)
            val model = ReaderScreenModel(initialSessionState = input)
            try {
                val owner = owner(model)
                val content = CountingContent(owner.identity.content)
                owner.identity.javaClass.getDeclaredField("content").run {
                    isAccessible = true
                    set(owner.identity, content)
                }
                repeat(2) { round ->
                    content.reads = 0
                    model.acceptSessionState(input)
                    println("D6_C1 pages=$pageCount round=$round oldIdentityReads=${content.reads}")
                    assertSame(owner, owner(model))
                    assertEquals(input.snapshot.activeChapter.pages.map { it.id }, owner.identity.pageIds)
                    counts += pageCount to content.reads
                }
            } finally {
                model.onDispose()
            }
        }
        counts.forEach { (pages, reads) ->
            assertTrue(reads <= pages * 4, "old identity reads=$reads, pages=$pages; all counts=$counts")
        }
    }

    @Test
    fun `identity reuse preserves orientation by id and version across order generation and close`() {
        var input = desktopReaderSessionState(pageCount = 8)
        val model = ReaderScreenModel(initialSessionState = input)
        try {
            val first = owner(model)
            input.snapshot.activeChapter.pages.forEachIndexed { index, page ->
                assertTrue(model.acceptDualFact(
                    requireNotNull(model.captureDualFact(page.id)),
                    ReaderPageFact.dimensions(
                        400,
                        600,
                        sourceOrientation = if (index % 2 == 0) ReaderImageOrientation.CLOCKWISE else ReaderImageOrientation.NORMAL,
                    ),
                ))
            }
            val oriented = first.identity.content
            model.acceptSessionState(input)
            assertEquals(oriented, owner(model).identity.content)
            assertSame(first, owner(model))

            input = input.copy(snapshot = input.snapshot.copy(activeChapter = input.snapshot.activeChapter.copy(
                pages = input.snapshot.activeChapter.pages.mapIndexed { index, page ->
                    if (index == 2) page.copy(attemptGeneration = page.attemptGeneration + 1) else page
                },
            )))
            model.acceptSessionState(input)
            val changed = owner(model).identity.content
            assertEquals("desktop:1", changed[2].contentVersion)
            assertEquals(ReaderImageOrientation.NORMAL, changed[2].sourceOrientation)
            assertEquals(oriented[0], changed[0])

            val beforeOrder = owner(model)
            input = input.copy(snapshot = input.snapshot.copy(activeChapter = input.snapshot.activeChapter.copy(
                pages = input.snapshot.activeChapter.pages.reversed(),
            )))
            model.acceptSessionState(input)
            assertTrue(beforeOrder.isClosed)
            val reordered = owner(model).identity.content
            assertEquals(input.snapshot.activeChapter.pages.map { it.id }, reordered.map { it.pageId })
            assertEquals(oriented[0], reordered.last())
            assertEquals(ReaderImageOrientation.NORMAL, reordered.first { it.pageId == changed[2].pageId }.sourceOrientation)

            val beforeGeneration = owner(model)
            input = input.copy(snapshot = input.snapshot.copy(
                generation = input.snapshot.generation + 1,
                activeChapter = input.snapshot.activeChapter.copy(generation = input.snapshot.generation + 1),
            ))
            model.acceptSessionState(input)
            assertTrue(beforeGeneration.isClosed)
            assertEquals(input.snapshot.generation, owner(model).identity.generation)
            assertEquals(oriented[0], owner(model).identity.content.last())
            assertFalse(owner(model).isClosed)
            val last = owner(model)
            model.onDispose()
            assertTrue(last.isClosed)
            model.acceptSessionState(input)
            assertTrue(last.isClosed)
        } finally {
            model.onDispose()
        }
    }

    private fun owner(model: ReaderScreenModel): SharedDualPageSession =
        model.javaClass.getDeclaredField("dualSession").run {
            isAccessible = true
            get(model) as SharedDualPageSession
        }

    private class CountingContent(private val delegate: List<ReaderDualPageContentIdentity>) :
        AbstractList<ReaderDualPageContentIdentity>() {
        var reads = 0
        override val size: Int get() = delegate.size
        override fun get(index: Int): ReaderDualPageContentIdentity {
            reads++
            return delegate[index]
        }
    }
}
