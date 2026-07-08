package mihon.desktop.ui.reader

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import mihon.desktop.reader.ReaderBackgroundTheme
import mihon.desktop.reader.ReaderColorFilter
import mihon.desktop.reader.ReaderPreferences
import mihon.desktop.reader.ReadingMode
import mihon.desktop.reader.ScaleType
import mihon.desktop.reader.WebtoonSidePadding
import mihon.desktop.reader.ZoomState
import mihon.desktop.reader.viewerFlagsWithDualPage
import mihon.desktop.reader.viewerFlagsWithReadingMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Stage 25.0 — ReaderScreenModel tests.
 *
 * Verifies that all reader state lives in a Voyager ScreenModel with a
 * StateFlow<ReaderState> and mutation methods, enabling JVM unit tests
 * for all state transitions without Compose or DI.
 */
class ReaderScreenModelTest {

    // ── Construction ────────────────────────────────────────────────────────

    @Test
    fun `state flow exists and is accessible`() {
        val model = ReaderScreenModel()
        val flow: StateFlow<ReaderState> = model.state
        assertNotNull(flow)
        assertNotNull(flow.value)
    }

    @Test
    fun `initial state reflects pageUrls param`() {
        val model = ReaderScreenModel(
            pageUrls = listOf("url1", "url2", "url3"),
            initialPage = 1,
        )
        val state = model.state.value
        assertEquals(listOf("url1", "url2", "url3"), state.resolvedUrls)
        assertEquals(1, state.currentPage)
        assertFalse(state.isLoadingPages)
    }

    @Test
    fun `initial state marks loading when no urls and sourceId provided`() {
        val model = ReaderScreenModel(
            pageUrls = emptyList(),
            sourceId = 42L,
            chapterUrl = "/chapter/1",
        )
        assertTrue(model.state.value.isLoadingPages)
    }

    @Test
    fun `reader defaults to dual page mode`() {
        val prefs = ReaderPreferences().apply { clearDualPageForTests() }
        val model = ReaderScreenModel(prefs = prefs)

        assertTrue(model.state.value.dualPageMode)
    }

    @Test
    fun `reader defaults to dual page mode even when old global preference was single page`() {
        val prefs = ReaderPreferences().apply { isDualPage = false }
        val model = ReaderScreenModel(prefs = prefs)

        assertTrue(model.state.value.dualPageMode)
    }

    @Test
    fun `manga viewer flags override global dual page mode`() {
        val prefs = ReaderPreferences().apply { isDualPage = true }
        val model = ReaderScreenModel(
            mangaViewerFlags = viewerFlagsWithDualPage(0L, enabled = false),
            prefs = prefs,
        )

        assertFalse(model.state.value.dualPageMode)
    }

    @Test
    fun `webtoon flag forces webtoon reading mode regardless of prefs`() {
        val model = ReaderScreenModel(isWebtoon = true)
        assertEquals(ReadingMode.WEBTOON, model.state.value.readingMode)
    }

    @Test
    fun `non-webtoon chapter uses prefs reading mode`() {
        val prefs = ReaderPreferences()
        val model = ReaderScreenModel(isWebtoon = false, mangaViewerFlags = 0L, prefs = prefs)
        // Mode should come from prefs (whatever it is), not be forced to WEBTOON
        assertEquals(prefs.readingMode, model.state.value.readingMode)
    }

    @Test
    fun `non-webtoon manga viewer flags keep RTL dual page reader mode`() {
        val flags = viewerFlagsWithDualPage(
            viewerFlagsWithReadingMode(0L, ReadingMode.RTL),
            enabled = true,
        )

        val model = ReaderScreenModel(isWebtoon = false, mangaViewerFlags = flags)

        assertEquals(ReadingMode.RTL, model.state.value.readingMode)
        assertTrue(model.state.value.dualPageMode)
    }

    @Test
    fun `showSettings and showUI start as false`() {
        val model = ReaderScreenModel()
        assertFalse(model.state.value.showSettings)
        assertFalse(model.state.value.showUI)
    }

    @Test
    fun `errorMessage starts null`() {
        val model = ReaderScreenModel()
        assertNull(model.state.value.errorMessage)
    }

