package mihon.desktop.extension

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class ExtensionUpdateDetectionTest {

    @TempDir
    lateinit var tmpDir: Path

    private fun jar(name: String) = File(tmpDir.toFile(), "$name.jar").also { it.createNewFile() }

    private fun installedExt(pkgName: String, versionCode: Long) =
        InstalledExtension(
            jarFile = jar(pkgName),
            sources = emptyList(),
            versionCode = versionCode,
            versionName = "1.0.0",
        )

    private fun availableExt(pkgName: String, versionCode: Long) =
        DesktopAvailableExtension(
            name = pkgName,
            pkgName = pkgName,
            versionCode = versionCode,
            versionName = "1.0.0",
            lang = "en",
            isNsfw = false,
            jarUrl = "https://example.com/$pkgName.jar",
            iconUrl = "",
            repoUrl = "https://example.com",
        )

    @Test
    fun `findUpdatableExtensions returns extension when available version is newer`() {
        val installed = listOf(installedExt("eu.kanade.ext.a", versionCode = 10L))
        val available = listOf(availableExt("eu.kanade.ext.a", versionCode = 11L))
        val updates = findUpdatableExtensions(installed, available)
        assertEquals(1, updates.size)
        assertEquals("eu.kanade.ext.a", updates.first().pkgName)
    }

    @Test
    fun `findUpdatableExtensions excludes extension at same version`() {
        val installed = listOf(installedExt("eu.kanade.ext.b", versionCode = 10L))
        val available = listOf(availableExt("eu.kanade.ext.b", versionCode = 10L))
        val updates = findUpdatableExtensions(installed, available)
        assertTrue(updates.isEmpty())
    }

    @Test
    fun `findUpdatableExtensions excludes extension with older available version`() {
        val installed = listOf(installedExt("eu.kanade.ext.c", versionCode = 15L))
        val available = listOf(availableExt("eu.kanade.ext.c", versionCode = 12L))
        val updates = findUpdatableExtensions(installed, available)
        assertTrue(updates.isEmpty())
    }

    @Test
    fun `findUpdatableExtensions excludes extension not in available list`() {
        val installed = listOf(installedExt("eu.kanade.ext.d", versionCode = 5L))
        val available = listOf(availableExt("eu.kanade.ext.e", versionCode = 10L))
        val updates = findUpdatableExtensions(installed, available)
        assertTrue(updates.isEmpty())
    }

    @Test
    fun `findUpdatableExtensions handles extension with no meta (versionCode 0)`() {
        // Extension installed before meta tracking: versionCode=0, available=5 → should update
        val installed = listOf(installedExt("eu.kanade.ext.f", versionCode = 0L))
        val available = listOf(availableExt("eu.kanade.ext.f", versionCode = 5L))
        val updates = findUpdatableExtensions(installed, available)
        assertEquals(1, updates.size)
    }

    @Test
    fun `isExtensionInstalled returns true when pkgName matches installed jar name`() {
        val installed = listOf(installedExt("eu.kanade.ext.g", versionCode = 1L))
        assertTrue(isExtensionInstalled("eu.kanade.ext.g", installed))
    }

    @Test
    fun `isExtensionInstalled returns false when not installed`() {
        val installed = listOf(installedExt("eu.kanade.ext.g", versionCode = 1L))
        assertFalse(isExtensionInstalled("eu.kanade.ext.z", installed))
    }

    @Test
    fun `isExtensionAvailableOnDesktop returns true for bundled MangaDex extension package`() {
        assertTrue(isExtensionAvailableOnDesktop("eu.kanade.tachiyomi.extension.all.mangadex", emptyList()))
    }

    @Test
    fun `findUpdatableExtensions excludes bundled MangaDex extension package`() {
        val installed = listOf(installedExt("eu.kanade.tachiyomi.extension.all.mangadex", versionCode = 0L))
        val available = listOf(
            availableExt("eu.kanade.tachiyomi.extension.all.mangadex", versionCode = 211L),
        )
        val updates = findUpdatableExtensions(installed, available)
        assertTrue(updates.isEmpty())
    }
}
