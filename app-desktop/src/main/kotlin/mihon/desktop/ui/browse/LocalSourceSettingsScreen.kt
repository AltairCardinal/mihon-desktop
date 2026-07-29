package mihon.desktop.ui.browse

import tachiyomi.i18n.MR

import mihon.desktop.LocalDesktopUiDependencies

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import mihon.desktop.settings.DesktopAppPreferences
import mihon.desktop.source.LocalSourceScanService
import java.awt.FileDialog
import java.io.File
import javax.swing.JFileChooser

/**
 * Settings screen for the local manga source.
 *
 * Allows the user to configure:
 * - Root directory for manga scanning
 * - Maximum recursive scan depth (1–5)
 * - Manual rescan trigger
 * - Current scan state display
 */
class LocalSourceSettingsScreen : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val prefs = LocalDesktopUiDependencies.current.appPreferences
        val scanService = LocalDesktopUiDependencies.current.localSourceScanService

        val scanState by scanService.scanState.collectAsState()
        val mangaList by scanService.mangaList.collectAsState()
        val currentDir = remember { prefs.localSourceRootDir.get() }
        val currentDepth = remember { prefs.localSourceMaxDepth.get() }
        var depthSlider by remember { mutableFloatStateOf(currentDepth.toFloat()) }

        fun pickDirectory() {
            val dir = if (System.getProperty("os.name").lowercase().contains("mac")) {
                System.setProperty("apple.awt.fileDialogForDirectories", "true")
                try {
                    val dialog = FileDialog(
                        null as java.awt.Frame?,
                        MR.strings.desktop_ui_select_manga_root_directory.localized(),
                        FileDialog.LOAD,
                    )
                    dialog.isVisible = true
                    val d = dialog.directory ?: return
                    val f = dialog.file ?: return
                    File(d, f)
                } finally {
                    System.setProperty("apple.awt.fileDialogForDirectories", "false")
                }
            } else {
                val chooser = JFileChooser().apply {
                    fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                    dialogTitle = MR.strings.desktop_ui_select_manga_root_directory.localized()
                }
                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                    chooser.selectedFile
                } else {
                    return
                }
            }
            prefs.localSourceRootDir.set(dir.absolutePath)
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(MR.strings.desktop_ui_local_source_settings.localized()) },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = MR.strings.action_bar_up_description.localized())
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Root directory
                Text(MR.strings.desktop_ui_manga_root_directory.localized(), style = MaterialTheme.typography.titleMedium)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = currentDir.ifEmpty { MR.strings.desktop_ui_not_set.localized() },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (currentDir.isEmpty()) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = { pickDirectory() }) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null)
                        Text(MR.strings.desktop_ui_select.localized(), modifier = Modifier.padding(start = 4.dp))
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Scan depth
                Text(MR.strings.desktop_ui_recursive_scan_depth.localized(), style = MaterialTheme.typography.titleMedium)
                Text(
                    MR.strings.desktop_ui_current_depth.localized(
                        java.util.Locale.getDefault(),
                        depthSlider.toInt(),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = depthSlider,
                    onValueChange = { depthSlider = it },
                    onValueChangeFinished = {
                        prefs.localSourceMaxDepth.set(depthSlider.toInt())
                    },
                    valueRange = 1f..5f,
                    steps = 3, // 1,2,3,4,5 → 3 intermediate steps
                )

                Spacer(Modifier.height(8.dp))

                // Scan status
                Text(MR.strings.desktop_ui_scan_status.localized(), style = MaterialTheme.typography.titleMedium)
                val stateText = when (scanState) {
                    is LocalSourceScanService.ScanState.Idle -> MR.strings.desktop_ui_idle.localized()
                    is LocalSourceScanService.ScanState.Scanning -> MR.strings.desktop_ui_scanning.localized()
                    is LocalSourceScanService.ScanState.Watching -> MR.strings.desktop_ui_watching_manga.localized(
                        java.util.Locale.getDefault(),
                        mangaList.size,
                    )
                    is LocalSourceScanService.ScanState.Error ->
                        MR.strings.desktop_ui_error_reason.localized(
                            java.util.Locale.getDefault(),
                            (scanState as LocalSourceScanService.ScanState.Error).message,
                        )
                }
                Text(stateText, style = MaterialTheme.typography.bodyMedium)

                Spacer(Modifier.height(8.dp))

                // Rescan button
                Button(
                    onClick = { scanService.rescan() },
                    enabled = currentDir.isNotEmpty(),
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Text(MR.strings.desktop_ui_rescan_now.localized(), modifier = Modifier.padding(start = 4.dp))
                }
            }
        }
    }
}
