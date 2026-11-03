package eu.kanade.tachiyomi.ui.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReaderOpenLatencyProbeTest {
    @Test
    fun configuredTargetPageRejectsOtherPagesAndOldRequest() {
        val probe = ReaderOpenLatencyProbe
        try {
            probe.configure(true, 10, 20, "dual-target", targetPage = 4)
            val request = probe.startChapter(10, 20)
            assertEquals(request, probe.requestFor(20, 4))
            assertEquals(0L, probe.requestFor(20, 0))
            assertFalse(probe.record("FIRST_DRAW", 0, 20, request))
            assertTrue(probe.record("PAGE_LIST_READY", chapter = 20, request = request))
            assertTrue(probe.record("FIRST_DRAW", 4, 20, request))
            assertTrue(probe.capturedEvents().all { it.page == 4 })
            probe.configure(true, 10, 20, "default-target")
            val next = probe.startChapter(10, 20)
            assertFalse(probe.record("FIRST_DRAW", 4, 20, request))
            assertEquals(next, probe.requestFor(20, 0))
        } finally {
            probe.configure(false, 10, 20, "off")
        }
    }

    @Test
    fun reconfigurationRejectsPriorEpoch() {
        ReaderOpenLatencyProbe.configure(true, 10, 20, "first")
        val old = ReaderOpenLatencyProbe.startChapter(10, 20)
        ReaderOpenLatencyProbe.configure(true, 10, 20, "second")
        val current = ReaderOpenLatencyProbe.startChapter(10, 20)
        assertNotEquals(old, current)
        assertFalse(ReaderOpenLatencyProbe.record("FIRST_DRAW", 0, 20, old))
        assertTrue(ReaderOpenLatencyProbe.record("FIRST_DRAW", 0, 20, current))
    }

    @Test
    fun targetHeaderCallIsObservableAndFirstEventWins() {
        ReaderOpenLatencyProbe.configure(true, 10, 20, "target-header")
        val request = ReaderOpenLatencyProbe.startChapter(10, 20)
        assertFalse(ReaderOpenLatencyProbe.record("TARGET_HEADER_BEGIN", 1, 20, request))
        assertFalse(ReaderOpenLatencyProbe.record("TARGET_HEADER_BEGIN", 0, 21, request))
        assertFalse(ReaderOpenLatencyProbe.record("TARGET_HEADER_BEGIN", 0, 20, request + 1))
        assertTrue(ReaderOpenLatencyProbe.record("TARGET_HEADER_BEGIN", 0, 20, request))
        val begin = ReaderOpenLatencyProbe.capturedEvents().single { it.event == "TARGET_HEADER_BEGIN" }
        assertFalse(ReaderOpenLatencyProbe.record("TARGET_HEADER_BEGIN", 0, 20, request))
        assertEquals(begin, ReaderOpenLatencyProbe.capturedEvents().single { it.event == "TARGET_HEADER_BEGIN" })
        assertFalse(ReaderOpenLatencyProbe.record("TARGET_HEADER_END", 1, 20, request))
        assertFalse(ReaderOpenLatencyProbe.record("TARGET_HEADER_END", 0, 20, request + 1))
        assertTrue(ReaderOpenLatencyProbe.record("TARGET_HEADER_END", 0, 20, request))
        assertFalse(ReaderOpenLatencyProbe.record("TARGET_HEADER_END", 0, 20, request))
        val end = ReaderOpenLatencyProbe.capturedEvents().single { it.event == "TARGET_HEADER_END" }
        assertTrue(begin.monotonicNanos <= end.monotonicNanos)
        listOf(
            "ACTIVATION_UI_ENTER",
            "PAIRING_OWNER_READY",
            "READING_GATE_READY",
            "CHAPTER_STATE_PUBLISH_BEGIN",
            "ACTIVITY_CHAPTERS_RECEIVED",
            "TARGET_IMAGE_ENTER",
            "TARGET_HEADER_DISPATCH",
        ).forEach { event ->
            assertTrue(ReaderOpenLatencyProbe.record(event, 0, 20, request), event)
            assertFalse(ReaderOpenLatencyProbe.record(event, 0, 20, request), event)
        }
        val chapter = Any()
        val pages = Any()
        val page0 = Any()
        val viewerChapters = Any()
        assertFalse(ReaderOpenLatencyProbe.bindPageList(request + 1, 20, chapter, pages, page0))
        assertFalse(ReaderOpenLatencyProbe.bindPageList(request, 21, chapter, pages, page0))
        assertTrue(ReaderOpenLatencyProbe.bindPageList(request, 20, chapter, pages, page0))
        assertFalse(ReaderOpenLatencyProbe.bindPageList(request, 20, chapter, Any(), page0))
        assertFalse(ReaderOpenLatencyProbe.recordForPageList("MANGA_READY", request, 20, Any(), pages))
        assertFalse(ReaderOpenLatencyProbe.recordForPageList("MANGA_READY", request, 20, chapter, null))
        assertTrue(ReaderOpenLatencyProbe.recordForPageList("MANGA_READY", request, 20, chapter, pages))
        assertTrue(ReaderOpenLatencyProbe.bindViewerChapters(request, 20, chapter, pages, viewerChapters))
        assertFalse(ReaderOpenLatencyProbe.bindViewerChapters(request, 20, chapter, pages, Any()))
        assertEquals(
            request,
            ReaderOpenLatencyProbe.requestForTargetPage(request, 20, chapter, pages, page0, viewerChapters),
        )
        assertEquals(
            0L,
            ReaderOpenLatencyProbe.requestForTargetPage(request, 20, chapter, pages, Any(), viewerChapters),
        )
        val next = ReaderOpenLatencyProbe.startChapter(10, 20)
        // Same chapter/pages can activate again; the old concrete publication must not acquire next's request.
        val nextViewerChapters = Any()
        assertTrue(ReaderOpenLatencyProbe.bindPageList(next, 20, chapter, pages, page0))
        assertTrue(ReaderOpenLatencyProbe.bindViewerChapters(next, 20, chapter, pages, nextViewerChapters))
        assertFalse(ReaderOpenLatencyProbe.recordForPageList("ACTIVATION_UI_ENTER", request, 20, chapter, pages))
        assertFalse(
            ReaderOpenLatencyProbe.recordForViewerChapters(
                "ACTIVITY_CHAPTERS_RECEIVED",
                next,
                20,
                chapter,
                pages,
                viewerChapters,
            ),
        )
        assertEquals(
            0L,
            ReaderOpenLatencyProbe.requestForTargetPage(next, 20, chapter, pages, page0, viewerChapters),
        )
        assertEquals(
            next,
            ReaderOpenLatencyProbe.requestForTargetPage(next, 20, chapter, pages, page0, nextViewerChapters),
        )
        listOf(
            "ACTIVATION_UI_ENTER",
            "PAIRING_OWNER_READY",
            "READING_GATE_READY",
            "CHAPTER_STATE_PUBLISH_BEGIN",
            "ACTIVITY_CHAPTERS_RECEIVED",
            "TARGET_IMAGE_ENTER",
            "TARGET_HEADER_DISPATCH",
        ).forEach { event ->
            assertFalse(ReaderOpenLatencyProbe.record(event, 1, 20, next), event)
            assertFalse(ReaderOpenLatencyProbe.record(event, 0, 20, request), event)
            fun recordBound() = if (event == "ACTIVITY_CHAPTERS_RECEIVED") {
                ReaderOpenLatencyProbe.recordForViewerChapters(event, next, 20, chapter, pages, nextViewerChapters)
            } else {
                ReaderOpenLatencyProbe.recordForPageList(event, next, 20, chapter, pages)
            }
            assertTrue(recordBound(), event)
            assertFalse(recordBound(), event)
        }
        listOf(
            "VIEWER_CHAPTERS_APPLY_ENTER",
            "PAGER_ADAPTER_READY",
            "TARGET_HOLDER_CREATED",
            "TARGET_LOAD_ENTER",
            "TARGET_STATUS_COLLECT",
            "TARGET_READY_ENTER",
        ).forEach { event ->
            assertFalse(ReaderOpenLatencyProbe.record(event, 1, 20, next), event)
            assertTrue(ReaderOpenLatencyProbe.recordForPageList(event, next, 20, chapter, pages), event)
            assertFalse(ReaderOpenLatencyProbe.recordForPageList(event, next, 20, chapter, pages), event)
        }
        assertTrue(ReaderOpenLatencyProbe.isStartupWindow(next, 20))
        assertFalse(ReaderOpenLatencyProbe.isStartupWindow(request, 20))
        assertFalse(ReaderOpenLatencyProbe.isStartupWindow(next, 21))
        assertTrue(ReaderOpenLatencyProbe.record("TARGET_HEADER_BEGIN", 0, 20, next))
        assertFalse(ReaderOpenLatencyProbe.isStartupWindow(next, 20))
        assertFalse(ReaderOpenLatencyProbe.notePagerDispatch(next, 20, chapter, pages, viewerChapters, true))
        assertTrue(ReaderOpenLatencyProbe.notePagerDispatch(next, 20, chapter, pages, nextViewerChapters, false))
        assertEquals(false, ReaderOpenLatencyProbe.capturedPagerAwaitedIdle(next))
        assertFalse(ReaderOpenLatencyProbe.notePagerDispatch(next, 20, chapter, pages, nextViewerChapters, true))
        assertEquals(false, ReaderOpenLatencyProbe.capturedPagerAwaitedIdle(next))
        val deferred = ReaderOpenLatencyProbe.startChapter(10, 20)
        val deferredViewerChapters = Any()
        assertTrue(ReaderOpenLatencyProbe.bindPageList(deferred, 20, chapter, pages, page0))
        assertTrue(ReaderOpenLatencyProbe.bindViewerChapters(deferred, 20, chapter, pages, deferredViewerChapters))
        assertTrue(ReaderOpenLatencyProbe.notePagerDispatch(deferred, 20, chapter, pages, deferredViewerChapters, true))
        assertEquals(true, ReaderOpenLatencyProbe.capturedPagerAwaitedIdle(deferred))
        ReaderOpenLatencyProbe.configure(false, 10, 20, "off")
        assertEquals(null, ReaderOpenLatencyProbe.capturedPagerAwaitedIdle(deferred))
        assertFalse(ReaderOpenLatencyProbe.bindPageList(next, 20, chapter, pages, page0))
        assertEquals(
            0L,
            ReaderOpenLatencyProbe.requestForTargetPage(next, 20, chapter, pages, page0, nextViewerChapters),
        )
        ReaderOpenLatencyProbe.configure(true, 10, 20, "new-epoch")
        assertEquals(
            0L,
            ReaderOpenLatencyProbe.requestForTargetPage(next, 20, chapter, pages, page0, nextViewerChapters),
        )
        ReaderOpenLatencyProbe.configure(false, 10, 20, "off")
    }

    @Test
    fun diagnosticBudgetIsBoundedAndDisabledDoesNothing() {
        ReaderOpenLatencyProbe.configure(true, 10, 20, "bounded")
        repeat(32) { assertTrue(ReaderOpenLatencyProbe.startChapter(10, 20) > 0) }
        assertEquals(0L, ReaderOpenLatencyProbe.startChapter(10, 20))
        ReaderOpenLatencyProbe.configure(false, 10, 20, "off")
        assertEquals(0L, ReaderOpenLatencyProbe.startChapter(10, 20))
        assertFalse(ReaderOpenLatencyProbe.record("FIRST_DRAW"))
    }
}
