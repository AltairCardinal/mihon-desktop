package mihon.desktop.ui.reader

import tachiyomi.i18n.MR

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material.icons.outlined.FitScreen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import mihon.desktop.reader.ReaderBackgroundTheme
import mihon.desktop.reader.ReaderColorFilter
import mihon.desktop.reader.ReadingMode
import mihon.desktop.reader.ScaleType
import mihon.desktop.reader.WebtoonSidePadding
import mihon.desktop.reader.ZoomState

/**
 * Reader settings dialog with three tabs, mirroring Android's reader bottom sheet:
 *  - General: Reading mode, dual-page, auto spread, background theme
 *  - Display : Crop borders
 *  - Filter  : Brightness, colour filter (RGB + alpha)
 *
 * Android reference: presentation/reader/settings/
 *   GeneralSettingsPage, ColorFilterPage, ReadingModePage
 */
@Composable
fun ReaderSettingsPanel(
    currentMode: ReadingMode,
    isDualPage: Boolean,
    autoSplitPages: Boolean,
    isAutoSpreadMatching: Boolean,
    backgroundTheme: ReaderBackgroundTheme,
    navigationMode: NavigationMode,
    cropBordersPager: Boolean,
    cropBordersWebtoon: Boolean,
    webtoonSidePadding: WebtoonSidePadding,
    webtoonAutoScroll: Boolean,
    webtoonAutoScrollSpeed: WebtoonAutoScrollSpeed,
    colorFilter: ReaderColorFilter,
    scaleType: ScaleType = ScaleType.FIT_SCREEN,
    skipReadChapters: Boolean = false,
    skipFilteredChapters: Boolean = false,
    skipDuplicateChapters: Boolean = false,
    zoomState: ZoomState,
    onModeChange: (ReadingMode) -> Unit,
    onDualPageChange: (Boolean) -> Unit,
    onAutoSplitPagesChange: (Boolean) -> Unit,
    onAutoSpreadMatchingChange: (Boolean) -> Unit,
    onBackgroundThemeChange: (ReaderBackgroundTheme) -> Unit,
    onNavigationModeChange: (NavigationMode) -> Unit,
    onCropBordersPagerChange: (Boolean) -> Unit,
    onCropBordersWebtoonChange: (Boolean) -> Unit,
    onWebtoonSidePaddingChange: (WebtoonSidePadding) -> Unit,
    onWebtoonAutoScrollChange: (Boolean) -> Unit,
    onWebtoonAutoScrollSpeedChange: (WebtoonAutoScrollSpeed) -> Unit,
    onColorFilterChange: (ReaderColorFilter) -> Unit,
    onScaleTypeChange: (ScaleType) -> Unit = {},
    onSkipReadChaptersChange: (Boolean) -> Unit = {},
    onSkipFilteredChaptersChange: (Boolean) -> Unit = {},
    onSkipDuplicateChaptersChange: (Boolean) -> Unit = {},
    onZoomChange: (ZoomState) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf(
        MR.strings.pref_category_reading_mode.localized(),
        MR.strings.pref_category_general.localized(),
        MR.strings.custom_filter.localized(),
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(MR.strings.desktop_ui_reader_settings.localized()) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                TabRow(selectedTabIndex = selectedTab) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(title, style = MaterialTheme.typography.labelMedium) },
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    when (selectedTab) {
                        0 -> GeneralTab(
                            currentMode = currentMode,
                            isDualPage = isDualPage,
                            autoSplitPages = autoSplitPages,
                            isAutoSpreadMatching = isAutoSpreadMatching,
                            backgroundTheme = backgroundTheme,
                            navigationMode = navigationMode,
                            skipReadChapters = skipReadChapters,
                            skipFilteredChapters = skipFilteredChapters,
                            skipDuplicateChapters = skipDuplicateChapters,
                            zoomState = zoomState,
                            onModeChange = onModeChange,
                            onDualPageChange = onDualPageChange,
                            onAutoSplitPagesChange = onAutoSplitPagesChange,
                            onAutoSpreadMatchingChange = onAutoSpreadMatchingChange,
                            onBackgroundThemeChange = onBackgroundThemeChange,
                            onNavigationModeChange = onNavigationModeChange,
                            onSkipReadChaptersChange = onSkipReadChaptersChange,
                            onSkipFilteredChaptersChange = onSkipFilteredChaptersChange,
                            onSkipDuplicateChaptersChange = onSkipDuplicateChaptersChange,
                            onZoomChange = onZoomChange,
                        )
                        1 -> DisplayTab(
                            cropBordersPager = cropBordersPager,
                            cropBordersWebtoon = cropBordersWebtoon,
                            webtoonSidePadding = webtoonSidePadding,
                            webtoonAutoScroll = webtoonAutoScroll,
                            webtoonAutoScrollSpeed = webtoonAutoScrollSpeed,
                            scaleType = scaleType,
                            onCropBordersPagerChange = onCropBordersPagerChange,
                            onCropBordersWebtoonChange = onCropBordersWebtoonChange,
                            onWebtoonSidePaddingChange = onWebtoonSidePaddingChange,
                            onWebtoonAutoScrollChange = onWebtoonAutoScrollChange,
                            onWebtoonAutoScrollSpeedChange = onWebtoonAutoScrollSpeedChange,
                            onScaleTypeChange = onScaleTypeChange,
                        )
                        2 -> FilterTab(
                            colorFilter = colorFilter,
                            onColorFilterChange = onColorFilterChange,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(MR.strings.action_close.localized()) }
        },
    )
}

