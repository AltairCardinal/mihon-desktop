package mihon.desktop.extension

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

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

    @Test
    fun `one installed jar exposing duplicate source ids publishes one source`() {
        val jar = File(tempDir, "mihon.desktop.extension.jar")
        JarOutputStream(jar.outputStream()).use { output ->
            listOf(DuplicateFixtureSourceFactory::class.java, DuplicateFixtureSource::class.java).forEach { type ->
                val resource = type.name.replace('.', '/') + ".class"
                output.putNextEntry(JarEntry(resource))
                checkNotNull(type.classLoader.getResourceAsStream(resource)).use { input ->
                    input.copyTo(output)
                }
                output.closeEntry()
            }
        }
        val manager = DesktopExtensionManager(DesktopExtensionLoader(tempDir))

        try {
            manager.loadAll()

            assertEquals(listOf(DuplicateFixtureSource.ID), manager.getInstalledSources().map { it.id })
            assertEquals(
                listOf(DuplicateFixtureSource.ID),
                manager.getInstalledExtensions().single().sources.map { it.id },
            )
        } finally {
            manager.close()
        }
    }
}

private class DuplicateFixtureSource : Source {
    override val id = ID
    override val name = "Duplicate fixture"

    companion object {
        const val ID = 7002L
    }
}

private class DuplicateFixtureSourceFactory : SourceFactory {
    override fun createSources(): List<Source> = listOf(DuplicateFixtureSource())
}
