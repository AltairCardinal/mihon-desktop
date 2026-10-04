package eu.kanade.tachiyomi.ui.reader.viewer

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.AndroidChapterPairingCoordinator
import eu.kanade.tachiyomi.ui.reader.HostReaderArtFixture
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.viewer.pager.DualPageR2LPagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.pager.DualPagerPageHolder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mihon.domain.reader.ChapterPairingRepository
import mihon.domain.reader.ChapterPairingSnapshot
import mihon.domain.reader.ReaderPairingPersistenceStatus
import mihon.domain.reader.scheduler.ReaderPageMaterializeRunner
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addFactory
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Actual original SSIV tasks and original encoded JNI sampling share the production reader. */
class SharedReaderResourceNativeAcceptanceTest {
    @Test
    fun coldFixtureRemovesTheRealImageFromOriginalCacheJournal() {
        HostReaderArtFixture("copymanga", "real-b2-original.cbz").use { fixture ->
            fixture.cold()
            fixture.configure(mode = 6, matching = false)
            fixture.launch(initialPage = 19)
            val original = SharedDualPageOriginalImagesNativeTest()
            fixture.await("The original page must be fetched, decoded and painted before testing cold reset") {
                original.observeOriginal(fixture, 19) != null
            }
            var imageUrl: String? = null
            fixture.onReaderMain { activity ->
                val viewer = activity.viewModel.state.value.viewer as DualPageR2LPagerViewer
                imageUrl = requireNotNull(viewer.adapter.currentChapter).pages?.get(19)?.imageUrl
            }
            val key = eu.kanade.tachiyomi.util.storage.DiskUtil.hashKeyForDisk(requireNotNull(imageUrl))
            val before = fixture.cacheJournalState()
            assertTrue(before.first > 0 && key in before.second)
            fixture.cold()
            val after = fixture.cacheJournalState()
            android.util.Log.i(
                "ReaderCacheDiagnostic",
                "protocol key=$key beforeBytes=${before.first} afterBytes=${after.first} " +
                    "entryBefore=${key in before.second} entryAfter=${key in after.second}",
            )
            assertTrue("Cold must remove this actual image from the original cache journal", key !in after.second)
            assertTrue(after.first < before.first)
            assertEquals(0, ReaderPageMaterializeRunner.processPhysicalWorkCount.value)
            assertEquals(0, ReaderPageImageView.foregroundWork.value)
            assertEquals(
                0,
                fixture.context.cacheDir.resolve("chapter_disk_cache").listFiles().orEmpty()
                    .count { it.extension == "0" },
            )
        }
    }

    @Test
    fun rapidTargetDrawsWhileTheEarlierSourceFactIsStillUnknown() {
        HostReaderArtFixture("copymanga", "real-b2-original.cbz").use { fixture ->
            fixture.cold()
            fixture.configure(mode = 6, matching = true)
            val firstEntered = CountDownLatch(1)
            val firstRelease = CountDownLatch(1)
            val earlierEntered = CountDownLatch(1)
            val earlierRelease = CountDownLatch(1)
            val targetEntered = CountDownLatch(1)
            val targetRelease = CountDownLatch(1)
            fixture.beforeImageRequest.set { index ->
                if (index == 0) {
                    firstEntered.countDown()
                    firstRelease.await(30, TimeUnit.SECONDS)
                }
                if (index == 2) {
                    earlierEntered.countDown()
                    earlierRelease.await(30, TimeUnit.SECONDS)
                }
                if (index == 3) {
                    targetEntered.countDown()
                    targetRelease.await(30, TimeUnit.SECONDS)
                }
            }
            val observation = NativePerformanceObservation(0, 0, 0, true, false)
            var tree: ViewTreeObserver? = null
            var listener: ViewTreeObserver.OnPreDrawListener? = null
            try {
                fixture.launch(initialPage = 0)
                fixture.await("The initial HTTP must enter before observing actual callbacks") {
                    firstEntered.count == 0L
                }
                fixture.onReaderMain { activity ->
                    tree = activity.window.decorView.viewTreeObserver
                    listener = ViewTreeObserver.OnPreDrawListener {
                        observation.observe(activity.window.decorView)
                        true
                    }
                    tree?.addOnPreDrawListener(listener)
                }
                firstRelease.countDown()
                performancePixels(fixture, observation, 0)
                repeat(3) { fixture.key(KeyEvent.KEYCODE_DPAD_LEFT) }
                fixture.await("The original target HTTP must enter after real rapid navigation") {
                    targetEntered.count == 0L && earlierEntered.count == 0L && currentSource(fixture) == 3
                }
                fixture.onReaderMain { activity ->
                    val viewer = activity.viewModel.state.value.viewer as DualPageR2LPagerViewer
                    val chapter = requireNotNull(viewer.adapter.currentChapter)
                    val owner = requireNotNull(viewer.adapter.session(chapter))
                    assertEquals(3, owner.location?.pageId?.sourcePageIndex)
                    assertEquals(
                        mihon.domain.reader.ReaderPageFactState.UNKNOWN,
                        owner.pageFact(owner.identity.pageIds[2])?.state,
                    )
                    assertEquals(1L, earlierRelease.count)
                }
                targetRelease.countDown()
                performancePixels(fixture, observation, 3)
                fixture.onReaderMain { activity ->
                    val viewer = activity.viewModel.state.value.viewer as DualPageR2LPagerViewer
                    val owner = requireNotNull(viewer.adapter.session(requireNotNull(viewer.adapter.currentChapter)))
                    val target = owner.identity.pageIds[3]
                    assertEquals(
                        mihon.domain.reader.ReaderPortraitSingleSlot.RIGHT,
                        owner.targetUnit?.slots?.single { it.pageId == target }?.position,
                    )
                    assertTrue(owner.displayedHistory.any { target in it.drawnPageIds })
                    assertEquals(
                        mihon.domain.reader.ReaderPageFactState.UNKNOWN,
                        owner.pageFact(owner.identity.pageIds[2])?.state,
                    )
                    assertTrue(requireNotNull(viewer.adapter.currentChapter).pages!![2].status != Page.State.Ready)
                    assertEquals(1L, earlierRelease.count)
                }
                fixture.await("The actual drawn set must reach original maximum-page SQL progress") {
                    var drawnMaximum: Long? = null
                    fixture.onReaderMain { activity ->
                        val viewer = activity.viewModel.state.value.viewer as DualPageR2LPagerViewer
                        val owner =
                            requireNotNull(viewer.adapter.session(requireNotNull(viewer.adapter.currentChapter)))
                        val target = owner.identity.pageIds[3]
                        val drawn = owner.displayedHistory.firstOrNull { target in it.drawnPageIds }?.drawnPageIds
                        if (drawn != null) {
                            assertTrue(target in drawn && owner.identity.pageIds[2] !in drawn)
                            drawnMaximum = drawn.maxOf { it.sourcePageIndex }.toLong()
                        }
                    }
                    drawnMaximum != null && fixture.progress().first == drawnMaximum
                }
            } catch (error: Throwable) {
                fixture.onReaderMain { activity ->
                    val viewer = activity.viewModel.state.value.viewer as? DualPageR2LPagerViewer
                    val owner = viewer?.adapter?.currentChapter?.let { viewer.adapter.session(it) }
                    val failureFacts = (2..3).associateWith {
                        owner?.identity?.pageIds?.getOrNull(it)?.let { id -> owner.pageFact(id) }
                    }
                    android.util.Log.e("ReaderPerformanceDiagnostic", "failureFacts=$failureFacts")
                    for (image in observation.mountedImages(activity.window.decorView)) {
                        val holder = generateSequence(image.parent) { it.parent }
                            .filterIsInstance<DualPagerPageHolder>().firstOrNull() ?: continue
                        val page = generateSequence<Class<*>>(image.javaClass) { it.superclass }
                            .flatMap { it.declaredFields.asSequence() }.firstOrNull { it.name == "page" }
                            ?.apply { isAccessible = true }?.get(image) as? ReaderPage ?: continue
                        val native = (0 until image.childCount).map(image::getChildAt)
                            .filterIsInstance<SubsamplingScaleImageView>().singleOrNull()
                        val rectangle = android.graphics.Rect()
                        val visible = image.getGlobalVisibleRect(rectangle)
                        val bound = listOf("boundContents", "boundEncodedContents").associateWith { name ->
                            (
                                DualPagerPageHolder::class.java.getDeclaredField(name).apply { isAccessible = true }
                                    .get(holder) as Map<*, *>
                                )[page]
                        }
                        val scope = holder.presentationScope
                        android.util.Log.e(
                            "ReaderPerformanceDiagnostic",
                            "failureChild source=${page.index} shown=${image.isShown} " +
                                "visible=$visible rect=$rectangle " +
                                "native=${native?.sWidth}x${native?.sHeight}/" +
                                "${native?.isReady}/${native?.isImageLoaded} " +
                                "status=${page.status} content=${page.contentIdentity()} " +
                                "encoded=${page.encodedContentIdentity()} bound=$bound " +
                                "ticket=${scope?.second} frame=${scope?.let { it.first.frame(it.second) }}",
                        )
                    }
                }
                throw error
            } finally {
                firstRelease.countDown()
                targetRelease.countDown()
                earlierRelease.countDown()
                fixture.onReaderMain { if (tree?.isAlive == true) tree?.removeOnPreDrawListener(listener) }
                observation.close()
            }
        }
    }

