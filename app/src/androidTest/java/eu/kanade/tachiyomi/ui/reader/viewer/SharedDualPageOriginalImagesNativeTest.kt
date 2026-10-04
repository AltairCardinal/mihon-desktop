package eu.kanade.tachiyomi.ui.reader.viewer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.HostReaderArtFixture
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.ReaderNavigationOverlayView
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.ui.reader.viewer.pager.DualPageR2LPagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.pager.DualPagerPageHolder
import mihon.domain.reader.ReaderDualPageFrameId
import mihon.domain.reader.ReaderPageFactState
import mihon.domain.reader.ReaderPortraitSingleSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.ceil

/** Original encoded artwork goes through the installed extension, ordinary loader and actual JNI View. */
class SharedDualPageOriginalImagesNativeTest {
    private data class FirstPrepared(
        val frameId: ReaderDualPageFrameId,
        val position: ReaderPortraitSingleSlot,
        val facts: List<ReaderPageFactState?>,
        val wasDrawn: Boolean,
    )

    private val diagnostics = mutableSetOf<String>()

    private fun diagnostic(source: Int, reason: String) {
        if (diagnostics.add("$source:${reason.substringBefore('=')}")) {
            Log.i("DualOriginal", "source=${source + 1} observation=$reason")
        }
    }

    @Test
    fun originalB1ColdDirect17AndUnknownReverseKeepTheirFirstPixelsInThePromisedSlots() {
        HostReaderArtFixture("manhuagui", "real-b1-original.cbz").use { fixture ->
            val earlier = CountDownLatch(1)
            fixture.beforeImageRequest.set { if (it == 14) check(earlier.await(90, TimeUnit.SECONDS)) }
            fixture.configure(mode = 6)
            fixture.cold()
            try {
                fixture.launch(initialPage = 16)
                awaitOriginal(fixture, 16)
                assertEquals(17, pageCount(fixture))
                assertEquals(16, fixture.logicalPageIndex())
                assertEquals(ReaderPortraitSingleSlot.RIGHT, slot(fixture, 16))
                val last = requireNotNull(observeOriginal(fixture, 16))
                stableOriginal(fixture, 16, last)
                fixture.key(KeyEvent.KEYCODE_DPAD_RIGHT)
                awaitOriginal(fixture, 15)
                assertEquals("Cold reverse must address source16, preserving unread15", 15, fixture.logicalPageIndex())
                assertEquals(ReaderPageFactState.UNKNOWN, fact(fixture, 14))
                assertEquals(ReaderPortraitSingleSlot.RIGHT, slot(fixture, 15))
                val reverse = requireNotNull(observeOriginal(fixture, 15))
                earlier.countDown()
                fixture.await("Original source15 must really acquire its later portrait header") {
                    fact(fixture, 14) == ReaderPageFactState.PORTRAIT
                }
                stableOriginal(fixture, 15, reverse)
                fixture.key(KeyEvent.KEYCODE_DPAD_LEFT)
                awaitOriginal(fixture, 16)
                assertEquals(16, fixture.logicalPageIndex())
                assertEquals(last.content, requireNotNull(observeOriginal(fixture, 16)).content)
                assertEquals(ReaderPortraitSingleSlot.RIGHT, slot(fixture, 16))
            } finally {
                earlier.countDown()
            }
        }
    }

