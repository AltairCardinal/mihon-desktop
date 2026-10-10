package mihon.desktop.sync

import java.io.File
import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DesktopSyncFailureLogOpenerTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun `opens existing report file and reports native launch failure`() {
        val report = File(directory, "report.txt").apply { writeText("失败详情", Charsets.UTF_8) }
        var opened: File? = null
        assertTrue(DesktopSyncFailureLogOpener.open(report.path, directory.path) { opened = it })
        assertEquals(report.canonicalFile, opened)
        assertFalse(DesktopSyncFailureLogOpener.open(report.path, directory.path) { error("no viewer") })
        Files.delete(report.toPath())
        assertFalse(DesktopSyncFailureLogOpener.open(report.path, directory.path) { error("must not launch") })
        assertFalse(DesktopSyncFailureLogOpener.open(directory.path, directory.path) { error("must not launch") })
    }
}
