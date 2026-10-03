package mihon.presentation.history

import android.graphics.Insets
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.core.view.WindowCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tachiyomi.domain.history.service.HistoryState
import android.view.WindowInsets as PlatformWindowInsets

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h900dp-mdpi", manifest = Config.NONE)
class HistoryContentAndroidInsetsTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun `real Android status inset keeps shared history top actions below system area`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        var observedInset = 0
        try {
            WindowCompat.setDecorFitsSystemWindows(activity.get().window, false)
            activity.get().setContent {
                observedInset = WindowInsets.statusBars.getTop(LocalDensity.current)
                MaterialTheme {
                    HistoryContent(HistoryState(list = emptyList()), {}, {}, {}, {}, {}, {}, { _, _, _ -> })
                }
            }
            compose.waitForIdle()
            compose.runOnIdle {
                // Dispatch to the actual Compose platform owner; Robolectric's decor dispatch
                // does not forward synthetic insets through every ViewGroup as the device does.
                val content = activity.get().findViewById<ViewGroup>(android.R.id.content)
                val composeView = content.getChildAt(0) as ViewGroup
                composeView.getChildAt(0).dispatchApplyWindowInsets(
                    PlatformWindowInsets.Builder().setInsets(
                        PlatformWindowInsets.Type.statusBars(),
                        Insets.of(0, 48, 0, 0),
                    ).setVisible(PlatformWindowInsets.Type.statusBars(), true).build(),
                )
            }
            compose.waitForIdle()
            assertEquals(
                "The real Compose Android inset owner must receive the dispatched status inset",
                48,
                observedInset,
            )
            val search = compose.onNodeWithTag("history_search_open").fetchSemanticsNode().boundsInRoot
            val clear = compose.onNodeWithTag("history_clear_all").fetchSemanticsNode().boundsInRoot
            assertTrue("Search must remain below 48px actual Android status inset: $search", search.top >= 48f)
            assertTrue("Clear action must remain below system area: $clear", clear.top >= 48f)
        } finally {
            activity.close()
        }
    }
}
