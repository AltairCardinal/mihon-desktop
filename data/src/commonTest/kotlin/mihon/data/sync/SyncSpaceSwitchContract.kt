package mihon.data.sync

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mihon.data.sync.auth.SyncSpaceDiscovery
import mihon.data.sync.journal.SyncOutboxStore
import mihon.data.sync.runtime.SyncPanelAction
import mihon.data.sync.runtime.SyncPanelController
import mihon.data.sync.runtime.SyncPanelPage
import mihon.data.sync.runtime.SyncPanelQuestion
import mihon.data.sync.runtime.SyncSetupStep
import mihon.data.sync.runtime.SyncSpaceRecoveryReason
import mihon.data.sync.runtime.SyncSpaceSwitchPurpose
import mihon.data.sync.runtime.SyncSpaceSwitchStage
import mihon.data.sync.transport.SyncBatchSyncService
import mihon.domain.sync.SyncCancellationDecision
import mihon.domain.sync.crypto.SyncSpaceMaterial
import mihon.domain.sync.runtime.SyncRunStatus
import mihon.domain.sync.runtime.SyncTrigger
import mihon.domain.sync.security.SyncSecureStoreException
import mihon.domain.sync.transport.SyncInitializationStage
import mihon.domain.sync.transport.SyncRepository
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.Mangas
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter

abstract class SyncSpaceSwitchContract {
    protected abstract fun open(): SyncRuntimeStorageContract.Storage

    @Test
    fun `explicit switch confirms before activating current baseline`() = runBlocking {
        open().use { storage ->
            SyncSpaceSwitchFixture(storage).use { fixture ->
                fixture.prepareOld()
                val target = fixture.target(100, "another-sync", "another-space")
                fixture.target(101, "third-sync", "third-space")
                storage.favorite("/current-local-change")
                val oldConnection = fixture.old.runtime.connection()
                val oldPending = requireNotNull(fixture.old.runtime.onboarding.storage.pending(1L))
                val oldCredential = fixture.old.runtime.credentials.read()
                val writes = fixture.writes.get()
                fixture.old.panel.act(SyncPanelAction.ConnectOtherSpace)
                withTimeout(5_000) {
                    fixture.old.panel.state.first { !it.setupBusy && it.setupStep == SyncSetupStep.CHOOSE_SPACE }
                }
                assertEquals(2, fixture.old.panel.state.value.spaces.size, "no automatic candidate selection")
                assertEquals(oldConnection, fixture.old.runtime.connection())
                assertEquals(oldPending, fixture.old.runtime.onboarding.storage.pending(1L))
                assertEquals(writes, fixture.writes.get(), "discovery is read-only")
                val choice = fixture.old.panel.state.value.spaces.single { it.repositoryId == target.id }
                fixture.old.panel.act(SyncPanelAction.ChooseSpace(choice))
                withTimeout(5_000) { fixture.old.panel.state.first { it.question == SyncPanelQuestion.CONNECT_SPACE } }
                assertEquals(target.repository, fixture.old.panel.state.value.switchTargetRepository)
                assertEquals(oldConnection, fixture.old.runtime.connection(), "choice is not activation")
                assertEquals(writes, fixture.writes.get(), "no upload before explicit confirmation")
                fixture.old.panel.act(SyncPanelAction.ConfirmQuestion)
                withTimeout(5_000) {
                    fixture.old.panel.state.first { !it.setupBusy && it.connection?.spaceId == "another-space" }
                }
                assertEquals(oldPending, fixture.old.runtime.onboarding.storage.pending(1L))
                assertEquals(oldCredential, fixture.old.runtime.credentials.read())
                val importEntries = storage.handler.await {
                    sync_importQueries.countPendingImports("another-space", 1).executeAsOne()
                }
                assertTrue(importEntries >= 2L, "baseline freezes current local state")
                assertNotNull(fixture.old.runtime.onboarding.storage.connection("space", 1))
                assertTrue(
                    storage.handler.await {
                        sync_importQueries.countPendingImports("space", 1).executeAsOne() > 0L
                    },
                    "old unfinished import remains",
                )
                assertEquals(writes, fixture.writes.get(), "import is paused: old batches are never retargeted")
            }
        }
    }

    @Test
    fun `create preparation and close preserve old setup and perform no remote writes`() = runBlocking {
        open().use { storage ->
            SyncSpaceSwitchFixture(storage).use { fixture ->
                fixture.prepareOld()
                val connection = fixture.old.runtime.connection()
                val oldPending = fixture.old.runtime.onboarding.storage.pending(1L)
                val writes = fixture.writes.get()
                fixture.old.panel.act(SyncPanelAction.CreateNewSpace)
                assertEquals(SyncPanelQuestion.CREATE_NEW_SPACE, fixture.old.panel.state.value.question)
                fixture.old.panel.act(SyncPanelAction.ConfirmQuestion)
                assertEquals(SyncSetupStep.PREPARE_REPOSITORY, fixture.old.panel.state.value.setupStep)
                fixture.old.panel.act(SyncPanelAction.Close)
                assertEquals(connection, fixture.old.runtime.connection())
                assertEquals(oldPending, fixture.old.runtime.onboarding.storage.pending(1L))
                assertEquals(writes, fixture.writes.get())
                assertEquals(
                    SyncRunStatus.SKIPPED,
                    fixture.old.runtime.coordinator.synchronize(SyncTrigger.PERIODIC).status,
                )
                fixture.old.panel.act(SyncPanelAction.Open)
                assertNotNull(fixture.old.panel.state.value.recovery)
            }
        }
    }

