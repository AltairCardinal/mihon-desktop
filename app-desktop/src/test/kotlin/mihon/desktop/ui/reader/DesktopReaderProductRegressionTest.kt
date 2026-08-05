package mihon.desktop.ui.reader

import mihon.desktop.reader.ReaderColorFilter
import mihon.desktop.reader.ReaderKeyboardAction
import mihon.desktop.reader.ReaderPageAction
import mihon.desktop.reader.ReadingMode
import mihon.desktop.reader.buildVirtualPageList
import mihon.desktop.reader.desktopReaderSessionState
import mihon.desktop.reader.readerChapterSession
import mihon.desktop.ui.reader.presentation.DesktopReaderPresentationRegistry
import mihon.desktop.ui.reader.presentation.ReaderPresentationMode
import mihon.desktop.ui.reader.presentation.desktopReaderPresentationRequest
import mihon.domain.reader.ReaderDirection
import mihon.domain.reader.PixelBounds
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

class DesktopReaderProductRegressionTest {

    @Test
    fun `adjust spread shifts subsequent pairing and can restore the previous boundary`() {
        val baseline = ReaderScreenModel(
            initialSessionState = desktopReaderSessionState(pageCount = 6),
        ).state.value.copy(
            currentPage = 2,
            dualPageMode = true,
        )

        val shiftedSingles = adjustedForcedSinglePages(baseline)

        assertEquals(setOf(1), shiftedSingles)
        assertEquals(
            listOf(listOf(0), listOf(1), listOf(2, 3), listOf(4, 5)),
            dualGroups(pageCount = 6, forcedSinglePages = shiftedSingles),
        )

        val restoredSingles = adjustedForcedSinglePages(
            baseline.copy(currentPage = 3, forcedSinglePages = shiftedSingles),
        )
        assertEquals(emptySet<Int>(), restoredSingles)
        assertEquals(
            listOf(listOf(0), listOf(1, 2), listOf(3, 4), listOf(5)),
            dualGroups(pageCount = 6, forcedSinglePages = restoredSingles),
        )
    }

    @Test
    fun `manual spread adjustment takes precedence over automatic matched pairs`() {
        val automaticallyMatched = ReaderScreenModel(
            initialSessionState = desktopReaderSessionState(pageCount = 6),
        ).state.value.copy(
            currentPage = 3,
            dualPageMode = true,
            readingMode = ReadingMode.LTR,
            matchedPairs = setOf(2 to 3),
        )

        val adjustedPages = adjustedForcedSinglePages(automaticallyMatched)
        val manuallyAdjusted = automaticallyMatched.copy(
            currentPage = 4,
            forcedSinglePages = adjustedPages,
        )

        assertEquals(setOf(2), adjustedPages)
        assertEquals(emptySet<Pair<Int, Int>>(), manuallyAdjusted.effectiveMatchedPairs())
        assertEquals(
            listOf(listOf(0), listOf(1), listOf(2), listOf(3, 4), listOf(5)),
            manuallyAdjusted.dualPresentationSnapshot().displayUnits.map { unit ->
                unit.slots.mapNotNull { it.page?.id?.sourcePageIndex }
            },
        )

        val restoredPages = adjustedForcedSinglePages(manuallyAdjusted)

        assertEquals(emptySet<Int>(), restoredPages)
        assertEquals(
            setOf(2 to 3),
            manuallyAdjusted.copy(forcedSinglePages = restoredPages).effectiveMatchedPairs(),
        )
    }

    @Test
    fun `adjust spread preserves unrelated manual boundaries`() {
        val state = ReaderScreenModel(
            initialSessionState = desktopReaderSessionState(pageCount = 8),
        ).state.value.copy(
            currentPage = 3,
            dualPageMode = true,
            forcedSinglePages = setOf(1, 4),
            matchedPairs = setOf(2 to 3),
        )

        assertEquals(setOf(4), adjustedForcedSinglePages(state))
    }