    /** Fixed 4 x 6 x 3 development comparison, with original B2 payloads and original native callbacks. */
    @Test
    fun originalPayloadFourConditionsSixScenesHaveNoMatchingDependencyWait() {
        val arguments = androidx.test.platform.app.InstrumentationRegistry.getArguments()
        val scenes = listOf(
            "cold-home",
            "warm-sequential",
            "slow-partner",
            "rapid-keys",
            "cold-jump-reverse",
            "300-pages",
        ).filter { arguments.getString("performanceScene")?.split(',')?.contains(it) ?: true }
        val conditions = listOf("off", "on", "suspended", "foreground-pressure").filter {
            arguments.getString("performanceCondition")?.let { selected -> it == selected } ?: true
        }
        repeat(arguments.getString("performanceRepeats")?.toInt() ?: 3) { repeat ->
            for (condition in conditions) {
                for (scene in scenes) {
                    HostReaderArtFixture(
                        "copymanga",
                        "real-b2-original.cbz",
                        300.takeIf { scene == "300-pages" },
                    ).use { fixture ->
                        val count = if (scene == "300-pages") 300 else 22
                        val initial = when (scene) {
                            "slow-partner" -> 3
                            "cold-jump-reverse" -> 19
                            "300-pages" -> 199
                            else -> 0
                        }
                        val optionalEntered = CountDownLatch(1)
                        val optionalRelease = CountDownLatch(1)
                        val optionalExpired = AtomicBoolean()
                        if (condition == "suspended") {
                            fixture.cold()
                            fixture.configure(mode = 6, matching = false)
                            fixture.launch(initialPage = 3)
                            val donorObservation = NativePerformanceObservation(0, 0, 0, false, false)
                            var donorTree: ViewTreeObserver? = null
                            var donorListener: ViewTreeObserver.OnPreDrawListener? = null
                            if (arguments.getString("performanceDonorDiagnostic") == "true") {
                                fixture.onReaderMain { activity ->
                                    android.util.Log.i(
                                        "ReaderPerformanceDiagnostic",
                                        "donor scene=$scene expectedCount=$count " +
                                            "responseBytes=${fixture.success.size}",
                                    )
                                    donorTree = activity.window.decorView.viewTreeObserver
                                    donorListener = ViewTreeObserver.OnPreDrawListener {
                                        donorObservation.observe(
                                            activity.window.decorView,
                                            frame = false,
                                            errorsOnly = true,
                                        )
                                        true
                                    }
                                    donorTree?.addOnPreDrawListener(donorListener)
                                }
                            }
                            try {
                                fixture.await("Original donor foreground must finish before optional work") {
                                    var idle = false
                                    fixture.onReaderMain { activity ->
                                        val viewer = activity.viewModel.state.value.viewer as? DualPageR2LPagerViewer
                                        val chapter = viewer?.adapter?.currentChapter
                                        val owner = chapter?.let { viewer.adapter.session(it) }
                                        idle = chapter?.pages?.slice(3..4)?.all {
                                            it.status == Page.State.Ready
                                        } == true && ReaderPageImageView.foregroundWork.value == 0 &&
                                            chapter.pageLoader?.hasForegroundWork == false && owner != null &&
                                            (3..4).all { index ->
                                                owner.pageFact(owner.identity.pageIds[index])?.state ==
                                                    mihon.domain.reader.ReaderPageFactState.PORTRAIT
                                            }
                                    }
                                    idle
                                }
                            } finally {
                                fixture.onReaderMain {
                                    if (donorTree?.isAlive == true) donorTree?.removeOnPreDrawListener(donorListener)
                                }
                                donorObservation.close()
                            }
                            fixture.onReaderMain { activity ->
                                val viewer = activity.viewModel.state.value.viewer as DualPageR2LPagerViewer
                                val original = viewer.adapter.spreadDecoderFactory
                                viewer.adapter.spreadDecoderFactory = { stream ->
                                    optionalEntered.countDown()
                                    if (!optionalRelease.await(90, TimeUnit.SECONDS)) optionalExpired.set(true)
                                    original(stream)
                                }
                            }
                            fixture.matching(true)
                            fixture.await("The actual old optional native task must enter its transparent gate") {
                                optionalEntered.count == 0L
                            }
                        }
                        fixture.cold()
                        fixture.configure(mode = 6, matching = condition in listOf("on", "suspended"))
                        val targetEntered = CountDownLatch(1)
                        val targetRelease = CountDownLatch(1)
                        val partnerRelease = CountDownLatch(1)
                        val pendingGeometryRelease = CountDownLatch(1)
                        fixture.beforeImageRequest.set { index ->
                            if (index == initial) {
                                targetEntered.countDown()
                                targetRelease.await(30, TimeUnit.SECONDS)
                            }
                            if (scene == "slow-partner" && index == 4) partnerRelease.await(30, TimeUnit.SECONDS)
                            if (arguments.getString("performanceGeometryChange") == "pending-scale" &&
                                index in 2..3
                            ) {
                                pendingGeometryRelease.await(30, TimeUnit.SECONDS)
                            }
                        }
                        val measurement = NativePerformanceObservation(
                            fixture.imageRequests.get(),
                            fixture.chapterRequests.get(),
                            fixture.responsePayloadBytes.get(),
                            arguments.getString("performanceDiagnostic") == "true",
                            condition == "foreground-pressure",
                        )
                        lateinit var tree: ViewTreeObserver
                        lateinit var listener: ViewTreeObserver.OnPreDrawListener
                        var mounted = false
                        try {
                            val opening = System.nanoTime()
                            fixture.launch(initialPage = initial)
                            fixture.await("Normal target HTTP must enter before installing observers") {
                                targetEntered.count == 0L
                            }
                            fixture.onReaderMain { activity ->
                                measurement.observeState(activity)
                                tree = activity.window.decorView.viewTreeObserver
                                listener = ViewTreeObserver.OnPreDrawListener {
                                    measurement.observe(activity.window.decorView)
                                    if (condition == "foreground-pressure") {
                                        measurement.event("preDrawTrim", activity)
                                        activity.onTrimMemory(
                                            android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
                                        )
                                    }
                                    true
                                }
                                tree.addOnPreDrawListener(listener)
                                mounted = true
                                measurement.observe(activity.window.decorView, frame = false)
                            }
                            if (condition == "foreground-pressure") {
                                fixture.onReaderMain { activity ->
                                    measurement.event("beforeInitialTrim", activity)
                                    activity.onTrimMemory(
                                        android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
                                    )
                                    assertTrue(activity.optionalWorkUnderMemoryPressure.value)
                                }
                                fixture.matching(true)
                                fixture.onReaderMain {
                                    measurement.event("settingOnReturned", it)
                                    assertTrue(it.optionalWorkUnderMemoryPressure.value)
                                    assertEquals(0, AndroidReaderSpreadSampler.optionalWork.value)
                                }
                            }
                            fixture.onReaderMain { measurement.event("targetRelease", it) }
                            targetRelease.countDown()
                            performancePixels(fixture, measurement, initial)
                            if (arguments.getString("performanceGeometryChange") == "scale") {
                                fixture.configure(mode = 6, matching = condition == "on", scale = 2)
                                fixture.await("Original scale must change the real native geometry context") {
                                    var changed = false
                                    fixture.onReaderMain { activity ->
                                        val viewer = activity.viewModel.state.value.viewer as DualPageR2LPagerViewer
                                        changed = viewer.config.imageScaleType == 2 &&
                                            measurement.mountedImages(activity.window.decorView)
                                                .any { image ->
                                                    generateSequence(image.parent) { it.parent }
                                                        .filterIsInstance<
                                                            DualPagerPageHolder,
                                                            >().firstOrNull()?.presentationScope?.second
                                                        ?.frameId?.context == 1L
                                                }
                                    }
                                    changed
                                }
                            }
                            when (scene) {
                                "warm-sequential" -> {
                                    measurement.markInput()
                                    fixture.key(KeyEvent.KEYCODE_DPAD_LEFT)
                                    performancePixels(fixture, measurement, currentSource(fixture))
                                }
                                "slow-partner" -> {
                                    assertEquals(1L, partnerRelease.count)
                                    partnerRelease.countDown()
                                    performancePixels(fixture, measurement, 4)
                                }
                                "rapid-keys" -> {
                                    measurement.markInput()
                                    repeat(3) { fixture.key(KeyEvent.KEYCODE_DPAD_LEFT) }
                                    if (arguments.getString("performanceGeometryChange") == "pending-scale") {
                                        fixture.onReaderMain { activity ->
                                            val viewer = activity.viewModel.state.value.viewer as DualPageR2LPagerViewer
                                            val owner = requireNotNull(
                                                viewer.adapter.session(requireNotNull(viewer.adapter.currentChapter)),
                                            )
                                            assertEquals(4, activity.viewModel.state.value.currentPage)
                                            val pending = requireNotNull(viewer.adapter.currentChapter).pages!![3]
                                            assertTrue(
                                                "Original target HTTP remains held",
                                                pending.status != Page.State.Ready,
                                            )
                                            android.util.Log.i(
                                                "ReaderPerformanceDiagnostic",
                                                "pendingScale source=3 status=${pending.status} " +
                                                    "facts2=${owner.pageFact(owner.identity.pageIds[2])} " +
                                                    "target=${owner.targetUnit}",
                                            )
                                            assertEquals(
                                                listOf(2, 3),
                                                owner.targetUnit?.slots?.map { it.pageId.sourcePageIndex },
                                            )
                                            assertEquals(
                                                mihon.domain.reader.ReaderPageFactState.UNKNOWN,
                                                owner.pageFact(owner.identity.pageIds[2])?.state,
                                            )
                                        }
                                        fixture.configure(mode = 6, matching = true, scale = 2)
                                        fixture.await("Original pending target must adopt the real scale context") {
                                            var changed = false
                                            fixture.onReaderMain { activity ->
                                                val viewer = activity.viewModel.state.value.viewer
                                                    as DualPageR2LPagerViewer
                                                val pager = activity.findViewById<ViewGroup>(
                                                    eu.kanade.tachiyomi.R.id.reader_pager,
                                                )
                                                changed = viewer.config.imageScaleType == 2 &&
                                                    (0 until pager.childCount).map(pager::getChildAt)
                                                        .filterIsInstance<DualPagerPageHolder>().any { holder ->
                                                            holder.displayPage.visiblePages.any { it.index == 3 } &&
                                                                holder.presentationScope?.second?.frameId?.context == 1L
                                                        }
                                            }
                                            changed
                                        }
                                        pendingGeometryRelease.countDown()
                                    }
                                    performancePixels(fixture, measurement, currentSource(fixture))
                                }
                                "cold-jump-reverse" -> {
                                    measurement.markInput()
                                    fixture.key(KeyEvent.KEYCODE_DPAD_RIGHT)
                                    performancePixels(fixture, measurement, currentSource(fixture))
                                }
                            }
                            fixture.onReaderMain { activity ->
                                if (condition == "suspended") {
                                    assertEquals(1, AndroidReaderSpreadSampler.optionalWork.value)
                                    assertEquals(1L, optionalRelease.count)
                                }
                                if (condition == "foreground-pressure") {
                                    measurement.event("pixelsObserved", activity)
                                    // Real idle MemoryInfo refresh can end trim pressure; an admitted original
                                    // task remains counted until its actual completion after a later trim.
                                    assertTrue(AndroidReaderSpreadSampler.optionalWork.value <= 1)
                                }
                            }
                            var manual = emptySet<Int>()
                            fixture.onReaderMain { activity ->
                                val viewer = activity.viewModel.state.value.viewer as DualPageR2LPagerViewer
                                manual = requireNotNull(
                                    viewer.adapter.session(
                                        requireNotNull(viewer.adapter.currentChapter),
                                    ),
                                ).pairingState.committedForcedSinglePages
                            }
                            measurement.print(repeat, condition, scene, opening, fixture, count, initial, manual)
                        } finally {
                            targetRelease.countDown()
                            partnerRelease.countDown()
                            pendingGeometryRelease.countDown()
                            optionalRelease.countDown()
                            measurement.close()
                            if (mounted) {
                                fixture.onReaderMain {
                                    if (tree.isAlive) tree.removeOnPreDrawListener(listener)
                                }
                            }
                        }
                        assertTrue("Only a bounded native test gate is used", !optionalExpired.get())
                        fixture.finish()
                        fixture.await("The actual original optional task must exit before the next paired scene") {
                            AndroidReaderSpreadSampler.optionalWork.value == 0
                        }
                        if (condition == "foreground-pressure") {
                            android.util.Log.i(
                                "ReaderPerformanceDiagnostic",
                                "event=sceneFinally:$repeat:$scene ns=${System.nanoTime()} actualOptional=0",
                            )
                        }
                    }
                }
            }
        }
    }

