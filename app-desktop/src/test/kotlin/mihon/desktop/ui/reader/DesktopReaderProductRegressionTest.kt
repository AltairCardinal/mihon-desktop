package mihon.desktop.ui.reader

import mihon.desktop.reader.DualPageState
import mihon.desktop.reader.ReaderColorFilter
import mihon.desktop.reader.ReaderKeyboardAction
import mihon.desktop.reader.ReaderPageAction
import mihon.desktop.reader.buildVirtualPageList
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
    fun `shared pairing keeps cover edge matching adjust and landscape parity enhancements`() {
        val cover = DualPageState(totalPages = 4)
        assertEquals(listOf(listOf(0), listOf(1, 2), listOf(3)), List(cover.groupCount, cover::getGroup))

        val edgeMatched = DualPageState(totalPages = 6, matchedPairs = setOf(2 to 3))
        assertEquals(
            listOf(listOf(0), listOf(1), listOf(2, 3), listOf(4, 5)),
            List(edgeMatched.groupCount, edgeMatched::getGroup),
        )

        val parity = DualPageState(totalPages = 7, spreadPages = setOf(3))
        assertEquals(
            listOf(listOf(0), listOf(1, 2), listOf(3), listOf(4), listOf(5, 6)),
            List(parity.groupCount, parity::getGroup),
        )
        val adjusted = DualPageState(4, forcedSinglePages = setOf(1))
        assertEquals(listOf(1), adjusted.getGroup(1))
    }

    @Test
    fun `edge matching adapter only invokes production matcher for enabled dual page reading`() = runBlocking {
        var invocations = 0
        val matcher: suspend (List<String>) -> Set<Pair<Int, Int>> = {
            invocations++
            setOf(2 to 3)
        }

        assertEquals(
            setOf(2 to 3),
            resolveDesktopMatchedPairs(true, true, listOf("cover", "one", "two", "three"), matcher),
        )
        assertEquals(emptySet<Pair<Int, Int>>(), resolveDesktopMatchedPairs(false, true, listOf("a", "b"), matcher))
        assertEquals(emptySet<Pair<Int, Int>>(), resolveDesktopMatchedPairs(true, false, listOf("a", "b"), matcher))
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
        assertTrue(readerSource("ReaderSettingsPanel.kt").contains("Grayscale"))
        assertTrue(readerSource("ReaderSettingsPanel.kt").contains("Invert colours"))
        assertTrue(readerSource("DesktopReaderScreen.kt").contains("readerColorTransform"))
    }

    @Test
    fun `reader product wiring retains entry retry transitions auto scroll and context menu`() {
        val detail = source("app-desktop/src/main/kotlin/mihon/desktop/ui/library/MangaDetailScreen.kt")
        val reader = readerSource("DesktopReaderScreen.kt")
        val webtoon = readerSource("WebtoonViewer.kt")
        val dual = readerSource("DualPagePagerViewer.kt")
        val page = readerSource("ZoomablePageBox.kt")
        val pageContextMenuSource = readerSource("PageContextMenu.kt")

        assertTrue(detail.contains("DesktopReaderScreen("), "Manga detail must remain the reader entry point")
        assertTrue(reader.contains("onRetry"), "Loading errors must expose retry")
        assertTrue(reader.contains("ChapterTransitionFeedback"), "Chapter boundary and missing chapter feedback must be visible")
        assertTrue(reader.contains("Button(onClick = onContinue"), "A chapter transition must remain visible until the user continues")
        assertTrue(reader.contains("loadGeneration = state.loadGeneration"), "Retry must restart the production loading effect")
        val edgeMatchingCall = callBlock(reader, "model.setMatchedPairs(")
        assertTrue(edgeMatchingCall.contains("resolveDesktopMatchedPairs("))
        assertTrue(edgeMatchingCall.contains("autoSpreadMatching = state.autoSpreadMatching"))
        assertTrue(edgeMatchingCall.contains("dualPageMode = state.dualPageMode"))
        assertTrue(edgeMatchingCall.contains("pageUrls = state.resolvedUrls"))

        val autoScrollEffect = bracedBlock(webtoon, "LaunchedEffect(autoScroll, autoScrollSpeed)")
        assertTrue(autoScrollEffect.contains("webtoonAutoScrollAction("))
        assertTrue(autoScrollEffect.contains("enabled = autoScroll"))
        assertTrue(autoScrollEffect.contains("WebtoonAutoScrollAction.Scroll -> listState.scroll { scrollBy(pixelsPerTick) }"))
        assertTrue(autoScrollEffect.contains("WebtoonAutoScrollAction.NextChapter -> onNextChapter?.invoke()"))

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

        val singlePage = callBlock(single, "        ZoomablePageBox(")
        assertTrue(singlePage.contains("isRtl = isRtl"))
        assertTrue(singlePage.contains("onTapPrevious = onTapPrevious"))
        assertTrue(singlePage.contains("onTapNext = onTapNext"))
        assertTrue(single.contains("ReaderKeyboardAction.forPagerCommand(command, isRtl, pagerState.currentPage, effectivePageCount)"))

        assertEquals(2, Regex("readerPrimaryTapInput\\(zoomState\\.scale, navigationMode, isRtl\\)").findAll(dual).count())
        assertTrue(dual.contains("ReaderKeyboardAction.forPagerCommand(command, isRtl, pagerState.currentPage, dualState.groupCount)"))
        assertTrue(dual.contains("pointerInput(zoomScale, navigationMode, isRtl)"))
        assertTrue(page.contains("Modifier.pointerInput(navigationMode, isRtl)"))
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
            page.contains("rememberAsyncImagePainter(readerPagePainterModel(url, preloadedBitmap))"),
            "A bounded/tiled preload hit must stop the ordinary full-image Coil request",
        )
    }

    @Test
    fun `platform reader code consumes common reader contracts instead of duplicate algorithms`() {
        val dualState = source("app-desktop/src/main/kotlin/mihon/desktop/reader/DualPageState.kt")
        val virtualPages = source("app-desktop/src/main/kotlin/mihon/desktop/reader/VirtualPageList.kt")
        val tapZone = readerSource("TapZone.kt")

        assertTrue(dualState.contains("mihon.domain.reader.ReaderPairingState"))
        assertFalse(dualState.contains("private fun buildGroups"))
        assertTrue(virtualPages.contains("mihon.domain.reader.buildVirtualReaderPages"))
        assertTrue(tapZone.contains("mihon.domain.reader.ReaderNavigation"))
    }

    @Test
    fun `desktop reader settings and production navigator wire all upstream chapter skip modes`() {
        val screen = readerSource("DesktopReaderScreen.kt")
        val settings = readerSource("ReaderSettingsPanel.kt")
        val detail = source("app-desktop/src/main/kotlin/mihon/desktop/ui/library/MangaDetailScreen.kt")

        assertTrue(screen.contains("skipFilteredChapters = state.skipFilteredChapters"))
        assertTrue(screen.contains("skipDuplicateChapters = state.skipDuplicateChapters"))
        assertTrue(settings.contains("Skip filtered chapters"))
        assertTrue(settings.contains("Skip duplicate chapters"))
        assertTrue(detail.contains("visibleChapterIds = displayedChapters"))
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
}
