package eu.kanade.presentation.more.settings.screen.about

import android.content.Context
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.CoroutineScope
import mihon.feedback.androidFeedbackCrashReader
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import tachiyomi.core.common.feedback.DiagnosticStore
import tachiyomi.data.feedback.NativeFeedbackHttpService
import tachiyomi.domain.feedback.FeedbackDiagnosticController
import tachiyomi.domain.feedback.FeedbackLogReader
import tachiyomi.domain.feedback.NativeFeedbackController
import tachiyomi.domain.feedback.NativeFeedbackSnapshot
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

internal fun createAndroidNativeFeedbackService(
    helper: NetworkHelper,
    baseUrl: HttpUrl = NativeFeedbackHttpService.ORIGIN.toHttpUrl(),
): NativeFeedbackHttpService = NativeFeedbackHttpService(helper.nonCloudflareClient, baseUrl)

internal fun createAndroidNativeFeedbackController(
    helper: NetworkHelper,
    scope: CoroutineScope,
    summary: String,
    openVerification: (String) -> Unit,
    context: Context,
    initialSnapshot: NativeFeedbackSnapshot? = null,
): NativeFeedbackController = NativeFeedbackController(
    createAndroidNativeFeedbackService(helper),
    androidFeedbackCrashReader(context),
    summary,
    scope,
    openVerification,
    language = if (java.util.Locale.getDefault().language == "zh") {
        tachiyomi.domain.feedback.FeedbackLanguage.CHINESE
    } else {
        tachiyomi.domain.feedback.FeedbackLanguage.ENGLISH
    },
    screenshotProcessor = AndroidFeedbackScreenshotProcessor(),
    diagnostics = FeedbackDiagnosticController(
        Injekt.get<DiagnosticStore>(),
        androidFeedbackCrashReader(context),
        scope,
    ),
    initialSnapshot = initialSnapshot,
)
