package mihon.desktop.extension

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class ExtensionVersionMetaTest {

    @Test
    fun `InstalledExtension has versionCode field defaulting to 0`(@TempDir tmpDir: Path) {
        val jar = File(tmpDir.toFile(), "ext.jar").also { it.createNewFile() }
        val ext = InstalledExtension(jarFile = jar, sources = emptyList())
        assertEquals(0L, ext.versionCode)
    }

    @Test
    fun `InstalledExtension has versionName field defaulting to empty`(@TempDir tmpDir: Path) {
        val jar = File(tmpDir.toFile(), "ext.jar").also { it.createNewFile() }
        val ext = InstalledExtension(jarFile = jar, sources = emptyList())
        assertEquals("", ext.versionName)
    }

    @Test
    fun `DesktopExtensionLoader reads versionCode from meta file`(@TempDir tmpDir: Path) {
        val dir = tmpDir.toFile()
        val jar = File(dir, "eu.kanade.tachiyomi.extension.en.test.jar").also { it.createNewFile() }
        val meta = ExtensionMeta(
            pkgName = "eu.kanade.tachiyomi.extension.en.test",
            versionCode = 42L,
            versionName = "1.2.3",
            iconUrl = "https://example.com/icon.png",
        )
        File(dir, "eu.kanade.tachiyomi.extension.en.test.meta.json")
            .writeText(Json.encodeToString(meta))

        val loader = DesktopExtensionLoader(dir)
        val versionCode = loader.readMetaVersionCode(jar)
        assertEquals(42L, versionCode)
    }

    @Test
    fun `DesktopExtensionLoader returns 0 versionCode when no meta file`(@TempDir tmpDir: Path) {
        val jar = File(tmpDir.toFile(), "someext.jar").also { it.createNewFile() }
        val loader = DesktopExtensionLoader(tmpDir.toFile())
        assertEquals(0L, loader.readMetaVersionCode(jar))
    }

    @Test
    fun `ExtensionMeta serializes and deserializes correctly`() {
        val meta = ExtensionMeta(
            pkgName = "eu.kanade.test",
            versionCode = 100L,
            versionName = "1.0.0",
            iconUrl = "https://host/icon.png",
        )
        val json = Json.encodeToString(meta)
        val decoded = Json.decodeFromString<ExtensionMeta>(json)
        assertEquals(meta, decoded)
    }

    @Test
    fun `removeExtension also removes meta file`(@TempDir tmpDir: Path) {
        val dir = tmpDir.toFile()
        val jar = File(dir, "eu.kanade.tachiyomi.extension.en.test.jar").also { it.createNewFile() }
        val metaFile = File(dir, "eu.kanade.tachiyomi.extension.en.test.meta.json")
            .also { it.writeText("{}") }

        val loader = object : DesktopExtensionLoader(dir) {
            override fun loadExtensions() = emptyList<LoadedExtension>()
        }
        val manager = DesktopExtensionManager(loader)
        val ext = InstalledExtension(jarFile = jar, sources = emptyList())
        manager.removeExtensionWithMeta(ext)

        assertEquals(false, jar.exists())
        assertEquals(false, metaFile.exists())
    }
}
