package mihon.desktop.ui.extension

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.launch
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.extension.ExtensionOrigin
import mihon.desktop.ui.browse.SourceBrowseScreen
import mihon.desktop.ui.settings.DesktopDirectoryOpener
import mihon.desktop.source.DesktopSourceManager
import java.awt.Desktop
import java.net.URI

data class ExtensionDetailsScreen(val jarPath: String) : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val manager = LocalDesktopUiDependencies.current.extensionManager
        val dependencies = LocalDesktopUiDependencies.current
        val sourceManager = dependencies.sourceManager as? DesktopSourceManager
        val appPreferences = dependencies.appPreferences
        val extension = remember(jarPath) {
            manager.getInstalledExtensions().firstOrNull { it.jarFile.absolutePath == jarPath }
        }
        val snackbar = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        var confirmUninstall by remember { mutableStateOf(false) }

        fun openUrl(url: String) {
            runCatching { Desktop.getDesktop().browse(URI(url)) }
                .onFailure { scope.launch { snackbar.showSnackbar("Unable to open link: ${it.message}") } }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(extension?.name ?: "Extension details") },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            if (extension == null) {
                Column(Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("This extension is no longer installed")
                }
                return@Scaffold
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ExtensionIcon(
                            iconUrl = extension.iconUrl,
                            loadIcon = dependencies.extensionApi::loadExtensionIcon,
                            modifier = Modifier.size(72.dp).padding(end = 16.dp),
                        )
                        Column {
                            Text(extension.name, style = MaterialTheme.typography.titleLarge)
                            Text("Version ${extension.versionName.ifBlank { "unknown" }}")
                            Text(extension.origin.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Windows extension information", style = MaterialTheme.typography.titleMedium)
                            Text("File: ${extension.jarFile.absolutePath}")
                            Text("Size: ${extension.jarFile.length()} bytes")
                            Text("SHA-256: ${extension.artifactSha256.ifBlank { "not recorded" }}")
                            Text("Repository: ${extension.repoName.ifBlank { extension.repoUrl.ifBlank { "unknown" } }}")
                            Text("Repository fingerprint: ${extension.repoFingerprint.ifBlank { "not available" }}")
                            Spacer(Modifier.height(4.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = {
                                    if (!DesktopDirectoryOpener.open(extension.jarFile.parentFile)) {
                                        scope.launch { snackbar.showSnackbar("Unable to open extension folder") }
                                    }
                                }) {
                                    Icon(Icons.Default.FolderOpen, contentDescription = null)
                                    Text("Open folder", Modifier.padding(start = 6.dp))
                                }
                                if (extension.repoUrl.isNotBlank()) {
                                    OutlinedButton(onClick = { openUrl(extension.repoUrl) }) {
                                        Icon(Icons.Default.Public, contentDescription = null)
                                        Text("Repository", Modifier.padding(start = 6.dp))
                                    }
                                }
                            }
                        }
                    }
                }
                item { Text("Sources", style = MaterialTheme.typography.titleMedium) }
                items(extension.sources, key = { it.id }) { source ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(source.name, style = MaterialTheme.typography.titleSmall)
                                Text(source.lang, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (sourceManager != null) {
                                var enabled by remember(source.id) {
                                    mutableStateOf(sourceManager.isSourceEnabled(source.id))
                                }
                                Switch(
                                    checked = enabled,
                                    onCheckedChange = {
                                        enabled = it
                                        sourceManager.setSourceEnabled(source.id, it)
                                    },
                                )
                            }
                            if (source is HttpSource && source.baseUrl.startsWith("http")) {
                                IconButton(onClick = { openUrl(source.baseUrl) }) {
                                    Icon(Icons.Default.Public, contentDescription = "Open ${source.name} website")
                                }
                            }
                            if (source is ConfigurableSource) {
                                IconButton(onClick = { navigator.push(SourcePreferencesScreen(source.id, source.name)) }) {
                                    Icon(Icons.Default.Settings, contentDescription = "Settings for ${source.name}")
                                }
                            }
                            TextButton(onClick = { navigator.push(SourceBrowseScreen(source.id)) }) { Text("Browse") }
                        }
                    }
                }
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            var incognito by remember { mutableStateOf(appPreferences.incognitoMode.get()) }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Incognito mode", style = MaterialTheme.typography.titleSmall)
                                    Text("Do not record reading history", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(
                                    checked = incognito,
                                    onCheckedChange = {
                                        incognito = it
                                        appPreferences.incognitoMode.set(it)
                                    },
                                )
                            }
                            OutlinedButton(onClick = {
                                val domains = extension.sources
                                    .filterIsInstance<HttpSource>()
                                    .mapNotNull { runCatching { URI(it.baseUrl).host }.getOrNull() }
                                    .toSet()
                                val removed = dependencies.networkHelper.cookieJar.clearDomains(domains)
                                scope.launch { snackbar.showSnackbar("Cleared cookies for $removed domain(s)") }
                            }) { Text("Clear extension cookies") }
                        }
                    }
                }
                item {
                    Button(onClick = { confirmUninstall = true }) {
                        Icon(Icons.Default.Delete, contentDescription = null)
                        Text("Uninstall", Modifier.padding(start = 6.dp))
                    }
                }
            }

            if (confirmUninstall) {
                AlertDialog(
                    onDismissRequest = { confirmUninstall = false },
                    title = { Text("Uninstall extension?") },
                    text = { Text("Remove ${extension.name} and its metadata?") },
                    confirmButton = {
                        TextButton(onClick = {
                            manager.removeExtensionWithMeta(extension)
                            confirmUninstall = false
                            navigator.pop()
                        }) { Text("Uninstall") }
                    },
                    dismissButton = { TextButton(onClick = { confirmUninstall = false }) { Text("Cancel") } },
                )
            }
        }
    }
}

private val ExtensionOrigin.label: String
    get() = when (this) {
        ExtensionOrigin.COMPILED_JAR -> "Native desktop JAR"
        ExtensionOrigin.CONVERTED_APK -> "Converted from Android APK"
    }
