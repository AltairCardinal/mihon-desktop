package eu.kanade.presentation.browse

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.tachiyomi.ui.browse.extension.ExtensionsScreenModel
import mihon.domain.extension.model.ExtensionArtifact
import mihon.domain.extension.model.ExtensionSourceDescriptor
import mihon.domain.extension.model.RepositoryIdentity
import mihon.domain.extension.suggestion.ExtensionSuggestion
import mihon.domain.extension.suggestion.SuggestedSource
import mihon.domain.extension.suggestion.SuggestionIdentity
import mihon.domain.extension.suggestion.SuggestionPanelRow
import mihon.domain.extension.suggestion.SuggestionPanelState
import mihon.domain.extension.suggestion.SuggestionProblem
import mihon.domain.extension.suggestion.UnmatchedLibrarySource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w900dp-h1000dp-mdpi")
class ExtensionSuggestionLayoutTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun `wide suggestion and unmatched actions use right side of full rows`() {
        show {
            ExtensionSuggestionSection(panel(), null, {}, {}, {}, {}, {}, {})
        }.use {
            val title = compose.onNodeWithText("Reader 1").fetchSemanticsNode().boundsInRoot
            val install = compose.onNodeWithText("Install").fetchSemanticsNode().boundsInRoot
            val missing = compose.onNodeWithText("Missing source", substring = true).fetchSemanticsNode().boundsInRoot
            val migrate = compose.onNodeWithText("Migrate").fetchSemanticsNode().boundsInRoot
            assertEquals(16f, title.left, 1f)
            assertEquals(title.left, missing.left, 1f)
            assertTrue("Wide install must be right of information: $title / $install", install.left > title.right)
            assertTrue("Wide actions must use right half", install.left > 450)
            assertTrue(
                "Unmatched actions must share the row",
                migrate.left > missing.right && migrate.top < missing.bottom + 80,
            )
        }
    }

    @Test
    @Config(qualifiers = "w360dp-h1000dp-mdpi")
    fun `narrow actions wrap and still install the exact suggestion`() {
        var clicked: SuggestionIdentity? = null
        val state = panel()
        show { ExtensionSuggestionSection(state, null, { clicked = it }, {}, {}, {}, {}, {}) }.use {
            val title = compose.onNodeWithText("Reader 1").fetchSemanticsNode().boundsInRoot
            val action = compose.onNodeWithText("Install").fetchSemanticsNode().boundsInRoot
            assertEquals(16f, title.left, 1f)
            assertTrue(action.right <= 344f)
            assertTrue(action.top > title.bottom)
            val trailing = compose.onNodeWithText("Ignore").fetchSemanticsNode().boundsInRoot
            assertTrue("Wrapped actions remain right aligned: $trailing", trailing.right > 315)
            compose.onNodeWithText("Install").performClick()
            assertEquals(state.rows.single().suggestion.identity, clicked)
        }
    }

    @Test fun `blank missing source name displays its stable source ID`() {
        val state = panel().let { it.copy(unmatched = it.unmatched.map { missing -> missing.copy(name = " ") }) }
        show { ExtensionSuggestionSection(state, null, {}, {}, {}, {}, {}, {}) }.use {
            compose.onNodeWithText("999", substring = true).assertIsDisplayed()
        }
    }

    @Test fun `real page shares one lazy scroll for suggestions and missing rows`() {
        val state = ExtensionsScreenModel.State(isLoading = false, suggestionPanel = panel(40))
        val screen = object : Screen {
            override val key = "suggestion-layout-page"

            @Composable override fun Content() {
                ExtensionScreen(
                    state, PaddingValues(), null,
                    onLongClickItem = {}, onClickItemCancel = {}, onOpenWebView = {},
                    onInstallExtension = {}, onUninstallExtension = {}, onUpdateExtension = {},
                    onTrustExtension = {}, onOpenExtension = {}, onClickUpdateAll = {}, onRefresh = {},
                )
            }
        }
        show { Navigator(screen) { screen.Content() } }.use {
            val scrolls = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            assertEquals("Only the page owns vertical scrolling", 1, scrolls.fetchSemanticsNodes().size)
            compose.onNodeWithText("Reader 40").assertDoesNotExist()
            scrolls[0].performScrollToNode(hasText("Missing source", substring = true))
            compose.onNodeWithText("Missing source", substring = true).assertIsDisplayed()
            compose.onNodeWithText("Reader 1").assertDoesNotExist()
        }
    }

    private fun show(content: @Composable () -> Unit): ActivityController<ComponentActivity> {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        activity.get().setContent { MaterialTheme { content() } }
        return activity
    }
    private fun panel(count: Int = 1) = SuggestionPanelState(
        loading = false,
        total = count,
        rows = (1..count).map { index ->
            val source = ExtensionSourceDescriptor(index.toLong(), "en", "Source $index", "https://source.example")
            val artifact = ExtensionArtifact(
                "Reader $index", "pkg.$index", "1.6.1", 1, "en", false, listOf(source),
                RepositoryIdentity("https://repo.example", "Repository", "key"),
                "https://repo.example/$index.apk", "", null,
            )
            SuggestionPanelRow(
                ExtensionSuggestion(
                    SuggestionIdentity.of(artifact),
                    artifact,
                    listOf(SuggestedSource(source, 3)),
                    false,
                ),
                canInstall = true,
                canIgnore = true,
                websites = listOf(source),
            )
        },
        unmatched = listOf(UnmatchedLibrarySource(999, 2, SuggestionProblem.NOT_IN_CATALOG, "Missing source")),
    )
}
