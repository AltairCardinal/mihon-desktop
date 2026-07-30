package mihon.desktop.ui.settings

import mihon.desktop.LocalDesktopUiDependencies

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import mihon.desktop.settings.DesktopAppPreferences
import mihon.desktop.settings.DohProvider
import mihon.desktop.settings.GlobalNetworkMode
import mihon.desktop.settings.parseDesktopProxyUrl
import kotlinx.coroutines.launch
import tachiyomi.i18n.MR
import java.util.Locale

class GeneralSettingsScreen : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val prefs = LocalDesktopUiDependencies.current.appPreferences

        val incognito by prefs.incognitoMode.changes().collectAsState(initial = prefs.incognitoMode.get())
        val pageTurnAnim by prefs.pageTurnAnimation.changes().collectAsState(initial = prefs.pageTurnAnimation.get())
        val doh by prefs.dohProvider.changes().collectAsState(initial = prefs.dohProvider.get())
        val networkMode by prefs.globalNetworkMode.changes().collectAsState(initial = prefs.globalNetworkMode.get())
        val proxyUrl by prefs.proxyUrl.changes().collectAsState(initial = prefs.proxyUrl.get())
        val networkHelper = LocalDesktopUiDependencies.current.networkRoutingPort
        val routeFlow = remember(networkHelper) { runCatching { networkHelper.routeObservations }.getOrNull() }
        val routeObservations = routeFlow?.collectAsState()?.value.orEmpty()
        val activeMode = remember(networkHelper) {
            runCatching { networkHelper.activeGlobalMode }.getOrDefault(networkMode)
        }
        val activeProxy = remember(networkHelper) {
            runCatching { networkHelper.activeGlobalProxy }.getOrNull()
        }
        var testUrl by remember { mutableStateOf("https://raw.githubusercontent.com/") }
        var testResult by remember { mutableStateOf<String?>(null) }
        var testing by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        val incognitoTitle = MR.strings.pref_incognito_mode.localized()
        val dnsTitle = MR.strings.pref_dns_over_https.localized()
        val proxyTitle = MR.strings.desktop_general_proxy_title.localized()

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(MR.strings.pref_category_general.localized()) },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = MR.strings.action_bar_up_description.localized(),
                            )
                        }
                    },
                )
            },
        ) { padding ->
            DesktopSettingsAnchorColumn(
                route = this@GeneralSettingsScreen,
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                SwitchSettingsItem(
                    title = incognitoTitle,
                    subtitle = MR.strings.pref_incognito_mode_summary.localized(),
                    checked = incognito,
                    onCheckedChange = { prefs.incognitoMode.set(it) },
                    modifier = Modifier.desktopSettingsAnchor(incognitoTitle),
                )
                SwitchSettingsItem(
                    title = MR.strings.pref_page_transitions.localized(),
                    subtitle = MR.strings.desktop_general_page_transition_summary.localized(),
                    checked = pageTurnAnim,
                    onCheckedChange = { prefs.pageTurnAnimation.set(it) },
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Text(
                    text = proxyTitle,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.desktopSettingsAnchor(proxyTitle).padding(horizontal = 16.dp, vertical = 4.dp),
                )
                Text(
                    text = MR.strings.desktop_general_proxy_summary.localized(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
                GlobalNetworkMode.entries.forEach { mode ->
                    RadioSettingsItem(
                        title = networkModeLabel(mode),
                        selected = networkMode == mode,
                        onClick = { prefs.globalNetworkMode.set(mode) },
                    )
                }
                if (networkMode == GlobalNetworkMode.MANUAL) {
                    val proxyUrlValid = parseDesktopProxyUrl(proxyUrl) != null
                    OutlinedTextField(
                        value = proxyUrl,
                        onValueChange = { prefs.proxyUrl.set(it) },
                        label = { Text(MR.strings.desktop_general_proxy_url.localized()) },
                        placeholder = { Text("http://127.0.0.1:10808") },
                        supportingText = {
                            Text(
                                if (proxyUrlValid) {
                                    MR.strings.desktop_general_proxy_supported_types.localized()
                                } else {
                                    MR.strings.desktop_general_proxy_invalid.localized()
                                },
                            )
                        },
                        isError = !proxyUrlValid,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                val savedLabel = networkModeLabel(networkMode)
                val activeLabel = networkModeLabel(activeMode) +
                    activeProxy?.let { " (${it.type.name} ${it.host}:${it.port})" }.orEmpty()
                Text(
                    text = MR.strings.desktop_network_saved_policy.localized(Locale.getDefault(), savedLabel),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
                Text(
                    text = MR.strings.desktop_network_active_policy.localized(Locale.getDefault(), activeLabel),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
                if (networkMode != activeMode || (networkMode == GlobalNetworkMode.MANUAL && prefs.proxyRuntimeConfig() != activeProxy)) {
                    Text(
                        text = MR.strings.desktop_network_restart_pending.localized(),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                    )
                }
                val lastRoute = routeObservations.lastOrNull { it.scope == "mihon-global" }
                Text(
                    text = lastRoute?.let {
                        val route = "${it.host} → ${it.proxyType.name}${it.proxyAddress?.let { address -> " $address" }.orEmpty()}"
                        MR.strings.desktop_network_last_route.localized(Locale.getDefault(), route)
                    } ?: MR.strings.desktop_network_no_route.localized(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
                OutlinedTextField(
                    value = testUrl,
                    onValueChange = { testUrl = it },
                    label = { Text(MR.strings.desktop_network_test_url.localized()) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
                OutlinedButton(
                    enabled = !testing,
                    onClick = {
                        testing = true
                        scope.launch {
                            val result = networkHelper.testConnection(testUrl)
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
                            testing = false
                        }
                    },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Text(MR.strings.desktop_network_test.localized())
                }
                testResult?.let {
                    Text(it, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Text(
                    text = dnsTitle,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.desktopSettingsAnchor(dnsTitle).padding(horizontal = 16.dp, vertical = 4.dp),
                )
                Text(
                    text = MR.strings.desktop_general_doh_restart_summary.localized(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )

                DohProvider.entries.forEach { provider ->
                    RadioSettingsItem(
                        title = when (provider) {
                            DohProvider.OFF -> MR.strings.desktop_general_system_dns.localized()
                            DohProvider.GOOGLE -> MR.strings.desktop_general_google_dns.localized()
                            DohProvider.CLOUDFLARE -> MR.strings.desktop_general_cloudflare_dns.localized()
                            DohProvider.ADGUARD -> MR.strings.desktop_general_adguard_dns.localized()
                        },
                        selected = doh == provider,
                        onClick = { prefs.dohProvider.set(provider) },
                    )
                }
            }
        }
    }
}

@Composable
private fun networkModeLabel(mode: GlobalNetworkMode): String = when (mode) {
    GlobalNetworkMode.SYSTEM -> MR.strings.desktop_network_mode_system.localized()
    GlobalNetworkMode.DIRECT -> MR.strings.desktop_network_mode_direct.localized()
    GlobalNetworkMode.MANUAL -> MR.strings.desktop_network_mode_manual.localized()
}
