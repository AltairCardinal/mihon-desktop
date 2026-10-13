package eu.kanade.presentation.more.settings.screen.about

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.tachiyomi.BuildConfig
import tachiyomi.domain.feedback.FeedbackPlatform
import tachiyomi.domain.feedback.SafeFeedbackSummary
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
internal fun AboutFeedbackPreference() {
    var visible by remember { mutableStateOf(false) }
    TextPreferenceWidget(
        title = stringResource(MR.strings.feedback_report_problem),
        onPreferenceClick = { visible = true },
    )
    if (!visible) return
    val summary = remember {
        SafeFeedbackSummary(
            FeedbackPlatform.ANDROID,
            BuildConfig.VERSION_NAME,
            Build.VERSION.RELEASE,
            Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
            Build.VERSION.SDK_INT.toString(),
        ).text()
    }
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    val copied = stringResource(MR.strings.feedback_summary_copied)
    val copyFailed = stringResource(MR.strings.feedback_copy_failed)
    val opened = stringResource(MR.strings.feedback_opened)
    val openFailed = stringResource(MR.strings.feedback_open_failed)
    var feedback by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { visible = false },
        title = { Text(stringResource(MR.strings.feedback_report_problem)) },
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                Text(stringResource(MR.strings.feedback_privacy_notice))
                Text(summary, style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(MR.strings.feedback_logs_android), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = {
                    failed = runCatching { clipboard.setText(AnnotatedString(summary)) }.isFailure
                    feedback = if (failed) copyFailed else copied
                }) { Text(stringResource(MR.strings.feedback_copy_summary)) }
                feedback?.let {
                    Text(
                        it,
                        color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                failed = runCatching { uriHandler.openUri(SafeFeedbackSummary.ISSUE_FORM_URL) }.isFailure
                feedback = if (failed) openFailed else opened
            }) { Text(stringResource(MR.strings.feedback_open_issue)) }
        },
        dismissButton = {
            TextButton(onClick = { visible = false }) { Text(stringResource(MR.strings.action_close)) }
        },
    )
}