    @Test
    fun `unfinished target checkpoint resumes after save without overwriting original pending`() = runBlocking {
        open().use { storage ->
            SyncSpaceSwitchFixture(storage).use { fixture ->
                fixture.prepareOld()
                val target = fixture.target(100, "another-sync", "another-space")
                val runtime = fixture.old.runtime
                val oldPending = runtime.onboarding.storage.pending(1L)
                val intent = runtime.beginSwitch(SyncSpaceSwitchPurpose.CONNECT)
                val found = (runtime.onboarding.discover() as SyncSpaceDiscovery.Found).space
                fixture.old.secure.afterWrite = { key, _ ->
                    if (key.startsWith("sync-switch-setup-v1-")) throw SyncSecureStoreException()
                }
                Assertions.assertThrows(Exception::class.java) {
                    runBlocking {
                        runtime.onboarding.join(
                            found,
                            SyncSpaceMaterial(found.descriptor, null),
                            intent,
                        )
                    }
                }
                fixture.old.secure.afterWrite = null
                val reopened = fixture.old.runtime()
                val resumed = requireNotNull(reopened.activeSwitch())
                assertNotNull(resumed.target, "saved target checkpoint must be recovered")
                assertEquals(target.id, resumed.target?.repositoryId)
                assertEquals(oldPending, reopened.onboarding.storage.pending(1L))
                assertEquals("space", reopened.connection()?.spaceId)
                assertEquals(SyncRunStatus.SKIPPED, reopened.coordinator.synchronize(SyncTrigger.PERIODIC).status)
            }
        }
    }

    @Test
    fun `activation recovers each secure commit boundary locally exactly once`() = runBlocking {
        val boundaries = listOf("pointer", "target-binding", "connected-checkpoint", "complete")
        for (boundary in boundaries) {
            open().use { storage ->
                SyncSpaceSwitchFixture(storage).use { fixture ->
                    fixture.prepareOld()
                    fixture.target(100, "another-sync", "another-space")
                    val runtime = fixture.old.runtime
                    val oldPending = runtime.onboarding.storage.pending(1L)
                    var intent = runtime.beginSwitch(SyncSpaceSwitchPurpose.CONNECT)
                    val found = (runtime.onboarding.discover() as SyncSpaceDiscovery.Found).space
                    runtime.onboarding.join(found, SyncSpaceMaterial(found.descriptor, null), intent)
                    intent = requireNotNull(runtime.activeSwitch())
                    val setup = runtime.confirmSwitch(intent)
                    var faulted = false
                    val crash: (String, String?) -> Unit = { key, value ->
                        val intentKey = key.startsWith("sync-switch-intent-v1-")
                        val hasTargetConnection = value?.contains("targetConnection\":{") == true
                        val hit = when (boundary) {
                            "pointer" -> intentKey && hasTargetConnection
                            "target-binding" -> key.startsWith("space-")
                            "connected-checkpoint" ->
                                key.startsWith("sync-switch-setup-v1-") && value?.contains("CONNECTED") == true
                            else -> key.startsWith("sync-switch-intent-v1-") && value?.contains("COMPLETE") == true
                        }
                        if (hit && !faulted) {
                            faulted = true
                            throw SyncSecureStoreException()
                        }
                    }
                    if (boundary == "complete") {
                        fixture.old.secure.beforeWrite = crash
                    } else {
                        fixture.old.secure.afterWrite = crash
                    }
                    Assertions.assertThrows(Exception::class.java) {
                        runBlocking { runtime.onboarding.resume(setup) }
                    }
                    assertTrue(faulted, "boundary $boundary reached")
                    fixture.old.secure.afterWrite = null
                    fixture.old.secure.beforeWrite = null
                    val reads = fixture.old.git.snapshotReads
                    val writes = fixture.writes.get()
                    val reopened = fixture.old.runtime()
                    assertEquals("another-space", reopened.connection()?.spaceId)
                    assertEquals("another-space", reopened.connection()?.spaceId)
                    assertEquals(reads, fixture.old.git.snapshotReads, "cold recovery is local")
                    assertEquals(writes, fixture.writes.get(), "cold recovery never publishes")
                    assertEquals(oldPending, reopened.onboarding.storage.pending(1L))
                    assertEquals(
                        1L,
                        storage.handler.await {
                            sync_importQueries.getImport(intent.intentId).executeAsOne().total
                        },
                    )
                }
            }
        }
    }

