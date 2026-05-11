package mihon.desktop.network

import eu.kanade.tachiyomi.network.DesktopCookieJar
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import java.io.IOException
import java.util.concurrent.TimeUnit

class DesktopCloudflareInterceptor(
    private val cookieJar: DesktopCookieJar,
    private val challengeManager: CloudflareChallengeManager,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        if (!shouldIntercept(response)) return response

        response.close()
        cookieJar.remove(request.url, COOKIE_NAMES)

        val challenge = CloudflareChallenge(url = request.url.toString())
        challengeManager.emit(challenge)

        challenge.latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)

        if (!challenge.resolved) {
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
        private val COOKIE_NAMES = listOf("cf_clearance")
        internal const val TIMEOUT_SECONDS = 120L
    }
}
