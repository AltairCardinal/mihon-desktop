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
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter

class BackupSettingsScreen : Screen {

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

        val restoreModel = rememberScreenModel { backupFactory.create() }
        val restoreState by restoreModel.state.collectAsState()

        // ── Restore error dialog ──────────────────────────────────────────────
        restoreErrors?.let { errors ->
            AlertDialog(
                onDismissRequest = { restoreErrors = null },
                title = { Text("Restore completed with errors") },
                text = {
                    Column {
                        Text("${errors.size} item(s) failed to restore:")
                        Spacer(Modifier.height(8.dp))
                        errors.take(10).forEach { err ->
                            Text("• $err", style = MaterialTheme.typography.bodySmall)
                        }
                        if (errors.size > 10) {
                            Text("…and ${errors.size - 10} more", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { restoreErrors = null }) { Text("OK") }
                },
            )
        }

        (restoreState as? BackupRestoreUiState.Preview)?.let { preview ->
            AlertDialog(
                onDismissRequest = restoreModel::cancel,
                title = { Text("确认恢复备份？") },
                text = {
                    Column {
                        Text("将把缺失数据合并到当前资料库；现有条目会保留。")
                        Spacer(Modifier.height(8.dp))
                        BackupPreviewText(preview.summary)
                    }
                },
                confirmButton = {
                    TextButton(onClick = restoreModel::confirmRestore) { Text("确认恢复") }
                },
                dismissButton = { TextButton(onClick = restoreModel::cancel) { Text("取消") } },
            )
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Backup and Restore") },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                Text("Create Backup", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Export your library, chapters, and history to a .tachibk file " +
                        "compatible with Mihon Android.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        scope.launch {
                            val dir = chooseDirectory("Choose backup folder") ?: return@launch
                            isBusy = true
                            try {
                                val file = backupFactory.createBackup(dir)
                                snackbar.showSnackbar("Backup saved: ${file.name}")
                            } catch (e: Exception) {
                                snackbar.showSnackbar("Backup failed: ${e.message}")
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
                        Text("Create Backup")
                    }
                }

                Spacer(Modifier.height(32.dp))
                Text("Restore Backup", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Import a .tachibk backup file. Existing entries will be preserved; " +
                        "only missing data will be added.",
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
                    Text("选择备份文件")
                }
                Spacer(Modifier.height(12.dp))
                RestoreStatus(restoreState, restoreModel::retryRestore, restoreModel::cancel)

                Spacer(Modifier.height(32.dp))
                HorizontalDivider()
                Spacer(Modifier.height(16.dp))

                Text("Automatic Backup", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Automatically create backups at a regular interval.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))

                Text(
                    text = "Backup Frequency",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                val intervalLabels = mapOf(
                    AutoBackupInterval.OFF to "Off",
                    AutoBackupInterval.EVERY_6H to "Every 6 hours",
                    AutoBackupInterval.EVERY_12H to "Every 12 hours",
                    AutoBackupInterval.EVERY_24H to "Every 24 hours",
                    AutoBackupInterval.EVERY_48H to "Every 48 hours",
                    AutoBackupInterval.WEEKLY to "Weekly",
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
                    text = "Maximum Backups",
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
                    dialogTitle = "Select backup file"
                    fileFilter = FileNameExtensionFilter("Mihon backup (*.tachibk)", "tachibk")
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
    Text("漫画 ${preview.mangaCount} · 章节 ${preview.chapterCount} · 分类 ${preview.categoryCount}")
    Text("追踪 ${preview.trackingCount} · 偏好 ${preview.preferenceCount} · 来源 ${preview.sourceCount}")
    Text("扩展仓库 ${preview.extensionRepoCount}")
}

@Composable
private fun RestoreStatus(
    state: BackupRestoreUiState,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
) {
    when (state) {
        BackupRestoreUiState.Empty -> Text("尚未选择备份文件", style = MaterialTheme.typography.bodySmall)
        is BackupRestoreUiState.Loading -> {
            CircularProgressIndicator()
            Text("正在读取 ${state.fileName}…")
        }
        is BackupRestoreUiState.Preview -> BackupPreviewText(state.summary)
        is BackupRestoreUiState.Restoring -> {
            CircularProgressIndicator(progress = { state.progress })
            Text("正在恢复 ${state.fileName}…")
            Text("${state.completed} / ${state.total}（${(state.progress * 100).toInt()}%）")
            TextButton(onClick = onCancel) { Text("取消恢复") }
        }
        is BackupRestoreUiState.Completed -> Text("恢复完成：成功 ${state.restoredItems} 项")
        is BackupRestoreUiState.PartialSuccess -> {
            Text("恢复完成，但以下项目失败：")
            state.failedUnits.take(10).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        }
        is BackupRestoreUiState.Failure -> {
            Text(state.message, color = MaterialTheme.colorScheme.error)
            if (state.recoverable) TextButton(onClick = onRetry) { Text("重试") }
        }
        BackupRestoreUiState.Cancelled -> Text("恢复已取消，可重新选择备份文件")
    }
}
