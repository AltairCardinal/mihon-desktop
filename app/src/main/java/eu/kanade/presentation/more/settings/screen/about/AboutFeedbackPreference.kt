package eu.kanade.presentation.more.settings.screen.about

import android.os.Build
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
import androidx.compose.ui.text.AnnotatedString
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.CoroutineScope
import tachiyomi.domain.feedback.FeedbackPlatform
import tachiyomi.domain.feedback.NativeFeedbackController
import tachiyomi.domain.feedback.SafeFeedbackSummary
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.feedback.NativeFeedbackDialog
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
internal fun AboutFeedbackPreference(
    controllerFactory: ((CoroutineScope, String, (String) -> Unit) -> NativeFeedbackController)? = null,
) {
    var visible by remember { mutableStateOf(false) }
    var restoreFocus by remember { mutableStateOf(0) }
    val trigger = remember { FocusRequester() }
    TextPreferenceWidget(
        modifier = Modifier.focusRequester(trigger),
        title = stringResource(MR.strings.feedback_report_problem),
        onPreferenceClick = { visible = true },
    )
    LaunchedEffect(restoreFocus) {
        if (restoreFocus > 0) {
            withFrameNanos { }
            trigger.requestFocus()
        }
    }
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
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    val controller = remember(scope) {
        controllerFactory?.invoke(scope, summary, uriHandler::openUri)
            ?: createAndroidNativeFeedbackController(Injekt.get<NetworkHelper>(), scope, summary, uriHandler::openUri)
    }
    NativeFeedbackDialog(
        controller = controller,
        summary = summary,
        onClose = {
            visible = false
            restoreFocus++
        },
        copySummary = { clipboard.setText(AnnotatedString(summary)) },
        text = { stringResource(it) },
    )
}
