package mihon.desktop.extension

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class ExtensionManagerTest {

    private class StubSource(override val id: Long = 1L) : Source {
        override val name = "Stub"
        override val lang = "en"
        override suspend fun getMangaDetails(manga: SManga) = manga
        override suspend fun getChapterList(manga: SManga) = emptyList<SChapter>()
        override suspend fun getPageList(chapter: SChapter) = emptyList<Page>()
    }

    /** A loader that returns a fixed list of pre-built extensions. */
    private class FakeLoader(private val fakes: List<LoadedExtension>) : DesktopExtensionLoader() {
        override fun loadExtensions() = fakes
    }

    @Test
    fun `getInstalledExtensions groups by JAR file`(@TempDir tmpDir: Path) {
        val jar1 = File(tmpDir.toFile(), "ext1.jar").also { it.createNewFile() }
        val jar2 = File(tmpDir.toFile(), "ext2.jar").also { it.createNewFile() }
        val fakes = listOf(
            LoadedExtension(source = StubSource(1L), jarFile = jar1, classLoader = javaClass.classLoader),
            LoadedExtension(source = StubSource(2L), jarFile = jar1, classLoader = javaClass.classLoader),
            LoadedExtension(source = StubSource(3L), jarFile = jar2, classLoader = javaClass.classLoader),
        )
        val manager = DesktopExtensionManager(FakeLoader(fakes))
        manager.loadAll()

        val extensions = manager.getInstalledExtensions()
        assertEquals(2, extensions.size)
        val ext1 = extensions.first { it.jarFile == jar1 }
        assertEquals(2, ext1.sources.size)
        val ext2 = extensions.first { it.jarFile == jar2 }
        assertEquals(1, ext2.sources.size)
    }

    @Test
    fun `removeExtension deletes JAR and removes its sources`(@TempDir tmpDir: Path) {
        val jar1 = File(tmpDir.toFile(), "ext1.jar").also { it.createNewFile() }
        val jar2 = File(tmpDir.toFile(), "ext2.jar").also { it.createNewFile() }
        val fakes = listOf(
            LoadedExtension(source = StubSource(1L), jarFile = jar1, classLoader = javaClass.classLoader),
            LoadedExtension(source = StubSource(2L), jarFile = jar2, classLoader = javaClass.classLoader),
        )
        val manager = DesktopExtensionManager(FakeLoader(fakes))
        manager.loadAll()

        val toRemove = manager.getInstalledExtensions().first { it.jarFile == jar1 }
        val result = manager.removeExtension(toRemove)

        assertTrue(result)
        val remaining = manager.getInstalledExtensions()
        assertEquals(1, remaining.size)
        assertEquals(jar2, remaining.first().jarFile)
    }

    @Test
    fun `InstalledExtension name is jar file without extension`(@TempDir tmpDir: Path) {
        val jar = File(tmpDir.toFile(), "my-source-1.0.jar").also { it.createNewFile() }
        val fakes = listOf(
            LoadedExtension(source = StubSource(1L), jarFile = jar, classLoader = javaClass.classLoader),
        )
        val manager = DesktopExtensionManager(FakeLoader(fakes))
        manager.loadAll()
        val ext = manager.getInstalledExtensions().first()
        assertEquals("my-source-1.0", ext.name)
    }

    @Test
    fun `source id resolves to its loaded extension package`() {
        val jar = File("extension.hidden.jar")
        val manager = DesktopExtensionManager(
            FakeLoader(listOf(LoadedExtension(StubSource(42L), jar, javaClass.classLoader))),
        )
        manager.loadAll()

        assertEquals("extension.hidden", manager.getExtensionPackage(42L))
        assertEquals(null, manager.getExtensionPackage(404L))
    }
}
