package mihon.desktop.ui.settings

import mihon.desktop.LocalDesktopUiDependencies

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.StringResource
import mihon.desktop.network.CF_CLEARANCE_COOKIE_NAME
import mihon.desktop.network.CookieImportResult
import mihon.desktop.network.validateCloudflareCookieInput
import mihon.desktop.settings.DesktopAppPreferences
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.desktop.CrashHandler
import mihon.desktop.platform.DesktopNetworkHelper
import mihon.desktop.platform.DesktopPlatformPaths
import tachiyomi.i18n.MR
import java.util.Locale

internal enum class FlareSolverrUrlError {
    Required,
    Invalid,
}

internal data class FlareSolverrSettingsState(
    val enabled: Boolean,
    val url: String,
    val solverAvailable: Boolean,
    val urlError: FlareSolverrUrlError?,
)

internal data class FlareSolverrSettingsSection(
    val copy: CloudflareSettingsCopy,
    val switch: FlareSolverrSwitchItem,
    val url: FlareSolverrUrlItem,
)

internal data class FlareSolverrSwitchItem(
    val title: String,
    val subtitle: String,
    val checked: Boolean,
    val onCheckedChange: (Boolean) -> Unit,
)

internal data class FlareSolverrUrlItem(
    val label: String,
    val value: String,
    val error: String?,
    val onValueChange: (String) -> Unit,
)

internal data class CloudflareSettingsCopy(
    val title: String,
    val description: String,
    val domainLabel: String,
    val cookieLabel: String,
    val invalidDomain: String,
    val cookieRequired: String,
    val domainParseFailed: String,
    val submit: String,
    val clearTitle: String,
    val clearSummary: String,
    val clearedFeedback: String,
    val clearWarning: String,
    val clearConfirm: String,
    val cancel: String,
)

internal fun flareSolverrSettingsState(preferences: DesktopAppPreferences): FlareSolverrSettingsState {
    val enabled = preferences.flareSolverrEnabled.get()
    val url = preferences.flareSolverrUrl.get()
    val solverAvailable = preferences.flareSolverrRuntimeConfig() != null
    return FlareSolverrSettingsState(
        enabled = enabled,
        url = url,
        solverAvailable = solverAvailable,
        urlError = when {
            !enabled -> null
            url.isBlank() -> FlareSolverrUrlError.Required
            !solverAvailable -> FlareSolverrUrlError.Invalid
            else -> null
        },
    )
}

internal fun updateFlareSolverrEnabled(
    preferences: DesktopAppPreferences,
    enabled: Boolean,
): FlareSolverrSettingsState {
    preferences.flareSolverrEnabled.set(enabled)
    return flareSolverrSettingsState(preferences)
}

internal fun updateFlareSolverrUrl(
    preferences: DesktopAppPreferences,
    url: String,
): FlareSolverrSettingsState {
    preferences.flareSolverrUrl.set(url)
    return flareSolverrSettingsState(preferences)
}

internal fun flareSolverrSettingsSection(
    preferences: DesktopAppPreferences,
    state: FlareSolverrSettingsState,
    text: (StringResource) -> String,
    onStateChanged: (FlareSolverrSettingsState) -> Unit,
) = FlareSolverrSettingsSection(
    copy = CloudflareSettingsCopy(
        text(MR.strings.desktop_settings_cloudflare_title),
        text(MR.strings.desktop_settings_cloudflare_description),
        text(MR.strings.desktop_settings_cloudflare_domain),
        text(MR.strings.desktop_challenge_manual_cookie),
        text(MR.strings.desktop_settings_cloudflare_invalid_domain),
        text(MR.strings.desktop_settings_cloudflare_cookie_required),
        text(MR.strings.desktop_settings_cloudflare_domain_parse_failed),
        text(MR.strings.desktop_challenge_manual_submit),
        text(MR.strings.pref_clear_cookies),
        text(MR.strings.desktop_settings_clear_cookies_summary),
        text(MR.strings.cookies_cleared),
        text(MR.strings.desktop_settings_clear_cookies_warning),
        text(MR.strings.desktop_settings_clear_cookies_confirm),
        text(MR.strings.action_cancel),
    ),
    switch = FlareSolverrSwitchItem(
        text(MR.strings.desktop_settings_cloudflare_solver_title),
        text(MR.strings.desktop_settings_cloudflare_solver_explicit_only),
        state.enabled,
    ) { onStateChanged(updateFlareSolverrEnabled(preferences, it)) },
    url = FlareSolverrUrlItem(
        text(MR.strings.desktop_settings_cloudflare_solver_url),
        state.url,
        when (state.urlError) {
            FlareSolverrUrlError.Required -> text(MR.strings.desktop_settings_cloudflare_solver_url_required)
            FlareSolverrUrlError.Invalid -> text(MR.strings.desktop_settings_cloudflare_solver_url_invalid)
            null -> null
        },
    ) { onStateChanged(updateFlareSolverrUrl(preferences, it)) },
)

