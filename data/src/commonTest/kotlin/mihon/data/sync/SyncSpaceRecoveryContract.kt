package mihon.data.sync

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import mihon.data.sync.crypto.SyncAeadEngineFactory
import mihon.data.sync.runtime.SyncPanelAction
import mihon.data.sync.runtime.SyncPanelController
import mihon.data.sync.runtime.SyncPanelPage
import mihon.data.sync.runtime.SyncSetupStep
import mihon.data.sync.runtime.SyncSpaceRecoveryReason
import mihon.domain.sync.crypto.SyncCryptoBinding
import mihon.domain.sync.crypto.SyncSpaceDescriptorCodec
import mihon.domain.sync.crypto.SyncSpacePayload
import mihon.domain.sync.crypto.SyncSpacePayloadCodec
import mihon.domain.sync.runtime.SyncRunProblem
import mihon.domain.sync.runtime.SyncRunStatus
import mihon.domain.sync.runtime.SyncTrigger
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.Mangas
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

abstract class SyncSpaceRecoveryContract {
    protected abstract fun open(): SyncRuntimeStorageContract.Storage

    @Test
    fun `required repository 404 gates restart and all triggers without losing local data`() = runBlocking {
        open().use { storage ->
            SyncOnboardingFixture(storage).use { setup ->
                setup.existing("")
                setup.authorize()
                setup.begin()
                storage.favorite("/preserved-local-change")
                val credential = setup.runtime.credentials.read()
                val binding = setup.runtime.connection()
                val delegate = setup.git.server.dispatcher
                setup.git.server.dispatcher = failing(delegate, "/repos/${setup.repository.fullName}", 404)
                val result = setup.runtime.coordinator.synchronize(SyncTrigger.MANUAL)
                assertEquals(SyncRunProblem.SPACE_UNAVAILABLE, result.problem)
                setup.panel.act(SyncPanelAction.Open)
                assertEquals(SyncSpaceRecoveryReason.SPACE_UNAVAILABLE, setup.panel.state.value.recovery?.reason)
                val run = setup.panel.state.value.run
                val requests = setup.git.server.requestCount
                val restarted = setup.runtime()
                try {
                    val triggers = listOf(
                        SyncTrigger.STARTUP,
                        SyncTrigger.PERIODIC,
                        SyncTrigger.RECOVERY,
                        SyncTrigger.MANUAL,
                    )
                    for (trigger in triggers) {
                        assertEquals(SyncRunStatus.SKIPPED, restarted.coordinator.synchronize(trigger).status)
                    }
                    (restarted.panel as SyncPanelController).act(SyncPanelAction.Open)
                    assertEquals(run?.runId, restarted.panel.state.value.run?.runId)
                    assertEquals(
                        SyncSpaceRecoveryReason.SPACE_UNAVAILABLE,
                        restarted.panel.state.value.recovery?.reason,
                    )
                    assertEquals(requests, setup.git.server.requestCount)
                    assertEquals(credential, restarted.credentials.read())
                    assertEquals(binding, restarted.connection())
                    assertEquals(1L, restarted.panel.state.value.queuedTotal)
                    assertEquals(0L, restarted.panel.state.value.nextSyncAtMillis)
                    setup.git.server.dispatcher = delegate
                    (restarted.panel as SyncPanelController).act(SyncPanelAction.RecheckSpace)
                    (restarted.panel as SyncPanelController).awaitRecoveryIdle()
                    assertNull(restarted.panel.state.value.recovery)
                    assertEquals(run?.runId, restarted.panel.state.value.run?.runId, "recheck is read-only")
                    assertEquals(1L, restarted.panel.state.value.queuedTotal)
                } finally {
                    restarted.stopPanel()
                }
            }
        }
    }

    @Test
    fun `connected snapshot ref 404 is recovery while transient server and rate failures are not`() = runBlocking {
        for ((code, rateLimited, expected) in listOf(
            Triple(404, false, SyncSpaceRecoveryReason.SPACE_DATA_INVALID),
            Triple(401, false, SyncSpaceRecoveryReason.AUTHORIZATION_REQUIRED),
            Triple(403, false, SyncSpaceRecoveryReason.SPACE_UNAVAILABLE),
            Triple(403, true, null),
            Triple(429, false, null),
            Triple(500, false, null),
        )) {
            open().use { storage ->
                SyncOnboardingFixture(storage).use { setup ->
                    setup.existing("")
                    setup.authorize()
                    setup.begin()
                    val delegate = setup.git.server.dispatcher
                    setup.git.server.dispatcher = failing(
                        delegate,
                        "/repos/${setup.repository.fullName}/git/ref/heads/${setup.repository.branch}",
                        code,
                        rateLimited,
                    )
                    val result = setup.runtime.coordinator.synchronize(SyncTrigger.MANUAL)
                    setup.panel.act(SyncPanelAction.Open)
                    assertEquals(expected, setup.panel.state.value.recovery?.reason, "$code rate=$rateLimited")
                    if (expected == null) {
                        assertEquals(SyncRunProblem.NETWORK, result.problem)
                    } else {
                        assertNotNull(setup.panel.state.value.recovery)
                    }
                }
            }
        }
    }

