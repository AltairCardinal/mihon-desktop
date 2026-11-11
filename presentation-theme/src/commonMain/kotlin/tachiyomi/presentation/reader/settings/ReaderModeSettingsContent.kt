package tachiyomi.presentation.reader.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.icerock.moko.resources.StringResource
import mihon.domain.reader.ReaderDirection
import mihon.domain.reader.ReaderScrollMode
import mihon.domain.reader.ReaderSettings
import mihon.domain.reader.ReaderSettingsLayout
import mihon.domain.reader.ReaderSettingsMode
import tachiyomi.i18n.MR

private val LocalReaderText = staticCompositionLocalOf<(StringResource) -> String> {
    error("Reader settings require a platform string resolver")
}

/** Platforms provide resources; the rendered controls and interaction contract are shared. */
@Composable
fun ReaderSettingsTextScope(text: (StringResource) -> String, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalReaderText provides text, content = content)
}

@Composable
private fun StringResource.readerText(): String = LocalReaderText.current(this)

private fun Modifier.readerSettingsActivationKeys(
    role: Role,
    onClick: () -> Unit,
): Modifier = onPreviewKeyEvent { event ->
    val activates = event.key == Key.Enter || event.key == Key.NumPadEnter ||
        (event.key == Key.Spacebar && role in setOf(Role.RadioButton, Role.Switch))
    if (event.type == KeyEventType.KeyDown && activates) {
        onClick()
        true
    } else {
        false
    }
}

