package eu.kanade.tachiyomi.network

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DesktopCookieJarTest {

    private lateinit var jar: DesktopCookieJar

    @BeforeEach
    fun setUp() {
        jar = DesktopCookieJar()
    }

    @Test
    fun `loadForRequest returns empty for unknown url`() {
        val url = "https://example.com/path".toHttpUrl()
        assertTrue(jar.loadForRequest(url).isEmpty())
    }

    @Test
    fun `saveFromResponse and loadForRequest round-trip`() {
        val url = "https://example.com/path".toHttpUrl()
        val cookie = Cookie.Builder()
            .name("session")
            .value("abc123")
            .domain("example.com")
            .build()
        jar.saveFromResponse(url, listOf(cookie))

        val loaded = jar.loadForRequest(url)
        assertEquals(1, loaded.size)
        assertEquals("session", loaded[0].name)
        assertEquals("abc123", loaded[0].value)
    }

    @Test
    fun `cookies are scoped to domain`() {
        val url1 = "https://example.com/".toHttpUrl()
        val url2 = "https://other.com/".toHttpUrl()
        val cookie = Cookie.Builder()
            .name("token")
            .value("xyz")
            .domain("example.com")
            .build()
        jar.saveFromResponse(url1, listOf(cookie))

        assertTrue(jar.loadForRequest(url2).isEmpty())
        assertEquals(1, jar.loadForRequest(url1).size)
    }

    @Test
    fun `same-name cookie is overwritten`() {
        val url = "https://example.com/".toHttpUrl()
        val cookie1 = Cookie.Builder().name("k").value("v1").domain("example.com").build()
        val cookie2 = Cookie.Builder().name("k").value("v2").domain("example.com").build()
        jar.saveFromResponse(url, listOf(cookie1))
        jar.saveFromResponse(url, listOf(cookie2))

        val loaded = jar.loadForRequest(url)
        assertEquals(1, loaded.size)
        assertEquals("v2", loaded[0].value)
    }

    @Test
    fun `clear removes all cookies`() {
        val url = "https://example.com/".toHttpUrl()
        val cookie = Cookie.Builder().name("k").value("v").domain("example.com").build()
        jar.saveFromResponse(url, listOf(cookie))
        jar.clear()

        assertTrue(jar.loadForRequest(url).isEmpty())
    }
}
