package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.databinding.ReaderActivityBinding
import eu.kanade.tachiyomi.databinding.ReaderErrorBinding
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.loader.PageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import eu.kanade.tachiyomi.ui.reader.viewer.RecordingReaderActivity
import eu.kanade.tachiyomi.widget.ViewPagerAdapter
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.domain.chapter.model.Chapter
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.registry.default.DefaultRegistrar
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Duration
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class DualPagerPageHolderLayoutTest {
    private lateinit var previousInjekt: InjektScope
    private lateinit var viewer: DualPageR2LPagerViewer

    @Before
    fun setUp() {
        previousInjekt = Injekt
        Injekt = InjektScope(DefaultRegistrar())
        val preferences = InMemoryPreferenceStore()
        Injekt.addSingleton(ReaderPreferences(preferences))
        Injekt.addSingleton(UiPreferences(preferences))
        Injekt.addSingleton(BasePreferences(RuntimeEnvironment.getApplication() as Application, preferences))
        // Native format sniffing is unavailable in a Windows JVM; dimensions still use
        // the real BitmapFactory and the production ReaderPageImageView is mounted.
        mockkObject(ImageUtil)
        every { ImageUtil.isAnimatedAndSupported(any()) } returns false
        Dispatchers.setMain(StandardTestDispatcher())
        val activity = Robolectric.buildActivity(RecordingReaderActivity::class.java).get()
        activity.binding = mockk<ReaderActivityBinding>(relaxed = true)
        viewer = DualPageR2LPagerViewer(activity)
        // This fixture mounts the reader viewport, not the activity's navigation help overlay.
        viewer.config.navigationModeChangedListener = null
    }

    @After
    fun tearDown() {
        if (::viewer.isInitialized) viewer.destroy()
        Dispatchers.resetMain()
        Injekt = previousInjekt
        unmockkObject(ImageUtil)
    }

    @Test
    fun `images ready before first measurement occupy equal centered halves`() = runTest {
        val (holder, right, left) = pair()
        load(holder, right, "RIGHT")
        load(holder, left, "LEFT")
        measure(holder, 1440, 3120)
        assertPair(holder, 1440, 3120, 720, 1024)
    }

    @Test
    fun `either late image order preserves centered size and resize recomputes fit`() = runTest {
        for (rightFirst in listOf(true, false)) {
            val (holder, right, left) = pair()
            measure(holder, 1440, 3120)
            load(holder, if (rightFirst) right else left, if (rightFirst) "RIGHT" else "LEFT")
            measure(holder, 1440, 3120)
            load(holder, if (rightFirst) left else right, if (rightFirst) "LEFT" else "RIGHT")
            measure(holder, 1440, 3120)
            assertPair(holder, 1440, 3120, 720, 1024)
            measure(holder, 3120, 1440)
            assertPair(holder, 3120, 1440, 1012, 1440)
        }
    }

    @Test
    fun `shared Android Desktop Fit vectors preserve physical slot bounds and image aspect`() = runTest {
        val vectors = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "domain/src/commonTest/resources/reader/dual-page-fit.csv") }
            .first { it.isFile }
            .readLines(Charsets.UTF_8)
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line -> line.split(',').map(String::toInt) }
        for (v in vectors) {
            val (holder, right, left) = pair(v[2], v[3], v[4], v[5])
            load(holder, right, "RIGHT")
            load(holder, left, "LEFT")
            measure(holder, v[0], v[1])
            val images = descendants(holder).filterIsInstance<ReaderPageImageView>().toList()
            assertEquals(2, images.size)
            images.forEachIndexed { index, image ->
                val offset = 6 + index * 4
                val slot = image.parent as View
                assertEquals(v[0] / 2, slot.width)
                assertEquals(v[1], slot.height)
                assertEquals(v[offset], slot.left + image.left)
                assertEquals(v[offset + 1], slot.top + image.top)
                assertEquals(v[offset + 2], image.width)
                assertEquals(v[offset + 3], image.height)
            }
        }
    }

    @Test
    fun `Ready status from the real holder collector mounts both images without direct binding`() = runTest {
        val (unused, right, left) = pair()
        val chapter = right.chapter
        right.status = Page.State.Ready
        left.status = Page.State.Ready
        chapter.pageLoader = object : PageLoader() {
            override var isLocal = true
            override suspend fun getPages() = listOf(right, left)
        }
        val holder = DualPagerPageHolder(viewer.activity, viewer, DisplayPage.Double(right, left))
        withContext(Dispatchers.Default) {
            withTimeout(10_000) {
                while (descendants(holder).filterIsInstance<ReaderPageImageView>().count() != 2) {
                    delay(10)
                }
            }
        }
        measure(holder, 1440, 3120)
        assertPair(holder, 1440, 3120, 720, 1024)
        detach(holder)
        detach(unused)
    }

    @Test
    fun `double tap zoom and reset transform the pair without child zoom or pager tap`() = runTest {
        val (holder, right, left) = pair()
        load(holder, right, "RIGHT")
        load(holder, left, "LEFT")
        mountInPager(holder)
        var pagerTaps = 0
        viewer.pager.tapListener = { pagerTaps++ }
        val container = holder.getChildAt(0)
        doubleTap(viewer.pager, SystemClock.uptimeMillis())
        assertEquals(2f, container.scaleX, 0.001f)
        assertEquals(2f, container.scaleY, 0.001f)
        measure(holder, 3120, 1440)
        assertEquals("Resize must use the new viewport pivot immediately", 1560f, container.pivotX, 0.001f)
        assertEquals(720f, container.pivotY, 0.001f)
        measure(holder, 1440, 3120)
        descendants(holder).filterIsInstance<ReaderPageImageView>().forEach {
            assertEquals(1f, it.scaleX, 0.001f)
        }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
        assertEquals(0, pagerTaps)
        measure(viewer.pager, 1440, 3120)
        measure(holder, 1440, 3120)
        val panStart = SystemClock.uptimeMillis()
        val drag = listOf(
            Triple(MotionEvent.ACTION_DOWN, 400f, panStart),
            Triple(MotionEvent.ACTION_MOVE, 700f, panStart + 30),
            Triple(MotionEvent.ACTION_MOVE, 900f, panStart + 60),
            Triple(MotionEvent.ACTION_UP, 900f, panStart + 80),
        )
        for ((action, x, time) in drag) {
            MotionEvent.obtain(panStart, time, action, x, 1400f, 0).also {
                viewer.pager.dispatchTouchEvent(it)
                it.recycle()
            }
        }
        assertTrue("Zoomed drag pans the shared container", container.translationX > 0f)
        assertEquals("Zoomed drag cannot turn the outer pager", 1, viewer.pager.currentItem)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
        measure(viewer.pager, 1440, 3120)
        measure(holder, 1440, 3120)
        doubleTap(viewer.pager, SystemClock.uptimeMillis())
        assertEquals(1f, container.scaleX, 0.001f)
        assertEquals(0f, container.translationX, 0.001f)
    }

    @Test
    fun `continuous two finger pinch zooms the group and late page keeps its transform`() = runTest {
        val (holder, right, left) = pair()
        load(holder, right, "RIGHT")
        load(holder, left, "LEFT")
        val originalStream = left.stream
        left.stream = { error("fixture image unavailable") }
        load(holder, left, "LEFT")
        left.stream = originalStream
        mountInPager(holder)
        val start = SystemClock.uptimeMillis()
        MotionEvent.obtain(start, start, MotionEvent.ACTION_DOWN, 300f, 1400f, 0).also {
            viewer.pager.dispatchTouchEvent(it)
            it.recycle()
        }
        val properties = Array(2) { index ->
            MotionEvent.PointerProperties().apply {
                id = index
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        fun send(action: Int, time: Long, leftX: Float, rightX: Float) {
            val coordinates = arrayOf(leftX, rightX).map { xValue ->
                MotionEvent.PointerCoords().apply {
                    x = xValue
                    y = 1400f
                    pressure = 1f
                    size = 1f
                }
            }.toTypedArray()
            MotionEvent.obtain(
                start, time, action, 2, properties, coordinates, 0, 0, 1f, 1f, 0, 0,
                android.view.InputDevice.SOURCE_TOUCHSCREEN, 0,
            ).also {
                viewer.pager.dispatchTouchEvent(it)
                it.recycle()
            }
        }
        send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), start + 20, 300f, 900f)
        val container = holder.getChildAt(0)
        var previousScale = 1f
        for ((index, span) in listOf(800f, 1000f, 1200f).withIndex()) {
            send(MotionEvent.ACTION_MOVE, start + 40 + index * 20, 600f - span / 2, 600f + span / 2)
            assertTrue("An expanding physical pinch cannot shrink the group", container.scaleX >= previousScale)
            previousScale = container.scaleX
        }
        assertTrue(container.scaleX > 1f)
        for ((index, span) in listOf(1100f, 1000f).withIndex()) {
            send(MotionEvent.ACTION_MOVE, start + 100 + index * 20, 600f - span / 2, 600f + span / 2)
            assertTrue("A contracting physical pinch cannot enlarge the group", container.scaleX <= previousScale)
            previousScale = container.scaleX
        }
        assertTrue(container.scaleX > 1f)
        send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), start + 160, 100f, 1100f)
        MotionEvent.obtain(start, start + 180, MotionEvent.ACTION_UP, 100f, 1400f, 0).also {
            viewer.pager.dispatchTouchEvent(it)
            it.recycle()
        }
        load(holder, left, "LEFT")
        measure(holder, 1440, 3120)
        assertEquals(previousScale, container.scaleX, 0.001f)
        assertEquals(container.scaleX, container.scaleY, 0.001f)
        assertEquals(1, viewer.pager.currentItem)
    }

    @Test
    fun `retry button uses failed page loader and Ready collector clears its error`() = runTest {
        val (unused, right, left) = pair()
        val retried = mutableListOf<ReaderPage>()
        right.status = Page.State.Ready
        left.status = Page.State.Error(IllegalStateException("fixture image unavailable"))
        right.chapter.pageLoader = object : PageLoader() {
            override var isLocal = false
            override suspend fun getPages() = listOf(right, left)
            override fun retryPage(page: ReaderPage) {
                retried += page
                page.status = Page.State.Ready
            }
        }
        val holder = DualPagerPageHolder(viewer.activity, viewer, DisplayPage.Double(right, left))
        val errorField = DualPagerPageHolder::class.java.getDeclaredField("errorLayout").apply { isAccessible = true }
        suspend fun waitUntil(predicate: () -> Boolean) = withContext(Dispatchers.Default) {
            withTimeout(10_000) { while (!predicate()) delay(10) }
        }
        try {
            waitUntil {
                errorField.get(holder) != null &&
                    descendants(holder).filterIsInstance<ReaderPageImageView>().count() == 1
            }
            val error = errorField.get(holder) as ReaderErrorBinding
            assertEquals(View.VISIBLE, error.root.visibility)
            mountInPager(holder)
            measure(holder, 1440, 3120)
            val buttonBounds = Rect()
            error.actionRetry.getDrawingRect(buttonBounds)
            holder.offsetDescendantRectToMyCoords(error.actionRetry, buttonBounds)
            val time = SystemClock.uptimeMillis()
            for ((action, eventTime) in listOf(MotionEvent.ACTION_DOWN to time, MotionEvent.ACTION_UP to time + 20)) {
                MotionEvent.obtain(
                    time,
                    eventTime,
                    action,
                    buttonBounds.exactCenterX(),
                    buttonBounds.exactCenterY(),
                    0,
                ).also {
                    holder.dispatchTouchEvent(it)
                    it.recycle()
                }
            }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf(left), retried)
            waitUntil { error.root.visibility == View.GONE }
            measure(holder, 1440, 3120)
            assertPair(holder, 1440, 3120, 720, 1024)
        } finally {
            detach(holder)
            detach(unused)
        }
    }

    @Test
    fun `single page keeps full viewport and its own image touch target`() = runTest {
        val (_, page, _) = pair()
        val holder = DualPagerPageHolder(viewer.activity, viewer, DisplayPage.Single(page))
        load(holder, page, "CENTER")
        measure(holder, 1440, 3120)
        val image = descendants(holder).filterIsInstance<ReaderPageImageView>().single()
        assertEquals(1440, image.width)
        assertEquals(3120, image.height)
        var imageTouches = 0
        image.getChildAt(0).setOnTouchListener { _, _ ->
            imageTouches++
            true
        }
        doubleTap(holder, SystemClock.uptimeMillis() + 1000)
        assertTrue(imageTouches > 0)
        assertEquals(1f, holder.getChildAt(0).scaleX, 0.001f)
    }

    @Test
    fun `error retry preserves the surviving image slot and clears error after success`() = runTest {
        val (holder, right, left) = pair()
        load(holder, right, "RIGHT")
        measure(holder, 1440, 3120)
        val originalStream = left.stream
        left.stream = { error("fixture image unavailable") }
        load(holder, left, "LEFT")
        measure(holder, 1440, 3120)
        val errorField = DualPagerPageHolder::class.java.getDeclaredField("errorLayout").apply { isAccessible = true }
        val error = errorField.get(holder) as ReaderErrorBinding
        assertEquals(View.VISIBLE, error.root.visibility)
        load(holder, right, "RIGHT")
        assertEquals("A successful neighbour cannot dismiss this page's error", View.VISIBLE, error.root.visibility)
        val surviving = descendants(holder).filterIsInstance<ReaderPageImageView>().single()
        assertEquals(720, surviving.width)
        var childTouches = 0
        surviving.getChildAt(0).setOnTouchListener { _, _ ->
            childTouches++
            true
        }
        doubleTap(holder, SystemClock.uptimeMillis(), x = 1080f)
        assertEquals("An error must not enable independent image gestures", 0, childTouches)
        assertEquals(2f, holder.getChildAt(0).scaleX, 0.001f)
        left.stream = originalStream
        load(holder, left, "LEFT")
        measure(holder, 1440, 3120)
        assertPair(holder, 1440, 3120, 720, 1024)
        assertEquals(error.errorMessage.text.toString(), View.GONE, error.root.visibility)
    }

    private fun mountInPager(holder: DualPagerPageHolder) {
        viewer.pager.visibility = View.VISIBLE
        Robolectric.buildActivity(Activity::class.java).setup().visible().get().setContentView(viewer.pager)
        viewer.pager.clearOnPageChangeListeners()
        viewer.pager.adapter = object : ViewPagerAdapter() {
            override fun getCount(): Int = 3
            override fun createView(container: ViewGroup, position: Int): View =
                if (position == 1) holder else View(container.context)
        }
        viewer.pager.currentItem = 1
        measure(viewer.pager, 1440, 3120)
        measure(holder, 1440, 3120)
    }

    private fun doubleTap(target: View, time: Long, x: Float = 400f) {
        for (down in listOf(time, time + 100)) {
            for ((action, eventTime) in listOf(MotionEvent.ACTION_DOWN to down, MotionEvent.ACTION_UP to down + 20)) {
                MotionEvent.obtain(down, eventTime, action, x, 1400f, 0).also {
                    target.dispatchTouchEvent(it)
                    it.recycle()
                }
            }
        }
    }

    private fun detach(holder: DualPagerPageHolder) {
        val method = DualPagerPageHolder::class.java.getDeclaredMethod("onDetachedFromWindow")
        method.isAccessible = true
        method.invoke(holder)
    }

    private fun pair(
        leftWidth: Int = 1125,
        leftHeight: Int = 1600,
        rightWidth: Int = 1125,
        rightHeight: Int = 1600,
    ): Triple<DualPagerPageHolder, ReaderPage, ReaderPage> {
        val chapter = ReaderChapter(Chapter.create().copy(id = 1, mangaId = 1))
        fun bytes(w: Int, h: Int) = ByteArrayOutputStream().also {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        val rightBytes = bytes(rightWidth, rightHeight)
        val leftBytes = bytes(leftWidth, leftHeight)
        val right = ReaderPage(0, stream = { rightBytes.inputStream() }).apply { this.chapter = chapter }
        val left = ReaderPage(1, stream = { leftBytes.inputStream() }).apply { this.chapter = chapter }
        return Triple(DualPagerPageHolder(viewer.activity, viewer, DisplayPage.Double(right, left)), right, left)
    }

    // Exercise the production byte decode and image binding entry, controlling completion order
    // without a network loader or a copied layout implementation.
    private suspend fun load(holder: DualPagerPageHolder, page: ReaderPage, sideName: String) {
        val sideType = DualPagerPageHolder::class.java.declaredClasses.single { it.simpleName == "Side" }
        val side = sideType.enumConstants.single { it.toString() == sideName }
        val method = DualPagerPageHolder::class.java.declaredMethods.single { it.name == "setImage" }
        method.isAccessible = true
        suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
            val result = method.invoke(holder, page, side, continuation)
            if (result === COROUTINE_SUSPENDED) COROUTINE_SUSPENDED else Unit
        }
        val errorField = DualPagerPageHolder::class.java.getDeclaredField("errorLayout").apply { isAccessible = true }
        val error = errorField.get(holder) as? ReaderErrorBinding
        if (error != null && error.root.visibility == View.VISIBLE) {
            assertTrue(
                "Unexpected image binding failure: ${error.errorMessage.text}",
                error.errorMessage.text.contains("fixture image unavailable"),
            )
        }
    }

    private fun measure(holder: View, width: Int, height: Int) {
        holder.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        holder.layout(holder.left, holder.top, holder.left + width, holder.top + height)
    }

    private fun assertPair(holder: DualPagerPageHolder, width: Int, height: Int, imageWidth: Int, imageHeight: Int) {
        val images = descendants(holder).filterIsInstance<ReaderPageImageView>().toList()
        assertEquals("Both production images must be mounted", 2, images.size)
        images.forEachIndexed { index, image ->
            assertEquals("image $index width", imageWidth, image.width)
            assertEquals("image $index height", imageHeight, image.height)
            var y = image.top
            var parent = image.parent
            while (parent is View && parent !== holder) {
                y += parent.top
                parent = parent.parent
            }
            assertEquals("image $index vertically centered in $width x $height", (height - imageHeight) / 2, y)
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
