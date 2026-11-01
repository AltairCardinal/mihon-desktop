package eu.kanade.tachiyomi.ui.reader.viewer

import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.ui.reader.HostReaderArtFixture
import eu.kanade.tachiyomi.ui.reader.actualArtField
import eu.kanade.tachiyomi.ui.reader.actualArtReaderState
import eu.kanade.tachiyomi.ui.reader.loader.HttpPageLoader
import eu.kanade.tachiyomi.ui.reader.viewer.pager.DisplayPage
import eu.kanade.tachiyomi.ui.reader.viewer.pager.DualPageR2LPagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.pager.DualPagerPageHolder
import mihon.domain.reader.ReaderDualPageFrame
import mihon.domain.reader.ReaderPageAnalysisEvidence
import mihon.domain.reader.ReaderPairingCertification
import okhttp3.Call
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.roundToInt

/** Synthetic protocol, real online loader, native image views and production navigation. */
@RunWith(Parameterized::class)
class SharedDualPageNonblockingNativeTest(private val mode: Int, private val nextKey: Int) {
    @get:Rule val compose = createEmptyComposeRule()

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "mode-{0}")
        fun vectors() = listOf(arrayOf(6, KeyEvent.KEYCODE_DPAD_LEFT), arrayOf(8, KeyEvent.KEYCODE_DPAD_RIGHT))
    }

    @Test
    fun coldSeekPaintsBeforePrefixAndRetainsItsMountWhenGeometryArrives() {
        HostReaderArtFixture("synthetic", syntheticOnlinePages = 200).use { fixture ->
            val prefix = ActiveImageGate()
            val partner = ActiveImageGate()
            val tail = ActiveImageGate(enabled = false)
            val blue = HostReaderArtFixture.png(400, 600, Color.BLUE)
            val cyan = HostReaderArtFixture.png(400, 600, Color.CYAN)
            val wide = HostReaderArtFixture.png(900, 600, Color.MAGENTA)
            val red = HostReaderArtFixture.png(400, 600, Color.RED)
            val green = HostReaderArtFixture.png(400, 600, Color.GREEN)
            val yellow = HostReaderArtFixture.png(400, 600, Color.YELLOW)
            val orange = HostReaderArtFixture.png(400, 600, Color.rgb(255, 128, 0))
            fixture.images.set(
                List(200) { index ->
                    when (index) {
                        0 -> cyan
                        21 -> wide
                        125 -> red
                        126 -> green
                        127 -> yellow
                        180 -> orange
                        else -> blue
                    }
                },
            )
            fixture.beforeCancellableImageRequest.set { index, call ->
                when (index) {
                    21 -> prefix.hold(index, call)
                    126 -> partner.hold(index, call)
                    179, 181 -> tail.hold(index, call)
                }
            }
            fixture.configure(mode = mode, matching = true)
            fixture.cold()
            try {
                fixture.launch()
                fixture.await("Synthetic page table and first real native image must load") {
                    fixture.chapterRequests.get() > 0 && fixture.containsColor(Color.CYAN)
                }
                fixture.onReaderMain { it.showMenu() }
                fixture.await("The actual 200-page slider must be available") { slider() != null }
                val sliderNode = requireNotNull(slider())
                assertTrue(
                    "The actual slider must accept page 126",
                    sliderNode.performAction(
                        AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
                        Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 126f) },
                    ),
                )
                fixture.awaitStable("Target must paint while its ordinary partner is held") {
                    fixture.logicalPageIndex() == 125 && fixture.containsColor(Color.RED)
                }
                assertPageFeedback(fixture, 125)
                progressFailureSnapshot(fixture, "before-target-progress")
                try {
                    fixture.await("Real drawn target must reach chapter progress") { fixture.progress().first == 125L }
                } catch (failure: AssertionError) {
                    progressFailureSnapshot(fixture, "failed-target-progress")
                    throw failure
                }
                fixture.await("The current ordinary partner call must actually remain held") { partner.active(126) }
                val beforeFrame = selectedFrame(fixture, 125, requireUncertified = true)
                assertEquals(setOf(125, 126), beforeFrame.unit.pageIds.map { it.sourcePageIndex }.toSet())
                assertFalse("Held partner has not drawn", beforeFrame.drawnPageIds.any { it.sourcePageIndex == 126 })
                assertTrue(
                    "Target has a real native draw acknowledgement",
                    beforeFrame.drawnPageIds.any { it.sourcePageIndex == 125 },
                )
                assertLiveMatching(fixture, requirePending = true, pendingOriginalIndex = 21)
                exerciseDefaultDocking(fixture, partner)
                exerciseLateCorrection(fixture, prefix, partner)
                exerciseTailRetry(fixture, tail)
            } finally {
                prefix.release.countDown()
                partner.release.countDown()
                tail.release.countDown()
            }
        }
    }

    private fun exerciseDefaultDocking(fixture: HostReaderArtFixture, partner: ActiveImageGate) {
        fixture.key(nextKey)
        fixture.await("Forward must first dock on the undrawn ordinary partner") { fixture.logicalPageIndex() == 126 }
        assertPageFeedback(fixture, 126)
        fixture.await("Docked partner must expose actual loading") { fixture.loadingBounds(126) != null }
        val docked = selectedFrame(fixture, 126, requireUncertified = true)
        assertEquals(setOf(125, 126), docked.unit.pageIds.map { it.sourcePageIndex }.toSet())
        assertFalse(docked.drawnPageIds.any { it.sourcePageIndex == 126 })
        assertTrue("Undrawn partner cannot be read", fixture.progress().first != 126L)
        fixture.key(nextKey)
        fixture.awaitStable("The next real turn must leave the undrawn partner") {
            fixture.logicalPageIndex() == 127 && fixture.containsColor(Color.YELLOW)
        }
        fixture.await("The incoming group must really draw before reversing") {
            selectedFrame(fixture, 127).drawnPageIds.any { it.sourcePageIndex == 127 }
        }
        assertEquals(1L, partner.release.count)
        assertTrue("Held partner has never become read", fixture.progress().first != 126L)
        navigationSnapshot(fixture, "before-default-reverse")
        fixture.key(previousKey())
        navigationSnapshot(fixture, "after-default-reverse")
        try {
            fixture.awaitStable("Reverse must return to the undrawn partner with its painted red buddy") {
                fixture.logicalPageIndex() == 126 && fixture.containsColor(Color.RED)
            }
        } catch (failure: AssertionError) {
            navigationSnapshot(fixture, "failed-default-reverse")
            throw failure
        }
        assertPageFeedback(fixture, 126)
        fixture.await("Returned partner must be held by a current uncancelled HTTP call") { partner.active(126) }
        assertFalse(selectedFrame(fixture, 126).drawnPageIds.any { it.sourcePageIndex == 126 })
    }

    private fun exerciseLateCorrection(
        fixture: HostReaderArtFixture,
        prefix: ActiveImageGate,
        partner: ActiveImageGate,
    ) {
        val redBeforePartner = fixture.observeSlot(Color.RED)
        partner.release.countDown()
        fixture.awaitStable("Released ordinary partner must draw without waiting for matching") {
            fixture.containsColor(Color.RED) && fixture.containsColor(Color.GREEN)
        }
        fixture.await("Green must acknowledge a real draw") {
            selectedFrame(fixture, 126).drawnPageIds.any { it.sourcePageIndex == 126 }
        }
        val redBefore = fixture.observeSlot(Color.RED)
        assertSame(redBeforePartner.mount, redBefore.mount)
        assertEquals(redBeforePartner.imageBounds, redBefore.imageBounds)
        val greenBefore = fixture.observeSlot(Color.GREEN)
        fixture.await("Current wide reset HTTP call must stay held after ordinary IO exits") { prefix.active(21) }
        assertLiveMatching(fixture, requirePending = true, pendingOriginalIndex = 21)
        assertEquals(1L, prefix.release.count)
        fixture.await("The actual red and green viewport must persist its maximum drawn page 126") {
            fixture.progress().first == 126L
        }
        val progressBeforeCorrection = fixture.progress()
        prefix.release.countDown()
        fixture.await("Released prefix must discover real wide page 21") {
            fixture.hasDecodedSpread(21, requirePageReady = false)
        }
        fixture.await("Real prefix evidence must certify the corrected 126/127 segment") {
            val certified = selectedCertification(fixture) as? ReaderPairingCertification.Certified
            certified?.segment?.unit?.pageIds?.map { it.sourcePageIndex }?.toSet() == setOf(126, 127)
        }
        fixture.awaitStable("Background certification must retain the painted red and green") {
            fixture.containsColor(Color.RED) && fixture.containsColor(Color.GREEN)
        }
        val redAfter = fixture.observeSlot(Color.RED)
        val greenAfter = fixture.observeSlot(Color.GREEN)
        assertSame("Late correction must retain red mount", redBefore.mount, redAfter.mount)
        assertSame("Late correction must retain green mount", greenBefore.mount, greenAfter.mount)
        assertEquals(redBefore.imageBounds, redAfter.imageBounds)
        assertEquals(greenBefore.imageBounds, greenAfter.imageBounds)
        assertEquals(
            "Background correction must retain the same resume position without manufacturing reading events",
            progressBeforeCorrection,
            fixture.progress(),
        )
        fixture.key(nextKey)
        fixture.awaitStable("A real forward turn must adopt corrected 126/127") {
            fixture.logicalPageIndex() == 127 && fixture.containsColor(Color.GREEN) &&
                fixture.containsColor(Color.YELLOW) && !fixture.containsColor(Color.RED)
        }
        assertPageFeedback(fixture, 127)
        fixture.await("The actual corrected forward viewport must save page 127") { fixture.progress().first == 127L }
        assertEquals(setOf(126, 127), selectedFrame(fixture, 127).unit.pageIds.map { it.sourcePageIndex }.toSet())
        val greenOnLeadingSide = fixture.observeSlot(Color.GREEN).content.centerX() < fixture.windowWidth() / 2
        assertEquals("Boundary green must occupy the physical leading slot", mode == 8, greenOnLeadingSide)
        fixture.key(previousKey())
        fixture.awaitStable("A real reverse turn must adopt corrected 124/125") {
            fixture.logicalPageIndex() == 125 && fixture.containsColor(Color.BLUE) && fixture.containsColor(Color.RED)
        }
        assertPageFeedback(fixture, 125)
        assertEquals(setOf(124, 125), selectedFrame(fixture, 125).unit.pageIds.map { it.sourcePageIndex }.toSet())
        fixture.await("An explicit reverse must save the maximum actually drawn page 125 as resume") {
            fixture.progress().first == 125L
        }
        fixture.onReaderMain { activity ->
            assertFalse(
                "Neither corrected viewport has drawn the chapter's actual final page",
                requireNotNull(actualArtReaderState(activity).currentChapter).chapter.read,
            )
        }
    }

    private fun exerciseTailRetry(fixture: HostReaderArtFixture, tail: ActiveImageGate) {
        for (index in listOf(179, 181)) {
            assertEquals(
                "Tail retry candidate must never have entered the HTTP client",
                0,
                fixture.imageClientRequests.get(index),
            )
            assertEquals(
                "Tail retry candidate must never have reached the server",
                0,
                fixture.imagePageRequests.get(index),
            )
            assertFalse("Tail retry candidate must be genuinely cold", fixture.syntheticImageCached(index))
        }
        tail.enabled.set(true)
        seek(fixture, 181)
        val orange = Color.rgb(255, 128, 0)
        fixture.awaitStable("Tail seek must paint real orange source 180 independently") {
            fixture.logicalPageIndex() == 180 && fixture.containsColor(orange)
        }
        assertPageFeedback(fixture, 180)
        val frame = selectedFrame(fixture, 180)
        val buddy = frame.unit.pageIds.single { it.sourcePageIndex != 180 }.sourcePageIndex
        assertTrue("Actual orange frame must select one of the two cold adjacent pages", buddy in setOf(179, 181))
        fixture.await("The actual current buddy must have an uncancelled held HTTP call") { tail.active(buddy) }
        assertFalse(frame.drawnPageIds.any { it.sourcePageIndex == buddy })
        val before = fixture.observeSlot(orange)
        fixture.await("Painted orange must reach chapter progress") { fixture.progress().first == 180L }
        fixture.imageStatuses.set(mapOf(buddy to 503))
        tail.release.countDown()
        fixture.await("The real buddy HTTP 503 must expose UI Retry") { fixture.retryBounds() != null }
        assertFalse(selectedFrame(fixture, 180).drawnPageIds.any { it.sourcePageIndex == buddy })
        fixture.imageStatuses.set(emptyMap())
        fixture.retry()
        fixture.awaitStable("Actual UI Retry must paint the recovered blue buddy") {
            fixture.containsColor(orange) && fixture.containsColor(Color.BLUE)
        }
        fixture.await("Retried buddy must have a real draw acknowledgement") {
            selectedFrame(fixture, 180).drawnPageIds.any { it.sourcePageIndex == buddy }
        }
        val after = fixture.observeSlot(orange)
        assertSame("Retry must retain already drawn orange mount", before.mount, after.mount)
        assertEquals("Retry must retain already drawn orange geometry", before.imageBounds, after.imageBounds)
        assertTrue("Retry cannot roll back real chapter progress", fixture.progress().first >= 180L)
    }

    private fun seek(fixture: HostReaderArtFixture, userPage: Int) {
        fixture.onReaderMain { it.showMenu() }
        fixture.await("Actual chapter slider must be available") { slider() != null }
        assertTrue(
            "Actual slider must accept requested integer page",
            requireNotNull(slider()).performAction(
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
                Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, userPage.toFloat()) },
            ),
        )
    }

    private fun assertPageFeedback(fixture: HostReaderArtFixture, sourceIndex: Int) {
        fixture.onReaderMain { it.showMenu() }
        try {
            logFeedback(fixture, "before-feedback-$sourceIndex")
            var lastRaw: Float? = null
            var observed = false
            fixture.await("Actual VM, visible integer and slider must follow the exact source anchor") {
                var exactModel = false
                fixture.onReaderMain { activity ->
                    exactModel = activity.viewModel.currentReaderPage?.index == sourceIndex &&
                        actualArtReaderState(activity).currentPage == sourceIndex + 1
                }
                val snapshot = feedbackSnapshot()
                val range = snapshot.slider?.rangeInfo
                if (!observed || range?.current != lastRaw) {
                    observed = true
                    lastRaw = range?.current
                    logFeedback(fixture, "observed-feedback-$sourceIndex", snapshot)
                }
                exactModel && range != null && range.min == 1f && range.max == 200f &&
                    range.current.roundToInt() == sourceIndex + 1 && abs(range.current - (sourceIndex + 1)) < 0.0001f &&
                    visibleInteger(sourceIndex + 1)
            }
            compose.onNodeWithText((sourceIndex + 1).toString(), useUnmergedTree = true).assertIsDisplayed()
            logFeedback(fixture, "settled-feedback-$sourceIndex")
        } finally {
            fixture.onReaderMain { it.hideMenu() }
        }
    }

    private fun visibleInteger(page: Int): Boolean {
        return try {
            compose.onNodeWithText(page.toString(), useUnmergedTree = true).assertIsDisplayed()
            true
        } catch (_: AssertionError) {
            false
        }
    }

    private fun logFeedback(
        fixture: HostReaderArtFixture,
        label: String,
        snapshot: FeedbackSnapshot = feedbackSnapshot(),
    ) {
        val range = snapshot.slider?.rangeInfo
        var model = "unavailable"
        fixture.onReaderMain { activity ->
            val state = actualArtReaderState(activity)
            val viewer = state.viewer as? DualPageR2LPagerViewer
            val owner = viewer?.adapter?.currentChapter?.let { viewer.adapter.session(it) }
            model = "vmSource=${activity.viewModel.currentReaderPage?.index} visibleInt=${state.currentPage} " +
                "menu=${state.menuVisible} location=${owner?.location} target=${owner?.targetFrameId} " +
                "matching=${viewer?.config?.automaticSpreadMatching} " +
                "cert=${certificationDescription(owner?.certifyPairing())}"
        }
        android.util.Log.i(
            "ReaderNonblockingNative",
            "$label raw=${range?.current} min=${range?.min} max=${range?.max} " +
                "cleared=${snapshot.cacheCleared} refreshed=${snapshot.refreshed} " +
                "texts=${snapshot.texts} bounds=${snapshot.bounds} $model",
        )
    }

    /** Observes qualification and persistence without settling, retrying or flushing the reader. */
    private fun progressFailureSnapshot(fixture: HostReaderArtFixture, label: String) {
        try {
            fixture.onReaderMain { activity ->
                fun field(owner: Any?, name: String): Any? {
                    if (owner == null) return null
                    val member = generateSequence(owner.javaClass as Class<*>?) { it.superclass }
                        .firstNotNullOfOrNull { type -> type.declaredFields.firstOrNull { it.name == name } }
                    return requireNotNull(member) { "Missing actual ${owner.javaClass.name}.$name" }
                        .apply { isAccessible = true }.get(owner)
                }
                fun identity(value: Any?): String = value?.let {
                    "${it.javaClass.simpleName}@${System.identityHashCode(it)}"
                } ?: "null"
                fun pageIndex(value: Any?): Int? =
                    (value as? eu.kanade.tachiyomi.ui.reader.model.ReaderPage)?.index
                fun pageIndices(value: Any?): List<Int?>? = (value as? Collection<*>)?.map(::pageIndex)
                val model = activity.viewModel
                val state = actualArtReaderState(activity)
                val page = model.currentReaderPage
                val chapter = state.currentChapter
                val activation = field(model, "readingActivation")
                val pending = field(model, "pendingDualViewport")
                val handle = field(model, "progressHandle")
                val lazy = field(model, "incognitoMode\$delegate") as Lazy<*>
                val viewer = state.viewer as? DualPageR2LPagerViewer
                val values = linkedMapOf(
                    "source" to page?.index,
                    "stateInt" to state.currentPage,
                    "memoryRead" to chapter?.chapter?.last_page_read,
                    "pageCanonical" to (page != null && chapter?.pages?.getOrNull(page.index) === page),
                    "activation" to identity(activation),
                    "session" to identity(field(activation, "session")),
                    "activationWindow" to field(activation, "windowSequence"),
                    "stateWindow" to field(state.chapterWindow, "activationSequence"),
                    "handle" to identity(handle),
                    "handleClosed" to field(handle, "closed"),
                    "incognito" to if (lazy.isInitialized()) lazy.value else "uninitialized",
                    "pending" to identity(pending),
                    "pendingPage" to pageIndex(field(pending, "page")),
                    "pendingIsCurrent" to (page != null && field(pending, "page") === page),
                    "pendingSequence" to field(pending, "settlementSequence"),
                    "selectedActivation" to identity(field(pending, "selectedActivation")),
                    "activationJob" to identity(field(pending, "activationJob")),
                    "presented" to pageIndices(field(pending, "presentedPages")),
                    "accepted" to pageIndices(field(pending, "acceptedPages")),
                    "settling" to field(pending, "settling"),
                    "settled" to field(pending, "settled"),
                    "resumeRequired" to field(model, "resumeSelectionRequired"),
                    "explicitResumePage" to field(model, "explicitResumePage"),
                    "changingLayout" to field(activity, "changingLayout"),
                    "protectedPage" to pageIndex(field(activity, "protectedLayoutPage")),
                    "protectedIsCurrent" to (page != null && field(activity, "protectedLayoutPage") === page),
                    "idle" to field(viewer, "isIdle"),
                    "replacingPairing" to field(viewer, "replacingPairing"),
                )
                android.util.Log.e("ReaderNonblockingProgress", "$label ${values.entries.joinToString()}")
                val coordinator = field(handle, "this\$0")
                val failures = field(coordinator, "failures") as? Map<*, *>
                val tails = field(coordinator, "tails") as? Map<*, *>
                android.util.Log.e(
                    "ReaderNonblockingProgress",
                    "$label writeFailures=${failures?.size} pendingWrites=${tails?.size}",
                )
                failures?.values?.filterIsInstance<Throwable>()?.forEach {
                    android.util.Log.e("ReaderNonblockingProgress", "Actual progress storage failure", it)
                }
                val pager = viewer?.let { field(it, "pager") as ViewGroup } ?: return@onReaderMain
                for (index in 0 until pager.childCount) {
                    val holder = pager.getChildAt(index) as? DualPagerPageHolder ?: continue
                    if (holder.displayPage != field(viewer, "currentPage")) continue
                    val (owner, ticket) = holder.presentationScope ?: continue
                    android.util.Log.e(
                        "ReaderNonblockingProgress",
                        "$label ticketEligible=${ticket.readingEligible} ticketSeq=${ticket.locationSequence} " +
                            "location=${owner.location} lastSettled=${field(owner, "lastSettledLocationSequence")} " +
                            "lastRead=${field(owner, "lastReadLocationSequence")} " +
                            "lastSettlement=${field(owner, "lastSettlementSequence")} " +
                            "rendered=${holder.renderedPageIds(owner).map { it.sourcePageIndex }} " +
                            "drawn=${owner.frame(ticket)?.drawnPageIds?.map { it.sourcePageIndex }}",
                    )
                }
            }
            android.util.Log.e("ReaderNonblockingProgress", "$label SQL=${fixture.progress()}")
        } catch (diagnostic: Throwable) {
            android.util.Log.e("ReaderNonblockingProgress", "$label qualification snapshot unavailable", diagnostic)
        }
    }

    private fun assertLiveMatching(
        fixture: HostReaderArtFixture,
        requirePending: Boolean,
        pendingOriginalIndex: Int? = null,
    ) {
        fixture.onReaderMain { activity ->
            val viewer = actualArtReaderState(activity).viewer as DualPageR2LPagerViewer
            assertTrue("Installed viewer must consume live matching preference", viewer.config.automaticSpreadMatching)
            val owner = requireNotNull(viewer.adapter.session(requireNotNull(viewer.adapter.currentChapter)))
            val certification = owner.certifyPairing()
            val reason = certificationDescription(certification)
            android.util.Log.i("ReaderNonblockingNative", "live matching=true cert=$reason")
            if (requirePending) {
                assertFalse(
                    "Pending real evidence cannot be certified",
                    certification is ReaderPairingCertification.Certified,
                )
            }
            if (pendingOriginalIndex != null) {
                val pageId = owner.identity.pageIds[pendingOriginalIndex]
                assertEquals(ReaderPageAnalysisEvidence.NotAnalyzed, owner.originalAnalysis(pageId))
            }
        }
    }

    private fun certificationDescription(value: ReaderPairingCertification?): String = when (value) {
        is ReaderPairingCertification.Certified -> "Certified:${value.segment.unit.pageIds.map { it.sourcePageIndex }}"
        is ReaderPairingCertification.NeedFacts -> "NeedFacts:${value.facts.groupingBy { it.kind }.eachCount()}"
        is ReaderPairingCertification.RetryableBlocked ->
            "RetryableBlocked:${value.failures.entries.take(8).associate { it.key.sourcePageIndex to it.value }}"
        null -> "null"
    }

    private class ActiveImageGate(enabled: Boolean = true) {
        val enabled = AtomicBoolean(enabled)
        val release = CountDownLatch(1)
        private val activeCalls = ConcurrentHashMap<Call, Int>()
        fun active(index: Int): Boolean = activeCalls.any { (call, page) -> page == index && !call.isCanceled() }
        fun hold(index: Int, call: Call) {
            if (!enabled.get() || release.count == 0L) return
            activeCalls[call] = index
            try {
                val deadline = SystemClock.uptimeMillis() + 90_000
                while (!release.await(25, TimeUnit.MILLISECONDS)) {
                    if (call.isCanceled()) throw IOException("Actual held HTTP call was cancelled")
                    check(SystemClock.uptimeMillis() < deadline) { "Actual HTTP gate timed out at page $index" }
                }
                if (call.isCanceled()) throw IOException("Released HTTP call was already cancelled")
            } finally {
                activeCalls.remove(call)
            }
        }
    }

    private fun nodes(node: AccessibilityNodeInfo): Sequence<AccessibilityNodeInfo> = sequence {
        yield(node)
        for (index in 0 until node.childCount) node.getChild(index)?.let { yieldAll(nodes(it)) }
    }

    /** Reads the actual selected descriptor separately from the order of mounted neighbor holders. */
    private fun navigationSnapshot(fixture: HostReaderArtFixture, label: String) {
        try {
            val activePackage = InstrumentationRegistry.getInstrumentation().uiAutomation
                .rootInActiveWindow?.packageName
            val interactive = InstrumentationRegistry.getInstrumentation().targetContext
                .getSystemService(android.os.PowerManager::class.java)?.isInteractive
            fixture.onReaderMain { activity ->
                val state = actualArtReaderState(activity)
                val installed = state.viewer
                val viewer = installed as? DualPageR2LPagerViewer
                fun describe(item: Any?): String = when (item) {
                    is DisplayPage ->
                        "${item.javaClass.simpleName}:${item.visiblePages.map { it.index }}:${item.frameId}"
                    null -> "null"
                    else -> item.javaClass.simpleName
                }
                fun field(name: String): Any? = viewer?.let {
                    DualPageR2LPagerViewer::class.java.getDeclaredField(name).apply { isAccessible = true }.get(it)
                }
                val current = field("currentPage")
                val selected = viewer?.adapter?.items?.getOrNull(viewer.pager.currentItem)
                val viewerIdentity = "${installed?.javaClass?.name}@${System.identityHashCode(installed)}"
                android.util.Log.i(
                    "ReaderNonblockingNavigation",
                    "$label key=${previousKey()} viewer=$viewerIdentity " +
                        "focus=${activity.hasWindowFocus()} activePackage=$activePackage interactive=$interactive " +
                        "stateInt=${state.currentPage} " +
                        "nativePosition=${viewer?.pager?.currentItem} current=${describe(current)} " +
                        "selected=${describe(selected)} idle=${field("isIdle")} " +
                        "replacing=${field("replacingPairing")} gesture=${field("gestureInProgress")}",
                )
                val owner = viewer?.adapter?.currentChapter?.let { viewer.adapter.session(it) }
                val currentOwner = (current as? DisplayPage)?.firstPage?.chapter?.let { viewer?.adapter?.session(it) }
                val holders = viewer?.pager?.let { pager ->
                    (0 until pager.childCount).mapNotNull { pager.getChildAt(it) as? DualPagerPageHolder }
                }.orEmpty()
                val owners = "owner=${System.identityHashCode(
                    owner,
                )} currentOwner=${System.identityHashCode(currentOwner)}"
                android.util.Log.i(
                    "ReaderNonblockingNavigation",
                    "$label $owners " +
                        "closed=${owner?.isClosed} location=${owner?.location} target=${owner?.targetFrameId} " +
                        "holders=${holders.map { holder ->
                            val scope = holder.presentationScope
                            "selected=${holder.displayPage == selected}/${describe(holder.displayPage)}:" +
                                "drawn=${scope?.let { it.first.frame(it.second)?.drawnPageIds }
                                    ?.map { it.sourcePageIndex }}"
                        }}",
                )
            }
        } catch (diagnostic: Throwable) {
            android.util.Log.e(
                "ReaderNonblockingNavigation",
                "$label actual navigation snapshot unavailable",
                diagnostic,
            )
        }
    }

    private fun previousKey(): Int =
        if (nextKey == KeyEvent.KEYCODE_DPAD_LEFT) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT

    private fun selectedCertification(fixture: HostReaderArtFixture): ReaderPairingCertification? {
        var result: ReaderPairingCertification? = null
        fixture.onReaderMain { activity ->
            val viewer = actualArtField<Viewer?>(actualArtReaderState(activity), "viewer") as?
                DualPageR2LPagerViewer ?: return@onReaderMain
            val chapter = viewer.adapter.currentChapter ?: return@onReaderMain
            val owner = viewer.adapter.session(chapter) ?: return@onReaderMain
            result = owner.certifyPairing()
        }
        return result
    }

    private fun selectedFrame(
        fixture: HostReaderArtFixture,
        index: Int,
        requireUncertified: Boolean = false,
    ): ReaderDualPageFrame {
        var result: ReaderDualPageFrame? = null
        fixture.onReaderMain { activity ->
            val viewer = actualArtField<Viewer?>(actualArtReaderState(activity), "viewer")
            val dualViewer = requireNotNull(viewer as? DualPageR2LPagerViewer)
            assertTrue(
                "Synthetic source must use the real online page loader",
                dualViewer.adapter.currentChapter?.pageLoader is HttpPageLoader,
            )
            val pager = actualArtField<ViewGroup>(dualViewer, "pager")
            for (child in 0 until pager.childCount) {
                val holder = pager.getChildAt(child) as? DualPagerPageHolder ?: continue
                val (owner, ticket) = holder.presentationScope ?: continue
                if (owner.location?.pageId?.sourcePageIndex != index) continue
                val frame = owner.frame(ticket) ?: continue
                if (frame.id != owner.targetFrameId) continue
                if (requireUncertified) {
                    assertFalse(
                        "Unavailable real prefix evidence must leave the target uncertified",
                        owner.certifyPairing() is ReaderPairingCertification.Certified,
                    )
                }
                result = frame
                break
            }
        }
        return requireNotNull(result) { "Selected native holder must expose its real frame" }
    }

    private data class FeedbackSnapshot(
        val slider: AccessibilityNodeInfo?,
        val texts: List<String>,
        val bounds: List<String>,
        val cacheCleared: Boolean?,
        val refreshed: Boolean,
    )

    private fun feedbackSnapshot(): FeedbackSnapshot {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        val cleared = if (Build.VERSION.SDK_INT >= 34) automation.clearCache() else null
        val root = automation.rootInActiveWindow
        val refreshed = root?.refresh() == true
        val entries = if (refreshed && root?.packageName?.toString() == instrumentation.targetContext.packageName) {
            nodes(requireNotNull(root)).toList()
        } else {
            emptyList()
        }
        val slider = entries.firstOrNull { node ->
            node.rangeInfo?.let { it.max == 200f && it.min == 1f } == true &&
                node.actionList.any { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id }
        }
        val texts = entries.asSequence().filter { it.isVisibleToUser && it.text != null }
            .take(16).map { it.text.toString() }.toList()
        val bounds = entries.asSequence().filter { it.text != null || it.rangeInfo != null }.take(24).map { node ->
            val rectangle = Rect().also(node::getBoundsInScreen)
            "${node.text ?: node.className}:visible=${node.isVisibleToUser}:$rectangle"
        }.toList()
        return FeedbackSnapshot(slider, texts, bounds, cleared, refreshed)
    }

    private fun slider(): AccessibilityNodeInfo? = feedbackSnapshot().slider
}