    @Test
    fun `shared pairing keeps cover edge matching adjust and landscape parity enhancements`() {
        assertEquals(
            listOf(listOf(0), listOf(1, 2), listOf(3)),
            dualGroups(pageCount = 4),
        )
        assertEquals(
            listOf(listOf(0), listOf(1), listOf(2, 3), listOf(4, 5)),
            dualGroups(pageCount = 6, matchedPairs = setOf(2 to 3)),
        )
        assertEquals(
            listOf(listOf(0), listOf(1, 2), listOf(3), listOf(4), listOf(5, 6)),
            dualGroups(pageCount = 7, spreadPages = setOf(3)),
        )
        assertEquals(listOf(1), dualGroups(pageCount = 4, forcedSinglePages = setOf(1))[1])
    }

    @Test
    fun `edge matching adapter only invokes production matcher for enabled dual page reading`() = runBlocking {
        var invocations = 0
        val matcher: suspend (Int, (Int) -> androidx.compose.ui.graphics.ImageBitmap?) -> Set<Pair<Int, Int>> = { _, _ ->
            invocations++
            setOf(2 to 3)
        }
        val cachedPage: (Int) -> androidx.compose.ui.graphics.ImageBitmap? = { null }

        assertEquals(
            setOf(2 to 3),
            resolveDesktopMatchedPairs(true, true, pageCount = 4, pageAt = cachedPage, findMatchedPairs = matcher),
        )
        assertEquals(
            emptySet<Pair<Int, Int>>(),
            resolveDesktopMatchedPairs(false, true, pageCount = 2, pageAt = cachedPage, findMatchedPairs = matcher),
        )
        assertEquals(
            emptySet<Pair<Int, Int>>(),
            resolveDesktopMatchedPairs(true, false, pageCount = 2, pageAt = cachedPage, findMatchedPairs = matcher),
        )
        assertEquals(1, invocations)
    }

    @Test
    fun `webtoon auto scroll adapter distinguishes disabled scrolling and chapter boundary`() {
        assertEquals(
            WebtoonAutoScrollAction.Idle,
            webtoonAutoScrollAction(false, lastVisibleIndex = 2, totalItemsCount = 3, lastVisibleBottom = 100, viewportEnd = 100),
        )
        assertEquals(
            WebtoonAutoScrollAction.Scroll,
            webtoonAutoScrollAction(true, lastVisibleIndex = 1, totalItemsCount = 3, lastVisibleBottom = 100, viewportEnd = 100),
        )
        assertEquals(
            WebtoonAutoScrollAction.NextChapter,
            webtoonAutoScrollAction(true, lastVisibleIndex = 2, totalItemsCount = 3, lastVisibleBottom = 100, viewportEnd = 100),
        )
    }

    @Test
    fun `delegated page navigation keeps transform pan and double tap gestures enabled`() {
        val delegated = zoomableGestureCapabilities(handlesTapNavigation = false, hasNavigationCallbacks = false)
        assertTrue(delegated.transformEnabled)
        assertTrue(delegated.doubleTapResetEnabled)
        assertFalse(delegated.tapNavigationEnabled)

        val independent = zoomableGestureCapabilities(handlesTapNavigation = true, hasNavigationCallbacks = true)
        assertTrue(independent.transformEnabled)
        assertTrue(independent.doubleTapResetEnabled)
        assertTrue(independent.tapNavigationEnabled)
    }

    @Test
    fun `virtual pages and keyboard use the same shared direction contract`() {
        val rtlPages = buildVirtualPageList(2, setOf(0), isRtl = true)

        assertEquals(0, rtlPages[0].realIndex)
        assertEquals(PageSplitHalf.RIGHT, rtlPages[0].splitHalf)
        assertEquals(ReaderPageAction.GoToPage(2), ReaderKeyboardAction.forLeft(true, 1, 3))
        assertEquals(ReaderPageAction.GoToPage(0), ReaderKeyboardAction.forRight(true, 1, 3))
    }

