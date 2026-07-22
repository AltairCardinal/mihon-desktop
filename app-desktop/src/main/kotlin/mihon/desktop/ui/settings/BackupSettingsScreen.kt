package mihon.desktop.ui.settings

import mihon.desktop.LocalDesktopUiDependencies

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.desktop.backup.BackupPreview
import mihon.desktop.platform.DesktopExternalActionTarget
import tachiyomi.i18n.MR
import java.io.File
import java.util.Locale
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter

data class BackupSettingsScreen(val initialBackup: File? = null) : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val snackbar = remember { SnackbarHostState() }

        val appPrefs = LocalDesktopUiDependencies.current.appPreferences
        var isBusy by remember { mutableStateOf(false) }
        var restoreErrors by remember { mutableStateOf<List<String>?>(null) }
        val autoBackupInterval by appPrefs.autoBackupInterval.changes()
            .collectAsState(initial = appPrefs.autoBackupInterval.get())
        val autoBackupMaxFiles by appPrefs.autoBackupMaxFiles.changes()
            .collectAsState(initial = appPrefs.autoBackupMaxFiles.get())

        val backupFactory = LocalDesktopUiDependencies.current.backupRestoreScreenModelFactory

        val restoreModel = if (initialBackup == null) {
            rememberScreenModel { backupFactory.create() }
        } else {
            rememberScreenModel { backupFactory.create(DesktopExternalActionTarget.Backup(initialBackup)) }
        }
        val restoreState by restoreModel.state.collectAsState()

        // ── Restore error dialog ──────────────────────────────────────────────
        restoreErrors?.let { errors ->
            AlertDialog(
                onDismissRequest = { restoreErrors = null },
                title = { Text(MR.strings.restoring_backup_error.localized()) },
                text = {
                    Column {
                        Text(errors.size.toString())
                        Spacer(Modifier.height(8.dp))
                        errors.take(10).forEach { err ->
                            Text("• $err", style = MaterialTheme.typography.bodySmall)
                        }
                        if (errors.size > 10) {
                            Text(
                                MR.strings.desktop_backup_more_errors.localized(Locale.getDefault(), errors.size - 10),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { restoreErrors = null }) { Text(MR.strings.action_ok.localized()) }
                },
            )
        }

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
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(MR.strings.pref_create_backup.localized(), style = MaterialTheme.typography.titleMedium)
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
                            val dir = chooseDirectory(MR.strings.pref_create_backup.localized()) ?: return@launch
                            isBusy = true
                            try {
                                val file = backupFactory.createBackup(dir)
                                snackbar.showSnackbar(MR.strings.desktop_backup_saved.localized(Locale.getDefault(), file.name))
                            } catch (e: Exception) {
                                snackbar.showSnackbar(
                                    MR.strings.desktop_backup_failed.localized(Locale.getDefault(), e.message.orEmpty()),
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
                Text(MR.strings.pref_restore_backup.localized(), style = MaterialTheme.typography.titleMedium)
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
                            val file = chooseBackupFile() ?: return@launch
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

    /**
     * Opens a Swing JFileChooser on the EDT and returns the selected directory,
     * or null if the user cancels.
     */
    private suspend fun chooseDirectory(title: String): File? =
        withContext(Dispatchers.IO) {
            var result: File? = null
            val latch = java.util.concurrent.CountDownLatch(1)
            SwingUtilities.invokeLater {
                val chooser = JFileChooser().apply {
                    dialogTitle = title
                    fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                    currentDirectory = File(System.getProperty("user.home"))
                }
                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                    result = chooser.selectedFile
                }
                latch.countDown()
            }
            latch.await()
            result
        }

    /**
     * Opens a Swing JFileChooser filtered for `.tachibk` files.
     * Returns the selected file, or null if cancelled.
     */
    private suspend fun chooseBackupFile(): File? =
        withContext(Dispatchers.IO) {
            var result: File? = null
            val latch = java.util.concurrent.CountDownLatch(1)
            SwingUtilities.invokeLater {
                val chooser = JFileChooser().apply {
                    dialogTitle = MR.strings.file_select_backup.localized()
                    fileFilter = FileNameExtensionFilter(MR.strings.desktop_backup_file_filter.localized(), "tachibk")
                    currentDirectory = File(System.getProperty("user.home"))
                }
                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                    result = chooser.selectedFile
                }
                latch.countDown()
            }
            latch.await()
            result
        }
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
            Text(MR.strings.desktop_backup_partial.localized())
            state.failedUnits.take(10).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        }
        is BackupRestoreUiState.Failure -> {
            Text(MR.strings.restoring_backup_error.localized(), color = MaterialTheme.colorScheme.error)
            Text(state.message, color = MaterialTheme.colorScheme.error)
            if (state.recoverable) TextButton(onClick = onRetry) { Text(MR.strings.action_retry.localized()) }
        }
        BackupRestoreUiState.Cancelled -> Text(MR.strings.restoring_backup_canceled.localized())
    }
}
