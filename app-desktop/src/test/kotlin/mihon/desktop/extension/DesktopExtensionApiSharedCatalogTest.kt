package mihon.desktop.extension

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import mihon.desktop.domain.fakes.FakeExtensionRepoRepository
import mihon.domain.error.AppError
import mihon.domain.extension.model.ExtensionCompatibility
import mihon.domain.extension.service.ExtensionCatalogService
import mihon.domain.extension.service.TrustMismatch
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64

class DesktopExtensionApiSharedCatalogTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `Desktop production API prefers signed JVM artifact from repository v2 index`() = runBlocking {
        MockWebServer().also { it.start() }.use { server ->
            val indexUrl = server.url("/index.pb")
            server.enqueue(
                MockResponse(
                    body = """{"index_v2":"$indexUrl","meta":{"name":"repo","shortName":"R","website":"https://repo.example","signingKeyFingerprint":"repo-fingerprint"}}""",
                ),
            )
            server.enqueue(MockResponse.Builder().body(Buffer().write(V2_INDEX_GZIP)).build())

            val catalog = api(repository(server, "repo")).refreshCatalog()

            val artifact = catalog.entries.single().artifact
            assertEquals("https://repo.example/jar/example.jar", artifact.downloadUrl)
            assertEquals("https://repo.example/icon.png", artifact.iconUrl)
            assertEquals("Example Source", artifact.sources.single().name)
            assertEquals(ExtensionCompatibility.Compatible, catalog.entries.single().compatibility)
            assertEquals("/repo.json", server.takeRequest().url.encodedPath)
            assertEquals("/index.pb", server.takeRequest().url.encodedPath)
        }
    }

    @Test
    fun `Desktop production API rejects v2 index whose signing key differs from trusted repository`() = runBlocking {
        MockWebServer().also { it.start() }.use { server ->
            val indexUrl = server.url("/index.pb")
            server.enqueue(
                MockResponse(
                    body = """{"index_v2":"$indexUrl","meta":{"name":"repo","shortName":"R","website":"https://repo.example","signingKeyFingerprint":"trusted-fingerprint"}}""",
                ),
            )
            server.enqueue(MockResponse.Builder().body(Buffer().write(V2_INDEX_GZIP)).build())
            val repository = TestRepository(
                baseUrl = server.url("/").toString().removeSuffix("/"),
                name = "repo",
                fingerprint = "trusted-fingerprint",
            )

            val catalog = api(repository).refreshCatalog()

            assertTrue(catalog.entries.isEmpty())
            assertInstanceOf(AppError.MalformedData::class.java, catalog.failures.single().error)
        }
    }

    @Test
    fun `Desktop production API preserves successful repository when another repository fails`() = runBlocking {
        withServers { successful, failed ->
            successful.enqueue(legacyManifest("success"))
            successful.enqueue(MockResponse(body = INDEX_JSON))
            failed.enqueue(MockResponse(code = 500, body = "server error"))
            val api = api(
                repository(successful, "success"),
                repository(failed, "failed"),
            )

            val catalog = api.refreshCatalog()

            assertEquals(listOf("eu.kanade.tachiyomi.extension.en.example"), catalog.entries.map { it.artifact.packageName })
            assertEquals(1, catalog.failures.size)
            assertEquals(500, (catalog.failures.single().error as AppError.Server).statusCode)

            successful.enqueue(legacyManifest("success"))
            successful.enqueue(MockResponse(body = INDEX_JSON))
            failed.enqueue(MockResponse(code = 500, body = "server error"))
            val available = api.findAvailableExtensions().single()
            assertEquals("Example", available.name)
            assertEquals(1.4, available.libVersion)
            assertEquals("0123456789abcdef", available.declaredSha256)
            assertEquals("https://source.example", available.sources.single().baseUrl)
        }
    }

    @Test
    fun `Desktop production API distinguishes a successful empty repository`() = runBlocking {
        withServer(legacyManifest("empty"), MockResponse(body = "[]")) { server ->
            val catalog = api(repository(server, "empty")).refreshCatalog()

            assertTrue(catalog.isCompleteEmpty)
        }
    }

    @Test
    fun `Desktop production API maps malformed and HTTP repository failures`() = runBlocking {
        assertFailure(MockResponse(body = "not-json"), AppError.MalformedData::class.java)
        assertFailure(MockResponse(code = 403, body = "forbidden"), AppError.Authentication::class.java)
        assertFailure(MockResponse(code = 429, body = "slow down"), AppError.RateLimited::class.java)
        assertFailure(MockResponse(code = 500, body = "server error"), AppError.Server::class.java)
    }

    @Test
    fun `Desktop existing extension with legacy sidecar missing identity requires trust before download`() = runBlocking {
        withServer(MockResponse(body = "not-an-extension")) { server ->
            val installedJar = File(tempDir.toFile(), "legacy.extension.jar").also { it.writeText("installed") }
            writeExtensionMeta(
                installedJar,
                ExtensionMeta(
                    pkgName = "legacy.extension",
                    versionCode = 1,
                    versionName = "1.4.1",
                    artifactSha256 = installedJar.readBytes().sha256(),
                ),
            )
            val available = DesktopAvailableExtension(
                name = "Legacy",
                pkgName = "legacy.extension",
                versionName = "1.4.2",
                versionCode = 2,
                libVersion = 1.4,
                lang = "en",
                isNsfw = false,
                jarUrl = server.url("/apk/legacy.apk").toString(),
                iconUrl = "",
                repoUrl = server.url("/").toString().removeSuffix("/"),
                repoName = "incoming",
                repoFingerprint = "incoming-fingerprint",
            )

            val api = api()
            val result = install(api, available)

            val trustRequired =
                assertInstanceOf(DesktopExtensionApi.InstallResult.TrustRequired::class.java, result)
            assertEquals(setOf(TrustMismatch.LegacyMetadataMissingRepositoryIdentity), trustRequired.reasons)
            assertEquals(0, api.pendingTrustCount)
        }
    }

    @Test
    fun `Desktop production result preserves missing artifact digest reason`() = runBlocking {
        val installedJar = installedExtension(
            repositoryUrl = "https://repo.example",
            repositoryFingerprint = "repo-fingerprint",
            recordedDigest = "",
        )

        val result = install(api(), availableExtension())

        val trustRequired = assertInstanceOf(DesktopExtensionApi.InstallResult.TrustRequired::class.java, result)
        assertEquals(setOf(TrustMismatch.LegacyMetadataMissingArtifactDigest), trustRequired.reasons)
        assertTrue(installedJar.exists())
    }

    @Test
    fun `Desktop production result preserves repository origin change reason`() = runBlocking {
        installedExtension(
            repositoryUrl = "https://old.example",
            repositoryFingerprint = "repo-fingerprint",
        )

        val result = install(api(), availableExtension())

        val trustRequired = assertInstanceOf(DesktopExtensionApi.InstallResult.TrustRequired::class.java, result)
        assertEquals(
            setOf(TrustMismatch.InstalledOriginChanged("https://old.example", "https://repo.example")),
            trustRequired.reasons,
        )
    }

    @Test
    fun `Desktop production result preserves repository fingerprint change reason`() = runBlocking {
        installedExtension(
            repositoryUrl = "https://repo.example",
            repositoryFingerprint = "old-fingerprint",
        )

        val result = install(api(), availableExtension())

        val trustRequired = assertInstanceOf(DesktopExtensionApi.InstallResult.TrustRequired::class.java, result)
        assertEquals(
            setOf(TrustMismatch.RepositoryIdentityChanged("old-fingerprint", "repo-fingerprint")),
            trustRequired.reasons,
        )
    }

    @Test
    fun `Desktop production result preserves installed digest rejection error`() = runBlocking {
        installedExtension(
            repositoryUrl = "https://repo.example",
            repositoryFingerprint = "repo-fingerprint",
            recordedDigest = "not-the-installed-digest",
        )

        val result = install(api(), availableExtension())

        val rejected = assertInstanceOf(DesktopExtensionApi.InstallResult.Error::class.java, result)
        assertInstanceOf(AppError.MalformedData::class.java, rejected.error)
        assertEquals("Installed extension digest mismatch", rejected.error?.cause?.message)
    }

    @Test
    fun `Desktop production download rejects a declared digest mismatch`() = runBlocking {
        withServer(MockResponse(body = "not-an-extension")) { server ->
            val available = DesktopAvailableExtension(
                name = "Digest",
                pkgName = "digest.extension",
                versionName = "1.4.2",
                versionCode = 2,
                libVersion = 1.4,
                lang = "en",
                isNsfw = false,
                jarUrl = server.url("/apk/digest.apk").toString(),
                iconUrl = "",
                repoUrl = server.url("/").toString().removeSuffix("/"),
                repoName = "incoming",
                repoFingerprint = "incoming-fingerprint",
                declaredSha256 = "0000",
            )

            val result = install(api(), available)

            val error = assertInstanceOf(DesktopExtensionApi.InstallResult.Error::class.java, result)
            assertEquals("Extension artifact integrity validation failed", error.message)
            assertInstanceOf(AppError.MalformedData::class.java, error.error)
            assertEquals("Downloaded extension digest mismatch", error.error?.cause?.message)
        }
    }

    private fun installedExtension(
        repositoryUrl: String,
        repositoryFingerprint: String,
        recordedDigest: String? = null,
    ): File {
        val installedJar = File(tempDir.toFile(), "example.extension.jar").also { it.writeText("installed") }
        writeExtensionMeta(
            installedJar,
            ExtensionMeta(
                pkgName = "example.extension",
                versionCode = 1,
                versionName = "1.4.1",
                repoUrl = repositoryUrl,
                repoName = "repository",
                repoFingerprint = repositoryFingerprint,
                artifactSha256 = recordedDigest ?: installedJar.readBytes().sha256(),
            ),
        )
        return installedJar
    }

    private fun availableExtension() = DesktopAvailableExtension(
        name = "Example",
        pkgName = "example.extension",
        versionName = "1.4.2",
        versionCode = 2,
        libVersion = 1.4,
        lang = "en",
        isNsfw = false,
        jarUrl = "https://repo.example/apk/example.apk",
        iconUrl = "",
        repoUrl = "https://repo.example",
        repoName = "repository",
        repoFingerprint = "repo-fingerprint",
    )

    private suspend fun assertFailure(response: MockResponse, expected: Class<out AppError>) {
        withServer(response) { server ->
            val catalog = api(repository(server, "failure")).refreshCatalog()

            assertTrue(catalog.entries.isEmpty())
            assertEquals(1, catalog.failures.size)
            assertInstanceOf(expected, catalog.failures.single().error)
        }
    }

    private suspend fun api(vararg repositories: TestRepository): DesktopExtensionApi {
        val repository = FakeExtensionRepoRepository()
        repositories.forEach {
            repository.insertRepo(it.baseUrl, it.name, it.name, it.baseUrl, it.fingerprint)
        }
        return DesktopExtensionApi(
            client = OkHttpClient(),
            json = Json { ignoreUnknownKeys = true },
            extensionRepoRepository = repository,
            catalogService = ExtensionCatalogService(),
        )
    }

    private suspend fun install(
        api: DesktopExtensionApi,
        extension: DesktopAvailableExtension,
    ): DesktopExtensionApi.InstallResult {
        val manager = DesktopExtensionManager(
            loader = DesktopExtensionLoader(tempDir.toFile()),
            artifactProvider = api::downloadArtifact,
        ).also { it.loadAll() }
        return try {
            api.installExtension(extension, manager)
        } finally {
            manager.close()
        }
    }

    private fun repository(server: MockWebServer, name: String) = TestRepository(
        baseUrl = server.url("/").toString().removeSuffix("/"),
        name = name,
        fingerprint = "$name-fingerprint",
    )

    private suspend fun withServer(response: MockResponse, block: suspend (MockWebServer) -> Unit) {
        MockWebServer().also { it.start() }.use { server ->
            server.enqueue(response)
            block(server)
        }
    }

    private suspend fun withServer(
        first: MockResponse,
        second: MockResponse,
        block: suspend (MockWebServer) -> Unit,
    ) {
        MockWebServer().also { it.start() }.use { server ->
            server.enqueue(first)
            server.enqueue(second)
            block(server)
        }
    }

    private suspend fun withServers(block: suspend (MockWebServer, MockWebServer) -> Unit) {
        MockWebServer().also { it.start() }.use { first ->
            MockWebServer().also { it.start() }.use { second -> block(first, second) }
        }
    }

    private data class TestRepository(val baseUrl: String, val name: String, val fingerprint: String)

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { "%02x".format(it) }

    private fun legacyManifest(name: String) = MockResponse(
        body = """{"meta":{"name":"$name","shortName":"$name","website":"https://$name.example","signingKeyFingerprint":"$name-fingerprint"}}""",
    )

    private companion object {
        val V2_INDEX_GZIP: ByteArray = Base64.getDecoder().decode(
            "H4sIAAAAAAAC/+PiCkotyC/OLMkvqpQSKAKyddMy89JTiwqKMvNKVrE9YeR6yMjF7lqRmFuQkyqkkVqql52Yl5iSqleSmJyRWZmfm6mXWlGSmlecmZ+nlwpEEJVSOVwqGSUlBcVW+vogU2Hi+okF2fpQth6QLSSLVVVmMtC0grz0VfLYTclKLIKbAmQrMRvqmWkwGbECKT0jC0YnPQ4tIT6ooxWC80uLklOlmFLzlMRgphWDxWDmAQBGh0XiBgEAAA==",
        )
        const val INDEX_JSON =
            """[{"name":"Tachiyomi: Example","pkg":"eu.kanade.tachiyomi.extension.en.example","apk":"example.apk","lang":"en","code":42,"version":"1.4.7","nsfw":0,"sha256":"0123456789abcdef","sources":[{"id":7,"lang":"en","name":"Example Source","baseUrl":"https://source.example"}]}]"""
    }
}
