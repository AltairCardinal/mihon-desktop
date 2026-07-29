package mihon.desktop.ui.more

import tachiyomi.i18n.MR

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.LocalLibrary
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import mihon.desktop.LocalDesktopUiDependencies
import tachiyomi.domain.library.interactor.LibraryStats

class StatsScreen : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val snapshots = LocalDesktopUiDependencies.current.getLibraryManga.subscribe()
        val model = rememberScreenModel { StatsScreenModel(snapshots) }
        val state by model.state.collectAsState()
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(MR.strings.label_stats.localized()) },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, MR.strings.action_bar_up_description.localized())
                        }
                    },
                )
            },
        ) { padding ->
            when (val current = state) {
                StatsUiState.Loading -> Centered(Modifier.padding(padding)) { CircularProgressIndicator() }
                is StatsUiState.Error -> Centered(Modifier.padding(padding)) { Text(MR.strings.desktop_ui_unable_to_load_statistics.localized()) }
                is StatsUiState.Content -> StatsContent(current.stats, Modifier.padding(padding))
            }
        }
    }
}

@Composable
private fun Centered(modifier: Modifier, content: @Composable () -> Unit) = Column(
    modifier.fillMaxSize(),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
) { content() }

@Composable
private fun StatsContent(stats: LibraryStats, modifier: Modifier) {
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            StatsSection(MR.strings.label_library.localized())
            StatsRow {
                StatsOverviewItem(stats.mangaCount.toString(), MR.strings.desktop_ui_titles.localized(), Icons.Default.CollectionsBookmark)
                StatsOverviewItem(stats.readChapters.toString(), MR.strings.desktop_ui_chapters_read.localized(), Icons.Default.MenuBook)
                StatsOverviewItem(stats.startedCount.toString(), MR.strings.desktop_ui_started.localized(), Icons.Default.LocalLibrary)
            }
            HorizontalDivider()
        }
        item {
            StatsSection(MR.strings.desktop_ui_titles.localized())
            StatsRow {
                StatsItem(stats.startedCount.toString(), MR.strings.desktop_ui_started.localized())
                StatsItem(stats.bySource.size.toString(), MR.strings.label_sources.localized())
                StatsItem((stats.mangaCount - stats.startedCount).toString(), MR.strings.desktop_ui_not_started.localized())
            }
            HorizontalDivider()
        }
        item {
            StatsSection(MR.strings.chapters.localized())
            StatsRow {
                StatsItem(stats.totalChapters.toString(), MR.strings.desktop_ui_total.localized())
                StatsItem(stats.readChapters.toString(), MR.strings.label_read_chapters.localized())
                StatsItem((stats.totalChapters - stats.readChapters).toString(), MR.strings.unread.localized())
            }
        }
    }
}

@Composable private fun StatsRow(content: @Composable () -> Unit) = Row(
    Modifier.fillMaxWidth().padding(vertical = 12.dp),
    horizontalArrangement = Arrangement.SpaceEvenly,
) { content() }

@Composable private fun StatsSection(title: String) = Text(
    title,
    style = MaterialTheme.typography.titleSmall,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
)

@Composable private fun StatsOverviewItem(title: String, subtitle: String, icon: ImageVector) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleLarge)
        StatSubtitle(subtitle)
    }
}

@Composable private fun StatsItem(title: String, subtitle: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        StatSubtitle(subtitle)
    }
}

@Composable private fun StatSubtitle(text: String) = Text(
    text,
    style = MaterialTheme.typography.labelSmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    textAlign = TextAlign.Center,
)
