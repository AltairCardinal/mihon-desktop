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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class SharedDualPageAndroidWiringTest {
    private val mainDispatcher = StandardTestDispatcher()
    private lateinit var previousInjekt: InjektScope
    private lateinit var viewer: DualPageR2LPagerViewer
    private lateinit var activity: RecordingReaderActivity
    private lateinit var model: ReaderViewModel

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
        if (::viewer.isInitialized) viewer.destroy()
        Dispatchers.resetMain()
        Injekt = previousInjekt
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
    fun `late native facts publish only after the final gesture anchor and deferred chapter window`() {
        val chapter = gestureChapter()
        val pages = requireNotNull(chapter.pages)
        val listener = gestureListener()
        listener.onPageScrollStateChanged(ViewPager.SCROLL_STATE_DRAGGING)
        selectNative(pages[1])
        val frozen = viewer.adapter.items.toList()
        viewer.adapter.updatePageDimensions(pages[3], 300, 100)
        assertEquals(frozen, viewer.adapter.items)
        selectNative(pages[5])
        viewer.setChapters(ViewerChapters(chapter, null, chapter(3)))
        listener.onPageScrollStateChanged(ViewPager.SCROLL_STATE_IDLE)
        assertEquals(5, viewer.adapter.session(chapter)?.location?.pageId?.sourcePageIndex)
        assertTrue((viewer.adapter.items[viewer.pager.currentItem] as DisplayPage).containsPage(pages[5]))
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
            page.stream = { bytes.inputStream() }
            page.publishHeaderFact()
            page.status = eu.kanade.tachiyomi.source.model.Page.State.Ready
        }
        viewer.adapter.spreadDecoderFactory = { stream ->
            reads++
            eu.kanade.tachiyomi.ui.reader.viewer.decodedRegionFactory(stream) { releases++ }
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
            listOf(2, 3) in groups() && reads == 4 && reads == releases &&
                requireNotNull(viewer.adapter.session(chapter)).spreadSampleDependencies().isEmpty()
        }
        assertTrue(
            "Original sample lease must change actual native display pages",
            listOf(2, 3) in groups(),
        )
        assertEquals(reads, releases)
        assertEquals("Only the original current plus four ready window is sampled", 4, reads)
        assertTrue(requireNotNull(viewer.adapter.session(chapter)).spreadSampleDependencies().isEmpty())
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
            val original = eu.kanade.tachiyomi.ui.reader.viewer.decodedRegionFactory(stream) { releases++ }
            object : eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageRegionDecoder {
                override fun decode(region: android.graphics.Rect): android.graphics.Bitmap {
                    val active = nativeActive.incrementAndGet()
                    nativePeak.updateAndGet { maxOf(it, active) }
                    try {
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
    fun `same native page content replacement creates a new unknown owner without losing logical target`() {
        val chapter = chapter(5).apply { requestedPage = 4 }
        requireNotNull(chapter.pages)[4].apply {
            stream = { byteArrayOf(4, 5, 6).inputStream() }
            encodedPageRef = mihon.domain.reader.session.EncodedPageRef("original-content")
        }
        viewer.setChapters(ViewerChapters(chapter, null, null))
        requireNotNull(chapter.pages).forEach { viewer.adapter.updatePageDimensions(it, 100, 200) }
        val previous = requireNotNull(viewer.adapter.session(chapter))
        val pages = requireNotNull(chapter.pages)
        pages[4].stream = { byteArrayOf(1, 2, 3).inputStream() }
        pages[4].encodedPageRef = mihon.domain.reader.session.EncodedPageRef("replacement-content")
        viewer.setChapters(ViewerChapters(chapter, null, null))
        val current = requireNotNull(viewer.adapter.session(chapter))
        assertNotSame(
            "Changing actual content on the same native page must invalidate the old owner",
            previous,
            current,
        )
        assertEquals(4, current.location?.pageId?.sourcePageIndex)
        assertEquals(
            mihon.domain.reader.ReaderPageFactState.UNKNOWN,
            current.pageFact(current.identity.pageIds[4])?.state ?: mihon.domain.reader.ReaderPageFactState.UNKNOWN,
        )
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

    private fun verifyOriginalHttpPhysicalWork(
        switchLoader: Boolean,
        pauseDuringSample: Boolean = false,
        memoryPressureDuringSample: Boolean = false,
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

    private fun chapter(count: Int): ReaderChapter = ReaderChapter(
        Chapter.create().copy(
            id = 7,
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
