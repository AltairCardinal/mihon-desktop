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
import tachiyomi.data.feedback.NativeFeedbackHttpService
import tachiyomi.domain.feedback.NativeFeedbackController

internal fun DesktopNetworkHelper.nativeFeedbackClient(): OkHttpClient = client.newBuilder().apply {
    interceptors().removeAll { it is DesktopCloudflareInterceptor }
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
): NativeFeedbackController = NativeFeedbackController(
    createDesktopNativeFeedbackService(helper),
    DesktopFeedbackLogReader(paths.logsDir),
    summary,
    scope,
    openVerification,
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
)
