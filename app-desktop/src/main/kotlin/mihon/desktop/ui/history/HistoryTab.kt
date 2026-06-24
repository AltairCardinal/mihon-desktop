package mihon.desktop.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.Tab
import cafe.adriel.voyager.navigator.tab.TabOptions
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import mihon.desktop.domain.ReaderProgressTracker
import mihon.desktop.ui.reader.DesktopReaderScreen
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.interactor.RemoveHistory
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.manga.interactor.GetManga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** A date-labelled group of history items. */
data class HistorySection(val dateLabel: String, val items: List<HistoryWithRelations>)

/**
 * Groups [items] by calendar day of [HistoryWithRelations.readAt].
 * Items with null readAt are excluded.  Order within each group is preserved.
 * Day labels: "Today", "Yesterday", or "MMM dd, yyyy".
 */
internal fun groupHistoryByDate(items: List<HistoryWithRelations>): List<HistorySection> {
    val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.ENGLISH)
    val today = startOfDay(Calendar.getInstance().time)
    val yesterday = startOfDay(Date(today.time - 86_400_000L))

    return items
        .filter { it.readAt != null }
        .groupByTo(linkedMapOf()) { startOfDay(it.readAt!!) }
        .map { (dayStart, groupItems) ->
            val label = when (dayStart) {
                today -> "Today"
                yesterday -> "Yesterday"
                else -> dateFormat.format(dayStart)
            }
            HistorySection(dateLabel = label, items = groupItems)
        }
}

private fun startOfDay(date: Date): Date {
    val cal = Calendar.getInstance()
    cal.time = date
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return cal.time
}

object HistoryTab : Tab {

    override val options: TabOptions
        @Composable
        get() {
            val icon = rememberVectorPainter(Icons.Default.History)
            return remember {
                TabOptions(
                    index = 2u,
                    title = "History",
                    icon = icon,
                )
            }
        }

    @Composable
    override fun Content() {
        Navigator(HistoryRootScreen()) {
            CurrentScreen()
        }
    }
}

class HistoryRootScreen : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val getHistory = remember { Injekt.get<GetHistory>() }
        val removeHistory = remember { Injekt.get<RemoveHistory>() }
        val getChapter = remember { Injekt.get<GetChapter>() }
        val getManga = remember { Injekt.get<GetManga>() }
        val progressTracker = remember { Injekt.get<ReaderProgressTracker>() }
        val scope = rememberCoroutineScope()

        var searchQuery by remember { mutableStateOf("") }
        var historyItems by remember { mutableStateOf<List<HistoryWithRelations>>(emptyList()) }
        var showClearAllDialog by remember { mutableStateOf(false) }

        LaunchedEffect(searchQuery) {
            getHistory.subscribe(searchQuery).collect { historyItems = it }
        }

        if (showClearAllDialog) {
            AlertDialog(
                onDismissRequest = { showClearAllDialog = false },
                title = { Text("Clear all history?") },
                text = { Text("This will permanently delete all reading history. This cannot be undone.") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            scope.launch {
                                historyItems.forEach { removeHistory.await(it) }
                                showClearAllDialog = false
                            }
                        },
                    ) { Text("Clear all", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    TextButton(onClick = { showClearAllDialog = false }) { Text("Cancel") }
                },
            )
        }

        Column(Modifier.fillMaxSize()) {
            // Search bar + clear-all button
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search history...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                if (historyItems.isNotEmpty()) {
                    TooltipBox(
                        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                        tooltip = { Text("Clear all history") },
                        state = rememberTooltipState(),
                    ) {
                        IconButton(onClick = { showClearAllDialog = true }) {
                            Icon(
                                Icons.Default.DeleteSweep,
                                contentDescription = "Clear all history",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            if (historyItems.isEmpty()) {
                Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "No reading history",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "Manga you read will appear here",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            } else {
                val sections = remember(historyItems) { groupHistoryByDate(historyItems) }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    sections.forEach { section ->
                        stickyHeader(key = "header_${section.dateLabel}") {
                            HistoryDateHeader(section.dateLabel)
                        }
                        items(section.items, key = { it.id }) { item ->
                            HistoryItem(
                                item = item,
                                onRead = {
                                    scope.launch {
                                        val chapter = getChapter.await(item.chapterId) ?: return@launch
                                        val manga = getManga.await(item.mangaId) ?: return@launch
                                        navigator.push(
                                            DesktopReaderScreen(
                                                chapterTitle = chapter.name,
                                                mangaTitle = manga.title,
                                                isWebtoon = false,
                                                sourceId = manga.source,
                                                chapterUrl = chapter.url,
                                                chapterId = chapter.id,
                                                mangaId = manga.id,
                                                mangaViewerFlags = manga.viewerFlags,
                                                initialPage = chapter.lastPageRead.toInt().coerceAtLeast(0),
                                                progressTracker = progressTracker,
                                            ),
                                        )
                                    }
                                },
                                onRemove = {
                                    scope.launch { removeHistory.await(item) }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryDateHeader(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = 6.dp, horizontal = 4.dp),
    )
}

@Composable
private fun HistoryItem(
    item: HistoryWithRelations,
    onRead: () -> Unit,
    onRemove: () -> Unit,
) {
    val dateFormat = remember { SimpleDateFormat("MMM dd, yyyy  HH:mm", Locale.getDefault()) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onRead),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Cover thumbnail
            AsyncImage(
                model = item.coverData.url,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(48.dp, 68.dp)
                    .clip(RoundedCornerShape(4.dp)),
            )

            // Info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "Ch. ${item.chapterNumber}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                item.readAt?.let { date ->
                    Text(
                        text = dateFormat.format(date),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Delete button
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Remove",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
