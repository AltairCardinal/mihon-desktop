package eu.kanade.tachiyomi.extension.api

import android.app.Application
import android.content.Context
import androidx.core.content.ContextCompat
import eu.kanade.tachiyomi.di.AppModule
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.network.AndroidNetworkResponseAdapter
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import mihon.domain.error.AppError
import mihon.domain.extension.service.ExtensionCatalogService
import mihon.domain.extension.service.ExtensionUpdatePolicy
import mihon.domain.extensionrepo.model.ExtensionRepo
import mihon.domain.extensionrepo.service.ExtensionRepoIndexEntryDto
import mihon.domain.network.requireSuccessfulHttpResponse
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.registry.default.DefaultRegistrar
import java.util.concurrent.Executor

class ExtensionApiSharedCatalogTest {

    @Test
    fun `Android production API preserves successful repository when another repository fails`() = runBlocking {
        withServers { successful, failed ->
            successful.enqueue(MockResponse(body = INDEX_JSON))
            failed.enqueue(MockResponse(code = 500, body = "server error"))
            val api = api(listOf(repository(successful, "success"), repository(failed, "failed")))

            val catalog = api.refreshCatalog()

            assertEquals(
                listOf("eu.kanade.tachiyomi.extension.en.example"),
                catalog.entries.map {
                    it.artifact.packageName
                },
            )
            assertEquals(1, catalog.failures.size)
            assertEquals(500, (catalog.failures.single().error as AppError.Server).statusCode)

            successful.enqueue(MockResponse(body = INDEX_JSON))
            failed.enqueue(MockResponse(code = 500, body = "server error"))
            val available = api.findExtensions().single()
            assertEquals("Example", available.name)
            assertEquals(1.4, available.libVersion)
            assertEquals("https://source.example", available.sources.single().baseUrl)
        }
    }

    @Test
    fun `Android production API distinguishes a successful empty repository`() = runBlocking {
        withServer(MockResponse(body = "[]")) { server ->
            val catalog = api(listOf(repository(server, "empty"))).refreshCatalog()

            assertTrue(catalog.isCompleteEmpty)
        }
    }

    @Test
    fun `Android production API maps malformed and HTTP repository failures`() = runBlocking {
        assertFailure(MockResponse(body = "not-json"), AppError.MalformedData::class.java)
        assertFailure(MockResponse(code = 403, body = "forbidden"), AppError.Authentication::class.java)
        assertFailure(MockResponse(code = 429, body = "slow down"), AppError.RateLimited::class.java)
        assertFailure(MockResponse(code = 500, body = "server error"), AppError.Server::class.java)
        Unit
    }

    @Test
    fun `Android raw repository responses execute shared network error mapper`() = runBlocking {
        mockkStatic("mihon.domain.network.NetworkErrorMapperKt")
        try {
            every { requireSuccessfulHttpResponse(any(), any(), any()) } answers { callOriginal() }

            assertFailure(MockResponse(code = 401, body = "login required"), AppError.Authentication::class.java)
            assertFailure(MockResponse(code = 403, body = "forbidden"), AppError.Authentication::class.java)
            val rateLimited = assertFailure(
                MockResponse(
                    code = 429,
                    headers = okhttp3.Headers.headersOf("Retry-After", "42"),
                    body = "slow down",
                ),
                AppError.RateLimited::class.java,
            )
            assertEquals(42L, (rateLimited as AppError.RateLimited).retryAfterSeconds)
            assertFailure(MockResponse(code = 503, body = "unavailable"), AppError.Server::class.java)
            assertFailure(MockResponse(body = "not-json"), AppError.MalformedData::class.java)

            verify(exactly = 1) { requireSuccessfulHttpResponse(401, "login required", null) }
            verify(exactly = 1) { requireSuccessfulHttpResponse(403, "forbidden", null) }
            verify(exactly = 1) { requireSuccessfulHttpResponse(429, "slow down", "42") }
            verify(exactly = 1) { requireSuccessfulHttpResponse(503, "unavailable", null) }
            verify(exactly = 1) { requireSuccessfulHttpResponse(200, "not-json", null) }
        } finally {
            unmockkStatic("mihon.domain.network.NetworkErrorMapperKt")
        }
    }

    @Test
    fun `Android malformed repository executes shared payload parser`() = runBlocking {
        val adapter = spyk(AndroidNetworkResponseAdapter())
        withServer(MockResponse(body = "not-json")) { server ->
            val catalog = api(listOf(repository(server, "malformed")), adapter).refreshCatalog()

            assertInstanceOf(AppError.MalformedData::class.java, catalog.failures.single().error)
            verify(exactly = 1) {
                adapter.parsePayload<List<ExtensionRepoIndexEntryDto>>(any())
            }
        }
    }

    @Test
    fun `Android no arg API resolves AppModule adapter for raw repository response`() = runBlocking {
        withIsolatedAppModule { boundAdapter ->
            mockkStatic("mihon.domain.network.NetworkErrorMapperKt")
            try {
                every { requireSuccessfulHttpResponse(any(), any(), any()) } answers { callOriginal() }
                withServer(
                    MockResponse(
                        code = 429,
                        headers = okhttp3.Headers.headersOf("Retry-After", "73"),
                        body = "slow down",
                    ),
                ) { server ->
                    val api = ExtensionApi(
                        client = OkHttpClient(),
                        json = Json { ignoreUnknownKeys = true },
                        repositories = { listOf(repository(server, "injected")) },
                        catalogService = ExtensionCatalogService(),
                    )

                    val failure = api.refreshCatalog().failures.single().error

                    assertSame(boundAdapter, Injekt.get<AndroidNetworkResponseAdapter>())
                    assertEquals(73L, (failure as AppError.RateLimited).retryAfterSeconds)
                    verify(exactly = 1) { requireSuccessfulHttpResponse(429, "slow down", "73") }
                }
            } finally {
                unmockkStatic("mihon.domain.network.NetworkErrorMapperKt")
            }
        }
    }

