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
                    title = { Text("General") },
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
                    .verticalScroll(rememberScrollState()),
            ) {
                SwitchSettingsItem(
                    title = "Incognito mode",
                    subtitle = "Pause reading history while enabled",
                    checked = incognito,
                    onCheckedChange = { prefs.incognitoMode.set(it) },
                )
                SwitchSettingsItem(
                    title = "Page turn animation",
                    subtitle = "Show page-flip animation when navigating",
                    checked = pageTurnAnim,
                    onCheckedChange = { prefs.pageTurnAnimation.set(it) },
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Text(
                    text = "DNS over HTTPS",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                Text(
                    text = "Takes effect on next launch",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )

                DohProvider.entries.forEach { provider ->
                    RadioSettingsItem(
                        title = when (provider) {
                            DohProvider.OFF -> "System DNS (off)"
                            DohProvider.GOOGLE -> "Google (dns.google)"
                            DohProvider.CLOUDFLARE -> "Cloudflare (1.1.1.1)"
                            DohProvider.ADGUARD -> "AdGuard DNS"
                        },
                        selected = doh == provider,
                        onClick = { prefs.dohProvider.set(provider) },
                    )
                }
            }
        }
    }
}
