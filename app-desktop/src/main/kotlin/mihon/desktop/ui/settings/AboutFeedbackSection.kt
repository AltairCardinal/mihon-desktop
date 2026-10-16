package mihon.desktop.ui.settings

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import mihon.desktop.APP_VERSION
import mihon.desktop.ui.library.categoryDialogEscape
import tachiyomi.domain.feedback.FeedbackPlatform
import tachiyomi.domain.feedback.SafeFeedbackSummary
import tachiyomi.i18n.MR

@Composable
internal fun AboutFeedbackSection() {
    var visible by remember { mutableStateOf(false) }
    var restoreFocus by remember { mutableStateOf(0) }
    val trigger = remember { FocusRequester() }
    val open = { visible = true }
    Button(
        onClick = open,
        modifier = Modifier.focusRequester(trigger).desktopSettingsActivationKeys(Role.Button, onClick = open),
    ) { Text(MR.strings.feedback_report_problem.localized()) }
    LaunchedEffect(restoreFocus) {
        if (restoreFocus > 0) {
            withFrameNanos { }
            trigger.requestFocus()
        }
    }
    if (!visible) return
    val summary = remember {
        SafeFeedbackSummary(
            FeedbackPlatform.fromOsName(System.getProperty("os.name").orEmpty()),
            APP_VERSION,
            System.getProperty("os.version").orEmpty(),
            System.getProperty("os.arch").orEmpty(),
            System.getProperty("java.version").orEmpty(),
        ).text()
    }
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    val dialogFocus = remember { FocusRequester() }
    val copyFocus = remember { FocusRequester() }
    val openFocus = remember { FocusRequester() }
    val closeFocus = remember { FocusRequester() }
    var feedback by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    val close: () -> Unit = { visible = false; restoreFocus++ }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        dialogFocus.requestFocus()
    }
    AlertDialog(
        modifier = Modifier.categoryDialogEscape(true, close).focusRequester(dialogFocus).focusable(),
        onDismissRequest = close,
        title = { Text(MR.strings.feedback_report_problem.localized()) },
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                Text(MR.strings.feedback_privacy_notice.localized())
                Text(summary, style = MaterialTheme.typography.bodyMedium)
                Text(MR.strings.feedback_logs_desktop.localized(), style = MaterialTheme.typography.bodySmall)
                DesktopSettingsTextButton(
                    modifier = Modifier.focusRequester(copyFocus).focusProperties { next = openFocus; previous = closeFocus },
                    onClick = {
                        failed = runCatching { clipboard.setText(AnnotatedString(summary)) }.isFailure
                        feedback = if (failed) MR.strings.feedback_copy_failed.localized() else MR.strings.feedback_summary_copied.localized()
                    },
                ) { Text(MR.strings.feedback_copy_summary.localized()) }
                feedback?.let { Text(it, color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) }
            }
        },
        confirmButton = {
            DesktopSettingsTextButton(
                modifier = Modifier.focusRequester(openFocus).focusProperties { next = closeFocus; previous = copyFocus },
                onClick = {
                    failed = runCatching { uriHandler.openUri(SafeFeedbackSummary.FEEDBACK_FORM_URL) }.isFailure
                    feedback = if (failed) MR.strings.feedback_open_failed.localized() else MR.strings.feedback_opened.localized()
                },
            ) { Text(MR.strings.feedback_open_issue.localized()) }
        },
        dismissButton = {
            DesktopSettingsTextButton(
                modifier = Modifier.focusRequester(closeFocus).focusProperties { next = copyFocus; previous = openFocus },
                onClick = close,
            ) { Text(MR.strings.action_close.localized()) }
        },
    )
}
