package mihon.data.sync

import mihon.data.sync.runtime.SyncDiagnosticEvent
import mihon.data.sync.runtime.SyncDiagnosticEventKind
import mihon.data.sync.runtime.SyncDiagnosticSnapshot
import mihon.data.sync.runtime.SyncDiagnosticStatus
import mihon.data.sync.runtime.SyncDiagnosticStore
import okio.Path.Companion.toPath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class SyncDiagnosticStoreTest {
    @Test
    fun `actual report explicitly includes default false null zero and schema version`() {
        val json = SyncDiagnosticSnapshot().json()
        for (field in listOf(
            "\"schemaVersion\":1",
            "\"coordinatorRunning\":false",
            "\"activeRun\":null",
            "\"periodMinutes\":0",
            "\"truncated\":false",
        )) {
            assertTrue(json.contains(field), field)
        }
    }

    @Test
    fun `invalid export directory fails without leaving partial report`() {
        val directory = Files.createTempFile("sync-diag-unwritable", ".file")
        val store = SyncDiagnosticStore(directory.toString().toPath())
        assertThrows(Exception::class.java) { store.export(SyncDiagnosticSnapshot()) }
        assertEquals(0, Files.size(directory))
    }

    @Test
    fun `session persists only salt and previous association then expires or ends`() {
        val directory = Files.createTempDirectory("sync-diag-session").toString().toPath()
        var now = 1000L
        val store = SyncDiagnosticStore(directory, clock = { now })
        assertNull(store.loadSession())
        val session = store.beginSession()
        assertEquals(now + 86_400_000, session.expiresAt)
        store.saveSession(session.copy(previousAlias = "snapshot-012345678901234567890123"))
        val reloaded = SyncDiagnosticStore(directory, clock = { now }).loadSession()
        assertEquals(session.salt, reloaded?.salt)
        assertEquals("snapshot-012345678901234567890123", reloaded?.previousAlias)
        assertFalse(Files.exists(java.nio.file.Path.of(directory.toString(), "session.json")))
        now = session.expiresAt
        assertNull(store.loadSession())
        assertFalse(Files.exists(java.nio.file.Path.of(directory.toString(), "private", "session.json")))
        store.beginSession()
        store.endSession()
        assertNull(store.loadSession())
    }

    @Test
    fun `export is atomic bounded valid JSON with explicit truncated metadata`() {
        val directory = Files.createTempDirectory("sync-diag-export").toString().toPath()
        val store = SyncDiagnosticStore(directory)
        val snapshot = SyncDiagnosticSnapshot(
            status = SyncDiagnosticStatus.OK,
            events = List(10000) { SyncDiagnosticEvent(it.toLong(), SyncDiagnosticEventKind.OPEN) },
        )
        val path = java.nio.file.Path.of(store.export(snapshot))
        assertTrue(path.fileName.toString().startsWith("sync-diagnostic-"))
        assertTrue(Files.size(path) <= 256 * 1024)
        val json = kotlinx.serialization.json.Json.parseToJsonElement(Files.readString(path)).toString()
        assertTrue(json.contains("\"truncated\":true"))
        assertTrue(Files.list(path.parent).use { entries -> entries.noneMatch { it.toString().endsWith(".tmp") } })
    }
}
