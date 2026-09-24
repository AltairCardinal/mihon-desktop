@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package eu.kanade.tachiyomi.uicatalog

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.PreferenceScreen
import eu.kanade.tachiyomi.R
import kotlinx.collections.immutable.persistentMapOf

@Composable
internal fun DialogExamples(s: DemoState, send: Dispatch) {
    when (s.category) {
        13 -> {
            var submenu by remember { mutableStateOf(false) }
            Box {
                LabButton(R.string.lab_menu, "open-menu") { submenu = false; send(DemoAction.Modal(Overlay.MENU)) }
                DropdownMenu(expanded = s.overlay == Overlay.MENU, onDismissRequest = { send(DemoAction.Modal(Overlay.NONE)) }) {
                    if (s.scenario == 1 && !submenu) DropdownMenuItem(
                        text = { Text(stringResource(R.string.lab_sort)) }, onClick = { submenu = true },
                    ) else {
                        DropdownMenuItem(text = { Text(stringResource(R.string.lab_button)) }, enabled = s.enabled,
                            modifier = Modifier.testTag("menu-action"), onClick = {
                                send(DemoAction.Event(if (submenu) "nested-menu-action" else "menu-action"))
                                send(DemoAction.Modal(Overlay.NONE))
                            })
                    }
                }
            }
            Text(stringResource(R.string.lab_body))
        }
        14 -> FlowRow {
            LabButton(R.string.lab_dialog, "open-confirm") { send(DemoAction.Modal(Overlay.CONFIRM)) }
            LabButton(R.string.lab_sheet, "open-sheet") { send(DemoAction.Modal(Overlay.SHEET)) }
            LabButton(R.string.lab_edit, "open-edit") { send(DemoAction.Modal(Overlay.EDIT)) }
        }
        16 -> {
            NameField(s, send)
            Text(stringResource(R.string.lab_saved, s.saved), Modifier.testTag("saved-value"))
            FlowRow {
                LabButton(R.string.lab_save, "commit-name", s.canCommitName) { send(DemoAction.CommitName) }
                LabButton(R.string.lab_cancel, "cancel-edit") { send(DemoAction.CancelEdit) }
            }
            StateText(s)
        }
        17 -> {
            Text(stringResource(R.string.lab_remove_body))
            LabButton(R.string.lab_remove, "open-remove") { send(DemoAction.Modal(Overlay.CONFIRM)) }
            FixtureRows(s, send)
            StateText(s)
        }
    }
}

@Composable
internal fun NameField(s: DemoState, send: Dispatch) {
    OutlinedTextField(
        value = s.draft, onValueChange = { send(DemoAction.Draft(it)) },
        modifier = Modifier.fillMaxWidth().testTag("draft"),
        label = { Text(stringResource(R.string.lab_draft)) },
        supportingText = { Text(stringResource(if (s.canCommitName) R.string.lab_valid else R.string.lab_required)) },
        isError = s.draft.isNotEmpty() && !s.canCommitName && s.draft.trim() != s.saved,
        singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { send(DemoAction.CommitName) }),
    )
}

@Composable
internal fun LabeledToggle(label: Int, checked: Boolean, tag: String, enabled: Boolean = true, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().testTag(tag).toggleable(value = checked, enabled = enabled, role = Role.Checkbox,
            onValueChange = { onToggle() }).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Text(stringResource(label), Modifier.padding(start = 12.dp))
    }
}

@Composable
internal fun PreferenceExamples(s: DemoState, send: Dispatch, padding: PaddingValues) {
    val bool = remember { MemoryPreference("switch", s.enabled) }
    val multiple = remember { MemoryPreference("multiple", s.selected.map { it.toString() }.toSet()) }
    val text = remember { MemoryPreference("text", s.saved) }
    val entries = persistentMapOf("0" to stringResource(R.string.lab_tab, 1), "1" to stringResource(R.string.lab_tab, 2), "2" to stringResource(R.string.lab_tab, 3))
    PreferenceScreen(contentPadding = padding, items = listOf(
        Preference.PreferenceItem.InfoPreference(stringResource(R.string.lab_notice)),
        Preference.PreferenceItem.SwitchPreference(
            preference = bool, title = stringResource(R.string.lab_enabled),
            onValueChanged = { value -> if (value != s.enabled) send(DemoAction.ToggleEnabled); true },
        ),
        Preference.PreferenceItem.BasicListPreference(
            value = s.tab.toString(), entries = entries, title = stringResource(R.string.lab_secondary), enabled = s.enabled,
            onValueChanged = { send(DemoAction.Tab(it.toInt())) },
        ),
        Preference.PreferenceItem.MultiSelectListPreference(
            preference = multiple,
            entries = persistentMapOf("1" to stringResource(R.string.lab_item, 1), "2" to stringResource(R.string.lab_item, 2)),
            title = stringResource(R.string.lab_select_all), enabled = s.enabled,
            onValueChanged = { selected -> send(DemoAction.Selection(selected.map(String::toInt).toSet())); true },
        ),
        Preference.PreferenceItem.SliderPreference(
            value = s.score, title = stringResource(R.string.lab_score, s.score), valueRange = 0..10, enabled = s.enabled,
            onValueChanged = { send(DemoAction.Score(it)) },
        ),
        Preference.PreferenceItem.EditTextPreference(
            preference = text, title = stringResource(R.string.lab_edit),
            onValueChanged = { value ->
                val valid = value.trim().isNotEmpty() && value.trim() !in setOf("Existing", "已有名称")
                if (valid) { send(DemoAction.Draft(value)); send(DemoAction.CommitName) }
                valid
            },
        ),
        Preference.PreferenceItem.TextPreference(
            title = stringResource(R.string.lab_saved, s.saved), onClick = { send(DemoAction.Modal(Overlay.EDIT)) },
        ),
    ))
}

