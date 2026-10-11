package mihon.desktop.ui.reader

import mihon.desktop.reader.ReaderPreferences
import mihon.desktop.reader.ReadingMode
import mihon.desktop.reader.desktopReaderSessionState
import mihon.desktop.ui.reader.presentation.resolveDualVisiblePages
import mihon.domain.reader.ReaderPageFact
import mihon.domain.reader.session.ReaderPageId
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.DesktopPreferenceStore
import java.util.prefs.Preferences

class SharedDualPageDesktopWiringTest {
    private val roots = mutableListOf<Preferences>()

    private fun preferences(): ReaderPreferences {
        val root = Preferences.userRoot().node("/mihon/shared-dual-page-wiring/${System.nanoTime()}")
        roots += root
        return ReaderPreferences(DesktopPreferenceStore(root.node("current")), root.node("legacy")).apply {
            readingMode = ReadingMode.RTL
            isDualPage = true
            isAutoSpreadMatching = false
        }
    }

    @AfterEach
    fun cleanup() {
        roots.forEach { it.removeNode() }
    }

    @Test
    fun `explicit second page remains logical target through direction and single mode changes`() {
        val reader = desktopReaderSessionState(chapterId = 7, pageCount = 5)
        val model = ReaderScreenModel(initialSessionState = reader, prefs = preferences())
        try {
            val pages = reader.snapshot.activeChapter.pages
            model.goToPage(4)
            pages.forEach {
                model.acceptDualFact(requireNotNull(model.captureDualFact(it.id)), ReaderPageFact.dimensions(20, 40))
            }
            assertEquals(4, model.state.value.currentPage)
            model.setReadingMode(ReadingMode.LTR)
            assertEquals(
                4,
                model.state.value.currentPage,
                "Presentation changes must not normalize logical page five to unit anchor four",
            )
            model.setDualPageMode(false)
            assertEquals(pages[4].id, model.state.value.dualPageLocation?.pageId)
            assertEquals(4, model.state.value.currentPage)
        } finally {
            model.onDispose()
        }
    }

    @Test
    fun `existing image retry renews original fact capability while retaining logical intent`() {
        val reader = desktopReaderSessionState(chapterId = 7, pageCount = 1)
        val retries = mutableListOf<ReaderPageId>()
        val model = ReaderScreenModel(initialSessionState = reader, prefs = preferences(), onPageRetry = retries::add)
        try {
            val page = reader.snapshot.activeChapter.pages.single().id
            val original = requireNotNull(model.captureDualFact(page))
            val location = model.state.value.dualPageLocation
            model.retryPage(page)
            assertEquals(listOf(page), retries)
            assertEquals(location, model.state.value.dualPageLocation)
            assertFalse(
                model.acceptDualFact(original, ReaderPageFact.dimensions(20, 40)),
                "Explicit retry must reject previous optional work without a manufactured GoTo",
            )
            assertTrue(
                model.acceptDualFact(requireNotNull(model.captureDualFact(page)), ReaderPageFact.dimensions(20, 40)),
            )
        } finally {
            model.onDispose()
        }
    }

    @Test
    fun `model consumes late source dimensions without replacing explicit logical target`() {
        val reader = desktopReaderSessionState(chapterId = 7, pageCount = 17, initialPage = 16)
        val model = ReaderScreenModel(initialSessionState = reader, prefs = preferences())
        try {
            model.goToPage(16)
            val pageIds = reader.snapshot.activeChapter.pages.map { it.id }
            pageIds.forEach { pageId ->
                val request = model.captureDualFact(pageId)
                assertNotNull(request, "Production model must issue the shared original fact capability")
                assertTrue(model.acceptDualFact(requireNotNull(request), ReaderPageFact.dimensions(1127, 1600)))
            }
            val wide = requireNotNull(model.captureDualFact(pageIds[9]))
            assertTrue(model.acceptDualFact(wide, ReaderPageFact.dimensions(1500, 1067)))
            assertEquals(16, model.state.value.currentPage)
            assertEquals(pageIds[16], model.state.value.dualPageLocation?.pageId)
            val units = model.state.value.dualPageUnits
            assertEquals(listOf(pageIds[16]), units.single { pageIds[16] in it.pageIds }.pageIds)
            assertEquals(pageIds.toSet(), units.flatMap { it.pageIds }.toSet())
            assertEquals(
                pageIds.size,
                units.sumOf {
                    it.pageIds.size
                },
                "Every source page belongs to exactly one native unit",
            )
            assertEquals(listOf(pageIds[9]), units.single { pageIds[9] in it.pageIds }.pageIds)
        } finally {
            model.onDispose()
        }
    }

