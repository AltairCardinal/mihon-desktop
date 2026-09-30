package mihon.data.sync

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mihon.data.sync.crypto.SyncSpaceCrypto
import mihon.data.sync.inbox.SyncInboxStore
import mihon.data.sync.runtime.StoredSyncMaterial
import mihon.data.sync.runtime.StoredSyncSetup
import mihon.data.sync.runtime.SyncDecisionScope
import mihon.data.sync.runtime.SyncPanelAction
import mihon.data.sync.runtime.SyncPanelController
import mihon.data.sync.runtime.SyncPanelPage
import mihon.data.sync.runtime.SyncRuntime
import mihon.data.sync.runtime.SyncSetupStep
import mihon.domain.sync.SyncBatch
import mihon.domain.sync.SyncCancellationDecision
import mihon.domain.sync.SyncCategory
import mihon.domain.sync.SyncEffect
import mihon.domain.sync.SyncEffectKind
import mihon.domain.sync.SyncEventEnvelope
import mihon.domain.sync.SyncField
import mihon.domain.sync.SyncObjectDescriptor
import mihon.domain.sync.SyncObjectKey
import mihon.domain.sync.SyncObjectType
import mihon.domain.sync.SyncOrigin
import mihon.domain.sync.auth.GitHubAccessToken
import mihon.domain.sync.auth.GitHubAuthEndpoints
import mihon.domain.sync.auth.GitHubAuthFailureReason
import mihon.domain.sync.crypto.SyncRecoveryCodec
import mihon.domain.sync.crypto.SyncSpaceDescriptorCodec
import mihon.domain.sync.crypto.SyncSpaceMaterial
import mihon.domain.sync.security.SyncSecureStore
import mihon.domain.sync.transport.SyncInitializationIntent
import mihon.domain.sync.transport.SyncInitializationStage
import mihon.domain.sync.transport.SyncRepository
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okio.ByteString.Companion.encodeUtf8
import okio.Path.Companion.toPath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.data.Database
import tachiyomi.data.DatabaseHandler
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.Mangas
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.nio.file.Path as NioPath

@Timeout(30)
abstract class SyncPanelStorageContract {
    protected abstract fun open(): SyncRuntimeStorageContract.Storage
    private val repository = SyncRepository("fixture-owner", "private-sync", "mihon-sync-v1")

    @Test
    fun `diagnostic snapshot reads actual disconnected history and excludes sensitive output`() = runBlocking {
        open().use { storage ->
            SyncOnboardingFixture(storage).use { f ->
                storage.connect("diagnostic-private-actor", repository)
                val run = f.runtime.runStore.start("space", 1, mihon.domain.sync.runtime.SyncTrigger.MANUAL)
                f.runtime.runStore.finish(
                    run.runId,
                    mihon.data.sync.runtime.SyncRunState.CANCELLED,
                    "private-token-account-title-url",
                )
                storage.handler.await { sync_journalQueries.disconnectSpace("space", 1) }
                f.panel.act(SyncPanelAction.Open)
                val before = f.runtime.runStore.latest("space", 1)
                val snapshot = f.runtime.diagnostics.capture(f.panel.state.value)
                assertEquals(mihon.data.sync.runtime.SyncDiagnosticStatus.OK, snapshot.status)
                assertEquals(false, snapshot.connection.exchangeEnabled)
                assertEquals(mihon.data.sync.runtime.SyncBindingDecode.MISSING, snapshot.connection.storedBindingDecode)
                assertEquals("LATEST", snapshot.runSource)
                assertEquals("CANCELLED", snapshot.latestRun?.state)
                assertEquals("OTHER", snapshot.latestRun?.stopReason)
                assertEquals(before, f.runtime.runStore.latest("space", 1))
                val output = snapshot.json()
                listOf(
                    "fixture-owner",
                    "private-sync",
                    "diagnostic-private-actor",
                    run.runId,
                    "private-token-account-title-url",
                ).forEach { assertFalse(output.contains(it), it) }
                assertFalse(snapshot.crossProcessComparable)
                assertFalse(f.runtime.coordinator.activity.value.running)
            }
        }
    }

    @Test
    fun `diagnostic snapshot distinguishes raw unsupported binding from disabled projection`() = runBlocking {
        open().use { storage ->
            SyncOnboardingFixture(storage).use { f ->
                storage.connect("actor", repository)
                val key = "space-" + "1:space".encodeUtf8().sha256().hex()
                f.secure.values[key] = "{\"version\":1}"
                storage.handler.await { sync_journalQueries.disconnectSpace("space", 1) }
                f.panel.act(SyncPanelAction.Open)
                val snapshot = f.runtime.diagnostics.capture(f.panel.state.value)
                assertEquals(mihon.data.sync.runtime.SyncDiagnosticStatus.OK, snapshot.status)
                assertEquals(
                    mihon.data.sync.runtime.SyncBindingDecode.UNSUPPORTED,
                    snapshot.connection.storedBindingDecode,
                )
                assertEquals(false, snapshot.connection.panelUnsupportedFormat)
                assertEquals(false, snapshot.connection.panelConnectionEnabled)
            }
        }
    }

    @Test
    fun `diagnostic snapshot reports binding read failure without reporting disabled`() = runBlocking {
        open().use { storage ->
            SyncOnboardingFixture(storage).use { f ->
                storage.connect("actor", repository)
                f.runtime.stopPanel()
                f.secure.readFailure = true
                val snapshot = f.runtime.diagnostics.capture(mihon.data.sync.runtime.SyncPanelState())
                assertEquals(mihon.data.sync.runtime.SyncDiagnosticStatus.READ_FAILED, snapshot.status)
                assertEquals("UNKNOWN", snapshot.runSource)
                assertEquals(
                    mihon.data.sync.runtime.SyncBindingDecode.READ_FAILED,
                    snapshot.connection.storedBindingDecode,
                )
                assertNull(snapshot.connection.exchangeEnabled)
                assertNull(snapshot.connection.panelConnectionEnabled)
                assertFalse(snapshot.json().contains("private-sensitive-read-error"))
                assertTrue(f.runtime.connection()!!.enabled)
            }
        }
    }

    @Test
    fun `diagnostic snapshot rejects identity changing during real storage decode`() = runBlocking {
        open().use { storage ->
            SyncOnboardingFixture(storage).use { f ->
                storage.connect("actor", repository)
                f.runtime.stopPanel()
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                f.secure.nextSpaceRead = entered to release
                val collecting = async { f.runtime.diagnostics.capture(mihon.data.sync.runtime.SyncPanelState()) }
                withTimeout(5000) { entered.await() }
                storage.handler.await { sync_journalQueries.disconnectSpace("space", 1) }
                f.runtime.baseline.connectAndImport("other-private-space", 2, repository, "actor", 1)
                release.complete(Unit)
                val snapshot = collecting.await()
                assertEquals(mihon.data.sync.runtime.SyncDiagnosticStatus.INCONSISTENT, snapshot.status)
                assertNull(snapshot.connection.spaceAlias)
                assertNull(snapshot.activeRun)
                assertNull(snapshot.latestRun)
                assertFalse(snapshot.json().contains("other-private-space"))
            }
        }
    }

    @Test
    fun `diagnostic snapshot memory ring retains only last 128 transitions`() = runBlocking {
        open().use { storage ->
            SyncOnboardingFixture(storage).use { f ->
                f.runtime.stopPanel()
                repeat(200) { f.runtime.diagnostics.record(mihon.data.sync.runtime.SyncDiagnosticEventKind.OPEN) }
                val snapshot = f.runtime.diagnostics.capture(mihon.data.sync.runtime.SyncPanelState())
                assertEquals(128, snapshot.events.size)
            }
        }
    }

    @Test
    fun `diagnostic snapshot preserves production user cancellation reason`() = runBlocking {
        open().use { storage ->
            SyncOnboardingFixture(storage).use { f ->
                storage.connect("actor", repository)
                val run = f.runtime.runStore.start("space", 1, mihon.domain.sync.runtime.SyncTrigger.MANUAL)
                f.runtime.runStore.cancel(run.runId)
                f.runtime.stopPanel()
                val snapshot = f.runtime.diagnostics.capture(mihon.data.sync.runtime.SyncPanelState())
                assertEquals("USER", snapshot.latestRun?.stopReason)
                assertEquals("user", f.runtime.runStore.get(run.runId)?.stopReason)
            }
        }
    }

    @Test
    fun `diagnostic snapshot rejects stale panel identity even when storage is stable`() = runBlocking {
        open().use { storage ->
            SyncOnboardingFixture(storage).use { f ->
                storage.connect("actor", repository)
                val oldConnection = f.runtime.connection()
                f.runtime.stopPanel()
                storage.handler.await { sync_journalQueries.disconnectSpace("space", 1) }
                f.runtime.baseline.connectAndImport("another-private-space", 2, repository, "actor", 1)
                val oldPanel = mihon.data.sync.runtime.SyncPanelState(loaded = true, connection = oldConnection)
                val snapshot = f.runtime.diagnostics.capture(oldPanel)
                assertEquals(mihon.data.sync.runtime.SyncDiagnosticStatus.INCONSISTENT, snapshot.status)
                assertNull(snapshot.connection.spaceAlias)
                assertFalse(snapshot.json().contains("another-private-space"))
            }
        }
    }

