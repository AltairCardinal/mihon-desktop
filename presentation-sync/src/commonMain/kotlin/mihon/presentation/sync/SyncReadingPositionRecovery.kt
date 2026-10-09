package mihon.presentation.sync

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import tachiyomi.i18n.MR

@Composable
fun SyncReadingPositionRecovery(onChoose: () -> Unit, onDismiss: () -> Unit, chapterMissing: Boolean = false) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                syncString(
                    if (chapterMissing) {
                        MR.strings.sync_resume_chapter_unavailable
                    } else {
                        MR.strings.sync_resume_page_unavailable
                    },
                ),
            )
        },
        text = { Text(syncString(MR.strings.sync_reader_position_boundary)) },
        confirmButton = {
            TextButton(onClick = onChoose, modifier = Modifier.testTag("sync-reader-choose-position")) {
                Text(syncString(MR.strings.sync_reader_choose_position))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(syncString(MR.strings.sync_cancel)) } },
    )
}

@Composable
fun SyncReadingPositionConfirmation(onConfirm: () -> Unit) {
    TextButton(onClick = onConfirm, modifier = Modifier.testTag("sync-reader-confirm-position")) {
        Text(syncString(MR.strings.sync_reader_confirm_position))
    }
}
