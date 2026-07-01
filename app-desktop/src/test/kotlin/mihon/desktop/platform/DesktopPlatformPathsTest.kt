package mihon.desktop.platform

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class DesktopPlatformPathsTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `windows paths use appdata and localappdata directories`() {
        val paths = DesktopPlatformPaths.resolve(
            osName = "Windows 11",
            userHome = "C:/Users/Alice",
            env = mapOf(
                "APPDATA" to "C:/Users/Alice/AppData/Roaming",
                "LOCALAPPDATA" to "C:/Users/Alice/AppData/Local",
            ),
            createDirectories = false,
        )

        assertEquals("C:/Users/Alice/AppData/Roaming/Mihon", paths.configDir.path)
        assertEquals("C:/Users/Alice/AppData/Roaming/Mihon/mihon.db", paths.databaseFile.path)
        assertEquals("C:/Users/Alice/AppData/Local/Mihon/cache/network", paths.networkCacheDir.path)
        assertEquals("C:/Users/Alice/AppData/Local/Mihon/downloads", paths.downloadsDir.path)
        assertEquals("C:/Users/Alice/AppData/Local/Mihon/extensions", paths.extensionsDir.path)
        assertEquals("C:/Users/Alice/AppData/Local/Mihon/logs", paths.logsDir.path)
        assertEquals("C:/Users/Alice/AppData/Local/Mihon/backups", paths.backupsDir.path)
        assertFalse(paths.configDir.path.contains(".mihon"))
    }

    @Test
    fun `mac paths keep existing dot mihon app data but use library logs`() {
        val paths = DesktopPlatformPaths.resolve(
            osName = "Mac OS X",
            userHome = "/Users/alice",
            env = emptyMap(),
            createDirectories = false,
        )

        assertEquals("/Users/alice/.mihon", paths.configDir.path)
        assertEquals("/Users/alice/.mihon/downloads", paths.downloadsDir.path)
        assertEquals("/Users/alice/Library/Logs/Mihon", paths.logsDir.path)
    }

    @Test
    fun `resolve creates default directories when requested`() {
        val paths = DesktopPlatformPaths.resolve(
            osName = "Linux",
            userHome = tempDir.path,
            env = emptyMap(),
            createDirectories = true,
        )

        assertTrue(paths.configDir.isDirectory)
        assertTrue(paths.networkCacheDir.isDirectory)
        assertTrue(paths.downloadsDir.isDirectory)
        assertTrue(paths.extensionsDir.isDirectory)
        assertTrue(paths.logsDir.isDirectory)
        assertTrue(paths.backupsDir.isDirectory)
    }
}
