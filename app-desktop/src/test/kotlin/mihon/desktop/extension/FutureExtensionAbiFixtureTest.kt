package mihon.desktop.extension

import eu.kanade.tachiyomi.source.model.FilterList
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.security.MessageDigest
import java.util.jar.JarFile

class FutureExtensionAbiFixtureTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `future binary remains incompatible at filter API parser and network sites`() = runBlocking<Unit> {
        val resource = requireNotNull(javaClass.getResource("/extensions/real/ec-coroutines-future.jar"))
        val jar = tempDir.resolve("future.jar").apply {
            resource.openStream().use { input -> outputStream().use(input::copyTo) }
        }
        assertEquals(
            "37243fcb5e8dc11f05d3aa56cac1af9bb1f3621ac45d6a17fc73882858082bcc",
            MessageDigest.getInstance("SHA-256").digest(jar.readBytes()).joinToString("") { "%02x".format(it) },
        )
        JarFile(jar).use { archive ->
            assertTrue(archive.getJarEntry("org/jsoup/Jsoup.class") == null)
            assertTrue(archive.getJarEntry("ec/absent/FutureApi.class") == null)
            assertTrue(archive.entries().asSequence().none { it.name.startsWith("kotlinx/") })
        }
        val loader = DesktopExtensionLoader(tempDir)
        val loaded = loader.loadFromSingleJar(jar)
        try {
            assertEquals(1, loaded.size, loader.diagnostics.toString())
            val source = loaded.single().source
            val missingMethod = assertThrows(NoSuchMethodError::class.java) { source.getFilterList() }
            assertTrue(missingMethod.message.orEmpty().contains("org.jsoup.Jsoup.ecFutureCompatibility"))
            assertThrows(NoClassDefFoundError::class.java) {
                runBlocking { source.getSearchManga(1, "fixture", FilterList()) }
            }
            MockWebServer().also { it.start() }.use { server ->
                server.enqueue(MockResponse(body = "parser fixture"))
                OkHttpClient().newCall(Request.Builder().url(server.url("/parse")).build()).execute().use { response ->
                    val parserFailure = assertThrows(InvocationTargetException::class.java) {
                        source.javaClass.getMethod("parse", Response::class.java).invoke(source, response)
                    }
                    assertTrue(parserFailure.targetException is NoSuchMethodError)
                }
                val interceptor = loaded.single().classLoader.loadClass("ec.future.FutureInterceptor")
                    .getDeclaredConstructor().newInstance() as Interceptor
                val client = OkHttpClient.Builder().addNetworkInterceptor(interceptor).build()
                server.enqueue(MockResponse(body = "network fixture"))
                assertThrows(NoSuchMethodError::class.java) {
                    client.newCall(Request.Builder().url(server.url("/network")).build()).execute().close()
                }
            }
        } finally {
            loaded.map { it.classLoader }.distinct().filterIsInstance<AutoCloseable>().forEach { it.close() }
        }
    }
}
