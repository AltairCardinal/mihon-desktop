package mihon.desktop.ui.settings

import mihon.desktop.LocalDesktopUiDependencies

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import mihon.desktop.backup.AutoBackupInterval
import mihon.desktop.settings.DesktopAppPreferences
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import kotlinx.coroutines.launch
import mihon.desktop.backup.BackupPreview
import mihon.desktop.platform.DesktopBackupFilePickerRequest
import mihon.desktop.platform.DesktopBackupFilePickerResult
import mihon.desktop.platform.DesktopExternalActionTarget
import mihon.domain.error.AppError
import tachiyomi.i18n.MR
import java.io.File
import java.util.Locale

data class BackupSettingsScreen(val initialBackup: File? = null) : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val snackbar = remember { SnackbarHostState() }

        val appPrefs = LocalDesktopUiDependencies.current.appPreferences
        var isBusy by remember { mutableStateOf(false) }
        val autoBackupInterval by appPrefs.autoBackupInterval.changes()
            .collectAsState(initial = appPrefs.autoBackupInterval.get())
        val autoBackupMaxFiles by appPrefs.autoBackupMaxFiles.changes()
            .collectAsState(initial = appPrefs.autoBackupMaxFiles.get())

        val backupFactory = LocalDesktopUiDependencies.current.backupRestoreScreenModelFactory
        val backupFilePicker = LocalDesktopUiDependencies.current.backupFilePicker
        val createTitle = DesktopSettingsAnchorResources.createBackup.localized()
        val restoreTitle = DesktopSettingsAnchorResources.restoreBackup.localized()

        val restoreModel = if (initialBackup == null) {
            rememberScreenModel { backupFactory.create() }
        } else {
            rememberScreenModel { backupFactory.create(DesktopExternalActionTarget.Backup(initialBackup)) }
        }
        val restoreState by restoreModel.state.collectAsState()

        (restoreState as? BackupRestoreUiState.Preview)?.let { preview ->
            AlertDialog(
                onDismissRequest = restoreModel::cancel,
                title = { Text(MR.strings.desktop_backup_restore_confirm_title.localized()) },
                text = {
                    Column {
                        Text(MR.strings.desktop_backup_restore_confirm_summary.localized())
                        Spacer(Modifier.height(8.dp))
                        BackupPreviewText(preview.summary)
                    }
                },
                confirmButton = {
                    TextButton(onClick = restoreModel::confirmRestore) { Text(MR.strings.action_restore.localized()) }
                },
                dismissButton = { TextButton(onClick = restoreModel::cancel) { Text(MR.strings.action_cancel.localized()) } },
            )
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(MR.strings.label_backup.localized()) },
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
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            DesktopSettingsAnchorColumn(
                route = this@BackupSettingsScreen,
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            ) {
                Text(createTitle, style = MaterialTheme.typography.titleMedium, modifier = Modifier.desktopSettingsAnchor(createTitle))
                Spacer(Modifier.height(4.dp))
                Text(
                    text = MR.strings.desktop_backup_create_summary.localized(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        scope.launch {
                            val request = DesktopBackupFilePickerRequest.Directory(
                                backupPresentationText(BackupPresentationText.DirectoryChooserTitle),
                            )
                            val dir = when (val result = backupFilePicker.choose(request)) {
                                DesktopBackupFilePickerResult.Cancelled -> {
                                    snackbar.showSnackbar(backupPresentationText(BackupPresentationText.CreateCancelled))
                                    return@launch
                                }
                                is DesktopBackupFilePickerResult.Selected -> result.file
                            }
                            isBusy = true
                            try {
                                val file = backupFactory.createBackup(dir)
                                snackbar.showSnackbar(backupPresentationText(BackupPresentationText.Created(file.name)))
                            } catch (e: Exception) {
                                snackbar.showSnackbar(
                                    backupPresentationText(BackupPresentationText.CreationFailed(e.message.orEmpty())),
                                )
                            } finally {
                                isBusy = false
                            }
                        }
                    },
                    enabled = !isBusy,
                ) {
                    if (isBusy) {
                        CircularProgressIndicator()
                    } else {
                        Text(MR.strings.pref_create_backup.localized())
                    }
                }

                Spacer(Modifier.height(32.dp))
                Text(restoreTitle, style = MaterialTheme.typography.titleMedium, modifier = Modifier.desktopSettingsAnchor(restoreTitle))
                Spacer(Modifier.height(4.dp))
                Text(
                    text = MR.strings.desktop_backup_restore_summary.localized(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val request = DesktopBackupFilePickerRequest.BackupFile(
                                title = backupPresentationText(BackupPresentationText.FileChooserTitle),
                                description = backupPresentationText(BackupPresentationText.FileFilter),
                                extensions = setOf("tachibk"),
                            )
                            val file = when (val result = backupFilePicker.choose(request)) {
                                DesktopBackupFilePickerResult.Cancelled -> {
                                    snackbar.showSnackbar(backupPresentationText(BackupPresentationText.RestoreSelectionCancelled))
                                    return@launch
                                }
                                is DesktopBackupFilePickerResult.Selected -> result.file
                            }
                            restoreModel.select(file)
                        }
                    },
                    enabled = !isBusy && restoreState !is BackupRestoreUiState.Loading &&
                        restoreState !is BackupRestoreUiState.Restoring,
                ) {
                    Text(MR.strings.file_select_backup.localized())
                }
                Spacer(Modifier.height(12.dp))
                RestoreStatus(restoreState, restoreModel::retryRestore, restoreModel::cancel)

                Spacer(Modifier.height(32.dp))
                HorizontalDivider()
                Spacer(Modifier.height(16.dp))

                Text(MR.strings.desktop_backup_automatic.localized(), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = MR.strings.desktop_backup_automatic_summary.localized(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))

                Text(
                    text = MR.strings.pref_backup_interval.localized(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                val intervalLabels = mapOf(
                    AutoBackupInterval.OFF to MR.strings.off.localized(),
                    AutoBackupInterval.EVERY_6H to MR.strings.update_6hour.localized(),
                    AutoBackupInterval.EVERY_12H to MR.strings.update_12hour.localized(),
                    AutoBackupInterval.EVERY_24H to MR.strings.update_24hour.localized(),
                    AutoBackupInterval.EVERY_48H to MR.strings.update_48hour.localized(),
                    AutoBackupInterval.WEEKLY to MR.strings.update_weekly.localized(),
                )
                AutoBackupInterval.entries.forEach { interval ->
                    RadioSettingsItem(
                        title = intervalLabels[interval] ?: interval.name,
                        selected = autoBackupInterval == interval.name,
                        onClick = { appPrefs.autoBackupInterval.set(interval.name) },
                    )
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    text = MR.strings.desktop_backup_max_files.localized(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                listOf(1, 2, 3, 4, 5).forEach { count ->
                    RadioSettingsItem(
                        title = "$count",
                        selected = autoBackupMaxFiles == count,
                        onClick = { appPrefs.autoBackupMaxFiles.set(count) },
                    )
                }
            }
        }
    }

}

