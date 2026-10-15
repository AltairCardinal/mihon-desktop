package mihon.data.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import mihon.data.sync.http.SyncHttpClient
import mihon.data.sync.runtime.SyncDatabaseExchange
import mihon.data.sync.runtime.SyncDiagnosticJournal
import mihon.data.sync.runtime.SyncJournalContext
import mihon.data.sync.runtime.SyncJournalEventKind
import mihon.data.sync.runtime.SyncJournalStage
import mihon.data.sync.runtime.SyncRunPhase
import mihon.data.sync.runtime.SyncRunState
import mihon.data.sync.runtime.SyncRunStore
import mihon.domain.sync.runtime.SyncNetworkFailurePhase
import mihon.domain.sync.runtime.SyncTrigger
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
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
import java.nio.file.Files

class SyncDiagnosticJournalIntegrationTest {
    @Test
    fun `manual pause resume and cancel retain durable observations before exchange starts`() = runBlocking {
        storage().use { storage ->
            val journal = SyncDiagnosticJournal(Files.createTempDirectory("sync-journal-controls").toOkioPath())
            val store = SyncRunStore(storage.handler, diagnosticJournal = journal)
            val run = store.start("space", 1, SyncTrigger.MANUAL)
            store.pause(run.runId)
            assertTrue(store.resumeIfAllowed(run.runId))
            store.cancel(run.runId)
            val events = journal.snapshot().events
            assertTrue(events.any { it.code == mihon.data.sync.runtime.SyncJournalCode.PAUSED_USER })
            assertTrue(events.any { it.code == mihon.data.sync.runtime.SyncJournalCode.RESUMED })
            assertTrue(
                events.any {
                    it.kind == SyncJournalEventKind.TERMINAL &&
                        it.code == mihon.data.sync.runtime.SyncJournalCode.CANCELLED
                },
            )
            assertEquals(SyncRunState.CANCELLED, store.get(run.runId)?.state)
        }
    }

    @Test
    fun `production HTTP retains underlying network cause and safe failure phase`() = runBlocking {
        val journal = SyncDiagnosticJournal(Files.createTempDirectory("sync-journal-network").toOkioPath())
        val client = OkHttpClient.Builder().dns {
            throw java.net.UnknownHostException("private-host github_pat_secret")
        }.build()
        val http = SyncHttpClient(
            client,
            setOf("fixture.invalid"),
            diagnosticContext = SyncJournalContext(
                journal,
                SyncJournalStage.EXCHANGE,
                "private-run",
                2,
                SyncTrigger.RECOVERY,
            ),
        )
        val error = runCatching {
            http.execute(http.request("https://fixture.invalid/private-path", "GET"))
        }.exceptionOrNull()
        assertTrue(error?.cause is java.net.UnknownHostException)
        val event = journal.snapshot().events.single { it.kind == SyncJournalEventKind.HTTP_FAILURE }
        assertEquals(SyncNetworkFailurePhase.DNS, event.networkPhase)
        assertEquals(2L, event.attemptId)
        assertEquals(SyncTrigger.RECOVERY, event.trigger)
        assertTrue(event.exceptions.any { it.type == "java.net.UnknownHostException" })
        assertFalse(event.toString().contains("github_pat_secret"))
    }

