package mihon.desktop.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ListItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import tachiyomi.i18n.MR

private val spaceActivationRoles = setOf(Role.Button, Role.Checkbox, Role.RadioButton, Role.Switch)

internal fun Modifier.desktopSettingsEnterKey(action: () -> Unit): Modifier = onPreviewKeyEvent { event ->
    if (event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter)) {
        action()
        true
    } else {
        false
    }
}

internal fun Modifier.desktopSettingsAction(
    role: Role,
    onClick: () -> Unit,
): Modifier = desktopSettingsActivationKeys(role = role, onClick = onClick)
    .clickable(role = role, onClick = onClick)

internal fun Modifier.desktopSettingsActivationKeys(
    role: Role,
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier = onPreviewKeyEvent { event ->
    val activates = event.key == Key.Enter ||
        event.key == Key.NumPadEnter ||
        (event.key == Key.Spacebar && role in spaceActivationRoles)
    if (enabled && event.type == KeyEventType.KeyDown && activates) {
        onClick()
        true
    } else {
        false
    }
}

@Composable
internal fun RadioSettingsItem(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = if (selected) MR.strings.selected.localized() else MR.strings.not_selected.localized()
    ListItem(
        headlineContent = { Text(title) },
        leadingContent = {
            RadioButton(
                selected = selected,
                onClick = null,
                modifier = Modifier.clearAndSetSemantics {},
            )
        },
        modifier = modifier
            .semantics(mergeDescendants = true) { stateDescription = description }
            .desktopSettingsActivationKeys(role = Role.RadioButton, onClick = onClick)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
    )
}

@Composable
internal fun SwitchSettingsItem(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val description = if (checked) MR.strings.on.localized() else MR.strings.off.localized()
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = {
            Switch(
                checked = checked,
                enabled = enabled,
                onCheckedChange = null,
                modifier = Modifier.clearAndSetSemantics {},
            )
        },
        modifier = modifier
            .semantics(mergeDescendants = true) { stateDescription = description }
            .desktopSettingsActivationKeys(role = Role.Switch, enabled = enabled) { onCheckedChange(!checked) }
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            ),
    )
}
