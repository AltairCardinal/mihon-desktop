package mihon.desktop.ui.settings

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.desktop.backup.DesktopBackupCreator
import mihon.desktop.backup.DesktopBackupRestorer
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
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

        var isBusy by remember { mutableStateOf(false) }
        var restoreErrors by remember { mutableStateOf<List<String>?>(null) }

        val mangaRepo = remember { Injekt.get<MangaRepository>() }
        val chapterRepo = remember { Injekt.get<ChapterRepository>() }
        val categoryRepo = remember { Injekt.get<CategoryRepository>() }
        val historyRepo = remember { Injekt.get<HistoryRepository>() }

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
                    .padding(24.dp),
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
                                val backup = withContext(Dispatchers.IO) {
                                    DesktopBackupCreator.createFromDatabase(
                                        mangaRepo,
                                        chapterRepo,
                                        categoryRepo,
                                        historyRepo,
                                    )
                                }
                                val file = withContext(Dispatchers.IO) {
                                    DesktopBackupCreator.writeBackupFile(backup, dir)
                                }
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
                            isBusy = true
                            try {
                                val backup = withContext(Dispatchers.IO) {
                                    DesktopBackupCreator.readBackupFile(file)
                                }
                                if (backup == null) {
                                    snackbar.showSnackbar("Could not read backup file")
                                    return@launch
                                }
                                val restorer = DesktopBackupRestorer(
                                    mangaRepo,
                                    chapterRepo,
                                    categoryRepo,
                                    historyRepo,
                                )
                                val result = withContext(Dispatchers.IO) {
                                    restorer.restore(backup)
                                }
                                if (result.hasErrors) {
                                    restoreErrors = result.errors.map { "${it.first}: ${it.second}" }
                                } else {
                                    snackbar.showSnackbar("Restored ${result.successCount} entries successfully")
                                }
                            } catch (e: Exception) {
                                snackbar.showSnackbar("Restore failed: ${e.message}")
                            } finally {
                                isBusy = false
                            }
                        }
                    },
                    enabled = !isBusy,
                ) {
                    Text("Restore Backup")
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