    @Test
    fun originalB1NormalSequentialReadingAndWarmReturnPreserveReady15And16AndDirect17() {
        HostReaderArtFixture("manhuagui", "real-b1-original.cbz").use { fixture ->
            fixture.configure(mode = 6)
            fixture.cold()
            fixture.launch()
            awaitOriginal(fixture, 0)
            val timely = AtomicReference<Triple<List<ReaderPageFactState?>, List<Int>, List<Boolean>>?>()
            val hooked = mutableSetOf<ReaderPageImageView>()
            lateinit var observer: ViewTreeObserver
            lateinit var listener: ViewTreeObserver.OnPreDrawListener
            fixture.onReaderMain { activity ->
                observer = activity.window.decorView.viewTreeObserver
                listener = ViewTreeObserver.OnPreDrawListener {
                    val viewer = activity.viewModel.state.value.viewer as? DualPageR2LPagerViewer
                    viewer?.let { descendants(it.pager) }?.filterIsInstance<ReaderPageImageView>()
                        ?.filter {
                            source(it)?.index == 14 && it.beforeImageDraw != null && hooked.add(it)
                        }?.forEach { image ->
                            val original = requireNotNull(image.beforeImageDraw)
                            image.beforeImageDraw = { width, height ->
                                val prepared = original.invoke(width, height)
                                if (prepared) observeTimely15(image, activity, timely)
                                prepared
                            }
                        }
                    true
                }
                observer.addOnPreDrawListener(listener)
            }
            val presented = mutableSetOf<Int>()
            for (turn in 0 until 20) {
                val target = requireNotNull(fixture.logicalPageIndex())
                awaitOriginal(fixture, target)
                fixture.await("All current source slots must actually paint before the next real key") {
                    currentPages(fixture).all { observeOriginal(fixture, it) != null }
                }
                val visible = currentPages(fixture)
                presented += visible
                if (target == 16) break
                fixture.key(KeyEvent.KEYCODE_DPAD_LEFT)
            }
            assertEquals((0..16).toSet(), presented)
            assertEquals(16, fixture.logicalPageIndex())
            val firstPair = requireNotNull(timely.get())
            assertEquals(listOf(ReaderPageFactState.PORTRAIT, ReaderPageFactState.PORTRAIT), firstPair.first)
            assertEquals(listOf(14, 15), firstPair.second)
            assertEquals(listOf(true, true), firstPair.third)
            Log.i("DualOriginal", "before-first-15-pixel facts/pair/ordinary-ready=$firstPair")
            fixture.onReaderMain { if (observer.isAlive) observer.removeOnPreDrawListener(listener) }
            fixture.key(KeyEvent.KEYCODE_DPAD_RIGHT)
            fixture.await("The real already-presented15/16 pair must return from17") {
                currentPages(fixture) == listOf(14, 15) &&
                    observeOriginal(fixture, 14) != null && observeOriginal(fixture, 15) != null
            }
            assertEquals(ReaderPortraitSingleSlot.RIGHT, slot(fixture, 14))
            assertEquals(ReaderPortraitSingleSlot.LEFT, slot(fixture, 15))
            fixture.key(KeyEvent.KEYCODE_DPAD_LEFT)
            awaitOriginal(fixture, 16)
            assertEquals(16, fixture.logicalPageIndex())
            stableOriginal(fixture, 16, requireNotNull(observeOriginal(fixture, 16)))
        }
    }

    private fun observeTimely15(
        image: ReaderPageImageView,
        activity: ReaderActivity,
        result: AtomicReference<Triple<List<ReaderPageFactState?>, List<Int>, List<Boolean>>?>,
    ) {
        val holder = generateSequence(image.parent) { it.parent }.filterIsInstance<DualPagerPageHolder>().first()
        val (owner, ticket) = holder.presentationScope ?: return
        val frame = owner.frame(ticket) ?: return
        if (result.get() != null || owner.location?.pageId?.sourcePageIndex != 14 ||
            owner.identity.pageIds[14] in frame.drawnPageIds
        ) {
            return
        }
        val chapter = activity.viewModel.state.value.viewerChapters?.currChapter
        val pages = requireNotNull(chapter?.pages)
        result.set(
            Triple(
                listOf(14, 15).map { owner.pageFact(owner.identity.pageIds[it])?.state },
                frame.unit.slots.map { it.pageId.sourcePageIndex },
                listOf(14, 15).map { pages[it].status == Page.State.Ready },
            ),
        )
    }