    private fun currentSource(fixture: HostReaderArtFixture): Int {
        var index = -1
        fixture.onReaderMain { index = it.viewModel.state.value.currentPage - 1 }
        check(index >= 0)
        return index
    }

    private fun performancePixels(
        fixture: HostReaderArtFixture,
        observation: NativePerformanceObservation,
        source: Int,
    ) {
        observation.inputSource = source
        val original = SharedDualPageOriginalImagesNativeTest()
        fixture.await("Original source $source must expose real JNI and independently matched window pixels") {
            val pixels = observation.paint.containsKey(source) && original.observeOriginal(fixture, source) != null
            if (pixels) observation.recordPixels(source)
            pixels
        }
        check(observation.ready.containsKey(source)) { "Original SSIV onReady must actually be forwarded" }
    }

    private class NativePerformanceObservation(
        private val requestBaseline: Int,
        private val pageTableBaseline: Int,
        private val byteBaseline: Long,
        private val diagnostic: Boolean,
        private val pressureWave: Boolean,
    ) {
        private val observerScope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate,
        )

        fun event(label: String, activity: eu.kanade.tachiyomi.ui.reader.ReaderActivity) {
            if (!diagnostic && (!pressureWave || label == "preDrawTrim")) return
            val info = android.app.ActivityManager.MemoryInfo()
            (activity.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager)
                .getMemoryInfo(info)
            android.util.Log.i(
                "ReaderPerformanceDiagnostic",
                "event=$label ns=${System.nanoTime()} pressure=${activity.optionalWorkUnderMemoryPressure.value} " +
                    "optional=${AndroidReaderSpreadSampler.optionalWork.value} lowMemory=${info.lowMemory} " +
                    "focus=${activity.hasWindowFocus()} lifecycle=${activity.lifecycle.currentState}",
            )
        }

