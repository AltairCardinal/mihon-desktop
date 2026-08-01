package mihon.desktop.ui.updates

import tachiyomi.i18n.MR

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import mihon.desktop.ui.reader.DesktopReaderScreen
import mihon.desktop.updates.UpdatesScreenModelFactory
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.updates.model.UpdatesWithRelations
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object UpdatesTab : Tab {

    override val options: TabOptions
        @Composable
        get() {
            val icon = rememberVectorPainter(Icons.Default.NewReleases)
            return remember {
                TabOptions(
                    index = 1u,
                    title = MR.strings.label_recent_updates.localized(),
                    icon = icon,
                )
            }
        }

    @Composable
    override fun Content() {
        Navigator(UpdatesRootScreen()) {
            CurrentScreen()
        }
    }
}

class UpdatesRootScreen : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val model = rememberScreenModel { UpdatesScreenModelFactory.create() }
        val state by model.state.collectAsState()
        val scope = rememberCoroutineScope()

        LaunchedEffect(Unit) {
            model.loadUpdates()
        }

        if (state.showMarkAllReadDialog) {
            AlertDialog(
                onDismissRequest = { model.setShowMarkAllReadDialog(false) },
                title = { Text(MR.strings.desktop_ui_mark_all_as_read_b69f52ab.localized()) },
                text = {
                    Text(
                        MR.strings.desktop_ui_mark_updates_read.localized(
                            Locale.getDefault(),
                            state.items.count { !it.read },
                        ),
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        scope.launch {
                            model.markAllRead()
                        }
                    }) { Text(MR.strings.desktop_ui_mark_all_read.localized()) }
                },
                dismissButton = {
                    TextButton(onClick = { model.setShowMarkAllReadDialog(false) }) { Text(MR.strings.action_cancel.localized()) }
                },
            )
        }

        if (state.showFilterDialog) {
            UpdatesFilterDialog(
                filterUnread = state.filterUnread,
                filterDownloaded = state.filterDownloaded,
                filterStarted = state.filterStarted,
                filterBookmarked = state.filterBookmarked,
                filterExcludedScanlators = state.filterExcludedScanlators,
                onToggleUnread = {
                    scope.launch { model.toggleUnreadFilter() }
                },
                onToggleDownloaded = {
                    model.toggleDownloadedFilter()
                },
                onToggleStarted = {
                    scope.launch { model.toggleStartedFilter() }
                },
                onToggleBookmarked = {
                    scope.launch { model.toggleBookmarkedFilter() }
                },
                onToggleExcludedScanlators = {
                    scope.launch { model.toggleExcludedScanlatorsFilter() }
                },
                onDismiss = { model.setShowFilterDialog(false) },
            )
        }

        Column(Modifier.fillMaxSize()) {
            // Header row with title + refresh button
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = MR.strings.desktop_ui_recent_updates.localized(),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                // Filter button
                TooltipBox(
                    positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                    tooltip = { Text(MR.strings.action_filter.localized()) },
                    state = rememberTooltipState(),
                ) {
                    IconButton(onClick = { model.setShowFilterDialog(true) }) {
                        Icon(
                            Icons.Default.FilterList,
                            contentDescription = MR.strings.action_filter.localized(),
                            tint = if (state.hasActiveFilters) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                // Upcoming calendar
                TooltipBox(
                    positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                    tooltip = { Text(MR.strings.label_upcoming.localized()) },
                    state = rememberTooltipState(),
                ) {
                    IconButton(onClick = { navigator.push(UpcomingScreen()) }) {
                        Icon(Icons.Default.CalendarMonth, contentDescription = MR.strings.label_upcoming.localized())
                    }
                }
                // Mark all as read — only shown when unread items exist
                val hasUnread = state.items.any { !it.read }
                if (hasUnread) {
                    TooltipBox(
                        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                        tooltip = { Text(MR.strings.desktop_ui_mark_all_as_read.localized()) },
                        state = rememberTooltipState(),
                    ) {
                        IconButton(onClick = { model.setShowMarkAllReadDialog(true) }) {
                            Icon(Icons.Default.DoneAll, contentDescription = MR.strings.desktop_ui_mark_all_as_read.localized())
                        }
                    }
                }

                if (state.isRefreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp).padding(end = 8.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    TooltipBox(
                        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                        tooltip = { Text(MR.strings.check_for_updates.localized()) },
                        state = rememberTooltipState(),
                    ) {
                        IconButton(onClick = {
                            scope.launch {
                                model.refreshUpdates()
                            }
                        }) {
                            Icon(Icons.Default.Refresh, contentDescription = MR.strings.desktop_ui_refresh_updates.localized())
                        }
                    }
                }
            }

            val listItems = remember(state.items) { buildUpdatesListItems(state.items) }

            if (listItems.isEmpty()) {
                Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = MR.strings.information_no_recent.localized(),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = MR.strings.desktop_ui_new_chapters_from_your_library_will_appear_here.localized(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(
                        items = listItems,
                        key = { item ->
                            when (item) {
                                is UpdatesListItem.Header -> "header-${item.label}"
                                is UpdatesListItem.Entry -> item.update.chapterId
                            }
                        },
                        contentType = { item ->
                            when (item) {
                                is UpdatesListItem.Header -> "header"
                                is UpdatesListItem.Entry -> "entry"
                            }
                        },
                    ) { item ->
                        when (item) {
                            is UpdatesListItem.Header -> UpdatesDateHeader(item.label)
                            is UpdatesListItem.Entry -> UpdateItem(
                                item = item.update,
                                onRead = {
                                    scope.launch {
                                        val request = model.readerRequestFor(item.update)
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
                                onDownload = {
                                    model.enqueueDownload(item.update)
                                },
                                onMarkRead = {
                                    scope.launch {
                                        model.markRead(item.update)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Filter dialog ──────────────────────────────────────────────────────────────

@Composable
private fun UpdatesFilterDialog(
    filterUnread: TriState,
    filterDownloaded: TriState,
    filterStarted: TriState,
    filterBookmarked: TriState,
    filterExcludedScanlators: Boolean,
    onToggleUnread: () -> Unit,
    onToggleDownloaded: () -> Unit,
    onToggleStarted: () -> Unit,
    onToggleBookmarked: () -> Unit,
    onToggleExcludedScanlators: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(MR.strings.action_filter.localized()) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TriStateFilterRow(MR.strings.desktop_ui_unread.localized(), filterUnread, onToggleUnread)
                TriStateFilterRow(MR.strings.desktop_ui_downloaded.localized(), filterDownloaded, onToggleDownloaded)
                TriStateFilterRow(MR.strings.desktop_ui_started.localized(), filterStarted, onToggleStarted)
                TriStateFilterRow(MR.strings.desktop_ui_bookmarked.localized(), filterBookmarked, onToggleBookmarked)
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onToggleExcludedScanlators)
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(MR.strings.desktop_ui_hide_excluded_scanlators.localized(), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = filterExcludedScanlators, onCheckedChange = { onToggleExcludedScanlators() })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(MR.strings.action_close.localized()) }
        },
    )
}

@Composable
private fun TriStateFilterRow(label: String, state: TriState, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = when (state) {
                TriState.DISABLED -> "—"
                TriState.ENABLED_IS -> "✓"
                TriState.ENABLED_NOT -> "✗"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = when (state) {
                TriState.DISABLED -> MaterialTheme.colorScheme.onSurfaceVariant
                TriState.ENABLED_IS -> MaterialTheme.colorScheme.primary
                TriState.ENABLED_NOT -> MaterialTheme.colorScheme.error
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UpdateItem(
    item: UpdatesWithRelations,
    onRead: () -> Unit,
    onDownload: () -> Unit,
    onMarkRead: () -> Unit,
) {
    val zone = remember { ZoneId.systemDefault() }

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
                model = mihon.desktop.image.desktopSourceImageModel(item.coverData.url, item.coverData.sourceId),
                contentDescription = item.mangaTitle,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(48.dp, 68.dp)
                    .clip(RoundedCornerShape(4.dp)),
            )

            // Info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.mangaTitle,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = item.chapterName,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (item.read) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                item.scanlator?.let { scanlator ->
                    Text(
                        text = scanlator,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Date
            Text(
                text = formatUpdateTime(item.dateFetch, zone),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Download button
            TooltipBox(
                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                tooltip = { Text(MR.strings.action_download.localized()) },
                state = rememberTooltipState(),
            ) {
                IconButton(onClick = onDownload) {
                    Icon(
                        Icons.Default.CloudDownload,
                        contentDescription = MR.strings.action_download.localized(),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            // Mark read button (only shown for unread chapters)
            if (!item.read) {
                TooltipBox(
                    positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                    tooltip = { Text(MR.strings.action_mark_as_read.localized()) },
                    state = rememberTooltipState(),
                ) {
                    IconButton(onClick = onMarkRead) {
                        Icon(
                            Icons.Default.DoneAll,
                            contentDescription = MR.strings.action_mark_as_read.localized(),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

// ── Date-grouped list items ────────────────────────────────────────────────────

private sealed interface UpdatesListItem {
    data class Header(val label: String) : UpdatesListItem
    data class Entry(val update: UpdatesWithRelations) : UpdatesListItem
}

private fun buildUpdatesListItems(items: List<UpdatesWithRelations>): List<UpdatesListItem> {
    val today = LocalDate.now()
    val yesterday = today.minusDays(1)
    val grouped = items.groupBy { item ->
        Instant.ofEpochMilli(item.dateFetch).atZone(ZoneId.systemDefault()).toLocalDate()
    }
    return buildList {
        grouped.entries.sortedByDescending { it.key }.forEach { (date, updates) ->
            val label = when (date) {
                today -> MR.strings.relative_time_today.localized()
                yesterday -> MR.strings.desktop_ui_yesterday.localized()
                else -> date.format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault()))
            }
            add(UpdatesListItem.Header(label))
            updates.forEach { add(UpdatesListItem.Entry(it)) }
        }
    }
}

@Composable
private fun UpdatesDateHeader(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp),
    )
}
