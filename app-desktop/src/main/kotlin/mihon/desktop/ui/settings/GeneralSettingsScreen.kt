package mihon.desktop.ui.settings

import mihon.desktop.LocalDesktopUiDependencies

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import mihon.desktop.settings.DesktopAppPreferences
import mihon.desktop.settings.DohProvider
import tachiyomi.i18n.MR

class GeneralSettingsScreen : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val prefs = LocalDesktopUiDependencies.current.appPreferences

        val incognito by prefs.incognitoMode.changes().collectAsState(initial = prefs.incognitoMode.get())
        val pageTurnAnim by prefs.pageTurnAnimation.changes().collectAsState(initial = prefs.pageTurnAnimation.get())
        val doh by prefs.dohProvider.changes().collectAsState(initial = prefs.dohProvider.get())

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
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState()),
            ) {
                SwitchSettingsItem(
                    title = MR.strings.pref_incognito_mode.localized(),
                    subtitle = MR.strings.pref_incognito_mode_summary.localized(),
                    checked = incognito,
                    onCheckedChange = { prefs.incognitoMode.set(it) },
                )
                SwitchSettingsItem(
                    title = MR.strings.pref_page_transitions.localized(),
                    subtitle = MR.strings.desktop_general_page_transition_summary.localized(),
                    checked = pageTurnAnim,
                    onCheckedChange = { prefs.pageTurnAnimation.set(it) },
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Text(
                    text = MR.strings.pref_dns_over_https.localized(),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
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
