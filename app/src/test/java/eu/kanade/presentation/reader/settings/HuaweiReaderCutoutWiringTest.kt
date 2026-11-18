package eu.kanade.presentation.reader.settings

import android.app.Application
import android.graphics.Point
import android.graphics.Rect
import android.os.Looper
import android.view.DisplayCutout
import android.view.Surface
import android.view.WindowInsets
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
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.screen.SettingsReaderScreen
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.databinding.ReaderActivityBinding
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsScreenModel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild
import org.robolectric.util.ReflectionHelpers
import tachiyomi.core.common.preference.AndroidPreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.registry.default.DefaultRegistrar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w2880dp-h1920dp-land-mdpi")
class HuaweiReaderCutoutWiringTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Before
    fun setUp() {
        ShadowBuild.setManufacturer("HUAWEI")
        setHuaweiProperty("240,34,1340,0")
    }

    @Test
    @Config(qualifiers = "zh-rCN-w1920dp-h2880dp-port-mdpi")
    fun `reader settings exposes the Huawei switch and persists the real preference`() {
        val host = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = host.get()
        val preferences = readerPreferences(activity)
        val model = ReaderSettingsScreenModel(MutableStateFlow(ReaderViewModel.State()), {}, {}, preferences)
        try {
            activity.setContent {
                MaterialTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) { GeneralPage(model) }
                }
            }
            val option = compose.onNodeWithText("在屏幕刘海区域显示内容")
            assertTrue(preferences.drawUnderCutout().get())
            option.performScrollTo().performClick()
            assertFalse(preferences.drawUnderCutout().get())
            compose.runOnIdle { preferences.fullscreen().set(false) }
            option.assertDoesNotExist()
        } finally {
            host.pause().stop().destroy()
        }
    }

    @Test
    @Config(qualifiers = "zh-rCN-w1920dp-h2880dp-port-mdpi")
    fun `global reader settings enables the same preference on Huawei`() {
        val host = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val preferences = readerPreferences(host.get())
        val previousInjekt = Injekt
        Injekt = InjektScope(DefaultRegistrar())
        Injekt.addSingleton(preferences)
        var rendered: List<Preference> = emptyList()
        try {
            host.get().setContent { MaterialTheme { rendered = SettingsReaderScreen.getPreferences() } }
            compose.runOnIdle {
                val option = rendered.filterIsInstance<Preference.PreferenceGroup>()
                    .flatMap { it.preferenceItems }
                    .filterIsInstance<Preference.PreferenceItem.SwitchPreference>()
                    .single { it.title == "在屏幕刘海区域显示内容" }
                assertTrue(option.enabled)
                option.preference.set(false)
                assertFalse(preferences.drawUnderCutout().get())
            }
        } finally {
            host.pause().stop().destroy()
            Injekt = previousInjekt
        }
    }

    @Test
    @Suppress("DEPRECATION")
    fun `viewer uses Huawei padding only when the fullscreen option is off`() {
        val reader = readerWithViewer()
        val view = reader.binding.viewerContainer
        shadowOf(reader.windowManager.defaultDisplay).setRotation(Surface.ROTATION_90)
        updateViewerInset(reader, fullscreen = true, drawUnderCutout = false)
        assertEquals(35, view.paddingTop)
        assertEquals(0, view.paddingBottom)

        updateViewerInset(reader, fullscreen = true, drawUnderCutout = true)
        assertEquals(0, view.paddingTop)

        updateViewerInset(reader, fullscreen = false, drawUnderCutout = false)
        assertEquals(0, view.paddingTop)
    }

    @Test
    @Config(qualifiers = "zh-rCN-w1920dp-h2880dp-port-mdpi")
    @Suppress("DEPRECATION")
    fun `portrait and reverse portrait use opposite side edges`() {
        val reader = readerWithViewer()
        val view = reader.binding.viewerContainer
        val readerDisplay = reader.windowManager.defaultDisplay
        val display = shadowOf(readerDisplay)
        val preferences = ReflectionHelpers.getField<ReaderPreferences>(reader, "readerPreferences")

        display.setRealWidth(1920)
        display.setRealHeight(2880)
        display.setRotation(Surface.ROTATION_0)
        assertEquals(Point(1920, 2880), Point().also(readerDisplay::getRealSize))
        preferences.fullscreen().set(true)
        preferences.drawUnderCutout().set(false)
        setHuaweiCutoutUpdates(reader, enabled = true)
        try {
            assertEquals(35, view.paddingRight)
            assertEquals(0, view.paddingLeft)
            assertEquals(0, view.paddingTop)
            assertEquals(0, view.paddingBottom)

            display.setRotation(Surface.ROTATION_180)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(35, view.paddingLeft)
            assertEquals(0, view.paddingRight)
            assertEquals(0, view.paddingTop)
            assertEquals(0, view.paddingBottom)

            preferences.drawUnderCutout().set(true)
            updateViewerInset(reader, fullscreen = true, drawUnderCutout = true)
            assertEquals(0, view.paddingLeft)
        } finally {
            setHuaweiCutoutUpdates(reader, enabled = false)
        }

        preferences.drawUnderCutout().set(false)
        display.setRotation(Surface.ROTATION_0)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, view.paddingRight)
    }

    @Test
    @Suppress("DEPRECATION")
    fun `natural landscape scales the added physical pixel before rounding`() {
        val reader = readerWithViewer()
        val view = reader.binding.viewerContainer
        val display = shadowOf(reader.windowManager.defaultDisplay)

        display.setRealWidth(1800)
        display.setRealHeight(1200)
        display.setRotation(Surface.ROTATION_0)
        updateViewerInset(reader, fullscreen = true, drawUnderCutout = false)
        assertEquals(22, view.paddingTop)
        assertEquals(0, view.paddingLeft)

        display.setRealWidth(1200)
        display.setRealHeight(1800)
        display.setRotation(Surface.ROTATION_90)
        updateViewerInset(reader, fullscreen = true, drawUnderCutout = false)
        assertEquals(22, view.paddingLeft)
        assertEquals(0, view.paddingTop)
    }

    @Test
    @Suppress("DEPRECATION")
    fun `reverse landscape uses the other edge and platform cutout takes priority`() {
        val reader = readerWithViewer()
        val view = reader.binding.viewerContainer
        shadowOf(reader.windowManager.defaultDisplay).setRotation(Surface.ROTATION_270)
        updateViewerInset(reader, fullscreen = true, drawUnderCutout = false)
        assertEquals(0, view.paddingTop)
        assertEquals(35, view.paddingBottom)

        val cutout = DisplayCutout(Rect(0, 0, 28, 0), listOf(Rect(2852, 900, 2880, 1000)))
        val insets = WindowInsets.Builder()
            .setDisplayCutout(cutout)
            .setInsets(WindowInsets.Type.displayCutout(), android.graphics.Insets.of(0, 0, 28, 0))
            .setVisible(WindowInsets.Type.displayCutout(), true)
            .build()
        view.dispatchApplyWindowInsets(insets)
        assertEquals(28, view.paddingRight)
        assertEquals(0, view.paddingBottom)
    }

    @Test
    fun `malformed properties and other manufacturers do not add padding`() {
        val reader = readerWithViewer()
        val view = reader.binding.viewerContainer
        setHuaweiProperty("240,34,2880,0")
        updateViewerInset(reader, fullscreen = true, drawUnderCutout = false)
        assertEquals(0, view.paddingTop)

        setHuaweiProperty("240,34,1340,0")
        ShadowBuild.setManufacturer("Other")
        updateViewerInset(reader, fullscreen = true, drawUnderCutout = false)
        assertEquals(0, view.paddingTop)
    }

    @Test
    @Suppress("DEPRECATION")
    fun `missing Huawei property and multi window skip the fallback`() {
        val reader = readerWithViewer()
        val view = reader.binding.viewerContainer
        shadowOf(reader.windowManager.defaultDisplay).setRotation(Surface.ROTATION_90)

        setHuaweiProperty("")
        updateViewerInset(reader, fullscreen = true, drawUnderCutout = false)
        assertEquals(0, view.paddingTop)

        setHuaweiProperty("240,34,1340,0")
        updateViewerInset(reader, fullscreen = true, drawUnderCutout = false)
        assertEquals(35, view.paddingTop)

        shadowOf(reader).setInMultiWindowMode(true)
        updateViewerInset(reader, fullscreen = true, drawUnderCutout = false)
        assertEquals(0, view.paddingTop)
    }

    private fun readerWithViewer(): ReaderActivity {
        val previousInjekt = Injekt
        Injekt = InjektScope(DefaultRegistrar())
        val application = RuntimeEnvironment.getApplication()
        val shared = application.getSharedPreferences("huawei-cutout-viewer", 0)
        val store = AndroidPreferenceStore(application, shared)
        Injekt.addSingleton(ReaderPreferences(store))
        Injekt.addSingleton(BasePreferences(application, store))
        Injekt.addSingleton(UiPreferences(store))
        Injekt.addSingleton(SecurityPreferences(store))
        Injekt.addSingleton(application)
        try {
            return Robolectric.buildActivity(ReaderActivity::class.java).get().also {
                it.setTheme(R.style.Theme_Tachiyomi)
                it.binding = ReaderActivityBinding.inflate(it.layoutInflater)
            }
        } finally {
            Injekt = previousInjekt
        }
    }

    private fun readerPreferences(activity: ComponentActivity): ReaderPreferences {
        val shared = activity.getSharedPreferences("huawei-cutout-$TEST_PROPERTY", 0)
        return ReaderPreferences(AndroidPreferenceStore(activity, shared))
    }

    private fun setHuaweiProperty(value: String) {
        Class.forName("android.os.SystemProperties").getMethod("set", String::class.java, String::class.java)
            .invoke(null, TEST_PROPERTY, value)
    }

    private fun updateViewerInset(reader: ReaderActivity, fullscreen: Boolean, drawUnderCutout: Boolean) {
        ReflectionHelpers.callInstanceMethod<Unit>(
            reader,
            "updateViewerInset",
            ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType!!, fullscreen),
            ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType!!, drawUnderCutout),
        )
    }

    private fun setHuaweiCutoutUpdates(reader: ReaderActivity, enabled: Boolean) {
        ReflectionHelpers.callInstanceMethod<Unit>(
            reader,
            "setHuaweiCutoutUpdates",
            ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType!!, enabled),
        )
    }

    private companion object {
        const val TEST_PROPERTY = "hw_mc.pad.landscape.notch_area"
    }
}
