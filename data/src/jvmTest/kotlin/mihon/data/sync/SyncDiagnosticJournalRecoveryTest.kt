package mihon.data.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import mihon.data.sync.auth.SyncSpaceDiscovery
import mihon.data.sync.runtime.SyncDiagnosticJournal
import mihon.data.sync.runtime.SyncJournalEventKind
import mihon.data.sync.runtime.SyncJournalStage
import mihon.data.sync.runtime.SyncRecoveryAction
import mihon.data.sync.runtime.SyncRuntime
import mihon.domain.sync.runtime.SyncTrigger
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import okio.Path.Companion.toOkioPath
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

class SyncDiagnosticJournalRecoveryTest {
    @Test
    fun `actual runtime authorization exchange and recovery transitions share one journal`() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        val database = Database(
            driver,
            History.Adapter(DateColumnAdapter),
            Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
        )
        SyncRuntimeStorageContract.Storage(driver, JvmDatabaseHandler(database, driver)).use { storage ->
            SyncOnboardingFixture(storage).use { fixture ->
                val material = fixture.existing("")
                fixture.authorize()
                val discovered = fixture.runtime.onboarding.discover() as SyncSpaceDiscovery.Found
                val setup = fixture.runtime.onboarding.join(discovered.space, material)
                fixture.runtime.bindSetup(setup)
                val journal = SyncDiagnosticJournal(Files.createTempDirectory("sync-journal-recovery").toOkioPath())
                val runtime = SyncRuntime(
                    storage.handler, storage.bootstrap, storage.creators, storage.creators, { true }, fixture.secure,
                    fixture.preferences, fixture.client, fixture.endpoints, diagnosticJournal = journal,
                )
                runtime.recoveryWorkflow.officialOpened(SyncRecoveryAction.NETWORK)
                assertTrue(journal.snapshot().events.any { it.kind == SyncJournalEventKind.RECOVERY })
                runtime.recoveryWorkflow.officialCompleted(SyncRecoveryAction.NETWORK)
                val original = fixture.git.server.dispatcher
                val injectedFailures = java.util.concurrent.atomic.AtomicInteger()
                fixture.git.server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        if (request.url.encodedPath.endsWith("/git/ref/heads/${fixture.repository.branch}")) {
                            injectedFailures.incrementAndGet()
                            MockResponse(code = 500, body = "private-title github_pat_secret")
                        } else {
                            original.dispatch(request)
                        }
                }
                runtime.coordinator.synchronize(SyncTrigger.MANUAL)
                assertTrue(injectedFailures.get() > 0, "the real production request must hit the injected HTTP failure")
                val events = journal.snapshot().events
                assertTrue(events.any { it.kind == SyncJournalEventKind.AUTHORIZATION })
                assertTrue(
                    events.filter { it.kind == SyncJournalEventKind.AUTHORIZATION }.all {
                        it.attemptId != null && it.trigger == SyncTrigger.MANUAL
                    },
                    "authorization evidence must distinguish retries of the same run",
                )
                assertTrue(
                    events.any {
                        it.kind == SyncJournalEventKind.HTTP_REQUEST &&
                            it.operation == mihon.data.sync.runtime.SyncJournalOperation.ACCOUNT &&
                            it.runAlias != null && it.attemptId != null && it.trigger == SyncTrigger.MANUAL
                    },
                )
                assertTrue(events.any { it.kind == SyncJournalEventKind.SPACE_CHECK })
                assertTrue(events.any { it.kind == SyncJournalEventKind.HTTP_FAILURE && it.runAlias != null })
                assertTrue(
                    events.any {
                        it.kind == SyncJournalEventKind.EXCEPTION &&
                            it.stage == SyncJournalStage.CHECKING
                    },
                )
                runtime.stopPanel()
            }
        }
    }
}
