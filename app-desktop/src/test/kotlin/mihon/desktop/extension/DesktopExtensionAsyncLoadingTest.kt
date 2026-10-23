package mihon.desktop.extension

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The extension jars are read and their sources are instantiated while the application
 * is already usable, so the manager has to report the initialization state.
 */
class DesktopExtensionAsyncLoadingTest {

    @Test
    fun `the background load publishes the initialization state`(@TempDir tempDir: File) = runBlocking {
        val manager = DesktopExtensionManager(
            loader = DesktopExtensionLoader(tempDir.resolve("extensions").also { it.mkdirs() }),
        )
        try {
            assertFalse(manager.isInitialized.value, "the manager starts uninitialized")

            manager.loadAllAsync()
            manager.awaitInitialized()

            assertTrue(manager.isInitialized.value)
            assertTrue(manager.installedExtensions.value.isEmpty())
        } finally {
            manager.close()
        }
    }

    @Test
    fun `awaiting a manager that never started a load completes`(@TempDir tempDir: File) = runBlocking {
        val manager = DesktopExtensionManager(
            loader = DesktopExtensionLoader(tempDir.resolve("extensions").also { it.mkdirs() }),
        )
        try {
            manager.awaitInitialized()
            assertTrue(manager.isInitialized.value)
        } finally {
            manager.close()
        }
    }

    @Test
    fun `a repeated background load does not start a second load`(@TempDir tempDir: File) = runBlocking {
        val manager = DesktopExtensionManager(
            loader = DesktopExtensionLoader(tempDir.resolve("extensions").also { it.mkdirs() }),
        )
        try {
            manager.loadAllAsync()
            manager.loadAllAsync()
            manager.awaitInitialized()
            assertTrue(manager.isInitialized.value)
        } finally {
            manager.close()
        }
    }
}
