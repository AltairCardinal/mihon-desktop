package mihon.desktop.ui.settings

import mihon.desktop.LocalDesktopUiDependencies

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.desktop.platform.DesktopDownloadDirectoryAvailability
import mihon.desktop.platform.DesktopDownloadDirectoryProbeResult
import mihon.desktop.platform.DesktopDownloadDirectorySelection
import mihon.desktop.platform.DesktopDownloadDirectoryState
import mihon.desktop.platform.DesktopFilePickerRequest
import mihon.desktop.platform.DesktopFilePickerResult
import tachiyomi.i18n.MR
import java.io.File
import java.util.Locale

class DownloadSettingsScreen : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val dependencies = LocalDesktopUiDependencies.current
        val prefs = dependencies.downloadPreferences
        val directoryController = dependencies.downloadDirectoryController
        val scope = rememberCoroutineScope()
        var directoryState by remember(directoryController, dependencies.downloadDirectoryState) {
            mutableStateOf(directoryController.currentState())
        }
        var directoryFeedback by remember { mutableStateOf<DownloadDirectoryFeedback?>(null) }
        var directoryInspections by remember(directoryController) {
            mutableStateOf<Map<File, DesktopDownloadDirectoryProbeResult>>(emptyMap())
        }
        val downloadQueue by dependencies.downloadQueuePort.queue.collectAsState()

        LaunchedEffect(directoryController) {
            val roots = listOf(directoryState.activeDirectory, directoryState.pendingDirectory).distinct()
            directoryInspections = withContext(Dispatchers.IO) {
                roots.associateWith(directoryController::inspectDirectory)
            }
        }

        val downloadAsCbz by prefs.downloadAsCbz.changes().collectAsState(initial = prefs.downloadAsCbz.get())
        val autoDownload by prefs.autoDownloadNewChapters.changes().collectAsState(initial = prefs.autoDownloadNewChapters.get())
        val deleteAfterRead by prefs.deleteAfterRead.changes().collectAsState(initial = prefs.deleteAfterRead.get())
        val parallelLimit by prefs.parallelDownloadLimit.changes().collectAsState(initial = prefs.parallelDownloadLimit.get())
        val cbzTitle = DesktopSettingsAnchorResources.downloadAsCbz.localized()
        val downloadNewTitle = DesktopSettingsAnchorResources.downloadNew.localized()
        val directoryTitle = DesktopSettingsAnchorResources.downloadDirectory.localized()
        val selectDirectory: () -> Unit = {
            scope.launch {
                when (
                    val pickerResult = dependencies.filePicker.choose(
                        DesktopFilePickerRequest.Directory(
                            title = MR.strings.desktop_download_directory_select.localized(),
                            initialDirectory = directoryState.configuredDirectory ?: directoryState.defaultDirectory,
                        ),
                    )
                ) {
                    DesktopFilePickerResult.Cancelled -> Unit
                    is DesktopFilePickerResult.Failed -> {
                        directoryFeedback = DownloadDirectoryFeedback.PickerFailed(pickerResult.error)
                    }
                    is DesktopFilePickerResult.Selected -> {
                        val selection = withContext(Dispatchers.IO) {
                            directoryController.selectDirectory(pickerResult.file)
                        }
                        directoryState = directoryController.currentState()
                        directoryFeedback = when (selection) {
                            is DesktopDownloadDirectorySelection.Invalid ->
                                DownloadDirectoryFeedback.SelectionFailed(selection.availability)
                            is DesktopDownloadDirectorySelection.ValidCustom -> DownloadDirectoryFeedback.Saved
                            is DesktopDownloadDirectorySelection.UseDefault -> DownloadDirectoryFeedback.Restored
                        }
                    }
                }
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(MR.strings.pref_category_downloads.localized()) },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = MR.strings.action_bar_up_description.localized(),
                            )
                        }
                    },
                )
            },
        ) { padding ->
            DesktopSettingsAnchorColumn(
                route = this@DownloadSettingsScreen,
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                DownloadDirectorySettingsSection(
                    title = directoryTitle,
                    state = directoryState,
                    inspections = directoryInspections,
                    hasQueuedDownloads = downloadQueue.isNotEmpty(),
                    feedback = directoryFeedback,
                    onSelect = selectDirectory,
                    onOpen = {
                        scope.launch {
                            directoryFeedback = when (
                                withContext(Dispatchers.IO) {
                                    dependencies.downloadDirectoryOpener.open(directoryState.activeDirectory)
                                }
                            ) {
                                DesktopDirectoryOpenResult.Opened -> DownloadDirectoryFeedback.Opened
                                DesktopDirectoryOpenResult.Rejected -> DownloadDirectoryFeedback.OpenRejected
                                is DesktopDirectoryOpenResult.Failed -> DownloadDirectoryFeedback.OpenFailed
                            }
                        }
                    },
                    onRestoreDefault = {
                        scope.launch {
                            withContext(Dispatchers.IO) { directoryController.restoreDefault() }
                            directoryState = directoryController.currentState()
                            directoryFeedback = DownloadDirectoryFeedback.Restored
                        }
                    },
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                SwitchSettingsItem(
                    title = cbzTitle,
                    subtitle = MR.strings.desktop_download_cbz_summary.localized(),
                    checked = downloadAsCbz,
                    onCheckedChange = { prefs.downloadAsCbz.set(it) },
                    modifier = Modifier.desktopSettingsAnchor(cbzTitle),
                )
                SwitchSettingsItem(
                    title = downloadNewTitle,
                    subtitle = MR.strings.desktop_download_new_chapters_summary.localized(),
                    checked = autoDownload,
                    onCheckedChange = { prefs.autoDownloadNewChapters.set(it) },
                    modifier = Modifier.desktopSettingsAnchor(downloadNewTitle),
                )
                SwitchSettingsItem(
                    title = MR.strings.pref_remove_after_read.localized(),
                    subtitle = MR.strings.desktop_download_delete_after_read_summary.localized(),
                    checked = deleteAfterRead,
                    onCheckedChange = { prefs.deleteAfterRead.set(it) },
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Text(
                    text = MR.strings.desktop_download_parallel.localized(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                (1..5).forEach { limit ->
                    RadioSettingsItem(
                        title = if (limit == 1) MR.strings.desktop_download_sequential.localized() else "$limit",
                        selected = parallelLimit == limit,
                        onClick = { prefs.parallelDownloadLimit.set(limit) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadDirectorySettingsSection(
    title: String,
    state: DesktopDownloadDirectoryState,
    inspections: Map<File, DesktopDownloadDirectoryProbeResult>,
    hasQueuedDownloads: Boolean,
    feedback: DownloadDirectoryFeedback?,
    onSelect: () -> Unit,
    onOpen: () -> Unit,
    onRestoreDefault: () -> Unit,
) {
    val locale = Locale.getDefault()
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Column {
                Text(MR.strings.desktop_download_directory_current.localized(locale, state.activeDirectory.path))
                Text(MR.strings.desktop_download_directory_default.localized(locale, state.defaultDirectory.path))
                if (state.restartRequired || state.pendingDirectory != state.activeDirectory) {
                    Text(MR.strings.desktop_download_directory_next.localized(locale, state.pendingDirectory.path))
                }
            }
        },
        trailingContent = { Text(MR.strings.desktop_download_directory_select.localized()) },
        modifier = Modifier
            .fillMaxWidth()
            .desktopSettingsAnchor(title)
            .desktopSettingsAction(Role.Button, onSelect),
    )
    inspections[state.activeDirectory]
        ?.takeIf(DesktopDownloadDirectoryProbeResult::isUnavailable)
        ?.let { result ->
            DownloadDirectoryAvailabilityWarning(
                MR.strings.desktop_download_directory_current_unavailable.localized(
                    locale,
                    downloadDirectoryAvailabilityText(result.availability),
                ),
            )
        }
    if (state.pendingDirectory != state.activeDirectory) {
        inspections[state.pendingDirectory]
            ?.takeIf(DesktopDownloadDirectoryProbeResult::isUnavailable)
            ?.let { result ->
                DownloadDirectoryAvailabilityWarning(
                    MR.strings.desktop_download_directory_next_unavailable.localized(
                        locale,
                        downloadDirectoryAvailabilityText(result.availability),
                    ),
                )
            }
    }
    if (state.restartRequired) {
        Text(
            MR.strings.desktop_download_directory_restart_required.localized(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        Text(
            MR.strings.desktop_download_directory_no_migration.localized(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
    if (hasQueuedDownloads) {
        Text(
            MR.strings.desktop_download_directory_queue_warning.localized(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
    Spacer(Modifier.height(8.dp))
    Row(modifier = Modifier.padding(horizontal = 16.dp)) {
        DesktopSettingsButton(onClick = onOpen, outlined = true) {
            Text(MR.strings.desktop_download_directory_open.localized())
        }
        Spacer(Modifier.padding(horizontal = 4.dp))
        DesktopSettingsButton(onClick = onRestoreDefault, outlined = true) {
            Text(MR.strings.desktop_download_directory_restore_default.localized())
        }
    }
    feedback?.let {
        Text(
            text = downloadDirectoryFeedbackText(it),
            style = MaterialTheme.typography.bodySmall,
            color = when (it) {
                DownloadDirectoryFeedback.Saved,
                DownloadDirectoryFeedback.Restored,
                DownloadDirectoryFeedback.Opened,
                -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.error
            },
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

@Composable
private fun DownloadDirectoryAvailabilityWarning(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

private fun DesktopDownloadDirectoryProbeResult.isUnavailable(): Boolean =
    availability != DesktopDownloadDirectoryAvailability.AVAILABLE &&
        availability != DesktopDownloadDirectoryAvailability.UNKNOWN

private sealed interface DownloadDirectoryFeedback {
    data object Saved : DownloadDirectoryFeedback
    data object Restored : DownloadDirectoryFeedback
    data object Opened : DownloadDirectoryFeedback
    data class PickerFailed(val error: Throwable) : DownloadDirectoryFeedback
    data class SelectionFailed(val availability: DesktopDownloadDirectoryAvailability) : DownloadDirectoryFeedback
    data object OpenRejected : DownloadDirectoryFeedback
    data object OpenFailed : DownloadDirectoryFeedback
}

private fun downloadDirectoryFeedbackText(feedback: DownloadDirectoryFeedback): String = when (feedback) {
    DownloadDirectoryFeedback.Saved -> MR.strings.desktop_download_directory_saved.localized()
    DownloadDirectoryFeedback.Restored -> MR.strings.desktop_download_directory_default_restored.localized()
    DownloadDirectoryFeedback.Opened -> MR.strings.desktop_download_directory_opened.localized()
    is DownloadDirectoryFeedback.PickerFailed -> MR.strings.desktop_download_directory_picker_failed.localized()
    is DownloadDirectoryFeedback.SelectionFailed -> downloadDirectoryAvailabilityText(feedback.availability)
    DownloadDirectoryFeedback.OpenRejected -> MR.strings.desktop_download_directory_open_rejected.localized()
    DownloadDirectoryFeedback.OpenFailed -> MR.strings.desktop_download_directory_open_failed.localized()
}

private fun downloadDirectoryAvailabilityText(availability: DesktopDownloadDirectoryAvailability): String =
    when (availability) {
        DesktopDownloadDirectoryAvailability.INVALID_SYNTAX ->
            MR.strings.desktop_download_directory_invalid_syntax.localized()
        DesktopDownloadDirectoryAvailability.MISSING -> MR.strings.desktop_download_directory_missing.localized()
        DesktopDownloadDirectoryAvailability.NOT_DIRECTORY -> MR.strings.desktop_download_directory_not_directory.localized()
        DesktopDownloadDirectoryAvailability.NOT_WRITABLE -> MR.strings.desktop_download_directory_not_writable.localized()
        DesktopDownloadDirectoryAvailability.UNKNOWN,
        DesktopDownloadDirectoryAvailability.AVAILABLE,
        -> MR.strings.desktop_download_directory_picker_failed.localized()
    }
