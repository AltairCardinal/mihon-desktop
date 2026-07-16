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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
    fun `load matches cookies from every bucket by domain path secure and expiry`() {
        val responseUrl = "https://auth.example.com/login".toHttpUrl()
        val httpsAccount = "https://reader.example.com/account/profile".toHttpUrl()
        val httpAccount = "http://reader.example.com/account/profile".toHttpUrl()
        val httpsOtherPath = "https://reader.example.com/public".toHttpUrl()
        jar.saveFromResponse(
            responseUrl,
            listOf(
                Cookie.Builder().name("parent").value("ok").domain("example.com").path("/").build(),
                Cookie.Builder().name("secure-path").value("secret").domain("example.com")
                    .path("/account").secure().build(),
                Cookie.Builder().name("expired").value("old").domain("example.com").path("/")
                    .expiresAt(System.currentTimeMillis() - 1_000).build(),
                Cookie.Builder().name("host-only").value("auth").hostOnlyDomain("auth.example.com").path("/").build(),
            ),
        )

        assertEquals(setOf("parent", "secure-path"), jar.loadForRequest(httpsAccount).map { it.name }.toSet())
        assertEquals(setOf("parent"), jar.loadForRequest(httpAccount).map { it.name }.toSet())
        assertEquals(setOf("parent"), jar.loadForRequest(httpsOtherPath).map { it.name }.toSet())
    }

    @Test
    fun `host-only survives persistence round-trip`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("cookies.json").toFile()
        val url = "https://reader.example.com/".toHttpUrl()
        DesktopCookieJar(file).saveFromResponse(
            url,
            listOf(
                Cookie.Builder().name("host-only").value("secret").hostOnlyDomain("reader.example.com")
                    .path("/").expiresAt(System.currentTimeMillis() + 3_600_000).build(),
            ),
        )

        val restored = DesktopCookieJar(file).loadForRequest(url).single()

        assertTrue(restored.hostOnly)
        assertTrue(DesktopCookieJar(file).loadForRequest("https://child.reader.example.com/".toHttpUrl()).isEmpty())
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
    fun `same-name cookies with different paths are preserved`() {
        val url = "https://example.com/account/settings".toHttpUrl()
        jar.saveFromResponse(
            url,
            listOf(
                Cookie.Builder().name("session").value("root").domain("example.com").path("/").build(),
                Cookie.Builder().name("session").value("account").domain("example.com").path("/account").build(),
            ),
        )

        assertEquals(
            listOf("/account" to "account", "/" to "root"),
            jar.loadForRequest(url).map { it.path to it.value },
        )
        assertEquals(
            listOf("root"),
            jar.loadForRequest("https://example.com/public".toHttpUrl()).map { it.value },
        )
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
    fun `authenticated session globally replaces an old cookie identity from another bucket`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("cookies.json").toFile()
        val authUrl = "https://auth.example.com/login".toHttpUrl()
        val readerUrl = "https://reader.example.com/".toHttpUrl()
        DesktopCookieJar(file).saveFromResponse(
            authUrl,
            listOf(persistentCookie("session", "old-secret", "example.com")),
        )
        val jar = DesktopCookieJar(file)

        jar.commitAuthenticatedSession(
            readerUrl,
            listOf(persistentCookie("session", "new-secret", "example.com")),
        )

        assertEquals(listOf("new-secret"), jar.get(readerUrl).map { it.value })
        assertEquals(listOf("new-secret"), DesktopCookieJar(file).get(readerUrl).map { it.value })
        assertTrue("old-secret" !in file.readText(), "superseded credentials must not remain persisted")
    }

    @Test
    fun `authenticated session removes every old cookie deliverable to the target host`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("cookies.json").toFile()
        val authUrl = "https://auth.example.com/login".toHttpUrl()
        val readerUrl = "https://reader.example.com/".toHttpUrl()
        val readerLegacyUrl = "https://reader.example.com/legacy/account".toHttpUrl()
        val unrelatedUrl = "https://unrelated.test/".toHttpUrl()
        DesktopCookieJar(file).apply {
            saveFromResponse(
                authUrl,
                listOf(
                    persistentCookie("legacy_session", "old-secret", "example.com", path = "/legacy"),
                    persistentHostOnlyCookie("auth-only", "keep-auth", authUrl.host),
                ),
            )
            saveFromResponse(
                unrelatedUrl,
                listOf(persistentCookie("unrelated", "keep-other", unrelatedUrl.host)),
            )
        }
        val jar = DesktopCookieJar(file)

        jar.commitAuthenticatedSession(
            readerUrl,
            listOf(persistentCookie("session", "new-secret", readerUrl.host)),
        )

        assertEquals(listOf("session" to "new-secret"), jar.get(readerLegacyUrl).map { it.name to it.value })
        assertEquals(listOf("auth-only" to "keep-auth"), jar.get(authUrl).map { it.name to it.value })
        assertEquals(listOf("unrelated" to "keep-other"), jar.get(unrelatedUrl).map { it.name to it.value })

        val restored = DesktopCookieJar(file)
        assertEquals(listOf("session" to "new-secret"), restored.get(readerLegacyUrl).map { it.name to it.value })
        assertEquals(listOf("auth-only" to "keep-auth"), restored.get(authUrl).map { it.name to it.value })
        assertEquals(listOf("unrelated" to "keep-other"), restored.get(unrelatedUrl).map { it.name to it.value })
        assertTrue("old-secret" !in file.readText(), "stale target-domain credentials must not remain persisted")
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

    @Test
    fun `late persistence failure restores exact old memory and file bytes`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("cookies.json").toFile()
        val url = "https://reader.example.com/".toHttpUrl()
        DesktopCookieJar(file).saveFromResponse(url, listOf(persistentCookie("old", "old-value", url.host)))
        val oldFile = file.readBytes()
        val jar = DesktopCookieJar(file) { source, target ->
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            throw IOException("replace reported failure after moving target")
        }

        assertThrows(IOException::class.java) {
            jar.commitAuthenticatedSession(
                url,
                listOf(persistentCookie("session", "new-secret", url.host)),
            )
        }

        assertEquals(listOf("old-value"), jar.get(url).map { it.value })
        assertTrue(oldFile.contentEquals(file.readBytes()))
        assertTrue(tempDir.toFile().listFiles().orEmpty().none { it.name.endsWith(".tmp") })
    }

    @Test
    fun `late persistence failure restores an absent target as absent`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("cookies.json").toFile()
        val url = "https://reader.example.com/".toHttpUrl()
        val jar = DesktopCookieJar(file) { source, target ->
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            throw IOException("replace reported failure after creating target")
        }

        assertThrows(IOException::class.java) {
            jar.commitAuthenticatedSession(
                url,
                listOf(persistentCookie("session", "new-secret", url.host)),
            )
        }

        assertTrue(jar.get(url).isEmpty())
        assertTrue(!file.exists())
        assertTrue(tempDir.toFile().listFiles().orEmpty().none { it.name.endsWith(".tmp") })
    }

    @Test
    fun `reader and writer cannot enter while authenticated persistence is in flight`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("cookies.json").toFile()
        val url = "https://reader.example.com/".toHttpUrl()
        DesktopCookieJar(file).saveFromResponse(url, listOf(persistentCookie("old", "old-value", url.host)))
        val persistenceEntered = CountDownLatch(1)
        val releasePersistence = CountDownLatch(1)
        val jar = DesktopCookieJar(file) { source, target ->
            persistenceEntered.countDown()
            check(releasePersistence.await(5, TimeUnit.SECONDS)) { "persistence barrier was not released" }
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
        val executor = Executors.newFixedThreadPool(3)
        val readerStarted = CountDownLatch(1)
        val readerFinished = CountDownLatch(1)
        val writerStarted = CountDownLatch(1)
        val writerFinished = CountDownLatch(1)
        try {
            val commit = executor.submit {
                jar.commitAuthenticatedSession(url, listOf(persistentCookie("new", "new-value", url.host)))
            }
            assertTrue(persistenceEntered.await(5, TimeUnit.SECONDS))
            val read = executor.submit<List<Cookie>> {
                readerStarted.countDown()
                try {
                    jar.get(url)
                } finally {
                    readerFinished.countDown()
                }
            }
            val write = executor.submit {
                writerStarted.countDown()
                try {
                    jar.saveFromResponse(url, listOf(persistentCookie("later", "later-value", url.host)))
                } finally {
                    writerFinished.countDown()
                }
            }
            assertTrue(readerStarted.await(5, TimeUnit.SECONDS))
            assertTrue(writerStarted.await(5, TimeUnit.SECONDS))

            assertTrue(
                !readerFinished.await(200, TimeUnit.MILLISECONDS),
                "reader observed an unpersisted half-transaction",
            )
            assertTrue(
                !writerFinished.await(200, TimeUnit.MILLISECONDS),
                "writer entered during the atomic transaction",
            )

            releasePersistence.countDown()
            commit.get(5, TimeUnit.SECONDS)
            assertTrue(read.get(5, TimeUnit.SECONDS).map { it.name }.toSet().contains("new"))
            write.get(5, TimeUnit.SECONDS)
            assertEquals(setOf("later", "new"), DesktopCookieJar(file).get(url).map { it.name }.toSet())
        } finally {
            releasePersistence.countDown()
            executor.shutdownNow()
        }
    }

    private fun persistentCookie(name: String, value: String, domain: String, path: String = "/") = Cookie.Builder()
        .name(name)
        .value(value)
        .domain(domain)
        .path(path)
        .expiresAt(System.currentTimeMillis() + 3_600_000)
        .build()

    private fun persistentHostOnlyCookie(name: String, value: String, domain: String) = Cookie.Builder()
        .name(name)
        .value(value)
        .hostOnlyDomain(domain)
        .path("/")
        .expiresAt(System.currentTimeMillis() + 3_600_000)
        .build()
}