    @Test
    fun `connected setup resume checks inaccessible old binding without creating or deleting anything`() = runBlocking {
        open().use { storage ->
            SyncOnboardingFixture(storage).use { setup ->
                storage.favorite("/preserved-import")
                setup.runtime.preferences.importPaused.set(true)
                setup.existing("")
                setup.authorize()
                setup.begin()
                val binding = setup.runtime.connection()
                val credential = setup.runtime.credentials.read()
                val pending = requireNotNull(setup.runtime.onboarding.storage.pending(1L))
                val writes = setup.repositoryWrites
                val delegate = setup.git.server.dispatcher
                setup.git.server.dispatcher = failing(delegate, "/repos/${setup.repository.fullName}", 404)
                setup.panel.act(SyncPanelAction.BeginSetup)
                withTimeout(5_000) {
                    setup.panel.state.first { !it.setupBusy && it.recovery != null }
                }
                assertEquals(SyncSpaceRecoveryReason.SPACE_UNAVAILABLE, setup.panel.state.value.recovery?.reason)
                assertEquals(binding, setup.runtime.connection())
                assertEquals(credential, setup.runtime.credentials.read())
                assertEquals(pending, setup.runtime.onboarding.storage.pending(1L))
                assertEquals(writes, setup.repositoryWrites)
            }
        }
    }

    @Test
    fun `malformed and future recovery records gate runs and network`() = runBlocking {
        open().use { storage ->
            SyncOnboardingFixture(storage).use { setup ->
                setup.existing("")
                setup.authorize()
                setup.begin()
                val delegate = setup.git.server.dispatcher
                setup.git.server.dispatcher = failing(delegate, "/repos/${setup.repository.fullName}", 404)
                setup.runtime.coordinator.synchronize(SyncTrigger.MANUAL)
                val key = setup.secure.values.keys.single { it.endsWith("-recovery") }
                val connection = requireNotNull(setup.runtime.connection())
                val run = setup.runtime.runStore.latest(connection.spaceId, connection.generation)
                val requests = setup.git.server.requestCount
                for (record in listOf("{}", "{\"version\":999}")) {
                    setup.secure.values[key] = record
                    val restarted = setup.runtime()
                    try {
                        assertEquals(
                            SyncRunStatus.SKIPPED,
                            restarted.coordinator.synchronize(SyncTrigger.PERIODIC).status,
                        )
                        assertEquals(
                            run?.runId,
                            restarted.runStore.latest(connection.spaceId, connection.generation)?.runId,
                        )
                        assertEquals(requests, setup.git.server.requestCount)
                        assertEquals(false, restarted.hasResumableRun())
                    } finally {
                        restarted.stopPanel()
                    }
                }
            }
        }
    }

    @Test
    fun `explicit recheck does not overwrite future recovery record`() = runBlocking {
        open().use { storage ->
            SyncOnboardingFixture(storage).use { setup ->
                setup.existing("")
                setup.authorize()
                setup.begin()
                val delegate = setup.git.server.dispatcher
                setup.git.server.dispatcher = failing(delegate, "/repos/${setup.repository.fullName}", 404)
                setup.runtime.coordinator.synchronize(SyncTrigger.MANUAL)
                val key = setup.secure.values.keys.single { it.endsWith("-recovery") }
                val future = "{\"version\":999,\"future\":\"preserved\"}"
                setup.secure.values[key] = future
                val checked = setup.runtime.recheckSpace()
                assertEquals(future, setup.secure.values[key])
                assertEquals(SyncRunProblem.STORAGE, checked.problem)
            }
        }
    }

    @Test
    fun `verified connected descriptor corruption is durable space data recovery`() = runBlocking {
        open().use { storage ->
            SyncOnboardingFixture(storage).use { setup ->
                setup.existing("")
                setup.authorize()
                setup.begin()
                val binding = setup.runtime.connection()
                val writes = setup.repositoryWrites
                setup.git.replaceFile(
                    setup.repository.branch,
                    SyncSpaceDescriptorCodec.PATH,
                    "{}".encodeToByteArray(),
                )
                setup.runtime.coordinator.synchronize(SyncTrigger.MANUAL)
                setup.panel.act(SyncPanelAction.Open)
                assertEquals(SyncSpaceRecoveryReason.SPACE_DATA_INVALID, setup.panel.state.value.recovery?.reason)
                assertEquals(binding, setup.runtime.connection())
                assertEquals(writes, setup.repositoryWrites)
            }
        }
    }

