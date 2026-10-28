package eu.kanade.tachiyomi.ui.browse.author

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tachiyomi.i18n.MR

/** The author page offers a manual work search that opens the global search with the author name. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "zh-rCN-w600dp-h900dp-mdpi")
class AndroidAuthorManualSearchTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun `manual work search opens the global search with the author name`() = withActivity {
        val opened = mutableListOf<GlobalSearchScreen>()
        val screen = requireNotNull(authorManualSearchScreen("Jane Doe"))
        setContent {
            MaterialTheme {
                CreatorActionRow(
                    followed = false,
                    scanning = false,
                    manualSearchScreen = screen,
                    onToggleFollow = {},
                    onScan = {},
                    onManualSearch = { opened += it },
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText(label()).assertIsDisplayed()
        compose.onNodeWithText(label()).performClick()

        assertEquals(listOf(screen), opened)
        assertEquals("Jane Doe", screen.searchQuery)
    }

    @Test
    fun `manual work search is disabled without an author name`() = withActivity {
        setContent {
            MaterialTheme {
                CreatorActionRow(
                    followed = false,
                    scanning = false,
                    manualSearchScreen = authorManualSearchScreen("   "),
                    onToggleFollow = {},
                    onScan = {},
                    onManualSearch = {},
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText(label()).assertIsDisplayed()
        compose.onNodeWithText(label()).assertIsNotEnabled()
    }

    @Test
    fun `manual work search has no screen without an author name`() {
        assertNull(authorManualSearchScreen(null))
        assertNull(authorManualSearchScreen("  "))
        assertNotNull(authorManualSearchScreen("Jane Doe"))
    }

    private fun label(): String =
        androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
            .getString(MR.strings.desktop_ui_manual_search_works.resourceId)

    private fun withActivity(block: ComponentActivity.() -> Unit) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().block()
        } finally {
            activity.pause().stop().destroy()
        }
    }
}
