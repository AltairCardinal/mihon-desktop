package eu.kanade.tachiyomi.extension.api

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import mihon.domain.error.AppError
import mihon.domain.extension.model.isExtensionUpdateAvailable
import mihon.domain.extension.service.ExtensionCatalogService
import mihon.domain.extensionrepo.model.ExtensionRepo
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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
    }

    @Test
    fun `Android update rule detects a newer shared lib version`() {
        assertTrue(
            isExtensionUpdateAvailable(
                availableVersionCode = 10,
                availableLibVersion = 1.5,
                installedVersionCode = 10,
                installedLibVersion = 1.4,
            ),
        )
    }

    private suspend fun assertFailure(response: MockResponse, expected: Class<out AppError>) {
        withServer(response) { server ->
            val catalog = api(listOf(repository(server, "failure"))).refreshCatalog()

            assertTrue(catalog.entries.isEmpty())
            assertEquals(1, catalog.failures.size)
            assertInstanceOf(expected, catalog.failures.single().error)
        }
    }

    private fun api(repositories: List<ExtensionRepo>) = ExtensionApi(
        client = OkHttpClient(),
        json = Json { ignoreUnknownKeys = true },
        repositories = { repositories },
        catalogService = ExtensionCatalogService(),
    )

    private fun repository(server: MockWebServer, name: String) = ExtensionRepo(
        baseUrl = server.url("/").toString().removeSuffix("/"),
        name = name,
        shortName = name,
        website = server.url("/about").toString(),
        signingKeyFingerprint = "$name-fingerprint",
    )

    private suspend fun withServer(response: MockResponse, block: suspend (MockWebServer) -> Unit) {
        MockWebServer().also { it.start() }.use { server ->
            server.enqueue(response)
            block(server)
        }
    }

    private suspend fun withServers(block: suspend (MockWebServer, MockWebServer) -> Unit) {
        MockWebServer().also { it.start() }.use { first ->
            MockWebServer().also { it.start() }.use { second -> block(first, second) }
        }
    }

    private companion object {
        const val INDEX_JSON =
            """[{"name":"Tachiyomi: Example","pkg":"eu.kanade.tachiyomi.extension.en.example","apk":"example.apk","lang":"en","code":42,"version":"1.4.7","nsfw":0,"sha256":"0123456789abcdef","sources":[{"id":7,"lang":"en","name":"Example Source","baseUrl":"https://source.example"}]}]"""
    }
}