    @Test
    fun `late recheck cannot navigate back after user returns to main`() = runBlocking {
        open().use { storage ->
            SyncOnboardingFixture(storage).use { setup ->
                setup.existing("")
                setup.authorize()
                setup.begin()
                val delegate = setup.git.server.dispatcher
                val inaccessible = failing(delegate, "/repos/${setup.repository.fullName}", 404)
                setup.git.server.dispatcher = inaccessible
                setup.runtime.coordinator.synchronize(SyncTrigger.MANUAL)
                setup.panel.act(SyncPanelAction.OpenRecovery)
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                val once = AtomicBoolean()
                setup.git.server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        if (request.url.encodedPath == "/user" && once.compareAndSet(false, true)) {
                            entered.countDown()
                            check(release.await(5, TimeUnit.SECONDS))
                        }
                        return inaccessible.dispatch(request)
                    }
                }
                try {
                    setup.panel.act(SyncPanelAction.RecheckSpace)
                    assertTrue(entered.await(5, TimeUnit.SECONDS))
                    setup.panel.act(SyncPanelAction.Back)
                    release.countDown()
                    setup.panel.awaitRecoveryIdle()
                    assertEquals(SyncPanelPage.MAIN, setup.panel.state.value.page)
                    assertNotNull(setup.panel.state.value.recovery)
                } finally {
                    release.countDown()
                }
            }
        }
    }

    @Test
    fun `late recheck cannot replace an in progress authorization page`() = runBlocking {
        open().use { storage ->
            SyncOnboardingFixture(storage).use { setup ->
                setup.existing("")
                setup.authorize()
                setup.begin()
                val inaccessible = failing(setup.git.server.dispatcher, "/repos/${setup.repository.fullName}", 404)
                setup.git.server.dispatcher = inaccessible
                setup.runtime.coordinator.synchronize(SyncTrigger.MANUAL)
                setup.panel.act(SyncPanelAction.OpenRecovery)
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                val releaseToken = CountDownLatch(1)
                val once = AtomicBoolean()
                setup.git.server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        if (request.url.encodedPath == "/user" && once.compareAndSet(false, true)) {
                            entered.countDown()
                            check(release.await(5, TimeUnit.SECONDS))
                        }
                        if (request.url.encodedPath == "/token") {
                            check(releaseToken.await(5, TimeUnit.SECONDS))
                        }
                        return inaccessible.dispatch(request)
                    }
                }
                try {
                    setup.panel.act(SyncPanelAction.RecheckSpace)
                    assertTrue(entered.await(5, TimeUnit.SECONDS))
                    setup.panel.act(SyncPanelAction.CheckAuthorization)
                    withTimeout(5_000) { setup.panel.state.first { it.deviceCode != null } }
                    release.countDown()
                    setup.panel.awaitRecoveryIdle()
                    assertEquals(SyncPanelPage.SETUP, setup.panel.state.value.page)
                    assertEquals(SyncSetupStep.SIGN_IN, setup.panel.state.value.setupStep)
                    assertNotNull(setup.panel.state.value.deviceCode)
                    assertNotNull(setup.panel.state.value.recovery)
                } finally {
                    setup.panel.act(SyncPanelAction.Close)
                    release.countDown()
                    releaseToken.countDown()
                }
            }
        }
    }

    @Test
    fun `authenticated index identity mismatch is explicit durable data recovery`() = runBlocking {
        open().use { storage ->
            SyncOnboardingFixture(storage).use { setup ->
                val material = setup.existing("")
                setup.authorize()
                setup.begin()
                val path = ".mihon-sync/index/bootstrap/0/bootstrap.bin"
                val binding = SyncCryptoBinding(1, "space", 1, "bootstrap", path)
                val engine = SyncAeadEngineFactory.create()
                val plaintext = SyncSpacePayloadCodec.decode(
                    engine,
                    material,
                    binding,
                    SyncSpacePayload(requireNotNull(setup.git.file(setup.repository.branch, path))),
                )
                val original = Json.parseToJsonElement(plaintext.decodeToString())
                    .jsonObject
                val wrongIdentity = JsonObject(
                    original + ("actorId" to JsonPrimitive("other-actor")),
                )
                val payload = SyncSpacePayloadCodec.encode(
                    engine,
                    material,
                    binding,
                    wrongIdentity.toString().encodeToByteArray(),
                )
                setup.git.replaceFile(setup.repository.branch, path, payload.bytes)
                val result = setup.runtime.coordinator.synchronize(SyncTrigger.MANUAL)
                setup.panel.act(SyncPanelAction.Open)
                assertEquals(SyncRunProblem.INVALID_DATA, result.problem)
                assertEquals(SyncSpaceRecoveryReason.SPACE_DATA_INVALID, setup.panel.state.value.recovery?.reason)
            }
        }
    }

    private fun failing(delegate: Dispatcher, path: String, code: Int, rateLimited: Boolean = false) =
        object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.url.encodedPath == path) {
                    MockResponse.Builder().code(code).body("{}").apply {
                        if (rateLimited) addHeader("x-ratelimit-remaining", "0")
                    }.build()
                } else {
                    delegate.dispatch(request)
                }
        }

    protected fun database(driver: SqlDriver): Database = Database(
        driver,
        History.Adapter(DateColumnAdapter),
        Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
    ).also { Database.Schema.create(driver) }
}
