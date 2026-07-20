package mihon.desktop.extension

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.runBlocking
import mihon.desktop.di.initDesktopDIForTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import uy.kohesive.injekt.Injekt
import java.lang.reflect.InvocationTargetException
import java.net.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

@Isolated
class RealExtensionPageListCompatTest {

    @Test
    fun `real ManHuaGui source client executes its rate limit interceptor`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val preferences = IsolatedDesktopPreferenceStore.create()
        val previousInjekt = Injekt
        try {
            val apkPath = repositoryRoot().resolve(APK_PATH)
            assertTrue(Files.isRegularFile(apkPath), "Missing immutable extension fixture: $apkPath")
            assertEquals(APK_SHA256, sha256(apkPath))
            MockWebServer().also { it.start() }.use { server ->
                server.enqueue(MockResponse(body = "ok"))
                val diContext = initDesktopDIForTest(
                    appDir = tempDir.resolve("app").toFile(),
                    preferenceStore = preferences.store,
                )
                try {
                    val convertedJar = ApkToJarConverter().convert(apkPath.toFile(), tempDir.toFile())
                    assertNotNull(convertedJar, "Production converter rejected the immutable ManHuaGui APK")
                    val jar = requireNotNull(convertedJar)
                    writeManHuaGuiMeta(jar)
                    val loaded = DesktopExtensionLoader(tempDir.toFile()).loadFromSingleJar(jar)
                    assertTrue(loaded.isNotEmpty(), "Production loader rejected the converted ManHuaGui extension")
                    try {
                        val source = loaded.first().source as HttpSource
                        val targetHost = source.baseUrl.toHttpUrl().host
                        val sourceClient = source.client
                        val productionDns = sourceClient.dns
                        val extensionNetworkInterceptors = sourceClient.networkInterceptors
                        val rewrittenClient = sourceClient.newBuilder()
                            .dns { hostname ->
                                if (hostname == targetHost) {
                                    Dns.SYSTEM.lookup(server.hostName)
                                } else {
                                    productionDns.lookup(hostname)
                                }
                            }
                            .proxy(Proxy.NO_PROXY)
                            .build()
                        assertTrue(rewrittenClient.networkInterceptors.containsAll(extensionNetworkInterceptors))
                        assertSame(Proxy.NO_PROXY, rewrittenClient.proxy)

                        val request = Request.Builder()
                            .url("http://$targetHost:${server.port}/codex-rate-limit")
                            .build()
                        rewrittenClient.newCall(request).execute().use { response ->
                            assertEquals(200, response.code)
                            assertEquals("ok", response.body.string())
                        }
                        assertEquals(1, server.requestCount)
                        assertEquals("/codex-rate-limit", server.takeRequest().url.encodedPath)
                    } finally {
                        loaded.map { it.classLoader }.distinct().filterIsInstance<AutoCloseable>().forEach { it.close() }
                    }
                } finally {
                    diContext.closeAndJoin()
                }
            }
        } finally {
            Injekt = previousInjekt
            preferences.close()
        }
    }

    @Test
    fun `real ManHuaGui parser returns host Pages through fixed-main constructor ABI`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val preferences = IsolatedDesktopPreferenceStore.create()
        val previousInjekt = Injekt
        try {
            val apkPath = repositoryRoot().resolve(APK_PATH)
            assertTrue(Files.isRegularFile(apkPath), "Missing immutable extension fixture: $apkPath")
            assertEquals(APK_SHA256, sha256(apkPath))
            val diContext = initDesktopDIForTest(
                appDir = tempDir.resolve("app").toFile(),
                preferenceStore = preferences.store,
            )
            try {
                val convertedJar = ApkToJarConverter().convert(apkPath.toFile(), tempDir.toFile())
                assertNotNull(convertedJar, "Production converter rejected the immutable ManHuaGui APK")
                val jar = requireNotNull(convertedJar)
                writeManHuaGuiMeta(jar)
                val loaded = DesktopExtensionLoader(tempDir.toFile()).loadFromSingleJar(jar)
                assertTrue(loaded.isNotEmpty(), "Production loader rejected the converted ManHuaGui extension")
                try {
                    val source = loaded.first().source
                    val parser = source.javaClass.superclass.getDeclaredMethod("pageListParse", Response::class.java)
                        .also { it.isAccessible = true }
                    val html = Files.readString(repositoryRoot().resolve(HTML_PATH))
                    MockWebServer().also { it.start() }.use { server ->
                        server.enqueue(MockResponse(body = html))
                        val request = Request.Builder().url(server.url("/comic/123/chapter.html")).build()
                        OkHttpClient().newCall(request).execute().use { response ->
                            @Suppress("UNCHECKED_CAST")
                            val pages = try {
                                parser.invoke(source, response) as List<Page>
                            } catch (error: InvocationTargetException) {
                                throw error.targetException
                            }
                            val page = pages.single()
                            assertSame(Page::class.java, page.javaClass)
                            assertEquals(0, page.index)
                            assertEquals(EXPECTED_IMAGE_URL, page.imageUrl)
                        }
                    }
                } finally {
                    loaded.map { it.classLoader }.distinct().filterIsInstance<AutoCloseable>().forEach { it.close() }
                }
            } finally {
                diContext.closeAndJoin()
            }
        } finally {
            Injekt = previousInjekt
            preferences.close()
        }
    }

    private fun sha256(path: Path): String =
        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }

    private fun writeManHuaGuiMeta(jar: java.io.File) = writeExtensionMeta(
        jar,
        ExtensionMeta(
            pkgName = PACKAGE_NAME,
            versionCode = VERSION_CODE,
            versionName = VERSION_NAME,
            artifactSha256 = APK_SHA256,
            source = ExtensionOrigin.CONVERTED_APK,
            name = "ManHuaGui",
            language = "zh",
            extensionClass = EXTENSION_CLASS,
        ),
    )

    private fun repositoryRoot() =
        generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isDirectory(it.resolve("app-desktop")) && Files.isDirectory(it.resolve("docs")) }

    private companion object {
        const val APK_PATH =
            "app-desktop/src/test/resources/extensions/real/keiyoushi-manhuagui-1.4.28.apk"
        const val HTML_PATH = "app-desktop/src/test/resources/extensions/real/manhuagui-packed-page-list.html"
        const val APK_SHA256 = "200cfc4b3b9e98f387824e3cecb13f97f4b0971f8fb678ce49c60aab6856c0c8"
        const val PACKAGE_NAME = "eu.kanade.tachiyomi.extension.zh.manhuagui"
        const val VERSION_CODE = 28L
        const val VERSION_NAME = "1.4.28"
        const val EXTENSION_CLASS = "eu.kanade.tachiyomi.extension.zh.manhuagui.ExtensionGenerated"
        const val EXPECTED_IMAGE_URL = "https://i.hamreus.com/comic/123/001.jpg?e=1700000000&m=sig"
    }
}
