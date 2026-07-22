package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@RunWith(AndroidJUnit4::class)
class SettingsSearchNavigationUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @After
    fun tearDown() = run { SearchableSettings.highlightKey = null }

    @Test
    fun searchResultCallerUsesSharedResultAndSetsAnchorBeforeReplacingRoute() {
        val destination = SettingsSearchScreen()
        val screen = SearchableSettingsScreen<Screen>(
            route = destination,
            title = "Appearance",
            preferences = listOf(SearchablePreference.Entry("Duplicate")),
        )
        var replaced: Screen? = null

        composeRule.setContent {
            TachiyomiPreviewTheme {
                SearchResult(listOf(screen), searchKey = "duplicate") { route ->
                    assertEquals("Duplicate", SearchableSettings.highlightKey)
                    replaced = route
                }
            }
        }
        composeRule.onNodeWithText("Duplicate").performClick()

        composeRule.runOnIdle { assertSame(destination, replaced) }
    }

    @Test
    fun preferenceScreenCallerConsumesHighlightOnce() {
        SearchableSettings.highlightKey = "Duplicate"

        composeRule.setContent {
            TachiyomiPreviewTheme {
                PreferenceScreen(listOf(Preference.PreferenceItem.TextPreference("Duplicate")))
            }
        }

        composeRule.waitUntil(5_000) { SearchableSettings.highlightKey == null }
    }

    @Test
    fun singlePaneSearchEntryPushesSearchScreen() {
        val navigator = showSettingsMain(twoPane = false)

        clickSearch()

        composeRule.runOnIdle {
            assertEquals(2, navigator.items.size)
            assertEquals(SettingsSearchScreen::class, navigator.lastItem::class)
        }
    }

    @Test
    fun twoPaneSearchEntryReplacesStackWithSearchScreen() {
        val navigator = showSettingsMain(twoPane = true)

        clickSearch()

        composeRule.runOnIdle {
            assertEquals(1, navigator.items.size)
            assertEquals(SettingsSearchScreen::class, navigator.lastItem::class)
        }
    }

    private lateinit var searchLabel: String

    private fun showSettingsMain(twoPane: Boolean): Navigator {
        lateinit var navigator: Navigator
        composeRule.setContent {
            TachiyomiPreviewTheme {
                searchLabel = stringResource(MR.strings.action_search)
                CompositionLocalProvider(LocalBackPress provides {}) {
                    Navigator(SettingsAppearanceScreen) {
                        navigator = it
                        SettingsMainScreen.Content(twoPane)
                    }
                }
            }
        }
        composeRule.waitForIdle()
        return navigator
    }

    private fun clickSearch() = composeRule.onNodeWithContentDescription(searchLabel).performClick()
}
