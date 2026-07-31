package mihon.desktop.ui.settings

import mihon.desktop.LocalDesktopUiDependencies

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import mihon.desktop.test.state.applicationState
import mihon.desktop.ui.extension.extensionListDestination
import mihon.desktop.ui.migration.MigrationSearchScreen
import tachiyomi.i18n.MR
import java.util.Locale

class MoreRootScreen : Screen {

    internal fun onExtensions(navigator: Navigator) {
        navigator.push(extensionListDestination())
    }

    internal fun onTracking(navigator: Navigator) {
        mihon.desktop.ui.tracking.pushTrackingSettings(navigator)
    }

    internal fun onSecurity(navigator: Navigator) {
        navigator.push(SecuritySettingsScreen())
    }

    companion object {
        fun backupSettingsDestination(): Screen = BackupSettingsScreen()
        fun settingsDestination(): Screen = SettingsRootScreen()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val downloadQueuePort = LocalDesktopUiDependencies.current.downloadQueuePort
        val preferences = LocalDesktopUiDependencies.current.appPreferences
        val downloadQueue by downloadQueuePort.queue.collectAsState()
        val incognito by preferences.incognitoMode.changes().collectAsState(initial = preferences.incognitoMode.get())
        val activeDownloads = downloadQueue.size

        // Observe pending screen navigation from test automation after this screen is mounted.
        val pendingScreen by TestScreenNavigator.pendingScreen.collectAsState()

        LaunchedEffect(pendingScreen) {
            if (pendingScreen != null) {
                val screen = pendingScreen
                if (screen != null) {
                    when (screen) {
                        "open_settings" -> navigator.push(SettingsRootScreen())
                        "open_general_settings" -> navigator.push(GeneralSettingsScreen())
                        "open_download_settings" -> navigator.push(DownloadSettingsScreen())
                        "open_backup_settings" -> navigator.push(BackupSettingsScreen())
                        "open_extensions" -> onExtensions(navigator)
                        "open_migration" -> navigator.push(
                            MigrationSearchScreen(sourceMangaId = 0L, sourceMangaTitle = ""),
                        )
                        "open_tracking" -> onTracking(navigator)
                        "open_security_settings" -> onSecurity(navigator)
                    }
                    TestScreenNavigator.clear()
                }
            }
        }

        Scaffold(
            topBar = { TopAppBar(title = { Text(MR.strings.label_more.localized()) }) },
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                item {
                    SwitchSettingsItem(
                        title = MR.strings.pref_incognito_mode.localized(),
                        subtitle = MR.strings.pref_incognito_mode_summary.localized(),
                        checked = incognito,
                        onCheckedChange = preferences.incognitoMode::set,
                    )
                    HorizontalDivider()
                }
                item {
                    SettingsEntry(
                        icon = Icons.Default.CloudDownload,
                        title = MR.strings.label_download_queue.localized(),
                        subtitle = if (activeDownloads > 0) {
                            MR.strings.desktop_more_download_queue_count.localized(Locale.getDefault(), activeDownloads)
                        } else {
                            MR.strings.label_download_queue.localized()
                        },
                        onClick = { navigator.push(mihon.desktop.ui.download.DownloadQueueScreen()) },
                    )
                    HorizontalDivider()
                }
                item {
                    SettingsEntry(
                        icon = Icons.Default.SwapHoriz,
                        title = MR.strings.label_migration.localized(),
                        subtitle = MR.strings.desktop_more_migration_summary.localized(),
                        onClick = { navigator.push(mihon.desktop.ui.migration.MigrationSourceScreen()) },
                    )
                    HorizontalDivider()
                }
                item {
                    SettingsEntry(
                        icon = Icons.Default.BarChart,
                        title = MR.strings.label_stats.localized(),
                        subtitle = MR.strings.desktop_more_stats_summary.localized(),
                        onClick = { navigator.push(mihon.desktop.ui.more.StatsScreen()) },
                    )
                    HorizontalDivider()
                }
                item {
                    SettingsEntry(
                        icon = Icons.Default.Settings,
                        title = MR.strings.label_settings.localized(),
                        subtitle = null,
                        onClick = { navigator.push(settingsDestination()) },
                    )
                    HorizontalDivider()
                }
                item {
                    SettingsEntry(
                        icon = Icons.Default.Info,
                        title = MR.strings.pref_category_about.localized(),
                        subtitle = MR.strings.desktop_more_about_summary.localized(),
                        onClick = { navigator.push(AboutScreen()) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun SettingsEntry(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
) {
    ListItem(
        leadingContent = {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        },
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it, style = MaterialTheme.typography.bodySmall) } },
        trailingContent = {
            Icon(Icons.AutoMirrored.Filled.ArrowForwardIos, contentDescription = null)
        },
        modifier = Modifier
            .semantics(mergeDescendants = true) {}
            .desktopSettingsAction(Role.Button, onClick),
    )
}