    @Test
    fun `grayscale and invert remain effective and persistable reader settings`() {
        val filter = ReaderColorFilter(grayscaleEnabled = true, invertEnabled = true)

        assertTrue(filter.isEffective)
        assertTrue(filter.grayscaleEnabled)
        assertTrue(filter.invertEnabled)
        assertTrue(readerSource("ReaderSettingsPanel.kt").contains("pref_grayscale"))
        assertTrue(readerSource("ReaderSettingsPanel.kt").contains("desktop_ui_invert_colors"))
    }

    @Test
    fun `reader product wiring retains entry retry transitions auto scroll and context menu`() {
        val detail = source("app-desktop/src/main/kotlin/mihon/desktop/ui/library/MangaDetailScreen.kt")
        val reader = readerSource("DesktopReaderScreen.kt")
        val readerVisuals = readerSource("ReaderVisualComponents.kt")
        val webtoon = readerSource("WebtoonViewer.kt")
        val settings = readerSource("ReaderSettingsPanel.kt")
        val dual = readerSource("DualPagePagerViewer.kt")
        val page = readerSource("ZoomablePageBox.kt")
        val pageContextMenuSource = readerSource("PageContextMenu.kt")

        assertTrue(detail.contains("DesktopReaderScreen("), "Manga detail must remain the reader entry point")
        assertTrue(reader.contains("onRetry"), "Loading errors must expose retry")
        assertTrue(reader.contains("ChapterTransitionFeedback"), "Chapter boundary and missing chapter feedback must be visible")
        assertTrue(reader.contains("model.activateChapter("), "Chapter navigation must activate the target in the same session")
        assertFalse(reader.contains("navigator.replace("), "Chapter navigation must not replace the reader Screen")
        assertFalse(readerVisuals.contains("showContinue"), "Loaded chapter transitions must not expose Continue")
        assertTrue(reader.contains("state.session.activeChapter"), "The reader must render the canonical session snapshot")
        assertTrue(reader.contains("state.session.activeChapter.pages.map { it.encodedPageRef }"))
        assertTrue(reader.contains("ReadingMode.WEBTOON -> WebtoonPresentationViewer("))
        assertTrue(readerVisuals.contains(".require(ReaderPresentationMode.WEBTOON)"))
        assertTrue(readerVisuals.contains(".require(ReaderPresentationMode.DUAL_PAGED)"))
        assertTrue(dual.contains("key = { pagerIndex -> displayUnits[pagerToUnit(pagerIndex)].id }"))
        assertTrue(webtoon.contains("key = DisplayUnit::id"), "Webtoon Lazy items must keep stable display identities")
        assertTrue(settings.contains("if (currentMode == ReadingMode.WEBTOON)"))
        assertTrue(settings.contains("desktop_ui_split_wide_pages"), "Webtoon must expose its wide-page split option")
        val edgeObserverCall = callBlock(reader, "observeDesktopMatchedPairs(")
        assertTrue(edgeObserverCall.contains("autoSpreadMatching = state.autoSpreadMatching"))
        assertTrue(edgeObserverCall.contains("dualPageMode = state.dualPageMode"))
        assertTrue(edgeObserverCall.contains("pageCount = state.session.activeChapter.pages.size"))
        assertTrue(edgeObserverCall.contains("retainedMatchedPairs = state.matchedPairs"))
        assertTrue(edgeObserverCall.contains("onMatchedPairsChanged = model::setMatchedPairs"))
        assertEquals(
            1,
            occurrenceCount(reader, "internal suspend fun observeDesktopMatchedPairs("),
            "The cache-revision observer must have one authoritative declaration",
        )
        val edgeObserverStart = reader.indexOf("internal suspend fun observeDesktopMatchedPairs(")
        val edgeObserverEnd = reader.indexOf("internal suspend fun resolveDesktopMatchedPairs(")
        val edgeObserverDeclaration = reader.substring(edgeObserverStart, edgeObserverEnd)
        assertTrue(edgeObserverDeclaration.contains("preloader.cacheRevision.collect"))
        assertTrue(edgeObserverDeclaration.contains("retained + resolveDesktopMatchedPairs("))
        assertTrue(edgeObserverDeclaration.contains("pageAt = preloader::get"))
        assertEquals(
            1,
            occurrenceCount(reader, "internal suspend fun resolveDesktopMatchedPairs("),
            "The production matcher adapter must have one authoritative declaration",
        )
        val matcherDeclaration = callBlock(reader, "internal suspend fun resolveDesktopMatchedPairs(")
        assertTrue(
            matcherDeclaration.contains(
                "findMatchedPairs: suspend (Int, (Int) -> androidx.compose.ui.graphics.ImageBitmap?) -> Set<Pair<Int, Int>>",
            ),
            "The production matcher must consume caller-owned bounded decoded images",
        )
        assertTrue(matcherDeclaration.contains("EdgePixelMatcher().findMatchedPairs(count, provider)"))

        val autoScrollEffect = bracedBlock(webtoon, "LaunchedEffect(autoScroll, autoScrollSpeed, autoScrollLoopEnabled)")
        assertTrue(autoScrollEffect.contains("autoScrollGate.action("))
        assertTrue(autoScrollEffect.contains("enabled = autoScroll"))
        assertTrue(autoScrollEffect.contains("if (!autoScrollLoopEnabled)"))
        assertTrue(autoScrollEffect.contains("WebtoonAutoScrollAction.Scroll -> listState.scroll { scrollBy(pixelsPerTick) }"))
        assertTrue(autoScrollEffect.contains("WebtoonAutoScrollAction.NextChapter -> onNextChapter?.invoke()"))
        val autoScrollGate = bracedBlock(webtoon, "internal class WebtoonAutoScrollGate")
        assertTrue(autoScrollGate.contains("webtoonAutoScrollAction("))
        val autoScrollPause = bracedBlock(webtoon, "internal class WebtoonAutoScrollPauseState")
        assertTrue(autoScrollPause.contains("isUserDragging"))
        assertTrue(autoScrollPause.contains("isScrollInProgress"))

        val webtoonContextMenu = callBlock(webtoon, "        PageContextMenu(")
        assertTrue(webtoonContextMenu.contains("pageUrl = url"), "Webtoon right-click must receive the visible URL")
        val pageContextMenu = callBlock(page, "        PageContextMenu(")
        assertTrue(pageContextMenu.contains("splitHalf = splitHalf"), "Right-click must receive the visible split half")
        assertTrue(pageContextMenu.contains("sourceBounds = sourceBounds"), "Right-click must receive virtual-page source bounds")
        val dualPageBoxes = callBlocks(dual, "ZoomablePageBox(")
        assertTrue(dualPageBoxes.isNotEmpty())
        assertTrue(
            dualPageBoxes.all { it.contains("contextMenuScope = contextMenuScope") },
            "Every dual-page image must retain its context-menu scope",
        )
        val visibleImageLoads = callBlocks(pageContextMenuSource, "val img = loadPageContextMenuImage(")
        assertEquals(2, visibleImageLoads.size, "Save and copy must both load the visible image region")
        assertTrue(
            visibleImageLoads.all { it.contains("pageUrl, splitHalf, sourceBounds") },
            "Save and copy must both forward the exact split/sourceBounds parameters",
        )
    }

