package mihon.desktop.extension

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class DesktopExtensionLoaderTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `returns empty list when extensions directory does not exist`() {
        val nonExistent = File(tempDir, "nonexistent")
        val loader = DesktopExtensionLoader(nonExistent)
        assertTrue(loader.loadExtensions().isEmpty())
    }

    @Test
    fun `returns empty list when extensions directory is empty`() {
        val loader = DesktopExtensionLoader(tempDir)
        assertTrue(loader.loadExtensions().isEmpty())
    }

    @Test
    fun `ignores non-jar files`() {
        File(tempDir, "readme.txt").writeText("not a jar")
        val loader = DesktopExtensionLoader(tempDir)
        assertTrue(loader.loadExtensions().isEmpty())
    }

    @Test
    fun `getExtensionsDirectory returns configured path`() {
        val loader = DesktopExtensionLoader(tempDir)
        assertEquals(tempDir, loader.extensionsDirectory)
    }
}