    @Test
    fun originalB2LateOpeningWidthKeeps2OnTheRightAndShows3FullOnTheNextRealKey() {
        lateStructure(
            target = 1,
            neighbors = setOf(2),
            next = 2,
        )
    }

    @Test
    fun originalB2LateBodyWidthsKeep20OnTheRightAndDoNotLose21Or22() {
        lateStructure(
            target = 19,
            neighbors = setOf(18, 20),
            next = 20,
        )
    }

    @Test
    fun originalB2KnownOpeningStructureIsLeftBeforeItsFirstDualPixels() {
        knownStructure(target = 1, firstNeighbor = 0, secondNeighbor = 2, expected = ReaderPortraitSingleSlot.LEFT)
    }

    @Test
    fun originalB2KnownWideNeighborsCenter20BeforeItsFirstDualPixels() {
        knownStructure(target = 19, firstNeighbor = 18, secondNeighbor = 20, expected = ReaderPortraitSingleSlot.FULL)
    }

    private fun lateStructure(target: Int, neighbors: Set<Int>, next: Int) {
        HostReaderArtFixture("copymanga", "real-b2-original.cbz").use { fixture ->
            val held = CountDownLatch(1)
            fixture.beforeImageRequest.set { if (it in neighbors) check(held.await(90, TimeUnit.SECONDS)) }
            fixture.configure(mode = 6)
            fixture.cold()
            try {
                fixture.launch(initialPage = target)
                awaitOriginal(fixture, target)
                assertEquals(22, pageCount(fixture))
                assertEquals(target, fixture.logicalPageIndex())
                neighbors.forEach { assertEquals(ReaderPageFactState.UNKNOWN, fact(fixture, it)) }
                assertEquals(ReaderPortraitSingleSlot.RIGHT, slot(fixture, target))
                val before = requireNotNull(observeOriginal(fixture, target))
                held.countDown()
                fixture.await("Actual original neighbor bytes must confirm their oriented wide headers") {
                    neighbors.all { fact(fixture, it) == ReaderPageFactState.SPREAD }
                }
                stableOriginal(fixture, target, before)
                assertEquals(ReaderPortraitSingleSlot.RIGHT, slot(fixture, target))
                fixture.key(KeyEvent.KEYCODE_DPAD_LEFT)
                awaitOriginal(fixture, next)
                assertEquals(next, fixture.logicalPageIndex())
                assertEquals(ReaderPortraitSingleSlot.FULL, slot(fixture, next))
                if (next == 20) {
                    fixture.key(KeyEvent.KEYCODE_DPAD_LEFT)
                    awaitOriginal(fixture, 21)
                    assertEquals(
                        "The original advertising source22 must remain reachable",
                        21,
                        fixture.logicalPageIndex(),
                    )
                }
            } finally {
                held.countDown()
            }
        }
    }

