package mihon.desktop.ui.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import mihon.desktop.ui.settings.desktopSettingsActivationKeys
import mihon.domain.reader.ReaderDirection
import mihon.domain.reader.ReaderScrollMode
import mihon.domain.reader.ReaderSettings
import mihon.domain.reader.ReaderSettingsLayout
import mihon.domain.reader.ReaderSettingsMode
import tachiyomi.i18n.MR

/** Shared Desktop controls for the global default and the per-manga settings panel. */
@Composable
internal fun ReaderModeSettingsContent(
    settings: ReaderSettings,
    followsGlobal: Boolean,
    onFollowGlobal: () -> Unit,
    onCustomize: () -> Unit,
    onChange: (ReaderSettings) -> Unit,
    actualDualPage: Boolean,
) {
    ReaderModeChoiceRow {
        ReaderModeChoice(MR.strings.reader_settings_follow_default.localized(), followsGlobal, onFollowGlobal)
        ReaderModeChoice(MR.strings.reader_settings_custom.localized(), !followsGlobal, onCustomize)
    }
    if (followsGlobal) {
        val summary = buildList {
            add(
                if (settings.mode ==
                    ReaderSettingsMode.HORIZONTAL
                ) {
                    MR.strings.reader_settings_horizontal.localized()
                } else {
                    MR.strings.reader_settings_vertical.localized()
                },
            )
            if (settings.mode == ReaderSettingsMode.HORIZONTAL) {
                add(
                    when (settings.layout) {
                        ReaderSettingsLayout.AUTO -> MR.strings.reader_settings_auto.localized() + " · " +
                            if (actualDualPage) {
                                MR.strings.reader_settings_dual.localized()
                            } else {
                                MR.strings.reader_settings_single.localized()
                            }
                        ReaderSettingsLayout.SINGLE -> MR.strings.reader_settings_single.localized()
                        ReaderSettingsLayout.DOUBLE -> MR.strings.reader_settings_dual.localized()
                    },
                )
                add(
                    if (settings.direction ==
                        ReaderDirection.LTR
                    ) {
                        MR.strings.desktop_reader_direction_ltr.localized()
                    } else {
                        MR.strings.desktop_reader_direction_rtl.localized()
                    },
                )
                if (settings.layout != ReaderSettingsLayout.SINGLE) {
                    add(
                        MR.strings.desktop_ui_auto_spread_matching.localized() + ": " +
                            if (settings.match) MR.strings.on.localized() else MR.strings.off.localized(),
                    )
                }
            } else {
                add(
                    if (settings.scroll ==
                        ReaderScrollMode.PAGED
                    ) {
                        MR.strings.reader_settings_paged.localized()
                    } else {
                        MR.strings.reader_settings_continuous.localized()
                    },
                )
                if (settings.scroll == ReaderScrollMode.CONTINUOUS) {
                    add(
                        MR.strings.reader_settings_page_gaps.localized() + ": " +
                            if (settings.gap) MR.strings.on.localized() else MR.strings.off.localized(),
                    )
                }
            }
        }.joinToString(" · ")
        Text(
            summary,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
    } else {
        ReaderModeChoices(settings, onChange)
    }
}

@Composable
internal fun ReaderModeChoices(settings: ReaderSettings, onChange: (ReaderSettings) -> Unit) {
    ReaderModeChoiceRow {
        ReaderModeChoice(
            MR.strings.reader_settings_horizontal.localized(),
            settings.mode == ReaderSettingsMode.HORIZONTAL,
            { onChange(settings.copy(mode = ReaderSettingsMode.HORIZONTAL)) },
            hint = MR.strings.reader_settings_horizontal_hint.localized(),
            modeIcon = ReaderSettingsMode.HORIZONTAL,
        )
        ReaderModeChoice(
            MR.strings.reader_settings_vertical.localized(),
            settings.mode == ReaderSettingsMode.VERTICAL,
            { onChange(settings.copy(mode = ReaderSettingsMode.VERTICAL)) },
            hint = MR.strings.reader_settings_vertical_hint.localized(),
            modeIcon = ReaderSettingsMode.VERTICAL,
        )
    }
    if (settings.mode == ReaderSettingsMode.HORIZONTAL) {
        ReaderModeLabel(MR.strings.reader_settings_layout.localized())
        ReaderModeChoiceRow {
            ReaderSettingsLayout.entries.forEach { layout ->
                val title = when (layout) {
                    ReaderSettingsLayout.AUTO -> MR.strings.reader_settings_auto
                    ReaderSettingsLayout.SINGLE -> MR.strings.reader_settings_single
                    ReaderSettingsLayout.DOUBLE -> MR.strings.reader_settings_dual
                }.localized()
                ReaderModeChoice(title, settings.layout == layout, { onChange(settings.copy(layout = layout)) })
            }
        }
        Text(
            when (settings.layout) {
                ReaderSettingsLayout.AUTO -> MR.strings.reader_settings_auto_hint
                ReaderSettingsLayout.SINGLE -> MR.strings.reader_settings_single_hint
                ReaderSettingsLayout.DOUBLE -> MR.strings.reader_settings_dual_hint
            }.localized(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ReaderModeLabel(MR.strings.reader_settings_direction.localized())
        ReaderModeChoiceRow {
            ReaderModeChoice(
                MR.strings.desktop_reader_direction_ltr.localized(),
                settings.direction == ReaderDirection.LTR,
                { onChange(settings.copy(direction = ReaderDirection.LTR)) },
            )
            ReaderModeChoice(
                MR.strings.desktop_reader_direction_rtl.localized(),
                settings.direction == ReaderDirection.RTL,
                { onChange(settings.copy(direction = ReaderDirection.RTL)) },
            )
        }
        if (settings.layout != ReaderSettingsLayout.SINGLE) {
            ReaderModeSwitch(MR.strings.desktop_ui_auto_spread_matching.localized(), settings.match) {
                onChange(settings.copy(match = it))
            }
        }
    } else {
        ReaderModeLabel(MR.strings.reader_settings_scroll.localized())
        ReaderModeChoiceRow {
            ReaderScrollMode.entries.forEach { scroll ->
                ReaderModeChoice(
                    if (scroll ==
                        ReaderScrollMode.CONTINUOUS
                    ) {
                        MR.strings.reader_settings_continuous.localized()
                    } else {
                        MR.strings.reader_settings_paged.localized()
                    },
                    settings.scroll == scroll,
                    { onChange(settings.copy(scroll = scroll)) },
                    scrollIcon = scroll,
                )
            }
        }
        if (settings.scroll == ReaderScrollMode.CONTINUOUS) {
            ReaderModeSwitch(MR.strings.reader_settings_page_gaps.localized(), settings.gap) {
                onChange(settings.copy(gap = it))
            }
        }
    }
}

@Composable
private fun ReaderModeLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 12.dp))
}

