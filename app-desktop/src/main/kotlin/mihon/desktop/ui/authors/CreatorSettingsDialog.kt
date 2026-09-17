package mihon.desktop.ui.authors

import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import tachiyomi.domain.creator.service.CreatorCheckFrequency
import tachiyomi.domain.creator.service.CreatorSettingsEditor
import tachiyomi.i18n.MR

@Composable
internal fun CreatorSettingsButton(editor: CreatorSettingsEditor) {
    val state by editor.state.collectAsState()
    val gear = remember { FocusRequester() }
    LaunchedEffect(state.focusRevision) {
        if (state.focusRevision > 0) { withFrameNanos { }; gear.requestFocus() }
    }
    IconButton(onClick = editor::open, modifier = Modifier.focusRequester(gear).testTag("creator-settings-open")) {
        Icon(Icons.Outlined.Settings, MR.strings.creator_settings_title.localized())
    }
    CreatorSettingsDialog(editor)
}

@Composable
internal fun CreatorSettingsDialog(editor: CreatorSettingsEditor) {
    val state by editor.state.collectAsState()
    if (!state.open) return
    AlertDialog(
        onDismissRequest = editor::cancel,
        title = { Text(MR.strings.creator_settings_title.localized()) },
        text = {
            Column {
                Text(MR.strings.creator_settings_frequency.localized())
                CreatorCheckFrequency.entries.forEach { frequency ->
                    Row(
                        modifier = Modifier.testTag("creator-frequency-${frequency.value}").selectable(
                            selected = state.draft == frequency, enabled = !state.saving,
                            role = Role.RadioButton, onClick = { editor.select(frequency) },
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = state.draft == frequency, enabled = !state.saving, onClick = null)
                        Text(when (frequency) {
                            CreatorCheckFrequency.DAILY -> MR.strings.creator_frequency_daily
                            CreatorCheckFrequency.WEEKLY -> MR.strings.creator_frequency_weekly
                            CreatorCheckFrequency.MONTHLY -> MR.strings.creator_frequency_monthly
                        }.localized())
                    }
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = { editor.save() }, enabled = !state.saving, modifier = Modifier.testTag("creator-settings-save")) {
                Text(MR.strings.action_save.localized())
            }
        },
        dismissButton = {
            TextButton(onClick = editor::cancel, enabled = !state.saving, modifier = Modifier.testTag("creator-settings-cancel")) {
                Text(MR.strings.action_cancel.localized())
            }
        },
    )
}
