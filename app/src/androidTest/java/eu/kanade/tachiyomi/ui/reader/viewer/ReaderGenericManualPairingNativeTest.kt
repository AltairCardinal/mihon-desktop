package eu.kanade.tachiyomi.ui.reader.viewer

import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.ui.reader.HostReaderArtFixture
import eu.kanade.tachiyomi.ui.reader.actualArtField
import eu.kanade.tachiyomi.ui.reader.actualArtReaderState
import eu.kanade.tachiyomi.ui.reader.viewer.pager.DualPagerPageHolder
import mihon.domain.reader.ReaderDualPageSlot
import mihon.domain.reader.ReaderDualPageUnit
import mihon.domain.reader.ReaderDualPageViewportTicket
import mihon.domain.reader.ReaderPortraitSingleSlot
import mihon.domain.reader.SharedDualPageSession
import mihon.domain.reader.session.ReaderPageId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real release UI events, original save chain and a second Activity; no image inspection by the agent. */
class ReaderGenericManualPairingNativeTest {
    @Test
    fun originalAdjustButtonPairsFirstPagesAndRestoresWithoutAnOpeningMenu() {
        HostReaderArtFixture("copymanga").use { fixture ->
            fixture.images.set(List(22) { HostReaderArtFixture.png(400, 600, Color.RED) })
            fixture.response.set(200 to fixture.successWithImageIdentity("manual_ui_${SystemClock.uptimeMillis()}"))
            fixture.configure(mode = 6, matching = false)
            fixture.cold()
            fixture.launch(initialPage = 0)
            fixture.awaitStable("The ordinary first page must actually paint") { fixture.containsColor(Color.RED) }
            showControls(fixture)
            var adjustLabel = ""
            fixture.onReaderMain { activity ->
                val id = activity.resources.getIdentifier("action_adjust_page_pairing", "string", activity.packageName)
                check(id != 0) { "The real adjust control must have its actual accessibility label" }
                adjustLabel = activity.getString(id)
            }
            fixture.await("The original adjust button must be exposed and enabled") {
                adjustButton(adjustLabel)?.isEnabled == true
            }
            assertFalse(nodes().any { it.text?.toString() in setOf("Chapter opening", "章节开篇", "章節開篇") })
            // The isolated fixture may retain the prior run's real saved pair. Split it through the
            // same user control so every run exercises a new save without deleting persistence rows.
            if (currentSlots(fixture).keys == setOf(0, 1)) {
                assertTrue(requireNotNull(adjustButton(adjustLabel)).performAction(AccessibilityNodeInfo.ACTION_CLICK))
                fixture.await("The fixture's previous saved pair must split through the original control") {
                    currentSlots(fixture).keys == setOf(1) && adjustButton(adjustLabel)?.isEnabled == true
                }
                fixture.key(KeyEvent.KEYCODE_DPAD_RIGHT)
                fixture.await("A real backward turn must return to the separate cover") {
                    currentSlots(fixture).keys == setOf(0)
                }
                showControls(fixture)
                fixture.await("The general adjustment must be available again") {
                    adjustButton(adjustLabel)?.isEnabled == true
                }
            }
            assertEquals(setOf(0), currentSlots(fixture).keys)
            val button = requireNotNull(adjustButton(adjustLabel))
            assertTrue(
                "The actual accessibility click must dispatch",
                button.performAction(AccessibilityNodeInfo.ACTION_CLICK),
            )
            val expected = mapOf(0 to ReaderPortraitSingleSlot.RIGHT, 1 to ReaderPortraitSingleSlot.LEFT)
            fixture.await("The ordinary adjustment must create the first adjacent pair") {
                currentSlots(fixture) == expected
            }
            fixture.launch(initialPage = 0)
            fixture.await("A new reader Activity must restore the saved first pair") {
                currentSlots(fixture) == expected
            }
            assertEquals(0, fixture.logicalPageIndex())
        }
    }

    private fun showControls(fixture: HostReaderArtFixture) {
        var shown = false
        fixture.onReaderMain { shown = actualArtField<Boolean>(actualArtReaderState(it), "menuVisible") }
        if (!shown) fixture.key(KeyEvent.KEYCODE_MENU)
    }

    private fun adjustButton(label: String): AccessibilityNodeInfo? {
        var node = nodes().firstOrNull { it.contentDescription?.toString() == label } ?: return null
        while (!node.isClickable) node = node.parent ?: return null
        return node
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow ?: return emptyList()
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        val result = mutableListOf<AccessibilityNodeInfo>()
        pending.add(root)
        while (pending.isNotEmpty() && result.size < 256) {
            val node = pending.removeFirst()
            result += node
            for (index in 0 until node.childCount) node.getChild(index)?.let(pending::add)
        }
        return result
    }

    private fun currentSlots(fixture: HostReaderArtFixture): Map<Int, ReaderPortraitSingleSlot> {
        var result = emptyMap<Int, ReaderPortraitSingleSlot>()
        fixture.onReaderMain { activity ->
            val viewer = requireNotNull(actualArtField<Viewer?>(actualArtReaderState(activity), "viewer"))
            val pager = actualArtField<ViewGroup>(viewer, "pager")
            val origin = IntArray(2)
            activity.window.decorView.getLocationOnScreen(origin)
            val centerX = origin[0] + activity.window.decorView.width / 2
            val centerY = origin[1] + activity.window.decorView.height / 2
            for (index in 0 until pager.childCount) {
                val holder = pager.getChildAt(index) as? DualPagerPageHolder ?: continue
                val bounds = Rect()
                if (!holder.getGlobalVisibleRect(bounds) || !bounds.contains(centerX, centerY)) continue
                val scope = actualArtField<Pair<SharedDualPageSession, ReaderDualPageViewportTicket>?>(
                    holder,
                    "presentationScope",
                ) ?: continue
                val frame = scope.first.frame(scope.second) ?: continue
                val unit = actualArtField<ReaderDualPageUnit>(frame, "unit")
                val slots = actualArtField<List<ReaderDualPageSlot>>(unit, "slots")
                result = slots.associate {
                    val page = actualArtField<ReaderPageId>(it, "pageId")
                    actualArtField<Int>(page, "sourcePageIndex") to actualArtField(it, "position")
                }
            }
        }
        return result
    }
}
