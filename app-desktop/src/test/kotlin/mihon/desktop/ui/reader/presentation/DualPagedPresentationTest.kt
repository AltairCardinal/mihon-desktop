package mihon.desktop.ui.reader.presentation

import mihon.domain.reader.PageSplitHalf
import mihon.domain.reader.ReaderDirection
import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderChapterLoadState
import mihon.domain.reader.session.ReaderChapterSession
import mihon.domain.reader.session.ReaderPageId
import mihon.domain.reader.session.ReaderPageLoadState
import mihon.domain.reader.session.ReaderPageSession
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DualPagedPresentationTest {

    @Test
    fun `cover occupies the physical left slot for both reading directions`() {
        val ltr = DualPagedPresentation.present(request(pageCount = 4, direction = ReaderDirection.LTR))
        val rtl = DualPagedPresentation.present(request(pageCount = 4, direction = ReaderDirection.RTL))

        listOf(ltr, rtl).forEach { snapshot ->
            val cover = snapshot.displayUnits.first()
            assertEquals(2, cover.slots.size)
            assertEquals(pageId(0), cover.slots[0].page?.id)
            assertNull(cover.slots[1].page)
        }
    }

    @Test
    fun `landscape cover remains whole in the physical left slot for one and many page chapters`() {
        for (pageCount in listOf(1, 4)) {
            for (direction in listOf(ReaderDirection.LTR, ReaderDirection.RTL)) {
                for (splitCover in listOf(false, true)) {
                    val coverId = pageId(0)
                    val snapshot = present(
                        pageCount = pageCount,
                        direction = direction,
                        splitPageIds = if (splitCover) setOf(coverId) else emptySet(),
                        options = DualPagedPresentationOptions(spreadPageIds = setOf(coverId)),
                    )

                    val cover = snapshot.displayUnits.first()
                    assertEquals(2, cover.slots.size)
                    assertEquals(coverId, cover.slots[0].page?.id)
                    assertNull(cover.slots[0].splitHalf)
                    assertNull(cover.slots[1].page)
                }
            }
        }
    }

    @Test
    fun `portrait pairs follow physical LTR and RTL slots while reporting both pages`() {
        val ltr = DualPagedPresentation.present(request(pageCount = 5, direction = ReaderDirection.LTR))
        val rtl = DualPagedPresentation.present(request(pageCount = 5, direction = ReaderDirection.RTL))

        assertEquals(listOf(pageId(1), pageId(2)), ltr.displayUnits[1].slots.map { it.page?.id })
        assertEquals(listOf(pageId(2), pageId(1)), rtl.displayUnits[1].slots.map { it.page?.id })

        val visible = rtl.resolveDualVisiblePages(rtl.displayUnits[1].id)
        assertEquals(setOf(pageId(1), pageId(2)), visible.pageIds)
        assertEquals(pageId(2), visible.activePageId)
    }

    @Test
    fun `late content and state changes preserve pair and slot identities`() {
        val loading = present(pageCount = 5, direction = ReaderDirection.LTR)
        val ready = present(
            pageCount = 5,
            direction = ReaderDirection.LTR,
            stateAt = { ReaderPageLoadState.Ready },
            imageAt = { "ready-$it" },
        )

        assertEquals(loading.displayUnits.map(DisplayUnit::id), ready.displayUnits.map(DisplayUnit::id))
        assertEquals(loading.displayUnits[1].slots.map(DisplaySlot::id), ready.displayUnits[1].slots.map(DisplaySlot::id))
        assertEquals(
            listOf(ReaderPageLoadState.Ready, ReaderPageLoadState.Ready),
            ready.displayUnits[1].slots.map { it.page?.loadState },
        )
    }

    @Test
    fun `spread forced single edge match and parity change grouping without changing page identity`() {
        val baseline = present(pageCount = 8, direction = ReaderDirection.LTR)
        assertEquals(
            listOf(listOf(0), listOf(1, 2), listOf(3, 4), listOf(5, 6), listOf(7)),
            baseline.logicalGroups(),
        )

        val spread = present(
            pageCount = 8,
            direction = ReaderDirection.LTR,
            options = DualPagedPresentationOptions(spreadPageIds = setOf(pageId(3))),
        )
        assertEquals(
            listOf(listOf(0), listOf(1, 2), listOf(3), listOf(4, 5), listOf(6, 7)),
            spread.logicalGroups(),
        )

        val forced = present(
            pageCount = 8,
            direction = ReaderDirection.LTR,
            options = DualPagedPresentationOptions(forcedSinglePageIds = setOf(pageId(3))),
        )
        assertEquals(
            listOf(listOf(0), listOf(1, 2), listOf(3), listOf(4, 5), listOf(6, 7)),
            forced.logicalGroups(),
        )

        val matched = present(
            pageCount = 7,
            direction = ReaderDirection.LTR,
            options = DualPagedPresentationOptions(
                spreadPageIds = setOf(pageId(3)),
                matchedPagePairs = setOf(pageId(5) to pageId(6)),
            ),
        )
        assertEquals(
            listOf(listOf(0), listOf(1, 2), listOf(3), listOf(4), listOf(5, 6)),
            matched.logicalGroups(),
        )

        val allIds = matched.displayUnits.flatMap { it.slots }.mapNotNull { it.page?.id }.toSet()
        assertEquals((0 until 7).map(::pageId).toSet(), allIds)
    }

    @Test
    fun `wide spread can occupy two physical slice slots without changing source page identity`() {
        val snapshot = present(
            pageCount = 5,
            direction = ReaderDirection.RTL,
            splitPageIds = setOf(pageId(3)),
            options = DualPagedPresentationOptions(spreadPageIds = setOf(pageId(3))),
        )
        val spread = snapshot.displayUnits.single { unit -> unit.slots.any { it.page?.id == pageId(3) } }

        assertEquals(listOf(pageId(3), pageId(3)), spread.slots.map { it.page?.id })
        assertEquals(listOf(PageSplitHalf.RIGHT, PageSplitHalf.LEFT), spread.slots.map(DisplaySlot::splitHalf))
        assertEquals(setOf(pageId(3)), snapshot.resolveDualVisiblePages(spread.id).pageIds)
    }

    @Test
    fun `registry exposes dual beside single and webtoon`() {
        assertSame(DualPagedPresentation, DesktopReaderPresentationRegistry.require(ReaderPresentationMode.DUAL_PAGED))
        assertSame(SinglePagedPresentation, DesktopReaderPresentationRegistry.require(ReaderPresentationMode.SINGLE_PAGED))
        assertSame(WebtoonPresentation, DesktopReaderPresentationRegistry.require(ReaderPresentationMode.WEBTOON))
    }

    private fun present(
        pageCount: Int,
        direction: ReaderDirection,
        splitPageIds: Set<ReaderPageId> = emptySet(),
        options: DualPagedPresentationOptions = DualPagedPresentationOptions(),
        stateAt: (Int) -> ReaderPageLoadState = { ReaderPageLoadState.Queued },
        imageAt: (Int) -> String? = { null },
    ): ReaderPresentationSnapshot = DualPagedPresentation.present(
        request(pageCount, direction, splitPageIds, options, stateAt, imageAt),
    )

    private fun request(
        pageCount: Int,
        direction: ReaderDirection,
        splitPageIds: Set<ReaderPageId> = emptySet(),
        options: DualPagedPresentationOptions = DualPagedPresentationOptions(),
        stateAt: (Int) -> ReaderPageLoadState = { ReaderPageLoadState.Queued },
        imageAt: (Int) -> String? = { null },
    ): ReaderPresentationRequest =
        ReaderPresentationRequest(
            chapter = ReaderChapterSession(
                id = chapterId,
                generation = 19,
                loadState = ReaderChapterLoadState.Loaded,
                pages = List(pageCount) { index ->
                    ReaderPageSession(
                        id = pageId(index),
                        url = "/page/$index",
                        imageUrl = imageAt(index),
                        encodedPageRef = null,
                        loadState = stateAt(index),
                    )
                },
            ),
            direction = direction,
            splitPageIds = splitPageIds,
            dualPagedOptions = options,
        )

    private fun ReaderPresentationSnapshot.logicalGroups(): List<List<Int>> = displayUnits.map { unit ->
        unit.slots.mapNotNull { it.page?.id?.sourcePageIndex }.distinct().sorted()
    }

    private fun pageId(index: Int) = ReaderPageId(chapterId, index)

    private companion object {
        val chapterId = ReaderChapterId(88L)
    }
}
