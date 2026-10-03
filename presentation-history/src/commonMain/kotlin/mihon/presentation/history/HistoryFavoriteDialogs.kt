package mihon.presentation.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import tachiyomi.domain.history.service.HistoryDialog
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR

@Composable
fun HistoryCategoryDialog(
    dialog: HistoryDialog.ChangeCategory,
    onSelect: (Long, Boolean) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onEdit: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.historyDialogKeyboard(onDismiss),
        title = { Text(historyString(MR.strings.action_move_category)) },
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                dialog.categories.forEach { category ->
                    Row {
                        Checkbox(category.id in dialog.selectedIds, {
                            onSelect(category.id, it)
                        }, Modifier.testTag("history_category_${category.id}"))
                        Text(category.name)
                    }
                }
                TextButton(onClick = onEdit, modifier = Modifier.testTag("history_category_edit")) {
                    Text(historyString(MR.strings.action_edit_categories))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("history_category_confirm")) {
                Text(historyString(MR.strings.action_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("history_category_cancel")) {
                Text(historyString(MR.strings.action_cancel))
            }
        },
    )
}

@Composable
fun HistoryDuplicateDialog(
    dialog: HistoryDialog.Duplicate,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onOpen: (Manga) -> Unit,
    onMigrate: (Manga) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.historyDialogKeyboard(onDismiss),
        title = { Text(historyString(MR.strings.possible_duplicates_title)) },
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                Text(historyString(MR.strings.possible_duplicates_summary))
                dialog.duplicates.forEach { duplicate ->
                    TextButton(onClick = {
                        onDismiss()
                        onOpen(duplicate.manga)
                    }, modifier = Modifier.testTag("history_duplicate_open_${duplicate.manga.id}")) {
                        Text(duplicate.manga.title)
                    }
                    TextButton(onClick = {
                        onMigrate(duplicate.manga)
                    }, modifier = Modifier.testTag("history_duplicate_migrate_${duplicate.manga.id}")) {
                        Text(historyString(MR.strings.action_migrate))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("history_duplicate_confirm")) {
                Text(historyString(MR.strings.action_add_anyway))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("history_duplicate_cancel")) {
                Text(historyString(MR.strings.action_cancel))
            }
        },
    )
}
