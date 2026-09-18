package mihon.desktop.extension

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import mihon.desktop.domain.fakes.FakeExtensionRepoRepository
import mihon.domain.error.AppError
import mihon.domain.extension.model.ExtensionCompatibility
import mihon.domain.extension.service.ExtensionCatalogService
import mihon.domain.extension.service.TrustMismatch
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.security.MessageDigest

class DesktopExtensionApiSharedCatalogTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = [false, true])
    fun `cancelling at presentation Pending releases normal and confirmed API ownership`(trustRequired: Boolean) = runBlocking {
        val api = api()
        val extension = DesktopAvailableExtension(
            "Reader", "pkg.firstpending", "1.4.2", 2, 1.4, "en", false,
            "https://repo.example/reader.jar", "", "https://repo.example", repoFingerprint = "new-key",
        )
        if (trustRequired) {
            val jar = extensionArtifactFile(tempDir.toFile(), extension.pkgName, "jar")
            jar.writeBytes(byteArrayOf(1, 2, 3))
            writeExtensionMeta(jar, ExtensionMeta(extension.pkgName, 1, "1.4.1", repoUrl = extension.repoUrl, repoFingerprint = "old-key"))
        }
        val manager = io.mockk.mockk<DesktopExtensionPresentationService> {
            io.mockk.every { installedExtensions } returns kotlinx.coroutines.flow.MutableStateFlow(emptyList())
            io.mockk.every { extensionsDirectory } returns tempDir.toFile()
        }
        val port = mihon.desktop.ui.extension.DesktopExtensionPresentationPort(api, manager)
        val first = port.beginPresentationInstall(extension)
        val events = when (first) {
            is mihon.desktop.ui.extension.DesktopPresentationInstallStart.Started -> first.events
            is mihon.desktop.ui.extension.DesktopPresentationInstallStart.TrustRequired ->
                checkNotNull(port.confirmPresentationTrust(first.request.requestId))
            is mihon.desktop.ui.extension.DesktopPresentationInstallStart.Rejected -> error("Unexpected rejection")
        }
        assertEquals(mihon.domain.extension.presentation.ExtensionPresentationInstallStep.Pending, events.take(1).toList().single().step)
        org.junit.jupiter.api.Assertions.assertFalse(api.installArbiter.isBusy(extension.pkgName))
        io.mockk.verify(exactly = 0) { manager.installExtensionStates(any(), any()) }
    }


    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `metadata is read only after the actual package reservation is active`() = runBlocking {
        val api = api()
        val extension = DesktopAvailableExtension(
            "Reader", "pkg.metadata", "1.4.2", 2, 1.4, "en", false,
            "https://repo.example/reader.jar", "", "https://repo.example", repoFingerprint = "key",
        )
        val manager = io.mockk.mockk<DesktopExtensionPresentationService>()
        io.mockk.every { manager.extensionsDirectory } answers {
            assertTrue(api.installArbiter.isBusy(extension.pkgName))
            tempDir.toFile()
        }
        io.mockk.every { manager.installExtensionStates(any(), any()) } returns kotlinx.coroutines.flow.emptyFlow()
        assertInstanceOf(DesktopExtensionInstallStart.Started::class.java, api.beginInstall(extension, manager)).states.collect {}
    }

    @Test
    fun `trust synchronous adapter failure releases the exact reservation`() = runBlocking {
        val api = api()
        val extension = DesktopAvailableExtension(
            "Reader", "pkg.trust", "1.4.2", 2, 1.4, "en", false,
            "https://repo.example/reader.jar", "", "https://repo.example", repoFingerprint = "new-key",
        )
        val jar = extensionArtifactFile(tempDir.toFile(), extension.pkgName, "jar")
        jar.writeBytes(byteArrayOf(1, 2, 3))
        writeExtensionMeta(jar, ExtensionMeta(extension.pkgName, 1, "1.4.1", repoUrl = extension.repoUrl, repoFingerprint = "old-key"))
        val manager = io.mockk.mockk<DesktopExtensionPresentationService>()
        io.mockk.every { manager.extensionsDirectory } returns tempDir.toFile()
        val pending = assertInstanceOf(DesktopExtensionInstallStart.TrustRequired::class.java, api.beginInstall(extension, manager))
        assertInstanceOf(DesktopExtensionInstallStart.Rejected::class.java, api.beginInstall(extension, manager))
        io.mockk.every { manager.installExtensionStates(any(), any()) } throws IllegalStateException("synchronous adapter failure")
        val failure = runCatching { checkNotNull(api.confirmTrust(pending.requestId, manager)).collect {} }.exceptionOrNull()
        assertEquals("synchronous adapter failure", failure?.message)
        org.junit.jupiter.api.Assertions.assertFalse(api.installArbiter.isBusy(extension.pkgName))
        val retry = assertInstanceOf(DesktopExtensionInstallStart.TrustRequired::class.java, api.beginInstall(extension, manager))
        assertTrue(api.discardTrust(retry.requestId))
        org.junit.jupiter.api.Assertions.assertFalse(api.installArbiter.isBusy(extension.pkgName))
    }

    @Test
    fun `two production API entries reject a busy package instead of keeping a replacement request`() = runBlocking {
        val api = api()
        val extension = DesktopAvailableExtension(
            "Reader", "pkg.busy", "1.4.2", 2, 1.4, "en", false,
            "https://repo.example/reader.jar", "", "https://repo.example", repoFingerprint = "key",
        )
        val manager = io.mockk.mockk<DesktopExtensionPresentationService>(relaxed = true)
        io.mockk.every { manager.extensionsDirectory } returns tempDir.toFile()
        val entered = CompletableDeferred<Unit>()
        io.mockk.every { manager.installExtensionStates(any(), any()) } returns kotlinx.coroutines.flow.flow {
            entered.complete(Unit)
            kotlinx.coroutines.awaitCancellation()
        }
        val first = assertInstanceOf(DesktopExtensionInstallStart.Started::class.java, api.beginInstall(extension, manager))
        val running = async { first.states.collect {} }
        entered.await()
        try {
            assertInstanceOf(DesktopExtensionInstallStart.Rejected::class.java, api.beginInstall(extension.copy(repoUrl = "https://other.example"), manager))
            io.mockk.verify(exactly = 1) { manager.installExtensionStates(any(), any()) }
        } finally {
            running.cancelAndJoin()
        }
        val retry = assertInstanceOf(DesktopExtensionInstallStart.Started::class.java, api.beginInstall(extension, manager))
        val retryJob = async { retry.states.collect {} }
        kotlinx.coroutines.yield()
        retryJob.cancelAndJoin()
    }

    @Test
    fun `Desktop explicit zero API cannot fall back to supported version name`() = runBlocking {
        MockWebServer().also { it.start() }.use { server ->
            server.enqueue(MockResponse(body = V2_INDEX_JSON.replace("\"extensionLib\": \"1.6\"", "\"extensionLib\": \"0.0\"")))
            val api = api(repository(server, "repo"))
            val extension = api.findAvailableExtensions().single()
            val manager = io.mockk.mockk<DesktopExtensionPresentationService>(relaxed = true)
            io.mockk.every { manager.extensionsDirectory } returns tempDir.toFile()
            assertEquals(0.0, extension.libVersion)
            assertInstanceOf(ExtensionCompatibility.UnsupportedLib::class.java, extension.compatibility)
            assertInstanceOf(DesktopExtensionInstallStart.Rejected::class.java, api.beginInstall(extension, manager))
            io.mockk.verify(exactly = 0) { manager.installExtensionStates(any(), any()) }
        }
    }

    @Test
    fun `Desktop repository rate limiting preserves Retry After seconds`() = runBlocking {
        MockWebServer().also { it.start() }.use { server ->
            server.enqueue(MockResponse.Builder().code(429).addHeader("Retry-After", "42").body("slow down").build())
            val failure = api(repository(server, "rate-limited")).refreshCatalog().failures.single().error
            assertEquals(42L, (failure as AppError.RateLimited).retryAfterSeconds)
        }
    }

    @Test
    fun `Desktop install preserves explicit catalog API version through trust request`() = runBlocking {
        val api = api()
        val extension = DesktopAvailableExtension(
            "Reader", "pkg.explicit", "1.4.2", 2, 1.6, "en", false,
            "https://repo.example/reader.jar", "", "https://repo.example", repoFingerprint = "key",
        )
        val manager = io.mockk.mockk<DesktopExtensionPresentationService>(relaxed = true)
        io.mockk.every { manager.extensionsDirectory } returns tempDir.toFile()
        val captured = io.mockk.slot<mihon.domain.extension.model.ExtensionArtifact>()
        io.mockk.every { manager.installExtensionStates(capture(captured), any()) } returns kotlinx.coroutines.flow.emptyFlow()
        assertInstanceOf(DesktopExtensionInstallStart.Started::class.java, api.beginInstall(extension, manager)).states.collect {}
        assertEquals(1.6, captured.captured.libVersion)
    }

    @Test
    fun `Desktop HTTP timeout remains Network and parent cancellation releases production call`() = runBlocking {
        MockWebServer().also { it.start() }.use { server ->
            server.enqueue(MockResponse.Builder().headersDelay(250, java.util.concurrent.TimeUnit.MILLISECONDS).body("{}").build())
            val timeoutClient = OkHttpClient.Builder().callTimeout(50, java.util.concurrent.TimeUnit.MILLISECONDS).build()
            val failure = api(repository(server, "timeout"), client = timeoutClient).refreshCatalog().failures.single()
            assertInstanceOf(AppError.Network::class.java, failure.error)
        }
        MockWebServer().also { it.start() }.use { server ->
            server.enqueue(MockResponse.Builder().headersDelay(5, java.util.concurrent.TimeUnit.SECONDS).body("{}").build())
            val client = OkHttpClient()
            val callsIdle = CompletableDeferred<Unit>()
            client.dispatcher.idleCallback = Runnable { callsIdle.complete(Unit) }
            val pending = async { api(repository(server, "cancel"), client = client).refreshCatalog() }
            try {
                withContext(Dispatchers.IO) { checkNotNull(server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)) }
                pending.cancel(CancellationException("parent cancelled"))
                assertTrue(runCatching { pending.await() }.exceptionOrNull() is CancellationException)
                withTimeout(5_000) { callsIdle.await() }
                assertEquals(0, client.dispatcher.runningCallsCount())
            } finally {
                pending.cancelAndJoin()
            }
        }
    }

    @Test
    fun `unsupported API remains discoverable and install is rejected before manager starts`() = runBlocking {
        MockWebServer().also { it.start() }.use { server ->
            val unsupported = mihon.domain.extension.model.EXTENSION_LIB_VERSION_MAX + 0.1
            server.enqueue(MockResponse(body = V2_INDEX_JSON.replace("\"extensionLib\": \"1.6\"", "\"extensionLib\": \"$unsupported\"")))
            val api = api(repository(server, "repo"))
            val extensions = api.findAvailableExtensions()
            assertEquals(1, extensions.size)
            val manager = io.mockk.mockk<DesktopExtensionPresentationService>(relaxed = true)
            io.mockk.every { manager.extensionsDirectory } returns tempDir.toFile()
            val result = api.beginInstall(extensions.single(), manager)
            assertInstanceOf(DesktopExtensionInstallStart.Rejected::class.java, result)
            io.mockk.verify(exactly = 0) { manager.installExtensionStates(any(), any()) }
        }
    }

    @Test
    fun `Desktop production API prefers signed JVM artifact from repository v2 index`() = runBlocking {
        MockWebServer().also { it.start() }.use { server ->
            val indexUrl = server.url("/index.pb")
            server.enqueue(
                MockResponse(
                    body = """{"index_v2":"$indexUrl",
                        "meta":{"name":"repo",
                        "shortName":"R",
                        "website":"https://repo.example",
                        "signingKeyFingerprint":"repo-fingerprint"}}""",
                ),
            )
            server.enqueue(MockResponse(body = V2_INDEX_JSON))

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
    fun `Desktop production API follows the remote list while retaining JAR preference`() = runBlocking {
        MockWebServer().also { it.start() }.use { server ->
            val listUrl = server.url("/extensions.pb").toString()
            server.enqueue(
                MockResponse(
                    body = V2_INDEX_JSON.replace(
                        "\"extensionList\": {",
                        "\"extensionListUrl\": \"$listUrl\",\n              \"extensionList\": {",
                    ),
                ),
            )
            server.enqueue(MockResponse(body = REMOTE_EXTENSION_LIST_JSON))

            val catalog = api(repository(server, "repo").copy(fingerprint = "repo-fingerprint"))
                .refreshCatalog()

            assertEquals("https://repo.example/jar/example.jar", catalog.entries.single().artifact.downloadUrl)
            assertEquals(listOf("/repo.json", "/extensions.pb"), listOf(
                server.takeRequest().url.encodedPath,
                server.takeRequest().url.encodedPath,
            ))
        }
    }

    @Test
    fun `Desktop production API rejects v2 index whose signing key differs from trusted repository`(): Unit = runBlocking {
        MockWebServer().also { it.start() }.use { server ->
            val indexUrl = server.url("/index.pb")
            server.enqueue(
                MockResponse(
                    body = """{"index_v2":"$indexUrl",
                        "meta":{"name":"repo",
                        "shortName":"R",
                        "website":"https://repo.example",
                        "signingKeyFingerprint":"trusted-fingerprint"}}""",
                ),
            )
            server.enqueue(MockResponse(body = V2_INDEX_JSON.replace("repo-fingerprint", "other-fingerprint")))
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

    private suspend fun api(vararg repositories: TestRepository, client: OkHttpClient = OkHttpClient()): DesktopExtensionApi {
        val repository = FakeExtensionRepoRepository()
        repositories.forEach {
            repository.insertRepo(it.baseUrl, it.name, it.name, it.baseUrl, it.fingerprint)
        }
        return DesktopExtensionApi(
            client = client,
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
        body = """{"meta":{"name":"$name",
            "shortName":"$name",
            "website":"https://$name.example",
            "signingKeyFingerprint":"$name-fingerprint"}}""",
    )

    private companion object {
        val V2_INDEX_JSON = """
            {
              "name": "repo",
              "badgeLabel": "R",
              "signingKey": "repo-fingerprint",
              "contact": {"website": "https://repo.example", "discord": null},
              "extensionList": {"extensions": [{
                "name": "Example",
                "packageName": "eu.kanade.example",
                "resources": {
                  "apkUrl": "https://repo.example/apk/example.apk",
                  "iconUrl": "https://repo.example/icon.png",
                  "jarUrl": "https://repo.example/jar/example.jar"
                },
                "extensionLib": "1.6",
                "versionCode": 160,
                "versionName": "1.6.0",
                "contentWarning": "CONTENT_WARNING_SAFE",
                "sources": [{"id": 1, "name": "Example Source", "language": "en", "homeUrl": "https://source.example"}]
              }]}
            }
        """.trimIndent()
        const val INDEX_JSON =
            """[{"name":"Tachiyomi: Example",
                "pkg":"eu.kanade.tachiyomi.extension.en.example",
                "apk":"example.apk",
                "lang":"en",
                "code":42,
                "version":"1.4.7",
                "nsfw":0,
                "sha256":"0123456789abcdef",
                "sources":[{"id":7,
                "lang":"en",
                "name":"Example Source",
                "baseUrl":"https://source.example"}]}]"""

        const val REMOTE_EXTENSION_LIST_JSON =
            """{"extensions":[{"name":"Example",
                "packageName":"eu.kanade.example",
                "resources":{"apkUrl":"https://repo.example/apk/example.apk",
                "iconUrl":"https://repo.example/icon.png",
                "jarUrl":"https://repo.example/jar/example.jar"},
                "extensionLib":"1.6",
                "versionCode":160,
                "versionName":"1.6.0",
                "contentWarning":"CONTENT_WARNING_SAFE",
                "sources":[{"id":1,
                "name":"Example Source",
                "language":"en",
                "homeUrl":"https://source.example"}]}]}"""
    }
}
