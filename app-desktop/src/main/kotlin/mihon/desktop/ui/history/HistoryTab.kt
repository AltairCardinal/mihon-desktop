package mihon.desktop.ui.history

import tachiyomi.i18n.MR

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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.Tab
import cafe.adriel.voyager.navigator.tab.TabOptions
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import mihon.desktop.history.HistoryScreenModelFactory
import mihon.desktop.ui.reader.DesktopReaderScreen
import tachiyomi.domain.history.model.HistoryWithRelations
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
    val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
    val today = startOfDay(Calendar.getInstance().time)
    val yesterday = startOfDay(Date(today.time - 86_400_000L))

    return items
        .filter { it.readAt != null }
        .groupByTo(linkedMapOf()) { startOfDay(it.readAt!!) }
        .map { (dayStart, groupItems) ->
            val label = when (dayStart) {
                today -> MR.strings.relative_time_today.localized()
                yesterday -> MR.strings.desktop_ui_yesterday.localized()
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
                    title = MR.strings.history.localized(),
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
        val model = rememberScreenModel { HistoryScreenModelFactory.create() }
        val state by model.state.collectAsState()
        val scope = rememberCoroutineScope()

        LaunchedEffect(state.searchQuery) {
            model.loadHistory(state.searchQuery)
        }

        if (state.showClearAllDialog) {
            AlertDialog(
                onDismissRequest = { model.setShowClearAllDialog(false) },
                title = { Text(MR.strings.desktop_ui_clear_all_history_81616b91.localized()) },
                text = { Text(MR.strings.desktop_ui_this_will_permanently_delete_all_reading_history_this_ca.localized()) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            scope.launch {
                                model.clearAllHistory()
                            }
                        },
                    ) { Text(MR.strings.desktop_ui_clear_all.localized(), color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    TextButton(onClick = { model.setShowClearAllDialog(false) }) { Text(MR.strings.action_cancel.localized()) }
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
                    value = state.searchQuery,
                    onValueChange = { query -> scope.launch { model.loadHistory(query) } },
                    placeholder = { Text(MR.strings.desktop_ui_search_history.localized()) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = MR.strings.action_search.localized()) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                if (state.items.isNotEmpty()) {
                    TooltipBox(
                        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                        tooltip = { Text(MR.strings.desktop_ui_clear_all_history.localized()) },
                        state = rememberTooltipState(),
                    ) {
                        IconButton(onClick = { model.setShowClearAllDialog(true) }) {
                            Icon(
                                Icons.Default.DeleteSweep,
                                contentDescription = MR.strings.desktop_ui_clear_all_history.localized(),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            if (state.items.isEmpty()) {
                Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = MR.strings.desktop_ui_no_reading_history.localized(),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = MR.strings.desktop_ui_manga_you_read_will_appear_here.localized(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            } else {
                val sections = remember(state.items) { groupHistoryByDate(state.items) }
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
                                        val request = model.readerRequestFor(item) ?: return@launch
                                        navigator.push(
                                            DesktopReaderScreen(
                                                chapterTitle = request.chapterTitle,
                                                mangaTitle = request.mangaTitle,
                                                isWebtoon = false,
                                                sourceId = request.sourceId,
                                                chapterUrl = request.chapterUrl,
                                                chapterId = request.chapterId,
                                                mangaId = request.mangaId,
                                                mangaViewerFlags = request.mangaViewerFlags,
                                                initialPage = request.initialPage,
                                            ),
                                        )
                                    }
                                },
                                onRemove = {
                                    scope.launch { model.removeHistory(item) }
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
                    text = MR.strings.desktop_ui_chapter_number.localized(
                        Locale.getDefault(),
                        item.chapterNumber.toString(),
                    ),
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
                    contentDescription = MR.strings.action_remove.localized(),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
