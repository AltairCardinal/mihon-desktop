package eu.kanade.tachiyomi.network

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * Tests for [DesktopCookieJar] persistence to disk.
 *
 * A [DesktopCookieJar] constructed with a [storageFile] must:
 *  - save cookies to disk after each modification
 *  - restore all persisted cookies when a new jar is constructed with the same file
 *  - skip expired cookies on load
 *  - skip session-only cookies (no expiresAt) on load
 */
class DesktopCookieJarPersistenceTest {

    @Test
    fun `cookies survive restart — saved and restored from file`(@TempDir tmp: Path) {
        val file = tmp.resolve("cookies.json").toFile()
        val url = "https://example.com/page".toHttpUrl()

        // Write cookies in first jar instance
        val jar1 = DesktopCookieJar(storageFile = file)
        val cookie = Cookie.Builder()
            .name("session")
            .value("abc123")
            .domain("example.com")
            .path("/")
            .expiresAt(System.currentTimeMillis() + 3_600_000L) // 1 hour
            .build()
        jar1.saveFromResponse(url, listOf(cookie))

        // Second jar instance reads from same file
        val jar2 = DesktopCookieJar(storageFile = file)
        val loaded = jar2.loadForRequest(url)

        assertEquals(1, loaded.size)
        assertEquals("session", loaded[0].name)
        assertEquals("abc123", loaded[0].value)
    }

    @Test
    fun `multiple domains are persisted and restored`(@TempDir tmp: Path) {
        val file = tmp.resolve("cookies.json").toFile()

        val jar1 = DesktopCookieJar(storageFile = file)
        val url1 = "https://site-a.com/".toHttpUrl()
        val url2 = "https://site-b.com/".toHttpUrl()
        val expiry = System.currentTimeMillis() + 3_600_000L

        jar1.saveFromResponse(url1, listOf(
            Cookie.Builder().name("a").value("1").domain("site-a.com").path("/").expiresAt(expiry).build(),
        ))
        jar1.saveFromResponse(url2, listOf(
            Cookie.Builder().name("b").value("2").domain("site-b.com").path("/").expiresAt(expiry).build(),
        ))

        val jar2 = DesktopCookieJar(storageFile = file)
        assertEquals(1, jar2.loadForRequest(url1).size)
        assertEquals("1", jar2.loadForRequest(url1)[0].value)
        assertEquals(1, jar2.loadForRequest(url2).size)
        assertEquals("2", jar2.loadForRequest(url2)[0].value)
    }

    @Test
    fun `expired cookies are not restored on load`(@TempDir tmp: Path) {
        val file = tmp.resolve("cookies.json").toFile()
        val url = "https://example.com/".toHttpUrl()

        // Write an already-expired cookie directly to file then load
        val jar1 = DesktopCookieJar(storageFile = file)
        val expiredCookie = Cookie.Builder()
            .name("old")
            .value("expired")
            .domain("example.com")
            .path("/")
            .expiresAt(System.currentTimeMillis() - 1000L) // already expired
            .build()
        jar1.saveFromResponse(url, listOf(expiredCookie))

        // New jar should not restore expired cookies
        val jar2 = DesktopCookieJar(storageFile = file)
        assertTrue(jar2.loadForRequest(url).isEmpty(), "Expired cookies must not be loaded")
    }

    @Test
    fun `session-only cookies (no expiry) are not persisted`(@TempDir tmp: Path) {
        val file = tmp.resolve("cookies.json").toFile()
        val url = "https://example.com/".toHttpUrl()

        val jar1 = DesktopCookieJar(storageFile = file)
        // Session cookie: no expiresAt
        val sessionCookie = Cookie.Builder()
            .name("session")
            .value("temp")
            .domain("example.com")
            .path("/")
            .build() // no .expiresAt() → session-only
        jar1.saveFromResponse(url, listOf(sessionCookie))

        val jar2 = DesktopCookieJar(storageFile = file)
        assertTrue(jar2.loadForRequest(url).isEmpty(), "Session cookies must not be persisted")
    }

    @Test
    fun `addManual cookie is persisted`(@TempDir tmp: Path) {
        val file = tmp.resolve("cookies.json").toFile()
        val url = "https://example.com/".toHttpUrl()

        val jar1 = DesktopCookieJar(storageFile = file)
        jar1.addManual(url, "cf_clearance", "bypass_token_xyz")

        val jar2 = DesktopCookieJar(storageFile = file)
        val cookies = jar2.loadForRequest(url)
        assertEquals(1, cookies.size)
        assertEquals("cf_clearance", cookies[0].name)
        assertEquals("bypass_token_xyz", cookies[0].value)
    }

    @Test
    fun `clear removes persisted cookies from disk`(@TempDir tmp: Path) {
        val file = tmp.resolve("cookies.json").toFile()
        val url = "https://example.com/".toHttpUrl()
        val expiry = System.currentTimeMillis() + 3_600_000L

        val jar1 = DesktopCookieJar(storageFile = file)
        jar1.saveFromResponse(url, listOf(
            Cookie.Builder().name("k").value("v").domain("example.com").path("/").expiresAt(expiry).build(),
        ))
        jar1.clear()

        val jar2 = DesktopCookieJar(storageFile = file)
        assertTrue(jar2.loadForRequest(url).isEmpty(), "Cleared cookies must not be restored")
    }

    @Test
    fun `missing storage file is handled gracefully`(@TempDir tmp: Path) {
        val file = tmp.resolve("nonexistent_dir/cookies.json").toFile() // parent doesn't exist
        val jar = DesktopCookieJar(storageFile = file)
        val url = "https://example.com/".toHttpUrl()
        // Should not throw
        jar.saveFromResponse(url, listOf(
            Cookie.Builder().name("k").value("v").domain("example.com").path("/")
                .expiresAt(System.currentTimeMillis() + 3_600_000L).build(),
        ))
    }

    @Test
    fun `default constructor with null storageFile works without persistence`() {
        // The default jar (no storageFile) must still work in-memory
        val jar = DesktopCookieJar()
        val url = "https://example.com/".toHttpUrl()
        jar.saveFromResponse(url, listOf(
            Cookie.Builder().name("k").value("v").domain("example.com").build(),
        ))
        assertEquals(1, jar.loadForRequest(url).size)
    }
}
