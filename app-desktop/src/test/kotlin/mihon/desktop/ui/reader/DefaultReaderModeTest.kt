package mihon.desktop.ui.reader

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mihon.desktop.reader.ReaderPreferences
import mihon.desktop.reader.ReadingMode
import mihon.desktop.reader.desktopReaderSessionState
import mihon.desktop.reader.readingModeFromViewerFlags
import mihon.desktop.reader.viewerFlagsWithReadingMode
import mihon.desktop.ui.reader.presentation.DisplaySlotId
import mihon.desktop.ui.reader.presentation.DisplayUnitId
import mihon.desktop.ui.reader.presentation.ReaderPresentationMode
import mihon.desktop.ui.reader.presentation.VisiblePageSet
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.DesktopPreferenceStore
import java.util.prefs.Preferences

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultReaderModeTest {
    private val root = Preferences.userRoot().node("/mihon/default-reader-test/${System.nanoTime()}")
    private fun prefs() = ReaderPreferences(DesktopPreferenceStore(root.node("current")), root.node("legacy"))

    @AfterEach
    fun cleanup() = root.removeNode()

    @Test
    fun `default clears old manual layout override and inherits global automatic`() {
        val old = mihon.desktop.reader.viewerFlagsWithDualPage(2L or (1L shl 34), true)
        val flags = viewerFlagsWithReadingMode(old, ReadingMode.DEFAULT)
        assertEquals(0L, flags)
        val reopened = ReaderScreenModel(prefs = prefs(), mangaViewerFlags = flags)
        assertTrue(reopened.state.value.automaticLayout)
        assertTrue(reopened.state.value.followsGlobalReadingMode)
    }

    @Test
    fun `legacy automatic flags preserve orientation and new automatic uses shared value seven`() {
        val legacy = (1L shl 34) or 2L or 0x28L
        val mode = readingModeFromViewerFlags(legacy)
        assertEquals("AUTO", mode?.name)
        val written = viewerFlagsWithReadingMode(legacy, mode)
        assertEquals(7L, written and 7L)
        assertEquals(0x28L, written and 0x38L)
        assertEquals(0L, written and (1L shl 34))
    }

    @Test
    fun `saved global default migrates to automatic without changing manual values`() {
        DesktopPreferenceStore(root.node("current")).getString("reader_reading_mode", "").set("DEFAULT")
        assertEquals("AUTO", prefs().readingMode.name)
        assertEquals("AUTO", prefs().readingMode.name)
    }

    @Test
    fun `automatic mode persists separately from default using shared value seven`() {
        val mode = ReadingMode.valueOf("AUTO")
        val flags = viewerFlagsWithReadingMode(0L, mode)
        assertEquals(mode, readingModeFromViewerFlags(flags))
        assertEquals(7L, flags and 7L)
        assertEquals(null, readingModeFromViewerFlags(viewerFlagsWithReadingMode(flags, null)))
    }

    @Test
    fun `fresh default is adaptive while saved manual preference remains manual`() = runTest {
        assertEquals(ReadingMode.AUTO, prefs().readingMode)
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
        prefs().readingMode = ReadingMode.AUTO
        assertEquals(ReadingMode.AUTO, prefs().readingMode)
        assertTrue(ReaderScreenModel(prefs = prefs()).state.value.automaticLayout)
    }

    @Test
    fun `existing manga only dual marker remains manual until default explicitly selected`() {
        val flags = mihon.desktop.reader.viewerFlagsWithDualPage(0L, true)
        val model = ReaderScreenModel(prefs = prefs(), mangaViewerFlags = flags)
        assertFalse(model.state.value.automaticLayout)
        assertFalse(model.state.value.followsGlobalReadingMode)
        assertTrue(model.state.value.dualPageMode)
        model.setReadingMode(ReadingMode.AUTO)
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
        val initial = desktopReaderSessionState(chapterId = 7L, pageCount = 6, initialPage = 2)
        val reader = initial.copy(context = initial.context.copy(mangaId = 1))
        val repository = object : mihon.domain.reader.ChapterPairingRepository {
            override suspend fun load(chapterId: Long, mangaId: Long) =
                mihon.domain.reader.ChapterPairingSnapshot(
                    mihon.domain.reader.ChapterPairingRecord(1, 6, setOf(4)),
                    1,
                )
            override suspend fun replace(
                chapterId: Long,
                mangaId: Long,
                expectedRevision: Long,
                pageCount: Int,
                forcedSinglePages: Set<Int>,
            ): mihon.domain.reader.ChapterPairingSnapshot =
                error("This fixture restores an existing manual record")
        }
        var reports = 0
        val model = ReaderScreenModel(
            prefs = prefs(),
            initialSessionState = reader,
            pairingCoordinator = mihon.desktop.reader.DesktopChapterPairingCoordinator(repository, backgroundScope),
            onViewportSettled = { _, _ -> reports++ },
        )
        runCurrent()
        reader.snapshot.activeChapter.pages.forEach {
            model.acceptDualFact(
                requireNotNull(model.captureDualFact(it.id)),
                mihon.domain.reader.ReaderPageFact.dimensions(40, 60),
            )
        }
        model.updateViewportSize(900, 1000, this)
        model.presentReadyPagerViewport()
        assertEquals(1, reports)
        model.updateViewportSize(1600, 1000, this)
        runCurrent()
        advanceTimeBy(150)
        runCurrent()
        val dual = model.presentReadyPagerViewport()
        assertEquals(setOf(1, 2), dual.pageIds.mapTo(linkedSetOf()) { it.sourcePageIndex })
        assertEquals(2, model.state.value.currentPage)
        assertEquals(1, reports)
        model.updateViewportSize(900, 1000, this)
        runCurrent()
        advanceTimeBy(150)
        runCurrent()
        model.setSpreadPages(setOf(2))
        model.presentReadyPagerViewport()
        assertEquals(1, reports)
        assertEquals(setOf(4), model.state.value.forcedSinglePages)
        model.goToPage(3)
        model.presentReadyPagerViewport()
        assertEquals(2, reports)
        model.onDispose()
    }

    @Test
    fun `per manga default and following global are independent and do not rewrite global choice`() = runTest {
        val preferences = prefs().apply {
            readingMode = ReadingMode.LTR
            isDualPage = true
        }
        val model = ReaderScreenModel(prefs = preferences)
        model.updateViewportSize(900, 1000, this)
        model.setReadingMode(ReadingMode.AUTO, preferences)
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