// ── Tab content composables ───────────────────────────────────────────────────

@Composable
private fun GeneralTab(
    currentMode: ReadingMode,
    isDualPage: Boolean,
    autoSplitPages: Boolean,
    isAutoSpreadMatching: Boolean,
    backgroundTheme: ReaderBackgroundTheme,
    navigationMode: NavigationMode,
    skipReadChapters: Boolean = false,
    skipFilteredChapters: Boolean = false,
    skipDuplicateChapters: Boolean = false,
    zoomState: ZoomState,
    onModeChange: (ReadingMode) -> Unit,
    onDualPageChange: (Boolean) -> Unit,
    onAutoSplitPagesChange: (Boolean) -> Unit,
    onAutoSpreadMatchingChange: (Boolean) -> Unit,
    onBackgroundThemeChange: (ReaderBackgroundTheme) -> Unit,
    onNavigationModeChange: (NavigationMode) -> Unit,
    onSkipReadChaptersChange: (Boolean) -> Unit = {},
    onSkipFilteredChaptersChange: (Boolean) -> Unit = {},
    onSkipDuplicateChaptersChange: (Boolean) -> Unit = {},
    onZoomChange: (ZoomState) -> Unit,
) {
    // Reading mode
    SettingsSection(MR.strings.desktop_ui_reading_mode.localized()) {
        ReadingMode.entries.forEach { mode ->
            RadioRow(
                label = readingModeLabel(mode),
                selected = currentMode == mode,
                onClick = { onModeChange(mode) },
            )
        }
    }

    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

    if (currentMode == ReadingMode.WEBTOON) {
        SettingsSection(MR.strings.desktop_ui_webtoon.localized()) {
            CheckboxRow(
                label = MR.strings.desktop_ui_split_wide_pages.localized(),
                checked = autoSplitPages,
                onCheckedChange = onAutoSplitPagesChange,
            )
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
    } else {
        // Pager settings
        SettingsSection(MR.strings.desktop_ui_pager.localized()) {
            CheckboxRow(
                label = MR.strings.desktop_ui_split_wide_pages.localized(),
                checked = autoSplitPages,
                onCheckedChange = onAutoSplitPagesChange,
            )
            CheckboxRow(
                label = MR.strings.desktop_ui_dual_page_side_by_side.localized(),
                checked = isDualPage,
                onCheckedChange = onDualPageChange,
            )
            if (isDualPage) {
                CheckboxRow(
                    label = MR.strings.desktop_ui_auto_spread_matching.localized(),
                    checked = isAutoSpreadMatching,
                    onCheckedChange = onAutoSpreadMatchingChange,
                    indented = true,
                )
            }
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
    }

    // Navigation mode (pager only)
    if (currentMode != ReadingMode.WEBTOON) {
        SettingsSection(MR.strings.desktop_ui_tap_navigation.localized()) {
            NavigationMode.entries.forEach { mode ->
                RadioRow(
                    label = navigationModeLabel(mode),
                    selected = navigationMode == mode,
                    onClick = { onNavigationModeChange(mode) },
                )
            }
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
    }

    // Skip read chapters
    SettingsSection(MR.strings.desktop_ui_chapter_navigation.localized()) {
        SwitchRow(
            label = MR.strings.desktop_ui_skip_read_chapters.localized(),
            checked = skipReadChapters,
            onCheckedChange = onSkipReadChaptersChange,
        )
        SwitchRow(
            label = MR.strings.pref_skip_filtered_chapters.localized(),
            checked = skipFilteredChapters,
            onCheckedChange = onSkipFilteredChaptersChange,
        )
        SwitchRow(
            label = MR.strings.pref_skip_dupe_chapters.localized(),
            checked = skipDuplicateChapters,
            onCheckedChange = onSkipDuplicateChaptersChange,
        )
    }

    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

    // Background theme
    SettingsSection(MR.strings.desktop_ui_background.localized()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ReaderBackgroundTheme.entries.forEach { theme ->
                BackgroundThemeChip(
                    theme = theme,
                    selected = backgroundTheme == theme,
                    onClick = { onBackgroundThemeChange(theme) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

    // Zoom quick-controls
    SettingsSection(MR.strings.desktop_ui_zoom.localized()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onZoomChange(zoomState.zoomOut()) }) {
                Icon(Icons.Default.ZoomOut, contentDescription = MR.strings.desktop_ui_zoom_out.localized(), tint = Color.Unspecified)
            }
            Text(
                text = "×${"%.1f".format(zoomState.scale)}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            IconButton(onClick = { onZoomChange(zoomState.zoomIn()) }) {
                Icon(Icons.Default.ZoomIn, contentDescription = MR.strings.desktop_ui_zoom_in.localized(), tint = Color.Unspecified)
            }
            IconButton(onClick = { onZoomChange(zoomState.reset()) }) {
                Icon(Icons.Outlined.FitScreen, contentDescription = MR.strings.desktop_ui_reset_zoom.localized(), tint = Color.Unspecified)
            }
        }
    }
}

@Composable
private fun DisplayTab(
    cropBordersPager: Boolean,
    cropBordersWebtoon: Boolean,
    webtoonSidePadding: WebtoonSidePadding,
    webtoonAutoScroll: Boolean,
    webtoonAutoScrollSpeed: WebtoonAutoScrollSpeed,
    scaleType: ScaleType = ScaleType.FIT_SCREEN,
    onCropBordersPagerChange: (Boolean) -> Unit,
    onCropBordersWebtoonChange: (Boolean) -> Unit,
    onWebtoonSidePaddingChange: (WebtoonSidePadding) -> Unit,
    onWebtoonAutoScrollChange: (Boolean) -> Unit,
    onWebtoonAutoScrollSpeedChange: (WebtoonAutoScrollSpeed) -> Unit,
    onScaleTypeChange: (ScaleType) -> Unit = {},
) {
    SettingsSection(MR.strings.desktop_ui_scale_type.localized()) {
        ScaleType.entries.forEach { type ->
            RadioRow(
                label = scaleTypeLabel(type),
                selected = scaleType == type,
                onClick = { onScaleTypeChange(type) },
            )
        }
    }

    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

    SettingsSection(MR.strings.desktop_ui_crop_borders.localized()) {
        SwitchRow(
            label = MR.strings.desktop_ui_pager_ltr_rtl_dual.localized(),
            checked = cropBordersPager,
            onCheckedChange = onCropBordersPagerChange,
        )
        SwitchRow(
            label = MR.strings.desktop_ui_webtoon.localized(),
            checked = cropBordersWebtoon,
            onCheckedChange = onCropBordersWebtoonChange,
        )
    }

    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

    SettingsSection(MR.strings.desktop_ui_webtoon_side_padding.localized()) {
        WebtoonSidePadding.entries.forEach { padding ->
            RadioRow(
                label = webtoonSidePaddingLabel(padding),
                selected = webtoonSidePadding == padding,
                onClick = { onWebtoonSidePaddingChange(padding) },
            )
        }
    }

    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

    SettingsSection(MR.strings.desktop_ui_webtoon_auto_scroll.localized()) {
        SwitchRow(
            label = MR.strings.desktop_ui_enable_auto_scroll.localized(),
            checked = webtoonAutoScroll,
            onCheckedChange = onWebtoonAutoScrollChange,
        )
        if (webtoonAutoScroll) {
            WebtoonAutoScrollSpeed.entries.forEach { speed ->
                RadioRow(
                    label = webtoonAutoScrollSpeedLabel(speed),
                    selected = webtoonAutoScrollSpeed == speed,
                    onClick = { onWebtoonAutoScrollSpeedChange(speed) },
                )
            }
        }
    }
}

@Composable
private fun FilterTab(
    colorFilter: ReaderColorFilter,
    onColorFilterChange: (ReaderColorFilter) -> Unit,
) {
    // Brightness
    SettingsSection(MR.strings.desktop_ui_brightness.localized()) {
        SwitchRow(
            label = MR.strings.pref_custom_brightness.localized(),
            checked = colorFilter.brightnessEnabled,
            onCheckedChange = { onColorFilterChange(colorFilter.copy(brightnessEnabled = it)) },
        )
        Text(
            text = "%.0f%%".format(colorFilter.brightness * 100),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = colorFilter.brightness,
            onValueChange = { onColorFilterChange(colorFilter.copy(brightness = it)) },
            valueRange = ReaderColorFilter.BRIGHTNESS_MIN..ReaderColorFilter.BRIGHTNESS_MAX,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

    // Colour filter
    SettingsSection(MR.strings.desktop_ui_color_filter.localized()) {
        SwitchRow(
            label = MR.strings.pref_custom_color_filter.localized(),
            checked = colorFilter.tintEnabled,
            onCheckedChange = { onColorFilterChange(colorFilter.copy(tintEnabled = it)) },
        )

        if (colorFilter.tintEnabled) {
            Spacer(Modifier.height(4.dp))
            ColorChannelSlider("R", colorFilter.r) { onColorFilterChange(colorFilter.copy(r = it)) }
            ColorChannelSlider("G", colorFilter.g) { onColorFilterChange(colorFilter.copy(g = it)) }
            ColorChannelSlider("B", colorFilter.b) { onColorFilterChange(colorFilter.copy(b = it)) }
            ColorChannelSlider("A", colorFilter.alpha) { onColorFilterChange(colorFilter.copy(alpha = it)) }
            // Preview swatch
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(32.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        Color(
                            red = colorFilter.r / 255f,
                            green = colorFilter.g / 255f,
                            blue = colorFilter.b / 255f,
                            alpha = colorFilter.alpha / 255f,
                        ),
                    )
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp)),
            )
        }
        SwitchRow(
            label = MR.strings.pref_grayscale.localized(),
            checked = colorFilter.grayscaleEnabled,
            onCheckedChange = { onColorFilterChange(colorFilter.copy(grayscaleEnabled = it)) },
        )
        SwitchRow(
            label = MR.strings.pref_inverted_colors.localized(),
            checked = colorFilter.invertEnabled,
            onCheckedChange = { onColorFilterChange(colorFilter.copy(invertEnabled = it)) },
        )
    }
}

// ── Reusable row helpers ──────────────────────────────────────────────────────

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 4.dp),
    )
    content()
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 4.dp))
    }
}

