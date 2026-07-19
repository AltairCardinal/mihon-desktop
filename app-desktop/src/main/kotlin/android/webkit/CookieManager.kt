package android.webkit

import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Desktop stub for android.webkit.CookieManager.
 *
 * In-memory cookie store keyed by domain. Extensions commonly call
 * `CookieManager.getInstance().getCookie(url)` to manage auth cookies.
 *
 * Unlike Suwayomi which bridges directly to OkHttp's CookieJar,
 * this implementation uses a simple in-memory store. The OkHttp
 * cookie jar bridge can be connected via [syncToOkHttp] if needed.
 */
abstract class CookieManager {

    abstract fun setAcceptCookie(accept: Boolean)
    abstract fun setAcceptThirdPartyCookies(webView: WebView, accept: Boolean)
    abstract fun acceptCookie(): Boolean
    abstract fun setCookie(url: String, value: String)
    abstract fun setCookie(url: String, value: String, callback: ValueCallback<Boolean>?)
    abstract fun getCookie(url: String): String?
    abstract fun removeAllCookie()
    abstract fun removeAllCookies(callback: ValueCallback<Boolean>?)
    abstract fun removeSessionCookie()
    abstract fun removeSessionCookies(callback: ValueCallback<Boolean>?)
    abstract fun hasCookies(): Boolean
    abstract fun removeExpiredCookie()
    abstract fun flush()

    companion object {
        @Volatile
        private var instance: CookieManager? = null

        @JvmStatic
        fun getInstance(): CookieManager {
            return instance ?: synchronized(this) {
                instance ?: DesktopCookieManager().also { instance = it }
            }
        }
    }
}

/**
 * Simple in-memory CookieManager implementation.
 * Cookies are stored per-domain as name=value pairs.
 */
internal class DesktopCookieManager : CookieManager() {
    private var acceptCookies = true
    // domain -> mutable map of cookie-name -> cookie-value-string
    private val cookieStore = ConcurrentHashMap<String, MutableMap<String, String>>()

    override fun setAcceptCookie(accept: Boolean) {
        acceptCookies = accept
    }

    override fun setAcceptThirdPartyCookies(webView: WebView, accept: Boolean) {
        throw UnsupportedOperationException("Desktop WebView engine unavailable")
    }

    override fun acceptCookie(): Boolean = acceptCookies

    override fun setCookie(url: String, value: String) {
        if (!acceptCookies) return
        val domain = extractDomain(url) ?: return
        val cookies = cookieStore.getOrPut(domain) { ConcurrentHashMap() }
        // Parse "name=value; attributes..." — only store name=value
        val nameValue = value.split(";").first().trim()
        val eqIndex = nameValue.indexOf('=')
        if (eqIndex > 0) {
            val name = nameValue.substring(0, eqIndex).trim()
            cookies[name] = nameValue
        } else {
            cookies[nameValue] = nameValue
        }
    }

    override fun setCookie(url: String, value: String, callback: ValueCallback<Boolean>?) {
        setCookie(url, value)
        callback?.onReceiveValue(true)
    }

    override fun getCookie(url: String): String? {
        val domain = extractDomain(url) ?: return null
        val cookies = cookieStore[domain] ?: return null
        if (cookies.isEmpty()) return null
        return cookies.values.joinToString("; ")
    }

    override fun removeAllCookie() {
        cookieStore.clear()
    }

    override fun removeAllCookies(callback: ValueCallback<Boolean>?) {
        val had = cookieStore.isNotEmpty()
        cookieStore.clear()
        callback?.onReceiveValue(had)
    }

    override fun removeSessionCookie() { /* no-op: no expiry tracking */ }

    override fun removeSessionCookies(callback: ValueCallback<Boolean>?) {
        callback?.onReceiveValue(false)
    }

    override fun hasCookies(): Boolean = cookieStore.values.any { it.isNotEmpty() }

    override fun removeExpiredCookie() { /* no-op: no expiry tracking */ }

    override fun flush() { /* no-op: in-memory only */ }

    private fun extractDomain(url: String): String? = try {
        URI(url).host
    } catch (_: Exception) {
        null
    }
}
