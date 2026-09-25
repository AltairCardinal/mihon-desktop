package mihon.data.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mihon.data.sync.journal.SyncLocalJournal
import mihon.data.sync.runtime.SyncDatabaseExchange
import mihon.data.sync.runtime.SyncPanelAction
import mihon.data.sync.runtime.SyncRunState
import mihon.data.sync.runtime.SyncRuntime
import mihon.data.sync.runtime.SyncSetupStep
import mihon.data.sync.transport.GitHubSyncTransport
import mihon.domain.sync.runtime.SyncRunProblem
import mihon.domain.sync.runtime.SyncRunStatus
import mihon.domain.sync.runtime.SyncTrigger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.JvmDatabaseHandler
import tachiyomi.data.Mangas
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import java.nio.file.Files

/** Closes the JDBC driver and constructs a new production Runtime over the same durable database. */
class SyncRuntimeRestartRecoveryAcceptanceTest {
    @Test
    fun `F-E lost ref readback resumes the saved artifact after file database reopen`() = runBlocking {
        withFileDatabase { databasePath, storage ->
            SyncOnboardingFixture(storage).use { setup ->
                var initialStorageOpen = true
                var reopenedStorage: SyncRuntimeStorageContract.Storage? = null
                var reopenedRuntime: SyncRuntime? = null
                try {
                    setup.existing("")
                    setup.authorize()
                    setup.begin()
                    setup.completeSetup("")
                    val runtime = setup.runtime
                    storage.favorite("/f-e-lost-ref-readback")
                    setup.git.failReadAfterPatch = true

                    val interrupted = runtime.coordinator.synchronize(SyncTrigger.MANUAL)
                    assertNotEquals(SyncRunStatus.SUCCESS, interrupted.status)
                    val preparedBefore = preparedUpload(storage)
                    assertNotNull(preparedBefore, "the frozen upload must survive an unknown ref response")
                    val writesBeforeRestart = setup.git.pathWrites.mapValues { it.value.toList() }
                    val refPatchesBeforeRestart = setup.git.forceFlags.size
                    val readsBeforeRestart =
                        setup.git.snapshotReads + setup.git.commitReads + setup.git.treeRequests + setup.git.blobReads

                    runtime.stopPanel()
                    storage.close()
                    initialStorageOpen = false
                    reopenedStorage = openFileStorage(databasePath, create = false)
                    reopenedRuntime = newRuntime(reopenedStorage, setup)
                    setup.now += reopenedRuntime.recoveryDelayMillis()
                    val resumed = reopenedRuntime.coordinator.synchronize(SyncTrigger.RECOVERY)

                    assertEquals(SyncRunStatus.SUCCESS, resumed.status)
                    assertEquals(
                        refPatchesBeforeRestart,
                        setup.git.forceFlags.size,
                        "recovery must reconcile the live ref before republishing",
                    )
                    assertEquals(writesBeforeRestart, setup.git.pathWrites.mapValues { it.value.toList() })
                    assertEquals(null, preparedUpload(reopenedStorage))
                    assertTrue(
                        SyncLocalJournal(reopenedStorage.handler).pendingEvents("space", 1).isEmpty(),
                    )
                    printRecoveryCounters("F-E", setup, readsBeforeRestart)
                } finally {
                    reopenedRuntime?.stopPanel()
                    setup.runtime.stopPanel()
                    reopenedStorage?.close()
                    if (initialStorageOpen) storage.close()
                }
            }
        }
    }

