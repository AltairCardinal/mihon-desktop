package mihon.desktop.ui.extension

import androidx.compose.foundation.background
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.LocalExtensionScreenModel
import mihon.desktop.extension.ExtensionOrigin
import mihon.desktop.extension.InstalledExtension
import mihon.desktop.network.PluginDomainExportFormat
import mihon.desktop.network.exportPluginDomains
import mihon.desktop.network.normalizePluginHost
import mihon.desktop.platform.DesktopUrlOpener
import mihon.desktop.network.DesktopNetworkRoutingPort
import mihon.desktop.network.DesktopPluginNetworkSupport
import mihon.desktop.settings.DesktopAppPreferences
import mihon.desktop.settings.PluginNetworkMode
import mihon.desktop.ui.settings.DesktopDirectoryOpener
import mihon.desktop.ui.settings.RadioSettingsItem
import mihon.desktop.settings.parseDesktopProxyUrl
import tachiyomi.core.common.preference.getAndSet
import tachiyomi.i18n.MR
import java.io.File
import java.util.Locale

internal data class ExtensionDetailsPlatformActions(
    val openDirectory: (File) -> Boolean,
    val openUrl: (String) -> Result<Unit>,
)

internal val LocalExtensionDetailsPlatformActions = staticCompositionLocalOf {
    ExtensionDetailsPlatformActions(
        openDirectory = DesktopDirectoryOpener::open,
        openUrl = DesktopUrlOpener::open,
    )
}

data class ExtensionDetailsScreen(val jarPath: String) : Screen {
    internal fun onSettings(navigator: Navigator, sourceId: Long, sourceName: String) {
        navigator.push(sourcePreferencesDestination(sourceId, sourceName))
    }

