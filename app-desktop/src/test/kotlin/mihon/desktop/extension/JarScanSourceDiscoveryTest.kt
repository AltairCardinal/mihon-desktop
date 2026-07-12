package mihon.desktop.extension

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

/**
 * Tests for the JAR-scanning fallback in DesktopExtensionLoader.
 *
 * Android APK extensions converted with dex2jar do NOT contain
 * META-INF/services registrations — they rely on Android's PackageManager
 * for source discovery. The fallback must scan all class entries and
 * instantiate concrete Source implementations directly.
 */
class JarScanSourceDiscoveryTest {

    /**
     * Builds a JAR that contains the compiled bytecode of [JarScanFakeSource]
     * but deliberately omits the META-INF/services file.
     * This mimics what a dex2jar-converted Android APK produces.
     */
    private fun buildJarWithoutServices(dir: File): File {
        val className = JarScanFakeSource::class.java.name.replace('.', '/')
        val classBytes = JarScanFakeSource::class.java.classLoader
            .getResourceAsStream("$className.class")!!
            .readBytes()

        val jar = File(dir, "no-services.jar")
        JarOutputStream(jar.outputStream()).use { jos ->
            jos.putNextEntry(JarEntry("$className.class"))
            jos.write(classBytes)
            jos.closeEntry()
        }
        return jar
    }

    @Test
    fun `loadExtensions returns empty when JAR has no Source class and no services file`(
        @TempDir tmpDir: Path,
    ) {
        // JAR with no relevant classes at all
        val jar = File(tmpDir.toFile(), "empty.jar")
        JarOutputStream(jar.outputStream()).use { jos ->
            jos.putNextEntry(JarEntry("com/example/Helper.class"))
            jos.write(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()))
            jos.closeEntry()
        }
        val loaded = DesktopExtensionLoader(tmpDir.toFile()).loadExtensions()
        assertTrue(loaded.isEmpty())
        loaded.closeClassLoaders()
    }

    @Test
    fun `loadExtensions discovers Source via JAR scan when no services registration present`(
        @TempDir tmpDir: Path,
    ) {
        val jar = buildJarWithoutServices(tmpDir.toFile())
        val loaded = DesktopExtensionLoader(tmpDir.toFile()).loadExtensions()

        assertEquals(1, loaded.size, "Should discover source via JAR scanning fallback")
        assertEquals(jar.canonicalFile, loaded[0].jarFile.canonicalFile)
        assertEquals(12345L, loaded[0].source.id)
        assertEquals("JarScanFake", loaded[0].source.name)
        loaded.closeClassLoaders()
    }

    @Test
    fun `loadExtensions prefers ServiceLoader when registration is present`(
        @TempDir tmpDir: Path,
    ) {
        // Build JAR with BOTH services registration AND class bytecode
        val className = JarScanFakeSource::class.java.name.replace('.', '/')
        val classBytes = JarScanFakeSource::class.java.classLoader
            .getResourceAsStream("$className.class")!!
            .readBytes()
        val serviceLine = JarScanFakeSource::class.java.name + "\n"

        val jar = File(tmpDir.toFile(), "with-services.jar")
        JarOutputStream(jar.outputStream()).use { jos ->
            jos.putNextEntry(JarEntry("$className.class"))
            jos.write(classBytes)
            jos.closeEntry()

            jos.putNextEntry(JarEntry("META-INF/services/eu.kanade.tachiyomi.source.Source"))
            jos.write(serviceLine.toByteArray())
            jos.closeEntry()
        }

        val loaded = DesktopExtensionLoader(tmpDir.toFile()).loadExtensions()
        assertEquals(1, loaded.size, "Should find source exactly once (via ServiceLoader)")
        loaded.closeClassLoaders()
    }

    @Test
    fun `inner classes and anonymous classes are skipped during JAR scan`(
        @TempDir tmpDir: Path,
    ) {
        // Build a JAR with only inner-class entries ($ in name) — no top-level Source class
        val jar = File(tmpDir.toFile(), "inner-only.jar")
        JarOutputStream(jar.outputStream()).use { jos ->
            jos.putNextEntry(JarEntry("com/example/Outer\$Inner.class"))
            jos.write(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()))
            jos.closeEntry()
        }
        val loaded = DesktopExtensionLoader(tmpDir.toFile()).loadExtensions()
        assertTrue(loaded.isEmpty(), "Inner classes should not be instantiated")
        loaded.closeClassLoaders()
    }
}

private fun List<LoadedExtension>.closeClassLoaders() {
    forEach { (it.classLoader as? URLClassLoader)?.close() }
}

/**
 * Top-level Source stub used for JAR scanning tests.
 * Must be top-level (no $) so it's considered a candidate during scanning.
 */
class JarScanFakeSource : Source {
    override val id = 12345L
    override val name = "JarScanFake"
    override val lang = "zh"
    override suspend fun getMangaDetails(manga: SManga) = manga
    override suspend fun getChapterList(manga: SManga) = emptyList<SChapter>()
    override suspend fun getPageList(chapter: SChapter) = emptyList<Page>()
}
