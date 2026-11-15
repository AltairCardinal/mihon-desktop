package mihon.desktop.ui.reader.presentation

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsNode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mihon.domain.reader.PageSplitHalf
import mihon.domain.reader.ReaderDirection
import mihon.domain.reader.ReaderTransitionDirection
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

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `uncertified default half remains transparent even with a boundary reason`() = runTest {
        val base = request(
            pageCount = 6,
            direction = ReaderDirection.RTL,
            options = PresentationFixtureOptions(spreadPageIds = setOf(pageId(4))),
            stateAt = { ReaderPageLoadState.Ready },
        )
        val request = base.copy(
            pageSizes = base.pageSizes.filterKeys { it.sourcePageIndex in setOf(3, 4) },
        ).withSharedOwner(projectFrames = true)
        val sourceUnit = request.dualPageUnits!!.single { it.pageIds == listOf(pageId(3)) }
        assertTrue(sourceUnit.singleReason != null)
        val snapshot = DualPagedPresentation.present(request)
        val unit = snapshot.displayUnits.single { it.slots.any { slot -> slot.page?.id == pageId(3) } }
        assertEquals(2, unit.slots.size, "The default wide boundary must occupy one physical half: $sourceUnit")
        val emptyIndex = unit.slots.indexOfFirst { it.page == null }
        val scene = ImageComposeScene(400, 400, coroutineContext = coroutineContext) {
            mihon.desktop.ui.reader.DualPageDisplayUnitFrame(unit, {}) { _, _ -> }
        }
        try {
            scene.render(0).use { image ->
                org.jetbrains.skia.Bitmap.makeFromImage(image).use { bitmap ->
                    assertEquals(
                        0,
                        bitmap.getColor(if (emptyIndex == 0) 20 else 380, 100),
                        "A default neighbour boundary is not certified intentional blank paper",
                    )
                }
            }
        } finally {
            scene.close()
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `internal empty physical slot draws white paper without adding a visible source page`() = runTest {
        for (direction in listOf(ReaderDirection.LTR, ReaderDirection.RTL)) {
            for (state in listOf(
                ReaderPageLoadState.Ready,
                ReaderPageLoadState.Queued,
                ReaderPageLoadState.Error(mihon.domain.error.AppError.Network()),
            )) {
                val snapshot = DualPagedPresentation.present(
                    request(
                        pageCount = 7,
                        direction = direction,
                        options = PresentationFixtureOptions(forcedSinglePageIds = setOf(pageId(2), pageId(3))),
                        stateAt = { state },
                    ).withSharedOwner(forcedSingles = setOf(2, 3), projectFrames = true),
                )
                val units = snapshot.displayUnits.filter { unit ->
                    unit.slots.size == 2 && unit.slots.count { it.page != null } == 1
                }
                assertTrue(units.any { it.slots.any { slot -> slot.page?.id == pageId(2) } })
                for (unit in units) {
                    val real = unit.slots.single { it.page != null }.page!!
                    val internal = real.id.sourcePageIndex in setOf(2, 3)
                    val emptyIndex = unit.slots.indexOfFirst { it.page == null }
                    val scene = ImageComposeScene(400, 400, coroutineContext = coroutineContext) {
                        mihon.desktop.ui.reader.DualPageDisplayUnitFrame(unit, {}) { _, _ -> }
                    }
                    try {
                        val image = scene.render(0)
                        val bitmap = org.jetbrains.skia.Bitmap.makeFromImage(image)
                        assertEquals(
                            if (internal) 0xFFFFFFFF.toInt() else 0,
                            bitmap.getColor(if (emptyIndex == 0) 20 else 380, 100),
                            "source=${real.id.sourcePageIndex} direction=$direction",
                        )
                        bitmap.close()
                        image.close()
                        assertEquals(setOf(real.id), snapshot.resolveDualVisiblePages(unit.id).pageIds)
                    } finally {
                        scene.close()
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `certified manual boundary remainder maps its shared half slot and decorative paper`() = runTest {
        for (direction in listOf(ReaderDirection.RTL, ReaderDirection.LTR)) {
            val input = request(
                pageCount = 6,
                direction = direction,
                options = PresentationFixtureOptions(forcedSinglePageIds = setOf(pageId(4))),
                stateAt = { ReaderPageLoadState.Ready },
            ).withSharedOwner(forcedSingles = setOf(4), projectFrames = true, certifyPage = 3)
            val shared = input.dualPageFrames!!.single { pageId(3) in it.unit.pageIds }
            assertEquals(mihon.domain.reader.ReaderDualPageSingleReason.MANUAL_BOUNDARY, shared.unit.singleReason)
            val snapshot = DualPagedPresentation.present(input)
            val unit = snapshot.displayUnits.single { it.slots.any { slot -> slot.page?.id == pageId(3) } }
            val occupied = if (direction == ReaderDirection.RTL) 1 else 0
            assertEquals(pageId(3), unit.slots[occupied].page?.id)
            assertTrue(unit.slots[1 - occupied].isBlankPaper)
            val scene = ImageComposeScene(400, 400, coroutineContext = coroutineContext) {
                mihon.desktop.ui.reader.DualPageDisplayUnitFrame(unit, {}) { _, _ -> }
            }
            try {
                scene.render(0).use { image ->
                    org.jetbrains.skia.Bitmap.makeFromImage(image).use { bitmap ->
                        assertEquals(0xFFFFFFFF.toInt(), bitmap.getColor(if (occupied == 1) 20 else 380, 100))
                    }
                }
                assertEquals(setOf(pageId(3)), snapshot.resolveDualVisiblePages(unit.id).pageIds)
            } finally {
                scene.close()
            }
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `certified internal single draws paper from its published frame`() = runTest {
        val input = request(
            pageCount = 5,
            direction = ReaderDirection.LTR,
            options = PresentationFixtureOptions(spreadPageIds = setOf(pageId(2))),
            stateAt = { ReaderPageLoadState.Ready },
        ).withSharedOwner(projectFrames = true, certifyPage = 1)
        val snapshot = DualPagedPresentation.present(input)
        val unit = snapshot.displayUnits.single { it.slots.any { slot -> slot.page?.id == pageId(1) } }
        assertEquals(1, unit.slots.count { it.page != null })
        val emptyIndex = unit.slots.indexOfFirst { it.page == null }
        val scene = ImageComposeScene(400, 400, coroutineContext = coroutineContext) {
            mihon.desktop.ui.reader.DualPageDisplayUnitFrame(unit, {}) { _, _ -> }
        }
        try {
            scene.render(0).use { image ->
                org.jetbrains.skia.Bitmap.makeFromImage(image).use { bitmap ->
                    assertEquals(0xFFFFFFFF.toInt(), bitmap.getColor(if (emptyIndex == 0) 20 else 380, 100))
                }
            }
            assertEquals(setOf(pageId(1)), snapshot.resolveDualVisiblePages(unit.id).pageIds)
        } finally {
            scene.close()
        }
    }

    @OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
    @Test
    fun `published blank flag redraws without rebuilding the physical frame`() = runTest {
        val input = request(
            pageCount = 5,
            direction = ReaderDirection.LTR,
            options = PresentationFixtureOptions(forcedSinglePageIds = setOf(pageId(2))),
            stateAt = { ReaderPageLoadState.Ready },
        ).withSharedOwner(forcedSingles = setOf(2), projectFrames = true, certifyPage = 2)
        val hiddenInput = input.copy(
            dualPageFrames = input.dualPageFrames!!.map { it.copy(blankPaperSlot = null) },
        )
        fun target(request: ReaderPresentationRequest) = DualPagedPresentation.present(request).displayUnits.single {
            it.slots.any { slot -> slot.page?.id == pageId(2) }
        }
        val before = target(hiddenInput)
        val after = target(input)
        assertEquals(before.id, after.id)
        assertEquals(before.slots.map(DisplaySlot::id), after.slots.map(DisplaySlot::id))
        assertTrue(after.slots.any(DisplaySlot::isBlankPaper))
        val displayed = mutableStateOf(before)
        var realImageMount: Any? = null
        val scene = ImageComposeScene(400, 400, coroutineContext = coroutineContext) {
            mihon.desktop.ui.reader.DualPageDisplayUnitFrame(displayed.value, {}) { _, modifier ->
                val mount = remember { Any() }
                SideEffect { realImageMount = mount }
                Canvas(modifier) { drawRect(Color.Red) }
            }
        }
        fun frameIdentity(): Any {
            fun descendants(node: SemanticsNode): List<SemanticsNode> =
                listOf(node) + node.children.flatMap(::descendants)
            val key = mihon.desktop.ui.reader.DualPageDisplayUnitCompositionIdentityKey
            return scene.semanticsOwners.flatMap { descendants(it.rootSemanticsNode) }
                .single { it.config.contains(key) }.config[key]
        }
        val emptyIndex = after.slots.indexOfFirst { it.page == null }
        var time = 0L
        fun paperPixel(): Int {
            time += 16_000_000L
            return scene.render(time).use { image ->
                org.jetbrains.skia.Bitmap.makeFromImage(image).use { bitmap ->
                    bitmap.getColor(if (emptyIndex == 0) 20 else 380, 100)
                }
            }
        }
        try {
            assertEquals(0, paperPixel())
            val frame = frameIdentity()
            val realImage = realImageMount
            assertTrue(realImage != null, "The real page child must be mounted before a flag update")
            displayed.value = after
            runCurrent()
            assertEquals(0xFFFFFFFF.toInt(), paperPixel())
            assertSame(frame, frameIdentity())
            assertSame(realImage, realImageMount)
            displayed.value = before
            runCurrent()
            assertEquals(0, paperPixel())
            assertSame(frame, frameIdentity())
            assertSame(realImage, realImageMount)
        } finally {
            scene.close()
        }
    }

    @Test
    fun `portrait first and trailing singles occupy opposite physical halves for each direction`() {
        for (direction in listOf(ReaderDirection.LTR, ReaderDirection.RTL)) {
            val snapshot = DualPagedPresentation.present(request(pageCount = 4, direction = direction))
            assertEquals(listOf(listOf(0), listOf(1, 2), listOf(3)), snapshot.logicalGroups())
            val first = snapshot.displayUnits.first().slots.map { it.page?.id }
            val trailing = snapshot.displayUnits.last().slots.map { it.page?.id }
            if (direction == ReaderDirection.RTL) {
                assertEquals(listOf(pageId(0), null), first)
                assertEquals(listOf(null, pageId(3)), trailing)
            } else {
                assertEquals(listOf(null, pageId(0)), first)
                assertEquals(listOf(pageId(3), null), trailing)
            }
        }
    }

    @Test
    fun `one portrait page fills the viewport in either direction`() {
        for (direction in listOf(ReaderDirection.LTR, ReaderDirection.RTL)) {
            val only = present(pageCount = 1, direction = direction).displayUnits.single()
            assertEquals(listOf(pageId(0)), only.slots.map { it.page?.id })
        }
    }

    @Test
    fun `landscape cover remains whole across the complete viewport for one and many page chapters`() {
        for (pageCount in listOf(1, 4)) {
            for (direction in listOf(ReaderDirection.LTR, ReaderDirection.RTL)) {
                for (splitCover in listOf(false, true)) {
                    val coverId = pageId(0)
                    val snapshot = present(
                        pageCount = pageCount,
                        direction = direction,
                        splitPageIds = if (splitCover) setOf(coverId) else emptySet(),
                        options = PresentationFixtureOptions(spreadPageIds = setOf(coverId)),
                    )

                    val cover = snapshot.displayUnits.first()
                    assertEquals(listOf(coverId), cover.slots.map { it.page?.id })
                    assertNull(cover.slots.single().splitHalf)
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
        assertEquals(pageId(1), visible.activePageId)
        assertEquals(pageId(1), ltr.resolveDualVisiblePages(ltr.displayUnits[1].id).activePageId)
    }

    @Test
    fun `missing pager boundaries remain independent items in both directions`() {
        listOf(ReaderDirection.LTR, ReaderDirection.RTL).forEach { direction ->
            val snapshot = present(
                pageCount = 3,
                direction = direction,
                hasPreviousChapter = false,
                hasNextChapter = false,
            )

            assertEquals(ReaderTransitionDirection.PREVIOUS, snapshot.displayUnits.first().transitionDirection)
            assertEquals(ReaderTransitionDirection.NEXT, snapshot.displayUnits.last().transitionDirection)
            assertEquals(
                listOf(0, 1, 2),
                snapshot.displayUnits.drop(1).dropLast(1).flatMap { unit ->
                    unit.slots.mapNotNull { it.page?.id?.sourcePageIndex }.distinct()
                }.sorted(),
            )
        }
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
        assertEquals(
            loading.displayUnits[1].slots.map(DisplaySlot::id),
            ready.displayUnits[1].slots.map(DisplaySlot::id),
        )
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
            options = PresentationFixtureOptions(spreadPageIds = setOf(pageId(3))),
        )
        assertEquals(
            listOf(listOf(0), listOf(1, 2), listOf(3), listOf(4, 5), listOf(6, 7)),
            spread.logicalGroups(),
        )

        val forced = present(
            pageCount = 8,
            direction = ReaderDirection.LTR,
            options = PresentationFixtureOptions(forcedSinglePageIds = setOf(pageId(3))),
        )
        assertEquals(
            listOf(listOf(0), listOf(1, 2), listOf(3), listOf(4, 5), listOf(6, 7)),
            forced.logicalGroups(),
        )

        val matched = present(
            pageCount = 7,
            direction = ReaderDirection.LTR,
            options = PresentationFixtureOptions(
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
            options = PresentationFixtureOptions(spreadPageIds = setOf(pageId(3))),
        )
        val spread = snapshot.displayUnits.single { unit -> unit.slots.any { it.page?.id == pageId(3) } }

        assertEquals(listOf(pageId(3), pageId(3)), spread.slots.map { it.page?.id })
        assertEquals(listOf(PageSplitHalf.RIGHT, PageSplitHalf.LEFT), spread.slots.map(DisplaySlot::splitHalf))
        assertEquals(setOf(pageId(3)), snapshot.resolveDualVisiblePages(spread.id).pageIds)
    }

    @Test
    fun `registry exposes dual beside single and webtoon`() {
        assertSame(DualPagedPresentation, DesktopReaderPresentationRegistry.require(ReaderPresentationMode.DUAL_PAGED))
        assertSame(
            SinglePagedPresentation,
            DesktopReaderPresentationRegistry.require(ReaderPresentationMode.SINGLE_PAGED),
        )
        assertSame(WebtoonPresentation, DesktopReaderPresentationRegistry.require(ReaderPresentationMode.WEBTOON))
    }

    private fun present(
        pageCount: Int,
        direction: ReaderDirection,
        hasPreviousChapter: Boolean = true,
        hasNextChapter: Boolean = true,
        splitPageIds: Set<ReaderPageId> = emptySet(),
        options: PresentationFixtureOptions = PresentationFixtureOptions(),
        stateAt: (Int) -> ReaderPageLoadState = { ReaderPageLoadState.Queued },
        imageAt: (Int) -> String? = { null },
    ): ReaderPresentationSnapshot = DualPagedPresentation.present(
        request(pageCount, direction, hasPreviousChapter, hasNextChapter, splitPageIds, options, stateAt, imageAt),
    )

    private fun request(
        pageCount: Int,
        direction: ReaderDirection,
        hasPreviousChapter: Boolean = true,
        hasNextChapter: Boolean = true,
        splitPageIds: Set<ReaderPageId> = emptySet(),
        options: PresentationFixtureOptions = PresentationFixtureOptions(),
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
            hasPreviousChapter = hasPreviousChapter,
            hasNextChapter = hasNextChapter,
            splitPageIds = splitPageIds,
            dualPagedOptions = DualPagedPresentationOptions(spreadPageIds = options.spreadPageIds),
            pageSizes = (0 until pageCount).associate { index ->
                pageId(index) to
                    if (pageId(index) in options.spreadPageIds) {
                        mihon.domain.reader.ReaderPageSize(80, 40)
                    } else {
                        mihon.domain.reader.ReaderPageSize(40, 60)
                    }
            },
        ).withSharedOwner(
            forcedSingles = options.forcedSinglePageIds.mapTo(linkedSetOf()) { it.sourcePageIndex },
            matchedPairs = options.matchedPagePairs.mapTo(linkedSetOf()) {
                it.first.sourcePageIndex to
                    it.second.sourcePageIndex
            },
        )

    private fun ReaderPresentationSnapshot.logicalGroups(): List<List<Int>> = displayUnits.map { unit ->
        unit.slots.mapNotNull { it.page?.id?.sourcePageIndex }.distinct().sorted()
    }

    private fun pageId(index: Int) = ReaderPageId(chapterId, index)

    private companion object {
        val chapterId = ReaderChapterId(88L)
    }
}

private data class PresentationFixtureOptions(
    val spreadPageIds: Set<ReaderPageId> = emptySet(),
    val forcedSinglePageIds: Set<ReaderPageId> = emptySet(),
    val matchedPagePairs: Set<Pair<ReaderPageId, ReaderPageId>> = emptySet(),
)
