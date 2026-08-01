package mihon.desktop.ui.migration

import tachiyomi.i18n.MR
import java.util.Locale

import mihon.desktop.LocalDesktopUiDependencies

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import tachiyomi.domain.manga.interactor.GetFavorites
import mihon.desktop.migration.BatchMigrationRequest

/**
 * Lists library manga from a specific source — step 2 of migration flow.
 * User picks which manga to migrate.
 */
data class MigrationMangaScreen(
    val sourceId: Long,
    val sourceName: String,
) : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val getFavorites = LocalDesktopUiDependencies.current.getFavorites
        val batchMigrationController = LocalDesktopUiDependencies.current.batchMigrationController
        val selectedIds = remember { mutableStateListOf<Long>() }

        val manga by remember(sourceId) {
            getFavorites.subscribe(sourceId)
        }.collectAsState(initial = null)

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(sourceName) },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, MR.strings.desktop_ui_back.localized())
                        }
                    },
                    actions = {
                        if (selectedIds.isNotEmpty()) {
                            TextButton(onClick = {
                                val queued = manga.orEmpty().filter { it.id in selectedIds }
                                    .map { BatchMigrationRequest(it.id, it.title) }
                                navigator.push(MigrationBatchQueueScreen(batchMigrationController.submit(queued)))
                            }) {
                                Text(
                                    MR.strings.desktop_ui_queue_count.localized(
                                        Locale.getDefault(),
                                        selectedIds.size,
                                    ),
                                )
                            }
                        }
                    },
                )
            },
        ) { padding ->
            when {
                manga == null -> Box(
                    Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                manga!!.isEmpty() -> Box(
                    Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        MR.strings.desktop_ui_no_manga_from_this_source.localized(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                    items(manga!!, key = { it.id }) { m ->
                        ListItem(
                            leadingContent = {
                                AsyncImage(
                                    model = mihon.desktop.image.desktopSourceImageModel(m.thumbnailUrl, m.source),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.width(48.dp).size(64.dp),
                                )
                            },
                            headlineContent = { Text(m.title, maxLines = 2) },
                            supportingContent = m.author?.let { { Text(it) } },
                            trailingContent = {
                                Checkbox(
                                    checked = m.id in selectedIds,
                                    onCheckedChange = { checked ->
                                        if (checked) selectedIds.add(m.id) else selectedIds.remove(m.id)
                                    },
                                )
                            },
                            modifier = Modifier.clickable {
                                navigator.push(
                                    MigrationSearchScreen(
                                        sourceMangaId = m.id,
                                        sourceMangaTitle = m.title,
                                    ),
                                )
                            },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
