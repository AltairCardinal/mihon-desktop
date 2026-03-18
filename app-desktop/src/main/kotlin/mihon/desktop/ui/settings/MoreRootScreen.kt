package mihon.desktop.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import mihon.desktop.download.DesktopDownloadManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow

class MoreRootScreen : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val downloadManager = remember { Injekt.get<DesktopDownloadManager>() }
        val downloadQueue by downloadManager.queue.collectAsState()
        val activeDownloads = downloadQueue.size

        Scaffold(
            topBar = { TopAppBar(title = { Text("More") }) },
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
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
