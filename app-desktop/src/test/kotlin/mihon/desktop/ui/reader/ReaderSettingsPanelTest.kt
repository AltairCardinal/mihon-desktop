package mihon.desktop.ui.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import kotlinx.coroutines.Dispatchers
import mihon.desktop.reader.ReaderBackgroundTheme
import mihon.desktop.reader.ReaderColorFilter
import mihon.desktop.reader.ReadingMode
import mihon.desktop.reader.ScaleType
import mihon.desktop.reader.WebtoonSidePadding
import mihon.desktop.reader.ZoomState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.i18n.MR
import java.util.Locale

@OptIn(ExperimentalComposeUiApi::class)
class ReaderSettingsPanelTest {
    @Test
    fun `general reader settings keep other controls and remove zoom quick controls`() {
        val previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
        val scene = ImageComposeScene(900, 1400, coroutineContext = Dispatchers.Unconfined) {}
        var autoSplitPages = false
        var autoSpreadMatching = false
        var changedTheme: ReaderBackgroundTheme? = null
        var zoomChanges = 0
        try {
            scene.setContent {
                MaterialTheme {
                    Column {
                        GeneralTab(
                            currentMode = ReadingMode.LTR,
                            isDualPage = true,
                            autoSplitPages = autoSplitPages,
                            isAutoSpreadMatching = autoSpreadMatching,
                            backgroundTheme = ReaderBackgroundTheme.AUTOMATIC,
                            navigationMode = NavigationMode.RightAndLeft,
                            skipReadChapters = false,
                            skipFilteredChapters = false,
                            skipDuplicateChapters = false,
                            zoomState = ZoomState(scale = 1.5f),
                            onModeChange = {},
                            onDualPageChange = {},
                            onAutoSplitPagesChange = { autoSplitPages = it },
                            onAutoSpreadMatchingChange = { autoSpreadMatching = it },
                            onBackgroundThemeChange = { changedTheme = it },
                            onNavigationModeChange = {},
                            onZoomChange = { zoomChanges++ },
                        )
                    }
                }
            }
            scene.render().close()

            val currentText = nodes(scene).flatMap(::text)
            val descriptions = nodes(scene).flatMap(::contentDescriptions)
            assertFalse(currentText.any { it == MR.strings.desktop_ui_zoom.localized() })
            assertFalse(currentText.any { it == "×1.5" })
            assertFalse(
                descriptions.any {
                    it in setOf(
                        MR.strings.desktop_ui_zoom_in.localized(),
                        MR.strings.desktop_ui_zoom_out.localized(),
                        MR.strings.desktop_ui_reset_zoom.localized(),
                    )
                },
            )

            assertFalse(currentText.contains(MR.strings.pref_dual_page_split.localized()))
            click(scene, MR.strings.desktop_ui_auto_spread_matching.localized())
            assertTrue(autoSpreadMatching)
            assertFalse(currentText.contains(MR.strings.pref_reader_theme.localized()))
            assertEquals(0, zoomChanges, "The panel exposes no zoom action")
        } finally {
            scene.close()
            Locale.setDefault(previousLocale)
        }
    }

    @Test
    fun `display settings show scale mode in simplified and traditional Chinese`() {
        val previousLocale = Locale.getDefault()
        try {
            listOf(
                Locale.forLanguageTag("zh-CN") to "缩放模式",
                Locale.forLanguageTag("zh-TW") to "縮放模式",
            ).forEach { (locale, expectedLabel) ->
                Locale.setDefault(locale)
                val scene = ImageComposeScene(900, 1400, coroutineContext = Dispatchers.Unconfined) {}
                try {
                    scene.setContent {
                        MaterialTheme {
                            Column {
                                ReaderSettingsPanel(
                                    currentMode = ReadingMode.LTR,
                                    isDualPage = true,
                                    autoSplitPages = false,
                                    isAutoSpreadMatching = false,
                                    backgroundTheme = ReaderBackgroundTheme.AUTOMATIC,
                                    navigationMode = NavigationMode.RightAndLeft,
                                    cropBordersPager = false,
                                    cropBordersWebtoon = false,
                                    webtoonSidePadding = WebtoonSidePadding.NONE,
                                    webtoonAutoScroll = false,
                                    webtoonAutoScrollSpeed = WebtoonAutoScrollSpeed.Normal,
                                    colorFilter = ReaderColorFilter(),
                                    scaleType = ScaleType.FIT_SCREEN,
                                    zoomState = ZoomState(),
                                    onModeChange = {},
                                    onDualPageChange = {},
                                    onAutoSplitPagesChange = {},
                                    onAutoSpreadMatchingChange = {},
                                    onBackgroundThemeChange = {},
                                    onNavigationModeChange = {},
                                    onCropBordersPagerChange = {},
                                    onCropBordersWebtoonChange = {},
                                    onWebtoonSidePaddingChange = {},
                                    onWebtoonAutoScrollChange = {},
                                    onWebtoonAutoScrollSpeedChange = {},
                                    onColorFilterChange = {},
                                    onScaleTypeChange = {},
                                    onZoomChange = {},
                                    onDismiss = {},
                                )
                            }
                        }
                    }
                    scene.render().close()
                    assertTrue(
                        nodes(scene).flatMap(::text).any { it == expectedLabel },
                        "Expected '$expectedLabel' for $locale",
                    )
                } finally {
                    scene.close()
                }
            }
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    private fun click(scene: ImageComposeScene, label: String) {
        val node = nodes(scene).first {
            it.config.contains(SemanticsActions.OnClick) && flatten(it).any { child -> label in text(child) }
        }
        requireNotNull(node.config[SemanticsActions.OnClick].action).invoke()
        scene.render().close()
    }

    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> =
        scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }

    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)

    private fun text(node: SemanticsNode): List<String> = if (node.config.contains(SemanticsProperties.Text)) {
        node.config[SemanticsProperties.Text].map { it.text }
    } else {
        emptyList()
    }

    private fun contentDescriptions(node: SemanticsNode): List<String> =
        if (node.config.contains(SemanticsProperties.ContentDescription)) {
            node.config[SemanticsProperties.ContentDescription]
        } else {
            emptyList()
        }
}
