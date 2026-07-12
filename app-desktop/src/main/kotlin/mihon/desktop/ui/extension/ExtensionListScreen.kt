package mihon.desktop.ui.extension

import mihon.desktop.LocalDesktopUiDependencies

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import eu.kanade.tachiyomi.source.ConfigurableSource
import mihon.desktop.extension.DesktopAvailableExtension
import mihon.desktop.extension.DesktopExtensionApi
import mihon.desktop.extension.DesktopExtensionManager
import mihon.desktop.extension.InstalledExtension
import mihon.desktop.extension.availableLangs
import mihon.desktop.extension.filterAvailableByLangs
import mihon.desktop.extension.filterAvailableByNsfw
import mihon.desktop.extension.filterInstalledByLangs
import mihon.desktop.extension.findUpdatableExtensions
import mihon.desktop.extension.installedLangs
import mihon.desktop.extension.isExtensionAvailableOnDesktop
import mihon.desktop.extension.filterAvailableByQuery
import mihon.desktop.extension.filterInstalledByQuery
import java.awt.Desktop
import java.net.URI

/** Lists installed extensions and available extensions from registered repositories. */
class ExtensionListScreen : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val snackbarHostState = remember { SnackbarHostState() }

        val manager = LocalDesktopUiDependencies.current.extensionManager
        val api = LocalDesktopUiDependencies.current.extensionApi

        var selectedTab by remember { mutableStateOf(0) }
        var installedExtensions by remember { mutableStateOf(manager.getInstalledExtensions()) }
        var availableExtensions by remember { mutableStateOf<List<DesktopAvailableExtension>>(emptyList()) }
        var isLoadingAvailable by remember { mutableStateOf(false) }
        var pendingRemoval by remember { mutableStateOf<InstalledExtension?>(null) }
        var selectedLangs by remember { mutableStateOf(emptySet<String>()) }
        var showLangFilter by remember { mutableStateOf(false) }
        var showNsfw by remember { mutableStateOf(false) }
        var searchQuery by remember { mutableStateOf("") }
        val installStates = remember { mutableStateMapOf<String, ExtensionInstallUiState>() }
        val installJobs = remember { mutableMapOf<String, Job>() }

        // Compute which available extensions have a newer version than what is installed
        val availableWithUpdate = remember(availableExtensions, installedExtensions) {
            findUpdatableExtensions(installedExtensions, availableExtensions)
        }

        // Language + NSFW filter
        val filteredInstalled = remember(installedExtensions, selectedLangs, searchQuery) {
            filterInstalledByQuery(filterInstalledByLangs(installedExtensions, selectedLangs), searchQuery)
        }
        val filteredAvailable = remember(availableExtensions, selectedLangs, showNsfw, searchQuery) {
            filterAvailableByQuery(
                filterAvailableByNsfw(filterAvailableByLangs(availableExtensions, selectedLangs), showNsfw),
                searchQuery,
            )
        }
        val currentTabLangs = remember(selectedTab, installedExtensions, availableExtensions) {
            if (selectedTab == 0) installedLangs(installedExtensions)
            else availableLangs(availableExtensions)
        }

        fun loadAvailable() {
            isLoadingAvailable = true
            scope.launch {
                availableExtensions = api.findAvailableExtensions()
                isLoadingAvailable = false
            }
        }

        // Auto-load available when switching to that tab
        LaunchedEffect(selectedTab) {
            if (selectedTab == 1 && availableExtensions.isEmpty() && !isLoadingAvailable) {
                loadAvailable()
            }
        }

        pendingRemoval?.let { ext ->
            AlertDialog(
                onDismissRequest = { pendingRemoval = null },
                title = { Text("Uninstall extension?") },
                text = { Text("Remove \"${ext.name}\" and all its sources? This cannot be undone.") },
                confirmButton = {
                    TextButton(onClick = {
                        manager.removeExtensionWithMeta(ext)
                        installedExtensions = manager.getInstalledExtensions()
                        pendingRemoval = null
                    }) { Text("Uninstall", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    TextButton(onClick = { pendingRemoval = null }) { Text("Cancel") }
                },
            )
        }

        if (showLangFilter) {
            LanguageFilterDialog(
                langs = currentTabLangs,
                selectedLangs = selectedLangs,
                showNsfw = showNsfw,
                onSelectionChanged = { selectedLangs = it },
                onNsfwChanged = { showNsfw = it },
                onDismiss = { showLangFilter = false },
            )
        }

        Scaffold(
            topBar = {
                Column {
                    TopAppBar(
                        title = { Text("Extensions") },
                        navigationIcon = {
                            IconButton(onClick = { navigator.pop() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        },
                        actions = {
                            if (currentTabLangs.isNotEmpty()) {
                                IconButton(onClick = { showLangFilter = true }) {
                                    if (selectedLangs.isNotEmpty()) {
                                        BadgedBox(badge = { Badge { Text("${selectedLangs.size}") } }) {
                                            Icon(Icons.Default.FilterList, contentDescription = "Filter by language")
                                        }
                                    } else {
                                        Icon(Icons.Default.FilterList, contentDescription = "Filter by language")
                                    }
                                }
                            }
                            if (selectedTab == 0) {
                                IconButton(onClick = {
                                    manager.reloadAll()
                                    installedExtensions = manager.getInstalledExtensions()
                                }) {
                                    Icon(Icons.Default.Refresh, contentDescription = "Reload installed")
                                }
                            } else {
                                IconButton(onClick = { loadAvailable() }) {
                                    Icon(Icons.Default.Refresh, contentDescription = "Refresh available")
                                }
                            }
                        },
                    )
                    TabRow(selectedTabIndex = selectedTab) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            text = {
                                val count = if (selectedLangs.isEmpty()) installedExtensions.size else filteredInstalled.size
                                Text("Installed ($count)")
                            },
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            text = {
                                if (availableWithUpdate.isNotEmpty()) {
                                    BadgedBox(badge = { Badge { Text("${availableWithUpdate.size}") } }) {
                                        Text("Available")
                                    }
                                } else {
                                    Text("Available")
                                }
                            },
                        )
                    }
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        placeholder = { Text("Search extensions") },
                    )
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { padding ->
            when (selectedTab) {
                0 -> InstalledTab(
                    extensions = filteredInstalled,
                    onUninstall = { pendingRemoval = it },
                    onOpen = { navigator.push(ExtensionDetailsScreen(it.jarFile.absolutePath)) },
                    onSettings = { sourceId, sourceName ->
                        navigator.push(SourcePreferencesScreen(sourceId, sourceName))
                    },
                    modifier = Modifier.padding(padding),
                )
                1 -> AvailableTab(
                    extensions = filteredAvailable,
                    installedExtensions = installedExtensions,
                    updatableExtensions = availableWithUpdate,
                    isLoading = isLoadingAvailable,
                    installStates = installStates,
                    onInstall = { ext ->
                        if (installJobs[ext.pkgName]?.isActive == true) return@AvailableTab
                        installStates[ext.pkgName] = ExtensionInstallUiState.INSTALLING
                        installJobs[ext.pkgName] = scope.launch {
                            snackbarHostState.showSnackbar("Installing ${ext.name}…")
                            val result = api.installExtension(ext, manager.extensionsDirectory)
                            when (result) {
                                is DesktopExtensionApi.InstallResult.Success -> {
                                    installStates.remove(ext.pkgName)
                                    manager.reloadAll()
                                    installedExtensions = manager.getInstalledExtensions()
                                    snackbarHostState.showSnackbar("${ext.name} installed")
                                }
                                is DesktopExtensionApi.InstallResult.Error -> {
                                    installStates[ext.pkgName] = ExtensionInstallUiState.ERROR
                                    val msg = when {
                                        result.message.startsWith("Android-only") ->
                                            "${ext.name}: Android-only extension, cannot run on desktop"
                                        result.message.startsWith("APK convert failed") ->
                                            "${ext.name}: APK conversion failed — try a JAR-based repo"
                                        else ->
                                            "Install failed: ${result.message}"
                                    }
                                    snackbarHostState.showSnackbar(msg)
                                }
                                is DesktopExtensionApi.InstallResult.TrustRequired -> {
                                    installStates[ext.pkgName] = ExtensionInstallUiState.ERROR
                                    snackbarHostState.showSnackbar(
                                        "Update blocked: repository identity changed from " +
                                            "${result.existingFingerprint} to ${result.incomingFingerprint}",
                                    )
                                }
                            }
                        }
                    },
                    onCancel = { ext ->
                        installJobs.remove(ext.pkgName)?.cancel()
                        installStates.remove(ext.pkgName)
                    },
                    onOpenUrl = { url ->
                        runCatching { Desktop.getDesktop().browse(URI(url)) }
                            .onFailure { scope.launch { snackbarHostState.showSnackbar("Unable to open link: ${it.message}") } }
                    },
                    onUpdateAll = {
                        scope.launch {
                            val toUpdate = availableWithUpdate.toList()
                            if (toUpdate.isEmpty()) return@launch
                            snackbarHostState.showSnackbar("Updating ${toUpdate.size} extension(s)…")
                            var successCount = 0
                            toUpdate.forEach { ext ->
                                val result = api.installExtension(ext, manager.extensionsDirectory)
                                if (result is DesktopExtensionApi.InstallResult.Success) successCount++
                            }
                            manager.reloadAll()
                            installedExtensions = manager.getInstalledExtensions()
                            snackbarHostState.showSnackbar("Updated $successCount/${toUpdate.size} extension(s)")
                        }
                    },
                    modifier = Modifier.padding(padding),
                )
            }
        }
    }
}

@Composable
private fun InstalledTab(
    extensions: List<InstalledExtension>,
    onUninstall: (InstalledExtension) -> Unit,
    onOpen: (InstalledExtension) -> Unit,
    onSettings: (sourceId: Long, sourceName: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (extensions.isEmpty()) {
        EmptyExtensions(modifier = modifier.fillMaxSize())
    } else {
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        ) {
            items(extensions, key = { it.jarFile.absolutePath }) { ext ->
                ExtensionCard(
                    extension = ext,
                    onUninstall = { onUninstall(ext) },
                    onOpen = { onOpen(ext) },
                    onSettings = onSettings,
                )
            }
        }
    }
}

@Composable
private fun AvailableTab(
    extensions: List<DesktopAvailableExtension>,
    installedExtensions: List<InstalledExtension>,
    updatableExtensions: List<DesktopAvailableExtension>,
    isLoading: Boolean,
    installStates: Map<String, ExtensionInstallUiState>,
    onInstall: (DesktopAvailableExtension) -> Unit,
    onCancel: (DesktopAvailableExtension) -> Unit,
    onOpenUrl: (String) -> Unit,
    onUpdateAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        isLoading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        extensions.isEmpty() -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "No extensions found.\nAdd a repository in More → Extension Repos.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        else -> LazyColumn(
            modifier = modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        ) {
            if (updatableExtensions.isNotEmpty()) {
                item {
                    Button(
                        onClick = onUpdateAll,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(),
                    ) {
                        Text("Update All (${updatableExtensions.size})")
                    }
                }
            }
            items(extensions, key = { it.pkgName }) { ext ->
                val isInstalled = isExtensionAvailableOnDesktop(ext.pkgName, installedExtensions)
                val hasUpdate = updatableExtensions.any { it.pkgName == ext.pkgName }
                AvailableExtensionCard(
                    extension = ext,
                    isInstalled = isInstalled,
                    hasUpdate = hasUpdate,
                    onInstall = { onInstall(ext) },
                    onCancel = { onCancel(ext) },
                    installState = installStates[ext.pkgName],
                    onOpenUrl = onOpenUrl,
                )
            }
        }
    }
}

