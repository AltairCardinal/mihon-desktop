package mihon.desktop.ui.migration

import tachiyomi.i18n.MR
import java.util.Locale

import mihon.desktop.LocalDesktopUiDependencies

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Badge
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import kotlinx.coroutines.flow.map
import tachiyomi.domain.source.repository.SourceRepository

/**
 * Lists sources that have library manga — entry point for manga migration.
 * Mirrors Android's MigrateSourceTab.
 */
class MigrationSourceScreen : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val desktopDependencies = LocalDesktopUiDependencies.current
        val migrationQueues by desktopDependencies.batchMigrationController.queues.collectAsState()

        // Sources with at least one library manga, sorted descending by count
        val sourcesWithCount by remember {
            desktopDependencies.getSourcesWithFavoriteCount()
                .map { list -> list.filter { it.second > 0 }.sortedByDescending { it.second } }
        }.collectAsState(initial = null)

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(MR.strings.label_migration.localized()) },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, MR.strings.desktop_ui_back.localized())
                        }
                    },
                    actions = {
                        migrationQueues.values.lastOrNull()?.let { queue ->
                            TextButton(onClick = { navigator.push(MigrationBatchQueueScreen(queue.id)) }) {
                                Text(
                                    MR.strings.desktop_ui_migration_queues.localized(
                                        Locale.getDefault(),
                                        migrationQueues.size,
                                    ),
                                )
                            }
                        }
                    },
                )
            },
        ) { padding ->
            when {
                sourcesWithCount == null -> Box(
                    Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                sourcesWithCount!!.isEmpty() -> Box(
                    Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        MR.strings.desktop_ui_no_sources_with_library_manga.localized(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                    items(sourcesWithCount!!, key = { it.first.id }) { (source, count) ->
                        ListItem(
                            headlineContent = { Text(source.name) },
                            supportingContent = { Text(source.lang.uppercase()) },
                            trailingContent = {
                                Badge(containerColor = MaterialTheme.colorScheme.primaryContainer) {
                                    Text("$count", style = MaterialTheme.typography.labelSmall)
                                }
                            },
                            modifier = Modifier.clickable {
                                navigator.push(MigrationMangaScreen(source.id, source.name))
                            },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
