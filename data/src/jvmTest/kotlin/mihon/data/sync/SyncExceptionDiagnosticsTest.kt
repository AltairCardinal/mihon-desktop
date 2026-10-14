package mihon.data.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import mihon.data.sync.runtime.SyncDatabaseExchange
import mihon.data.sync.runtime.SyncExceptionDiagnostics
import mihon.domain.sync.crypto.SyncEncryptedBatch
import mihon.domain.sync.runtime.SyncRunProblem
import mihon.domain.sync.runtime.SyncRunStatus
import mihon.domain.sync.transport.SyncInitializationResult
import mihon.domain.sync.transport.SyncPreparedUpload
import mihon.domain.sync.transport.SyncPublishResult
import mihon.domain.sync.transport.SyncRepository
import mihon.domain.sync.transport.SyncSnapshot
import mihon.domain.sync.transport.SyncTransportPort
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

class SyncExceptionDiagnosticsTest {
    @Test
    fun `exception observations exclude messages and remain bounded`() {
        val directory = Files.createTempDirectory("sync-exception-test")
        val report = SyncExceptionDiagnostics(directory.toOkioPath())
        val failure = IllegalStateException("github_pat_secret private title private repository")
        failure.stackTrace = Array(1_000) { StackTraceElement("mihon.fixture.Sync", "exchange", "Sync.kt", it) }
        report.report(failure)
        val path = directory.resolve("sync-exception-latest.txt")
        assertTrue(Files.exists(path), "the real diagnostic writer must retain an exception")
        val content = Files.readString(path)
        assertTrue(content.contains("java.lang.IllegalStateException"))
        assertTrue(content.contains("mihon.fixture.Sync.exchange"))
        assertFalse(content.contains("github_pat_secret"))
        assertFalse(content.contains("private title"))
        assertTrue(Files.size(path) <= 32 * 1024)
        report.report(IllegalArgumentException("another secret"))
        assertFalse(Files.readString(path).contains("java.lang.IllegalStateException"))
    }

    @Test
    fun `production exchange retains unknown exception without changing its result`() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        val database = Database(
            driver,
            History.Adapter(DateColumnAdapter),
            Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
        )
        SyncRuntimeStorageContract.Storage(driver, JvmDatabaseHandler(database, driver)).use { storage ->
            val repository = SyncRepository("fixture-owner", "private-sync", "mihon-sync")
            storage.connect("fixture-actor", repository)
            val directory = Files.createTempDirectory("sync-exchange-exception")
            val diagnostics = SyncExceptionDiagnostics(directory.toOkioPath())
            val transport = FailingTransport()
            val exchange = SyncDatabaseExchange(
                storage.handler,
                storage.baseline,
                storage.projector,
                transport,
                exceptionDiagnostics = diagnostics,
            )
            val result = exchange.exchange("space", 1, repository)
            assertEquals(SyncRunStatus.FAILED, result.status)
            assertEquals(SyncRunProblem.UNKNOWN, result.problem)
            val path = directory.resolve("sync-exception-latest.txt")
            assertTrue(Files.exists(path), "the real exchange catch must be wired to the diagnostic writer")
            assertTrue(Files.readString(path).contains("FailingTransport"))
            assertFalse(Files.readString(path).contains("synthetic-secret"))

            val blockedDirectory = Files.createTempFile("sync-unwritable-diagnostic", ".txt")
            val failedWriter = SyncDatabaseExchange(
                storage.handler,
                storage.baseline,
                storage.projector,
                transport,
                exceptionDiagnostics = SyncExceptionDiagnostics(blockedDirectory.toOkioPath()),
            )
            assertEquals(result.problem, failedWriter.exchange("space", 1, repository).problem)
        }
    }

    private class FailingTransport : SyncTransportPort {
        override fun prepare(snapshot: SyncSnapshot, encryptedBatch: SyncEncryptedBatch): SyncPreparedUpload =
            error("unused")

        override suspend fun readSnapshot(
            repository: SyncRepository,
            expectedSpaceId: String,
            expectedGeneration: Long,
        ): Result<SyncSnapshot> = Result.failure(IllegalStateException("synthetic-secret"))

        override suspend fun publish(
            repository: SyncRepository,
            snapshot: SyncSnapshot,
            upload: SyncPreparedUpload,
            observeSnapshot: suspend (SyncSnapshot) -> Unit,
        ): SyncPublishResult = error("unused")

        override suspend fun initialize(
            repository: SyncRepository,
            spaceId: String,
            generation: Long,
        ): SyncInitializationResult = error("unused")
    }
}
