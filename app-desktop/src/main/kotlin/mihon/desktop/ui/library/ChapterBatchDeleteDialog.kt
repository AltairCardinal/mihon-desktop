package mihon.desktop.ui.library

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import tachiyomi.domain.chapter.interactor.BatchChapterResult
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import java.util.Locale

internal data class ChapterBatchDeleteSnapshot(
    val manga: Manga,
    val chapters: List<Chapter>,
    val downloadedCount: Int,
    val complete: (Collection<Long>) -> Unit,
)

@Composable
internal fun ChapterBatchDeleteDialog(
    snapshot: ChapterBatchDeleteSnapshot,
    onDismiss: () -> Unit,
    onConfirm: suspend () -> BatchChapterResult,
) {
    val scope = rememberCoroutineScope()
    val cancelFocus = remember { FocusRequester() }
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { cancelFocus.requestFocus() }
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
        title = { Text(MR.strings.desktop_ui_delete_download_bba9a9de.localized()) },
        text = {
            Column {
                Text(
                    MR.strings.desktop_chapter_delete_snapshot.localized(
                        Locale.getDefault(),
                        snapshot.chapters.size,
                        snapshot.downloadedCount,
                    ),
                )
                feedback?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && snapshot.downloadedCount > 0, onClick = {
                busy = true
                scope.launch {
                    try {
                        val result = onConfirm()
                        if (result.failures.isEmpty()) {
                            onDismiss()
                        } else {
                            feedback =
                                MR.strings.desktop_chapter_batch_result.localized(
                                    Locale.getDefault(),
                                    result.succeededIds.size,
                                    result.skippedIds.size,
                                    result.failures.size,
                                )
                        }
                    } catch (canceled: CancellationException) {
                        throw canceled
                    } catch (_: Exception) {
                        feedback = MR.strings.desktop_detail_save_failed.localized()
                    } finally {
                        busy = false
                    }
                }
            }) { Text(MR.strings.action_delete.localized(), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = {
            TextButton(modifier = Modifier.focusRequester(cancelFocus), enabled = !busy, onClick = close) {
                Text(MR.strings.action_cancel.localized())
            }
        },
    )
}
