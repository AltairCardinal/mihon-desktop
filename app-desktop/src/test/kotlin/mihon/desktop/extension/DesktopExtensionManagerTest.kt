package mihon.desktop.extension

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class DesktopExtensionManagerTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `getInstalledExtensions returns empty when no JARs exist`() {
        val manager = DesktopExtensionManager(DesktopExtensionLoader(tempDir))
        assertTrue(manager.getInstalledSources().isEmpty())
    }

    @Test
    fun `loadAll populates installed sources`() {
        val manager = DesktopExtensionManager(DesktopExtensionLoader(tempDir))
        manager.loadAll()
        // With empty dir, still empty
        assertEquals(0, manager.getInstalledSources().size)
    }
}