    private fun knownStructure(
        target: Int,
        firstNeighbor: Int,
        secondNeighbor: Int,
        expected: ReaderPortraitSingleSlot,
    ) {
        HostReaderArtFixture("copymanga", "real-b2-original.cbz").use { fixture ->
            fixture.configure(mode = 2)
            fixture.cold()
            fixture.launch(initialPage = firstNeighbor)
            awaitOriginal(fixture, firstNeighbor)
            seekForPreparation(fixture, secondNeighbor)
            awaitOriginal(fixture, secondNeighbor)
            seekForPreparation(fixture, target)
            awaitOriginal(fixture, target)
            val first = AtomicReference<Triple<Map<Int, ReaderPageFactState?>, Boolean, ReaderDualPageFrameId>?>()
            val firstPrepared = AtomicReference<FirstPrepared?>()
            val hooked = mutableSetOf<ReaderPageImageView>()
            lateinit var observer: ViewTreeObserver
            lateinit var listener: ViewTreeObserver.OnPreDrawListener
            fixture.onReaderMain { activity ->
                observer = activity.window.decorView.viewTreeObserver
                listener = ViewTreeObserver.OnPreDrawListener {
                    val viewer = activity.viewModel.state.value.viewer as? DualPageR2LPagerViewer
                    val chapter = activity.viewModel.state.value.viewerChapters?.currChapter
                    if (viewer != null && chapter != null) {
                        val holder = descendants(viewer.pager).filterIsInstance<DualPagerPageHolder>()
                            .firstOrNull { it.displayPage.visiblePages.any { it.index == target } }
                        holder?.presentationScope?.let { (owner, ticket) ->
                            if (first.get() == null) {
                                val frame = requireNotNull(owner.frame(ticket))
                                first.set(
                                    Triple(
                                        listOf(firstNeighbor, secondNeighbor).associateWith {
                                            owner.pageFact(owner.identity.pageIds[it])?.state
                                        },
                                        frame.lockedPageIds.isEmpty(),
                                        frame.id,
                                    ),
                                )
                            }
                        }
                        descendants(viewer.pager).filterIsInstance<ReaderPageImageView>()
                            .filter { source(it)?.index == target && it.beforeImageDraw != null && hooked.add(it) }
                            .forEach {
                                hookFirstPrepared(it, target, listOf(firstNeighbor, secondNeighbor), firstPrepared)
                            }
                    }
                    true
                }
                observer.addOnPreDrawListener(listener)
                // Existing settings consumer creates a fresh Dual presentation context after genuine Single reads.
                activity.viewModel.setMangaReadingMode(ReadingMode.DUAL_PAGE_R2L)
            }
            try {
                fixture.await("The new actual Dual owner must expose its facts before any target pixels") {
                    first.get() != null
                }
                val (facts, unpainted, proposedFrame) = requireNotNull(first.get())
                assertTrue("Known structure must precede the first Dual presentation commitment", unpainted)
                assertEquals(
                    if (firstNeighbor == 0) ReaderPageFactState.PORTRAIT else ReaderPageFactState.SPREAD,
                    facts[firstNeighbor],
                )
                assertEquals(ReaderPageFactState.SPREAD, facts[secondNeighbor])
                // Changing mode normally offers the existing navigation hint; dismiss its real click action.
                fixture.await("The mounted new Dual viewer must offer its original mode navigation hint") {
                    var offered = false
                    fixture.onReaderMain { activity ->
                        offered = descendants(activity.window.decorView).filterIsInstance<ReaderNavigationOverlayView>()
                            .any { it.isShown }
                    }
                    offered
                }
                fixture.await("The original navigation hint click must complete its normal fade-out") {
                    var hidden = false
                    fixture.onReaderMain { activity ->
                        val hints = descendants(
                            activity.window.decorView,
                        ).filterIsInstance<ReaderNavigationOverlayView>()
                        hints.filter { it.isShown && !it.hasTransientState() }.forEach { it.performClick() }
                        hints.forEach {
                            diagnostic(target, "hint=${it.isShown}/${it.alpha}/transient=${it.hasTransientState()}")
                        }
                        hidden = hints.none { it.isShown }
                    }
                    hidden
                }
                awaitOriginal(fixture, target)
                val paintedFrame = requireNotNull(firstPrepared.get())
                assertTrue("Original prepare precedes this frame's first paint", !paintedFrame.wasDrawn)
                assertEquals(expected, paintedFrame.position)
                assertEquals(listOf(facts[firstNeighbor], facts[secondNeighbor]), paintedFrame.facts)
                assertEquals(expected, slot(fixture, target))
                stableOriginal(fixture, target, requireNotNull(observeOriginal(fixture, target)))
                assertEquals(paintedFrame.frameId, frameId(fixture, target))
                Log.i("DualOriginal", "proposed=$proposedFrame before-first-target-pixels=$paintedFrame")
                assertTrue(fixture.originalArchiveIdentity?.contains("760c81b12dee") == true)
            } finally {
                fixture.onReaderMain { if (observer.isAlive) observer.removeOnPreDrawListener(listener) }
            }
        }
    }

