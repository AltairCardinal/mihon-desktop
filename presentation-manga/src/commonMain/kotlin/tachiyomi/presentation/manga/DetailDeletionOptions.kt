package tachiyomi.presentation.manga

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tachiyomi.domain.download.service.MangaDownloadDeletion
import tachiyomi.domain.download.service.MangaDownloadDeletionSettings

/** Reading-mode tile language shared across hosts; text buttons allow wrapped labels to grow. */
@Composable
internal fun DetailDeletionModeChoices(
    selected: MangaDownloadDeletion,
    labels: DetailActionLabels,
    onSelect: (MangaDownloadDeletion) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth().selectableGroup().testTag("manga-delete-modes")) {
        val minimum = 128.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
        val columns = ((maxWidth + 8.dp) / (minimum + 8.dp)).toInt().coerceIn(1, 3)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            MangaDownloadDeletion.entries.chunked(columns).forEach { modes ->
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    modes.forEach { mode ->
                        FilledTonalButton(
                            onClick = { onSelect(mode) },
                            contentPadding = PaddingValues(8.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = if (mode == selected) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHighest
                                },
                                contentColor = if (mode == selected) {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            ),
                            modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 48.dp)
                                .testTag("manga-delete-choice-$mode")
                                .semantics {
                                    role = Role.RadioButton
                                    this.selected = mode == selected
                                },
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    when (mode) {
                                        MangaDownloadDeletion.INHERIT -> Icons.Rounded.Public
                                        MangaDownloadDeletion.ENABLED -> Icons.Rounded.Check
                                        MangaDownloadDeletion.DISABLED -> Icons.Rounded.Close
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp),
                                )
                                Text(labels.deletion(mode), style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                    repeat(columns - modes.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** Both hosts provide strings only; layout, modal selection and immediate changes are identical. */
@Composable
fun DetailDeletionOptions(
    settings: MangaDownloadDeletionSettings,
    onChange: (MangaDownloadDeletionSettings) -> Unit,
    retentionTitle: String,
    retentionEntries: List<String>,
    bookmarkTitle: String,
    cancelLabel: String,
) {
    var picker by remember { mutableStateOf(false) }
    Column(Modifier.padding(top = 12.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("manga-delete-retention")
                .clickable(role = Role.Button) { picker = true }.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    retentionTitle,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp,
                    lineHeight = 28.sp,
                )
                Text(
                    retentionEntries[settings.slots],
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("manga-delete-bookmarked")
                .toggleable(settings.removeBookmarked, role = Role.Switch) {
                    onChange(settings.copy(removeBookmarked = it))
                }.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                bookmarkTitle,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                lineHeight = 28.sp,
            )
            Switch(settings.removeBookmarked, onCheckedChange = null)
        }
    }
    if (picker) {
        AlertDialog(
            modifier = Modifier.widthIn(max = 400.dp).testTag("manga-delete-retention-picker").onPreviewKeyEvent {
                if (it.key == Key.Escape && it.type == KeyEventType.KeyDown) {
                    picker = false
                    true
                } else {
                    false
                }
            },
            onDismissRequest = { picker = false },
            title = { Text(retentionTitle) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()).selectableGroup()) {
                    retentionEntries.forEachIndexed { index, label ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .testTag("manga-delete-retention-$index")
                                .selectable(settings.slots == index, role = Role.RadioButton, onClick = {
                                    if (settings.slots != index) {
                                        onChange(settings.copy(slots = index))
                                        picker = false
                                    }
                                }).padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(24.dp),
                        ) {
                            RadioButton(settings.slots == index, onClick = null)
                            Text(label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(
                    onClick = { picker = false },
                    modifier = Modifier.testTag("manga-delete-retention-cancel"),
                ) { Text(cancelLabel) }
            },
        )
    }
}
