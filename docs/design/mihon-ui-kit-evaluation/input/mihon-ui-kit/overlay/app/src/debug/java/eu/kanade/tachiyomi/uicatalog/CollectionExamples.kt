@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package eu.kanade.tachiyomi.uicatalog

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.R
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
internal fun CollectionExamples(s: DemoState, send: Dispatch) {
    when (s.category) {
        7 -> { StateText(s); if (s.selected.isNotEmpty()) SelectionControls(s, send); TaskControls(s, send); FixtureRows(s, send) }
        8 -> {
            if (s.scenario == 1) {
                s.order.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { id ->
                            Card(Modifier.weight(1f).testTag("grid:$id").combinedClickable(
                                onClick = { send(DemoAction.Event("open:$id")) },
                                onLongClick = { send(DemoAction.Select(id)) },
                            )) {
                                Box(Modifier.fillMaxWidth().aspectRatio(0.7f).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                                    contentAlignment = Alignment.Center) { Text(id.toString()) }
                                Text(stringResource(R.string.lab_item, id), Modifier.padding(8.dp), maxLines = 2)
                            }
                        }
                    }
                }
            } else FixtureRows(s, send, longTitle = s.scenario == 2)
        }
        9 -> {
            SelectionControls(s, send)
            FixtureRows(s, send)
            LabButton(R.string.lab_range, "range-select") { send(DemoAction.RangeSelect(5)) }
        }
        10 -> {
            if (s.scenario < 2) {
                if (s.selected.isNotEmpty()) Text(stringResource(R.string.lab_gesture_disabled))
                val swipe = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
                    if (value != SwipeToDismissBoxValue.Settled && s.selected.isEmpty()) {
                        send(DemoAction.Event("swipe:${value.name}"))
                    }
                    false // This fixture logs a configurable action and intentionally snaps back.
                })
                SwipeToDismissBox(
                    state = swipe,
                    enableDismissFromStartToEnd = s.selected.isEmpty(),
                    enableDismissFromEndToStart = s.selected.isEmpty(),
                    backgroundContent = { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.secondaryContainer)) },
                ) { ListItem(headlineContent = { Text(stringResource(R.string.lab_swipe)) }) }
            } else ReorderExample(s, send)
        }
        11 -> {
            FlowRow {
                LabButton(R.string.lab_search, "open-search") { send(DemoAction.Query("")) }
                LabButton(R.string.lab_clear, "clear-query", s.query != null) { send(DemoAction.Query("")) }
                LabButton(R.string.lab_close_search, "close-query", s.query != null) { send(DemoAction.Query(null)) }
            }
            // The production search field is in SearchToolbar. This probe exposes deterministic query injection.
            if (s.query != null) OutlinedTextField(
                value = s.query, onValueChange = { send(DemoAction.Query(it)) },
                label = { Text(stringResource(R.string.lab_query)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("query-probe"),
            )
            LabButton(R.string.lab_search, "submit-query", !s.query.isNullOrBlank()) { send(DemoAction.SubmitSearch) }
            if (s.phase == Phase.NO_RESULTS) ContentState(s, send) else FixtureRows(s, send)
        }
        12 -> {
            val state = listOf(ToggleableState.Off, ToggleableState.On, ToggleableState.Indeterminate)[s.filter]
            val label = stringResource(listOf(R.string.lab_filter_any, R.string.lab_filter_include, R.string.lab_filter_exclude)[s.filter])
            Row(Modifier.fillMaxWidth().testTag("filter").semantics { stateDescription = label }
                .triStateToggleable(state = state, enabled = !s.downloadedOnly, role = Role.Checkbox,
                    onClick = { send(DemoAction.CycleFilter) }), verticalAlignment = Alignment.CenterVertically) {
                TriStateCheckbox(state = state, onClick = null, enabled = !s.downloadedOnly)
                Text(label, Modifier.padding(12.dp))
            }
            if (s.downloadedOnly) Text(stringResource(R.string.lab_filter_locked))
            LabButton(R.string.lab_clear, "reset-filter", !s.downloadedOnly) { send(DemoAction.ResetFilter) }
            Text(stringResource(R.string.lab_sort_state, s.descending.toString()))
            LabButton(R.string.lab_sort, "sort") { send(DemoAction.Sort) }
            FixtureRows(s, send)
        }
    }
}

@Composable
internal fun FixtureRows(s: DemoState, send: Dispatch, longTitle: Boolean = false, chapters: Boolean = false) {
    val haptic = LocalHapticFeedback.current
    s.order.forEach { id ->
        val chosen = id in s.selected
        ListItem(
            modifier = Modifier.fillMaxWidth().testTag("row:$id").semantics { selected = chosen }.combinedClickable(
                role = if (s.selected.isNotEmpty()) Role.Checkbox else Role.Button,
                onClick = {
                    send(if (s.selected.isNotEmpty()) DemoAction.Select(id) else DemoAction.Event("open:$id"))
                },
                onLongClick = {
                    send(DemoAction.Select(id))
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                },
            ),
            headlineContent = {
                Text(if (longTitle) stringResource(R.string.lab_long_text) else stringResource(
                    if (chapters) R.string.lab_chapter else R.string.lab_item, id), maxLines = 2, overflow = TextOverflow.Ellipsis)
            },
            supportingContent = if (chapters) ({ Text("read=${id in s.read}; bookmark=${id in s.bookmarked}; downloaded=${id in s.downloaded}") }) else null,
            leadingContent = { if (s.selected.isNotEmpty()) Checkbox(checked = chosen, onCheckedChange = null) else Text(id.toString()) },
            trailingContent = {
                IconButton(onClick = { send(DemoAction.Event("details:$id")) }, modifier = Modifier.testTag("details:$id")) {
                    Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.lab_info))
                }
            },
            colors = ListItemDefaults.colors(containerColor = if (chosen) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface),
        )
    }
}

@Composable
internal fun SelectionControls(s: DemoState, send: Dispatch) {
    Text(stringResource(R.string.lab_selected, s.selected.size), Modifier.testTag("selected-count"))
    FlowRow {
        LabButton(R.string.lab_select_all, "body-select-all") { send(DemoAction.SelectAll) }
        LabButton(R.string.lab_invert, "body-invert") { send(DemoAction.InvertSelection) }
        LabButton(R.string.lab_clear_selection, "clear-selection") { send(DemoAction.ClearSelection) }
        LabButton(R.string.lab_mark_read, "mark-read", s.selected.isNotEmpty()) { send(DemoAction.MarkRead) }
        LabButton(R.string.lab_bookmark, "bookmark", s.selected.isNotEmpty()) { send(DemoAction.Bookmark) }
    }
}

@Composable
private fun ReorderExample(s: DemoState, send: Dispatch) {
    val listState = rememberLazyListState()
    val reorder = rememberReorderableLazyListState(listState, PaddingValues()) { from, to ->
        s.order.getOrNull(from.index)?.let { send(DemoAction.Move(it, to.index - from.index)) }
    }
    LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().height(360.dp).testTag("reorder-list")) {
        items(s.order, key = { it }) { id ->
            ReorderableItem(reorder, id) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.DragHandle, stringResource(R.string.lab_drag),
                        Modifier.padding(16.dp).draggableHandle())
                    Text(stringResource(R.string.lab_item, id), Modifier.weight(1f))
                    LabButton(R.string.lab_up, "up:$id", s.order.first() != id) { send(DemoAction.Move(id, -1)) }
                    LabButton(R.string.lab_down, "down:$id", s.order.last() != id) { send(DemoAction.Move(id, 1)) }
                }
            }
        }
    }
}
