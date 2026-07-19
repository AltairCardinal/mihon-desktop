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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import kotlinx.coroutines.launch
import eu.kanade.tachiyomi.source.ConfigurableSource
import mihon.desktop.extension.DesktopAvailableExtension
import mihon.desktop.extension.DesktopExtensionManager
import mihon.desktop.extension.InstalledExtension
import mihon.desktop.extension.isExtensionAvailableOnDesktop
import mihon.domain.error.AppError
import mihon.domain.extension.presentation.ExtensionPresentationInstallStep
import mihon.domain.extension.presentation.ExtensionPresentationOptions
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.awt.Desktop
import java.net.URI
import java.util.Locale

/** Lists installed extensions and available extensions from registered repositories. */
class ExtensionListScreen : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val manager = LocalDesktopUiDependencies.current.extensionManager
        ExtensionListContent(
            model = remember { Injekt.get<ExtensionsScreenModel>() },
            manager = manager,
            onBack = navigator::pop,
            onOpen = { navigator.push(ExtensionDetailsScreen(it.jarFile.absolutePath)) },
            onSettings = { sourceId, sourceName -> navigator.push(SourcePreferencesScreen(sourceId, sourceName)) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExtensionListContent(
    model: ExtensionsScreenModel,
    manager: DesktopExtensionManager,
    onBack: () -> Unit = {},
    onOpen: (InstalledExtension) -> Unit = {},
    onSettings: (Long, String) -> Unit = { _, _ -> },
) {
        val state by model.state.collectAsState()
        val copy = remember { extensionListCopy() }
        val scope = rememberCoroutineScope()
        val snackbarHostState = remember { SnackbarHostState() }

        var selectedTab by remember { mutableStateOf(0) }
        var pendingRemoval by remember { mutableStateOf<InstalledExtension?>(null) }
        var showLangFilter by remember { mutableStateOf(false) }
        var searchQuery by remember { mutableStateOf("") }
        val ui = remember(state, searchQuery) { state.toExtensionListUiProjection(searchQuery) }
        val installedExtensions = state.projection?.installed.orEmpty().mapNotNull(DesktopExtensionItem::installed)
        val languageInventory = remember(state.projection) {
            state.projection?.let { projection ->
                (projection.installed + projection.available).flatMap { item ->
                    listOfNotNull(item.presentation.language) + item.presentation.sources.map { it.language }
                }.filter(String::isNotBlank).toSet()
            }.orEmpty()
        }
        val currentTabLangs = remember(selectedTab, state.projection) {
            (if (selectedTab == 0) state.projection?.installed.orEmpty() else state.projection?.available.orEmpty())
                .flatMap { item ->
                    listOfNotNull(item.presentation.language) + item.presentation.sources.map { it.language }
                }.filter(String::isNotBlank).distinct().sorted()
        }

        LaunchedEffect(model) { model.refresh() }

        pendingRemoval?.let { ext ->
            AlertDialog(
                onDismissRequest = { pendingRemoval = null },
                title = { Text("Uninstall extension?") },
                text = { Text("Remove \"${ext.name}\" and all its sources? This cannot be undone.") },
                confirmButton = {
                    TextButton(onClick = {
                        manager.removeExtensionWithMeta(ext)
                        pendingRemoval = null
                    }) { Text("Uninstall", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    TextButton(onClick = { pendingRemoval = null }) { Text("Cancel") }
                },
            )
        }

        state.pendingTrust?.let { pending ->
            AlertDialog(
                onDismissRequest = { model.dismissTrust() },
                title = { Text(MR.strings.untrusted_extension.localized()) },
                text = {
                    Text(
                        MR.strings.desktop_extension_trust_changed.localized(
                            Locale.getDefault(),
                            pending.request.existingFingerprint,
                            pending.request.incomingFingerprint,
                        ),
                    )
                },
                confirmButton = {
                    TextButton(onClick = { model.confirmTrust() }) { Text(MR.strings.ext_trust.localized()) }
                },
                dismissButton = {
                    TextButton(
                        onClick = { model.dismissTrust() },
                        modifier = Modifier.semantics {
                            contentDescription = "${MR.strings.action_cancel.localized()} ${MR.strings.untrusted_extension.localized()}"
                        },
                    ) { Text(MR.strings.action_cancel.localized()) }
                },
            )
        }

        if (showLangFilter) {
            LanguageFilterDialog(
                langs = currentTabLangs,
                selectedLangs = state.options.enabledLanguages,
                showNsfw = state.options.showNsfw,
                onApply = { languages, showNsfw ->
                    model.setOptions(ExtensionPresentationOptions(showNsfw, languages.ifEmpty { languageInventory }))
                },
                onDismiss = { showLangFilter = false },
            )
        }

        Scaffold(
            topBar = {
                Column {
                    TopAppBar(
                        title = { Text(copy.title) },
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        },
                        actions = {
                            if (currentTabLangs.isNotEmpty()) {
                                IconButton(onClick = { showLangFilter = true }) {
                                    if (state.options.enabledLanguages.isNotEmpty()) {
                                        BadgedBox(badge = { Badge { Text("${state.options.enabledLanguages.size}") } }) {
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
                                }) {
                                    Icon(Icons.Default.Refresh, contentDescription = "Reload installed")
                                }
                            } else {
                                IconButton(onClick = { model.refresh() }) {
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
                                Text("${MR.strings.ext_installed.localized()} (${ui.installed.size})")
                            },
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            text = {
                                if (ui.updates.isNotEmpty()) {
                                    BadgedBox(badge = { Badge { Text("${ui.updates.size}") } }) {
                                        Text(copy.available)
                                    }
                                } else {
                                    Text(copy.available)
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
                        placeholder = { Text(copy.search) },
                    )
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                if (ui.failures.isNotEmpty()) {
                    ElevatedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Text(copy.repositoryFailure, style = MaterialTheme.typography.titleSmall)
                            ui.failures.forEach { Text("${it.repository.name.ifBlank { it.repository.baseUrl }}: ${it.error}") }
                            TextButton(onClick = model::refresh, modifier = Modifier.align(Alignment.End)) { Text(copy.retry) }
                        }
                    }
                }
                if (state.projection == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Text(copy.loading, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                } else when (selectedTab) {
                0 -> InstalledTab(
                    extensions = ui.installed.mapNotNull(DesktopExtensionItem::installed),
                    onUninstall = { pendingRemoval = it },
                    onOpen = onOpen,
                    onSettings = onSettings,
                    emptyCopy = copy,
                    modifier = Modifier.weight(1f),
                )
                1 -> AvailableTab(
                    extensions = ui.updates + ui.available,
                    installedExtensions = installedExtensions,
                    updatableExtensions = ui.updates,
                    installSteps = state.actions.installSteps,
                    installErrors = state.installErrors,
                    onInstall = model::install,
                    onUpdate = { model.update(it) },
                    onRetry = { model.retry(it) },
                    onCancel = { model.cancel(it.operationPackageName) },
                    onOpenUrl = { url ->
                        runCatching { Desktop.getDesktop().browse(URI(url)) }
                            .onFailure {
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        MR.strings.desktop_extension_open_link_failed.localized(Locale.getDefault(), it.message.orEmpty()),
                                    )
                                }
                            }
                    },
                    onUpdateAll = {
                        val jobs = model.updateAll()
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                MR.strings.desktop_extension_updating_message.localized(Locale.getDefault(), jobs.size),
                            )
                        }
                    },
                    emptyCopy = copy,
                    modifier = Modifier.weight(1f),
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
    emptyCopy: ExtensionListCopy,
    modifier: Modifier = Modifier,
) {
    if (extensions.isEmpty()) {
        EmptyExtensions(emptyCopy, modifier = modifier.fillMaxSize())
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
    extensions: List<DesktopExtensionItem>,
    installedExtensions: List<InstalledExtension>,
    updatableExtensions: List<DesktopExtensionItem>,
    installSteps: Map<String, ExtensionPresentationInstallStep>,
    installErrors: Map<String, AppError>,
    onInstall: (DesktopExtensionItem) -> Unit,
    onUpdate: (DesktopExtensionItem) -> Unit,
    onRetry: (DesktopExtensionItem) -> Unit,
    onCancel: (DesktopExtensionItem) -> Unit,
    onOpenUrl: (String) -> Unit,
    onUpdateAll: () -> Unit,
    emptyCopy: ExtensionListCopy,
    modifier: Modifier = Modifier,
) {
    when {
        extensions.isEmpty() -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                emptyCopy.emptyAvailable,
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
                        Text("${MR.strings.ext_update_all.localized()} (${updatableExtensions.size})")
                    }
                }
            }
            items(extensions, key = { it.presentation.packageName }) { item ->
                val isInstalled = isExtensionAvailableOnDesktop(item.operationPackageName, installedExtensions)
                val hasUpdate = updatableExtensions.any { it.operationPackageName == item.operationPackageName }
                AvailableExtensionCard(
                    item = item,
                    isInstalled = isInstalled,
                    hasUpdate = hasUpdate,
                    onInstall = { onInstall(item) },
                    onUpdate = { onUpdate(item) },
                    onRetry = { onRetry(item) },
                    onCancel = { onCancel(item) },
                    installStep = installSteps[item.operationPackageName],
                    installError = installErrors[item.operationPackageName],
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
    item: DesktopExtensionItem,
    isInstalled: Boolean,
    hasUpdate: Boolean,
    onInstall: () -> Unit,
    onUpdate: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    installStep: ExtensionPresentationInstallStep?,
    installError: AppError?,
    onOpenUrl: (String) -> Unit,
) {
    val extension = requireNotNull(item.available)
    val presentation = item.presentation
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
                    Text(text = presentation.name, style = MaterialTheme.typography.titleSmall)
                    if (presentation.isNsfw) {
                        Text(
                            text = "18+",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
                Text(
                    text = "${presentation.language.orEmpty()} • v${extension.versionName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = presentation.packageName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                extensionInstallStepCopy(installStep)?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                installError?.let {
                    Text(extensionInstallErrorCopy(presentation.name, it), color = MaterialTheme.colorScheme.error)
                }
            }
            presentation.sources.firstOrNull { it.baseUrl?.startsWith("http") == true }?.let { source ->
                IconButton(onClick = { onOpenUrl(requireNotNull(source.baseUrl)) }) {
                    Icon(Icons.Default.Public, contentDescription = "Open ${source.name} website")
                }
            }
            when {
                installStep != null && installStep != ExtensionPresentationInstallStep.Error -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                    TextButton(
                        onClick = onCancel,
                        modifier = Modifier.semantics {
                            contentDescription = "${MR.strings.action_cancel.localized()} ${presentation.name}"
                        },
                    ) { Text(MR.strings.action_cancel.localized()) }
                }
                installError != null || installStep == ExtensionPresentationInstallStep.Error ->
                    Button(
                        onClick = onRetry,
                        modifier = Modifier.semantics {
                            contentDescription = "${MR.strings.action_retry.localized()} ${presentation.name}"
                        },
                    ) { Text(MR.strings.action_retry.localized()) }
                hasUpdate -> OutlinedButton(
                    onClick = onUpdate,
                    modifier = Modifier.semantics {
                        contentDescription = "${MR.strings.ext_update.localized()} ${presentation.name}"
                    },
                ) { Text(MR.strings.ext_update.localized()) }
                isInstalled -> OutlinedButton(onClick = {}, enabled = false) { Text(MR.strings.ext_installed.localized()) }
                else -> Button(
                    onClick = onInstall,
                    modifier = Modifier.semantics {
                        contentDescription = "${MR.strings.action_install.localized()} ${presentation.name}"
                    },
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                    Text(MR.strings.action_install.localized())
                }
            }
        }
    }
}

internal fun extensionInstallErrorCopy(name: String, error: AppError, locale: Locale = Locale.getDefault()): String {
    val detail = error.cause?.message.orEmpty()
    return when {
        error is AppError.MalformedData && detail.startsWith("Android-only") -> MR.strings.desktop_extension_android_only.localized(locale, name)
        error is AppError.MalformedData && detail.startsWith("APK convert failed") -> MR.strings.desktop_extension_conversion_failed.localized(locale, name)
        else -> MR.strings.desktop_extension_install_failed.localized(locale, detail.ifBlank { error.toString() })
    }
}

internal fun extensionInstallStepCopy(step: ExtensionPresentationInstallStep?, locale: Locale = Locale.getDefault()): String? = when (step) {
    ExtensionPresentationInstallStep.Pending -> MR.strings.ext_pending.localized(locale)
    ExtensionPresentationInstallStep.Downloading -> MR.strings.ext_downloading.localized(locale)
    ExtensionPresentationInstallStep.Installing -> MR.strings.ext_installing.localized(locale)
    else -> null
}

@Composable
private fun EmptyExtensions(copy: ExtensionListCopy, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(copy.emptyInstalled, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(8.dp))
            Text(copy.emptyInstalledHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LanguageFilterDialog(
    langs: List<String>,
    selectedLangs: Set<String>,
    showNsfw: Boolean = false,
    onApply: (Set<String>, Boolean) -> Unit,
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
                onApply(pending, pendingNsfw)
                onDismiss()
            }) { Text("Apply") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    onApply(emptySet(), false)
                    onDismiss()
                }) { Text("Clear") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
