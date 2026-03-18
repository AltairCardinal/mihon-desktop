package mihon.desktop.ui.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material.icons.outlined.FitScreen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import mihon.desktop.reader.ReadingMode
import mihon.desktop.reader.ZoomState

/**
 * Reader settings dialog.
 *
 * Mirrors Android's reader bottom settings sheet:
 *   - Reading mode selector (LTR / RTL / Webtoon)
 *   - Dual-page toggle (disabled for Webtoon)
 *   - Zoom quick-controls (+/−/reset)
 */
@Composable
fun ReaderSettingsPanel(
    currentMode: ReadingMode,
    isDualPage: Boolean,
    zoomState: ZoomState,
    onModeChange: (ReadingMode) -> Unit,
    onDualPageChange: (Boolean) -> Unit,
    onZoomChange: (ZoomState) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reader Settings") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // ── Reading mode ──────────────────────────────────────────
                Text(
                    text = "Reading Mode",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                ReadingMode.entries.forEach { mode ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        RadioButton(
                            selected = currentMode == mode,
                            onClick = { onModeChange(mode) },
                        )
                        Text(
                            text = mode.displayName,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // ── Dual-page toggle (pager modes only) ───────────────────
                if (currentMode != ReadingMode.WEBTOON) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Checkbox(
                            checked = isDualPage,
                            onCheckedChange = onDualPageChange,
                        )
                        Text(
                            text = "Dual Page (side-by-side)",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                }

                // ── Zoom quick-controls ───────────────────────────────────
                Text(
                    text = "Zoom",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { onZoomChange(zoomState.zoomOut()) }) {
                        Icon(Icons.Default.ZoomOut, contentDescription = "Zoom Out", tint = Color.Unspecified)
                    }
                    Text(
                        text = "×${"%.1f".format(zoomState.scale)}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                    IconButton(onClick = { onZoomChange(zoomState.zoomIn()) }) {
                        Icon(Icons.Default.ZoomIn, contentDescription = "Zoom In", tint = Color.Unspecified)
                    }
                    IconButton(onClick = { onZoomChange(zoomState.reset()) }) {
                        Icon(Icons.Outlined.FitScreen, contentDescription = "Reset Zoom", tint = Color.Unspecified)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}
