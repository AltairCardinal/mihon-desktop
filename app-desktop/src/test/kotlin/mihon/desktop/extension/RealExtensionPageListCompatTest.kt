package mihon.desktop.extension

import eu.kanade.tachiyomi.source.model.Page
import kotlinx.coroutines.runBlocking
import mihon.desktop.di.initDesktopDIForTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.DesktopPreferenceStore
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

class RealExtensionPageListCompatTest {

    @Test
    fun `real ManHuaGui parser returns host Pages through fixed-main constructor ABI`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val apkPath = repositoryRoot().resolve(APK_PATH)
        assertTrue(Files.isRegularFile(apkPath), "Missing immutable extension fixture: $apkPath")
        assertEquals(APK_SHA256, sha256(apkPath))
        val diContext = initDesktopDIForTest(
            appDir = tempDir.resolve("app").toFile(),
            preferenceStore = DesktopPreferenceStore(),
        )
        try {
            val convertedJar = ApkToJarConverter().convert(apkPath.toFile(), tempDir.toFile())
            assertNotNull(convertedJar, "Production converter rejected the immutable ManHuaGui APK")
            val jar = requireNotNull(convertedJar)
            writeExtensionMeta(
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
    }

    private fun sha256(path: Path): String =
        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }

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