        fun observeState(activity: eu.kanade.tachiyomi.ui.reader.ReaderActivity) {
            if (!diagnostic && !pressureWave) return
            observerScope.launch {
                activity.optionalWorkUnderMemoryPressure.collect { event("pressureFlow:$it", activity) }
            }
            observerScope.launch {
                AndroidReaderSpreadSampler.optionalWork.collect { event("optionalFlow:$it", activity) }
            }
        }

        fun close() {
            observerScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            layoutHooks.forEach { (view, hook) -> view.removeOnLayoutChangeListener(hook) }
            layoutHooks.clear()
        }

        val ready = ConcurrentHashMap<Int, Long>()
        val paint = ConcurrentHashMap<Int, Long>()
        val identities = ConcurrentHashMap<Int, String>()
        val frames = ConcurrentHashMap<Int, mihon.domain.reader.ReaderDualPageFrameId>()
        val slots = ConcurrentHashMap<Int, List<Int>>()
        var inputSource = -1
        private val loadedHooks = IdentityHashMap<ReaderPageImageView, (() -> Unit)?>()
        private val paintHooks = IdentityHashMap<ReaderPageImageView, (() -> Unit)?>()
        private val errorHooks = IdentityHashMap<ReaderPageImageView, ((Throwable?) -> Unit)?>()
        private val prepareHooks = IdentityHashMap<ReaderPageImageView, ((Int, Int) -> Boolean)?>()
        private val observedErrors = mutableSetOf<String>()
        private val layoutHooks = IdentityHashMap<View, View.OnLayoutChangeListener>()
        private val frameIntervals = mutableListOf<Long>()
        private var previousFrame = 0L
        private var input = 0L
        private var inputPaint = 0L
        private var inputPixels = 0L
        private var heap = 0L
        private var nativeHeap = 0L
        private var nativeWorkPeak = 0
        private var optionalWorkPeak = 0

        fun markInput() {
            input = System.nanoTime()
            inputPaint = 0
            inputPixels = 0
        }

        fun recordPixels(source: Int) {
            if (input > 0 && inputSource == source && inputPixels == 0L) inputPixels = System.nanoTime()
        }

