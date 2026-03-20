package mihon.desktop.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import mihon.desktop.extension.DesktopExtensionManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

class AboutScreen : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val appDir = remember { File(System.getProperty("user.home"), ".mihon") }
        val cacheDir = remember { File(appDir, "cache/network") }
        val dbFile = remember { File(appDir, "mihon.db") }
        val extensionsDir = remember { File(appDir, "extensions") }
        val extensionManager = remember { runCatching { Injekt.get<DesktopExtensionManager>() }.getOrNull() }

        var cacheSizeText by remember {
            mutableStateOf(formatBytes(cacheDir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }))
        }

        // Gather debug info once
        val dbSize = remember { formatBytes(dbFile.length()) }
        val dbPath = remember { dbFile.absolutePath }
        val extensionCount = remember { extensionManager?.getInstalledExtensions()?.size ?: 0 }
        val javaVersion = remember { System.getProperty("java.version") ?: "unknown" }
        val javaVendor = remember { System.getProperty("java.vendor") ?: "" }
        val osName = remember { System.getProperty("os.name") ?: "unknown" }
        val osVersion = remember { System.getProperty("os.version") ?: "" }
        val appDirPath = remember { appDir.absolutePath }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("About") },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = "Mihon Desktop",
                    style = MaterialTheme.typography.headlineMedium,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Version 1.0.0",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "A KMP + Compose Multiplatform port of the Mihon Android manga reader.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "Based on Mihon",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "https://github.com/mihonapp/mihon",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))

                Text("Storage", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(8.dp))
                InfoRow(label = "App data directory", value = appDirPath)
                InfoRow(label = "Database", value = "$dbPath ($dbSize)")
                InfoRow(label = "Network cache", value = cacheSizeText)
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = {
                    cacheDir.deleteRecursively()
                    cacheSizeText = "0 B"
                }) {
                    Text("Clear network cache")
                }

                Spacer(modifier = Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))

                Text("Extensions", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(8.dp))
                InfoRow(label = "Installed extensions", value = extensionCount.toString())
                InfoRow(label = "Extensions directory", value = extensionsDir.absolutePath)

                Spacer(modifier = Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))

                Text("Environment", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(8.dp))
                InfoRow(label = "Java version", value = "$javaVersion ($javaVendor)".trimEnd('(', ' ').trim())
                InfoRow(label = "OS", value = "$osName $osVersion".trim())
            }
        }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    androidx.compose.foundation.layout.Row(
        modifier = androidx.compose.ui.Modifier.padding(vertical = 2.dp),
    ) {
        Text(
            text = "$label: ",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
