package mihon.presentation.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.history.service.HistoryState
import tachiyomi.domain.history.service.HistoryUiModel
import tachiyomi.i18n.MR
import java.text.DateFormat
import java.time.LocalDate

/** Shared history UI; only image loading and navigation are platform adapters. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryContent(
    state: HistoryState,
    onSearchQueryChange: (String?) -> Unit,
    onCover: (HistoryWithRelations) -> Unit,
    onResume: (HistoryWithRelations) -> Unit,
    onFavorite: (HistoryWithRelations) -> Unit,
    onDelete: (HistoryWithRelations) -> Unit,
    onClear: () -> Unit,
    cover: @Composable (HistoryWithRelations, Modifier, () -> Unit) -> Unit,
    listState: LazyListState = rememberLazyListState(),
    snackbar: @Composable () -> Unit = {},
    datePreferences: HistoryDatePreferences = HistoryDatePreferences(),
) {
    var focusAnchor by rememberSaveable { mutableStateOf<String?>(null) }
    val focusRequests = remember { mutableMapOf<String, FocusRequester>() }
    val toolbarFocus = remember { FocusRequester() }
    val clearFocus = remember { FocusRequester() }
    fun requester(key: String) = focusRequests.getOrPut(key) { FocusRequester() }
    LaunchedEffect(state.dialog, state.list) {
        val anchor = focusAnchor
        if (state.dialog == null && anchor != null) {
            val id = anchor.substringAfter(':').toLongOrNull()
            val item = state.items.firstOrNull { it.id == id }
            val key = when {
                item == null -> null
                anchor.startsWith("favorite:") && item.coverData.isMangaFavorite -> "row:$id"
                else -> anchor
            }
            if (anchor == "clear") {
                clearFocus.requestFocus()
            } else if (key != null) {
                focusRequests[key]?.requestFocus()
            } else {
                toolbarFocus.requestFocus()
            }
        }
    }
    Scaffold(
        modifier = Modifier.onPreviewKeyEvent {
            if (it.key == Key.Escape && it.type == KeyEventType.KeyDown && state.searchQuery != null) {
                onSearchQueryChange(null)
                true
            } else {
                false
            }
        },
        topBar = {
            Row(
                Modifier.fillMaxWidth()
                    .windowInsetsPadding(TopAppBarDefaults.windowInsets)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.searchQuery == null) {
                    Text(
                        historyString(MR.strings.history),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    IconButton(
                        onClick = { onSearchQueryChange("") },
                        modifier = Modifier.focusRequester(toolbarFocus)
                            .onFocusChanged { if (it.isFocused) focusAnchor = null }
                            .testTag("history_search_open"),
                    ) {
                        Icon(Icons.Outlined.Search, historyString(MR.strings.action_search))
                    }
                } else {
                    val focus = remember { FocusRequester() }
                    val input = remember { HistorySearchInputState(state.searchQuery.orEmpty()) }
                    input.align(state.searchQuery.orEmpty())
                    LaunchedEffect(Unit) { if (focusAnchor == null) focus.requestFocus() }
                    IconButton(
                        onClick = { onSearchQueryChange(null) },
                        modifier = Modifier.focusRequester(toolbarFocus)
                            .onFocusChanged { if (it.isFocused) focusAnchor = null }
                            .testTag("history_search_close"),
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, historyString(MR.strings.action_close))
                    }
                    OutlinedTextField(
                        value = input.value,
                        onValueChange = { input.accept(it, onSearchQueryChange) },
                        singleLine = true,
                        modifier = Modifier.weight(1f).focusRequester(focus)
                            .onFocusChanged { if (it.isFocused) focusAnchor = null }
                            .testTag("history_search_input"),
                        trailingIcon = {
                            IconButton(
                                onClick = { input.clear(onSearchQueryChange) },
                                modifier = Modifier.testTag("history_search_clear"),
                            ) {
                                Icon(Icons.Outlined.Close, historyString(MR.strings.action_reset))
                            }
                        },
                    )
                }
                IconButton(
                    onClick = {
                        focusAnchor = "clear"
                        onClear()
                    },
                    modifier = Modifier.focusRequester(clearFocus)
                        .onFocusChanged { if (it.isFocused) focusAnchor = "clear" }
                        .testTag("history_clear_all"),
                ) {
                    Icon(Icons.Outlined.DeleteSweep, historyString(MR.strings.pref_clear_history))
                }
            }
        },
        snackbarHost = snackbar,
    ) { padding ->
        val list = state.list
        if (list == null || list.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                if (list == null) {
                    CircularProgressIndicator(Modifier.testTag("history_loading"))
                } else {
                    val message = if (state.searchQuery.isNullOrEmpty()) {
                        MR.strings.information_no_recent_manga
                    } else {
                        MR.strings.no_results_found
                    }
                    Text(historyString(message))
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding), state = listState) {
                items(list, key = {
                    when (it) {
                        is HistoryUiModel.Header -> "date-${it.date}"
                        is HistoryUiModel.Item -> "history-${it.item.id}"
                    }
                }) { entry ->
                    when (entry) {
                        is HistoryUiModel.Header -> Text(
                            relativeHistoryDate(entry.date, datePreferences),
                            Modifier.fillMaxWidth().padding(16.dp),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelLarge,
                        )
                        is HistoryUiModel.Item -> HistoryItem(
                            entry.item,
                            onCover = {
                                focusAnchor = "cover:${it.id}"
                                onCover(it)
                            },
                            onResume = {
                                focusAnchor = "row:${it.id}"
                                onResume(it)
                            },
                            onFavorite = {
                                focusAnchor = "favorite:${it.id}"
                                onFavorite(it)
                            },
                            onDelete = {
                                focusAnchor = "delete:${it.id}"
                                onDelete(it)
                            },
                            cover = cover,
                            requester = ::requester,
                            onFocused = { focusAnchor = it },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryItem(
    item: HistoryWithRelations,
    onCover: (HistoryWithRelations) -> Unit,
    onResume: (HistoryWithRelations) -> Unit,
    onFavorite: (HistoryWithRelations) -> Unit,
    onDelete: (HistoryWithRelations) -> Unit,
    cover: @Composable (HistoryWithRelations, Modifier, () -> Unit) -> Unit,
    requester: (String) -> FocusRequester,
    onFocused: (String) -> Unit,
) {
    val showEntry = historyString(MR.strings.action_show_manga)
    Row(
        Modifier.fillMaxWidth().focusRequester(requester("row:${item.id}"))
            .onFocusChanged { if (it.isFocused) onFocused("row:${item.id}") }
            .testTag("history_item_${item.id}").clickable { onResume(item) }
            .heightIn(min = 96.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        cover(
            item,
            Modifier.size(53.333.dp, 80.dp).focusRequester(requester("cover:${item.id}"))
                .onFocusChanged { if (it.isFocused) onFocused("cover:${item.id}") }
                .testTag("history_cover_${item.id}")
                .semantics { onClick(label = showEntry, action = null) },
        ) { onCover(item) }
        Column(Modifier.weight(1f).padding(start = 16.dp, end = 8.dp)) {
            Text(
                item.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyMedium,
            )
            val time = remember(item.readAt) {
                item.readAt?.let { DateFormat.getTimeInstance(DateFormat.SHORT).format(it) }.orEmpty()
            }
            Text(
                if (item.chapterNumber > -1) {
                    historyString(MR.strings.recent_manga_time, formatChapterNumber(item.chapterNumber), time)
                } else {
                    time
                },
                Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (!item.coverData.isMangaFavorite) {
            IconButton(
                onClick = { onFavorite(item) },
                modifier = Modifier.focusRequester(requester("favorite:${item.id}"))
                    .onFocusChanged { if (it.isFocused) onFocused("favorite:${item.id}") }
                    .testTag("history_favorite_${item.id}"),
            ) {
                Icon(Icons.Outlined.FavoriteBorder, historyString(MR.strings.add_to_library))
            }
        }
        IconButton(
            onClick = { onDelete(item) },
            modifier = Modifier.focusRequester(requester("delete:${item.id}"))
                .onFocusChanged { if (it.isFocused) onFocused("delete:${item.id}") }
                .testTag("history_delete_${item.id}"),
        ) {
            Icon(Icons.Outlined.Delete, historyString(MR.strings.action_delete))
        }
    }
}

@Composable
private fun relativeHistoryDate(date: LocalDate, preferences: HistoryDatePreferences): String = when (
    val label = historyDateLabel(date, preferences.relativeTime, preferences.formatter())
) {
    HistoryDateLabel.Today -> historyString(MR.strings.relative_time_today)
    is HistoryDateLabel.DaysAgo -> historyPlural(MR.plurals.relative_time, label.days, label.days)
    is HistoryDateLabel.Upcoming -> historyPlural(MR.plurals.upcoming_relative_time, label.days, label.days)
    is HistoryDateLabel.Formatted -> label.text
}
