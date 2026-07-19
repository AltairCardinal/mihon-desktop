package mihon.desktop.extension

import kotlinx.coroutines.runBlocking
import mihon.desktop.di.initDesktopDIForTest
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import tachiyomi.core.common.preference.DesktopPreferenceStore
import uy.kohesive.injekt.Injekt
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

@Isolated
class RealExtensionWebViewUnsupportedCompatTest {

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `real Comix WebView path fails fast with the explicit desktop boundary`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val previousInjekt = Injekt
        try {
            val apkPath = repositoryRoot().resolve(APK_PATH)
            assertTrue(Files.isRegularFile(apkPath), "Missing immutable Comix fixture: $apkPath")
            assertEquals(APK_SHA256, sha256(apkPath))
            assertEquals(EXTENSION_CLASS, ManifestClassExtractor.extractFromApk(apkPath.toFile()))

            val diContext = initDesktopDIForTest(
                appDir = tempDir.resolve("app").toFile(),
                preferenceStore = DesktopPreferenceStore(),
            )
            try {
                val jar = requireNotNull(ApkToJarConverter().convert(apkPath.toFile(), tempDir.toFile()))
                writeExtensionMeta(
                    jar,
                    ExtensionMeta(
                        pkgName = PACKAGE_NAME,
                        versionCode = VERSION_CODE,
                        versionName = VERSION_NAME,
                        artifactSha256 = APK_SHA256,
                        source = ExtensionOrigin.CONVERTED_APK,
                        name = "Comix",
                        language = "en",
                        extensionClass = EXTENSION_CLASS,
                    ),
                )

                val loader = DesktopExtensionLoader(tempDir.toFile())
                val loaded = loader.loadFromSingleJar(jar)
                try {
                    assertEquals(1, loaded.size, "Comix did not load through production Desktop DI")
                    assertTrue(loader.diagnostics.isEmpty(), "Comix loader diagnostics: ${loader.diagnostics}")
                    val source = loaded.single().source
                    val superclass = source.javaClass.superclass
                    assertEquals("p0", superclass.name)
                    val runInWebView = superclass.getDeclaredMethod(
                        "P",
                        Document::class.java,
                        String::class.java,
                        kotlin.Function1::class.java,
                    ).apply { isAccessible = true }
                    assertEquals(String::class.java, runInWebView.returnType)

                    val document = Jsoup.parse("<html><head></head><body></body></html>", BASE_URI)
                    assertEquals(BASE_URI, document.baseUri())
                    assertEquals(BASE_URI, document.location())
                    var observedBridge: String? = null
                    val scriptFactory: (String) -> String = { bridge ->
                        observedBridge = bridge
                        "window.$bridge={};"
                    }

                    val startedAt = System.nanoTime()
                    val invocation = assertThrows(InvocationTargetException::class.java) {
                        runInWebView.invoke(source, document, null, scriptFactory)
                    }
                    val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
                    assertTrue(elapsedMillis < 9_000, "Desktop WebView boundary did not fail fast: ${elapsedMillis}ms")
                    assertTrue(observedBridge?.matches(ASCII_BRIDGE) == true, "Invalid bridge name: $observedBridge")

                    val target = invocation.targetException
                    assertEquals(Exception::class.java, target.javaClass)
                    assertEquals("Failed to start WebView (url=$BASE_URI)", target.message)
                    assertEquals(listOf("p0.P"), target.stackTrace.take(1).map { "${it.className}.${it.methodName}" })
                    val cause = requireNotNull(target.cause)
                    assertEquals(UnsupportedOperationException::class.java, cause.javaClass)
                    assertEquals("Desktop WebView engine unavailable", cause.message)
                    assertEquals(
                        listOf(
                            "android.webkit.WebViewCompatKt.unavailable",
                            "android.webkit.WebViewCompatKt.access\$unavailable",
                            "android.webkit.WebView.getSettings",
                            "p0.b",
                        ),
                        cause.stackTrace.take(4).map { "${it.className}.${it.methodName}" },
                    )
                } finally {
                    loaded.map { it.classLoader }.distinct().filterIsInstance<AutoCloseable>().forEach { it.close() }
                }
            } finally {
                diContext.closeAndJoin()
            }
        } finally {
            Injekt = previousInjekt
        }
    }

    private fun sha256(path: Path): String =
        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }

    private fun repositoryRoot() = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.isDirectory(it.resolve("app-desktop")) && Files.isDirectory(it.resolve("docs")) }

    private companion object {
        const val APK_PATH = "app-desktop/src/test/resources/extensions/real/keiyoushi-comix-1.4.34.apk"
        const val APK_SHA256 = "5d46a6ef98c1ac4f2ab22a29347748a36eb32b6995fb8a08e092446424e366d8"
        const val PACKAGE_NAME = "eu.kanade.tachiyomi.extension.en.comix"
        const val EXTENSION_CLASS = "eu.kanade.tachiyomi.extension.en.comix.ExtensionGenerated"
        const val VERSION_CODE = 34L
        const val VERSION_NAME = "1.4.34"
        const val BASE_URI = "https://example.invalid/comix-webview"
        val ASCII_BRIDGE = Regex("[A-Za-z]{10,20}")
    }
}
