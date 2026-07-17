package mihon.desktop.network

import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import tachiyomi.domain.source.service.SourceLoginRequest
import java.io.IOException
import java.util.concurrent.TimeUnit

class DesktopCloudflareInterceptor(
    private val challengeManager: CloudflareChallengeManager,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val explicitCookieHeaders = originalRequest.headers("Cookie")
        val requestWithCookieProvenance = if (explicitCookieHeaders.isEmpty()) {
            originalRequest
        } else {
            originalRequest.newBuilder()
                .tag(ExplicitCookieProvenance::class.java, ExplicitCookieProvenance(explicitCookieHeaders))
                .build()
        }
        val response = chain.proceed(requestWithCookieProvenance)

        if (!shouldIntercept(response)) return response

        response.close()
        val challenge = challengeManager.publish(
            SourceLoginRequest(
                url = originalRequest.url,
                requiredCookieNames = setOf(CF_CLEARANCE_COOKIE_NAME),
                timeoutMillis = TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS),
            ),
        )
        if (challenge.awaitTerminal() != ChallengeRecoveryTerminal.Recovered) {
            throw IOException("Cloudflare bypass failed or timed out")
        }

        return chain.proceed(requestWithCookieProvenance)
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

class DesktopCloudflareCredentialInterceptor(
    private val challengeManager: CloudflareChallengeManager,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        originalRequest.tag(ExplicitCookieProvenance::class.java)?.let { provenance ->
            val explicitRequest = originalRequest.newBuilder()
                .removeHeader("Cookie")
                .apply {
                    provenance.headers.forEach { addHeader("Cookie", it) }
                }
                .build()
            return chain.proceed(explicitRequest)
        }
        val solverUserAgent = challengeManager.solverUserAgentForOutboundRequest(
            originalRequest.url,
            originalRequest.headers("Cookie"),
        ) ?: return chain.proceed(originalRequest)
        return chain.proceed(
            originalRequest.newBuilder()
                .header("User-Agent", solverUserAgent)
                .build(),
        )
    }
}

private class ExplicitCookieProvenance(
    val headers: List<String>,
)