    /** Nearest available F-F seam: remote ref changed, then the local readback-anchor transaction fails before ack. */
    @Test
    fun `F-F nearest readback anchor failure preserves outbox through file database reopen`() = runBlocking {
        withFileDatabase { databasePath, storage ->
            SyncOnboardingFixture(storage).use { setup ->
                var initialStorageOpen = true
                var reopenedStorage: SyncRuntimeStorageContract.Storage? = null
                var reopenedRuntime: SyncRuntime? = null
                try {
                    val material = setup.existing("f-f-restart-password")
                    setup.authorize()
                    setup.begin()
                    setup.completeSetup("f-f-restart-password")
                    val runtime = setup.runtime
                    val current = spaceTransport(setup, material)
                        .readSnapshot(setup.repository, "space", 1).getOrThrow()
                    storage.favorite("/f-f-confirm-before-ack")
                    storage.driver.execute(
                        null,
                        "CREATE TRIGGER fail_restart_readback_anchor BEFORE INSERT ON sync_remote_heads " +
                            "WHEN NEW.head_sha != '${current.head}' " +
                            "BEGIN SELECT RAISE(ABORT, 'synthetic readback anchor failure'); END",
                        0,
                    )

                    val failed = runtime.coordinator.synchronize(SyncTrigger.MANUAL)
                    assertNotEquals(SyncRunStatus.SUCCESS, failed.status)
                    assertEquals(SyncRunProblem.UNKNOWN, failed.problem, failed.toString())
                    assertNotNull(
                        preparedUpload(storage),
                        "the outbox must remain pending before local acknowledgement",
                    )
                    val writtenPaths = setup.git.pathWrites.mapValues { it.value.toList() }
                    val patchCount = setup.git.forceFlags.size
                    val readsBeforeRestart =
                        setup.git.snapshotReads + setup.git.commitReads + setup.git.treeRequests + setup.git.blobReads
                    assertNotEquals(current.head, setup.git.head(setup.repository.branch))

                    runtime.stopPanel()
                    storage.close()
                    initialStorageOpen = false
                    reopenedStorage = openFileStorage(databasePath, create = false)
                    reopenedStorage.driver.execute(null, "DROP TRIGGER fail_restart_readback_anchor", 0)
                    reopenedRuntime = newRuntime(reopenedStorage, setup)
                    assertEquals(
                        SyncRunState.BLOCKED,
                        reopenedRuntime.runStore.latest("space", 1)?.state,
                        "the SQL anchor failure is durable but not an automatic network-recovery run",
                    )
                    val requestsBeforeRecovery = setup.git.server.requestCount
                    val automatic = reopenedRuntime.coordinator.synchronize(SyncTrigger.RECOVERY)
                    assertEquals(SyncRunStatus.SKIPPED, automatic.status)
                    assertEquals(requestsBeforeRecovery, setup.git.server.requestCount)
                    val resumed = reopenedRuntime.coordinator.synchronize(SyncTrigger.MANUAL)

                    assertEquals(SyncRunStatus.SUCCESS, resumed.status, "manual retry problem=${resumed.problem}")
                    assertEquals(
                        patchCount,
                        setup.git.forceFlags.size,
                        "recovery should first account for the already published ref",
                    )
                    assertEquals(writtenPaths, setup.git.pathWrites.mapValues { it.value.toList() })
                    assertEquals(null, preparedUpload(reopenedStorage))
                    assertTrue(SyncLocalJournal(reopenedStorage.handler).pendingEvents("space", 1).isEmpty())
                    printRecoveryCounters("F-F-nearest-anchor-before-ack", setup, readsBeforeRestart)
                } finally {
                    reopenedRuntime?.stopPanel()
                    setup.runtime.stopPanel()
                    reopenedStorage?.close()
                    if (initialStorageOpen) storage.close()
                }
            }
        }
    }

    @Test
    fun `F-G committed receipt stays deduplicated after file database reopen`() = runBlocking {
        withFileDatabase { databasePath, storage ->
            SyncOnboardingFixture(storage).use { setup ->
                var initialStorageOpen = true
                var reopenedStorage: SyncRuntimeStorageContract.Storage? = null
                var reopenedRuntime: SyncRuntime? = null
                openMemoryStorage().use { sender ->
                    try {
                        val material = setup.existing("f-g-restart-password")
                        setup.authorize()
                        setup.begin()
                        setup.completeSetup("f-g-restart-password")
                        sender.connect("restart-sender", setup.repository)
                        sender.favorite("/f-g-received-once")
                        assertNotNull(material.secret, "password-protected setup must create a transport key")
                        val transport = spaceTransport(setup, material)
                        val sent = SyncDatabaseExchange(
                            sender.handler,
                            sender.baseline,
                            sender.projector,
                            transport,
                            spaceMaterial = material,
                        ).exchange("space", 1, setup.repository)
                        assertEquals(SyncRunStatus.SUCCESS, sent.status, "sender problem=${sent.problem}")

                        val firstRuntime = setup.runtime
                        val firstReceive = firstRuntime.coordinator.synchronize(SyncTrigger.MANUAL)
                        assertEquals(SyncRunStatus.SUCCESS, firstReceive.status, "problem=${firstReceive.problem}")
                        val receivedRows = receivedBatchCount(storage)
                        assertEquals(1L, receivedRows)
                        assertTrue(storage.manga.getLibraryManga().any { it.manga.url == "/f-g-received-once" })
                        val refsBeforeRestart = setup.git.snapshotReads
                        val commitsBeforeRestart = setup.git.commitReads
                        val treesBeforeRestart = setup.git.treeRequests
                        val blobsBeforeRestart = setup.git.blobReads
                        val uploadsBeforeRestart = setup.git.forceFlags.size

                        firstRuntime.stopPanel()
                        storage.close()
                        initialStorageOpen = false
                        reopenedStorage = openFileStorage(databasePath, create = false)
                        reopenedRuntime = newRuntime(reopenedStorage, setup)
                        val replay = reopenedRuntime.coordinator.synchronize(SyncTrigger.MANUAL)

                        assertEquals(SyncRunStatus.SUCCESS, replay.status)
                        assertEquals(receivedRows, receivedBatchCount(reopenedStorage))
                        assertEquals(
                            1,
                            reopenedStorage.manga.getLibraryManga().count { it.manga.url == "/f-g-received-once" },
                        )
                        assertEquals(
                            uploadsBeforeRestart,
                            setup.git.forceFlags.size,
                            "receipt recovery must not echo the received event",
                        )
                        assertTrue(SyncLocalJournal(reopenedStorage.handler).pendingEvents("space", 1).isEmpty())
                        print(
                            "{\"scenario\":\"F-G\",\"receivedRowsBefore\":$receivedRows," +
                                "\"receivedRowsAfter\":${receivedBatchCount(reopenedStorage)}," +
                                "\"httpDelta\":{\"ref\":${setup.git.snapshotReads - refsBeforeRestart}," +
                                "\"commit\":${setup.git.commitReads - commitsBeforeRestart}," +
                                "\"tree\":${setup.git.treeRequests - treesBeforeRestart}," +
                                "\"blob\":${setup.git.blobReads - blobsBeforeRestart}}}",
                        )
                    } finally {
                        reopenedRuntime?.stopPanel()
                        setup.runtime.stopPanel()
                        reopenedStorage?.close()
                        if (initialStorageOpen) storage.close()
                    }
                }
            }
        }
    }

