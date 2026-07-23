package mihon.desktop.ui.settings

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.Desktop
import java.io.File

class DesktopDirectoryOpenerTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `default launcher is blocked inside a Gradle test worker`() {
        assertNotNull(System.getProperty("org.gradle.test.worker"))
        val target = File(tempDir, "logs")
        val desktop = mockk<Desktop>(relaxed = true)
        mockkStatic(Desktop::class)
        try {
            every { Desktop.isDesktopSupported() } returns true
            every { Desktop.getDesktop() } returns desktop

            val result = DesktopDirectoryOpener.open(target)

            assertTrue(target.exists())
            assertEquals(false, result)
            verify(exactly = 0) { Desktop.isDesktopSupported() }
            verify(exactly = 0) { Desktop.getDesktop() }
            verify(exactly = 0) { desktop.open(any()) }
        } finally {
            unmockkStatic(Desktop::class)
        }
    }

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
