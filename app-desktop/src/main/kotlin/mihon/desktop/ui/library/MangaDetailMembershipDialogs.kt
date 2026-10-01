package mihon.desktop.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaWithChapterCount
import tachiyomi.i18n.MR

@Composable
internal fun DuplicateMangaDialog(
    entries: List<MangaWithChapterCount>,
    onDismiss: () -> Unit,
    onView: (Manga) -> Unit,
    onMigrate: (Manga) -> Unit,
    onAdd: suspend () -> Boolean,
) {
    val scope = rememberCoroutineScope()
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { cancelFocus.requestFocus() }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val close = { if (!busy) onDismiss() }
    AlertDialog(
        modifier = Modifier.onPreviewKeyEvent {
            if (it.key == Key.Escape && it.type == KeyEventType.KeyDown) {
                close()
                true
            } else {
                false
            }
        },
        onDismissRequest = close,
        title = { Text(MR.strings.possible_duplicates_title.localized()) },
        text = {
            Column {
                Text(MR.strings.possible_duplicates_summary.localized())
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                    items(entries, key = { it.manga.id }) { entry ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Text(
                                entry.manga.title,
                                modifier = Modifier.clickable(enabled = !busy) {
                                    onView(entry.manga)
                                },
                            )
                            Text(
                                "${entry.chapterCount} ${MR.strings.chapters.localized()}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            TextButton(enabled = !busy, onClick = {
                                onMigrate(entry.manga)
                            }) { Text(MR.strings.action_migrate.localized()) }
                        }
                    }
                }
                if (error) {
                    Text(
                        MR.strings.desktop_detail_save_failed.localized(),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    try {
                        if (onAdd()) onDismiss() else error = true
                    } catch (canceled: CancellationException) {
                        throw canceled
                    } catch (_: Exception) {
                        error = true
                    } finally {
                        busy = false
                    }
                }
            }) { Text(MR.strings.action_add_anyway.localized()) }
        },
        dismissButton = {
            TextButton(modifier = Modifier.focusRequester(cancelFocus), enabled = !busy, onClick = close) {
                Text(MR.strings.action_cancel.localized())
            }
        },
    )
}

internal enum class MangaRemovalResult { SUCCESS, MEMBERSHIP_FAILED, DOWNLOADS_FAILED }

@Composable
internal fun RemoveFavoriteDialog(
    manga: Manga,
    onDismiss: () -> Unit,
    onConfirm: suspend (Boolean, Boolean) -> MangaRemovalResult,
) {
    val scope = rememberCoroutineScope()
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { cancelFocus.requestFocus() }
    var deleteFiles by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var partial by remember { mutableStateOf(false) }
    val close = { if (!busy) onDismiss() }
    AlertDialog(
        modifier = Modifier.onPreviewKeyEvent {
            if (it.key == Key.Escape && it.type == KeyEventType.KeyDown) {
                close()
                true
            } else {
                false
            }
        },
        onDismissRequest = close,
        title = { Text(MR.strings.remove_from_library.localized()) },
        text = {
            Column {
                Text(manga.title)
                if (manga.source != 0L) {
                    Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { deleteFiles = !deleteFiles }) {
                        Checkbox(checked = deleteFiles, enabled = !busy, onCheckedChange = { deleteFiles = it })
                        Text(MR.strings.delete_downloads_for_manga.localized())
                    }
                }
                if (error) {
                    val message = if (partial) {
                        MR.strings.desktop_detail_removal_partial.localized()
                    } else {
                        MR.strings.desktop_detail_save_failed.localized()
                    }
                    Text(message, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    try {
                        when (onConfirm(deleteFiles, partial)) {
                            MangaRemovalResult.SUCCESS -> onDismiss()
                            MangaRemovalResult.MEMBERSHIP_FAILED -> {
                                partial = false
                                error = true
                            }
                            MangaRemovalResult.DOWNLOADS_FAILED -> {
                                partial = true
                                error = true
                            }
                        }
                    } catch (canceled: CancellationException) {
                        throw canceled
                    } catch (_: Exception) {
                        error = true
                    } finally {
                        busy = false
                    }
                }
            }) { Text(MR.strings.action_remove.localized()) }
        },
        dismissButton = {
            TextButton(modifier = Modifier.focusRequester(cancelFocus), enabled = !busy, onClick = close) {
                Text(MR.strings.action_cancel.localized())
            }
        },
    )
}
