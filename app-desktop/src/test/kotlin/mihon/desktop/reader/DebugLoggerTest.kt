package mihon.desktop.reader

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DebugLoggerTest {

    @Test
    fun `default reader debug log file is not volatile temp storage`() {
        val file = DebugLogger.defaultLogFile()

        assertFalse(file.path.startsWith("/tmp"))
        assertTrue(file.path.contains("Mihon") || file.path.contains(".mihon"))
        assertTrue(file.name.endsWith(".log"))
    }
}
