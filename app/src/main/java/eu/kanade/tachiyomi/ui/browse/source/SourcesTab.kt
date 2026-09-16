package eu.kanade.tachiyomi.ui.browse.source

import android.content.ActivityNotFoundException
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.browse.InstalledAppsPermissionDialog
import eu.kanade.presentation.browse.SourceOptionsDialog
import eu.kanade.presentation.browse.SourcesScreen
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.TabContent
import eu.kanade.tachiyomi.extension.permission.AndroidInstalledAppsPermissionDetector
import eu.kanade.tachiyomi.extension.permission.InstalledAppsPermissionStatus
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreen
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchScreen
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun Screen.sourcesTab(screenModel: SourcesScreenModel = rememberScreenModel { SourcesScreenModel() }): TabContent {
    val navigator = LocalNavigator.currentOrThrow
    val state by screenModel.state.collectAsState()

    return TabContent(
        titleRes = MR.strings.label_sources,
        actions = persistentListOf(
            AppBar.Action(
                title = stringResource(MR.strings.action_global_search),
                icon = Icons.Outlined.TravelExplore,
                onClick = { navigator.push(GlobalSearchScreen()) },
            ),
            AppBar.Action(
                title = stringResource(MR.strings.action_filter),
                icon = Icons.Outlined.FilterList,
                onClick = { navigator.push(SourcesFilterScreen()) },
            ),
        ),
        content = { contentPadding, snackbarHostState ->
            var showPermissionDialog by rememberSaveable { mutableStateOf(false) }
            var permissionNeedsSettings by rememberSaveable { mutableStateOf(false) }
            val activity = LocalActivity.current
            val installedAppsPermission = AndroidInstalledAppsPermissionDetector.PERMISSION
            LaunchedEffect(state.installedAppsPermission.status) {
                if (state.installedAppsPermission.status == InstalledAppsPermissionStatus.GRANTED ||
                    state.installedAppsPermission.status == InstalledAppsPermissionStatus.NOT_REQUIRED
                ) {
                    permissionNeedsSettings = false
                    showPermissionDialog = false
                }
            }
            val runtimePermissionLauncher =
                rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                    screenModel.refreshInstalledAppsPermission()
                    if (!granted && activity?.shouldShowRequestPermissionRationale(installedAppsPermission) != true) {
                        // A suppressed request needs settings; never navigate there without another explicit action.
                        permissionNeedsSettings = true
                        showPermissionDialog = true
                    }
                }
            val permissionLauncher =
                rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                    screenModel.refreshInstalledAppsPermission()
                }
            if (showPermissionDialog) {
                InstalledAppsPermissionDialog(
                    settingsRequired = permissionNeedsSettings,
                    onDismiss = { showPermissionDialog = false },
                    onGetPermission = {
                        showPermissionDialog = false
                        if (!permissionNeedsSettings) {
                            try {
                                runtimePermissionLauncher.launch(installedAppsPermission)
                            } catch (_: ActivityNotFoundException) {
                                permissionNeedsSettings = true
                                showPermissionDialog = true
                            } catch (_: SecurityException) {
                                permissionNeedsSettings = true
                                showPermissionDialog = true
                            }
                            return@InstalledAppsPermissionDialog
                        }
                        var launched = false
                        for (fallback in listOf(false, true)) {
                            try {
                                val intent = screenModel.permissionSettingsIntent(fallback) ?: continue
                                permissionLauncher.launch(intent)
                                launched = true
                                break
                            } catch (_: ActivityNotFoundException) {
                                // Some device-specific permission activities are not exported.
                            } catch (_: SecurityException) {
                                // Fall back to the application's ordinary settings page.
                            }
                        }
                        if (!launched) screenModel.permissionSettingsUnavailable()
                    },
                )
            }
            SourcesScreen(
                state = state,
                contentPadding = contentPadding,
                onClickItem = { source, listing ->
                    navigator.push(BrowseSourceScreen(source.id, listing.query))
                },
                onClickPin = screenModel::togglePin,
                onLongClickItem = screenModel::showSourceDialog,
                onGetInstalledAppsPermission = { showPermissionDialog = true },
                onRetryInstalledAppsPermission = screenModel::refreshInstalledAppsPermission,
            )

            state.dialog?.let { dialog ->
                val source = dialog.source
                SourceOptionsDialog(
                    source = source,
                    onClickPin = {
                        screenModel.togglePin(source)
                        screenModel.closeDialog()
                    },
                    onClickDisable = {
                        screenModel.toggleSource(source)
                        screenModel.closeDialog()
                    },
                    onDismiss = screenModel::closeDialog,
                )
            }

            val internalErrString = stringResource(MR.strings.internal_error)
            LaunchedEffect(Unit) {
                screenModel.events.collectLatest { event ->
                    when (event) {
                        SourcesScreenModel.Event.FailedFetchingSources -> {
                            launch { snackbarHostState.showSnackbar(internalErrString) }
                        }
                    }
                }
            }
        },
    )
}
