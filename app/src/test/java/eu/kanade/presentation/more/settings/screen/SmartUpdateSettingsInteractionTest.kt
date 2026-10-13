package eu.kanade.presentation.more.settings.screen

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.presentation.more.settings.PreferenceScreen
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.registry.default.DefaultRegistrar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SmartUpdateSettingsInteractionTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun `real settings entry keeps nested overrides as draft and restores hidden selection`() {
        val previous = Injekt
        Injekt = InjektScope(DefaultRegistrar())
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            val store =
                AndroidPreferenceStore(
                    activity.get(),
                    activity.get().getSharedPreferences("smart-draft", Context.MODE_PRIVATE),
                )
            val preferences = LibraryPreferences(store)
            Injekt.addSingleton(preferences)
            Injekt.addSingleton(mockk<GetCategories> { every { subscribe() } returns flowOf(emptyList()) })
            val key = LibraryPreferences.MANGA_HAS_UNREAD
            val override = store.getStringSet("library_update_manual_restriction_overrides", emptySet())
            val smart = MR.strings.pref_library_update_smart_update.getString(activity.get())
            val ok = MR.strings.action_ok.getString(activity.get())
            val cancel = MR.strings.action_cancel.getString(activity.get())
            activity.get().setContent {
                MaterialTheme {
                    Navigator(SettingsLibraryScreen) {
                        PreferenceScreen(SettingsLibraryScreen.getPreferences(), Modifier.testTag("library-settings"))
                    }
                }
            }
            compose.onNodeWithTag("library-settings").performScrollToNode(hasText(smart))
            compose.onNodeWithText(smart).performClick()
            compose.onNodeWithTag("smart-manual-$key").performScrollTo().performClick()
            compose.runOnIdle { assertEquals(emptySet<String>(), override.get()) }
            compose.onNodeWithText(cancel).performClick()
            compose.runOnIdle { assertEquals(emptySet<String>(), override.get()) }
            compose.onNodeWithTag("library-settings").performScrollToNode(hasText(smart))
            compose.onNodeWithText(smart).performClick()
            compose.onNodeWithTag("smart-manual-$key").performScrollTo().performClick()
            compose.onNodeWithTag("smart-rule-$key").performClick()
            compose.onNodeWithTag("smart-manual-$key").assertDoesNotExist()
            compose.onNodeWithTag("smart-rule-$key").performClick()
            compose.onNodeWithTag("smart-manual-$key").assertIsOn()
            compose.onNodeWithText(ok).performClick()
            compose.runOnIdle { assertEquals(setOf(key), override.get()) }
            assertEquals(
                setOf(key),
                LibraryPreferences(
                    AndroidPreferenceStore(
                        activity.get(),
                        activity.get().getSharedPreferences("smart-draft", Context.MODE_PRIVATE),
                    ),
                ).manualUpdateMangaRestrictionOverrides().get(),
            )
        } finally {
            activity.pause().stop().destroy()
            Injekt = previous
        }
    }
}