@Composable
private fun CheckboxRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, indented: Boolean = false) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (indented) 24.dp else 0.dp)
            .clickable { onCheckedChange(!checked) },
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 4.dp))
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ColorChannelSlider(label: String, value: Int, onValueChange: (Int) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.width(16.dp),
            textAlign = TextAlign.Center,
        )
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt()) },
            valueRange = 0f..255f,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.width(28.dp),
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun BackgroundThemeChip(
    theme: ReaderBackgroundTheme,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bgColor = when (theme) {
        ReaderBackgroundTheme.BLACK -> Color.Black
        ReaderBackgroundTheme.GRAY -> Color(0xFF888888)
        ReaderBackgroundTheme.WHITE -> Color.White
        ReaderBackgroundTheme.AUTOMATIC -> Color.Transparent
    }
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Box(
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .border(2.dp, borderColor, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = readerBackgroundThemeLabel(theme),
            style = MaterialTheme.typography.labelSmall,
            color = if (theme == ReaderBackgroundTheme.BLACK) Color.White else Color.Black,
        )
    }
}

internal fun readingModeLabel(mode: ReadingMode): String = when (mode) {
    ReadingMode.LTR -> MR.strings.left_to_right_viewer.localized()
    ReadingMode.RTL -> MR.strings.right_to_left_viewer.localized()
    ReadingMode.WEBTOON -> MR.strings.webtoon_viewer.localized()
}