    @Test
    fun `production keyboard and mouse wiring uses shared logical navigation adapters`() {
        val reader = readerSource("DesktopReaderScreen.kt")
        val single = readerSource("SinglePagePagerViewer.kt")
        val dual = readerSource("DualPagePagerViewer.kt")
        val page = readerSource("ZoomablePageBox.kt")

        val keyboard = bracedBlock(reader, "val action = when (event.key)")
        assertTrue(keyboard.contains("Key.DirectionLeft, Key.A -> ReaderKeyboardAction.forLeft(isRtl, navCurrent, totalPages)"))
        assertTrue(keyboard.contains("Key.DirectionRight, Key.D, Key.Spacebar -> ReaderKeyboardAction.forRight(isRtl, navCurrent, totalPages)"))

        val singlePage = callBlock(single, "            ZoomablePageBox(")
        assertTrue(singlePage.contains("isRtl = isRtl"))
        assertTrue(singlePage.contains("onTapPrevious = { executeTapCommand(ReaderNavigationCommand.Previous) }"))
        assertTrue(singlePage.contains("onTapNext = { executeTapCommand(ReaderNavigationCommand.Next) }"))
        assertTrue(single.contains("ReaderKeyboardAction.forPagerCommand(command, isRtl, pagerState.currentPage, displayUnits.size)"))
        assertTrue(single.contains("settledPagerIndex = { pagerState.settledPage }"))

        val dualPageBoxes = callBlocks(dual, "ZoomablePageBox(")
        assertEquals(1, dualPageBoxes.size, "All physical slots must share the same renderer path")
        val delegatedPageBox = dualPageBoxes.single()
        assertTrue(delegatedPageBox.contains("handlesTapNavigation = false"))
        assertTrue(delegatedPageBox.contains("isRtl = isRtl"))
        assertTrue(delegatedPageBox.contains("splitHalf = slot.splitHalf"))
        assertTrue(delegatedPageBox.contains("sourceBounds = slot.sourceBounds"))
        assertEquals(1, Regex("readerPrimaryTapInput\\(zoomState\\.scale, navigationMode, isRtl\\)").findAll(dual).count())
        assertTrue(dual.contains("ReaderKeyboardAction.forPagerCommand(command, isRtl, pagerState.currentPage, displayUnits.size)"))
        assertTrue(dual.contains("settledPagerIndex = { pagerState.settledPage }"))
        assertTrue(dual.contains("pointerInput(zoomScale, navigationMode, isRtl)"))
        assertTrue(page.contains("Modifier.pointerInput(navigationMode, isRtl, gestureCapabilities.tapNavigationEnabled)"))
        assertTrue(page.contains("val gestureCapabilities = zoomableGestureCapabilities("))
        assertTrue(page.contains("if (gestureCapabilities.transformEnabled)"))
        assertTrue(page.contains("gestureCapabilities.tapNavigationEnabled && isTap && !moved"))
        assertTrue(page.contains("if (gestureCapabilities.doubleTapResetEnabled)"))
        assertTrue(page.contains("tapNavRegion(tapX, tapY, tapWidth, tapHeight, navigationMode, isRtl)"))
        assertTrue(page.contains("TapNavRegion.PREV -> onTapPrevious?.invoke()"))
        assertTrue(page.contains("TapNavRegion.NEXT -> onTapNext?.invoke()"))
    }

