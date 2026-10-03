package mihon.desktop.ui.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR

/** The same typed/version request as the mounted information cover, with only implemented actions. */
@Composable
internal fun MangaCoverViewerDialog(
    manga: Manga,
    coverModel: String?,
    coverVersion: Long,
    hasCustomCover: Boolean,
    busy: Boolean,
    feedback: String?,
    onDismiss: () -> Unit,
    onReplace: () -> Unit,
    onDelete: () -> Unit,
    onSave: (coil3.Image) -> Unit,
    onShare: (coil3.Image) -> Unit,
) {
    val request = rememberMangaCoverRequestState(manga.id, manga.source, coverModel, coverVersion)
    var loadedImage by remember(request.request) { mutableStateOf<coil3.Image?>(null) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var editing by remember { mutableStateOf(false) }
    val closeFocus = remember { FocusRequester() }
    LaunchedEffect(editing, busy) {
        if (!editing && !busy) {
            androidx.compose.runtime.withFrameNanos { }
            closeFocus.requestFocus()
        }
    }
    val close = { if (!busy) onDismiss() }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(.92f).fillMaxHeight(.92f).onPreviewKeyEvent {
                if (it.key == Key.Escape && it.type == KeyEventType.KeyDown) {
                    if (editing) editing = false else close()
                    true
                } else {
                    false
                }
            },
            shape = MaterialTheme.shapes.large,
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(modifier = Modifier.focusRequester(closeFocus), enabled = !busy, onClick = close) {
                        Icon(Icons.Default.Close, MR.strings.action_close.localized())
                    }
                    Text(manga.title, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    IconButton(enabled = !busy && loadedImage != null, onClick = { loadedImage?.let(onSave) }) {
                        Icon(Icons.Default.Save, MR.strings.action_save.localized())
                    }
                    IconButton(enabled = !busy && loadedImage != null, onClick = { loadedImage?.let(onShare) }) {
                        Icon(Icons.Default.Share, MR.strings.action_share.localized())
                    }
                    Box {
                        IconButton(enabled = !busy, onClick = {
                            editing = true
                        }) { Icon(Icons.Default.Edit, MR.strings.action_edit_cover.localized()) }
                        DropdownMenu(expanded = editing, onDismissRequest = { editing = false }) {
                            DropdownMenuItem(
                                text = { Text(MR.strings.action_edit_cover.localized()) },
                                onClick = {
                                    editing = false
                                    onReplace()
                                },
                            )
                            if (hasCustomCover) {
                                DropdownMenuItem(
                                    text = { Text(MR.strings.desktop_ui_delete_cover.localized()) },
                                    onClick = {
                                        editing = false
                                        onDelete()
                                    },
                                )
                            }
                        }
                    }
                }
                BoxWithConstraints(
                    Modifier.weight(1f).fillMaxWidth().clipToBounds(),
                    contentAlignment = Alignment.Center,
                ) {
                    AsyncImage(
                        model = request.request,
                        onSuccess = { loadedImage = it.result.image },
                        onError = { loadedImage = null },
                        contentDescription = manga.title,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.height(
                            minOf(maxHeight, maxWidth / .7f),
                        ).aspectRatio(.7f).graphicsLayer(scaleX = zoom, scaleY = zoom),
                    )
                }
                Text(MR.strings.desktop_ui_zoom.localized())
                Slider(value = zoom, onValueChange = { zoom = it }, valueRange = 1f..3f)
                feedback?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