    private fun hookFirstPrepared(
        image: ReaderPageImageView,
        target: Int,
        neighbors: List<Int>,
        result: AtomicReference<FirstPrepared?>,
    ) {
        val original = requireNotNull(image.beforeImageDraw)
        image.beforeImageDraw = { width, height ->
            val prepared = original(width, height)
            if (prepared && result.get() == null) {
                val holder = generateSequence(image.parent) { it.parent }
                    .filterIsInstance<DualPagerPageHolder>().first()
                holder.presentationScope?.let { (owner, ticket) ->
                    val frame = owner.frame(ticket)
                    val page = owner.identity.pageIds[target]
                    if (frame != null && owner.location?.pageId == page) {
                        result.set(
                            FirstPrepared(
                                frame.id,
                                frame.unit.slots.first { it.pageId == page }.position,
                                neighbors.map { owner.pageFact(owner.identity.pageIds[it])?.state },
                                page in frame.drawnPageIds,
                            ),
                        )
                    }
                }
            }
            prepared
        }
    }

    private fun frameId(fixture: HostReaderArtFixture, source: Int): ReaderDualPageFrameId {
        var result: ReaderDualPageFrameId? = null
        fixture.onReaderMain { activity ->
            val viewer = activity.viewModel.state.value.viewer as DualPageR2LPagerViewer
            val holder = descendants(viewer.pager).filterIsInstance<DualPagerPageHolder>()
                .first { it.displayPage.visiblePages.any { it.index == source } }
            val (owner, ticket) = requireNotNull(holder.presentationScope)
            result = requireNotNull(owner.frame(ticket)).id
        }
        return requireNotNull(result)
    }

