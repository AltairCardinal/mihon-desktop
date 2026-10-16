package eu.kanade.presentation.more.settings.screen.about

import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.BuildConfig
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.release.service.ApplicationUpdatePreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.registry.default.DefaultRegistrar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "en")
class AboutFeedbackWiringTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun `About update switches change shared preferences and survive activity recreation`() {
        val previous = Injekt
        Injekt = InjektScope(DefaultRegistrar())
        val preferences = ApplicationUpdatePreferences(InMemoryPreferenceStore())
        Injekt.addSingleton(UiPreferences(InMemoryPreferenceStore()))
        Injekt.addSingleton(preferences)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        fun mount() {
            activity.get().setContent { MaterialTheme { Navigator(AboutScreen) { CurrentScreen() } } }
        }
        try {
            mount()
            compose.onNodeWithText("Check for app updates automatically").performScrollTo().performClick()
            compose.onNodeWithText("Include testing releases").performScrollTo().performClick()
            compose.runOnIdle {
                assertFalse(preferences.enabled().get())
                assertFalse(preferences.testingChannel().get())
            }
            activity.recreate()
            mount()
            compose.onNodeWithText("Check for app updates automatically").performScrollTo().performClick()
            compose.onNodeWithText("Include testing releases").performScrollTo().performClick()
            compose.runOnIdle {
                assertTrue(preferences.enabled().get())
                assertTrue(preferences.testingChannel().get())
            }
        } finally {
            activity.pause().stop().destroy()
            Injekt = previous
        }
    }

    @Test
    fun `real About previews safe summary and only opens project issue after explicit action`() {
        val previous = Injekt
        Injekt = InjektScope(DefaultRegistrar())
        Injekt.addSingleton(UiPreferences(InMemoryPreferenceStore()))
        Injekt.addSingleton(ApplicationUpdatePreferences(InMemoryPreferenceStore()))
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val copied = mutableListOf<String>()
        val opened = mutableListOf<String>()
        var copyAllowed = false
        var openAllowed = false
        val clipboard = mockk<ClipboardManager> {
            every { setText(any()) } answers {
                check(copyAllowed) { "Private clipboard implementation detail" }
                copied += firstArg<AnnotatedString>().text
            }
        }
        val uri = object : UriHandler {
            override fun openUri(uri: String) {
                check(openAllowed) { "Private browser implementation detail" }
                opened += uri
            }
        }
        try {
            activity.get().setContent {
                CompositionLocalProvider(LocalClipboardManager provides clipboard, LocalUriHandler provides uri) {
                    MaterialTheme { Navigator(AboutScreen) { CurrentScreen() } }
                }
            }
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Report a problem"))
            compose.onNodeWithText("Report a problem").performClick()
            compose.runOnIdle {
                assertTrue(opened.isEmpty())
                assertTrue(copied.isEmpty())
            }
            compose.onNodeWithText("Copy safe summary").performScrollTo().performClick()
            compose.onNodeWithText("Could not copy the summary. Try again.").performScrollTo().assertIsDisplayed()
            compose.runOnIdle {
                assertTrue(copied.isEmpty())
                copyAllowed = true
            }
            compose.onNodeWithText("Copy safe summary").performScrollTo().performClick()
            compose.runOnIdle {
                assertTrue(BuildConfig.VERSION_NAME in copied.single())
                assertFalse("Installation ID" in copied.single())
                assertFalse(activity.get().filesDir.absolutePath in copied.single())
            }
            compose.onNodeWithText(copied.single()).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Safe summary copied").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Open feedback form").performClick()
            compose.onNodeWithText(
                "Could not open the feedback form. Try again or open the project feedback page in your browser.",
            )
                .performScrollTo().assertIsDisplayed()
            compose.runOnIdle {
                assertTrue(opened.isEmpty())
                openAllowed = true
            }
            compose.onNodeWithText("Open feedback form").performClick()
            compose.runOnIdle {
                assertEquals(
                    listOf("https://altaircardinal.github.io/mihon-desktop/feedback/"),
                    opened,
                )
            }
            compose.onNodeWithText("Close").performClick()
            compose.onNodeWithText("Copy safe summary").assertDoesNotExist()
        } finally {
            activity.pause().stop().destroy()
            Injekt = previous
        }
    }

    @Test
    @Config(qualifiers = "en-w320dp-h900dp")
    fun `feedback preview scrolls at 320dp double font in both themes and Back or close has no external effect`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val copied = mutableListOf<String>()
        val opened = mutableListOf<String>()
        val clipboard = mockk<ClipboardManager> {
            every { setText(any()) } answers { copied += firstArg<AnnotatedString>().text }
        }
        val uri = object : UriHandler {
            override fun openUri(uri: String) {
                opened += uri
            }
        }
        try {
            for (dark in listOf(false, true)) {
                activity.get().setContent {
                    CompositionLocalProvider(
                        LocalClipboardManager provides clipboard,
                        LocalUriHandler provides uri,
                        LocalDensity provides Density(LocalDensity.current.density, 2f),
                    ) {
                        MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                            AboutFeedbackPreference()
                        }
                    }
                }
                compose.onNodeWithText("Report a problem").performClick()
                compose.onNodeWithText("Application: Mihon", substring = true)
                    .performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("Copy safe summary").performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("Open feedback form").assertIsDisplayed()
                compose.onNodeWithText("Close").assertIsDisplayed()
                compose.runOnUiThread {
                    (ShadowDialog.getLatestDialog() as ComponentDialog).onBackPressedDispatcher.onBackPressed()
                }
                compose.onNodeWithText("Copy safe summary").assertDoesNotExist()
                compose.onNodeWithText("Report a problem").performClick()
                compose.onNodeWithText("Close").performClick()
                compose.onNodeWithText("Copy safe summary").assertDoesNotExist()
                compose.runOnIdle {
                    assertTrue(copied.isEmpty())
                    assertTrue(opened.isEmpty())
                }
            }
        } finally {
            activity.pause().stop().destroy()
        }
    }
}
