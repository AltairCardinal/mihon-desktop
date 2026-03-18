package eu.kanade.tachiyomi.network

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.util.concurrent.ConcurrentHashMap

class DesktopCookieJar : CookieJar {

    private val cookieStore = ConcurrentHashMap<String, MutableMap<String, Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val domain = url.host
        val domainCookies = cookieStore.getOrPut(domain) { mutableMapOf() }
        for (cookie in cookies) {
            domainCookies[cookie.name] = cookie
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val domain = url.host
        return cookieStore[domain]?.values?.toList() ?: emptyList()
    }

    fun clear() {
        cookieStore.clear()
    }
}
