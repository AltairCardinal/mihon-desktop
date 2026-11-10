package mihon.desktop.ui.reader

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import kotlinx.coroutines.Dispatchers
import mihon.desktop.reader.ReaderPreferences
import mihon.desktop.reader.ReadingMode
import mihon.desktop.reader.ScaleType
import mihon.desktop.reader.WebtoonSidePadding
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.i18n.MR
import java.io.File
import java.util.Locale
import java.util.prefs.Preferences

@OptIn(ExperimentalComposeUiApi::class)
class ReaderSettingsChoiceLayoutTest {
    private val frameTimes = java.util.IdentityHashMap<ImageComposeScene, Long>()

    @Test
    fun `reading mode direction and navigation choices flow horizontally and stay exclusive`() = withPanel(
        locale = Locale.SIMPLIFIED_CHINESE,
    ) {
            scene,
            model,
            _,
        ->
        assertHorizontal(
            scene,
            MR.strings.reader_settings_follow_default.localized(),
            MR.strings.reader_settings_custom.localized(),
        )
        capture(scene, "default")
        click(scene, MR.strings.reader_settings_custom.localized())
        assertHorizontal(
            scene,
            MR.strings.desktop_reader_direction_ltr.localized(),
            MR.strings.desktop_reader_direction_rtl.localized(),
        )
        assertHorizontal(scene, MR.strings.right_and_left_nav.localized(), MR.strings.l_nav.localized())
        capture(scene, "reading-mode")

        click(scene, MR.strings.desktop_reader_direction_rtl.localized())
        assertEquals(ReadingMode.RTL, model.state.value.readingMode)
        assertSelected(scene, MR.strings.desktop_reader_direction_rtl.localized(), true)
        assertSelected(scene, MR.strings.desktop_reader_direction_ltr.localized(), false)
        click(scene, MR.strings.reader_settings_horizontal.localized())
        assertEquals(ReadingMode.RTL, model.state.value.readingMode, "Paged mode retains the selected direction")
        click(scene, MR.strings.l_nav.localized())
        assertSelected(scene, MR.strings.l_nav.localized(), true)
        assertSelected(scene, MR.strings.right_and_left_nav.localized(), false)

        click(scene, MR.strings.reader_settings_auto.localized())
        val direction = option(scene, MR.strings.desktop_reader_direction_ltr.localized())
        assertFalse(direction.config.contains(SemanticsProperties.Disabled))
        click(scene, MR.strings.desktop_reader_direction_ltr.localized())
        assertTrue(model.state.value.automaticLayout, "Choosing direction must keep automatic layout")
        assertEquals(ReadingMode.LTR, model.state.value.readingMode)
        click(scene, MR.strings.reader_settings_follow_default.localized())
        assertTrue(model.state.value.followsGlobalReadingMode)
        assertSelected(scene, MR.strings.reader_settings_follow_default.localized(), true)
        assertSelected(scene, MR.strings.reader_settings_custom.localized(), false)
        click(scene, MR.strings.reader_settings_custom.localized())
        assertTrue(model.state.value.automaticLayout)
        assertEquals(ReadingMode.LTR, model.state.value.readingMode)
    }

