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
import mihon.desktop.download.DesktopDownloadPreferences
import tachiyomi.i18n.MR

class DownloadSettingsScreen : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val prefs = LocalDesktopUiDependencies.current.downloadPreferences

        val downloadAsCbz by prefs.downloadAsCbz.changes().collectAsState(initial = prefs.downloadAsCbz.get())
        val autoDownload by prefs.autoDownloadNewChapters.changes().collectAsState(initial = prefs.autoDownloadNewChapters.get())
        val deleteAfterRead by prefs.deleteAfterRead.changes().collectAsState(initial = prefs.deleteAfterRead.get())
        val parallelLimit by prefs.parallelDownloadLimit.changes().collectAsState(initial = prefs.parallelDownloadLimit.get())

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(MR.strings.pref_category_downloads.localized()) },
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
                    title = MR.strings.save_chapter_as_cbz.localized(),
                    subtitle = MR.strings.desktop_download_cbz_summary.localized(),
                    checked = downloadAsCbz,
                    onCheckedChange = { prefs.downloadAsCbz.set(it) },
                )
                SwitchSettingsItem(
                    title = MR.strings.pref_download_new.localized(),
                    subtitle = MR.strings.desktop_download_new_chapters_summary.localized(),
                    checked = autoDownload,
                    onCheckedChange = { prefs.autoDownloadNewChapters.set(it) },
                )
                SwitchSettingsItem(
                    title = MR.strings.pref_remove_after_read.localized(),
                    subtitle = MR.strings.desktop_download_delete_after_read_summary.localized(),
                    checked = deleteAfterRead,
                    onCheckedChange = { prefs.deleteAfterRead.set(it) },
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Text(
                    text = MR.strings.desktop_download_parallel.localized(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                (1..5).forEach { limit ->
                    RadioSettingsItem(
                        title = if (limit == 1) MR.strings.desktop_download_sequential.localized() else "$limit",
                        selected = parallelLimit == limit,
                        onClick = { prefs.parallelDownloadLimit.set(limit) },
                    )
                }
            }
        }
    }
}