internal sealed interface BackupPresentationText {
    data object DirectoryChooserTitle : BackupPresentationText
    data object FileChooserTitle : BackupPresentationText
    data object FileFilter : BackupPresentationText
    data object CreateCancelled : BackupPresentationText
    data object RestoreSelectionCancelled : BackupPresentationText
    data class Created(val fileName: String) : BackupPresentationText
    data class CreationFailed(val detail: String) : BackupPresentationText
    data class RestoreErrorCount(val count: Int) : BackupPresentationText
    data class RestoreErrorItem(val detail: String) : BackupPresentationText
    data class MoreErrors(val count: Int) : BackupPresentationText
    data class RestoreFailure(val reason: BackupRestoreFailureReason) : BackupPresentationText
}

internal fun backupPresentationText(
    text: BackupPresentationText,
    locale: Locale = Locale.getDefault(),
): String = when (text) {
    BackupPresentationText.DirectoryChooserTitle -> MR.strings.onboarding_storage_action_select.localized(locale)
    BackupPresentationText.FileChooserTitle -> MR.strings.file_select_backup.localized(locale)
    BackupPresentationText.FileFilter -> MR.strings.desktop_backup_file_filter.localized(locale)
    BackupPresentationText.CreateCancelled -> MR.strings.desktop_backup_create_cancelled.localized(locale)
    BackupPresentationText.RestoreSelectionCancelled -> MR.strings.desktop_backup_restore_selection_cancelled.localized(locale)
    is BackupPresentationText.Created -> MR.strings.desktop_backup_saved.localized(locale, text.fileName)
    is BackupPresentationText.CreationFailed -> if (text.detail.isBlank()) {
        MR.strings.creating_backup_error.localized(locale)
    } else {
        MR.strings.desktop_backup_failed.localized(locale, text.detail)
    }
    is BackupPresentationText.RestoreErrorCount -> MR.strings.desktop_backup_restore_error_count.localized(locale, text.count)
    is BackupPresentationText.RestoreErrorItem -> MR.strings.desktop_backup_error_item.localized(locale, text.detail)
    is BackupPresentationText.MoreErrors -> MR.strings.desktop_backup_more_errors.localized(locale, text.count)
    is BackupPresentationText.RestoreFailure -> backupRestoreFailureText(text.reason, locale)
}

internal data class BackupPartialFailurePresentation(
    val summary: String,
    val details: List<String>,
)

