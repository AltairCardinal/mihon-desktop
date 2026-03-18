package tachiyomi.core.common.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class DesktopStorageFolderProviderTest {

    @Test
    fun `directory returns Mihon folder under user home`() {
        val provider = DesktopStorageFolderProvider()
        val dir = provider.directory()
        val expected = File(System.getProperty("user.home"), "Mihon")
        assertEquals(expected, dir)
    }

    @Test
    fun `path returns absolute path string`() {
        val provider = DesktopStorageFolderProvider()
        val path = provider.path()
        assertTrue(path.endsWith("Mihon"))
        assertTrue(path.startsWith("/") || path.contains(":")) // Unix or Windows path
    }

    @Test
    fun `custom base directory is used`() {
        val customBase = File(System.getProperty("java.io.tmpdir"), "mihon-test-${System.nanoTime()}")
        val provider = DesktopStorageFolderProvider(customBase)
        assertEquals(customBase, provider.directory())
    }
}
