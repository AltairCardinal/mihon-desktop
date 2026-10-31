package eu.kanade.presentation.reader.settings

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsScreenModel
import eu.kanade.tachiyomi.ui.reader.viewer.pager.DualPageR2LPagerViewer
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tachiyomi.core.common.preference.AndroidPreferenceStore

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SharedSpreadAndroidSettingsWiringTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    @Config(qualifiers = "zh-rCN")
    fun `reading mode chips expose localized dual directions and dispatch the selected mode`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val shared = activity.get().getSharedPreferences("dual-direction-${System.nanoTime()}", 0)
        val preferences = ReaderPreferences(AndroidPreferenceStore(activity.get(), shared))
        val state = MutableStateFlow(ReaderViewModel.State())
        var selected: Int? = null
        val model = ReaderSettingsScreenModel(state, { selected = it.flagValue }, {}, preferences)
        try {
            activity.get().setContent {
                MaterialTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        ReadingModePage(model)
                    }
                }
            }
            compose.onNodeWithText("双页式（从右到左）").performScrollTo().assertIsDisplayed().performClick()
            assertEquals(6, selected)
            compose.onNodeWithText("双页式（从左到右）").performScrollTo().assertIsDisplayed().performClick()
            assertEquals(8, selected)
        } finally {
            activity.pause().stop().destroy()
            shared.edit().clear().commit()
        }
    }

    @Test
    fun `existing reading mode page setting event persists automatic matching only for dual viewer`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val shared = activity.get().getSharedPreferences("spread-settings-${System.nanoTime()}", 0)
        val store = AndroidPreferenceStore(activity.get(), shared)
        val preferences = ReaderPreferences(store)
        val preference = store.getBoolean("reader_auto_spread_matching", false)
        val state = MutableStateFlow(ReaderViewModel.State(viewer = mockk<DualPageR2LPagerViewer>(relaxed = true)))
        val model = ReaderSettingsScreenModel(state, {}, {}, preferences)
        try {
            assertFalse(preference.get())
            activity.get().setContent {
                MaterialTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        ReadingModePage(model)
                    }
                }
            }
            compose.onNodeWithText("Auto Spread Matching").performScrollTo().assertIsDisplayed().performClick()
            assertTrue("The real checkbox must persist the shared semantic setting", preference.get())
            compose.onNodeWithText("Auto Spread Matching").performClick()
            assertFalse(preference.get())
            state.value = state.value.copy(viewer = null)
            compose.onNodeWithText("Auto Spread Matching").assertDoesNotExist()
        } finally {
            activity.pause().stop().destroy()
            shared.edit().clear().commit()
        }
    }

    @Test
    @Config(qualifiers = "zh-rCN")
    fun `auto spread matching appears directly under reading mode without paged header for dual viewer`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val shared = activity.get().getSharedPreferences("auto-spread-pos-${System.nanoTime()}", 0)
        val store = AndroidPreferenceStore(activity.get(), shared)
        val preferences = ReaderPreferences(store)
        val state = MutableStateFlow(ReaderViewModel.State(viewer = mockk<DualPageR2LPagerViewer>(relaxed = true)))
        val model = ReaderSettingsScreenModel(state, {}, {}, preferences)
        try {
            activity.get().setContent {
                MaterialTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        ReadingModePage(model)
                    }
                }
            }
            val spreadNode = compose.onNodeWithText("自动跨页匹配").assertExists()
            val rotationNode = compose.onNodeWithText("屏幕方向").assertExists()

            val spreadBounds = spreadNode.getUnclippedBoundsInRoot()
            val rotationBounds = rotationNode.getUnclippedBoundsInRoot()
            assertTrue(
                "自动跨页匹配应当直接出现在阅读模式下方、位于屏幕方向之前",
                spreadBounds.top < rotationBounds.top,
            )

            compose.onNodeWithText("单页式").assertDoesNotExist()
        } finally {
            activity.pause().stop().destroy()
            shared.edit().clear().commit()
        }
    }

    @Test
    @Config(qualifiers = "zh-rCN")
    fun `default reading mode summary text has horizontal padding and is not flushed left`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val shared = activity.get().getSharedPreferences("default-padding-${System.nanoTime()}", 0)
        val store = AndroidPreferenceStore(activity.get(), shared)
        val preferences = ReaderPreferences(store)
        val state = MutableStateFlow(ReaderViewModel.State())
        val model = ReaderSettingsScreenModel(state, {}, {}, preferences)
        try {
            activity.get().setContent {
                MaterialTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        ReadingModePage(model)
                    }
                }
            }
            val defaultSummaryNode = compose.onNode(
                androidx.compose.ui.test.hasText("默认 ·", substring = true),
            ).assertExists()

            val bounds = defaultSummaryNode.getUnclippedBoundsInRoot()
            assertTrue(
                "默认模式说明文本应当留有左边距（不贴着最左边）",
                bounds.left > 0.dp,
            )
        } finally {
            activity.pause().stop().destroy()
            shared.edit().clear().commit()
        }
    }
}
