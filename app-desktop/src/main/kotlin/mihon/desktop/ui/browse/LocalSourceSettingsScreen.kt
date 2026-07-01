package mihon.desktop.ui.browse

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
                    val dialog = FileDialog(null as java.awt.Frame?, "选择漫画根目录", FileDialog.LOAD)
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
                    dialogTitle = "Select manga root directory"
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
                    title = { Text("本地源设置") },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                Text("漫画根目录", style = MaterialTheme.typography.titleMedium)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = currentDir.ifEmpty { "未设置" },
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
                        Text("选择", modifier = Modifier.padding(start = 4.dp))
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Scan depth
                Text("递归扫描深度", style = MaterialTheme.typography.titleMedium)
                Text(
                    "当前深度: ${depthSlider.toInt()}",
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
                Text("扫描状态", style = MaterialTheme.typography.titleMedium)
                val stateText = when (scanState) {
                    is LocalSourceScanService.ScanState.Idle -> "空闲"
                    is LocalSourceScanService.ScanState.Scanning -> "扫描中..."
                    is LocalSourceScanService.ScanState.Watching -> "监听中 (${mangaList.size} 部漫画)"
                    is LocalSourceScanService.ScanState.Error ->
                        "错误: ${(scanState as LocalSourceScanService.ScanState.Error).message}"
                }
                Text(stateText, style = MaterialTheme.typography.bodyMedium)

                Spacer(Modifier.height(8.dp))

                // Rescan button
                Button(
                    onClick = { scanService.rescan() },
                    enabled = currentDir.isNotEmpty(),
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Text("立即重新扫描", modifier = Modifier.padding(start = 4.dp))
                }
            }
        }
    }
}
