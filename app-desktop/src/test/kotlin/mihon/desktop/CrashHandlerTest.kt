package mihon.desktop

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

@Isolated
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

    @Test
    fun `install registers the production default handler`() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        try {
            CrashHandler.install()
            assertSame(CrashHandler, Thread.getDefaultUncaughtExceptionHandler())
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        }
    }

    @Test
    fun `uncaught handler reports original exception when crash log write fails`() {
        val blockingParent = File(tempDir, "not-a-directory").apply { writeText("blocking file") }
        val previousHome = System.getProperty("user.home")
        val previousOs = System.getProperty("os.name")
        val previousErr = System.err
        val stderrBytes = ByteArrayOutputStream()
        val capturedErr = PrintStream(stderrBytes, true, Charsets.UTF_8)
        val originalCrash = IllegalStateException("original crash remains visible").apply {
            stackTrace = Array(160) { index -> StackTraceElement("Example$index", "call", "Example.kt", index + 1) }
        }

        try {
            System.setProperty("user.home", blockingParent.path)
            System.setProperty("os.name", "Linux")
            System.setErr(capturedErr)

            assertDoesNotThrow {
                CrashHandler.uncaughtException(Thread("failing-crash-writer"), originalCrash)
            }

            val stderr = stderrBytes.toString(Charsets.UTF_8)
            assertTrue(stderr.contains("original crash remains visible"))
            assertTrue(stderr.contains("Failed to persist crash report"))
            assertTrue(stderr.contains("truncated, full trace in console"))
        } finally {
            System.setErr(previousErr)
            System.setProperty("user.home", previousHome)
            System.setProperty("os.name", previousOs)
            capturedErr.close()
        }
    }
}
