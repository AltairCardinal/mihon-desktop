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
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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

/** Native pixels and gestures exercise the original holder, viewport, image and decode ownership. */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
@Config(sdk = [35], manifest = Config.NONE, qualifiers = "w480dp-h1040dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DualPageViewportZoomTest {
    private val fixture = DualPagerPageHolderLayoutTest()
    private val mounted = mutableListOf<DualPagerPageHolder>()
    private lateinit var mainScheduler: kotlinx.coroutines.test.TestCoroutineScheduler

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
    fun uncertifiedOriginalNeverPreparesNativeSlot() = runTest {
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
        org.junit.Assert.assertNull(
            "Header/Ready alone must not issue a native slot capability",
            holder.presentationScope,
        )
        assertTrue(requireNotNull(viewer.adapter.session(chapter)).displayedHistory.isEmpty())
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
        awaitProduction { entered.size == 2 }
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
        chapter.pageLoader = object : eu.kanade.tachiyomi.ui.reader.loader.PageLoader() {
            override var isLocal = false
            override suspend fun getPages() = pages
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
            "entered=$entered location=${owner.location} proof=${owner.certifyPairing()} optional=${eu.kanade.tachiyomi.ui.reader.viewer.AndroidReaderSpreadSampler.optionalWork.value}"
        }) {
            entered.size ==
                2
        }
        val location = owner.location
        val budget = owner.factBudget
        assertEquals(
            "Both required consumers must actually be suspended before advancing their deadline",
            2,
            entered.size,
        )
        testScheduler.advanceTimeBy(mihon.domain.reader.ReaderDualPageFactLimits.MAX_WAIT_MILLIS + 1)
        runCurrent()
        assertEquals("The deadline must cancel the actual suspended loader calls", entered, cancelled)
        val blocked = owner.certifyPairing() as mihon.domain.reader.ReaderPairingCertification.RetryableBlocked
        assertTrue(blocked.failures.values.all { it == mihon.domain.reader.ReaderAnalysisFailure.RESOURCE })
        repeat(5) { runCurrent() }
        assertEquals(2, entered.size)
        assertSame(budget, owner.factBudget)
        assertEquals(location, owner.location)
        hanging = false
        chapter.pageLoader!!.retryPage(pages[2])
        awaitCertified(viewer, chapter, 2)
        assertEquals(location, owner.location)
        assertTrue(owner.factBudget !== budget)
    }

    @Test
    fun longPrefixStopsAtSixteenOriginalPagesWithoutResettingItsLocationBudget() = runTest {
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
                "started=${started.get()} location=${owner.location} proof=${owner.certifyPairing()} optional=${eu.kanade.tachiyomi.ui.reader.viewer.AndroidReaderSpreadSampler.optionalWork.value}"
            }) {
                started.get() ==
                    2
            }
            assertEquals(2, peak.get())
            firstBatch.countDown()
            awaitProduction {
                owner.certifyPairing() is mihon.domain.reader.ReaderPairingCertification.RetryableBlocked
            }
            assertEquals(16, started.get())
            assertEquals(16, recycled.get())
            assertEquals(2, peak.get())
            assertSame(budget, owner.factBudget)
            assertEquals(location, owner.location)
            repeat(5) {
                viewer.adapter.refreshPairing()
                runCurrent()
            }
            assertEquals("Completion and refresh cannot admit another page", 16, started.get())
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
        assertEquals(2, calls.get())
        assertEquals(mihon.domain.reader.ReaderDualPageFactLimits.MAX_NEW_BYTES, bytes.get())
        assertSame(budget, owner.factBudget)
        assertEquals(location, owner.location)
        repeat(5) { runCurrent() }
        assertEquals(2, calls.get())
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
            val number = entered.incrementAndGet()
            val actual = eu.kanade.tachiyomi.ui.reader.viewer.decodedRegionFactory(input) { recycled.incrementAndGet() }
            object : eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageRegionDecoder {
                override fun decode(region: Rect): Bitmap {
                    if (number <= 2) {
                        physicalEntered.incrementAndGet()
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
                "entered=${entered.get()} physical=${physicalEntered.get()} location=${owner.location} proof=${owner.certifyPairing()} optional=${eu.kanade.tachiyomi.ui.reader.viewer.AndroidReaderSpreadSampler.optionalWork.value}"
            }) {
                physicalEntered.get() ==
                    2
            }
            requireNotNull(chapter.pages)[2].retryHeaderFact()
            runCurrent()
            assertEquals(location, owner.location)
            assertTrue(owner.factBudget !== budget)
            assertEquals(0, recycled.get())
            assertEquals("Logical cancellation must not hide still-running physical decoders", 2, entered.get())
            assertTrue(
                owner.identity.pageIds.all {
                    owner.originalAnalysis(it) !is mihon.domain.reader.ReaderPageAnalysisEvidence.Computed
                },
            )
            release.countDown()
            awaitCertified(viewer, chapter, 2)
            awaitProduction { entered.get() == recycled.get() }
            assertTrue("Retry must run after original resources really exit", entered.get() > 2)
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
    fun readyNativeImageCertificationErrorRetriesFromItsActualWidgetAndClearsFeedback() = runTest {
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
        suspend fun bindReady(page: ReaderPage, sideName: String) {
            val sideType = DualPagerPageHolder::class.java.declaredClasses.single { it.simpleName == "Side" }
            val side = sideType.enumConstants.single { it.toString() == sideName }
            val method = DualPagerPageHolder::class.java.declaredMethods.single { it.name == "setImage" }
                .apply { isAccessible = true }
            suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
                val result = method.invoke(holder, page, side, continuation)
                if (result === COROUTINE_SUSPENDED) COROUTINE_SUSPENDED else Unit
            }
        }
        bindReady(pages[1], "RIGHT")
        bindReady(pages[2], "LEFT")
        invokeFixture("measure", holder, 1400, 1000)
        descendants(holder).filterIsInstance<ReaderPageImageView>().forEach { image ->
            val native = ReflectionHelpers.getField<SubsamplingScaleImageView>(image, "pageView")
            native.setImage(
                com.davemorrissey.labs.subscaleview.ImageSource.bitmap(
                    requireNotNull(BitmapFactory.decodeByteArray(encoded, 0, encoded.size)),
                ),
            )
            val output = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
            try {
                image.draw(Canvas(output))
                assertTrue("The actual normal bitmap is Ready while certification refuses painting", native.isReady)
                org.junit.Assert.assertNull(holder.presentationScope)
            } finally {
                output.recycle()
            }
        }
        holder.beginPresentation()
        invokeFixture("measure", holder, 1400, 1000)
        val error = ReflectionHelpers.getField<eu.kanade.tachiyomi.databinding.ReaderErrorBinding>(
            holder,
            "errorLayout",
        )
        assertEquals(View.VISIBLE, error.root.visibility)
        assertEquals(View.VISIBLE, error.actionRetry.visibility)
        assertTrue(error.actionRetry.width > 0 && error.actionRetry.height > 0)
        descendants(holder).filterIsInstance<ReaderPageImageView>().forEach { it.onImageLoaded?.invoke() }
        assertEquals(
            "Normal Loaded callbacks cannot hide a still-blocked certification error",
            View.VISIBLE,
            error.root.visibility,
        )
        assertTrue(owner.displayedHistory.none { it.drawnPageIds.isNotEmpty() })
        val clickedPage = ReflectionHelpers.getField<ReaderPage>(holder, "errorPage")
        useEncodedOriginalProducer(viewer)
        assertTrue(error.actionRetry.performClick())
        awaitCertified(viewer, chapter, 2)
        assertEquals(listOf(clickedPage), retries)
        assertEquals(location, owner.location)
        holder.beginPresentation()
        assertTrue(
            "A recovered certificate must clear the actual error overlay without re-decoding an already Ready image",
            error.root.visibility != View.VISIBLE,
        )
        descendants(holder).filterIsInstance<ReaderPageImageView>().forEach { paintReaderImage(it, encoded) }
        settle(holder)
        val ticket = requireNotNull(holder.presentationScope).second
        assertEquals(setOf(owner.identity.pageIds[1], owner.identity.pageIds[2]), owner.frame(ticket)!!.drawnPageIds)
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
        fixture.tearDown()
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
    fun latePartnerAndSingleDoubleUpdatesPreserveTheOriginalCameraAndMount() = runTest {
        val subject = subject(ReaderPortraitSingleSlot.RIGHT, provisionalPair = true)
        doubleTap(subject.holder, 950f, 500f)
        settle(subject.holder)
        val before = pixels(subject.holder)
        val single = subject.holder.displayPage
        val partner = subject.pages[2]
        assertTrue(subject.holder.updateDisplayPage(DisplayPage.Double(subject.pages[1], partner)))
        // The ready-but-unbound partner must not change the image already being read.
        invokeFixture("measure", subject.holder, 1400, 1000)
        settle(subject.holder)
        assertEquals(before, pixels(subject.holder))
        subject.assertRetained()
        load(subject.holder, partner, "LEFT")
        invokeFixture("measure", subject.holder, 1400, 1000)
        assertEquals(2f, subject.image.viewportScale, 0.001f)
        val partnerImage = descendants(subject.holder).filterIsInstance<ReaderPageImageView>().first {
            it !==
                subject.image
        }
        assertEquals(subject.image.viewportScale, partnerImage.viewportScale, 0.001f)
        subject.assertRetained()
        // A partner that has not actually painted can still leave without releasing the survivor.
        assertTrue(subject.holder.updateDisplayPage(single))
        settle(subject.holder)
        assertEquals(before, pixels(subject.holder))
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
        val subject = subject(ReaderPortraitSingleSlot.RIGHT, provisionalPair = true)
        val partner = subject.pages[2]
        assertTrue(subject.holder.updateDisplayPage(DisplayPage.Double(subject.pages[1], partner)))
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
    ): Subject {
        val viewer = ReflectionHelpers.getField<DualPageR2LPagerViewer>(fixture, "viewer")
        val source = Bitmap.createBitmap(sourceWidth, sourceHeight, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.RED)
        }
        val encoded = ByteArrayOutputStream().also { source.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        source.recycle()
        val opens = IntArray(4)
        val chapter = ReaderChapter(Chapter.create().copy(id = 61011 + mounted.size.toLong(), mangaId = 1))
        val pages = List(4) { index ->
            ReaderPage(index, stream = {
                opens[index]++
                encoded.inputStream()
            }).apply {
                this.chapter = chapter
                status = Page.State.Ready
            }
        }
        chapter.publishLoadedPageListForTest(pages)
        pages.forEach { it.publishHeaderFact() }
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
        viewer.moveToPage(page)
        val display = if (provisionalPair) {
            DisplayPage.Single(page, slot)
        } else {
            viewer.adapter.items.filterIsInstance<DisplayPage>().single { it.containsPage(page) }
        }
        assertEquals(slot, (display as DisplayPage.Single).slot)
        val holder = DualPagerPageHolder(viewer.activity, viewer, display)
        mounted += holder
        load(holder, page, "CENTER")
        invokeFixture("measure", holder, viewportWidth, viewportHeight)
        val image = descendants(holder).filterIsInstance<ReaderPageImageView>().single()
        paintReaderImage(image, encoded)
        val native = image.getChildAt(0) as SubsamplingScaleImageView
        settle(holder)
        val bitmap = ReflectionHelpers.getField<Bitmap>(native, "bitmap")
        val imageParent = image.parent
        val attachment = ReflectionHelpers.getField<Long>(holder, "attachmentGeneration")
        val opensBefore = opens[page.index]
        return Subject(holder, image, native, pages, encoded) {
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
