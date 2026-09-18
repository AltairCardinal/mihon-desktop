package eu.kanade.presentation.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.extension.permission.InstalledAppsPermissionState
import eu.kanade.tachiyomi.extension.permission.InstalledAppsPermissionStatus
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

internal val InstalledAppsPermissionState.hasNotice: Boolean
    get() = status in setOf(
        InstalledAppsPermissionStatus.CHECKING,
        InstalledAppsPermissionStatus.DENIED,
        InstalledAppsPermissionStatus.UNKNOWN,
    ) || isRefreshing || scanFailed

@Composable
fun InstalledAppsPermissionNotice(
    state: InstalledAppsPermissionState,
    settingsUnavailable: Boolean,
    onGetPermission: () -> Unit,
    onRetry: () -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth().padding(16.dp).testTag("installed-apps-permission-notice")) {
        Column(
            Modifier.padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                state.status == InstalledAppsPermissionStatus.DENIED -> {
                    Text(
                        stringResource(MR.strings.installed_apps_permission_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(stringResource(MR.strings.installed_apps_permission_required))
                    Text(stringResource(MR.strings.installed_apps_permission_local_available))
                    Button(onClick = onGetPermission, enabled = !state.isRefreshing) {
                        Text(stringResource(MR.strings.installed_apps_permission_get))
                    }
                    if (state.scanFailed) {
                        Text(stringResource(MR.strings.installed_apps_permission_private_read_failed))
                        TextButton(onClick = onRetry, enabled = !state.isRefreshing) {
                            Text(stringResource(MR.strings.action_retry))
                        }
                    }
                }
                state.status == InstalledAppsPermissionStatus.UNKNOWN -> {
                    Text(stringResource(MR.strings.installed_apps_permission_check_failed))
                    TextButton(onClick = onRetry, enabled = !state.isRefreshing) {
                        Text(stringResource(MR.strings.action_retry))
                    }
                }
                state.scanFailed -> {
                    Text(stringResource(MR.strings.installed_apps_permission_read_failed))
                    TextButton(onClick = onRetry, enabled = !state.isRefreshing) {
                        Text(stringResource(MR.strings.action_retry))
                    }
                }
                state.status == InstalledAppsPermissionStatus.CHECKING || state.isRefreshing -> {
                    CircularProgressIndicator()
                    Text(
                        stringResource(
                            if (state.status == InstalledAppsPermissionStatus.CHECKING) {
                                MR.strings.installed_apps_permission_checking
                            } else {
                                MR.strings.installed_apps_permission_reading
                            },
                        ),
                    )
                }
            }
            if (settingsUnavailable) {
                Text(stringResource(MR.strings.installed_apps_permission_settings_unavailable))
                TextButton(onClick = onRetry) { Text(stringResource(MR.strings.action_retry)) }
            }
        }
    }
}

@Composable
fun InstalledAppsPermissionDialog(
    onGetPermission: () -> Unit,
    onDismiss: () -> Unit,
    settingsRequired: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(MR.strings.installed_apps_permission_name)) },
        text = {
            Text(
                stringResource(
                    if (settingsRequired) {
                        MR.strings.installed_apps_permission_settings_required
                    } else {
                        MR.strings.installed_apps_permission_explanation
                    },
                ),
            )
        },
        confirmButton = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onGetPermission, modifier = Modifier.fillMaxWidth().testTag("permission-dialog-get")) {
                    Text(
                        stringResource(
                            if (settingsRequired) {
                                MR.strings.installed_apps_permission_open_settings
                            } else {
                                MR.strings.installed_apps_permission_get
                            },
                        ),
                    )
                }
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().testTag("permission-dialog-back"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                ) {
                    Text(stringResource(MR.strings.installed_apps_permission_back))
                }
            }
        },
    )
}
