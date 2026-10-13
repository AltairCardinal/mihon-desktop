package eu.kanade.presentation.more.settings.widget

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.library.service.saveSmartUpdateSettings
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.LabeledCheckbox
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun SmartUpdateDialog(
    preferences: LibraryPreferences,
    entries: Map<String, String>,
    onDismissRequest: () -> Unit,
) {
    var selected by remember { mutableStateOf(preferences.autoUpdateMangaRestrictions().get()) }
    var manualOverrides by remember { mutableStateOf(preferences.manualUpdateMangaRestrictionOverrides().get()) }
    var failed by remember { mutableStateOf(false) }
    val ignore = stringResource(MR.strings.pref_library_update_manual_ignore)
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(MR.strings.pref_library_update_smart_update)) },
        text = {
            LazyColumn {
                if (failed) {
                    item {
                        Text(stringResource(MR.strings.internal_error), color = MaterialTheme.colorScheme.error)
                    }
                }
                entries.forEach { (key, label) ->
                    item(key = key) {
                        LabeledCheckbox(
                            label = label,
                            checked = key in selected,
                            onCheckedChange = { selected = if (it) selected + key else selected - key },
                            modifier = Modifier.testTag("smart-rule-$key").semantics {
                                toggleableState = if (key in selected) ToggleableState.On else ToggleableState.Off
                            },
                        )
                    }
                    if (key in selected) {
                        item(key = "manual-$key") {
                            LabeledCheckbox(
                                label = ignore,
                                checked = key in manualOverrides,
                                onCheckedChange = {
                                    manualOverrides =
                                        if (it) manualOverrides + key else manualOverrides - key
                                },
                                modifier = Modifier.padding(start = 24.dp).testTag("smart-manual-$key")
                                    .semantics {
                                        contentDescription = "$label: $ignore"
                                        toggleableState =
                                            if (key in manualOverrides) ToggleableState.On else ToggleableState.Off
                                    },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                failed = !preferences.saveSmartUpdateSettings(selected, manualOverrides)
                if (!failed) onDismissRequest()
            }) { Text(stringResource(MR.strings.action_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) { Text(stringResource(MR.strings.action_cancel)) }
        },
    )
}
