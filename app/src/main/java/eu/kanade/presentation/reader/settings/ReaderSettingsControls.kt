package eu.kanade.presentation.reader.settings

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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsScreenModel
import eu.kanade.tachiyomi.ui.reader.viewer.pager.DualPageR2LPagerViewer
import mihon.domain.reader.ReaderDirection
import mihon.domain.reader.ReaderScrollMode
import mihon.domain.reader.ReaderSettings
import mihon.domain.reader.ReaderSettingsCodec
import mihon.domain.reader.ReaderSettingsLayout
import mihon.domain.reader.ReaderSettingsMode
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.HeadingItem
import tachiyomi.presentation.core.components.SettingsItemsPaddings
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

@Composable
internal fun SeriesReaderSettings(screenModel: ReaderSettingsScreenModel) {
    val manga by screenModel.mangaFlow.collectAsState()
    val viewer by screenModel.viewerFlow.collectAsState()
    val defaults = screenModel.preferences.collectDefaultReaderSettings()
    val flags = manga?.viewerFlags ?: 0L
    ReaderSettingsControls(
        settings = ReaderSettingsCodec.custom(flags, defaults),
        defaults = defaults,
        followingDefault = ReaderSettingsCodec.isFollowingDefault(flags),
        automaticDualPage = viewer is DualPageR2LPagerViewer,
        onChange = screenModel::changeReaderSettings,
        onFollowDefault = screenModel::followDefault,
    )
}

@Composable
internal fun ReaderPreferences.collectDefaultReaderSettings(): ReaderSettings {
    // The old keys remain authoritative for existing settings and migrated users.
    val mode by defaultReadingMode().collectAsState()
    val matching by automaticSpreadMatching().collectAsState()
    val snapshot by defaultReaderSettingsSnapshot().collectAsState()
    return androidx.compose.runtime.remember(mode, matching, snapshot) { defaultReaderSettings() }
}

@Composable
internal fun ReaderSettingsControls(
    settings: ReaderSettings,
    onChange: (ReaderSettings) -> Unit,
    followingDefault: Boolean? = null,
    defaults: ReaderSettings = settings,
    automaticDualPage: Boolean = false,
    onFollowDefault: (Boolean) -> Unit = {},
) {
    Column {
        if (followingDefault != null) {
            ChoiceRow {
                ChoiceChip(
                    MR.strings.reader_settings_follow_default,
                    followingDefault,
                    { onFollowDefault(true) },
                )
                ChoiceChip(MR.strings.reader_settings_custom, !followingDefault, { onFollowDefault(false) })
            }
        }
        if (followingDefault == true) {
            SettingsExplanation(
                stringResource(MR.strings.label_default) + " · " + readerSettingsSummary(defaults, automaticDualPage),
            )
            return@Column
        }
        ChoiceRow(MR.strings.pref_category_reading_mode) {
            ChoiceChip(
                MR.strings.reader_settings_horizontal,
                settings.mode == ReaderSettingsMode.HORIZONTAL,
                { onChange(settings.copy(mode = ReaderSettingsMode.HORIZONTAL)) },
                hint = MR.strings.reader_settings_horizontal_hint,
                icon = R.drawable.ic_reader_book_24dp,
            )
            ChoiceChip(
                MR.strings.reader_settings_vertical,
                settings.mode == ReaderSettingsMode.VERTICAL,
                { onChange(settings.copy(mode = ReaderSettingsMode.VERTICAL)) },
                hint = MR.strings.reader_settings_vertical_hint,
                icon = R.drawable.ic_reader_strip_24dp,
            )
        }
        if (settings.mode == ReaderSettingsMode.HORIZONTAL) {
            ChoiceRow(MR.strings.reader_settings_layout) {
                listOf(
                    ReaderSettingsLayout.AUTO to MR.strings.reader_settings_auto,
                    ReaderSettingsLayout.SINGLE to MR.strings.reader_settings_single,
                    ReaderSettingsLayout.DOUBLE to MR.strings.reader_settings_dual,
                ).forEach { (value, label) ->
                    ChoiceChip(label, settings.layout == value, { onChange(settings.copy(layout = value)) })
                }
            }
            SettingsExplanation(
                stringResource(
                    when (settings.layout) {
                        ReaderSettingsLayout.AUTO -> MR.strings.reader_settings_auto_hint
                        ReaderSettingsLayout.SINGLE -> MR.strings.reader_settings_single_hint
                        ReaderSettingsLayout.DOUBLE -> MR.strings.reader_settings_dual_hint
                    },
                ),
            )
            ChoiceRow(MR.strings.reader_settings_direction) {
                ChoiceChip(
                    MR.strings.desktop_reader_direction_ltr,
                    settings.direction == ReaderDirection.LTR,
                    { onChange(settings.copy(direction = ReaderDirection.LTR)) },
                )
                ChoiceChip(
                    MR.strings.desktop_reader_direction_rtl,
                    settings.direction == ReaderDirection.RTL,
                    { onChange(settings.copy(direction = ReaderDirection.RTL)) },
                )
            }
            if (settings.layout != ReaderSettingsLayout.SINGLE) {
                SettingsToggle(MR.strings.desktop_ui_auto_spread_matching, settings.match) {
                    onChange(settings.copy(match = it))
                }
            }
        } else {
            ChoiceRow(MR.strings.reader_settings_scroll) {
                ChoiceChip(
                    MR.strings.reader_settings_continuous,
                    settings.scroll == ReaderScrollMode.CONTINUOUS,
                    { onChange(settings.copy(scroll = ReaderScrollMode.CONTINUOUS)) },
                    icon = R.drawable.ic_reader_scroll_continuous_24dp,
                )
                ChoiceChip(
                    MR.strings.reader_settings_paged,
                    settings.scroll == ReaderScrollMode.PAGED,
                    { onChange(settings.copy(scroll = ReaderScrollMode.PAGED)) },
                    icon = R.drawable.ic_reader_gallery_vertical_24dp,
                )
            }
            if (settings.scroll == ReaderScrollMode.CONTINUOUS) {
                SettingsToggle(MR.strings.reader_settings_page_gaps, settings.gap) {
                    onChange(settings.copy(gap = it))
                }
            }
        }
    }
}