    @Test
    fun `success settles setup without recursive lock and preserves real confirmed result`() = runBlocking {
        open().use { storage ->
            SyncOnboardingFixture(storage).use { fixture ->
                storage.favorite("/success-favorite")
                val material = fixture.existing("")
                fixture.authorize()
                val space = (fixture.runtime.onboarding.discover() as SyncSpaceDiscovery.Found).space
                val setup = fixture.runtime.onboarding.join(space, material)
                fixture.runtime.onboarding.resume(setup)
                val result = withTimeout(5_000) {
                    fixture.runtime.coordinator.synchronize(SyncTrigger.MANUAL)
                }
                assertEquals(SyncRunStatus.SUCCESS, result.status)
                assertTrue(result.uploaded > 0)
                assertEquals(null, fixture.runtime.onboarding.storage.pending(1L))
            }
        }
    }

    @Test
    fun `verified repository rename updates both bindings and resumes old connected checkpoint`() = runBlocking {
        open().use { storage ->
            SyncSpaceSwitchFixture(storage).use { fixture ->
                fixture.prepareOld()
                val renamed = SyncRepository("fixture-owner", "renamed-space", fixture.old.repository.branch)
                fixture.renamedOld = renamed
                fixture.old.git.renameRepository(renamed)
                val material = fixture.old.runtime.onboarding.storage.connection("space", 1)?.material
                val writes = fixture.writes.get()
                fixture.old.panel.act(SyncPanelAction.RecheckSpace)
                fixture.old.panel.awaitRecoveryIdle()
                assertEquals(null, fixture.old.panel.state.value.recovery)
                assertEquals(renamed, fixture.old.runtime.connection()?.repository)
                assertEquals(material, fixture.old.runtime.onboarding.storage.connection("space", 1)?.material)
                assertEquals(renamed, fixture.old.runtime.onboarding.storage.pending(1L)?.repository())
                assertEquals(true, fixture.old.panel.state.value.notice?.spaceAddressUpdated)
                val checkpoint = requireNotNull(fixture.old.runtime.onboarding.storage.pending(1L))
                fixture.old.runtime.onboarding.resume(checkpoint)
                assertEquals(writes, fixture.writes.get(), "CONNECTED checkpoint must not repeat bootstrap")
            }
        }
    }

    @Test
    fun `successive switches retain sealed checkpoints and freeze inactive target current baseline`() = runBlocking {
        open().use { storage ->
            SyncSpaceSwitchFixture(storage).use { fixture ->
                fixture.prepareOld()
                val first = fixture.target(100, "another-sync", "another-space")
                val second = fixture.target(101, "third-sync", "third-space")
                suspend fun activate(target: SyncSpaceSwitchFixture.Target): String {
                    val runtime = fixture.old.runtime
                    val intent = runtime.beginSwitch(SyncSpaceSwitchPurpose.CONNECT)
                    val discovery = runtime.onboarding.discover()
                    val spaces = when (discovery) {
                        is SyncSpaceDiscovery.Multiple -> discovery.spaces
                        is SyncSpaceDiscovery.Found -> listOf(discovery.space)
                        else -> error("expected real candidates")
                    }
                    val choice = spaces.single { it.repositoryId == target.id }
                    runtime.onboarding.join(
                        choice,
                        SyncSpaceMaterial(choice.descriptor, null),
                        intent,
                    )
                    val saved = requireNotNull(runtime.activeSwitch())
                    runtime.onboarding.resume(runtime.confirmSwitch(saved))
                    return intent.intentId
                }
                val original = fixture.old.runtime.onboarding.storage.pending(1L)
                val a = activate(first)
                storage.favorite("/new-current-state")
                val b = activate(second)
                val c = activate(first)
                assertEquals(original, fixture.old.runtime.onboarding.storage.pending(1L))
                assertEquals("CONNECTED", fixture.old.runtime.onboarding.storage.switchSetup(a)?.stage?.name)
                assertEquals("CONNECTED", fixture.old.runtime.onboarding.storage.switchSetup(b)?.stage?.name)
                val import = storage.handler.await { sync_importQueries.getImport(c).executeAsOne() }
                assertEquals("BACKUP_RESTORE", import.origin)
                assertEquals(2, import.total)
                assertTrue(
                    storage.handler.await {
                        sync_importQueries.countPendingImports("another-space", 1).executeAsOne()
                    }
                        >= 3L,
                    "old import remains alongside fresh baseline",
                )
            }
        }
    }

    @Test
    fun `future or corrupt switch records block scheduling and cannot be replaced by explicit setup`() = runBlocking {
        for (kind in listOf("pointer-future", "pointer-corrupt", "intent-future", "intent-corrupt")) {
            open().use { storage ->
                SyncSpaceSwitchFixture(storage).use { fixture ->
                    fixture.prepareOld()
                    val runtime = fixture.old.runtime
                    val intent = runtime.beginSwitch(SyncSpaceSwitchPurpose.CONNECT)
                    val key = if (kind.startsWith("pointer")) {
                        "sync-switch-pointer-v1-1"
                    } else {
                        "sync-switch-intent-v1-${intent.intentId}"
                    }
                    val invalid = if (kind.endsWith("future")) "{\"version\":99}" else "not-json"
                    fixture.old.secure.values[key] = invalid
                    val writes = fixture.writes.get()
                    val run = runtime.runStore.latest("space", 1)
                    val reopened = fixture.old.runtime()
                    Assertions.assertFalse(reopened.hasResumableRun())
                    Assertions.assertFalse(reopened.isRecoveryDue())
                    assertEquals(0L, reopened.recoveryDelayMillis())
                    Assertions.assertFalse(reopened.resumeIfNeeded())
                    assertEquals(SyncRunStatus.SKIPPED, reopened.coordinator.synchronize(SyncTrigger.PERIODIC).status)
                    assertEquals(run, reopened.runStore.latest("space", 1))
                    val panel = reopened.panel as SyncPanelController
                    try {
                        panel.act(SyncPanelAction.Open)
                        assertNotNull(panel.state.value.problem)
                        Assertions.assertFalse(panel.state.value.canChangeSpace)
                        panel.act(SyncPanelAction.BeginSetup)
                        panel.act(SyncPanelAction.ConnectOtherSpace)
                        panel.act(SyncPanelAction.RecheckSpace)
                        panel.awaitRecoveryIdle()
                        assertEquals(invalid, fixture.old.secure.values[key])
                        assertEquals(writes, fixture.writes.get())
                    } finally {
                        panel.stop()
                    }
                }
            }
        }
    }

