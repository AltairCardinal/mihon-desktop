package mihon.desktop.ui.more

import mihon.desktop.LocalDesktopUiDependencies

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.LocalLibrary
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.interactor.GetLibraryManga

/** Statistics aggregated from the library. All fields are counts/sums. */
data class StatsData(
    val mangaCount: Int,
    val startedCount: Int,
    val localCount: Int,
    val totalChapters: Long,
    val readChapters: Long,
)

/** Pure function — no Compose dependency. Aggregates stats from a library snapshot. */
internal fun calculateStats(items: List<LibraryManga>): StatsData {
    val localSourceId = 0L
    return StatsData(
        mangaCount = items.size,
        startedCount = items.count { it.hasStarted },
        localCount = items.count { it.manga.source == localSourceId },
        totalChapters = items.sumOf { it.totalChapters },
        readChapters = items.sumOf { it.readCount },
    )
}

class StatsScreen : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val getLibraryManga = LocalDesktopUiDependencies.current.getLibraryManga
        var stats by remember { mutableStateOf(StatsData(0, 0, 0, 0L, 0L)) }

        LaunchedEffect(Unit) {
            getLibraryManga.subscribe().collect { items ->
                stats = calculateStats(items)
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Statistics") },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                )
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                // ── Overview ─────────────────────────────────────────────────
                item {
                    StatsSection(title = "Library")
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        StatsOverviewItem(
                            title = stats.mangaCount.toString(),
                            subtitle = "Titles",
                            icon = Icons.Default.CollectionsBookmark,
                        )
                        StatsOverviewItem(
                            title = stats.readChapters.toString(),
                            subtitle = "Chapters read",
                            icon = Icons.Default.MenuBook,
                        )
                        StatsOverviewItem(
                            title = stats.startedCount.toString(),
                            subtitle = "Started",
                            icon = Icons.Default.LocalLibrary,
                        )
                    }
                    HorizontalDivider()
                }

                // ── Titles ────────────────────────────────────────────────────
                item {
                    StatsSection(title = "Titles")
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        StatsItem(title = stats.startedCount.toString(), subtitle = "Started")
                        StatsItem(title = stats.localCount.toString(), subtitle = "Local source")
                        StatsItem(
                            title = (stats.mangaCount - stats.startedCount).toString(),
                            subtitle = "Not started",
                        )
                    }
                    HorizontalDivider()
                }

                // ── Chapters ──────────────────────────────────────────────────
                item {
                    StatsSection(title = "Chapters")
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        StatsItem(title = stats.totalChapters.toString(), subtitle = "Total")
                        StatsItem(title = stats.readChapters.toString(), subtitle = "Read")
                        StatsItem(
                            title = (stats.totalChapters - stats.readChapters).toString(),
                            subtitle = "Unread",
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatsSection(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun StatsOverviewItem(title: String, subtitle: String, icon: ImageVector) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(28.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(text = title, style = MaterialTheme.typography.titleLarge)
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun StatsItem(title: String, subtitle: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
