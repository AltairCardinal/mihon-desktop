package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.app.Application
import androidx.viewpager.widget.ViewPager
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.databinding.ReaderActivityBinding
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import eu.kanade.tachiyomi.ui.reader.loader.HttpReaderMaterializeAdapterIntegrationTest.TestHttpSource
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.model.completePageListForTest
import eu.kanade.tachiyomi.ui.reader.model.openPageListForTest
import eu.kanade.tachiyomi.ui.reader.model.publishLoadedPageListForTest
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.viewer.AndroidReaderSpreadSampler
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import eu.kanade.tachiyomi.ui.reader.viewer.RecordingReaderActivity
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import mihon.domain.reader.ReaderAnalysisFailure
import mihon.domain.reader.ReaderDualPageFactReadPermit
import mihon.domain.reader.ReaderPageAnalysisEvidence
import mihon.domain.reader.ReaderPageFactState
import mihon.domain.reader.SharedDualPageSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.domain.chapter.model.Chapter
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.registry.default.DefaultRegistrar
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class SharedDualPageAndroidWiringTest {
    private var o7Sink: eu.kanade.tachiyomi.ui.reader.diagnostics.ReaderDiagnosticSink? = null
    private var o7PreviousSink: eu.kanade.tachiyomi.ui.reader.diagnostics.ReaderDiagnosticSink? = null
    private var o7Directory: java.io.File? = null

    private fun o7Lines(): List<String> =
        o7Directory?.listFiles().orEmpty().flatMap { it.readLines() }

    private val mainDispatcher = StandardTestDispatcher()
    private lateinit var previousInjekt: InjektScope
    private lateinit var viewer: DualPageR2LPagerViewer
    private lateinit var activity: RecordingReaderActivity
    private lateinit var model: ReaderViewModel

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun `O2 equal dimension callbacks stay accepted without another certification request`() = runBlocking {
        val fixture = o2Facts()
        val page = fixture.pages[1]
        val before = fixture.owner.certificationMetrics.entries
        viewer.adapter.updatePageDimensions(page, 40, 60)
        assertTrue(viewer.adapter.acceptDecodedDimensions(page, viewer.adapter.captureDimensions(page), 40, 60))
        assertEquals(
            "An accepted duplicate must not re-enter the actual adapter certification work",
            before,
            fixture.owner.certificationMetrics.entries,
        )
        assertO2Projection(fixture)
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun `O2 a fact consumed by ensure still reaches the native adapter projection`() = runBlocking {
        val fixture = o2Facts(deferredHeader = 1, deferredWidth = 60, deferredHeight = 40)
        val page = fixture.pages[1]
        val oldLayout = fixture.owner.layoutRevision
        page.publishHeaderFact()
        val admitted = o2Store().ensure(fixture.chapter, fixture.pages)
        assertSame(fixture.owner, admitted.session)
        assertEquals(ReaderPageFactState.SPREAD, fixture.owner.pageFact(fixture.owner.identity.pageIds[1])?.state)
        assertTrue(fixture.owner.layoutRevision > oldLayout)
        val before = fixture.owner.certificationMetrics.entries
        assertTrue(viewer.adapter.acceptDecodedDimensions(page, viewer.adapter.captureDimensions(page), 60, 40))
        assertTrue(
            "ensure already changed the owner; comparing only this accept call would lose its projection",
            fixture.owner.certificationMetrics.entries > before,
        )
        assertO2Projection(fixture)
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun `O2 changed dimensions project once while their repeated accepted value does no new certification`() =
        runBlocking {
            val fixture = o2Facts()
            val page = fixture.pages[1]
            val location = fixture.owner.location
            val before = fixture.owner.certificationMetrics.entries
            viewer.adapter.updatePageDimensions(page, 60, 40)
            assertEquals(ReaderPageFactState.SPREAD, fixture.owner.pageFact(fixture.owner.identity.pageIds[1])?.state)
            assertTrue(fixture.owner.certificationMetrics.entries > before)
            assertEquals(location, fixture.owner.location)
            assertO2Projection(fixture)
            val projected = fixture.owner.certificationMetrics.entries
            assertTrue(viewer.adapter.acceptDecodedDimensions(page, viewer.adapter.captureDimensions(page), 60, 40))
            assertEquals(projected, fixture.owner.certificationMetrics.entries)
        }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun `O2 changed original header still projects when computed original facts retain their value`() = runBlocking {
        val fixture = o2Facts(sampleOriginals = true)
        val page = fixture.pages[0]
        val id = fixture.owner.identity.pageIds[0]
        assertTrue(fixture.owner.originalAnalysis(id) is ReaderPageAnalysisEvidence.Computed)
        val retained = fixture.owner.pageFact(id)
        val before = fixture.owner.certificationMetrics.entries
        assertTrue(viewer.adapter.acceptDecodedDimensions(page, viewer.adapter.captureDimensions(page), 60, 40))
        assertEquals("Computed original evidence remains authoritative", retained, fixture.owner.pageFact(id))
        assertTrue(
            "A changed header is a certification input even when the effective sampled fact is retained",
            fixture.owner.certificationMetrics.entries > before,
        )
        val projected = fixture.owner.certificationMetrics.entries
        assertTrue(viewer.adapter.acceptDecodedDimensions(page, viewer.adapter.captureDimensions(page), 60, 40))
        assertEquals(projected, fixture.owner.certificationMetrics.entries)
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun `O2 replacement rejects old facts and projects new owner before deduplication`() = runBlocking {
        val fixture = o2Facts()
        val page = fixture.pages[1]
        val oldRequest = viewer.adapter.captureDimensions(page)
        val replacement = readerTestPng(40, 60)
        page.stream = { replacement.inputStream() }
        page.publishHeaderFact()
        val next = o2Store().ensure(fixture.chapter, fixture.pages).session
        assertNotSame(fixture.owner, next)
        val before = next.certificationMetrics.entries
        assertFalse(viewer.adapter.acceptDecodedDimensions(page, oldRequest, 40, 60))
        assertEquals(before, next.certificationMetrics.entries)
        assertTrue(viewer.adapter.acceptDecodedDimensions(page, viewer.adapter.captureDimensions(page), 40, 60))
        assertTrue(next.certificationMetrics.entries > before)
        assertO2Projection(fixture.copy(owner = next))
        val projected = next.certificationMetrics.entries
        assertTrue(viewer.adapter.acceptDecodedDimensions(page, viewer.adapter.captureDimensions(page), 40, 60))
        assertEquals(projected, next.certificationMetrics.entries)
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun `O2 retry sequence still refreshes equal facts before later duplicates are skipped`() = runBlocking {
        val fixture = o2Facts()
        val location = fixture.owner.location
        val revision = fixture.owner.factWorkRevision
        val before = fixture.owner.certificationMetrics.entries
        fixture.pages[1].retryHeaderFact()
        mainDispatcher.scheduler.runCurrent()
        assertTrue(fixture.owner.factWorkRevision > revision)
        assertTrue(fixture.owner.certificationMetrics.entries > before)
        assertEquals(location, fixture.owner.location)
        assertO2Projection(fixture)
        val projected = fixture.owner.certificationMetrics.entries
        viewer.adapter.updatePageDimensions(fixture.pages[1], 40, 60)
        assertEquals(projected, fixture.owner.certificationMetrics.entries)
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun `O2 changed facts remain pending throughout a native gesture and project at idle`() = runBlocking {
        val fixture = o2Facts()
        val listener = gestureListener()
        listener.onPageScrollStateChanged(ViewPager.SCROLL_STATE_DRAGGING)
        assertTrue(viewer.hasActiveGesture)
        val held = viewer.adapter.items.toList()
        val before = fixture.owner.certificationMetrics.entries
        viewer.adapter.updatePageDimensions(fixture.pages[1], 60, 40)
        assertEquals(held, viewer.adapter.items)
        assertEquals(before, fixture.owner.certificationMetrics.entries)
        assertTrue(ReflectionHelpers.getField<Boolean>(viewer.adapter, "pendingPairing"))
        listener.onPageScrollStateChanged(ViewPager.SCROLL_STATE_IDLE)
        assertFalse(viewer.hasActiveGesture)
        assertFalse(ReflectionHelpers.getField<Boolean>(viewer.adapter, "pendingPairing"))
        assertTrue(fixture.owner.certificationMetrics.entries > before)
        assertO2Projection(fixture)
        val projected = fixture.owner.certificationMetrics.entries
        viewer.adapter.updatePageDimensions(fixture.pages[1], 60, 40)
        assertEquals(projected, fixture.owner.certificationMetrics.entries)
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun `O2 duplicate accepted facts preserve the drawn frame ticket and location`() = runBlocking {
        val fixture = o2Facts()
        val ticket = requireNotNull(fixture.owner.captureViewport(preparingPageIds = emptySet()))
        val ids = requireNotNull(fixture.owner.frame(ticket)).unit.pageIds.toSet()
        ids.forEach { assertTrue(fixture.owner.preparePage(ticket, it)) }
        assertTrue(fixture.owner.reportDrawn(ticket, ids))
        val frame = requireNotNull(fixture.owner.frame(ticket))
        val location = fixture.owner.location
        val before = fixture.owner.certificationMetrics.entries
        repeat(3) {
            assertTrue(
                viewer.adapter.acceptDecodedDimensions(
                    fixture.pages[0],
                    viewer.adapter.captureDimensions(fixture.pages[0]),
                    40,
                    60,
                ),
            )
        }
        assertEquals(before, fixture.owner.certificationMetrics.entries)
        assertEquals(frame, fixture.owner.frame(ticket))
        assertEquals(location, fixture.owner.location)
        assertEquals(ids, fixture.owner.frame(ticket)?.drawnPageIds)
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun `O2 explicit sample failure refresh remains active before equal fact callbacks are skipped`() = runBlocking {
        val fixture = o2Facts()
        val layout = fixture.owner.layoutRevision
        val work = fixture.owner.sampleWorkRevision
        val request = requireNotNull(fixture.owner.requestOriginalAnalysis(fixture.owner.identity.pageIds[1]))
        assertTrue(fixture.owner.failOriginalAnalysis(request, ReaderAnalysisFailure.RESOURCE))
        assertEquals(layout, fixture.owner.layoutRevision)
        assertTrue(fixture.owner.sampleWorkRevision > work)
        val before = fixture.owner.certificationMetrics.entries
        // This existing explicit matching refresh remains outside fact-event deduplication.
        viewer.adapter.refreshPairing()
        assertTrue(fixture.owner.certificationMetrics.entries > before)
        val projected = fixture.owner.certificationMetrics.entries
        viewer.adapter.updatePageDimensions(fixture.pages[1], 40, 60)
        assertEquals(projected, fixture.owner.certificationMetrics.entries)
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun `O2 notify reentry cannot mark a later ensured fact as already projected`() = runBlocking {
        val fixture = o2Facts(deferredHeader = 2)
        var injected = false
        val observer = object : android.database.DataSetObserver() {
            override fun onChanged() {
                if (injected) return
                injected = true
                fixture.pages[2].publishHeaderFact()
                assertSame(fixture.owner, o2Store().ensure(fixture.chapter, fixture.pages).session)
            }
        }
        viewer.adapter.registerDataSetObserver(observer)
        try {
            viewer.adapter.updatePageDimensions(fixture.pages[1], 60, 40)
            assertTrue("A real adapter notify must synchronously admit the later fact", injected)
            val before = fixture.owner.certificationMetrics.entries
            assertTrue(
                viewer.adapter.acceptDecodedDimensions(
                    fixture.pages[2],
                    viewer.adapter.captureDimensions(fixture.pages[2]),
                    40,
                    60,
                ),
            )
            assertTrue(
                "The outer projection consumed older input; the reentrant ensured fact still owes projection",
                fixture.owner.certificationMetrics.entries > before,
            )
            assertO2Projection(fixture)
        } finally {
            viewer.adapter.unregisterDataSetObserver(observer)
        }
    }

    private data class O2FactFixture(
        val chapter: ReaderChapter,
        val pages: List<ReaderPage>,
        val owner: SharedDualPageSession,
    )

    private fun o2Store(): DualPagePairingStore = ReflectionHelpers.getField(viewer.adapter, "store")

    private suspend fun o2Facts(
        deferredHeader: Int? = null,
        deferredWidth: Int = 40,
        deferredHeight: Int = 60,
        sampleOriginals: Boolean = false,
    ): O2FactFixture {
        val chapter = chapter(5)
        val pages = requireNotNull(chapter.pages)
        pages.forEach { page ->
            val bytes = if (page.index == deferredHeader) {
                readerTestPng(deferredWidth, deferredHeight)
            } else {
                readerTestPng(40, 60)
            }
            page.stream = { bytes.inputStream() }
            page.status = eu.kanade.tachiyomi.source.model.Page.State.Ready
            if (page.index != deferredHeader) page.publishHeaderFact()
        }
        chapter.pageLoader = object : eu.kanade.tachiyomi.ui.reader.loader.PageLoader() {
            override var isLocal = true
            override val hasForegroundWork = !sampleOriginals
            override suspend fun getPages() = pages
        }
        val reads = java.util.concurrent.atomic.AtomicInteger()
        val releases = java.util.concurrent.atomic.AtomicInteger()
        viewer.adapter.spreadDecoderFactory = { stream ->
            reads.incrementAndGet()
            eu.kanade.tachiyomi.ui.reader.viewer.decodedRegionFactory(stream) { releases.incrementAndGet() }
        }
        Injekt.get<ReaderPreferences>().automaticSpreadMatching().set(sampleOriginals)
        mainDispatcher.scheduler.runCurrent()
        viewer.setChapters(ViewerChapters(chapter, null, null))
        val owner = requireNotNull(viewer.adapter.session(chapter))
        val deadline = System.nanoTime() + 5_000_000_000L
        do {
            mainDispatcher.scheduler.runCurrent()
            val job = ReflectionHelpers.getField<kotlinx.coroutines.Job?>(viewer.adapter, "matchingJob")
            val originalsReady = !sampleOriginals || owner.identity.pageIds.all {
                owner.originalAnalysis(it) is ReaderPageAnalysisEvidence.Computed
            }
            if (job?.isCompleted != false && AndroidReaderSpreadSampler.optionalWork.value == 0 && originalsReady) {
                if (sampleOriginals) {
                    assertTrue(reads.get() > 0)
                    assertEquals(reads.get(), releases.get())
                }
                return O2FactFixture(chapter, pages, owner)
            }
            kotlinx.coroutines.delay(5)
        } while (System.nanoTime() < deadline)
        error("O2 fixture did not finish its real matching/sampling work")
    }

    private fun assertO2Projection(fixture: O2FactFixture) {
        val actual = viewer.adapter.items.filterIsInstance<DisplayPage>()
            .filter { it.firstPage.chapter === fixture.chapter }.map { it.frameId }.toSet()
        val expected = fixture.owner.presentationFrames.map { it.frameId }.toSet()
        assertTrue(expected.isNotEmpty())
        assertEquals("The actual native adapter must contain the current owner's frame projection", expected, actual)
    }

    @Before
    fun setUp() {
        previousInjekt = Injekt
        Injekt = InjektScope(DefaultRegistrar())
        val application = RuntimeEnvironment.getApplication() as Application
        val preferences = AndroidPreferenceStore(
            application,
            application.getSharedPreferences("shared-dual-${System.nanoTime()}", 0),
        )
        Injekt.addSingleton(ReaderPreferences(preferences))
        Injekt.addSingleton(UiPreferences(preferences))
        Injekt.addSingleton(BasePreferences(application, preferences))
        Injekt.addSingleton(mockk<DownloadManager>(relaxed = true))
        Dispatchers.setMain(mainDispatcher)
        activity = Robolectric.buildActivity(RecordingReaderActivity::class.java).get()
        activity.binding = mockk<ReaderActivityBinding>(relaxed = true)
        model = mockk<ReaderViewModel>(relaxed = true)
        every { model.state } returns MutableStateFlow(ReaderViewModel.State())
        ReflectionHelpers.setField(activity, "viewModel\$delegate", lazyOf(model))
        viewer = DualPageR2LPagerViewer(activity)
        viewer.config.navigationModeChangedListener = null
    }

    @After
    fun tearDown() {
        // Restore optional diagnostics even if the native wiring assertion fails.
        try {
            if (::viewer.isInitialized) {
                val ownedScope = ReflectionHelpers.getField<kotlinx.coroutines.CoroutineScope>(viewer.adapter, "scope")
                val ownedJob = requireNotNull(ownedScope.coroutineContext[kotlinx.coroutines.Job])
                viewer.destroy()
                // Cancellation does not mean the native IO has exited. Its continuation and
                // the sampler's completion counter still belong to this fixture's Main queue.
                val deadline = System.nanoTime() + 10_000_000_000L
                do {
                    mainDispatcher.scheduler.runCurrent()
                    if (ownedJob.isCompleted &&
                        eu.kanade.tachiyomi.ui.reader.viewer.AndroidReaderSpreadSampler.optionalWork.value == 0
                    ) {
                        break
                    }
                    Thread.sleep(5)
                } while (System.nanoTime() < deadline)
                assertTrue("Destroyed adapter scope must finish its actual owned work: $ownedJob", ownedJob.isCompleted)
                assertEquals(
                    "Finish physical sampling and its completion callback before resetting the old Main dispatcher",
                    0,
                    eu.kanade.tachiyomi.ui.reader.viewer.AndroidReaderSpreadSampler.optionalWork.value,
                )
            }
        } finally {
            if (o7Sink != null) {
                eu.kanade.tachiyomi.ui.reader.ReaderOpenLatencyProbe.configure(false, -1, -1, "off")
                eu.kanade.tachiyomi.ui.reader.diagnostics.ReaderDiagnostics.sink = o7PreviousSink
                o7Sink?.close()
                o7Directory?.deleteRecursively()
            }
            Dispatchers.resetMain()
            Injekt = previousInjekt
        }
    }

    private var replaceViewerDuringMatching = false

    @Test
    fun `real key input turns whole loading groups and exact jump keeps its entered second source`() {
        val chapter = chapter(9)
        val pages = requireNotNull(chapter.pages)
        viewer.config.usePageTransitions = false
        viewer.setChapters(ViewerChapters(chapter, null, null))
        viewer.moveToPage(pages[1])
        viewer.adapter.updatePageDimensions(pages[1], 100, 200)
        val owner = requireNotNull(viewer.adapter.session(chapter))
        val current = requireNotNull(owner.captureViewport())
        assertTrue(owner.reportDrawn(current, setOf(owner.identity.pageIds[1])))
        fun next() = viewer.handleKeyEvent(
            android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DPAD_LEFT),
        )
        assertTrue(next())
        assertEquals(3, owner.location!!.pageId.sourcePageIndex)
        assertEquals(
            listOf(3, 4),
            (viewer.adapter.items[viewer.pager.currentItem] as DisplayPage).visiblePages.map {
                it.index
            },
        )
        assertTrue(next())
        assertEquals(5, owner.location!!.pageId.sourcePageIndex)
        assertTrue(
            viewer.handleKeyEvent(
                android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DPAD_RIGHT),
            ),
        )
        assertEquals(listOf(3, 4), owner.targetUnit!!.pageIds.map { it.sourcePageIndex })
        viewer.moveToPage(pages[4])
        assertEquals(4, owner.location!!.pageId.sourcePageIndex)
        assertEquals(listOf(3, 4), owner.targetUnit!!.pageIds.map { it.sourcePageIndex })
        assertTrue(owner.frame(current)?.drawnPageIds.orEmpty().none { it.sourcePageIndex == 2 })
    }

    @Test
    fun `explicit cold jump publishes target group before any new image fact arrives`() {
        for (ready in listOf(false, true)) {
            val chapter = chapter(9)
            val pages = requireNotNull(chapter.pages)
            viewer.config.usePageTransitions = false
            viewer.setChapters(ViewerChapters(chapter, null, null))
            if (ready) pages.forEach { viewer.adapter.updatePageDimensions(it, 100, 200) }
            val owner = requireNotNull(viewer.adapter.session(chapter))
            assertTrue(owner.displayedHistory.isEmpty())
            // Neither jump may inherit the preceding page from an unentered list proposal.
            for ((target, expected) in listOf(6 to listOf(6, 7), 8 to listOf(8))) {
                viewer.moveToPage(pages[target])
                assertEquals(target, owner.location!!.pageId.sourcePageIndex)
                assertEquals(expected, owner.targetUnit!!.pageIds.map { it.sourcePageIndex })
                assertEquals(
                    expected,
                    (viewer.adapter.items[viewer.pager.currentItem] as DisplayPage).visiblePages.map { it.index },
                )
            }
        }
    }

    @Test
    fun `continuous native drags synchronize every anchor without jumping back at idle`() {
        val chapter = gestureChapter()
        val pages = requireNotNull(chapter.pages)
        val listener = gestureListener()
        listener.onPageScrollStateChanged(ViewPager.SCROLL_STATE_DRAGGING)
        selectNative(pages[1])
        assertEquals(1, viewer.adapter.session(chapter)?.location?.pageId?.sourcePageIndex)
        verify { model.onPageSelected(pages[1], listOf(pages[1]), recordProgress = false) }
        listener.onPageScrollStateChanged(ViewPager.SCROLL_STATE_SETTLING)
        listener.onPageScrollStateChanged(ViewPager.SCROLL_STATE_DRAGGING)
        selectNative(pages[3])
        selectNative(pages[5])
        assertEquals(
            "Native selected source5 must remain logical source5; units=" +
                viewer.adapter.session(chapter)?.units?.map { it.pageIds.map { page -> page.sourcePageIndex } },
            5,
            viewer.adapter.session(chapter)?.location?.pageId?.sourcePageIndex,
        )
        val position = viewer.pager.currentItem
        listener.onPageScrollStateChanged(ViewPager.SCROLL_STATE_IDLE)
        assertEquals(position, viewer.pager.currentItem)
        assertEquals(5, viewer.adapter.session(chapter)?.location?.pageId?.sourcePageIndex)
        verify { model.onPageSelected(pages[5], listOf(pages[5]), recordProgress = false) }
    }

    @Test
    fun `native drag reversal and cancellation retain the actually selected locked group`() {
        val chapter = gestureChapter()
        val pages = requireNotNull(chapter.pages)
        val listener = gestureListener()
        listener.onPageScrollStateChanged(ViewPager.SCROLL_STATE_DRAGGING)
        selectNative(pages[1])
        lockNativeSelection(chapter)
        selectNative(pages[3])
        lockNativeSelection(chapter)
        selectNative(pages[1])
        val owner = requireNotNull(viewer.adapter.session(chapter))
        assertTrue(owner.location?.pageId?.sourcePageIndex in 1..2)
        val position = viewer.pager.currentItem
        listener.onPageScrollStateChanged(ViewPager.SCROLL_STATE_IDLE)
        assertEquals(position, viewer.pager.currentItem)
        listener.onPageScrollStateChanged(ViewPager.SCROLL_STATE_DRAGGING)
        selectNative(pages[0])
        selectNative(pages[1])
        listener.onPageScrollStateChanged(ViewPager.SCROLL_STATE_IDLE)
        assertEquals(position, viewer.pager.currentItem)
        assertTrue(owner.location?.pageId?.sourcePageIndex in 1..2)
    }

    @Test
    fun `unchanged native rebuild retains the bounded normal pairing memo`() {
        val chapter = gestureChapter()
        viewer.moveToPage(requireNotNull(chapter.pages)[3])
        val owner = requireNotNull(viewer.adapter.session(chapter))
        viewer.adapter.refreshPairing()
        val before = owner.certificationMetrics.engineRuns
        viewer.adapter.refreshPairing()
        assertEquals(
            "The same unchanged near window must not thrash the four-entry certification memo",
            before,
            owner.certificationMetrics.engineRuns,
        )
    }

    @Test
    fun `cancelled reverse selection preserves last successful forward preparation direction`() {
        val chapter = gestureChapter()
        val pages = requireNotNull(chapter.pages)
        val listener = gestureListener()
        viewer.moveToPage(pages[3])
        lockNativeSelection(chapter)
        assertEquals(true, ReflectionHelpers.getField<Boolean>(viewer.adapter, "lastNavigationForward"))
        listener.onPageScrollStateChanged(ViewPager.SCROLL_STATE_DRAGGING)
        selectNative(pages[1])
        lockNativeSelection(chapter)
        selectNative(pages[3])
        listener.onPageScrollStateChanged(ViewPager.SCROLL_STATE_IDLE)
        assertEquals(true, ReflectionHelpers.getField<Boolean>(viewer.adapter, "lastNavigationForward"))
        listener.onPageScrollStateChanged(ViewPager.SCROLL_STATE_DRAGGING)
        selectNative(pages[5])
        lockNativeSelection(chapter)
        selectNative(pages[3])
        listener.onPageScrollStateChanged(ViewPager.SCROLL_STATE_IDLE)
        assertEquals(true, ReflectionHelpers.getField<Boolean>(viewer.adapter, "lastNavigationForward"))
    }

    @Test
    fun `explicit jump during drag discards the obsolete physical direction snapshot`() {
        val chapter = gestureChapter()
        val pages = chapter.pages!!
        viewer.moveToPage(pages[3])
        lockNativeSelection(chapter)
        val listener = gestureListener()
        listener.onPageScrollStateChanged(ViewPager.SCROLL_STATE_DRAGGING)
        selectNative(pages[5])
        viewer.moveToPage(pages[0])
        assertTrue("Explicit source intent replaces the in-flight drag", !viewer.hasActiveGesture)
        listener.onPageScrollStateChanged(ViewPager.SCROLL_STATE_IDLE)
        assertEquals(0, viewer.adapter.session(chapter)!!.location!!.pageId.sourcePageIndex)
        assertEquals(true, ReflectionHelpers.getField<Boolean>(viewer.adapter, "lastNavigationForward"))
    }

    @Test
    fun `late native facts publish only after the final gesture anchor and deferred chapter window`() {
        val chapter = gestureChapter()
        val pages = requireNotNull(chapter.pages)
        val listener = gestureListener()
        listener.onPageScrollStateChanged(ViewPager.SCROLL_STATE_DRAGGING)
        selectNative(pages[1])
        lockNativeSelection(chapter)
        val frozen = viewer.adapter.items.toList()
        val position = viewer.pager.currentItem
        val notifications = java.util.concurrent.atomic.AtomicInteger()
        val observer = object : android.database.DataSetObserver() {
            override fun onChanged() {
                notifications.incrementAndGet()
            }
        }
        viewer.adapter.registerDataSetObserver(observer)
        viewer.adapter.updatePageDimensions(pages[3], 300, 100)
        assertEquals(
            "Only unexposed shell descriptions may change during a drag",
            frozen.size,
            viewer.adapter.items.size,
        )
        assertEquals(position, viewer.pager.currentItem)
        assertEquals(frozen[position], viewer.adapter.items[position])
        assertEquals(0, notifications.get())
        assertEquals(
            frozen.withIndex().filter { it.value is eu.kanade.tachiyomi.ui.reader.model.ChapterTransition },
            viewer.adapter.items.withIndex().filter {
                it.value is eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
            },
        )
        viewer.adapter.unregisterDataSetObserver(observer)
        selectNative(pages[3])
        assertEquals(
            "A drawn B advances to the actual next wide source",
            3,
            viewer.adapter.session(chapter)?.location?.pageId?.sourcePageIndex,
        )
        val deferredNext = chapter(3, 8002)
        viewer.setChapters(ViewerChapters(chapter, null, deferredNext))
        assertTrue(viewer.adapter.items.filterIsInstance<DisplayPage>().none { it.firstPage.chapter === deferredNext })
        listener.onPageScrollStateChanged(ViewPager.SCROLL_STATE_IDLE)
        assertEquals(3, viewer.adapter.session(chapter)?.location?.pageId?.sourcePageIndex)
        val selected = viewer.adapter.items[viewer.pager.currentItem] as DisplayPage.Single
        assertSame(pages[3], selected.page)
        assertEquals(mihon.domain.reader.ReaderPortraitSingleSlot.FULL, selected.slot)
        assertTrue(viewer.adapter.items.filterIsInstance<DisplayPage>().any { it.firstPage.chapter === deferredNext })
        assertTrue(
            viewer.adapter.items.filterIsInstance<DisplayPage>().any {
                it.visiblePages == listOf(pages[3])
            },
        )
    }

    @Test
    fun `native chapter end transition and interrupted back drag do not replay an old forward intent`() {
        val chapter = gestureChapter()
        val pages = requireNotNull(chapter.pages)
        val listener = gestureListener()
        listener.onPageScrollStateChanged(ViewPager.SCROLL_STATE_DRAGGING)
        selectNative(pages[5])
        viewer.pager.setCurrentItem(0, false)
        assertTrue(
            viewer.adapter.items[viewer.pager.currentItem] is
                eu.kanade.tachiyomi.ui.reader.model.ChapterTransition.Next,
        )
        listener.onPageScrollStateChanged(ViewPager.SCROLL_STATE_SETTLING)
        listener.onPageScrollStateChanged(ViewPager.SCROLL_STATE_DRAGGING)
        selectNative(pages[5])
        val position = viewer.pager.currentItem
        listener.onPageScrollStateChanged(ViewPager.SCROLL_STATE_IDLE)
        assertEquals(position, viewer.pager.currentItem)
        assertTrue((viewer.adapter.items[position] as DisplayPage).containsPage(pages[5]))
        assertTrue(viewer.adapter.session(chapter)?.location?.pageId?.sourcePageIndex in 5..6)
    }

    @Test
    fun `gesture entering next chapter admits the selected chapter before current chapter changes`() {
        val current = gestureChapter()
        val next = chapter(9, 8001)
        viewer.setChapters(ViewerChapters(current, null, next))
        next.pages!!.forEach { viewer.adapter.updatePageDimensions(it, 100, 200) }
        val origin = viewer.adapter.items.filterIsInstance<DisplayPage>().last { it.firstPage.chapter === current }
        val selected = viewer.adapter.items.filterIsInstance<DisplayPage>().first { it.firstPage.chapter === next }
        assertSame(current, viewer.adapter.currentChapter)
        viewer.adapter.selectGesturePage(selected, forward = true, origin)
        val owner = requireNotNull(viewer.adapter.session(next))
        val targets = owner.targetUnit!!.pageIds.map { next.pages!![it.sourcePageIndex] }
        assertTrue(
            "New chapter target images must enter normal preparation immediately",
            targets.all(viewer.imagePreparation::contains),
        )
        val actualCurrent = ReflectionHelpers.getField<Set<ReaderPage>>(viewer.imagePreparation, "current")
        assertEquals(targets.toSet(), actualCurrent)
        assertSame(
            "The admission runs before onPageChange switches native chapter",
            current,
            viewer.adapter.currentChapter,
        )
    }

    private fun gestureChapter(): ReaderChapter {
        Injekt.get<ReaderPreferences>().automaticSpreadMatching().set(true)
        Injekt.get<ReaderPreferences>().pageTransitions().set(false)
        mainDispatcher.scheduler.runCurrent()
        assertTrue(viewer.config.automaticSpreadMatching)
        assertTrue(!viewer.config.usePageTransitions)
        return chapter(9).also {
            viewer.setChapters(ViewerChapters(it, null, null))
            requireNotNull(it.pages).forEach { page -> viewer.adapter.updatePageDimensions(page, 100, 200) }
        }
    }

    @Test
    fun `final native group stays on terminal transition at idle and can drag back`() {
        val chapter = gestureChapter()
        val pages = requireNotNull(chapter.pages)
        val listener = gestureListener()
        listener.onPageScrollStateChanged(ViewPager.SCROLL_STATE_DRAGGING)
        selectNative(pages.last())
        lockNativeSelection(chapter)
        viewer.pager.setCurrentItem(0, false)
        viewer.adapter.updatePageDimensions(pages[3], 300, 100)
        listener.onPageScrollStateChanged(ViewPager.SCROLL_STATE_IDLE)
        assertTrue(
            viewer.adapter.items[viewer.pager.currentItem] is
                eu.kanade.tachiyomi.ui.reader.model.ChapterTransition.Next,
        )
        assertTrue(
            viewer.adapter.items.filterIsInstance<DisplayPage>().any {
                it.visiblePages == listOf(pages[3])
            },
        )
        listener.onPageScrollStateChanged(ViewPager.SCROLL_STATE_DRAGGING)
        selectNative(pages.last())
        listener.onPageScrollStateChanged(ViewPager.SCROLL_STATE_IDLE)
        assertTrue((viewer.adapter.items[viewer.pager.currentItem] as DisplayPage).containsPage(pages.last()))
        assertTrue(viewer.adapter.session(chapter)?.location?.pageId?.sourcePageIndex in 7..8)
    }

    private fun gestureListener() =
        ReflectionHelpers.getField<ViewPager.SimpleOnPageChangeListener>(viewer, "pagerListener")

    @Test
    fun `cold reverse native selection preserves the frozen group without claiming its earlier neighbour`() {
        val chapter = gestureChapter()
        val pages = requireNotNull(chapter.pages)
        val listener = gestureListener()
        listener.onPageScrollStateChanged(ViewPager.SCROLL_STATE_DRAGGING)
        selectNative(pages[7])
        selectNative(pages[5])
        val selected = viewer.adapter.items[viewer.pager.currentItem] as DisplayPage
        assertEquals(listOf(5, 6), selected.visiblePages.map { it.index })
        val owner = requireNotNull(viewer.adapter.session(chapter))
        assertEquals(selected.visiblePages.map { it.index }, owner.targetUnit!!.pageIds.map { it.sourcePageIndex })
        listener.onPageScrollStateChanged(ViewPager.SCROLL_STATE_IDLE)
        assertEquals(listOf(5, 6), owner.targetUnit!!.pageIds.map { it.sourcePageIndex })
    }

    @Test
    fun `native previous terminal transition retains its direction during late fact publication`() {
        val chapter = gestureChapter()
        val listener = gestureListener()
        listener.onPageScrollStateChanged(ViewPager.SCROLL_STATE_DRAGGING)
        viewer.pager.setCurrentItem(viewer.adapter.items.lastIndex, false)
        assertTrue(
            viewer.adapter.items[viewer.pager.currentItem] is
                eu.kanade.tachiyomi.ui.reader.model.ChapterTransition.Prev,
        )
        viewer.adapter.updatePageDimensions(requireNotNull(chapter.pages)[3], 300, 100)
        listener.onPageScrollStateChanged(ViewPager.SCROLL_STATE_IDLE)
        assertTrue(
            viewer.adapter.items[viewer.pager.currentItem] is
                eu.kanade.tachiyomi.ui.reader.model.ChapterTransition.Prev,
        )
    }

    private fun selectNative(page: ReaderPage) {
        val position = viewer.adapter.items.indexOfFirst { it is DisplayPage && it.containsPage(page) }
        assertTrue(position >= 0)
        viewer.pager.setCurrentItem(position, false)
    }

    private fun lockNativeSelection(chapter: ReaderChapter) {
        val owner = requireNotNull(viewer.adapter.session(chapter))
        val ticket = requireNotNull(owner.captureViewport())
        assertTrue(owner.reportDrawn(ticket, requireNotNull(owner.frame(ticket)).unit.pageIds.toSet()))
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun `O7 diagnostics follow automatic and required original work`() = runBlocking {
        o7Directory = java.nio.file.Files.createTempDirectory("reader-o7-wiring").toFile()
        o7PreviousSink = eu.kanade.tachiyomi.ui.reader.diagnostics.ReaderDiagnostics.sink
        o7Sink =
            eu.kanade.tachiyomi.ui.reader.diagnostics.ReaderDiagnosticSink(directory = { requireNotNull(o7Directory) })
        eu.kanade.tachiyomi.ui.reader.diagnostics.ReaderDiagnostics.sink = o7Sink
        eu.kanade.tachiyomi.ui.reader.ReaderOpenLatencyProbe.configure(true, 1, 7, "o7-wiring", 4)
        val chapter = chapter(5)
        val encoded = readerTestPng(40, 60)
        requireNotNull(chapter.pages).forEach {
            it.stream = { encoded.inputStream() }
            it.publishHeaderFact()
            it.status = eu.kanade.tachiyomi.source.model.Page.State.Ready
        }
        var decoderCalls = 0
        var regionCalls = 0
        viewer.adapter.spreadDecoderFactory = { stream ->
            decoderCalls++
            val decoder = eu.kanade.tachiyomi.ui.reader.viewer.decodedRegionFactory(stream)
            object : eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageRegionDecoder {
                override fun decode(region: android.graphics.Rect): android.graphics.Bitmap {
                    regionCalls++
                    return decoder.decode(region)
                }
                override fun recycle() = decoder.recycle()
            }
        }
        suspend fun pump(condition: () -> Boolean) {
            val deadline = System.nanoTime() + 5_000_000_000L
            do {
                mainDispatcher.scheduler.runCurrent()
                kotlinx.coroutines.delay(5)
            } while (!condition() && System.nanoTime() < deadline)
        }
        val preferences = Injekt.get<ReaderPreferences>()
        viewer.setChapters(ViewerChapters(chapter, null, null))
        mainDispatcher.scheduler.runCurrent()
        assertEquals(0, decoderCalls)
        assertTrue(o7Lines().none { "event=sample_begin " in it })
        preferences.automaticSpreadMatching().set(true)
        pump {
            val owner = requireNotNull(viewer.adapter.session(chapter))
            owner.identity.pageIds.all { owner.originalAnalysis(it) is ReaderPageAnalysisEvidence.Computed } &&
                o7Lines().count { "event=sample_end " in it } == 5
        }
        val owner = requireNotNull(viewer.adapter.session(chapter))
        assertTrue(
            "Actual sampler outputs reach the real shared owner",
            owner.identity.pageIds.all {
                owner.originalAnalysis(it) is ReaderPageAnalysisEvidence.Computed
            },
        )
        val calls = o7Lines().filter { "event=sample_begin " in it }
        assertEquals(5, decoderCalls)
        assertEquals(5, calls.size)
        assertTrue(calls.all { "session=${activity.diagnosticSession} " in it && "chapter=7 " in it })
        assertEquals(10, regionCalls)
        assertEquals(10, o7Lines().count { "event=sample_region_end " in it && "outcome=RETURNED" in it })
        preferences.automaticSpreadMatching().set(false)
        mainDispatcher.scheduler.runCurrent()
        pump { !viewer.config.automaticSpreadMatching }
        assertFalse(
            "The real preference consumer must reach OFF before replacing display content",
            viewer.config.automaticSpreadMatching,
        )
        pump {
            ReflectionHelpers.getField<kotlinx.coroutines.Job?>(viewer.adapter, "matchingJob")?.isCompleted != false &&
                AndroidReaderSpreadSampler.optionalWork.value == 0
        }
        assertEquals(0, AndroidReaderSpreadSampler.optionalWork.value)
        // The sink is FIFO: this test-only barrier drains all spans offered by the completed job.
        eu.kanade.tachiyomi.ui.reader.diagnostics.ReaderDiagnostics.record(
            "o7_test_drain",
            session = activity.diagnosticSession,
        )
        pump { o7Lines().any { "event=o7_test_drain " in it } }
        assertTrue(o7Lines().any { "event=o7_test_drain " in it })
        fun serial(line: String) = line.substringAfter("serial=").substringBefore(' ')
        val callsBeforeDisplay = o7Lines().filter { "event=sample_begin " in it }.map(::serial).toSet()
        val decodersBeforeDisplay = decoderCalls
        val regionsBeforeDisplay = regionCalls
        val streams = ConcurrentLinkedQueue<Pair<AtomicInteger, AtomicBoolean>>()
        val page = requireNotNull(chapter.pages)[4]
        page.stream = {
            val bytes = AtomicInteger()
            val closed = AtomicBoolean()
            streams.add(bytes to closed)
            object : java.io.FilterInputStream(encoded.inputStream()) {
                override fun read(): Int = super.read().also { if (it >= 0) bytes.incrementAndGet() }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                    super.read(buffer, offset, length).also { if (it > 0) bytes.addAndGet(it) }
                override fun close() {
                    closed.set(true)
                    super.close()
                }
            }
        }
        assertEquals(ReaderPageFactState.UNKNOWN, page.pageFact.value.state)
        // Finish required evidence independently before ordinary display preparation can supply geometry itself.
        pump {
            viewer.adapter.session(chapter)?.identity?.content?.getOrNull(page.index)?.contentVersion ==
                page.encodedContentIdentity()
        }
        val requiredNeed = viewer.adapter.requirePairing(DisplayPage.Single(page))
        assertTrue(
            "The real display requirement must request missing geometry",
            requiredNeed is mihon.domain.reader.ReaderPairingCertification.NeedFacts,
        )
        pump {
            decoderCalls == decodersBeforeDisplay + 1 &&
                o7Lines().count { "event=sample_end " in it } == callsBeforeDisplay.size + 1
        }
        assertFalse(viewer.config.automaticSpreadMatching)
        assertEquals(
            "The invalidated required page reaches the real decoder even with matching OFF",
            decodersBeforeDisplay + 1,
            decoderCalls,
        )
        assertEquals(regionsBeforeDisplay + 2, regionCalls)
        val newCalls = o7Lines().filter { "event=sample_begin " in it && serial(it) !in callsBeforeDisplay }
        assertEquals(1, newCalls.size)
        val requiredEvents = o7Lines().filter { "serial=" in it && serial(it) == serial(newCalls.single()) }
        assertTrue(
            requiredEvents.all {
                "chapter=7 sourceIndex=4 " in it && "session=${activity.diagnosticSession} " in it
            },
        )
        assertEquals(1, requiredEvents.count { "event=sample_end " in it && "outcome=COMPUTED" in it })
        val regionBegins = requiredEvents.filter { "event=sample_region_begin " in it }
        val regionEnds = requiredEvents.filter { "event=sample_region_end " in it }
        assertEquals(2, regionBegins.size)
        assertEquals(2, regionEnds.size)
        assertTrue(regionEnds.all { "outcome=RETURNED" in it })
        fun ordinal(line: String) = line.substringAfter("context=").substringBefore(' ')
        assertEquals(listOf("1", "2"), regionBegins.map(::ordinal))
        assertEquals(regionBegins.map(::ordinal), regionEnds.map(::ordinal))
        val display = DualPagerPageHolder(activity, viewer, DisplayPage.Single(page))
        val displayRead = launch(mainDispatcher) {
            val sideType = DualPagerPageHolder::class.java.declaredClasses.single { it.simpleName == "Side" }
            val side = requireNotNull(sideType.enumConstants).single { it.toString() == "CENTER" }
            val setImage = DualPagerPageHolder::class.java.declaredMethods.single { it.name == "setImage" }
                .apply { isAccessible = true }
            kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
                val result = setImage.invoke(display, page, side, continuation)
                if (result === kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED) result else Unit
            }
        }
        try {
            pump { displayRead.isCompleted && o7Lines().any { "event=read_start " in it && "sourceIndex=4 " in it } }
            assertTrue(
                o7Lines().any {
                    "event=read_start " in it && "sourceIndex=4 " in it &&
                        "session=${activity.diagnosticSession} " in it
                },
            )
            assertTrue(
                "Production consumed and closed an entire encoded PNG before the Windows JNI boundary",
                streams.any {
                    it.first.get() == encoded.size && it.second.get()
                },
            )
        } finally {
            displayRead.cancel()
            mainDispatcher.scheduler.runCurrent()
            displayRead.join()
            ReflectionHelpers.callInstanceMethod<Unit>(display, "onDetachedFromWindow")
            mainDispatcher.scheduler.runCurrent()
        }
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun `replacement native viewer waits for cancelled original optional region and resumes on its real completion`() {
        replaceViewerDuringMatching = true
        try {
            `matching preference reaches actual encoded sampler shared session and native display pages`()
        } finally {
            replaceViewerDuringMatching = false
        }
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun `matching preference reaches actual encoded sampler shared session and native display pages`() = runBlocking {
        val replaceViewer = replaceViewerDuringMatching
        val chapter = chapter(19)
        val baseline = listOf(
            listOf(0), listOf(1, 2), listOf(3, 4), listOf(5, 6), listOf(7, 8),
            listOf(9, 10), listOf(11, 12), listOf(13, 14), listOf(15, 16), listOf(17, 18),
        )
        var reads = 0
        var releases = 0
        val encodedPages = mutableListOf<ByteArray>()
        val sampledPages = mutableListOf<Int>()
        requireNotNull(chapter.pages).forEach { page ->
            val bitmap = android.graphics.Bitmap.createBitmap(40, 60, android.graphics.Bitmap.Config.ARGB_8888)
            for (y in 0 until 60) {
                for (x in 0 until 40) {
                    val channel = if ((x == 2 && page.index == 2) || (x == 37 && page.index == 3)) {
                        80 + y * 2
                    } else {
                        page.index * 35
                    }
                    bitmap.setPixel(x, y, (255 shl 24) or (channel shl 16) or (80 shl 8) or 120)
                }
            }
            val bytes = java.io.ByteArrayOutputStream().also {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }.toByteArray()
            bitmap.recycle()
            encodedPages += bytes
            page.stream = { bytes.inputStream() }
            page.publishHeaderFact()
            page.status = eu.kanade.tachiyomi.source.model.Page.State.Ready
        }
        viewer.adapter.spreadDecoderFactory = { stream ->
            reads++
            val encoded = stream.readBytes()
            val pageIndex = encodedPages.indexOfFirst { it.contentEquals(encoded) }
            check(pageIndex >= 0) { "The actual decoder input must identify one encoded source page" }
            sampledPages += pageIndex
            eu.kanade.tachiyomi.ui.reader.viewer.decodedRegionFactory(encoded.inputStream()) { releases++ }
        }
        fun groups() = viewer.adapter.items.filterIsInstance<DisplayPage>().asReversed().map { item ->
            when (item) {
                is DisplayPage.Single -> listOf(item.firstPage.index)
                is DisplayPage.Double -> listOf(item.rightPage.index, item.leftPage.index)
            }
        }
        suspend fun pump(condition: () -> Boolean) {
            val deadline = System.nanoTime() + 5_000_000_000L
            do {
                mainDispatcher.scheduler.runCurrent()
                kotlinx.coroutines.delay(5)
            }
            while (!condition() && System.nanoTime() < deadline)
        }
        viewer.setChapters(ViewerChapters(chapter, null, null))
        mainDispatcher.scheduler.runCurrent()
        assertEquals(baseline, groups())
        val preferences = uy.kohesive.injekt.Injekt.get<ReaderPreferences>()
        assertTrue(!preferences.automaticSpreadMatching().get())
        assertEquals(0, reads)
        preferences.automaticSpreadMatching().set(true)
        pump {
            listOf(2, 3) in groups() && reads == 5 && reads == releases &&
                requireNotNull(viewer.adapter.session(chapter)).spreadSampleDependencies().isEmpty()
        }
        assertTrue(
            "Original sample lease must change actual native display pages",
            listOf(2, 3) in groups(),
        )
        assertEquals(reads, releases)
        assertEquals(
            "Actual decoder inputs must cover current plus four ready pages once",
            (0..4).toList(),
            sampledPages.sorted(),
        )
        assertEquals("Only the original current plus four ready window is sampled", 5, reads)
        val initialOwner = requireNotNull(viewer.adapter.session(chapter))
        (0..4).forEach { index ->
            assertTrue(
                initialOwner.originalAnalysis(
                    initialOwner.identity.pageIds[index],
                ) is ReaderPageAnalysisEvidence.Computed,
            )
        }
        assertEquals(
            ReaderPageAnalysisEvidence.NotAnalyzed,
            initialOwner.originalAnalysis(initialOwner.identity.pageIds[5]),
        )
        assertTrue(initialOwner.spreadSampleDependencies().isEmpty())
        preferences.automaticSpreadMatching().set(false)
        pump { groups() == baseline }
        assertEquals(baseline, groups())
        val page = requireNotNull(chapter.pages)[2]
        val originalStream = requireNotNull(page.stream)
        page.stream = { originalStream() }
        page.publishHeaderFact()
        mainDispatcher.scheduler.runCurrent()
        val beforeFailure = reads
        viewer.adapter.spreadDecoderFactory = { _ ->
            reads++
            object : eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageRegionDecoder {
                override fun decode(region: android.graphics.Rect): android.graphics.Bitmap = error("bad region")
                override fun recycle() {
                    releases++
                }
            }
        }
        preferences.automaticSpreadMatching().set(true)
        fun samplePassFinished() =
            ReflectionHelpers.getField<kotlinx.coroutines.Job?>(viewer.adapter, "matchingJob")?.isCompleted != false
        pump { reads == beforeFailure + 1 && reads == releases && samplePassFinished() }
        assertEquals(beforeFailure + 1, reads)
        val failedOwner = requireNotNull(viewer.adapter.session(chapter))
        val failedPageId = failedOwner.identity.pageIds[page.index]
        val failedContent = failedOwner.identity.content.single { it.pageId == failedPageId }
        assertEquals(
            ReaderPageAnalysisEvidence.RetryableFailure(failedContent, ReaderAnalysisFailure.READ),
            failedOwner.originalAnalysis(failedPageId),
        )
        repeat(3) {
            viewer.setChapters(ViewerChapters(chapter, null, null))
            mainDispatcher.scheduler.runCurrent()
        }
        assertEquals("Unrelated rebuilds must not loop a failed edge", beforeFailure + 1, reads)
        page.retryHeaderFact()
        pump { reads == beforeFailure + 2 && reads == releases && samplePassFinished() }

        assertEquals("Original image retry admits one new bounded sample", beforeFailure + 2, reads)
        assertTrue("The failed sample cannot restore an earlier positive match", listOf(2, 3) !in groups())
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val nativeActive = java.util.concurrent.atomic.AtomicInteger()
        val nativePeak = java.util.concurrent.atomic.AtomicInteger()
        viewer.adapter.spreadDecoderFactory = { stream ->
            reads++
            val encoded = stream.readBytes()
            val isOldMatchingPage = encoded.contentEquals(encodedPages[page.index])
            val original = eu.kanade.tachiyomi.ui.reader.viewer.decodedRegionFactory(encoded.inputStream()) {
                releases++
            }
            object : eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageRegionDecoder {
                override fun decode(region: android.graphics.Rect): android.graphics.Bitmap {
                    val active = nativeActive.incrementAndGet()
                    nativePeak.updateAndGet { maxOf(it, active) }
                    try {
                        if (entered.count > 0L) {
                            check(isOldMatchingPage) { "The blocked decoder must hold the old matching page 2 pixels" }
                        }
                        entered.countDown()
                        check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                        return original.decode(region)
                    } finally {
                        nativeActive.decrementAndGet()
                    }
                }
                override fun recycle() = original.recycle()
            }
        }
        try {
            val blockedContentVersion = page.captureContentFact().first
            page.retryHeaderFact()
            pump { entered.count == 0L }
            assertEquals(0L, entered.count)
            val pending = ReflectionHelpers.getField<kotlinx.coroutines.Job>(viewer.adapter, "matchingJob")
            mainDispatcher.scheduler.advanceTimeBy(15_001)
            mainDispatcher.scheduler.runCurrent()
            assertTrue(
                "Elapsed optional budget cannot cancel an executing original region or block ordinary reading",
                !pending.isCancelled && pending.children.none { it.isCancelled },
            )
            preferences.automaticSpreadMatching().set(false)
            mainDispatcher.scheduler.runCurrent()
            assertTrue(
                "Off revokes the original logical ticket but cannot finish blocked physical decode",
                pending.isCancelled,
            )
            assertTrue(!pending.isCompleted && nativeActive.get() == 1)
            // Give the new owner genuinely different pixels while the cancelled decoder still holds the old match.
            page.stream = { encodedPages[1].inputStream() }
            page.publishHeaderFact()
            val replacementContentVersion = page.captureContentFact().first
            assertNotEquals(blockedContentVersion, replacementContentVersion)
            if (replaceViewer) {
                val originalFactory = viewer.adapter.spreadDecoderFactory
                viewer.destroy()
                assertTrue(!pending.isCompleted && nativeActive.get() == 1)
                viewer = DualPageR2LPagerViewer(activity)
                viewer.config.navigationModeChangedListener = null
                viewer.adapter.spreadDecoderFactory = originalFactory
                viewer.setChapters(ViewerChapters(chapter, null, null))
                mainDispatcher.scheduler.runCurrent()
            }
            val target = requireNotNull(chapter.pages)[3]
            viewer.moveToPage(target)
            mainDispatcher.scheduler.runCurrent()
            val owner = requireNotNull(viewer.adapter.session(chapter))
            assertEquals(
                "Ordinary source navigation remains available during hung matching",
                3,
                owner.location?.pageId?.sourcePageIndex,
            )
            assertTrue(target.status == eu.kanade.tachiyomi.source.model.Page.State.Ready)
            preferences.automaticSpreadMatching().set(true)
            mainDispatcher.scheduler.runCurrent()
            assertTrue(owner.spreadSampleDependencies().isNotEmpty())
            repeat(50) {
                mainDispatcher.scheduler.runCurrent()
                kotlinx.coroutines.delay(5)
            }
            assertEquals(
                "Off then On cannot overlap a still executing original optional decoder",
                1,
                nativePeak.get(),
            )
            release.countDown()
            pump {
                pending.isCompleted && reads == releases && nativeActive.get() == 0 &&
                    owner.spreadSampleDependencies().isEmpty()
            }
            assertTrue(pending.isCompleted)
            assertEquals(reads, releases)
            assertEquals(1, nativePeak.get())
            assertTrue(
                "Original completion resumes the valid current owner without another setting or Retry",
                owner.spreadSampleDependencies().isEmpty(),
            )
            val currentPageId = owner.identity.pageIds[page.index]
            val currentAnalysis = owner.originalAnalysis(currentPageId)
            assertTrue(currentAnalysis is ReaderPageAnalysisEvidence.Computed)
            assertEquals(
                replacementContentVersion,
                (currentAnalysis as ReaderPageAnalysisEvidence.Computed).content.contentVersion,
            )
            assertTrue(
                "A cancelled old-content sample cannot publish its earlier pairing after ordinary navigation",
                listOf(2, 3) !in groups(),
            )
            preferences.automaticSpreadMatching().set(false)
            mainDispatcher.scheduler.runCurrent()
            assertEquals(baseline, groups())
        } finally {
            release.countDown()
        }
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun `interleaved real native direction facts preserve owner budget and other page tickets`() {
        val chapter = chapter(3).apply { requestedPage = 2 }
        val pages = requireNotNull(chapter.pages)
        val raw = requireNotNull(javaClass.classLoader?.getResourceAsStream("reader/exif-corners-raw.jpg")).use {
            it.readBytes()
        }
        for ((index, orientation) in listOf(6, 8, 1).withIndex()) {
            val exif = "45786966000049492a0008000000010012010300010000000100000000000000"
                .chunked(2).map { it.toInt(16).toByte() }.toByteArray().also { it[24] = orientation.toByte() }
            val bytes = raw.copyOfRange(0, 2) + byteArrayOf(-1, -31, 0, (exif.size + 2).toByte()) + exif +
                raw.copyOfRange(2, raw.size)
            pages[index].stream = { bytes.inputStream() }
        }
        val store = DualPagePairingStore()
        val original = store.ensure(chapter, pages).session
        val budget = original.factBudget
        val location = original.location
        val originalPhysical = original.identity.content.map { it.contentVersion }
        val otherRequest = original.requestFact(original.identity.pageIds[1])
        val viewport = checkNotNull(original.captureViewport())
        pages[0].publishHeaderFact()
        val first = store.ensure(chapter, pages).session
        assertTrue("First confirmed EXIF is metadata of the same physical owner", first === original)
        assertEquals(originalPhysical, first.identity.content.map { it.contentVersion })
        assertEquals(mihon.domain.reader.ReaderImageOrientation.CLOCKWISE, first.identity.content[0].sourceOrientation)
        assertTrue(first.ownsFactRequest(otherRequest))
        assertTrue(first.factBudget === budget)
        assertEquals(location, first.location)
        assertEquals(
            null,
            first.settle(
                mihon.domain.reader.ReaderDualPageSettlement(
                    viewport.identity, viewport.layoutRevision, viewport.locationSequence, viewport.unitId,
                    original.targetUnit!!.pageIds.toSet(), true,
                    mihon.domain.reader.ReaderDualPageSettlementReason.USER_NAVIGATION, 1, viewport,
                ),
                false,
                "native-exif",
            ),
        )
        pages[1].publishHeaderFact()
        val second = store.ensure(chapter, pages).session
        assertTrue(second === original)
        assertEquals(
            mihon.domain.reader.ReaderImageOrientation.COUNTER_CLOCKWISE,
            second.identity.content[1].sourceOrientation,
        )
        assertTrue(second.factBudget === budget)
        assertEquals(location, second.location)
        val retired = second.requestFact(second.identity.pageIds[1])
        pages[1].stream = { raw.inputStream() }
        val replacement = store.ensure(chapter, pages).session
        assertTrue("Actual bytes replacement still disposes the original owner", replacement !== original)
        assertTrue(!replacement.ownsFactRequest(retired))
        assertEquals(
            mihon.domain.reader.ReaderImageOrientation.NORMAL,
            replacement.identity.content[1].sourceOrientation,
        )
        assertEquals(location, replacement.location)
        replacement.close()
    }

    @Test
    fun `initial store facts remain bound to their sampled content during real stream replacement`() {
        val chapter = chapter(3).apply { requestedPage = 1 }
        val pages = requireNotNull(chapter.pages)
        val portrait = readerTestPng(30, 50)
        val wide = readerTestPng(80, 50)
        pages.forEach {
            it.stream = { portrait.inputStream() }
            it.publishHeaderFact()
        }
        val oldIdentity = pages[1].encodedContentIdentity()
        val fired = java.util.concurrent.atomic.AtomicBoolean()
        val sampled = object : AbstractList<ReaderPage>() {
            override val size = pages.size
            override fun get(index: Int): ReaderPage {
                if (index == 2 && fired.compareAndSet(false, true)) {
                    val producer = Thread {
                        pages[1].stream = { wide.inputStream() }
                        pages[1].publishHeaderFact()
                    }
                    producer.start()
                    producer.join(5000)
                    assertTrue(!producer.isAlive)
                }
                return pages[index]
            }
        }
        val store = DualPagePairingStore()
        val owner = store.ensure(chapter, sampled).session
        assertTrue(fired.get())
        assertTrue(pages[1].encodedContentIdentity() != oldIdentity)
        assertEquals(oldIdentity, owner.identity.content[1].contentVersion)
        assertEquals(
            "A replaced physical snapshot cannot seed either its old portrait or the new wide fact",
            mihon.domain.reader.ReaderPageFactState.UNKNOWN,
            owner.pageFact(owner.identity.pageIds[1])?.state ?: mihon.domain.reader.ReaderPageFactState.UNKNOWN,
        )
        val original = owner.requestFact(owner.identity.pageIds[1])
        val replacement = store.ensure(chapter, sampled).session
        assertTrue(replacement !== owner)
        assertEquals(pages[1].encodedContentIdentity(), replacement.identity.content[1].contentVersion)
        assertEquals(mihon.domain.reader.ReaderDualPageSingleReason.SPREAD, replacement.targetUnit!!.singleReason)
        assertTrue(replacement.targetUnit!!.certain)
        assertTrue(!replacement.acceptFact(original, mihon.domain.reader.ReaderPageFact.dimensions(30, 50)))
        replacement.close()
    }

    @Test
    fun `cold unknown pages retain each source in provisional native slots`() {
        val chapter = chapter(5)
        viewer.setChapters(ViewerChapters(chapter, null, null))
        val units = viewer.adapter.items.filterIsInstance<DisplayPage>()
        assertEquals(3, units.size)
        assertEquals((0..4).toSet(), units.flatMap { it.visiblePages.map(ReaderPage::index) }.toSet())
        assertEquals(5, units.sumOf { it.visiblePages.size })
        val target = requireNotNull(viewer.adapter.session(chapter)).targetUnit!!
        assertEquals(0, target.pageIds.single().sourcePageIndex)
        assertEquals(mihon.domain.reader.ReaderPortraitSingleSlot.LEFT, target.slots.single().position)
    }

    @Test
    fun `direct target seventeen survives late wide ten and regrouping`() {
        val chapter = chapter(17).apply { requestedPage = 16 }
        viewer.setChapters(ViewerChapters(chapter, null, null))
        val pages = requireNotNull(chapter.pages)
        pages.forEach { page ->
            viewer.adapter.updatePageDimensions(
                page,
                if (page.index == 9) 1500 else 1127,
                if (page.index == 9) 1067 else 1600,
            )
        }
        val selected = viewer.adapter.items[viewer.pager.currentItem] as DisplayPage
        assertTrue("Explicit page 17 must remain in the actual selected native unit", selected.containsPage(pages[16]))
        val groups = viewer.adapter.items.filterIsInstance<DisplayPage>().asReversed()
            .map { it.visiblePages.map(ReaderPage::index) }
        assertEquals(listOf(listOf(14, 15), listOf(16)), groups.takeLast(2))
    }

    @Test
    fun `unrendered native single selection cannot record a read`() {
        viewer.setChapters(ViewerChapters(chapter(1), null, null))
        assertTrue("Selection before image rendering must only update the UI anchor", activity.selectedPages.isEmpty())
    }

    @Test
    fun `same native page content replacement creates a new unknown owner without losing logical target`() =
        runBlocking {
            val reads = java.util.concurrent.atomic.AtomicInteger()
            viewer.adapter.spreadDecoderFactory = { stream ->
                reads.incrementAndGet()
                eu.kanade.tachiyomi.ui.reader.viewer.decodedRegionFactory(stream)
            }

            fun createPng(width: Int, height: Int): ByteArray {
                val bitmap = android.graphics.Bitmap.createBitmap(
                    width,
                    height,
                    android.graphics.Bitmap.Config.ARGB_8888,
                )
                return java.io.ByteArrayOutputStream().also {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    bitmap.recycle()
                }.toByteArray()
            }
            val portraitPng = createPng(100, 200)
            val replacementPortraitPng = createPng(120, 240)
            val chapter = chapter(3, id = 7).apply { requestedPage = 1 }
            val pages = requireNotNull(chapter.pages)
            for (i in 0..2) {
                pages[i].stream = { portraitPng.inputStream() }
                pages[i].encodedPageRef = mihon.domain.reader.session.EncodedPageRef("page-$i-v1")
                pages[i].status = eu.kanade.tachiyomi.source.model.Page.State.Ready
                // Normal header publishing runs initially for all pages to establish baseline PORTRAIT dimensions
                pages[i].publishHeaderFact()
            }

            suspend fun pump(condition: () -> Boolean) {
                val deadline = System.nanoTime() + 10_000_000_000L
                do {
                    mainDispatcher.scheduler.runCurrent()
                    kotlinx.coroutines.delay(5)
                } while (!condition() && System.nanoTime() < deadline)
            }

            // This case tests content replacement with matching already enabled, rather than
            // changing the matching setting while the initial chapter is being prepared.
            val preferences = Injekt.get<ReaderPreferences>()
            preferences.automaticSpreadMatching().set(true)
            pump { viewer.config.automaticSpreadMatching }
            assertTrue(
                "The real preference must enable matching before the chapter starts",
                viewer.config.automaticSpreadMatching,
            )

            viewer.setChapters(ViewerChapters(chapter, null, null))
            viewer.adapter.selectPage(pages[1])
            mainDispatcher.scheduler.runCurrent()

            val initialSession = requireNotNull(viewer.adapter.session(chapter))
            val page1Id = initialSession.identity.pageIds[1]
            val targetPageId = initialSession.identity.pageIds[2]

            // Wait for initial original analysis to be Computed via fact budget
            pump {
                val session = viewer.adapter.session(chapter)
                reads.get() > 0 && session?.originalAnalysis(targetPageId) is ReaderPageAnalysisEvidence.Computed
            }
            val previous = requireNotNull(viewer.adapter.session(chapter))
            val initialAnalysis = previous.originalAnalysis(targetPageId)
            assertEquals(1, previous.location?.pageId?.sourcePageIndex)
            assertTrue(
                "Initial owner must produce Computed original analysis via fact budget: " +
                    "automatic=${viewer.config.automaticSpreadMatching} reads=${reads.get()} " +
                    "location=${previous.location} closed=${previous.isClosed} " +
                    "proof=${previous.certifyPairing()} " +
                    "analysis=${previous.identity.pageIds.map { previous.originalAnalysis(it) }} " +
                    "required=${previous.factBudget.requiredPages()} optional=${previous.factBudget.optionalPages()} " +
                    "job=${ReflectionHelpers.getField<kotlinx.coroutines.Job?>(viewer.adapter, "matchingJob")} " +
                    "requested=${ReflectionHelpers.getField<Boolean>(viewer.adapter, "matchingRequested")} " +
                    "work=${eu.kanade.tachiyomi.ui.reader.viewer.AndroidReaderSpreadSampler.optionalWork.value} " +
                    "foreground=${eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView.foregroundWork.value} " +
                    "pressure=${activity.optionalWorkUnderMemoryPressure.value}",
                initialAnalysis is ReaderPageAnalysisEvidence.Computed,
            )
            val initialContentVersion = (initialAnalysis as ReaderPageAnalysisEvidence.Computed).content.contentVersion
            assertEquals(
                mihon.domain.reader.ReaderPageFactState.PORTRAIT,
                previous.pageFact(targetPageId)?.state,
            )
            assertTrue(
                "Initial budget must have attempted target page",
                previous.factBudget.holds(targetPageId) ||
                    targetPageId in previous.factBudget.requiredPages() ||
                    targetPageId in previous.factBudget.optionalPages(),
            )
            val readsBefore = reads.get()

            // Replace content on page 2 with new PORTRAIT dimensions requiring re-analysis without navigating away (staying at page 1)
            pages[2].stream = { replacementPortraitPng.inputStream() }
            pages[2].encodedPageRef = mihon.domain.reader.session.EncodedPageRef("replacement-content")
            viewer.setChapters(ViewerChapters(chapter, null, null))
            mainDispatcher.scheduler.runCurrent()
            val current = requireNotNull(viewer.adapter.session(chapter))
            assertNotSame(
                "Changing actual content on the same native page must invalidate the old owner",
                previous,
                current,
            )
            assertEquals(1, current.location?.pageId?.sourcePageIndex)
            assertEquals(
                mihon.domain.reader.ReaderPageFactState.UNKNOWN,
                current.pageFact(targetPageId)?.state ?: mihon.domain.reader.ReaderPageFactState.UNKNOWN,
            )
            assertTrue(
                "Changed page original analysis must be reset to NotAnalyzed on new owner",
                current.originalAnalysis(targetPageId) is ReaderPageAnalysisEvidence.NotAnalyzed,
            )
            assertEquals(
                "Untouched page 1 fact must remain stable",
                mihon.domain.reader.ReaderPageFactState.PORTRAIT,
                current.pageFact(page1Id)?.state,
            )

            // B wiring: Re-admit replacement content via real adapter matching and fact budget without manual publication or navigation
            pump {
                val s = viewer.adapter.session(chapter)
                reads.get() > readsBefore && s?.originalAnalysis(targetPageId) is ReaderPageAnalysisEvidence.Computed
            }
            val newAnalysis = current.originalAnalysis(targetPageId)
            assertTrue(
                "Replaced content must produce new Computed analysis via new budget shell",
                newAnalysis is ReaderPageAnalysisEvidence.Computed,
            )
            val newContentVersion = (newAnalysis as ReaderPageAnalysisEvidence.Computed).content.contentVersion
            assertNotEquals(
                initialContentVersion,
                newContentVersion,
            )
            assertEquals(
                "Replaced content must publish its new PORTRAIT fact on the new owner",
                mihon.domain.reader.ReaderPageFactState.PORTRAIT,
                current.pageFact(targetPageId)?.state,
            )
            assertTrue("Sampling reads must increment via real read permit", reads.get() > readsBefore)

            // Untouched pages remain stable
            assertEquals(
                "Untouched page 1 fact must remain stable",
                mihon.domain.reader.ReaderPageFactState.PORTRAIT,
                current.pageFact(page1Id)?.state,
            )
            // Ordinary adapter items and display units remain valid
            assertTrue(viewer.adapter.items.isNotEmpty())
            preferences.automaticSpreadMatching().set(false)
        }

    @Test
    fun `explicit capacity hold blocks analysis admission until slot released`() =
        runTest(mainDispatcher) {
            val reads = java.util.concurrent.atomic.AtomicInteger()
            viewer.adapter.spreadDecoderFactory = { stream ->
                reads.incrementAndGet()
                eu.kanade.tachiyomi.ui.reader.viewer.decodedRegionFactory(stream)
            }

            fun createPng(width: Int, height: Int): ByteArray {
                val bitmap = android.graphics.Bitmap.createBitmap(
                    width,
                    height,
                    android.graphics.Bitmap.Config.ARGB_8888,
                )
                return java.io.ByteArrayOutputStream().also {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    bitmap.recycle()
                }.toByteArray()
            }
            val portraitPng = createPng(100, 200)
            val spreadPng = createPng(300, 150)
            val chapter = chapter(3, id = 8).apply { requestedPage = 1 }
            val pages = requireNotNull(chapter.pages)
            for (i in 0..2) {
                pages[i].stream = { portraitPng.inputStream() }
                pages[i].encodedPageRef = mihon.domain.reader.session.EncodedPageRef("page-$i-v1")
                pages[i].status = eu.kanade.tachiyomi.source.model.Page.State.Ready
                pages[i].publishHeaderFact()
            }
            viewer.setChapters(ViewerChapters(chapter, null, null))
            viewer.adapter.selectPage(pages[1])
            mainDispatcher.scheduler.runCurrent()

            suspend fun pump(condition: () -> Boolean) {
                val deadline = System.nanoTime() + 10_000_000_000L
                do {
                    mainDispatcher.scheduler.runCurrent()
                    kotlinx.coroutines.delay(5)
                } while (!condition() && System.nanoTime() < deadline)
            }

            val preferences = Injekt.get<ReaderPreferences>()
            preferences.automaticSpreadMatching().set(true)

            val initialSession = requireNotNull(viewer.adapter.session(chapter))
            val targetPageId = initialSession.identity.pageIds[2]
            pump {
                val session = viewer.adapter.session(chapter)
                reads.get() > 0 && session?.originalAnalysis(targetPageId) is ReaderPageAnalysisEvidence.Computed
            }
            val previous = requireNotNull(viewer.adapter.session(chapter))
            assertTrue(previous.originalAnalysis(targetPageId) is ReaderPageAnalysisEvidence.Computed)

            // Replace content on page 2
            pages[2].stream = { spreadPng.inputStream() }
            pages[2].encodedPageRef = mihon.domain.reader.session.EncodedPageRef("replacement-content")
            viewer.setChapters(ViewerChapters(chapter, null, null))
            mainDispatcher.scheduler.runCurrent()
            val current = requireNotNull(viewer.adapter.session(chapter))
            val currentTargetPageId = current.identity.pageIds[2]

            // Explicit capacity hold: reserve slot to verify capacity limit blocks admission
            current.factBudget.reserve(listOf(currentTargetPageId), 0L, required = true)
            try {
                // Verify matching with explicit capacity hold
                for (step in 0..20) {
                    mainDispatcher.scheduler.runCurrent()
                    kotlinx.coroutines.delay(10)
                }

                // Verify that holding capacity blocks new analysis from being admitted and computed
                assertTrue(
                    "Explicit capacity hold must block new analysis admission",
                    current.originalAnalysis(currentTargetPageId) !is ReaderPageAnalysisEvidence.Computed,
                )
            } finally {
                current.factBudget.complete(currentTargetPageId, 0L, cacheHit = true)
                preferences.automaticSpreadMatching().set(false)
                mainDispatcher.scheduler.runCurrent()
            }
        }

    @Test
    fun `first encoded materialization preserves restored owner and committed manual boundaries`() {
        val chapter = chapter(5)
        val store = DualPagePairingStore(requiresRestoration = true)
        store.install(
            chapter,
            requireNotNull(chapter.pages),
            mihon.domain.reader.ChapterPairingSnapshot(mihon.domain.reader.ChapterPairingRecord(1, 5, setOf(3)), 7),
        )
        viewer.destroy()
        viewer = DualPageR2LPagerViewer(activity, store)
        viewer.config.navigationModeChangedListener = null
        viewer.setChapters(ViewerChapters(chapter, null, null))
        val restored = requireNotNull(viewer.adapter.session(chapter))
        val page = requireNotNull(chapter.pages)[4]
        page.encodedPageRef = mihon.domain.reader.session.EncodedPageRef("initial-materialization")
        page.stream = { byteArrayOf(1, 2, 3).inputStream() }
        viewer.setChapters(ViewerChapters(chapter, null, null))
        val materialized = requireNotNull(viewer.adapter.session(chapter))
        org.junit.Assert.assertSame("First content binding must preserve the restored owner", restored, materialized)
        assertEquals(setOf(3), materialized.pairingState.committedForcedSinglePages)
        assertEquals(mihon.domain.reader.ReaderPairingPersistenceStatus.READY, materialized.pairingState.status)
    }

    @Test
    fun `native viewer recreation retains pending intent and revokes the detached viewport scope`() {
        val chapter = chapter(5).apply { requestedPage = 4 }
        val store = DualPagePairingStore()
        viewer.destroy()
        viewer = DualPageR2LPagerViewer(activity, store)
        viewer.config.navigationModeChangedListener = null
        viewer.setChapters(ViewerChapters(chapter, null, null))
        val retained = requireNotNull(viewer.adapter.session(chapter))
        val before = retained.location
        val original = requireNotNull(retained.captureViewport())
        viewer.destroy()
        viewer = DualPageR2LPagerViewer(activity, store)
        viewer.config.navigationModeChangedListener = null
        viewer.setChapters(ViewerChapters(chapter, null, null))
        org.junit.Assert.assertSame(retained, viewer.adapter.session(chapter))
        assertEquals("Restoring native presentation is not an explicit navigation", before, retained.location)
        val restored = requireNotNull(retained.captureViewport())
        assertEquals(original.identity, restored.identity)
        assertTrue(
            "Native close/reopen changes presentation mode and revokes its layout scope",
            restored.layoutRevision > original.layoutRevision,
        )
        assertEquals(original.unitId, restored.unitId)
        assertTrue("The detached native viewport capability must be revoked", restored !== original)
        assertEquals(
            null,
            retained.settle(
                mihon.domain.reader.ReaderDualPageSettlement(
                    original.identity, original.layoutRevision, original.locationSequence, original.unitId,
                    retained.targetUnit!!.pageIds.toSet(), true,
                    mihon.domain.reader.ReaderDualPageSettlementReason.USER_NAVIGATION,
                    original.locationSequence, original,
                ),
                false,
                "detached-native-recreation",
            ),
        )
    }

    @Test
    fun `detached native holder revokes its original capability on the retained owner`() {
        val chapter = chapter(1)
        viewer.setChapters(ViewerChapters(chapter, null, null))
        val retained = requireNotNull(viewer.adapter.session(chapter))
        val display = viewer.adapter.items.filterIsInstance<DisplayPage>().single()
        val holder = DualPagerPageHolder(activity, viewer, display)
        val original = requireNotNull(retained.captureViewport())
        val detach = DualPagerPageHolder::class.java.getDeclaredMethod("onDetachedFromWindow")
            .apply { isAccessible = true }
        detach.invoke(holder)
        val successor = requireNotNull(retained.captureViewport())
        assertEquals(original.layoutRevision, successor.layoutRevision)
        assertEquals(original.locationSequence, successor.locationSequence)
        assertEquals(original.unitId, successor.unitId)
        assertNotSame("A detached holder cannot retain its original settled capability", original, successor)
    }

    @Test
    fun `queued old fact cannot mint a new ticket while collector acquires replacement identity`() {
        val chapter = chapter(3).apply { requestedPage = 1 }
        val page = requireNotNull(chapter.pages)[1]
        val bitmap = android.graphics.Bitmap.createBitmap(30, 50, android.graphics.Bitmap.Config.ARGB_8888)
        val encoded = java.io.ByteArrayOutputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            it.toByteArray()
        }
        bitmap.recycle()
        page.stream = { encoded.inputStream() }
        page.publishHeaderFact()
        requireNotNull(chapter.pages)[2].apply {
            stream = { encoded.inputStream() }
            publishHeaderFact()
        }
        viewer.setChapters(ViewerChapters(chapter, null, null))
        val originalOwner = requireNotNull(viewer.adapter.session(chapter))
        val originalRequest = originalOwner.requestFact(originalOwner.identity.pageIds[1])
        val lock = ReflectionHelpers.getField<Any>(page, "contentLock")
        val entered = java.util.concurrent.CountDownLatch(1)
        val collectorThread = Thread.currentThread()
        val replacement = Thread {
            synchronized(lock) {
                entered.countDown()
                val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
                while (collectorThread.state != Thread.State.BLOCKED && System.nanoTime() < deadline) {
                    Thread.sleep(1)
                }
                page.stream = { byteArrayOf(1, 2, 3).inputStream() }
            }
        }
        replacement.start()
        try {
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            mainDispatcher.scheduler.runCurrent()
            replacement.join(5000)
            val owner = requireNotNull(viewer.adapter.session(chapter))
            assertEquals(
                "The old captured portrait fact must not be accepted by the replacement owner",
                mihon.domain.reader.ReaderPageFactState.UNKNOWN,
                owner.pageFact(owner.identity.pageIds[1])?.state ?: mihon.domain.reader.ReaderPageFactState.UNKNOWN,
            )
            assertTrue(!owner.acceptFact(originalRequest, mihon.domain.reader.ReaderPageFact.dimensions(30, 50)))
        } finally {
            replacement.join(5000)
        }
    }

    @Test
    fun `explicit native target supplements at most two missing facts and deadline`() = runTest(mainDispatcher) {
        val chapter = chapter(17).apply { requestedPage = 2 }
        val pages = requireNotNull(chapter.pages)
        val extra = mutableSetOf<Int>()
        val cancelled = mutableSetOf<Int>()
        var visibleActive = false
        val loader = object : eu.kanade.tachiyomi.ui.reader.loader.PageLoader() {
            override var isLocal = false
            override suspend fun getPages() = pages
            override suspend fun loadPage(page: ReaderPage) {
                visibleActive = true
                try {
                    awaitCancellation()
                } finally {
                    visibleActive = false
                }
            }
            override suspend fun loadFactPage(
                page: ReaderPage,
                permit: mihon.domain.reader.ReaderDualPageFactReadPermit,
            ) {
                extra += page.index
                try {
                    awaitCancellation()
                } finally {
                    cancelled += page.index
                }
            }
        }
        chapter.pageLoader = loader
        val normal = launch { loader.loadPage(pages[2]) }
        try {
            viewer.setChapters(ViewerChapters(chapter, null, null))
            mainDispatcher.scheduler.runCurrent()
            assertTrue(visibleActive && normal.isActive)
            assertTrue(requireNotNull(viewer.adapter.session(chapter)).factDependencies().isNotEmpty())
            assertEquals("Only the original normal loader may materialize encoded pages", emptySet<Int>(), extra)
            assertTrue(visibleActive)
            mainDispatcher.scheduler.advanceTimeBy(15_001)
            mainDispatcher.scheduler.runCurrent()
            assertEquals(extra, cancelled)
            assertTrue("Optional deadline cannot cancel the normal visible consumer", visibleActive && normal.isActive)
            assertEquals(2, viewer.adapter.session(chapter)?.location?.pageId?.sourcePageIndex)
        } finally {
            normal.cancelAndJoin()
        }
    }

    @Test
    fun `pending original pairing restoration retains the dual owner and does not gate target presentation`() =
        runTest(mainDispatcher) {
            val chapter = chapter(8).apply { requestedPage = 2 }
            val pages = requireNotNull(chapter.pages)
            val store = DualPagePairingStore(requiresRestoration = true)
            val restoredViewer = DualPageR2LPagerViewer(activity, store)
            restoredViewer.config.navigationModeChangedListener = null
            try {
                restoredViewer.setChapters(ViewerChapters(chapter, null, null))
                mainDispatcher.scheduler.runCurrent()
                val available = requireNotNull(restoredViewer.adapter.session(chapter)) {
                    "A cold target must not wait for old persisted pairing I/O"
                }
                assertTrue(available.pairingState.revision == null)
                assertTrue(available.adjustPairing() == null)
                val original = requireNotNull(store.beginRestore(chapter, pages))
                restoredViewer.setChapters(ViewerChapters(chapter, null, null))
                mainDispatcher.scheduler.runCurrent()
                assertTrue(
                    "Pending original I/O must retain the signed owner, pages and load request",
                    restoredViewer.adapter.session(chapter) === original.owner,
                )
                assertTrue(
                    "Normal dual target presentation must be available before old pairing I/O completes",
                    restoredViewer.adapter.items.filterIsInstance<DisplayPage>().any { it.containsPage(pages[2]) },
                )
                assertEquals(
                    "Reading availability does not declare an unknown persisted record ready",
                    mihon.domain.reader.ReaderPairingPersistenceStatus.RESTORING,
                    original.owner.pairingState.status,
                )
                assertTrue(original.owner.adjustPairing() == null)
            } finally {
                restoredViewer.destroy()
            }
        }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun `original HTTP physical work defers matching and actual completion resumes pending samples`() =
        verifyOriginalHttpPhysicalWork(switchLoader = false)

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun `replaced original HTTP loader remains foreground busy until its physical work actually exits`() =
        verifyOriginalHttpPhysicalWork(switchLoader = true)

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun `foreground arriving during original region pauses sampling and idle before close resumes after completion`() =
        verifyOriginalHttpPhysicalWork(switchLoader = false, pauseDuringSample = true)

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun `system pressure arriving during original region pauses before next unit and real recovery waits for close`() =
        verifyOriginalHttpPhysicalWork(
            switchLoader = false,
            pauseDuringSample = true,
            memoryPressureDuringSample = true,
        )

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun `continuous foreground busy keeps matching rounds bounded and idle resumes automatically`() =
        verifyOriginalHttpPhysicalWork(
            switchLoader = false,
            pauseDuringSample = false,
            assertBoundedRounds = true,
        )

    private fun verifyOriginalHttpPhysicalWork(
        switchLoader: Boolean,
        pauseDuringSample: Boolean = false,
        memoryPressureDuringSample: Boolean = false,
        assertBoundedRounds: Boolean = false,
    ) = runBlocking<Unit> {
        val directory = java.nio.file.Files.createTempDirectory("reader-matching-http-").toFile()
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val reads = java.util.concurrent.atomic.AtomicInteger()
        val decodes = java.util.concurrent.atomic.AtomicInteger()
        val firstRegion = java.util.concurrent.atomic.AtomicBoolean(true)
        val firstRecycle = java.util.concurrent.atomic.AtomicBoolean(true)
        val regionEntered = java.util.concurrent.CountDownLatch(1)
        val regionRelease = java.util.concurrent.CountDownLatch(1)
        val closeEntered = java.util.concurrent.CountDownLatch(1)
        val closeRelease = java.util.concurrent.CountDownLatch(1)
        val server = mockwebserver3.MockWebServer().apply {
            dispatcher = object : mockwebserver3.Dispatcher() {
                override fun dispatch(request: mockwebserver3.RecordedRequest): mockwebserver3.MockResponse {
                    if (request.url.encodedPath == "/chapter") {
                        return mockwebserver3.MockResponse(body = "pages:17")
                    }
                    val index = request.url.encodedPath.substringAfterLast('/').toInt() - 1
                    val bitmap = android.graphics.Bitmap.createBitmap(
                        40,
                        60,
                        android.graphics.Bitmap.Config.ARGB_8888,
                    )
                    for (y in 0 until 60) {
                        for (x in 0 until 40) {
                            val channel = if ((x == 2 && index == 2) || (x == 37 && index == 3)) {
                                80 + y * 2
                            } else {
                                index * 35
                            }
                            bitmap.setPixel(x, y, (255 shl 24) or (channel shl 16) or (80 shl 8) or 120)
                        }
                    }
                    val bytes = java.io.ByteArrayOutputStream().also {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }.toByteArray()
                    bitmap.recycle()
                    return mockwebserver3.MockResponse.Builder().body(okio.Buffer().write(bytes)).build()
                }
            }
            start()
        }
        val cache = mockk<eu.kanade.tachiyomi.data.cache.ChapterCache>(relaxed = true)
        fun encodedFile(url: String) = java.io.File(directory, "${url.substringAfterLast('/')}.png")
        every { cache.getPageListFromCache(any()) } throws java.io.IOException("normal cold cache")
        every { cache.getImageFile(any()) } answers { encodedFile(firstArg()) }
        every { cache.isImageInCache(any()) } answers { encodedFile(firstArg()).length() > 0 }
        every { cache.putImageToCache(any(), any()) } answers {
            val file = encodedFile(firstArg())
            secondArg<okhttp3.Response>().use { response ->
                file.outputStream().use { output -> response.body.byteStream().use { it.copyTo(output) } }
            }
            true
        }
        val chapter = ReaderChapter(Chapter.create().copy(id = 7, mangaId = 1, url = "/chapter"))
        val executor = object : mihon.domain.reader.materialize.ReaderMaterializeExecutor by
        mihon.domain.reader.materialize.CanonicalReaderMaterializeExecutor {
            override suspend fun materializePage(
                request: mihon.domain.reader.materialize.ReaderPageFetchRequest,
                port: mihon.domain.reader.materialize.ReaderPageFetchPort,
                forceRefresh: Boolean,
                publish: (mihon.domain.reader.materialize.ReaderPageMaterializeEvent) -> Boolean,
            ): mihon.domain.reader.materialize.ReaderPageMaterializeResult {
                if (request.pageId.sourcePageIndex == 5) {
                    entered.complete(Unit)
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { release.await() }
                }
                return mihon.domain.reader.materialize.CanonicalReaderMaterializeExecutor.materializePage(
                    request,
                    port,
                    forceRefresh,
                    publish,
                )
            }
        }
        val loader = eu.kanade.tachiyomi.ui.reader.loader.HttpPageLoader(
            chapter,
            TestHttpSource(server),
            cache,
            materializeExecutor = executor,
        )
        val generation = chapter.openPageListForTest()
        assertTrue(chapter.installPageLoader(generation, loader))
        val port = eu.kanade.tachiyomi.ui.reader.loader.AndroidReaderChapterContentPort(chapter, loader)
        val runner = ReflectionHelpers.getField<mihon.domain.reader.scheduler.ReaderPageMaterializeRunner<*>>(
            loader,
            "pageRunner",
        )
        suspend fun pump(message: String, condition: () -> Boolean) {
            kotlinx.coroutines.withTimeout(10_000) {
                while (!condition()) {
                    mainDispatcher.scheduler.runCurrent()
                    kotlinx.coroutines.delay(5)
                }
            }
            assertTrue(message, condition())
        }
        var normal: kotlinx.coroutines.Job? = null
        var replacementLoader: eu.kanade.tachiyomi.ui.reader.loader.HttpPageLoader? = null
        try {
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                port.loadChapterContent(
                    mihon.domain.reader.materialize.ReaderChapterContentRequest(
                        mihon.domain.reader.session.ReaderChapterId(7),
                        generation,
                    ),
                )
            }
            chapter.completePageListForTest(generation, port.materializedPages)
            val pages = requireNotNull(chapter.pages)
            loader.onPageSelected(pages[0])
            pump("The original current plus four normal HTTP requests must be actually ready") {
                pages.take(5).all { it.status == eu.kanade.tachiyomi.source.model.Page.State.Ready } &&
                    runner.physicalWorkCount.value == 0
            }
            viewer.adapter.spreadDecoderFactory = { stream ->
                reads.incrementAndGet()
                val original = eu.kanade.tachiyomi.ui.reader.viewer.decodedRegionFactory(stream)
                if (!pauseDuringSample) {
                    original
                } else {
                    object : eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageRegionDecoder {
                        override fun decode(region: android.graphics.Rect): android.graphics.Bitmap {
                            decodes.incrementAndGet()
                            if (firstRegion.getAndSet(false)) {
                                regionEntered.countDown()
                                check(regionRelease.await(10, java.util.concurrent.TimeUnit.SECONDS))
                            }
                            return original.decode(region)
                        }

                        override fun recycle() {
                            try {
                                if (firstRecycle.getAndSet(false)) {
                                    closeEntered.countDown()
                                    check(closeRelease.await(10, java.util.concurrent.TimeUnit.SECONDS))
                                }
                            } finally {
                                original.recycle()
                            }
                        }
                    }
                }
            }
            viewer.setChapters(ViewerChapters(chapter, null, null))
            mainDispatcher.scheduler.runCurrent()
            var activeChapter = chapter
            var owner = requireNotNull(viewer.adapter.session(chapter))
            if (pauseDuringSample) {
                Injekt.get<ReaderPreferences>().automaticSpreadMatching().set(true)
                pump("The original encoded region must enter before new ordinary foreground work") {
                    regionEntered.count == 0L
                }
                assertEquals(1, reads.get())
                assertTrue(owner.spreadSampleDependencies().isNotEmpty())
            }
            if (memoryPressureDuringSample) {
                activity.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
                assertTrue(activity.optionalWorkUnderMemoryPressure.value)
                assertTrue(!loader.hasForegroundWork)
                regionRelease.countDown()
                pump("Actual region cleanup must begin while the system pressure signal remains active") {
                    closeEntered.count == 0L
                }
                assertEquals("Pressure arrival stops before a second bounded region", 1, decodes.get())
                val publisher = ReflectionHelpers.getField<kotlinx.coroutines.Job>(viewer.adapter, "matchingJob")
                assertTrue(!publisher.isCompleted)
                assertTrue(owner.spreadSampleDependencies().isNotEmpty())
                activity.onWindowFocusChanged(true)
                assertTrue(!activity.optionalWorkUnderMemoryPressure.value)
                mainDispatcher.scheduler.runCurrent()
                assertTrue(!publisher.isCompleted)
                assertEquals("Real recovery cannot overlap the still closing original optional job", 1, reads.get())
                closeRelease.countDown()
                pump("Only actual close completion resumes pending quality samples without Retry or navigation") {
                    owner.spreadSampleDependencies().isEmpty()
                }
                assertTrue(viewer.adapter.session(chapter) === owner)
                assertEquals(0, owner.location?.pageId?.sourcePageIndex)
                return@runBlocking
            }
            normal = launch(Dispatchers.IO) { loader.loadPage(pages[5]) }
            pump("A real normal materializer must enter the held original executor") { entered.isCompleted }
            assertEquals(1, runner.physicalWorkCount.value)
            assertTrue(loader.pageRunnerSnapshot().activeRequestKeys.any { it.pageIndex == 5 })
            loader.onPageSelected(pages[0])
            val scheduler = ReflectionHelpers.getField<mihon.domain.reader.scheduler.ReaderRequestScheduler>(
                loader,
                "requestScheduler",
            )
            pump("Public source navigation must cancel logical work while real old execution stays held") {
                scheduler.snapshot().pendingRequests.isEmpty() && scheduler.snapshot().activeRequests.isEmpty() &&
                    loader.pageRunnerSnapshot().activeRequestKeys.isEmpty()
            }
            assertEquals(1, runner.physicalWorkCount.value)
            assertTrue(
                "No logical request remains; busy must consume actual cancelled physical work",
                loader.hasForegroundWork,
            )
            if (switchLoader) {
                loader.recycle()
                assertTrue(loader.isRecycled)
                assertEquals(1, runner.physicalWorkCount.value)
                activeChapter = ReaderChapter(Chapter.create().copy(id = 8, mangaId = 1, url = "/chapter"))
                val nextLoader = eu.kanade.tachiyomi.ui.reader.loader.HttpPageLoader(
                    activeChapter,
                    TestHttpSource(server),
                    cache,
                )
                replacementLoader = nextLoader
                val nextGeneration = activeChapter.openPageListForTest()
                assertTrue(activeChapter.installPageLoader(nextGeneration, nextLoader))
                val nextPort = eu.kanade.tachiyomi.ui.reader.loader.AndroidReaderChapterContentPort(
                    activeChapter,
                    nextLoader,
                )
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    nextPort.loadChapterContent(
                        mihon.domain.reader.materialize.ReaderChapterContentRequest(
                            mihon.domain.reader.session.ReaderChapterId(8),
                            nextGeneration,
                        ),
                    )
                }
                activeChapter.completePageListForTest(nextGeneration, nextPort.materializedPages)
                nextLoader.onPageSelected(requireNotNull(activeChapter.pages)[0])
                pump("The actual replacement loader must be Ready and idle independently of old physical work") {
                    requireNotNull(activeChapter.pages).take(5).all {
                        it.status == eu.kanade.tachiyomi.source.model.Page.State.Ready
                    } && !nextLoader.hasForegroundWork
                }
                viewer.setChapters(ViewerChapters(activeChapter, null, null))
                mainDispatcher.scheduler.runCurrent()
                owner = requireNotNull(viewer.adapter.session(activeChapter))
            }
            assertEquals(0, owner.location?.pageId?.sourcePageIndex)
            if (!pauseDuringSample) Injekt.get<ReaderPreferences>().automaticSpreadMatching().set(true)
            mainDispatcher.scheduler.runCurrent()
            assertTrue(owner.spreadSampleDependencies().isNotEmpty())
            if (pauseDuringSample) {
                regionRelease.countDown()
                pump("Actual region cleanup must start while the ordinary original task stays busy") {
                    closeEntered.count == 0L
                }
                assertEquals("Foreground arrival stops before a second bounded region", 1, decodes.get())
                val publisher = ReflectionHelpers.getField<kotlinx.coroutines.Job>(viewer.adapter, "matchingJob")
                assertTrue(!publisher.isCompleted)
                release.complete(Unit)
                pump("Normal physical work must actually exit while the original region close is still held") {
                    runner.physicalWorkCount.value == 0 && !loader.hasForegroundWork
                }
                repeat(10) {
                    mainDispatcher.scheduler.runCurrent()
                    kotlinx.coroutines.delay(5)
                }
                assertTrue(!publisher.isCompleted)
                assertEquals(1, reads.get())
                assertTrue(owner.spreadSampleDependencies().isNotEmpty())
                closeRelease.countDown()
            } else {
                repeat(50) {
                    mainDispatcher.scheduler.runCurrent()
                    kotlinx.coroutines.delay(5)
                }
                if (assertBoundedRounds) {
                    val rounds = ReflectionHelpers.getField<Int>(viewer.adapter, "matchingRounds")
                    assertTrue(
                        "Matching rounds must stay bounded while foreground is continuously busy, but was $rounds",
                        rounds <= 1,
                    )
                }
                assertEquals(
                    "Optional matching must not decode while original normal physical work is busy",
                    0,
                    reads.get(),
                )
                release.complete(Unit)
            }
            pump("Original physical completion must resume remaining quality samples without Retry or navigation") {
                runner.physicalWorkCount.value == 0 && reads.get() > 0 &&
                    owner.spreadSampleDependencies().isEmpty()
            }
            assertTrue(viewer.adapter.session(activeChapter) === owner)
            assertEquals(0, owner.location?.pageId?.sourcePageIndex)
        } finally {
            regionRelease.countDown()
            closeRelease.countDown()
            release.complete(Unit)
            normal?.cancelAndJoin()
            loader.recycle()
            replacementLoader?.recycle()
            server.close()
            directory.listFiles()?.forEach { it.delete() }
            directory.delete()
        }
    }

    @Test
    fun `adapter matching setting change reconfigures session and refreshes budget`() {
        val readerPreferences = Injekt.get<ReaderPreferences>()
        readerPreferences.automaticSpreadMatching().set(false)
        mainDispatcher.scheduler.runCurrent()
        val chapter = chapter(5)
        viewer.config.usePageTransitions = false
        viewer.setChapters(ViewerChapters(chapter, null, null))
        val session = requireNotNull(viewer.adapter.session(chapter))

        val initialBudget = session.factBudget
        val targetPage = session.identity.pageIds[1]
        assertEquals(listOf(targetPage), initialBudget.reserve(listOf(targetPage), 1000L, required = true))
        val permit = ReaderDualPageFactReadPermit(initialBudget, targetPage, { 1000L }, ownerKey = 1L)
        assertFalse(permit.exhausted)
        assertTrue(initialBudget.canRead(targetPage, 1000L))

        val resourcePage = session.identity.pageIds[2]
        val resourceContent = session.identity.content[2]
        val req = requireNotNull(session.requestOriginalAnalysis(resourcePage))
        assertTrue(session.failOriginalAnalysis(req, ReaderAnalysisFailure.RESOURCE))
        assertEquals(
            ReaderPageAnalysisEvidence.RetryableFailure(resourceContent, ReaderAnalysisFailure.RESOURCE),
            session.originalAnalysis(resourcePage),
        )

        val oldFactRevision = session.factWorkRevision

        // 1. Same configuration must remain completely idempotent
        viewer.adapter.matchingSettingChanged()
        assertSame("Same configuration must keep existing budget", initialBudget, session.factBudget)
        assertEquals("Same configuration must be idempotent", oldFactRevision, session.factWorkRevision)
        assertEquals(
            ReaderPageAnalysisEvidence.RetryableFailure(resourceContent, ReaderAnalysisFailure.RESOURCE),
            session.originalAnalysis(resourcePage),
        )
        assertFalse(permit.exhausted)

        // 2. Setting change reconfigures session, refreshes budget and clears resource failure
        readerPreferences.automaticSpreadMatching().set(true)
        mainDispatcher.scheduler.runCurrent()
        val newBudget = session.factBudget
        assertNotSame("Config change must replace fact budget", initialBudget, newBudget)
        assertTrue("Old permit must be exhausted", permit.exhausted)
        assertFalse("Old budget must be closed", initialBudget.canRead(targetPage, 1000L))
        assertTrue("Fact revision must increment", session.factWorkRevision > oldFactRevision)
        assertEquals(
            ReaderPageAnalysisEvidence.NotAnalyzed,
            session.originalAnalysis(resourcePage),
        )

        // 3. Applying identical matching setting again remains idempotent
        val nextFactRevision = session.factWorkRevision
        viewer.adapter.matchingSettingChanged()
        assertSame("Identical matching setting must not replace budget again", newBudget, session.factBudget)
        assertEquals(nextFactRevision, session.factWorkRevision)
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun `actual required original sample records resource failure when production clock budget expires`() =
        runBlocking {
            val chapter = chapter(4)
            val pages = requireNotNull(chapter.pages)
            val targetColor = android.graphics.Color.rgb(2, 80, 120)
            pages.forEach { page ->
                val bitmap = android.graphics.Bitmap.createBitmap(40, 60, android.graphics.Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.rgb(page.index + 1, 80, 120))
                val bytes = java.io.ByteArrayOutputStream().also {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }.toByteArray()
                bitmap.recycle()
                page.stream = { bytes.inputStream() }
                page.status = eu.kanade.tachiyomi.source.model.Page.State.Ready
                page.publishHeaderFact()
            }
            viewer.config.usePageTransitions = false
            viewer.setChapters(ViewerChapters(chapter, null, null))
            viewer.adapter.selectPage(pages[0])
            mainDispatcher.scheduler.runCurrent()
            val owner = requireNotNull(viewer.adapter.session(chapter))
            val targetPageId = owner.identity.pageIds[1]
            val targetContent = owner.identity.content.single { it.pageId == targetPageId }
            val location = owner.location
            val reads = java.util.concurrent.atomic.AtomicInteger()
            val releases = java.util.concurrent.atomic.AtomicInteger()
            val elapsedBudget = java.util.concurrent.atomic.AtomicBoolean()
            val targetRegions = java.util.concurrent.atomic.AtomicInteger()
            viewer.adapter.spreadDecoderFactory = { stream ->
                reads.incrementAndGet()
                val original = eu.kanade.tachiyomi.ui.reader.viewer.decodedRegionFactory(stream) {
                    releases.incrementAndGet()
                }
                object : eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageRegionDecoder {
                    override fun decode(region: android.graphics.Rect): android.graphics.Bitmap {
                        val decoded = original.decode(region)
                        if (decoded.getPixel(0, 0) == targetColor && targetRegions.incrementAndGet() == 1) {
                            val remaining = owner.factBudget.remainingWaitMillis(android.os.SystemClock.uptimeMillis())
                            check(remaining > 0) { "Actual required sampling must begin inside the production budget" }
                            check(targetPageId in owner.factBudget.requiredPages())
                            check(!activity.optionalWorkUnderMemoryPressure.value)
                            check(chapter.pageLoader?.hasForegroundWork != true)
                            check(eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView.foregroundWork.value == 0)
                            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(remaining))
                            elapsedBudget.set(true)
                        }
                        return decoded
                    }

                    override fun recycle() = original.recycle()
                }
            }
            suspend fun pump(condition: () -> Boolean) {
                // This wall-clock guard only bounds the test; the decoder advances the production clock above.
                kotlinx.coroutines.withTimeout(10_000) {
                    while (!condition()) {
                        mainDispatcher.scheduler.runCurrent()
                        kotlinx.coroutines.delay(5)
                    }
                }
            }
            Injekt.get<ReaderPreferences>().automaticSpreadMatching().set(true)
            fun samplePassFinished() =
                ReflectionHelpers.getField<kotlinx.coroutines.Job?>(viewer.adapter, "matchingJob")?.isCompleted != false
            pump { elapsedBudget.get() && reads.get() == releases.get() && samplePassFinished() }
            assertSame(owner, viewer.adapter.session(chapter))
            assertEquals(location, owner.location)
            assertEquals(0L, owner.factBudget.remainingWaitMillis(android.os.SystemClock.uptimeMillis()))
            assertEquals(
                ReaderPageAnalysisEvidence.RetryableFailure(targetContent, ReaderAnalysisFailure.RESOURCE),
                owner.originalAnalysis(targetPageId),
            )
            assertEquals(
                "The actual target page region must consume the remaining production budget",
                1,
                targetRegions.get(),
            )
            val completedReads = reads.get()
            assertTrue(completedReads > 0)
            assertEquals(completedReads, releases.get())
            repeat(3) {
                viewer.setChapters(ViewerChapters(chapter, null, null))
                mainDispatcher.scheduler.runCurrent()
            }
            pump { samplePassFinished() }
            assertEquals(
                "Unrelated rebuilds must not restart a budget-exhausted original sample",
                completedReads,
                reads.get(),
            )
            assertEquals(completedReads, releases.get())
            assertEquals(
                ReaderPageAnalysisEvidence.RetryableFailure(targetContent, ReaderAnalysisFailure.RESOURCE),
                owner.originalAnalysis(targetPageId),
            )
        }

    @Test
    fun `memory pressure during required sample defers work and auto-resumes on relief without retry`() =
        runBlocking<Unit> {
            val chapter = chapter(4)
            val pages = requireNotNull(chapter.pages)
            val bitmap = android.graphics.Bitmap.createBitmap(40, 60, android.graphics.Bitmap.Config.ARGB_8888)
            val bytes = java.io.ByteArrayOutputStream().also {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }.toByteArray()
            bitmap.recycle()

            pages.forEach { page ->
                page.stream = { bytes.inputStream() }
                page.status = eu.kanade.tachiyomi.source.model.Page.State.Ready
                page.publishHeaderFact()
            }

            val readerPreferences = Injekt.get<ReaderPreferences>()
            readerPreferences.automaticSpreadMatching().set(true)
            viewer.config.usePageTransitions = false

            val regionEntered = java.util.concurrent.CountDownLatch(1)
            val regionRelease = java.util.concurrent.CountDownLatch(1)
            var pressureTriggered = false
            viewer.adapter.spreadDecoderFactory = { stream ->
                val original = eu.kanade.tachiyomi.ui.reader.viewer.decodedRegionFactory(stream)
                object : eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageRegionDecoder {
                    override fun decode(region: android.graphics.Rect): android.graphics.Bitmap {
                        if (!pressureTriggered) {
                            pressureTriggered = true
                            activity.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
                            regionEntered.countDown()
                            check(regionRelease.await(10, java.util.concurrent.TimeUnit.SECONDS))
                        }
                        return original.decode(region)
                    }

                    override fun recycle() {
                        original.recycle()
                    }
                }
            }

            suspend fun pump(phase: String, condition: () -> Boolean) {
                try {
                    kotlinx.coroutines.withTimeout(10_000) {
                        while (!condition()) {
                            mainDispatcher.scheduler.runCurrent()
                            kotlinx.coroutines.delay(5)
                        }
                    }
                } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
                    val owner = viewer.adapter.session(chapter)
                    val job = ReflectionHelpers.getField<kotlinx.coroutines.Job?>(viewer.adapter, "matchingJob")
                    throw AssertionError(
                        "$phase: entered=${regionEntered.count} release=${regionRelease.count} " +
                            "pressure=${activity.optionalWorkUnderMemoryPressure.value} job=$job " +
                            "optional=${AndroidReaderSpreadSampler.optionalWork.value} " +
                            "foreground=${ReaderPageImageView.foregroundWork.value} " +
                            "location=${owner?.location} proof=${owner?.certifyPairing()} " +
                            "analysis=${owner?.identity?.pageIds?.map { owner.originalAnalysis(it) }}",
                        error,
                    )
                }
            }

            viewer.setChapters(ViewerChapters(chapter, null, null))
            viewer.adapter.selectPage(pages[0])
            val session = requireNotNull(viewer.adapter.session(chapter))
            val targetPage = session.identity.pageIds[1]

            pump("Required native region must enter before applying the pause assertions") { regionEntered.count == 0L }
            assertTrue(activity.optionalWorkUnderMemoryPressure.value)
            regionRelease.countDown()

            // Wait until the matchingJob finishes the paused round
            pump("Paused matching round must finish its real work") {
                val job = ReflectionHelpers.getField<kotlinx.coroutines.Job?>(viewer.adapter, "matchingJob")
                job == null || job.isCompleted
            }

            assertNotEquals(
                "Deferred page must NOT be recorded as RetryableFailure during temporary memory pressure",
                ReaderAnalysisFailure.RESOURCE,
                (session.originalAnalysis(targetPage) as? ReaderPageAnalysisEvidence.RetryableFailure)?.reason,
            )
            assertEquals(ReaderPageAnalysisEvidence.NotAnalyzed, session.originalAnalysis(targetPage))

            // Relieve memory pressure
            activity.onWindowFocusChanged(true)
            assertFalse(activity.optionalWorkUnderMemoryPressure.value)

            pump("Memory relief must resume the existing required sample") {
                session.originalAnalysis(targetPage) is ReaderPageAnalysisEvidence.Computed
            }

            assertTrue(
                "Page must complete analysis automatically after memory pressure relief",
                session.originalAnalysis(targetPage) is ReaderPageAnalysisEvidence.Computed,
            )
        }

    private fun chapter(count: Int, id: Long = 7): ReaderChapter = ReaderChapter(
        Chapter.create().copy(
            id = id,
            mangaId =
            1,
        ),
    ).also { chapter ->
        chapter.publishLoadedPageListForTest(
            List(count) { index ->
                ReaderPage(index).apply {
                    this.chapter =
                        chapter
                }
            },
        )
    }
}
