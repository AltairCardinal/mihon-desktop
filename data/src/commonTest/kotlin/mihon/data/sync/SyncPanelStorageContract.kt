package mihon.data.sync

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mihon.data.sync.inbox.SyncInboxStore
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
import mihon.domain.sync.security.SyncSecureStore
import mihon.domain.sync.transport.SyncRepository
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
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
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.Mangas
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import java.util.concurrent.ConcurrentHashMap

@Timeout(30)
abstract class SyncPanelStorageContract {
    protected abstract fun open(): SyncRuntimeStorageContract.Storage
    private val repository = SyncRepository("fixture-owner", "private-sync", "mihon-sync-v1")

    @Test
    fun `onboarding creates plain space and automatically finishes actual baseline exchange`() = runBlocking {
        createsSpace("")
    }

    @Test
    fun `onboarding creates password space and automatically finishes actual baseline exchange`() = runBlocking {
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
                assertEquals(1, fixture.creationPosts)
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
                assertEquals(0, fixture.creationPosts)
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
                assertEquals(0, fixture.creationPosts)
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
                f.creationEntered = java.util.concurrent.CountDownLatch(1)
                f.creationRelease = java.util.concurrent.CountDownLatch(1)
                f.panel.act(SyncPanelAction.SubmitPassword(""))
                assertTrue(f.creationEntered!!.await(3, java.util.concurrent.TimeUnit.SECONDS))
                f.panel.act(SyncPanelAction.SubmitPassword("second-click"))
                f.panel.act(SyncPanelAction.Close)
                f.panel.act(SyncPanelAction.Open)
                f.panel.act(SyncPanelAction.Navigate(SyncPanelPage.SETTINGS))
                f.creationRelease!!.countDown()
                withTimeout(10_000) { f.panel.state.first { it.setupStep == SyncSetupStep.COMPLETE } }
                assertEquals(SyncPanelPage.SETTINGS, f.panel.state.value.page)
                assertNull(f.panel.state.value.notice)
                assertEquals(1, f.creationPosts)
            }
        }
    }

    @Test
    fun `onboarding paused merge resumes after restart without password or duplicate creation`() = runBlocking {
        open().use { storage ->
            storage.favorite("/before-onboarding")
            SyncOnboardingFixture(storage).use { f ->
                f.authorize()
                f.runtime.preferences.importPaused.set(true)
                f.begin()
                f.panel.act(SyncPanelAction.SubmitPassword("restart-password"))
                withTimeout(10_000) { f.panel.state.first { it.setupStep == SyncSetupStep.MERGING && !it.setupBusy } }
                assertEquals(1L, f.panel.state.value.importRemaining)
                assertTrue(f.secure.values.keys.any { it.startsWith("sync-setup-v2-") })
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
                    assertEquals(1, f.creationPosts)
                    assertFalse(f.secure.values.toString().contains("restart-password"))
                    assertFalse(f.secure.values.keys.any { it.startsWith("sync-setup-v2-") })
                } finally {
                    reopened.stopPanel()
                }
            }
        }
    }

    @Test
    fun `onboarding response lost after create resumes same remote repository without another POST`() = runBlocking {
        open().use { storage ->
            SyncOnboardingFixture(storage).use { f ->
                f.authorize()
                f.loseCreationResponse = true
                f.begin()
                f.panel.act(SyncPanelAction.SubmitPassword(""))
                withTimeout(10_000) {
                    f.panel.state.first {
                        it.setupStep == SyncSetupStep.COMPLETE ||
                            it.setupStep == SyncSetupStep.ERROR
                    }
                }
                if (f.panel.state.value.setupStep == SyncSetupStep.ERROR) {
                    f.panel.act(SyncPanelAction.RetrySetup)
                    withTimeout(10_000) { f.panel.state.first { it.setupStep == SyncSetupStep.COMPLETE } }
                }
                assertEquals(1, f.creationPosts)
                assertEquals("none", f.runtime.connection()?.protectionMode)
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
                assertEquals(1, f.creationPosts)
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
                val before = f.secure.values.entries.single { it.key.startsWith("sync-setup-v2-") }.value
                assertTrue(before.contains("\"connected\":false"))
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
                    assertEquals(before, f.secure.values.entries.single { it.key.startsWith("sync-setup-v2-") }.value)
                    panel.act(SyncPanelAction.ResumeImport)
                    withTimeout(10_000) { panel.state.first { it.setupStep == SyncSetupStep.COMPLETE } }
                    assertEquals(1, f.creationPosts)
                    assertTrue(panel.state.value.notice?.setupCompleted == true)
                    assertEquals(0L, panel.state.value.importRemaining)
                    assertFalse(f.secure.values.keys.any { it.startsWith("sync-setup-v2-") })
                    assertFalse(f.secure.values.toString().contains("crash-window-password"))
                } finally {
                    reopened.stopPanel()
                }
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
                    assertEquals(0, f.creationPosts)
                    assertNull(f.runtime.connection())
                    assertFalse(f.secure.values.keys.any { it.startsWith("sync-setup-v2-") })
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
                assertEquals(0, f.creationPosts)
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
            open().use { storage ->
                withPanel(storage, endpoints = endpoints(auth)) { panel, runtime ->
                    panel.act(SyncPanelAction.Open)
                    panel.act(SyncPanelAction.Authorize)
                    withTimeout(3_000) { panel.state.first { it.deviceCode != null } }
                    panel.act(SyncPanelAction.Close)
                    assertNull(panel.state.value.deviceCode)
                    assertFalse(panel.state.value.setupBusy)
                    assertNull(runtime.credentials.read())
                    panel.act(SyncPanelAction.Open)
                    assertNull(panel.state.value.deviceCode)
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

    private suspend fun withPanel(
        storage: SyncRuntimeStorageContract.Storage,
        endpoints: GitHubAuthEndpoints = GitHubAuthEndpoints(),
        bootstrap: tachiyomi.domain.creator.repository.CreatorArchiveBootstrap = storage.bootstrap,
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
            storage.handler, bootstrap, storage.creators, storage.creators, { true }, secure,
            preferences, client, endpoints, clock = { 1000L },
        )
        val panel = SyncPanelController(runtime, storage.handler, scope) { 1000L }
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
