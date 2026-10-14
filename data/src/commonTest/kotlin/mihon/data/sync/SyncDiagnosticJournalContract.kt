package mihon.data.sync

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mihon.data.sync.runtime.SyncDiagnosticEnvironment
import mihon.data.sync.runtime.SyncDiagnosticJournal
import mihon.data.sync.runtime.SyncJournalCode
import mihon.data.sync.runtime.SyncJournalEventKind
import mihon.data.sync.runtime.SyncJournalIoState
import mihon.data.sync.runtime.SyncJournalStage
import okio.Path.Companion.toOkioPath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

abstract class SyncDiagnosticJournalContract {
    @Test
    fun `exception cycles and hostile frames are bounded and event versions survive upgrade`() {
        val directory = Files.createTempDirectory("sync-journal-graph")
        val first = IllegalStateException(SECRET)
        val second = IllegalArgumentException(SECRET)
        first.initCause(second)
        second.initCause(first)
        first.addSuppressed(second)
        first.stackTrace = arrayOf(StackTraceElement(SECRET, SECRET, "/private/user/Sync.kt", 42))
        val old = SyncDiagnosticEnvironment(appVersion = "1.0", sourceRevision = "abc123")
        val newer = old.copy(appVersion = "2.0")
        SyncDiagnosticJournal(
            directory.toOkioPath(),
            old,
        ).record(SyncJournalEventKind.EXCEPTION, SyncJournalStage.EXCHANGE, failure = first)
        val restored = SyncDiagnosticJournal(directory.toOkioPath(), newer)
        assertEquals(old, restored.snapshot().events.single().environment)
        assertEquals(newer, restored.snapshot().environment)
        assertEquals(2, restored.snapshot().events.single().exceptions.size)
        assertFalse(Json.encodeToString(restored.snapshot()).contains(SECRET))
        assertFalse(Json.encodeToString(restored.snapshot()).contains("/private/user"))
    }

    @Test
    fun `restart retains failures and stable aliases without messages or identifiers`() {
        val directory = Files.createTempDirectory("sync-journal")
        val environment = SyncDiagnosticEnvironment(platform = "JVM", appVersion = "fixture")
        val journal = SyncDiagnosticJournal(directory.toOkioPath(), environment)
        val failure = IllegalStateException(SECRET, IllegalArgumentException(SECRET))
        failure.addSuppressed(UnsupportedOperationException(SECRET))
        journal.record(SyncJournalEventKind.EXCEPTION, SyncJournalStage.EXCHANGE, SECRET, failure = failure)
        journal.record(SyncJournalEventKind.TERMINAL, SyncJournalStage.COMPLETE, SECRET, SyncJournalCode.SUCCESS)
        val restored = SyncDiagnosticJournal(directory.toOkioPath(), environment)
        restored.record(SyncJournalEventKind.RUN_START, SyncJournalStage.RUNTIME, SECRET)
        val snapshot = restored.snapshot()
        assertEquals(3, snapshot.events.size)
        assertEquals(1, snapshot.events.map { it.runAlias }.distinct().size)
        assertEquals(3, snapshot.events.first().exceptions.size)
        assertTrue(snapshot.events.first().exceptions.first().frames.any { it.contains("SyncDiagnosticJournal") })
        assertEquals(environment, snapshot.environment)
        assertFalse(Json.encodeToString(snapshot).contains(SECRET))
        Files.list(directory).use { paths ->
            paths.forEach { assertFalse(String(Files.readAllBytes(it), Charsets.UTF_8).contains(SECRET)) }
        }
    }

    @Test
    fun `rotation is bounded and preserves recent failure through successful noise`() {
        val directory = Files.createTempDirectory("sync-journal-limit")
        val journal = SyncDiagnosticJournal(directory.toOkioPath())
        val error = IllegalStateException(SECRET)
        error.stackTrace = Array(1000) { StackTraceElement("mihon.fixture.Sync", "exchange", "Sync.kt", it) }
        journal.record(SyncJournalEventKind.EXCEPTION, SyncJournalStage.EXCHANGE, failure = error)
        repeat(400) { journal.record(SyncJournalEventKind.PHASE, SyncJournalStage.CHECKING) }
        val snapshot = journal.snapshot()
        assertTrue(snapshot.truncated)
        assertTrue(snapshot.events.size <= 256)
        assertTrue(snapshot.events.any { it.kind == SyncJournalEventKind.EXCEPTION })
        assertTrue(Json.encodeToString(snapshot).encodeToByteArray().size <= 128 * 1024)
        snapshot.events.forEach { assertTrue(Json.encodeToString(it).encodeToByteArray().size <= 16 * 1024) }
        assertTrue(SyncDiagnosticJournal(directory.toOkioPath()).snapshot().truncated)
    }

    @Test
    fun `corrupt and unwritable journals are observable and do not throw`() {
        val directory = Files.createTempDirectory("sync-journal-corrupt")
        Files.write(directory.resolve("journal.json"), "invalid".toByteArray(Charsets.UTF_8))
        val corrupt = SyncDiagnosticJournal(directory.toOkioPath())
        assertEquals(SyncJournalIoState.CORRUPT, corrupt.snapshot().readState)
        corrupt.record(
            SyncJournalEventKind.EXCEPTION,
            SyncJournalStage.RUNTIME,
            failure = IllegalStateException(SECRET),
        )
        assertTrue(corrupt.snapshot().truncated)
        val blocked = SyncDiagnosticJournal(Files.createTempFile("sync-journal-blocked", ".txt").toOkioPath())
        blocked.record(SyncJournalEventKind.RUN_START, SyncJournalStage.RUNTIME)
        assertEquals(SyncJournalIoState.FAILED, blocked.snapshot().writeState)
    }

    @Test
    fun `profiles have isolated events and aliases`() {
        val first = SyncDiagnosticJournal(Files.createTempDirectory("sync-journal-a").toOkioPath())
        val second = SyncDiagnosticJournal(Files.createTempDirectory("sync-journal-b").toOkioPath())
        first.record(SyncJournalEventKind.RUN_START, SyncJournalStage.RUNTIME, SECRET)
        assertTrue(second.snapshot().events.isEmpty())
        second.record(SyncJournalEventKind.RUN_START, SyncJournalStage.RUNTIME, SECRET)
        assertFalse(first.snapshot().events.single().runAlias == second.snapshot().events.single().runAlias)
    }

    companion object {
        private const val SECRET = "github_pat_secret account/repository private-title https://private.example/path"
    }
}
