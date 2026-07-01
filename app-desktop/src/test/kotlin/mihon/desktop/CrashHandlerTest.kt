package mihon.desktop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class CrashHandlerTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `default crash log directory uses user-visible mihon logs directory`() {
        val directory = CrashHandler.defaultCrashLogDir()

        assertFalse(directory.path.startsWith("/tmp"), "Crash logs must not be written to volatile temp storage")
        assertTrue(directory.path.contains("Mihon") || directory.path.contains(".mihon"))
        assertTrue(directory.path.contains("Logs") || directory.path.contains("logs"))
    }

    @Test
    fun `appendCrashReport creates parent directory and writes report`() {
        val logFile = File(tempDir, "nested/mihon_crash.log")

        CrashHandler.appendCrashReport(
            file = logFile,
            report = "first crash\n",
            maxBytes = 1024L,
            maxArchives = 2,
        )

        assertTrue(logFile.exists())
        assertEquals("first crash\n", logFile.readText())
    }

    @Test
    fun `appendCrashReport rotates existing oversized log before writing`() {
        val logFile = File(tempDir, "mihon_crash.log")
        logFile.writeText("old crash report")

        CrashHandler.appendCrashReport(
            file = logFile,
            report = "new crash report",
            maxBytes = 8L,
            maxArchives = 2,
        )

        assertEquals("new crash report", logFile.readText())
        assertEquals("old crash report", File(tempDir, "mihon_crash.log.1").readText())
    }
}