private fun navigationModeLabel(mode: NavigationMode): String = when (mode) {
    NavigationMode.RightAndLeft -> MR.strings.right_and_left_nav.localized()
    NavigationMode.L -> MR.strings.l_nav.localized()
    NavigationMode.Kindle -> MR.strings.kindlish_nav.localized()
    NavigationMode.Edge -> MR.strings.edge_nav.localized()
    NavigationMode.Disabled -> MR.strings.disabled_nav.localized()
}

private fun scaleTypeLabel(type: ScaleType): String = when (type) {
    ScaleType.FIT_SCREEN -> MR.strings.scale_type_fit_screen.localized()
    ScaleType.FIT_WIDTH -> MR.strings.scale_type_fit_width.localized()
    ScaleType.FIT_HEIGHT -> MR.strings.scale_type_fit_height.localized()
    ScaleType.ORIGINAL_SIZE -> MR.strings.scale_type_original_size.localized()
    ScaleType.SMART_FIT -> MR.strings.scale_type_smart_fit.localized()
}

private fun webtoonSidePaddingLabel(padding: WebtoonSidePadding): String =
    if (padding == WebtoonSidePadding.NONE) MR.strings.none.localized() else padding.displayName

private fun webtoonAutoScrollSpeedLabel(speed: WebtoonAutoScrollSpeed): String = when (speed) {
    WebtoonAutoScrollSpeed.Slowest -> MR.strings.desktop_ui_slowest.localized()
    WebtoonAutoScrollSpeed.Slow -> MR.strings.desktop_ui_slow.localized()
    WebtoonAutoScrollSpeed.Normal -> MR.strings.desktop_ui_normal.localized()
    WebtoonAutoScrollSpeed.Fast -> MR.strings.desktop_ui_fast.localized()
    WebtoonAutoScrollSpeed.Fastest -> MR.strings.desktop_ui_fastest.localized()
}

private fun readerBackgroundThemeLabel(theme: ReaderBackgroundTheme): String = when (theme) {
    ReaderBackgroundTheme.BLACK -> MR.strings.black_background.localized()
    ReaderBackgroundTheme.GRAY -> MR.strings.gray_background.localized()
    ReaderBackgroundTheme.WHITE -> MR.strings.white_background.localized()
    ReaderBackgroundTheme.AUTOMATIC -> MR.strings.automatic_background.localized()
}