    private suspend fun withFileDatabase(
        block: suspend (String, SyncRuntimeStorageContract.Storage) -> Unit,
    ) {
        val file = Files.createTempFile("mihon-sync-restart-acceptance-", ".db")
        var primaryFailure: Throwable? = null
        try {
            block(file.toString(), openFileStorage(file.toString(), create = true))
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            try {
                Files.deleteIfExists(file)
            } catch (cleanupFailure: Throwable) {
                if (primaryFailure == null) throw cleanupFailure
                primaryFailure.addSuppressed(cleanupFailure)
            }
        }
    }

    private fun openFileStorage(path: String, create: Boolean): SyncRuntimeStorageContract.Storage {
        val driver = JdbcSqliteDriver("jdbc:sqlite:$path")
        if (create) Database.Schema.create(driver)
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        val database = Database(
            driver,
            History.Adapter(DateColumnAdapter),
            Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
        )
        return SyncRuntimeStorageContract.Storage(driver, JvmDatabaseHandler(database, driver))
    }

    private fun openMemoryStorage(): SyncRuntimeStorageContract.Storage {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        val database = Database(
            driver,
            History.Adapter(DateColumnAdapter),
            Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
        )
        return SyncRuntimeStorageContract.Storage(driver, JvmDatabaseHandler(database, driver))
    }

    private fun newRuntime(
        storage: SyncRuntimeStorageContract.Storage,
        setup: SyncOnboardingFixture,
    ) = SyncRuntime(
        storage.handler,
        storage.bootstrap,
        storage.creators,
        storage.creators,
        { true },
        setup.secure,
        setup.preferences,
        setup.client,
        setup.endpoints,
        clock = { setup.now },
    )

    private fun spaceTransport(setup: SyncOnboardingFixture, material: mihon.domain.sync.crypto.SyncSpaceMaterial) =
        GitHubSyncTransport(
            setup.client,
            { "synthetic-token" },
            setup.git.baseUrl,
            spaceMaterial = material,
        )

    private suspend fun preparedUpload(storage: SyncRuntimeStorageContract.Storage): String? =
        storage.handler.await {
            sync_journalQueries.getNextUploadBatch("space", 1).executeAsOneOrNull()?.prepared_upload
        }

    private suspend fun receivedBatchCount(storage: SyncRuntimeStorageContract.Storage): Long =
        storage.handler.await {
            sync_inboxQueries.countInboxBatches("space", 1).executeAsList()
                .singleOrNull { it.status == "RECEIVED" }?.count ?: 0L
        }

    private fun printRecoveryCounters(scenario: String, setup: SyncOnboardingFixture, readsBefore: Int) {
        val readsNow = setup.git.snapshotReads + setup.git.commitReads + setup.git.treeRequests + setup.git.blobReads
        println(
            "{\"scenario\":\"$scenario\",\"refGets\":${setup.git.snapshotReads}," +
                "\"commitGets\":${setup.git.commitReads},\"treeGets\":${setup.git.treeRequests}," +
                "\"blobGets\":${setup.git.blobReads},\"postRestartReadDelta\":${readsNow - readsBefore}," +
                "\"refPatches\":${setup.git.forceFlags.size}}",
        )
    }

    private suspend fun SyncOnboardingFixture.completeSetup(password: String) {
        if (panel.state.value.setupStep != SyncSetupStep.COMPLETE) {
            panel.act(SyncPanelAction.SubmitPassword(password))
            withTimeout(10_000) {
                panel.state.first {
                    it.setupStep == SyncSetupStep.COMPLETE || it.setupStep == SyncSetupStep.ERROR
                }
            }
        }
        assertEquals(SyncSetupStep.COMPLETE, panel.state.value.setupStep)
    }
}