/** The approved controls, shared by Android/Desktop global and per-work settings. */
@Composable
fun ReaderModeSettingsContent(
    settings: ReaderSettings,
    followsGlobal: Boolean,
    onFollowGlobal: () -> Unit,
    onCustomize: () -> Unit,
    onChange: (ReaderSettings) -> Unit,
    actualDualPage: Boolean,
    singlePageOptions: @Composable () -> Unit = {},
) {
    Column(Modifier.fillMaxWidth()) {
        ReaderModeLabel(MR.strings.desktop_reader_scope_title.readerText())
        ReaderSegments {
            ReaderSegment(
                MR.strings.reader_settings_follow_default.readerText(),
                followsGlobal,
                ModeSettingsIcon.LINK,
                onClick = onFollowGlobal,
            )
            ReaderSegment(
                MR.strings.reader_settings_custom.readerText(),
                !followsGlobal,
                ModeSettingsIcon.SLIDERS,
                onClick = onCustomize,
            )
        }
        ReaderHelp(
            if (followsGlobal) {
                MR.strings.desktop_reader_default_hint.readerText()
            } else {
                MR.strings.desktop_reader_custom_hint.readerText()
            },
        )
        HorizontalDivider(
            Modifier.padding(vertical = 22.dp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
        )
        if (followsGlobal) {
            ReaderDefaultSummary(settings, actualDualPage)
        } else {
            ReaderModeChoices(settings, onChange)
            if (settings.mode == ReaderSettingsMode.HORIZONTAL && settings.layout == ReaderSettingsLayout.SINGLE) {
                Spacer(Modifier.height(14.dp))
                singlePageOptions()
            }
        }
        Spacer(Modifier.height(14.dp))
    }
}

@Composable
fun ReaderModeChoices(settings: ReaderSettings, onChange: (ReaderSettings) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        ReaderModeLabel(MR.strings.desktop_reader_mode_selection.readerText())
        Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ReaderModeCard(
                MR.strings.reader_settings_horizontal.readerText(),
                MR.strings.reader_settings_horizontal_hint.readerText(),
                ModeSettingsIcon.BOOK,
                settings.mode == ReaderSettingsMode.HORIZONTAL,
            ) { onChange(settings.copy(mode = ReaderSettingsMode.HORIZONTAL)) }
            ReaderModeCard(
                MR.strings.reader_settings_vertical.readerText(),
                MR.strings.reader_settings_vertical_hint.readerText(),
                ModeSettingsIcon.STRIP,
                settings.mode == ReaderSettingsMode.VERTICAL,
            ) { onChange(settings.copy(mode = ReaderSettingsMode.VERTICAL)) }
        }
        Spacer(Modifier.height(22.dp))
        if (settings.mode == ReaderSettingsMode.HORIZONTAL) {
            ReaderModeLabel(MR.strings.desktop_reader_page_layout.readerText())
            ReaderSegments {
                ReaderSettingsLayout.entries.forEach { layout ->
                    val (title, icon) = when (layout) {
                        ReaderSettingsLayout.AUTO -> MR.strings.reader_settings_auto to ModeSettingsIcon.AUTO
                        ReaderSettingsLayout.SINGLE -> MR.strings.reader_settings_single to ModeSettingsIcon.SINGLE
                        ReaderSettingsLayout.DOUBLE -> MR.strings.reader_settings_dual to ModeSettingsIcon.DOUBLE
                    }
                    ReaderSegment(title.readerText(), settings.layout == layout, icon, accent = true) {
                        onChange(settings.copy(layout = layout))
                    }
                }
            }
            ReaderHelp(
                when (settings.layout) {
                    ReaderSettingsLayout.AUTO -> MR.strings.reader_settings_auto_hint
                    ReaderSettingsLayout.SINGLE -> MR.strings.reader_settings_single_hint
                    ReaderSettingsLayout.DOUBLE -> MR.strings.reader_settings_dual_hint
                }.readerText(),
            )
            Spacer(Modifier.height(22.dp))
            ReaderModeLabel(MR.strings.desktop_reader_turn_direction.readerText())
            Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ReaderDirectionCard(
                    MR.strings.desktop_reader_direction_ltr.readerText(),
                    ModeSettingsIcon.RIGHT,
                    settings.direction == ReaderDirection.LTR,
                ) {
                    onChange(settings.copy(direction = ReaderDirection.LTR))
                }
                ReaderDirectionCard(
                    MR.strings.desktop_reader_direction_rtl.readerText(),
                    ModeSettingsIcon.LEFT,
                    settings.direction == ReaderDirection.RTL,
                ) {
                    onChange(settings.copy(direction = ReaderDirection.RTL))
                }
            }
            if (settings.layout != ReaderSettingsLayout.SINGLE) {
                Spacer(Modifier.height(14.dp))
                ReaderModeSwitch(
                    MR.strings.desktop_ui_auto_spread_matching.readerText(),
                    MR.strings.desktop_reader_match_hint.readerText(),
                    settings.match,
                    showState = true,
                ) { onChange(settings.copy(match = it)) }
            }
        } else {
            ReaderModeLabel(MR.strings.reader_settings_scroll.readerText())
            ReaderSegments {
                ReaderScrollMode.entries.forEach { scroll ->
                    val continuous = scroll == ReaderScrollMode.CONTINUOUS
                    ReaderSegment(
                        if (continuous) {
                            MR.strings.reader_settings_continuous.readerText()
                        } else {
                            MR.strings.reader_settings_paged.readerText()
                        },
                        settings.scroll == scroll,
                        if (continuous) ModeSettingsIcon.CONTINUOUS else ModeSettingsIcon.PAGED,
                    ) { onChange(settings.copy(scroll = scroll)) }
                }
            }
            if (settings.scroll == ReaderScrollMode.CONTINUOUS) {
                Spacer(Modifier.height(14.dp))
                ReaderModeSwitch(
                    MR.strings.reader_settings_page_gaps.readerText(),
                    MR.strings.desktop_reader_gap_hint.readerText(),
                    settings.gap,
                ) {
                    onChange(settings.copy(gap = it))
                }
            }
        }
    }
}

/** Theme-derived fills keep the prototype's subdued hierarchy in light, dark and custom themes. */
@Composable
private fun readerSelectionColor(): Color = with(MaterialTheme.colorScheme) {
    lerp(surfaceContainerHighest, primaryContainer, 0.4f)
}

@Composable
private fun ReaderModeLabel(text: String) {
    Text(
        text,
        fontSize = 14.sp,
        lineHeight = 21.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(bottom = 10.dp),
    )
}

@Composable
private fun ReaderHelp(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        lineHeight = 19.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 9.dp),
    )
}

@Composable
private fun ReaderSegments(content: @Composable RowScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).background(colors.surfaceContainerLow)
            .border(
                1.dp,
                colors.onSurface.copy(alpha = 0.025f),
                RoundedCornerShape(15.dp),
            ).padding(4.dp).selectableGroup(),
        content = content,
    )
}