@Composable
internal fun CatalogOverlay(s: DemoState, send: Dispatch, onBack: () -> Unit) {
    when (s.overlay) {
        Overlay.NONE, Overlay.MENU -> Unit
        Overlay.SHEET -> AdaptiveSheet(onDismissRequest = onBack) {
            Column(Modifier.fillMaxWidth().heightIn(max = 600.dp).verticalScroll(rememberScrollState()).padding(24.dp)) {
                Text(stringResource(R.string.lab_sheet), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.lab_depth, s.overlayDepth))
                NameField(s, send)
                FixtureTabs(s.tab % 3) { send(DemoAction.Tab(it)) }
                LabButton(R.string.lab_nested, "sheet-next") { send(DemoAction.Nested) }
                LabButton(R.string.lab_back, "sheet-back", onClick = onBack)
            }
        }
        Overlay.EDIT -> AlertDialog(
            onDismissRequest = onBack,
            title = { Text(stringResource(R.string.lab_edit)) },
            text = { NameField(s, send) },
            confirmButton = { LabButton(R.string.lab_save, "dialog-save", s.canCommitName) { send(DemoAction.CommitName) } },
            dismissButton = { LabButton(R.string.lab_cancel, "dialog-cancel") { send(DemoAction.CancelEdit) } },
        )
        Overlay.DISCARD -> AlertDialog(
            onDismissRequest = onBack,
            title = { Text(stringResource(R.string.lab_discard)) },
            text = { Text(stringResource(R.string.lab_discard_body)) },
            confirmButton = { LabButton(R.string.lab_confirm, "discard-confirm") { send(DemoAction.ConfirmDiscard) } },
            dismissButton = { LabButton(R.string.lab_cancel, "discard-cancel", onClick = onBack) },
        )
        Overlay.CONFIRM -> AlertDialog(
            onDismissRequest = onBack,
            title = { Text(stringResource(if (s.category == 31) R.string.lab_unbind else R.string.lab_remove)) },
            text = {
                Column {
                    Text(stringResource(R.string.lab_remove_body))
                    if (s.category == 17) {
                        LabeledToggle(R.string.lab_remove_library, s.removeLibrary, "scope-library") { send(DemoAction.ScopeLibrary) }
                        LabeledToggle(R.string.lab_remove_downloads, s.removeDownloads, "scope-downloads") { send(DemoAction.ScopeDownloads) }
                    }
                }
            },
            confirmButton = {
                LabButton(R.string.lab_confirm, "confirm-delete", s.category != 17 || s.canDelete) {
                    when (s.category) {
                        17 -> send(DemoAction.ConfirmDelete)
                        31 -> send(DemoAction.Unbind)
                        else -> { send(DemoAction.Event("confirmed-fixture")); send(DemoAction.Modal(Overlay.NONE)) }
                    }
                }
            },
            dismissButton = { LabButton(R.string.lab_cancel, "dialog-cancel", onClick = onBack) },
        )
        Overlay.TRUST -> AlertDialog(
            onDismissRequest = onBack,
            title = { Text(stringResource(R.string.lab_trust)) },
            text = { Text(stringResource(R.string.lab_trust_body)) },
            confirmButton = { LabButton(R.string.lab_confirm, "trust-confirm") { send(DemoAction.Trust) } },
            dismissButton = { LabButton(R.string.lab_cancel, "trust-cancel", onClick = onBack) },
        )
        Overlay.PICKER -> AlertDialog(
            onDismissRequest = onBack,
            title = { Text(stringResource(R.string.lab_choose_dir)) },
            text = { Text(stringResource(R.string.lab_storage_notice)) },
            confirmButton = { LabButton(R.string.lab_confirm, "picker-confirm") { send(DemoAction.ChooseDirectory) } },
            dismissButton = { LabButton(R.string.lab_cancel, "picker-cancel", onClick = onBack) },
        )
    }
}
