package eu.kanade.tachiyomi.ui.browse.author

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import eu.kanade.presentation.components.AppBar
import tachiyomi.domain.creator.service.CreatorCheckFrequency
import tachiyomi.domain.creator.service.CreatorSettingsEditor
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
internal fun creatorSettingsAction(editor: CreatorSettingsEditor): AppBar.Action {
    val state by editor.state.collectAsState()
    val gear = remember { FocusRequester() }
    LaunchedEffect(state.focusRevision) {
        if (state.focusRevision > 0) {
            withFrameNanos { }
            gear.requestFocus()
        }
    }
    return AppBar.Action(
        title = stringResource(MR.strings.creator_settings_title),
        icon = Icons.Outlined.Settings,
        onClick = editor::open,
        modifier = Modifier.focusRequester(gear).testTag("creator-settings-open"),
    )
}

@Composable
internal fun CreatorSettingsDialog(editor: CreatorSettingsEditor) {
    val state by editor.state.collectAsState()
    if (!state.open) return
    AlertDialog(
        onDismissRequest = editor::cancel,
        title = { Text(stringResource(MR.strings.creator_settings_title)) },
        text = {
            Column {
                Text(stringResource(MR.strings.creator_settings_frequency))
                CreatorCheckFrequency.entries.forEach { frequency ->
                    Row(
                        modifier = Modifier.testTag("creator-frequency-${frequency.value}").selectable(
                            selected = state.draft == frequency,
                            enabled = !state.saving,
                            role = Role.RadioButton,
                            onClick = { editor.select(frequency) },
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = state.draft == frequency, enabled = !state.saving, onClick = null)
                        Text(
                            stringResource(
                                when (frequency) {
                                    CreatorCheckFrequency.DAILY -> MR.strings.creator_frequency_daily
                                    CreatorCheckFrequency.WEEKLY -> MR.strings.creator_frequency_weekly
                                    CreatorCheckFrequency.MONTHLY -> MR.strings.creator_frequency_monthly
                                },
                            ),
                        )
                    }
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                editor.save()
            }, enabled = !state.saving, modifier = Modifier.testTag("creator-settings-save")) {
                Text(stringResource(MR.strings.action_save))
            }
        },
        dismissButton = {
            TextButton(
                onClick = editor::cancel,
                enabled = !state.saving,
                modifier = Modifier.testTag("creator-settings-cancel"),
            ) {
                Text(stringResource(MR.strings.action_cancel))
            }
        },
    )
}
