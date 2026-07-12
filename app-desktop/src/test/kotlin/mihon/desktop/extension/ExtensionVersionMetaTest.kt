package mihon.desktop.extension

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.time.Instant

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
            repoUrl = "https://repo.example",
            repoName = "Example repository",
            repoFingerprint = "ABC123",
            installedAt = Instant.parse("2026-07-11T12:00:00Z").toEpochMilli(),
            artifactSha256 = "deadbeef",
        )
        val json = Json.encodeToString(meta)
        val decoded = Json.decodeFromString<ExtensionMeta>(json)
        assertEquals(meta, decoded)
    }

    @Test
    fun `old metadata remains readable with safe repository defaults`(@TempDir tmpDir: Path) {
        val jar = File(tmpDir.toFile(), "legacy.jar").also { it.createNewFile() }
        File(tmpDir.toFile(), "legacy.meta.json").writeText(
            """{"pkgName":"legacy","versionCode":1,"versionName":"1.0"}""",
        )

        val meta = requireNotNull(readExtensionMeta(jar))

        assertEquals("", meta.repoUrl)
        assertEquals("", meta.repoName)
        assertEquals("", meta.repoFingerprint)
        assertEquals(0L, meta.installedAt)
        assertEquals("", meta.artifactSha256)
    }

    @Test
    fun `installed extension exposes persisted repository identity`(@TempDir tmpDir: Path) {
        val jar = File(tmpDir.toFile(), "example.jar").also { it.createNewFile() }
        writeExtensionMeta(
            jar,
            ExtensionMeta(
                pkgName = "example",
                versionCode = 2,
                versionName = "2.0",
                repoUrl = "https://repo.example",
                repoName = "Example repository",
                repoFingerprint = "ABC123",
                artifactSha256 = "deadbeef",
            ),
        )
        val loader = object : DesktopExtensionLoader(tmpDir.toFile()) {
            override fun loadExtensions() = emptyList<LoadedExtension>()
        }
        val extension = InstalledExtension(
            jarFile = jar,
            sources = emptyList(),
            repoUrl = "https://repo.example",
            repoName = "Example repository",
            repoFingerprint = "ABC123",
            artifactSha256 = "deadbeef",
        )

        assertEquals("https://repo.example", extension.repoUrl)
        assertEquals("ABC123", extension.repoFingerprint)
    }

    @Test
    fun `repository identity change is rejected but legacy metadata can adopt identity`() {
        assertEquals(true, repositoryIdentityConflicts("ABC", "DEF"))
        assertEquals(false, repositoryIdentityConflicts("ABC", "abc"))
        assertEquals(false, repositoryIdentityConflicts("", "DEF"))
        assertEquals(false, repositoryIdentityConflicts("ABC", ""))
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