    @Test
    fun `vertical scrolling hides gaps for paging and preserves the hidden gap choice`() = withPanel(
        locale = Locale.SIMPLIFIED_CHINESE,
    ) {
            scene,
            model,
            _,
        ->
        click(scene, MR.strings.reader_settings_custom.localized())
        click(scene, MR.strings.reader_settings_vertical.localized())
        val gapSwitch = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }.first {
            it.config.getOrNull(
                SemanticsProperties.ContentDescription,
            )?.contains(MR.strings.reader_settings_page_gaps.localized()) ==
                true &&
                it.config.contains(SemanticsActions.OnClick)
        }
        val position = gapSwitch.boundsInRoot.center
        scene.sendPointerEvent(PointerEventType.Press, position)
        scene.sendPointerEvent(PointerEventType.Release, position)
        render(scene)
        assertTrue(model.state.value.readerSettings.gap)
        capture(scene, "vertical")
        click(scene, MR.strings.reader_settings_paged.localized())
        assertEquals(ReadingMode.VERTICAL, model.state.value.readingMode)
        assertTrue(model.state.value.readerSettings.gap)
        assertFalse(
            scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }.any {
                it.config.getOrNull(
                    SemanticsProperties.ContentDescription,
                )?.contains(MR.strings.reader_settings_page_gaps.localized()) ==
                    true
            },
        )
        click(scene, MR.strings.reader_settings_continuous.localized())
        assertEquals(ReadingMode.WEBTOON, model.state.value.readingMode)
        assertTrue(model.state.value.readerSettings.gap)
    }

    @Test
    fun `display choices flow horizontally and pointer clicks persist through reopening`() = withPanel {
            scene,
            model,
            prefs,
        ->
        click(scene, MR.strings.pref_category_general.localized())
        assertHorizontal(
            scene,
            MR.strings.scale_type_fit_screen.localized(),
            MR.strings.scale_type_fit_width.localized(),
        )
        assertHorizontal(scene, MR.strings.none.localized(), "10%")
        assertHorizontal(scene, MR.strings.desktop_ui_slowest.localized(), MR.strings.desktop_ui_slow.localized())

        click(scene, MR.strings.scale_type_fit_width.localized())
        click(scene, "10%")
        click(scene, MR.strings.desktop_ui_fast.localized())
        assertSelected(scene, MR.strings.scale_type_fit_width.localized(), true)
        assertSelected(scene, MR.strings.scale_type_fit_screen.localized(), false)
        assertSelected(scene, "10%", true)
        assertSelected(scene, MR.strings.none.localized(), false)
        assertSelected(scene, MR.strings.desktop_ui_fast.localized(), true)
        assertSelected(scene, MR.strings.desktop_ui_normal.localized(), false)
        assertEquals(ScaleType.FIT_WIDTH, prefs.scaleType)
        assertEquals(WebtoonSidePadding.SMALL, prefs.webtoonSidePadding)
        assertEquals(WebtoonAutoScrollSpeed.Fast, prefs.webtoonAutoScrollSpeed)
        capture(scene, "display")

        click(scene, MR.strings.action_close.localized())
        assertFalse(model.state.value.showSettings)
        model.toggleSettings()
        render(scene)
        click(scene, MR.strings.pref_category_general.localized())
        assertSelected(scene, MR.strings.scale_type_fit_width.localized(), true)
        assertSelected(scene, "10%", true)
        assertSelected(scene, MR.strings.desktop_ui_fast.localized(), true)
        val reopened = ReaderScreenModel(prefs = prefs)
        try {
            assertEquals(ScaleType.FIT_WIDTH, reopened.state.value.scaleType)
            assertEquals(WebtoonSidePadding.SMALL, reopened.state.value.webtoonSidePadding)
            assertEquals(WebtoonAutoScrollSpeed.Fast, reopened.state.value.webtoonAutoScrollSpeed)
        } finally {
            reopened.onDispose()
        }
    }

    @Test
    fun `narrow Chinese panel wraps choices within the dialog`() = withPanel(
        width = 360,
        locale = Locale.SIMPLIFIED_CHINESE,
    ) {
            scene,
            _,
            _,
        ->
        click(scene, MR.strings.pref_category_general.localized())
        val labels = listOf(
            MR.strings.scale_type_fit_screen,
            MR.strings.scale_type_fit_width,
            MR.strings.scale_type_fit_height,
            MR.strings.scale_type_original_size,
            MR.strings.scale_type_smart_fit,
        ).map { it.localized() }
        val bounds = labels.map { option(scene, it).boundsInRoot }
        assertTrue(bounds.map { it.top }.distinct().size > 1, "Choices wrap rather than overflow")
        assertTrue(bounds.all { it.left >= 0 && it.right <= 360 })
        assertTrue(bounds.zipWithNext().any { (a, b) -> a.top == b.top && a.right <= b.left })
        click(scene, labels.last())
        assertSelected(scene, labels.last(), true)
        assertSelected(scene, labels.first(), false)
        capture(scene, "narrow-chinese")
    }

    private fun withPanel(
        width: Int = 900,
        locale: Locale = Locale.US,
        test: (ImageComposeScene, ReaderScreenModel, ReaderPreferences) -> Unit,
    ) {
        val previousLocale = Locale.getDefault()
        Locale.setDefault(locale)
        val legacy = Preferences.userRoot().node("/mihon/test/reader-choice-${System.nanoTime()}")
        val prefs = ReaderPreferences(InMemoryPreferenceStore(), legacy).apply {
            readingMode = ReadingMode.LTR
            webtoonAutoScroll = true
        }
        val model = ReaderScreenModel(prefs = prefs)
        val scene = ImageComposeScene(width, 1600, coroutineContext = Dispatchers.Unconfined) {}
        try {
            model.toggleSettings()
            scene.setContent {
                MaterialTheme {
                    val state by model.state.collectAsState()
                    if (state.showSettings) {
                        ReaderSettingsPanel(
                            currentMode = if (state.automaticLayout) ReadingMode.AUTO else state.readingMode,
                            readerSettings = state.readerSettings,
                            onReaderSettingsChange = model::setReaderSettings,
                            onCustomize = model::restoreCustomReadingMode,
                            followsGlobal = state.followsGlobalReadingMode,
                            onFollowGlobal = { model.followGlobalReadingMode(prefs) },
                            isDualPage = state.dualPageMode,
                            autoSplitPages = state.autoSplitPages,
                            isAutoSpreadMatching = state.autoSpreadMatching,
                            backgroundTheme = state.backgroundTheme,
                            navigationMode = state.navigationMode,
                            cropBordersPager = state.cropBordersPager,
                            cropBordersWebtoon = state.cropBordersWebtoon,
                            webtoonSidePadding = state.webtoonSidePadding,
                            webtoonAutoScroll = state.webtoonAutoScroll,
                            webtoonAutoScrollSpeed = state.webtoonAutoScrollSpeed,
                            colorFilter = state.colorFilter,
                            scaleType = state.scaleType,
                            zoomState = state.zoomState,
                            onModeChange = { model.setReadingMode(it, prefs) },
                            onDualPageChange = { model.setDualPageMode(it, prefs) },
                            onAutoSplitPagesChange = { model.setAutoSplitPages(it, prefs) },
                            onAutoSpreadMatchingChange = { model.setAutoSpreadMatching(it, prefs) },
                            onBackgroundThemeChange = { model.setBackgroundTheme(it, prefs) },
                            onNavigationModeChange = { model.setNavigationMode(it, prefs) },
                            onCropBordersPagerChange = { model.setCropBordersPager(it, prefs) },
                            onCropBordersWebtoonChange = { model.setCropBordersWebtoon(it, prefs) },
                            onWebtoonSidePaddingChange = { model.setWebtoonSidePadding(it, prefs) },
                            onWebtoonAutoScrollChange = { model.setWebtoonAutoScroll(it, prefs) },
                            onWebtoonAutoScrollSpeedChange = { model.setWebtoonAutoScrollSpeed(it, prefs) },
                            onColorFilterChange = { model.setColorFilter(it, prefs) },
                            onScaleTypeChange = { model.setScaleType(it, prefs) },
                            onZoomChange = { model.setZoomState(it) },
                            onDismiss = model::closeSettings,
                        )
                    }
                }
            }
            render(scene)
            test(scene, model, prefs)
        } finally {
            scene.close()
            frameTimes.remove(scene)
            model.onDispose()
            legacy.removeNode()
            Locale.setDefault(previousLocale)
        }
    }

    private fun assertHorizontal(scene: ImageComposeScene, first: String, second: String) {
        val a = option(scene, first).boundsInRoot
        val b = option(scene, second).boundsInRoot
        assertEquals(a.top, b.top, "Choices '$first' and '$second' must share a row")
        assertTrue(a.right <= b.left, "Choices must be separate horizontal buttons")
    }

    private fun assertSelected(scene: ImageComposeScene, label: String, selected: Boolean) {
        assertEquals(selected, option(scene, label).config[SemanticsProperties.Selected], label)
        assertEquals(Role.RadioButton, option(scene, label).config[SemanticsProperties.Role], label)
    }

    private fun capture(scene: ImageComposeScene, name: String) {
        val directory = File("../.gradle-coordinator/reader-settings-native").apply { mkdirs() }
        repeat(2) { frame(scene, 1_000_000_000L).close() }
        frame(scene).use { image ->
            requireNotNull(image.encodeToData()).use { File(directory, "$name.png").writeBytes(it.bytes) }
        }
    }

    private fun frame(scene: ImageComposeScene, stepNanos: Long = 16_000_000L): org.jetbrains.skia.Image {
        val time = (frameTimes[scene] ?: 0L) + stepNanos
        frameTimes[scene] = time
        return scene.render(time)
    }

    private fun render(scene: ImageComposeScene) = frame(scene).close()

    private fun click(scene: ImageComposeScene, label: String) {
        val position = option(scene, label).boundsInRoot.center
        scene.sendPointerEvent(PointerEventType.Press, position)
        scene.sendPointerEvent(PointerEventType.Release, position)
        render(scene)
    }

    private fun option(scene: ImageComposeScene, label: String): SemanticsNode = scene.semanticsOwners
        .flatMap { flatten(it.rootSemanticsNode) }
        .first { node ->
            node.config.contains(SemanticsActions.OnClick) && flatten(node).any {
                it.config.contains(SemanticsProperties.Text) &&
                    it.config[SemanticsProperties.Text].any { text -> text.text == label }
            }
        }

    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
}
