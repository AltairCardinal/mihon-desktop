package mihon.desktop.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import mihon.desktop.reader.ReaderBackgroundTheme
import mihon.desktop.reader.ReaderColorFilter
import mihon.desktop.reader.ReaderDisplayOptions
import mihon.desktop.reader.ReaderFlashColor
import mihon.desktop.reader.ReaderRippleStyle
import mihon.desktop.reader.ReaderZoomStart
import mihon.desktop.reader.ReadingMode
import mihon.desktop.reader.ScaleType
import mihon.desktop.reader.WebtoonSidePadding
import mihon.desktop.reader.ZoomState
import mihon.desktop.ui.settings.desktopSettingsActivationKeys
import mihon.domain.reader.NavigationInversion
import mihon.domain.reader.ReaderDirection
import mihon.domain.reader.ReaderScrollMode
import mihon.domain.reader.ReaderSettings
import mihon.domain.reader.ReaderSettingsLayout
import mihon.domain.reader.ReaderSettingsMode
import tachiyomi.i18n.MR

/** Reader settings mirror Android: reading mode/extensions, general/E-Ink, and filters. */
@Composable
fun ReaderSettingsPanel(
    currentMode: ReadingMode,
    displayOptions: ReaderDisplayOptions = ReaderDisplayOptions(),
    onDisplayOptionsChange: (ReaderDisplayOptions) -> Unit = {},
    followsGlobal: Boolean = false,
    onFollowGlobal: () -> Unit = {},
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
    feedback: String? = null,
    pairingRecovery: @Composable () -> Unit = {},
    readerSettings: ReaderSettings? = null,
    onReaderSettingsChange: ((ReaderSettings) -> Unit)? = null,
    onCustomize: (() -> Unit)? = null,
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
                feedback?.let { Text(it, color = MaterialTheme.colorScheme.error) }
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
                            followsGlobal = followsGlobal,
                            onFollowGlobal = onFollowGlobal,
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
                            pairingRecovery = pairingRecovery,
                            readerSettings = readerSettings,
                            onReaderSettingsChange = onReaderSettingsChange,
                            onCustomize = onCustomize,
                            displayOptions = displayOptions,
                            onDisplayOptionsChange = onDisplayOptionsChange,
                            scaleType = scaleType,
                            onScaleTypeChange = onScaleTypeChange,
                            cropBordersPager = cropBordersPager,
                            cropBordersWebtoon = cropBordersWebtoon,
                            onCropBordersPagerChange = onCropBordersPagerChange,
                            onCropBordersWebtoonChange = onCropBordersWebtoonChange,
                        )
                        1 -> ReaderGeneralTab(
                            backgroundTheme,
                            onBackgroundThemeChange,
                            displayOptions,
                            onDisplayOptionsChange,
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
@Suppress("UNUSED_PARAMETER")
internal fun GeneralTab(
    currentMode: ReadingMode,
    followsGlobal: Boolean = false,
    onFollowGlobal: () -> Unit = {},
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
    pairingRecovery: @Composable () -> Unit = {},
    readerSettings: ReaderSettings? = null,
    onReaderSettingsChange: ((ReaderSettings) -> Unit)? = null,
    onCustomize: (() -> Unit)? = null,
    displayOptions: ReaderDisplayOptions = ReaderDisplayOptions(),
    onDisplayOptionsChange: (ReaderDisplayOptions) -> Unit = {},
    scaleType: ScaleType = ScaleType.DEFAULT,
    onScaleTypeChange: (ScaleType) -> Unit = {},
    cropBordersPager: Boolean = false,
    cropBordersWebtoon: Boolean = false,
    onCropBordersPagerChange: (Boolean) -> Unit = {},
    onCropBordersWebtoonChange: (Boolean) -> Unit = {},
) {
    val settings = readerSettings ?: ReaderSettings(
        mode = if (currentMode == ReadingMode.WEBTOON ||
            currentMode == ReadingMode.VERTICAL
        ) {
            ReaderSettingsMode.VERTICAL
        } else {
            ReaderSettingsMode.HORIZONTAL
        },
        layout = when {
            currentMode == ReadingMode.AUTO -> ReaderSettingsLayout.AUTO
            isDualPage -> ReaderSettingsLayout.DOUBLE
            else -> ReaderSettingsLayout.SINGLE
        },
        direction = if (currentMode == ReadingMode.LTR) ReaderDirection.LTR else ReaderDirection.RTL,
        scroll = if (currentMode == ReadingMode.VERTICAL) ReaderScrollMode.PAGED else ReaderScrollMode.CONTINUOUS,
        match = isAutoSpreadMatching,
    )
    ReaderModeSettingsContent(
        settings = settings,
        followsGlobal = followsGlobal,
        onFollowGlobal = onFollowGlobal,
        onCustomize = onCustomize ?: { onModeChange(currentMode) },
        onChange = onReaderSettingsChange ?: { changed ->
            onModeChange(
                when {
                    changed.mode == ReaderSettingsMode.VERTICAL && changed.scroll == ReaderScrollMode.PAGED ->
                        ReadingMode.VERTICAL
                    changed.mode == ReaderSettingsMode.VERTICAL -> ReadingMode.WEBTOON
                    changed.layout == ReaderSettingsLayout.AUTO -> ReadingMode.AUTO
                    changed.direction == ReaderDirection.LTR -> ReadingMode.LTR
                    else -> ReadingMode.RTL
                },
            )
            if (changed.layout !=
                ReaderSettingsLayout.AUTO
            ) {
                onDualPageChange(changed.layout == ReaderSettingsLayout.DOUBLE)
            }
            if (changed.match != isAutoSpreadMatching) onAutoSpreadMatchingChange(changed.match)
        },
        actualDualPage = isDualPage,
    )
    pairingRecovery()
    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

    ReaderModeExtensions(
        settings, isDualPage, navigationMode, onNavigationModeChange, scaleType, onScaleTypeChange,
        autoSplitPages, onAutoSplitPagesChange, cropBordersPager, cropBordersWebtoon,
        onCropBordersPagerChange, onCropBordersWebtoonChange, displayOptions, onDisplayOptionsChange,
    )
}

@Composable
private fun ReaderModeExtensions(
    settings: ReaderSettings,
    actualDualPage: Boolean,
    navigationMode: NavigationMode,
    onNavigationModeChange: (NavigationMode) -> Unit,
    scaleType: ScaleType,
    onScaleTypeChange: (ScaleType) -> Unit,
    split: Boolean,
    onSplitChange: (Boolean) -> Unit,
    cropPager: Boolean,
    cropWebtoon: Boolean,
    onCropPagerChange: (Boolean) -> Unit,
    onCropWebtoonChange: (Boolean) -> Unit,
    options: ReaderDisplayOptions,
    onChange: (ReaderDisplayOptions) -> Unit,
) {
    val webtoon = settings.mode == ReaderSettingsMode.VERTICAL && settings.scroll == ReaderScrollMode.CONTINUOUS
    val nav = if (webtoon) options.webtoonNavigation else navigationMode
    SettingsSection(MR.strings.pref_viewer_nav.localized()) {
        ChoiceRow {
            listOf(
                NavigationMode.Default,
                NavigationMode.L,
                NavigationMode.Kindle,
                NavigationMode.Edge,
                NavigationMode.RightAndLeft,
                NavigationMode.Disabled,
            ).forEach { mode ->
                ChoiceChip(navigationModeLabel(mode), nav == mode, {
                    if (webtoon) onChange(options.copy(webtoonNavigation = mode)) else onNavigationModeChange(mode)
                })
            }
        }
    }
    if (nav != NavigationMode.Disabled) {
        SettingsSection(MR.strings.pref_read_with_tapping_inverted.localized()) {
            ChoiceRow {
                val inversion = if (webtoon) options.webtoonInversion else options.pagerInversion
                val labels =
                    listOf(
                        MR.strings.tapping_inverted_none,
                        MR.strings.tapping_inverted_horizontal,
                        MR.strings.tapping_inverted_vertical,
                        MR.strings.tapping_inverted_both,
                    )
                NavigationInversion.entries.forEachIndexed { index, value ->
                    ChoiceChip(labels[index].localized(), inversion == value, {
                        onChange(
                            if (webtoon) {
                                options.copy(
                                    webtoonInversion = value,
                                )
                            } else {
                                options.copy(pagerInversion = value)
                            },
                        )
                    })
                }
            }
        }
    }
    if (webtoon) {
        ReaderIntegerSlider(MR.strings.pref_webtoon_side_padding.localized(), options.webtoonPadding, 0..25, "%") {
            onChange(options.copy(webtoonPadding = it))
        }
    } else {
        SettingsSection(MR.strings.pref_image_scale_type.localized()) {
            ChoiceRow {
                ScaleType.entries.forEach { type ->
                    ChoiceChip(scaleTypeLabel(type), scaleType == type, { onScaleTypeChange(type) })
                }
            }
        }
        SettingsSection(MR.strings.pref_zoom_start.localized()) {
            val labels =
                listOf(
                    MR.strings.zoom_start_automatic,
                    MR.strings.zoom_start_left,
                    MR.strings.zoom_start_right,
                    MR.strings.zoom_start_center,
                )
            ChoiceRow {
                ReaderZoomStart.entries.forEachIndexed { i, value ->
                    ChoiceChip(labels[i].localized(), options.zoomStart == value, {
                        onChange(options.copy(zoomStart = value))
                    })
                }
            }
        }
    }
    CheckboxRow(MR.strings.pref_crop_borders.localized(), if (webtoon) cropWebtoon else cropPager, {
        if (webtoon) onCropWebtoonChange(it) else onCropPagerChange(it)
    })
    if (settings.singlePageExtrasEnabled(actualDualPage)) {
        CheckboxRow(MR.strings.pref_landscape_zoom.localized(), options.landscapeZoom, {
            onChange(options.copy(landscapeZoom = it))
        })
    }
    if (!webtoon) {
        CheckboxRow(MR.strings.pref_navigate_pan.localized(), options.navigateToPan, {
            onChange(options.copy(navigateToPan = it))
        })
    }
    if (settings.singlePageExtrasEnabled(actualDualPage)) {
        CheckboxRow(MR.strings.pref_dual_page_split.localized(), split, {
            if (it && options.rotateWide) onChange(options.copy(rotateWide = false))
            onSplitChange(it)
        })
        if (split) {
            CheckboxRow(MR.strings.pref_dual_page_invert.localized(), options.splitInvert, {
                onChange(options.copy(splitInvert = it))
            }, indented = true)
        }
        CheckboxRow(MR.strings.pref_page_rotate.localized(), options.rotateWide, {
            if (it && split) onSplitChange(false)
            onChange(options.copy(rotateWide = it))
        })
        if (options.rotateWide) {
            CheckboxRow(MR.strings.pref_page_rotate_invert.localized(), options.rotateInvert, {
                onChange(options.copy(rotateInvert = it))
            }, indented = true)
        }
    }
    if (webtoon) {
        CheckboxRow(MR.strings.pref_double_tap_zoom.localized(), options.webtoonDoubleTapZoom, {
            onChange(options.copy(webtoonDoubleTapZoom = it))
        })
        CheckboxRow(MR.strings.pref_webtoon_disable_zoom_out.localized(), options.webtoonDisableZoomOut, {
            onChange(options.copy(webtoonDisableZoomOut = it))
        })
    }
}

@Composable
private fun ReaderGeneralTab(
    background: ReaderBackgroundTheme,
    onBackgroundChange: (ReaderBackgroundTheme) -> Unit,
    options: ReaderDisplayOptions,
    onChange: (ReaderDisplayOptions) -> Unit,
) {
    SettingsSection(MR.strings.pref_reader_theme.localized()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReaderBackgroundTheme.entries.forEach { theme ->
                BackgroundThemeChip(theme, background == theme, { onBackgroundChange(theme) }, Modifier.weight(1f))
            }
        }
    }
    CheckboxRow(MR.strings.pref_show_page_number.localized(), options.showPageNumber, {
        onChange(options.copy(showPageNumber = it))
    })
    CheckboxRow(MR.strings.pref_fullscreen.localized(), options.fullscreen, { onChange(options.copy(fullscreen = it)) })
    CheckboxRow(MR.strings.pref_keep_screen_on.localized(), options.keepScreenOn, {
        onChange(options.copy(keepScreenOn = it))
    }, enabled = com.sun.jna.Platform.isWindows() || com.sun.jna.Platform.isMac())
    CheckboxRow(MR.strings.pref_read_with_long_tap.localized(), options.longTap, {
        onChange(options.copy(longTap = it))
    })
    CheckboxRow(MR.strings.pref_always_show_chapter_transition.localized(), options.alwaysShowChapterTransition, {
        onChange(options.copy(alwaysShowChapterTransition = it))
    })
    CheckboxRow(MR.strings.pref_page_transitions.localized(), options.pageTransitions, {
        onChange(options.copy(pageTransitions = it))
    })
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    SettingsSection(MR.strings.pref_reader_eink.localized()) {
        CheckboxRow(MR.strings.pref_flash_page.localized(), options.flash, { onChange(options.copy(flash = it)) })
        CheckboxRow(MR.strings.pref_page_turn_ripple.localized(), options.ripple, {
            onChange(options.copy(ripple = it))
        })
        if (options.ripple) {
            SettingsSection(MR.strings.pref_page_turn_ripple_style.localized()) {
                val labels =
                    listOf(
                        MR.strings.pref_page_turn_ripple_style_clean,
                        MR.strings.pref_page_turn_ripple_style_shadow_line,
                        MR.strings.pref_page_turn_ripple_style_smooth,
                    )
                ChoiceRow {
                    ReaderRippleStyle.entries.forEachIndexed { i, value ->
                        ChoiceChip(labels[i].localized(), options.rippleStyle == value, {
                            onChange(options.copy(rippleStyle = value))
                        })
                    }
                }
            }
        }
        if (options.flash) {
            ReaderIntegerSlider(
                MR.strings.pref_flash_duration.localized(),
                options.flashMillis,
                100..1500,
                " ms",
                100,
            ) {
                onChange(options.copy(flashMillis = it))
            }
            ReaderIntegerSlider(MR.strings.pref_flash_page_interval.localized(), options.flashInterval, 1..10) {
                onChange(options.copy(flashInterval = it))
            }
            SettingsSection(MR.strings.pref_flash_with.localized()) {
                val labels =
                    listOf(
                        MR.strings.pref_flash_style_black,
                        MR.strings.pref_flash_style_white,
                        MR.strings.pref_flash_style_white_black,
                    )
                ChoiceRow {
                    ReaderFlashColor.entries.forEachIndexed { i, value ->
                        ChoiceChip(labels[i].localized(), options.flashColor == value, {
                            onChange(options.copy(flashColor = value))
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderIntegerSlider(
    label: String,
    value: Int,
    range: IntRange,
    suffix: String = "",
    step: Int = 1,
    onChange: (Int) -> Unit,
) {
    SettingsSection(label) {
        Text("$value$suffix", style = MaterialTheme.typography.bodySmall)
        Slider(
            value.toFloat(),
            { onChange((it / step).toInt() * step) },
            valueRange =
            range.first.toFloat()..range.last.toFloat(),
            steps = ((range.last - range.first) / step - 1).coerceAtLeast(0),
        )
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
private fun ChoiceRow(content: @Composable FlowRowScope.() -> Unit) {
    // Android's SettingsChipRow uses the same wrapping layout; keep the dialog's own padding.
    FlowRow(
        modifier = Modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
private fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit, enabled: Boolean = true) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        enabled = enabled,
        modifier = Modifier.semantics { role = Role.RadioButton },
    )
}

@Composable
private fun CheckboxRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    indented: Boolean = false,
    enabled: Boolean = true,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (indented) 24.dp else 0.dp)
            .heightIn(min = 48.dp)
            .desktopSettingsActivationKeys(Role.Checkbox, enabled) { onCheckedChange(!checked) }
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange),
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            modifier = Modifier.clearAndSetSemantics {
            },
        )
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
    ReadingMode.DEFAULT -> MR.strings.label_default.localized()
    ReadingMode.AUTO -> MR.strings.automatic_background.localized()
    ReadingMode.LTR -> MR.strings.left_to_right_viewer.localized()
    ReadingMode.RTL -> MR.strings.right_to_left_viewer.localized()
    ReadingMode.WEBTOON -> MR.strings.webtoon_viewer.localized()
    ReadingMode.VERTICAL -> MR.strings.vertical_viewer.localized()
}

private fun navigationModeLabel(mode: NavigationMode): String = when (mode) {
    NavigationMode.Default -> MR.strings.label_default.localized()
    NavigationMode.RightAndLeft -> MR.strings.right_and_left_nav.localized()
    NavigationMode.L -> MR.strings.l_nav.localized()
    NavigationMode.Kindle -> MR.strings.kindlish_nav.localized()
    NavigationMode.Edge -> MR.strings.edge_nav.localized()
    NavigationMode.Disabled -> MR.strings.disabled_nav.localized()
}

private fun scaleTypeLabel(type: ScaleType): String = when (type) {
    ScaleType.STRETCH -> MR.strings.scale_type_stretch.localized()
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
