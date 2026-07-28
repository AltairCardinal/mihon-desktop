package android.webkit

import java.net.URI

/**
 * Small in-memory Android CookieManager adapter used by legacy extensions.
 *
 * Production HTTP persistence remains owned by DesktopCookieJar; this adapter
 * only preserves the process-local API semantics expected by extension-owned
 * OkHttp CookieJar implementations.
 */
class CookieManager private constructor() {
    private val cookiesByHost = linkedMapOf<String, LinkedHashMap<String, String>>()

    @Synchronized
    fun setCookie(url: String, value: String) {
        val host = normalizedHost(url)
        val name = value.substringBefore('=').trim()
        if (host.isEmpty() || name.isEmpty()) return
        cookiesByHost.getOrPut(host, ::linkedMapOf)[name] = value.substringBefore(';').trim()
    }

    @Synchronized
    fun getCookie(url: String): String? =
        cookiesByHost[normalizedHost(url)]
            ?.values
            ?.takeIf(Collection<String>::isNotEmpty)
            ?.joinToString("; ")

    @Synchronized
    fun removeAllCookie() {
        cookiesByHost.clear()
    }

    private fun normalizedHost(url: String): String = runCatching {
        URI(url.takeIf { "://" in it } ?: "https://$url").host.orEmpty().lowercase()
    }.getOrDefault("")

    companion object {
        private val INSTANCE = CookieManager()

        @JvmStatic
        fun getInstance(): CookieManager = INSTANCE
    }
}
