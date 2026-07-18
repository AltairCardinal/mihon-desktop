package mihon.domain.reader

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import mihon.domain.error.AppError
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReaderParityContractTest {

    @Test
    fun `fork-added shared portrait pairing enhancement groups adjacent pages`() {
        val pairings = ReaderPagePairing.build(
            pageCount = 4,
            layoutAt = { PageLayout.PORTRAIT },
        )

        assertEquals(2, pairings.size)
        assertArrayEquals(intArrayOf(0, 1), pairings[0])
        assertArrayEquals(intArrayOf(2, 3), pairings[1])
    }

    @Test
    fun `unknown and spread pages stay single without relying on offset`() {
        val layouts = listOf(PageLayout.UNKNOWN, PageLayout.SPREAD, PageLayout.PORTRAIT, PageLayout.PORTRAIT)
        val pairings = ReaderPagePairing.build(
            pageCount = layouts.size,
            layoutAt = layouts::get,
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
    fun `Desktop cover single is disabled by default and independently enabled`() {
        val defaultPairings = ReaderPagePairing.build(4, { PageLayout.PORTRAIT })
        val coverSingle = ReaderPagePairing.build(
            pageCount = 4,
            layoutAt = { PageLayout.PORTRAIT },
            options = PagePairingOptions(forceFirstPageSingle = true),
        )

        assertEquals(listOf(listOf(0, 1), listOf(2, 3)), defaultPairings.map(IntArray::toList))
        assertEquals(listOf(listOf(0), listOf(1, 2), listOf(3)), coverSingle.map(IntArray::toList))
    }

    @Test
    fun `Desktop forced singles are disabled by default and independently enabled`() {
        val defaultPairings = ReaderPagePairing.build(4, { PageLayout.PORTRAIT })
        val forcedSingle = ReaderPagePairing.build(
            pageCount = 4,
            layoutAt = { PageLayout.PORTRAIT },
            options = PagePairingOptions(forcedSinglePages = setOf(1)),
        )

        assertEquals(listOf(listOf(0, 1), listOf(2, 3)), defaultPairings.map(IntArray::toList))
        assertEquals(listOf(listOf(0), listOf(1), listOf(2, 3)), forcedSingle.map(IntArray::toList))
    }

    @Test
    fun `Desktop edge matching is disabled by default and independently enabled`() {
        val defaultPairings = ReaderPagePairing.build(5, { PageLayout.PORTRAIT })
        val edgeMatched = ReaderPagePairing.build(
            pageCount = 5,
            layoutAt = { PageLayout.PORTRAIT },
            options = PagePairingOptions(matchedPairs = setOf(PagePair(1, 2))),
        )

        assertEquals(listOf(listOf(0, 1), listOf(2, 3), listOf(4)), defaultPairings.map(IntArray::toList))
        assertEquals(listOf(listOf(0), listOf(1, 2), listOf(3, 4)), edgeMatched.map(IntArray::toList))
    }

    @Test
    fun `Desktop landscape parity is disabled by default and independently enabled`() {
        val layoutAt: (Int) -> PageLayout = { if (it == 1) PageLayout.SPREAD else PageLayout.PORTRAIT }
        val defaultPairings = ReaderPagePairing.build(5, layoutAt)
        val parityPreserved = ReaderPagePairing.build(
            pageCount = 5,
            layoutAt = layoutAt,
            options = PagePairingOptions(preserveParityAfterSpread = true),
        )

        assertEquals(listOf(listOf(0), listOf(1), listOf(2, 3), listOf(4)), defaultPairings.map(IntArray::toList))
        assertEquals(listOf(listOf(0), listOf(1), listOf(2), listOf(3, 4)), parityPreserved.map(IntArray::toList))
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
    fun `rotated split maps display halves back to complete non-overlapping source bounds`() {
        val clockwiseLeft = splitPageBounds(200, 101, PageSplitHalf.LEFT, PageRotation.CLOCKWISE_90)
        val clockwiseRight = splitPageBounds(200, 101, PageSplitHalf.RIGHT, PageRotation.CLOCKWISE_90)
        val counterClockwiseLeft = splitPageBounds(200, 101, PageSplitHalf.LEFT, PageRotation.COUNTER_CLOCKWISE_90)
        val counterClockwiseRight = splitPageBounds(200, 101, PageSplitHalf.RIGHT, PageRotation.COUNTER_CLOCKWISE_90)

        assertEquals(PixelBounds(0, 51, 200, 50), clockwiseLeft)
        assertEquals(PixelBounds(0, 0, 200, 51), clockwiseRight)
        assertEquals(PixelBounds(0, 0, 200, 50), counterClockwiseLeft)
        assertEquals(PixelBounds(0, 50, 200, 51), counterClockwiseRight)
        assertEquals(101, clockwiseLeft!!.height + clockwiseRight!!.height)
        assertEquals(clockwiseRight.y + clockwiseRight.height, clockwiseLeft.y)
        assertEquals(101, counterClockwiseLeft!!.height + counterClockwiseRight!!.height)
        assertEquals(counterClockwiseLeft.y + counterClockwiseLeft.height, counterClockwiseRight.y)

        val evenClockwiseLeft = splitPageBounds(200, 100, PageSplitHalf.LEFT, PageRotation.CLOCKWISE_90)
        val evenClockwiseRight = splitPageBounds(200, 100, PageSplitHalf.RIGHT, PageRotation.CLOCKWISE_90)
        assertEquals(PixelBounds(0, 50, 200, 50), evenClockwiseLeft)
        assertEquals(PixelBounds(0, 0, 200, 50), evenClockwiseRight)
        assertEquals(100, evenClockwiseLeft!!.height + evenClockwiseRight!!.height)
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
    fun `vertical virtual wide pages follow fixed original Mihon Webtoon order and expose rotated source bounds`() {
        val vertical = buildVirtualReaderPages(
            totalPages = 1,
            spreadPages = setOf(0),
            direction = ReaderDirection.VERTICAL,
            pageSizes = mapOf(0 to ReaderPageSize(width = 200, height = 101)),
            rotations = mapOf(0 to PageRotation.CLOCKWISE_90),
        )

        assertEquals(listOf(PageSplitHalf.RIGHT, PageSplitHalf.LEFT), vertical.map { it.splitHalf })
        assertEquals(PixelBounds(0, 0, 200, 51), vertical[0].sourceBounds)
        assertEquals(PixelBounds(0, 51, 200, 50), vertical[1].sourceBounds)
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
            state = ReaderChapterState.Error(AppError.Network(), retryTargetChapterId = to.id),
        )

        assertInstanceOf(ReaderChapterState.Error::class.java, transition.state)
        assertEquals(1, transition.missingChapterCount)
        assertEquals(ReaderNavigationCommand.RetryChapter(3), transition.retryCommand())
        assertInstanceOf(ReaderChapterState.Wait::class.java, ReaderChapterState.Wait)
        assertInstanceOf(ReaderChapterState.Loading::class.java, ReaderChapterState.Loading)
        assertEquals(emptyList<ReaderPageModel>(), ReaderChapterState.Loaded(emptyList()).pages)
    }

    @Test
    fun `current chapter error retries the current chapter through the shared command`() {
        val current = ReaderChapterModel(1, "/1", "Chapter 1", 1.0)
        val error = ReaderChapterState.Error(AppError.Network(), retryTargetChapterId = current.id)
        val transition = ReaderChapterTransitionModel(
            direction = ReaderTransitionDirection.NEXT,
            from = current,
            to = null,
            state = error,
        )

        assertEquals(ReaderNavigationCommand.RetryChapter(current.id), error.retryCommand())
        assertEquals(ReaderNavigationCommand.RetryChapter(current.id), transition.retryCommand())
    }

    @Test
    fun `previous chapter error retries the previous target through the shared command`() {
        val current = ReaderChapterModel(3, "/3", "Chapter 3", 3.0)
        val previous = ReaderChapterModel(1, "/1", "Chapter 1", 1.0)
        val transition = ReaderChapterTransitionModel(
            direction = ReaderTransitionDirection.PREVIOUS,
            from = current,
            to = previous,
            state = ReaderChapterState.Error(AppError.Network(), retryTargetChapterId = previous.id),
        )

        assertEquals(ReaderNavigationCommand.RetryChapter(previous.id), transition.retryCommand())
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
    fun `fixed original Mihon navigation presets support horizontal vertical and combined inversion`() {
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
    fun `duplicate identification matches fixed original Mihon current-chapter and scanlator order`() {
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
    fun `duplicate identification is recomputed when the active chapter changes`() {
        val chapters = listOf(
            ReaderChapterEntry(41, chapterNumber = 4.0, scanlator = "A"),
            ReaderChapterEntry(42, chapterNumber = 4.0, scanlator = "B"),
            ReaderChapterEntry(31, chapterNumber = 3.0, scanlator = "A"),
            ReaderChapterEntry(32, chapterNumber = 3.0, scanlator = "B"),
        )
        val markedForB = markDuplicateChapters(chapters, currentChapterId = 42)

        val markedForA = markDuplicateChapters(markedForB, currentChapterId = 41)

        assertEquals(
            setOf(42L, 32L),
            markedForA.filter(ReaderChapterEntry::isDuplicate).mapTo(mutableSetOf()) {
                it.id
            },
        )
        assertFalse(markedForA.first { it.id == 41L }.isDuplicate)
        assertFalse(markedForA.first { it.id == 31L }.isDuplicate)
    }

    @Test
    fun `duplicate identification clears a stale duplicate flag from the active chapter`() {
        val chapters = listOf(
            ReaderChapterEntry(41, isDuplicate = true, chapterNumber = 4.0, scanlator = "A"),
            ReaderChapterEntry(42, chapterNumber = 4.0, scanlator = "B"),
        )

        val marked = markDuplicateChapters(chapters, currentChapterId = 41)

        assertFalse(marked.first { it.id == 41L }.isDuplicate)
        assertTrue(marked.first { it.id == 42L }.isDuplicate)
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
    fun `reader filtered metadata follows fixed original Mihon manga chapter flags`() {
        assertTrue(
            isReaderChapterFiltered(
                unreadFilterRaw = tachiyomi.domain.manga.model.Manga.CHAPTER_SHOW_UNREAD,
                downloadedFilterRaw = tachiyomi.domain.manga.model.Manga.SHOW_ALL,
                bookmarkedFilterRaw = tachiyomi.domain.manga.model.Manga.SHOW_ALL,
                chapterIsRead = true,
                chapterIsBookmarked = false,
                chapterIsDownloaded = false,
            ),
        )
        assertTrue(
            isReaderChapterFiltered(
                unreadFilterRaw = tachiyomi.domain.manga.model.Manga.SHOW_ALL,
                downloadedFilterRaw = tachiyomi.domain.manga.model.Manga.CHAPTER_SHOW_DOWNLOADED,
                bookmarkedFilterRaw = tachiyomi.domain.manga.model.Manga.SHOW_ALL,
                chapterIsRead = false,
                chapterIsBookmarked = false,
                chapterIsDownloaded = false,
            ),
        )
        assertTrue(
            isReaderChapterFiltered(
                unreadFilterRaw = tachiyomi.domain.manga.model.Manga.SHOW_ALL,
                downloadedFilterRaw = tachiyomi.domain.manga.model.Manga.SHOW_ALL,
                bookmarkedFilterRaw = tachiyomi.domain.manga.model.Manga.CHAPTER_SHOW_BOOKMARKED,
                chapterIsRead = false,
                chapterIsBookmarked = false,
                chapterIsDownloaded = false,
            ),
        )
        assertFalse(
            isReaderChapterFiltered(
                unreadFilterRaw = tachiyomi.domain.manga.model.Manga.CHAPTER_SHOW_UNREAD,
                downloadedFilterRaw = tachiyomi.domain.manga.model.Manga.CHAPTER_SHOW_DOWNLOADED,
                bookmarkedFilterRaw = tachiyomi.domain.manga.model.Manga.CHAPTER_SHOW_BOOKMARKED,
                chapterIsRead = false,
                chapterIsBookmarked = true,
                chapterIsDownloaded = true,
            ),
        )
    }

    @Test
    fun `preload planner cancels every old generation job and evicts the complete old window`() {
        val planner = ReaderPreloadPlanner(windowSize = 2)
        val first = planner.moveTo(currentPage = 2, pageCount = 8)
        val second = planner.moveTo(currentPage = 6, pageCount = 8)

        assertEquals(listOf(2, 3, 4, 1, 0), first.requests.map { it.pageIndex })
        assertEquals(PreloadPriority.CURRENT, first.requests.first().priority)
        assertEquals(first.requests.mapTo(mutableSetOf()) { it.jobKey }, second.cancelRequests)
        assertEquals(first.keepPageIndices, second.evictPageIndices)
        assertEquals(setOf(0, 1, 2, 3, 4), second.cancelPageIndices)
        assertEquals(setOf(4, 5, 6, 7), second.keepPageIndices)
        assertTrue(second.requests.all { it.generation == second.generation })
        assertFalse(planner.accepts(first.generation))
        assertTrue(planner.accepts(second.generation))
        assertTrue(second.generation > first.generation)
    }

    @Test
    fun `fixed original Mihon preload window keeps forward-only behavior`() {
        val planner = ReaderPreloadPlanner(windowSize = 4, backwardWindowSize = 0)

        val plan = planner.moveTo(currentPage = 2, pageCount = 8)

        assertEquals(listOf(2, 3, 4, 5, 6), plan.requests.map { it.pageIndex })
        assertEquals(setOf(2, 3, 4, 5, 6), plan.keepPageIndices)
    }

    @Test
    fun `decode requests and results carry generation without platform types`() {
        val request = PageDecodeRequest(
            pageIndex = 4,
            generation = 7,
            maxWidth = 1920,
            maxHeight = 1080,
            region = PixelBounds(100, 0, 100, 200),
        )
        val failure: PageDecodeResult<String> = PageDecodeResult.Failure(
            generation = request.generation,
            error = AppError.MalformedData(),
        )

        assertEquals(4, request.pageIndex)
        assertInstanceOf(PageDecodeResult.Failure::class.java, failure)
        assertEquals(request.generation, failure.generation)
    }

    @Test
    fun `negative generations are invalid and cache writes require an active generation`() {
        val cache = ByteBudgetPageCache<String>(maxBytes = 8)

        assertNull(cache.generation)
        assertEquals(
            PageCacheCommitResult.REJECTED_STALE_GENERATION,
            cache.commit(PageCacheWrite(pageIndex = 0, generation = 0, value = "before-start", estimatedBytes = 4)),
        )
        assertEquals(
            PageCacheCommitResult.REJECTED_STALE_GENERATION,
            cache.commit(PageCacheWrite(pageIndex = 0, generation = 7, value = "before-start", estimatedBytes = 4)),
        )
        assertThrows(IllegalArgumentException::class.java) {
            PageDecodeRequest(pageIndex = 0, generation = -1, maxWidth = 1, maxHeight = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PreloadRequest(pageIndex = 0, priority = PreloadPriority.CURRENT, generation = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PageDecodeResult.Success(-1, "decoded", width = 1, height = 1, estimatedBytes = 4)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PageDecodeResult.Failure(-1, AppError.MalformedData())
        }
        assertThrows(IllegalArgumentException::class.java) {
            PageCacheWrite(pageIndex = 0, generation = -1, value = "invalid", estimatedBytes = 4)
        }

        assertTrue(cache.beginGeneration(generation = 0, evictPageIndices = emptySet()))
        assertEquals(0, cache.generation)
        assertEquals(
            PageCacheCommitResult.STORED,
            cache.commit(PageCacheWrite(pageIndex = 0, generation = 0, value = "current", estimatedBytes = 4)),
        )
    }

    @Test
    fun `cache revision publishes late commits and whole-window evictions only`() = runTest {
        val cache = ByteBudgetPageCache<String>(maxBytes = 8)
        val revisions = mutableListOf<Long>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            cache.revision.collect { revisions += it }
        }

        assertEquals(listOf(0L), revisions)
        assertEquals(
            PageCacheCommitResult.REJECTED_STALE_GENERATION,
            cache.commit(PageCacheWrite(pageIndex = 0, generation = 0, value = "before-start", estimatedBytes = 4)),
        )
        assertEquals(listOf(0L), revisions)
        assertTrue(cache.beginGeneration(generation = 1, evictPageIndices = emptySet()))

        val stale = cache.commit(PageCacheWrite(pageIndex = 0, generation = 0, value = "stale", estimatedBytes = 4))
        assertEquals(PageCacheCommitResult.REJECTED_STALE_GENERATION, stale)
        assertEquals(
            PageCacheCommitResult.REJECTED_OVERSIZED,
            cache.commit(PageCacheWrite(pageIndex = 0, generation = 1, value = "oversize", estimatedBytes = 9)),
        )
        assertFalse(cache.beginGeneration(generation = 1, evictPageIndices = setOf(0)))
        assertNull(cache.remove(99))
        cache.clear()
        assertNull(cache.get(0))
        assertEquals(listOf(0L), revisions)

        assertEquals(
            PageCacheCommitResult.STORED,
            cache.commit(PageCacheWrite(pageIndex = 0, generation = 1, value = "fresh-0", estimatedBytes = 4)),
        )
        assertEquals(
            PageCacheCommitResult.STORED,
            cache.commit(PageCacheWrite(pageIndex = 1, generation = 1, value = "fresh-1", estimatedBytes = 4)),
        )
        assertEquals("fresh-0", cache.get(0))
        assertEquals(listOf(0L, 1L, 2L), revisions)

        assertTrue(cache.beginGeneration(generation = 2, evictPageIndices = setOf(0, 1)))
        assertNull(cache.get(0))
        assertNull(cache.get(1))
        assertEquals(listOf(0L, 1L, 2L, 3L), revisions)

        val late = cache.commit(PageCacheWrite(pageIndex = 0, generation = 1, value = "late", estimatedBytes = 4))
        assertEquals(PageCacheCommitResult.REJECTED_STALE_GENERATION, late)
        assertNull(cache.get(0))
        assertEquals(listOf(0L, 1L, 2L, 3L), revisions)
    }

    @Test
    fun `byte budget cache rejects oversize values and evicts least recently used entries`() {
        val cache = ByteBudgetPageCache<String>(maxBytes = 10)
        cache.beginGeneration(generation = 1, evictPageIndices = emptySet())
        assertEquals(
            PageCacheCommitResult.STORED,
            cache.commit(PageCacheWrite(pageIndex = 0, generation = 1, value = "zero", estimatedBytes = 4)),
        )
        assertEquals(
            PageCacheCommitResult.STORED,
            cache.commit(PageCacheWrite(pageIndex = 1, generation = 1, value = "one", estimatedBytes = 4)),
        )
        assertEquals("zero", cache.get(0))
        assertEquals(
            PageCacheCommitResult.STORED,
            cache.commit(PageCacheWrite(pageIndex = 2, generation = 1, value = "two", estimatedBytes = 4)),
        )

        assertEquals(setOf(0, 2), cache.snapshot().keys)
        assertNull(cache.get(1))
        val beforeOversize = cache.snapshot()
        val beforeOversizeRevision = cache.revision.value
        assertEquals(
            PageCacheCommitResult.REJECTED_OVERSIZED,
            cache.commit(PageCacheWrite(pageIndex = 3, generation = 1, value = "oversize", estimatedBytes = 11)),
        )
        assertEquals(beforeOversize, cache.snapshot())
        assertEquals(beforeOversizeRevision, cache.revision.value)
        assertTrue(cache.snapshot().usedBytes <= cache.snapshot().maxBytes)
    }

    @Test
    fun `cache generation change can evict every key from the complete previous window`() {
        val planner = ReaderPreloadPlanner(windowSize = 1)
        val cache = ByteBudgetPageCache<String>(maxBytes = 64)
        val first = planner.moveTo(currentPage = 1, pageCount = 5)
        cache.beginGeneration(first.generation, first.evictPageIndices)
        first.requests.forEach { request ->
            cache.commit(PageCacheWrite(request.pageIndex, request.generation, "page-${request.pageIndex}", 4))
        }
        assertEquals(first.keepPageIndices, cache.snapshot().keys)

        val second = planner.moveTo(currentPage = 2, pageCount = 5)
        cache.beginGeneration(second.generation, second.evictPageIndices)

        assertEquals(first.keepPageIndices, second.evictPageIndices)
        assertTrue(cache.snapshot().keys.isEmpty())
    }

    @Test
    fun `cache snapshots reject negative or over-budget states`() {
        assertThrows(IllegalArgumentException::class.java) {
            PageCacheSnapshot(keys = emptySet(), usedBytes = -1, maxBytes = 8)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PageCacheSnapshot(keys = emptySet(), usedBytes = 9, maxBytes = 8)
        }
    }

    @Test
    fun `tint brightness grayscale and invert can each be enabled independently`() {
        assertEquals(
            listOf(ReaderColorFilterEffect.TINT),
            ReaderColorFilterParams(tintEnabled = true).activeEffects,
        )
        assertEquals(
            listOf(ReaderColorFilterEffect.BRIGHTNESS),
            ReaderColorFilterParams(brightnessEnabled = true, brightness = 0.25f).activeEffects,
        )
        assertEquals(
            listOf(ReaderColorFilterEffect.GRAYSCALE),
            ReaderColorFilterParams(grayscaleEnabled = true).activeEffects,
        )
        assertEquals(
            listOf(ReaderColorFilterEffect.INVERT),
            ReaderColorFilterParams(invertEnabled = true).activeEffects,
        )
    }

    @Test
    fun `disabling tint preserves grayscale and invert in canonical shared order`() {
        val params = ReaderColorFilterParams(
            tintEnabled = false,
            brightnessEnabled = true,
            brightness = 0.25f,
            grayscaleEnabled = true,
            invertEnabled = true,
        )

        assertTrue(params.isEffective)
        assertEquals(
            listOf(
                ReaderColorFilterEffect.BRIGHTNESS,
                ReaderColorFilterEffect.GRAYSCALE,
                ReaderColorFilterEffect.INVERT,
            ),
            params.activeEffects,
        )
    }

    @Test
    fun `filter normalization clamps values without changing independent switches`() {
        val normalized = ReaderColorFilterParams(
            tintEnabled = true,
            brightnessEnabled = true,
            brightness = 2f,
            r = -1,
            g = 128,
            b = 300,
            alpha = 999,
            grayscaleEnabled = true,
            invertEnabled = true,
        ).normalized()

        assertEquals(ReaderColorFilterParams.BRIGHTNESS_MAX, normalized.brightness)
        assertEquals(0, normalized.r)
        assertEquals(255, normalized.b)
        assertEquals(255, normalized.alpha)
        assertTrue(normalized.tintEnabled)
        assertTrue(normalized.brightnessEnabled)
        assertTrue(normalized.grayscaleEnabled)
        assertTrue(normalized.invertEnabled)
        assertEquals(
            listOf(
                ReaderColorFilterEffect.TINT,
                ReaderColorFilterEffect.BRIGHTNESS,
                ReaderColorFilterEffect.GRAYSCALE,
                ReaderColorFilterEffect.INVERT,
            ),
            normalized.activeEffects,
        )
    }
}
