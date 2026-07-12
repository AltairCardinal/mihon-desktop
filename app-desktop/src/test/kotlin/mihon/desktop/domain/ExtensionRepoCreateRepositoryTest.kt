package mihon.desktop.domain

import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import mihon.desktop.domain.fakes.FakeExtensionRepoRepository
import mihon.domain.extensionrepo.interactor.CreateExtensionRepo
import mihon.domain.extensionrepo.service.ExtensionRepoService
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ExtensionRepoCreateRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: FakeExtensionRepoRepository
    private lateinit var createExtensionRepo: CreateExtensionRepo

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        repository = FakeExtensionRepoRepository()
        val service = ExtensionRepoService(
            networkHelper = NetworkHelper(OkHttpClient()),
            json = Json { ignoreUnknownKeys = true },
        )
        createExtensionRepo = CreateExtensionRepo(repository, service)
    }

    @AfterEach
    fun tearDown() {
        server.close()
    }

    @Test
    fun `base repository url is accepted and normalized before insert`() = runBlocking {
        server.enqueue(repoJson())
        val baseUrl = server.url("/keiyoushi/repo").toString().removeSuffix("/")

        val result = createExtensionRepo.await(baseUrl)

        assertEquals(CreateExtensionRepo.Result.Success, result)
        assertEquals(baseUrl, repository.getAll().single().baseUrl)
        assertEquals("/keiyoushi/repo/repo.json", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `index url is accepted and normalized before insert`() = runBlocking {
        server.enqueue(repoJson())
        val baseUrl = server.url("/keiyoushi/repo").toString().removeSuffix("/")

        val result = createExtensionRepo.await("$baseUrl/index.min.json")

        assertEquals(CreateExtensionRepo.Result.Success, result)
        assertEquals(baseUrl, repository.getAll().single().baseUrl)
        assertEquals("/keiyoushi/repo/repo.json", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `http failure returns repository unavailable instead of invalid url`() = runBlocking {
        server.enqueue(MockResponse(code = 404, body = "not found"))
        val baseUrl = server.url("/missing/repo").toString().removeSuffix("/")

        val result = createExtensionRepo.await(baseUrl)

        assertEquals(CreateExtensionRepo.Result.RepositoryUnavailable, result)
    }

    @Test
    fun `malformed metadata returns invalid repository instead of invalid url`() = runBlocking {
        server.enqueue(MockResponse(body = """{"meta":{"name":42}}"""))
        val baseUrl = server.url("/bad/repo").toString().removeSuffix("/")

        val result = createExtensionRepo.await(baseUrl)

        assertEquals(CreateExtensionRepo.Result.InvalidRepository, result)
    }

    @Test
    fun `duplicate fingerprint still offers replacement after url normalization`() = runBlocking {
        repository.insertRepo(
            baseUrl = "https://old.example/repo",
            name = "Old",
            shortName = null,
            website = "https://old.example",
            signingKeyFingerprint = "shared-fingerprint",
        )
        server.enqueue(repoJson(signingKeyFingerprint = "shared-fingerprint"))
        val baseUrl = server.url("/new/repo").toString().removeSuffix("/")

        val result = createExtensionRepo.await(baseUrl)

        assertInstanceOf(CreateExtensionRepo.Result.DuplicateFingerprint::class.java, result)
    }

    private fun repoJson(
        signingKeyFingerprint: String = "fingerprint-1",
    ): MockResponse {
        return MockResponse(
            body = """
                {
                  "meta": {
                    "name": "Keiyoushi",
                    "shortName": "Keiyoushi",
                    "website": "https://keiyoushi.github.io",
                    "signingKeyFingerprint": "$signingKeyFingerprint"
                  }
                }
            """.trimIndent(),
        )
    }
}
