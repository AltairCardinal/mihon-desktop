package eu.kanade.presentation.more.settings.screen.about

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.domain.feedback.FeedbackDraftMemory
import tachiyomi.domain.feedback.FeedbackLanguage
import tachiyomi.domain.feedback.FeedbackPlatform
import tachiyomi.domain.feedback.FeedbackScreenshotInput
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
    val context = LocalContext.current.applicationContext
    val memory = remember {
        if (controllerFactory ==
            null
        ) {
            Injekt.get<FeedbackDraftMemory>()
        } else {
            FeedbackDraftMemory()
        }
    }
    val controller = remember(scope) {
        controllerFactory?.invoke(scope, summary, uriHandler::openUri)
            ?: createAndroidNativeFeedbackController(
                Injekt.get<NetworkHelper>(),
                scope,
                summary,
                uriHandler::openUri,
                context,
                memory.snapshot,
            )
    }
    DisposableEffect(controller) {
        onDispose {
            controller.cancel()
            memory.snapshot = controller.snapshot()
        }
    }
    val resolver = LocalContext.current.contentResolver
    var pickerToken by remember { mutableStateOf<Int?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val token = pickerToken
        pickerToken = null
        if (token != null) {
            scope.launch {
                try {
                    val inputs = withContext(Dispatchers.IO) {
                        androidFeedbackScreenshotInputs(resolver, uris)
                    }
                    controller.finishScreenshotSelection(token, inputs)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    controller.finishScreenshotSelection(token, emptyList(), true)
                }
            }
        }
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
        language = if (java.util.Locale.getDefault().language ==
            "zh"
        ) {
            FeedbackLanguage.CHINESE
        } else {
            FeedbackLanguage.ENGLISH
        },
        onPickScreenshot = {
            val token = controller.beginScreenshotSelection()
            if (token != null) {
                pickerToken = token
                try {
                    launcher.launch(arrayOf("image/png", "image/jpeg"))
                } catch (error: Exception) {
                    pickerToken = null
                    controller.finishScreenshotSelection(token, emptyList(), true)
                }
            }
        },
    )
}