@Composable
internal fun FlareSolverrSettingsSectionContent(
    section: FlareSolverrSettingsSection,
    renderSwitch: @Composable (FlareSolverrSwitchItem) -> Unit,
    renderUrl: @Composable (FlareSolverrUrlItem) -> Unit,
) {
    renderSwitch(section.switch)
    renderUrl(section.url)
}

internal fun cloudflareCookieImportedFeedback(url: okhttp3.HttpUrl, locale: Locale): String =
    MR.strings.desktop_settings_cloudflare_cookie_imported.localized(locale, url.host)

internal interface AdvancedSettingsPlatformActions {
    suspend fun loadNetworkCacheSize(): String
    suspend fun openCrashLogFolder(): Boolean
}

private object ProductionAdvancedSettingsPlatformActions : AdvancedSettingsPlatformActions {
    override suspend fun loadNetworkCacheSize(): String {
        val cacheDir = DesktopPlatformPaths.current().networkCacheDir
        return if (cacheDir.exists()) formatBytes(cacheDir.walkTopDown().sumOf { it.length() }) else "0 B"
    }

    override suspend fun openCrashLogFolder(): Boolean = DesktopDirectoryOpener.open(CrashHandler.defaultCrashLogDir())
}

internal val LocalAdvancedSettingsPlatformActions =
    staticCompositionLocalOf<AdvancedSettingsPlatformActions> { ProductionAdvancedSettingsPlatformActions }

