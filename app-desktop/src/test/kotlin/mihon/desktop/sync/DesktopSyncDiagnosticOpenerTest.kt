package mihon.desktop.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class DesktopSyncDiagnosticOpenerTest {
    @Test
    fun `only generated JSON directly in private diagnostic cache can be opened`(@TempDir folder: File) {
        val directory = folder.resolve("sync-diagnostics").apply { mkdirs() }
        val valid = directory.resolve("sync-diagnostic-01234567-0123-0123-0123-012345678901.json").apply {
            writeText("{}", Charsets.UTF_8)
        }
        val opened = mutableListOf<File>()
        assertTrue(DesktopSyncDiagnosticOpener.open(valid.path, directory.path, opened::add))
        assertEquals(listOf(valid.canonicalFile), opened)
        for (invalid in listOf(folder.resolve(valid.name), directory.resolve("private/session.json"),
            directory.resolve("arbitrary.json"), directory.resolve("sync-diagnostic-private.txt"))) {
            invalid.parentFile!!.mkdirs()
            invalid.writeText("private", Charsets.UTF_8)
            assertFalse(DesktopSyncDiagnosticOpener.open(invalid.path, directory.path, opened::add))
        }
        assertFalse(DesktopSyncDiagnosticOpener.open(valid.path, null, opened::add))
        assertFalse(DesktopSyncDiagnosticOpener.open(valid.path, directory.path) { error("viewer unavailable") })
        assertEquals(1, opened.size)
    }
}
