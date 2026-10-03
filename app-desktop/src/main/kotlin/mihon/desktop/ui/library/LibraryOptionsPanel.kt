package mihon.desktop.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import mihon.desktop.domain.SortMode
import mihon.desktop.ui.settings.LibraryColumnControls
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.i18n.MR

/** One modal session; each page owns its scroll state until the session is dismissed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryOptionsPanel(
    model: LibraryScreenModel,
    preferences: LibraryPreferences?,
    categoryId: Long?,
    focusRequest: Int,
    onDismiss: () -> Unit,
) {
    val state by model.state.collectAsState()
    var page by remember { mutableIntStateOf(0) }
    val scrolls = listOf(rememberScrollState(), rememberScrollState(), rememberScrollState())
    val focus = remember { FocusRequester() }
    val titles = listOf(MR.strings.action_filter, MR.strings.action_sort, MR.strings.action_display).map {
        it.localized()
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
            modifier = Modifier.widthIn(max = 560.dp).padding(12.dp).fillMaxWidth().heightIn(max = 640.dp)
                .testTag("library-options-panel")
                .onPreviewKeyEvent {
                    if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) {
                        onDismiss()
                        true
                    } else {
                        false
                    }
                },
        ) {
            Column(Modifier.padding(16.dp)) {
                PrimaryTabRow(
                    selectedTabIndex = page,
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    divider = {},
                ) {
                    titles.forEachIndexed { index, title ->
                        Tab(
                            selected = page == index,
                            onClick = { page = index },
                            text = { Text(title) },
                            unselectedContentColor = MaterialTheme.colorScheme.onSurface,
                            modifier = if (index == 0) Modifier.focusRequester(focus) else Modifier,
                        )
                    }
                }
                Column(
                    Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(scrolls[page])
                        .testTag("library-options-page-$page"),
                ) {
                    when (page) {
                        0 -> {
                            val displayed = if (state.filter.globalDownloadedOnly) {
                                state.filter.copy(downloaded = TriState.ENABLED_IS)
                            } else {
                                state.filter
                            }
                            filterRows(displayed).forEach { (title, field) ->
                                val enabled = isFilterFieldEnabled(state.filter, field.first) &&
                                    (
                                        field.first != LibraryFilterField.INTERVAL_CUSTOM ||
                                            state.filter.skipOutsideReleasePeriod
                                        )
                                TriStateOption(title, field.second, enabled) { model.toggleFilter(field.first) }
                                if (field.first == LibraryFilterField.INTERVAL_CUSTOM && !enabled) {
                                    Text(
                                        MR.strings.desktop_library_interval_requires_restriction.localized(),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                            if (state.availableTrackerIds.isEmpty()) {
                                Text(
                                    MR.strings.action_filter_tracked.localized(),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                state.availableTrackerIds.sorted().forEach { id ->
                                    TriStateOption(
                                        if (state.availableTrackerIds.size ==
                                            1
                                        ) {
                                            MR.strings.action_filter_tracked.localized()
                                        } else {
                                            state.trackerNamesById[id].orEmpty()
                                        },
                                        state.filter.tracking[id].orDisabledForUi(),
                                        true,
                                    ) { model.toggleTrackingFilter(id) }
                                }
                            }
                        }
                        1 -> SortMode.entries.forEach { mode ->
                            val selected = state.sortMode == mode
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    val ascending = if (mode ==
                                        SortMode.RANDOM
                                    ) {
                                        true
                                    } else {
                                        nextSortAscending(mode, state.sortMode, state.sortAscending)
                                    }
                                    model.setSortModeAndDirectionForCategory(categoryId, mode, ascending)
                                }.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = selected, onClick = null)
                                Text(
                                    sortLabel(mode) + if (selected && mode != SortMode.RANDOM) {
                                        if (state.sortAscending) " ↑" else " ↓"
                                    } else {
                                        ""
                                    },
                                    Modifier.padding(start = 12.dp),
                                )
                            }
                        }
                        2 -> {
                            listOf(
                                LibraryDisplayMode.COMPACT_GRID to MR.strings.action_display_grid,
                                LibraryDisplayMode.COMFORTABLE_GRID to MR.strings.action_display_comfortable_grid,
                                LibraryDisplayMode.COVER_ONLY_GRID to MR.strings.action_display_cover_only_grid,
                                LibraryDisplayMode.LIST to MR.strings.action_display_list,
                            ).forEach { (mode, title) ->
                                Row(
                                    Modifier.fillMaxWidth().clickable {
                                        model.setDisplayModeForCategory(categoryId, mode)
                                    }.padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(selected = state.displayMode == mode, onClick = null)
                                    Text(title.localized(), Modifier.padding(start = 12.dp))
                                }
                            }
                            preferences?.let { prefs ->
                                LibraryColumnControls(prefs)
                                listOf(
                                    prefs.downloadBadge() to MR.strings.action_display_download_badge,
                                    prefs.unreadBadge() to MR.strings.action_display_unread_badge,
                                    prefs.localBadge() to MR.strings.action_display_local_badge,
                                    prefs.languageBadge() to MR.strings.action_display_language_badge,
                                    prefs.showContinueReadingButton() to
                                        MR.strings.action_display_show_continue_reading_button,
                                    prefs.categoryTabs() to MR.strings.action_display_show_tabs,
                                    prefs.categoryNumberOfItems() to MR.strings.action_display_show_number_of_items,
                                ).forEach { (preference, title) -> BooleanOption(title.localized(), preference, model) }
                            }
                        }
                    }
                }
                state.operationFeedback?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text(MR.strings.action_close.localized())
                }
            }
        }
        LaunchedEffect(focusRequest) {
            withFrameNanos { }
            focus.requestFocus()
        }
    }
}

@Composable
private fun BooleanOption(title: String, preference: Preference<Boolean>, model: LibraryScreenModel) {
    val checked by preference.changes().collectAsState(preference.get())
    Row(
        Modifier.fillMaxWidth().clickable {
            model.writeLibraryPreference(preference, !checked)
        }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(title, Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun TriStateOption(title: String, state: TriState, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).semantics {
            role = Role.Checkbox
            stateDescription = state.label()
        }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TriStateCheckbox(
            state = when (state) {
                TriState.DISABLED -> ToggleableState.Off
                TriState.ENABLED_IS -> ToggleableState.On
                TriState.ENABLED_NOT -> ToggleableState.Indeterminate
            },
            enabled = enabled,
            onClick = null,
        )
        Text(title, Modifier.padding(start = 12.dp))
    }
}

internal fun sortLabel(mode: SortMode): String = when (mode) {
    SortMode.TITLE -> MR.strings.action_sort_alpha
    SortMode.LAST_READ -> MR.strings.action_sort_last_read
    SortMode.LAST_UPDATE -> MR.strings.action_sort_last_manga_update
    SortMode.UNREAD_COUNT -> MR.strings.action_sort_unread_count
    SortMode.TOTAL_CHAPTERS -> MR.strings.action_sort_total
    SortMode.LATEST_CHAPTER -> MR.strings.action_sort_latest_chapter
    SortMode.CHAPTER_FETCH_DATE -> MR.strings.action_sort_chapter_fetch_date
    SortMode.DATE_ADDED -> MR.strings.action_sort_date_added
    SortMode.TRACKER_MEAN -> MR.strings.action_sort_tracker_score
    SortMode.RANDOM -> MR.strings.action_sort_random
}.localized()
