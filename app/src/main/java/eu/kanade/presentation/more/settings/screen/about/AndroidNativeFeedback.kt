package eu.kanade.presentation.more.settings.screen.about

import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.util.CrashLogUtil
import kotlinx.coroutines.CoroutineScope
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import tachiyomi.data.feedback.NativeFeedbackHttpService
import tachiyomi.domain.feedback.FeedbackLogReader
import tachiyomi.domain.feedback.NativeFeedbackController

internal fun createAndroidNativeFeedbackService(
    helper: NetworkHelper,
    baseUrl: HttpUrl = NativeFeedbackHttpService.ORIGIN.toHttpUrl(),
): NativeFeedbackHttpService = NativeFeedbackHttpService(helper.nonCloudflareClient, baseUrl)

internal fun createAndroidNativeFeedbackController(
    helper: NetworkHelper,
    scope: CoroutineScope,
    summary: String,
    openVerification: (String) -> Unit,
): NativeFeedbackController = NativeFeedbackController(
    createAndroidNativeFeedbackService(helper),
    FeedbackLogReader {
        CrashLogUtil.readFeedbackLogs()
    },
    summary,
    scope,
    openVerification,
)
