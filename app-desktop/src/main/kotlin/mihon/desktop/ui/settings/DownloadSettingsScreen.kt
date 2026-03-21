package mihon.desktop.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import mihon.desktop.download.DesktopDownloadPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class DownloadSettingsScreen : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val prefs = remember { Injekt.get<DesktopDownloadPreferences>() }

        val downloadAsCbz by prefs.downloadAsCbz.changes().collectAsState(initial = prefs.downloadAsCbz.get())
        val autoDownload by prefs.autoDownloadNewChapters.changes().collectAsState(initial = prefs.autoDownloadNewChapters.get())
        val deleteAfterRead by prefs.deleteAfterRead.changes().collectAsState(initial = prefs.deleteAfterRead.get())

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Downloads") },
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
                    .padding(padding),
            ) {
                SwitchSettingsItem(
                    title = "Save as CBZ",
                    subtitle = "Package downloaded chapters as .cbz archives (comic book zip)",
                    checked = downloadAsCbz,
                    onCheckedChange = { prefs.downloadAsCbz.set(it) },
                )
                SwitchSettingsItem(
                    title = "Auto-download new chapters",
                    subtitle = "Automatically download chapters when updates are found",
                    checked = autoDownload,
                    onCheckedChange = { prefs.autoDownloadNewChapters.set(it) },
                )
                SwitchSettingsItem(
                    title = "Delete after read",
                    subtitle = "Remove downloaded files after a chapter is fully read",
                    checked = deleteAfterRead,
                    onCheckedChange = { prefs.deleteAfterRead.set(it) },
                )
            }
        }
    }
}
