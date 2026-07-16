package mihon.desktop.network

import eu.kanade.tachiyomi.network.DesktopCookieJar
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import tachiyomi.domain.source.service.SourceLoginRequest
import java.io.IOException
import java.util.concurrent.TimeUnit

class DesktopCloudflareInterceptor(
    @Suppress("UNUSED_PARAMETER") cookieJar: DesktopCookieJar,
    private val challengeManager: CloudflareChallengeManager,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        if (!shouldIntercept(response)) return response

        response.close()
        val challenge = challengeManager.publish(
            SourceLoginRequest(
                url = request.url,
                requiredCookieNames = setOf(CF_CLEARANCE_COOKIE_NAME),
                timeoutMillis = TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS),
            ),
        )
        if (challenge.awaitTerminal() != ChallengeRecoveryTerminal.Recovered) {
            throw IOException("Cloudflare bypass failed or timed out")
        }

        return chain.proceed(request)
    }

    internal fun shouldIntercept(response: Response): Boolean {
        if (response.code !in ERROR_CODES) return false
        if (response.header("Server") !in SERVER_CHECK) return false

        val body = response.peekBody(Long.MAX_VALUE).string()
        val document = Jsoup.parse(body, response.request.url.toString())

        return document.getElementById("challenge-error-title") != null ||
            document.getElementById("challenge-error-text") != null
    }

    companion object {
        private val ERROR_CODES = listOf(403, 503)
        private val SERVER_CHECK = arrayOf("cloudflare-nginx", "cloudflare")
        internal const val TIMEOUT_SECONDS = 120L
    }
}