internal fun backupPartialFailurePresentation(
    error: AppError.PartialFailure,
    locale: Locale = Locale.getDefault(),
): BackupPartialFailurePresentation {
    val details = if (error.failedUnits.isNotEmpty()) {
        error.failedUnits.map { unit ->
            MR.strings.desktop_backup_failed_unit.localized(locale, unit.unitId, backupAppErrorText(unit.error, locale))
        }
    } else {
        error.failures.map { backupAppErrorText(it, locale) }
    }
    return BackupPartialFailurePresentation(
        summary = MR.strings.desktop_backup_partial.localized(locale, details.size),
        details = details,
    )
}

private fun backupRestoreFailureText(reason: BackupRestoreFailureReason, locale: Locale): String = when (reason) {
    BackupRestoreFailureReason.EmptyBackup -> MR.strings.invalid_backup_file_missing_manga.localized(locale)
    BackupRestoreFailureReason.UnsupportedVersion -> MR.strings.desktop_backup_unsupported_version.localized(locale)
    BackupRestoreFailureReason.EmptyFile -> MR.strings.desktop_backup_empty_file.localized(locale)
    BackupRestoreFailureReason.MissingData -> MR.strings.desktop_backup_missing_data.localized(locale)
    BackupRestoreFailureReason.Corrupted -> MR.strings.invalid_backup_file_unknown.localized(locale)
    BackupRestoreFailureReason.RestoreNotStarted -> MR.strings.desktop_backup_restore_not_started.localized(locale)
    is BackupRestoreFailureReason.Restore -> backupAppErrorText(reason.error, locale)
}

private fun backupAppErrorText(error: AppError, locale: Locale): String = when (error) {
    is AppError.Storage -> MR.strings.desktop_backup_restore_storage_error.localized(locale)
    is AppError.Permission -> MR.strings.desktop_backup_restore_permission_error.localized(locale)
    is AppError.MalformedData -> MR.strings.desktop_backup_restore_malformed_error.localized(locale)
    AppError.Cancelled -> MR.strings.restoring_backup_canceled.localized(locale)
    is AppError.PartialFailure -> MR.strings.desktop_backup_partial.localized(
        locale,
        if (error.failedUnits.isNotEmpty()) error.failedUnits.size else error.failures.size,
    )
    else -> MR.strings.desktop_backup_restore_unknown_error.localized(locale)
}

@Composable
private fun BackupPreviewText(preview: BackupPreview) {
    val locale = Locale.getDefault()
    Text(MR.strings.desktop_backup_preview_library.localized(locale, preview.mangaCount, preview.chapterCount, preview.categoryCount))
    Text(MR.strings.desktop_backup_preview_services.localized(locale, preview.trackingCount, preview.preferenceCount, preview.sourceCount))
    Text(MR.strings.desktop_backup_preview_repositories.localized(locale, preview.extensionRepoCount))
}

@Composable
private fun RestoreStatus(
    state: BackupRestoreUiState,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
) {
    when (state) {
        BackupRestoreUiState.Empty -> Text(MR.strings.file_select_backup.localized(), style = MaterialTheme.typography.bodySmall)
        is BackupRestoreUiState.Loading -> {
            CircularProgressIndicator()
            Text(MR.strings.desktop_backup_reading_file.localized(Locale.getDefault(), state.fileName))
        }
        is BackupRestoreUiState.Preview -> BackupPreviewText(state.summary)
        is BackupRestoreUiState.Restoring -> {
            CircularProgressIndicator(progress = { state.progress })
            Text(MR.strings.restoring_backup.localized())
            Text(state.fileName)
            Text(
                MR.strings.desktop_backup_progress.localized(
                    Locale.getDefault(),
                    state.completed,
                    state.total,
                    (state.progress * 100).toInt(),
                ),
            )
            TextButton(onClick = onCancel) { Text(MR.strings.action_cancel.localized()) }
        }
        is BackupRestoreUiState.Completed -> Text(
            MR.strings.desktop_backup_completed_count.localized(Locale.getDefault(), state.restoredItems),
        )
        is BackupRestoreUiState.PartialSuccess -> {
            val presentation = backupPartialFailurePresentation(state.error)
            Text(presentation.summary)
            presentation.details.take(10).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (presentation.details.size > 10) {
                Text(
                    backupPresentationText(BackupPresentationText.MoreErrors(presentation.details.size - 10)),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        is BackupRestoreUiState.Failure -> {
            Text(MR.strings.restoring_backup_error.localized(), color = MaterialTheme.colorScheme.error)
            Text(
                backupPresentationText(BackupPresentationText.RestoreFailure(state.reason)),
                color = MaterialTheme.colorScheme.error,
            )
            if (state.recoverable) TextButton(onClick = onRetry) { Text(MR.strings.action_retry.localized()) }
        }
        BackupRestoreUiState.Cancelled -> Text(MR.strings.restoring_backup_canceled.localized())
    }
}
