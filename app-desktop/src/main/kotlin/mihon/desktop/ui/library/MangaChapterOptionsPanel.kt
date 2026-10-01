package mihon.desktop.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.chapter.service.effectiveDownloadedChapterFilter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MangaChapterOptionsPanel(model: MangaDetailScreenModel, downloadedOnly: Boolean, onDismiss: () -> Unit) {
    val state by model.state.collectAsState()
    val manga = state.manga ?: return
    val scope = rememberCoroutineScope()
    var page by remember { mutableIntStateOf(0) }
    val scrolls = listOf(rememberScrollState(), rememberScrollState(), rememberScrollState())
    val focus = remember { FocusRequester() }
    val defaultFocus = remember { FocusRequester() }
    val scanlatorFocus = remember { FocusRequester() }
    var showDefaults by remember { mutableStateOf(false) }
    var showScanlators by remember { mutableStateOf(false) }
    var returnFocus by remember { mutableStateOf<FocusRequester?>(null) }
    val titles = listOf(MR.strings.action_filter, MR.strings.action_sort, MR.strings.action_display)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.widthIn(max = 560.dp).padding(12.dp).fillMaxWidth().heightIn(max = 640.dp)
                .testTag("manga-chapter-options")
                .onPreviewKeyEvent {
                    if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) {
                        onDismiss()
                        true
                    } else {
                        false
                    }
                },
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(16.dp)) {
                PrimaryTabRow(selectedTabIndex = page, divider = {}) {
                    titles.forEachIndexed { index, title ->
                        Tab(
                            selected = page == index,
                            onClick = { page = index },
                            text = { Text(title.localized()) },
                            modifier = if (index == 0) Modifier.focusRequester(focus) else Modifier,
                        )
                    }
                }
                Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(scrolls[page])) {
                    when (page) {
                        0 -> {
                            ChapterFilterOption(
                                MR.strings.label_read_chapters.localized(),
                                readChapterFilter(manga),
                                true,
                            ) {
                                scope.launch { model.setChapterReadFilter(it) }
                            }
                            ChapterFilterOption(
                                MR.strings.label_downloaded.localized(),
                                manga.effectiveDownloadedChapterFilter(downloadedOnly),
                                !downloadedOnly,
                            ) { scope.launch { model.setChapterDownloadFilter(it) } }
                            if (downloadedOnly) {
                                Text(
                                    MR.strings.label_downloaded_only.localized(),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            ChapterFilterOption(
                                MR.strings.action_filter_bookmarked.localized(),
                                manga.bookmarkedFilter,
                                true,
                            ) {
                                scope.launch { model.setChapterBookmarkFilter(it) }
                            }
                            TextButton(
                                onClick = {
                                    model.clearChapterSettingsFeedback()
                                    showScanlators = true
                                },
                                modifier = Modifier.focusRequester(scanlatorFocus),
                            ) {
                                Text(MR.strings.scanlator.localized())
                            }
                        }
                        1 -> ChapterSortMode.entries.forEach { mode ->
                            val label = when (mode) {
                                ChapterSortMode.BY_SOURCE_ORDER -> MR.strings.sort_by_source
                                ChapterSortMode.BY_CHAPTER_NUMBER -> MR.strings.sort_by_number
                                ChapterSortMode.BY_DATE_UPLOAD -> MR.strings.sort_by_upload_date
                                ChapterSortMode.BY_ALPHABET -> MR.strings.action_sort_alpha
                            }.localized()
                            val direction = if (manga.sorting == mode.toMangaFlag()) {
                                if (manga.sortDescending()) " ↓" else " ↑"
                            } else {
                                ""
                            }
                            TextButton(onClick = { scope.launch { model.setChapterSort(manga, mode) } }) {
                                Text(label + direction)
                            }
                        }
                        2 -> listOf(
                            MR.strings.show_title to Manga.CHAPTER_DISPLAY_NAME,
                            MR.strings.show_chapter_number to Manga.CHAPTER_DISPLAY_NUMBER,
                        ).forEach { (label, value) ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    scope.launch { model.setChapterDisplayMode(manga, value) }
                                }.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = manga.displayMode == value, onClick = null)
                                Text(label.localized(), Modifier.padding(start = 12.dp))
                            }
                        }
                    }
                }
                state.chapterSettingsFeedback?.let {
                    Text(
                        it,
                        color = if (state.chapterSettingsFeedbackIsError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
                FlowRow(Modifier.fillMaxWidth()) {
                    TextButton(
                        onClick = {
                            model.clearChapterSettingsFeedback()
                            showDefaults = true
                        },
                        modifier = Modifier.focusRequester(defaultFocus),
                    ) {
                        Text(MR.strings.set_chapter_settings_as_default.localized())
                    }
                    TextButton(onClick = { scope.launch { model.resetChapterDefaults() } }) {
                        Text(MR.strings.action_reset.localized())
                    }
                    TextButton(onClick = onDismiss) {
                        Text(MR.strings.action_close.localized())
                    }
                }
            }
        }
        LaunchedEffect(Unit) {
            withFrameNanos { }
            focus.requestFocus()
        }
        LaunchedEffect(returnFocus) {
            returnFocus?.let {
                withFrameNanos { }
                it.requestFocus()
                returnFocus = null
            }
        }
        if (showDefaults) {
            ChapterDefaultsDialog(model) {
                showDefaults = false
                returnFocus = defaultFocus
            }
        }
        if (showScanlators) {
            ChapterScanlatorDialog(model) {
                showScanlators = false
                returnFocus = scanlatorFocus
            }
        }
    }
}

