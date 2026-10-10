package mihon.data.sync

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import mihon.data.sync.auth.SyncDiscoveryProblem
import mihon.data.sync.crypto.SyncAeadEngineFactory
import mihon.data.sync.runtime.SyncDatabaseExchange
import mihon.data.sync.runtime.SyncPanelAction
import mihon.data.sync.runtime.SyncPanelController
import mihon.data.sync.runtime.SyncPanelPage
import mihon.data.sync.runtime.SyncPanelQuestion
import mihon.data.sync.runtime.SyncRecoveryAction
import mihon.data.sync.runtime.SyncRecoveryAuthorization
import mihon.data.sync.runtime.SyncRecoveryContinuation
import mihon.data.sync.runtime.SyncRecoveryOutcome
import mihon.data.sync.runtime.SyncRecoveryPlatformAction
import mihon.data.sync.runtime.SyncRecoveryPlatformResult
import mihon.data.sync.runtime.SyncSetupStep
import mihon.data.sync.runtime.SyncSpaceRecoveryReason
import mihon.data.sync.runtime.SyncSpaceSwitchPurpose
import mihon.data.sync.runtime.SyncSpaceSwitchStage
import mihon.domain.sync.SyncBatch
import mihon.domain.sync.SyncObjectKey
import mihon.domain.sync.SyncObjectType
import mihon.domain.sync.auth.GitHubAccessToken
import mihon.domain.sync.crypto.SyncCryptoBinding
import mihon.domain.sync.crypto.SyncSpaceDescriptorCodec
import mihon.domain.sync.crypto.SyncSpaceMaterial
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
    @Test
    fun `action regression observed properties execute fixed repository repair`() = runBlocking {
        for (archived in listOf(false, true)) {
            open().use { storage ->
                SyncNativeRepositoryFixture(storage, "mihon-sync").use { f ->
                    f.available = true
                    f.app.existing("")
                    f.app.authorize()
                    f.app.begin()
                    val actor = f.app.runtime.recoveryBinding().stored.actorId
                    f.archived = archived
                    f.privateRepository = archived
                    f.app.panel.act(SyncPanelAction.OpenRecovery)
                    f.app.panel.act(SyncPanelAction.VerifyRecovery)
                    f.app.panel.awaitRecoveryIdle()
                    assertNull(f.app.panel.state.value.setupProblem)
                    assertEquals(
                        if (archived) {
                            SyncDiscoveryProblem.REPOSITORY_ARCHIVED
                        } else {
                            SyncDiscoveryProblem.REPOSITORY_NOT_PRIVATE
                        },
                        f.app.panel.state.value.recoveryFailure?.discovery,
                    )
                    f.app.panel.act(
                        SyncPanelAction.ExecuteRecoveryAction(SyncRecoveryAction.REPAIR_REPOSITORY_PROPERTIES),
                    )
                    assertEquals(SyncPanelQuestion.REPAIR_REPOSITORY_PROPERTIES, f.app.panel.state.value.question)
                    assertEquals(f.repository, f.app.panel.state.value.setupRepository)
                    assertEquals(!archived, f.app.panel.state.value.repairMakePrivate)
                    assertEquals(archived, f.app.panel.state.value.repairUnarchive)
                    assertEquals(0, f.patches)
                    assertEquals(actor, f.app.runtime.recoveryBinding().stored.actorId)
                    f.app.panel.act(SyncPanelAction.ConfirmQuestion)
                    withTimeout(10_000) {
                        f.app.panel.state.first {
                            !it.recoveryBusy && it.recoveryOutcome == SyncRecoveryOutcome.ORIGINAL_VERIFIED
                        }
                    }
                    assertEquals(1, f.patches)
                    assertEquals(false, f.archived)
                    assertEquals(true, f.privateRepository)
                    assertEquals(actor, f.app.runtime.recoveryBinding().stored.actorId)
                }
            }
        }
    }

    @Test
    fun `action regression unbound installation retains official management`() = runBlocking {
        open().use { storage ->
            SyncOnboardingFixture(storage).use { f ->
                f.created = false
                f.authorize()
                f.begin()
                assertNull(f.runtime.connection())
                assertNotNull(f.panel.state.value.setupInstallation)
                assertTrue(
                    f.panel.state.value.recoveryAlternativeActions.any {
                        it.action == SyncRecoveryAction.MANAGE_AUTHORIZATION
                    },
                )
                val requests = f.git.server.requestCount
                f.panel.act(SyncPanelAction.ExecuteRecoveryAction(SyncRecoveryAction.MANAGE_AUTHORIZATION))
                f.panel.act(SyncPanelAction.RecoveryOfficialOpened(SyncRecoveryAction.MANAGE_AUTHORIZATION))
                assertEquals(SyncRecoveryAction.MANAGE_AUTHORIZATION, f.panel.state.value.recoveryOfficialAction)
                assertEquals(requests, f.git.server.requestCount)
                assertEquals(0, f.repositoryWrites)
            }
        }
    }

    @Test
    fun `action regression authorization precedes report and real confirmation avoids another login`() = runBlocking {
        open().use { storage ->
            SyncOnboardingFixture(storage).use { f ->
                f.existing("")
                f.authorize()
                f.begin()
                mihon.data.sync.inbox.SyncInboxStore(storage.handler).recordRejected(
                    "space",
                    1,
                    "auth-report",
                    "",
                    "temporary read failure",
                    "retained original",
                )
                val healthy = f.git.server.dispatcher
                var deviceRequests = 0
                f.git.server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        if (request.url.encodedPath == "/device") deviceRequests++
                        if (request.url.encodedPath == "/user") return MockResponse(code = 401, body = "{}")
                        return healthy.dispatch(request)
                    }
                }
                f.runtime.coordinator.synchronize(SyncTrigger.MANUAL)
                f.panel.act(SyncPanelAction.OpenRecovery)
                assertEquals(SyncSpaceRecoveryReason.AUTHORIZATION_REQUIRED, f.panel.state.value.recovery?.reason)
                assertEquals(1L, f.panel.state.value.recoveryRepairReport?.remaining)
                assertEquals(SyncRecoveryAction.CONNECT_GITHUB, f.panel.state.value.recoveryPrimaryAction.action)
                f.panel.act(SyncPanelAction.ExecuteRecoveryAction(SyncRecoveryAction.CONNECT_GITHUB))
                withTimeout(10_000) {
                    f.panel.state.first { it.recoveryAuthorization == SyncRecoveryAuthorization.FAILED }
                }
                assertEquals(1, deviceRequests)
                f.git.server.dispatcher = healthy
                f.panel.act(SyncPanelAction.CheckAuthorization)
                withTimeout(10_000) {
                    f.panel.state.first { it.recoveryAuthorization == SyncRecoveryAuthorization.CONFIRMED }
                }
                f.panel.awaitRecoveryIdle()
                assertEquals(SyncRecoveryAction.REPAIR_DATA, f.panel.state.value.recoveryPrimaryAction.action)
                assertEquals(1L, f.panel.state.value.recoveryRepairReport?.remaining)
                assertEquals(1, deviceRequests)
            }
        }
    }

    @Test
    fun `recovery continuation background backup return restores durable context without opening panel`() =
        runBlocking {
            open().use { storage ->
                SyncOnboardingFixture(storage).use { f ->
                    f.existing("")
                    f.authorize()
                    f.begin()
                    f.panel.act(SyncPanelAction.OpenRecovery)
                    f.panel.act(SyncPanelAction.OpenRecoveryPlatform(SyncRecoveryPlatformAction.BACKUP))
                    val request = requireNotNull(f.panel.state.value.recoveryPlatformRequest)
                    f.panel.act(SyncPanelAction.Close)
                    val restarted = f.runtime()
                    try {
                        val panel = restarted.panel as SyncPanelController
                        panel.act(
                            SyncPanelAction.RecoveryPlatformCompleted(
                                request.requestId,
                                SyncRecoveryPlatformResult.PartialFailure(remaining = 2),
                            ),
                        )
                        assertEquals(false, panel.state.value.visible)
                        panel.act(SyncPanelAction.OpenRecovery)
                        panel.act(SyncPanelAction.VerifyRecovery)
                        panel.awaitRecoveryIdle()
                        assertEquals(SyncRecoveryOutcome.REMAINING, panel.state.value.recoveryOutcome)
                        assertEquals(2L, panel.state.value.recoveryExternalScopes.single().remaining)
                    } finally {
                        restarted.stopPanel()
                    }
                }
            }
        }

    @Test
    fun `recovery continuation replaces deleted unbound pending with distinct created space and baseline`() =
        runBlocking {
            for (newSpace in listOf(false, true)) {
                open().use { storage ->
                    SyncNativeRepositoryFixture(storage).use { f ->
                        val oldMaterial = mihon.data.sync.crypto.SyncSpaceCrypto.create(
                            "deleted-pending",
                            1,
                            "old-password",
                        )
                        val pending = mihon.data.sync.runtime.StoredSyncSetup(
                            accountId = 1, accountLogin = "fixture-owner", attemptId = "old-pending-attempt-0001",
                            attemptNonce = "old-pending-nonce-0001", newSpace = newSpace,
                            material = mihon.data.sync.runtime.StoredSyncMaterial.from(oldMaterial),
                            stage = if (newSpace) {
                                mihon.domain.sync.transport.SyncInitializationStage.VERIFIED_EMPTY
                            } else {
                                mihon.domain.sync.transport.SyncInitializationStage.SPACE_CONFIRMED
                            },
                            repositoryId = 88, owner = "fixture-owner", repository = "mihon-sync",
                            branch = f.repository.branch, defaultBranch = if (newSpace) "main" else null,
                        )
                        f.app.runtime.onboarding.storage.save(pending, null)
                        val original = requireNotNull(f.app.secure.values["sync-setup-v3-1"])
                        f.app.panel.act(SyncPanelAction.Open)
                        f.app.panel.act(SyncPanelAction.Authorize)
                        withTimeout(10_000) {
                            f.app.panel.state.first {
                                it.setupStep == SyncSetupStep.ERROR &&
                                    !it.setupBusy
                            }
                        }
                        assertEquals(
                            if (newSpace) {
                                SyncDiscoveryProblem.NEEDS_REPOSITORY_ACCESS
                            } else {
                                SyncDiscoveryProblem.REPOSITORY_UNAVAILABLE
                            },
                            f.app.panel.state.value.setupProblem,
                        )
                        f.app.panel.act(SyncPanelAction.OpenRecovery)
                        f.app.panel.act(SyncPanelAction.ExecuteRecoveryAction(SyncRecoveryAction.CREATE_SPACE))
                        f.app.panel.act(SyncPanelAction.ConfirmQuestion)
                        f.app.panel.act(SyncPanelAction.PrepareRepositoryCreation(f.repository.name))
                        f.app.panel.act(SyncPanelAction.ConfirmQuestion)
                        withTimeout(10_000) {
                            f.app.panel.state.first {
                                !it.setupBusy &&
                                    it.setupStep == SyncSetupStep.NEW_PASSWORD
                            }
                        }
                        f.app.panel.act(SyncPanelAction.SubmitPassword("new-password"))
                        withTimeout(10_000) {
                            f.app.panel.state.first {
                                !it.setupBusy &&
                                    it.setupStep in setOf(
                                        SyncSetupStep.COMPLETE,
                                        SyncSetupStep.ERROR,
                                    )
                            }
                        }
                        assertEquals(SyncSetupStep.COMPLETE, f.app.panel.state.value.setupStep)
                        assertEquals(f.repository, f.app.runtime.connection()?.repository)
                        assertTrue(f.app.runtime.connection()?.spaceId != oldMaterial.descriptor.spaceId)
                        val connection = requireNotNull(f.app.runtime.connection())
                        val stored = requireNotNull(
                            f.app.runtime.onboarding.storage.connection(
                                connection.spaceId,
                                connection.generation,
                            ),
                        )
                        assertTrue(
                            stored.material.keyHex != pending.material.keyHex,
                            "new password creates independent key material",
                        )
                        assertTrue(
                            f.app.secure.values.values.contains(original),
                            "old sealed setup survives as an archived original",
                        )
                        assertEquals(1, f.posts)
                        f.app.panel.act(SyncPanelAction.OpenRecovery)
                        f.app.panel.act(SyncPanelAction.VerifyRecovery)
                        f.app.panel.awaitRecoveryIdle()
                        assertEquals(
                            SyncRecoveryOutcome.NEW_SCOPE_VERIFIED_WITH_OLD_REMAINING,
                            f.app.panel.state.value.recoveryOutcome,
                        )
                        assertTrue(
                            f.app.panel.state.value.recoveryOldScopes.any {
                                it.spaceId == "deleted-pending" &&
                                    it.remoteUnverified
                            },
                        )
                    }
                }
            }
        }

    @Test
    fun `recovery continuation resumes paused initial import through existing import control`() =
        runBlocking {
            open().use { storage ->
                SyncOnboardingFixture(storage).use { f ->
                    storage.favorite("/paused-import-book")
                    f.runtime.preferences.importPaused.set(true)
                    f.existing("")
                    f.authorize()
                    f.begin()
                    f.panel.act(SyncPanelAction.OpenRecovery)
                    assertTrue(f.panel.state.value.importRemaining > 0)
                    assertTrue(f.panel.state.value.run?.state != mihon.data.sync.runtime.SyncRunState.PAUSED_USER)
                    assertEquals(SyncRecoveryAction.RESUME_IMPORT, f.panel.state.value.recoveryPrimaryAction.action)
                    f.panel.act(SyncPanelAction.ExecuteRecoveryAction(SyncRecoveryAction.RESUME_IMPORT))
                    withTimeout(10_000) { f.panel.state.first { it.importRemaining == 0L && !it.busy } }
                    assertEquals(false, f.runtime.preferences.importPaused.get())
                    f.panel.act(SyncPanelAction.VerifyRecovery)
                    f.panel.awaitRecoveryIdle()
                    assertEquals(SyncRecoveryOutcome.ORIGINAL_VERIFIED, f.panel.state.value.recoveryOutcome)
                }
            }
        }

    @Test
    fun `recovery continuation bounded whole identities never settle an unknown range`() =
        runBlocking {
            open().use { storage ->
                SyncOnboardingFixture(storage).use { f ->
                    f.existing("")
                    f.authorize()
                    f.begin()
                    f.panel.act(SyncPanelAction.OpenRecovery)
                    val objects = (1..1000).map {
                        SyncObjectKey(
                            SyncObjectType.MANGA,
                            sourceId = "1",
                            originalUrl = "/$it-" + "x".repeat(300),
                        )
                    }
                    f.panel.act(SyncPanelAction.OpenRecoveryPlatform(SyncRecoveryPlatformAction.BACKUP))
                    f.panel.act(
                        SyncPanelAction.RecoveryPlatformCompleted(
                            requireNotNull(f.panel.state.value.recoveryPlatformRequest).requestId,
                            SyncRecoveryPlatformResult.PartialFailure(
                                succeededObjects = objects,
                                failedObjects = objects,
                                remaining = 1000,
                            ),
                        ),
                    )
                    assertEquals(false, f.panel.state.value.recoveryPersistenceFailed)
                    val range = f.panel.state.value.recoveryExternalScopes.single()
                    assertEquals(1000L, range.remaining)
                    assertTrue(range.failedObjects.size < objects.size)
                    assertTrue(range.failedObjects.all { it in objects }, "object identity strings are never cut")
                    f.panel.act(SyncPanelAction.OpenRecoveryPlatform(SyncRecoveryPlatformAction.BACKUP))
                    f.panel.act(
                        SyncPanelAction.RecoveryPlatformCompleted(
                            requireNotNull(f.panel.state.value.recoveryPlatformRequest).requestId,
                            SyncRecoveryPlatformResult.Changed(range.failedObjects),
                        ),
                    )
                    f.panel.act(SyncPanelAction.VerifyRecovery)
                    f.panel.awaitRecoveryIdle()
                    assertEquals(SyncRecoveryOutcome.REMAINING, f.panel.state.value.recoveryOutcome)
                    assertEquals(1000L, f.panel.state.value.recoveryExternalScopes.single().remaining)
                }
            }
        }

    @Test
    fun `recovery data completion switch retains old backup failure and unknown range`() =
        runBlocking {
            for (remaining in listOf(2L, null)) {
                open().use { storage ->
                    SyncSpaceSwitchFixture(storage).use { f ->
                        f.prepareOld()
                        val runtime = f.old.runtime
                        f.old.panel.act(SyncPanelAction.OpenRecovery)
                        f.old.panel.act(SyncPanelAction.OpenRecoveryPlatform(SyncRecoveryPlatformAction.BACKUP))
                        f.old.panel.act(
                            SyncPanelAction.RecoveryPlatformCompleted(
                                requireNotNull(f.old.panel.state.value.recoveryPlatformRequest).requestId,
                                SyncRecoveryPlatformResult.PartialFailure(remaining = remaining),
                            ),
                        )
                        f.target(100, "backup-rebuilt", "rebuilt-space")
                        val intent = runtime.beginSwitch(SyncSpaceSwitchPurpose.CONNECT)
                        val discovered = runtime.onboarding.discover()
                        val space = (discovered as mihon.data.sync.auth.SyncSpaceDiscovery.Found).space
                        runtime.onboarding.join(space, SyncSpaceMaterial(space.descriptor, null), intent)
                        runtime.onboarding.resume(runtime.confirmSwitch(requireNotNull(runtime.activeSwitch())))
                        runtime.preferences.importPaused.set(false)
                        f.old.panel.act(SyncPanelAction.OpenRecovery)
                        f.old.panel.act(SyncPanelAction.VerifyRecovery)
                        f.old.panel.awaitRecoveryIdle()
                        assertEquals(
                            SyncRecoveryOutcome.NEW_SCOPE_VERIFIED_WITH_OLD_REMAINING,
                            f.old.panel.state.value.recoveryOutcome,
                        )
                        val old = f.old.panel.state.value.recoveryOldScopes.single { it.spaceId == "space" }
                        if (remaining == null) {
                            assertTrue(old.remainingUnknown)
                        } else {
                            assertTrue(old.remaining >= remaining)
                        }
                        assertEquals("rebuilt-space", runtime.connection()?.spaceId)
                    }
                }
            }
        }

    @Test
    fun `recovery data completion partial backup requires matching restoration`() =
        runBlocking {
            open().use { storage ->
                SyncOnboardingFixture(storage).use { f ->
                    f.existing("")
                    f.authorize()
                    f.begin()
                    f.panel.act(SyncPanelAction.OpenRecovery)
                    val first = SyncObjectKey(
                        SyncObjectType.MANGA,
                        sourceId = "1",
                        originalUrl = "/failed-one",
                    )
                    val second = SyncObjectKey(
                        SyncObjectType.MANGA,
                        sourceId = "1",
                        originalUrl = "/failed-two",
                    )
                    f.panel.act(SyncPanelAction.OpenRecoveryPlatform(SyncRecoveryPlatformAction.BACKUP))
                    f.panel.act(
                        SyncPanelAction.RecoveryPlatformCompleted(
                            requireNotNull(f.panel.state.value.recoveryPlatformRequest).requestId,
                            SyncRecoveryPlatformResult.PartialFailure(
                                failedObjects = listOf(first, second),
                                remaining = 2,
                            ),
                        ),
                    )
                    f.panel.act(SyncPanelAction.VerifyRecovery)
                    f.panel.awaitRecoveryIdle()
                    assertEquals(SyncRecoveryOutcome.REMAINING, f.panel.state.value.recoveryOutcome)
                    assertEquals(2L, f.panel.state.value.recoveryExternalScopes.single().remaining)
                    for ((objects, outcome) in listOf(
                        listOf(first) to SyncRecoveryOutcome.REMAINING,
                        listOf(second) to SyncRecoveryOutcome.ORIGINAL_VERIFIED,
                    )) {
                        f.panel.act(SyncPanelAction.OpenRecoveryPlatform(SyncRecoveryPlatformAction.BACKUP))
                        f.panel.act(
                            SyncPanelAction.RecoveryPlatformCompleted(
                                requireNotNull(f.panel.state.value.recoveryPlatformRequest).requestId,
                                SyncRecoveryPlatformResult.Changed(objects),
                            ),
                        )
                        f.panel.act(SyncPanelAction.VerifyRecovery)
                        f.panel.awaitRecoveryIdle()
                        assertEquals(outcome, f.panel.state.value.recoveryOutcome)
                    }
                }
            }
        }

    @Test
    fun `recovery data completion unknown range survives restart and unrelated changes`() =
        runBlocking {
            open().use { storage ->
                SyncOnboardingFixture(storage).use { f ->
                    f.existing("")
                    f.authorize()
                    f.begin()
                    f.panel.act(SyncPanelAction.OpenRecovery)
                    f.panel.act(SyncPanelAction.OpenRecoveryPlatform(SyncRecoveryPlatformAction.BACKUP))
                    f.panel.act(
                        SyncPanelAction.RecoveryPlatformCompleted(
                            requireNotNull(f.panel.state.value.recoveryPlatformRequest).requestId,
                            SyncRecoveryPlatformResult.PartialFailure(remaining = null),
                        ),
                    )
                    f.panel.act(SyncPanelAction.Close)
                    val restarted = f.runtime()
                    try {
                        val panel = restarted.panel as SyncPanelController
                        panel.act(SyncPanelAction.Open)
                        panel.act(SyncPanelAction.OpenRecovery)
                        for (result in listOf(
                            SyncRecoveryPlatformResult.Cancelled,
                            SyncRecoveryPlatformResult.NoChange,
                            SyncRecoveryPlatformResult.Changed(),
                        )) {
                            panel.act(SyncPanelAction.OpenRecoveryPlatform(SyncRecoveryPlatformAction.BACKUP))
                            panel.act(
                                SyncPanelAction.RecoveryPlatformCompleted(
                                    requireNotNull(panel.state.value.recoveryPlatformRequest).requestId,
                                    result,
                                ),
                            )
                            panel.act(SyncPanelAction.VerifyRecovery)
                            panel.awaitRecoveryIdle()
                            assertEquals(SyncRecoveryOutcome.REMAINING, panel.state.value.recoveryOutcome)
                            assertNull(panel.state.value.recoveryExternalScopes.single().remaining)
                        }
                    } finally {
                        restarted.stopPanel()
                    }
                }
            }
        }

    @Test
    fun `recovery data completion future protocol opens compatibility check without altering quarantine`() =
        runBlocking {
            open().use { storage ->
                SyncOnboardingFixture(storage).use { f ->
                    f.existing("")
                    f.authorize()
                    f.begin()
                    val inbox = mihon.data.sync.inbox.SyncInboxStore(storage.handler)
                    val rejected = inbox.ingest(SyncBatch(999, "space", 1, "future-version", emptyList()))
                    assertEquals(false, rejected.accepted)
                    f.panel.act(SyncPanelAction.OpenRecovery)
                    val original = requireNotNull(f.panel.state.value.recoveryRepairReport).batches.single()
                    assertTrue(original.reason.startsWith("UNKNOWN_PROTOCOL"))
                    val writes = f.repositoryWrites
                    assertEquals(SyncRecoveryAction.UPDATE, f.panel.state.value.recoveryPrimaryAction.action)
                    f.panel.act(SyncPanelAction.ExecuteRecoveryAction(SyncRecoveryAction.UPDATE))
                    assertEquals(SyncRecoveryPlatformAction.UPDATE, f.panel.state.value.recoveryPlatformRequest?.action)
                    assertEquals(original, requireNotNull(f.panel.state.value.recoveryRepairReport).batches.single())
                    assertEquals(writes, f.repositoryWrites)
                }
            }
        }

    @Test
    fun `recovery data completion known identity rejection offers new protected scope`() =
        runBlocking {
            open().use { storage ->
                SyncOnboardingFixture(storage).use { f ->
                    f.existing("")
                    f.authorize()
                    f.begin()
                    val inbox = mihon.data.sync.inbox.SyncInboxStore(storage.handler)
                    inbox.recordRejected(
                        "space",
                        1,
                        "invalid-identity",
                        "",
                        "INVALID_SEQUENCE: actor mismatch",
                        "original-invalid-packet",
                    )
                    f.panel.act(SyncPanelAction.OpenRecovery)
                    val original = requireNotNull(f.panel.state.value.recoveryRepairReport).batches.single()
                    val writes = f.repositoryWrites
                    assertEquals(SyncRecoveryAction.CREATE_SPACE, f.panel.state.value.recoveryPrimaryAction.action)
                    f.panel.act(SyncPanelAction.ExecuteRecoveryAction(SyncRecoveryAction.CREATE_SPACE))
                    assertEquals(SyncPanelQuestion.CREATE_NEW_SPACE, f.panel.state.value.question)
                    assertEquals(original, requireNotNull(f.panel.state.value.recoveryRepairReport).batches.single())
                    assertEquals(writes, f.repositoryWrites)
                }
            }
        }

    @Test
    fun `recovery data completion exhausted authenticated refetch advances to trusted copy`() =
        runBlocking {
            open().use { storage ->
                SyncOnboardingFixture(storage).use { f ->
                    f.existing("")
                    f.authorize()
                    f.begin()
                    val inbox = mihon.data.sync.inbox.SyncInboxStore(storage.handler)
                    inbox.recordRejected(
                        "space",
                        1,
                        "missing-from-authenticated-inventory",
                        "",
                        "sync batch could not be authenticated",
                        "original-retained-packet",
                    )
                    f.panel.act(SyncPanelAction.OpenRecovery)
                    val original = requireNotNull(f.panel.state.value.recoveryRepairReport).batches.single()
                    assertEquals(SyncRecoveryAction.REPAIR_DATA, f.panel.state.value.recoveryPrimaryAction.action)
                    f.panel.act(SyncPanelAction.ExecuteRecoveryAction(SyncRecoveryAction.REPAIR_DATA))
                    f.panel.awaitRecoveryIdle()
                    assertEquals(SyncRecoveryAction.BACKUP, f.panel.state.value.recoveryPrimaryAction.action)
                    assertEquals(original, requireNotNull(f.panel.state.value.recoveryRepairReport).batches.single())
                    assertEquals(SyncRecoveryOutcome.REMAINING, f.panel.state.value.recoveryOutcome)
                }
            }
        }

    @Test
    fun `recovery completion checked conditions advances to actual synchronization verification`() =
        runBlocking {
            open().use { storage ->
                SyncOnboardingFixture(storage).use { f ->
                    f.existing("")
                    f.authorize()
                    f.begin()
                    val healthy = f.git.server.dispatcher
                    f.git.server.dispatcher = failing(healthy, "/user", 500)
                    f.panel.act(SyncPanelAction.OpenRecovery)
                    f.panel.act(SyncPanelAction.VerifyRecovery)
                    f.panel.awaitRecoveryIdle()
                    f.git.server.dispatcher = healthy
                    val completion = f.runtime.coordinator.activity.value.completion
                    f.panel.act(SyncPanelAction.ExecuteRecoveryAction(SyncRecoveryAction.CHECK_CONDITIONS))
                    f.panel.awaitRecoveryIdle()
                    assertEquals(completion, f.runtime.coordinator.activity.value.completion)
                    assertEquals(SyncRecoveryAction.VERIFY_SYNC, f.panel.state.value.recoveryPrimaryAction.action)
                    assertTrue(f.panel.state.value.recoveryOutcome != SyncRecoveryOutcome.ORIGINAL_VERIFIED)
                    f.panel.act(SyncPanelAction.ExecuteRecoveryAction(SyncRecoveryAction.VERIFY_SYNC))
                    f.panel.awaitRecoveryIdle()
                    assertEquals(SyncRecoveryOutcome.ORIGINAL_VERIFIED, f.panel.state.value.recoveryOutcome)
                }
            }
        }

    @Test
    fun `recovery completion secondary storage failure preserves original HTTP problem`() =
        runBlocking {
            open().use { storage ->
                SyncOnboardingFixture(storage).use { f ->
                    f.existing("")
                    f.authorize()
                    f.begin()
                    f.git.server.dispatcher = failing(f.git.server.dispatcher, "/user", 500)
                    f.panel.act(SyncPanelAction.OpenRecovery)
                    f.panel.act(SyncPanelAction.VerifyRecovery)
                    f.panel.awaitRecoveryIdle()
                    val original = f.panel.state.value.recoveryFailure
                    val key = f.secure.values.keys.single { it.startsWith("space-") && !it.contains("-recovery") }
                    f.secure.values[key] = "{"
                    f.panel.act(SyncPanelAction.VerifyRecovery)
                    f.panel.awaitRecoveryIdle()
                    assertEquals(original, f.panel.state.value.recoveryFailure)
                    assertEquals("{", f.secure.values[key])
                }
            }
        }

    @Test
    fun `recovery completion device request generic HTTP failure is neutral connection action`() =
        runBlocking {
            open().use { storage ->
                SyncOnboardingFixture(storage).use { f ->
                    f.git.server.dispatcher = failing(f.git.server.dispatcher, "/device", 500)
                    f.panel.act(SyncPanelAction.Open)
                    f.panel.act(SyncPanelAction.Authorize)
                    withTimeout(5_000) { f.panel.state.first { it.authFailure != null } }
                    assertEquals(mihon.domain.sync.auth.GitHubAuthFailureReason.HTTP, f.panel.state.value.authFailure)
                    assertEquals(SyncRecoveryAction.NETWORK, f.panel.state.value.recoveryPrimaryAction.action)
                    assertNull(f.runtime.connection())
                    assertEquals(0, f.repositoryWrites)
                }
            }
        }

    @Test
    fun `unbound stale recovery completion cannot adopt a new credential revision`() =
        runBlocking {
            open().use { storage ->
                SyncOnboardingFixture(storage).use { f ->
                    f.panel.act(SyncPanelAction.OpenRecovery)
                    f.panel.act(SyncPanelAction.OpenRecoveryPlatform(SyncRecoveryPlatformAction.NETWORK))
                    val request = requireNotNull(f.panel.state.value.recoveryPlatformRequest)
                    f.authorize("different-credential")
                    f.panel.act(
                        SyncPanelAction.RecoveryPlatformCompleted(
                            request.requestId,
                            SyncRecoveryPlatformResult.RestartRequired,
                        ),
                    )
                    assertEquals(false, f.panel.state.value.recoveryRestartRequired)
                    assertEquals(false, f.panel.state.value.recoveryPlatformRequest?.restartRequired)
                    assertNull(f.runtime.onboarding.storage.unboundRecoveryFlow()?.credentialRevision)
                }
            }
        }

    @Test
    fun `unbound stale official completion cannot adopt a new credential revision`() =
        runBlocking {
            open().use { storage ->
                SyncOnboardingFixture(storage).use { f ->
                    f.installationSuspended = true
                    f.authorize()
                    f.begin()
                    f.panel.act(SyncPanelAction.ExecuteRecoveryAction(SyncRecoveryAction.RESTORE_INSTALLATION))
                    f.authorize("different-credential")
                    f.panel.act(SyncPanelAction.RecoveryOfficialOpened(SyncRecoveryAction.RESTORE_INSTALLATION))
                    assertNull(f.panel.state.value.recoveryOfficialAction)
                }
            }
        }

    @Test
    fun `recovery completion unbound external step survives close and controller restart without relaunch`() =
        runBlocking {
            open().use { storage ->
                SyncOnboardingFixture(storage).use { f ->
                    f.panel.act(SyncPanelAction.OpenRecovery)
                    f.panel.act(SyncPanelAction.OpenRecoveryPlatform(SyncRecoveryPlatformAction.NETWORK))
                    val request = requireNotNull(f.panel.state.value.recoveryPlatformRequest)
                    assertTrue(f.panel.claimRecoveryPlatform(request.requestId))
                    f.panel.act(SyncPanelAction.Close)
                    val restarted = f.runtime()
                    try {
                        val panel = restarted.panel as SyncPanelController
                        panel.act(SyncPanelAction.Open)
                        panel.act(SyncPanelAction.OpenRecovery)
                        assertEquals(request.requestId, panel.state.value.recoveryPlatformRequest?.requestId)
                        assertEquals(false, panel.claimRecoveryPlatform(request.requestId))
                        assertEquals(false, panel.state.value.recoveryPersistenceFailed)
                        assertNull(restarted.connection())
                        assertEquals(0, f.repositoryWrites)
                    } finally {
                        restarted.stopPanel()
                    }
                }
            }
        }

    @Test
    fun `recovery completion restricted repository and suspended installation require distinct official steps`() =
        runBlocking {
            for (installation in listOf(false, true)) {
                open().use { storage ->
                    SyncOnboardingFixture(storage).use { f ->
                        f.repositoryDisabled = !installation
                        f.installationSuspended = installation
                        f.authorize()
                        f.begin()
                        assertEquals(
                            if (installation) {
                                SyncDiscoveryProblem.INSTALLATION_SUSPENDED
                            } else {
                                SyncDiscoveryProblem.REPOSITORY_DISABLED
                            },
                            f.panel.state.value.setupProblem,
                        )
                        assertEquals(
                            if (installation) {
                                SyncRecoveryAction.RESTORE_INSTALLATION
                            } else {
                                SyncRecoveryAction.RESTORE_REPOSITORY
                            },
                            f.panel.state.value.recoveryPrimaryAction.action,
                        )
                        assertEquals(0, f.creationPosts)
                        assertEquals(0, f.repositoryWrites)
                    }
                }
            }
        }

    @Test
    fun `recovery completion identity mismatch remains distinct from inaccessible and archived`() =
        runBlocking {
            open().use { storage ->
                SyncOnboardingFixture(storage).use { f ->
                    f.existing("")
                    f.authorize()
                    f.begin()
                    val delegate = f.git.server.dispatcher
                    f.git.server.dispatcher = object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse {
                            val response = delegate.dispatch(request)
                            if (request.url.encodedPath == "/repos/${f.repository.fullName}") {
                                return MockResponse(
                                    body = """{
                                "id":100,"owner":{"id":1},"private":true,
                                "archived":false,"disabled":false,"permissions":{"push":true}
                            }""",
                                )
                            }
                            return response
                        }
                    }
                    val writes = f.repositoryWrites
                    val checked = f.runtime.recheckSpace()
                    assertEquals(SyncDiscoveryProblem.REPOSITORY_IDENTITY_MISMATCH, checked.discoveryProblem)
                    assertEquals(writes, f.repositoryWrites)
                    assertEquals(f.repository, f.runtime.connection()?.repository)
                }
            }
        }

    @Test
    fun `recovery completion rejects platform return after credential revision changes`() =
        runBlocking {
            open().use { storage ->
                SyncOnboardingFixture(storage).use { f ->
                    f.existing("")
                    f.authorize()
                    f.begin()
                    f.panel.act(SyncPanelAction.OpenRecovery)
                    f.panel.act(SyncPanelAction.OpenRecoveryPlatform(SyncRecoveryPlatformAction.NETWORK))
                    val request = requireNotNull(f.panel.state.value.recoveryPlatformRequest)
                    f.authorize("changed-token")
                    f.panel.act(
                        SyncPanelAction.RecoveryPlatformCompleted(
                            request.requestId,
                            SyncRecoveryPlatformResult.RestartRequired,
                        ),
                    )
                    assertEquals(false, f.panel.state.value.recoveryRestartRequired)
                    assertEquals(false, f.panel.state.value.recoveryPlatformRequest?.restartRequired)
                }
            }
        }

    @Test
    fun `recovery completion cancellation and no change never synchronize or erase original problem`() =
        runBlocking {
            for (result in listOf(SyncRecoveryPlatformResult.Cancelled, SyncRecoveryPlatformResult.NoChange)) {
                open().use { storage ->
                    SyncOnboardingFixture(storage).use { f ->
                        f.existing("")
                        f.authorize()
                        f.begin()
                        f.git.server.dispatcher = failing(f.git.server.dispatcher, "/user", 500)
                        f.panel.act(SyncPanelAction.OpenRecovery)
                        f.panel.act(SyncPanelAction.VerifyRecovery)
                        f.panel.awaitRecoveryIdle()
                        val original = f.panel.state.value.recoveryFailure
                        f.panel.act(SyncPanelAction.OpenRecoveryPlatform(SyncRecoveryPlatformAction.NETWORK))
                        val request = requireNotNull(f.panel.state.value.recoveryPlatformRequest)
                        val completion = f.runtime.coordinator.activity.value.completion
                        f.panel.act(SyncPanelAction.RecoveryPlatformCompleted(request.requestId, result))
                        assertEquals(completion, f.runtime.coordinator.activity.value.completion)
                        assertEquals(original, f.panel.state.value.recoveryFailure)
                        assertEquals(SyncRecoveryOutcome.WAITING_EXTERNAL, f.panel.state.value.recoveryOutcome)
                    }
                }
            }
        }

    @Test
    fun `recovery completion retained source failure opens actual extensions and offers protected new scope`() =
        runBlocking {
            open().use { storage ->
                SyncOnboardingFixture(storage).use { f ->
                    val material = f.existing("")
                    f.authorize()
                    f.begin()
                    open().use { sender ->
                        sender.connect("remote-source-actor", f.repository)
                        sender.favorite("/missing-source-object")
                        val transport = f.runtime.onboarding.transport("synthetic-token", material, repositoryId = 99)
                        val sent = SyncDatabaseExchange(
                            sender.handler,
                            sender.baseline,
                            sender.projector,
                            transport,
                            spaceMaterial = material,
                        )
                            .exchange("space", 1, f.repository)
                        assertEquals(SyncRunStatus.SUCCESS, sent.status)
                    }
                    val received = f.runtime.coordinator.synchronize(SyncTrigger.MANUAL)
                    assertEquals(SyncRunStatus.SUCCESS, received.status)
                    // SOURCE records originate in older projections. Current descriptors can restore a book offline.
                    // Preserve a retained legacy record through the real persistent report and controller path.
                    storage.handler.await {
                        val key = SyncObjectKey(
                            SyncObjectType.MANGA,
                            sourceId = "1",
                            originalUrl = "/missing-source-object",
                        )
                        val field = sync_inboxQueries.getFieldState(
                            "space",
                            1,
                            key.stableKey,
                            "FAVORITE",
                        ).executeAsOne()
                        sync_inboxQueries.setFieldState(
                            "SOURCE",
                            field.applied_heads,
                            "space",
                            1,
                            key.stableKey,
                            "FAVORITE",
                            field.revision,
                        )
                    }
                    f.panel.act(SyncPanelAction.OpenRecovery)
                    assertEquals("SOURCE", f.panel.state.value.recoveryRepairReport?.fields?.single()?.reason)
                    assertEquals(SyncRecoveryAction.EXTENSIONS, f.panel.state.value.recoveryPrimaryAction.action)
                    assertTrue(
                        f.panel.state.value.recoveryAlternativeActions.any {
                            it.action == SyncRecoveryAction.CREATE_SPACE
                        },
                    )
                    f.panel.act(SyncPanelAction.ExecuteRecoveryAction(SyncRecoveryAction.EXTENSIONS))
                    assertTrue(requireNotNull(f.panel.state.value.recoveryPlatformRequest).sourceIds.isNotEmpty())
                    assertEquals(1, storage.manga.getLibraryManga().size)
                    assertTrue(requireNotNull(f.panel.state.value.recoveryRepairReport).remaining > 0)
                }
            }
        }

    @Test
    fun `recovery completion preserves confirmed account when later setup read fails`() =
        runBlocking {
            open().use { storage ->
                SyncNativeRepositoryFixture(storage).use { f ->
                    f.app.authorize()
                    f.app.begin()
                    assertEquals(f.app.accountLogin, f.app.panel.state.value.setupAccountLogin)
                    f.app.git.server.dispatcher = failing(f.app.git.server.dispatcher, "/user", 500)
                    f.app.panel.act(SyncPanelAction.PrepareRepositoryCreation(f.repository.name))
                    assertEquals(f.app.accountLogin, f.app.panel.state.value.setupAccountLogin)
                    assertTrue(f.app.panel.state.value.setupInstallation?.canCreateRepository != true)
                    assertEquals(SyncDiscoveryProblem.RETRYABLE, f.app.panel.state.value.creationPermissionProblem)
                }
            }
        }

    @Test
    fun `recovery completion directs denied native creation to official creation without losing attempt`() =
        runBlocking {
            open().use { storage ->
                SyncNativeRepositoryFixture(storage).use { f ->
                    f.denyCreation = true
                    f.app.authorize()
                    f.app.begin()
                    f.app.panel.act(SyncPanelAction.PrepareRepositoryCreation(f.repository.name))
                    f.app.panel.act(SyncPanelAction.ConfirmQuestion)
                    withTimeout(10_000) {
                        f.app.panel.state.first {
                            it.setupStep == SyncSetupStep.ERROR && !it.setupBusy
                        }
                    }
                    assertEquals(
                        SyncRecoveryAction.OFFICIAL_CREATE,
                        f.app.panel.state.value.recoveryPrimaryAction.action,
                    )
                    assertEquals(f.repository.name, f.app.panel.state.value.repositoryCreationName)
                    assertEquals(1, f.posts)
                }
            }
        }

    @Test
    fun `recovery completion distinguishes service response from local network settings`() =
        runBlocking {
            open().use { storage ->
                SyncOnboardingFixture(storage).use { f ->
                    f.existing("")
                    f.authorize()
                    f.begin()
                    f.git.server.dispatcher = failing(f.git.server.dispatcher, "/user", 500)
                    f.panel.act(SyncPanelAction.OpenRecovery)
                    f.panel.act(SyncPanelAction.VerifyRecovery)
                    f.panel.awaitRecoveryIdle()
                    assertEquals(SyncRecoveryAction.WAIT_SERVICE, f.panel.state.value.recoveryPrimaryAction.action)
                    assertEquals(500, f.panel.state.value.recoveryFailure?.httpStatus)
                }
            }
        }

    @Test
    fun `recovery completion keeps safe unbound network request separate from storage failure`() =
        runBlocking {
            open().use { storage ->
                SyncOnboardingFixture(storage).use { f ->
                    f.panel.act(SyncPanelAction.OpenRecovery)
                    f.panel.act(SyncPanelAction.OpenRecoveryPlatform(SyncRecoveryPlatformAction.NETWORK))
                    assertEquals(false, f.panel.state.value.recoveryPersistenceFailed)
                    assertNull(f.panel.state.value.recoveryFailure)
                    val request = requireNotNull(f.panel.state.value.recoveryPlatformRequest)
                    val completion = f.runtime.coordinator.activity.value.completion
                    f.panel.act(
                        SyncPanelAction.RecoveryPlatformCompleted(
                            request.requestId,
                            SyncRecoveryPlatformResult.NoChange,
                        ),
                    )
                    assertEquals(completion, f.runtime.coordinator.activity.value.completion)
                    assertEquals(false, f.panel.state.value.recoveryPlatformLaunchPending)
                }
            }
        }

    @Test
    fun `recovery completion offers existing disabled binding restore and retains original actor`() =
        runBlocking {
            open().use { storage ->
                SyncOnboardingFixture(storage).use { f ->
                    f.existing("")
                    f.authorize()
                    f.begin()
                    val original = storage.handler.await {
                        sync_journalQueries.getCurrentActor("space", 1).executeAsOne()
                    }
                    val writes = f.repositoryWrites
                    f.runtime.disconnect()
                    f.panel.act(SyncPanelAction.Open)
                    f.panel.act(SyncPanelAction.OpenRecovery)
                    assertEquals(SyncRecoveryAction.ENABLE_SYNC, f.panel.state.value.recoveryPrimaryAction.action)
                    assertEquals(
                        original,
                        storage.handler.await {
                            sync_journalQueries.getCurrentActor(
                                "space",
                                1,
                            ).executeAsOne()
                        },
                    )
                    assertEquals(SyncPanelPage.RECOVERY, f.panel.state.value.page)
                    f.authorize()
                    f.panel.act(SyncPanelAction.Open)
                    f.panel.act(SyncPanelAction.OpenRecovery)
                    f.panel.act(SyncPanelAction.ExecuteRecoveryAction(SyncRecoveryAction.ENABLE_SYNC))
                    assertEquals(true, f.runtime.connection()?.enabled)
                    assertEquals(
                        original,
                        storage.handler.await {
                            sync_journalQueries.getCurrentActor(
                                "space",
                                1,
                            ).executeAsOne()
                        },
                    )
                    assertEquals(writes, f.repositoryWrites, "re-enabling validates by reads before synchronizing")
                    f.panel.act(SyncPanelAction.VerifyRecovery)
                    f.panel.awaitRecoveryIdle()
                    assertEquals(SyncRecoveryOutcome.ORIGINAL_VERIFIED, f.panel.state.value.recoveryOutcome)
                }
            }
        }

    @Test
    fun `unbound recovery remains on problem page and opens network without binding`() =
        runBlocking {
            open().use { storage ->
                SyncOnboardingFixture(storage).use { f ->
                    f.panel.act(SyncPanelAction.Open)
                    f.panel.act(SyncPanelAction.OpenRecovery)
                    assertEquals(SyncPanelPage.RECOVERY, f.panel.state.value.page)
                    f.panel.act(SyncPanelAction.OpenRecoveryPlatform(SyncRecoveryPlatformAction.NETWORK))
                    assertEquals(
                        SyncRecoveryPlatformAction.NETWORK,
                        f.panel.state.value.recoveryPlatformRequest?.action,
                    )
                    assertTrue(f.panel.state.value.recoveryPlatformLaunchPending)
                    assertNull(f.runtime.connection())
                    assertEquals(0, f.repositoryWrites)
                }
            }
        }

    @Test
    fun `unbound create space uses onboarding instead of old binding switch`() =
        runBlocking {
            open().use { storage ->
                SyncOnboardingFixture(storage).use { f ->
                    f.authorize()
                    f.panel.act(SyncPanelAction.Open)
                    f.panel.act(SyncPanelAction.CreateNewSpace)
                    f.panel.act(SyncPanelAction.ConfirmQuestion)
                    assertEquals(SyncSetupStep.PREPARE_REPOSITORY, f.panel.state.value.setupStep)
                    assertEquals(SyncPanelPage.SETUP, f.panel.state.value.page)
                    assertNull(f.runtime.connection())
                    assertEquals(0, f.repositoryWrites)
                }
            }
        }

    @Test
    fun `current panel retries recorded repository after authorization without another post`() =
        runBlocking {
            open().use { storage ->
                SyncNativeRepositoryFixture(storage).use { f ->
                    f.granted = false
                    f.app.authorize()
                    f.app.begin()
                    f.app.panel.act(SyncPanelAction.PrepareRepositoryCreation(f.repository.name))
                    f.app.panel.act(SyncPanelAction.ConfirmQuestion)
                    withTimeout(
                        10_000,
                    )
                    {
                        f.app.panel.state.first {
                            it.setupStep == SyncSetupStep.ERROR &&
                                it.creationRepositoryId == 99L
                        }
                    }
                    f.granted = true
                    f.app.panel.act(SyncPanelAction.RetrySetup)
                    withTimeout(
                        10_000,
                    )
                    {
                        f.app.panel.state.first {
                            !it.setupBusy &&
                                it.setupStep in setOf(
                                    SyncSetupStep.NEW_PASSWORD,
                                    SyncSetupStep.ERROR,
                                )
                        }
                    }
                    assertEquals(SyncSetupStep.NEW_PASSWORD, f.app.panel.state.value.setupStep)
                    assertEquals(f.repository, f.app.panel.state.value.setupRepository)
                    assertEquals(1, f.posts)
                }
            }
        }

    @Test
    fun `manual private empty repository requires confirmation after native permission rejection`() =
        runBlocking {
            open().use { storage ->
                SyncNativeRepositoryFixture(storage).use { f ->
                    f.denyCreation = true
                    f.app.authorize()
                    f.app.begin()
                    f.app.panel.act(SyncPanelAction.PrepareRepositoryCreation(f.repository.name))
                    f.app.panel.act(SyncPanelAction.ConfirmQuestion)
                    withTimeout(10_000) {
                        f.app.panel.state.first { it.setupStep == SyncSetupStep.ERROR && !it.setupBusy }
                    }
                    assertEquals(SyncDiscoveryProblem.NEEDS_CREATION_PERMISSION, f.app.panel.state.value.setupProblem)
                    assertEquals(f.repository.name, f.app.panel.state.value.repositoryCreationName)
                    // Explicit official creation with this name; its description has no private attempt marker.
                    f.available = true
                    f.app.panel.act(SyncPanelAction.PrepareManualRepository(f.repository.name))
                    assertEquals(SyncPanelQuestion.CONNECT_MANUAL_REPOSITORY, f.app.panel.state.value.question)
                    assertEquals(f.repository, f.app.panel.state.value.setupRepository)
                    f.app.panel.act(SyncPanelAction.ConfirmQuestion)
                    withTimeout(
                        10_000,
                    )
                    {
                        f.app.panel.state.first {
                            it.setupStep == SyncSetupStep.NEW_PASSWORD ||
                                (
                                    it.setupStep == SyncSetupStep.ERROR &&
                                        !it.setupBusy
                                    )
                        }
                    }
                    assertEquals(SyncSetupStep.NEW_PASSWORD, f.app.panel.state.value.setupStep)
                    assertEquals(99L, f.app.panel.state.value.creationRepositoryId)
                    assertEquals(1, f.posts)
                    f.app.panel.act(SyncPanelAction.SubmitPassword(""))
                    withTimeout(
                        10_000,
                    )
                    {
                        f.app.panel.state.first {
                            it.setupStep in setOf(
                                SyncSetupStep.COMPLETE,
                                SyncSetupStep.ERROR,
                            ) &&
                                !it.setupBusy
                        }
                    }
                    assertEquals(SyncSetupStep.COMPLETE, f.app.panel.state.value.setupStep)
                    assertEquals(f.repository, f.app.runtime.connection()?.repository)
                    assertEquals(1, f.posts)
                }
            }
        }

    @Test
    fun `historical recovery success is invalidated by later real failure or new retained rejection`() =
        runBlocking {
            for (networkFailure in listOf(true, false)) {
                open().use { storage ->
                    SyncOnboardingFixture(storage).use { f ->
                        f.existing("")
                        f.authorize()
                        f.begin()
                        f.panel.act(SyncPanelAction.OpenRecovery)
                        f.panel.act(SyncPanelAction.VerifyRecovery)
                        f.panel.awaitRecoveryIdle()
                        assertEquals(
                            SyncRecoveryOutcome.ORIGINAL_VERIFIED,
                            f.panel.state.value.recoveryOutcome,
                        )
                        if (networkFailure) {
                            f.git.server.dispatcher = failing(f.git.server.dispatcher, "/user", 500)
                            assertEquals(
                                SyncRunProblem.NETWORK,
                                f.runtime.coordinator.synchronize(SyncTrigger.MANUAL).problem,
                            )
                        } else {
                            val inbox = mihon.data.sync.inbox.SyncInboxStore(storage.handler)
                            inbox.recordRejected(
                                "space",
                                1,
                                "retained-invalid",
                                "",
                                "INVALID_PAYLOAD",
                                "original retained body",
                            )
                        }
                        f.panel.act(SyncPanelAction.OpenRecovery)
                        assertEquals(
                            if (networkFailure) {
                                SyncRecoveryOutcome.WAITING_EXTERNAL
                            } else {
                                SyncRecoveryOutcome.REMAINING
                            },
                            f.panel.state.value.recoveryOutcome,
                        )
                    }
                }
            }
        }

    @Test
    fun `native explicit repository creation verifies fixed id password and real baseline`() =
        runBlocking {
            open().use { storage ->
                storage.favorite("/kept-native-baseline")
                SyncNativeRepositoryFixture(storage).use { f ->
                    f.app.authorize()
                    f.app.begin()
                    f.app.panel.act(SyncPanelAction.PrepareRepositoryCreation(f.repository.name))
                    assertEquals(SyncPanelQuestion.CREATE_REPOSITORY, f.app.panel.state.value.question)
                    assertEquals(f.repository, f.app.panel.state.value.setupRepository)
                    assertEquals(0, f.posts)
                    f.app.panel.act(SyncPanelAction.CancelQuestion)
                    assertEquals(0, f.posts)
                    f.app.panel.act(SyncPanelAction.PrepareRepositoryCreation(f.repository.name))
                    f.app.panel.act(SyncPanelAction.ConfirmQuestion)
                    withTimeout(
                        10_000,
                    )
                    {
                        f.app.panel.state.first {
                            it.setupStep == SyncSetupStep.NEW_PASSWORD ||
                                (
                                    it.setupStep == SyncSetupStep.ERROR &&
                                        it.creationSubmitted
                                    )
                        }
                    }
                    assertEquals(SyncSetupStep.NEW_PASSWORD, f.app.panel.state.value.setupStep)
                    assertEquals(1, f.posts)
                    assertEquals(99L, f.app.panel.state.value.creationRepositoryId)
                    f.app.panel.act(SyncPanelAction.SubmitPassword(""))
                    withTimeout(
                        10_000,
                    )
                    {
                        f.app.panel.state.first {
                            it.setupStep in setOf(
                                SyncSetupStep.COMPLETE,
                                SyncSetupStep.ERROR,
                            ) &&
                                !it.setupBusy
                        }
                    }
                    assertEquals(SyncSetupStep.COMPLETE, f.app.panel.state.value.setupStep)
                    assertEquals(f.repository, f.app.runtime.connection()?.repository)
                    assertEquals(0L, f.app.panel.state.value.importRemaining)
                    assertEquals("/kept-native-baseline", storage.manga.getLibraryManga().single().manga.url)
                    assertEquals(1, f.posts)
                }
            }
        }

    @Test
    fun `native created repository resumes granted scope without another post`() =
        runBlocking {
            open().use { storage ->
                SyncNativeRepositoryFixture(storage).use { f ->
                    f.granted = false
                    f.app.authorize()
                    f.app.begin()
                    f.app.panel.act(SyncPanelAction.PrepareRepositoryCreation(f.repository.name))
                    f.app.panel.act(SyncPanelAction.ConfirmQuestion)
                    withTimeout(
                        10_000,
                    )
                    {
                        f.app.panel.state.first {
                            it.setupStep == SyncSetupStep.ERROR &&
                                it.creationRepositoryId == 99L
                        }
                    }
                    assertEquals(
                        SyncDiscoveryProblem.NEEDS_INSTALLATION_ACCESS_PERMISSION,
                        f.app.panel.state.value.setupProblem,
                    )
                    assertEquals(1, f.posts)
                    f.app.runtime.stopPanel()
                    // The user selected the already created fixed id through the official installation settings.
                    f.granted = true
                    val reopened = f.app.runtime()
                    try {
                        val panel = reopened.panel as SyncPanelController
                        panel.act(SyncPanelAction.Open)
                        panel.act(SyncPanelAction.BeginSetup)
                        withTimeout(
                            10_000,
                        )
                        {
                            panel.state.first {
                                it.setupStep == SyncSetupStep.NEW_PASSWORD ||
                                    (
                                        it.setupStep == SyncSetupStep.ERROR &&
                                            !it.setupBusy
                                        )
                            }
                        }
                        assertEquals(SyncSetupStep.NEW_PASSWORD, panel.state.value.setupStep)
                        assertEquals(1, f.posts)
                        assertEquals(99L, panel.state.value.creationRepositoryId)
                    } finally {
                        reopened.stopPanel()
                    }
                }
            }
        }

    @Test
    fun `native property confirmation targets the fixed repository then verifies real synchronization`() =
        runBlocking {
            open().use { storage ->
                SyncNativeRepositoryFixture(storage, "mihon-sync").use { f ->
                    f.available = true
                    f.app.existing("")
                    f.app.authorize()
                    f.app.begin()
                    f.archived = true
                    f.privateRepository = false
                    f.app.panel.act(SyncPanelAction.OpenRecovery)
                    f.app.panel.act(SyncPanelAction.RepairRepositoryProperties(makePrivate = true, unarchive = true))
                    assertEquals(SyncPanelQuestion.REPAIR_REPOSITORY_PROPERTIES, f.app.panel.state.value.question)
                    assertEquals(f.repository, f.app.panel.state.value.setupRepository)
                    assertEquals(0, f.patches)
                    f.app.panel.act(SyncPanelAction.ConfirmQuestion)
                    withTimeout(
                        10_000,
                    )
                    {
                        f.app.panel.state.first {
                            !it.recoveryBusy &&
                                it.recoveryOutcome == SyncRecoveryOutcome.ORIGINAL_VERIFIED
                        }
                    }
                    assertEquals(1, f.patches)
                    assertEquals(false, f.archived)
                    assertEquals(true, f.privateRepository)
                }
            }
        }

    @Test
    fun `native pending repository creation rejects changed account before remote mutation`() =
        runBlocking {
            open().use { storage ->
                SyncNativeRepositoryFixture(storage).use { f ->
                    f.app.authorize()
                    f.app.begin()
                    val credential = f.app.runtime.credentials.read()
                    f.app.panel.act(SyncPanelAction.PrepareRepositoryCreation(f.repository.name))
                    assertEquals(SyncPanelQuestion.CREATE_REPOSITORY, f.app.panel.state.value.question)
                    f.app.accountId = 2
                    f.app.panel.act(SyncPanelAction.ConfirmQuestion)
                    withTimeout(5_000) {
                        f.app.panel.state.first {
                            it.setupStep == SyncSetupStep.ERROR && !it.setupBusy
                        }
                    }
                    assertEquals(SyncDiscoveryProblem.ACCOUNT_CHANGED, f.app.panel.state.value.setupProblem)
                    assertEquals(credential, f.app.runtime.credentials.read())
                    assertEquals(0, f.posts)
                }
            }
        }

    @Test
    fun `secure store read and write failure still emits claimable minimal storage escape`() =
        runBlocking {
            open().use { storage ->
                SyncOnboardingFixture(storage).use { f ->
                    f.existing("")
                    f.authorize()
                    f.begin()
                    f.runtime.stopPanel()
                    val original = f.secure.values.toMap()
                    f.secure.fail = true
                    val restarted = f.runtime()
                    try {
                        val panel = restarted.panel as SyncPanelController
                        panel.act(SyncPanelAction.Open)
                        panel.act(SyncPanelAction.OpenRecovery)
                        panel.act(
                            SyncPanelAction.OpenRecoveryPlatform(
                                mihon.data.sync.runtime.SyncRecoveryPlatformAction.STORAGE,
                            ),
                        )
                        val request = requireNotNull(panel.state.value.recoveryPlatformRequest)
                        assertTrue(panel.claimRecoveryPlatform(request.requestId))
                        assertTrue(request.objects.isEmpty())
                        assertTrue(request.sourceIds.isEmpty())
                        assertTrue(panel.state.value.recoveryPersistenceFailed)
                        assertEquals(original, f.secure.values.toMap())
                        assertEquals(0, f.userRepoPosts)
                    } finally {
                        restarted.stopPanel()
                    }
                }
            }
        }

    @Test
    fun `data repair acts through actual remote projection and final synchronization`() =
        runBlocking {
            open().use { storage ->
                SyncOnboardingFixture(storage).use { f ->
                    val material = f.existing("")
                    f.authorize()
                    f.begin()
                    open().use { sender ->
                        sender.connect("remote-repair-actor", f.repository)
                        sender.favorite("/source-repair-object")
                        val transport = f.runtime.onboarding.transport("synthetic-token", material, repositoryId = 99)
                        val sent = SyncDatabaseExchange(
                            sender.handler,
                            sender.baseline,
                            sender.projector,
                            transport,
                            spaceMaterial = material,
                        ).exchange(
                            "space",
                            1,
                            f.repository,
                        )
                        assertEquals(SyncRunStatus.SUCCESS, sent.status)
                    }
                    val transport = f.runtime.onboarding.transport("synthetic-token", material, repositoryId = 99)
                    val snapshot = transport.readSnapshot(f.repository, "space", 1).getOrThrow()
                    val entry = snapshot.batches.single()
                    // A prior transient validator failure is persisted with the actual immutable remote body.
                    // Ordinary synchronization does not rediscover this already admitted batch; repair must refetch it.
                    assertEquals(SyncRunStatus.SUCCESS, f.runtime.coordinator.synchronize(SyncTrigger.MANUAL).status)
                    mihon.data.sync.inbox.SyncInboxStore(storage.handler).recordRejected(
                        "space",
                        1,
                        entry.batchId,
                        entry.path,
                        "sync batch could not be authenticated",
                        requireNotNull(f.git.file(f.repository.branch, entry.path)).decodeToString(),
                    )
                    val batchBlob = snapshot.tree.entries.single { it.path == entry.path }.sha
                    var batchReads = 0
                    val delegate = f.git.server.dispatcher
                    f.git.server.dispatcher = object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse {
                            if (request.url.encodedPath.endsWith("/git/blobs/$batchBlob")) batchReads++
                            return delegate.dispatch(request)
                        }
                    }
                    f.panel.act(SyncPanelAction.OpenRecovery)
                    f.panel.act(SyncPanelAction.VerifyRecovery)
                    f.panel.awaitRecoveryIdle()
                    assertEquals(SyncRecoveryOutcome.REMAINING, f.panel.state.value.recoveryOutcome)
                    assertEquals(
                        entry.batchId,
                        requireNotNull(
                            f.panel.state.value.recoveryRepairReport,
                        ).batches.single().batchId,
                    )
                    assertEquals(
                        0,
                        batchReads,
                        "a read-only check and ordinary sync must not replace the explicit refetch",
                    )
                    f.panel.act(SyncPanelAction.RepairData())
                    f.panel.awaitRecoveryIdle()
                    assertTrue(batchReads > 0, "RepairData must execute the real production batch read")
                    assertEquals(
                        SyncRecoveryOutcome.ORIGINAL_VERIFIED,
                        f.panel.state.value.recoveryOutcome,
                    )
                    assertEquals(0L, f.panel.state.value.recoveryRepairReport?.remaining)
                    assertEquals("/source-repair-object", storage.manga.getLibraryManga().single().manga.url)
                }
            }
        }

    @Test
    fun `recovery HTTP failure retains actual phase and status rather than clearing original problem`() =
        runBlocking {
            open().use { storage ->
                SyncOnboardingFixture(storage).use { f ->
                    f.existing("")
                    f.authorize()
                    f.begin()
                    f.git.server.dispatcher = failing(f.git.server.dispatcher, "/user", 500)
                    f.panel.act(SyncPanelAction.OpenRecovery)
                    f.panel.act(SyncPanelAction.VerifyRecovery)
                    f.panel.awaitRecoveryIdle()
                    assertEquals(
                        mihon.domain.sync.runtime.SyncNetworkFailurePhase.HTTP_RESPONSE,
                        f.panel.state.value.recoveryFailure?.networkPhase,
                    )
                    assertEquals(500, f.panel.state.value.recoveryFailure?.httpStatus)
                    assertEquals(
                        SyncRecoveryOutcome.WAITING_EXTERNAL,
                        f.panel.state.value.recoveryOutcome,
                    )
                }
            }
        }

    @Test
    fun `recovery verifies by actual coordinator exchange and retains the recovery page`() =
        runBlocking {
            open().use { storage ->
                SyncOnboardingFixture(storage).use { f ->
                    f.existing("")
                    f.authorize()
                    f.begin()
                    f.panel.act(SyncPanelAction.OpenRecovery)
                    val completion = f.runtime.coordinator.activity.value.completion
                    val requests = f.git.server.requestCount
                    f.panel.act(SyncPanelAction.VerifyRecovery)
                    f.panel.awaitRecoveryIdle()
                    assertTrue(f.runtime.coordinator.activity.value.completion > completion)
                    assertTrue(f.git.server.requestCount > requests)
                    assertEquals(
                        SyncRecoveryOutcome.ORIGINAL_VERIFIED,
                        f.panel.state.value.recoveryOutcome,
                    )
                    assertEquals(SyncPanelPage.RECOVERY, f.panel.state.value.page)
                }
            }
        }

    @Test
    fun `successful read only recheck retains recovery without claiming a synchronization result`() =
        runBlocking {
            open().use { storage ->
                SyncOnboardingFixture(storage).use { f ->
                    f.existing("")
                    f.authorize()
                    f.begin()
                    f.panel.act(SyncPanelAction.OpenRecovery)
                    val completion = f.runtime.coordinator.activity.value.completion
                    f.panel.act(SyncPanelAction.RecheckSpace)
                    f.panel.awaitRecoveryIdle()
                    assertEquals(SyncPanelPage.RECOVERY, f.panel.state.value.page)
                    assertEquals(completion, f.runtime.coordinator.activity.value.completion)
                    assertNull(f.panel.state.value.recoveryOutcome)
                }
            }
        }

    @Test
    fun `unreadable sealed binding still opens safe recovery without changing original record`() =
        runBlocking {
            open().use { storage ->
                SyncOnboardingFixture(storage).use { f ->
                    f.existing("")
                    f.authorize()
                    f.begin()
                    val key = f.secure.values.keys.single { it.startsWith("space-") && !it.contains("-recovery") }
                    f.secure.values[key] = "{"
                    f.panel.act(SyncPanelAction.OpenRecovery)
                    assertEquals(SyncPanelPage.RECOVERY, f.panel.state.value.page)
                    assertEquals(SyncRunProblem.STORAGE, f.panel.state.value.recoveryFailure?.problem)
                    assertEquals(false, f.panel.state.value.canChangeSpace)
                    assertEquals("{", f.secure.values[key])
                    assertEquals(0, f.userRepoPosts)
                }
            }
        }

    @Test
    fun `external recovery action survives close restart and failure without automatic reopen`() =
        runBlocking {
            open().use { storage ->
                SyncOnboardingFixture(storage).use { f ->
                    f.existing("")
                    f.authorize()
                    f.begin()
                    f.panel.act(SyncPanelAction.OpenRecovery)
                    f.panel.act(
                        SyncPanelAction.OpenRecoveryPlatform(
                            mihon.data.sync.runtime.SyncRecoveryPlatformAction.NETWORK,
                        ),
                    )
                    val request = f.panel.state.value.recoveryPlatformRequest
                    assertNotNull(request)
                    assertTrue(f.panel.claimRecoveryPlatform(requireNotNull(request).requestId))
                    assertEquals(false, f.panel.claimRecoveryPlatform(request.requestId))
                    f.panel.act(SyncPanelAction.RecoveryPlatformFailed(request.requestId))
                    f.panel.act(SyncPanelAction.Close)
                    val restarted = f.runtime()
                    try {
                        val panel = restarted.panel as SyncPanelController
                        panel.act(SyncPanelAction.Open)
                        panel.act(SyncPanelAction.OpenRecovery)
                        assertEquals(request.requestId, panel.state.value.recoveryPlatformRequest?.requestId)
                        assertEquals(true, panel.state.value.recoveryPlatformRequest?.failed)
                        assertEquals(false, panel.claimRecoveryPlatform(request.requestId))
                        assertEquals(
                            SyncRecoveryOutcome.WAITING_EXTERNAL,
                            panel.state.value.recoveryOutcome,
                        )
                    } finally {
                        restarted.stopPanel()
                    }
                }
            }
        }

    @Test
    fun `external old profile origin survives restart without claiming original scope success`() =
        runBlocking {
            open().use { storage ->
                SyncOnboardingFixture(storage).use { f ->
                    f.runtime.markExternalUnverifiedRecoveryOrigin("原来无法读取的配置目录")
                    f.existing("")
                    f.authorize()
                    f.begin()
                    f.runtime.stopPanel()
                    val restarted = f.runtime()
                    try {
                        val panel = restarted.panel as SyncPanelController
                        panel.act(SyncPanelAction.Open)
                        panel.act(SyncPanelAction.OpenRecovery)
                        panel.act(SyncPanelAction.VerifyRecovery)
                        panel.awaitRecoveryIdle()
                        assertEquals("原来无法读取的配置目录", panel.state.value.externalRecoveryOrigin)
                        assertEquals(
                            SyncRecoveryOutcome.NEW_SCOPE_VERIFIED_WITH_OLD_REMAINING,
                            panel.state.value.recoveryOutcome,
                        )
                    } finally {
                        restarted.stopPanel()
                    }
                }
            }
        }

    @Test
    fun `oauth server retry deadline persists before account identity is available`() =
        runBlocking {
            open().use { storage ->
                SyncOnboardingFixture(storage).use { f ->
                    val delegate = f.git.server.dispatcher
                    var calls = 0
                    f.git.server.dispatcher = object : Dispatcher() {
                        override fun dispatch(
                            request: RecordedRequest,
                        ): MockResponse =
                            if (
                                request.url.encodedPath == "/device"
                            ) {
                                calls++
                                MockResponse.Builder().code(429).addHeader("Retry-After", "120").body("{}").build()
                            } else {
                                delegate.dispatch(request)
                            }
                    }
                    f.runtime.authorization.authorize("public-client") {}
                    val restarted = f.runtime()
                    try {
                        val result = restarted.authorization.authorize(
                            "public-client",
                        )
                            {
                            }
                            as mihon.domain.sync.auth.GitHubDeviceAuthResult.Failed
                        assertEquals(mihon.domain.sync.auth.GitHubAuthFailureReason.RATE_LIMITED, result.failure.reason)
                        assertEquals(1, calls)
                    } finally {
                        restarted.stopPanel()
                    }
                }
            }
        }

    protected abstract fun open(): SyncRuntimeStorageContract.Storage

    @Test
    fun `reauthorization refreshes recovery choices before resuming a deleted repository`() =
        runBlocking {
            open().use { storage ->
                SyncOnboardingFixture(storage).use { setup ->
                    storage.favorite("/preserved-import")
                    setup.runtime.preferences.importPaused.set(true)
                    setup.existing("")
                    setup.authorize()
                    setup.begin()
                    val binding = setup.runtime.connection()
                    val pending = requireNotNull(setup.runtime.onboarding.storage.pending(1L))
                    val writes = setup.repositoryWrites
                    setup.created = false
                    setup.runtime.credentials.clear()
                    setup.panel.act(SyncPanelAction.Open)
                    assertEquals(false, setup.panel.state.value.canChangeSpace)

                    setup.panel.act(SyncPanelAction.Authorize)
                    withTimeout(10_000) {
                        setup.panel.state.first { it.setupStep == SyncSetupStep.ERROR && !it.setupBusy }
                    }
                    assertTrue(
                        setup.panel.state.value.canChangeSpace,
                        "new authorization must refresh recovery eligibility",
                    )
                    assertEquals(
                        SyncDiscoveryProblem.REPOSITORY_UNAVAILABLE,
                        setup.panel.state.value.setupProblem,
                    )
                    setup.panel.awaitRecoveryIdle()
                    setup.panel.act(SyncPanelAction.OpenRecovery)
                    assertEquals(SyncPanelPage.RECOVERY, setup.panel.state.value.page)
                    setup.panel.act(SyncPanelAction.CreateNewSpace)
                    setup.panel.act(SyncPanelAction.ConfirmQuestion)
                    withTimeout(5_000) { setup.panel.state.first { it.setupStep == SyncSetupStep.PREPARE_REPOSITORY } }
                    assertEquals(binding, setup.runtime.connection())
                    assertEquals(pending, setup.runtime.onboarding.storage.pending(1L))
                    assertEquals(writes, setup.repositoryWrites)
                    assertEquals(0, setup.userRepoPosts)
                }
            }
        }

    @Test
    fun `generic setup error opens neutral recovery and back preserves source`() =
        runBlocking {
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
    fun `setup account authorization failure is verified as recovery while keeping error page`() =
        runBlocking {
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
                    assertEquals(
                        SyncSpaceRecoveryReason.AUTHORIZATION_REQUIRED,
                        setup.panel.state.value.recovery?.reason,
                    )
                    assertEquals(SyncPanelPage.SETUP, setup.panel.state.value.page)
                    assertEquals(binding, setup.runtime.connection())
                    assertEquals(credential, setup.runtime.credentials.read())
                    assertNull(setup.runtime.activeSwitch())
                }
            }
        }

    @Test
    fun `setup error diagnostics returns to the same failure page`() =
        runBlocking {
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
    fun `retrying failed setup records another failure and does not restart a busy request`() =
        runBlocking {
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
    fun `recovery entry without binding keeps safe problem page and setup continuation`() =
        runBlocking {
            open().use { storage ->
                SyncOnboardingFixture(storage).use { setup ->
                    setup.panel.act(SyncPanelAction.Open)
                    setup.panel.act(SyncPanelAction.OpenRecovery)
                    assertEquals(SyncPanelPage.RECOVERY, setup.panel.state.value.page)
                    assertEquals(SyncSetupStep.SIGN_IN, setup.panel.state.value.setupStep)
                    assertEquals(false, setup.panel.state.value.canChangeSpace)
                }
            }
        }

    @Test
    fun `unreadable or unsupported binding cannot open executable recovery choices`() =
        runBlocking {
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
                        assertEquals(SyncPanelPage.RECOVERY, setup.panel.state.value.page)
                        assertEquals(value, setup.secure.values[key])
                        val requests = setup.git.server.requestCount
                        setup.panel.act(SyncPanelAction.OpenRecoveryPlatform(SyncRecoveryPlatformAction.STORAGE))
                        val request = requireNotNull(setup.panel.state.value.recoveryPlatformRequest)
                        assertTrue(setup.panel.claimRecoveryPlatform(request.requestId))
                        assertEquals(value, setup.secure.values[key])
                        assertEquals(requests, setup.git.server.requestCount)
                    }
                    setup.secure.values[key] = binding
                    setup.runtime.credentials.clear()
                    setup.panel.act(SyncPanelAction.Open)
                    setup.panel.act(SyncPanelAction.OpenRecovery)
                    assertEquals(false, setup.panel.state.value.canChangeSpace)
                    assertEquals(
                        SyncPanelPage.RECOVERY,
                        setup.panel.state.value.page,
                        "missing credential keeps a safe recovery page without executable replacement choices",
                    )
                    assertEquals(SyncSetupStep.ERROR, setup.panel.state.value.setupStep)
                    setup.authorize()
                    setup.secure.readFailure = true
                    setup.panel.act(SyncPanelAction.OpenRecovery)
                    assertEquals(false, setup.panel.state.value.canChangeSpace)
                    assertEquals(SyncPanelPage.RECOVERY, setup.panel.state.value.page)
                    assertEquals(binding, setup.secure.values[key])
                    assertEquals(SyncRunProblem.STORAGE, setup.panel.state.value.problem)
                    setup.secure.readFailure = false
                }
            }
        }

    @Test
    fun `cold recovery read failure does not assume a first configuration`() =
        runBlocking {
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
                        SyncPanelPage.RECOVERY,
                        setup.panel.state.value.page,
                        "cold storage failure opens only safe recovery, never first setup",
                    )
                    assertEquals(SyncRunProblem.STORAGE, setup.panel.state.value.recoveryFailure?.problem)
                    assertNull(setup.panel.state.value.question)
                    assertEquals(false, setup.panel.state.value.canChangeSpace)
                    assertEquals(SyncRunProblem.STORAGE, setup.panel.state.value.problem)
                    setup.panel.act(SyncPanelAction.OpenRecoveryPlatform(SyncRecoveryPlatformAction.STORAGE))
                    val request = requireNotNull(setup.panel.state.value.recoveryPlatformRequest)
                    assertTrue(setup.panel.claimRecoveryPlatform(request.requestId))
                    assertEquals(false, setup.panel.state.value.canChangeSpace)
                    assertEquals(0, setup.git.server.requestCount)
                }
            }
        }

    @Test
    fun `replacement authorization validates real account before replacing original credential`() =
        runBlocking {
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
    fun `recovery check remembers last real fact across failed check and controller recreation`() =
        runBlocking {
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
    fun `authorization check uses real identity and confirms independently of unavailable space`() =
        runBlocking {
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
                        assertEquals(
                            SyncRecoveryAuthorization.CONFIRMED,
                            restarted.panel.state.value.recoveryAuthorization,
                        )
                        assertEquals(setup.now, restarted.panel.state.value.recovery?.authorizationConfirmedAtMillis)
                    } finally {
                        restarted.stopPanel()
                    }
                }
            }
        }

    @Test
    fun `authorization identity errors never become confirmed or browser success`() =
        runBlocking {
            val cases = listOf(
                404 to "{}",
                403 to "{}",
                429 to "{}",
                500 to "{}",
                200 to "{}",
                200 to "broken",
            )
            for ((code, body) in cases) {
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
                        assertEquals(
                            SyncSpaceRecoveryReason.SPACE_UNAVAILABLE,
                            setup.panel.state.value.recovery?.reason,
                        )
                    }
                }
            }
        }

    @Test
    fun `unfinished recovery switch remains available after close and recreation`() =
        runBlocking {
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
                        assertEquals(
                            SyncRecoveryContinuation.CONNECT,
                            restarted.panel.state.value.pendingRecoveryPurpose,
                        )
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
    fun `invalid recovery observations never supply facts or clear the gate`() =
        runBlocking {
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
                            assertEquals(
                                SyncRecoveryAuthorization.IDLE,
                                restarted.panel.state.value.recoveryAuthorization,
                            )
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
    fun `confirmed authorization is cleared by real 401 while waiting for fresh browser approval`() =
        runBlocking {
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
    fun `required repository 404 gates restart and all triggers without losing local data`() =
        runBlocking {
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
    fun `connected snapshot ref 404 is recovery while transient server and rate failures are not`() =
        runBlocking {
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
    fun `connected setup resume checks inaccessible old binding without creating or deleting anything`() =
        runBlocking {
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
    fun `malformed and future recovery records gate runs and network`() =
        runBlocking {
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
    fun `explicit recheck does not overwrite future recovery record`() =
        runBlocking {
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
    fun `verified connected descriptor corruption is durable space data recovery`() =
        runBlocking {
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
    fun `late recheck cannot navigate back after user returns to main`() =
        runBlocking {
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
    fun `late recheck cannot replace an in progress authorization page`() =
        runBlocking {
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
    fun `authenticated index identity mismatch is explicit durable data recovery`() =
        runBlocking {
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