    // ── Page navigation ──────────────────────────────────────────────────────

    @Test
    fun `goToPage updates currentPage`() {
        val model = ReaderScreenModel(
            pageUrls = listOf("a", "b", "c"),
            initialPage = 0,
        )
        model.goToPage(2)
        assertEquals(2, model.state.value.currentPage)
    }

    @Test
    fun `goToPage clamps to zero on negative input`() {
        val model = ReaderScreenModel(pageUrls = listOf("a", "b", "c"))
        model.goToPage(-5)
        assertEquals(0, model.state.value.currentPage)
    }

    @Test
    fun `goToPage clamps to last page when exceeding url count`() {
        val model = ReaderScreenModel(pageUrls = listOf("a", "b", "c"))
        model.goToPage(100)
        assertEquals(2, model.state.value.currentPage)
    }

    @Test
    fun `goToPage on empty url list stays at 0`() {
        val model = ReaderScreenModel(pageUrls = emptyList())
        model.goToPage(3)
        assertEquals(0, model.state.value.currentPage)
    }

    // ── UI visibility ────────────────────────────────────────────────────────

    @Test
    fun `toggleSettings flips showSettings`() {
        val model = ReaderScreenModel()
        assertFalse(model.state.value.showSettings)
        model.toggleSettings()
        assertTrue(model.state.value.showSettings)
        model.toggleSettings()
        assertFalse(model.state.value.showSettings)
    }

    @Test
    fun `toggleUI flips showUI`() {
        val model = ReaderScreenModel()
        assertFalse(model.state.value.showUI)
        model.toggleUI()
        assertTrue(model.state.value.showUI)
        model.toggleUI()
        assertFalse(model.state.value.showUI)
    }

    // ── Reading mode ─────────────────────────────────────────────────────────

    @Test
    fun `setReadingMode changes readingMode for non-webtoon`() {
        val model = ReaderScreenModel(isWebtoon = false)
        model.setReadingMode(ReadingMode.RTL)
        assertEquals(ReadingMode.RTL, model.state.value.readingMode)
    }

    @Test
    fun `setReadingMode has no effect for webtoon chapters`() {
        val model = ReaderScreenModel(isWebtoon = true)
        assertEquals(ReadingMode.WEBTOON, model.state.value.readingMode)
        model.setReadingMode(ReadingMode.LTR)
        // Webtoon chapters are pinned to WEBTOON mode
        assertEquals(ReadingMode.WEBTOON, model.state.value.readingMode)
    }

    // ── Dual-page mode ────────────────────────────────────────────────────────

    @Test
    fun `setDualPageMode updates dualPageMode`() {
        val model = ReaderScreenModel(mangaViewerFlags = viewerFlagsWithDualPage(0L, enabled = false))
        assertFalse(model.state.value.dualPageMode)
        model.setDualPageMode(true)
        assertTrue(model.state.value.dualPageMode)
    }

    @Test
    fun `disabling dualPageMode clears forcedSinglePages`() {
        val model = ReaderScreenModel()
        model.setForcedSinglePages(setOf(0, 2))
        model.setDualPageMode(false)
        assertTrue(model.state.value.forcedSinglePages.isEmpty())
    }

    // ── Spread pages ──────────────────────────────────────────────────────────

    @Test
    fun `setSpreadPages updates spreadPages`() {
        val model = ReaderScreenModel(pageUrls = listOf("a", "b", "c", "d"))
        model.setSpreadPages(setOf(1, 3))
        assertEquals(setOf(1, 3), model.state.value.spreadPages)
    }

    @Test
    fun `setForcedSinglePages updates forcedSinglePages`() {
        val model = ReaderScreenModel()
        model.setForcedSinglePages(setOf(2, 4))
        assertEquals(setOf(2, 4), model.state.value.forcedSinglePages)
    }

    // ── Settings state ────────────────────────────────────────────────────────

    @Test
    fun `setBackgroundTheme updates backgroundTheme`() {
        val model = ReaderScreenModel()
        model.setBackgroundTheme(ReaderBackgroundTheme.BLACK)
        assertEquals(ReaderBackgroundTheme.BLACK, model.state.value.backgroundTheme)
    }