    private fun seekForPreparation(fixture: HostReaderArtFixture, source: Int) {
        fixture.onReaderMain { activity ->
            val page = requireNotNull(activity.viewModel.state.value.viewerChapters?.currChapter?.pages)[source]
            requireNotNull(activity.viewModel.state.value.viewer).moveToPage(page)
        }
    }

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) {
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
    } else {
        emptyList()
    }

    private fun source(view: ReaderPageImageView): ReaderPage? =
        generateSequence<Class<*>>(view.javaClass) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }.firstOrNull { it.name == "page" }
            ?.apply { isAccessible = true }?.get(view) as? ReaderPage

    /** Public SSIV draw transform and real window pixels; no decoder, fact or draw callback is synthesized. */
    internal fun observeOriginal(fixture: HostReaderArtFixture, index: Int): HostReaderArtFixture.SlotObservation? {
        val bytes = fixture.originalEncodedPage(index)
        val expected = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        val samples = mutableListOf<Triple<Int, Int, Int>>()
        var observation: HostReaderArtFixture.SlotObservation? = null
        try {
            fixture.onReaderMain { activity ->
                val root = activity.viewModel.state.value.viewer?.getView() ?: return@onReaderMain
                val image = descendants(root).filterIsInstance<ReaderPageImageView>()
                    .firstOrNull { source(it)?.index == index && it.isShown && it.getGlobalVisibleRect(Rect()) }
                    ?: run {
                        diagnostic(index, "no-visible-source")
                        return@onReaderMain
                    }
                val native = descendants(image).filterIsInstance<SubsamplingScaleImageView>().singleOrNull()
                    ?.takeIf { it.isImageLoaded }
                    ?: run {
                        diagnostic(index, "no-loaded-native")
                        return@onReaderMain
                    }
                if (native.sWidth != expected.width || native.sHeight != expected.height) {
                    diagnostic(
                        index,
                        "size=${native.sWidth}x${native.sHeight}/expected${expected.width}x${expected.height}",
                    )
                    return@onReaderMain
                }
                val matrix = Matrix().also(native::transformMatrixToGlobal)
                val corners = floatArrayOf(
                    requireNotNull(native.sourceToViewCoord(0f, 0f)).x,
                    requireNotNull(native.sourceToViewCoord(0f, 0f)).y,
                    requireNotNull(native.sourceToViewCoord(native.sWidth.toFloat(), native.sHeight.toFloat())).x,
                    requireNotNull(native.sourceToViewCoord(native.sWidth.toFloat(), native.sHeight.toFloat())).y,
                )
                matrix.mapPoints(corners)
                val content = Rect().also { RectF(corners[0], corners[1], corners[2], corners[3]).roundOut(it) }
                if (content.left < -1 || content.top < -1 || content.right > root.width + 1 ||
                    content.bottom > root.height + 1
                ) {
                    diagnostic(index, "bounds=$content/root${root.width}x${root.height} image=$image")
                    return@onReaderMain
                }
                for (y in 1..5) {
                    for (x in 1..5) {
                        val (sx, sy) = stableSample(expected, x, y, ceil(2f / native.scale).toInt())
                        val point = requireNotNull(native.sourceToViewCoord(PointF(sx.toFloat(), sy.toFloat())))
                        val coords = floatArrayOf(point.x, point.y)
                        matrix.mapPoints(coords)
                        samples += Triple(coords[0].toInt(), coords[1].toInt(), expected.getPixel(sx, sy))
                    }
                }
                check(image.getGlobalVisibleRect(Rect()))
                val full = RectF(0f, 0f, image.width.toFloat(), image.height.toFloat())
                Matrix().also(image::transformMatrixToGlobal).mapRect(full)
                val holder = image.parent as View
                val container = holder.parent as View
                observation = HostReaderArtFixture.SlotObservation(
                    content,
                    Rect().also(full::roundOut),
                    image,
                    container,
                    container.scaleX,
                    container.scaleY,
                    container.translationX,
                    container.translationY,
                )
            }
            if (observation == null || samples.size != 25) return null
            check(colorSpan(samples.map { it.third }) > 70) { "Original spatial probes must reject a uniform fill" }
            val painted = fixture.frame() ?: return null
            try {
                val matching = samples.count { (x, y, color) ->
                    x in 0 until painted.width && y in 0 until painted.height &&
                        HostReaderArtFixture.closeColor(painted.getPixel(x, y), color)
                }
                val paintedColors = samples.mapNotNull { (x, y, _) ->
                    if (x in 0 until painted.width && y in 0 until painted.height) painted.getPixel(x, y) else null
                }
                if (matching < 20) {
                    diagnostic(
                        index,
                        "matching=$matching/25 rect=${observation?.content} samples=" + samples.map { (x, y, color) ->
                            "$x,$y:${Integer.toHexString(color)}/" +
                                if (x in 0 until painted.width && y in 0 until painted.height) {
                                    Integer.toHexString(painted.getPixel(x, y))
                                } else {
                                    "outside"
                                }
                        },
                    )
                }
                return observation.takeIf { matching >= 20 && colorSpan(paintedColors) > 70 }
            } finally {
                painted.recycle()
            }
        } finally {
            expected.recycle()
        }
    }

    private fun colorSpan(colors: List<Int>): Int = if (colors.isEmpty()) {
        0
    } else {
        listOf(0, 8, 16).maxOf { shift ->
            colors.maxOf { (it shr shift) and 255 } - colors.minOf { (it shr shift) and 255 }
        }
    }

    /** Keep the 5x5 spatial grid, moving within each small neighborhood away from downsampling edges. */
    private fun stableSample(bitmap: Bitmap, column: Int, row: Int, radius: Int): Pair<Int, Int> {
        val centerX = bitmap.width * column / 6
        val centerY = bitmap.height * row / 6
        val offsets = (-24..24 step 3).flatMap { y -> (-24..24 step 3).map { x -> x to y } }
            .sortedBy { (x, y) -> abs(x) + abs(y) }
        var selected = centerX to centerY
        var variation = Int.MAX_VALUE
        for ((dx, dy) in offsets) {
            val sx = centerX + dx
            val sy = centerY + dy
            val pixels = listOf(-radius, 0, radius).flatMap { y ->
                listOf(-radius, 0, radius).map { x ->
                    bitmap.getPixel((sx + x).coerceIn(0, bitmap.width - 1), (sy + y).coerceIn(0, bitmap.height - 1))
                }
            }
            val delta = listOf(0, 8, 16).maxOf { shift ->
                pixels.maxOf { (it shr shift) and 255 } - pixels.minOf { (it shr shift) and 255 }
            }
            if (delta < variation) {
                selected = sx to sy
                variation = delta
                if (delta <= 8) break
            }
        }
        return selected
    }

    private fun awaitOriginal(fixture: HostReaderArtFixture, index: Int) {
        fixture.awaitStable("Original source${index + 1} must produce its own actual JNI/window pixels") {
            observeOriginal(fixture, index) != null
        }
        Log.i(
            "DualOriginal",
            "archive=${fixture.originalArchiveIdentity} source=${index + 1} " +
                "actual=${observeOriginal(fixture, index)} SQL=${fixture.progress()}",
        )
    }

    private fun stableOriginal(
        fixture: HostReaderArtFixture,
        index: Int,
        before: HostReaderArtFixture.SlotObservation,
    ) {
        repeat(12) {
            assertEquals(
                "Original source pixels/geometry/mount/transform must remain stable",
                before,
                observeOriginal(fixture, index),
            )
            SystemClock.sleep(16)
        }
        Log.i("DualOriginal", "stable source=${index + 1} actual=$before")
    }

    private fun pageCount(fixture: HostReaderArtFixture): Int {
        var result = 0
        fixture.onReaderMain {
            result = requireNotNull(it.viewModel.state.value.viewerChapters?.currChapter?.pages).size
        }
        return result
    }

    private fun currentPages(fixture: HostReaderArtFixture): List<Int> {
        var result = emptyList<Int>()
        fixture.onReaderMain { activity ->
            val viewer = activity.viewModel.state.value.viewer as DualPageR2LPagerViewer
            val chapter = requireNotNull(activity.viewModel.state.value.viewerChapters?.currChapter)
            val owner = requireNotNull(viewer.adapter.session(chapter))
            result = requireNotNull(owner.targetUnit).slots.map { it.pageId.sourcePageIndex }
        }
        return result
    }

    private fun slot(fixture: HostReaderArtFixture, source: Int): ReaderPortraitSingleSlot? {
        var result: ReaderPortraitSingleSlot? = null
        fixture.onReaderMain { activity ->
            val viewer = activity.viewModel.state.value.viewer as DualPageR2LPagerViewer
            val holder = descendants(viewer.pager).filterIsInstance<DualPagerPageHolder>()
                .first { it.displayPage.visiblePages.any { it.index == source } }
            val (owner, ticket) = requireNotNull(holder.presentationScope)
            result =
                requireNotNull(owner.frame(ticket)).unit.slots.first { it.pageId.sourcePageIndex == source }.position
        }
        return result
    }

    private fun fact(fixture: HostReaderArtFixture, source: Int): ReaderPageFactState? {
        var result: ReaderPageFactState? = null
        fixture.onReaderMain { activity ->
            val viewer = activity.viewModel.state.value.viewer as DualPageR2LPagerViewer
            val chapter = requireNotNull(activity.viewModel.state.value.viewerChapters?.currChapter)
            val owner = requireNotNull(viewer.adapter.session(chapter))
            result = owner.pageFact(owner.identity.pageIds[source])?.state
        }
        return result
    }
}