    @Test
    fun `same ordered content replacement rejects old fact and presented viewport capabilities`() {
        val reader = desktopReaderSessionState(chapterId = 7, pageCount = 1)
        val reports = mutableListOf<Set<ReaderPageId>>()
        val model = ReaderScreenModel(
            initialSessionState = reader,
            prefs = preferences(),
            onViewportSettled = { pages, _ -> reports += pages },
        )
        try {
            val page = reader.snapshot.activeChapter.pages.single()
            val fact = requireNotNull(model.captureDualFact(page.id))
            assertTrue(model.acceptDualFact(fact, ReaderPageFact.dimensions(20, 40)))
            val presentation = model.state.value.dualPresentationSnapshot(false, false)
            val visible = presentation.resolveDualVisiblePages(
                presentation.displayUnits.single {
                    it.slots.any { slot ->
                        slot.page !=
                            null
                    }
                }.id,
            )
            val original = requireNotNull(model.beginPagerViewport(visible, userNavigation = false))
            val nextChapter = reader.snapshot.activeChapter.copy(pages = listOf(page.copy(attemptGeneration = 1)))
            model.acceptSessionState(reader.copy(snapshot = reader.snapshot.copy(activeChapter = nextChapter)))
            assertFalse(model.acceptDualFact(fact, ReaderPageFact.dimensions(20, 40)))
            model.settleDualPage(visible, original)
            assertTrue(reports.isEmpty(), "Old pixels cannot report reading for replacement content")
            assertEquals(page.id, model.state.value.dualPageLocation?.pageId)
        } finally {
            model.onDispose()
        }
    }

    @Test
    fun `real presented original ticket reports once and closed model rejects its delayed facts`() {
        val reader = desktopReaderSessionState(chapterId = 7, pageCount = 1)
        val reports = mutableListOf<Set<ReaderPageId>>()
        val model = ReaderScreenModel(
            initialSessionState = reader,
            prefs = preferences(),
            onViewportSettled = { pages, _ -> reports += pages },
        )
        val pageId = reader.snapshot.activeChapter.pages.single().id
        val original = requireNotNull(model.captureDualFact(pageId))
        assertTrue(model.acceptDualFact(original, ReaderPageFact.dimensions(20, 40)))
        val presentation = model.state.value.dualPresentationSnapshot(false, false)
        val visible = presentation.resolveDualVisiblePages(
            presentation.displayUnits.single {
                it.slots.any { slot ->
                    slot.page !=
                        null
                }
            }.id,
        )
        val ticket = requireNotNull(model.beginPagerViewport(visible, userNavigation = false))
        assertTrue(model.prepareDualViewportPage(ticket, pageId))
        assertTrue(model.drawDualViewportPage(ticket, pageId))
        model.settleDualPage(visible, ticket)
        model.settleDualPage(visible, ticket)
        assertEquals(listOf(setOf(pageId)), reports)
        val delayed = requireNotNull(model.captureDualFact(pageId))
        model.onDispose()
        assertFalse(model.acceptDualFact(delayed, ReaderPageFact.dimensions(30, 40)))
    }
}

/** Feeds synthetic Ready metadata through the real presenter protocol; this is not a Skia pixel observation. */
internal fun ReaderScreenModel.presentReadyPagerViewport(): mihon.desktop.ui.reader.presentation.VisiblePageSet {
    val current = state.value
    val snapshot = if (current.dualPageMode) {
        current.dualPresentationSnapshot(false, false)
    } else {
        mihon.desktop.ui.reader.presentation.SinglePagedPresentation.present(
            mihon.desktop.ui.reader.presentation.ReaderPresentationRequest(
                chapter = current.session.activeChapter,
                direction = if (current.readingMode ==
                    ReadingMode.RTL
                ) {
                    mihon.domain.reader.ReaderDirection.RTL
                } else {
                    mihon.domain.reader.ReaderDirection.LTR
                },
            ),
        )
    }
    val unit = snapshot.displayUnits.first { unit ->
        unit.slots.any { it.page?.id?.sourcePageIndex == current.currentPage }
    }
    val visible = if (current.dualPageMode) {
        snapshot.resolveDualVisiblePages(unit.id)
    } else {
        snapshot.visiblePages(unit.id)
    }
    val native = ReaderPagerNativePresentation(
        unit,
        visible,
        ReaderPagerPresentationBinding(Any(), this),
        {},
    )
    try {
        native.begin(userNavigation = false)
        unit.slots.filter { it.page?.loadState is mihon.domain.reader.session.ReaderPageLoadState.Ready }.forEach {
            if (native.prepare(it.id, it.page, 40, 60)) native.drawn(it.id)
        }
        native.setIdle(true)
        return visible
    } finally {
        native.close()
    }
}