    @Test
    fun `diagnostic snapshot association survives restart only in opted in private session`() = runBlocking {
        open().use { storage ->
            val directory = Files.createTempDirectory("sync-diag-association").toString().toPath()
            SyncOnboardingFixture(storage, diagnosticDirectory = directory).use { f ->
                storage.connect("actor", repository)
                f.runtime.stopPanel()
                assertTrue(f.runtime.diagnostics.beginSession())
                val first = f.runtime.diagnostics.capture(mihon.data.sync.runtime.SyncPanelState())
                val restarted = f.runtime()
                val second = restarted.diagnostics.capture(mihon.data.sync.runtime.SyncPanelState())
                assertEquals(first.snapshotAlias, second.previousSnapshotAlias)
                assertEquals(first.connection.spaceAlias, second.connection.spaceAlias)
                assertTrue(first.processSession != second.processSession)
                restarted.diagnostics.record(mihon.data.sync.runtime.SyncDiagnosticEventKind.OPEN)
                assertTrue(restarted.diagnostics.endSession())
                val after = restarted.diagnostics.capture(mihon.data.sync.runtime.SyncPanelState())
                assertFalse(after.crossProcessComparable)
                assertNull(after.previousSnapshotAlias)
                assertTrue(after.events.isEmpty())
                assertTrue(first.connection.spaceAlias != after.connection.spaceAlias)
                assertFalse(Files.exists(NioPath.of(directory.toString(), "private/session.json")))
            }
        }
    }

    @Test
    fun `diagnostic snapshot expired cache cleanup failure cannot break business observations`() = runBlocking {
        open().use { storage ->
            val directory = Files.createTempDirectory("sync-diag-expiry").toString().toPath()
            SyncOnboardingFixture(storage, diagnosticDirectory = directory).use { f ->
                storage.connect("actor", repository)
                f.runtime.stopPanel()
                assertTrue(f.runtime.diagnostics.beginSession())
                val cache = NioPath.of(directory.toString(), "private/session.json")
                Files.delete(cache)
                Files.createDirectory(cache)
                Files.write(cache.resolve("blocked"), "synthetic".toByteArray(Charsets.UTF_8))
                f.now += 86_400_001
                f.runtime.diagnostics.record(mihon.data.sync.runtime.SyncDiagnosticEventKind.OPEN)
                val snapshot = f.runtime.diagnostics.capture(mihon.data.sync.runtime.SyncPanelState())
                assertFalse(snapshot.crossProcessComparable)
                assertTrue(f.runtime.connection()!!.enabled)
            }
        }
    }

    @Test
    fun `diagnostic snapshot reads secure binding and preferences without HTTP or mutations`() = runBlocking {
        open().use { storage ->
            SyncOnboardingFixture(storage).use { f ->
                f.existing("")
                f.authorize()
                f.begin()
                f.runtime.stopPanel()
                f.runtime.preferences.startup.set(false)
                f.runtime.preferences.periodMinutes.set(15)
                f.runtime.preferences.importPaused.set(true)
                val runBefore = f.runtime.runStore.latest("space", 1)
                val secureBefore = f.secure.values.toMap()
                val requests = f.repositoryTokens.size
                val snapshot = f.runtime.diagnostics.capture(mihon.data.sync.runtime.SyncPanelState())
                assertEquals(mihon.data.sync.runtime.SyncBindingDecode.OK, snapshot.connection.storedBindingDecode)
                assertEquals(true, snapshot.connection.panelConnectionEnabled)
                assertEquals(false, snapshot.startup)
                assertEquals(15, snapshot.periodMinutes)
                assertEquals(true, snapshot.importPaused)
                assertEquals(requests, f.repositoryTokens.size)
                assertEquals(secureBefore, f.secure.values.toMap())
                assertEquals(runBefore, f.runtime.runStore.latest("space", 1))
                assertFalse(snapshot.json().contains(f.accountLogin))
                assertFalse(snapshot.json().contains("synthetic-token"))
            }
        }
    }

    @Test
    fun `diagnostic snapshot live session expires by monotonic deadline after wall clock rollback`() = runBlocking {
        open().use { storage ->
            val directory = Files.createTempDirectory("sync-diag-monotonic").toString().toPath()
            SyncOnboardingFixture(storage).use { f ->
                f.runtime.stopPanel()
                var monotonic = 0L
                val diagnostics = mihon.data.sync.runtime.SyncDiagnostics(
                    f.runtime,
                    directory,
                    mihon.data.sync.runtime.SyncDiagnosticEnvironment(),
                    { f.now },
                    { monotonic },
                )
                assertTrue(diagnostics.beginSession())
                f.now -= 60_000
                monotonic = 86_400_000_000_001L
                val snapshot = diagnostics.capture(mihon.data.sync.runtime.SyncPanelState())
                assertFalse(snapshot.crossProcessComparable)
                assertFalse(Files.exists(NioPath.of(directory.toString(), "private/session.json")))
            }
        }
    }

    @Test
    fun `diagnostic snapshot records real coordinator state and typed refresh failures`() = runBlocking {
        open().use { storage ->
            SyncOnboardingFixture(storage).use { f ->
                f.panel.act(SyncPanelAction.Open)
                val snapshot = f.runtime.diagnostics.capture(f.panel.state.value)
                assertTrue(
                    snapshot.events.any {
                        it.kind == mihon.data.sync.runtime.SyncDiagnosticEventKind.COORDINATOR &&
                            it.coordinatorRunning == false
                    },
                )
                f.runtime.diagnostics.record(
                    mihon.data.sync.runtime.SyncDiagnosticEventKind.REFRESH_END,
                    mihon.data.sync.runtime.SyncDiagnosticRefreshSource.OPEN,
                    failed = true,
                )
                val failed = f.runtime.diagnostics.capture(f.panel.state.value)
                assertEquals(mihon.data.sync.runtime.SyncDiagnosticStatus.READ_FAILED, failed.lastRefreshError)
            }
        }
    }

    @Test
    fun `pre-created empty repository onboarding completes without a password`() = runBlocking {
        createsSpace("")
    }

    @Test
    fun `existing empty repository completes setup with a password`() = runBlocking {
        createsSpace("private-test-password")
    }

    private suspend fun createsSpace(password: String) {
        open().use { storage ->
            storage.favorite("/first-book")
            SyncOnboardingFixture(storage).use { fixture ->
                fixture.authorize()
                fixture.begin()
                assertEquals(SyncSetupStep.NEW_PASSWORD, fixture.panel.state.value.setupStep)
                fixture.panel.act(SyncPanelAction.SubmitPassword(password))
                withTimeout(10_000) { fixture.panel.state.first { it.setupStep == SyncSetupStep.COMPLETE } }
                assertEquals(0, fixture.userRepoPosts)
                assertEquals(0, fixture.repositorySettingsWrites)
                assertEquals(
                    if (password.isEmpty()) "none" else "password",
                    fixture.runtime.connection()?.protectionMode,
                )
                assertEquals(0L, fixture.panel.state.value.importRemaining)
                assertEquals(0L, fixture.panel.state.value.queuedTotal)
                if (password.isNotEmpty()) assertFalse(fixture.secure.values.toString().contains(password))
            }
        }
    }

    @Test
    fun `onboarding existing plain space merges immediately without a password or confirmation`() = runBlocking {
        open().use { storage ->
            SyncOnboardingFixture(storage).use { fixture ->
                fixture.existing("")
                fixture.authorize()
                fixture.begin()
                assertEquals(SyncSetupStep.COMPLETE, fixture.panel.state.value.setupStep)
                assertEquals("none", fixture.runtime.connection()?.protectionMode)
                assertEquals(0, fixture.userRepoPosts)
                assertEquals(0, fixture.repositorySettingsWrites)
            }
        }
    }

    @Test
    fun `onboarding existing password space requires verification before connecting`() = runBlocking {
        open().use { storage ->
            SyncOnboardingFixture(storage).use { fixture ->
                fixture.existing("private-test-password")
                fixture.authorize()
                fixture.begin()
                assertEquals(SyncSetupStep.UNLOCK, fixture.panel.state.value.setupStep)
                fixture.panel.act(SyncPanelAction.SubmitPassword("wrong"))
                withTimeout(5_000) { fixture.panel.state.first { it.passwordProblem != null } }
                assertNull(fixture.runtime.connection())
                fixture.panel.act(SyncPanelAction.SubmitPassword("private-test-password"))
                withTimeout(10_000) { fixture.panel.state.first { it.setupStep == SyncSetupStep.COMPLETE } }
                assertEquals("password", fixture.runtime.connection()?.protectionMode)
                assertEquals(0, fixture.userRepoPosts)
                assertEquals(0, fixture.repositorySettingsWrites)
            }
        }
    }