    @Test
    fun `right click image actions use the visible virtual half instead of the full source page`() {
        val source = BufferedImage(5, 2, BufferedImage.TYPE_INT_ARGB).apply {
            for (y in 0 until height) {
                for (x in 0 until width) {
                    setRGB(x, y, if (x < 2) Color.RED.rgb else Color.BLUE.rgb)
                }
            }
        }
        val file = File.createTempFile("mihon-visible-half-", ".png").apply { deleteOnExit() }
        ImageIO.write(source, "png", file)

        val visible = loadPageContextMenuImage(file.toURI().toString(), splitHalf = PageSplitHalf.RIGHT)

        requireNotNull(visible)
        assertEquals(3, visible.width)
        assertEquals(2, visible.height)
        assertTrue((0 until visible.width).all { x -> visible.getRGB(x, 0) == Color.BLUE.rgb })
    }

    @Test
    fun `right click image actions crop exact source bounds before split hints`() {
        withContextMenuImage(width = 5, height = 3) { file ->
            val visible =
                loadPageContextMenuImage(
                    pageUrl = file.toURI().toString(),
                    splitHalf = PageSplitHalf.RIGHT,
                    sourceBounds = PixelBounds(x = 1, y = 1, width = 3, height = 2),
                )

            requireNotNull(visible)
            assertEquals(3, visible.width)
            assertEquals(2, visible.height)
            assertEquals(Color(1, 1, 0).rgb, visible.getRGB(0, 0))
            assertEquals(Color(3, 2, 0).rgb, visible.getRGB(2, 1))
        }
    }

