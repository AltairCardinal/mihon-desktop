package eu.kanade.tachiyomi.ui.reader.viewer

import android.graphics.Color
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import eu.kanade.tachiyomi.ui.reader.HostReaderArtFixture
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.viewer.pager.DualPageR2LPagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.pager.DualPagerPageHolder
import mihon.domain.reader.ReaderPortraitSingleSlot
import mihon.domain.reader.scheduler.ReaderRequestScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class SharedDualPageExposureNativeTest {
    @Test
    fun laterSlotPaintsWhileTheOriginalEarlierNativeBaseDecodeIsHeldAndNavigationKeepsItsMissingSource() {
        HostReaderArtFixture("copymanga").use { fixture ->
            val source = CountDownLatch(1)
            fixture.beforeImageRequest.set { if (it in 1..2) check(source.await(90, TimeUnit.SECONDS)) }
            fixture.images.set(
                List(22) { index ->
                    HostReaderArtFixture.png(
                        400,
                        600,
                        when (index) {
                            1 -> Color.RED
                            2 -> Color.GREEN
                            else -> Color.BLUE
                        },
                    )
                },
            )
            fixture.response.set(200 to fixture.successWithImageIdentity("later_${SystemClock.uptimeMillis()}"))
            fixture.configure(mode = 6)
            fixture.cold()
            fixture.launch(initialPage = 1)
            val tasks = LinkedBlockingQueue<Runnable>()
            val executor = Executors.newSingleThreadExecutor()
            val installed = AtomicBoolean(false)
            var native: SubsamplingScaleImageView? = null
            var image: ReaderPageImageView? = null
            lateinit var observer: ViewTreeObserver
            lateinit var root: View
            val listener = ViewTreeObserver.OnPreDrawListener {
                if (!installed.get()) {
                    descendants(root).filterIsInstance<ReaderPageImageView>().firstOrNull { view ->
                        view.javaClass.declaredFields.firstOrNull { it.name == "page" }?.let { field ->
                            field.isAccessible = true
                            (field.get(view) as? ReaderPage)?.index == 1
                        } == true
                    }?.let { view ->
                        (view.getChildAt(0) as? SubsamplingScaleImageView)?.let { child ->
                            check(!child.isImageLoaded) {
                                "The native base must not already be decoded before the gate"
                            }
                            child.setExecutor { task -> tasks.add(task) }
                            native = child
                            image = view
                            installed.set(true)
                        }
                    }
                }
                true
            }
            fixture.onReaderMain { activity ->
                root = activity.window.decorView
                observer = root.viewTreeObserver
                observer.addOnPreDrawListener(listener)
            }
            try {
                source.countDown()
                fixture.await("The real earlier JNI base decode must be queued while D paints") {
                    installed.get() && tasks.isNotEmpty() && fixture.containsColor(Color.GREEN)
                }
                fixture.onReaderMain {
                    assertEquals(400, requireNotNull(native).sWidth)
                    assertFalse(requireNotNull(native).isImageLoaded)
                }
                assertFalse(fixture.containsColor(Color.RED))
                assertEquals(setOf(2), drawn(fixture))
                fixture.await("Only D's actual pixels may reach SQL") { fixture.progress().first == 2L }
                val before = fixture.observeSlot(Color.GREEN)
                assertTrue(before.content.right <= fixture.windowWidth() / 2)
                fixture.await("C's own undecoded slot must have actual visible loading feedback") {
                    fixture.loadingBounds(1) != null
                }
                val loading = fixture.loadingBounds(1)
                assertTrue(
                    "The undecoded source C must retain its RIGHT loading feedback",
                    loading != null &&
                        loading.left >= fixture.windowWidth() / 2,
                )
                fixture.key(KeyEvent.KEYCODE_DPAD_LEFT)
                assertEquals("The actual request must still address missing C", 1, fixture.logicalPageIndex())
                fixture.awaitStable("D must remain in its original physical LEFT slot") {
                    fixture.observeSlot(Color.GREEN) == before
                }
                fixture.onReaderMain {
                    requireNotNull(native).setExecutor(executor)
                    while (true) executor.execute(tasks.poll() ?: break)
                    requireNotNull(image).invalidateImageDraw()
                }
                fixture.awaitStable("C must join by its real JNI first paint") { fixture.containsColor(Color.RED) }
                fixture.await("Both original actual slots must have been acknowledged") {
                    drawn(fixture) == setOf(1, 2)
                }
                assertEquals("Appending earlier C cannot regress D's actual progress", 2L, fixture.progress().first)
            } finally {
                source.countDown()
                fixture.onReaderMain {
                    observer.removeOnPreDrawListener(listener)
                    native?.setExecutor(executor)
                    while (true) executor.execute(tasks.poll() ?: break)
                }
                executor.shutdown()
            }
        }
    }

    @Test
    fun reverseUnknownEarlierSourceCannotPlaceTheReadyTargetOnTheLeftOrMoveItsLaterPixels() {
        HostReaderArtFixture("copymanga").use { fixture ->
            val earlier = CountDownLatch(1)
            fixture.beforeImageRequest.set { if (it == 1) check(earlier.await(90, TimeUnit.SECONDS)) }
            fixture.images.set(
                List(22) { index ->
                    if (index == 1) {
                        HostReaderArtFixture.png(900, 600, Color.MAGENTA)
                    } else {
                        HostReaderArtFixture.png(400, 600, if (index == 2) Color.GREEN else Color.BLUE)
                    }
                },
            )
            fixture.response.set(200 to fixture.successWithImageIdentity("reverse_${SystemClock.uptimeMillis()}"))
            fixture.configure(mode = 6)
            fixture.cold()
            try {
                fixture.launch(initialPage = 3)
                fixture.await("The initial real source 3 must paint before reverse input") {
                    fixture.containsColor(Color.BLUE) && fixture.progress().first >= 3L
                }
                fixture.key(KeyEvent.KEYCODE_DPAD_RIGHT)
                fixture.awaitStable("Known D must paint without waiting for its unknown preceding C") {
                    fixture.logicalPageIndex() == 2 && fixture.containsColor(Color.GREEN)
                }
                val before = fixture.observeSlot(Color.GREEN)
                assertTrue(before.content.left >= fixture.windowWidth() / 2)
                fixture.onReaderMain { activity ->
                    val chapter = requireNotNull(activity.viewModel.state.value.viewerChapters).currChapter
                    val viewer = activity.viewModel.state.value.viewer as DualPageR2LPagerViewer
                    val owner = requireNotNull(viewer.adapter.session(chapter))
                    assertTrue(owner.pageFact(owner.identity.pageIds[1])?.width in listOf(null, 0))
                    assertEquals(
                        ReaderPortraitSingleSlot.RIGHT,
                        requireNotNull(owner.targetUnit).slots.single().position,
                    )
                }
                earlier.countDown()
                fixture.await("The original earlier HTTP bytes must really classify as wide") {
                    fixture.hasDecodedSpread(1)
                }
                repeat(12) {
                    assertEquals(before, fixture.observeSlot(Color.GREEN))
                    assertEquals(2, fixture.logicalPageIndex())
                    SystemClock.sleep(16)
                }
            } finally {
                earlier.countDown()
            }
        }
    }

    @Test
    fun fingerHeldNativeNeighborKeepsItsExposureWhenItsUndrawnPartnerReallyBecomesWideWithoutReadingIt() {
        HostReaderArtFixture("copymanga").use { fixture ->
            val partner = CountDownLatch(1)
            fixture.beforeImageRequest.set { if (it == 4) check(partner.await(90, TimeUnit.SECONDS)) }
            fixture.images.set(
                List(22) { index ->
                    if (index == 4) {
                        HostReaderArtFixture.png(900, 600, Color.MAGENTA)
                    } else {
                        HostReaderArtFixture.png(
                            400,
                            600,
                            when (index) {
                                1 -> Color.RED
                                2 -> Color.GREEN
                                3 -> Color.BLUE
                                else -> Color.CYAN
                            },
                        )
                    }
                },
            )
            fixture.response.set(200 to fixture.successWithImageIdentity("exposure_${SystemClock.uptimeMillis()}"))
            fixture.configure(mode = 6)
            fixture.cold()
            var down = 0L
            var width = 0f
            try {
                fixture.launch(initialPage = 1)
                fixture.await("The original C/D must paint before the native drag") { fixture.progress().first == 2L }
                val baseline = fixture.progress()
                val actualWidth = fixture.windowWidth().toFloat()
                width = actualWidth
                down = SystemClock.uptimeMillis()
                fixture.touchWindow(MotionEvent.ACTION_DOWN, down, down, actualWidth * 0.15f, 1200f)
                val trace = mutableListOf(dragState(fixture))
                for (step in 1..6) {
                    SystemClock.sleep(30)
                    fixture.touchWindow(
                        MotionEvent.ACTION_MOVE,
                        down,
                        SystemClock.uptimeMillis(),
                        actualWidth * (0.15f + step * 0.105f),
                        1200f,
                    )
                    trace += dragState(fixture)
                }
                fixture.awaitStable("Real next-screen E pixels must be exposed under the still-held finger: $trace") {
                    fixture.containsColor(Color.BLUE)
                }
                val before = fixture.observeSlot(Color.BLUE)
                val scroll = scrollX(fixture)
                val frame = frameId(fixture, 3)
                Log.i("DualExposure", "before=$before scroll=$scroll frame=$frame trace=$trace SQL=$baseline")
                assertEquals(1, fixture.logicalPageIndex())
                assertEquals(baseline, fixture.progress())
                partner.countDown()
                fixture.await("F's real normal request must finish decode and classification") {
                    fixture.hasDecodedSpread(4)
                }
                repeat(12) {
                    assertEquals("A fact cannot change the held native offset", scroll, scrollX(fixture))
                    assertEquals(
                        "E's full exposure, native mount and transform must stay fixed",
                        before,
                        fixture.observeSlot(Color.BLUE),
                    )
                    assertEquals(frame, frameId(fixture, 3))
                    assertEquals(1, fixture.logicalPageIndex())
                    assertEquals("A real neighbor exposure cannot read E", baseline, fixture.progress())
                    SystemClock.sleep(16)
                }
                Log.i(
                    "DualExposure",
                    "after=${fixture.observeSlot(Color.BLUE)} scroll=${scrollX(fixture)} " +
                        "frame=${frameId(fixture, 3)} SQL=${fixture.progress()}",
                )
                // The still-held slow drag must cross the original pager's release threshold.
                fixture.touchWindow(
                    MotionEvent.ACTION_MOVE,
                    down,
                    SystemClock.uptimeMillis(),
                    width * 0.93f,
                    1200f,
                )
                Log.i("DualExposure", "release=${dragState(fixture)}")
                fixture.touchWindow(
                    MotionEvent.ACTION_UP,
                    down,
                    SystemClock.uptimeMillis(),
                    width * 0.93f,
                    1200f,
                )
                down = 0L
                fixture.await("The original completed drag must select E and read its actual pixels") {
                    fixture.logicalPageIndex() == 3 && fixture.progress().first == 3L
                }
                fixture.key(KeyEvent.KEYCODE_DPAD_LEFT)
                fixture.await("The removed wide partner F must become the next full native viewport") {
                    fixture.logicalPageIndex() == 4 && fixture.containsColor(Color.MAGENTA) &&
                        fixture.progress().first == 4L
                }
                assertTrue(fixture.observeSlot(Color.MAGENTA).content.width() > fixture.windowWidth() * 0.75f)
            } finally {
                partner.countDown()
                if (down != 0L) {
                    fixture.touchWindow(
                        MotionEvent.ACTION_CANCEL,
                        down,
                        SystemClock.uptimeMillis(),
                        width * 0.78f,
                        1200f,
                    )
                }
            }
        }
    }

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) {
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
    } else {
        emptyList()
    }

    private fun drawn(fixture: HostReaderArtFixture): Set<Int> {
        var result = emptySet<Int>()
        fixture.onReaderMain { activity ->
            val viewer = activity.viewModel.state.value.viewer as DualPageR2LPagerViewer
            val holder = (0 until viewer.pager.childCount).map(viewer.pager::getChildAt)
                .filterIsInstance<DualPagerPageHolder>().first { it.displayPage.firstPage.index == 1 }
            val (owner, ticket) = requireNotNull(holder.presentationScope)
            result = requireNotNull(owner.frame(ticket)).drawnPageIds.mapTo(mutableSetOf()) { it.sourcePageIndex }
        }
        return result
    }

    private fun scrollX(fixture: HostReaderArtFixture): Int {
        var result = 0
        fixture.onReaderMain { result = (it.viewModel.state.value.viewer as DualPageR2LPagerViewer).pager.scrollX }
        return result
    }

    private fun dragState(fixture: HostReaderArtFixture): String {
        var result = ""
        fixture.onReaderMain { activity ->
            val viewer = activity.viewModel.state.value.viewer as DualPageR2LPagerViewer
            val state = generateSequence<Class<*>>(viewer.pager.javaClass) { it.superclass }
                .flatMap { it.declaredFields.asSequence() }.first { it.name == "mScrollState" }
                .apply { isAccessible = true }.get(viewer.pager)
            val page = viewer.adapter.items.filterIsInstance<eu.kanade.tachiyomi.ui.reader.viewer.pager.DisplayPage>()
                .first().firstPage
            val loader = requireNotNull(page.chapter.pageLoader)
            val scheduler = loader.javaClass.getDeclaredField("requestScheduler").apply { isAccessible = true }
                .get(loader) as ReaderRequestScheduler
            val snapshot = scheduler.snapshot()
            val info = generateSequence<Class<*>>(viewer.pager.javaClass) { it.superclass }
                .flatMap { it.declaredMethods.asSequence() }.first { it.name == "infoForCurrentScrollPosition" }
                .apply { isAccessible = true }.invoke(viewer.pager)
            val fields = listOf("position", "offset", "widthFactor").associateWith { name ->
                info.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(info)
            }
            result = "x=${viewer.pager.scrollX}/state=$state/current=${viewer.pager.currentItem}/" +
                "info=$fields/pages=${page.chapter.pages?.take(6)?.map { it.index to it.status }}/" +
                "pending=${snapshot.pendingRequests.map { it.jobKey.pageId.sourcePageIndex to it.kind }}/" +
                "active=${snapshot.activeRequests.map { it.jobKey.pageId.sourcePageIndex to it.kind }}"
        }
        return result
    }

    private fun frameId(fixture: HostReaderArtFixture, source: Int): Any? {
        var result: Any? = null
        fixture.onReaderMain { activity ->
            val viewer = activity.viewModel.state.value.viewer as DualPageR2LPagerViewer
            val holder = (0 until viewer.pager.childCount).map(viewer.pager::getChildAt)
                .filterIsInstance<DualPagerPageHolder>().first {
                    it.displayPage.visiblePages.any { page -> page.index == source }
                }
            val (owner, ticket) = requireNotNull(holder.presentationScope)
            result = requireNotNull(owner.frame(ticket)).id
        }
        return result
    }
}
