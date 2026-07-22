package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.PreferenceScreen
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import eu.kanade.presentation.util.LocalBackPress
import mihon.domain.settings.SearchablePreference
import mihon.domain.settings.SearchableSettingsScreen
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsSearchNavigationUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @After
    fun tearDown() = run { SearchableSettings.highlightKey = null }

    @Test
    fun searchResultDefersIndexAndUsesSharedPolicyInRtl() {
        val destination = SettingsSearchScreen()
        val entry = SearchablePreference.Group("Group", listOf(SearchablePreference.Entry("Match")))
        val screen = SearchableSettingsScreen<Screen>(destination, "Appearance", listOf(entry))
        var query by mutableStateOf("")
        var indexCalls = 0
        var firstQueryCalls = 0
        var replaced: Screen? = null

        @androidx.compose.runtime.Composable
        fun index() = listOf(screen).also { indexCalls++ }
        composeRule.setContent {
            TachiyomiPreviewTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    SearchResult(
                        indexProvider = ::index,
                        searchKey = query,
                        replace = {
                            assertEquals("Match", SearchableSettings.highlightKey)
                            replaced = it
                        },
                    )
                }
            }
        }
        composeRule.runOnIdle {
            assertEquals(0, indexCalls)
            query = "absent"
        }
        composeRule.onNodeWithText("No results found").assertIsDisplayed()
        composeRule.runOnIdle {
            firstQueryCalls = indexCalls
            query = "match"
        }
        composeRule.onNodeWithText("Group < Appearance").assertIsDisplayed()
        composeRule.onNodeWithText("Match").performClick()
        composeRule.runOnIdle {
            assertEquals(true, indexCalls > firstQueryCalls)
            assertEquals(destination, replaced)
        }
    }

    @Test
    fun preferenceScreenWaitsThenScrollsToFirstDuplicate() {
        fun item(title: String) = Preference.PreferenceItem.TextPreference(title)
        val items = buildList {
            repeat(8) { add(item("Before $it")) }
            add(item("Duplicate"))
            add(item("After first"))
            repeat(12) { add(item("Between $it")) }
            add(item("Duplicate"))
            add(item("After second"))
        }
        SearchableSettings.highlightKey = "Duplicate"
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent { TachiyomiPreviewTheme { PreferenceScreen(items, Modifier.height(180.dp)) } }
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(499)
        composeRule.onAllNodesWithText("After first").assertCountEquals(0)
        composeRule.mainClock.advanceTimeBy(1)
        composeRule.mainClock.autoAdvance = true
        composeRule.waitUntil(5_000) { SearchableSettings.highlightKey == null }
        composeRule.onNodeWithText("After first").assertIsDisplayed()
        composeRule.onAllNodesWithText("After second").assertCountEquals(0)
    }

    @Test
    fun preferenceScreenClearsMissingHighlight() {
        SearchableSettings.highlightKey = "Missing"
        val item = Preference.PreferenceItem.TextPreference("Present")
        composeRule.setContent { TachiyomiPreviewTheme { PreferenceScreen(listOf(item)) } }
        composeRule.waitUntil(1_000) { SearchableSettings.highlightKey == null }
    }

    @Test
    fun singlePaneSearchEntryPushesSearchScreen() = assertNavigation(false, 2)

    @Test
    fun twoPaneSearchEntryReplacesStackWithSearchScreen() = assertNavigation(true, 1)
    private fun assertNavigation(twoPane: Boolean, expectedSize: Int) {
        lateinit var navigator: Navigator
        composeRule.setContent {
            TachiyomiPreviewTheme {
                CompositionLocalProvider(LocalBackPress provides {}) {
                    Navigator(SettingsAppearanceScreen) {
                        navigator = it
                        SettingsMainScreen.Content(twoPane)
                    }
                }
            }
        }
        composeRule.onNodeWithContentDescription("Search").performClick()
        composeRule.runOnIdle {
            assertEquals(expectedSize, navigator.items.size)
            assertEquals(SettingsSearchScreen::class, navigator.lastItem::class)
        }
    }
}