    @Test
    fun `onboarding completion has one session notice and never replays after reopen`() = runBlocking {
        open().use { storage ->
            SyncOnboardingFixture(storage).use { f ->
                f.authorize()
                f.begin()
                f.panel.act(SyncPanelAction.SubmitPassword(""))
                withTimeout(10_000) { f.panel.state.first { it.setupStep == SyncSetupStep.COMPLETE } }
                assertTrue(f.panel.state.value.notice?.setupCompleted == true)
                f.panel.act(SyncPanelAction.Close)
                f.panel.act(SyncPanelAction.Open)
                assertNull(f.panel.state.value.notice)
            }
        }
    }

    @Test
    fun `onboarding confirmed background setup does not seize settings or replay completion`() = runBlocking {
        open().use { storage ->
            SyncOnboardingFixture(storage).use { f ->
                f.authorize()
                f.begin()
                f.bootstrapPutEntered = java.util.concurrent.CountDownLatch(1)
                f.bootstrapPutRelease = java.util.concurrent.CountDownLatch(1)
                f.panel.act(SyncPanelAction.SubmitPassword(""))
                assertTrue(f.bootstrapPutEntered!!.await(3, java.util.concurrent.TimeUnit.SECONDS))
                f.panel.act(SyncPanelAction.SubmitPassword("second-click"))
                f.panel.act(SyncPanelAction.Close)
                f.panel.act(SyncPanelAction.Open)
                f.panel.act(SyncPanelAction.Navigate(SyncPanelPage.SETTINGS))
                f.bootstrapPutRelease!!.countDown()
                withTimeout(10_000) { f.panel.state.first { it.setupStep == SyncSetupStep.COMPLETE } }
                assertEquals(SyncPanelPage.SETTINGS, f.panel.state.value.page)
                assertNull(f.panel.state.value.notice)
                assertEquals(0, f.userRepoPosts)
            }
        }
    }

    @Test
    fun `paused onboarding resumes after restart without duplicate initialization`() = runBlocking {
        open().use { storage ->
            storage.favorite("/before-onboarding")
            SyncOnboardingFixture(storage).use { f ->
                f.authorize()
                f.runtime.preferences.importPaused.set(true)
                f.begin()
                f.panel.act(SyncPanelAction.SubmitPassword("restart-password"))
                withTimeout(10_000) { f.panel.state.first { it.setupStep == SyncSetupStep.MERGING && !it.setupBusy } }
                assertEquals(1L, f.panel.state.value.importRemaining)
                assertTrue(f.secure.values.keys.any { it.startsWith("sync-setup-v3-") })
                f.runtime.stopPanel()
                val reopened = f.runtime()
                try {
                    val panel = reopened.panel as SyncPanelController
                    val requests = f.git.server.requestCount
                    panel.act(SyncPanelAction.Open)
                    assertEquals(SyncSetupStep.MERGING, panel.state.value.setupStep)
                    assertFalse(panel.state.value.setupBusy)
                    assertEquals(requests, f.git.server.requestCount)
                    assertEquals(SyncPanelPage.MAIN, panel.state.value.page)
                    panel.act(SyncPanelAction.ResumeImport)
                    withTimeout(10_000) { panel.state.first { it.setupStep == SyncSetupStep.COMPLETE } }
                    assertEquals(0L, panel.state.value.importRemaining)
                    assertTrue(panel.state.value.notice?.setupCompleted == true)
                    assertEquals(0, f.userRepoPosts)
                    assertFalse(f.secure.values.toString().contains("restart-password"))
                    assertFalse(f.secure.values.keys.any { it.startsWith("sync-setup-v3-") })
                } finally {
                    reopened.stopPanel()
                }
            }
        }
    }

