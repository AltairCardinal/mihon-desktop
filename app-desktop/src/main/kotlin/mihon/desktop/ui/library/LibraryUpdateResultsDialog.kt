package mihon.desktop.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import mihon.desktop.task.LibraryUnitStatus
import mihon.desktop.task.StoredTask
import mihon.domain.task.TaskStatus
import tachiyomi.domain.library.service.LibraryUpdateSkipReason
import tachiyomi.i18n.MR
import java.util.Locale

@Composable
internal fun LibraryUpdateResultsDialog(
    task: StoredTask,
    running: Boolean,
    onRetryFailed: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    val units = task.libraryUpdate?.units.orEmpty()
    val closeFocus = remember { FocusRequester() }
    var dialogFocus by remember { mutableStateOf<androidx.compose.ui.focus.FocusManager?>(null) }
    LaunchedEffect(Unit) { closeFocus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.onPreviewKeyEvent {
            when {
                it.type != KeyEventType.KeyDown -> false
                it.key == Key.Escape -> {
                    onDismiss()
                    true
                }
                it.key == Key.Tab -> {
                    dialogFocus?.moveFocus(if (it.isShiftPressed) FocusDirection.Previous else FocusDirection.Next)
                    true
                }
                else -> false
            }
        },
        title = { Text(MR.strings.desktop_library_update_results.localized()) },
        text = {
            val focusManager = LocalFocusManager.current
            DisposableEffect(focusManager) {
                dialogFocus = focusManager
                onDispose { dialogFocus = null }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(requireNotNull(libraryUpdateSummary(task)))
                if (task.status == TaskStatus.Failed && units.none { it.status == LibraryUnitStatus.FAILED }) {
                    Text(MR.strings.desktop_library_update_cleanup_needed.localized())
                }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 340.dp)) {
                    items(units, key = { it.mangaId }) { unit ->
                        Column {
                            Text(unit.title.ifBlank { MR.strings.desktop_library_update_item_missing.localized() })
                            if (unit.localSource && unit.status == LibraryUnitStatus.FAILED) {
                                Text(MR.strings.desktop_library_update_local_failed.localized())
                            } else if (unit.sourceUnavailable) {
                                Text(
                                    MR.strings.source_not_installed.localized(
                                        Locale.getDefault(),
                                        unit.sourceId.toString(),
                                    ),
                                )
                            } else {
                                unit.failure?.let { failure ->
                                    Text(mihon.desktop.ui.source.desktopSourceErrorMessage(failure.toAppError()))
                                }
                            }
                            unit.skipReason?.let { reason ->
                                Text(libraryUpdateSkipMessage(reason))
                            }
                            if (unit.localSource) Text(MR.strings.local_source.localized())
                            val status = when (unit.status) {
                                LibraryUnitStatus.SUCCESS -> MR.strings.desktop_library_update_unit_success.localized(
                                    Locale.getDefault(),
                                    unit.newChapterCount,
                                )
                                LibraryUnitStatus.SKIPPED -> MR.strings.desktop_library_update_unit_skipped.localized()
                                LibraryUnitStatus.FAILED -> MR.strings.desktop_library_update_unit_failed.localized()
                                LibraryUnitStatus.UNPROCESSED ->
                                    MR.strings.desktop_library_update_unit_unprocessed.localized()
                            }
                            Text(status, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = {
            FlowRow {
                if (running) {
                    TextButton(onClick = onCancel) { Text(MR.strings.action_cancel.localized()) }
                } else {
                    if (units.any { it.status == LibraryUnitStatus.FAILED }) {
                        TextButton(onClick = onRetryFailed) { Text(MR.strings.desktop_ui_retry_failed.localized()) }
                    }
                    if (units.any { it.status == LibraryUnitStatus.UNPROCESSED } || task.status == TaskStatus.Failed) {
                        TextButton(onClick = onResume) { Text(MR.strings.action_resume.localized()) }
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.focusRequester(closeFocus)) {
                    Text(MR.strings.action_close.localized())
                }
            }
        },
    )
}

internal fun libraryUpdateSkipMessage(reason: LibraryUpdateSkipReason): String = when (reason) {
    LibraryUpdateSkipReason.ONLY_FETCH_ONCE -> MR.strings.skipped_reason_not_always_update
    LibraryUpdateSkipReason.COMPLETED -> MR.strings.skipped_reason_completed
    LibraryUpdateSkipReason.HAS_UNREAD -> MR.strings.skipped_reason_not_caught_up
    LibraryUpdateSkipReason.NOT_STARTED -> MR.strings.skipped_reason_not_started
    LibraryUpdateSkipReason.OUTSIDE_RELEASE_PERIOD -> MR.strings.skipped_reason_not_in_release_period
}.localized()
