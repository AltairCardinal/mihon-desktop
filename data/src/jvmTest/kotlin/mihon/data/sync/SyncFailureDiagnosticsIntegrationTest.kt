package mihon.data.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import mihon.data.sync.auth.GitHubSyncSpaceClient
import mihon.data.sync.auth.SyncDiscoveryProblem
import mihon.data.sync.auth.SyncSpaceDiscovery
import mihon.data.sync.http.SyncFailureDiagnostics
import mihon.data.sync.http.SyncFailurePhase
import mihon.data.sync.http.SyncHttpClient
import mihon.data.sync.runtime.StoredSyncMaterial
import mihon.data.sync.runtime.StoredSyncSetup
import mihon.data.sync.runtime.SyncSetupException
import mihon.domain.sync.transport.SyncInitializationResult
import mihon.domain.sync.transport.SyncInitializationStage
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.JvmDatabaseHandler
import tachiyomi.data.Mangas
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PrintStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class SyncFailureDiagnosticsIntegrationTest {
    @Test
    fun `initialization reason classification never emits an unknown reason`() = runBlocking {
        for ((reason, category) in listOf(
            "confirmed bootstrap changed" to "BOOTSTRAP_CHANGED",
            "repository default branch changed" to "DEFAULT_BRANCH_CHANGED",
            "repository identity changed" to "REPOSITORY_CHANGED",
            SECRET to "OTHER",
        )) {
            val output = capture {
                SyncFailureDiagnostics.record(
                    SyncFailurePhase.RESUME_INITIALIZE_RESULT,
                    result = mihon.domain.sync.transport.SyncInitializationResult.NeedsExplicitAction(reason),
                )
            }
            assertTrue(output.contains("reason=$category"), output)
            assertFalse(output.contains(reason), output)
        }
    }

    @Test
    fun `real resume separates repository and snapshot errors from unfinished initialization`() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        val database = Database(
            driver,
            History.Adapter(DateColumnAdapter),
            Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
        )
        SyncRuntimeStorageContract.Storage(driver, JvmDatabaseHandler(database, driver)).use { storage ->
            SyncOnboardingFixture(storage).use { setup ->
                val material = setup.existing("")
                setup.authorize(SECRET)
                var saved = StoredSyncSetup(
                    accountId = setup.accountId,
                    accountLogin = setup.accountLogin,
                    attemptId = "diagnostic-resume-attempt",
                    attemptNonce = "diagnostic-resume-attempt",
                    newSpace = false,
                    material = StoredSyncMaterial.from(material),
                    stage = SyncInitializationStage.CONNECTED,
                    repositoryId = 99,
                    owner = setup.repository.owner,
                    repository = setup.repository.name,
                    branch = setup.repository.branch,
                )
                setup.runtime.onboarding.storage.save(saved, null)
                val original = setup.git.server.dispatcher
                for ((path, phase) in listOf(
                    "/repos/${setup.repository.fullName}" to "RESUME_VERIFY_REPOSITORY",
                    "/repos/${setup.repository.fullName}/git/ref/heads/${setup.repository.branch}" to
                        "RESUME_READ_SNAPSHOT",
                )) {
                    setup.git.server.dispatcher = object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse =
                            if (request.url.encodedPath == path) {
                                MockResponse(code = 404, body = SECRET)
                            } else {
                                original.dispatch(request)
                            }
                    }
                    val output = capture {
                        val failure = runCatching { setup.runtime.onboarding.resume(saved) }.exceptionOrNull()
                        assertEquals(404, (failure as mihon.data.sync.http.SyncHttpException).code)
                    }
                    assertTrue(output.contains("phase=$phase class=HTTP status=404"), output)
                    assertTrue(output.contains("newSpace=false stage=CONNECTED"), output)
                    assertFalse(output.contains(SECRET), output)
                    assertFalse(output.contains(path), output)
                }
                setup.git.server.dispatcher = original
                val initializing = saved.copy(
                    stage = SyncInitializationStage.SPACE_CONFIRMED,
                    newSpace = true,
                    defaultBranch = "main",
                    confirmedBootstrapCommitSha = "a".repeat(40),
                    confirmedBootstrapTreeSha = "b".repeat(40),
                )
                setup.runtime.onboarding.storage.save(initializing, saved)
                saved = initializing
                val output = capture {
                    val failure = runCatching { setup.runtime.onboarding.resume(saved) }.exceptionOrNull()
                    assertEquals(SyncDiscoveryProblem.RETRYABLE, (failure as SyncSetupException).problem)
                }
                assertTrue(output.contains("phase=RESUME_INITIALIZE_RESULT"), output)
                assertTrue(output.contains("result=NEEDS_EXPLICIT_ACTION"), output)
                assertTrue(output.contains("reason=BOOTSTRAP_CHANGED"), output)
                assertTrue(output.contains("newSpace=true stage=SPACE_CONFIRMED"), output)
                assertFalse(output.contains(SECRET), output)
                assertFalse(output.contains("confirmed bootstrap"), output)
                val connected = saved.copy(stage = SyncInitializationStage.CONNECTED)
                setup.runtime.onboarding.storage.save(connected, saved)
                val connectedOutput = capture { setup.runtime.onboarding.resume(connected) }
                assertFalse(connectedOutput.contains("phase=RESUME_INITIALIZE"), connectedOutput)
                assertFalse(connectedOutput.contains(SECRET), connectedOutput)
            }
        }
    }

    @Test
    fun `pending setup identifies real account HTTP failure and local secure store failure`() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        val database = Database(
            driver,
            History.Adapter(DateColumnAdapter),
            Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
        )
        SyncRuntimeStorageContract.Storage(driver, JvmDatabaseHandler(database, driver)).use { storage ->
            SyncOnboardingFixture(storage).use { setup ->
                setup.authorize(SECRET)
                setup.git.server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest) = MockResponse(code = 503, body = SECRET)
                }
                val accountOutput = capture {
                    assertTrue(runCatching { setup.runtime.onboarding.pendingForCurrentAccount() }.isFailure)
                }
                assertTrue(accountOutput.contains("phase=PENDING_ACCOUNT class=HTTP status=503"), accountOutput)
                assertFalse(accountOutput.contains(SECRET), accountOutput)
                setup.secure.fail = true
                val localOutput = capture {
                    assertTrue(runCatching { setup.runtime.onboarding.pendingForCurrentAccount() }.isFailure)
                }
                assertTrue(localOutput.contains("phase=PENDING_ACCOUNT class=LOCAL"), localOutput)
                assertFalse(localOutput.contains(SECRET), localOutput)
            }
        }
    }

    @Test
    fun `broken diagnostic output does not replace discovery result`() = runBlocking {
        val original = System.out
        val broken = object : PrintStream(ByteArrayOutputStream()) {
            override fun println(value: String?) = throw IOException(SECRET)
        }
        val production = OkHttpClient()
        try {
            System.setOut(broken)
            val result = GitHubSyncSpaceClient(production, { throw IllegalArgumentException(SECRET) }).discover()
            assertEquals(SyncSpaceDiscovery.Failed(SyncDiscoveryProblem.MALFORMED), result)
        } finally {
            System.setOut(original)
            broken.close()
            production.connectionPool.evictAll()
            production.dispatcher.executorService.shutdown()
        }
    }

    @Test
    fun `production HTTP callback logs allowlisted original failure without hostile message`() = runBlocking {
        for ((category, failure) in listOf(
            "DNS" to UnknownHostException(SECRET),
            "CONNECT" to ConnectException(SECRET),
            "TLS" to SSLHandshakeException(SECRET),
            "TIMEOUT" to SocketTimeoutException(SECRET),
            "IO" to IOException(SECRET),
        )) {
            val production = OkHttpClient.Builder().dns { throw failure }.build()
            try {
                val http = SyncHttpClient(production, setOf("failure.test"))
                val output = capture {
                    assertTrue(
                        runCatching {
                            http.execute(http.request("https://failure.test/private", "GET"))
                        }.isFailure,
                    )
                }
                assertTrue(output.contains("phase=HTTP_CALL class=$category"), output)
                assertFalse(output.contains(SECRET), output)
                assertFalse(output.contains("failure.test"), output)
                assertFalse(output.contains("/private"), output)
            } finally {
                production.connectionPool.evictAll()
                production.dispatcher.executorService.shutdown()
            }
        }
    }

    @Test
    fun `real discovery logs HTTP status before normalization without credentials or response content`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse(code = 503, body = SECRET))
            val production = OkHttpClient()
            try {
                val output = capture {
                    val result = GitHubSyncSpaceClient(production, { SECRET }, server.url("/").toString()).discover()
                    assertEquals(SyncSpaceDiscovery.Failed(SyncDiscoveryProblem.RETRYABLE), result)
                }
                assertTrue(output.contains("phase=HTTP_RESPONSE class=HTTP status=503"), output)
                assertTrue(output.contains("phase=DISCOVERY class=DISCOVERY"), output)
                assertFalse(output.contains(SECRET), output)
                assertFalse(output.contains(server.url("/").toString()), output)
                assertFalse(output.contains("/user"), output)
            } finally {
                production.connectionPool.evictAll()
                production.dispatcher.executorService.shutdown()
            }
        }
    }

    @Test
    fun `real discovery identifies parse failure without emitting malformed body`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse(body = SECRET))
            val production = OkHttpClient()
            try {
                val output = capture {
                    val result = GitHubSyncSpaceClient(production, { SECRET }, server.url("/").toString()).discover()
                    assertEquals(SyncSpaceDiscovery.Failed(SyncDiscoveryProblem.MALFORMED), result)
                }
                assertTrue(output.contains("phase=DISCOVERY class=PARSE"), output)
                assertFalse(output.contains(SECRET), output)
            } finally {
                production.connectionPool.evictAll()
                production.dispatcher.executorService.shutdown()
            }
        }
    }

    private suspend fun capture(block: suspend () -> Unit): String {
        val original = System.out
        val bytes = ByteArrayOutputStream()
        val output = PrintStream(bytes, true, Charsets.UTF_8)
        try {
            System.setOut(output)
            block()
            return bytes.toString(Charsets.UTF_8)
        } finally {
            System.setOut(original)
            output.close()
        }
    }

    private companion object {
        const val SECRET = "hostile-token account-owner Authorization: Bearer secret /repos/private response-body"
    }
}
