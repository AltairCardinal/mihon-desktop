package mihon.desktop.ui.reader

import mihon.desktop.reader.ReadingMode
import mihon.desktop.reader.readingModeFromViewerFlags
import mihon.desktop.reader.viewerFlagsWithReadingMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import mihon.desktop.reader.ReaderPreferences
import mihon.desktop.reader.desktopReaderSessionState
import mihon.desktop.ui.reader.presentation.DisplayUnitId
import mihon.desktop.ui.reader.presentation.DisplaySlotId
import mihon.desktop.ui.reader.presentation.ReaderPresentationMode
import mihon.desktop.ui.reader.presentation.VisiblePageSet
import tachiyomi.core.common.preference.DesktopPreferenceStore
import java.util.prefs.Preferences

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultReaderModeTest {
    private val root = Preferences.userRoot().node("/mihon/default-reader-test/${System.nanoTime()}")
    private fun prefs() = ReaderPreferences(DesktopPreferenceStore(root.node("current")), root.node("legacy"))

    @AfterEach
    fun cleanup() = root.removeNode()

    @Test
    fun `default mode persists separately from inherited mode with Android RTL fallback`() {
        val mode = ReadingMode.valueOf("DEFAULT")
        val flags = viewerFlagsWithReadingMode(0L, mode)
        assertEquals(mode, readingModeFromViewerFlags(flags))
        assertEquals(2L, flags and 0xFFL)
        assertEquals(null, readingModeFromViewerFlags(viewerFlagsWithReadingMode(flags, null)))
    }

    @Test
    fun `fresh default is adaptive while saved manual preference remains manual`() = runTest {
        assertEquals(ReadingMode.DEFAULT, prefs().readingMode)
        val auto = ReaderScreenModel(prefs = prefs())
        auto.updateViewportSize(1350, 1000, this)
        assertTrue(auto.state.value.dualPageMode)
        assertEquals(ReadingMode.RTL, auto.state.value.readingMode)
        prefs().readingMode = ReadingMode.LTR
        val manual = ReaderScreenModel(prefs = prefs())
        manual.updateViewportSize(1800, 1000, this)
        advanceTimeBy(200)
        assertFalse(manual.state.value.dualPageMode)
        assertEquals(ReadingMode.LTR, manual.state.value.readingMode)
    }

    @Test
    fun `existing dual page preference without a saved direction remains manual`() = runTest {
        DesktopPreferenceStore(root.node("current")).getBoolean("reader_dual_page", false).set(true)
        val preferences = prefs()
        assertEquals(ReadingMode.RTL, preferences.readingMode)
        val model = ReaderScreenModel(prefs = preferences)
        model.updateViewportSize(900, 1000, this)
        assertTrue(model.state.value.dualPageMode)
        assertFalse(model.state.value.automaticLayout)
    }

    @Test
    fun `legacy single layout is preserved but explicit adaptive preference wins`() {
        root.node("legacy").putBoolean("isDualPage", false)
        assertEquals(ReadingMode.RTL, prefs().readingMode)
        prefs().readingMode = ReadingMode.DEFAULT
        assertEquals(ReadingMode.DEFAULT, prefs().readingMode)
        assertTrue(ReaderScreenModel(prefs = prefs()).state.value.automaticLayout)
    }

    @Test
    fun `existing manga only dual marker remains manual until default explicitly selected`() {
        val flags = mihon.desktop.reader.viewerFlagsWithDualPage(0L, true)
        val model = ReaderScreenModel(prefs = prefs(), mangaViewerFlags = flags)
        assertFalse(model.state.value.automaticLayout)
        assertFalse(model.state.value.followsGlobalReadingMode)
        assertTrue(model.state.value.dualPageMode)
        model.setReadingMode(ReadingMode.DEFAULT)
        val reopened = ReaderScreenModel(prefs = prefs(), mangaViewerFlags = model.currentViewerFlags())
        assertTrue(reopened.state.value.automaticLayout)
    }

    @Test
    fun `resize applies hysteresis and stable target rather than restarting on each size event`() = runTest {
        val model = ReaderScreenModel(prefs = prefs())
        model.updateViewportSize(900, 1000, this)
        model.updateViewportSize(1350, 1000, this)
        runCurrent()
        advanceTimeBy(100)
        model.updateViewportSize(1500, 1000, this)
        advanceTimeBy(49)
        runCurrent()
        assertFalse(model.state.value.dualPageMode)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(model.state.value.dualPageMode)
        model.updateViewportSize(1300, 1000, this)
        advanceTimeBy(200)
        assertTrue(model.state.value.dualPageMode)
        model.updateViewportSize(1250, 1000, this)
        runCurrent()
        advanceTimeBy(150)
        runCurrent()
        assertFalse(model.state.value.dualPageMode)
        model.updateViewportSize(1600, 1000, this)
        runCurrent()
        advanceTimeBy(100)
        model.updateViewportSize(1000, 1000, this)
        advanceTimeBy(100)
        runCurrent()
        assertFalse(model.state.value.dualPageMode)
    }

    @Test
    fun `invalid size cancels pending switch and explicit mode cancels automatic work`() = runTest {
        val model = ReaderScreenModel(prefs = prefs())
        model.updateViewportSize(900, 1000, this)
        model.updateViewportSize(1600, 1000, this)
        model.updateViewportSize(0, 0, this)
        advanceTimeBy(200)
        assertFalse(model.state.value.dualPageMode)
        model.updateViewportSize(1600, 1000, this)
        model.setReadingMode(ReadingMode.LTR)
        advanceTimeBy(200)
        assertFalse(model.state.value.dualPageMode)
    }

    @Test
    fun `layout settles preserve anchor and do not mark companion read but next page still reports`() = runTest {
        val reader = desktopReaderSessionState(chapterId = 7L, pageCount = 6, initialPage = 2)
        var reports = 0
        val model = ReaderScreenModel(prefs = prefs(), initialSessionState = reader, onViewportSettled = { _, _ -> reports++ })
        val pages = reader.snapshot.activeChapter.pages.map { it.id }
        fun visible(mode: ReaderPresentationMode, indices: List<Int>, active: Int) = VisiblePageSet(
            DisplayUnitId(mode, indices.map { DisplaySlotId(pages[it]) }),
            indices.map { pages[it] }.toSet(), pages[active],
        )
        model.updateViewportSize(900, 1000, this)
        model.settleSinglePage(visible(ReaderPresentationMode.SINGLE_PAGED, listOf(2), 2))
        assertEquals(1, reports)
        model.setForcedSinglePages(setOf(4))
        model.updateViewportSize(1600, 1000, this)
        runCurrent()
        advanceTimeBy(150)
        runCurrent()
        model.settleDualPage(visible(ReaderPresentationMode.DUAL_PAGED, listOf(1, 2), 1))
        assertEquals(2, model.state.value.currentPage)
        assertEquals(1, reports)
        model.updateViewportSize(900, 1000, this)
        runCurrent()
        advanceTimeBy(150)
        runCurrent()
        model.settleSinglePage(visible(ReaderPresentationMode.SINGLE_PAGED, listOf(2), 2))
        assertEquals(1, reports)
        assertEquals(setOf(4), model.state.value.forcedSinglePages)
        model.goToPage(3)
        model.settleSinglePage(visible(ReaderPresentationMode.SINGLE_PAGED, listOf(3), 3))
        assertEquals(2, reports)
    }

    @Test
    fun `per manga default and following global are independent and do not rewrite global choice`() = runTest {
        val preferences = prefs().apply { readingMode = ReadingMode.LTR; isDualPage = true }
        val model = ReaderScreenModel(prefs = preferences)
        model.updateViewportSize(900, 1000, this)
        model.setReadingMode(ReadingMode.DEFAULT, preferences)
        assertEquals(ReadingMode.LTR, preferences.readingMode)
        assertFalse(model.state.value.dualPageMode)
        model.setDualPageMode(true, preferences)
        assertFalse(model.state.value.dualPageMode)
        assertTrue(preferences.isDualPage)
        val reopened = ReaderScreenModel(prefs = preferences, mangaViewerFlags = model.currentViewerFlags())
        assertTrue(reopened.state.value.automaticLayout)
        assertFalse(reopened.state.value.followsGlobalReadingMode)
        model.followGlobalReadingMode(preferences)
        assertEquals(ReadingMode.LTR, model.state.value.readingMode)
        assertTrue(model.state.value.followsGlobalReadingMode)
        assertTrue(model.state.value.dualPageMode)
        val inherited = ReaderScreenModel(prefs = preferences, mangaViewerFlags = model.currentViewerFlags())
        assertTrue(inherited.state.value.followsGlobalReadingMode)
        assertEquals(ReadingMode.LTR, inherited.state.value.readingMode)
        assertTrue(inherited.state.value.dualPageMode)
    }
}
