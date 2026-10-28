package mihon.desktop.ui.settings

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.CoroutineScope
import mihon.desktop.APP_VERSION
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.feedback.createNativeFeedbackController
import mihon.desktop.platform.DesktopPlatformPaths
import tachiyomi.domain.feedback.FeedbackPlatform
import tachiyomi.domain.feedback.NativeFeedbackController
import tachiyomi.domain.feedback.SafeFeedbackSummary
import tachiyomi.i18n.MR
import tachiyomi.presentation.feedback.NativeFeedbackDialog

@Composable
internal fun AboutFeedbackSection(
    platformPaths: DesktopPlatformPaths = DesktopPlatformPaths.current(),
    controllerFactory: ((CoroutineScope, String, (String) -> Unit) -> NativeFeedbackController)? = null,
) {
    var visible by remember { mutableStateOf(false) }
    var restoreFocus by remember { mutableStateOf(0) }
    val trigger = remember { FocusRequester() }
    val open = { visible = true }
    Button(
        onClick = open,
        modifier = Modifier.focusRequester(trigger).desktopSettingsActivationKeys(Role.Button, onClick = open),
    ) {
        Text(MR.strings.feedback_report_problem.localized())
    }
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
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    val dependencies = if (controllerFactory == null) LocalDesktopUiDependencies.current else null
    val controller = remember(scope) {
        controllerFactory?.invoke(scope, summary, uriHandler::openUri)
            ?: requireNotNull(dependencies).createNativeFeedbackController(
                platformPaths,
                scope,
                summary,
                uriHandler::openUri,
            )
    }
    NativeFeedbackDialog(
        controller = controller,
        summary = summary,
        onClose = {
            visible = false
            restoreFocus++
        },
        copySummary = { clipboard.setText(AnnotatedString(summary)) },
        text = { it.localized() },
    )
}
