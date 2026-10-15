package eu.kanade.presentation.easteregg

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import mihon.domain.easteregg.PigEasterEggController
import mihon.domain.easteregg.PigEasterEggTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w800dp-h1200dp-mdpi")
class AndroidPigEasterEggOverlayTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun trigger_fires_controller_when_pig_emoji_entered() {
        val controller = PigEasterEggController()
        assertEquals(0, controller.triggerCount.value)

        assertTrue(PigEasterEggTrigger.check("海贼王 🐖", controller))
        assertEquals(1, controller.triggerCount.value)

        assertFalse(PigEasterEggTrigger.check("普通搜索", controller))
        assertEquals(1, controller.triggerCount.value)
    }

    @Test
    fun overlay_renders_seven_pigs_and_clicking_removes_them() {
        val controller = PigEasterEggController()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

        activity.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    AndroidPigEasterEggOverlay(
                        controller = controller,
                        initialLifetimeMillis = 10_000L,
                        giantLifetimeMillis = 5_000L,
                    )
                }
            }
        }

        composeRule.waitForIdle()
        assertEquals(0, composeRule.onAllNodesWithTag(ANDROID_PIG_EASTER_EGG_TAG).fetchSemanticsNodes().size)

        // Trigger easter egg
        controller.fire()
        composeRule.waitForIdle()

        val pigsBefore = composeRule.onAllNodesWithTag(ANDROID_PIG_EASTER_EGG_TAG).fetchSemanticsNodes()
        assertEquals(7, pigsBefore.size)

        // Click first pig
        composeRule.onAllNodesWithTag(ANDROID_PIG_EASTER_EGG_TAG).onFirst().performClick()
        composeRule.waitForIdle()

        val pigsAfter = composeRule.onAllNodesWithTag(ANDROID_PIG_EASTER_EGG_TAG).fetchSemanticsNodes()
        assertEquals(6, pigsAfter.size)
        assertTrue(
            "clicking a pig should spawn a lingering snout",
            composeRule.onAllNodesWithTag(ANDROID_PIG_EASTER_EGG_SNOUT_TAG).fetchSemanticsNodes().isNotEmpty(),
        )

        // Click remaining 6 pigs
        repeat(6) {
            composeRule.onAllNodesWithTag(ANDROID_PIG_EASTER_EGG_TAG).onFirst().performClick()
            composeRule.waitForIdle()
        }

        // All 7 clicked: giant pig appears!
        composeRule.onNodeWithTag(ANDROID_PIG_EASTER_EGG_GIANT_TAG).assertExists()
    }
}
