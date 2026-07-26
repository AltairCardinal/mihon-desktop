package mihon.desktop.network

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import tachiyomi.domain.source.service.AuthenticatedCookie
import tachiyomi.domain.source.service.AuthenticatedSession
import tachiyomi.domain.source.service.SourceLoginRequest

class DesktopSourceLoginEndpoint internal constructor(
    internal val url: HttpUrl,
) {
    val host: String = url.host
}

object DesktopSourceLoginAdapter {
    fun parseEndpoint(value: String): DesktopSourceLoginEndpoint? =
        value.toHttpUrlOrNull()?.let(::DesktopSourceLoginEndpoint)

    fun request(endpoint: DesktopSourceLoginEndpoint, timeoutMillis: Long): SourceLoginRequest =
        SourceLoginRequest(endpoint.url, timeoutMillis = timeoutMillis)

    fun parseCookieHeader(header: String, endpoint: DesktopSourceLoginEndpoint): AuthenticatedSession? {
        if (header.isBlank()) return null
        val names = hashSetOf<String>()
        val cookies = header.split(';').map { raw ->
            val pair = raw.trim()
            val separator = pair.indexOf('=')
            if (separator <= 0) return null
            val name = pair.substring(0, separator).trim()
            val value = pair.substring(separator + 1).trim()
            if (!COOKIE_NAME.matches(name) || value.isBlank() || !value.all(::isCookieOctet) || !names.add(name)) {
                return null
            }
            AuthenticatedCookie(name, value, endpoint.host, true, "/", null, endpoint.url.isHttps, false)
        }
        return AuthenticatedSession(cookies)
    }

    private fun isCookieOctet(char: Char): Boolean = char.code == 0x21 ||
        char.code in 0x23..0x2B ||
        char.code in 0x2D..0x3A ||
        char.code in 0x3C..0x5B ||
        char.code in 0x5D..0x7E

    private val COOKIE_NAME = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
}
