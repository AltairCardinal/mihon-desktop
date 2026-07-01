package mihon.desktop.ui.settings

import mihon.desktop.LocalDesktopUiDependencies

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tune
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import mihon.desktop.download.DesktopDownloadManager
import mihon.desktop.test.state.applicationState
import mihon.desktop.ui.extension.ExtensionListScreen
import mihon.desktop.ui.migration.MigrationSearchScreen

class MoreRootScreen : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val downloadManager = LocalDesktopUiDependencies.current.downloadManager
        val downloadQueue by downloadManager.queue.collectAsState()
        val activeDownloads = downloadQueue.size

        // Handle pending screen navigation from test automation
        // Use local state to track if we've handled the pending navigation
        var hasHandledPending by remember { mutableStateOf(false) }

        // Check pending navigation on every composition
        LaunchedEffect(hasHandledPending) {
            if (!hasHandledPending) {
                val screen = TestScreenNavigator.pendingScreen.value
                if (screen != null) {
                    when (screen) {
                        "open_general_settings" -> navigator.push(GeneralSettingsScreen())
                        "open_download_settings" -> navigator.push(DownloadSettingsScreen())
                        "open_backup_settings" -> navigator.push(BackupSettingsScreen())
                        "open_extensions" -> navigator.push(ExtensionListScreen())
                        "open_migration" -> navigator.push(
                            MigrationSearchScreen(sourceMangaId = 0L, sourceMangaTitle = ""),
                        )
                    }
                    TestScreenNavigator.clear()
                    hasHandledPending = true
                }
            }
        }

        Scaffold(
            topBar = { TopAppBar(title = { Text("More") }) },
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                item {
                    SettingsEntry(
                        icon = Icons.Default.Settings,
                        title = "General",
                        subtitle = "Incognito mode, page turn animation",
                        onClick = { navigator.push(GeneralSettingsScreen()) },
                    )
                    HorizontalDivider()
                }
                item {
                    SettingsEntry(
                        icon = Icons.Default.Tune,
                        title = "Download settings",
                        subtitle = "CBZ format, auto-download, delete after read",
                        onClick = { navigator.push(DownloadSettingsScreen()) },
                    )
                    HorizontalDivider()
                }
                item {
                    SettingsEntry(
                        icon = Icons.Default.SaveAlt,
                        title = "Backup and Restore",
                        subtitle = "Create or restore .tachibk backups",
                        onClick = { navigator.push(BackupSettingsScreen()) },
                    )
                    HorizontalDivider()
                }
                item {
                    SettingsEntry(
                        icon = Icons.Default.CloudDownload,
                        title = "Downloads",
                        subtitle = if (activeDownloads > 0) "$activeDownloads in queue" else "Download queue",
                        onClick = { navigator.push(mihon.desktop.ui.download.DownloadQueueScreen()) },
                    )
                    HorizontalDivider()
                }
                item {
                    SettingsEntry(
                        icon = Icons.Default.Palette,
                        title = "Appearance",
                        subtitle = "Theme, colors, grid layout",
                        onClick = { navigator.push(AppearanceSettingsScreen()) },
                    )
                    HorizontalDivider()
                }
                item {
                    SettingsEntry(
                        icon = Icons.Default.MenuBook,
                        title = "Reader",
                        subtitle = "Default reading mode, RTL, zoom",
                        onClick = { navigator.push(ReaderSettingsScreen()) },
                    )
                    HorizontalDivider()
                }
                item {
                    SettingsEntry(
                        icon = Icons.Default.Book,
                        title = "Library",
                        subtitle = "Grid columns, update settings",
                        onClick = { navigator.push(LibrarySettingsScreen()) },
                    )
                    HorizontalDivider()
                }
                item {
                    SettingsEntry(
                        icon = Icons.Default.Extension,
                        title = "Extensions",
                        subtitle = "Manage installed source extensions",
                        onClick = { navigator.push(mihon.desktop.ui.extension.ExtensionListScreen()) },
                    )
                    HorizontalDivider()
                }
                item {
                    SettingsEntry(
                        icon = Icons.Default.Extension,
                        title = "Extension Repos",
                        subtitle = "Add or remove extension repositories",
                        onClick = { navigator.push(ExtensionRepoScreen()) },
                    )
                    HorizontalDivider()
                }
                item {
                    SettingsEntry(
                        icon = Icons.Default.SwapHoriz,
                        title = "Migrate Manga",
                        subtitle = "Move manga to a different source",
                        onClick = { navigator.push(mihon.desktop.ui.migration.MigrationSourceScreen()) },
                    )
                    HorizontalDivider()
                }
                item {
                    SettingsEntry(
                        icon = Icons.Default.BarChart,
                        title = "Statistics",
                        subtitle = "Reading stats: titles, chapters",
                        onClick = { navigator.push(mihon.desktop.ui.more.StatsScreen()) },
                    )
                    HorizontalDivider()
                }
                item {
                    SettingsEntry(
                        icon = Icons.Default.Build,
                        title = "Advanced",
                        subtitle = "Clear cookies, network cache",
                        onClick = { navigator.push(AdvancedSettingsScreen()) },
                    )
                    HorizontalDivider()
                }
                item {
                    SettingsEntry(
                        icon = Icons.Default.Info,
                        title = "About",
                        subtitle = "Version, build info",
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
    subtitle: String,
    onClick: () -> Unit,
) {
    ListItem(
        leadingContent = {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        },
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle, style = MaterialTheme.typography.bodySmall) },
        trailingContent = {
            Icon(Icons.AutoMirrored.Filled.ArrowForwardIos, contentDescription = null)
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}