    @Test
    fun `real HTTP captures request response status and isolation without URLs or bodies`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse(code = 500, body = "private body"))
            server.enqueue(MockResponse(code = 200, body = "private body"))
            val journal = SyncDiagnosticJournal(Files.createTempDirectory("sync-journal-http").toOkioPath())
            val http = SyncHttpClient(
                OkHttpClient(),
                setOf("localhost"),
                diagnosticContext = SyncJournalContext(journal, SyncJournalStage.SPACE_CHECK, "private-run"),
            )
            assertEquals(500, http.execute(http.request(server.url("/private/repository").toString(), "GET")).code)
            val events = journal.snapshot().events
            assertTrue(events.any { it.kind == SyncJournalEventKind.HTTP_FAILURE && it.httpStatus == 500 })
            assertTrue(events.any { it.kind == SyncJournalEventKind.HTTP_REQUEST })
            assertTrue(events.any { it.networkPhase == SyncNetworkFailurePhase.HTTP_RESPONSE })
            assertEquals(1, events.map { it.requestAlias }.distinct().size)
            assertTrue(events.all { it.runAlias != null })
            val isolated = SyncHttpClient(OkHttpClient(), setOf("localhost"))
            isolated.execute(isolated.request(server.url("/another").toString(), "GET"))
            assertEquals(events, journal.snapshot().events)
            assertFalse(journal.snapshot().toString().contains("private"))
        }
    }

    @Test
    fun `durable run production store observes phases retry and terminal state`() = runBlocking {
        storage().use { storage ->
            val repository = mihon.domain.sync.transport.SyncRepository("fixture-owner", "mihon-sync", "mihon-sync")
            storage.connect("fixture-actor", repository)
            val journal = SyncDiagnosticJournal(Files.createTempDirectory("sync-journal-run").toOkioPath())
            val store = SyncRunStore(storage.handler, diagnosticJournal = journal)
            val run = store.start("space", 1, SyncTrigger.MANUAL)
            store.progress(run.runId, SyncRunPhase.DOWNLOADING, 0, 1)
            store.progress(
                run.runId,
                SyncRunPhase.DOWNLOADING,
                0,
                1,
                nextRetryAt = 9000,
                state = SyncRunState.WAITING_RETRY,
            )
            store.finish(run.runId, SyncRunState.FAILED, "private reason")
            val events = journal.snapshot().events
            assertTrue(events.any { it.kind == SyncJournalEventKind.RUN_START })
            assertTrue(events.any { it.kind == SyncJournalEventKind.PHASE && it.stage == SyncJournalStage.DOWNLOADING })
            assertTrue(events.any { it.kind == SyncJournalEventKind.RETRY_SCHEDULED && it.nextRetryAt == 9000L })
            assertTrue(events.any { it.kind == SyncJournalEventKind.TERMINAL })
            assertFalse(events.toString().contains("private reason"))
        }
    }

    @Test
    fun `actual exchange failure keeps stack and result when diagnostic disk fails`() = runBlocking {
        storage().use { storage ->
            SyncOnboardingFixture(storage).use { fixture ->
                storage.connect("fixture-actor", fixture.repository)
                val journal = SyncDiagnosticJournal(Files.createTempDirectory("sync-journal-exchange").toOkioPath())
                val transport = mihon.data.sync.transport.GitHubSyncTransport(
                    fixture.client,
                    { "fixture-token" },
                    fixture.git.baseUrl,
                )
                val exchange = SyncDatabaseExchange(
                    storage.handler,
                    storage.baseline,
                    storage.projector,
                    transport,
                    diagnosticContext = SyncJournalContext(journal, SyncJournalStage.EXCHANGE, "fixture-run"),
                )
                val result = exchange.exchange("space", 1, fixture.repository)
                assertTrue(journal.snapshot().events.any { it.kind == SyncJournalEventKind.EXCEPTION })
                val broken = SyncDiagnosticJournal(Files.createTempFile("sync-journal", ".txt").toOkioPath())
                val repeated = SyncDatabaseExchange(
                    storage.handler,
                    storage.baseline,
                    storage.projector,
                    transport,
                    diagnosticContext = SyncJournalContext(broken, SyncJournalStage.EXCHANGE),
                ).exchange("space", 1, fixture.repository)
                assertEquals(result.problem, repeated.problem)
                assertEquals(result.status, repeated.status)
            }
        }
    }

    private fun storage(): SyncRuntimeStorageContract.Storage {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        val database = Database(
            driver,
            History.Adapter(DateColumnAdapter),
            Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
        )
        return SyncRuntimeStorageContract.Storage(driver, JvmDatabaseHandler(database, driver))
    }
}
