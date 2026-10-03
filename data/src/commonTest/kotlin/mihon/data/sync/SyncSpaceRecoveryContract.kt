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
import mihon.data.sync.runtime.SyncPanelQuestion
import mihon.data.sync.runtime.SyncRecoveryAuthorization
import mihon.data.sync.runtime.SyncRecoveryContinuation
import mihon.data.sync.runtime.SyncSetupStep
import mihon.data.sync.runtime.SyncSpaceRecoveryReason
import mihon.data.sync.runtime.SyncSpaceSwitchStage
import mihon.domain.sync.auth.GitHubAccessToken
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
    fun `generic setup error opens neutral recovery and back preserves source`() = runBlocking {
        open().use { storage ->
            SyncOnboardingFixture(storage).use { setup ->
                setup.existing("")
                setup.authorize()
                setup.begin()
                val binding = setup.runtime.connection()
                val credential = setup.runtime.credentials.read()
                setup.git.server.dispatcher = failing(setup.git.server.dispatcher, "/user", 500)
                setup.panel.act(SyncPanelAction.BeginSetup)
                withTimeout(5_000) { setup.panel.state.first { it.setupStep == SyncSetupStep.ERROR } }
                val problem = setup.panel.state.value.setupProblem
                assertTrue(setup.panel.state.value.canChangeSpace)
                setup.panel.act(SyncPanelAction.OpenRecovery)
                assertEquals(SyncPanelPage.RECOVERY, setup.panel.state.value.page)
                assertEquals(SyncPanelPage.SETUP, setup.panel.state.value.recoveryReturnPage)
                assertNull(setup.panel.state.value.recovery, "network failure is not proof of a deleted space")
                assertNull(setup.runtime.activeSwitch())
                assertEquals(binding, setup.runtime.connection())
                assertEquals(credential, setup.runtime.credentials.read())
                setup.panel.act(SyncPanelAction.Back)
                assertEquals(SyncPanelPage.SETUP, setup.panel.state.value.page)
                assertEquals(SyncSetupStep.ERROR, setup.panel.state.value.setupStep)
                assertEquals(problem, setup.panel.state.value.setupProblem)
                setup.panel.act(SyncPanelAction.Navigate(SyncPanelPage.DIAGNOSTICS))
                setup.panel.act(SyncPanelAction.Back)
                assertEquals(SyncPanelPage.SETUP, setup.panel.state.value.page)
                assertEquals(SyncSetupStep.ERROR, setup.panel.state.value.setupStep)
                assertEquals(problem, setup.panel.state.value.setupProblem)
            }
        }
    }

    @Test
    fun `setup account authorization failure is verified as recovery while keeping error page`() = runBlocking {
        open().use { storage ->
            SyncOnboardingFixture(storage).use { setup ->
                setup.existing("")
                setup.authorize()
                setup.begin()
                val binding = setup.runtime.connection()
                val credential = setup.runtime.credentials.read()
                setup.git.server.dispatcher = failing(setup.git.server.dispatcher, "/user", 401)
                setup.panel.act(SyncPanelAction.BeginSetup)
                withTimeout(5_000) { setup.panel.state.first { it.setupStep == SyncSetupStep.ERROR } }
                setup.panel.awaitRecoveryIdle()
                assertEquals(SyncSpaceRecoveryReason.AUTHORIZATION_REQUIRED, setup.panel.state.value.recovery?.reason)
                assertEquals(SyncPanelPage.SETUP, setup.panel.state.value.page)
                assertEquals(binding, setup.runtime.connection())
                assertEquals(credential, setup.runtime.credentials.read())
                assertNull(setup.runtime.activeSwitch())
            }
        }
    }

    @Test
    fun `setup error diagnostics returns to the same failure page`() = runBlocking {
        open().use { storage ->
            SyncOnboardingFixture(storage).use { setup ->
                setup.existing("")
                setup.authorize()
                setup.begin()
                setup.git.server.dispatcher = failing(setup.git.server.dispatcher, "/user", 500)
                setup.panel.act(SyncPanelAction.BeginSetup)
                withTimeout(5_000) { setup.panel.state.first { it.setupStep == SyncSetupStep.ERROR } }
                val problem = setup.panel.state.value.setupProblem
                setup.panel.act(SyncPanelAction.Navigate(SyncPanelPage.DIAGNOSTICS))
                setup.panel.act(SyncPanelAction.Back)
                assertEquals(SyncPanelPage.SETUP, setup.panel.state.value.page)
                assertEquals(SyncSetupStep.ERROR, setup.panel.state.value.setupStep)
                assertEquals(problem, setup.panel.state.value.setupProblem)
            }
        }
    }

    @Test
    fun `retrying failed setup records another failure and does not restart a busy request`() = runBlocking {
        open().use { storage ->
            SyncOnboardingFixture(storage).use { setup ->
                setup.existing("")
                setup.authorize()
                setup.begin()
                val delegate = setup.git.server.dispatcher
                setup.git.server.dispatcher = failing(delegate, "/user", 500)
                setup.panel.act(SyncPanelAction.BeginSetup)
                withTimeout(5_000) { setup.panel.state.first { it.setupStep == SyncSetupStep.ERROR } }
                assertEquals(false, setup.panel.state.value.setupRetryAttempted)
                assertEquals(false, setup.panel.state.value.setupRetryFailed)
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                val requests = java.util.concurrent.atomic.AtomicInteger()
                setup.git.server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        if (request.url.encodedPath == "/user") {
                            requests.incrementAndGet()
                            entered.countDown()
                            check(release.await(5, TimeUnit.SECONDS))
                            return MockResponse(code = 500, body = "{}")
                        }
                        return delegate.dispatch(request)
                    }
                }
                try {
                    setup.panel.act(SyncPanelAction.RetrySetup)
                    assertTrue(entered.await(5, TimeUnit.SECONDS))
                    setup.panel.act(SyncPanelAction.RetrySetup)
                    assertEquals(1, requests.get())
                    release.countDown()
                    withTimeout(5_000) {
                        setup.panel.state.first { it.setupStep == SyncSetupStep.ERROR && !it.setupBusy }
                    }
                    assertTrue(setup.panel.state.value.setupRetryAttempted)
                    assertTrue(setup.panel.state.value.setupRetryFailed)
                } finally {
                    release.countDown()
                }
            }
        }
    }

    @Test
    fun `recovery entry without binding returns to first setup`() = runBlocking {
        open().use { storage ->
            SyncOnboardingFixture(storage).use { setup ->
                setup.panel.act(SyncPanelAction.Open)
                setup.panel.act(SyncPanelAction.OpenRecovery)
                assertEquals(SyncPanelPage.SETUP, setup.panel.state.value.page)
                assertEquals(SyncSetupStep.SIGN_IN, setup.panel.state.value.setupStep)
                assertEquals(false, setup.panel.state.value.canChangeSpace)
            }
        }
    }

    @Test
    fun `unreadable or unsupported binding cannot open executable recovery choices`() = runBlocking {
        open().use { storage ->
            SyncOnboardingFixture(storage).use { setup ->
                setup.existing("")
                setup.authorize()
                setup.begin()
                val key = setup.secure.values.keys.single {
                    it.startsWith("space-") && !it.contains("-recovery") && !it.contains("-address")
                }
                val binding = requireNotNull(setup.secure.values[key])
                for (value in listOf("{}", "{\"version\":999}", "{\"version\":2}")) {
                    setup.secure.values[key] = value
                    setup.panel.act(SyncPanelAction.Open)
                    setup.panel.act(SyncPanelAction.OpenRecovery)
                    assertEquals(false, setup.panel.state.value.canChangeSpace)
                    assertEquals(false, setup.panel.state.value.page == SyncPanelPage.RECOVERY)
                }
                setup.secure.values[key] = binding
                setup.runtime.credentials.clear()
                setup.panel.act(SyncPanelAction.Open)
                setup.panel.act(SyncPanelAction.OpenRecovery)
                assertEquals(false, setup.panel.state.value.canChangeSpace)
                assertEquals(
                    SyncPanelPage.SETUP,
                    setup.panel.state.value.page,
                    "missing credential cannot open an empty chooser",
                )
                assertEquals(SyncSetupStep.ERROR, setup.panel.state.value.setupStep)
                setup.authorize()
                setup.secure.readFailure = true
                setup.panel.act(SyncPanelAction.OpenRecovery)
                assertEquals(false, setup.panel.state.value.canChangeSpace)
                assertEquals(SyncPanelPage.SETUP, setup.panel.state.value.page)
                assertEquals(SyncRunProblem.STORAGE, setup.panel.state.value.problem)
                setup.secure.readFailure = false
            }
        }
    }

    @Test
    fun `cold recovery read failure does not assume a first configuration`() = runBlocking {
        open().use { storage ->
            val unreadable = object : tachiyomi.data.DatabaseHandler by storage.handler {
                override suspend fun <T> await(inTransaction: Boolean, block: suspend Database.() -> T): T {
                    throw mihon.domain.sync.security.SyncSecureStoreException()
                }
            }
            val cold = SyncRuntimeStorageContract.Storage(storage.driver, unreadable)
            SyncOnboardingFixture(cold).use { setup ->
                setup.panel.act(SyncPanelAction.Open)
                assertNull(setup.panel.state.value.connection)
                setup.panel.act(SyncPanelAction.OpenRecovery)
                assertEquals(
                    SyncPanelPage.SETUP,
                    setup.panel.state.value.page,
                    "cold storage failure is not first setup",
                )
                assertEquals(SyncSetupStep.ERROR, setup.panel.state.value.setupStep)
                assertEquals(false, setup.panel.state.value.canChangeSpace)
                assertEquals(SyncRunProblem.STORAGE, setup.panel.state.value.problem)
                assertEquals(0, setup.git.server.requestCount)
            }
        }
    }

    @Test
    fun `replacement authorization validates real account before replacing original credential`() = runBlocking {
        open().use { storage ->
            SyncOnboardingFixture(storage).use { setup ->
                setup.existing("")
                setup.authorize()
                setup.begin()
                val credential = requireNotNull(setup.runtime.credentials.read())
                setup.accountId = 99L
                var rejected = false
                try {
                    setup.runtime.acceptAuthorization(
                        credential.revision,
                        GitHubAccessToken("new-account-token", null, "bearer", emptySet(), null, null),
                    )
                } catch (_: Exception) {
                    rejected = true
                }
                assertTrue(rejected, "different account authorization must be rejected before replacement")
                assertEquals(credential, setup.runtime.credentials.read())
            }
        }
    }

    @Test
    fun `recovery check remembers last real fact across failed check and controller recreation`() = runBlocking {
        open().use { storage ->
            SyncOnboardingFixture(storage).use { setup ->
                setup.existing("")
                setup.authorize()
                setup.begin()
                val delegate = setup.git.server.dispatcher
                setup.git.server.dispatcher = failing(delegate, "/repos/${setup.repository.fullName}", 404)
                setup.runtime.coordinator.synchronize(SyncTrigger.MANUAL)
                setup.panel.act(SyncPanelAction.OpenRecovery)
                setup.now = 2_000L
                setup.panel.act(SyncPanelAction.RecheckSpace)
                setup.panel.awaitRecoveryIdle()
                assertEquals(2_000L, setup.panel.state.value.recovery?.lastCheckedAtMillis)
                assertEquals(
                    SyncSpaceRecoveryReason.SPACE_UNAVAILABLE,
                    setup.panel.state.value.recovery?.lastCheckReason,
                )
                assertEquals(true, setup.panel.state.value.recovery?.lastCheckSucceeded)
                setup.git.server.dispatcher = failing(delegate, "/user", 500)
                setup.now = 3_000L
                setup.panel.act(SyncPanelAction.RecheckSpace)
                setup.panel.awaitRecoveryIdle()
                val fact = requireNotNull(setup.panel.state.value.recovery)
                assertEquals(3_000L, fact.lastCheckedAtMillis)
                assertEquals(SyncSpaceRecoveryReason.SPACE_UNAVAILABLE, fact.lastCheckReason)
                assertEquals(false, fact.lastCheckSucceeded)
                assertEquals(SyncRunProblem.NETWORK, fact.lastCheckProblem)
                setup.panel.act(SyncPanelAction.Close)
                val restarted = setup.runtime()
                try {
                    (restarted.panel as SyncPanelController).act(SyncPanelAction.OpenRecovery)
                    assertEquals(fact.copy(busy = false), restarted.panel.state.value.recovery)
                } finally {
                    restarted.stopPanel()
                }
                setup.git.server.dispatcher = failing(delegate, "/user", 500)
                setup.panel.act(SyncPanelAction.Open)
                setup.panel.act(SyncPanelAction.CheckAuthorization)
                withTimeout(5_000) {
                    setup.panel.state.first { it.recoveryAuthorization == SyncRecoveryAuthorization.FAILED }
                }
                assertEquals(
                    fact.authorizationConfirmedAtMillis,
                    setup.panel.state.value.recovery?.authorizationConfirmedAtMillis,
                )
                assertEquals(SyncSpaceRecoveryReason.SPACE_UNAVAILABLE, setup.panel.state.value.recovery?.reason)
                setup.authorize("renewed-token")
                val credentialChanged = setup.runtime()
                try {
                    (credentialChanged.panel as SyncPanelController).act(SyncPanelAction.OpenRecovery)
                    assertNull(credentialChanged.panel.state.value.recovery?.authorizationConfirmedAtMillis)
                    assertEquals(
                        SyncRecoveryAuthorization.IDLE,
                        credentialChanged.panel.state.value.recoveryAuthorization,
                    )
                } finally {
                    credentialChanged.stopPanel()
                }
            }
        }
    }

    @Test
    fun `authorization check uses real identity and confirms independently of unavailable space`() = runBlocking {
        open().use { storage ->
            SyncOnboardingFixture(storage).use { setup ->
                setup.existing("")
                setup.authorize()
                setup.begin()
                val delegate = setup.git.server.dispatcher
                var deviceRequests = 0
                val inaccessible = failing(delegate, "/repos/${setup.repository.fullName}", 404)
                setup.git.server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        if (request.url.encodedPath == "/device") deviceRequests++
                        return inaccessible.dispatch(request)
                    }
                }
                setup.runtime.coordinator.synchronize(SyncTrigger.MANUAL)
                setup.panel.act(SyncPanelAction.OpenRecovery)
                val credential = setup.runtime.credentials.read()
                setup.panel.act(SyncPanelAction.CheckAuthorization)
                withTimeout(5_000) {
                    setup.panel.state.first {
                        it.recoveryAuthorization == SyncRecoveryAuthorization.CONFIRMED &&
                            it.recovery?.lastCheckedAtMillis != null && it.recovery?.busy == false
                    }
                }
                setup.panel.awaitRecoveryIdle()
                assertEquals(0, deviceRequests)
                assertEquals(credential, setup.runtime.credentials.read())
                assertEquals(SyncSpaceRecoveryReason.SPACE_UNAVAILABLE, setup.panel.state.value.recovery?.reason)
                assertEquals(setup.now, setup.panel.state.value.recovery?.authorizationConfirmedAtMillis)
                setup.panel.act(SyncPanelAction.Close)
                val restarted = setup.runtime()
                try {
                    (restarted.panel as SyncPanelController).act(SyncPanelAction.OpenRecovery)
                    assertEquals(SyncRecoveryAuthorization.CONFIRMED, restarted.panel.state.value.recoveryAuthorization)
                    assertEquals(setup.now, restarted.panel.state.value.recovery?.authorizationConfirmedAtMillis)
                } finally {
                    restarted.stopPanel()
                }
            }
        }
    }

    @Test
    fun `authorization identity errors never become confirmed or browser success`() = runBlocking {
        for ((code, body) in listOf(404 to "{}", 403 to "{}", 429 to "{}", 500 to "{}", 200 to "{}", 200 to "broken")) {
            open().use { storage ->
                SyncOnboardingFixture(storage).use { setup ->
                    setup.existing("")
                    setup.authorize()
                    setup.begin()
                    val delegate = setup.git.server.dispatcher
                    setup.git.server.dispatcher = failing(delegate, "/repos/${setup.repository.fullName}", 404)
                    setup.runtime.coordinator.synchronize(SyncTrigger.MANUAL)
                    setup.panel.act(SyncPanelAction.OpenRecovery)
                    setup.git.server.dispatcher = object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse =
                            if (request.url.encodedPath == "/user") {
                                MockResponse(code = code, body = body)
                            } else {
                                delegate.dispatch(request)
                            }
                    }
                    setup.panel.act(SyncPanelAction.CheckAuthorization)
                    withTimeout(5_000) {
                        setup.panel.state.first { it.recoveryAuthorization == SyncRecoveryAuthorization.FAILED }
                    }
                    assertNull(setup.panel.state.value.deviceCode, "$code $body")
                    assertNull(setup.panel.state.value.recovery?.authorizationConfirmedAtMillis, "$code $body")
                    assertEquals(SyncSpaceRecoveryReason.SPACE_UNAVAILABLE, setup.panel.state.value.recovery?.reason)
                }
            }
        }
    }

    @Test
    fun `unfinished recovery switch remains available after close and recreation`() = runBlocking {
        open().use { storage ->
            SyncOnboardingFixture(storage).use { setup ->
                setup.existing("")
                setup.authorize()
                setup.begin()
                val credential = setup.runtime.credentials.read()
                val binding = setup.runtime.connection()
                setup.panel.act(SyncPanelAction.ConnectOtherSpace)
                withTimeout(5_000) { setup.panel.state.first { !it.setupBusy } }
                val intent = requireNotNull(setup.runtime.activeSwitch())
                setup.panel.act(SyncPanelAction.RecheckSpace)
                setup.panel.awaitRecoveryIdle()
                assertEquals(
                    intent,
                    setup.runtime.activeSwitch(),
                    "a read-only recheck cannot cancel an unfinished switch",
                )
                setup.panel.act(SyncPanelAction.Close)
                assertEquals(intent, setup.runtime.activeSwitch())
                val restarted = setup.runtime()
                try {
                    (restarted.panel as SyncPanelController).act(SyncPanelAction.OpenRecovery)
                    assertEquals(SyncRecoveryContinuation.CONNECT, restarted.panel.state.value.pendingRecoveryPurpose)
                    assertTrue(restarted.panel.state.value.canCancelRecoverySwitch)
                    assertEquals(binding, restarted.connection())
                    assertEquals(credential, restarted.credentials.read())
                    val panel = restarted.panel as SyncPanelController
                    panel.act(SyncPanelAction.ContinueRecovery)
                    assertEquals(intent, restarted.activeSwitch())
                    panel.act(SyncPanelAction.Ask(SyncPanelQuestion.CANCEL_RECOVERY_SWITCH))
                    panel.act(SyncPanelAction.CancelQuestion)
                    assertEquals(intent, restarted.activeSwitch(), "dismissing confirmation retains continuation")
                    panel.act(SyncPanelAction.Ask(SyncPanelQuestion.CANCEL_RECOVERY_SWITCH))
                    panel.act(SyncPanelAction.ConfirmQuestion)
                    assertNull(restarted.activeSwitch(), "confirmed cancellation archives only the pending switch")
                    assertEquals(binding, restarted.connection())
                    assertEquals(credential, restarted.credentials.read())
                    assertEquals(
                        intent.oldPending,
                        restarted.onboarding.storage.pendingForConnection(intent.oldConnection),
                    )
                    val activating = intent.copy(stage = SyncSpaceSwitchStage.ACTIVATING)
                    val archived = requireNotNull(restarted.onboarding.storage.activeSwitch(intent.accountId))
                    restarted.onboarding.storage.saveSwitch(activating, archived)
                    var rejected = false
                    try {
                        restarted.cancelRecoverySwitch()
                    } catch (_: IllegalArgumentException) {
                        rejected = true
                    }
                    assertTrue(rejected, "committed activation cannot be rolled back")
                    assertEquals(activating, restarted.activeSwitch())
                    assertEquals(binding, restarted.connection())
                } finally {
                    restarted.stopPanel()
                }
            }
        }
    }

    @Test
    fun `invalid recovery observations never supply facts or clear the gate`() = runBlocking {
        open().use { storage ->
            SyncOnboardingFixture(storage).use { setup ->
                setup.existing("")
                setup.authorize()
                setup.begin()
                setup.git.server.dispatcher = failing(
                    setup.git.server.dispatcher,
                    "/repos/${setup.repository.fullName}",
                    404,
                )
                setup.runtime.coordinator.synchronize(SyncTrigger.MANUAL)
                setup.panel.act(SyncPanelAction.OpenRecovery)
                setup.panel.act(SyncPanelAction.RecheckSpace)
                setup.panel.awaitRecoveryIdle()
                val key = setup.secure.values.keys.single { it.endsWith("-recovery-observation") }
                val original = Json.parseToJsonElement(requireNotNull(setup.secure.values[key])).jsonObject
                for (record in listOf(
                    JsonObject(original + ("bindingRevision" to JsonPrimitive("other-binding"))).toString(),
                    "{}",
                    "{\"version\":999}",
                )) {
                    setup.secure.values[key] = record
                    val restarted = setup.runtime()
                    try {
                        (restarted.panel as SyncPanelController).act(SyncPanelAction.OpenRecovery)
                        assertEquals(
                            SyncSpaceRecoveryReason.SPACE_UNAVAILABLE,
                            restarted.panel.state.value.recovery?.reason,
                        )
                        assertNull(restarted.panel.state.value.recovery?.lastCheckedAtMillis)
                        assertNull(restarted.panel.state.value.recovery?.authorizationConfirmedAtMillis)
                        assertEquals(SyncRecoveryAuthorization.IDLE, restarted.panel.state.value.recoveryAuthorization)
                        assertEquals(
                            SyncRunStatus.SKIPPED,
                            restarted.coordinator.synchronize(SyncTrigger.MANUAL).status,
                        )
                    } finally {
                        restarted.stopPanel()
                    }
                }
            }
        }
    }

    @Test
    fun `confirmed authorization is cleared by real 401 while waiting for fresh browser approval`() = runBlocking {
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
                setup.panel.act(SyncPanelAction.RecheckSpace)
                setup.panel.awaitRecoveryIdle()
                assertNotNull(setup.panel.state.value.recovery?.authorizationConfirmedAtMillis)
                val release = CountDownLatch(1)
                setup.git.server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                        "/user" -> MockResponse(code = 401, body = "{}")
                        "/token" -> {
                            check(release.await(5, TimeUnit.SECONDS))
                            inaccessible.dispatch(request)
                        }
                        else -> inaccessible.dispatch(request)
                    }
                }
                try {
                    setup.panel.act(SyncPanelAction.CheckAuthorization)
                    withTimeout(5_000) { setup.panel.state.first { it.deviceCode != null } }
                    assertEquals(SyncRecoveryAuthorization.WAITING, setup.panel.state.value.recoveryAuthorization)
                    assertNull(setup.panel.state.value.recovery?.authorizationConfirmedAtMillis)
                } finally {
                    setup.panel.act(SyncPanelAction.Close)
                    release.countDown()
                }
            }
        }
    }

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
                    setup.panel.act(SyncPanelAction.ManageAuthorization)
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
