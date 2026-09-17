package eu.kanade.presentation.browse

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.tab.Tab
import eu.kanade.tachiyomi.ui.browse.extension.ExtensionsScreenModel
import eu.kanade.tachiyomi.ui.browse.migration.manga.MigrateMangaScreen
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import kotlinx.coroutines.flow.MutableStateFlow
import mihon.domain.extension.model.ExtensionArtifact
import mihon.domain.extension.model.ExtensionSourceDescriptor
import mihon.domain.extension.model.RepositoryIdentity
import mihon.domain.extension.suggestion.ExtensionSuggestion
import mihon.domain.extension.suggestion.ExtensionSuggestionPanel
import mihon.domain.extension.suggestion.SuggestedSource
import mihon.domain.extension.suggestion.SuggestionBatchPause
import mihon.domain.extension.suggestion.SuggestionIdentity
import mihon.domain.extension.suggestion.SuggestionPanelRow
import mihon.domain.extension.suggestion.SuggestionPanelState
import mihon.domain.extension.suggestion.SuggestionProblem
import mihon.domain.extension.suggestion.UnmatchedLibrarySource
import org.junit.Assert
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExtensionSuggestionRenderedTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun `running batch exposes interrupted system service without inventing a terminal or resume action`() {
        val artifact = ExtensionArtifact(
            "Pending Reader", "pkg.pending", "1.6.1", 1, "en", false, emptyList(),
            RepositoryIdentity("https://repo.example", "Repo", "signer"), "https://repo.example/a.apk", "", null,
        )
        val states = MutableStateFlow(
            ExtensionsScreenModel.State(
                isLoading = false,
                installer = eu.kanade.domain.base.BasePreferences.ExtensionInstaller.SHIZUKU,
                pendingSystemPauses = mapOf(artifact.packageName to SuggestionBatchPause.SERVICE),
                suggestionBatch = mihon.domain.extension.suggestion.SuggestionBatchState(
                    id = 1,
                    running = true,
                    items = listOf(mihon.domain.extension.suggestion.SuggestionBatchItem(artifact, 1)),
                ),
            ),
        )
        val model = io.mockk.mockk<ExtensionsScreenModel>(relaxed = true)
        io.mockk.every { model.state } returns states
        val screen = object : Screen {
            override val key = "pending-system-wiring"

            @Composable override fun Content() {
                eu.kanade.tachiyomi.ui.browse.extension.extensionsTab(model).content(
                    PaddingValues(),
                    androidx.compose.runtime.remember {
                        SnackbarHostState()
                    },
                )
            }
        }
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { MaterialTheme { Navigator(screen) { screen.Content() } } }
            compose.onNodeWithText("Shizuku is not running").assertIsDisplayed()
            compose.onNodeWithText("Settings").performClick()
            val intent = org.robolectric.Shadows.shadowOf(activity.get()).nextStartedActivity
            Assert.assertEquals(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
            Assert.assertEquals("package:moe.shizuku.privileged.api", intent.dataString)
            compose.onNodeWithText("Review remaining").assertDoesNotExist()
            compose.onNodeWithText("Installed").assertDoesNotExist()
            states.value = states.value.copy(
                pendingSystemPauses = mapOf(
                    "pkg.unrelated" to
                        SuggestionBatchPause.SERVICE,
                ),
            )
            compose.onNodeWithText("Shizuku is not running").assertDoesNotExist()
        } finally {
            activity.close()
        }
    }

    @Test
    fun `real extension tab opens fixed batch confirmation and forwards the exact approved artifacts`() {
        val artifact = ExtensionArtifact(
            "Batch Reader", "pkg.batch", "1.6.1", 1, "en", false, emptyList(),
            RepositoryIdentity("https://repo.example", "Repo", "signer"), "https://repo.example/a.apk", "", null,
        )
        val states = MutableStateFlow(
            ExtensionsScreenModel.State(
                isLoading = false,
                searchQuery = "Batch",
                suggestionPanel = SuggestionPanelState(
                    loading = false,
                    total = 1,
                    rows = listOf(
                        SuggestionPanelRow(
                            ExtensionSuggestion(SuggestionIdentity.of(artifact), artifact, emptyList(), false),
                            canInstall = true,
                            canIgnore = true,
                            websites = emptyList(),
                        ),
                    ),
                ),
            ),
        )
        val model = io.mockk.mockk<ExtensionsScreenModel>(relaxed = true)
        io.mockk.every { model.state } returns states
        io.mockk.every { model.requestSuggestionBatch(any()) } answers {
            states.value = states.value.copy(batchConfirmation = ExtensionSuggestionBatchConfirmation(listOf(artifact)))
        }
        io.mockk.every { model.confirmSuggestionBatch(any()) } returns true
        io.mockk.every { model.dismissBatchReview() } answers {
            states.value = states.value.copy(batchConfirmation = null)
        }
        val screen = object : Screen {
            override val key = "batch-confirmation-wiring"

            @Composable override fun Content() {
                eu.kanade.tachiyomi.ui.browse.extension.extensionsTab(model).content(
                    PaddingValues(),
                    androidx.compose.runtime.remember { SnackbarHostState() },
                )
            }
        }
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { MaterialTheme { Navigator(screen) { screen.Content() } } }
            compose.onNodeWithText("Install matching").performClick()
            io.mockk.verify(exactly = 1) { model.requestSuggestionBatch(ExtensionsScreenModel.BatchReviewMode.START) }
            compose.onNodeWithText("Batch Reader · 1.6.1").assertIsDisplayed()
            compose.onNodeWithText("Install selected (1)").performClick()
            io.mockk.verify(exactly = 1) { model.confirmSuggestionBatch(listOf(artifact)) }
            io.mockk.verify(exactly = 1) { model.dismissBatchReview() }
        } finally {
            activity.close()
        }
    }

    @Test
    fun `real extension screen keeps completed batch visible after all suggestion rows disappear`() {
        val artifact = ExtensionArtifact(
            "Completed Reader", "pkg.completed", "1.6.1", 1, "en", false, emptyList(),
            RepositoryIdentity("https://repo.example", "Repo", "signer"), "https://repo.example/a.apk", "", null,
        )
        val state = ExtensionsScreenModel.State(
            isLoading = false,
            suggestionPanel = SuggestionPanelState(loading = false),
            suggestionBatch = mihon.domain.extension.suggestion.SuggestionBatchState(
                id = 1,
                items = listOf(
                    mihon.domain.extension.suggestion.SuggestionBatchItem(
                        artifact,
                        1,
                        result = mihon.domain.extension.suggestion.SuggestionBatchResult.Installed,
                    ),
                ),
            ),
        )
        val screen = object : Screen {
            override val key = "batch-result-wiring"

            @Composable override fun Content() {
                ExtensionScreen(
                    state, PaddingValues(), null,
                    onLongClickItem = {}, onClickItemCancel = {}, onOpenWebView = {},
                    onInstallExtension = {}, onUninstallExtension = {}, onUpdateExtension = {},
                    onTrustExtension = {}, onOpenExtension = {}, onClickUpdateAll = {}, onRefresh = {},
                )
            }
        }
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { MaterialTheme { Navigator(screen) { CurrentScreen() } } }
            compose.onNodeWithText("Completed Reader · 1.6.1").assertIsDisplayed()
            compose.onNodeWithText("Installed").assertIsDisplayed()
        } finally {
            activity.close()
        }
    }

    @Test
    fun `real Android extension page renders suggestions even when ordinary list is empty`() {
        val source = ExtensionSourceDescriptor(71, "ja", "My source", "https://source.example")
        val artifact = ExtensionArtifact(
            "Suggested Reader", "pkg.reader", "1.6.1", 1, "ja", false,
            listOf(source), RepositoryIdentity("https://repo.example", "Repository", "key"),
            "https://repo.example/a.apk", "", null,
        )
        val suggestion = ExtensionSuggestion(
            SuggestionIdentity.of(artifact),
            artifact,
            listOf(SuggestedSource(source, 3)),
            false,
        )
        val state = ExtensionsScreenModel.State(
            isLoading = false,
            suggestionPanel = SuggestionPanelState(
                loading = false,
                total = 1,
                rows = listOf(
                    SuggestionPanelRow(
                        suggestion,
                        canInstall = true,
                        canIgnore = true,
                        websites = listOf(source),
                    ),
                ),
            ),
        )
        val screen = object : Screen {
            override val key = "extension-suggestion-test"

            @Composable override fun Content() {
                ExtensionScreen(
                    state, PaddingValues(), null,
                    onLongClickItem = {}, onClickItemCancel = {}, onOpenWebView = {},
                    onInstallExtension = {}, onUninstallExtension = {}, onUpdateExtension = {},
                    onTrustExtension = {}, onOpenExtension = {}, onClickUpdateAll = {}, onRefresh = {},
                )
            }
        }
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { MaterialTheme { Navigator(screen) { CurrentScreen() } } }
            compose.onNodeWithText("Suggested Reader").assertIsDisplayed()
        } finally {
            activity.close()
        }
    }

    @Test
    fun `real extension tab sends suggestion websites and migration to nested navigator`() {
        val source = ExtensionSourceDescriptor(71, "ja", "My source", "https://source.example")
        val artifact = ExtensionArtifact(
            "Suggested Reader", "pkg.reader", "1.6.1", 1, "ja", false,
            listOf(source), RepositoryIdentity("https://repo.example", "Repository", "key"),
            "https://repo.example/a.apk", "", null,
        )
        val suggestion = ExtensionSuggestion(
            SuggestionIdentity.of(artifact),
            artifact,
            listOf(SuggestedSource(source, 3)),
            false,
        )
        val states = MutableStateFlow(
            ExtensionsScreenModel.State(
                isLoading = false,
                suggestionPanel = SuggestionPanelState(
                    loading = false,
                    total = 1,
                    rows = listOf(
                        SuggestionPanelRow(
                            suggestion,
                            canInstall = true,
                            canIgnore = true,
                            websites = listOf(source),
                        ),
                    ),
                    unmatched = listOf(
                        UnmatchedLibrarySource(
                            72,
                            2,
                            SuggestionProblem.NOT_IN_CATALOG,
                            "Absent source",
                        ),
                    ),
                ),
            ),
        )
        val model = io.mockk.mockk<ExtensionsScreenModel>(relaxed = true)
        io.mockk.every { model.state } returns states
        val panelController = io.mockk.mockk<ExtensionSuggestionPanel>(relaxed = true)
        io.mockk.every { model.suggestionPanel } returns panelController
        lateinit var outer: Navigator
        lateinit var inner: Navigator
        val screen = object : Screen {
            override val key = "suggestion-navigation"

            @Composable override fun Content() {
                eu.kanade.tachiyomi.ui.browse.extension.extensionsTab(model).content(
                    PaddingValues(),
                    androidx.compose.runtime.remember {
                        SnackbarHostState()
                    },
                )
            }
        }
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent {
                MaterialTheme {
                    Navigator(screen) { parent ->
                        outer = parent
                        Navigator(screen) { child ->
                            inner = child
                            screen.Content()
                        }
                    }
                }
            }
            compose.onNodeWithText("Open website").performClick()
            compose.runOnIdle {
                Assert.assertTrue(inner.lastItem is WebViewScreen)
                Assert.assertFalse(inner.lastItem is Tab)
                Assert.assertEquals(1, outer.items.size)
                // Verify the real destination's configuration, not a separately constructed WebView.
                val field = inner.lastItem.javaClass.getDeclaredField("sourceId").apply { isAccessible = true }
                Assert.assertNull(field.get(inner.lastItem))
                inner.pop()
            }
            val second = source.copy(id = 72, name = "Other site", baseUrl = "https://other.example")
            compose.runOnIdle {
                val panel = states.value.suggestionPanel
                states.value = states.value.copy(
                    suggestionPanel = panel.copy(
                        rows = panel.rows.map {
                            it.copy(websites = listOf(source, second))
                        },
                    ),
                )
            }
            compose.onNodeWithText("Open website").performClick()
            compose.onNodeWithText("Other site").performClick()
            compose.runOnIdle {
                val field = inner.lastItem.javaClass.getDeclaredField("url").apply { isAccessible = true }
                Assert.assertEquals(second.baseUrl, field.get(inner.lastItem))
                inner.pop()
                val panel = states.value.suggestionPanel
                states.value = states.value.copy(
                    suggestionPanel = panel.copy(
                        rows = panel.rows.map {
                            it.copy(websites = emptyList())
                        },
                    ),
                )
            }
            compose.onNodeWithText("Open website").assertIsNotEnabled()
            compose.onNodeWithText("Migrate").performScrollTo().performClick()
            compose.runOnIdle {
                Assert.assertTrue(inner.lastItem is MigrateMangaScreen)
                Assert.assertFalse(inner.lastItem is Tab)
                Assert.assertEquals(1, outer.items.size)
            }
            compose.runOnIdle {
                inner.pop()
                states.value = states.value.copy(
                    suggestionPanel = SuggestionPanelState(
                        loading = false,
                        unmatched = listOf(
                            UnmatchedLibrarySource(
                                71,
                                3,
                                SuggestionProblem.INVENTORY_UNKNOWN,
                                "My source",
                            ),
                        ),
                    ),
                )
            }
            compose.onNodeWithText("Review installed extensions").performScrollTo().performClick()
            compose.runOnIdle {
                io.mockk.verify(exactly = 1) { model.recheckInstalledInventory() }
                io.mockk.verify(exactly = 0) { panelController.toggle() }
            }
            Assert.assertNull(
                eu.kanade.tachiyomi.ui.browse.extension.suggestionWebsiteDestination(
                    source.copy(baseUrl = "javascript:alert(1)"),
                ),
            )
        } finally {
            activity.close()
        }
    }
}