class AdvancedSettingsScreen : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val dependencies = LocalDesktopUiDependencies.current
        val networkHelper = dependencies.networkHelper
        val preferences = dependencies.appPreferences
        val platformActions = LocalAdvancedSettingsPlatformActions.current
        val paths = remember { DesktopPlatformPaths.current() }
        val scope = rememberCoroutineScope()
        val locale = remember { Locale.getDefault() }
        val text: (StringResource) -> String = { it.localized(locale) }
        val crashLogActionTitle = text(DesktopSettingsAnchorResources.advancedCrashLog)

        val snackbar = remember { SnackbarHostState() }
        var showClearCookiesDialog by remember { mutableStateOf(false) }
        var showClearCacheDialog by remember { mutableStateOf(false) }
        var cookiesCleared by remember { mutableStateOf(false) }
        var cacheCleared by remember { mutableStateOf(false) }

        // Cloudflare manual cookie import state
        var cfDomain by remember { mutableStateOf("") }
        var cfCookieValue by remember { mutableStateOf("") }
        var cfDomainError by remember { mutableStateOf<String?>(null) }
        var cfValueError by remember { mutableStateOf<String?>(null) }
        var solverSettings by remember(preferences) {
            mutableStateOf(flareSolverrSettingsState(preferences))
        }
        val cloudflare = flareSolverrSettingsSection(preferences, solverSettings, text) {
            solverSettings = it
        }

        // Compute network cache size once (and refresh after clearing)
        val cacheSize by produceState(initialValue = "", cacheCleared) {
            value = withContext(Dispatchers.IO) { platformActions.loadNetworkCacheSize() }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(text(MR.strings.pref_category_advanced)) },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = text(MR.strings.action_bar_up_description),
                            )
                        }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            DesktopSettingsAnchorColumn(
                route = this@AdvancedSettingsScreen,
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                // Section header
                Text(
                    text = text(MR.strings.label_network),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )

                ListItem(
                    headlineContent = { Text(cloudflare.copy.clearTitle) },
                    supportingContent = {
                        Text(if (cookiesCleared) cloudflare.copy.clearedFeedback else cloudflare.copy.clearSummary)
                    },
                )
                TextButton(
                    onClick = { showClearCookiesDialog = true },
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    Text(cloudflare.copy.clearTitle)
                }

                HorizontalDivider()

                ListItem(
                    headlineContent = { Text(text(MR.strings.desktop_advanced_network_cache_size)) },
                    supportingContent = {
                        Text(if (cacheSize.isNotEmpty()) cacheSize else text(MR.strings.desktop_advanced_calculating))
                    },
                )
                TextButton(
                    onClick = { showClearCacheDialog = true },
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    Text(text(MR.strings.desktop_advanced_clear_network_cache))
                }

                HorizontalDivider()

                ListItem(
                    headlineContent = { Text(text(MR.strings.desktop_advanced_crash_log_folder)) },
                    supportingContent = { Text(CrashHandler.defaultCrashLogDir().path) },
                )
                TextButton(
                    onClick = {
                        scope.launch {
                            val opened = withContext(Dispatchers.IO) { platformActions.openCrashLogFolder() }
                            snackbar.showSnackbar(
                                if (opened) {
                                    text(MR.strings.desktop_advanced_crash_log_opened)
                                } else {
                                    text(MR.strings.desktop_advanced_crash_log_open_failed)
                                },
                            )
                        }
                    },
                    modifier = Modifier.padding(horizontal = 16.dp).desktopSettingsAnchor(crashLogActionTitle),
                ) {
                    Text(crashLogActionTitle)
                }

                HorizontalDivider()

                // Cloudflare bypass section
                Text(
                    text = cloudflare.copy.title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                Text(
                    text = cloudflare.copy.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                )
                FlareSolverrSettingsSectionContent(
                    section = cloudflare,
                    renderSwitch = { item ->
                        SwitchSettingsItem(
                            title = item.title,
                            subtitle = item.subtitle,
                            checked = item.checked,
                            onCheckedChange = item.onCheckedChange,
                        )
                    },
                    renderUrl = { item ->
                        OutlinedTextField(
                            value = item.value,
                            onValueChange = item.onValueChange,
                            label = { Text(item.label) },
                            isError = item.error != null,
                            supportingText = item.error?.let { error ->
                                { Text(error) }
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        )
                    },
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = cfDomain,
                    onValueChange = { cfDomain = it; cfDomainError = null },
                    label = { Text(cloudflare.copy.domainLabel) },
                    isError = cfDomainError != null,
                    supportingText = if (cfDomainError != null) {{ Text(cfDomainError!!) }} else null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = cfCookieValue,
                    onValueChange = { cfCookieValue = it; cfValueError = null },
                    label = { Text(cloudflare.copy.cookieLabel) },
                    isError = cfValueError != null,
                    supportingText = if (cfValueError != null) {{ Text(cfValueError!!) }} else null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        when (val result = validateCloudflareCookieInput(cfDomain, cfCookieValue)) {
                            is CookieImportResult.InvalidDomain -> cfDomainError = cloudflare.copy.invalidDomain
                            is CookieImportResult.InvalidValue -> cfValueError = cloudflare.copy.cookieRequired
                            is CookieImportResult.Valid -> {
                                val url = "https://${result.domain}".toHttpUrlOrNull()
                                if (url != null) {
                                    networkHelper.cookieJar.addManual(url, CF_CLEARANCE_COOKIE_NAME, result.value)
                                    cfDomain = ""
                                    cfCookieValue = ""
                                    scope.launch { snackbar.showSnackbar(cloudflareCookieImportedFeedback(url, locale)) }
                                } else {
                                    cfDomainError = cloudflare.copy.domainParseFailed
                                }
                            }
                        }
                    },
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    Text(cloudflare.copy.submit)
                }
                Spacer(Modifier.height(8.dp))
            }
        }

        if (showClearCookiesDialog) {
            AlertDialog(
                onDismissRequest = { showClearCookiesDialog = false },
                title = { Text(cloudflare.copy.clearTitle) },
                text = { Text(cloudflare.copy.clearWarning) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showClearCookiesDialog = false
                            networkHelper.cookieJar.clear()
                            cookiesCleared = true
                        },
                    ) { Text(cloudflare.copy.clearConfirm) }
                },
                dismissButton = {
                    TextButton(onClick = { showClearCookiesDialog = false }) { Text(cloudflare.copy.cancel) }
                },
            )
        }

        if (showClearCacheDialog) {
            AlertDialog(
                onDismissRequest = { showClearCacheDialog = false },
                title = { Text(text(MR.strings.desktop_advanced_clear_network_cache)) },
                text = { Text(text(MR.strings.desktop_advanced_clear_network_cache_warning)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showClearCacheDialog = false
                            scope.launch(Dispatchers.IO) {
                                paths.networkCacheDir.deleteRecursively()
                                withContext(Dispatchers.Main) {
                                    cacheCleared = !cacheCleared // trigger produceState refresh
                                }
                            }
                        },
                    ) { Text(text(MR.strings.desktop_advanced_clear_confirm)) }
                },
                dismissButton = {
                    TextButton(onClick = { showClearCacheDialog = false }) { Text(text(MR.strings.action_cancel)) }
                },
            )
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