@Composable
private fun ChoiceRow(label: StringResource? = null, content: @Composable RowScope.() -> Unit) {
    Column {
        if (label != null) HeadingItem(label)
        Row(
            modifier = Modifier.fillMaxWidth().selectableGroup().padding(
                start = SettingsItemsPaddings.Horizontal,
                end = SettingsItemsPaddings.Horizontal,
                bottom = 8.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

@Composable
private fun RowScope.ChoiceChip(
    label: StringResource,
    selected: Boolean,
    onClick: () -> Unit,
    hint: StringResource? = null,
    icon: Int? = null,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        colors = FilterChipDefaults.filterChipColors(
            selectedLabelColor = MaterialTheme.colorScheme.primary,
            selectedLeadingIconColor = MaterialTheme.colorScheme.primary,
        ),
        modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
        label = {
            Column {
                Text(stringResource(label))
                if (hint != null) Text(stringResource(hint), style = MaterialTheme.typography.labelSmall)
            }
        },
        leadingIcon = icon?.let {
            { Icon(painterResource(it), contentDescription = null, modifier = Modifier.size(19.dp)) }
        },
    )
}

@Composable
internal fun SettingsExplanation(text: String) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth().padding(horizontal = SettingsItemsPaddings.Horizontal, vertical = 4.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun SettingsToggle(label: StringResource, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .toggleable(value = value, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = SettingsItemsPaddings.Horizontal, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(label), modifier = Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = null)
    }
}

@Composable
internal fun readerSettingsSummary(settings: ReaderSettings, automaticDualPage: Boolean): String {
    if (settings.mode == ReaderSettingsMode.VERTICAL) {
        return buildList {
            add(stringResource(MR.strings.reader_settings_vertical))
            add(
                stringResource(
                    if (settings.scroll == ReaderScrollMode.PAGED) {
                        MR.strings.reader_settings_paged
                    } else {
                        MR.strings.reader_settings_continuous
                    },
                ),
            )
            if (settings.scroll == ReaderScrollMode.CONTINUOUS) {
                add(
                    stringResource(MR.strings.reader_settings_page_gaps) + ": " +
                        stringResource(if (settings.gap) MR.strings.on else MR.strings.off),
                )
            }
        }.joinToString(" · ")
    }
    return buildList {
        add(stringResource(MR.strings.reader_settings_horizontal))
        if (settings.layout == ReaderSettingsLayout.AUTO) add(stringResource(MR.strings.reader_settings_auto))
        add(
            stringResource(
                if (settings.layout == ReaderSettingsLayout.DOUBLE ||
                    (settings.layout == ReaderSettingsLayout.AUTO && automaticDualPage)
                ) {
                    MR.strings.reader_settings_dual
                } else {
                    MR.strings.reader_settings_single
                },
            ),
        )
        add(
            stringResource(
                if (settings.direction == ReaderDirection.LTR) {
                    MR.strings.desktop_reader_direction_ltr
                } else {
                    MR.strings.desktop_reader_direction_rtl
                },
            ),
        )
        if (settings.layout != ReaderSettingsLayout.SINGLE) {
            add(
                stringResource(MR.strings.desktop_ui_auto_spread_matching) + ": " +
                    stringResource(if (settings.match) MR.strings.on else MR.strings.off),
            )
        }
    }.joinToString(" · ")
}
