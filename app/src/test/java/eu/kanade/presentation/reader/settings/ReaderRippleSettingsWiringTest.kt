package eu.kanade.presentation.reader.settings

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.screen.SettingsReaderScreen
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsScreenModel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tachiyomi.core.common.preference.AndroidPreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.registry.default.DefaultRegistrar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "zh-rCN")
class ReaderRippleSettingsWiringTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun `enabled ripple exposes classic and scheme A and persists the real selected preference`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val shared = activity.get().getSharedPreferences("ripple-options-${System.nanoTime()}", 0)
        val preferences = ReaderPreferences(AndroidPreferenceStore(activity.get(), shared))
        preferences.pageTurnRipple().set(false)
        val model = ReaderSettingsScreenModel(MutableStateFlow(ReaderViewModel.State()), {}, {}, preferences)
        try {
            activity.get().setContent {
                MaterialTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) { GeneralPage(model) }
                }
            }
            compose.onNodeWithText("方案 A（平滑）").assertDoesNotExist()
            compose.onNodeWithText("翻页时用水波纹").performScrollTo().performClick()
            compose.onNodeWithText("方案 A（平滑）").performScrollTo().performClick()
            assertEquals(ReaderPreferences.PageTurnRippleMode.SMOOTH, preferences.pageTurnRippleMode().get())
            compose.onNodeWithText("经典（分步）").performScrollTo().performClick()
            assertEquals(ReaderPreferences.PageTurnRippleMode.SHADOW_LINE, preferences.pageTurnRippleMode().get())
        } finally {
            activity.pause().stop().destroy()
            shared.edit().clear().commit()
        }
    }

    @Test
    fun `global settings list ripple schemes only after enabling the real preference`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val shared = activity.get().getSharedPreferences("global-ripple-${System.nanoTime()}", 0)
        val preferences = ReaderPreferences(AndroidPreferenceStore(activity.get(), shared))
        preferences.pageTurnRipple().set(false)
        val previousInjekt = Injekt
        Injekt = InjektScope(DefaultRegistrar())
        Injekt.addSingleton(preferences)
        var rendered: List<Preference> = emptyList()
        fun schemePreference(): Preference.PreferenceItem.ListPreference<*>? = rendered
            .filterIsInstance<Preference.PreferenceGroup>()
            .flatMap { it.preferenceItems }
            .filterIsInstance<Preference.PreferenceItem.ListPreference<*>>()
            .singleOrNull { it.entries.containsKey(ReaderPreferences.PageTurnRippleMode.SMOOTH) }
        try {
            activity.get().setContent { MaterialTheme { rendered = SettingsReaderScreen.getPreferences() } }
            compose.runOnIdle {
                assertNull("Disabled ripple must not list scheme controls", schemePreference())
                preferences.pageTurnRipple().set(true)
            }
            compose.runOnIdle {
                val scheme = requireNotNull(schemePreference())
                assertTrue(scheme.entries.containsKey(ReaderPreferences.PageTurnRippleMode.CLEAN))
                scheme.internalSet(ReaderPreferences.PageTurnRippleMode.SMOOTH)
                assertEquals(ReaderPreferences.PageTurnRippleMode.SMOOTH, preferences.pageTurnRippleMode().get())
            }
        } finally {
            activity.pause().stop().destroy()
            Injekt = previousInjekt
            shared.edit().clear().commit()
        }
    }
}
