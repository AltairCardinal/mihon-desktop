package eu.kanade.presentation.reader.settings

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsScreenModel
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.ui.reader.viewer.pager.DualPageR2LPagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.pager.R2LPagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonViewer
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import mihon.domain.reader.ReaderSettingsCodec
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import java.io.File
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SharedSpreadAndroidSettingsWiringTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `default automatic explanation uses secondary text color and standard horizontal padding`() {
        val previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val shared = activity.get().getSharedPreferences("default-reading-${System.nanoTime()}", 0)
        val preferences = ReaderPreferences(AndroidPreferenceStore(activity.get(), shared))
        preferences.defaultReadingMode().set(ReadingMode.AUTO.flagValue)
        val state = MutableStateFlow(ReaderViewModel.State(viewer = mockk<DualPageR2LPagerViewer>(relaxed = true)))
        val model = ReaderSettingsScreenModel(state, {}, {}, preferences)
        val summary = MR.strings.desktop_reader_default_description.getString(activity.get())
        try {
            activity.get().setContent {
                MaterialTheme(
                    colorScheme = lightColorScheme(onSurface = Color.Red, onSurfaceVariant = Color.Green),
                ) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        ReadingModePage(model)
                    }
                }
            }
            assertStyledDefaultExplanation(activity.get(), summary, "default-summary.png")
            compose.onNodeWithText("Auto · Double page").assertIsDisplayed()
        } finally {
            activity.pause().stop().destroy()
            shared.edit().clear().commit()
            Locale.setDefault(previousLocale)
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `fixed global default explanation uses secondary text color and standard horizontal padding`() {
        val previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val shared = activity.get().getSharedPreferences("fixed-reading-${System.nanoTime()}", 0)
        val preferences = ReaderPreferences(AndroidPreferenceStore(activity.get(), shared))
        preferences.defaultReadingMode().set(ReadingMode.LEFT_TO_RIGHT.flagValue)
        val state = MutableStateFlow(ReaderViewModel.State(viewer = mockk<DualPageR2LPagerViewer>(relaxed = true)))
        val model = ReaderSettingsScreenModel(state, {}, {}, preferences)
        val fixedMode = MR.strings.desktop_reader_default_description.getString(activity.get())
        val automaticSummary = MR.strings.desktop_reader_default_summary.getString(activity.get())
        try {
            activity.get().setContent {
                MaterialTheme(
                    colorScheme = lightColorScheme(onSurface = Color.Red, onSurfaceVariant = Color.Green),
                ) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        ReadingModePage(model)
                    }
                }
            }

            assertStyledDefaultExplanation(activity.get(), fixedMode, "fixed-default-mode.png")
            compose.onNodeWithText("Single page").assertIsDisplayed()
            compose.onNodeWithText(automaticSummary).assertDoesNotExist()
        } finally {
            activity.pause().stop().destroy()
            shared.edit().clear().commit()
            Locale.setDefault(previousLocale)
        }
    }

    @Test
    fun `grouped matching writes series snapshot before rotation and is hidden for single and vertical layouts`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val shared = activity.get().getSharedPreferences("spread-settings-${System.nanoTime()}", 0)
        val store = AndroidPreferenceStore(activity.get(), shared)
        val preferences = ReaderPreferences(store)
        val preference = store.getBoolean("reader_auto_spread_matching", false)
        val state = MutableStateFlow(
            ReaderViewModel.State(
                manga = Manga.create().copy(viewerFlags = 6L),
                viewer = mockk<DualPageR2LPagerViewer>(relaxed = true),
            ),
        )
        val model = ReaderSettingsScreenModel(state, {}, {}, preferences, { settings, _ ->
            state.value = state.value.copy(
                manga = state.value.manga!!.copy(
                    viewerFlags = ReaderSettingsCodec.setCustom(
                        state.value.manga!!.viewerFlags,
                        requireNotNull(settings),
                    ),
                ),
            )
        })
        try {
            assertFalse(preference.get())
            activity.get().setContent {
                MaterialTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        ReadingModePage(model)
                    }
                }
            }
            compose.onNodeWithText(MR.strings.pager_viewer.getString(activity.get())).assertDoesNotExist()
            compose.onNodeWithText("Auto Spread Matching").performScrollTo().assertIsDisplayed().performClick()
            assertFalse("A series override must preserve the global default", preference.get())
            compose.waitForIdle()
            assertTrue(ReaderSettingsCodec.custom(state.value.manga!!.viewerFlags).match)
            val matchingY = compose.onNodeWithText("Auto Spread Matching").fetchSemanticsNode().positionInRoot.y
            val rotationY = compose.onNodeWithText("Rotation").fetchSemanticsNode().positionInRoot.y
            assertTrue(
                "Dual-page matching belongs beside reading mode, before rotation: $matchingY / $rotationY",
                matchingY < rotationY,
            )
            compose.onNodeWithText("Auto Spread Matching").performClick()
            assertFalse(preference.get())
            state.value = state.value.copy(
                manga = state.value.manga!!.copy(viewerFlags = 2L),
                viewer = mockk<R2LPagerViewer>(relaxed = true),
            )
            compose.waitForIdle()
            compose.onNodeWithText("Auto Spread Matching").assertDoesNotExist()
            state.value = state.value.copy(
                manga = state.value.manga!!.copy(viewerFlags = 4L),
                viewer = mockk<WebtoonViewer>(relaxed = true),
            )
            compose.waitForIdle()
            compose.onNodeWithText("Auto Spread Matching").assertDoesNotExist()
        } finally {
            activity.pause().stop().destroy()
            shared.edit().clear().commit()
        }
    }

    private fun assertStyledDefaultExplanation(activity: ComponentActivity, text: String, screenshotName: String) {
        compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()
        val bounds = compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot
        val expectedPadding = 16f * activity.resources.displayMetrics.density
        assertTrue(
            "Default explanation should have the standard horizontal inset: $bounds",
            bounds.left >= expectedPadding - 1f,
        )

        val rootView = activity.findViewById<View>(android.R.id.content)
        assertTrue(
            "The composed settings view must be laid out before capture",
            rootView.width > 0 && rootView.height > 0,
        )
        val image = Bitmap.createBitmap(rootView.width, rootView.height, Bitmap.Config.ARGB_8888)
        try {
            activity.runOnUiThread { rootView.draw(Canvas(image)) }
            shadowOf(Looper.getMainLooper()).idle()
            val screenshot = File("build/reader-settings-restoration/$screenshotName")
            screenshot.parentFile?.mkdirs()
            assertTrue(
                "The real settings surface should be retained for visual review",
                screenshot.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) },
            )
            val top = bounds.top.toInt().coerceIn(0, image.height - 1)
            val bottom = bounds.bottom.toInt().coerceIn(top, image.height - 1)
            val hasVariantColor = (0 until image.width).any { x ->
                (top..bottom).any { y ->
                    val pixel = image.getPixel(x, y)
                    android.graphics.Color.green(pixel) > 191 &&
                        android.graphics.Color.red(pixel) < 64 &&
                        android.graphics.Color.blue(pixel) < 64
                }
            }
            assertTrue("Default explanation must render with onSurfaceVariant", hasVariantColor)
        } finally {
            image.recycle()
        }
    }
}
