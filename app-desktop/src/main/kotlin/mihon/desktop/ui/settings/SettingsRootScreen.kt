package mihon.desktop.ui.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import mihon.desktop.ui.tracking.TrackingSettingsScreen
import tachiyomi.i18n.MR

class SettingsRootScreen : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val items = settingsItems()

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(MR.strings.label_settings.localized()) },
                    navigationIcon = {
                        IconButton(onClick = navigator::pop) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = MR.strings.action_bar_up_description.localized(),
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { navigator.push(SettingsSearchScreen()) }) {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = MR.strings.action_search_settings.localized(),
                            )
                        }
                    },
                )
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                items.forEachIndexed { index, settingsItem ->
                    item {
                        SettingsEntry(
                            icon = settingsItem.icon,
                            title = settingsItem.title,
                            subtitle = settingsItem.subtitle,
                            onClick = { navigator.push(settingsItem.route) },
                        )
                        if (index != items.lastIndex) {
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

    private data class SettingsItem(
        val icon: ImageVector,
        val title: String,
        val subtitle: String,
        val route: Screen,
    )

    @Composable
    private fun settingsItems() = listOf(
        SettingsItem(
            Icons.Default.Settings,
            MR.strings.pref_category_general.localized(),
            MR.strings.desktop_more_general_summary.localized(),
            GeneralSettingsScreen(),
        ),
        SettingsItem(
            Icons.Default.Palette,
            MR.strings.pref_category_appearance.localized(),
            MR.strings.pref_appearance_summary.localized(),
            AppearanceSettingsScreen(),
        ),
        SettingsItem(
            Icons.Default.Book,
            MR.strings.pref_category_library.localized(),
            MR.strings.pref_library_summary.localized(),
            LibrarySettingsScreen(),
        ),
        SettingsItem(
            Icons.Default.MenuBook,
            MR.strings.pref_category_reader.localized(),
            MR.strings.pref_reader_summary.localized(),
            ReaderSettingsScreen(),
        ),
        SettingsItem(
            Icons.Default.Download,
            MR.strings.pref_category_downloads.localized(),
            MR.strings.pref_downloads_summary.localized(),
            DownloadSettingsScreen(),
        ),
        SettingsItem(
            Icons.Default.Sync,
            MR.strings.pref_category_tracking.localized(),
            MR.strings.pref_tracking_summary.localized(),
            TrackingSettingsScreen(),
        ),
        SettingsItem(
            Icons.Default.Explore,
            MR.strings.browse.localized(),
            MR.strings.pref_browse_summary.localized(),
            ExtensionRepoScreen(),
        ),
        SettingsItem(
            Icons.Default.SaveAlt,
            MR.strings.label_data_storage.localized(),
            MR.strings.pref_backup_summary.localized(),
            BackupSettingsScreen(),
        ),
        SettingsItem(
            Icons.Default.Lock,
            MR.strings.pref_category_security.localized(),
            MR.strings.pref_security_summary.localized(),
            SecuritySettingsScreen(),
        ),
        SettingsItem(
            Icons.Default.Build,
            MR.strings.pref_category_advanced.localized(),
            MR.strings.pref_advanced_summary.localized(),
            AdvancedSettingsScreen(),
        ),
        SettingsItem(
            Icons.Default.Info,
            MR.strings.pref_category_about.localized(),
            MR.strings.desktop_more_about_summary.localized(),
            AboutScreen(),
        ),
    )
}