    internal fun onBrowse(navigator: Navigator, sourceId: Long) {
        navigator.push(sourceBrowseDestination(sourceId))
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val dependencies = LocalDesktopUiDependencies.current
        val platformActions = LocalExtensionDetailsPlatformActions.current
        val model = LocalExtensionScreenModel.current()
        val state by model.state.collectAsState()
        val appPreferences = dependencies.appPreferences
        val item = state.projection?.installed?.firstOrNull { it.installed?.jarFile?.absolutePath == jarPath }
        val extension = item?.installed
        val sources = item?.let(model::extensionSources).orEmpty()
        val snackbar = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        var confirmUninstall by remember { mutableStateOf(false) }
        var showNsfwWarning by remember { mutableStateOf(false) }

        LaunchedEffect(model) {
            if (model.state.value.projection == null) model.refresh().join()
        }
        LaunchedEffect(state.projection, extension, jarPath) {
            if (state.projection != null && extension == null) navigator.pop()
        }
        fun openUrl(url: String) {
            platformActions.openUrl(url)
                .onFailure { scope.launch { snackbar.showSnackbar(MR.strings.desktop_extension_open_link_failed.localized(Locale.getDefault(), it.message.orEmpty())) } }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(extension?.name ?: MR.strings.desktop_extension_details_title.localized()) },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = MR.strings.action_bar_up_description.localized())
                        }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            if (state.projection == null) {
                Column(
                    Modifier.fillMaxSize().padding(padding),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Text(MR.strings.loading.localized(), Modifier.padding(top = 8.dp))
                }
                return@Scaffold
            }
            if (extension == null) {
                Column(
                    Modifier.fillMaxSize().padding(padding).padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        MR.strings.desktop_extension_not_installed.localized(),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                    )
                    Button(onClick = { navigator.pop() }, modifier = Modifier.padding(top = 16.dp)) {
                        Text(MR.strings.action_bar_up_description.localized())
                    }
                }
                return@Scaffold
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (item?.presentation?.isObsolete == true) {
                    item {
                        Text(
                            text = MR.strings.obsolete_extension_message.localized(),
                            color = MaterialTheme.colorScheme.onError,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.error).padding(16.dp),
                        )
                    }
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ExtensionIcon(
                            iconUrl = extension.iconUrl,
                            loadIcon = dependencies.extensionApi::loadExtensionIcon,
                            modifier = Modifier.size(72.dp).padding(end = 16.dp),
                        )
                        Column {
                            Text(extension.name, style = MaterialTheme.typography.titleLarge)
                            Text(extensionVersionCopy(extension.versionName, Locale.getDefault()))
                            Text(extension.origin.localizedLabel(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (item?.presentation?.isNsfw == true) {
                                TextButton(onClick = { showNsfwWarning = true }) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            text = MR.strings.ext_nsfw_short.localized(),
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                        Text(MR.strings.ext_info_age_rating.localized())
                                    }
                                }
                            }
                        }
                    }
                }
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(MR.strings.desktop_extension_metadata_title.localized(), style = MaterialTheme.typography.titleMedium)
                            Text(MR.strings.desktop_extension_metadata_file.localized(Locale.getDefault(), extension.jarFile.absolutePath))
                            Text(MR.strings.desktop_extension_metadata_size.localized(Locale.getDefault(), extension.jarFile.length()))
                            Text(MR.strings.desktop_extension_metadata_sha256.localized(Locale.getDefault(), extension.artifactSha256.ifBlank { MR.strings.unknown.localized() }))
                            Text(MR.strings.desktop_extension_metadata_repository.localized(Locale.getDefault(), extension.repoName.ifBlank { extension.repoUrl.ifBlank { MR.strings.unknown.localized() } }))
                            Text(MR.strings.desktop_extension_metadata_fingerprint.localized(Locale.getDefault(), extension.repoFingerprint.ifBlank { MR.strings.unknown.localized() }))
                            Spacer(Modifier.height(4.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = {
                                    if (!platformActions.openDirectory(extension.jarFile.parentFile)) {
                                        scope.launch { snackbar.showSnackbar(MR.strings.desktop_extension_open_folder_failed.localized()) }
                                    }
                                }) {
                                    Icon(Icons.Default.FolderOpen, contentDescription = null)
                                    Text(MR.strings.desktop_extension_open_folder.localized(), Modifier.padding(start = 6.dp))
                                }
                                if (extension.repoUrl.isNotBlank()) {
                                    OutlinedButton(onClick = { openUrl(extension.repoUrl) }) {
                                        Icon(Icons.Default.Public, contentDescription = null)
                                        Text(MR.strings.action_open_repo.localized(), Modifier.padding(start = 6.dp))
                                    }
                                }
                            }
                        }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            MR.strings.label_sources.localized(),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { item?.let { model.setSourcesEnabled(it, true) } }) {
                            Text(MR.strings.action_enable_all.localized())
                        }
                        TextButton(onClick = { item?.let { model.setSourcesEnabled(it, false) } }) {
                            Text(MR.strings.action_disable_all.localized())
                        }
                    }
                }
                items(sources, key = { it.source.id }) { sourceItem ->
                    val source = sourceItem.source
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                val displayName = sourceItem.displayName(Locale.getDefault())
                                Text(displayName, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    listOf(source.name.takeUnless { it == displayName }, source.lang).filterNotNull().joinToString(" · "),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = sourceItem.enabled,
                                onCheckedChange = { model.setSourceEnabled(source.id, it) },
                            )
                            if (source is HttpSource && source.baseUrl.startsWith("http")) {
                                IconButton(onClick = { openUrl(source.baseUrl) }) {
                                    Icon(Icons.Default.Public, contentDescription = MR.strings.desktop_extension_open_source_website.localized(Locale.getDefault(), source.name))
                                }
                            }
                            if (source is ConfigurableSource) {
                                IconButton(onClick = { onSettings(navigator, source.id, source.name) }) {
                                    Icon(Icons.Default.Settings, contentDescription = MR.strings.desktop_extension_source_settings.localized(Locale.getDefault(), source.name))
                                }
                            }
                            TextButton(onClick = { onBrowse(navigator, source.id) }) { Text(MR.strings.browse.localized()) }
                        }
                    }
                }
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            val incognitoExtensions by appPreferences.incognitoExtensions.changes().collectAsState(
                                initial = appPreferences.incognitoExtensions.get(),
                            )
                            val incognito = extension.pkgName in incognitoExtensions
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(MR.strings.pref_incognito_mode.localized(), style = MaterialTheme.typography.titleSmall)
                                    Text(MR.strings.pref_incognito_mode_extension_summary.localized(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(
                                    checked = incognito,
                                    onCheckedChange = {
                                        appPreferences.incognitoExtensions.getAndSet { current ->
                                            if (it) current + extension.pkgName else current - extension.pkgName
                                        }
                                    },
                                    modifier = Modifier.semantics {
                                        contentDescription = MR.strings.desktop_extension_incognito_for.localized(Locale.getDefault(), extension.pkgName)
                                    },
                                )
                            }
                            OutlinedButton(onClick = {
                                val removed = dependencies.extensionCookiePort.clearCookies(extension.sources)
                                scope.launch { snackbar.showSnackbar(MR.strings.desktop_extension_cookies_cleared.localized(Locale.getDefault(), removed)) }
                            }) { Text(MR.strings.pref_clear_cookies.localized()) }
                        }
                    }
                }
                item {
                    PluginNetworkCard(
                        extension = extension,
                        sources = extension.sources,
                        appPreferences = appPreferences,
                        networkHelper = dependencies.networkRoutingPort,
                        snackbar = snackbar,
                    )
                }
                item {
                    Button(onClick = { confirmUninstall = true }) {
                        Icon(Icons.Default.Delete, contentDescription = null)
                        Text(MR.strings.ext_uninstall.localized(), Modifier.padding(start = 6.dp))
                    }
                }
            }

            if (confirmUninstall) {
                AlertDialog(
                    onDismissRequest = { confirmUninstall = false },
                    title = { Text(MR.strings.ext_confirm_remove.localized()) },
                    text = { Text(MR.strings.desktop_extension_remove_metadata_confirmation.localized(Locale.getDefault(), extension.name)) },
                    confirmButton = {
                        TextButton(onClick = {
                            confirmUninstall = false
                            if (!model.uninstall(item)) {
                                scope.launch { snackbar.showSnackbar(MR.strings.desktop_extension_uninstall_failed.localized()) }
                            }
                        }) { Text(MR.strings.ext_uninstall.localized()) }
                    },
                    dismissButton = { TextButton(onClick = { confirmUninstall = false }) { Text(MR.strings.action_cancel.localized()) } },
                )
            }
            if (showNsfwWarning) {
                AlertDialog(
                    onDismissRequest = { showNsfwWarning = false },
                    text = { Text(MR.strings.ext_nsfw_warning.localized()) },
                    confirmButton = {
                        TextButton(onClick = { showNsfwWarning = false }) {
                            Text(MR.strings.action_ok.localized())
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun PluginNetworkCard(
    extension: InstalledExtension,
    sources: List<eu.kanade.tachiyomi.source.Source>,
    appPreferences: DesktopAppPreferences,
    networkHelper: DesktopNetworkRoutingPort,
    snackbar: SnackbarHostState,
) {
    val packageName = extension.pkgName
    val modePreference = remember(packageName) { appPreferences.pluginNetworkMode(packageName) }
    val proxyPreference = remember(packageName) { appPreferences.pluginProxyUrl(packageName) }
    val observedPreference = remember(packageName) { appPreferences.pluginObservedDomains(packageName) }
    val exportTargetPreference = remember(packageName) { appPreferences.pluginDomainExportTarget(packageName) }
    val mode by modePreference.changes().collectAsState(initial = modePreference.get())
    val proxyUrl by proxyPreference.changes().collectAsState(initial = proxyPreference.get())
    val observedDomains by observedPreference.changes().collectAsState(initial = observedPreference.get())
    val exportTarget by exportTargetPreference.changes().collectAsState(initial = exportTargetPreference.get())
    val declaredDomains = remember(sources) {
        sources.filterIsInstance<HttpSource>().mapNotNull { normalizePluginHost(it.baseUrl) }.toSet()
    }
    val domains = (declaredDomains + observedDomains).sorted()
    val support = remember(sources, mode, proxyUrl) { networkHelper.pluginNetworkSupport(sources) }
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var testUrl by remember {
        mutableStateOf(sources.filterIsInstance<HttpSource>().firstOrNull()?.baseUrl.orEmpty())
    }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }

    fun copy(format: PluginDomainExportFormat, formatName: String) {
        if (domains.isEmpty()) return
        val target = when (format) {
            PluginDomainExportFormat.SING_BOX -> exportTarget.ifBlank { "proxy" }
            else -> exportTarget.ifBlank { "PROXY" }
        }
        clipboard.setText(AnnotatedString(exportPluginDomains(domains, format, target)))
        scope.launch {
            snackbar.showSnackbar(
                MR.strings.desktop_plugin_domains_copied.localized(
                    Locale.getDefault(),
                    domains.size,
                    formatName,
                ),
            )
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(MR.strings.desktop_plugin_network_title.localized(), style = MaterialTheme.typography.titleMedium)
            Text(
                MR.strings.desktop_plugin_network_summary.localized(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PluginNetworkMode.entries.forEach { candidate ->
                RadioSettingsItem(
                    title = pluginNetworkModeLabel(candidate),
                    selected = mode == candidate,
                    onClick = { modePreference.set(candidate) },
                )
            }
            if (mode == PluginNetworkMode.MANUAL) {
                val valid = parseDesktopProxyUrl(proxyUrl) != null
                OutlinedTextField(
                    value = proxyUrl,
                    onValueChange = { proxyPreference.set(it) },
                    label = { Text(MR.strings.desktop_general_proxy_url.localized()) },
                    supportingText = {
                        Text(
                            if (valid) {
                                MR.strings.desktop_general_proxy_supported_types.localized()
                            } else {
                                MR.strings.desktop_general_proxy_invalid.localized()
                            },
                        )
                    },
                    isError = !valid,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text(
                when (support) {
                    DesktopPluginNetworkSupport.FULL -> MR.strings.desktop_plugin_network_support_full.localized()
                    DesktopPluginNetworkSupport.PARTIAL -> MR.strings.desktop_plugin_network_support_partial.localized()
                    DesktopPluginNetworkSupport.UNKNOWN -> MR.strings.desktop_plugin_network_support_unknown.localized()
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                MR.strings.desktop_plugin_network_effective.localized(
                    Locale.getDefault(),
                    networkHelper.pluginEffectiveRoute(packageName),
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = testUrl,
                onValueChange = { testUrl = it },
                label = { Text(MR.strings.desktop_network_test_url.localized()) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                onClick = {
                    val sourceId = sources.firstOrNull()?.id
                    testResult = null
                    testing = true
                    scope.launch {
                        try {
                            val result = networkHelper.testConnection(testUrl, sourceId)
                            testResult = if (result.successful) {
                                val route = result.route?.let {
                                    "${it.proxyType.name}${it.proxyAddress?.let { address -> " $address" }.orEmpty()}"
                                } ?: "unknown"
                                MR.strings.desktop_network_test_success.localized(
                                    Locale.getDefault(),
                                    result.statusCode ?: 0,
                                    route,
                                )
                            } else {
                                MR.strings.desktop_network_test_failed.localized(
                                    Locale.getDefault(),
                                    result.error.orEmpty(),
                                )
                            }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            testResult = MR.strings.desktop_network_test_failed.localized(
                                Locale.getDefault(),
                                error.message ?: error.javaClass.simpleName,
                            )
                        } finally {
                            testing = false
                        }
                    }
                },
                enabled = testUrl.isNotBlank() && !testing,
            ) {
                Text(
                    if (testing) {
                        MR.strings.desktop_network_testing.localized()
                    } else {
                        MR.strings.desktop_network_test.localized()
                    },
                )
            }
            when {
                testing -> Text(MR.strings.desktop_network_testing.localized())
                testResult != null -> Text(requireNotNull(testResult))
            }

            HorizontalDivider()
            Text(MR.strings.desktop_plugin_domains_title.localized(), style = MaterialTheme.typography.titleSmall)
            Text(
                MR.strings.desktop_plugin_domains_notice.localized(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (domains.isEmpty()) {
                Text(MR.strings.desktop_plugin_domains_empty.localized())
            } else {
                Text(
                    MR.strings.desktop_plugin_domains_declared.localized(),
                    style = MaterialTheme.typography.labelLarge,
                )
                if (declaredDomains.isEmpty()) {
                    Text(MR.strings.desktop_plugin_domains_none.localized())
                } else {
                    declaredDomains.sorted().forEach { Text(it) }
                }
                Text(
                    MR.strings.desktop_plugin_domains_observed.localized(),
                    style = MaterialTheme.typography.labelLarge,
                )
                if (observedDomains.isEmpty()) {
                    Text(MR.strings.desktop_plugin_domains_none.localized())
                } else {
                    observedDomains.sorted().forEach { Text(it) }
                }
                OutlinedTextField(
                    value = exportTarget,
                    onValueChange = { exportTargetPreference.set(it) },
                    label = { Text(MR.strings.desktop_plugin_domains_export_target.localized()) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(onClick = { copy(PluginDomainExportFormat.PLAIN, "domain") }) {
                    Text(MR.strings.desktop_plugin_domains_copy_plain.localized())
                }
                OutlinedButton(onClick = { copy(PluginDomainExportFormat.MIHOMO_DOMAIN, "Mihomo") }) {
                    Text(MR.strings.desktop_plugin_domains_copy_mihomo.localized())
                }
                OutlinedButton(onClick = { copy(PluginDomainExportFormat.MIHOMO_SUFFIX, "Mihomo DOMAIN-SUFFIX") }) {
                    Text(MR.strings.desktop_plugin_domains_copy_mihomo_suffix.localized())
                }
                OutlinedButton(onClick = { copy(PluginDomainExportFormat.SING_BOX, "sing-box") }) {
                    Text(MR.strings.desktop_plugin_domains_copy_sing_box.localized())
                }
                OutlinedButton(onClick = { copy(PluginDomainExportFormat.XRAY, "v2rayN/Xray") }) {
                    Text(MR.strings.desktop_plugin_domains_copy_xray.localized())
                }
            }
            OutlinedButton(
                onClick = { observedPreference.delete() },
                enabled = observedDomains.isNotEmpty(),
            ) {
                Text(MR.strings.desktop_plugin_domains_clear.localized())
            }
        }
    }
}

@Composable
private fun pluginNetworkModeLabel(mode: PluginNetworkMode): String = when (mode) {
    PluginNetworkMode.INHERIT_GLOBAL -> MR.strings.desktop_plugin_network_inherit.localized()
    PluginNetworkMode.SYSTEM -> MR.strings.desktop_network_mode_system.localized()
    PluginNetworkMode.DIRECT -> MR.strings.desktop_network_mode_direct.localized()
    PluginNetworkMode.MANUAL -> MR.strings.desktop_network_mode_manual.localized()
}

internal fun extensionVersionCopy(versionName: String, locale: Locale): String =
    "${MR.strings.ext_info_version.localized(locale)}: ${versionName.ifBlank { MR.strings.unknown.localized(locale) }}"

private fun ExtensionOrigin.localizedLabel(): String =
    when (this) {
        ExtensionOrigin.COMPILED_JAR -> MR.strings.desktop_extension_origin_native.localized()
        ExtensionOrigin.CONVERTED_APK -> MR.strings.desktop_extension_origin_converted.localized()
    }