    @Test
    fun `onboarding resumes the same durable bootstrap after a lost response and restart`() = runBlocking {
        open().use { storage ->
            SyncOnboardingFixture(storage).use { f ->
                f.authorize()
                f.git.loseNextBootstrapResponse = true
                f.begin()
                f.panel.act(SyncPanelAction.SubmitPassword(""))
                withTimeout(10_000) { f.panel.state.first { it.setupStep == SyncSetupStep.ERROR } }

                val pendingBeforeRestart = f.secure.values.entries.single { it.key.startsWith("sync-setup-v3-") }.value
                assertTrue(pendingBeforeRestart.contains(""""stage":"BOOTSTRAP_SUBMITTING""""))
                assertEquals(1, f.git.contentsPutBodies.size)
                assertEquals(0, f.userRepoPosts)
                assertNull(f.runtime.connection())
                f.runtime.stopPanel()

                val reopened = f.runtime()
                try {
                    val panel = reopened.panel as SyncPanelController
                    panel.act(SyncPanelAction.Open)
                    panel.act(SyncPanelAction.BeginSetup)
                    withTimeout(10_000) {
                        panel.state.first {
                            !it.setupBusy && it.setupStep in setOf(SyncSetupStep.COMPLETE, SyncSetupStep.ERROR)
                        }
                    }

                    assertEquals(SyncSetupStep.COMPLETE, panel.state.value.setupStep)
                    assertEquals(1, f.git.contentsPutBodies.size, "restart must reuse the persisted bootstrap intent")
                    assertEquals(0, f.userRepoPosts)
                    assertEquals(0, f.repositorySettingsWrites)
                    assertEquals("none", reopened.connection()?.protectionMode)
                    assertFalse(f.secure.values.keys.any { it.startsWith("sync-setup-v3-") })
                } finally {
                    reopened.stopPanel()
                }
            }
        }
    }

    @Test
    fun `legacy submitted v2 pending is rechecked without connecting or discarding material`() = runBlocking {
        open().use { storage ->
            SyncOnboardingFixture(storage).use { f ->
                val material = f.existing("")
                val record = legacySetupJson(material)
                f.secure.values["sync-setup-v2-${f.accountId}"] = record
                f.authorize()
                val requestsBefore = f.git.server.requestCount
                val writesBefore = f.repositoryWrites

                f.begin()
                val first = withTimeout(10_000) {
                    f.panel.state.first {
                        !it.setupBusy && it.setupStep in setOf(SyncSetupStep.ERROR, SyncSetupStep.COMPLETE)
                    }
                }

                assertEquals(SyncSetupStep.ERROR, first.setupStep)
                assertEquals(mihon.data.sync.auth.SyncDiscoveryProblem.CREATION_UNCONFIRMED, first.setupProblem)
                assertNull(f.runtime.connection())
                assertEquals(record, f.secure.values["sync-setup-v2-${f.accountId}"])
                assertTrue(f.git.server.requestCount > requestsBefore, "legacy retry should recheck GitHub read-only")
                assertEquals(writesBefore, f.repositoryWrites, "legacy retry must not write to GitHub")

                val afterFirstRecheck = f.git.server.requestCount
                f.panel.act(SyncPanelAction.RetrySetup)
                val retried = withTimeout(10_000) {
                    f.panel.state.first { !it.setupBusy && it.setupStep == SyncSetupStep.ERROR }
                }

                assertEquals(mihon.data.sync.auth.SyncDiscoveryProblem.CREATION_UNCONFIRMED, retried.setupProblem)
                assertTrue(f.git.server.requestCount > afterFirstRecheck)
                assertEquals(writesBefore, f.repositoryWrites)
                assertEquals(record, f.secure.values["sync-setup-v2-${f.accountId}"])
                assertEquals(0, f.userRepoPosts)
            }
        }
    }

    @Test
    fun `legacy pending can be explicitly abandoned without remote writes or local queue loss`() = runBlocking {
        open().use { storage ->
            SyncOnboardingFixture(storage).use { f ->
                val material = f.existing("")
                val record = legacySetupJson(material)
                f.secure.values["sync-setup-v2-${f.accountId}"] = record
                storage.favorite("/keep-legacy-queue")
                f.authorize()
                f.begin()
                withTimeout(10_000) {
                    f.panel.state.first {
                        !it.setupBusy && it.setupStep == SyncSetupStep.ERROR && it.legacyRecoveryAvailable
                    }
                }

                val queuedBefore = mihon.data.sync.journal.SyncLocalJournal(storage.handler)
                    .pendingEvents(material.descriptor.spaceId, material.descriptor.generation)
                val remoteWritesBefore = f.git.contentsPutBodies.size
                val repositoryWritesBefore = f.repositoryWrites

                f.panel.act(SyncPanelAction.Ask(mihon.data.sync.runtime.SyncPanelQuestion.ABANDON_LEGACY))
                assertNotNull(f.panel.state.value.question)
                f.panel.act(SyncPanelAction.ConfirmQuestion)
                withTimeout(10_000) {
                    f.panel.state.first { !it.setupBusy && !it.legacyRecoveryAvailable }
                }

                assertNull(f.secure.values["sync-setup-v2-${f.accountId}"])
                assertEquals(
                    queuedBefore,
                    mihon.data.sync.journal.SyncLocalJournal(storage.handler)
                        .pendingEvents(material.descriptor.spaceId, material.descriptor.generation),
                )
                assertEquals(remoteWritesBefore, f.git.contentsPutBodies.size)
                assertEquals(repositoryWritesBefore, f.repositoryWrites)
            }
        }
    }

    @Test
    fun `legacy v2 join pending resumes exact connected space and retains queued work`() = runBlocking {
        open().use { storage ->
            SyncOnboardingFixture(storage).use { f ->
                val material = f.existing("")
                f.authorize()
                f.begin()
                assertEquals(SyncSetupStep.COMPLETE, f.panel.state.value.setupStep)
                val existingBinding = requireNotNull(f.runtime.connection())
                val existingStoredBinding = requireNotNull(
                    f.runtime.onboarding.storage.connection(
                        material.descriptor.spaceId,
                        material.descriptor.generation,
                    ),
                )
                val pendingEventsBefore = mihon.data.sync.journal.SyncLocalJournal(storage.handler)
                    .pendingEvents(material.descriptor.spaceId, material.descriptor.generation)
                storage.favorite("/queued-before-legacy-join-recovery")
                val queueBefore = mihon.data.sync.journal.SyncLocalJournal(storage.handler)
                    .pendingEvents(material.descriptor.spaceId, material.descriptor.generation)
                assertTrue(queueBefore.size > pendingEventsBefore.size)

                val record = legacySetupJson(
                    material,
                    newSpace = false,
                    submitted = false,
                    connected = true,
                )
                f.secure.values["sync-setup-v2-${f.accountId}"] = record
                val requestsBefore = f.git.server.requestCount
                val writesBefore = f.repositorySettingsWrites
                f.panel.act(SyncPanelAction.Close)
                f.panel.act(SyncPanelAction.Open)
                f.panel.act(SyncPanelAction.BeginSetup)
                val resumed = withTimeout(10_000) {
                    f.panel.state.first {
                        !it.setupBusy && it.setupStep in setOf(SyncSetupStep.ERROR, SyncSetupStep.COMPLETE)
                    }
                }

                assertEquals(SyncSetupStep.COMPLETE, resumed.setupStep)
                assertEquals(existingBinding.spaceId, f.runtime.connection()?.spaceId)
                assertEquals(existingBinding.generation, f.runtime.connection()?.generation)
                assertNull(f.secure.values["sync-setup-v2-${f.accountId}"])
                assertEquals(
                    existingStoredBinding.actorId,
                    f.runtime.onboarding.storage.connection(
                        material.descriptor.spaceId,
                        material.descriptor.generation,
                    )?.actorId,
                )
                assertTrue(f.git.server.requestCount > requestsBefore, "legacy join recovery must recheck GitHub")
                assertEquals(writesBefore, f.repositorySettingsWrites)
                assertEquals(0, f.userRepoPosts)
                assertTrue(
                    mihon.data.sync.journal.SyncLocalJournal(storage.handler)
                        .pendingEvents(material.descriptor.spaceId, material.descriptor.generation).isEmpty(),
                    "the pre-existing connection must continue syncing its local queue",
                )
            }
        }
    }

    @Test
    fun `onboarding another account cannot transmit the original queued events`() = runBlocking {
        open().use { storage ->
            SyncOnboardingFixture(storage).use { f ->
                f.existing("")
                f.authorize()
                f.begin()
                storage.favorite("/keep-local-queue")
                f.accountId = 2
                f.accountLogin = "other-account"
                f.authorize("other-account-token")
                val requests = f.repositoryTokens.size
                val result = f.runtime.coordinator.synchronize(mihon.domain.sync.runtime.SyncTrigger.MANUAL)
                assertEquals(mihon.domain.sync.runtime.SyncRunProblem.AUTHORIZATION, result.problem)
                assertEquals(requests, f.repositoryTokens.size)
                assertEquals(
                    1L,
                    storage.handler.await {
                        sync_journalQueries.getPendingCategoryCounts("space", 1).executeAsList().sumOf { it.count }
                    },
                )
            }
        }
    }

    @Test
    fun `onboarding rejects legacy binding before network without deleting its recovery data`() = runBlocking {
        open().use { storage ->
            SyncOnboardingFixture(storage).use { f ->
                storage.connect("legacy-device", f.repository)
                f.authorize()
                val key = "space-" + java.security.MessageDigest.getInstance("SHA-256")
                    .digest("1:space".encodeToByteArray()).joinToString("") { "%02x".format(it) }
                val legacy = """{"recovery":{"rawKeyset":"legacy-test-material"},"actorId":"legacy-device","epoch":1}"""
                f.secure.values[key] = legacy
                storage.favorite("/keep-legacy-queue")
                f.panel.act(SyncPanelAction.Open)
                assertEquals(mihon.data.sync.auth.SyncDiscoveryProblem.INCOMPATIBLE, f.panel.state.value.setupProblem)
                assertEquals(SyncSetupStep.ERROR, f.panel.state.value.setupStep)
                assertFalse(f.panel.state.value.connection!!.enabled)
                assertEquals(1L, f.panel.state.value.queuedTotal)
                assertEquals(0, f.git.server.requestCount)
                assertEquals(legacy, f.secure.values[key])
                f.panel.act(SyncPanelAction.BeginSetup)
                assertEquals(mihon.data.sync.auth.SyncDiscoveryProblem.INCOMPATIBLE, f.panel.state.value.setupProblem)
                assertEquals(0, f.git.server.requestCount)
                val result = f.runtime.coordinator.synchronize(mihon.domain.sync.runtime.SyncTrigger.MANUAL)
                assertEquals(mihon.domain.sync.runtime.SyncRunProblem.INVALID_DATA, result.problem)
                assertEquals(legacy, f.secure.values[key])
                assertEquals(0, f.repositoryTokens.size)
                assertNull(f.runtime.connection()?.protectionMode)
            }
        }
    }

    @Test
    fun `onboarding disconnected legacy binding permits fresh setup without consuming the old queue`() = runBlocking {
        open().use { storage ->
            SyncOnboardingFixture(storage).use { f ->
                storage.connect("legacy-device", f.repository)
                f.authorize()
                val key = "space-" + java.security.MessageDigest.getInstance("SHA-256")
                    .digest("1:space".encodeToByteArray()).joinToString("") { "%02x".format(it) }
                val legacy = """{"recovery":{"rawKeyset":"legacy-test-material"},"actorId":"legacy-device","epoch":1}"""
                f.secure.values[key] = legacy
                storage.favorite("/keep-legacy-queue")
                f.panel.act(SyncPanelAction.Open)
                assertEquals(mihon.data.sync.auth.SyncDiscoveryProblem.INCOMPATIBLE, f.panel.state.value.setupProblem)
                val queued = mihon.data.sync.journal.SyncLocalJournal(storage.handler).pendingEvents("space", 1)
                f.panel.act(SyncPanelAction.Ask(mihon.data.sync.runtime.SyncPanelQuestion.DISCONNECT))
                f.panel.act(SyncPanelAction.ConfirmQuestion)
                assertNull(f.runtime.credentials.read())
                assertFalse(f.runtime.connection()!!.enabled)
                f.authorize()
                f.panel.act(SyncPanelAction.Synchronize)
                withTimeout(5_000) { f.panel.state.first { !it.setupBusy } }
                assertEquals(SyncSetupStep.NEW_PASSWORD, f.panel.state.value.setupStep)
                f.panel.act(SyncPanelAction.SubmitPassword(""))
                withTimeout(10_000) { f.panel.state.first { it.setupStep == SyncSetupStep.COMPLETE } }
                assertTrue(f.runtime.connection()!!.spaceId != "space")
                assertEquals("none", f.runtime.connection()!!.protectionMode)
                assertEquals(legacy, f.secure.values[key])
                assertEquals(
                    queued,
                    mihon.data.sync.journal.SyncLocalJournal(storage.handler).pendingEvents("space", 1),
                )
                assertEquals(0, f.userRepoPosts)
            }
        }
    }

    @Test
    fun `onboarding resumes after database binding succeeds but connected marker persistence fails`() = runBlocking {
        open().use { storage ->
            storage.favorite("/first-import")
            SyncOnboardingFixture(storage).use { f ->
                f.authorize()
                f.runtime.preferences.importPaused.set(true)
                f.secure.rejectConnectedSetup = true
                f.begin()
                f.panel.act(SyncPanelAction.SubmitPassword("crash-window-password"))
                withTimeout(10_000) { f.panel.state.first { it.setupStep == SyncSetupStep.ERROR } }
                assertEquals("password", f.runtime.connection()!!.protectionMode)
                val before = f.secure.values.entries.single { it.key.startsWith("sync-setup-v3-") }.value
                assertTrue(before.contains("\"stage\":\"SPACE_CONFIRMED\""))
                f.runtime.stopPanel()
                f.secure.rejectConnectedSetup = false
                val reopened = f.runtime()
                try {
                    val panel = reopened.panel as SyncPanelController
                    val requests = f.git.server.requestCount
                    panel.act(SyncPanelAction.Open)
                    assertEquals(SyncSetupStep.MERGING, panel.state.value.setupStep)
                    assertEquals(requests, f.git.server.requestCount)
                    assertEquals(1L, panel.state.value.importRemaining)
                    assertEquals(before, f.secure.values.entries.single { it.key.startsWith("sync-setup-v3-") }.value)
                    panel.act(SyncPanelAction.ResumeImport)
                    withTimeout(10_000) { panel.state.first { it.setupStep == SyncSetupStep.COMPLETE } }
                    assertEquals(0, f.userRepoPosts)
                    assertEquals(0, f.repositorySettingsWrites)
                    assertTrue(panel.state.value.notice?.setupCompleted == true)
                    assertEquals(0L, panel.state.value.importRemaining)
                    assertFalse(f.secure.values.keys.any { it.startsWith("sync-setup-v3-") })
                    assertFalse(f.secure.values.toString().contains("crash-window-password"))
                } finally {
                    reopened.stopPanel()
                }
            }
        }
    }

    @Test
    fun `connected onboarding marker completes idempotently after binding commit`() = runBlocking {
        open().use { storage ->
            SyncOnboardingFixture(storage).use { f ->
                f.created = true
                f.authorize()
                f.begin()
                f.panel.act(SyncPanelAction.SubmitPassword(""))
                withTimeout(10_000) { f.panel.state.first { it.setupStep == SyncSetupStep.COMPLETE } }

                val remoteDescriptor = SyncSpaceDescriptorCodec.decode(
                    requireNotNull(f.git.file(f.repository.branch, SyncSpaceDescriptorCodec.PATH)),
                ).getOrThrow()
                val runtimeConnection = requireNotNull(f.runtime.connection()) {
                    "initial onboarding must create a local database binding"
                }
                assertEquals(remoteDescriptor.spaceId, runtimeConnection.spaceId)
                assertEquals(remoteDescriptor.generation, runtimeConnection.generation)
                val binding = requireNotNull(
                    f.runtime.onboarding.storage.connection(remoteDescriptor.spaceId, remoteDescriptor.generation),
                ) {
                    "initial onboarding must persist the secure connection binding"
                }
                assertEquals(remoteDescriptor, binding.material.material().descriptor)
                val bootstrap = Json.parseToJsonElement(
                    requireNotNull(f.git.file("main", ".mihon-sync/bootstrap")).decodeToString(),
                ).jsonObject
                val setup = StoredSyncSetup(
                    accountId = binding.accountId,
                    accountLogin = binding.accountLogin,
                    attemptId = UUID.randomUUID().toString(),
                    attemptNonce = bootstrap.getValue("attemptNonce").jsonPrimitive.content,
                    newSpace = true,
                    material = binding.material,
                    stage = SyncInitializationStage.CONNECTED,
                    repositoryId = binding.repositoryId,
                    owner = binding.owner,
                    repository = binding.repository,
                    branch = binding.branch,
                    defaultBranch = "main",
                    confirmedBootstrapCommitSha = f.git.head("main"),
                    confirmedBootstrapTreeSha = f.git.treeSha("main"),
                )
                f.runtime.onboarding.storage.save(setup, null)
                val writesBeforeRecovery = f.repositoryWrites
                val createsBeforeRecovery = f.userRepoPosts
                f.runtime.stopPanel()

                val reopened = f.runtime()
                try {
                    val panel = reopened.panel as SyncPanelController
                    panel.act(SyncPanelAction.Open)
                    assertEquals(SyncSetupStep.MERGING, panel.state.value.setupStep)
                    panel.act(SyncPanelAction.ResumeImport)
                    val recovered = withTimeout(10_000) {
                        panel.state.first {
                            !it.setupBusy && it.setupStep in setOf(SyncSetupStep.COMPLETE, SyncSetupStep.ERROR)
                        }
                    }

                    assertEquals(SyncSetupStep.COMPLETE, recovered.setupStep)
                    assertEquals(writesBeforeRecovery, f.repositoryWrites)
                    assertEquals(createsBeforeRecovery, f.userRepoPosts)
                    assertNull(f.runtime.onboarding.storage.pending(f.accountId))
                } finally {
                    reopened.stopPanel()
                }
            }
        }
    }

    @Test
    fun `losing bootstrap attempt joins a verified winner in the same repository`() = runBlocking {
        open().use { storage ->
            SyncOnboardingFixture(storage).use { f ->
                val winnerMaterial = SyncSpaceCrypto.create("winning-space", 1, "winner-password")
                val winnerIntent = SyncInitializationIntent(
                    accountId = f.accountId,
                    repositoryId = 99,
                    defaultBranch = "main",
                    attemptNonce = "winner-attempt-nonce-0001",
                    stage = SyncInitializationStage.VERIFIED_EMPTY,
                )
                val winnerResult = f.runtime.onboarding.transport("synthetic-token", winnerMaterial).initialize(
                    f.repository,
                    winnerMaterial.descriptor.spaceId,
                    winnerMaterial.descriptor.generation,
                    winnerIntent,
                ) {}
                assertTrue(winnerResult is mihon.domain.sync.transport.SyncInitializationResult.Initialized)

                val loserMaterial = SyncSpaceCrypto.create("losing-space", 1, "loser-password")
                val loserSetup = StoredSyncSetup(
                    accountId = f.accountId,
                    accountLogin = f.accountLogin,
                    attemptId = "loser-attempt-id-0001",
                    attemptNonce = "loser-attempt-nonce-0001",
                    newSpace = true,
                    material = StoredSyncMaterial.from(loserMaterial),
                    stage = SyncInitializationStage.BOOTSTRAP_SUBMITTING,
                    repositoryId = 99,
                    owner = f.repository.owner,
                    repository = f.repository.name,
                    branch = f.repository.branch,
                    defaultBranch = "main",
                )
                f.runtime.onboarding.storage.save(loserSetup, null)
                f.authorize()
                storage.favorite("/queued-during-losing-attempt")
                val requestsBefore = f.git.server.requestCount

                f.panel.act(SyncPanelAction.Open)
                f.panel.act(SyncPanelAction.BeginSetup)
                val discoveredWinner = withTimeout(10_000) {
                    f.panel.state.first {
                        !it.setupBusy && it.setupStep in setOf(SyncSetupStep.ERROR, SyncSetupStep.UNLOCK)
                    }
                }
                assertEquals(SyncSetupStep.UNLOCK, discoveredWinner.setupStep)
                assertNull(f.runtime.onboarding.pending())
                assertFalse(f.secure.values.values.any { "losing-space" in it })
                assertFalse(f.secure.values.values.any { "loser-attempt-nonce-0001" in it })

                f.panel.act(SyncPanelAction.SubmitPassword("winner-password"))
                val completed = withTimeout(10_000) {
                    f.panel.state.first {
                        !it.setupBusy && it.setupStep in setOf(SyncSetupStep.ERROR, SyncSetupStep.COMPLETE)
                    }
                }

                assertEquals(SyncSetupStep.COMPLETE, completed.setupStep)
                assertEquals(winnerMaterial.descriptor.spaceId, f.runtime.connection()?.spaceId)
                assertNull(f.runtime.onboarding.pending())
                assertEquals(1, f.git.contentsPutBodies.size, "the losing attempt must not issue another PUT")
                assertEquals(0, f.userRepoPosts)
                assertEquals(0, f.repositorySettingsWrites)
                assertTrue(f.git.server.requestCount > requestsBefore)
                assertTrue(
                    mihon.data.sync.journal.SyncLocalJournal(storage.handler)
                        .pendingEvents("winning-space", 1).isEmpty(),
                    "joining the winner must resume the queued local work",
                )
            }
        }
    }

    @Test
    fun `onboarding invalid nonempty passwords cannot create an unprotected space`() = runBlocking {
        open().use { storage ->
            SyncOnboardingFixture(storage).use { f ->
                f.authorize()
                f.begin()
                for ((password, problem) in listOf(
                    "界".repeat(342) to mihon.data.sync.runtime.SyncPasswordProblem.TOO_LONG,
                    "\uD800" to mihon.data.sync.runtime.SyncPasswordProblem.INVALID,
                )) {
                    f.panel.act(SyncPanelAction.SubmitPassword(password))
                    assertEquals(problem, f.panel.state.value.passwordProblem)
                    assertEquals(SyncSetupStep.NEW_PASSWORD, f.panel.state.value.setupStep)
                    assertEquals(0, f.userRepoPosts)
                    assertEquals(0, f.repositorySettingsWrites)
                    assertNull(f.runtime.connection())
                    assertFalse(f.secure.values.keys.any { it.startsWith("sync-setup-v3-") })
                }
            }
        }
    }

    @Test
    fun `onboarding verified password repairs a damaged local data key while preserving queued work`() = runBlocking {
        open().use { storage ->
            SyncOnboardingFixture(storage).use { f ->
                f.existing("correct-password")
                f.authorize()
                f.begin()
                f.panel.act(SyncPanelAction.SubmitPassword("correct-password"))
                withTimeout(10_000) { f.panel.state.first { it.setupStep == SyncSetupStep.COMPLETE } }
                storage.favorite("/preserved-work")
                val binding = f.secure.values.entries.single { it.key.startsWith("space-") }
                val json = kotlinx.serialization.json.Json.parseToJsonElement(binding.value)
                    as kotlinx.serialization.json.JsonObject
                val material = json.getValue("material") as kotlinx.serialization.json.JsonObject
                f.secure.values[binding.key] = kotlinx.serialization.json.JsonObject(
                    json + (
                        "material" to kotlinx.serialization.json.JsonObject(
                            material + (
                                "keyHex" to kotlinx.serialization.json.JsonPrimitive("00".repeat(32))
                                ),
                        )
                        ),
                ).toString()
                val damaged = f.runtime.coordinator.synchronize(mihon.domain.sync.runtime.SyncTrigger.MANUAL)
                assertEquals(mihon.domain.sync.runtime.SyncRunStatus.FAILED, damaged.status)
                assertEquals(mihon.domain.sync.runtime.SyncRunProblem.INVALID_DATA, damaged.problem)
                f.begin()
                assertEquals(SyncSetupStep.UNLOCK, f.panel.state.value.setupStep)
                f.panel.act(SyncPanelAction.SubmitPassword("wrong-password"))
                withTimeout(5_000) { f.panel.state.first { it.passwordProblem != null } }
                assertEquals(
                    1L,
                    storage.handler.await {
                        sync_journalQueries.getPendingCategoryCounts("space", 1).executeAsList().sumOf { it.count }
                    },
                )
                f.panel.act(SyncPanelAction.SubmitPassword("correct-password"))
                withTimeout(10_000) {
                    f.panel.state.first {
                        it.setupStep == SyncSetupStep.COMPLETE || it.setupStep == SyncSetupStep.ERROR
                    }
                }
                assertEquals(SyncSetupStep.COMPLETE, f.panel.state.value.setupStep)
                assertEquals(0L, f.panel.state.value.queuedTotal)
                assertEquals(0, f.userRepoPosts)
            }
        }
    }

    @Test
    fun `onboarding public repository stops uploads with explicit privacy feedback and retains queue`() = runBlocking {
        open().use { storage ->
            SyncOnboardingFixture(storage).use { f ->
                f.existing("")
                f.authorize()
                f.begin()
                storage.favorite("/private-work")
                f.repositoryPrivate = false
                val before = f.repositoryWrites
                val result = f.runtime.coordinator.synchronize(mihon.domain.sync.runtime.SyncTrigger.MANUAL)
                assertEquals(mihon.domain.sync.runtime.SyncRunProblem.REPOSITORY_NOT_PRIVATE, result.problem)
                assertEquals(before, f.repositoryWrites)
                assertEquals(
                    1L,
                    storage.handler.await {
                        sync_journalQueries.getPendingCategoryCounts("space", 1).executeAsList().sumOf { it.count }
                    },
                )
                f.repositoryPrivate = true
                assertEquals(
                    mihon.domain.sync.runtime.SyncRunStatus.SUCCESS,
                    f.runtime.coordinator.synchronize(mihon.domain.sync.runtime.SyncTrigger.MANUAL).status,
                )
            }
        }
    }

    @Test
    fun `onboarding public space stops before binding with privacy feedback`() = runBlocking {
        open().use { storage ->
            SyncOnboardingFixture(storage).use { f ->
                f.existing("private-password")
                f.authorize()
                f.begin()
                assertEquals(SyncSetupStep.UNLOCK, f.panel.state.value.setupStep)
                f.repositoryPrivate = false
                val before = f.repositoryWrites
                f.panel.act(SyncPanelAction.SubmitPassword("private-password"))
                withTimeout(5_000) { f.panel.state.first { it.setupStep == SyncSetupStep.ERROR } }
                assertEquals(
                    mihon.data.sync.auth.SyncDiscoveryProblem.REPOSITORY_NOT_PRIVATE,
                    f.panel.state.value.setupProblem,
                )
                assertNull(f.runtime.connection())
                assertEquals(before, f.repositoryWrites)
            }
        }
    }

    @Test
    fun `opening observes upload queue rather than existing library totals`() = runBlocking {
        open().use { storage ->
            storage.favorite("/before-connection")
            storage.connect("local", repository)
            storage.favorite("/queued-one")
            withPanel(storage) { panel, _ ->
                panel.act(SyncPanelAction.Open)
                assertTrue(panel.state.value.visible)
                assertTrue(panel.state.value.loaded)
                assertEquals(1L, panel.state.value.queuedMembership)
                assertEquals(1L, panel.state.value.queuedFavorites)
                assertEquals(0L, panel.state.value.queuedFollows)
                assertEquals(0L, panel.state.value.queuedReading)
                assertEquals(1L, panel.state.value.importRemaining)
                assertEquals(2, storage.manga.getLibraryManga().size)
            }
        }
    }

    @Test
    fun `settings return and close retain local schedule while clearing session selection`() = runBlocking {
        open().use { storage ->
            storage.connect("local", repository)
            withPanel(storage) { panel, runtime ->
                panel.act(SyncPanelAction.Open)
                val due = panel.state.value.nextSyncAtMillis
                assertEquals(3_601_000L, due)
                panel.act(SyncPanelAction.Navigate(SyncPanelPage.SETTINGS))
                panel.act(SyncPanelAction.SetPeriod(15))
                panel.act(SyncPanelAction.SetStartup(false))
                panel.act(SyncPanelAction.SetDeviceName("电脑 "))
                assertEquals("电脑 ", panel.state.value.deviceName)
                panel.act(SyncPanelAction.SetDeviceName("电脑 B"))
                panel.act(SyncPanelAction.Back)
                assertEquals(SyncPanelPage.MAIN, panel.state.value.page)
                assertEquals(15, runtime.preferences.intervalMinutes())
                assertFalse(runtime.preferences.startup.get())
                assertEquals("电脑 B", runtime.preferences.deviceName.get())
                panel.act(SyncPanelAction.SelectionMode(true))
                panel.act(SyncPanelAction.Close)
                panel.act(SyncPanelAction.Open)
                assertFalse(panel.state.value.selecting)
                assertNull(panel.state.value.notice)
                assertEquals(901_000L, panel.state.value.nextSyncAtMillis)
            }
        }
    }

    @Test
    fun `all selection covers unloaded rows and freezes confirmation before new arrivals`() = runBlocking {
        open().use { storage ->
            storage.connect("local", repository)
            pending(storage, 120)
            withPanel(storage) { panel, _ ->
                panel.act(SyncPanelAction.Open)
                assertEquals(120L, panel.state.value.pendingTotal)
                assertTrue(panel.state.value.pending.size in 1..100)
                assertTrue(panel.state.value.hasMore)
                panel.act(SyncPanelAction.SelectAll)
                assertEquals(120, panel.state.value.selected.size)
                panel.act(SyncPanelAction.InvertSelection)
                assertTrue(panel.state.value.selected.isEmpty())
                panel.act(SyncPanelAction.SelectAll)
                panel.act(
                    SyncPanelAction.PrepareDecision(SyncCancellationDecision.KEEP_LOCAL, SyncDecisionScope.SELECTED),
                )
                val confirmation = panel.state.value.confirmation
                assertNotNull(confirmation)
                assertEquals(120L, confirmation!!.manga)
                pending(storage, 1, offset = 120)
                val frozen = storage.handler.await {
                    sync_inboxQueries.getBulkItems(confirmation.jobId, 1000).executeAsList()
                }
                assertEquals(120, frozen.size)
                assertEquals(121, storage.manga.getLibraryManga().size)
                panel.act(SyncPanelAction.CancelDecision)
                assertNull(panel.state.value.confirmation)
                assertEquals(120, panel.state.value.selected.size)
            }
        }
    }

    @Test
    fun `range selection uses complete ordered pending identities and closes without decisions`() = runBlocking {
        open().use { storage ->
            storage.connect("local", repository)
            pending(storage, 5)
            withPanel(storage) { panel, _ ->
                panel.act(SyncPanelAction.Open)
                val ids = panel.state.value.pending.map { it.id }
                assertEquals(5, ids.size)
                panel.act(SyncPanelAction.ToggleItem(ids[1], range = true))
                panel.act(SyncPanelAction.ToggleItem(ids[4], range = true))
                assertEquals(ids.subList(1, 5).toSet(), panel.state.value.selected)
                panel.act(SyncPanelAction.Close)
                assertTrue(panel.state.value.selected.isEmpty())
                assertEquals(5, storage.projector.pending("space", 1).size)
            }
        }
    }

    protected fun database(driver: SqlDriver): Database {
        Database.Schema.create(driver)
        return Database(
            driver,
            History.Adapter(DateColumnAdapter),
            Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
        )
    }

    @Test
    fun `bulk confirmation performs real decisions and completed feedback never replays on reopen`() = runBlocking {
        open().use { storage ->
            storage.connect("local", repository)
            pending(storage, 3)
            withPanel(storage) { panel, _ ->
                panel.act(SyncPanelAction.Open)
                panel.act(SyncPanelAction.PrepareDecision(SyncCancellationDecision.KEEP_LOCAL, SyncDecisionScope.ALL))
                panel.act(SyncPanelAction.ConfirmDecision)
                withTimeout(5_000) { panel.state.first { it.notice?.bulk?.completed == 3L } }
                assertEquals(0L, panel.state.value.pendingTotal)
                assertEquals(0L, panel.state.value.queuedTotal)
                assertEquals(3, storage.manga.getLibraryManga().size)
                panel.act(SyncPanelAction.Navigate(SyncPanelPage.SETTINGS))
                panel.act(SyncPanelAction.Back)
                assertEquals(3L, panel.state.value.notice?.bulk?.completed)
                panel.act(SyncPanelAction.Close)
                panel.act(SyncPanelAction.Open)
                assertNull(panel.state.value.notice)
            }
        }
    }

    @Test
    fun `closing device authorization clears the code and never stores an unfinished credential`() = runBlocking {
        MockWebServer().use { auth ->
            auth.start()
            auth.enqueue(deviceCode())
            auth.enqueue(deviceCode("SECOND-CODE"))
            open().use { storage ->
                var now = 1_000L
                withPanel(storage, endpoints = endpoints(auth), clock = { now }) { panel, runtime ->
                    panel.act(SyncPanelAction.Open)
                    panel.act(SyncPanelAction.Authorize)
                    assertEquals(1_000L, panel.state.value.authRequestStartedAtMillis)
                    withTimeout(3_000) { panel.state.first { it.deviceCode != null } }
                    val code = requireNotNull(panel.state.value.deviceCode)
                    assertTrue(panel.claimDeviceCodeBrowser(code))
                    assertFalse(panel.claimDeviceCodeBrowser(code))
                    panel.act(SyncPanelAction.Close)
                    assertNull(panel.state.value.deviceCode)
                    assertNull(panel.state.value.authRequestStartedAtMillis)
                    assertFalse(panel.claimDeviceCodeBrowser(code))
                    assertFalse(panel.state.value.setupBusy)
                    assertNull(runtime.credentials.read())
                    panel.act(SyncPanelAction.Open)
                    assertNull(panel.state.value.deviceCode)
                    now = 10_000L
                    panel.act(SyncPanelAction.Authorize)
                    assertEquals(10_000L, panel.state.value.authRequestStartedAtMillis)
                    withTimeout(3_000) { panel.state.first { it.deviceCode?.userCode == "SECOND-CODE" } }
                    assertTrue(panel.claimDeviceCodeBrowser(requireNotNull(panel.state.value.deviceCode)))
                    panel.act(SyncPanelAction.CancelAuthorization)
                    assertNull(panel.state.value.authRequestStartedAtMillis)
                }
            }
        }
    }

    @Test
    fun `failed device code request stops the spinner and leaves retry available`() = runBlocking {
        MockWebServer().use { auth ->
            auth.start()
            auth.enqueue(MockResponse(code = 503, body = ""))
            open().use { storage ->
                withPanel(storage, endpoints = endpoints(auth)) { panel, runtime ->
                    panel.act(SyncPanelAction.Open)
                    panel.act(SyncPanelAction.Authorize)
                    withTimeout(3_000) { panel.state.first { it.authFailure == GitHubAuthFailureReason.HTTP } }
                    assertFalse(panel.state.value.setupBusy)
                    assertNull(panel.state.value.deviceCode)
                    assertNull(runtime.credentials.read())
                    assertEquals(1, auth.requestCount)
                }
            }
        }
    }

    @Test
    fun `closing repository discovery cancels its busy state and allows setup again`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder().body("""{"id":1,"login":"fixture-owner","type":"User"}""")
                    .headersDelay(1, java.util.concurrent.TimeUnit.SECONDS).build(),
            )
            repeat(2) { server.enqueue(MockResponse(body = """{"id":1,"login":"fixture-owner","type":"User"}""")) }
            server.enqueue(MockResponse(body = """{"installations":[]}"""))
            open().use { storage ->
                withPanel(storage, endpoints = endpoints(server)) { panel, runtime ->
                    runtime.credentials.replace(
                        null,
                        GitHubAccessToken("fixture", null, "bearer", emptySet(), null, null),
                    )
                    panel.act(SyncPanelAction.Open)
                    panel.act(SyncPanelAction.BeginSetup)
                    assertNotNull(server.takeRequest(3, java.util.concurrent.TimeUnit.SECONDS))
                    panel.act(SyncPanelAction.Close)
                    assertFalse(panel.state.value.setupBusy)
                    panel.act(SyncPanelAction.Open)
                    panel.act(SyncPanelAction.BeginSetup)
                    withTimeout(3_000) { panel.state.first { !it.setupBusy } }
                    assertEquals(SyncSetupStep.ERROR, panel.state.value.setupStep)
                }
            }
        }
    }

    @Test
    fun `paused durable batch cannot be replaced by a new decision`() = runBlocking {
        open().use { storage ->
            storage.connect("local", repository)
            pending(storage, 3)
            withPanel(storage) { panel, runtime ->
                val job = runtime.projector.startBulk("space", 1, SyncCancellationDecision.KEEP_LOCAL)
                runtime.preferences.activeBulkJob("space", 1).set(job)
                panel.act(SyncPanelAction.Open)
                assertEquals(3L, panel.state.value.bulk?.remaining)
                assertFalse(panel.state.value.bulk!!.running)
                panel.act(SyncPanelAction.PrepareDecision(SyncCancellationDecision.CONFIRM, SyncDecisionScope.ALL))
                assertNull(panel.state.value.confirmation)
                assertEquals(job, panel.state.value.bulk?.jobId)
                panel.act(SyncPanelAction.ResumeBulk)
                withTimeout(5_000) { panel.state.first { it.notice?.bulk?.completed == 3L } }
                assertEquals(3, storage.manga.getLibraryManga().size)
            }
        }
    }

    @Test
    fun `expired device authorization can acquire a fresh code without reusing the old value`() = runBlocking {
        MockWebServer().use { auth ->
            auth.start()
            auth.enqueue(deviceCode())
            auth.enqueue(MockResponse(body = """{"error":"expired_token"}"""))
            auth.enqueue(deviceCode("NEXT-CODE"))
            open().use { storage ->
                withPanel(storage, endpoints = endpoints(auth)) { panel, _ ->
                    panel.act(SyncPanelAction.Open)
                    panel.act(SyncPanelAction.Authorize)
                    withTimeout(4_000) { panel.state.first { it.authFailure != null } }
                    assertEquals(GitHubAuthFailureReason.EXPIRED, panel.state.value.authFailure)
                    panel.act(SyncPanelAction.Authorize)
                    withTimeout(3_000) { panel.state.first { it.deviceCode?.userCode == "NEXT-CODE" } }
                    val nextCode = requireNotNull(panel.state.value.deviceCode)
                    assertTrue(panel.claimDeviceCodeBrowser(nextCode))
                    assertFalse(panel.claimDeviceCodeBrowser(nextCode))
                    assertNull(panel.state.value.authFailure)
                    panel.act(SyncPanelAction.CancelAuthorization)
                }
            }
        }
    }

    @Test
    fun `inactive space stops a running durable batch without consuming its remaining decisions`() = runBlocking {
        open().use { storage ->
            storage.connect("local", repository)
            pending(storage, 3)
            val id = storage.projector.startBulk("space", 1, SyncCancellationDecision.KEEP_LOCAL)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val gate = object : tachiyomi.domain.creator.repository.CreatorArchiveBootstrap {
                override suspend fun awaitReady() {
                    storage.bootstrap.awaitReady()
                    entered.complete(Unit)
                    release.await()
                }
            }
            withPanel(storage, bootstrap = gate) { panel, runtime ->
                runtime.preferences.activeBulkJob("space", 1).set(id)
                panel.act(SyncPanelAction.Open)
                panel.act(SyncPanelAction.ResumeBulk)
                withTimeout(3_000) { entered.await() }
                storage.baseline.connectAndImport("other", 1, repository, "other-local", 1)
                release.complete(Unit)
                withTimeout(3_000) { panel.awaitBulkIdle() }
                assertEquals(3L, storage.projector.bulkProgress(id).queued)
            }
        }
    }

    @Test
    fun `switching spaces retains each paused bulk resume entry`() = runBlocking {
        open().use { storage ->
            storage.connect("local", repository)
            pending(storage, 3)
            val original = storage.projector.startBulk("space", 1, SyncCancellationDecision.KEEP_LOCAL)
            withPanel(storage) { panel, runtime ->
                runtime.preferences.activeBulkJob("space", 1).set(original)
                panel.act(SyncPanelAction.Open)
                assertEquals(original, panel.state.value.bulk?.jobId)
                storage.baseline.connectAndImport("other", 1, repository, "other-local", 1)
                pending(storage, 1, offset = 100, spaceId = "other")
                panel.act(SyncPanelAction.Open)
                panel.act(SyncPanelAction.PrepareDecision(SyncCancellationDecision.KEEP_LOCAL, SyncDecisionScope.ALL))
                panel.act(SyncPanelAction.ConfirmDecision)
                withTimeout(5_000) { panel.state.first { it.notice?.bulk?.completed == 1L } }
                storage.connect("local", repository)
                panel.act(SyncPanelAction.Open)
                assertEquals(original, panel.state.value.bulk?.jobId)
                assertEquals(3L, panel.state.value.bulk?.remaining)
            }
        }
    }

    @Test
    fun `pausing initial import still exchanges independent changes and resuming drains the baseline`() = runBlocking {
        open().use { storage ->
            storage.favorite("/baseline")
            SyncOnboardingFixture(storage).use { f ->
                f.existing("")
                f.authorize()
                f.runtime.preferences.importPaused.set(true)
                f.begin()
                assertEquals(SyncSetupStep.MERGING, f.panel.state.value.setupStep)
                storage.favorite("/independent")
                val result = f.runtime.coordinator.synchronize(mihon.domain.sync.runtime.SyncTrigger.MANUAL)
                assertEquals(1, result.uploaded)
                f.panel.act(SyncPanelAction.Open)
                assertTrue(f.panel.state.value.importPaused)
                assertEquals(1L, f.panel.state.value.importRemaining)
                assertEquals(0L, f.panel.state.value.queuedTotal)
                f.panel.act(SyncPanelAction.ResumeImport)
                withTimeout(5_000) { f.panel.state.first { it.setupStep == SyncSetupStep.COMPLETE } }
                assertFalse(f.panel.state.value.importPaused)
                assertEquals(0L, f.panel.state.value.queuedTotal)
            }
        }
    }

    @Test
    fun `cancelled history survives disconnected setup and a separate new manual round`() = runBlocking {
        open().use { storage ->
            storage.connect("local", repository)
            withPanel(storage) { panel, runtime ->
                val old = runtime.runStore.start("space", 1, mihon.domain.sync.runtime.SyncTrigger.MANUAL)
                assertTrue(runtime.runStore.claim(old.runId, "fixture-owner", 1))
                runtime.runStore.confirmed(
                    old.runId,
                    "fixture-owner",
                    mihon.data.sync.runtime.SyncProgressDirection.UPLOAD,
                    "batch",
                    1536,
                )
                runtime.runStore.cancel(old.runId)
                storage.handler.await { sync_journalQueries.disconnectSpace("space", 1) }
                panel.act(SyncPanelAction.Open)
                assertEquals(mihon.data.sync.runtime.SyncPanelRunSource.LATEST, panel.state.value.runSource)
                panel.act(SyncPanelAction.BeginSetup)
                assertEquals(SyncPanelPage.SETUP, panel.state.value.page)
                assertFalse(runtime.connection()!!.enabled)
                assertEquals(old.runId, runtime.runStore.latest("space", 1)!!.runId)
                assertEquals(1536L, runtime.runStore.get(old.runId)!!.confirmedItems)
                storage.handler.await { sync_journalQueries.activateSpace("space", 1) }
                panel.act(SyncPanelAction.Open)
                panel.act(SyncPanelAction.Synchronize)
                withTimeout(5_000) { panel.state.first { it.run != null && it.run!!.runId != old.runId } }
                withTimeout(5_000) {
                    panel.state.first { it.run?.state == mihon.data.sync.runtime.SyncRunState.BLOCKED }
                }
                assertEquals(mihon.data.sync.runtime.SyncPanelRunSource.ACTIVE, panel.state.value.runSource)
                assertEquals(0L, panel.state.value.run!!.confirmedItems)
                assertEquals(mihon.data.sync.runtime.SyncRunState.CANCELLED, runtime.runStore.get(old.runId)!!.state)
                assertEquals(1536L, runtime.runStore.get(old.runId)!!.confirmedItems)
            }
        }
    }

    @Test
    fun `resuming import never bypasses the durable user pause or creates another run`() = runBlocking {
        open().use { storage ->
            storage.favorite("/baseline")
            storage.connect("actor", repository)
            withPanel(storage) { panel, runtime ->
                runtime.preferences.importPaused.set(true)
                val run = runtime.runStore.start("space", 1, mihon.domain.sync.runtime.SyncTrigger.MANUAL)
                runtime.runStore.pause(run.runId)
                panel.act(SyncPanelAction.Open)
                panel.act(SyncPanelAction.ResumeImport)
                assertFalse(runtime.preferences.importPaused.get())
                repeat(10) {
                    kotlinx.coroutines.delay(20)
                    assertEquals(run.runId, runtime.runStore.latest("space", 1)!!.runId)
                    assertEquals(
                        mihon.data.sync.runtime.SyncRunState.PAUSED_USER,
                        runtime.runStore.get(run.runId)!!.state,
                    )
                }
            }
        }
    }

    private fun deviceCode(code: String = "FIRST-CODE") = MockResponse(
        body = """
            {"device_code":"fixture-secret","user_code":"$code",
            "verification_uri":"https://github.com/login/device","expires_in":600,"interval":1}
        """.trimIndent(),
    )

    private fun endpoints(server: MockWebServer) = GitHubAuthEndpoints(
        server.url("/device").toString(),
        server.url("/token").toString(),
        server.url("/").toString(),
    )

    private suspend fun SyncPanelController.act(action: SyncPanelAction) {
        dispatch(action)
        awaitIdle()
    }

    protected suspend fun withPanel(
        storage: SyncRuntimeStorageContract.Storage,
        endpoints: GitHubAuthEndpoints = GitHubAuthEndpoints(),
        bootstrap: tachiyomi.domain.creator.repository.CreatorArchiveBootstrap = storage.bootstrap,
        handler: DatabaseHandler = storage.handler,
        clock: () -> Long = { 1000L },
        block: suspend (SyncPanelController, SyncRuntime) -> Unit,
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = OkHttpClient()
        val secure = object : SyncSecureStore {
            private val values = mutableMapOf<String, String>()
            override suspend fun read(key: String) = values[key]
            override suspend fun compareAndSet(key: String, expected: String?, value: String?): Boolean {
                if (values[key] != expected) return false
                if (value == null) values.remove(key) else values[key] = value
                return true
            }
        }
        val defaults = InMemoryPreferenceStore()
        val strings = ConcurrentHashMap<String, Preference<String>>()
        val preferences = object : PreferenceStore by defaults {
            override fun getString(key: String, defaultValue: String) =
                strings.computeIfAbsent(key) { defaults.getString(key, defaultValue) }
        }
        val runtime = SyncRuntime(
            handler, bootstrap, storage.creators, storage.creators, { true }, secure,
            preferences, client, endpoints, clock = clock,
        )
        val panel = SyncPanelController(runtime, handler, scope, clock)
        try {
            block(panel, runtime)
        } finally {
            panel.stop()
            scope.cancel()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    private suspend fun pending(
        storage: SyncRuntimeStorageContract.Storage,
        count: Int,
        offset: Int = 0,
        spaceId: String = "space",
    ) {
        val inbox = SyncInboxStore(storage.handler)
        repeat(count) { index ->
            val number = index + offset
            val key = SyncObjectKey(SyncObjectType.MANGA, sourceId = "1", originalUrl = "/remote-$number")
            val add = SyncEventEnvelope(
                1, spaceId, 1, "remote-$number", 1, 1, SyncCategory.FAVORITE,
                listOf(SyncEffect("membership", key, SyncField.FAVORITE, SyncEffectKind.ADD)), SyncOrigin.USER,
                batchId = "add-$number",
            )
            val remove = add.copy(
                seq = 2,
                batchId = "remove-$number",
                effects = listOf(
                    add.effects.single().copy(kind = SyncEffectKind.REMOVE, parents = listOf(add.ref("membership"))),
                ),
            )
            assertTrue(
                inbox.ingest(
                    SyncBatch(
                        1,
                        spaceId,
                        1,
                        "add-$number",
                        listOf(add),
                        listOf(SyncObjectDescriptor(key, "漫画 $number")),
                    ),
                ).accepted,
            )
            while (storage.projector.project(spaceId, 1) == 50) Unit
            assertTrue(inbox.ingest(SyncBatch(1, spaceId, 1, "remove-$number", listOf(remove))).accepted)
            while (storage.projector.project(spaceId, 1) == 50) Unit
        }
    }
}

private fun legacySetupJson(
    material: SyncSpaceMaterial,
    newSpace: Boolean = true,
    submitted: Boolean = true,
    connected: Boolean = false,
): String {
    val storedMaterial = Json.encodeToString(StoredSyncMaterial.from(material))
    return """
        {
          "version": 2,
          "accountId": 1,
          "accountLogin": "fixture-owner",
          "attemptId": "legacy-pending-fixture-0001",
          "newSpace": $newSpace,
          "material": $storedMaterial,
          "submitted": $submitted,
          "repositoryId": 99,
          "owner": "fixture-owner",
          "repository": "mihon-sync",
          "branch": "mihon-sync-v1",
          "connected": $connected
        }
    """.trimIndent()
}