@Composable
private fun ChapterDefaultsDialog(model: MangaDetailScreenModel, onDismiss: () -> Unit) {
    val state by model.state.collectAsState()
    var applyExisting by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val cancelFocus = remember { FocusRequester() }
    val dismiss = { if (!busy) onDismiss() }
    AlertDialog(
        modifier = Modifier.categoryDialogEscape(!busy, dismiss).testTag("manga-chapter-defaults"),
        onDismissRequest = dismiss,
        title = { Text(MR.strings.set_chapter_settings_as_default.localized()) },
        text = {
            Column {
                Text(MR.strings.confirm_set_chapter_settings.localized())
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = !busy) { applyExisting = !applyExisting },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = applyExisting, onCheckedChange = null, enabled = !busy)
                    Text(MR.strings.also_set_chapter_settings_for_library.localized())
                }
                state.chapterSettingsFeedback?.let {
                    Text(
                        it,
                        color = if (state.chapterSettingsFeedbackIsError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                scope.launch {
                    busy = true
                    try {
                        if (model.saveChapterDefaults(applyExisting)) onDismiss()
                    } finally {
                        busy = false
                    }
                }
            }) { Text(MR.strings.action_ok.localized()) }
        },
        dismissButton = {
            TextButton(onClick = dismiss, enabled = !busy, modifier = Modifier.focusRequester(cancelFocus)) {
                Text(MR.strings.action_cancel.localized())
            }
        },
    )
    LaunchedEffect(Unit) {
        withFrameNanos { }
        cancelFocus.requestFocus()
    }
}

@Composable
private fun ChapterScanlatorDialog(model: MangaDetailScreenModel, onDismiss: () -> Unit) {
    val state by model.state.collectAsState()
    var draft by remember { mutableStateOf(state.excludedScanlators) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val cancelFocus = remember { FocusRequester() }
    val dismiss = { if (!busy) onDismiss() }
    AlertDialog(
        modifier = Modifier.categoryDialogEscape(!busy, dismiss).testTag("manga-chapter-scanlators"),
        onDismissRequest = dismiss,
        title = { Text(MR.strings.exclude_scanlators.localized()) },
        text = {
            Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                if (state.availableScanlators.isEmpty()) Text(MR.strings.no_scanlators_found.localized())
                state.availableScanlators.sorted().forEach { name ->
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = !busy) {
                            draft = if (name in draft) draft - name else draft + name
                        }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = name in draft, enabled = !busy, onCheckedChange = null)
                        Text(name, Modifier.padding(start = 12.dp))
                    }
                }
                state.chapterSettingsFeedback?.let {
                    Text(
                        it,
                        color = if (state.chapterSettingsFeedbackIsError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
        },
        confirmButton = {
            FlowRow {
                TextButton(enabled = !busy, onClick = { draft = state.availableScanlators }) {
                    Text(MR.strings.action_select_all.localized())
                }
                TextButton(enabled = !busy, onClick = { draft = emptySet() }) {
                    Text(MR.strings.action_reset.localized())
                }
                TextButton(enabled = !busy, onClick = {
                    scope.launch {
                        busy = true
                        try {
                            if (model.updateExcludedScanlators(draft)) onDismiss()
                        } finally {
                            busy = false
                        }
                    }
                }) { Text(MR.strings.action_ok.localized()) }
            }
        },
        dismissButton = {
            TextButton(onClick = dismiss, enabled = !busy, modifier = Modifier.focusRequester(cancelFocus)) {
                Text(MR.strings.action_cancel.localized())
            }
        },
    )
    LaunchedEffect(Unit) {
        withFrameNanos { }
        cancelFocus.requestFocus()
    }
}

internal fun readChapterFilter(manga: Manga): TriState = when (manga.unreadFilter) {
    TriState.DISABLED -> TriState.DISABLED
    TriState.ENABLED_IS -> TriState.ENABLED_NOT
    TriState.ENABLED_NOT -> TriState.ENABLED_IS
}

@Composable
private fun ChapterFilterOption(title: String, state: TriState, enabled: Boolean, onChange: (TriState) -> Unit) {
    val checkboxState = when (state) {
        TriState.DISABLED -> ToggleableState.Off
        TriState.ENABLED_IS -> ToggleableState.On
        TriState.ENABLED_NOT -> ToggleableState.Indeterminate
    }
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) {
            onChange(
                when (state) {
                    TriState.DISABLED -> TriState.ENABLED_IS
                    TriState.ENABLED_IS -> TriState.ENABLED_NOT
                    TriState.ENABLED_NOT -> TriState.DISABLED
                },
            )
        }.semantics {
            role = Role.Checkbox
            toggleableState = checkboxState
        }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TriStateCheckbox(
            state = checkboxState,
            enabled = enabled,
            onClick = null,
        )
        Text(title, Modifier.padding(start = 12.dp))
    }
}