    @Test
    fun `right click image actions keep the full page without visible bounds`() {
        withContextMenuImage(width = 5, height = 3) { file ->
            val visible = loadPageContextMenuImage(file.toURI().toString())

            requireNotNull(visible)
            assertEquals(5, visible.width)
            assertEquals(3, visible.height)
            assertEquals(Color(4, 2, 0).rgb, visible.getRGB(4, 2))
        }
    }

    @Test
    fun `right click image actions reject ordinary and overflowing invalid bounds`() {
        withContextMenuImage(width = 5, height = 3) { file ->
            assertNull(
                loadPageContextMenuImage(
                    file.toURI().toString(),
                    sourceBounds = PixelBounds(x = -1, y = 0, width = 1, height = 1),
                ),
            )
            assertNull(
                loadPageContextMenuImage(
                    file.toURI().toString(),
                    sourceBounds = PixelBounds(x = Int.MAX_VALUE, y = 0, width = 2, height = 1),
                ),
            )
            assertNull(
                loadPageContextMenuImage(
                    file.toURI().toString(),
                    sourceBounds = PixelBounds(x = 0, y = Int.MAX_VALUE, width = 1, height = 2),
                ),
            )
        }
    }

    @Test
    fun `all production viewers observe the bounded preload cache and stop duplicate full image loading`() {
        val reader = readerSource("DesktopReaderScreen.kt")
        val single = readerSource("SinglePagePagerViewer.kt")
        val dual = readerSource("DualPagePagerViewer.kt")
        val webtoon = readerSource("WebtoonViewer.kt")
        val page = readerSource("ZoomablePageBox.kt")

        assertTrue(reader.contains("preloader = preloader"), "Reader dispatcher must forward the production preloader")
        assertTrue(single.contains("preloader = preloader"), "Single-page viewer must forward the production preloader")
        assertTrue(dual.contains("preloader: PagePreloader?"), "Dual-page viewer must accept the production preloader")
        assertTrue(dual.contains("preloader = preloader"), "Every dual-page image path must consume the preload cache")
        assertTrue(webtoon.contains("preloader: PagePreloader?"), "Webtoon viewer must accept the production preloader")
        assertTrue(webtoon.contains("preloader = preloader"), "Webtoon items must consume the preload cache")
        assertTrue(page.contains("cacheRevision.collectAsState"), "Late preload writes must recompose visible pages")
        assertTrue(
            page.contains("rememberAsyncImagePainter(readerPagePainterModel(url, preloadedBitmap, sourceId))"),
            "A bounded/tiled preload hit must stop the ordinary full-image Coil request",
        )
    }

    @Test
    fun `platform reader code consumes common reader contracts instead of duplicate algorithms`() {
        val dualPresentation = readerSource("presentation/DualPagedPresentation.kt")
        val virtualPages = source("app-desktop/src/main/kotlin/mihon/desktop/reader/VirtualPageList.kt")
        val tapZone = readerSource("TapZone.kt")

        assertTrue(dualPresentation.contains("ReaderPagePairing.build("))
        assertFalse(dualPresentation.contains("private fun buildGroups"))
        assertTrue(virtualPages.contains("mihon.domain.reader.buildVirtualReaderPages"))
        assertTrue(tapZone.contains("mihon.domain.reader.ReaderNavigation"))
    }

    private fun dualGroups(
        pageCount: Int,
        spreadPages: Set<Int> = emptySet(),
        forcedSinglePages: Set<Int> = emptySet(),
        matchedPairs: Set<Pair<Int, Int>> = emptySet(),
    ): List<List<Int>> {
        val request = desktopReaderPresentationRequest(
            chapter = readerChapterSession(pageCount = pageCount),
            direction = ReaderDirection.LTR,
            spreadPageIndices = spreadPages,
            forcedSinglePageIndices = forcedSinglePages,
            matchedPagePairs = matchedPairs,
            splitWidePages = false,
        )
        return DesktopReaderPresentationRegistry
            .require(ReaderPresentationMode.DUAL_PAGED)
            .present(request)
            .displayUnits
            .map { unit -> unit.slots.mapNotNull { it.page?.id?.sourcePageIndex }.distinct().sorted() }
    }

