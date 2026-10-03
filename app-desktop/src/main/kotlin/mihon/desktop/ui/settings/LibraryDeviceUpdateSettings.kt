package mihon.desktop.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mihon.desktop.platform.DesktopDeviceConditions
import mihon.desktop.platform.DeviceCondition
import mihon.desktop.platform.DeviceConditionState
import mihon.desktop.platform.DeviceConditionsSnapshot
import mihon.desktop.settings.saveDesktopPreference
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.i18n.MR

@Composable
internal fun LibraryDeviceUpdateSettings(preferences: LibraryPreferences, port: DesktopDeviceConditions) {
    if (port.supported.isEmpty()) return
    val preference = remember(preferences) { preferences.autoUpdateDeviceRestrictions() }
    val selected by preference.changes().collectAsState(initial = preference.get())
    var failed by remember { mutableStateOf(false) }
    var snapshot by remember(port) { mutableStateOf(DeviceConditionsSnapshot()) }
    LaunchedEffect(port) { snapshot = withContext(Dispatchers.IO) { port.query() } }
    val title = MR.strings.pref_library_update_restriction.localized()
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.desktopSettingsAnchor(title).padding(horizontal = 16.dp, vertical = 8.dp),
    )
    port.supported.forEach { condition ->
        val label = when (condition) {
            DeviceCondition.WIFI -> MR.strings.connected_to_wifi
            DeviceCondition.UNMETERED -> MR.strings.network_not_metered
            DeviceCondition.EXTERNAL_POWER -> MR.strings.desktop_device_external_power
        }.localized()
        CheckboxSettingsRow(
            label,
            condition.preferenceKey in selected,
            modifier = Modifier.desktopSettingsAnchor(label),
            onCheckedChange = { checked ->
                val current = preference.get()
                failed = !saveDesktopPreference(
                    preference,
                    if (checked) current + condition.preferenceKey else current - condition.preferenceKey,
                )
            },
        )
        if (condition.preferenceKey in selected && snapshot.state(condition) != DeviceConditionState.SATISFIED) {
            Text(
                (
                    if (snapshot.state(condition) == DeviceConditionState.UNKNOWN) {
                        MR.strings.desktop_device_condition_unknown
                    } else {
                        MR.strings.desktop_device_condition_unmet
                    }
                    ).localized(),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
    Text(
        MR.strings.desktop_device_conditions_summary.localized(),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    if (failed) Text(MR.strings.internal_error.localized(), color = MaterialTheme.colorScheme.error)
}
