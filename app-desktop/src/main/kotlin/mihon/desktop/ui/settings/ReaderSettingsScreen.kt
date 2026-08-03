package mihon.desktop.ui.settings

import mihon.desktop.LocalDesktopUiDependencies

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import mihon.desktop.settings.ReaderDefaultMode
import mihon.desktop.reader.NextChapterPrefetchMode
import tachiyomi.i18n.MR

class ReaderSettingsScreen : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val dependencies = LocalDesktopUiDependencies.current
        val prefs = dependencies.appPreferences
        val readerPreferences = dependencies.readerPreferences
        var readerMode by remember { mutableStateOf(prefs.defaultReaderMode.get()) }
        var isRtl by remember { mutableStateOf(prefs.defaultRtl.get()) }
        val nextChapterPrefetchMode by readerPreferences.nextChapterPrefetchPreference.changes().collectAsState(
            initial = readerPreferences.nextChapterPrefetchMode,
        )
        val viewerTypeTitle = MR.strings.pref_viewer_type.localized()
        val prefetchTitle = MR.strings.desktop_reader_prefetch_next_chapter.localized()

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(MR.strings.pref_category_reader.localized()) },
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
                route = this@ReaderSettingsScreen,
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                Text(
                    text = viewerTypeTitle,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.desktopSettingsAnchor(viewerTypeTitle).padding(horizontal = 16.dp, vertical = 8.dp),
                )
                ReaderDefaultMode.entries.forEach { mode ->
                    val modeTitle = when (mode) {
                        ReaderDefaultMode.PAGER -> MR.strings.desktop_reader_pager_mode.localized()
                        ReaderDefaultMode.WEBTOON -> MR.strings.desktop_reader_webtoon_mode.localized()
                    }
                    RadioSettingsItem(
                        title = modeTitle,
                        selected = readerMode == mode,
                        onClick = {
                            readerMode = mode
                            prefs.defaultReaderMode.set(mode)
                        },
                        modifier = Modifier.desktopSettingsAnchor(modeTitle),
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                SwitchSettingsItem(
                    title = MR.strings.desktop_reader_rtl.localized(),
                    subtitle = MR.strings.desktop_reader_rtl_summary.localized(),
                    checked = isRtl,
                    onCheckedChange = {
                        isRtl = it
                        prefs.defaultRtl.set(it)
                    },
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    text = prefetchTitle,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.desktopSettingsAnchor(prefetchTitle).padding(horizontal = 16.dp, vertical = 8.dp),
                )
                Text(
                    text = MR.strings.desktop_reader_prefetch_summary.localized(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                NextChapterPrefetchMode.entries.forEach { mode ->
                    RadioSettingsItem(
                        title = nextChapterPrefetchLabel(mode),
                        selected = nextChapterPrefetchMode == mode,
                        onClick = { readerPreferences.nextChapterPrefetchMode = mode },
                    )
                }
            }
        }
    }
}

private fun nextChapterPrefetchLabel(mode: NextChapterPrefetchMode): String = when (mode) {
    NextChapterPrefetchMode.OFF -> MR.strings.off.localized()
    NextChapterPrefetchMode.FIRST_VIEWPORT -> MR.strings.desktop_reader_prefetch_first_viewport.localized()
    NextChapterPrefetchMode.FULL_NEXT_CHAPTER -> MR.strings.desktop_reader_prefetch_full_next_chapter.localized()
}
