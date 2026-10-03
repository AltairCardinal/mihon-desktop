package mihon.presentation.history

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import tachiyomi.i18n.MR

@Composable
fun HistoryDeleteDialog(onDismissRequest: () -> Unit, onDelete: (Boolean) -> Unit) {
    var all by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        modifier = Modifier.historyDialogKeyboard(onDismissRequest),
        title = { Text(historyString(MR.strings.action_remove)) },
        text = {
            Column {
                Text(historyString(MR.strings.dialog_with_checkbox_remove_description))
                Row {
                    Checkbox(all, { all = it }, modifier = Modifier.testTag("history_delete_all_chapters"))
                    Text(historyString(MR.strings.dialog_with_checkbox_reset))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onDelete(all)
                onDismissRequest()
            }, modifier = Modifier.testTag("history_delete_confirm")) {
                Text(historyString(MR.strings.action_remove))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest, modifier = Modifier.testTag("history_delete_cancel")) {
                Text(historyString(MR.strings.action_cancel))
            }
        },
    )
}

@Composable
fun HistoryDeleteAllDialog(onDismissRequest: () -> Unit, onDelete: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        modifier = Modifier.historyDialogKeyboard(onDismissRequest),
        title = { Text(historyString(MR.strings.action_remove_everything)) },
        text = { Text(historyString(MR.strings.clear_history_confirmation)) },
        confirmButton = {
            TextButton(onClick = {
                onDelete()
                onDismissRequest()
            }, modifier = Modifier.testTag("history_clear_confirm")) { Text(historyString(MR.strings.action_remove)) }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest, modifier = Modifier.testTag("history_clear_cancel")) {
                Text(historyString(MR.strings.action_cancel))
            }
        },
    )
}

internal fun Modifier.historyDialogKeyboard(onDismiss: () -> Unit) = onPreviewKeyEvent {
    if (it.key == Key.Escape && it.type == KeyEventType.KeyDown) {
        onDismiss()
        true
    } else {
        false
    }
}
