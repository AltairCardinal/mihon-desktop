package mihon.desktop.ui.library

import tachiyomi.i18n.MR

import mihon.desktop.LocalDesktopUiDependencies

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import tachiyomi.domain.manga.interactor.UpdateMangaNotes
import tachiyomi.domain.manga.model.Manga

/**
 * Dialog for viewing and editing manga notes.
 * Mirrors the Android MangaNotesScreen but as an inline dialog for desktop.
 */
@Composable
fun MangaNotesDialog(
    manga: Manga,
    onDismiss: () -> Unit,
) {
    val updateMangaNotes = LocalDesktopUiDependencies.current.updateMangaNotes
    val scope = rememberCoroutineScope()
    var notes by remember { mutableStateOf(manga.notes) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(MR.strings.action_notes.localized()) },
        text = {
            Column {
                Text(
                    text = manga.title,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp),
                    placeholder = { Text(MR.strings.desktop_ui_write_notes_about_this_manga.localized()) },
                    maxLines = 10,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    updateMangaNotes(manga.id, notes)
                }
                onDismiss()
            }) { Text(MR.strings.action_save.localized()) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(MR.strings.action_cancel.localized()) }
        },
    )
}