    @Test
    fun `new empty same named repository uses independent sealed intent and keeps old initialization`() = runBlocking {
        open().use { storage ->
            SyncSpaceSwitchFixture(storage).use { fixture ->
                fixture.prepareOld()
                fixture.target(200, "mihon-sync", null)
                val oldPending = fixture.old.runtime.onboarding.storage.pending(1L)
                val writes = fixture.writes.get()
                val panel = fixture.old.panel
                panel.act(SyncPanelAction.CreateNewSpace)
                panel.act(SyncPanelAction.ConfirmQuestion)
                panel.act(SyncPanelAction.CheckRepositoryCreationPermission)
                assertEquals(SyncSetupStep.PREPARE_REPOSITORY, panel.state.value.setupStep)
                panel.act(SyncPanelAction.ConfirmRepositoryPrepared("mihon-sync"))
                try {
                    withTimeout(5_000) {
                        panel.state.first { !it.setupBusy && it.setupStep == SyncSetupStep.NEW_PASSWORD }
                    }
                } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
                    val state = panel.state.value
                    throw AssertionError(
                        "new repository was not selected: step=${state.setupStep}, busy=${state.setupBusy}, " +
                            "problem=${state.setupProblem}, permission=${state.creationPermissionProblem}",
                        error,
                    )
                }
                panel.act(
                    SyncPanelAction.SubmitCreateSpace(
                        requireNotNull(panel.state.value.createContextId),
                        mihon.data.sync.runtime.SyncCreateProtection.PASSWORD,
                        "new-secret",
                        true,
                    ),
                )
                withTimeout(5_000) { panel.state.first { it.question == SyncPanelQuestion.CONNECT_SPACE } }
                assertEquals(writes, fixture.writes.get(), "sealed password setup is still read-only")
                assertEquals("space", fixture.old.runtime.connection()?.spaceId)
                panel.act(SyncPanelAction.ConfirmQuestion)
                withTimeout(10_000) { panel.state.first { !it.setupBusy && it.connection?.spaceId != "space" } }
                val newConnection = requireNotNull(fixture.old.runtime.connection())
                assertEquals(
                    200,
                    fixture.old.runtime.onboarding.storage.connection(newConnection.spaceId, 1)?.repositoryId,
                )
                assertEquals(oldPending, fixture.old.runtime.onboarding.storage.pending(1L))
                assertTrue(newConnection.spaceId != "space")
                assertNotNull(fixture.old.runtime.onboarding.storage.connection("space", 1))
                assertTrue(fixture.old.secure.values.values.none { it.contains("new-secret") })
            }
        }
    }

    @Test
    fun `incorrect target password and changed account cannot activate or overwrite old binding`() = runBlocking {
        open().use { storage ->
            SyncSpaceSwitchFixture(storage).use { fixture ->
                fixture.prepareOld()
                fixture.target(100, "another-sync", "another-space", "target-password")
                val panel = fixture.old.panel
                val old = fixture.old.runtime.connection()
                panel.act(SyncPanelAction.ConnectOtherSpace)
                withTimeout(5_000) { panel.state.first { !it.setupBusy && it.spaces.size == 1 } }
                panel.act(SyncPanelAction.ChooseSpace(panel.state.value.spaces.single()))
                panel.act(SyncPanelAction.SubmitPassword("wrong-password"))
                withTimeout(5_000) { panel.state.first { !it.setupBusy } }
                assertEquals(old, fixture.old.runtime.connection())
                assertEquals(null, panel.state.value.question)
                panel.act(SyncPanelAction.SubmitPassword("target-password"))
                withTimeout(5_000) { panel.state.first { it.question == SyncPanelQuestion.CONNECT_SPACE } }
                fixture.old.accountId = 2L
                panel.act(SyncPanelAction.ConfirmQuestion)
                withTimeout(5_000) { panel.state.first { !it.setupBusy } }
                assertEquals(old, fixture.old.runtime.connection())
                assertNotNull(panel.state.value.setupProblem)
            }
        }
    }

    @Test
    fun `failed baseline transaction rolls back and locally resumes`() = runBlocking {
        open().use { storage ->
            SyncSpaceSwitchFixture(storage).use { fixture ->
                fixture.prepareOld()
                fixture.target(100, "another-sync", "another-space")
                storage.favorite("/queued-old-work")
                val runtime = fixture.old.runtime
                val oldEvents = storage.handler.await {
                    sync_journalQueries.getPendingEvents("space", 1, 256, 0).executeAsList()
                }
                val oldPending = runtime.onboarding.storage.pending(1L)
                var intent = runtime.beginSwitch(SyncSpaceSwitchPurpose.CONNECT)
                val choice = (runtime.onboarding.discover() as SyncSpaceDiscovery.Found).space
                runtime.onboarding.join(choice, SyncSpaceMaterial(choice.descriptor, null), intent)
                intent = requireNotNull(runtime.activeSwitch())
                val setup = runtime.confirmSwitch(intent)
                storage.driver.execute(
                    null,
                    "CREATE TRIGGER reject_switch_baseline BEFORE INSERT ON sync_import_entries " +
                        "WHEN NEW.import_id = '${intent.intentId}' BEGIN SELECT RAISE(ABORT, 'fixture abort'); END",
                    0,
                )
                Assertions.assertThrows(Exception::class.java) {
                    runBlocking { runtime.onboarding.resume(setup) }
                }
                assertEquals(
                    "space",
                    storage.handler.await {
                        sync_journalQueries.getActiveSpace().executeAsOne().space_id
                    },
                )
                assertEquals(
                    null,
                    storage.handler.await {
                        sync_importQueries.getImport(intent.intentId).executeAsOneOrNull()
                    },
                )
                assertEquals(
                    oldEvents,
                    storage.handler.await {
                        sync_journalQueries.getPendingEvents("space", 1, 256, 0).executeAsList()
                    },
                )
                assertNotNull(runtime.onboarding.storage.connection("another-space", 1))
                fixture.old.panel.stop()
                storage.driver.execute(null, "DROP TRIGGER reject_switch_baseline", 0)
                val requests = fixture.requests.get()
                val reopened = fixture.old.runtime()
                assertEquals("another-space", reopened.connection()?.spaceId)
                assertEquals("another-space", reopened.connection()?.spaceId)
                assertEquals(requests, fixture.requests.get(), "restart completes only local writes")
                assertEquals(oldPending, reopened.onboarding.storage.pending(1L))
                assertEquals(
                    oldEvents,
                    storage.handler.await {
                        sync_journalQueries.getPendingEvents("space", 1, 256, 0).executeAsList()
                    },
                )
                assertEquals(
                    2,
                    storage.handler.await {
                        sync_importQueries.getImport(intent.intentId).executeAsOne().total
                    },
                )
                val actor = storage.handler.await {
                    sync_journalQueries.getCurrentActor("another-space", 1).executeAsOne()
                }
                assertEquals(actor.actor_id, reopened.onboarding.storage.connection("another-space", 1)?.actorId)
            }
        }
    }

    @Test
    fun `saved intent restarts and read only recheck retains continuation until explicit cancellation`() = runBlocking {
        open().use { storage ->
            SyncSpaceSwitchFixture(storage).use { fixture ->
                fixture.prepareOld()
                val runtime = fixture.old.runtime
                fixture.old.secure.afterWrite = { key, _ ->
                    if (key == "sync-switch-pointer-v1-1") throw SyncSecureStoreException()
                }
                Assertions.assertThrows(Exception::class.java) {
                    runBlocking { runtime.beginSwitch(SyncSpaceSwitchPurpose.CONNECT) }
                }
                fixture.old.secure.afterWrite = null
                val reopened = fixture.old.runtime()
                val intent = requireNotNull(reopened.activeSwitch())
                assertEquals(SyncSpaceRecoveryReason.SWITCH_PENDING, reopened.spaceRecovery()?.reason)
                assertEquals(SyncRunStatus.SKIPPED, reopened.coordinator.synchronize(SyncTrigger.PERIODIC).status)
                fixture.oldUnavailable = false
                assertEquals(SyncSpaceRecoveryReason.SWITCH_PENDING, reopened.recheckSpace().recovery?.reason)
                assertEquals(intent, reopened.activeSwitch())
                reopened.cancelRecoverySwitch()
                assertEquals(null, reopened.activeSwitch())
                assertEquals(null, reopened.spaceRecovery())
                assertNotNull(reopened.onboarding.storage.pending(1L))
                assertEquals("space", reopened.connection()?.spaceId)
            }
        }
    }

    @Test
    fun `deleted join target can be replaced with checkpoint retained`() = runBlocking {
        open().use { storage ->
            SyncSpaceSwitchFixture(storage).use { fixture ->
                fixture.prepareOld()
                fixture.target(100, "another-sync", "another-space")
                fixture.target(101, "third-sync", "third-space")
                val runtime = fixture.old.runtime
                var intent = runtime.beginSwitch(SyncSpaceSwitchPurpose.CONNECT)
                val space = (runtime.onboarding.discover() as SyncSpaceDiscovery.Multiple)
                    .spaces.single { it.repositoryId == 100L }
                runtime.onboarding.join(space, SyncSpaceMaterial(space.descriptor, null), intent)
                intent = requireNotNull(runtime.activeSwitch())
                val setup = runtime.confirmSwitch(intent)
                fixture.unavailableTargets += 100L
                Assertions.assertThrows(Exception::class.java) {
                    runBlocking { runtime.onboarding.resume(setup) }
                }
                val reopened = fixture.old.runtime()
                val replacement = reopened.beginSwitch(SyncSpaceSwitchPurpose.CONNECT)
                assertTrue(
                    replacement.intentId != intent.intentId,
                    "explicit new choice escapes unavailable unbound join",
                )
                assertNotNull(reopened.onboarding.storage.switchSetup(intent.intentId), "sealed evidence is archived")
                assertEquals("space", reopened.connection()?.spaceId)
                val candidate = (reopened.onboarding.discover() as SyncSpaceDiscovery.Found).space
                reopened.onboarding.join(
                    candidate,
                    SyncSpaceMaterial(candidate.descriptor, null),
                    replacement,
                )
                reopened.onboarding.resume(reopened.confirmSwitch(requireNotNull(reopened.activeSwitch())))
                assertEquals("third-space", reopened.connection()?.spaceId)
                assertNotNull(reopened.onboarding.storage.pending(1L))
            }
        }
    }

    @Test
    fun `wizard back first cancels confirmation then returns to recovery with original binding intact`() = runBlocking {
        open().use { storage ->
            SyncSpaceSwitchFixture(storage).use { fixture ->
                fixture.prepareOld()
                fixture.target(100, "another-sync", "another-space", "target-password")
                val panel = fixture.old.panel
                val writes = fixture.writes.get()
                panel.act(SyncPanelAction.ConnectOtherSpace)
                withTimeout(5_000) { panel.state.first { !it.setupBusy && it.spaces.size == 1 } }
                panel.act(SyncPanelAction.ChooseSpace(panel.state.value.spaces.single()))
                panel.act(SyncPanelAction.Back)
                withTimeout(5_000) { panel.state.first { !it.setupBusy && it.setupStep == SyncSetupStep.CHOOSE_SPACE } }
                panel.act(SyncPanelAction.ChooseSpace(panel.state.value.spaces.single()))
                panel.act(SyncPanelAction.SubmitPassword("target-password"))
                withTimeout(5_000) { panel.state.first { it.question == SyncPanelQuestion.CONNECT_SPACE } }
                panel.act(SyncPanelAction.Back)
                assertEquals(null, panel.state.value.question)
                assertEquals(SyncPanelPage.SETUP, panel.state.value.page)
                panel.act(SyncPanelAction.Back)
                assertEquals(SyncPanelPage.RECOVERY, panel.state.value.page)
                assertEquals("space", fixture.old.runtime.connection()?.spaceId)
                assertEquals(writes, fixture.writes.get())
            }
        }
    }

    @Test
    fun `failed old recheck retains active switch and explicit create archives a new purpose`() = runBlocking {
        open().use { storage ->
            SyncSpaceSwitchFixture(storage).use { fixture ->
                fixture.prepareOld()
                fixture.target(100, "another-sync", "another-space")
                val runtime = fixture.old.runtime
                var intent = runtime.beginSwitch(SyncSpaceSwitchPurpose.CONNECT)
                val space = (runtime.onboarding.discover() as SyncSpaceDiscovery.Found).space
                runtime.onboarding.join(space, SyncSpaceMaterial(space.descriptor, null), intent)
                intent = requireNotNull(runtime.activeSwitch())
                runtime.recheckSpace()
                assertEquals(intent.intentId, runtime.activeSwitch()?.intentId, "old inaccessible is not cancellation")
                val create = runtime.beginSwitch(SyncSpaceSwitchPurpose.CREATE)
                assertTrue(create.intentId != intent.intentId)
                assertEquals(SyncSpaceSwitchPurpose.CREATE, create.purpose)
                assertEquals(intent.target, runtime.onboarding.storage.switchIntent(intent.intentId)?.target)
                assertEquals(
                    SyncSpaceSwitchStage.CANCELLED,
                    runtime.onboarding.storage.switchIntent(intent.intentId)?.stage,
                )
                assertNotNull(runtime.onboarding.storage.pending(1L))
            }
        }
    }

    @Test
    fun `old durable bulk and decisions stay scoped while switch prepares and after new activation`() = runBlocking {
        open().use { storage ->
            SyncSpaceSwitchFixture(storage).use { fixture ->
                fixture.prepareOld()
                fixture.target(100, "another-sync", "another-space")
                fixture.pending(3)
                val runtime = fixture.old.runtime
                val job = runtime.projector.startBulk("space", 1, SyncCancellationDecision.CONFIRM)
                runtime.preferences.activeBulkJob("space", 1).set(job)
                val panel = fixture.old.panel
                panel.act(SyncPanelAction.Open)
                assertEquals(3L, panel.state.value.pendingTotal)
                panel.act(SyncPanelAction.ConnectOtherSpace)
                withTimeout(5_000) { panel.state.first { !it.setupBusy && it.spaces.size == 1 } }
                panel.act(SyncPanelAction.ResumeBulk)
                panel.awaitBulkIdle()
                assertEquals(3L, runtime.projector.bulkProgress(job).queued)
                panel.act(SyncPanelAction.ChooseSpace(panel.state.value.spaces.single()))
                withTimeout(5_000) { panel.state.first { it.question == SyncPanelQuestion.CONNECT_SPACE } }
                assertEquals(3L, panel.state.value.switchPendingDecisions)
                panel.act(SyncPanelAction.ConfirmQuestion)
                withTimeout(5_000) { panel.state.first { !it.setupBusy && it.connection?.spaceId == "another-space" } }
                val library = storage.manga.getLibraryManga()
                runtime.projector.processBulk(job)
                panel.act(SyncPanelAction.ResumeBulk)
                panel.awaitBulkIdle()
                assertEquals(library, storage.manga.getLibraryManga())
                assertEquals(3L, runtime.projector.bulkProgress(job).queued)
                assertEquals(
                    3L,
                    storage.handler.await {
                        sync_inboxQueries.countPendingDecisions("space", 1).executeAsOne()
                    },
                )
                assertEquals(job, runtime.preferences.activeBulkJob("space", 1).get())
            }
        }
    }

    @Test
    fun `initialized new target deletion archives checkpoint through a linked replacement intent`() = runBlocking {
        open().use { storage ->
            SyncSpaceSwitchFixture(storage).use { fixture ->
                fixture.prepareOld()
                fixture.target(200, "mihon-sync", null)
                val runtime = fixture.old.runtime
                val created = runtime.beginSwitch(SyncSpaceSwitchPurpose.CREATE)
                val candidate = (runtime.onboarding.discover() as SyncSpaceDiscovery.EmptyRepository)
                    .candidate
                val setup = runtime.onboarding.create(candidate, "secret-not-stored-as-password", created)
                runtime.confirmSwitch(requireNotNull(runtime.activeSwitch()))
                fixture.old.panel.stop()
                fixture.old.secure.beforeWrite = { key, value ->
                    if (key.startsWith("sync-switch-intent-v1-") && value?.contains("targetConnection\":{") == true) {
                        throw SyncSecureStoreException()
                    }
                }
                Assertions.assertThrows(Exception::class.java) {
                    runBlocking { runtime.onboarding.resume(setup) }
                }
                fixture.old.secure.beforeWrite = null
                val checkpoint = requireNotNull(runtime.onboarding.storage.switchSetup(created.intentId))
                assertEquals(SyncInitializationStage.SPACE_CONFIRMED, checkpoint.stage)
                fixture.unavailableTargets += 200L
                fixture.target(101, "replacement-sync", "replacement-space")
                val reopened = fixture.old.runtime()
                val next = reopened.beginSwitch(SyncSpaceSwitchPurpose.CONNECT)
                assertEquals(created.intentId, next.previousIntentId)
                assertEquals(checkpoint, reopened.onboarding.storage.switchSetup(created.intentId))
                assertEquals(setup, reopened.onboarding.storage.switchIntent(created.intentId)?.target)
                assertEquals("space", reopened.connection()?.spaceId)
                val target = (reopened.onboarding.discover() as SyncSpaceDiscovery.Found).space
                reopened.onboarding.join(target, SyncSpaceMaterial(target.descriptor, null), next)
                reopened.onboarding.resume(reopened.confirmSwitch(requireNotNull(reopened.activeSwitch())))
                assertEquals("replacement-space", reopened.connection()?.spaceId)
                assertEquals(checkpoint, reopened.onboarding.storage.switchSetup(created.intentId))
            }
        }
    }

    @Test
    fun `rename commit failures recover matching address locally`() = runBlocking {
        for (boundary in listOf("binding", "database", "complete")) {
            open().use { storage ->
                SyncSpaceSwitchFixture(storage).use { fixture ->
                    fixture.prepareOld()
                    val renamed = SyncRepository(
                        "fixture-owner",
                        "renamed-space",
                        fixture.old.repository.branch,
                    )
                    fixture.renamedOld = renamed
                    fixture.old.git.renameRepository(renamed)
                    fixture.old.panel.stop()
                    val material = fixture.old.runtime.onboarding.storage.connection("space", 1)?.material
                    when (boundary) {
                        "binding" -> fixture.old.secure.afterWrite = { key, _ ->
                            if (key.startsWith("space-") && !key.contains("-address-") && !key.endsWith("-recovery")) {
                                throw SyncSecureStoreException()
                            }
                        }
                        "database" -> storage.driver.execute(
                            null,
                            "CREATE TRIGGER reject_address BEFORE UPDATE OF repository_name ON sync_spaces " +
                                "BEGIN SELECT RAISE(ABORT, 'fixture address abort'); END",
                            0,
                        )
                        else -> fixture.old.secure.beforeWrite = { key, value ->
                            if (key.contains("-address-v1") && value?.contains("complete\":true") == true) {
                                throw SyncSecureStoreException()
                            }
                        }
                    }
                    assertNotNull(fixture.old.runtime.recheckSpace().problem)
                    fixture.old.secure.afterWrite = null
                    fixture.old.secure.beforeWrite = null
                    if (boundary == "database") storage.driver.execute(null, "DROP TRIGGER reject_address", 0)
                    val requests = fixture.requests.get()
                    val reopened = fixture.old.runtime()
                    assertEquals(renamed, reopened.connection()?.repository)
                    assertEquals(renamed, reopened.connection()?.repository)
                    assertEquals(renamed, reopened.onboarding.storage.connection("space", 1)?.repository())
                    assertEquals(renamed, reopened.onboarding.storage.pending(1L)?.repository())
                    assertEquals(material, reopened.onboarding.storage.connection("space", 1)?.material)
                    assertEquals(requests, fixture.requests.get())
                    assertEquals(
                        1L,
                        storage.handler.await {
                            sync_importQueries.countPendingImports("space", 1).executeAsOne()
                        },
                    )
                }
            }
        }
    }

    @Test
    fun `new target uploads baseline while old artifact and run remain unchanged`() = runBlocking {
        open().use { storage ->
            SyncSpaceSwitchFixture(storage).use { fixture ->
                fixture.prepareOld()
                val target = fixture.target(100, "another-sync", "another-space")
                storage.favorite("/old-unsent-operation")
                val runtime = fixture.old.runtime
                val oldBinding = requireNotNull(runtime.onboarding.storage.connection("space", 1))
                val oldPending = runtime.onboarding.storage.pending(1L)
                val oldRun = runtime.runStore.latest("space", 1)
                val outbox = SyncOutboxStore(storage.handler)
                val frozen = outbox.freezeRound("space", 1)
                val batch = requireNotNull(outbox.nextBatch("space", 1, frozen.batchIds.single()))
                fixture.oldUnavailable = false
                val oldTransport = runtime.onboarding.transport(
                    runtime.accessToken(),
                    oldBinding.material.material(),
                    repositoryId = oldBinding.repositoryId,
                )
                val oldSnapshot = oldTransport.readSnapshot(fixture.old.repository, "space", 1).getOrThrow()
                fixture.oldUnavailable = true
                val artifact = SyncBatchSyncService(
                    oldTransport,
                    spaceMaterial = oldBinding.material.material(),
                ).prepare(
                    oldSnapshot,
                    batch,
                    ".mihon-sync/batches/${batch.events.first().actorId}/" +
                        "${batch.events.first().epoch}/${batch.batchId}.json",
                )
                outbox.savePrepared(batch, artifact)
                val oldBatch = storage.handler.await {
                    sync_journalQueries.getBatch("space", 1, batch.batchId).executeAsOne()
                }
                val oldEvents = storage.handler.await {
                    sync_journalQueries.getPendingEvents("space", 1, 256, 0).executeAsList()
                }
                val intent = runtime.beginSwitch(SyncSpaceSwitchPurpose.CONNECT)
                val space = (runtime.onboarding.discover() as SyncSpaceDiscovery.Found).space
                runtime.onboarding.join(space, SyncSpaceMaterial(space.descriptor, null), intent)
                runtime.onboarding.resume(runtime.confirmSwitch(requireNotNull(runtime.activeSwitch())))
                runtime.preferences.importPaused.set(false)
                fixture.paths.clear()
                val result = withTimeout(10_000) { runtime.coordinator.synchronize(SyncTrigger.MANUAL) }
                assertEquals(SyncRunStatus.SUCCESS, result.status)
                assertTrue(result.uploaded > 0)
                assertTrue(fixture.paths.none { it.startsWith("/repos/${fixture.old.repository.fullName}") })
                assertEquals(oldBinding, runtime.onboarding.storage.connection("space", 1))
                assertEquals(oldPending, runtime.onboarding.storage.pending(1L))
                assertEquals(oldRun, runtime.runStore.latest("space", 1))
                assertEquals(
                    oldBatch,
                    storage.handler.await {
                        sync_journalQueries.getBatch("space", 1, batch.batchId).executeAsOne()
                    },
                )
                assertEquals(
                    oldEvents,
                    storage.handler.await {
                        sync_journalQueries.getPendingEvents("space", 1, 256, 0).executeAsList()
                    },
                )
                val newBinding = requireNotNull(runtime.onboarding.storage.connection("another-space", 1))
                val transport = runtime.onboarding.transport(
                    runtime.accessToken(),
                    requireNotNull(target.material),
                    repositoryId = target.id,
                )
                val snapshot = transport.readSnapshot(target.repository, "another-space", 1).getOrThrow()
                val service = SyncBatchSyncService(transport, spaceMaterial = target.material)
                val uploaded = snapshot.batches.map { requireNotNull(service.receive(snapshot, it).batch) }
                assertTrue(uploaded.isNotEmpty())
                assertTrue(uploaded.none { it.batchId == batch.batchId })
                assertTrue(
                    uploaded.all {
                        it.spaceId == "another-space" && it.generation == 1L &&
                            it.events.all { event ->
                                event.actorId == newBinding.actorId && event.origin.name == "INITIAL_IMPORT"
                            }
                    },
                )
                assertEquals(2, uploaded.sumOf { it.events.size })
            }
        }
    }

    protected fun database(driver: SqlDriver): Database = Database(
        driver,
        History.Adapter(DateColumnAdapter),
        Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
    ).also { Database.Schema.create(driver) }
}