    @Test
    fun `Android production API update check delegates to shared version policy`() = runBlocking {
        val evaluatedVersions = mutableListOf<List<Number>>()
        val installed = installedExtension()
        val available = availableExtension()
        val api = ExtensionApi(
            updatePolicy = ExtensionUpdatePolicy { availableCode, availableLib, installedCode, installedLib ->
                evaluatedVersions += listOf(availableCode, availableLib, installedCode, installedLib)
                true
            },
            refreshRepositories = {},
            availableExtensionsForUpdate = { listOf(available) },
            installedExtensions = { listOf(installed) },
            notifyUpdates = { _, _ -> },
        )

        val updates = api.checkForUpdates(context = mockk<Context>(relaxed = true), fromAvailableExtensionList = true)

        assertEquals(listOf(installed), updates)
        assertEquals(listOf(listOf(10L, 1.4, 10L, 1.4)), evaluatedVersions)
    }

    @Test
    fun `Android production API defaults to shared lib version update policy`() = runBlocking {
        val installed = installedExtension()
        val api = ExtensionApi(
            refreshRepositories = {},
            availableExtensionsForUpdate = { listOf(availableExtension(libVersion = 1.5)) },
            installedExtensions = { listOf(installed) },
            notifyUpdates = { _, _ -> },
        )

        val updates = api.checkForUpdates(context = mockk<Context>(relaxed = true), fromAvailableExtensionList = true)

        assertEquals(listOf(installed), updates)
    }

    private fun installedExtension() = Extension.Installed(
        name = "Example",
        pkgName = "example.extension",
        versionName = "1.4.1",
        versionCode = 10,
        libVersion = 1.4,
        lang = "en",
        isNsfw = false,
        pkgFactory = null,
        sources = emptyList(),
        icon = null,
        isShared = false,
    )

    private fun availableExtension(libVersion: Double = 1.4) = Extension.Available(
        name = "Example",
        pkgName = "example.extension",
        versionName = "1.4.1",
        versionCode = 10,
        libVersion = libVersion,
        lang = "en",
        isNsfw = false,
        sources = emptyList(),
        apkName = "example.apk",
        iconUrl = "https://repo.example/icon.png",
        repoUrl = "https://repo.example",
    )

    private suspend fun assertFailure(response: MockResponse, expected: Class<out AppError>): AppError =
        withServer(response) { server ->
            val catalog = api(listOf(repository(server, "failure"))).refreshCatalog()

            assertTrue(catalog.entries.isEmpty())
            assertEquals(1, catalog.failures.size)
            assertInstanceOf(expected, catalog.failures.single().error)
            catalog.failures.single().error
        }

    private fun api(
        repositories: List<ExtensionRepo>,
        responseAdapter: AndroidNetworkResponseAdapter = AndroidNetworkResponseAdapter(),
    ) = ExtensionApi(
        client = OkHttpClient(),
        json = Json { ignoreUnknownKeys = true },
        repositories = { repositories },
        catalogService = ExtensionCatalogService(),
        responseAdapter = responseAdapter,
    )

    private fun repository(server: MockWebServer, name: String) = ExtensionRepo(
        baseUrl = server.url("/").toString().removeSuffix("/"),
        name = name,
        shortName = name,
        website = server.url("/about").toString(),
        signingKeyFingerprint = "$name-fingerprint",
    )

    private suspend fun <T> withServer(response: MockResponse, block: suspend (MockWebServer) -> T): T =
        MockWebServer().also { it.start() }.use { server ->
            server.enqueue(response)
            block(server)
        }

    private suspend fun withServers(block: suspend (MockWebServer, MockWebServer) -> Unit) {
        MockWebServer().also { it.start() }.use { first ->
            MockWebServer().also { it.start() }.use { second -> block(first, second) }
        }
    }

    private suspend fun <T> withIsolatedAppModule(
        block: suspend (AndroidNetworkResponseAdapter) -> T,
    ): T {
        val previous = Injekt
        Injekt = InjektScope(DefaultRegistrar())
        val application = mockk<Application>(relaxed = true)
        mockkStatic(ContextCompat::class)
        return try {
            every { ContextCompat.getMainExecutor(application) } returns Executor { }
            Injekt.importModule(AppModule(application))
            block(Injekt.get())
        } finally {
            unmockkStatic(ContextCompat::class)
            Injekt = previous
        }
    }

    private companion object {
        const val INDEX_JSON =
            """[{"name":"Tachiyomi: Example","pkg":"eu.kanade.tachiyomi.extension.en.example","apk":"example.apk","lang":"en","code":42,"version":"1.4.7","nsfw":0,"sha256":"0123456789abcdef","sources":[{"id":7,"lang":"en","name":"Example Source","baseUrl":"https://source.example"}]}]"""
    }
}