    @Test
    fun `desktop reader settings and production navigator wire all upstream chapter skip modes`() {
        val screen = readerSource("DesktopReaderScreen.kt")
        val settings = readerSource("ReaderSettingsPanel.kt")
        val detail = source("app-desktop/src/main/kotlin/mihon/desktop/ui/library/MangaDetailScreen.kt")
        val chapterRefs = source("app-desktop/src/main/kotlin/mihon/desktop/ui/library/ReaderChapterRefs.kt")

        assertTrue(screen.contains("skipFilteredChapters = state.skipFilteredChapters"))
        assertTrue(screen.contains("skipDuplicateChapters = state.skipDuplicateChapters"))
        assertTrue(settings.contains("pref_skip_filtered_chapters"))
        assertTrue(settings.contains("pref_skip_dupe_chapters"))
        assertFalse(detail.contains("visibleChapterIds = displayedChapters"))
        assertTrue(chapterRefs.contains("isReaderChapterFiltered("))
        assertTrue(chapterRefs.contains("unreadFilterRaw = manga.unreadFilterRaw"))
    }

    private fun readerSource(name: String): String =
        source("app-desktop/src/main/kotlin/mihon/desktop/ui/reader/$name")

    private fun withContextMenuImage(
        width: Int,
        height: Int,
        block: (File) -> Unit,
    ) {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).apply {
            for (y in 0 until height) {
                for (x in 0 until width) {
                    setRGB(x, y, Color(x, y, 0).rgb)
                }
            }
        }
        val file = File.createTempFile("mihon-context-menu-", ".png")
        try {
            ImageIO.write(image, "png", file)
            block(file)
        } finally {
            file.delete()
        }
    }

    private fun source(path: String): String {
        val cwd = File(System.getProperty("user.dir"))
        val root = if (File(cwd, "app-desktop").exists()) cwd else cwd.parentFile
        return File(root, path).readText()
    }

    private fun callBlock(source: String, marker: String): String {
        val start = source.indexOf(marker)
        require(start >= 0) { "Missing production call: $marker" }
        val open = source.indexOf('(', start)
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return source.substring(start, index + 1)
                }
            }
        }
        error("Unclosed production call: $marker")
    }

    private fun callBlocks(source: String, marker: String): List<String> {
        val blocks = mutableListOf<String>()
        var offset = 0
        while (true) {
            val relative = source.substring(offset).indexOf(marker)
            if (relative < 0) return blocks
            val start = offset + relative
            blocks += callBlock(source.substring(start), marker)
            offset = start + marker.length
        }
    }

    private fun bracedBlock(source: String, marker: String): String {
        val start = source.indexOf(marker)
        require(start >= 0) { "Missing production block: $marker" }
        val open = source.indexOf('{', start)
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(start, index + 1)
                }
            }
        }
        error("Unclosed production block: $marker")
    }

    private fun trailingLambdaCallBlocks(source: String, marker: String): List<String> {
        val blocks = mutableListOf<String>()
        var offset = 0
        while (true) {
            val relative = source.substring(offset).indexOf(marker)
            if (relative < 0) return blocks
            val start = offset + relative
            val call = callBlock(source.substring(start), marker)
            val lambdaOpen = source.indexOf('{', start + call.length)
            require(lambdaOpen >= 0) { "Missing trailing lambda for production call: $marker" }
            var depth = 0
            for (index in lambdaOpen until source.length) {
                when (source[index]) {
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            blocks += source.substring(start, index + 1)
                            offset = index + 1
                            break
                        }
                    }
                }
            }
            require(offset > start) { "Unclosed trailing lambda for production call: $marker" }
        }
    }

    private fun occurrenceCount(source: String, marker: String): Int =
        Regex(Regex.escape(marker)).findAll(source).count()
}
