package mihon.desktop.ui.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class DesktopDirectoryOpenerTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `open creates missing directory before launching file manager`() {
        val target = File(tempDir, "logs")
        val opened = mutableListOf<File>()

        val result = DesktopDirectoryOpener.open(target) { opened += it }

        assertTrue(result)
        assertTrue(target.exists())
        assertEquals(listOf(target), opened)
    }

    @Test
    fun `open returns false when launcher fails`() {
        val target = File(tempDir, "logs")

        val result = DesktopDirectoryOpener.open(target) { error("not supported") }

        assertTrue(target.exists())
        assertEquals(false, result)
    }
}