    @Test
    fun `setScaleType updates scaleType`() {
        val model = ReaderScreenModel()
        model.setScaleType(ScaleType.FIT_WIDTH)
        assertEquals(ScaleType.FIT_WIDTH, model.state.value.scaleType)
    }

    @Test
    fun `setColorFilter updates colorFilter`() {
        val model = ReaderScreenModel()
        val filter = ReaderColorFilter(enabled = true, brightness = 0.5f)
        model.setColorFilter(filter)
        assertEquals(filter, model.state.value.colorFilter)
    }

    @Test
    fun `setZoomState updates zoomState`() {
        val model = ReaderScreenModel()
        val zoom = ZoomState(scale = 2.0f)
        model.setZoomState(zoom)
        assertEquals(2.0f, model.state.value.zoomState.scale)
    }

    // ── Loaded pages ──────────────────────────────────────────────────────────

    @Test
    fun `setLoadedPages updates resolvedUrls and clears loading flag`() {
        val model = ReaderScreenModel(
            pageUrls = emptyList(),
            sourceId = 1L,
            chapterUrl = "/ch/1",
        )
        assertTrue(model.state.value.isLoadingPages)
        model.setLoadedPages(listOf("img1.jpg", "img2.jpg"), initialPage = 0)
        assertFalse(model.state.value.isLoadingPages)
        assertEquals(listOf("img1.jpg", "img2.jpg"), model.state.value.resolvedUrls)
        assertEquals(0, model.state.value.currentPage)
    }

    @Test
    fun `setLoadingPageSlots fixes total page count before out of order page downloads`() {
        val model = ReaderScreenModel(
            pageUrls = emptyList(),
            sourceId = 1L,
            chapterUrl = "/ch/1",
        )

        model.setLoadingPageSlots(totalPages = 44, initialPage = 0)
        model.appendLoadedPage(20, "img21.jpg")

        assertFalse(model.state.value.isLoadingPages)
        assertEquals(44, model.state.value.resolvedUrls.size)
        assertEquals("img21.jpg", model.state.value.resolvedUrls[20])
        assertEquals(0, model.state.value.currentPage)
        assertTrue(model.hasLoadedPage())
    }

    @Test
    fun `setLoadError sets errorMessage and clears loading flag`() {
        val model = ReaderScreenModel(
            pageUrls = emptyList(),
            sourceId = 1L,
            chapterUrl = "/ch/1",
        )
        model.setLoadError("Network timeout")
        assertFalse(model.state.value.isLoadingPages)
        assertEquals("Network timeout", model.state.value.errorMessage)
    }

    @Test
    fun `persistViewerFlags ignores missing manga id`() = runTest {
        val calls = mutableListOf<Pair<Long, Long>>()
        val model = ReaderScreenModel(persistViewerFlags = { mangaId, flags -> calls += mangaId to flags })

        model.persistViewerFlags(mangaId = 0L, flags = 7L)

        assertTrue(calls.isEmpty())
    }

    @Test
    fun `persistViewerFlags delegates nonzero manga id`() = runTest {
        val calls = mutableListOf<Pair<Long, Long>>()
        val model = ReaderScreenModel(persistViewerFlags = { mangaId, flags -> calls += mangaId to flags })

        model.persistViewerFlags(mangaId = 42L, flags = 7L)

        assertEquals(listOf(42L to 7L), calls)
    }

    // ── ReaderState data class sanity ─────────────────────────────────────────

    @Test
    fun `ReaderState has expected fields`() {
        val state = ReaderState(
            currentPage = 3,
            resolvedUrls = listOf("a", "b"),
            isLoadingPages = true,
            errorMessage = "err",
            readingMode = ReadingMode.RTL,
            dualPageMode = true,
            showSettings = true,
            showUI = true,
        )
        assertEquals(3, state.currentPage)
        assertEquals(listOf("a", "b"), state.resolvedUrls)
        assertTrue(state.isLoadingPages)
        assertEquals("err", state.errorMessage)
        assertEquals(ReadingMode.RTL, state.readingMode)
        assertTrue(state.dualPageMode)
        assertTrue(state.showSettings)
        assertTrue(state.showUI)
    }
}
