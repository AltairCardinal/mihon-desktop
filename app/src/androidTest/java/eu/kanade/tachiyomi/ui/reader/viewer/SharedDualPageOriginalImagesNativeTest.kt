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
import eu.kanade.tachiyomi.ui.reader.actualArtField
import eu.kanade.tachiyomi.ui.reader.actualArtReaderState
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.viewer.pager.DualPageR2LPagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.pager.DualPagerPageHolder
import mihon.domain.reader.ReaderDualPageFrame
import mihon.domain.reader.ReaderDualPageFrameId
import mihon.domain.reader.ReaderDualPageIdentity
import mihon.domain.reader.ReaderDualPageLocationIntent
import mihon.domain.reader.ReaderDualPageSlot
import mihon.domain.reader.ReaderDualPageUnit
import mihon.domain.reader.ReaderDualPageViewportTicket
import mihon.domain.reader.ReaderPageFact
import mihon.domain.reader.ReaderPageFactState
import mihon.domain.reader.ReaderPortraitSingleSlot
import mihon.domain.reader.SharedDualPageSession
import mihon.domain.reader.session.ReaderPageId
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
        val ordinaryReady: List<Boolean>,
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
            val commitTrace = mutableListOf<String>()
            var candidateScheduled = false
            lateinit var observer: ViewTreeObserver
            lateinit var listener: ViewTreeObserver.OnPreDrawListener
            fixture.onReaderMain { activity ->
                observer = activity.window.decorView.viewTreeObserver
                listener = ViewTreeObserver.OnPreDrawListener {
                    if (!candidateScheduled) {
                        candidateScheduled = observeFirstCommit(
                            activity,
                            14,
                            listOf(14, 15),
                            commitTrace,
                        ) { snapshot, pages ->
                            timely.compareAndSet(null, Triple(snapshot.facts, pages, snapshot.ordinaryReady))
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
            if (timely.get() == null) {
                fixture.onReaderMain {
                    commitTrace.forEach { Log.i("DualOriginal", "warm-first-commit=$it") }
                }
            }
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
            val nextHeld = CountDownLatch(1)
            val earlierEntered = CountDownLatch(1)
            val nextEntered = CountDownLatch(1)
            fixture.beforeImageRequest.set {
                if (it in neighbors) {
                    if (it == target - 1) earlierEntered.countDown()
                    if (it == target + 1) nextEntered.countDown()
                    val gate = if (target == 19 && it == target + 1) nextHeld else held
                    check(gate.await(90, TimeUnit.SECONDS))
                }
            }
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
                if (target == 19) {
                    nextHeld.countDown()
                    fixture.await("Original source21 must deliver its real late wide header first") {
                        fact(fixture, target + 1) == ReaderPageFactState.SPREAD
                    }
                    assertEquals(0L, nextEntered.count)
                    assertEquals(ReaderPageFactState.UNKNOWN, fact(fixture, target - 1))
                    assertEquals(target, fixture.logicalPageIndex())
                    stableOriginal(fixture, target, before)
                    assertEquals(ReaderPortraitSingleSlot.RIGHT, slot(fixture, target))
                }
                val originalFrame = frameId(fixture, target)
                if (target == 19) {
                    held.countDown()
                    fixture.key(KeyEvent.KEYCODE_DPAD_RIGHT)
                    awaitOriginal(fixture, target - 1)
                    assertEquals(target - 1, fixture.logicalPageIndex())
                    assertEquals(0L, earlierEntered.count)
                    assertEquals(ReaderPortraitSingleSlot.FULL, slot(fixture, target - 1))
                }
                held.countDown()
                fixture.await("Actual original neighbor bytes must confirm their oriented wide headers") {
                    neighbors.all { fact(fixture, it) == ReaderPageFactState.SPREAD }
                }
                if (target == 19) {
                    fixture.key(KeyEvent.KEYCODE_DPAD_LEFT)
                    awaitOriginal(fixture, target)
                    assertEquals(target, fixture.logicalPageIndex())
                    assertEquals(originalFrame, frameId(fixture, target))
                    fixture.awaitStable("Return by the real key to source20 original geometry") {
                        observeOriginal(fixture, target)?.let {
                            it.content == before.content && it.imageBounds == before.imageBounds &&
                                it.scaleX == before.scaleX && it.scaleY == before.scaleY &&
                                it.translationX == before.translationX && it.translationY == before.translationY
                        } == true
                    }
                    stableOriginal(fixture, target, requireNotNull(observeOriginal(fixture, target)))
                } else {
                    stableOriginal(fixture, target, before)
                }
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
                nextHeld.countDown()
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
            var candidateScheduled = false
            lateinit var observer: ViewTreeObserver
            lateinit var listener: ViewTreeObserver.OnPreDrawListener
            fixture.onReaderMain { activity ->
                observer = activity.window.decorView.viewTreeObserver
                listener = ViewTreeObserver.OnPreDrawListener {
                    val viewer = actualArtField<Viewer?>(actualArtReaderState(activity), "viewer")
                        as? DualPageR2LPagerViewer
                    val chapter = viewer?.let { actualChapter(activity) }
                    if (viewer != null && chapter != null) {
                        val holder = descendants(viewer.pager).filterIsInstance<DualPagerPageHolder>()
                            .firstOrNull { actualDisplayPages(it).any { page -> page.index == target } }
                        holder?.let(::actualScope)?.let { (owner, ticket) ->
                            if (first.get() == null) {
                                val frame = requireNotNull(owner.frame(ticket))
                                first.set(
                                    Triple(
                                        listOf(firstNeighbor, secondNeighbor).associateWith {
                                            actualFact(owner, it)
                                        },
                                        actualArtField<Set<ReaderPageId>>(frame, "lockedPageIds").isEmpty(),
                                        actualArtField(frame, "id"),
                                    ),
                                )
                            }
                        }
                        if (!candidateScheduled) {
                            candidateScheduled = observeFirstCommit(
                                activity,
                                target,
                                listOf(firstNeighbor, secondNeighbor),
                            ) { snapshot, _ ->
                                firstPrepared.compareAndSet(null, snapshot)
                            }
                        }
                    }
                    true
                }
                observer.addOnPreDrawListener(listener)
            }
            // The original default-mode preference consumer creates a new Dual viewer after genuine Single reads.
            fixture.configure(mode = 6)
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
                assertTrue("The same committed frame was unpainted at its pre-draw", !paintedFrame.wasDrawn)
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

    /** Brackets the original synchronous SSIV prepare/drawn chain within one actual committed frame. */
    private fun observeFirstCommit(
        activity: ReaderActivity,
        target: Int,
        neighbors: List<Int>,
        trace: MutableList<String>? = null,
        accept: (FirstPrepared, List<Int>) -> Unit,
    ): Boolean {
        fun note(reason: String) {
            if (trace != null && trace.size < 48 && trace.none { it.substringAfter('|') == reason }) {
                trace.add("${SystemClock.uptimeMillis()}|$reason")
            }
        }
        val viewer = actualArtField<Viewer?>(actualArtReaderState(activity), "viewer")
            as? DualPageR2LPagerViewer ?: return false
        val targetImages = descendants(viewer.pager).filterIsInstance<ReaderPageImageView>()
            .filter { source(it)?.index == target }
        if (trace != null) {
            if (targetImages.isEmpty()) note("no-mounted-target")
            targetImages.forEach { image ->
                val rect = Rect()
                val visible = image.getGlobalVisibleRect(rect)
                val native = descendants(image).filterIsInstance<SubsamplingScaleImageView>().singleOrNull()
                val holder = generateSequence(image.parent) { it.parent }
                    .filterIsInstance<DualPagerPageHolder>().first()
                val scope = actualScope(holder)
                val frame = scope?.let { it.first.frame(it.second) }
                val location = scope?.let { actualArtField<ReaderDualPageLocationIntent?>(it.first, "location") }
                val index = location?.let {
                    actualArtField<Int>(actualArtField<ReaderPageId>(it, "pageId"), "sourcePageIndex")
                }
                val drawn = frame?.let { actualArtField<Set<ReaderPageId>>(it, "drawnPageIds") }
                    ?.map { actualArtField<Int>(it, "sourcePageIndex") }
                note(
                    "pre image=${System.identityHashCode(image)} shown=${image.isShown}/$visible rect=$rect " +
                        "native=${native?.isReady}/${native?.isImageLoaded} location=$index " +
                        "frame=${frame?.let { actualArtField<ReaderDualPageFrameId>(it, "id") }} drawn=$drawn",
                )
            }
        }
        val image = targetImages.firstOrNull {
            it.isShown &&
                descendants(it).filterIsInstance<SubsamplingScaleImageView>().any { native ->
                    native.isReady || native.isImageLoaded
                }
        } ?: return false
        val holder = generateSequence(image.parent) { it.parent }.filterIsInstance<DualPagerPageHolder>().first()
        val (owner, ticket) = actualScope(holder) ?: return false
        val frame = owner.frame(ticket) ?: return false
        val page = actualPages(owner)[target]
        val location = actualArtField<ReaderDualPageLocationIntent?>(owner, "location") ?: return false
        if (actualArtField<ReaderPageId>(location, "pageId") != page) {
            note("reject-location")
            return false
        }
        val drawn = actualArtField<Set<ReaderPageId>>(frame, "drawnPageIds")
        if (page in drawn) {
            note("reject-already-drawn")
            return false
        }
        val slots = actualSlots(frame)
        val targetSlot = slots.firstOrNull { actualArtField<ReaderPageId>(it, "pageId") == page } ?: return false
        val snapshot = FirstPrepared(
            actualArtField(frame, "id"),
            actualArtField(targetSlot, "position"),
            neighbors.map { actualFact(owner, it) },
            false,
            slots.map {
                val index = actualArtField<Int>(actualArtField<ReaderPageId>(it, "pageId"), "sourcePageIndex")
                requireNotNull(actualChapter(activity).pages)[index].status == Page.State.Ready
            },
        )
        val sourcePages = slots.map {
            actualArtField<Int>(actualArtField<ReaderPageId>(it, "pageId"), "sourcePageIndex")
        }
        val observer = activity.window.decorView.viewTreeObserver
        check(activity.window.decorView.isHardwareAccelerated) { "Frame commit requires the real hardware renderer" }
        observer.registerFrameCommitCallback {
            // Read on the original UI owner; never call prepare/reportDrawn or replace production callbacks.
            activity.runOnUiThread {
                val current = actualScope(holder) ?: run {
                    note("commit-no-scope")
                    return@runOnUiThread
                }
                if (current.first !== owner || current.second !== ticket ||
                    !image.isShown || !image.getGlobalVisibleRect(Rect())
                ) {
                    note("commit-stale-owner-ticket-or-hidden")
                    return@runOnUiThread
                }
                val committed = owner.frame(ticket) ?: return@runOnUiThread
                if (actualArtField<ReaderDualPageFrameId>(committed, "id") != snapshot.frameId) return@runOnUiThread
                if (page !in actualArtField<Set<ReaderPageId>>(committed, "drawnPageIds")) {
                    note("commit-not-drawn")
                    return@runOnUiThread
                }
                if (page !in actualArtField<Set<ReaderPageId>>(committed, "lockedPageIds")) return@runOnUiThread
                val currentSlots = actualSlots(committed)
                val currentPages = currentSlots.map {
                    actualArtField<Int>(actualArtField<ReaderPageId>(it, "pageId"), "sourcePageIndex")
                }
                if (currentPages != sourcePages) return@runOnUiThread
                val currentSlot = currentSlots.firstOrNull {
                    actualArtField<ReaderPageId>(it, "pageId") == page
                } ?: return@runOnUiThread
                if (actualArtField<ReaderPortraitSingleSlot>(currentSlot, "position") != snapshot.position) {
                    return@runOnUiThread
                }
                accept(snapshot, sourcePages)
                note("commit-accepted frame=${snapshot.frameId} sources=$sourcePages")
            }
        }
        note("scheduled frame=${snapshot.frameId} sources=$sourcePages facts=${snapshot.facts}")
        return true
    }

    private fun actualScope(holder: DualPagerPageHolder): Pair<SharedDualPageSession, ReaderDualPageViewportTicket>? =
        actualArtField(holder, "presentationScope")

    private fun actualDisplayPages(holder: DualPagerPageHolder): List<ReaderPage> =
        actualArtField<eu.kanade.tachiyomi.ui.reader.viewer.pager.DisplayPage>(holder, "displayPage").visiblePages

    private fun actualPages(owner: SharedDualPageSession): List<ReaderPageId> =
        actualArtField(actualArtField<ReaderDualPageIdentity>(owner, "identity"), "pageIds")

    private fun actualFact(owner: SharedDualPageSession, index: Int): ReaderPageFactState? =
        actualArtField<Map<ReaderPageId, ReaderPageFact>>(owner, "facts")[actualPages(owner)[index]]
            ?.let { actualArtField(it, "state") }

    private fun actualSlots(frame: ReaderDualPageFrame): List<ReaderDualPageSlot> =
        actualArtField(actualArtField<ReaderDualPageUnit>(frame, "unit"), "slots")

    private fun frameId(fixture: HostReaderArtFixture, source: Int): ReaderDualPageFrameId {
        var result: ReaderDualPageFrameId? = null
        fixture.onReaderMain { activity ->
            val viewer = actualViewer(activity)
            val holder = descendants(viewer.pager).filterIsInstance<DualPagerPageHolder>()
                .first { actualDisplayPages(it).any { page -> page.index == source } }
            val (owner, ticket) = requireNotNull(actualScope(holder))
            result = actualArtField(requireNotNull(owner.frame(ticket)), "id")
        }
        return requireNotNull(result)
    }

    private fun seekForPreparation(fixture: HostReaderArtFixture, source: Int) {
        fixture.onReaderMain { activity ->
            val page = requireNotNull(actualChapter(activity).pages)[source]
            requireNotNull(actualArtField<Viewer?>(actualArtReaderState(activity), "viewer")).moveToPage(page)
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
                val root = actualArtField<Viewer?>(actualArtReaderState(activity), "viewer")
                    ?.getView() ?: return@onReaderMain
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
        fixture.onReaderMain { activity -> result = actualChapter(activity).pages!!.size }
        return result
    }

    private fun actualChapter(activity: ReaderActivity): eu.kanade.tachiyomi.ui.reader.model.ReaderChapter {
        val chapters = requireNotNull(
            actualArtField<eu.kanade.tachiyomi.ui.reader.model.ViewerChapters?>(
                actualArtReaderState(activity),
                "viewerChapters",
            ),
        )
        return actualArtField(chapters, "currChapter")
    }

    private fun actualViewer(activity: ReaderActivity): DualPageR2LPagerViewer =
        requireNotNull(actualArtField<Viewer?>(actualArtReaderState(activity), "viewer")) as DualPageR2LPagerViewer

    private fun actualAdapter(viewer: DualPageR2LPagerViewer):
        eu.kanade.tachiyomi.ui.reader.viewer.pager.DualPageViewerAdapter = actualArtField(viewer, "adapter")

    private fun currentPages(fixture: HostReaderArtFixture): List<Int> {
        var result = emptyList<Int>()
        val logical = fixture.logicalPageIndex()
        fixture.onReaderMain { activity ->
            val viewer = actualViewer(activity)
            val holder = descendants(viewer.pager).filterIsInstance<DualPagerPageHolder>()
                .first { actualDisplayPages(it).any { page -> page.index == logical } }
            val (owner, ticket) = requireNotNull(actualScope(holder))
            result = actualSlots(requireNotNull(owner.frame(ticket))).map {
                actualArtField<Int>(actualArtField<ReaderPageId>(it, "pageId"), "sourcePageIndex")
            }
        }
        return result
    }

    private fun slot(fixture: HostReaderArtFixture, source: Int): ReaderPortraitSingleSlot? {
        var result: ReaderPortraitSingleSlot? = null
        fixture.onReaderMain { activity ->
            val viewer = actualViewer(activity)
            val holder = descendants(viewer.pager).filterIsInstance<DualPagerPageHolder>()
                .first {
                    actualArtField<eu.kanade.tachiyomi.ui.reader.viewer.pager.DisplayPage>(it, "displayPage")
                        .visiblePages.any { page -> page.index == source }
                }
            val (owner, ticket) = requireNotNull(
                actualArtField<
                    Pair<
                        mihon.domain.reader.SharedDualPageSession,
                        mihon.domain.reader.ReaderDualPageViewportTicket,
                        >?,
                    >(holder, "presentationScope"),
            )
            val frame = requireNotNull(owner.frame(ticket))
            val unit = actualArtField<mihon.domain.reader.ReaderDualPageUnit>(frame, "unit")
            val slots = actualArtField<List<mihon.domain.reader.ReaderDualPageSlot>>(unit, "slots")
            val slot = slots.first {
                val page = actualArtField<mihon.domain.reader.session.ReaderPageId>(it, "pageId")
                actualArtField<Int>(page, "sourcePageIndex") == source
            }
            result = actualArtField(slot, "position")
        }
        return result
    }

    private fun fact(fixture: HostReaderArtFixture, source: Int): ReaderPageFactState? {
        var result: ReaderPageFactState? = null
        fixture.onReaderMain { activity ->
            val owner = requireNotNull(actualAdapter(actualViewer(activity)).session(actualChapter(activity)))
            val identity = actualArtField<mihon.domain.reader.ReaderDualPageIdentity>(owner, "identity")
            val pages = actualArtField<List<mihon.domain.reader.session.ReaderPageId>>(identity, "pageIds")
            val facts = actualArtField<
                Map<
                    mihon.domain.reader.session.ReaderPageId,
                    mihon.domain.reader.ReaderPageFact,
                    >,
                >(owner, "facts")
            result = facts[pages[source]]?.let { actualArtField(it, "state") }
        }
        return result
    }
}
