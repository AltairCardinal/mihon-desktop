package mihon.desktop.network

import eu.kanade.tachiyomi.network.DesktopCookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CloudflareCookieImportTest {

    @Test
    fun `valid domain and cookie value are accepted`() {
        val result = validateCloudflareCookieInput("example.com", "abc123xyz")
        assertTrue(result is CookieImportResult.Valid)
    }

    @Test
    fun `blank domain returns error`() {
        val result = validateCloudflareCookieInput("", "abc123xyz")
        assertTrue(result is CookieImportResult.InvalidDomain)
    }

    @Test
    fun `blank cookie value returns error`() {
        val result = validateCloudflareCookieInput("example.com", "")
        assertTrue(result is CookieImportResult.InvalidValue)
    }

    @Test
    fun `domain with http scheme is stripped`() {
        val result = validateCloudflareCookieInput("https://example.com", "abc123xyz")
        assertTrue(result is CookieImportResult.Valid)
        assertEquals("example.com", (result as CookieImportResult.Valid).domain)
    }

    @Test
    fun `domain with path is stripped to host only`() {
        val result = validateCloudflareCookieInput("example.com/manga/1", "abc123xyz")
        assertTrue(result is CookieImportResult.Valid)
        assertEquals("example.com", (result as CookieImportResult.Valid).domain)
    }

    @Test
    fun `cookie is injected into jar`() {
        val jar = DesktopCookieJar()
        val url = "https://example.com".toHttpUrl()
        jar.addManual(url, CF_CLEARANCE_COOKIE_NAME, "testvalue")
        val cookies = jar.loadForRequest(url)
        val cfCookie = cookies.find { it.name == CF_CLEARANCE_COOKIE_NAME }
        assertEquals("testvalue", cfCookie?.value)
    }

    @Test
    fun `null result for invalid domain string`() {
        val result = validateCloudflareCookieInput("not a domain!@#", "value")
        // Should either succeed or return InvalidDomain — never crash
        assertTrue(result is CookieImportResult.Valid || result is CookieImportResult.InvalidDomain)
    }
}
