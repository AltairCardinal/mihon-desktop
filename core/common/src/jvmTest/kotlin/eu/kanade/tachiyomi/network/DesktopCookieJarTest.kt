package eu.kanade.tachiyomi.network

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class DesktopCookieJarTest {

    @Test
    fun `clearDomains removes matching host and subdomains only`() {
        val jar = DesktopCookieJar()
        val root = "https://example.com".toHttpUrl()
        val subdomain = "https://reader.example.com".toHttpUrl()
        val other = "https://other.test".toHttpUrl()
        jar.addManual(root, "root", "1")
        jar.addManual(subdomain, "sub", "2")
        jar.addManual(other, "other", "3")

        val removed = jar.clearDomains(setOf("example.com"))

        assertEquals(2, removed)
        assertTrue(jar.get(root).isEmpty())
        assertTrue(jar.get(subdomain).isEmpty())
        assertTrue(jar.get(other).isNotEmpty())
    }

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

    // --- Cloudflare bypass support ---

    @Test
    fun `get returns cookies for url`() {
        val url = "https://example.com/path".toHttpUrl()
        val cookie = Cookie.Builder().name("cf_clearance").value("abc").domain("example.com").build()
        jar.saveFromResponse(url, listOf(cookie))

        val result = jar.get(url)
        assertEquals(1, result.size)
        assertEquals("cf_clearance", result[0].name)
    }

    @Test
    fun `remove deletes specified cookies by name`() {
        val url = "https://example.com/".toHttpUrl()
        val cf = Cookie.Builder().name("cf_clearance").value("old").domain("example.com").build()
        val session = Cookie.Builder().name("session").value("keep").domain("example.com").build()
        jar.saveFromResponse(url, listOf(cf, session))

        jar.remove(url, listOf("cf_clearance"))

        val remaining = jar.loadForRequest(url)
        assertEquals(1, remaining.size)
        assertEquals("session", remaining[0].name)
    }

    @Test
    fun `remove with empty list does nothing`() {
        val url = "https://example.com/".toHttpUrl()
        val cookie = Cookie.Builder().name("k").value("v").domain("example.com").build()
        jar.saveFromResponse(url, listOf(cookie))

        jar.remove(url, emptyList())

        assertEquals(1, jar.loadForRequest(url).size)
    }

    @Test
    fun `addManual inserts cookie for domain`() {
        val url = "https://example.com/".toHttpUrl()

        jar.addManual(url, "cf_clearance", "new_value_123")

        val cookies = jar.loadForRequest(url)
        assertEquals(1, cookies.size)
        assertEquals("cf_clearance", cookies[0].name)
        assertEquals("new_value_123", cookies[0].value)
    }

    @Test
    fun `addManual overwrites existing cookie with same name`() {
        val url = "https://example.com/".toHttpUrl()
        val old = Cookie.Builder().name("cf_clearance").value("old").domain("example.com").build()
        jar.saveFromResponse(url, listOf(old))

        jar.addManual(url, "cf_clearance", "new")

        val cookies = jar.get(url)
        assertEquals(1, cookies.size)
        assertEquals("new", cookies[0].value)
    }

    @Test
    fun `authenticated session replaces the complete host set with one atomic persistence`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("cookies.json").toFile()
        val url = "https://reader.example.com/".toHttpUrl()
        DesktopCookieJar(file).saveFromResponse(url, listOf(persistentCookie("old", "old-value", "reader.example.com")))
        var replacements = 0
        val jar = DesktopCookieJar(file) { source, target ->
            replacements += 1
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }

        jar.commitAuthenticatedSession(
            url,
            listOf(
                persistentCookie("session", "new-value", "reader.example.com"),
                persistentCookie("clearance", "clear-value", "example.com"),
            ),
        )

        assertEquals(1, replacements)
        assertEquals(setOf("clearance", "session"), jar.get(url).map { it.name }.toSet())
        assertEquals(setOf("clearance", "session"), DesktopCookieJar(file).get(url).map { it.name }.toSet())
    }

    @Test
    fun `failed authenticated session persistence preserves old memory and old file`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("cookies.json").toFile()
        val url = "https://reader.example.com/".toHttpUrl()
        DesktopCookieJar(file).saveFromResponse(url, listOf(persistentCookie("old", "old-value", "reader.example.com")))
        val oldFile = file.readText()
        val jar = DesktopCookieJar(file) { _, _ -> throw IOException("replace failed") }

        assertThrows(IOException::class.java) {
            jar.commitAuthenticatedSession(
                url,
                listOf(persistentCookie("session", "new-secret", "reader.example.com")),
            )
        }

        assertEquals(listOf("old"), jar.get(url).map { it.name })
        assertEquals(oldFile, file.readText())
        assertTrue(tempDir.toFile().listFiles().orEmpty().none { it.name.endsWith(".tmp") })
    }

    private fun persistentCookie(name: String, value: String, domain: String) = Cookie.Builder()
        .name(name)
        .value(value)
        .domain(domain)
        .path("/")
        .expiresAt(System.currentTimeMillis() + 3_600_000)
        .build()
}
