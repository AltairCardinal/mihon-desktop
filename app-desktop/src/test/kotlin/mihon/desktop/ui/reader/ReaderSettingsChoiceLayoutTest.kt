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
import androidx.compose.ui.state.ToggleableState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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
    fun `split pages is a plain checkbox in lower settings and retains its saved value`() = withPanel(
        locale = Locale.SIMPLIFIED_CHINESE,
    ) { scene, model, prefs ->
        val label = MR.strings.pref_dual_page_split.localized()
        val hint = "将宽页分切为两个单页显示，适合在竖屏阅读图源为单张跨页的漫画"
        fun hasText(value: String) = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }.any {
            it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == value } == true
        }
        assertTrue(hasText(label))
        assertFalse(hasText(hint))
        assertEquals(Role.Checkbox, option(scene, label).config[SemanticsProperties.Role])
        assertEquals(ToggleableState.Off, option(scene, label).config[SemanticsProperties.ToggleableState])
        click(scene, label)
        assertTrue(model.state.value.autoSplitPages)
        assertTrue(prefs.autoSplitPages)
        assertEquals(ToggleableState.On, option(scene, label).config[SemanticsProperties.ToggleableState])
        capture(scene, "split-checkbox-lower")
        click(scene, MR.strings.reader_settings_custom.localized())
        for (mode in listOf(
            MR.strings.reader_settings_single,
            MR.strings.reader_settings_dual,
            MR.strings.reader_settings_auto,
            MR.strings.reader_settings_vertical,
        )) {
            click(scene, mode.localized())
            assertEquals(
                mode == MR.strings.reader_settings_single ||
                    (mode == MR.strings.reader_settings_auto && !model.state.value.dualPageMode),
                hasText(label),
            )
            assertFalse(hasText(hint))
            assertTrue(prefs.autoSplitPages)
        }
        click(scene, MR.strings.reader_settings_horizontal.localized())
        click(scene, MR.strings.reader_settings_single.localized())
        capture(scene, "split-checkbox-restored")
        click(scene, MR.strings.action_close.localized(), Role.Button)
        assertFalse(model.state.value.showSettings)
        model.toggleSettings()
        repeat(3) { render(scene) }
        assertEquals(ToggleableState.On, option(scene, label).config[SemanticsProperties.ToggleableState])
    }

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
        assertHorizontal(scene, MR.strings.l_nav.localized(), MR.strings.right_and_left_nav.localized())
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
    fun `reading extensions and general choices persist through real model reopening`() = withPanel {
            scene,
            model,
            prefs,
        ->
        click(scene, MR.strings.reader_settings_custom.localized())
        click(scene, MR.strings.scale_type_fit_width.localized())
        click(scene, MR.strings.zoom_start_right.localized())
        assertEquals(ScaleType.FIT_WIDTH, prefs.scaleType)
        assertEquals(mihon.desktop.reader.ReaderZoomStart.RIGHT, prefs.loadDisplayOptions().zoomStart)
        click(scene, MR.strings.pref_category_general.localized())
        click(scene, MR.strings.pref_show_page_number.localized())
        click(scene, MR.strings.pref_flash_page.localized())
        click(scene, MR.strings.pref_page_turn_ripple.localized())
        assertFalse(prefs.loadDisplayOptions().showPageNumber)
        assertTrue(prefs.loadDisplayOptions().flash && prefs.loadDisplayOptions().ripple)
        capture(scene, "general-options-enabled")
        click(scene, MR.strings.action_close.localized(), Role.Button)
        model.toggleSettings()
        render(scene)
        val reopened = ReaderScreenModel(prefs = prefs)
        try {
            assertEquals(model.state.value.displayOptions, reopened.state.value.displayOptions)
            assertEquals(ScaleType.FIT_WIDTH, reopened.state.value.scaleType)
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

    @Test
    fun `reading and general tabs expose Android sections instead of old desktop groups`() = withPanel(
        locale = Locale.SIMPLIFIED_CHINESE,
    ) { scene, _, _ ->
        fun texts() = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }.flatMap {
            it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { t -> t.text }
        }
        assertFalse(texts().contains(MR.strings.desktop_ui_background.localized()))
        assertFalse(texts().contains(MR.strings.desktop_ui_chapter_navigation.localized()))
        assertTrue(texts().contains(MR.strings.pref_read_with_tapping_inverted.localized()))
        assertTrue(texts().contains(MR.strings.pref_image_scale_type.localized()))
        assertTrue(texts().contains(MR.strings.pref_zoom_start.localized()))
        click(scene, MR.strings.pref_category_general.localized())
        assertTrue(texts().contains(MR.strings.pref_reader_theme.localized()))
        assertTrue(texts().contains(MR.strings.pref_show_page_number.localized()))
        assertTrue(texts().contains(MR.strings.pref_keep_screen_on.localized()))
        assertTrue(texts().contains(MR.strings.pref_read_with_long_tap.localized()))
        assertTrue(texts().contains(MR.strings.pref_always_show_chapter_transition.localized()))
        assertTrue(texts().contains(MR.strings.pref_page_transitions.localized()))
        assertTrue(texts().contains("E-Ink"))
        assertTrue(texts().contains(MR.strings.pref_flash_page.localized()))
        assertTrue(texts().contains(MR.strings.pref_page_turn_ripple.localized()))
        assertFalse(texts().contains(MR.strings.pref_image_scale_type.localized()))
        capture(scene, "general-parity")
    }

    @Test
    fun `continuous reader exposes its own tap zones padding slider and zoom checkboxes`() = withPanel {
            scene,
            _,
            prefs,
        ->
        click(scene, MR.strings.reader_settings_custom.localized())
        click(scene, MR.strings.reader_settings_vertical.localized())
        click(scene, MR.strings.reader_settings_continuous.localized())
        val texts = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }.flatMap {
            it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { t -> t.text }
        }
        assertFalse(texts.contains(MR.strings.pref_zoom_start.localized()))
        assertFalse(texts.contains(MR.strings.pref_dual_page_split.localized()))
        assertTrue(texts.contains(MR.strings.pref_webtoon_side_padding.localized()))
        val slider = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }.first {
            it.config.contains(SemanticsActions.SetProgress)
        }
        requireNotNull(slider.config[SemanticsActions.SetProgress].action).invoke(17f)
        render(scene)
        assertEquals(17, prefs.loadDisplayOptions().webtoonPadding)
        click(scene, MR.strings.pref_double_tap_zoom.localized())
        assertFalse(prefs.loadDisplayOptions().webtoonDoubleTapZoom)
        capture(scene, "webtoon-extensions")
    }

    @Test
    fun `automatic actual single page shows image checkboxes and resizing retains saved values`() = runTest {
        val layoutScope = this
        withPanel(locale = Locale.SIMPLIFIED_CHINESE) { scene, model, prefs ->
            click(scene, MR.strings.reader_settings_custom.localized())
            click(scene, MR.strings.reader_settings_auto.localized())
            model.updateViewportSize(400, 900, layoutScope)
            render(scene)
            assertTrue(model.state.value.automaticLayout)
            assertFalse(model.state.value.dualPageMode)
            model.setDisplayOptions(
                model.state.value.displayOptions.copy(rotateWide = true, rotateInvert = true),
                prefs,
            )
            render(scene)
            val labels = listOf(
                MR.strings.pref_landscape_zoom,
                MR.strings.pref_dual_page_split,
                MR.strings.pref_page_rotate,
                MR.strings.pref_page_rotate_invert,
            ).map { it.localized() }
            fun hasText(label: String) = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }.any {
                it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == label } == true
            }
            labels.forEach { assertTrue(hasText(it), "Automatic single must show $it") }
            capture(scene, "automatic-single-checkboxes")
            model.updateViewportSize(1600, 900, layoutScope)
            advanceTimeBy(mihon.domain.reader.AdaptiveReaderLayout.STABILITY_MILLIS + 1)
            runCurrent()
            render(scene)
            assertTrue(model.state.value.dualPageMode)
            labels.forEach { assertFalse(hasText(it), "Automatic dual must hide $it") }
            assertTrue(prefs.loadDisplayOptions().rotateWide && prefs.loadDisplayOptions().rotateInvert)
            model.updateViewportSize(400, 900, layoutScope)
            advanceTimeBy(mihon.domain.reader.AdaptiveReaderLayout.STABILITY_MILLIS + 1)
            runCurrent()
            render(scene)
            labels.forEach { assertTrue(hasText(it)) }
            click(scene, MR.strings.pref_dual_page_split.localized())
            assertTrue(model.state.value.effectiveAutoSplitPages)
            assertTrue(prefs.autoSplitPages)
            model.updateViewportSize(1600, 900, layoutScope)
            advanceTimeBy(mihon.domain.reader.AdaptiveReaderLayout.STABILITY_MILLIS + 1)
            runCurrent()
            render(scene)
            assertFalse(model.state.value.effectiveAutoSplitPages)
            assertTrue(prefs.autoSplitPages)
        }
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
                            displayOptions = state.displayOptions,
                            onDisplayOptionsChange = { model.setDisplayOptions(it, prefs) },
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

    private fun click(scene: ImageComposeScene, label: String, role: Role? = null) {
        var node = option(scene, label, role)
        val scroller = scene.semanticsOwners.flatMap {
            flatten(it.rootSemanticsNode)
        }.firstOrNull { it.config.contains(SemanticsActions.ScrollBy) }
        repeat(18) {
            if (node.boundsInRoot.height <= 0f || node.boundsInRoot.top < 150 || node.boundsInRoot.bottom > 1430) {
                val dy = if (node.boundsInRoot.top < 150 && node.boundsInRoot.height > 0f) -250f else 250f
                scroller?.config?.get(SemanticsActions.ScrollBy)?.action?.invoke(0f, dy)
                repeat(3) { render(scene) }
                node = option(scene, label, role)
            }
        }
        val position = node.boundsInRoot.center
        scene.sendPointerEvent(PointerEventType.Press, position)
        scene.sendPointerEvent(PointerEventType.Release, position)
        render(scene)
    }

    private fun option(
        scene: ImageComposeScene,
        label: String,
        role: Role? = null,
    ): SemanticsNode = scene.semanticsOwners
        .flatMap { flatten(it.rootSemanticsNode) }
        .first { node ->
            node.config.contains(SemanticsActions.OnClick) &&
                (role == null || node.config.getOrNull(SemanticsProperties.Role) == role) && flatten(node).any {
                    it.config.contains(SemanticsProperties.Text) &&
                        it.config[SemanticsProperties.Text].any { text -> text.text == label }
                }
        }

    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
}