        fun mountedImages(root: View): List<ReaderPageImageView> {
            fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup) {
                (0 until view.childCount).flatMap { views(view.getChildAt(it)) }
            } else {
                emptyList()
            }
            return views(root).filterIsInstance<ReaderPageImageView>()
        }

        fun observe(root: View, frame: Boolean = true, errorsOnly: Boolean = false) {
            val now = System.nanoTime()
            if (frame) {
                if (previousFrame != 0L) frameIntervals += now - previousFrame
                previousFrame = now
            }
            val runtime = Runtime.getRuntime()
            heap = maxOf(heap, runtime.totalMemory() - runtime.freeMemory())
            nativeHeap = maxOf(nativeHeap, Debug.getNativeHeapAllocatedSize())
            nativeWorkPeak = maxOf(nativeWorkPeak, ReaderPageImageView.foregroundWork.value)
            optionalWorkPeak = maxOf(optionalWorkPeak, AndroidReaderSpreadSampler.optionalWork.value)
            fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup) {
                (0 until view.childCount).flatMap { views(view.getChildAt(it)) }
            } else {
                emptyList()
            }
            val mounted = views(root)
            if (diagnostic) {
                mounted.filter { it.id == eu.kanade.tachiyomi.R.id.reader_pager }.forEach { pager ->
                    if (pager !in layoutHooks) {
                        val hook = View.OnLayoutChangeListener {
                                _,
                                left,
                                top,
                                right,
                                bottom,
                                oldLeft,
                                oldTop,
                                oldRight,
                                oldBottom,
                            ->
                            android.util.Log.i(
                                "ReaderPerformanceDiagnostic",
                                "pagerLayout=${right - left}x${bottom - top} " +
                                    "old=${oldRight - oldLeft}x${oldBottom - oldTop}",
                            )
                        }
                        pager.addOnLayoutChangeListener(hook)
                        layoutHooks[pager] = hook
                        android.util.Log.i(
                            "ReaderPerformanceDiagnostic",
                            "pagerMounted=${pager.width}x${pager.height}",
                        )
                    }
                }
            }
            for (message in mounted.filterIsInstance<android.widget.TextView>()) {
                if (message.id == eu.kanade.tachiyomi.R.id.error_message && message.isShown) {
                    val text = message.text.toString()
                    if (observedErrors.add(text)) android.util.Log.i("ReaderPerformanceDiagnostic", "errorUI=$text")
                }
            }
            for (image in mounted.filterIsInstance<ReaderPageImageView>()) {
                val page = generateSequence<Class<*>>(image.javaClass) { it.superclass }
                    .flatMap { it.declaredFields.asSequence() }.firstOrNull { it.name == "page" }
                    ?.apply { isAccessible = true }?.get(image) as? ReaderPage ?: continue
                if (diagnostic) {
                    val holder = generateSequence(image.parent) { it.parent }
                        .filterIsInstance<DualPagerPageHolder>()
                        .firstOrNull()
                    fun describe(label: String) {
                        val bound = listOf("boundContents", "boundEncodedContents", "renderedPages").associateWith {
                            DualPagerPageHolder::class.java
                                .getDeclaredField(it).apply { isAccessible = true }.get(holder) as Map<*, *>
                        }.mapValues { it.value[page] }
                        val native = views(image).filterIsInstance<SubsamplingScaleImageView>().singleOrNull()
                        val scope = holder?.presentationScope
                        val state = "$label source=${page.index} image=${System.identityHashCode(image)} " +
                            "encoded=${page.encodedContentIdentity()} state=${page.status} bound=$bound " +
                            "native=${native?.sWidth}x${native?.sHeight}/${native?.isReady}/${native?.isImageLoaded} " +
                            "fact=${scope?.first?.pageFact(scope.first.identity.pageIds[page.index])} " +
                            "frame=${scope?.let { it.first.frame(it.second) }} " +
                            "holderBounds=${holder?.left},${holder?.top},${holder?.right},${holder?.bottom} " +
                            "readyKeys=${ready.keys.sorted()} paintKeys=${paint.keys.sorted()}"
                        if (observedErrors.add(state)) android.util.Log.i("ReaderPerformanceDiagnostic", state)
                    }
                    if (holder != null) describe("preDraw")
                    if (image.beforeImageDraw != null && image.beforeImageDraw !== prepareHooks[image]) {
                        val original = image.beforeImageDraw
                        val hook: (Int, Int) -> Boolean = { width, height ->
                            val accepted = requireNotNull(original).invoke(width, height)
                            if (holder != null) describe("actualBeforeDraw:$accepted")
                            accepted
                        }
                        image.beforeImageDraw = hook
                        prepareHooks[image] = hook
                    }
                }
                if (image.onImageLoadError != null && image.onImageLoadError !== errorHooks[image]) {
                    val original = image.onImageLoadError
                    val hook: (Throwable?) -> Unit = { error ->
                        android.util.Log.e(
                            "ReaderPerformanceDiagnostic",
                            "source=${page.index} identity=${page.encodedContentIdentity()} " +
                                "state=${page.status} error=${error?.javaClass?.name}:${error?.message}",
                            error,
                        )
                        requireNotNull(original).invoke(error)
                    }
                    image.onImageLoadError = hook
                    errorHooks[image] = hook
                }
                if (errorsOnly) continue
                if (image.onImageLoaded != null && image.onImageLoaded !== loadedHooks[image]) {
                    val original = image.onImageLoaded
                    val hook = {
                        requireNotNull(original).invoke()
                        ready.putIfAbsent(page.index, System.nanoTime())
                        identities[page.index] = page.encodedContentIdentity()
                        Unit
                    }
                    image.onImageLoaded = hook
                    loadedHooks[image] = hook
                }
                if (image.onImageDrawn != null && image.onImageDrawn !== paintHooks[image]) {
                    val original = image.onImageDrawn
                    val hook = {
                        requireNotNull(original).invoke()
                        val painted = System.nanoTime()
                        paint.putIfAbsent(page.index, painted)
                        generateSequence(image.parent) { it.parent }
                            .filterIsInstance<DualPagerPageHolder>()
                            .firstOrNull()?.presentationScope?.let { (owner, ticket) ->
                                frames.putIfAbsent(page.index, ticket.frameId)
                                owner.frame(ticket)?.let { frame ->
                                    slots[page.index] = frame.unit.slots.map { it.pageId.sourcePageIndex }
                                }
                            }
                        if (input > 0 && painted >= input && inputPaint == 0L && page.index == inputSource) {
                            inputPaint = painted
                        }
                        Unit
                    }
                    image.onImageDrawn = hook
                    paintHooks[image] = hook
                }
            }
        }

        fun print(
            repeat: Int,
            condition: String,
            scene: String,
            opening: Long,
            fixture: HostReaderArtFixture,
            count: Int,
            target: Int,
            manual: Set<Int>,
        ) {
            val painted = requireNotNull(paint[target])
            val readyAt = requireNotNull(ready[target])
            val frame = requireNotNull(frames[target])
            val second = slots[target].orEmpty().firstOrNull { it != target && frames[it] == frame }

            val fields = linkedMapOf(
                "platform" to "android", "repeat" to repeat, "condition" to condition, "scene" to scene,
                "source" to target, "identity" to identities[target], "frame" to frame,
                "openingNs" to opening, "readyNs" to readyAt, "paintNs" to painted,
                "readyToPaintNs" to (painted - readyAt), "secondSource" to second,
                "secondReadyNs" to second?.let { ready[it] }, "secondPaintNs" to second?.let { paint[it] },
                "inputNs" to input, "inputChildDrawNs" to inputPaint.takeIf { it > 0 },
                "inputPixelObservedUpperNs" to inputPixels.takeIf { it > 0 },
                "frameIntervalNs" to frameIntervals.toList(),
                "requests" to (fixture.imageRequests.get() - requestBaseline),
                "pageTableRequests" to (fixture.chapterRequests.get() - pageTableBaseline),
                "responsePayloadBytes" to (fixture.responsePayloadBytes.get() - byteBaseline),
                "javaHeapObservedPeak" to heap, "nativeHeapObservedPeak" to nativeHeap,
                "nativeWorkObservedPeak" to nativeWorkPeak, "optionalWorkObservedPeak" to optionalWorkPeak,
                "pageCount" to count, "manualBaseline" to manual,
            )
            android.util.Log.i("ReaderPerformance", fields.entries.joinToString(" ") { "${it.key}=${it.value}" })
            assertTrue(painted >= readyAt)
            if (input > 0) assertTrue(inputPixels >= input)
        }
    }

    @Test
    fun heldOriginalPairingRecordDoesNotDelayNativePixelsOrMoveExposedSlots() = runBlocking<Unit> {
        val repository = Injekt.get<ChapterPairingRepository>()
        val originalCoordinator = Injekt.get<AndroidChapterPairingCoordinator>()
        originalCoordinator.awaitAcceptedOperations()
        val entered = CompletableDeferred<Pair<Long, Long>>()
        val release = CompletableDeferred<Unit>()
        val coordinator = AndroidChapterPairingCoordinator(object : ChapterPairingRepository by repository {
            override suspend fun load(chapterId: Long, mangaId: Long): ChapterPairingSnapshot {
                entered.complete(chapterId to mangaId)
                release.await()
                return repository.load(chapterId, mangaId)
            }
        })
        // addSingleton retains an already materialized singleton in this Injekt version.
        // The original factory API lets the actual new ViewModel resolve this same decorator.
        Injekt.addFactory<AndroidChapterPairingCoordinator> { coordinator }
        assertTrue(Injekt.get<AndroidChapterPairingCoordinator>() === coordinator)
        try {
            HostReaderArtFixture("copymanga").use { fixture ->
                val encoded = SharedSpreadNativeAcceptanceTest()
                fixture.images.set(List(22) { encoded.encodedPage(it, 1, false) })
                fixture.cold()
                fixture.configure(mode = 6, matching = false)
                try {
                    fixture.launch(initialPage = 1)
                    fixture.onReaderMain { activity ->
                        val field = activity.viewModel.javaClass.getDeclaredField("pairingCoordinator")
                        field.isAccessible = true
                        assertTrue(
                            "The real ViewModel must use the original gated repository",
                            field.get(
                                activity.viewModel,
                            ) === coordinator,
                        )
                        android.util.Log.i("SharedReaderRestoreNative", "ACTUAL_COORDINATOR same=true")
                    }
                    fixture.await("The actual original pairing read must enter while ordinary pages remain available") {
                        entered.isCompleted
                    }
                    val (chapterId, mangaId) = entered.await()
                    val snapshot = repository.load(chapterId, mangaId)
                    repository.replace(chapterId, mangaId, snapshot.revision, 22, setOf(1))
                    fixture.awaitStable("Real Copy HTTP and native first pixels must not wait for the held record") {
                        fixture.containsColor(Color.RED) && fixture.containsColor(Color.GREEN)
                    }
                    fixture.await("Both actual slots must reach real SQL progress before record completion") {
                        fixture.progress().first == 2L
                    }
                    lateinit var owner: mihon.domain.reader.SharedDualPageSession
                    fixture.onReaderMain { activity ->
                        val viewer = activity.viewModel.state.value.viewer as DualPageR2LPagerViewer
                        owner = requireNotNull(viewer.adapter.session(requireNotNull(viewer.adapter.currentChapter)))
                        assertEquals(ReaderPairingPersistenceStatus.RESTORING, owner.pairingState.status)
                        assertEquals(null, owner.pairingState.revision)
                        assertEquals(null, viewer.pairingAdjustmentRequest())
                        assertTrue(!activity.viewModel.state.value.pairingRecordReady)
                    }
                    val first = fixture.observeSlot(Color.RED)
                    assertTrue(first.content.left >= fixture.windowWidth() / 2)
                    val x = first.content.exactCenterX()
                    val y = first.content.exactCenterY()
                    val now = SystemClock.uptimeMillis()
                    fixture.touchWindow(MotionEvent.ACTION_DOWN, now, now, x, y)
                    fixture.touchWindow(MotionEvent.ACTION_UP, now, now + 30, x, y)
                    fixture.touchWindow(MotionEvent.ACTION_DOWN, now + 100, now + 100, x, y)
                    fixture.touchWindow(MotionEvent.ACTION_UP, now + 100, now + 130, x, y)
                    fixture.awaitStable("Ordinary double tap must zoom while the original record is held") {
                        fixture.observeSlot(Color.RED).scaleX > 1f
                    }
                    val red = fixture.observeSlot(Color.RED)
                    val green = fixture.observeSlot(Color.GREEN)
                    val events = fixture.progress().second
                    android.util.Log.i(
                        "SharedReaderRestoreNative",
                        "HELD red=$red green=$green sql=${fixture.progress()}",
                    )
                    lateinit var frame: mihon.domain.reader.ReaderDualPageFrame
                    fixture.onReaderMain {
                        frame = owner.displayedHistory.single {
                            it.unit.slots.any { slot ->
                                slot.pageId.sourcePageIndex == 1
                            }
                        }
                    }
                    release.complete(Unit)
                    fixture.await("Only the original read result must restore the same owner without navigation") {
                        var restored = false
                        fixture.onReaderMain { activity ->
                            val viewer = activity.viewModel.state.value.viewer as DualPageR2LPagerViewer
                            restored = viewer.adapter.session(
                                requireNotNull(viewer.adapter.currentChapter),
                            ) === owner &&
                                owner.pairingState.status == ReaderPairingPersistenceStatus.READY &&
                                activity.viewModel.state.value.pairingRecordReady
                        }
                        restored
                    }
                    fixture.onReaderMain { assertEquals(setOf(1), owner.pairingState.committedForcedSinglePages) }
                    repeat(12) {
                        assertEquals(red, fixture.observeSlot(Color.RED))
                        assertEquals(green, fixture.observeSlot(Color.GREEN))
                        assertEquals(events, fixture.progress().second)
                        fixture.onReaderMain {
                            assertEquals(frame, owner.displayedHistory.single { it.id == frame.id })
                        }
                        SystemClock.sleep(20)
                    }
                    android.util.Log.i(
                        "SharedReaderRestoreNative",
                        "READY red=${fixture.observeSlot(Color.RED)} green=${fixture.observeSlot(Color.GREEN)} " +
                            "sql=${fixture.progress()} native=${fixture.nativePresentationState()}",
                    )
                    fixture.key(KeyEvent.KEYCODE_DPAD_LEFT)
                    fixture.await("Real source navigation remains available after late record completion") {
                        var selected = false
                        fixture.onReaderMain { selected = owner.location?.pageId?.sourcePageIndex == 3 }
                        selected && fixture.containsColor(Color.BLUE) && fixture.progress().first >= 3L
                    }
                } finally {
                    release.complete(Unit)
                }
            }
        } finally {
            release.complete(Unit)
            coordinator.awaitAcceptedOperations()
            Injekt.addSingleton(originalCoordinator)
        }
    }

    @Test
    fun originalSystemTrimPausesOptionalJniAndWindowRecoveryResumesSameOwner() {
        HostReaderArtFixture("copymanga").use { fixture ->
            val encodedFixture = SharedSpreadNativeAcceptanceTest()
            fixture.images.set(List(22) { encodedFixture.encodedPage(it, 1, false) })
            fixture.cold()
            fixture.configure(mode = 6, matching = false)
            fixture.launch()
            fixture.await("Original foreground cover pixels must be visible") { fixture.containsColor(Color.CYAN) }
            lateinit var viewer: DualPageR2LPagerViewer
            lateinit var owner: mihon.domain.reader.SharedDualPageSession
            fixture.await("Original five portraits must be Ready and physically idle before system trim") {
                var ready = false
                fixture.onReaderMain { activity ->
                    viewer = activity.viewModel.state.value.viewer as DualPageR2LPagerViewer
                    val chapter = requireNotNull(viewer.adapter.currentChapter)
                    owner = requireNotNull(viewer.adapter.session(chapter))
                    ready = chapter.pages?.take(5)?.all { it.status == Page.State.Ready } == true &&
                        chapter.pageLoader?.hasForegroundWork == false &&
                        ReaderPageImageView.foregroundWork.value == 0 && owner.identity.pageIds.take(5).all {
                            owner.pageFact(it)?.state == mihon.domain.reader.ReaderPageFactState.PORTRAIT
                        }
                }
                ready
            }
            val memory = android.app.ActivityManager.MemoryInfo()
            (fixture.context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager)
                .getMemoryInfo(memory)
            assertTrue("This bounded test sends real trim without manufacturing system OOM", !memory.lowMemory)
            val trim = AtomicInteger(-1)
            val observer = object : android.content.ComponentCallbacks2 {
                override fun onTrimMemory(level: Int) {
                    trim.set(level)
                }
                override fun onLowMemory() = Unit
                override fun onConfigurationChanged(config: android.content.res.Configuration) = Unit
            }
            val application = fixture.context.applicationContext
            application.registerComponentCallbacks(observer)
            val release = CountDownLatch(1)
            val reads = AtomicInteger()
            try {
                fixture.onReaderMain {
                    val original = viewer.adapter.spreadDecoderFactory
                    viewer.adapter.spreadDecoderFactory = { input ->
                        reads.incrementAndGet()
                        check(release.await(30, TimeUnit.SECONDS))
                        original(input)
                    }
                }
                android.os.ParcelFileDescriptor.AutoCloseInputStream(
                    androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
                        .executeShellCommand("am send-trim-memory ${fixture.context.packageName} RUNNING_LOW"),
                ).use { stream -> assertTrue(stream.readBytes().toString(Charsets.UTF_8).isBlank()) }
                fixture.await("Original Android process must receive the actual RUNNING_LOW callback") {
                    trim.get() == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
                }
                fixture.onReaderMain {
                    assertTrue(
                        "Original Activity must retain the actual trim signal",
                        it.optionalWorkUnderMemoryPressure.value,
                    )
                }
                fixture.matching(true)
                fixture.await("Original On event must keep actual unscored dependencies available") {
                    var enabled = false
                    fixture.onReaderMain {
                        enabled = viewer.config.automaticSpreadMatching && owner.spreadSampleDependencies().isNotEmpty()
                    }
                    enabled
                }
                fixture.onReaderMain {
                    assertTrue(viewer.adapter.session(requireNotNull(viewer.adapter.currentChapter)) === owner)
                    assertEquals(
                        "Actual system trim must prevent registering optional native work",
                        0,
                        AndroidReaderSpreadSampler.optionalWork.value,
                    )
                    assertEquals("Actual system trim must prevent optional JNI decode", 0, reads.get())
                }
                release.countDown()
                lateinit var dialog: android.app.AlertDialog
                fixture.onReaderMain { activity ->
                    dialog = android.app.AlertDialog.Builder(activity).setTitle("Resource test window").create()
                    dialog.show()
                }
                fixture.await("The real reader window must lose focus before the recovery event") {
                    var unfocused = false
                    fixture.onReaderMain { unfocused = !it.hasWindowFocus() }
                    unfocused
                }
                fixture.onReaderMain { dialog.dismiss() }
                fixture.await("Real focus recovery must resume the same owner without Retry or navigation") {
                    var complete = false
                    fixture.onReaderMain { activity ->
                        complete = activity.hasWindowFocus() && reads.get() > 0 &&
                            !activity.optionalWorkUnderMemoryPressure.value &&
                            viewer.adapter.session(requireNotNull(viewer.adapter.currentChapter)) === owner &&
                            owner.spreadSampleDependencies().isEmpty()
                    }
                    complete
                }
                fixture.await("Original cover pixels remain readable through system pressure and recovery") {
                    fixture.containsColor(Color.CYAN)
                }
            } finally {
                release.countDown()
                application.unregisterComponentCallbacks(observer)
            }
        }
    }

    @Test
    fun originalSsivQueuedWorkDefersOptionalJniAndActualCompletionResumesWithoutRetry() =
        verifyOriginalDecodeResource(useCoil = false)

    @Test
    fun originalCoilInitialDecodeDefersOptionalJniAndActualCompletionResumesWithoutRetry() =
        verifyOriginalDecodeResource(useCoil = true)

    private fun verifyOriginalDecodeResource(useCoil: Boolean) {
        HostReaderArtFixture("copymanga").use { fixture ->
            val encodedFixture = SharedSpreadNativeAcceptanceTest()
            fixture.images.set(List(22) { encodedFixture.encodedPage(it, 1, false) })
            fixture.cold()
            fixture.configure(mode = 6, matching = false)
            fixture.launch()
            fixture.await("The original cover must expose its actual native pixels") {
                fixture.containsColor(Color.CYAN)
            }
            lateinit var viewer: DualPageR2LPagerViewer
            fixture.await("Normal current plus four original images must be Ready and foreground idle") {
                var ready = false
                fixture.onReaderMain { activity ->
                    viewer = activity.viewModel.state.value.viewer as DualPageR2LPagerViewer
                    val chapter = requireNotNull(viewer.adapter.currentChapter)
                    val owner = requireNotNull(viewer.adapter.session(chapter))
                    ready = chapter.pages?.take(5)?.all { it.status == Page.State.Ready } == true &&
                        chapter.pageLoader?.hasForegroundWork == false &&
                        ReaderPageImageView.foregroundWork.value == 0 &&
                        owner.identity.pageIds.take(5).all {
                            owner.pageFact(it)?.state == mihon.domain.reader.ReaderPageFactState.PORTRAIT
                        }
                }
                ready
            }
            val accepted = CountDownLatch(1)
            val release = CountDownLatch(1)
            val optionalRelease = CountDownLatch(1)
            val reads = AtomicInteger()
            val expired = AtomicBoolean()
            val imageLoaded = AtomicBoolean()
            lateinit var image: ReaderPageImageView
            var mounted = false
            lateinit var native: SubsamplingScaleImageView
            val bitmap = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
            val encoded = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                .toByteArray()
            bitmap.recycle()
            try {
                fixture.onReaderMain { activity ->
                    val originalSampler = viewer.adapter.spreadDecoderFactory
                    viewer.adapter.spreadDecoderFactory = { stream ->
                        reads.incrementAndGet()
                        check(optionalRelease.await(30, TimeUnit.SECONDS))
                        originalSampler(stream)
                    }
                    image = ReaderPageImageView(activity)
                    mounted = true
                    activity.window.decorView.findViewById<ViewGroup>(android.R.id.content).addView(
                        image,
                        FrameLayout.LayoutParams(600, 900),
                    )
                    image.setImage(
                        BitmapDrawable(activity.resources, Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)),
                        ReaderPageImageView.Config(zoomDuration = 0),
                    )
                    image.onImageLoaded = { imageLoaded.set(true) }
                    if (useCoil) {
                        val firstRead = AtomicBoolean(true)
                        val original = Buffer().write(animatedEncoded())
                        val source = object : ForwardingSource(original) {
                            override fun read(sink: Buffer, byteCount: Long): Long {
                                if (firstRead.compareAndSet(true, false)) {
                                    accepted.countDown()
                                    if (!release.await(30, TimeUnit.SECONDS)) expired.set(true)
                                }
                                return super.read(sink, byteCount)
                            }
                        }.buffer()
                        image.setImage(source, true, ReaderPageImageView.Config(zoomDuration = 0), 0)
                    } else {
                        native = image.getChildAt(0) as SubsamplingScaleImageView
                        val field = SubsamplingScaleImageView::class.java.getDeclaredField("executor").apply {
                            isAccessible = true
                        }
                        val originalExecutor = field.get(native) as Executor
                        // The original native task is already accepted by its original executor while held.
                        native.setExecutor { task ->
                            originalExecutor.execute {
                                accepted.countDown()
                                try {
                                    if (!release.await(30, TimeUnit.SECONDS)) expired.set(true)
                                } finally {
                                    task.run()
                                }
                            }
                        }
                        image.setImage(Buffer().write(encoded), false, ReaderPageImageView.Config(zoomDuration = 0), 0)
                    }
                }
                fixture.await("A real original initial decoder task must be accepted and remain physically executing") {
                    accepted.count == 0L
                }
                fixture.onReaderMain { assertTrue(if (useCoil) !imageLoaded.get() else !native.isImageLoaded) }
                fixture.matching(true)
                val observed = AtomicReference<String>()
                val enabled = AtomicBoolean()
                val observing = AtomicBoolean()
                val registered = AtomicInteger(-1)
                val main = Handler.createAsync(Looper.getMainLooper())
                try {
                    fixture.await("The actual On event must reach the original reader's Ready sample dependencies") {
                        if (observing.compareAndSet(false, true)) {
                            main.post {
                                try {
                                    val chapter = requireNotNull(viewer.adapter.currentChapter)
                                    val owner = requireNotNull(viewer.adapter.session(chapter))
                                    registered.set(AndroidReaderSpreadSampler.optionalWork.value)
                                    observed.set(
                                        "location=${owner.location} config=${viewer.config.automaticSpreadMatching} " +
                                            "busy=${chapter.pageLoader?.hasForegroundWork} " +
                                            "native=${ReaderPageImageView.foregroundWork.value} " +
                                            "optional=${registered.get()} reads=${reads.get()} " +
                                            "deps=${owner.spreadSampleDependencies()} " +
                                            "facts=${chapter.pages?.take(5)?.map { page ->
                                                page.captureContentFact().second.fact to
                                                    owner.pageFact(owner.identity.pageIds[page.index])
                                            }}",
                                    )
                                    enabled.set(
                                        viewer.config.automaticSpreadMatching &&
                                            (reads.get() > 0 || owner.spreadSampleDependencies().isNotEmpty()),
                                    )
                                } finally {
                                    observing.set(false)
                                }
                            }
                        }
                        enabled.get()
                    }
                } catch (failure: AssertionError) {
                    throw AssertionError("Original Main resource observation: ${observed.get()}", failure)
                }
                assertTrue("The original native task must still be held: ${observed.get()}", !expired.get())
                assertEquals(
                    "Accepted native work prevents registering the original optional job: ${observed.get()}",
                    0,
                    registered.get(),
                )
                assertEquals("Optional original JNI must not start during accepted SSIV work", 0, reads.get())
                optionalRelease.countDown()
                release.countDown()
                fixture.await(
                    "Actual initial decoder completion must resume original JNI without Retry or navigation",
                ) {
                    var complete = false
                    fixture.onReaderMain {
                        complete = (if (useCoil) imageLoaded.get() else native.isImageLoaded) && reads.get() > 0 &&
                            requireNotNull(viewer.adapter.session(requireNotNull(viewer.adapter.currentChapter)))
                                .spreadSampleDependencies().isEmpty()
                    }
                    complete
                }
                if (useCoil) {
                    fixture.onReaderMain {
                        assertTrue(
                            (image.getChildAt(0) as android.widget.ImageView).drawable is
                                android.graphics.drawable.Animatable,
                        )
                    }
                }
                fixture.await("The same original native image must actually paint after its task is released") {
                    fixture.containsColor(Color.MAGENTA)
                }
            } finally {
                optionalRelease.countDown()
                release.countDown()
                fixture.onReaderMain {
                    if (mounted) {
                        image.recycle()
                        (image.parent as? ViewGroup)?.removeView(image)
                    }
                }
            }
        }
    }
    private fun animatedEncoded(): ByteArray = android.util.Base64.decode(
        "R0lGODlhEAAYAIEAAP8A/wAAAAAAAAAAACH/C05FVFNDQVBFMi4wAwEAAAAh+QQACgAAACwAAAAAEAAY" +
            "AAAIIgABCBxIsKDBgwgTKlzIsKHDhxAjSpxIsaLFixgzatxoMCAAIfkEAAoAAAAsAAAAABAAGACB/wD+" +
            "AAAAAAAAAAAACCIAAQgcSLCgwYMIEypcyLChw4cQI0qcSLGixYsYM2rcaDAgADs=",
        android.util.Base64.NO_WRAP,
    )
}
