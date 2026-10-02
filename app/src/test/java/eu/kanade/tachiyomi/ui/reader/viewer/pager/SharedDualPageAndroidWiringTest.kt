package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.app.Application
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.databinding.ReaderActivityBinding
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.model.publishLoadedPageListForTest
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.viewer.RecordingReaderActivity
import io.mockk.every
import io.mockk.mockk
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
        val model = mockk<ReaderViewModel>(relaxed = true)
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

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun `matching preference reaches actual encoded sampler shared session and native display pages`() =
        runBlocking {
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
            pump { listOf(2, 3) in groups() && reads == 18 && reads == releases }
            assertTrue(
                "Original sample lease must change actual native display pages",
                listOf(2, 3) in groups(),
            )
            assertEquals(reads, releases)
            assertEquals("All seventeen adjacent edges must be scored beyond the sixteen sample cache", 18, reads)
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
            viewer.adapter.spreadDecoderFactory = { stream ->
                reads++
                val original = eu.kanade.tachiyomi.ui.reader.viewer.decodedRegionFactory(stream) { releases++ }
                object : eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageRegionDecoder {
                    override fun decode(region: android.graphics.Rect): android.graphics.Bitmap {
                        entered.countDown()
                        check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                        return original.decode(region)
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
                    "The active original-region wait must be cancelled at the deadline before native completion",
                    pending.isCancelled || pending.children.any { it.isCancelled },
                )
                release.countDown()
                pump { pending.isCompleted && reads == releases }
                assertTrue(pending.isCompleted)
                assertEquals(reads, releases)
                assertTrue(
                    "A native region finishing after the active deadline cannot publish matching",
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
            listOf(1),
            owner.targetUnit!!.pageIds.map { it.sourcePageIndex },
        )
        assertTrue(!owner.targetUnit!!.certain)
        assertEquals(mihon.domain.reader.ReaderPortraitSingleSlot.FULL, owner.targetUnit!!.slots.single().position)
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
    fun `cold unknown pages remain native centered fallback singles`() {
        val chapter = chapter(5)
        viewer.setChapters(ViewerChapters(chapter, null, null))
        val units = viewer.adapter.items.filterIsInstance<DisplayPage>()
        assertEquals(5, units.size)
        assertTrue(
            units.all {
                it is DisplayPage.Single &&
                    it.slot == mihon.domain.reader.ReaderPortraitSingleSlot.FULL
            },
        )
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
        assertEquals(mihon.domain.reader.ReaderDualPageSingleReason.UNRESOLVED, current.targetUnit?.singleReason)
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
                mihon.domain.reader.ReaderDualPageSingleReason.UNRESOLVED,
                owner.targetUnit?.singleReason,
            )
        } finally {
            replacement.join(5000)
        }
    }

    @Test
    fun `explicit native target supplements at most two missing facts and deadline`() = runTest(mainDispatcher) {
        val chapter = chapter(17).apply { requestedPage = 16 }
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
        val normal = launch { loader.loadPage(pages[16]) }
        try {
            viewer.setChapters(ViewerChapters(chapter, null, null))
            mainDispatcher.scheduler.runCurrent()
            assertEquals(setOf(15, 14), extra)
            assertTrue(visibleActive)
            mainDispatcher.scheduler.advanceTimeBy(15_001)
            mainDispatcher.scheduler.runCurrent()
            assertEquals(extra, cancelled)
            assertTrue("Optional deadline cannot cancel the normal visible consumer", visibleActive && normal.isActive)
            assertEquals(16, viewer.adapter.session(chapter)?.location?.pageId?.sourcePageIndex)
        } finally {
            normal.cancelAndJoin()
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