@Composable
private fun ReaderModeChoiceRow(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
private fun RowScope.ReaderModeChoice(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    hint: String? = null,
    scrollIcon: ReaderScrollMode? = null,
    modeIcon: ReaderSettingsMode? = null,
) {
    val description = if (selected) MR.strings.selected.localized() else MR.strings.not_selected.localized()
    FilterChip(
        selected = selected,
        onClick = onClick,
        colors = FilterChipDefaults.filterChipColors(selectedLabelColor = MaterialTheme.colorScheme.primary),
        modifier = Modifier.weight(1f)
            .desktopSettingsActivationKeys(Role.RadioButton, onClick = onClick)
            .semantics {
                role = Role.RadioButton
                stateDescription = description
            },
        label = {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                scrollIcon?.let { ReaderScrollIcon(it) }
                modeIcon?.let { ReaderModeIcon(it) }
                Column(modifier = Modifier.padding(vertical = if (hint != null) 6.dp else 0.dp)) {
                    Text(title)
                    hint?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                }
            }
        },
    )
}

@Composable
private fun ReaderModeIcon(mode: ReaderSettingsMode) {
    val color = LocalContentColor.current
    Canvas(Modifier.padding(end = 6.dp).size(19.dp)) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            val stroke = Stroke(
                width = 1.65f,
                cap = StrokeCap.Round,
                join = androidx.compose.ui.graphics.StrokeJoin.Round,
            )
            if (mode == ReaderSettingsMode.VERTICAL) {
                drawRoundRect(color, Offset(4f, 2.75f), Size(16f, 18.5f), CornerRadius(2.5f), style = stroke)
                drawLine(color, Offset(4f, 8f), Offset(20f, 8f), 1.65f, StrokeCap.Round)
                drawLine(color, Offset(4f, 16f), Offset(20f, 16f), 1.65f, StrokeCap.Round)
            } else {
                drawPath(
                    Path().apply {
                        moveTo(12f, 5.5f)
                        cubicTo(9.1f, 3.3f, 5.9f, 3.3f, 3f, 4.5f)
                        lineTo(3f, 18.5f)
                        cubicTo(5.9f, 17.4f, 9.1f, 17.4f, 12f, 19.7f)
                        cubicTo(14.9f, 17.4f, 18.1f, 17.4f, 21f, 18.5f)
                        lineTo(21f, 4.5f)
                        cubicTo(18.1f, 3.3f, 14.9f, 3.3f, 12f, 5.5f)
                        close()
                    },
                    color,
                    style = stroke,
                )
                drawLine(color, Offset(12f, 5.5f), Offset(12f, 19.7f), 1.65f, StrokeCap.Round)
            }
        }
    }
}

@Composable
private fun ReaderModeSwitch(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val description = if (checked) MR.strings.on.localized() else MR.strings.off.localized()
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .desktopSettingsActivationKeys(Role.Switch) { onChange(!checked) }
            .semantics(mergeDescendants = true) {
                contentDescription = title
                stateDescription = description
            }
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = null,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

/** Decorative 24-unit paths from the approved prototype; selection inherits the chip's content color. */
@Composable
private fun ReaderScrollIcon(scroll: ReaderScrollMode) {
    val color = LocalContentColor.current
    Canvas(Modifier.padding(end = 6.dp).size(19.dp)) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            val stroke =
                Stroke(width = 1.65f, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
            if (scroll == ReaderScrollMode.CONTINUOUS) {
                drawRoundRect(color, Offset(3f, 2f), Size(18f, 20f), CornerRadius(2f), style = stroke)
                drawPath(
                    Path().apply {
                        moveTo(12f, 6f)
                        lineTo(12f, 18f)
                        moveTo(9f, 9f)
                        lineTo(12f, 6f)
                        lineTo(15f, 9f)
                        moveTo(9f, 15f)
                        lineTo(12f, 18f)
                        lineTo(15f, 15f)
                    },
                    color,
                    style = stroke,
                )
            } else {
                drawLine(color, Offset(4f, 2f), Offset(20f, 2f), 1.65f, StrokeCap.Round)
                drawRoundRect(color, Offset(4f, 6f), Size(16f, 12f), CornerRadius(2f), style = stroke)
                drawLine(color, Offset(4f, 22f), Offset(20f, 22f), 1.65f, StrokeCap.Round)
            }
        }
    }
}
