package mihon.desktop.ui.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.FormatBold
import androidx.compose.material.icons.outlined.FormatItalic
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material.icons.outlined.FormatUnderlined
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.mohamedrejeb.richeditor.model.rememberRichTextState
import com.mohamedrejeb.richeditor.ui.material3.RichText
import com.mohamedrejeb.richeditor.ui.material3.RichTextEditor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import mihon.desktop.LocalDesktopUiDependencies
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR

@Composable
internal fun MangaNotesSummary(content: String) {
    val state = rememberRichTextState()
    val primary = MaterialTheme.colorScheme.primary
    LaunchedEffect(content) { state.setMarkdown(content) }
    LaunchedEffect(primary) {
        state.config.linkColor = primary
        state.config.unorderedListIndent = 4
        state.config.orderedListIndent = 20
    }
    RichText(
        state = state,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 3,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
    )
}

/** The Android editor and Markdown storage semantics, with an explicit Desktop draft owner. */
@Composable
fun MangaNotesDialog(manga: Manga, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val updateNotes = LocalDesktopUiDependencies.current.updateMangaNotes
    val scope = rememberCoroutineScope()
    val richText = rememberRichTextState()
    val focus = remember { FocusRequester() }
    val primary = MaterialTheme.colorScheme.primary
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(manga.id) {
        richText.setMarkdown(manga.notes)
        richText.config.unorderedListIndent = 4
        richText.config.orderedListIndent = 20
        focus.requestFocus()
    }
    LaunchedEffect(primary) {
        richText.config.linkColor = primary
    }
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
        title = { Text(MR.strings.action_notes.localized()) },
        text = {
            val dialogFocus = LocalFocusManager.current
            Column {
                Text(manga.title, Modifier.padding(bottom = 8.dp))
                RichTextEditor(
                    state = richText,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(
                        min = 120.dp,
                        max = 300.dp,
                    ).focusRequester(focus).onPreviewKeyEvent {
                        if (it.key == Key.Tab && it.type == KeyEventType.KeyDown) {
                            dialogFocus.moveFocus(
                                if (it.isShiftPressed) FocusDirection.Previous else FocusDirection.Next,
                            )
                            true
                        } else {
                            false
                        }
                    },
                    textStyle = MaterialTheme.typography.bodyLarge,
                    placeholder = { Text(MR.strings.notes_placeholder.localized()) },
                )
                FlowRow(Modifier.fillMaxWidth()) {
                    IconToggleButton(
                        checked = richText.currentSpanStyle.fontWeight == FontWeight.Bold,
                        onCheckedChange = { richText.toggleSpanStyle(SpanStyle(fontWeight = FontWeight.Bold)) },
                        enabled = !busy,
                    ) { Icon(Icons.Outlined.FormatBold, MR.strings.desktop_notes_bold.localized()) }
                    IconToggleButton(
                        checked = richText.currentSpanStyle.fontStyle == FontStyle.Italic,
                        onCheckedChange = { richText.toggleSpanStyle(SpanStyle(fontStyle = FontStyle.Italic)) },
                        enabled = !busy,
                    ) { Icon(Icons.Outlined.FormatItalic, MR.strings.desktop_notes_italic.localized()) }
                    IconToggleButton(
                        checked = richText.currentSpanStyle.textDecoration?.contains(TextDecoration.Underline) == true,
                        onCheckedChange = {
                            richText.toggleSpanStyle(SpanStyle(textDecoration = TextDecoration.Underline))
                        },
                        enabled = !busy,
                    ) { Icon(Icons.Outlined.FormatUnderlined, MR.strings.desktop_notes_underline.localized()) }
                    IconToggleButton(
                        checked = richText.isUnorderedList,
                        onCheckedChange = { richText.toggleUnorderedList() },
                        enabled = !busy,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Outlined.FormatListBulleted,
                            MR.strings.desktop_notes_unordered.localized(),
                        )
                    }
                    IconToggleButton(
                        checked = richText.isOrderedList,
                        onCheckedChange = { richText.toggleOrderedList() },
                        enabled = !busy,
                    ) { Icon(Icons.Outlined.FormatListNumbered, MR.strings.desktop_notes_ordered.localized()) }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    try {
                        check(updateNotes(manga.id, richText.toMarkdown()))
                        onDismiss()
                        onSaved()
                    } catch (canceled: CancellationException) {
                        throw canceled
                    } catch (_: Exception) {
                        error = MR.strings.desktop_notes_save_failed.localized()
                    } finally {
                        busy = false
                    }
                }
            }) { Text(MR.strings.action_save.localized()) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = close) { Text(MR.strings.action_cancel.localized()) } },
    )
}
