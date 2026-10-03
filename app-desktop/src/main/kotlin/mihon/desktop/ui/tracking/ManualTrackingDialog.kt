package mihon.desktop.ui.tracking

import androidx.compose.foundation.focusable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import kotlinx.coroutines.launch
import mihon.desktop.tracking.DesktopManualTracking
import mihon.desktop.ui.library.categoryDialogEscape
import mihon.desktop.ui.settings.DesktopSettingsTextButton
import tachiyomi.i18n.MR

@Composable
internal fun ManualTrackingDialog(controller: DesktopManualTracking?, onClosed: () -> Unit) {
    if (controller == null) return
    val prompts by controller.prompts.collectAsState()
    val prompt = prompts.firstOrNull() ?: return
    var busy by remember(prompt.eventId) { mutableStateOf(false) }
    val focus = remember(prompt.eventId) { FocusRequester() }
    val scope = rememberCoroutineScope()
    val dismiss = {
        if (!busy) {
            controller.cancel(prompt)
            onClosed()
        }
    }
    LaunchedEffect(prompt.eventId) {
        withFrameNanos { }
        focus.requestFocus()
    }
    AlertDialog(
        modifier = Modifier.categoryDialogEscape(!busy, dismiss),
        onDismissRequest = dismiss,
        title = { Text(MR.strings.pref_category_tracking.localized()) },
        text = {
            Text(MR.strings.confirm_tracker_update.localized(java.util.Locale.getDefault(), prompt.progress.toInt()))
        },
        confirmButton = {
            DesktopSettingsTextButton(enabled = !busy, onClick = {
                scope.launch {
                    busy = true
                    try {
                        if (controller.confirm(prompt)) onClosed()
                    } finally {
                        busy = false
                    }
                }
            }) { Text(MR.strings.action_ok.localized()) }
        },
        dismissButton = {
            DesktopSettingsTextButton(modifier = Modifier.focusRequester(focus), enabled = !busy, onClick = dismiss) {
                Text(MR.strings.action_cancel.localized())
            }
        },
    )
}