@Composable
private fun ExtensionCard(
    extension: InstalledExtension,
    onUninstall: () -> Unit,
    onOpen: () -> Unit,
    onSettings: (sourceId: Long, sourceName: String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ExtensionIcon(
                iconUrl = extension.iconUrl,
                loadIcon = LocalDesktopUiDependencies.current.extensionApi::loadExtensionIcon,
                modifier = Modifier.size(48.dp).padding(end = 12.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(text = extension.name, style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = extension.sources.joinToString(", ") { "${it.name} (${it.lang})" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = extension.jarFile.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            // Show settings icon for each configurable source
            extension.sources.filter { it is ConfigurableSource }.forEach { source ->
                IconButton(onClick = { onSettings(source.id, source.name) }) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings for ${source.name}")
                }
            }
            IconButton(onClick = onUninstall) {
                Icon(Icons.Default.Delete, contentDescription = "Uninstall", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun AvailableExtensionCard(
    extension: DesktopAvailableExtension,
    isInstalled: Boolean,
    hasUpdate: Boolean,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    installState: ExtensionInstallUiState?,
    onOpenUrl: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ExtensionIcon(
                iconUrl = extension.iconUrl,
                loadIcon = LocalDesktopUiDependencies.current.extensionApi::loadExtensionIcon,
                modifier = Modifier.size(48.dp).padding(end = 12.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = extension.name, style = MaterialTheme.typography.titleSmall)
                    if (extension.isNsfw) {
                        Text(
                            text = "18+",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
                Text(
                    text = "${extension.lang} • v${extension.versionName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = extension.pkgName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            extension.sources.firstOrNull { it.baseUrl.startsWith("http") }?.let { source ->
                IconButton(onClick = { onOpenUrl(source.baseUrl) }) {
                    Icon(Icons.Default.Public, contentDescription = "Open ${source.name} website")
                }
            }
            when {
                installState == ExtensionInstallUiState.INSTALLING -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                    TextButton(onClick = onCancel) { Text("Cancel") }
                }
                installState == ExtensionInstallUiState.ERROR -> Button(onClick = onInstall) { Text("Retry") }
                hasUpdate -> OutlinedButton(onClick = onInstall) { Text("Update") }
                isInstalled -> OutlinedButton(onClick = {}, enabled = false) { Text("Installed") }
                else -> Button(onClick = onInstall) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                    Text("Install")
                }
            }
        }
    }
}

private enum class ExtensionInstallUiState { INSTALLING, ERROR }

@Composable
private fun EmptyExtensions(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("No extensions installed", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Browse the Available tab to install extensions.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LanguageFilterDialog(
    langs: List<String>,
    selectedLangs: Set<String>,
    showNsfw: Boolean = false,
    onSelectionChanged: (Set<String>) -> Unit,
    onNsfwChanged: (Boolean) -> Unit = {},
    onDismiss: () -> Unit,
) {
    var pending by remember(selectedLangs) { mutableStateOf(selectedLangs) }
    var pendingNsfw by remember(showNsfw) { mutableStateOf(showNsfw) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Filter extensions") },
        text = {
            LazyColumn {
                // NSFW toggle at top
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    ) {
                        Checkbox(
                            checked = pendingNsfw,
                            onCheckedChange = { pendingNsfw = it },
                        )
                        Text(
                            text = "Show NSFW extensions",
                            modifier = Modifier.padding(start = 8.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                items(langs) { lang ->
                    val displayName = remember(lang) {
                        try {
                            java.util.Locale.forLanguageTag(lang).getDisplayLanguage(java.util.Locale.ENGLISH)
                                .replaceFirstChar { it.uppercase() }
                                .takeIf { it.isNotBlank() } ?: lang
                        } catch (_: Exception) { lang }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                    ) {
                        Checkbox(
                            checked = lang in pending,
                            onCheckedChange = { checked ->
                                pending = if (checked) pending + lang else pending - lang
                            },
                        )
                        Text(
                            text = "$displayName ($lang)",
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSelectionChanged(pending)
                onNsfwChanged(pendingNsfw)
                onDismiss()
            }) { Text("Apply") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    onSelectionChanged(emptySet())
                    onNsfwChanged(false)
                    onDismiss()
                }) { Text("Clear") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
