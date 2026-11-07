package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.view.children
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.davemorrissey.labs.subscaleview.decoder.Decoder
import com.davemorrissey.labs.subscaleview.provider.InputProvider
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.model.publishLoadedPageListForTest
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import io.mockk.every
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mihon.domain.reader.ReaderDualPageFactLimits
import mihon.domain.reader.ReaderImageOrientation
import mihon.domain.reader.ReaderPortraitSingleSlot
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import tachiyomi.domain.chapter.model.Chapter
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.RandomAccessFile
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.time.Duration.Companion.seconds

/** Native pixels and gestures exercise the original holder, viewport, image and decode ownership. */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
@Config(sdk = [35], manifest = Config.NONE, qualifiers = "w480dp-h1040dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DualPageViewportZoomTest {
    private val fixture = DualPagerPageHolderLayoutTest()
    private val mounted = mutableListOf<DualPagerPageHolder>()
    private var displayDecoderInstalled = false
    private val displayDecoderPixels = java.util.concurrent.ConcurrentHashMap<Any, Bitmap>()
    private lateinit var mainScheduler: kotlinx.coroutines.test.TestCoroutineScheduler

    private fun useOriginalDisplayDecoder() {
        if (displayDecoderInstalled) return
        displayDecoderInstalled = true
        mockkConstructor(Decoder::class)
        every { anyConstructed<Decoder>().init(any(), any()) } answers {
            val source = secondArg<InputProvider>().openStream()!!.use { BitmapFactory.decodeStream(it)!! }
            displayDecoderPixels[self] = source
            Point(source.width, source.height)
        }
        every { anyConstructed<Decoder>().decodeRegion(any(), any()) } answers {
            val region = firstArg<Rect>()
            val sample = secondArg<Int>()
            Bitmap.createBitmap(
                (region.width() + sample - 1) / sample,
                (region.height() + sample - 1) / sample,
                Bitmap.Config.ARGB_8888,
            ).also { tile ->
                Canvas(tile).drawBitmap(
                    displayDecoderPixels[self]!!,
                    region,
                    Rect(0, 0, tile.width, tile.height),
                    Paint(Paint.FILTER_BITMAP_FLAG),
                )
            }
        }
        every { anyConstructed<Decoder>().isReady() } answers { displayDecoderPixels[self]?.isRecycled == false }
        every { anyConstructed<Decoder>().recycle() } answers {
            displayDecoderPixels.remove(self)?.recycle()
            Unit
        }
    }

    @Test
    fun sixSourceAdmissionStartsCurrentThenNaturalForwardAndReverseNeighbours() = runTest {
        normalAdmission(emptySet())
    }

    @Test
    fun oneCurrentImageErrorStillAdmitsTheSuccessfulCurrentAndFourNeighbours() = runTest {
        normalAdmission(setOf(5))
    }

    @Test
    fun bothCurrentImageErrorsStillReleaseTheOrderedNeighbourQueue() = runTest {
        normalAdmission(setOf(5, 6))
    }

    @Test
    fun missingCurrentStreamStillReleasesTheOrderedNeighbourQueue() = runTest {
        normalAdmission(emptySet(), missingStream = true)
    }

    private fun kotlinx.coroutines.test.TestScope.normalAdmission(errors: Set<Int>, missingStream: Boolean = false) {
        useOriginalDisplayDecoder()
        useEncodedOriginalProducer(viewer())
        val viewer = viewer()
        uy.kohesive.injekt.Injekt.get<eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences>()
            .automaticSpreadMatching().set(false)
        runCurrent()
        val chapter = originalChapter(74103, 16, 6)
        val pages = chapter.pages!!
        errors.forEach { pages[it].status = Page.State.Error(java.io.IOException("Controlled current image error")) }
        if (missingStream) pages[5].stream = null
        val starts = java.util.concurrent.CopyOnWriteArrayList<Int>()
        chapter.pageLoader = object : eu.kanade.tachiyomi.ui.reader.loader.PageLoader() {
            override var isLocal = true
            override suspend fun getPages() = pages
            override suspend fun loadPage(page: ReaderPage) {
                starts += page.index
            }
        }
        viewer.setChapters(ViewerChapters(chapter, null, null))
        viewer.moveToPage(pages[6])
        invokeFixture("measure", viewer.pager, 1400, 1000)
        runCurrent()
        awaitProduction {
            viewer.pager.children.filterIsInstance<DualPagerPageHolder>().forEach { holder ->
                descendants(holder).filterIsInstance<ReaderPageImageView>().filter { it.childCount > 0 }
                    .forEach { it.onImageLoaded() }
            }
            starts.size >= 6
        }
        assertEquals(setOf(5, 6), starts.take(2).toSet())
        assertEquals(listOf(7, 8, 4, 3), starts.drop(2).take(4))
        assertEquals("Only the six-source window may enter normal preparation", 6, starts.size)
        assertTrue(viewer.adapter.session(chapter)!!.displayedHistory.isEmpty())
    }

    @Test
    fun preparedShiftedNeighboursSurviveRealForwardAndReverseKeys() = runTest {
        preparedNeighboursSurviveRealKeys(shifted = true)
    }

    @Test
    fun nullableProposalUpgradesToItsIssuedFrameWithoutRebindingNativePixels() = runTest {
        useOriginalDisplayDecoder()
        val source = subject(ReaderPortraitSingleSlot.RIGHT, provisionalPair = true, actualPairFrame = true)
        val published = source.holder.displayPage as DisplayPage.Double
        val proposal = DisplayPage.Double(published.rightPage, published.leftPage)
        val holder = DualPagerPageHolder(viewer().activity, viewer(), proposal).also { mounted += it }
        val issued = requireNotNull(holder.presentationScope).second.frameId
        invokeFixture("detach", source.holder)
        load(holder, published.rightPage, "RIGHT")
        invokeFixture("measure", holder, 1400, 1000)
        val image = ReflectionHelpers.getField<ReaderPageImageView>(holder, "rightHolder")
        paintReaderImage(image, source.encoded)
        val native = image.getChildAt(0)
        val generations = ReflectionHelpers.getField<Map<ReaderPage, Long>>(holder, "decodeGenerations")
        val generation = generations.getValue(published.rightPage)
        assertTrue(viewer().adapter.getItemPosition(holder) != androidx.viewpager.widget.PagerAdapter.POSITION_NONE)
        assertEquals(issued, holder.displayPage.frameId)
        assertSame(native, image.getChildAt(0))
        val updatedGenerations = ReflectionHelpers.getField<Map<ReaderPage, Long>>(holder, "decodeGenerations")
        assertEquals(generation, updatedGenerations.getValue(published.rightPage))
    }

    @Test
    fun preparedSamePhaseProposalSurvivesPublishedFrameUpgrade() = runTest {
        preparedNeighboursSurviveRealKeys(shifted = false)
    }

    private suspend fun kotlinx.coroutines.test.TestScope.preparedNeighboursSurviveRealKeys(shifted: Boolean) {
        useOriginalDisplayDecoder()
        val viewer = viewer()
        useEncodedOriginalProducer(viewer)
        uy.kohesive.injekt.Injekt.get<eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences>()
            .automaticSpreadMatching().set(shifted)
        runCurrent()
        val encoded = List(16) { index ->
            val bitmap = Bitmap.createBitmap(400, 800, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.RED)
                if (shifted && index == 2) {
                    Canvas(this).drawRect(0f, 0f, 40f, 800f, Paint().apply { color = Color.WHITE })
                }
            }
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                .toByteArray().also { bitmap.recycle() }
        }
        val chapter = ReaderChapter(Chapter.create().copy(id = if (shifted) 74101 else 74102, mangaId = 1))
            .apply { requestedPage = 6 }
        val pages = List(16) { index ->
            ReaderPage(index, stream = { encoded[index].inputStream() }).apply {
                this.chapter = chapter
                status = Page.State.Ready
                publishHeaderFact()
            }
        }
        chapter.publishLoadedPageListForTest(pages)
        chapter.pageLoader = eu.kanade.tachiyomi.ui.reader.loader.DownloadPageLoader(
            chapter,
            tachiyomi.domain.manga.model.Manga.create(),
            io.mockk.mockk(relaxed = true),
            io.mockk.mockk(relaxed = true),
            io.mockk.mockk(relaxed = true),
        )
        viewer.pager.pageTurnRippleEnabled = false
        viewer.setChapters(ViewerChapters(chapter, null, null))
        awaitCertified(viewer, chapter, 6)
        viewer.moveToPage(pages[6])
        val owner = requireNotNull(viewer.adapter.session(chapter))
        val expectedStart = if (shifted) 6 else 5
        assertEquals(listOf(expectedStart, expectedStart + 1), owner.targetUnit!!.pageIds.map { it.sourcePageIndex })
        invokeFixture("measure", viewer.pager, 1400, 1000)
        settleOriginalPager(viewer)
        fun holders() = viewer.pager.children.filterIsInstance<DualPagerPageHolder>().toList()
        fun ready(holder: DualPagerPageHolder): Boolean {
            val bound = ReflectionHelpers.getField<Map<ReaderPage, Any>>(holder, "decodedImages")
            val needed = holder.displayPage.visiblePages.filter { viewer.imagePreparation.contains(it) }
            if (needed.isEmpty()) return true
            var complete = needed.all { it in bound }
            for (field in listOf("leftHolder", "rightHolder")) {
                val image = ReflectionHelpers.getField<ReaderPageImageView?>(holder, field) ?: continue
                if (image.childCount == 0) {
                    complete = false
                    continue
                }
                val native = image.getChildAt(0) as SubsamplingScaleImageView
                if (ReflectionHelpers.getField<Bitmap?>(native, "bitmap") == null) {
                    // Supply only the unavailable host decoder boundary. No offscreen draw is fabricated.
                    val page = if (field == "leftHolder" && holder.displayPage is DisplayPage.Double) {
                        (holder.displayPage as DisplayPage.Double).leftPage
                    } else {
                        holder.displayPage.firstPage
                    }
                    if (page !in needed) continue
                    val bytes = encoded[page.index]
                    native.setImage(
                        com.davemorrissey.labs.subscaleview.ImageSource.bitmap(
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size),
                        ),
                    )
                }
                image.onImageLoaded()
            }
            return complete
        }
        awaitProduction({
            "Mounted neighbours must really prepare: target=${owner.targetUnit!!.pageIds.map { it.sourcePageIndex }} " +
                "priority=${ReflectionHelpers.getField<List<ReaderPage>>(viewer.imagePreparation, "priority").map {
                    it.index
                }} " +
                "active=${ReflectionHelpers.getField<Map<Any, ReaderPage>>(
                    viewer.imagePreparation,
                    "active",
                ).values.map {
                    it.index
                }} " +
                "frames=${owner.presentationFrames.map {
                    it.frameId to it.unit.pageIds.map { p -> p.sourcePageIndex }
                }} " +
                "holders=${holders().map { h ->
                    h.displayPage.visiblePages.map { it.index } to
                        ReflectionHelpers.getField<Map<ReaderPage, Any>>(h, "decodedImages").keys.map { it.index }
                }}"
        }) {
            holders().isNotEmpty() && holders().all(::ready)
        }
        fun drawCurrent() {
            invokeFixture("measure", viewer.pager, 1400, 1000)
            val current = holders().single { it.displayPage.frameId == owner.targetFrameId }
            for (page in current.displayPage.visiblePages) {
                val sides = current.displayPage as DisplayPage.Double
                val field = if (sides.leftPage === page) "leftHolder" else "rightHolder"
                paintReaderImage(ReflectionHelpers.getField(current, field), encoded[page.index])
            }
            invokeFixture("measure", viewer.pager, 1400, 1000)
            settleOriginalPager(viewer)
        }
        drawCurrent()
        val targetBefore = owner.location
        assertEquals(
            setOf(expectedStart, expectedStart + 1),
            owner.displayedHistory.flatMap { it.drawnPageIds }
                .map { it.sourcePageIndex }.toSet(),
        )
        val nextPage = expectedStart + 2
        val prepared = holders().single { it.displayPage.containsPage(pages[nextPage]) }
        val nativeBefore = descendants(prepared).filterIsInstance<ReaderPageImageView>().map {
            it.getChildAt(0)
        }.toList()
        assertEquals(targetBefore, owner.location)
        val directory = kotlin.io.path.createTempDirectory("dual-prepared-normal-work").toFile()
        val sink = eu.kanade.tachiyomi.ui.reader.diagnostics.ReaderDiagnosticSink({ directory })
        val previousSink = eu.kanade.tachiyomi.ui.reader.diagnostics.ReaderDiagnostics.sink
        eu.kanade.tachiyomi.ui.reader.diagnostics.ReaderDiagnostics.sink = sink
        try {
            for ((key, target) in listOf(
                android.view.KeyEvent.KEYCODE_DPAD_LEFT to nextPage,
                android.view.KeyEvent.KEYCODE_DPAD_LEFT to nextPage + 2,
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT to nextPage,
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT to expectedStart,
            )) {
                val engineRunsBefore = owner.certificationMetrics.engineRuns
                viewer.handleKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, key))
                invokeFixture("measure", viewer.pager, 1400, 1000)
                settleOriginalPager(viewer)
                assertEquals(target, owner.targetUnit!!.pageIds.minOf { it.sourcePageIndex })
                if (target == nextPage) {
                    val actual = holders().single { it.displayPage.frameId == owner.targetFrameId }
                    assertSame("A prepared neighbour must survive native adapter reconciliation", prepared, actual)
                    assertEquals(
                        nativeBefore,
                        descendants(actual).filterIsInstance<ReaderPageImageView>()
                            .map { it.getChildAt(0) }.toList(),
                    )
                }
                awaitProduction({
                    "After real input $key target=$target expected prepared holders, actual=" + holders().map {
                        "${it.displayPage}:" +
                            ReflectionHelpers.getField<Map<ReaderPage, Any>>(it, "decodedImages").keys.map { it.index }
                    }
                }) { holders().all(::ready) }
                drawCurrent()
                println(
                    "DUAL_PREFETCH_CERTIFY shifted=$shifted target=$target key=$key engineDelta=" +
                        (owner.certificationMetrics.engineRuns - engineRunsBefore),
                )
            }
            assertEquals(false, ReflectionHelpers.getField<Boolean>(viewer.adapter, "lastNavigationForward"))
            val beforeCancel = owner.location
            val down = SystemClock.uptimeMillis()
            send(viewer.pager, down, down, MotionEvent.ACTION_DOWN, 700f, 500f)
            send(viewer.pager, down, down + 30, MotionEvent.ACTION_MOVE, 680f, 500f)
            send(viewer.pager, down, down + 60, MotionEvent.ACTION_CANCEL, 680f, 500f)
            settleOriginalPager(viewer)
            assertEquals(beforeCancel, owner.location)
            assertEquals(false, ReflectionHelpers.getField<Boolean>(viewer.adapter, "lastNavigationForward"))
        } finally {
            eu.kanade.tachiyomi.ui.reader.diagnostics.ReaderDiagnostics.sink = previousSink
            sink.close()
        }
        val lines = directory.listFiles()!!.flatMap { it.readLines() }
        for (event in listOf("loader_start", "read_start", "decode_start")) {
            assertEquals(
                "Already prepared source must have zero additional normal $event on entry or revisit",
                0,
                lines.count { it.contains("sourceIndex=$nextPage ") && it.contains("event=$event ") },
            )
        }
        directory.deleteRecursively()
    }

    @Test
    fun realHttpDualRtlHeldTargetStillPaintsPartner() = runTest {
        realHttpDualSlots(mihon.domain.reader.ReaderDirection.RTL, held = 125)
    }

    @Test
    fun realHttpDualRtlHeldPartnerStillPaintsTarget() = runTest {
        realHttpDualSlots(mihon.domain.reader.ReaderDirection.RTL, held = 126)
    }

    @Test
    fun realHttpDualLtrHeldTargetStillPaintsPartner() = runTest {
        realHttpDualSlots(mihon.domain.reader.ReaderDirection.LTR, held = 125)
    }

    @Test
    fun realHttpDualLtrHeldPartnerStillPaintsTarget() = runTest {
        realHttpDualSlots(mihon.domain.reader.ReaderDirection.LTR, held = 126)
    }

    @Test
    fun realHttpDualRtlRetryTargetWhilePartnerRemainsHeld() = runTest {
        realHttpDualSlots(mihon.domain.reader.ReaderDirection.RTL, held = 126, retry = true)
    }

    @Test
    fun realHttpDualRtlRapidReplacementKeepsTheLatestLogicalFrame() = runTest {
        realHttpDualSlots(mihon.domain.reader.ReaderDirection.RTL, held = 126, rapid = true)
    }

    @Test
    fun realHttpDualLtrRapidReplacementKeepsTheLatestLogicalFrame() = runTest {
        realHttpDualSlots(mihon.domain.reader.ReaderDirection.LTR, held = 126, rapid = true)
    }

    private fun kotlinx.coroutines.test.TestScope.realHttpDualSlots(
        direction: mihon.domain.reader.ReaderDirection,
        held: Int,
        retry: Boolean = false,
        rapid: Boolean = false,
    ) {
        val release = java.util.concurrent.CountDownLatch(1)
        val entered = java.util.concurrent.CountDownLatch(1)
        val requested = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val failTarget = java.util.concurrent.atomic.AtomicBoolean(retry)
        fun png(color: Int): ByteArray {
            val bitmap = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
            return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                .toByteArray().also { bitmap.recycle() }
        }
        val red = png(Color.RED)
        val green = png(Color.GREEN)
        val blue = png(Color.BLUE)
        val server = mockwebserver3.MockWebServer()
        server.dispatcher = object : mockwebserver3.Dispatcher() {
            override fun dispatch(request: mockwebserver3.RecordedRequest): mockwebserver3.MockResponse {
                val index = request.url.encodedPath.substringAfterLast('/').toInt()
                requested += index
                if (index == held) {
                    entered.countDown()
                    check(release.await(30, java.util.concurrent.TimeUnit.SECONDS))
                }
                val bytes = when (index) {
                    125 -> red
                    126 -> green
                    else -> blue
                }
                val status = if (index == 125 && failTarget.compareAndSet(true, false)) 503 else 200
                return mockwebserver3.MockResponse.Builder().code(status).addHeader("Content-Type", "image/png")
                    .body(Buffer().write(bytes)).build()
            }
        }
        server.start()
        val cache = eu.kanade.tachiyomi.data.cache.ChapterCache(
            org.robolectric.RuntimeEnvironment.getApplication(),
            kotlinx.serialization.json.Json,
        )
        cache.clear()
        val original = viewer()
        val subject = if (direction == mihon.domain.reader.ReaderDirection.RTL) {
            original
        } else {
            DualPageR2LPagerViewer(original.activity, direction = direction).apply {
                config.navigationModeChangedListener = null
            }
        }
        val model = realViewerLifecycleModel()
        model.onViewerLoaded(subject)
        assertSame(subject, model.state.value.viewer)
        useEncodedOriginalProducer(subject)
        uy.kohesive.injekt.Injekt.get<eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences>()
            .automaticSpreadMatching().set(true)
        runCurrent()
        val source = eu.kanade.tachiyomi.ui.reader.loader.HttpReaderMaterializeAdapterIntegrationTest.TestHttpSource(
            server,
            okhttp3.OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).build(),
        )
        val chapter = ReaderChapter(Chapter.create().copy(id = 74000L + held, mangaId = 1))
            .apply { requestedPage = 125 }
        val pages = List(200) { index ->
            ReaderPage(index, imageUrl = server.url("/image/$index").toString()).apply { this.chapter = chapter }
        }
        chapter.publishLoadedPageListForTest(pages)
        val loader = eu.kanade.tachiyomi.ui.reader.loader.HttpPageLoader(chapter, source, cache)
        chapter.pageLoader = loader
        val available = if (held == 125) 126 else 125
        val color = if (available == 125) Color.RED else Color.GREEN
        try {
            subject.setChapters(ViewerChapters(chapter, null, null))
            subject.moveToPage(pages[125])
            invokeFixture("measure", subject.pager, 1400, 1000)
            val owner = requireNotNull(subject.adapter.session(chapter))
            if (retry) {
                awaitProduction { pages[125].status is Page.State.Error && entered.count == 0L }
                loader.retryPage(pages[125])
            }
            var selected: DualPagerPageHolder? = null
            var image: ReaderPageImageView? = null
            awaitProduction({
                "Independent $direction HTTP slot $available must bind while $held is held; " +
                    "requests=$requested states=${pages[125].status}/${pages[126].status} " +
                    "owner=${owner.location} frame=${owner.targetFrameId}"
            }) {
                selected = subject.pager.children.filterIsInstance<DualPagerPageHolder>()
                    .firstOrNull { it.displayPage.frameId == owner.targetFrameId }
                image = selected?.let { holder ->
                    val field = if ((holder.displayPage as? DisplayPage.Double)?.leftPage === pages[available]) {
                        "leftHolder"
                    } else {
                        "rightHolder"
                    }
                    ReflectionHelpers.getField<ReaderPageImageView?>(holder, field)
                }
                pages[available].status == Page.State.Ready && image?.let {
                    it.childCount > 0 && it.beforeImageDraw != null
                } == true
            }
            val holder = requireNotNull(selected)
            val mountedImage = requireNotNull(image)
            invokeFixture("measure", subject.pager, 1400, 1000)
            paintReaderImage(mountedImage, if (available == 125) red else green)
            val bounds = actualColorBounds(holder, color)
            assertTrue("The available HTTP page must paint real native pixels", bounds.width() > 0)
            val scope = requireNotNull(holder.presentationScope)
            assertEquals(mihon.domain.reader.ReaderDualPagePresentationSource.DEFAULT, scope.second.source)
            assertEquals(setOf(owner.identity.pageIds[available]), owner.frame(scope.second)?.drawnPageIds)
            assertEquals(125, owner.location?.pageId?.sourcePageIndex)
            assertTrue(owner.certifyPairing() !is mihon.domain.reader.ReaderPairingCertification.Certified)
            assertEquals(null, owner.captureCertifiedViewport(owner.identity.pageIds[125]))
            assertEquals(1L, release.count)
            assertTrue(
                "The sibling HTTP request must actually be held",
                entered.await(5, java.util.concurrent.TimeUnit.SECONDS),
            )
            assertTrue(owner.displayedHistory.none { owner.identity.pageIds[held] in it.drawnPageIds })
            if (retry) {
                awaitProduction { requested.count { it == 125 } >= 2 && requested.count { it == 126 } >= 2 }
            }
            if (rapid) {
                for (index in listOf(128, 132, 129)) subject.moveToPage(pages[index])
                invokeFixture("measure", subject.pager, 1400, 1000)
                settleOriginalPager(subject)
                var latestHolder: DualPagerPageHolder? = null
                var latestImage: ReaderPageImageView? = null
                awaitProduction({ "The latest HTTP frame must load while the obsolete call is held: $requested" }) {
                    latestHolder = subject.pager.children.filterIsInstance<DualPagerPageHolder>()
                        .firstOrNull { it.displayPage.frameId == owner.targetFrameId }
                    latestImage = latestHolder?.let { current ->
                        val field = if ((current.displayPage as? DisplayPage.Double)?.leftPage === pages[129]) {
                            "leftHolder"
                        } else {
                            "rightHolder"
                        }
                        ReflectionHelpers.getField<ReaderPageImageView?>(current, field)
                    }
                    pages[129].status == Page.State.Ready && latestImage?.let {
                        it.childCount > 0 && it.beforeImageDraw != null
                    } == true
                }
                invokeFixture("measure", subject.pager, 1400, 1000)
                paintReaderImage(requireNotNull(latestImage), blue)
                val latest = requireNotNull(latestHolder)
                assertTrue(actualColorBounds(latest, Color.BLUE).width() > 0)
                assertEquals(129, owner.location?.pageId?.sourcePageIndex)
                val latestTicket = requireNotNull(latest.presentationScope).second
                assertEquals(owner.targetFrameId, latestTicket.frameId)
                assertTrue(owner.frame(latestTicket)!!.drawnPageIds.contains(owner.identity.pageIds[129]))
                assertTrue(owner.displayedHistory.none { owner.identity.pageIds[126] in it.drawnPageIds })
                assertTrue(pages[126].status !is Page.State.Error)
                assertEquals(1L, release.count)
                return
            }
            release.countDown()
            awaitProduction { pages[held].status == Page.State.Ready }
            invokeFixture("measure", subject.pager, 1400, 1000)
            val currentImage = ReflectionHelpers.getField<ReaderPageImageView>(
                holder,
                if ((holder.displayPage as? DisplayPage.Double)?.leftPage === pages[available]) {
                    "leftHolder"
                } else {
                    "rightHolder"
                },
            )
            assertSame(mountedImage, currentImage)
            assertEquals(bounds, actualColorBounds(holder, color))
        } finally {
            release.countDown()
            (subject.pager.parent as? ViewGroup)?.removeView(subject.pager)
            subject.destroy()
            runCurrent()
            loader.recycle()
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
            while (loader.hasForegroundWork && System.nanoTime() < deadline) {
                runCurrent()
                Thread.sleep(5)
            }
            assertTrue("Actual HTTP work must exit before closing the fixture cache", !loader.hasForegroundWork)
            server.close()
            cache.close()
        }
    }

    /** Only dependencies are mocked; the production lifecycle method updates its actual state flow. */
    private fun realViewerLifecycleModel(): eu.kanade.tachiyomi.ui.reader.ReaderViewModel {
        val model = viewer().activity.viewModel
        val state = model.state as kotlinx.coroutines.flow.MutableStateFlow<
            eu.kanade.tachiyomi.ui.reader.ReaderViewModel.State,
            >
        ReflectionHelpers.setField(model, "mutableState", state)
        every { model.onViewerLoaded(any()) } answers { callOriginal() }
        every { model.onViewerUnloaded(any()) } answers { callOriginal() }
        return model
    }

    @Test
    fun onlyTheCurrentInstalledViewerCanGrantAnOrdinaryViewport() = runTest {
        val original = viewer()
        val model = realViewerLifecycleModel()
        model.onViewerLoaded(null)
        val chapter = originalChapter(74190, 7, 1)
        val loader = eu.kanade.tachiyomi.ui.reader.loader.HttpPageLoader(
            chapter,
            io.mockk.mockk(relaxed = true),
            io.mockk.mockk(relaxed = true),
            dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler),
        )
        chapter.pageLoader = loader
        var replacement: DualPageR2LPagerViewer? = null
        try {
            runCurrent()
            original.setChapters(ViewerChapters(chapter, null, null))
            original.moveToPage(requireNotNull(chapter.pages)[1])
            assertEquals(null, ReflectionHelpers.getField<Any?>(loader, "normalViewport"))
            model.onViewerLoaded(original)
            val selected = original.adapter.items[original.adapter.positionForPage(requireNotNull(chapter.pages)[1])]
                as DisplayPage
            original.onDisplayPageSelected(selected)
            org.junit.Assert.assertNotNull(ReflectionHelpers.getField<Any?>(loader, "normalViewport"))
            replacement = DualPageR2LPagerViewer(original.activity).apply {
                config.navigationModeChangedListener = null
            }
            model.onViewerLoaded(replacement)
            replacement.setChapters(ViewerChapters(chapter, null, null))
            replacement.moveToPage(requireNotNull(chapter.pages)[1])
            val current = ReflectionHelpers.getField<Any>(loader, "normalViewport")
            original.onDisplayPageSelected(selected)
            original.destroy()
            assertSame(current, ReflectionHelpers.getField<Any>(loader, "normalViewport"))
            model.onViewerLoaded(null)
            val next = replacement.adapter.items[replacement.adapter.positionForPage(requireNotNull(chapter.pages)[1])]
                as DisplayPage
            replacement.onDisplayPageSelected(next)
            assertSame(current, ReflectionHelpers.getField<Any>(loader, "normalViewport"))
        } finally {
            replacement?.destroy()
            original.destroy()
            model.onViewerLoaded(null)
            loader.recycle()
            runCurrent()
        }
    }

    private fun actualColorBounds(view: View, color: Int): Rect {
        val output = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(output))
            val pixels = IntArray(output.width * output.height)
            output.getPixels(pixels, 0, output.width, 0, 0, output.width, output.height)
            val bounds = Rect()
            pixels.forEachIndexed { index, actual ->
                if (actual == color) {
                    bounds.union(
                        index % output.width,
                        index / output.width,
                        index % output.width + 1,
                        index / output.width + 1,
                    )
                }
            }
            return bounds
        } finally {
            output.recycle()
        }
    }

    @Test
    fun readyFileOriginalReadsShareTheLocationByteBudget() = runTest {
        readyFileByteBudgetContract(swallowCancellation = false)
    }

    @Test
    fun readyFileNativeNullAfterQuotaKeepsItsResourceFailure() = runTest {
        readyFileByteBudgetContract(swallowCancellation = true)
    }

    private fun kotlinx.coroutines.test.TestScope.readyFileByteBudgetContract(swallowCancellation: Boolean) {
        val viewer = ReflectionHelpers.getField<DualPageR2LPagerViewer>(fixture, "viewer")
        uy.kohesive.injekt.Injekt.get<eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences>()
            .automaticSpreadMatching().set(true)
        runCurrent()
        val directory = java.nio.file.Files.createTempDirectory("ready-file-budget").toFile()
        val bitmap = Bitmap.createBitmap(80, 120, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
        val encoded = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        val small = directory.resolve("small.png").apply { writeBytes(encoded) }
        val large = List(2) { index ->
            directory.resolve("large-$index.png").also { file ->
                file.writeBytes(encoded)
                RandomAccessFile(file, "rw").use { it.setLength(17L * 1024 * 1024) }
            }
        }
        val bytes = java.util.concurrent.atomic.AtomicLong()
        val opens = java.util.concurrent.atomic.AtomicInteger()
        val closes = java.util.concurrent.atomic.AtomicInteger()
        var measured = false
        val chapter = ReaderChapter(
            Chapter.create().copy(id = if (swallowCancellation) 73081 else 73080, mangaId = 1),
        ).apply { requestedPage = 2 }
        val pages = List(5) { index ->
            val file = when (index) {
                1 -> large[0]
                2 -> large[1]
                else -> small
            }
            ReaderPage(index, stream = {
                val countThisStream = measured
                if (countThisStream) opens.incrementAndGet()
                object : FilterInputStream(FileInputStream(file)) {
                    override fun read(): Int = super.read().also {
                        if (countThisStream && it >= 0) bytes.incrementAndGet()
                    }
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                        super.read(buffer, offset, length).also {
                            if (countThisStream && it > 0) bytes.addAndGet(it.toLong())
                        }
                    override fun close() {
                        super.close()
                        if (countThisStream) closes.incrementAndGet()
                    }
                }
            }).apply {
                this.chapter = chapter
                status = Page.State.Ready
                publishHeaderFact()
            }
        }
        chapter.publishLoadedPageListForTest(pages)
        val entered = java.util.concurrent.CountDownLatch(2)
        viewer.adapter.spreadDecoderFactory = { input ->
            entered.countDown()
            check(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            try {
                val buffer = ByteArray(8192)
                while (input.read(buffer) != -1) Unit
            } catch (error: kotlinx.coroutines.CancellationException) {
                // The exact JNI stream boundary can return null after a Java read exception.
                if (swallowCancellation) throw IllegalStateException("Native decoder returned null", error)
                throw error
            }
            object : eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageRegionDecoder {
                override fun decode(region: Rect) =
                    Bitmap.createBitmap(region.width(), region.height(), Bitmap.Config.ARGB_8888)
                        .apply { eraseColor(Color.GRAY) }
                override fun recycle() = Unit
            }
        }
        measured = true
        try {
            viewer.setChapters(ViewerChapters(chapter, null, null))
            val owner = requireNotNull(viewer.adapter.session(chapter))
            val location = owner.location
            val budget = owner.factBudget
            awaitProduction {
                val result = owner.certifyPairing()
                (
                    result is mihon.domain.reader.ReaderPairingCertification.Certified ||
                        result is mihon.domain.reader.ReaderPairingCertification.RetryableBlocked
                    ) &&
                    eu.kanade.tachiyomi.ui.reader.viewer.AndroidReaderSpreadSampler.optionalWork.value == 0
            }
            assertTrue(
                "Ready file bytes must be reserved before their actual read: " + bytes.get(),
                bytes.get() <= mihon.domain.reader.ReaderDualPageFactLimits.MAX_NEW_BYTES,
            )
            assertEquals(bytes.get(), ReflectionHelpers.getField<Long>(budget, "newlyReadBytes"))
            val blocked = owner.certifyPairing() as mihon.domain.reader.ReaderPairingCertification.RetryableBlocked
            assertTrue(blocked.failures.values.all { it == mihon.domain.reader.ReaderAnalysisFailure.RESOURCE })
            blocked.failures.keys.forEach { id ->
                assertTrue(owner.originalAnalysis(id) !is mihon.domain.reader.ReaderPageAnalysisEvidence.Computed)
            }
            assertEquals(location, owner.location)
            assertSame(budget, owner.factBudget)
            assertEquals(opens.get(), closes.get())
            val completedReads = bytes.get()
            repeat(5) {
                viewer.adapter.refreshPairing()
                runCurrent()
            }
            assertEquals("Quota failure cannot start new file reads", completedReads, bytes.get())
        } finally {
            viewer.destroy()
            runCurrent()
            directory.listFiles().orEmpty().forEach(File::delete)
            directory.delete()
        }
    }

    @Test
    fun readyFileFreshAndCompleteHeadersShareTheLocationByteBudget() = runTest {
        val viewer = ReflectionHelpers.getField<DualPageR2LPagerViewer>(fixture, "viewer")
        uy.kohesive.injekt.Injekt.get<eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences>()
            .automaticSpreadMatching().set(true)
        runCurrent()
        val file = File.createTempFile("ready-header-budget", ".png")
        val bitmap = Bitmap.createBitmap(80, 120, Bitmap.Config.ARGB_8888)
        val encoded = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        RandomAccessFile(file, "rw").use { output ->
            output.write(encoded, 0, encoded.size - 12)
            val payload = mihon.domain.reader.ReaderDualPageFactLimits.MAX_NEW_BYTES.toInt()
            val type = "raNd".toByteArray(Charsets.US_ASCII)
            output.writeInt(payload)
            output.write(type)
            val crc = java.util.zip.CRC32().apply { update(type) }
            val zeros = ByteArray(8192)
            repeat(payload / zeros.size) {
                crc.update(zeros)
                output.write(zeros)
            }
            output.writeInt(crc.value.toInt())
            output.write(encoded, encoded.size - 12, 12)
        }
        val bytes = java.util.concurrent.atomic.AtomicLong()
        val opens = java.util.concurrent.atomic.AtomicInteger()
        val chapter = ReaderChapter(Chapter.create().copy(id = 73082, mangaId = 1))
        val page = ReaderPage(0, stream = {
            opens.incrementAndGet()
            object : FilterInputStream(FileInputStream(file)) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                    super.read(buffer, offset, length).also { if (it > 0) bytes.addAndGet(it.toLong()) }
            }
        }).apply {
            this.chapter = chapter
            status = Page.State.Ready
        }
        chapter.publishLoadedPageListForTest(listOf(page))
        try {
            viewer.setChapters(ViewerChapters(chapter, null, null))
            val owner = requireNotNull(viewer.adapter.session(chapter))
            val location = owner.location
            val budget = owner.factBudget
            awaitProduction {
                owner.certifyPairing() is mihon.domain.reader.ReaderPairingCertification.RetryableBlocked &&
                    eu.kanade.tachiyomi.ui.reader.viewer.AndroidReaderSpreadSampler.optionalWork.value == 0
            }
            assertTrue(
                "Fresh bounded and complete header reads must share 32 MiB: " + bytes.get(),
                bytes.get() <= mihon.domain.reader.ReaderDualPageFactLimits.MAX_NEW_BYTES,
            )
            assertEquals(bytes.get(), ReflectionHelpers.getField<Long>(budget, "newlyReadBytes"))
            val blocked = owner.certifyPairing() as mihon.domain.reader.ReaderPairingCertification.RetryableBlocked
            assertEquals(
                mihon.domain.reader.ReaderAnalysisFailure.RESOURCE,
                blocked.failures[owner.identity.pageIds[0]],
            )
            assertTrue(
                owner.originalAnalysis(
                    owner.identity.pageIds[0],
                ) !is mihon.domain.reader.ReaderPageAnalysisEvidence.Computed,
            )
            assertEquals(location, owner.location)
            assertSame(budget, owner.factBudget)
            val completedReads = bytes.get()
            val completedOpens = opens.get()
            repeat(5) {
                viewer.adapter.refreshPairing()
                runCurrent()
            }
            assertEquals(completedReads, bytes.get())
            assertEquals(completedOpens, opens.get())
        } finally {
            viewer.destroy()
            runCurrent()
            file.delete()
        }
    }

    @Test
    fun completedRealOriginalDrawsWhileSpeculativeNativeDecodeIsStillHeld() = runTest {
        originalTargetIgnoresSpeculativeWork(failSpeculativeOnce = false)
    }

    @Test
    fun speculativeOriginalFailureLeavesEvidenceUnknownAndRealNavigationCanRecover() = runTest {
        originalTargetIgnoresSpeculativeWork(failSpeculativeOnce = true)
    }

    private suspend fun kotlinx.coroutines.test.TestScope.originalTargetIgnoresSpeculativeWork(
        failSpeculativeOnce: Boolean,
    ) {
        val viewer = ReflectionHelpers.getField<DualPageR2LPagerViewer>(fixture, "viewer")
        uy.kohesive.injekt.Injekt.get<eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences>()
            .automaticSpreadMatching().set(true)
        runCurrent()
        fun png(width: Int, height: Int, color: Int): ByteArray {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
            return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                .toByteArray().also { bitmap.recycle() }
        }
        val red = png(80, 120, Color.RED)
        val blue = png(80, 120, Color.BLUE)
        val wide = png(120, 80, Color.RED)
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val speculativeRegions = java.util.concurrent.atomic.AtomicInteger()
        val failNext = java.util.concurrent.atomic.AtomicBoolean(failSpeculativeOnce)
        val chapter = ReaderChapter(Chapter.create().copy(id = 73100, mangaId = 1)).apply { requestedPage = 10 }
        val pages = List(11) { index ->
            val bytes = when (index) {
                9 -> wide
                7 -> blue
                else -> red
            }
            ReaderPage(index, stream = { bytes.inputStream() }).apply {
                this.chapter = chapter
                status = Page.State.Ready
                publishHeaderFact()
            }
        }
        chapter.publishLoadedPageListForTest(pages)
        viewer.adapter.spreadDecoderFactory = { input ->
            val original = requireNotNull(BitmapFactory.decodeStream(input))
            val speculative = original.getPixel(0, 0) == Color.BLUE
            object : eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageRegionDecoder {
                override fun decode(region: Rect): Bitmap {
                    if (speculative) {
                        speculativeRegions.incrementAndGet()
                        entered.countDown()
                        if (failNext.compareAndSet(true, false)) {
                            throw java.io.IOException("Speculative source read failed")
                        }
                        check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    }
                    return Bitmap.createBitmap(original, region.left, region.top, region.width(), region.height())
                }
                override fun recycle() = original.recycle()
            }
        }
        viewer.setChapters(ViewerChapters(chapter, null, null))
        val owner = requireNotNull(viewer.adapter.session(chapter))
        val location = owner.location
        try {
            awaitProduction({
                val job = ReflectionHelpers.getField<kotlinx.coroutines.Job?>(viewer.adapter, "matchingJob")
                "Speculative original did not enter: location=${owner.location} proof=${owner.certifyPairing()} " +
                    "speculative=${owner.originalAnalysis(
                        owner.identity.pageIds[7],
                    )} regions=${speculativeRegions.get()} " +
                    "optional=${eu.kanade.tachiyomi.ui.reader.viewer.AndroidReaderSpreadSampler.optionalWork.value} " +
                    "foreground=${ReaderPageImageView.foregroundWork.value} job=$job " +
                    "pressure=${viewer.activity.optionalWorkUnderMemoryPressure.value}"
            }) { entered.count == 0L }
            val certificate = awaitCertified(viewer, chapter, 10)
            assertEquals("Fact/UI refresh must preserve the exact navigation identity", location, owner.location)
            assertEquals("Target certification must precede speculative native completion", 1L, release.count)
            val display = viewer.adapter.items.filterIsInstance<DisplayPage>().single { it.containsPage(pages[10]) }
            val holder = DualPagerPageHolder(viewer.activity, viewer, display)
            mounted += holder
            assertCertifiedNativeFrame(holder, certificate)
            load(holder, pages[10], "CENTER")
            invokeFixture("measure", holder, 1400, 1000)
            descendants(holder).filterIsInstance<ReaderPageImageView>().forEach { paintReaderImage(it, red) }
            settle(holder)
            pixels(holder)
            val ticket = requireNotNull(holder.presentationScope).second
            assertTrue(owner.frame(ticket)!!.drawnPageIds.containsAll(certificate.segment.unit.pageIds))
            assertEquals(location, owner.location)
            assertEquals(1L, release.count)
            if (failSpeculativeOnce) {
                awaitProduction {
                    eu.kanade.tachiyomi.ui.reader.viewer.AndroidReaderSpreadSampler.optionalWork.value == 0
                }
                val speculativeEvidence = owner.originalAnalysis(owner.identity.pageIds[7])
                assertTrue(
                    "Speculation failure must not publish a blocking original fact",
                    speculativeEvidence is mihon.domain.reader.ReaderPageAnalysisEvidence.NotAnalyzed,
                )
                release.countDown()
                viewer.moveToPage(pages[7])
                awaitCertified(viewer, chapter, 7)
                val realEvidence = owner.originalAnalysis(owner.identity.pageIds[7])
                assertTrue(realEvidence is mihon.domain.reader.ReaderPageAnalysisEvidence.Computed)
                return
            }
            viewer.moveToPage(pages[0])
            runCurrent()
            assertEquals(0, owner.location!!.pageId.sourcePageIndex)
            assertTrue(
                "Cancellation cannot hide the executing native region",
                eu.kanade.tachiyomi.ui.reader.viewer.AndroidReaderSpreadSampler.optionalWork.value > 0,
            )
            release.countDown()
            awaitProduction { eu.kanade.tachiyomi.ui.reader.viewer.AndroidReaderSpreadSampler.optionalWork.value == 0 }
            assertEquals("Cancelled original sampling cannot start another region", 1, speculativeRegions.get())
            val oldSpeculative = owner.originalAnalysis(owner.identity.pageIds[7])
            assertTrue(
                "Old speculative completion must be rejected after navigation",
                oldSpeculative !is mihon.domain.reader.ReaderPageAnalysisEvidence.Computed,
            )
        } finally {
            release.countDown()
        }
    }

    @Test
    fun automaticOpeningCanReadItsRealPageZeroDependency() = runTest {
        val viewer = ReflectionHelpers.getField<DualPageR2LPagerViewer>(fixture, "viewer")
        useEncodedOriginalProducer(viewer)
        uy.kohesive.injekt.Injekt.get<eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences>()
            .automaticSpreadMatching().set(true)
        runCurrent()
        val chapter = originalChapter(73101, 5, 0)
        val bitmap = Bitmap.createBitmap(80, 120, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.RED)
            for (y in 0 until height) for (x in 75 until width) setPixel(x, y, Color.WHITE)
        }
        val trailing = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            .toByteArray().also { bitmap.recycle() }
        requireNotNull(chapter.pages)[1].apply {
            stream = { trailing.inputStream() }
            publishHeaderFact()
        }
        viewer.setChapters(ViewerChapters(chapter, null, null))
        val owner = requireNotNull(viewer.adapter.session(chapter))
        val location = owner.location
        val initial = owner.certifyPairing(owner.identity.pageIds[0])
        assertTrue(
            "Opening page zero must be a real certifier dependency before any IO",
            initial is mihon.domain.reader.ReaderPairingCertification.NeedFacts &&
                initial.facts.any { it.pageId == owner.identity.pageIds[0] },
        )
        val certificate = awaitCertified(viewer, chapter, 0)
        val openingEvidence = owner.originalAnalysis(owner.identity.pageIds[0])
        assertTrue(openingEvidence is mihon.domain.reader.ReaderPageAnalysisEvidence.Computed)
        assertEquals(location, owner.location)
        assertEquals(owner.canonicalUnits.single { owner.identity.pageIds[0] in it.pageIds }, certificate.segment.unit)
    }

    @Test
    fun targetRequiredPairingDemandExceedsSixteenPagesAndCertifies() = runTest {
        val viewer = ReflectionHelpers.getField<DualPageR2LPagerViewer>(fixture, "viewer")
        val opensByPage = java.util.concurrent.ConcurrentHashMap<Int, java.util.concurrent.atomic.AtomicInteger>()
        val active = java.util.concurrent.atomic.AtomicInteger()
        val peak = java.util.concurrent.atomic.AtomicInteger()
        val indexByChannel = (0 until 40).associateBy { (it * 35) and 255 }
        viewer.adapter.spreadDecoderFactory = { input ->
            val original = requireNotNull(BitmapFactory.decodeStream(input))
            val index = indexByChannel.getValue(Color.red(original.getPixel(10, 10)))
            opensByPage.computeIfAbsent(index) { java.util.concurrent.atomic.AtomicInteger() }.incrementAndGet()
            val currentActive = active.incrementAndGet()
            peak.updateAndGet { maxOf(it, currentActive) }
            object : eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageRegionDecoder {
                override fun decode(region: Rect) =
                    Bitmap.createBitmap(original, region.left, region.top, region.width(), region.height())
                override fun recycle() {
                    original.recycle()
                    active.decrementAndGet()
                }
            }
        }
        uy.kohesive.injekt.Injekt.get<eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences>()
            .automaticSpreadMatching().set(true)
        runCurrent()
        fun png(index: Int): ByteArray {
            val bitmap = Bitmap.createBitmap(40, 60, Bitmap.Config.ARGB_8888)
            for (y in 0 until 60) {
                for (x in 0 until 40) {
                    val channel = if ((x == 2 && index == 2) || (x == 37 && index == 3)) 80 + y * 2 else index * 35
                    bitmap.setPixel(x, y, Color.argb(255, channel, 80, 120))
                }
            }
            return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                .toByteArray().also { bitmap.recycle() }
        }
        val chapter = ReaderChapter(Chapter.create().copy(id = 73099, mangaId = 1)).apply { requestedPage = 30 }
        val pages = List(40) { index ->
            val bytes = png(index)
            ReaderPage(index, stream = { bytes.inputStream() }).apply {
                this.chapter = chapter
                status = Page.State.Ready
                publishHeaderFact()
            }
        }
        chapter.publishLoadedPageListForTest(pages)
        try {
            viewer.setChapters(ViewerChapters(chapter, null, null))
            viewer.moveToPage(pages[30])
            val owner = requireNotNull(viewer.adapter.session(chapter))
            awaitProduction({ "Expected certified page 30, got ${owner.certifyPairing(owner.identity.pageIds[30])}" }) {
                owner.certifyPairing(owner.identity.pageIds[30]) is
                    mihon.domain.reader.ReaderPairingCertification.Certified
            }
            val cert = owner.certifyPairing(owner.identity.pageIds[30])
            assertTrue(cert is mihon.domain.reader.ReaderPairingCertification.Certified)
            awaitProduction {
                eu.kanade.tachiyomi.ui.reader.viewer.AndroidReaderSpreadSampler.optionalWork.value == 0
            }
            assertTrue("Real original demand must exceed the optional sixteen-page limit", opensByPage.size > 16)
            assertEquals("The adapter serializes native sampling while required admission stays bounded", 1, peak.get())
            assertTrue(
                "A page can be sampled once speculatively then upgraded once",
                opensByPage.values.all { it.get() <= 2 },
            )
            assertTrue(opensByPage.values.sumOf { it.get() } <= 2 * pages.size)
            assertEquals(0, active.get())
        } finally {
            viewer.destroy()
            runCurrent()
        }
    }

    @Test
    fun realPagerSwipeActivatesItsUnpaintedPartnerBeforeLeavingTheGroup() = runTest {
        val (chapter, holder, _) = mountOriginalPagerWithOnlyEarlierPixels(73113)
        val viewer = viewer()
        val owner = requireNotNull(viewer.adapter.session(chapter))
        val original = requireNotNull(holder.presentationScope).second.frameId
        swipe(viewer.pager, 100f, 1300f, 500f)
        settleOriginalPager(viewer)
        assertEquals(
            "A real ViewPager drag cannot skip its unpainted source 4",
            4,
            owner.location!!.pageId.sourcePageIndex,
        )
        assertEquals(original, owner.targetFrameId)
        assertEquals(
            "Native pager must select its exact shared target",
            listOf(3, 4),
            (viewer.adapter.items[viewer.pager.currentItem] as DisplayPage).visiblePages.map {
                it.index
            },
        )
        assertTrue(owner.displayedHistory.none { owner.identity.pageIds[4] in it.drawnPageIds })
        swipe(viewer.pager, 100f, 1300f, 500f)
        settleOriginalPager(viewer)
        assertEquals(
            "The next deliberate drag may leave the still-unpainted partner",
            5,
            owner.location!!.pageId.sourcePageIndex,
        )
        assertTrue(owner.displayedHistory.none { owner.identity.pageIds[4] in it.drawnPageIds })
    }

    @Test
    fun realPagerCancelledMovementAndRapidReverseKeepExactLogicalFramesWithoutUndrawnReading() = runTest {
        val (chapter, holder, _) = mountOriginalPagerWithOnlyEarlierPixels(73114)
        val viewer = viewer()
        val owner = requireNotNull(viewer.adapter.session(chapter))
        val ticket = requireNotNull(holder.presentationScope).second
        val anchor = owner.location
        val before = pixels(viewer.pager)
        val time = SystemClock.uptimeMillis() + 1000
        send(viewer.pager, time, time, MotionEvent.ACTION_DOWN, 100f, 500f)
        for (step in 1..12) {
            send(viewer.pager, time, time + step * 30, MotionEvent.ACTION_MOVE, 100f + step * 140f, 500f)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            runCurrent()
        }
        assertTrue("The production listener must observe an actual drag", viewer.hasActiveGesture)
        send(viewer.pager, time, time + 390, MotionEvent.ACTION_CANCEL, 1780f, 500f)
        settleOriginalPager(viewer)
        assertEquals(anchor, owner.location)
        assertEquals("Cancellation must retain the old physical identity", ticket.frameId, owner.targetFrameId)
        assertEquals(before, pixels(viewer.pager))
        val next = android.view.KeyEvent.KEYCODE_DPAD_LEFT
        val previous = android.view.KeyEvent.KEYCODE_DPAD_RIGHT
        for ((key, expected) in listOf(next to 4, next to 5, previous to 4, previous to 2)) {
            viewer.handleKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, key))
            assertEquals(expected, owner.location!!.pageId.sourcePageIndex)
        }
        assertEquals(
            listOf(1, 2),
            (viewer.adapter.items[viewer.pager.currentItem] as DisplayPage).visiblePages.map {
                it.index
            },
        )
        assertTrue(owner.displayedHistory.none { owner.identity.pageIds[4] in it.drawnPageIds })
    }

    private fun kotlinx.coroutines.test.TestScope.settleOriginalPager(viewer: DualPageR2LPagerViewer) {
        settleOriginalPager(viewer) { runCurrent() }
    }

    private fun settleOriginalPager(viewer: DualPageR2LPagerViewer, pumpMain: () -> Unit) {
        val pager = viewer.pager
        val output = Bitmap.createBitmap(pager.width, pager.height, Bitmap.Config.ARGB_8888)
        try {
            repeat(80) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
                // A direct root View.draw does not run the parent traversal's Scroller step.
                // Drive that original production step, then paint the actual native hierarchy.
                pager.computeScroll()
                pager.draw(Canvas(output))
                pumpMain()
                if (!viewer.hasActiveGesture && ReflectionHelpers.getField<Boolean>(viewer, "isIdle")) return
            }
            error("The actual native pager did not become idle after its bounded animation frames")
        } finally {
            output.recycle()
        }
    }

    private suspend fun kotlinx.coroutines.test.TestScope.mountOriginalPagerWithOnlyEarlierPixels(
        id: Long,
    ): Triple<ReaderChapter, DualPagerPageHolder, ReaderPageImageView> {
        val viewer = viewer()
        useEncodedOriginalProducer(viewer)
        val chapter = originalChapter(id, 8, 3)
        val pages = requireNotNull(chapter.pages)
        pages.filter { it.index != 3 }.forEach { it.status = Page.State.Queue }
        chapter.pageLoader = object : eu.kanade.tachiyomi.ui.reader.loader.PageLoader() {
            override var isLocal = false
            override suspend fun getPages() = pages
            override suspend fun loadPage(page: ReaderPage) {
                if (page.index != 3) kotlinx.coroutines.awaitCancellation()
            }
        }
        viewer.pager.pageTurnRippleEnabled = false
        viewer.setChapters(ViewerChapters(chapter, null, null))
        viewer.moveToPage(pages[3])
        awaitCertified(viewer, chapter, 3)
        invokeFixture("measure", viewer.pager, 1400, 1000)
        runCurrent()
        val holder = descendants(viewer.pager).filterIsInstance<DualPagerPageHolder>()
            .first { it.displayPage.containsPage(pages[3]) }
        load(holder, pages[3], "RIGHT")
        invokeFixture("measure", viewer.pager, 1400, 1000)
        val image = descendants(holder).filterIsInstance<ReaderPageImageView>().first { it.childCount > 0 }
        paintReaderImage(image, requireNotNull(pages[3].stream).invoke().use { it.readBytes() })
        settle(viewer.pager)
        runCurrent()
        assertTrue("This test retains the production adapter", viewer.pager.adapter === viewer.adapter)
        val (owner, ticket) = requireNotNull(holder.presentationScope)
        assertEquals(setOf(owner.identity.pageIds[3]), owner.frame(ticket)!!.drawnPageIds)
        return Triple(chapter, holder, image)
    }

    @Test
    fun temporarilyHiddenCurrentNativeFrameRetainsItsLockedPixelsForCancelledMovement() = runTest {
        val viewer = viewer()
        useEncodedOriginalProducer(viewer)
        val chapter = originalChapter(73112, 7, 3)
        val pages = requireNotNull(chapter.pages)
        viewer.setChapters(ViewerChapters(chapter, null, null))
        viewer.moveToPage(pages[3])
        awaitCertified(viewer, chapter, 3)
        val display = viewer.adapter.items.filterIsInstance<DisplayPage>().first { it.containsPage(pages[3]) }
        val holder = DualPagerPageHolder(viewer.activity, viewer, display).also { mounted += it }
        load(holder, pages[3], "RIGHT")
        invokeFixture("measure", holder, 1400, 1000)
        val image = descendants(holder).filterIsInstance<ReaderPageImageView>().first { it.childCount > 0 }
        val encoded = requireNotNull(pages[3].stream).invoke().use { it.readBytes() }
        paintReaderImage(image, encoded)
        settle(holder)
        val (owner, ticket) = requireNotNull(holder.presentationScope)
        val anchor = owner.location
        holder.translationX = 4000f
        assertTrue(!holder.getGlobalVisibleRect(Rect()))
        holder.onViewportMoved()
        org.junit.Assert.assertNotNull(
            "A temporarily hidden logical target must retain its original frame",
            owner.frame(ticket),
        )
        holder.translationX = 0f
        holder.onViewportMoved()
        paintReaderImage(image, encoded)
        assertEquals(anchor, owner.location)
        assertEquals(ticket.frameId, requireNotNull(holder.presentationScope).second.frameId)
        assertTrue(pixels(holder).width() > 0)
    }

    @Test
    fun independentNativeMountsKeepTheirSiblingCapabilityAfterDetach() = runTest {
        val viewer = viewer()
        useEncodedOriginalProducer(viewer)
        val chapter = originalChapter(73110, 7, 3)
        val pages = requireNotNull(chapter.pages)
        viewer.setChapters(ViewerChapters(chapter, null, null))
        viewer.moveToPage(pages[3])
        awaitCertified(viewer, chapter, 3)
        val display = viewer.adapter.items.filterIsInstance<DisplayPage>().first { it.containsPage(pages[3]) }
        val first = DualPagerPageHolder(viewer.activity, viewer, display).also { mounted += it }
        val second = DualPagerPageHolder(viewer.activity, viewer, display).also { mounted += it }
        val old = requireNotNull(first.presentationScope)
        val remaining = requireNotNull(second.presentationScope)
        assertEquals(old.second.frameId, remaining.second.frameId)
        org.junit.Assert.assertNotSame(
            "Every holder must own its release even for the same physical frame",
            old.second,
            remaining.second,
        )
        invokeFixture("detach", first)
        org.junit.Assert.assertNotNull(remaining.first.frame(remaining.second))
    }

    @Test
    fun latePrefixGeometryNavigatesToTheExactOverlappingIncomingNativeFrame() = runTest {
        val viewer = DualPageR2LPagerViewer(viewer().activity, direction = mihon.domain.reader.ReaderDirection.LTR)
            .apply { config.navigationModeChangedListener = null }
        try {
            useEncodedOriginalProducer(viewer)
            val chapter = originalChapter(73111, 8, 3)
            val pages = requireNotNull(chapter.pages)
            viewer.setChapters(ViewerChapters(chapter, null, null))
            viewer.moveToPage(pages[3])
            val owner = requireNotNull(viewer.adapter.session(chapter))
            awaitCertified(viewer, chapter, 3)
            val display = viewer.adapter.items.filterIsInstance<DisplayPage>().first { it.containsPage(pages[3]) }
            val holder = DualPagerPageHolder(viewer.activity, viewer, display).also { mounted += it }
            load(holder, pages[3], "LEFT")
            invokeFixture("measure", holder, 1400, 1000)
            val image = descendants(holder).filterIsInstance<ReaderPageImageView>().first { it.childCount > 0 }
            paintReaderImage(image, requireNotNull(pages[3].stream).invoke().use { it.readBytes() })
            settle(holder)
            val outgoing = requireNotNull(holder.presentationScope).second
            assertEquals(setOf(owner.identity.pageIds[3]), owner.frame(outgoing)!!.drawnPageIds)
            viewer.adapter.updatePageDimensions(pages[1], 240, 120)
            assertEquals(listOf(3, 4), holder.displayPage.visiblePages.map { it.index })
            viewer.handleKeyEvent(
                android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DPAD_RIGHT),
            )
            assertEquals(4, owner.location!!.pageId.sourcePageIndex)
            val selected = viewer.adapter.items[viewer.pager.currentItem] as DisplayPage
            assertEquals(
                "The shared boundary must choose incoming [4,5], not outgoing [3,4]",
                listOf(4, 5),
                selected.visiblePages.map {
                    it.index
                },
            )
            val incoming = DualPagerPageHolder(viewer.activity, viewer, selected).also { mounted += it }
            val ticket = requireNotNull(incoming.presentationScope).second
            org.junit.Assert.assertNotEquals(outgoing.frameId, ticket.frameId)
            assertTrue(owner.frame(outgoing) != null)
            assertTrue(owner.frame(ticket) != null)
            invokeFixture("detach", holder)
            assertTrue(owner.frame(ticket) != null)
        } finally {
            viewer.destroy()
            runCurrent()
        }
    }

    @Test
    fun onlineTwoHundredColdJumpPaintsBeforeRequiredFactsRtl() = runTest {
        coldJumpBeforeRequiredFacts(mihon.domain.reader.ReaderDirection.RTL)
    }

    @Test
    fun onlineTwoHundredColdJumpPaintsBeforeRequiredFactsLtr() = runTest {
        coldJumpBeforeRequiredFacts(mihon.domain.reader.ReaderDirection.LTR)
    }

    private suspend fun kotlinx.coroutines.test.TestScope.coldJumpBeforeRequiredFacts(
        direction: mihon.domain.reader.ReaderDirection,
    ) {
        val viewer = if (direction == mihon.domain.reader.ReaderDirection.RTL) {
            viewer()
        } else {
            DualPageR2LPagerViewer(viewer().activity, direction = direction)
                .apply { config.navigationModeChangedListener = null }
        }
        useEncodedOriginalProducer(viewer)
        uy.kohesive.injekt.Injekt.get<eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences>()
            .automaticSpreadMatching().set(true)
        runCurrent()
        val bitmap = Bitmap.createBitmap(80, 120, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val encoded = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        val chapter = ReaderChapter(Chapter.create().copy(id = 73100 + direction.ordinal.toLong(), mangaId = 1))
            .apply { requestedPage = 125 }
        val pages = List(200) { index -> ReaderPage(index).apply { this.chapter = chapter } }
        chapter.publishLoadedPageListForTest(pages)
        val entered = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
        val activeFacts = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
        chapter.pageLoader = object : eu.kanade.tachiyomi.ui.reader.loader.PageLoader() {
            override var isLocal = false
            override suspend fun getPages() = pages
            override suspend fun loadPage(page: ReaderPage) {
                if (page.index != 125) kotlinx.coroutines.awaitCancellation()
            }
            override suspend fun loadFactPage(
                page: ReaderPage,
                permit: mihon.domain.reader.ReaderDualPageFactReadPermit,
            ) {
                entered += page.index
                activeFacts += page.index
                try {
                    kotlinx.coroutines.awaitCancellation()
                } finally {
                    activeFacts -= page.index
                }
            }
        }
        // Only the requested image bytes arrive. No original/header fact is prefilled.
        pages[125].stream = { encoded.inputStream() }
        pages[125].status = Page.State.Ready
        try {
            viewer.setChapters(ViewerChapters(chapter, null, null))
            viewer.moveToPage(pages[125])
            awaitProduction { entered.isNotEmpty() }
            var owner = requireNotNull(viewer.adapter.session(chapter))
            val requested = owner.identity.pageIds[125]
            assertTrue(owner.certifyPairing() is mihon.domain.reader.ReaderPairingCertification.NeedFacts)
            org.junit.Assert.assertNull(owner.captureCertifiedViewport(requested))
            val display = viewer.adapter.items.filterIsInstance<DisplayPage>().first { it.containsPage(pages[125]) }
            val holder = DualPagerPageHolder(viewer.activity, viewer, display)
            mounted += holder
            org.junit.Assert.assertNotNull(
                "The real holder needs a legal default capability while original facts hang",
                holder.presentationScope,
            )
            // Keep runTest from auto-advancing the required timeout while normal external IO
            // is running; the explicit production poll advances runnable work, not virtual time.
            val normalBinding = backgroundScope.async {
                load(holder, pages[125], if (direction == mihon.domain.reader.ReaderDirection.RTL) "RIGHT" else "LEFT")
            }
            awaitProduction { normalBinding.isCompleted }
            normalBinding.await()
            invokeFixture("measure", holder, 1400, 1000)
            val imageField = if (direction == mihon.domain.reader.ReaderDirection.RTL) "rightHolder" else "leftHolder"
            awaitProduction {
                val image = ReflectionHelpers.getField<ReaderPageImageView?>(holder, imageField)
                image != null && image.childCount > 0 && image.beforeImageDraw != null
            }
            // The asynchronously mounted image must participate in an actual layout pass.
            invokeFixture("measure", holder, 1400, 1000)
            val image = requireNotNull(ReflectionHelpers.getField<ReaderPageImageView?>(holder, imageField))
            paintReaderImage(image, encoded)
            settle(holder)
            owner = requireNotNull(holder.presentationScope).first
            val ticket = requireNotNull(holder.presentationScope).second
            assertEquals(mihon.domain.reader.ReaderDualPagePresentationSource.DEFAULT, ticket.source)
            assertEquals(setOf(requested), owner.frame(ticket)!!.drawnPageIds)
            assertTrue(
                "Production native child must paint while required original calls are still suspended",
                pixels(holder).width() > 0,
            )
            assertEquals(125, owner.location!!.pageId.sourcePageIndex)
            val afterDrawCertification = owner.certifyPairing()
            assertTrue(
                "Required facts remain pending after actual pixels: $afterDrawCertification active=$activeFacts",
                afterDrawCertification is mihon.domain.reader.ReaderPairingCertification.NeedFacts,
            )
            assertTrue(
                owner.identity.pageIds.filterIndexed { index, _ -> index != 125 }.all {
                    val fact = owner.pageFact(it)
                    (
                        fact == null || (
                            fact.state == mihon.domain.reader.ReaderPageFactState.UNKNOWN &&
                                fact.width == 0 && fact.height == 0
                            )
                        ) &&
                        owner.originalAnalysis(it) is mihon.domain.reader.ReaderPageAnalysisEvidence.NotAnalyzed
                },
            )
            assertTrue("Required facts must still be held while real pixels are visible", activeFacts.isNotEmpty())
            org.junit.Assert.assertNull(owner.captureCertifiedViewport(requested))
            val nextKey = if (direction == mihon.domain.reader.ReaderDirection.RTL) {
                android.view.KeyEvent.KEYCODE_DPAD_LEFT
            } else {
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT
            }
            viewer.handleKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, nextKey))
            assertEquals(
                "The first real input activates the missing partner",
                126,
                owner.location!!.pageId.sourcePageIndex,
            )
            holder.beginPresentation()
            assertEquals(ticket.frameId, requireNotNull(holder.presentationScope).second.frameId)
            assertEquals(setOf(requested), owner.frame(requireNotNull(holder.presentationScope).second)!!.drawnPageIds)
            viewer.handleKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, nextKey))
            assertEquals(
                "A second real input may deliberately leave that still-unpainted source",
                127,
                owner.location!!.pageId.sourcePageIndex,
            )
            assertTrue(owner.displayedHistory.none { owner.identity.pageIds[126] in it.drawnPageIds })
        } finally {
            viewer.destroy()
            runCurrent()
        }
    }

    @Test
    fun uncertifiedOriginalNeverPreparesCertifiedNativeSlot() = runTest {
        val viewer = ReflectionHelpers.getField<DualPageR2LPagerViewer>(fixture, "viewer")
        viewer.adapter.spreadDecoderFactory = { error("Original analysis unavailable until an explicit retry") }
        uy.kohesive.injekt.Injekt.get<eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences>()
            .automaticSpreadMatching().set(true)
        runCurrent()
        val bitmap = Bitmap.createBitmap(80, 120, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val encoded = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        val chapter = ReaderChapter(Chapter.create().copy(id = 73003, mangaId = 1))
        val pages = List(5) { index ->
            ReaderPage(index, stream = { encoded.inputStream() }).apply {
                this.chapter = chapter
                status = Page.State.Ready
                publishHeaderFact()
            }
        }
        chapter.publishLoadedPageListForTest(pages)
        viewer.setChapters(ViewerChapters(chapter, null, null))
        viewer.moveToPage(pages[2])
        val display = viewer.adapter.items.filterIsInstance<DisplayPage>().single { it.containsPage(pages[2]) }
        val holder = DualPagerPageHolder(viewer.activity, viewer, display)
        mounted += holder
        val owner = requireNotNull(viewer.adapter.session(chapter))
        org.junit.Assert.assertNull(
            "Header/Ready cannot forge original certification",
            owner.captureCertifiedViewport(owner.identity.pageIds[2]),
        )
        val capability = requireNotNull(holder.presentationScope).second
        assertEquals(mihon.domain.reader.ReaderDualPagePresentationSource.DEFAULT, capability.source)
        assertTrue(
            "A basic capability is not a drawn page",
            owner.displayedHistory.none {
                it.drawnPageIds.isNotEmpty()
            },
        )
    }

    @Test
    fun originalProducerPublishesReadFailureAndRecoversThroughExistingRetry() = runTest {
        val viewer = ReflectionHelpers.getField<DualPageR2LPagerViewer>(fixture, "viewer")
        val failedReads = java.util.concurrent.atomic.AtomicInteger()
        viewer.adapter.spreadDecoderFactory =
            {
                failedReads.incrementAndGet()
                throw java.io.IOException("fixture read failed")
            }
        uy.kohesive.injekt.Injekt.get<eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences>()
            .automaticSpreadMatching().set(true)
        runCurrent()
        val bitmap = Bitmap.createBitmap(80, 120, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val encoded = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        val chapter = ReaderChapter(Chapter.create().copy(id = 73004, mangaId = 1))
        val pages = List(5) { index ->
            ReaderPage(index, stream = { encoded.inputStream() }).apply {
                this.chapter = chapter
                status = Page.State.Ready
                publishHeaderFact()
            }
        }
        chapter.publishLoadedPageListForTest(pages)
        viewer.setChapters(ViewerChapters(chapter, null, null))
        viewer.moveToPage(pages[2])
        val owner = requireNotNull(viewer.adapter.session(chapter))
        fun awaitCertification(predicate: (mihon.domain.reader.ReaderPairingCertification) -> Boolean) {
            awaitProduction({
                "Actual decoder failures=${failedReads.get()}, result=${owner.certifyPairing(
                    owner.identity.pageIds[2],
                )}"
            }) {
                predicate(owner.certifyPairing(owner.identity.pageIds[2]))
            }
        }
        awaitCertification { it is mihon.domain.reader.ReaderPairingCertification.RetryableBlocked }
        assertTrue("The actual original decoder factory must have failed", failedReads.get() > 0)
        val failed = owner.certifyPairing(
            owner.identity.pageIds[2],
        ) as mihon.domain.reader.ReaderPairingCertification.RetryableBlocked
        assertTrue(failed.failures.values.all { it == mihon.domain.reader.ReaderAnalysisFailure.READ })
        viewer.adapter.spreadDecoderFactory = { input ->
            val original = requireNotNull(BitmapFactory.decodeStream(input))
            object : eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageRegionDecoder {
                override fun decode(
                    region: Rect,
                ) = Bitmap.createBitmap(original, region.left, region.top, region.width(), region.height())
                override fun recycle() = original.recycle()
            }
        }
        pages[2].retryHeaderFact()
        awaitCertification { it is mihon.domain.reader.ReaderPairingCertification.Certified }
        val display = viewer.adapter.items.filterIsInstance<DisplayPage>().single { it.containsPage(pages[2]) }
        assertTrue(viewer.capturePresentation(display) != null)
        assertTrue(
            "Analysis and a prepared capability still do not count as reading",
            owner.displayedHistory.none {
                it.drawnPageIds.isNotEmpty()
            },
        )
    }

    @Test
    fun missingHeaderReadAndQuotaFailuresAreRecoverableWithoutNavigationOrSpin() = runTest {
        val viewer = ReflectionHelpers.getField<DualPageR2LPagerViewer>(fixture, "viewer")
        uy.kohesive.injekt.Injekt.get<eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences>()
            .automaticSpreadMatching().set(true)
        runCurrent()
        val bitmap = Bitmap.createBitmap(80, 120, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val encoded = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        viewer.adapter.spreadDecoderFactory = { input ->
            val original = requireNotNull(BitmapFactory.decodeStream(input))
            object : eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageRegionDecoder {
                override fun decode(
                    region: Rect,
                ) = Bitmap.createBitmap(original, region.left, region.top, region.width(), region.height())
                override fun recycle() = original.recycle()
            }
        }
        for (reason in listOf(
            mihon.domain.reader.ReaderAnalysisFailure.READ,
            mihon.domain.reader.ReaderAnalysisFailure.RESOURCE,
        )) {
            val chapter = ReaderChapter(Chapter.create().copy(id = 73005 + reason.ordinal.toLong(), mangaId = 1))
            val page = ReaderPage(0).apply { this.chapter = chapter }
            chapter.publishLoadedPageListForTest(listOf(page))
            var failing = true
            var reads = 0
            chapter.pageLoader = object : eu.kanade.tachiyomi.ui.reader.loader.PageLoader() {
                override var isLocal = false
                override suspend fun getPages() = listOf(page)
                override suspend fun loadFactPage(
                    page: ReaderPage,
                    permit: mihon.domain.reader.ReaderDualPageFactReadPermit,
                ) {
                    reads++
                    if (failing) {
                        if (reason == mihon.domain.reader.ReaderAnalysisFailure.RESOURCE) {
                            viewer.adapter.session(chapter)!!.factBudget.close()
                            throw kotlinx.coroutines.CancellationException("Fact read permit exhausted")
                        }
                        throw java.io.IOException("Encoded content failed before a header existed")
                    }
                    page.stream = { encoded.inputStream() }
                    page.status = Page.State.Ready
                    super.loadFactPage(page, permit)
                }
            }
            viewer.setChapters(ViewerChapters(chapter, null, null))
            val owner = viewer.adapter.session(chapter)!!
            val location = owner.location
            fun awaitResult(predicate: (mihon.domain.reader.ReaderPairingCertification) -> Boolean) {
                awaitProduction({
                    "Read count=$reads, result=${owner.certifyPairing()}"
                }) { predicate(owner.certifyPairing()) }
            }
            awaitResult { it is mihon.domain.reader.ReaderPairingCertification.RetryableBlocked }
            val failed = owner.certifyPairing() as mihon.domain.reader.ReaderPairingCertification.RetryableBlocked
            assertEquals(reason, failed.failures[owner.identity.pageIds[0]])
            assertEquals(1, reads)
            repeat(5) { runCurrent() }
            assertEquals("A terminal preparation failure must not spin", 1, reads)
            failing = false
            page.retryHeaderFact()
            awaitResult { it is mihon.domain.reader.ReaderPairingCertification.Certified }
            assertEquals(location, owner.location)
            assertEquals(2, reads)
        }
    }

    @Test
    fun replacedOriginalResourceFailureRecoversThroughRealSourceNavigation() = runTest {
        val viewer = ReflectionHelpers.getField<DualPageR2LPagerViewer>(fixture, "viewer")
        uy.kohesive.injekt.Injekt.get<eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences>()
            .automaticSpreadMatching().set(true)
        runCurrent()
        fun png(color: Int): ByteArray {
            val bitmap = Bitmap.createBitmap(80, 120, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
            return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                .toByteArray().also { bitmap.recycle() }
        }
        val red = png(Color.RED)
        val blue = png(Color.BLUE)
        val gray = png(Color.GRAY)
        val replacement = png(Color.GREEN)
        val firstComplete = java.util.concurrent.CountDownLatch(1)
        val partnerStarted = java.util.concurrent.CountDownLatch(1)
        val releasePartner = java.util.concurrent.CountDownLatch(1)
        val newOriginalReads = java.util.concurrent.atomic.AtomicInteger()
        val decodedColors = java.util.concurrent.ConcurrentLinkedQueue<Int>()
        // Only Windows JNI is substituted; the actual incoming PNG, sampler and producer are exercised.
        viewer.adapter.spreadDecoderFactory = { input ->
            val original = requireNotNull(BitmapFactory.decodeStream(input))
            val color = original.getPixel(0, 0)
            decodedColors.add(color)
            if (color == Color.BLUE) {
                partnerStarted.countDown()
                check(releasePartner.await(5, java.util.concurrent.TimeUnit.SECONDS))
            }
            if (color == Color.GREEN) newOriginalReads.incrementAndGet()
            object : eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageRegionDecoder {
                override fun decode(region: Rect) =
                    Bitmap.createBitmap(original, region.left, region.top, region.width(), region.height())
                override fun recycle() {
                    original.recycle()
                    if (color == Color.RED) firstComplete.countDown()
                }
            }
        }
        val chapter = ReaderChapter(Chapter.create().copy(id = 73096, mangaId = 1)).apply { requestedPage = 2 }
        val pages = List(5) { index ->
            val encoded = when (index) {
                1 -> red
                2 -> blue
                else -> gray
            }
            ReaderPage(index, stream = { encoded.inputStream() }).apply {
                this.chapter = chapter
                status = Page.State.Ready
                publishHeaderFact()
            }
        }
        chapter.publishLoadedPageListForTest(pages)
        viewer.setChapters(ViewerChapters(chapter, null, null))
        var owner = requireNotNull(viewer.adapter.session(chapter))
        try {
            awaitProduction({
                "Before replacement: decodedColors=$decodedColors location=${owner.location} " +
                    "partnerStarted=${partnerStarted.count} firstComplete=${firstComplete.count}"
            }) { partnerStarted.count == 0L && firstComplete.count == 0L }
            val oldContent = pages[1].encodedContentIdentity()
            pages[1].stream = { replacement.inputStream() }
            runCurrent()
            owner = requireNotNull(viewer.adapter.session(chapter))
            val newContent = pages[1].encodedContentIdentity()
            assertTrue(oldContent != newContent)
            val oldJob = ReflectionHelpers.getField<kotlinx.coroutines.Job>(viewer.adapter, "matchingJob")
            releasePartner.countDown()
            awaitProduction { oldJob.isCompleted }
            awaitProduction({
                "Replacement did not reach its actual old-location quota failure: ${owner.certifyPairing()}"
            }) {
                val evidence = owner.originalAnalysis(owner.identity.pageIds[1])
                evidence is mihon.domain.reader.ReaderPageAnalysisEvidence.RetryableFailure &&
                    evidence.reason == mihon.domain.reader.ReaderAnalysisFailure.RESOURCE
            }
            assertEquals(0, newOriginalReads.get())
            val budget = owner.factBudget
            val before = requireNotNull(owner.location)
            assertTrue(owner.certifyPairing() is mihon.domain.reader.ReaderPairingCertification.RetryableBlocked)
            assertTrue("Same-location failure must retain its real budget", owner.factBudget === budget)
            // A different logical source page goes through the existing Viewer navigation; no Retry is invoked.
            viewer.moveToPage(pages[1])
            runCurrent()
            owner = requireNotNull(viewer.adapter.session(chapter))
            assertEquals(before.sequence + 1, owner.location!!.sequence)
            assertEquals(owner.identity.pageIds[1], owner.location!!.pageId)
            val certificate = awaitCertified(viewer, chapter, 1)
            assertTrue("New location must actually decode the replacement PNG", newOriginalReads.get() > 0)
            val computed = owner.originalAnalysis(owner.identity.pageIds[1]) as
                mihon.domain.reader.ReaderPageAnalysisEvidence.Computed
            assertEquals(newContent, computed.content.contentVersion)
            val display = viewer.adapter.items.filterIsInstance<DisplayPage>().single { it.containsPage(pages[1]) }
            val holder = DualPagerPageHolder(viewer.activity, viewer, display)
            mounted += holder
            assertCertifiedNativeFrame(holder, certificate)
            load(holder, pages[1], "RIGHT")
            load(holder, pages[2], "LEFT")
            invokeFixture("measure", holder, 1400, 1000)
            val images = descendants(holder).filterIsInstance<ReaderPageImageView>()
                .filter { it.childCount > 0 }.toList()
            assertEquals(2, images.size)
            val bound = ReflectionHelpers.getField<Map<ReaderPage, String>>(holder, "boundEncodedContents")
            images.forEach { image ->
                val page = ReflectionHelpers.getField<ReaderPage>(image, "page")
                paintReaderImage(image, if (page === pages[1]) replacement else blue)
            }
            assertEquals(newContent, bound[pages[1]])
            settle(holder)
            val rendered = Bitmap.createBitmap(holder.width, holder.height, Bitmap.Config.ARGB_8888)
            try {
                holder.draw(Canvas(rendered))
                assertTrue(
                    "Actual native image must paint the replacement green pixels",
                    (0 until rendered.height step 40).any { y ->
                        (0 until rendered.width step 40).any { x -> rendered.getPixel(x, y) == Color.GREEN }
                    },
                )
            } finally {
                rendered.recycle()
            }
            val ticket = requireNotNull(holder.presentationScope).second
            assertTrue(owner.frame(ticket)!!.drawnPageIds.containsAll(certificate.segment.unit.pageIds))
        } finally {
            releasePartner.countDown()
        }
    }

    @Test
    fun replacingCompletedOriginalWhilePartnerIsPendingRejectsTheWholeBatch() = runTest {
        val viewer = ReflectionHelpers.getField<DualPageR2LPagerViewer>(fixture, "viewer")
        uy.kohesive.injekt.Injekt.get<eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences>()
            .automaticSpreadMatching().set(true)
        runCurrent()
        fun png(color: Int): ByteArray {
            val bitmap = Bitmap.createBitmap(80, 120, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
            return ByteArrayOutputStream().also {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }.toByteArray().also { bitmap.recycle() }
        }
        val red = png(Color.RED)
        val blue = png(Color.BLUE)
        val replacement = png(Color.GREEN)
        val firstComplete = java.util.concurrent.CountDownLatch(1)
        val partnerStarted = java.util.concurrent.CountDownLatch(1)
        val releasePartner = java.util.concurrent.CountDownLatch(1)
        viewer.adapter.spreadDecoderFactory = { input ->
            val original = requireNotNull(BitmapFactory.decodeStream(input))
            val partner = original.getPixel(0, 0) == Color.BLUE
            if (partner) {
                partnerStarted.countDown()
                check(releasePartner.await(5, java.util.concurrent.TimeUnit.SECONDS))
            }
            object : eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageRegionDecoder {
                override fun decode(
                    region: Rect,
                ) = Bitmap.createBitmap(original, region.left, region.top, region.width(), region.height())
                override fun recycle() {
                    original.recycle()
                    if (!partner) firstComplete.countDown()
                }
            }
        }
        val chapter = ReaderChapter(Chapter.create().copy(id = 73010, mangaId = 1)).apply { requestedPage = 2 }
        val pages = List(5) { index ->
            ReaderPage(index, stream = { (if (index == 2) blue else red).inputStream() }).apply {
                this.chapter = chapter
                status = Page.State.Ready
                publishHeaderFact()
            }
        }
        chapter.publishLoadedPageListForTest(pages)
        viewer.setChapters(ViewerChapters(chapter, null, null))
        val owner = viewer.adapter.session(chapter)!!
        try {
            awaitProduction { partnerStarted.count == 0L && firstComplete.count == 0L }
            runCurrent()
            awaitProduction {
                val evidence = owner.originalAnalysis(owner.identity.pageIds[1])
                evidence is mihon.domain.reader.ReaderPageAnalysisEvidence.Computed
            }
            // Withhold this ordinary subscriber to exercise the real publication capability,
            // rather than relying on the usual metadata collector winning the completion race.
            val collectors = ReflectionHelpers.getField<MutableMap<ReaderPage, kotlinx.coroutines.Job>>(
                viewer.adapter,
                "factCollectors",
            )
            collectors.getValue(pages[1]).cancel()
            runCurrent()
            pages[1].stream = { replacement.inputStream() }
            val publishingJob = ReflectionHelpers.getField<kotlinx.coroutines.Job>(viewer.adapter, "matchingJob")
            releasePartner.countDown()
            awaitProduction { publishingJob.isCompleted }
            assertTrue(
                "The completed page's old physical bytes cannot be published",
                owner.originalAnalysis(
                    owner.identity.pageIds[1],
                ) !is mihon.domain.reader.ReaderPageAnalysisEvidence.Computed,
            )
            assertTrue(
                "An invalid batch cannot publish its otherwise valid partner",
                owner.originalAnalysis(
                    owner.identity.pageIds[2],
                ) !is mihon.domain.reader.ReaderPageAnalysisEvidence.Computed,
            )
            collectors.remove(pages[1])
            viewer.setChapters(ViewerChapters(chapter, null, null))
            pages[2].retryHeaderFact()
            awaitCertified(viewer, chapter, 2)
            assertEquals(2, viewer.adapter.session(chapter)!!.location!!.pageId.sourcePageIndex)
        } finally {
            releasePartner.countDown()
        }
    }

    @Test
    fun lateWidePartnerKeepsThePaintedNativeImageAndRemainsTheNextPage() = runTest {
        val viewer = ReflectionHelpers.getField<DualPageR2LPagerViewer>(fixture, "viewer")
        useEncodedOriginalProducer(viewer)
        uy.kohesive.injekt.Injekt.get<eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences>()
            .automaticSpreadMatching().set(true)
        runCurrent()
        fun png(width: Int, height: Int): ByteArray {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
            return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                .toByteArray().also { bitmap.recycle() }
        }
        val portrait = png(400, 800)
        val wide = png(1200, 800)
        val chapter = ReaderChapter(Chapter.create().copy(id = 61091, mangaId = 1))
        val pages = List(7) { index ->
            ReaderPage(index, stream = { (if (index == 2) wide else portrait).inputStream() }).apply {
                this.chapter = chapter
                status = Page.State.Ready
            }
        }
        chapter.publishLoadedPageListForTest(pages)
        pages.filter { it.index != 2 }.forEach { it.publishHeaderFact() }
        viewer.setChapters(ViewerChapters(chapter, null, null))
        viewer.moveToPage(pages[1])
        val certificate = awaitCertified(viewer, chapter, 1)
        val display = viewer.adapter.items.filterIsInstance<DisplayPage>().single { it.containsPage(pages[1]) }
        val holder = DualPagerPageHolder(viewer.activity, viewer, display)
        mounted += holder
        assertCertifiedNativeFrame(holder, certificate)
        load(holder, pages[1], "CENTER")
        invokeFixture("measure", holder, 1400, 1000)
        val image = descendants(holder).filterIsInstance<ReaderPageImageView>().first { it.childCount > 0 }
        paintReaderImage(image, portrait)
        settle(holder)
        val before = pixels(holder)
        val native = image.getChildAt(0)
        val decoded = ReflectionHelpers.getField<Bitmap>(native, "bitmap")
        val owner = requireNotNull(viewer.adapter.session(chapter))
        val id = owner.identity.pageIds[1]
        val frame = holder.presentationScope!!.second.frameId
        assertTrue(owner.frame(holder.presentationScope!!.second)!!.drawnPageIds.contains(id))
        pages[2].publishHeaderFact()
        runCurrent()
        assertTrue(viewer.adapter.getItemPosition(holder) != androidx.viewpager.widget.PagerAdapter.POSITION_NONE)
        invokeFixture("measure", holder, 1400, 1000)
        settle(holder)
        assertEquals(before, pixels(holder))
        assertSame(native, image.getChildAt(0))
        assertSame(decoded, ReflectionHelpers.getField<Bitmap>(native, "bitmap"))
        assertEquals(frame, holder.presentationScope!!.second.frameId)
        assertEquals(listOf(id), owner.targetUnit!!.pageIds)
        assertSame(pages[2], viewer.adapter.navigate(chapter, true))
        val wideDisplay = viewer.adapter.items.filterIsInstance<DisplayPage>().single { it.containsPage(pages[2]) }
        assertEquals(ReaderPortraitSingleSlot.FULL, (wideDisplay as DisplayPage.Single).slot)
        invokeFixture("detach", holder)
        runCurrent()
        assertEquals(owner.canonicalUnits, owner.presentationFrameProjection)
    }

    @Test
    fun partialNeighbourNativePaintReleaseKeepsItsUndrawnPartnerOnEntry() = runTest {
        val viewer = ReflectionHelpers.getField<DualPageR2LPagerViewer>(fixture, "viewer")
        useEncodedOriginalProducer(viewer)
        uy.kohesive.injekt.Injekt.get<eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences>()
            .automaticSpreadMatching().set(true)
        runCurrent()
        val bitmap = Bitmap.createBitmap(400, 800, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val encoded = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        val chapter = ReaderChapter(Chapter.create().copy(id = 61090, mangaId = 1))
        val pages = List(7) { index ->
            ReaderPage(index, stream = { encoded.inputStream() }).apply {
                this.chapter = chapter
                status = Page.State.Ready
            }
        }
        chapter.publishLoadedPageListForTest(pages)
        pages.forEach { it.publishHeaderFact() }
        viewer.setChapters(ViewerChapters(chapter, null, null))
        viewer.moveToPage(pages[1])
        val candidate = viewer.adapter.items.filterIsInstance<DisplayPage>().single { it.containsPage(pages[3]) }
        viewer.adapter.requirePairing(candidate)
        val certificate = awaitCertified(viewer, chapter, 3)
        val display = viewer.adapter.items.filterIsInstance<DisplayPage>().single { it.containsPage(pages[3]) }
        val holder = DualPagerPageHolder(viewer.activity, viewer, display)
        mounted += holder
        assertCertifiedNativeFrame(holder, certificate)
        load(holder, pages[3], "CENTER")
        invokeFixture("measure", holder, 1400, 1000)
        val image = descendants(holder).filterIsInstance<ReaderPageImageView>().first { it.childCount > 0 }
        paintReaderImage(image, encoded)
        settle(holder)
        assertTrue("The unselected neighbouring holder paints real pixels", !pixels(holder).isEmpty)
        val owner = requireNotNull(viewer.adapter.session(chapter))
        val ids = owner.identity.pageIds
        assertTrue(owner.displayedHistory.any { ids[3] in it.drawnPageIds })
        assertTrue(owner.displayedHistory.none { ids[4] in it.drawnPageIds })
        invokeFixture("detach", holder)
        viewer.moveToPage(pages[3])
        assertEquals(listOf(ids[3], ids[4]), owner.targetUnit!!.pageIds)
        val reentered = viewer.adapter.items.filterIsInstance<DisplayPage>().single { it.containsPage(pages[3]) }
        assertTrue("Production adapter retains the unpainted partner", reentered.containsPage(pages[4]))
    }

    private fun useEncodedOriginalProducer(viewer: DualPageR2LPagerViewer) {
        // Replace the unavailable Windows JNI boundary only; sampler, owner and holder remain real.
        viewer.adapter.spreadDecoderFactory = { input ->
            eu.kanade.tachiyomi.ui.reader.viewer.decodedRegionFactory(input)
        }
    }

    @Test
    fun realNavigationCancelsOnlyTheOldRequiredCallsAndCloseReleasesTheRemainder() = runTest {
        val viewer = ReflectionHelpers.getField<DualPageR2LPagerViewer>(fixture, "viewer")
        uy.kohesive.injekt.Injekt.get<eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences>()
            .automaticSpreadMatching().set(true)
        runCurrent()
        val chapter = originalChapter(73024, 12, 2)
        val pages = requireNotNull(chapter.pages)
        pages.forEach { it.status = Page.State.Queue }
        val entered = mutableSetOf<Int>()
        val cancelled = mutableSetOf<Int>()
        chapter.pageLoader = object : eu.kanade.tachiyomi.ui.reader.loader.PageLoader() {
            override var isLocal = false
            override suspend fun getPages() = pages
            override suspend fun loadFactPage(
                page: ReaderPage,
                permit: mihon.domain.reader.ReaderDualPageFactReadPermit,
            ) {
                entered += page.index
                try {
                    kotlinx.coroutines.awaitCancellation()
                } finally {
                    cancelled += page.index
                }
            }
        }
        viewer.setChapters(ViewerChapters(chapter, null, null))
        viewer.moveToPage(pages[2])
        awaitProduction { entered.size >= 1 }
        val initial = entered.toSet()
        val owner = requireNotNull(viewer.adapter.session(chapter))
        val budget = owner.factBudget
        viewer.moveToPage(pages[8])
        runCurrent()
        assertTrue("Actual navigation must promptly cancel the old loader calls", cancelled.containsAll(initial))
        assertTrue(owner.factBudget !== budget)
        assertEquals(8, owner.location!!.pageId.sourcePageIndex)
        viewer.destroy()
        runCurrent()
        assertEquals(entered, cancelled)
        assertTrue(
            owner.identity.pageIds.all {
                owner.originalAnalysis(it) !is mihon.domain.reader.ReaderPageAnalysisEvidence.Computed
            },
        )
        assertEquals(0, eu.kanade.tachiyomi.ui.reader.viewer.AndroidReaderSpreadSampler.optionalWork.value)
    }

    @Test
    fun requiredPreparationDeadlineCancelsItsLoaderAndRetriesAtTheSameLocation() = runTest {
        val viewer = ReflectionHelpers.getField<DualPageR2LPagerViewer>(fixture, "viewer")
        useEncodedOriginalProducer(viewer)
        uy.kohesive.injekt.Injekt.get<eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences>()
            .automaticSpreadMatching().set(true)
        runCurrent()
        val chapter = originalChapter(73020, 7, 2)
        val pages = requireNotNull(chapter.pages)
        pages.forEach { it.status = Page.State.Queue }
        val entered = mutableSetOf<Int>()
        val cancelled = mutableSetOf<Int>()
        var hanging = true
        var normalReady = false
        val normalLoads = mutableSetOf<Int>()
        chapter.pageLoader = object : eu.kanade.tachiyomi.ui.reader.loader.PageLoader() {
            override var isLocal = false
            override suspend fun getPages() = pages
            override suspend fun loadPage(page: ReaderPage) {
                normalLoads += page.index
                if (normalReady && page.index == 2) page.status = Page.State.Ready
            }
            override suspend fun loadFactPage(
                page: ReaderPage,
                permit: mihon.domain.reader.ReaderDualPageFactReadPermit,
            ) {
                entered += page.index
                if (hanging) {
                    try {
                        kotlinx.coroutines.awaitCancellation()
                    } finally {
                        cancelled += page.index
                    }
                }
                page.status = Page.State.Ready
            }
        }
        viewer.setChapters(ViewerChapters(chapter, null, null))
        viewer.moveToPage(pages[2])
        val owner = requireNotNull(viewer.adapter.session(chapter))
        awaitProduction({
            "entered=$entered location=${owner.location} proof=${owner.certifyPairing()} " +
                "optional=${eu.kanade.tachiyomi.ui.reader.viewer.AndroidReaderSpreadSampler.optionalWork.value}"
        }) {
            entered.size >=
                1
        }
        val location = owner.location
        val budget = owner.factBudget
        assertTrue(
            "The required lane must not exceed its own concurrency: entered=$entered",
            entered.size <= mihon.domain.reader.ReaderDualPageFactLimits.REQUIRED_CONCURRENT_REQUESTS,
        )
        testScheduler.advanceTimeBy(mihon.domain.reader.ReaderDualPageFactLimits.MAX_WAIT_MILLIS + 1)
        runCurrent()
        assertEquals("The deadline must cancel the actual suspended loader calls", entered, cancelled)
        val blocked = owner.certifyPairing() as mihon.domain.reader.ReaderPairingCertification.RetryableBlocked
        assertTrue(blocked.failures.values.all { it == mihon.domain.reader.ReaderAnalysisFailure.RESOURCE })
        repeat(5) { runCurrent() }
        assertTrue(
            "The deadline must not admit further required consumers: entered=$entered",
            entered.size <= mihon.domain.reader.ReaderDualPageFactLimits.REQUIRED_CONCURRENT_REQUESTS,
        )
        assertSame(budget, owner.factBudget)
        assertEquals(location, owner.location)
        val attemptsBeforePixels = entered.toSet()
        normalReady = true
        invokeFixture("measure", viewer.pager, 1400, 1000)
        var normalHolder: DualPagerPageHolder? = null
        var normalImage: ReaderPageImageView? = null
        awaitProduction {
            normalHolder = viewer.pager.children.filterIsInstance<DualPagerPageHolder>()
                .firstOrNull { it.displayPage.frameId == owner.targetFrameId }
            normalImage = normalHolder?.let { holder ->
                val field = if ((holder.displayPage as? DisplayPage.Double)?.leftPage === pages[2]) {
                    "leftHolder"
                } else {
                    "rightHolder"
                }
                ReflectionHelpers.getField<ReaderPageImageView?>(holder, field)
            }
            normalImage?.let { it.childCount > 0 && it.beforeImageDraw != null } == true
        }
        val holder = requireNotNull(normalHolder)
        invokeFixture("measure", holder, 1400, 1000)
        val encoded = requireNotNull(pages[2].stream).invoke().use { it.readBytes() }
        paintReaderImage(requireNotNull(normalImage), encoded)
        assertTrue("The normal loader must bind its actual target after the fact deadline", 2 in normalLoads)
        assertTrue("Resource exhaustion must leave real ready pixels readable", pixels(holder).width() > 0)
        val visible = requireNotNull(holder.presentationScope)
        assertEquals(mihon.domain.reader.ReaderDualPagePresentationSource.DEFAULT, visible.second.source)
        assertEquals(setOf(owner.identity.pageIds[2]), owner.frame(visible.second)?.drawnPageIds)
        val afterPixels = owner.certifyPairing() as mihon.domain.reader.ReaderPairingCertification.RetryableBlocked
        assertTrue(afterPixels.failures.values.all { it == mihon.domain.reader.ReaderAnalysisFailure.RESOURCE })
        repeat(5) { runCurrent() }
        assertEquals("Normal pixels must not readmit exhausted fact consumers", attemptsBeforePixels, entered)
        assertSame(budget, owner.factBudget)
        assertEquals(location, owner.location)
        hanging = false
        chapter.pageLoader!!.retryPage(pages[2])
        awaitCertified(viewer, chapter, 2)
        assertEquals(location, owner.location)
        assertTrue(owner.factBudget !== budget)
    }

    @Test
    fun longRequiredPrefixCertifiesWithoutResettingItsLocationBudget() = runTest {
        val viewer = ReflectionHelpers.getField<DualPageR2LPagerViewer>(fixture, "viewer")
        uy.kohesive.injekt.Injekt.get<eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences>()
            .automaticSpreadMatching().set(true)
        runCurrent()
        val chapter = originalChapter(73021, 64, 40)
        val started = java.util.concurrent.atomic.AtomicInteger()
        val active = java.util.concurrent.atomic.AtomicInteger()
        val peak = java.util.concurrent.atomic.AtomicInteger()
        val recycled = java.util.concurrent.atomic.AtomicInteger()
        val firstBatch = java.util.concurrent.CountDownLatch(1)
        viewer.adapter.spreadDecoderFactory = { input ->
            val number = started.incrementAndGet()
            peak.accumulateAndGet(active.incrementAndGet(), ::maxOf)
            if (number <= 2) check(firstBatch.await(5, java.util.concurrent.TimeUnit.SECONDS))
            eu.kanade.tachiyomi.ui.reader.viewer.decodedRegionFactory(input) {
                active.decrementAndGet()
                recycled.incrementAndGet()
            }
        }
        viewer.setChapters(ViewerChapters(chapter, null, null))
        viewer.moveToPage(requireNotNull(chapter.pages)[40])
        val owner = requireNotNull(viewer.adapter.session(chapter))
        val location = owner.location
        val budget = owner.factBudget
        try {
            awaitProduction({
                "started=${started.get()} location=${owner.location} proof=${owner.certifyPairing()} " +
                    "optional=${eu.kanade.tachiyomi.ui.reader.viewer.AndroidReaderSpreadSampler.optionalWork.value}"
            }) {
                started.get() >=
                    1
            }
            // The required lane admits up to its own concurrency, so the peak is bounded by
            // that cap instead of being fixed at the old two lane precondition.
            assertTrue(
                "The required lane must not exceed its own concurrency: peak=${peak.get()}",
                peak.get() <= mihon.domain.reader.ReaderDualPageFactLimits.REQUIRED_CONCURRENT_REQUESTS,
            )
            firstBatch.countDown()
            // The required lane is deliberately not capped by the optional page budget, so the
            // long required prefix is served instead of being stopped at sixteen pages. The
            // protected behaviour is that the location budget is neither reset nor moved.
            awaitProduction {
                owner.certifyPairing() is mihon.domain.reader.ReaderPairingCertification.Certified
            }
            // A certificate may arrive while an already admitted sample is still queued or
            // unwinding. Observe real exit before measuring whether refresh starts new work.
            awaitProduction {
                active.get() == 0 &&
                    eu.kanade.tachiyomi.ui.reader.viewer.AndroidReaderSpreadSampler.optionalWork.value == 0
            }
            val served = started.get()
            assertTrue(
                "The long required prefix must be served: started=$served",
                served > 0,
            )
            assertEquals(served, recycled.get())
            assertTrue(
                "The required lane must not exceed its own concurrency: peak=${peak.get()}",
                peak.get() <= mihon.domain.reader.ReaderDualPageFactLimits.REQUIRED_CONCURRENT_REQUESTS,
            )
            assertSame(budget, owner.factBudget)
            assertEquals(location, owner.location)
            repeat(5) {
                viewer.adapter.refreshPairing()
                runCurrent()
            }
            assertEquals("Completion and refresh cannot admit another page", served, started.get())
            assertEquals(location, owner.location)
        } finally {
            firstBatch.countDown()
        }
    }

    @Test
    fun simultaneousRequiredReadsCannotExceedThirtyTwoMiB() = runTest {
        val viewer = ReflectionHelpers.getField<DualPageR2LPagerViewer>(fixture, "viewer")
        useEncodedOriginalProducer(viewer)
        uy.kohesive.injekt.Injekt.get<eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences>()
            .automaticSpreadMatching().set(true)
        runCurrent()
        val chapter = originalChapter(73022, 7, 2)
        val pages = requireNotNull(chapter.pages)
        pages.forEach { it.status = Page.State.Queue }
        val bytes = java.util.concurrent.atomic.AtomicLong()
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        chapter.pageLoader = object : eu.kanade.tachiyomi.ui.reader.loader.PageLoader() {
            override var isLocal = false
            override suspend fun getPages() = pages
            override suspend fun loadFactPage(
                page: ReaderPage,
                permit: mihon.domain.reader.ReaderDualPageFactReadPermit,
            ) {
                if (calls.incrementAndGet() == 2) entered.complete(Unit)
                entered.await()
                val source = object : okio.Source {
                    private var remaining = 17L * 1024 * 1024
                    private val timeout = okio.Timeout()
                    override fun timeout() = timeout
                    override fun close() = Unit
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        if (remaining == 0L) return -1
                        val count = minOf(remaining, byteCount)
                        sink.write(ByteArray(count.toInt()))
                        remaining -= count
                        bytes.addAndGet(count)
                        return count
                    }
                }
                permit.bound(source, System::nanoTime).use { bounded ->
                    val sink = Buffer()
                    while (bounded.read(sink, 8192) != -1L) sink.clear()
                }
                page.status = Page.State.Ready
            }
        }
        viewer.setChapters(ViewerChapters(chapter, null, null))
        viewer.moveToPage(pages[2])
        val owner = requireNotNull(viewer.adapter.session(chapter))
        val location = owner.location
        val budget = owner.factBudget
        awaitProduction { owner.certifyPairing() is mihon.domain.reader.ReaderPairingCertification.RetryableBlocked }
        // The required lane admits up to its own concurrency, so the number of attempts is not
        // fixed at two any more. The protected behaviour is the byte cap: no read may push the
        // new bytes past it, and no later attempt may add any.
        assertTrue(
            "The required lane must not exceed its own concurrency: calls=${calls.get()}",
            calls.get() <= mihon.domain.reader.ReaderDualPageFactLimits.REQUIRED_CONCURRENT_REQUESTS,
        )
        assertEquals(mihon.domain.reader.ReaderDualPageFactLimits.MAX_NEW_BYTES, bytes.get())
        assertSame(budget, owner.factBudget)
        assertEquals(location, owner.location)
        repeat(5) { runCurrent() }
        assertEquals(mihon.domain.reader.ReaderDualPageFactLimits.MAX_NEW_BYTES, bytes.get())
    }

    @Test
    fun cancelledOriginalPreparationWaitsForPhysicalReleaseBeforeRetryPublishes() = runTest {
        val viewer = ReflectionHelpers.getField<DualPageR2LPagerViewer>(fixture, "viewer")
        uy.kohesive.injekt.Injekt.get<eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences>()
            .automaticSpreadMatching().set(true)
        runCurrent()
        val chapter = originalChapter(73023, 7, 2)
        val entered = java.util.concurrent.atomic.AtomicInteger()
        val physicalEntered = java.util.concurrent.atomic.AtomicInteger()
        val recycled = java.util.concurrent.atomic.AtomicInteger()
        val release = java.util.concurrent.CountDownLatch(1)
        viewer.adapter.spreadDecoderFactory = { input ->
            entered.incrementAndGet()
            val actual = eu.kanade.tachiyomi.ui.reader.viewer.decodedRegionFactory(input) { recycled.incrementAndGet() }
            object : eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageRegionDecoder {
                private var firstRegion = true
                override fun decode(region: Rect): Bitmap {
                    if (release.count > 0) {
                        if (firstRegion) physicalEntered.incrementAndGet()
                        firstRegion = false
                        check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    }
                    return actual.decode(region)
                }
                override fun recycle() = actual.recycle()
            }
        }
        viewer.setChapters(ViewerChapters(chapter, null, null))
        viewer.moveToPage(requireNotNull(chapter.pages)[2])
        val owner = requireNotNull(viewer.adapter.session(chapter))
        val location = owner.location
        val budget = owner.factBudget
        try {
            awaitProduction({
                "entered=${entered.get()} physical=${physicalEntered.get()} location=${owner.location} " +
                    "proof=${owner.certifyPairing()} " +
                    "optional=${eu.kanade.tachiyomi.ui.reader.viewer.AndroidReaderSpreadSampler.optionalWork.value}"
            }) {
                val admitted = ReflectionHelpers.getField<Set<mihon.domain.reader.session.ReaderPageId>>(
                    budget,
                    "active",
                ).size
                admitted in 2..4 && physicalEntered.get() == admitted
            }
            val heldCount = physicalEntered.get()
            assertTrue(heldCount in 2..4)
            requireNotNull(chapter.pages)[2].retryHeaderFact()
            runCurrent()
            assertEquals(location, owner.location)
            assertTrue(owner.factBudget !== budget)
            assertEquals(0, recycled.get())
            assertEquals("Logical cancellation must not hide still-running physical decoders", heldCount, entered.get())
            assertTrue(eu.kanade.tachiyomi.ui.reader.viewer.AndroidReaderSpreadSampler.optionalWork.value > 0)
            assertTrue(
                owner.identity.pageIds.all {
                    owner.originalAnalysis(it) !is mihon.domain.reader.ReaderPageAnalysisEvidence.Computed
                },
            )
            release.countDown()
            awaitCertified(viewer, chapter, 2)
            awaitProduction { entered.get() == recycled.get() }
            assertTrue("Retry must run after original resources really exit", entered.get() > heldCount)
            assertEquals(location, owner.location)
            viewer.destroy()
            runCurrent()
            assertEquals(entered.get(), recycled.get())
            assertEquals(0, eu.kanade.tachiyomi.ui.reader.viewer.AndroidReaderSpreadSampler.optionalWork.value)
        } finally {
            release.countDown()
        }
    }

    private fun originalChapter(id: Long, count: Int, target: Int): ReaderChapter {
        val bitmap = Bitmap.createBitmap(80, 120, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val encoded = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        val ownerChapter = ReaderChapter(Chapter.create().copy(id = id, mangaId = 1)).apply { requestedPage = target }
        val pages = List(count) { index ->
            ReaderPage(index, stream = { encoded.inputStream() }).apply {
                chapter = ownerChapter
                status = Page.State.Ready
                publishHeaderFact()
            }
        }
        ownerChapter.publishLoadedPageListForTest(pages)
        return ownerChapter
    }

    @Test
    fun unrelatedContentRebindRetainsNativeFrameAndOldNeighbourCloseRefreshesDeferredContent() = runTest {
        val viewer = ReflectionHelpers.getField<DualPageR2LPagerViewer>(fixture, "viewer")
        useEncodedOriginalProducer(viewer)
        uy.kohesive.injekt.Injekt.get<eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences>()
            .automaticSpreadMatching().set(false)
        runCurrent()
        fun png(color: Int): ByteArray {
            val bitmap = Bitmap.createBitmap(400, 800, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
            return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                .toByteArray().also { bitmap.recycle() }
        }
        val red = png(Color.RED)
        val green = png(Color.GREEN)
        val chapter = ReaderChapter(Chapter.create().copy(id = 73092, mangaId = 1)).apply { requestedPage = 1 }
        val pages = List(7) { index ->
            ReaderPage(index, stream = { red.inputStream() }).apply {
                this.chapter = chapter
                status = Page.State.Ready
                publishHeaderFact()
            }
        }
        chapter.publishLoadedPageListForTest(pages)
        viewer.setChapters(ViewerChapters(chapter, null, null))
        viewer.moveToPage(pages[1])
        suspend fun paint(index: Int): DualPagerPageHolder {
            val display = viewer.adapter.items.filterIsInstance<DisplayPage>().single { it.containsPage(pages[index]) }
            val certificate = awaitCertified(viewer, chapter, index)
            val holder = DualPagerPageHolder(viewer.activity, viewer, display)
            mounted += holder
            assertCertifiedNativeFrame(holder, certificate)
            val page = pages[index]
            load(holder, page, "RIGHT")
            invokeFixture("measure", holder, 1400, 1000)
            val image = descendants(holder).filterIsInstance<ReaderPageImageView>().first { it.childCount > 0 }
            paintReaderImage(image, red)
            settle(holder)
            val (owner, ticket) = requireNotNull(holder.presentationScope)
            assertTrue(owner.frame(ticket)!!.drawnPageIds.contains(owner.identity.pageIds[index]))
            return holder
        }
        val current = paint(1)
        val neighbour = paint(3)
        val (oldOwner, oldCurrent) = requireNotNull(current.presentationScope)
        val oldFrame = oldOwner.frame(oldCurrent)!!
        val oldNeighbour = requireNotNull(neighbour.presentationScope).second
        val originalPixels = pixels(current)
        pages[5].stream = { green.inputStream() }
        pages[5].publishHeaderFact()
        runCurrent()
        val rebound = requireNotNull(viewer.adapter.session(chapter))
        assertTrue("The real unrelated page update must create a new owner", rebound !== oldOwner)
        current.beginPresentation()
        val renewed = requireNotNull(current.presentationScope)
        assertSame(rebound, renewed.first)
        assertEquals("An unchanged native mount keeps its shared physical frame", oldFrame.id, renewed.second.frameId)
        assertTrue(oldCurrent !== renewed.second)
        assertEquals(oldFrame.unit.slots, rebound.frame(renewed.second)!!.unit.slots)
        assertEquals(originalPixels, pixels(current))
        assertTrue(
            "The still-mounted neighbour retains its release-only lease",
            rebound.visibleContentPageIds.contains(
                rebound.identity.pageIds[3],
            ),
        )
        val oldVersion = rebound.identity.content[3].contentVersion
        pages[3].stream = { green.inputStream() }
        pages[3].publishHeaderFact()
        runCurrent()
        assertSame(
            "A native neighbour must defer replacement of its exposed bytes",
            rebound,
            viewer.adapter.session(chapter),
        )
        assertEquals(oldVersion, rebound.identity.content[3].contentVersion)
        assertTrue(!oldOwner.preparePage(oldNeighbour, oldOwner.identity.pageIds[3]))
        invokeFixture("detach", neighbour)
        runCurrent()
        val accepted = requireNotNull(viewer.adapter.session(chapter))
        assertTrue(
            "The old native detach must refresh the current owner and consume deferred content",
            accepted !== rebound,
        )
        assertEquals(pages[3].encodedContentIdentity(), accepted.identity.content[3].contentVersion)
        assertTrue(!accepted.visibleContentPageIds.contains(accepted.identity.pageIds[3]))
        assertTrue(!oldOwner.releaseViewport(oldNeighbour))
    }

    @Test
    fun originalAnalysisFailureKeepsReadyNativePixelsWithoutManufacturingImageError() = runTest {
        val viewer = ReflectionHelpers.getField<DualPageR2LPagerViewer>(fixture, "viewer")
        uy.kohesive.injekt.Injekt.get<eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences>()
            .automaticSpreadMatching().set(true)
        viewer.adapter.spreadDecoderFactory = { throw java.io.IOException("actual original read fails") }
        runCurrent()
        val bitmap = Bitmap.createBitmap(400, 800, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val encoded = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            .toByteArray()
        bitmap.recycle()
        val chapter = ReaderChapter(Chapter.create().copy(id = 73060, mangaId = 1)).apply { requestedPage = 2 }
        val pages = List(5) { index ->
            ReaderPage(index, stream = { encoded.inputStream() }).apply {
                this.chapter = chapter
                status = Page.State.Ready
                publishHeaderFact()
            }
        }
        chapter.publishLoadedPageListForTest(pages)
        val retries = mutableListOf<ReaderPage>()
        chapter.pageLoader = object : eu.kanade.tachiyomi.ui.reader.loader.PageLoader() {
            override var isLocal = true
            override suspend fun getPages() = pages
            override fun retryPage(page: ReaderPage) {
                retries += page
                super.retryPage(page)
            }
        }
        viewer.setChapters(ViewerChapters(chapter, null, null))
        viewer.moveToPage(pages[2])
        val owner = requireNotNull(viewer.adapter.session(chapter))
        awaitProduction { owner.certifyPairing() is mihon.domain.reader.ReaderPairingCertification.RetryableBlocked }
        val location = owner.location
        val display = viewer.adapter.items.filterIsInstance<DisplayPage>().single { it.containsPage(pages[2]) }
        val holder = DualPagerPageHolder(viewer.activity, viewer, display)
        mounted += holder
        load(holder, pages[1], "RIGHT")
        load(holder, pages[2], "LEFT")
        invokeFixture("measure", holder, 1400, 1000)
        descendants(holder).filterIsInstance<ReaderPageImageView>().forEach { paintReaderImage(it, encoded) }
        settle(holder)
        val ticket = requireNotNull(holder.presentationScope).second
        assertEquals(mihon.domain.reader.ReaderDualPagePresentationSource.DEFAULT, ticket.source)
        assertEquals(setOf(owner.identity.pageIds[1], owner.identity.pageIds[2]), owner.frame(ticket)!!.drawnPageIds)
        assertTrue(pixels(holder).width() > 0)
        val errors = ReflectionHelpers.getField<Map<Any, eu.kanade.tachiyomi.databinding.ReaderErrorBinding>>(
            holder,
            "errorLayouts",
        )
        assertTrue(
            "An enhancement failure cannot cover valid images with a Retry overlay",
            errors.values.none {
                it.root.visibility ==
                    View.VISIBLE
            },
        )
        assertTrue(
            "A basic draw cannot forge a successful original analysis",
            owner.certifyPairing() is mihon.domain.reader.ReaderPairingCertification.RetryableBlocked,
        )
        assertEquals(location, owner.location)
        assertTrue("Normal pixels never retried the source as if it had failed", retries.isEmpty())
    }

    @Test
    fun releasedSecondOwnerRefreshesAfterFirstScheduledOwnerLeavesTheChapterWindow() = runTest {
        val viewer = ReflectionHelpers.getField<DualPageR2LPagerViewer>(fixture, "viewer")
        useEncodedOriginalProducer(viewer)
        val preference = uy.kohesive.injekt.Injekt
            .get<eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences>().automaticSpreadMatching()
        preference.set(true)
        runCurrent()
        fun png(rightPage: Boolean): ByteArray {
            val bitmap = Bitmap.createBitmap(400, 800, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.RED)
            if (rightPage) for (y in 0 until 800) for (x in 0..14) bitmap.setPixel(x, y, Color.WHITE)
            return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                .toByteArray().also { bitmap.recycle() }
        }
        val red = png(false)
        val right = png(true)
        fun chapter(id: Long, target: Int, special: Boolean): ReaderChapter {
            val chapter = ReaderChapter(Chapter.create().copy(id = id, mangaId = 1)).apply { requestedPage = target }
            val pages = List(7) { index ->
                ReaderPage(index, stream = { (if (special && index == 4) right else red).inputStream() }).apply {
                    this.chapter = chapter
                    status = Page.State.Ready
                    publishHeaderFact()
                }
            }
            chapter.publishLoadedPageListForTest(pages)
            return chapter
        }
        val first = chapter(73051, 1, false)
        val second = chapter(73052, 3, true)
        viewer.setChapters(ViewerChapters(first, null, second))
        viewer.moveToPage(first.pages!![1])
        fun display(chapter: ReaderChapter, index: Int) = viewer.adapter.items.filterIsInstance<DisplayPage>()
            .single { it.containsPage(chapter.pages!![index]) }
        suspend fun paint(chapter: ReaderChapter, index: Int): DualPagerPageHolder {
            viewer.adapter.requirePairing(display(chapter, index))
            val certificate = awaitCertified(viewer, chapter, index)
            val holder = DualPagerPageHolder(viewer.activity, viewer, display(chapter, index))
            mounted += holder
            assertCertifiedNativeFrame(holder, certificate)
            load(holder, chapter.pages!![index], "CENTER")
            invokeFixture("measure", holder, 1400, 1000)
            val image = descendants(holder).filterIsInstance<ReaderPageImageView>().first { it.childCount > 0 }
            paintReaderImage(image, red)
            settle(holder)
            val (owner, ticket) = requireNotNull(holder.presentationScope)
            assertTrue(requireNotNull(owner.frame(ticket)).drawnPageIds.contains(owner.identity.pageIds[index]))
            return holder
        }
        val firstHolder = paint(first, 1)
        val secondHolder = paint(second, 3)
        val firstOwner = requireNotNull(viewer.adapter.session(first))
        val secondOwner = requireNotNull(viewer.adapter.session(second))
        assertEquals(
            listOf(secondOwner.identity.pageIds[3]),
            secondHolder.presentationScope!!.let {
                secondOwner.frame(it.second)!!.unit.pageIds
            },
        )
        awaitProduction { eu.kanade.tachiyomi.ui.reader.viewer.AndroidReaderSpreadSampler.optionalWork.value == 0 }
        preference.set(false)
        runCurrent()
        assertTrue(
            "The native painted frame must still protect the old presentation",
            secondOwner.canonicalUnits != secondOwner.presentationFrameProjection,
        )
        invokeFixture("detach", firstHolder)
        viewer.setChapters(ViewerChapters(second, null, null))
        assertTrue(!viewer.adapter.ownsSession(firstOwner))
        assertTrue(viewer.adapter.ownsSession(secondOwner))
        assertTrue(
            "The second holder is still painted before its release",
            secondOwner.canonicalUnits != secondOwner.presentationFrameProjection,
        )
        invokeFixture("detach", secondHolder)
        runCurrent()
        val actual = viewer.adapter.items.filterIsInstance<DisplayPage>().filter { it.firstPage.chapter === second }
            .map { it.visiblePages.map(ReaderPage::index).toSet() }.sortedBy { it.minOrNull() }
        val expected = secondOwner.presentationFrameProjection.map { unit ->
            unit.pageIds.map { it.sourcePageIndex }.toSet()
        }
            .sortedBy { it.minOrNull() }
        assertEquals("A still-owned release cannot be lost when the first scheduled owner leaves", expected, actual)
    }

    private fun kotlinx.coroutines.test.TestScope.awaitProduction(
        diagnostic: () -> String = { "Expected production event did not arrive" },
        predicate: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
        // Native IO runs in real time. Do not auto-advance the Main test clock to its 15s timer
        // while a controlled physical decoder is held; the deadline test advances it explicitly.
        do {
            runCurrent()
            if (predicate()) return
            Thread.sleep(5)
        } while (System.nanoTime() < deadline)
        throw AssertionError(diagnostic())
    }

    private fun kotlinx.coroutines.test.TestScope.awaitCertified(
        viewer: DualPageR2LPagerViewer,
        chapter: ReaderChapter,
        index: Int,
    ): mihon.domain.reader.ReaderPairingCertification.Certified {
        var result: mihon.domain.reader.ReaderPairingCertification? = null
        awaitProduction({ "Expected certificate, got $result" }) {
            val owner = requireNotNull(viewer.adapter.session(chapter))
            result = owner.certifyPairing(owner.identity.pageIds[index])
            result is mihon.domain.reader.ReaderPairingCertification.Certified
        }
        return result as mihon.domain.reader.ReaderPairingCertification.Certified
    }

    private fun assertCertifiedNativeFrame(
        holder: DualPagerPageHolder,
        certificate: mihon.domain.reader.ReaderPairingCertification.Certified,
    ) {
        val (owner, ticket) = requireNotNull(holder.presentationScope)
        val frame = requireNotNull(owner.frame(ticket))
        assertEquals(certificate.segment.unit.pageIds, frame.unit.pageIds)
        assertEquals(certificate.segment.unit.slots, frame.unit.slots)
        assertTrue("Certification must not acknowledge or prepare native pixels", frame.drawnPageIds.isEmpty())
        assertTrue(frame.lockedPageIds.isEmpty())
    }

    @Test
    fun diagnosticFileReceivesRealLoadingAndDrawCallbacks() = runTest {
        val directory = kotlin.io.path.createTempDirectory("reader-diagnostics").toFile()
        val sink = eu.kanade.tachiyomi.ui.reader.diagnostics.ReaderDiagnosticSink({ directory })
        eu.kanade.tachiyomi.ui.reader.diagnostics.ReaderDiagnostics.sink = sink
        try {
            val subject = subject(ReaderPortraitSingleSlot.LEFT)
            settle(subject.holder)
            val page = subject.pages.first()
            page.status = Page.State.Queue
            assertEquals(false, subject.image.beforeImageDraw?.invoke(400, 800))
            invokeFixture("detach", subject.holder)
            page.status = Page.State.Ready
            load(subject.holder, page, "CENTER")
            paintReaderImage(subject.image, subject.encoded)
            settle(subject.holder)
            sink.close()
            val text = directory.listFiles()!!.joinToString { it.readText() }
            for (event in listOf("read_start", "read_complete", "decode_start", "decode_ready", "drawn")) {
                assertTrue("Missing production event $event: $text", text.contains("event=$event"))
            }
            assertTrue(text.contains("chapter=61011 sourceIndex=0"))
            assertTrue(text.contains("reason=STALE_CALLBACK"))
            assertTrue("The real dual viewer jump must reach the file", text.contains("event=jump "))
            val holderEvents = text.lineSequence().filter {
                it.contains("event=read_start ") || it.contains("event=drawn ") || it.contains("event=prepare ")
            }.toList()
            val holderIds = holderEvents.map { Regex("holder=(\\d+)").find(it)?.groupValues?.get(1) }
            assertTrue("Loading and presentation must identify their holder", holderIds.all { it != null })
            assertEquals("Rebinding must retain the same holder identity", 1, holderIds.distinct().size)
            assertEquals(
                "Repeated native frames must not flood the file",
                2,
                text.lineSequence().count { it.contains("event=drawn ") },
            )
        } finally {
            eu.kanade.tachiyomi.ui.reader.diagnostics.ReaderDiagnostics.sink = null
            sink.close()
            directory.deleteRecursively()
        }
    }

    @Before
    fun setUp() {
        fixture.setUp()
        mainScheduler = kotlinx.coroutines.test.TestScope().testScheduler
    }

    @After
    fun tearDown() {
        mounted.forEach { holder ->
            descendants(holder).filterIsInstance<ReaderPageImageView>().forEach { it.recycle() }
            invokeFixture("detach", holder)
        }
        ReflectionHelpers.getField<DualPageR2LPagerViewer>(fixture, "viewer").destroy()
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
        do {
            mainScheduler.runCurrent()
            if (eu.kanade.tachiyomi.ui.reader.viewer.AndroidReaderSpreadSampler.optionalWork.value == 0) break
            Thread.sleep(5)
        } while (System.nanoTime() < deadline)
        assertEquals(
            "Finish real cancelled work before resetting this fixture's Main dispatcher",
            0,
            eu.kanade.tachiyomi.ui.reader.viewer.AndroidReaderSpreadSampler.optionalWork.value,
        )
        if (displayDecoderInstalled) {
            viewer().pager.children.filterIsInstance<DualPagerPageHolder>().forEach { holder ->
                descendants(holder).filterIsInstance<ReaderPageImageView>().forEach { it.recycle() }
            }
        }
        fixture.tearDown()
        if (displayDecoderInstalled) {
            val idleDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
            while (ReaderPageImageView.foregroundWork.value > 0 && System.nanoTime() < idleDeadline) {
                mainScheduler.runCurrent()
                Thread.sleep(5)
            }
            assertEquals(0, ReaderPageImageView.foregroundWork.value)
            unmockkConstructor(Decoder::class)
            displayDecoderPixels.values.filter { !it.isRecycled }.forEach { it.recycle() }
            displayDecoderPixels.clear()
        }
    }

    @Test
    fun zoomedFramesHandOutwardDragsToTheRealPagerAtEitherEdge() = runTest {
        runCurrent()
        for (slot in listOf(ReaderPortraitSingleSlot.LEFT, ReaderPortraitSingleSlot.RIGHT)) {
            for (direction in listOf(-1f, 1f)) {
                val subject = subject(
                    slot,
                    provisionalPair = slot == ReaderPortraitSingleSlot.RIGHT,
                    actualPairFrame = slot == ReaderPortraitSingleSlot.RIGHT,
                )
                if (slot == ReaderPortraitSingleSlot.RIGHT) {
                    val partner = subject.pages[2]
                    assertSame(partner, (subject.holder.displayPage as DisplayPage.Double).leftPage)
                }
                mountInPager(subject.holder)
                val pager = viewer().pager
                setCamera(subject.holder, 2f, direction * 720f, 0f)
                swipe(pager, 720f, 720f + direction * 620f)
                assertEquals(
                    "$slot outward direction=$direction must turn the whole frame",
                    if (direction >
                        0
                    ) {
                        0
                    } else {
                        2
                    },
                    pager.currentItem,
                )
            }
        }
    }

    @Test
    fun edgeHandoffDoesNotReplayDistanceAlreadyConsumedByTheZoomedImage() = runTest {
        for (direction in listOf(-1f, 1f)) {
            val subject = subject(ReaderPortraitSingleSlot.LEFT)
            mountInPager(subject.holder)
            val pager = viewer().pager
            setCamera(subject.holder, 2f, 0f, 0f)
            val time = SystemClock.uptimeMillis() + 1000
            val startX = if (direction < 0f) 1300f else 100f
            send(pager, time, time, MotionEvent.ACTION_DOWN, startX, 1500f)
            var previousScroll = pager.scrollX
            for (step in 1..12) {
                send(pager, time, time + step * 30, MotionEvent.ACTION_MOVE, startX + direction * step * 100f, 1500f)
                val movement = kotlin.math.abs(pager.scrollX - previousScroll)
                assertTrue(
                    "The pager must not replay the image pan at handoff: " +
                        "direction=$direction step=$step movement=$movement",
                    movement <= 100,
                )
                previousScroll = pager.scrollX
            }
            assertTrue("The same gesture must still move the pager", pager.scrollX != pager.width)
            send(pager, time, time + 390, MotionEvent.ACTION_UP, startX + direction * 1200f, 1500f)
        }
    }

    @Test
    fun panReachingTheEdgeHandsOffWithinTheSameDrag() = runTest {
        val subject = subject(ReaderPortraitSingleSlot.LEFT)
        mountInPager(subject.holder)
        setCamera(subject.holder, 2f, -600f, 0f)
        swipe(viewer().pager, 1250f, 100f)
        assertEquals(
            "Pan must reach its boundary then let the parent finish this same drag",
            2,
            viewer().pager.currentItem,
        )
    }

    @Test
    fun inwardAndVerticalDragsStayInTheZoomedViewportAndCancellationResetsTheGesture() = runTest {
        val subject = subject(ReaderPortraitSingleSlot.LEFT)
        mountInPager(subject.holder)
        val pager = viewer().pager
        setCamera(subject.holder, 2f, -720f, 0f)
        swipe(pager, 500f, 950f)
        assertEquals(1, pager.currentItem)
        assertTrue("Inward motion must pan", ReflectionHelpers.getField<Float>(subject.holder, "translateX") > -720f)
        setCamera(subject.holder, 2f, -720f, 0f)
        swipe(pager, 720f, 680f, 1500f, 2200f)
        assertEquals("Predominantly vertical motion must never flip", 1, pager.currentItem)
        assertTrue(ReflectionHelpers.getField<Float>(subject.holder, "translateY") > 0f)
        val time = SystemClock.uptimeMillis() + 1000
        send(pager, time, time, MotionEvent.ACTION_DOWN, 720f, 1500f)
        send(pager, time, time + 20, MotionEvent.ACTION_CANCEL, 720f, 1500f)
        swipe(pager, 720f, 100f)
        assertEquals("A cancelled gesture must not block a subsequent edge turn", 2, pager.currentItem)
    }

    @Test
    fun pinchAndTheRemainingFingerCannotTurnPagesUntilTheNextGesture() = runTest {
        pinchRemainingFinger(handoffBeforePinch = false)
    }

    @Test
    fun secondFingerBeforeThePagerTakesOverCancelsThePendingHandoff() = runTest {
        pinchRemainingFinger(handoffBeforePinch = true)
    }

    private suspend fun pinchRemainingFinger(handoffBeforePinch: Boolean) {
        val subject = subject(ReaderPortraitSingleSlot.LEFT)
        mountInPager(subject.holder)
        val pager = viewer().pager
        setCamera(subject.holder, 2f, -720f, 0f)
        val time = SystemClock.uptimeMillis() + 1000
        send(pager, time, time, MotionEvent.ACTION_DOWN, 700f, 1500f)
        if (handoffBeforePinch) {
            send(pager, time, time + 10, MotionEvent.ACTION_MOVE, 650f, 1500f)
        }
        val properties = Array(2) { index ->
            MotionEvent.PointerProperties().apply {
                id = index
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        fun fingers(action: Int, offset: Long, x1: Float, x2: Float) {
            val coordinates = arrayOf(x1, x2).map { position ->
                MotionEvent.PointerCoords().apply {
                    x = position
                    y = 1500f
                    pressure = 1f
                    size = 1f
                }
            }.toTypedArray()
            MotionEvent.obtain(
                time, time + offset, action, 2, properties, coordinates,
                0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0,
            ).also {
                pager.dispatchTouchEvent(it)
                it.recycle()
            }
        }
        fingers(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 20, 700f, 1000f)
        fingers(MotionEvent.ACTION_MOVE, 40, 550f, 1050f)
        fingers(MotionEvent.ACTION_MOVE, 60, 400f, 1100f)
        fingers(MotionEvent.ACTION_MOVE, 70, 300f, 1200f)
        fingers(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 80, 400f, 1100f)
        // Place the same zoomed camera at its left edge while the first finger remains down.
        val scale = ReflectionHelpers.getField<Float>(subject.holder, "currentScale")
        setCamera(subject.holder, scale, -720f * (scale - 1f), 0f)
        for (step in 1..6) send(pager, time, time + 80 + step * 30, MotionEvent.ACTION_MOVE, 400f - step * 50f, 1500f)
        send(pager, time, time + 290, MotionEvent.ACTION_UP, 100f, 1500f)
        assertEquals("A multi-pointer gesture stays with the holder after one finger lifts", 1, pager.currentItem)
        assertTrue("The real pinch must change the camera", scale > 2f)
        swipe(pager, 720f, 100f)
        assertEquals("The next single-pointer gesture can turn from that same edge", 2, pager.currentItem)
    }

    private fun mountInPager(holder: DualPagerPageHolder) {
        (holder.parent as? ViewGroup)?.removeView(holder)
        val pager = viewer().pager
        (pager.parent as? ViewGroup)?.removeView(pager)
        invokeFixture("mountInPager", holder)
    }

    private fun viewer(): DualPageR2LPagerViewer = ReflectionHelpers.getField(fixture, "viewer")

    private fun swipe(target: View, fromX: Float, toX: Float, fromY: Float = 1500f, toY: Float = fromY) {
        val time = SystemClock.uptimeMillis() + 1000
        send(target, time, time, MotionEvent.ACTION_DOWN, fromX, fromY)
        for (step in 1..12) {
            send(
                target,
                time,
                time + step * 30,
                MotionEvent.ACTION_MOVE,
                fromX + (toX - fromX) * step / 12,
                fromY + (toY - fromY) * step / 12,
            )
        }
        send(target, time, time + 390, MotionEvent.ACTION_UP, toX, toY)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
    }

    @Test
    fun halfSlotGesturesCrossTheSpineAndResetWithoutReopeningTheImage() = runTest {
        for (slot in listOf(ReaderPortraitSingleSlot.LEFT, ReaderPortraitSingleSlot.RIGHT)) {
            val subject = subject(slot)
            val initial = pixels(subject.holder)
            assertEquals(
                if (slot ==
                    ReaderPortraitSingleSlot.LEFT
                ) {
                    Rect(200, 0, 700, 1000)
                } else {
                    Rect(700, 0, 1200, 1000)
                },
                initial,
            )
            val baseScale = subject.native.scale
            doubleTap(subject.holder, initial.exactCenterX(), initial.exactCenterY())
            settle(subject.holder)
            val direction = if (slot == ReaderPortraitSingleSlot.LEFT) 1f else -1f
            drag(subject.holder, 700f, 700f + 400f * direction)
            settle(subject.holder)
            val zoomed = pixels(subject.holder)
            assertTrue("$slot must paint beyond its original Fit rectangle: $zoomed", zoomed.width() > initial.width())
            assertTrue("$slot must cross the physical spine: $zoomed", zoomed.left < 700 && zoomed.right > 700)
            assertEquals(
                "Native scale must refine image detail with the viewport",
                baseScale * 2f,
                subject.native.scale,
                0.001f,
            )
            assertEquals(
                "An ancestor-only scale cannot select native tiles",
                1f,
                subject.holder.getChildAt(0).scaleX,
                0.001f,
            )
            subject.assertRetained()
            doubleTap(subject.holder, 700f, 500f)
            settle(subject.holder)
            assertEquals(initial, pixels(subject.holder))
            assertEquals(baseScale, subject.native.scale, 0.001f)
            subject.assertRetained()
        }
    }

    @Test
    fun detachingHalfSlotReleasesNativePixelsAndRebindingCreatesFreshContent() = runTest {
        var image: ReaderPageImageView? = null
        mockkConstructor(Decoder::class)
        try {
            every { anyConstructed<Decoder>().init(any(), any()) } answers { Point(400, 800) }
            every { anyConstructed<Decoder>().decodeRegion(any(), any()) } answers {
                val region = firstArg<Rect>()
                val sample = secondArg<Int>()
                Bitmap.createBitmap(
                    (region.width() + sample - 1) / sample,
                    (region.height() + sample - 1) / sample,
                    Bitmap.Config.ARGB_8888,
                ).apply { eraseColor(Color.RED) }
            }
            every { anyConstructed<Decoder>().isReady() } returns true
            every { anyConstructed<Decoder>().recycle() } answers { Unit }
            // subject() already mounts through the real ComponentActivity window lifecycle.
            val subject = subject(ReaderPortraitSingleSlot.LEFT)
            image = subject.image
            awaitNativeCodecTasks()
            val original = ReflectionHelpers.getField<Bitmap>(subject.native, "bitmap")
            val parent = subject.holder.parent as ViewGroup
            assertTrue("The resource must belong to an attached production holder", subject.holder.isAttachedToWindow)

            parent.removeView(subject.holder)

            assertTrue("Leaving the window must release the original native bitmap", original.isRecycled)
            assertEquals("A detached native image must release its source dimensions", 0, subject.native.sWidth)

            parent.addView(subject.holder)
            load(subject.holder, subject.pages.first(), "CENTER")
            paintReaderImage(subject.image, subject.encoded)
            invokeFixture("measure", subject.holder, 1440, 3120)
            settle(subject.holder)
            assertSame(subject.native, subject.image.getChildAt(0))
            val rebound = ReflectionHelpers.getField<Bitmap>(subject.native, "bitmap")
            assertTrue("Rebinding must own a fresh live bitmap", rebound !== original && !rebound.isRecycled)
            assertTrue("The reattached production image must paint again", pixels(subject.holder).width() > 0)
        } finally {
            image?.recycle()
            try {
                awaitNativeCodecTasks()
            } finally {
                unmockkConstructor(Decoder::class)
            }
        }
    }

    @Test
    fun fractionalFitKeepsBothNativePagesPinnedToTheSpineDuringResize() = runTest {
        for ((rightWidth, rightHeight, leftWidth, leftHeight) in listOf(
            listOf(1125, 1600, 1125, 1600),
            listOf(1125, 1600, 2250, 3200),
            listOf(750, 1000, 670, 1000),
        )) {
            val subject = subject(
                ReaderPortraitSingleSlot.RIGHT,
                rightWidth,
                rightHeight,
                provisionalPair = true,
                partnerWidth = leftWidth,
                partnerHeight = leftHeight,
                actualPairFrame = true,
            )
            val partner = subject.pages[2]
            assertSame(partner, (subject.holder.displayPage as DisplayPage.Double).leftPage)
            load(subject.holder, partner, "LEFT")
            val left = descendants(subject.holder).filterIsInstance<ReaderPageImageView>().first {
                it !== subject.image
            }
            invokeFixture("measure", subject.holder, 1400, 1000)
            paintReaderImage(left, subject.partnerEncoded)
            for (width in listOf(1400, 1401, 1250, 1461, 1400)) {
                subject.holder.layoutParams = subject.holder.layoutParams.apply { this.width = width }
                invokeFixture("measure", subject.holder, width, 1000)
                settle(subject.holder)
                assertEquals(width, subject.holder.width)
                val spine = (left.parent as View).right.toFloat()
                val leftNative = left.getChildAt(0) as SubsamplingScaleImageView
                val leftEdge = requireNotNull(leftNative.sourceToViewCoord(leftWidth.toFloat(), leftHeight / 2f))
                val rightEdge = requireNotNull(subject.native.sourceToViewCoord(0f, rightHeight / 2f))
                assertEquals("Left native edge at width=$width", spine, leftEdge.x, 0.001f)
                assertEquals("Right native edge at width=$width", spine, rightEdge.x, 0.001f)
                val output = Bitmap.createBitmap(width, 1000, Bitmap.Config.ARGB_8888)
                try {
                    output.eraseColor(Color.WHITE)
                    subject.holder.draw(Canvas(output))
                    for (x in spine.toInt() - 1..spine.toInt()) {
                        assertEquals("No background at spine width=$width x=$x", Color.RED, output.getPixel(x, 500))
                    }
                } finally {
                    output.recycle()
                }
            }
        }
    }

    @Test
    fun oddViewportNativeGeometryMatchesTheGroupCameraIncludingMirroring() = runTest {
        for (orientation in listOf(ReaderImageOrientation.NORMAL, ReaderImageOrientation.MIRROR_HORIZONTAL)) {
            val subject = subject(ReaderPortraitSingleSlot.LEFT, 1125, 1600, 1401, 1001)
            val bitmap = Bitmap.createBitmap(1125, 1600, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
            subject.image.setImage(
                BitmapDrawable(subject.image.resources, bitmap),
                requireNotNull(subject.image.decodedSideConfig).copy(sourceOrientation = orientation),
            )
            for (zoom in listOf(1f, 2f, 1f)) {
                setCamera(subject.holder, zoom, if (zoom == 1f) 0f else 50f, 0f)
                settle(subject.holder)
                val bounds = requireNotNull(subject.image.viewportImageBounds())
                val first = requireNotNull(subject.native.sourceToViewCoord(0f, 0f))
                val last = requireNotNull(subject.native.sourceToViewCoord(1125f, 1600f))
                val nativeLeft = if (orientation.mirrorHorizontally) 1401f - last.x else first.x
                assertEquals("$orientation zoom=$zoom horizontal mapping", bounds.left, nativeLeft, 0.001f)
                assertEquals("$orientation zoom=$zoom vertical mapping", bounds.top, first.y, 0.001f)
            }
        }
    }

    @Test
    fun nativeViewportKeepsLockedFitAndIncreasesSamplingDetail() = runTest {
        val subject = subject(ReaderPortraitSingleSlot.LEFT, 1600, 3200)
        assertEquals(
            "The native tile viewport must span the reader, not its initial Fit rectangle",
            1400,
            subject.native.width,
        )
        assertEquals(1000, subject.native.height)
        val before = sampleSize(subject.native)
        doubleTap(subject.holder, 450f, 500f)
        settle(subject.holder)
        assertTrue("The actual SSIV sampling decision must select more detail", sampleSize(subject.native) < before)
        subject.assertRetained()
    }

    @Test
    fun sharedZoomVectorsDriveTheActualNativeImageGeometry() = runTest {
        val vectors = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "domain/src/commonTest/resources/reader/dual-page-zoom.csv") }
            .first { it.isFile }.readLines(Charsets.UTF_8)
            .filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split(',') }
        for (row in vectors) {
            val subject = subject(
                ReaderPortraitSingleSlot.valueOf(row[1]),
                row[4].toInt(),
                row[5].toInt(),
                row[2].toInt(),
                row[3].toInt(),
            )
            setCamera(subject.holder, row[6].toFloat(), row[7].toFloat(), row[8].toFloat())
            settle(subject.holder)
            val expected = Rect(row[9].toInt(), row[10].toInt(), row[11].toInt(), row[12].toInt())
            val first = requireNotNull(subject.native.sourceToViewCoord(0f, 0f))
            val last = requireNotNull(subject.native.sourceToViewCoord(row[4].toFloat(), row[5].toFloat()))
            assertEquals(row[0], expected.left.toFloat(), first.x, 1f)
            assertEquals(row[0], expected.top.toFloat(), first.y, 1f)
            assertEquals(row[0], expected.right.toFloat(), last.x, 1f)
            assertEquals(row[0], expected.bottom.toFloat(), last.y, 1f)
            expected.intersect(0, 0, subject.holder.width, subject.holder.height)
            assertEquals(row[0], expected, pixels(subject.holder))
            subject.assertRetained()
        }
    }

    @Test
    fun latePartnerAndRejectedSingleProposalsPreserveTheOriginalCameraAndMount() = runTest {
        val subject = subject(
            ReaderPortraitSingleSlot.RIGHT,
            provisionalPair = true,
            actualPairFrame = true,
        )
        doubleTap(subject.holder, 950f, 500f)
        settle(subject.holder)
        val before = pixels(subject.holder)
        val beforeImage = pixels(subject.image)
        val (owner, ticket) = requireNotNull(subject.holder.presentationScope)
        val partner = subject.pages[2]
        val partnerId = owner.identity.pageIds[partner.index]
        val invalidSingle = DisplayPage.Single(subject.pages[1], ReaderPortraitSingleSlot.RIGHT, ticket.frameId)
        org.junit.Assert.assertNull(viewer().capturePresentation(invalidSingle, Any()))
        assertSame(ticket, subject.holder.presentationScope?.second)
        invokeFixture("measure", subject.holder, 1400, 1000)
        settle(subject.holder)
        assertEquals(before, pixels(subject.holder))
        subject.assertRetained()
        load(subject.holder, partner, "LEFT")
        invokeFixture("measure", subject.holder, 1400, 1000)
        assertEquals(2f, subject.image.viewportScale, 0.001f)
        val partnerImage = descendants(subject.holder).filterIsInstance<ReaderPageImageView>().first {
            it !== subject.image
        }
        assertEquals(subject.image.viewportScale, partnerImage.viewportScale, 0.001f)
        subject.assertRetained()
        assertTrue(partnerId !in requireNotNull(owner.frame(ticket)).drawnPageIds)
        // A partial binding is still the full Pair frame; a fabricated Single cannot replace its capability.
        org.junit.Assert.assertNull(viewer().capturePresentation(invalidSingle, Any()))
        settle(subject.image)
        assertEquals(beforeImage, pixels(subject.image))
        assertSame(ticket, subject.holder.presentationScope?.second)
        assertTrue(partnerId !in requireNotNull(owner.frame(ticket)).drawnPageIds)
        subject.assertRetained()
    }

    @Test
    fun movingTheImageOutsideTheViewportCannotAcknowledgeEmptyNativeViewBounds() = runTest {
        val subject = subject(ReaderPortraitSingleSlot.LEFT)
        var preparations = 0
        var acknowledgements = 0
        subject.image.beforeImageDraw = { _, _ ->
            preparations++
            true
        }
        subject.image.onImageDrawn = { acknowledgements++ }
        setCamera(subject.holder, 2f, -700f, 0f)
        settle(subject.holder)
        assertEquals("The full native view is visible but its image is not", 0, preparations)
        assertEquals(0, acknowledgements)
        setCamera(subject.holder, 1f, 0f, 0f)
        settle(subject.holder)
        assertTrue(preparations > 0)
        assertTrue(acknowledgements > 0)
    }

    @Test
    fun mirroredNativePixelsKeepTheirPhysicalSlotBeforeAndAfterViewportZoom() = runTest {
        val colors = intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
        for (orientation in listOf(ReaderImageOrientation.MIRROR_HORIZONTAL, ReaderImageOrientation.MIRROR_VERTICAL)) {
            val subject = subject(ReaderPortraitSingleSlot.LEFT)
            val bitmap = Bitmap.createBitmap(400, 800, Bitmap.Config.ARGB_8888)
            for (y in 0 until 800) {
                for (x in 0 until 400) {
                    bitmap.setPixel(x, y, colors[(if (y < 400) 0 else 2) + (if (x < 200) 0 else 1)])
                }
            }
            subject.image.setImage(
                BitmapDrawable(subject.image.resources, bitmap),
                requireNotNull(subject.image.decodedSideConfig).copy(sourceOrientation = orientation),
            )
            for (zoom in listOf(1f, 2f, 1f)) {
                setCamera(subject.holder, zoom, if (zoom == 1f) 0f else 600f, 0f)
                settle(subject.holder)
                val bounds = requireNotNull(subject.image.viewportImageBounds())
                val output = Bitmap.createBitmap(1400, 1000, Bitmap.Config.ARGB_8888)
                try {
                    subject.holder.draw(Canvas(output))
                    for (fy in listOf(0.3f, 0.7f)) {
                        for (fx in listOf(0.3f, 0.7f)) {
                            val (sx, sy) = orientation.sourcePixel((400 * fx).toInt(), (800 * fy).toInt(), 400, 800)
                            val expected = colors[(if (sy < 400) 0 else 2) + (if (sx < 200) 0 else 1)]
                            assertEquals(
                                "$orientation zoom=$zoom at $fx,$fy",
                                expected,
                                output.getPixel(
                                    (bounds.left + bounds.width() * fx).toInt(),
                                    (
                                        bounds.top +
                                            bounds.height() * fy
                                        ).toInt(),
                                ),
                            )
                        }
                    }
                } finally {
                    output.recycle()
                }
            }
        }
    }

    @Test
    fun eachPageBackgroundStaysWithinItsImageAndCannotHideItsPartner() = runTest {
        val subject = subject(
            ReaderPortraitSingleSlot.RIGHT,
            provisionalPair = true,
            actualPairFrame = true,
        )
        val partner = subject.pages[2]
        assertSame(partner, (subject.holder.displayPage as DisplayPage.Double).leftPage)
        load(subject.holder, partner, "LEFT")
        invokeFixture("measure", subject.holder, 1400, 1000)
        val left = descendants(subject.holder).filterIsInstance<ReaderPageImageView>().first { it !== subject.image }
        paintReaderImage(left, subject.encoded)
        listOf(subject.image, left).forEach {
            it.pageBackground = ColorDrawable(Color.BLACK)
            it.onImageLoaded()
        }
        settle(subject.holder)
        assertEquals(
            "Two independent full-size native views must both remain visible",
            Rect(200, 0, 1200, 1000),
            pixels(subject.holder),
        )
        assertSame(subject.native, subject.image.getChildAt(0))
        setCamera(subject.holder, 2f, 0f, 0f)
        settle(subject.holder)
        assertEquals(Rect(0, 0, 1400, 1000), pixels(subject.holder))
    }

    @Test
    fun decodedSourceRegionRemainsCenteredWithinTheLockedInitialFit() = runTest {
        val subject = subject(ReaderPortraitSingleSlot.LEFT)
        val bitmap = Bitmap.createBitmap(400, 800, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        subject.image.setImage(
            BitmapDrawable(subject.image.resources, bitmap),
            requireNotNull(
                subject.image.decodedSideConfig,
            ).copy(sourceBounds = mihon.domain.reader.PixelBounds(100, 0, 200, 800)),
        )
        settle(subject.holder)
        assertEquals(Rect(325, 0, 575, 1000), pixels(subject.holder))
        val decoded = ReflectionHelpers.getField<Bitmap>(subject.native, "bitmap")
        setCamera(subject.holder, 2f, 600f, 0f)
        settle(subject.holder)
        assertEquals(Rect(550, 0, 1050, 1000), pixels(subject.holder))
        assertSame(decoded, ReflectionHelpers.getField(subject.native, "bitmap"))
        setCamera(subject.holder, 1f, 0f, 0f)
        settle(subject.holder)
        assertEquals(Rect(325, 0, 575, 1000), pixels(subject.holder))
    }

    @Test
    fun firstEncodedBindingChoosesBaseTilesFromItsPhysicalHalfSlot() = runTest {
        var image: ReaderPageImageView? = null
        val samples = CopyOnWriteArrayList<Int>()
        mockkConstructor(Decoder::class)
        try {
            every { anyConstructed<Decoder>().init(any(), any()) } answers { Point(1600, 3200) }
            every { anyConstructed<Decoder>().decodeRegion(any(), any()) } answers {
                val region = firstArg<Rect>()
                val sample = secondArg<Int>()
                samples += sample
                Bitmap.createBitmap(
                    (region.width() + sample - 1) / sample,
                    (region.height() + sample - 1) / sample,
                    Bitmap.Config.ARGB_8888,
                ).apply { eraseColor(Color.RED) }
            }
            every { anyConstructed<Decoder>().isReady() } returns true
            every { anyConstructed<Decoder>().recycle() } answers { Unit }
            val subject = subject(ReaderPortraitSingleSlot.LEFT, 1600, 3200, 1080, 2400)
            awaitNativeCodecTasks()
            val fresh = ReaderPageImageView(subject.image.context).also { image = it }
            val parent = subject.image.parent as ViewGroup
            val config = requireNotNull(subject.image.decodedSideConfig)
            parent.removeView(subject.image)
            subject.image.recycle()
            parent.addView(fresh)
            invokeFixture("measure", subject.holder, 1080, 2400)
            samples.clear()
            fresh.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
                override fun onChildViewAdded(parent: View?, child: View?) {
                    val native = child as SubsamplingScaleImageView
                    native.setExecutor { it.run() }
                    // Metadata can finish after a normal layout. Exercise that actual ordering on
                    // the first encoded binding, before any native bitmap or decoder exists.
                    native.measure(
                        View.MeasureSpec.makeMeasureSpec(fresh.width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(fresh.height, View.MeasureSpec.EXACTLY),
                    )
                    native.layout(0, 0, fresh.width, fresh.height)
                }

                override fun onChildViewRemoved(parent: View?, child: View?) = Unit
            })
            fresh.setImage(Buffer().write(subject.encoded), false, config, 0)
            settle(subject.holder)
            val native = fresh.getChildAt(0) as SubsamplingScaleImageView
            assertTrue("The first production binding must execute native tile tasks", samples.isNotEmpty())
            assertEquals(
                "The first base must use half-slot Fit before requesting any tiles",
                2,
                ReflectionHelpers.getField<Int>(native, "fullImageSampleSize"),
            )
            assertTrue(
                "Initial Fit must not decode unnecessary full-resolution tiles: $samples",
                samples.all { it >= 2 },
            )
            assertEquals(Rect(0, 660, 540, 1740), pixels(subject.holder))
        } finally {
            image?.recycle()
            try {
                awaitNativeCodecTasks()
            } finally {
                unmockkConstructor(Decoder::class)
            }
        }
    }

    private fun awaitNativeCodecTasks() {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
        while (ReaderPageImageView.foregroundWork.value > 0 && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(
            "Codec tasks must finish before removing the JVM codec seam",
            0,
            ReaderPageImageView.foregroundWork.value,
        )
    }

    @Test
    fun realNativeTilePipelineDecodesAndDrawsFinerRegionsWithoutReplacingItsDecoder() = runTest {
        val subject = subject(ReaderPortraitSingleSlot.LEFT, 1600, 3200)
        val decoders = java.util.IdentityHashMap<Any, Bitmap>()
        val samples = CopyOnWriteArrayList<Int>()
        var sourceOpens = 0
        mockkConstructor(Decoder::class)
        try {
            // Robolectric's BitmapRegionDecoder shadow creates empty bitmaps and ignores sampling.
            // Adapt only the codec seam using real decoded source pixels and Canvas resampling.
            // SSIV still owns all tasks, tile grids, visibility and final drawing. The corresponding
            // instrumentation case separately executes the shipped JNI region decoder unchanged.
            every { anyConstructed<Decoder>().init(any(), any()) } answers {
                val provider = secondArg<InputProvider>()
                val decoder = requireNotNull(provider.openStream()).use {
                    requireNotNull(BitmapFactory.decodeStream(it))
                }
                decoders[self] = decoder
                sourceOpens++
                Point(decoder.width, decoder.height)
            }
            every { anyConstructed<Decoder>().decodeRegion(any(), any()) } answers {
                val sample = secondArg<Int>()
                samples += sample
                val region = firstArg<Rect>()
                val tile = Bitmap.createBitmap(
                    (region.width() + sample - 1) / sample,
                    (region.height() + sample - 1) / sample,
                    Bitmap.Config.ARGB_8888,
                )
                Canvas(tile).drawBitmap(
                    requireNotNull(decoders[self]),
                    region,
                    Rect(0, 0, tile.width, tile.height),
                    Paint(Paint.FILTER_BITMAP_FLAG),
                )
                tile
            }
            every { anyConstructed<Decoder>().isReady() } answers { decoders[self]?.isRecycled == false }
            every { anyConstructed<Decoder>().recycle() } answers {
                decoders.remove(self)?.recycle()
                Unit
            }
            subject.native.setExecutor { it.run() }
            subject.native.setMaxTileSize(256)
            val config = requireNotNull(subject.image.decodedSideConfig)
            subject.image.setImage(Buffer().write(subject.encoded), false, config, 0)
            settle(subject.holder)
            assertTrue("The real base tile tasks must decode", samples.isNotEmpty())
            val baseSample = samples.min()
            val decoder = ReflectionHelpers.getField<Any>(subject.native, "decoder")
            val opensBefore = sourceOpens
            samples.clear()
            doubleTap(subject.holder, 450f, 500f)
            settle(subject.holder)
            assertTrue("Actual zoom tile tasks must request finer regions: $samples", samples.any { it < baseSample })
            val tiles = ReflectionHelpers.getField<Map<Int, List<Any>>>(subject.native, "tileMap")
            val fineBitmaps = tiles.filterKeys { it < baseSample }.values.flatten()
                .mapNotNull { ReflectionHelpers.getField<Bitmap?>(it, "bitmap") }
            assertTrue("Finer native tiles must actually finish decoding", fineBitmaps.isNotEmpty())
            val output = Bitmap.createBitmap(subject.holder.width, subject.holder.height, Bitmap.Config.ARGB_8888)
            val drawn = mutableListOf<Bitmap>()
            try {
                subject.holder.draw(object : Canvas(output) {
                    override fun drawBitmap(bitmap: Bitmap, matrix: Matrix, paint: Paint?) {
                        drawn += bitmap
                        super.drawBitmap(bitmap, matrix, paint)
                    }
                })
                assertTrue(
                    "SSIV must draw an actual finer tile",
                    drawn.any { drawnBitmap ->
                        fineBitmaps.any {
                            it ===
                                drawnBitmap
                        }
                    },
                )
            } finally {
                output.recycle()
            }
            assertSame(decoder, ReflectionHelpers.getField(subject.native, "decoder"))
            assertEquals("Zoom must not reopen the original codec stream", opensBefore, sourceOpens)
            assertTrue(pixels(subject.holder).width() > 500)
            subject.image.recycle()
        } finally {
            decoders.values.forEach { if (!it.isRecycled) it.recycle() }
            unmockkConstructor(Decoder::class)
        }
    }

    @Test
    fun encodedDualPageTilesCoverTheSpineAtFractionalFitAndAfterZoomReset() = runTest {
        assertEncodedTileSpine()
    }

    @Test
    fun encodedMirroredDualPageTilesCoverTheSpineAtFractionalFitAndAfterZoomReset() = runTest(timeout = 180.seconds) {
        for ((mirrorLeft, mirrorRight) in listOf(false to true, true to false, true to true)) {
            assertEncodedTileSpine(mirrorLeft, mirrorRight)
        }
    }

    private suspend fun assertEncodedTileSpine(mirrorLeft: Boolean = false, mirrorRight: Boolean = false) {
        val subject = subject(
            ReaderPortraitSingleSlot.RIGHT,
            4501,
            6401,
            provisionalPair = true,
            actualPairFrame = true,
        )
        val partner = subject.pages[2]
        val display = subject.holder.displayPage as DisplayPage.Double
        assertSame(partner, display.leftPage)
        assertTrue("Encoded tiles must use the selected production frame", display.frameId != null)
        load(subject.holder, partner, "LEFT")
        val left = descendants(subject.holder).filterIsInstance<ReaderPageImageView>().first {
            it !== subject.image
        }
        val images = listOf(left to subject.partnerEncoded, subject.image to subject.encoded)
        val decoders = java.util.IdentityHashMap<Any, Bitmap>()
        mockkConstructor(Decoder::class)
        try {
            // Adapt only Robolectric's codec seam. SSIV owns the real tile grid and draw matrices.
            every { anyConstructed<Decoder>().init(any(), any()) } answers {
                val provider = secondArg<InputProvider>()
                val pixels = requireNotNull(provider.openStream()).use {
                    requireNotNull(BitmapFactory.decodeStream(it))
                }
                decoders[self] = pixels
                Point(pixels.width, pixels.height)
            }
            every { anyConstructed<Decoder>().decodeRegion(any(), any()) } answers {
                val region = firstArg<Rect>()
                val sample = secondArg<Int>()
                Bitmap.createBitmap(
                    (region.width() + sample - 1) / sample,
                    (region.height() + sample - 1) / sample,
                    Bitmap.Config.ARGB_8888,
                ).also { tile ->
                    Canvas(tile).drawBitmap(
                        requireNotNull(decoders[self]),
                        region,
                        Rect(0, 0, tile.width, tile.height),
                        Paint(Paint.FILTER_BITMAP_FLAG),
                    )
                }
            }
            every { anyConstructed<Decoder>().isReady() } answers { decoders[self]?.isRecycled == false }
            every { anyConstructed<Decoder>().recycle() } answers {
                decoders.remove(self)?.recycle()
                Unit
            }
            for ((image, encoded) in images) {
                val native = image.getChildAt(0) as SubsamplingScaleImageView
                native.setExecutor { it.run() }
                native.setMaxTileSize(256)
                val mirrored = if (image === left) mirrorLeft else mirrorRight
                val config = requireNotNull(image.decodedSideConfig).let {
                    if (mirrored) it.copy(sourceOrientation = ReaderImageOrientation.MIRROR_HORIZONTAL) else it
                }
                image.setImage(Buffer().write(encoded), false, config, 0)
            }
            for (height in listOf(1000, 1001)) {
                for (width in listOf(1283, 1327, 1393, 1282, 1284, 1400, 1401)) {
                    subject.holder.layoutParams = subject.holder.layoutParams.apply {
                        this.width = width
                        this.height = height
                    }
                    invokeFixture("measure", subject.holder, width, height)
                    settle(subject.holder)
                    assertEquals(
                        "The real window must retain the requested viewport width",
                        width,
                        subject.holder.width,
                    )
                    assertEquals(
                        "The real window must retain the requested viewport height",
                        height,
                        subject.holder.height,
                    )
                    val spine = (left.parent as View).right
                    fun assertSpinePixels() {
                        val tiles = images.flatMap { (image, _) ->
                            val native = image.getChildAt(0) as SubsamplingScaleImageView
                            assertEquals(
                                "Encoded regression must not fall back to a whole bitmap",
                                null,
                                ReflectionHelpers.getField<Bitmap?>(native, "bitmap"),
                            )
                            ReflectionHelpers.getField<Map<Int, List<Any>>>(native, "tileMap").values.flatten()
                                .mapNotNull { ReflectionHelpers.getField<Bitmap?>(it, "bitmap") }
                        }
                        val drawn = mutableListOf<Bitmap>()
                        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        try {
                            output.eraseColor(Color.WHITE)
                            subject.holder.draw(object : Canvas(output) {
                                override fun drawBitmap(bitmap: Bitmap, matrix: Matrix, paint: Paint?) {
                                    drawn += bitmap
                                    super.drawBitmap(bitmap, matrix, paint)
                                }
                            })
                            assertTrue("Actual SSIV tiles must reach the final canvas", drawn.any { it in tiles })
                            for (y in height / 5 until height * 4 / 5) {
                                for (x in spine - 1..spine) {
                                    assertEquals(
                                        "Tile seam at ${width}x$height x=$x y=$y",
                                        Color.RED,
                                        output.getPixel(x, y),
                                    )
                                }
                            }
                        } finally {
                            output.recycle()
                        }
                    }
                    assertSpinePixels()
                    doubleTap(subject.holder, spine.toFloat(), height / 2f)
                    settle(subject.holder)
                    assertSpinePixels()
                    doubleTap(subject.holder, spine.toFloat(), height / 2f)
                    settle(subject.holder)
                    assertSpinePixels()
                }
            }
        } finally {
            images.forEach { it.first.recycle() }
            awaitNativeCodecTasks()
            decoders.values.forEach { if (!it.isRecycled) it.recycle() }
            unmockkConstructor(Decoder::class)
        }
    }

    private fun setCamera(holder: DualPagerPageHolder, scale: Float, x: Float, y: Float) {
        ReflectionHelpers.setField(holder, "currentScale", scale)
        ReflectionHelpers.setField(holder, "translateX", x)
        ReflectionHelpers.setField(holder, "translateY", y)
        ReflectionHelpers.callInstanceMethod<Unit>(holder, "applyTransform")
    }

    private fun sampleSize(native: SubsamplingScaleImageView): Int = ReflectionHelpers.callInstanceMethod(
        native,
        "calculateInSampleSize",
        ReflectionHelpers.ClassParameter.from(Float::class.javaPrimitiveType!!, native.scale),
    )

    private suspend fun subject(
        slot: ReaderPortraitSingleSlot,
        sourceWidth: Int = 400,
        sourceHeight: Int = 800,
        viewportWidth: Int = 1400,
        viewportHeight: Int = 1000,
        provisionalPair: Boolean = false,
        partnerWidth: Int = sourceWidth,
        partnerHeight: Int = sourceHeight,
        actualPairFrame: Boolean = false,
    ): Subject {
        val viewer = ReflectionHelpers.getField<DualPageR2LPagerViewer>(fixture, "viewer")
        val source = Bitmap.createBitmap(sourceWidth, sourceHeight, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.RED)
        }
        val encoded = ByteArrayOutputStream().also { source.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        source.recycle()
        val partnerBitmap = Bitmap.createBitmap(partnerWidth, partnerHeight, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.RED)
        }
        val partnerEncoded = ByteArrayOutputStream().also {
            partnerBitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        partnerBitmap.recycle()
        val opens = IntArray(4)
        val chapter = ReaderChapter(Chapter.create().copy(id = 61011 + mounted.size.toLong(), mangaId = 1))
        val pages = List(4) { index ->
            ReaderPage(index, stream = {
                opens[index]++
                (if (index == 2) partnerEncoded else encoded).inputStream()
            }).apply {
                this.chapter = chapter
                status = Page.State.Ready
            }
        }
        chapter.publishLoadedPageListForTest(pages)
        pages.forEach { it.publishHeaderFact() }
        if (actualPairFrame && !ReflectionHelpers.getField<Boolean>(viewer, "isIdle")) {
            settleOriginalPager(viewer) { mainScheduler.runCurrent() }
        }
        viewer.setChapters(ViewerChapters(chapter, null, null))
        val page = if (provisionalPair) {
            pages[1]
        } else if (slot ==
            ReaderPortraitSingleSlot.LEFT
        ) {
            pages.first()
        } else {
            pages.last()
        }
        if (actualPairFrame) {
            // Initial window/configuration callbacks retire undrawn descriptors in the old context.
            invokeFixture("measure", viewer.pager, viewportWidth, viewportHeight)
            mainScheduler.runCurrent()
        }
        viewer.moveToPage(page)
        if (actualPairFrame) {
            mainScheduler.runCurrent()
            settleOriginalPager(viewer) { mainScheduler.runCurrent() }
        }
        val display = if (actualPairFrame) {
            viewer.adapter.items[viewer.adapter.positionForPage(page)] as DisplayPage
        } else if (provisionalPair) {
            DisplayPage.Single(page, slot)
        } else {
            viewer.adapter.items.filterIsInstance<DisplayPage>().single { it.containsPage(page) }
        }
        if (actualPairFrame) {
            assertSame(page, (display as DisplayPage.Double).rightPage)
            assertEquals(viewer.adapter.session(chapter)?.targetFrameId, display.frameId)
        } else {
            assertEquals(slot, (display as DisplayPage.Single).slot)
        }
        val holder = DualPagerPageHolder(viewer.activity, viewer, display)
        mounted += holder
        load(holder, page, "CENTER")
        invokeFixture("measure", holder, viewportWidth, viewportHeight)
        val image = if (actualPairFrame) {
            ReflectionHelpers.getField<ReaderPageImageView>(holder, "rightHolder")
        } else {
            descendants(holder).filterIsInstance<ReaderPageImageView>().single()
        }
        paintReaderImage(image, encoded)
        val native = image.getChildAt(0) as SubsamplingScaleImageView
        settle(holder)
        val bitmap = ReflectionHelpers.getField<Bitmap>(native, "bitmap")
        val imageParent = image.parent
        val attachment = ReflectionHelpers.getField<Long>(holder, "attachmentGeneration")
        val opensBefore = opens[page.index]
        return Subject(holder, image, native, pages, encoded, partnerEncoded) {
            assertTrue(descendants(holder).any { it === image })
            assertSame(native, image.getChildAt(0))
            assertSame(imageParent, image.parent)
            assertEquals(attachment, ReflectionHelpers.getField<Long>(holder, "attachmentGeneration"))
            assertSame(
                "Zoom retains the exact decoded bitmap",
                bitmap,
                ReflectionHelpers.getField<Bitmap>(native, "bitmap"),
            )
            assertEquals("Zoom must not reopen encoded source", opensBefore, opens[page.index])
        }
    }

    private data class Subject(
        val holder: DualPagerPageHolder,
        val image: ReaderPageImageView,
        val native: SubsamplingScaleImageView,
        val pages: List<ReaderPage>,
        val encoded: ByteArray,
        val partnerEncoded: ByteArray,
        val assertRetained: () -> Unit,
    )

    private suspend fun load(holder: DualPagerPageHolder, page: ReaderPage, side: String) {
        val method = fixture.javaClass.declaredMethods.single { it.name == "load" }.apply { isAccessible = true }
        suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
            val result = method.invoke(fixture, holder, page, side, continuation)
            if (result === COROUTINE_SUSPENDED) COROUTINE_SUSPENDED else Unit
        }
    }

    private fun invokeFixture(name: String, vararg args: Any) {
        fixture.javaClass.declaredMethods.single { it.name == name && it.parameterCount == args.size }
            .apply { isAccessible = true }.invoke(fixture, *args)
    }

    private fun doubleTap(target: View, x: Float, y: Float) {
        val time = SystemClock.uptimeMillis()
        for (down in listOf(time, time + 100)) {
            send(target, down, down, MotionEvent.ACTION_DOWN, x, y)
            send(target, down, down + 20, MotionEvent.ACTION_UP, x, y)
        }
    }

    private fun drag(target: View, fromX: Float, toX: Float) {
        val time = SystemClock.uptimeMillis()
        send(target, time, time, MotionEvent.ACTION_DOWN, fromX, 500f)
        for (step in 1..6) {
            send(target, time, time + step * 30, MotionEvent.ACTION_MOVE, fromX + (toX - fromX) * step / 6, 500f)
        }
        send(target, time, time + 210, MotionEvent.ACTION_UP, toX, 500f)
    }

    private fun send(target: View, down: Long, time: Long, action: Int, x: Float, y: Float) {
        MotionEvent.obtain(down, time, action, x, y, 0).also {
            target.dispatchTouchEvent(it)
            it.recycle()
        }
    }

    private fun settle(holder: View) {
        val output = Bitmap.createBitmap(holder.width, holder.height, Bitmap.Config.ARGB_8888)
        try {
            repeat(40) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
                holder.draw(Canvas(output))
            }
        } finally {
            output.recycle()
        }
    }

    private fun pixels(holder: View): Rect {
        val output = Bitmap.createBitmap(holder.width, holder.height, Bitmap.Config.ARGB_8888)
        try {
            holder.draw(Canvas(output))
            val pixels = IntArray(output.width * output.height)
            output.getPixels(pixels, 0, output.width, 0, 0, output.width, output.height)
            val bounds = Rect()
            pixels.forEachIndexed { index, color ->
                if (Color.red(color) > 220 && Color.green(color) < 30 && Color.blue(color) < 30 &&
                    Color.alpha(color) > 220
                ) {
                    val x = index % output.width
                    val y = index / output.width
                    bounds.union(x, y, x + 1, y + 1)
                }
            }
            assertTrue("Production view must paint actual red pixels", !bounds.isEmpty)
            return bounds
        } finally {
            output.recycle()
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
