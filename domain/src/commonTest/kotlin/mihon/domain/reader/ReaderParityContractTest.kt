package mihon.domain.reader

import mihon.domain.error.AppError
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReaderParityContractTest {

    @Test
    fun `Android portrait pairing vectors are preserved by the shared algorithm`() {
        val pairings = ReaderPagePairing.build(
            pageCount = 4,
            layoutAt = { PageLayout.PORTRAIT },
        )

        assertEquals(2, pairings.size)
        assertArrayEquals(intArrayOf(0, 1), pairings[0])
        assertArrayEquals(intArrayOf(2, 3), pairings[1])
    }

    @Test
    fun `unknown and spread pages stay single while offset realigns portrait pairs`() {
        val layouts = listOf(PageLayout.UNKNOWN, PageLayout.SPREAD, PageLayout.PORTRAIT, PageLayout.PORTRAIT)
        val pairings = ReaderPagePairing.build(
            pageCount = layouts.size,
            layoutAt = layouts::get,
            offset = 1,
        )

        assertEquals(listOf(listOf(0), listOf(1), listOf(2, 3)), pairings.map(IntArray::toList))
    }

    @Test
    fun `R2L pairing state reverses display units without reversing pages inside a pair`() {
        val state = ReaderPairingState(pageCount = 4, isRtl = true)
        repeat(4) { state.updateDimensions(it, width = 100, height = 200) }

        assertEquals(listOf(listOf(2, 3), listOf(0, 1)), state.pairings.map(IntArray::toList))
        assertEquals(1, state.findDisplayUnitIndexForPage(0))
    }

    @Test
    fun `Desktop cover edge matching and landscape parity are explicit enhancements`() {
        val pairings = ReaderPagePairing.build(
            pageCount = 7,
            layoutAt = { if (it == 3) PageLayout.SPREAD else PageLayout.PORTRAIT },
            options = PagePairingOptions(
                forceFirstPageSingle = true,
                matchedPairs = setOf(PagePair(1, 2)),
                preserveParityAfterSpread = true,
            ),
        )

        assertEquals(
            listOf(listOf(0), listOf(1, 2), listOf(3), listOf(4), listOf(5, 6)),
            pairings.map(IntArray::toList),
        )
        val defaultPairings = ReaderPagePairing.build(4, { PageLayout.PORTRAIT })
        assertEquals(listOf(listOf(0, 1), listOf(2, 3)), defaultPairings.map(IntArray::toList))
    }

    @Test
    fun `wide page classification respects rotation and rejects invalid dimensions`() {
        assertEquals(PageLayout.SPREAD, classifyPage(width = 200, height = 100, rotation = PageRotation.NONE))
        assertEquals(PageLayout.PORTRAIT, classifyPage(width = 200, height = 100, rotation = PageRotation.CLOCKWISE_90))
        assertEquals(PageLayout.UNKNOWN, classifyPage(width = 0, height = 100, rotation = PageRotation.NONE))
        assertEquals(PageLayout.UNKNOWN, classifyPage(width = -1, height = 100, rotation = PageRotation.NONE))
    }

    @Test
    fun `odd width split covers every pixel exactly once`() {
        val left = splitPageBounds(201, 100, PageSplitHalf.LEFT)
        val right = splitPageBounds(201, 100, PageSplitHalf.RIGHT)

        assertEquals(PixelBounds(0, 0, 100, 100), left)
        assertEquals(PixelBounds(100, 0, 101, 100), right)
        assertEquals(201, left!!.width + right!!.width)
        assertNull(splitPageBounds(0, 100, PageSplitHalf.LEFT))
    }

    @Test
    fun `virtual wide pages map back to their source page in reading order`() {
        val ltr = buildVirtualReaderPages(3, setOf(1), ReaderDirection.LTR)
        val rtl = buildVirtualReaderPages(3, setOf(1), ReaderDirection.RTL)

        assertEquals(PageSplitHalf.LEFT, ltr[1].splitHalf)
        assertEquals(PageSplitHalf.RIGHT, ltr[2].splitHalf)
        assertEquals(PageSplitHalf.RIGHT, rtl[1].splitHalf)
        assertEquals(PageSplitHalf.LEFT, rtl[2].splitHalf)
        assertEquals(1, ltr[1].sourcePageIndex)
        assertEquals(1, ltr[2].sourcePageIndex)
    }

    @Test
    fun `chapter transition exposes wait loading loaded error missing count and retry command`() {
        val from = ReaderChapterModel(1, "/1", "Chapter 1", 1.0)
        val to = ReaderChapterModel(3, "/3", "Chapter 3", 3.0)
        val transition = ReaderChapterTransitionModel(
            direction = ReaderTransitionDirection.NEXT,
            from = from,
            to = to,
            missingChapterCount = 1,
            state = ReaderChapterState.Error(AppError.Network()),
        )

        assertInstanceOf(ReaderChapterState.Error::class.java, transition.state)
        assertEquals(1, transition.missingChapterCount)
        assertEquals(ReaderNavigationCommand.RetryChapter(3), transition.retryCommand())
        assertInstanceOf(ReaderChapterState.Wait::class.java, ReaderChapterState.Wait)
        assertInstanceOf(ReaderChapterState.Loading::class.java, ReaderChapterState.Loading)
        assertEquals(emptyList<ReaderPageModel>(), ReaderChapterState.Loaded(emptyList()).pages)
    }

    @Test
    fun `transition without an adjacent chapter returns an explicit boundary command`() {
        val from = ReaderChapterModel(1, "/1", "Only chapter", 1.0)
        val transition = ReaderChapterTransitionModel(
            direction = ReaderTransitionDirection.NEXT,
            from = from,
            to = null,
        )

        assertEquals(ReaderNavigationCommand.ChapterBoundary(ReaderTransitionDirection.NEXT), transition.retryCommand())
    }

    @Test
    fun `Android navigation presets support horizontal vertical and combined inversion`() {
        assertEquals(
            ReaderNavigationCommand.Previous,
            ReaderNavigation.commandAt(0.1f, 0.5f, NavigationPreset.RIGHT_AND_LEFT),
        )
        assertEquals(
            ReaderNavigationCommand.Next,
            ReaderNavigation.commandAt(
                0.1f,
                0.5f,
                NavigationPreset.RIGHT_AND_LEFT,
                inversion = NavigationInversion.HORIZONTAL,
            ),
        )
        assertEquals(
            ReaderNavigationCommand.Next,
            ReaderNavigation.commandAt(
                0.5f,
                0.1f,
                NavigationPreset.L,
                inversion = NavigationInversion.VERTICAL,
            ),
        )
        assertEquals(
            ReaderNavigationCommand.Previous,
            ReaderNavigation.commandAt(
                0.5f,
                0.9f,
                NavigationPreset.L,
                inversion = NavigationInversion.BOTH,
            ),
        )
    }

    @Test
    fun `reading direction reverses physical page commands but not menu`() {
        assertEquals(
            ReaderNavigationCommand.Previous,
            ReaderNavigation.resolvePhysicalPageCommand(PhysicalDirection.LEFT, ReaderDirection.LTR),
        )
        assertEquals(
            ReaderNavigationCommand.Next,
            ReaderNavigation.resolvePhysicalPageCommand(PhysicalDirection.LEFT, ReaderDirection.RTL),
        )
        assertEquals(
            ReaderNavigationCommand.Next,
            ReaderNavigation.resolvePhysicalPageCommand(PhysicalDirection.DOWN, ReaderDirection.VERTICAL),
        )
    }

    @Test
    fun `read filtered and duplicate skip rules can be combined`() {
        val chapters = listOf(
            ReaderChapterEntry(5, isRead = false),
            ReaderChapterEntry(4, isRead = true),
            ReaderChapterEntry(3, isFiltered = true),
            ReaderChapterEntry(2, isDuplicate = true),
            ReaderChapterEntry(1),
        )
        val result = findAdjacentChapter(
            chapters = chapters,
            currentIndex = 0,
            direction = ChapterListDirection.OLDER,
            skipPolicy = ChapterSkipPolicy(read = true, filtered = true, duplicate = true),
        )

        assertEquals(ChapterNavigationResult.Found(index = 4, chapter = chapters[4]), result)
    }

    @Test
    fun `duplicate identification preserves current chapter then matching scanlator like Android`() {
        val chapters = listOf(
            ReaderChapterEntry(5, chapterNumber = 5.0, scanlator = "A"),
            ReaderChapterEntry(41, chapterNumber = 4.0, scanlator = "A"),
            ReaderChapterEntry(42, chapterNumber = 4.0, scanlator = "B"),
            ReaderChapterEntry(31, chapterNumber = 3.0, scanlator = "A"),
            ReaderChapterEntry(32, chapterNumber = 3.0, scanlator = "B"),
        )

        val marked = markDuplicateChapters(chapters, currentChapterId = 42)

        assertEquals(setOf(41L, 31L), marked.filter(ReaderChapterEntry::isDuplicate).mapTo(mutableSetOf()) { it.id })
        assertFalse(marked.first { it.id == 42L }.isDuplicate)
        assertFalse(marked.first { it.id == 32L }.isDuplicate)
    }

    @Test
    fun `chapter skip at list boundary is explicit and never falls back to wrong chapter`() {
        val chapters = listOf(ReaderChapterEntry(2, isRead = false), ReaderChapterEntry(1, isRead = true))

        assertEquals(
            ChapterNavigationResult.Boundary(ChapterListDirection.OLDER),
            findAdjacentChapter(chapters, 0, ChapterListDirection.OLDER, ChapterSkipPolicy(read = true)),
        )
        assertEquals(
            ChapterNavigationResult.InvalidCurrent,
            findAdjacentChapter(chapters, 99, ChapterListDirection.NEWER, ChapterSkipPolicy()),
        )
    }

    @Test
    fun `reader chapter filtering applies all skip flags but always retains the active chapter`() {
        val chapters = listOf(
            ReaderChapterEntry(5, isRead = true),
            ReaderChapterEntry(4, isFiltered = true),
            ReaderChapterEntry(3, isDuplicate = true),
            ReaderChapterEntry(2),
        )

        val filtered = filterChaptersForReader(
            chapters = chapters,
            currentChapterId = 4,
            skipPolicy = ChapterSkipPolicy(read = true, filtered = true, duplicate = true),
        )

        assertEquals(listOf(4L, 2L), filtered.map { it.id })
    }

    @Test
    fun `preload planner prioritizes current then forward then backward and cancels stale requests`() {
        val planner = ReaderPreloadPlanner(windowSize = 2)
        val first = planner.moveTo(currentPage = 2, pageCount = 8)
        val second = planner.moveTo(currentPage = 6, pageCount = 8)

        assertEquals(listOf(2, 3, 4, 1, 0), first.requests.map { it.pageIndex })
        assertEquals(PreloadPriority.CURRENT, first.requests.first().priority)
        assertTrue(second.cancelPageIndices.containsAll(listOf(0, 1, 2, 3)))
        assertEquals(setOf(4, 5, 6, 7), second.keepPageIndices)
        assertTrue(second.evictPageIndices.containsAll(listOf(0, 1, 2, 3)))
        assertTrue(second.generation > first.generation)
    }

    @Test
    fun `Android preload window keeps authoritative forward-only behavior`() {
        val planner = ReaderPreloadPlanner(windowSize = 4, backwardWindowSize = 0)

        val plan = planner.moveTo(currentPage = 2, pageCount = 8)

        assertEquals(listOf(2, 3, 4, 5, 6), plan.requests.map { it.pageIndex })
        assertEquals(setOf(2, 3, 4, 5, 6), plan.keepPageIndices)
    }

    @Test
    fun `decode and cache contracts expose platform neutral requests failures and byte budgets`() {
        val request = PageDecodeRequest(
            pageIndex = 4,
            maxWidth = 1920,
            maxHeight = 1080,
            region = PixelBounds(100, 0, 100, 200),
        )
        val failure: PageDecodeResult<String> = PageDecodeResult.Failure(AppError.MalformedData())
        val cacheSnapshot = PageCacheSnapshot(keys = setOf(4), usedBytes = 1024, maxBytes = 2048)

        assertEquals(4, request.pageIndex)
        assertInstanceOf(PageDecodeResult.Failure::class.java, failure)
        assertEquals(1024, cacheSnapshot.availableBytes)
    }

    @Test
    fun `brightness tint grayscale and invert parameters clamp inputs and report effective state`() {
        val disabled = ReaderColorFilterParams(enabled = false, grayscale = true, invert = true)
        val effective = ReaderColorFilterParams(
            enabled = true,
            brightness = 2f,
            r = -1,
            g = 128,
            b = 300,
            alpha = 999,
            grayscale = true,
            invert = true,
        ).normalized()

        assertFalse(disabled.isEffective)
        assertEquals(ReaderColorFilterParams.BRIGHTNESS_MAX, effective.brightness)
        assertEquals(0, effective.r)
        assertEquals(255, effective.b)
        assertEquals(255, effective.alpha)
        assertTrue(effective.grayscale)
        assertTrue(effective.invert)
        assertTrue(effective.isEffective)
    }
}
