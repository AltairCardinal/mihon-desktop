package eu.kanade.presentation.browse

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import mihon.domain.error.AppError
import mihon.domain.extension.model.ExtensionArtifact
import mihon.domain.extension.model.RepositoryIdentity
import mihon.domain.extension.suggestion.SuggestionBatchItem
import mihon.domain.extension.suggestion.SuggestionBatchPause
import mihon.domain.extension.suggestion.SuggestionBatchResult
import mihon.domain.extension.suggestion.SuggestionBatchState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExtensionSuggestionBatchRenderedTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun `matching install opens explicit confirmation and rejected snapshot stays reviewable`() {
        val artifact = artifact("Reader")
        val dialog = mutableStateOf<ExtensionSuggestionBatchConfirmation?>(null)
        val submitted = mutableListOf<List<ExtensionArtifact>>()
        var accept = false
        withContent({
            ExtensionSuggestionBatchSection(
                SuggestionBatchState(),
                true,
                true,
                onRequestStart = { dialog.value = ExtensionSuggestionBatchConfirmation(listOf(artifact)) },
                onRequestResume = {},
                onRequestRetry = {},
                onStop = {},
            )
            dialog.value?.let {
                ExtensionSuggestionBatchDialog(
                    it,
                    onConfirm = { snapshot ->
                        submitted += snapshot
                        accept
                    },
                    onSelectReplacement = {},
                    onDismiss = { dialog.value = null },
                )
            }
        }) {
            compose.onNodeWithText("Install matching").performClick()
            assertEquals(0, submitted.size)
            compose.onNodeWithText("Confirm installations").assertIsDisplayed()
            compose.onNodeWithText(artifact.repository.baseUrl).assertExists()
            compose.onNodeWithText(artifact.repository.signingKeyFingerprint).assertExists()
            compose.onNodeWithText("Install selected (1)").performClick()
            assertEquals(listOf(listOf(artifact)), submitted)
            compose.onNodeWithText("Confirm installations").assertIsDisplayed()
            compose.onNodeWithText(
                "The list changed. Refresh extensions or explicitly select a replacement repository, then review again.",

            ).assertExists()
            compose.runOnIdle { accept = true }
            compose.onNodeWithText("Install selected (1)").performClick()
            compose.onNodeWithText("Confirm installations").assertDoesNotExist()
            assertEquals(listOf(artifact), submitted.last())
        }
    }

    @Test
    fun `unavailable repository requires explicit replacement and conflicts block confirmation`() {
        val original = artifact("Reader")
        val replacement = original.copy(
            repository = RepositoryIdentity(
                "https://replacement.example",
                "Replacement",
                "new-key",
            ),
        )
        val dialog = mutableStateOf(
            ExtensionSuggestionBatchConfirmation(
                listOf(original),
                setOf(original.packageName),
                mapOf(original.packageName to listOf(replacement)),
            ),
        )
        val submitted = mutableListOf<List<ExtensionArtifact>>()
        var dismissed = false
        withContent({
            ExtensionSuggestionBatchDialog(
                dialog.value,
                onConfirm = {
                    submitted += it
                    true
                },
                onSelectReplacement = { selected ->
                    dialog.value = ExtensionSuggestionBatchConfirmation(listOf(selected), hasSourceConflict = true)
                },
                onDismiss = { dismissed = true },
            )
        }) {
            compose.onNodeWithText("Install selected (1)").assertIsNotEnabled()
            compose.onNodeWithText("Use this repository").performClick()
            compose.onNodeWithText(replacement.repository.baseUrl).assertExists()
            compose.onNodeWithText("Install selected (1)").assertIsNotEnabled()
            compose.onNodeWithText(
                "Selected extensions provide the same source. Choose one provider before continuing.",
            ).assertExists()
            assertFalse(dismissed)
            assertEquals(0, submitted.size)
            compose.runOnIdle { dialog.value = dialog.value.copy(hasSourceConflict = false) }
            compose.onNodeWithText("Install selected (1)").performClick()
            assertEquals(listOf(listOf(replacement)), submitted)
        }
    }

    @Test
    fun `paused results remain visible without suggestions and final cancellation has no continue`() {
        val first = artifact("First")
        val second = artifact("Second")
        val batch = mutableStateOf(
            SuggestionBatchState(
                id = 1,
                items = listOf(
                    SuggestionBatchItem(first, 1, result = SuggestionBatchResult.Cancelled),
                    SuggestionBatchItem(
                        second,
                        2,
                        result = SuggestionBatchResult.Paused(SuggestionBatchPause.PERMISSION),
                    ),

                ),
                pauseReason = SuggestionBatchPause.PERMISSION,
            ),
        )
        var reviews = 0
        var stops = 0
        var settings = 0
        withContent({
            ExtensionSuggestionBatchSection(
                batch.value, false, false,
                onRequestStart = {}, onRequestResume = { reviews++ }, onRequestRetry = {}, onStop = { stops++ },
                pauseExplanation = "Installation permission is required.",
                onResolvePause = { settings++ },
            )
        }) {
            compose.onNodeWithText("First · 1.6.1").assertIsDisplayed()
            compose.onNodeWithText("Cancelled").assertIsDisplayed()
            compose.onNodeWithText("Installation permission is required.").assertIsDisplayed()
            compose.onNodeWithText("Settings").performClick()
            compose.onNodeWithText("Review remaining").performClick()
            compose.onNodeWithText("Stop remaining").performClick()
            assertEquals(listOf(1, 1, 1), listOf(reviews, stops, settings))
            compose.runOnIdle {
                batch.value = batch.value.copy(items = batch.value.items.take(1), pauseReason = null)
            }
            compose.onNodeWithText("Review remaining").assertDoesNotExist()
            compose.onNodeWithText("Stop remaining").assertDoesNotExist()
            compose.onNodeWithText("First · 1.6.1").assertIsDisplayed()
        }
    }

    @Test
    fun `failure retry is separate from stop and stopping disables duplicate stop`() {
        val batch = mutableStateOf(
            SuggestionBatchState(
                id = 1,
                items = listOf(
                    SuggestionBatchItem(
                        artifact("Reader"),
                        1,
                        result = SuggestionBatchResult.Failed(AppError.Network()),
                    ),
                ),

            ),
        )
        var retries = 0
        withContent({
            ExtensionSuggestionBatchSection(
                batch.value,
                false,
                false,
                onRequestStart = {},
                onRequestResume = {},
                onRequestRetry = { retries++ },
                onStop = {},
            )
        }) {
            compose.onNodeWithText("Retry failed").performClick()
            assertEquals(1, retries)
            compose.onNodeWithText("Stop remaining").assertDoesNotExist()
            compose.runOnIdle {
                batch.value = batch.value.copy(running = true, stopping = true)
            }
            compose.onNodeWithText("Retry failed").assertDoesNotExist()
            compose.onNodeWithText("Stop remaining").assertIsNotEnabled()
        }
    }

    private fun withContent(content: @Composable () -> Unit, assertions: () -> Unit) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { MaterialTheme { content() } }
            assertions()
        } finally {
            activity.close()
        }
    }

    private fun artifact(name: String) = ExtensionArtifact(
        name, "pkg.${name.lowercase()}", "1.6.1", 1, "en", false, emptyList(),
        RepositoryIdentity("https://repo.example", "Repository", "signer-$name"),
        "https://repo.example/$name.apk", "", null,
    )
}