@Composable
private fun RowScope.ReaderSegment(
    title: String,
    selected: Boolean,
    icon: ModeSettingsIcon,
    accent: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val neutral = lerp(colors.surfaceContainerHighest, colors.primary, 0.07f)
    ReaderSelectable(
        selected,
        onClick,
        Modifier.weight(1f),
        radius = 11,
        fill = if (selected) {
            if (accent) readerSelectionColor() else neutral
        } else {
            Color.Transparent
        },
        border = if (selected) colors.onSurface.copy(alpha = 0.07f) else Color.Transparent,
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 43.dp).padding(horizontal = 5.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ReaderModeIcon(icon, Modifier.size(19.dp), if (selected) colors.primary else colors.onSurfaceVariant)
            Spacer(Modifier.width(7.dp))
            Text(
                title,
                modifier = Modifier.weight(1f, fill = false),
                textAlign = TextAlign.Center,
                fontSize = 15.sp,
                lineHeight = 22.sp,
                fontWeight = FontWeight.Medium,
                color = if (selected) colors.onSurface else colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RowScope.ReaderModeCard(
    title: String,
    hint: String,
    icon: ModeSettingsIcon,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    ReaderSelectable(selected, onClick, Modifier.weight(1f), radius = 17) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 82.dp).padding(horizontal = 12.dp, vertical = 13.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ReaderModeIcon(icon, Modifier.size(22.dp), if (selected) colors.primary else colors.onSurfaceVariant)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.Medium)
                Text(hint, fontSize = 12.sp, lineHeight = 18.sp, color = colors.onSurfaceVariant)
            }
            Canvas(Modifier.size(17.dp)) {
                drawCircle(
                    if (selected) colors.primary else colors.outline,
                    radius =
                    size.minDimension / 2 - 0.75.dp.toPx(),
                    style = if (selected) androidx.compose.ui.graphics.drawscope.Fill else Stroke(1.5.dp.toPx()),
                )
                if (selected) drawCircle(colors.onPrimary, 3.dp.toPx())
            }
        }
    }
}

