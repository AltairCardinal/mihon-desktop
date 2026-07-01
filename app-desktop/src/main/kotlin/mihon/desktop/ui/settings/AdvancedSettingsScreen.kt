package mihon.desktop.ui.settings

import mihon.desktop.LocalDesktopUiDependencies

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import mihon.desktop.network.CF_CLEARANCE_COOKIE_NAME
import mihon.desktop.network.CookieImportResult
import mihon.desktop.network.validateCloudflareCookieInput
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

class AdvancedSettingsScreen : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val networkHelper = LocalDesktopUiDependencies.current.networkHelper
        val paths = remember { DesktopPlatformPaths.current() }
        val scope = rememberCoroutineScope()

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

        // Compute network cache size once (and refresh after clearing)
        val cacheSize by produceState(initialValue = "", cacheCleared) {
            value = withContext(Dispatchers.IO) {
                val cacheDir = paths.networkCacheDir
                if (cacheDir.exists()) formatBytes(cacheDir.walkTopDown().sumOf { it.length() })
                else "0 B"
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Advanced") },
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
                    .verticalScroll(rememberScrollState()),
            ) {
                // Section header
                Text(
                    text = "Network",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )

                ListItem(
                    headlineContent = { Text("Clear cookies") },
                    supportingContent = {
                        Text(
                            if (cookiesCleared) "Cookies cleared" else "Remove all stored cookies",
                        )
                    },
                )
                TextButton(
                    onClick = { showClearCookiesDialog = true },
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    Text("Clear cookies")
                }

                HorizontalDivider()

                ListItem(
                    headlineContent = { Text("Network cache size") },
                    supportingContent = { Text(if (cacheSize.isNotEmpty()) cacheSize else "Calculating…") },
                )
                TextButton(
                    onClick = { showClearCacheDialog = true },
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    Text("Clear network cache")
                }

                HorizontalDivider()

                ListItem(
                    headlineContent = { Text("Crash log folder") },
                    supportingContent = { Text(CrashHandler.defaultCrashLogDir().path) },
                )
                TextButton(
                    onClick = {
                        scope.launch {
                            val opened = withContext(Dispatchers.IO) {
                                DesktopDirectoryOpener.open(CrashHandler.defaultCrashLogDir())
                            }
                            snackbar.showSnackbar(
                                if (opened) {
                                    "Crash log folder opened"
                                } else {
                                    "Could not open crash log folder"
                                },
                            )
                        }
                    },
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    Text("Open crash log folder")
                }

                HorizontalDivider()

                // Cloudflare bypass section
                Text(
                    text = "Cloudflare Bypass",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                Text(
                    text = "If a source shows a Cloudflare challenge, solve it in a browser, then paste the cf_clearance cookie here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                )
                OutlinedTextField(
                    value = cfDomain,
                    onValueChange = { cfDomain = it; cfDomainError = null },
                    label = { Text("Site domain (e.g. example.com)") },
                    isError = cfDomainError != null,
                    supportingText = if (cfDomainError != null) {{ Text(cfDomainError!!) }} else null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = cfCookieValue,
                    onValueChange = { cfCookieValue = it; cfValueError = null },
                    label = { Text("cf_clearance value") },
                    isError = cfValueError != null,
                    supportingText = if (cfValueError != null) {{ Text(cfValueError!!) }} else null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        when (val result = validateCloudflareCookieInput(cfDomain, cfCookieValue)) {
                            is CookieImportResult.InvalidDomain -> cfDomainError = "Invalid domain"
                            is CookieImportResult.InvalidValue -> cfValueError = "Cookie value must not be blank"
                            is CookieImportResult.Valid -> {
                                val url = "https://${result.domain}".toHttpUrlOrNull()
                                if (url != null) {
                                    networkHelper.cookieJar.addManual(url, CF_CLEARANCE_COOKIE_NAME, result.value)
                                    cfDomain = ""
                                    cfCookieValue = ""
                                    scope.launch { snackbar.showSnackbar("Cookie imported for ${result.domain}") }
                                } else {
                                    cfDomainError = "Could not parse domain"
                                }
                            }
                        }
                    },
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    Text("Import Cookie")
                }
                Spacer(Modifier.height(8.dp))
            }
        }

        if (showClearCookiesDialog) {
            AlertDialog(
                onDismissRequest = { showClearCookiesDialog = false },
                title = { Text("Clear cookies") },
                text = { Text("This will remove all stored cookies. You may need to re-solve CAPTCHA challenges.") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showClearCookiesDialog = false
                            networkHelper.cookieJar.clear()
                            cookiesCleared = true
                        },
                    ) { Text("Clear") }
                },
                dismissButton = {
                    TextButton(onClick = { showClearCookiesDialog = false }) { Text("Cancel") }
                },
            )
        }

        if (showClearCacheDialog) {
            AlertDialog(
                onDismissRequest = { showClearCacheDialog = false },
                title = { Text("Clear network cache") },
                text = { Text("This will delete all cached HTTP responses. Pages may load more slowly until the cache is rebuilt.") },
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
                    ) { Text("Clear") }
                },
                dismissButton = {
                    TextButton(onClick = { showClearCacheDialog = false }) { Text("Cancel") }
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
