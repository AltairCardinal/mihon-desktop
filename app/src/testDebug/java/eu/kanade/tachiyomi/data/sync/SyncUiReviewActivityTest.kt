package eu.kanade.tachiyomi.data.sync

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SyncUiReviewActivityTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<SyncUiReviewActivity>

    @After
    fun tearDown() {
        if (::activity.isInitialized) activity.pause().stop().destroy()
    }

    @Test
    fun `review launcher opens production sync sheet and its real settings page`() {
        activity = Robolectric.buildActivity(SyncUiReviewActivity::class.java).setup()
        val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(activity.get().packageName)
        val launcherActivities = activity.get().packageManager.queryIntentActivities(launchIntent, 0)
        org.junit.Assert.assertTrue(
            launcherActivities.any { it.activityInfo.name == SyncUiReviewActivity::class.java.name },
        )
        compose.onNodeWithTag("sync-review-scenario-connected").assertIsDisplayed().performClick()
        compose.onNodeWithTag("sync-now").assertIsDisplayed()
        compose.onNodeWithTag("sync-settings").performClick()
        compose.onNodeWithTag("sync-settings-list", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("sync-back").performClick()
        compose.onNodeWithTag("sync-now").assertIsDisplayed()
        compose.onNodeWithTag("sync-close").performClick()
        compose.onNodeWithTag("sync-review-scenario-connected").assertIsDisplayed().performClick()
        compose.onNodeWithTag("sync-now").assertIsDisplayed()
    }
}
