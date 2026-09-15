package mihon.domain.extensionrepo.service

import kotlinx.coroutines.runBlocking
import mihon.domain.extensionrepo.model.ExtensionRepo
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class ExtensionRepoServiceV2DiscoveryTest {
    @Test
    fun `extensionless index remains discoverable when legacy metadata is absent`() = runBlocking {
        MockWebServer().also { it.start() }.use { server ->
            server.enqueue(MockResponse(code = 404, body = "no legacy metadata"))
            server.enqueue(MockResponse(body = V2_STORE_JSON))
            val index = server.url("/catalog").toString()
            val result = assertInstanceOf(
                ExtensionRepoService.FetchRepoDetailsResult.Success::class.java,
                ExtensionRepoService().fetchRepoDetailsResult(index),
            )
            assertEquals(index, result.repo.indexUrl)
            assertEquals(listOf("/catalog/repo.json", "/catalog"), List(2) { server.takeRequest().url.encodedPath })
        }
    }

    @Test
    fun `unsafe redirect is invalid metadata rather than an unavailable repository`() = runBlocking {
        MockWebServer().also { it.start() }.use { server ->
            server.enqueue(
                MockResponse(
                    code = 302,
                    headers = okhttp3.Headers.headersOf("Location", "http://remote.example/repo.json"),
                ),
            )

            assertEquals(
                ExtensionRepoService.FetchRepoDetailsResult.InvalidRepository,
                ExtensionRepoService().fetchRepoDetailsResult(server.url("/").toString()),
            )
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `explicit v2 index is fetched directly and retained as its locator`() = runBlocking {
        MockWebServer().also { it.start() }.use { server ->
            server.enqueue(MockResponse(body = V2_STORE_JSON))
            val indexUrl = server.url("/catalog/index.json").toString()

            val result = ExtensionRepoService().fetchRepoDetailsResult(indexUrl)

            val repository = assertInstanceOf(
                ExtensionRepoService.FetchRepoDetailsResult.Success::class.java,
                result,
            ).repo
            assertEquals("/catalog/index.json", server.takeRequest().url.encodedPath)
            assertEquals(indexUrl, repository.baseUrl)
            assertEquals(indexUrl, repository.indexUrl)
        }
    }

    @Test
    fun `v2 store metadata discovers the trusted identity and locator fields`() = runBlocking {
        MockWebServer().also { it.start() }.use { server ->
            server.enqueue(MockResponse(body = V2_STORE_JSON))
            val baseUrl = server.url("/").toString().removeSuffix("/")

            val result = ExtensionRepoService().fetchRepoDetailsResult(baseUrl)

            val repository = assertInstanceOf(
                ExtensionRepoService.FetchRepoDetailsResult.Success::class.java,
                result,
            ).repo
            assertEquals(
                ExtensionRepo(
                    baseUrl = baseUrl,
                    name = "Store",
                    shortName = "Store badge",
                    website = "https://store.example/about",
                    signingKeyFingerprint = "v2-fingerprint",
                    extensionListUrl = "https://store.example/extensions.pb",
                    contactDiscord = "https://discord.example/store",
                ),
                repository,
            )
        }
    }

    private companion object {
        const val V2_STORE_JSON = """
            {
              "name": "Store",
              "badgeLabel": "Store badge",
              "signingKey": "v2-fingerprint",
              "contact": {
                "website": "https://store.example/about",
                "discord": "https://discord.example/store"
              },
              "extensionList": {"extensions": []},
              "extensionListUrl": "https://store.example/extensions.pb"
            }
        """
    }
}