@Composable
private fun RowScope.ReaderDirectionCard(
    title: String,
    icon: ModeSettingsIcon,
    selected: Boolean,
    onClick: () -> Unit,
) {
    ReaderSelectable(selected, onClick, Modifier.weight(1f), radius = 14) {
        Column(
            Modifier.fillMaxWidth().heightIn(min = 74.dp).padding(horizontal = 4.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ReaderModeIcon(icon, Modifier.size(23.dp))
            Text(
                title,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ReaderSelectable(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
    radius: Int,
    fill: Color = if (selected) readerSelectionColor() else MaterialTheme.colorScheme.surfaceContainerHighest,
    border: Color = if (selected) {
        MaterialTheme.colorScheme.primary.copy(
            alpha = 0.8f,
        )
    } else {
        MaterialTheme.colorScheme.outlineVariant
    },
    content: @Composable () -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val hovered by interactions.collectIsHoveredAsState()
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(radius.dp)
    val description = if (selected) MR.strings.selected.readerText() else MR.strings.not_selected.readerText()
    Box(
        modifier.clip(shape).background(fill)
            .border(if (focused) 2.dp else 1.dp, if (focused || hovered) colors.primary else border, shape)
            .readerSettingsActivationKeys(Role.RadioButton, onClick = onClick)
            .semantics(mergeDescendants = true) { stateDescription = description }
            .hoverable(interactions)
            .selectable(selected, interactions, indication = null, role = Role.RadioButton, onClick = onClick),
    ) {
        CompositionLocalProvider(LocalContentColor provides colors.onSurface, content = content)
    }
}

@Composable
fun ReaderModeSwitch(
    title: String,
    hint: String,
    checked: Boolean,
    showState: Boolean = false,
    onChange: (Boolean) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val shape = RoundedCornerShape(16.dp)
    val state = if (checked) MR.strings.on.readerText() else MR.strings.off.readerText()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val showStateLabel = showState && maxWidth > 390.dp
        Row(
            Modifier.fillMaxWidth().clip(shape).background(colors.surfaceContainerHighest)
                .border(
                    if (focused) 2.dp else 1.dp,
                    if (focused) colors.primary else colors.onSurface.copy(alpha = 0.025f),
                    shape,
                )
                .readerSettingsActivationKeys(Role.Switch) { onChange(!checked) }
                .semantics(mergeDescendants = true) {
                    contentDescription = title
                    stateDescription = state
                }
                .toggleable(checked, interactions, indication = null, role = Role.Switch, onValueChange = onChange)
                .heightIn(min = 81.dp).padding(horizontal = 14.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    title,
                    color = colors.onSurface,
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(hint, color = colors.onSurfaceVariant, fontSize = 12.sp, lineHeight = 19.sp)
            }
            if (showStateLabel) Text(state, color = colors.onSurfaceVariant, fontSize = 12.sp)
            Canvas(Modifier.size(width = 48.dp, height = 44.dp)) {
                val top = (size.height - 28.dp.toPx()) / 2
                drawRoundRect(
                    if (checked) colors.primary else colors.surfaceContainerLow,
                    Offset(0f, top),
                    Size(size.width, 28.dp.toPx()),
                    CornerRadius(14.dp.toPx()),
                )
                if (!checked) {
                    drawRoundRect(
                        colors.outline,
                        Offset(0f, top),
                        Size(size.width, 28.dp.toPx()),
                        CornerRadius(14.dp.toPx()),
                        style = Stroke(1.5.dp.toPx()),
                    )
                }
                drawCircle(
                    if (checked) colors.onPrimary else colors.onSurfaceVariant,
                    9.5.dp.toPx(),
                    Offset(if (checked) 34.dp.toPx() else 13.dp.toPx(), size.height / 2),
                )
            }
        }
    }
}

@Composable
private fun ReaderDefaultSummary(settings: ReaderSettings, actualDualPage: Boolean) {
    val colors = MaterialTheme.colorScheme
    val rows = buildList {
        add(
            MR.strings.pref_category_reading_mode.readerText() to if (settings.mode == ReaderSettingsMode.HORIZONTAL) {
                MR.strings.reader_settings_horizontal.readerText()
            } else {
                MR.strings.reader_settings_vertical.readerText()
            },
        )
        if (settings.mode == ReaderSettingsMode.HORIZONTAL) {
            add(
                MR.strings.desktop_reader_page_layout.readerText() to when (settings.layout) {
                    ReaderSettingsLayout.AUTO -> MR.strings.reader_settings_auto.readerText() + " · " +
                        if (actualDualPage) {
                            MR.strings.reader_settings_dual.readerText()
                        } else {
                            MR.strings.reader_settings_single.readerText()
                        }
                    ReaderSettingsLayout.SINGLE -> MR.strings.reader_settings_single.readerText()
                    ReaderSettingsLayout.DOUBLE -> MR.strings.reader_settings_dual.readerText()
                },
            )
            add(
                MR.strings.desktop_reader_turn_direction.readerText() to
                    if (settings.direction == ReaderDirection.LTR) {
                        MR.strings.desktop_reader_direction_ltr.readerText()
                    } else {
                        MR.strings.desktop_reader_direction_rtl.readerText()
                    },
            )
            if (settings.layout !=
                ReaderSettingsLayout.SINGLE
            ) {
                add(
                    MR.strings.desktop_ui_auto_spread_matching.readerText() to
                        if (settings.match) MR.strings.on.readerText() else MR.strings.off.readerText(),
                )
            }
        } else {
            add(
                MR.strings.reader_settings_scroll.readerText() to if (settings.scroll == ReaderScrollMode.CONTINUOUS) {
                    MR.strings.reader_settings_continuous.readerText()
                } else {
                    MR.strings.reader_settings_paged.readerText()
                },
            )
            if (settings.scroll == ReaderScrollMode.CONTINUOUS) {
                add(
                    MR.strings.reader_settings_page_gaps.readerText() to
                        if (settings.gap) MR.strings.on.readerText() else MR.strings.off.readerText(),
                )
            }
        }
    }
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = lerp(colors.surfaceContainerHigh, colors.primary, 0.06f),
        border = BorderStroke(1.dp, colors.primary.copy(alpha = 0.17f)),
    ) {
        Column(Modifier.fillMaxWidth().padding(19.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ReaderModeIcon(ModeSettingsIcon.LINK, Modifier.size(20.dp), colors.primary)
                Text(
                    MR.strings.desktop_reader_default_title.readerText(),
                    color = colors.onSurface,
                    fontSize = 15.sp,
                    lineHeight = 23.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            Text(
                MR.strings.desktop_reader_default_description.readerText(),
                modifier = Modifier.padding(top = 8.dp),
                color = colors.onSurfaceVariant,
                fontSize = 12.sp,
                lineHeight = 21.sp,
            )
            Spacer(Modifier.height(17.dp))
            rows.forEach { (label, value) ->
                HorizontalDivider(color = colors.onSurface.copy(alpha = 0.04f))
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 11.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        label,
                        Modifier.weight(1f),
                        color = colors.onSurfaceVariant,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                    )
                    Text(
                        value,
                        Modifier.weight(1f),
                        textAlign = TextAlign.End,
                        color = colors.onSurface,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}
