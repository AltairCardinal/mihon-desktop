package mihon.desktop.extension

import android.app.Application
import dev.mihon.injekt.patchInjekt
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.model.FilterList
import kotlinx.coroutines.runBlocking
import mihon.desktop.di.initDesktopDIForTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.get
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Collections

/** Explicit immutable official JVM JAR integration; HTTP stays entirely on loopback. */
@Isolated
@Tag("integration")
class RealZerobywCoroutineCompatibilityTest {
    @Test
    fun `real Zerobyw 1_6_2 fetches its domain cold then searches through production loader`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val input = Path.of(
            requireNotNull(System.getenv("EC_ZEROBYW_JAR")) {
                "Run scripts/ec-fetch-zerobyw-fixture.py --kind jar and set EC_ZEROBYW_JAR to its pinned output"
            },
        )
        assertEquals(JAR_SHA256, sha256(input))
        DefaultDesktopArtifactAuthenticator.authenticate(input.toFile(), SIGNER_SHA256, isApk = false)
        val preferences = IsolatedDesktopPreferenceStore.create()
        val previousInjekt = Injekt
        try {
            val context = initDesktopDIForTest(tempDir.resolve("app").toFile(), preferences.store)
            try {
                val application = Injekt.get<Application>()
                val productionClient = Injekt.get<NetworkHelper>().client
                MockWebServer().also { it.start() }.use { server ->
                    val requests = Collections.synchronizedList(mutableListOf<String>())
                    // Keep the production network interceptors and extension logic. Rewrite only
                    // the external transport destination, including the nested cold-domain call.
                    val client = productionClient.newBuilder().addInterceptor { chain ->
                        val original = chain.request()
                        requests += original.url.toString()
                        val url = server.url(original.url.encodedPath).newBuilder()
                            .encodedQuery(original.url.encodedQuery).build()
                        chain.proceed(original.newBuilder().url(url).build())
                    }.build()
                    patchInjekt()
                    Injekt.addSingleton(application)
                    Injekt.addSingleton(NetworkHelper(client))
                    server.enqueue(MockResponse(body = "https://zerobyw.example"))
                    server.enqueue(MockResponse(body = SEARCH_HTML))
                    server.enqueue(MockResponse(body = SEARCH_HTML))
                    val adapted = tempDir.resolve("zerobyw-runtime.jar").toFile()
                    val runtime = if (DefaultJvmExtensionArtifactAdapter.adaptIfRequired(input.toFile(), adapted)) {
                        adapted
                    } else {
                        tempDir.resolve("zerobyw-original.jar").also { Files.copy(input, it) }.toFile()
                    }
                    writeExtensionMeta(
                        runtime,
                        ExtensionMeta(
                            pkgName = "eu.kanade.tachiyomi.extension.zh.zerobyw",
                            versionCode = 106002L,
                            versionName = "1.6.2",
                            artifactSha256 = JAR_SHA256,
                            source = ExtensionOrigin.COMPILED_JAR,
                            name = "Zerobyw",
                            language = "zh",
                            extensionClass = "keiyoushi.source.Generated",
                        ),
                    )
                    val loader = DesktopExtensionLoader(tempDir.toFile())
                    val loaded = loader.loadFromSingleJar(runtime)
                    try {
                        assertEquals(1, loaded.size, loader.diagnostics.toString())
                        assertEquals(0, server.requestCount, "Loading must not warm the domain fetch")
                        val source = loaded.single().source
                        val first = source.getSearchManga(1, "fixture", FilterList())
                        assertEquals(listOf("EC controlled manga"), first.mangas.map { it.title })
                        assertTrue(!first.hasNextPage)
                        assertEquals(
                            listOf("EC controlled manga"),
                            source.getSearchManga(2, "fixture", FilterList()).mangas.map {
                                it.title
                            },
                        )
                        assertEquals(3, server.requestCount, "Domain is fetched once before two searches")
                        assertEquals("/source-domains/zerobyw.txt", server.takeRequest().url.encodedPath)
                        listOf("1", "2").forEach { page ->
                            val request = server.takeRequest().url
                            assertEquals("/pc/pc/", request.encodedPath)
                            assertEquals("fixture", request.queryParameter("keyword"))
                            assertEquals(page, request.queryParameter("page"))
                        }
                        assertEquals(
                            1,
                            requests.count {
                                it.contains("source-domains/zerobyw.txt")
                            },
                            requests.toString(),
                        )
                        assertTrue(requests.last().startsWith("https://zerobyw.example/"), requests.toString())
                    } finally {
                        loaded.map {
                            it.classLoader
                        }.distinct().filterIsInstance<AutoCloseable>().forEach { it.close() }
                    }
                }
            } finally {
                context.closeAndJoin()
            }
        } finally {
            Injekt = previousInjekt
            preferences.close()
        }
    }

    private fun sha256(path: Path) = MessageDigest.getInstance("SHA-256")
        .digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }

    private companion object {
        const val JAR_SHA256 = "f9f016079892a344b03028fb2a84347e7e15823503851721ebbda7a0c2513396"
        const val SIGNER_SHA256 = "9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2"
        const val SEARCH_HTML = """
            <html><body>
            <a href="/details/?kuid=ec1"><h3>EC controlled manga</h3><img src="/cover.jpg"></a>
            </body></html>
        """
    }
}
