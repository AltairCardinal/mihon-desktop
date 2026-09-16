package mihon.desktop.extension

import mihon.domain.extension.suggestion.ExtensionPresence
import mihon.domain.extension.suggestion.ExtensionInventoryLocation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class DesktopExtensionInventoryTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun `failed final artifact is still installed while temporary and rollback files are ignored`() {
        val broken = File(directory, "pkg.failed.jar").apply { writeText("invalid jar") }
        writeExtensionMeta(broken, ExtensionMeta("pkg.failed", 1, "1.6.1", repoUrl = "https://repo.example", repoFingerprint = "key"))
        File(directory, "pkg.pending.jar.part").writeText("partial")
        File(directory, "pkg.old.jar.backup").writeText("rollback")
        File(directory, ".staging").mkdir()
        File(directory, ".staging/pkg.pending.jar").writeText("staged")
        DesktopExtensionManager(DesktopExtensionLoader(directory)).use { manager ->
            assertFalse(manager.inventory.value.initialized)
            manager.loadAll()
            val inventory = manager.inventory.value
            assertTrue(inventory.initialized)
            assertEquals(mapOf("pkg.failed" to ExtensionPresence.LOAD_FAILED), inventory.packages)
            assertFalse(inventory.hasUnknownArtifacts)
            assertEquals(setOf(ExtensionInventoryLocation.DESKTOP), inventory.records.getValue("pkg.failed").locations)
            assertEquals("https://repo.example", inventory.records.getValue("pkg.failed").repository?.baseUrl)
            assertEquals(false, inventory.records.getValue("pkg.failed").runtimeLoaded)
            broken.delete()
            manager.reloadAll()
            assertTrue(manager.inventory.value.packages.isEmpty())
        }
    }

    @Test
    fun `unknown manual jar and unavailable directory do not prove package absence`() {
        File(directory, "old-manual.jar").writeText("unidentified")
        DesktopExtensionManager(DesktopExtensionLoader(directory)).use { manager ->
            manager.loadAll()
            assertTrue(manager.inventory.value.hasUnknownArtifacts)
        }
        val notDirectory = File(directory, "blocked").apply { writeText("file") }
        DesktopExtensionManager(DesktopExtensionLoader(notDirectory)).use { manager ->
            manager.loadAll()
            assertTrue(manager.inventory.value.hasUnknownArtifacts)
        }
    }
}
