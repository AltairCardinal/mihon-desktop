package mihon.desktop.ui.reader

import mihon.desktop.reader.DualPageState
import mihon.desktop.reader.ReaderColorFilter
import mihon.desktop.reader.ReaderKeyboardAction
import mihon.desktop.reader.ReaderPageAction
import mihon.desktop.reader.buildVirtualPageList
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

class DesktopReaderProductRegressionTest {

    @Test
    fun `shared pairing keeps cover adjust edge matching and landscape parity enhancements`() {
        val state = DualPageState(
            totalPages = 7,
            spreadPages = setOf(3),
            forcedSinglePages = emptySet(),
            matchedPairs = setOf(1 to 2),
        )

        assertEquals(
            listOf(listOf(0), listOf(1, 2), listOf(3), listOf(4), listOf(5, 6)),
            List(state.groupCount, state::getGroup),
        )
        val adjusted = DualPageState(4, forcedSinglePages = setOf(1))
        assertEquals(listOf(1), adjusted.getGroup(1))
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

        assertTrue(detail.contains("DesktopReaderScreen("), "Manga detail must remain the reader entry point")
        assertTrue(reader.contains("onRetry"), "Loading errors must expose retry")
        assertTrue(reader.contains("ChapterTransitionFeedback"), "Chapter boundary and missing chapter feedback must be visible")
        assertTrue(reader.contains("Button(onClick = onContinue"), "A chapter transition must remain visible until the user continues")
        assertTrue(reader.contains("loadGeneration = state.loadGeneration"), "Retry must restart the production loading effect")
        assertTrue(webtoon.contains("autoScroll"), "Webtoon auto-scroll must be retained")
        assertTrue(webtoon.contains("PageContextMenu("), "Webtoon right-click save must be retained")
        assertTrue(dual.contains("contextMenuScope = contextMenuScope"), "Dual-page right-click save must be retained")
        assertTrue(page.contains("splitHalf = splitHalf"), "Right-click actions must receive the visible split half")
        assertTrue(page.contains("sourceBounds = sourceBounds"), "Right-click actions must receive virtual-page source bounds")
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

    private fun source(path: String): String {
        val cwd = File(System.getProperty("user.dir"))
        val root = if (File(cwd, "app-desktop").exists()) cwd else cwd.parentFile
        return File(root, path).readText()
    }
}
