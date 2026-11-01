package mihon.desktop.feedback

import kotlinx.coroutines.CoroutineScope
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.network.DesktopCloudflareCredentialInterceptor
import mihon.desktop.network.DesktopCloudflareInterceptor
import mihon.desktop.platform.DesktopNetworkHelper
import mihon.desktop.platform.DesktopPlatformPaths
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import tachiyomi.core.common.feedback.DiagnosticNetworkInterceptor
import tachiyomi.data.feedback.NativeFeedbackHttpService
import tachiyomi.domain.feedback.NativeFeedbackController

internal fun DesktopNetworkHelper.nativeFeedbackClient(): OkHttpClient = client.newBuilder().apply {
    interceptors().removeAll { it is DesktopCloudflareInterceptor || it is DiagnosticNetworkInterceptor }
    networkInterceptors().removeAll { it is DesktopCloudflareCredentialInterceptor }
}.build()

internal fun createDesktopNativeFeedbackService(
    helper: DesktopNetworkHelper,
    baseUrl: HttpUrl = NativeFeedbackHttpService.ORIGIN.toHttpUrl(),
): NativeFeedbackHttpService = NativeFeedbackHttpService(helper.nativeFeedbackClient(), baseUrl)

internal fun createDesktopNativeFeedbackController(
    helper: DesktopNetworkHelper,
    paths: DesktopPlatformPaths,
    scope: CoroutineScope,
    summary: String,
    openVerification: (String) -> Unit,
    runtime: DesktopFeedbackRuntime,
): NativeFeedbackController = NativeFeedbackController(
    createDesktopNativeFeedbackService(helper),
    desktopFeedbackCrashReader(paths),
    summary,
    scope,
    openVerification,
    language = if (java.util.Locale.getDefault().language == "zh") {
        tachiyomi.domain.feedback.FeedbackLanguage.CHINESE
    } else {
        tachiyomi.domain.feedback.FeedbackLanguage.ENGLISH
    },
    screenshotProcessor = DesktopFeedbackScreenshotProcessor(),
    diagnostics = tachiyomi.domain.feedback.FeedbackDiagnosticController(
        runtime.store,
        desktopFeedbackCrashReader(paths),
        scope,
    ),
    initialSnapshot = runtime.memory.snapshot,
)

/**
 * Keeps the concrete network helper out of the Desktop UI layer. The UI asks the dependency
 * container for a feedback controller and the feedback boundary owns the HTTP client, so no
 * `mihon.desktop.ui` class depends on `DesktopNetworkHelper`.
 */
internal fun DesktopUiDependencies.createNativeFeedbackController(
    paths: DesktopPlatformPaths,
    scope: CoroutineScope,
    summary: String,
    openVerification: (String) -> Unit,
): NativeFeedbackController = createDesktopNativeFeedbackController(
    networkHelper,
    paths,
    scope,
    summary,
    openVerification,
    requireNotNull(feedbackRuntime),
)
